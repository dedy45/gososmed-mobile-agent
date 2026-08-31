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
 * Keeps the agent process in the FOREGROUND and acts as the command entry
 * point for P0 device-side validation.
 *
 * Why this matters:
 *  - Android 12+ blocks background BroadcastReceiver execution, so
 *    `am broadcast ... cmd=dump` is rejected with "Background execution not
 *    allowed" unless the app is foreground. A foreground service (which is
 *    NOT blocked) fixes that and matches the production shape: the agent is a
 *    persistent service driven by the GoSosmed agenthub over WebSocket.
 *  - In P0 (no server yet), `adb shell am start-foreground-service ... --es
 *    cmd dump` reaches onStartCommand (not subject to the background
 *    restriction) and runs the same command the server would send over WS.
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

        fun commandIntent(context: Context, cmd: String, text: String? = null): Intent {
            val i = Intent(context, AgentForegroundService::class.java)
            i.putExtra("cmd", cmd)
            text?.let { i.putExtra("text", it) }
            return i
        }
    }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra("cmd") == true) {
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
        return START_STICKY
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
        super.onDestroy()
    }
}
