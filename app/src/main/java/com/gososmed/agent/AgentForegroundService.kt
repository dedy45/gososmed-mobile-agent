package com.gososmed.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject

/**
 * Keeps the agent process in the FOREGROUND and OWNS the outbound WebSocket
 * connection to the GoSosmed agenthub (M3 / gap S1).
 *
 * Why the service owns the WS (and not MainActivity):
 *  - Android destroys activities freely (rotation, swipe-away, memory
 *    pressure) — a WS owned by the activity dies with the UI and leaves the
 *    device "offline" while the app still looks alive. A START_STICKY
 *    foreground service survives and restarts.
 *  - On restart (START_STICKY with a null intent) the service re-reads its
 *    persisted config (ws_url + pairing code) and reconnects by itself, so the
 *    device comes back online after a process kill or reboot without any UI.
 *
 * M2: pairing code is SERVER-ISSUED (from the GoSosmed dashboard, single-use
 * 8-char). MainActivity delivers it here via ACTION_SET_PAIRING; the service
 * persists it and (re)starts AgentWsClient. Rejection (`register_ack ok:false`)
 * stops the reconnect loop and surfaces via the status broadcast so the UI can
 * ask for a fresh code.
 *
 * Usage (validation, P0):
 *   adb shell am start-foreground-service \
 *     -n com.gososmed.agent/.AgentForegroundService --es cmd dump
 * Result is written to the internal files dir (run-as readable) + logcat.
 */
class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "GoAgent"
        private const val CHANNEL_ID = "agent"
        private const val NOTIF_ID = 1

        const val ACTION_SET_PAIRING = "com.gososmed.agent.SET_PAIRING"
        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val ACTION_STOP_WS = "com.gososmed.agent.STOP_WS"

        // Broadcast status agar MainActivity (yang mungkin sudah mati) bisa
        // menampilkan status koneksi terbaru tanpa memegang referensi service.
        const val ACTION_STATUS = "com.gososmed.agent.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_REJECTED = "rejected"

        fun commandIntent(context: Context, cmd: String, text: String? = null): Intent {
            val i = Intent(context, AgentForegroundService::class.java)
            i.putExtra("cmd", cmd)
            text?.let { i.putExtra("text", it) }
            return i
        }
    }

    private var ws: AgentWsClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ForegroundService created")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GoSosmed Agent", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GoSosmed Agent")
            .setContentText("Perangkat siap di-drive dari server")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
        // M3/S1: pulihkan koneksi WS dari config tersimpan (proses dibunuh OS →
        // START_STICKY restart service dengan intent null → auto-reconnect).
        startWsFromPrefs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_SET_PAIRING -> {
                val url = intent.getStringExtra(EXTRA_WS_URL)?.trim().orEmpty()
                val code = intent.getStringExtra(EXTRA_PAIRING_CODE)?.trim().orEmpty()
                if (url.isNotEmpty() && code.isNotEmpty()) {
                    prefs().edit()
                        .putString("ws_url", url)
                        .putString("pairing_code", code)
                        .apply()
                    startWs(url, code)
                }
            }
            intent?.action == ACTION_STOP_WS -> {
                stopWs()
            }
            intent?.hasExtra("cmd") == true -> {
                // Command request: run it (possibly waiting for the accessibility
                // service to bind) then write the result. Runs off the main thread.
                val cmd = intent.getStringExtra("cmd")
                val text = intent.getStringExtra("text")
                val x = readIntExtra(intent, "x")
                val y = readIntExtra(intent, "y")
                Thread {
                    try {
                        var ready = AgentAccessibilityService.instance?.isServiceReady() == true
                        var attempts = 0
                        while (!ready && attempts < 6) {
                            Thread.sleep(500)
                            attempts++
                            ready = AgentAccessibilityService.instance?.isServiceReady() == true
                        }
                        val req = JSONObject().put("cmd", cmd)
                        text?.let { req.put("text", it) }
                        x?.let { req.put("x", it) }
                        y?.let { req.put("y", it) }
                        val resp = AgentCommand.execute(req)
                        AgentReceiver.ResultStore.write(this, cmd ?: "?", resp.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "command failed: ${e.message}")
                    }
                }.start()
            }
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)

    private fun startWsFromPrefs() {
        val url = prefs().getString("ws_url", "") ?: ""
        val code = prefs().getString("pairing_code", "") ?: ""
        if (url.isNotEmpty() && code.isNotEmpty()) {
            startWs(url, code)
        }
    }

    private fun startWs(url: String, code: String) {
        ws?.destroy()
        ws = AgentWsClient(
            context = this,
            url = url,
            deviceId = prefs().getString("device_id", "") ?: "",
            pairingCode = code,
            onStatus = { status ->
                Log.i(TAG, "ws status: $status")
                broadcastStatus(status, rejected = false)
            },
            onPairingRejected = { reason ->
                // M2: pairing ditolak (kode salah/kedaluwarsa/terpakai) → hapus
                // kode tersimpan agar reconnect berikutnya tidak memakai kode
                // busuk, dan tandai status untuk UI.
                prefs().edit().remove("pairing_code").apply()
                broadcastStatus("pairing ditolak: $reason", rejected = true)
            }
        ).also { it.start() }
    }

    private fun stopWs() {
        ws?.destroy()
        ws = null
        broadcastStatus("stopped", rejected = false)
    }

    private fun broadcastStatus(status: String, rejected: Boolean) {
        val i = Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_REJECTED, rejected)
        try {
            sendBroadcast(i)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast status gagal: ${e.message}")
        }
    }

    /** Reads an integer extra tolerating both String (`am --es`) and Int
     *  (`am --ei`, or JSON from the server) representations. */
    private fun readIntExtra(intent: Intent, name: String): Int? {
        if (!intent.hasExtra(name)) return null
        intent.getStringExtra(name)?.let { s -> s.toIntOrNull()?.let { return it } }
        return intent.getIntExtra(name, -1).takeIf { it != -1 }
    }

    override fun onDestroy() {
        Log.i(TAG, "ForegroundService destroyed")
        ws?.destroy()
        ws = null
        super.onDestroy()
    }
}
