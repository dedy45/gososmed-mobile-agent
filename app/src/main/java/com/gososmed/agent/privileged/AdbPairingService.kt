package com.gososmed.agent.privileged

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.gososmed.agent.AgentLog
import com.gososmed.agent.R
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.net.InetAddress

/**
 * v0.9.5 — DUAL INPUT PAIRING ENGINE (Floating Window Service + Notification RemoteInput).
 *
 * MENGAPA VERSI SEBELUMNYA GAGAL DI XIAOMI / HYPEROS / ANDROID 11+:
 * 1. Di Xiaomi/HyperOS, `wm.addView()` memakai `appContext` dari Activity yang kehilangan fokus
 *    sering ditahan oleh sistem keamanan MIUI ("Display pop-up windows in background").
 * 2. `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` langsung meluncur seketika mematikan/menutup
 *    proses rendering view overlay jika context-nya tidak diikat ke Foreground Service!
 * 3. Jika pengguna tidak menyadari floating window atau OEM memblokirnya, pengguna HARUS punya
 *    jalur alternatif resmi (Notification RemoteInput seperti Shizuku).
 *
 * SOLUSI TUNTAS DI v0.9.5:
 * 1. AdbPairingService berjalan sebagai Foreground Service dengan Notification interaktif.
 * 2. Notifikasi memiliki baris "Ketik Kode Pairing" (RemoteInput) langsung di panel notifikasi!
 * 3. Floating Overlay dipasang dari Service Context (bukan Activity), sehingga tetap hidup dan
 *    bebas di layar Setelan Wireless Debugging.
 * 4. Floating Overlay bisa digeser (draggable) jika menutupi angka di layar Android.
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "GoAgentPairSvc"
        private const val CHANNEL_ID = "adb_pairing_channel"
        private const val NOTIF_ID = 4919

        const val ACTION_START = "com.gososmed.agent.START_PAIRING"
        const val ACTION_STOP = "com.gososmed.agent.STOP_PAIRING"
        const val ACTION_INPUT_CODE = "com.gososmed.agent.INPUT_PAIR_CODE"
        const val EXTRA_CODE = "key_pairing_code"

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var floatView: View? = null
    private var adbMdns: AdbMdns? = null

    @Volatile private var currentHost: String = "127.0.0.1"
    @Volatile private var discoveredPort: Int = -1

    private val codeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_INPUT_CODE) {
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(EXTRA_CODE)?.toString()?.trim()
                if (!code.isNullOrEmpty()) {
                    handlePairSubmission(currentHost, discoveredPort, code)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(codeReceiver, IntentFilter(ACTION_INPUT_CODE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(codeReceiver, IntentFilter(ACTION_INPUT_CODE))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanupAndStop()
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("Mencari port pairing otomatis…"))
                currentHost = AndroidUtils.getHostIpAddress(this)
                startMdns()
                showFloatingOverlay()
            }
        }
        return START_NOT_STICKY
    }

    private fun showFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Izin overlay belum aktif, mengandalkan Notifikasi")
            return
        }

        main.post {
            removeFloatingView()

            wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val dp = resources.displayMetrics.density
            val pad = (12 * dp).toInt()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F172A")) // Slate 900
                    cornerRadius = 14 * dp
                    setStroke((1.5 * dp).toInt(), Color.parseColor("#0EA5E9")) // Sky 500
                }
                elevation = 25 * dp
            }

            // Header draggable
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTv = TextView(this).apply {
                text = "⚡ Pairing ADB GoSosmed"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val closeBtn = TextView(this).apply {
                text = " ✕ "
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                setOnClickListener { cleanupAndStop() }
            }
            header.addView(titleTv)
            header.addView(closeBtn)

            val portEt = EditText(this).apply {
                hint = "Port (cth: 38475)"
                if (discoveredPort > 0) setText(discoveredPort.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = createInputBg(dp)
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                tag = "portEt"
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (6 * dp).toInt()
                layoutParams = lp
            }

            val codeEt = EditText(this).apply {
                hint = "Kode 6 Angka"
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = createInputBg(dp)
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                tag = "codeEt"
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (6 * dp).toInt()
                layoutParams = lp
            }

            val statusTv = TextView(this).apply {
                tag = "statusTv"
                text = if (discoveredPort > 0) "✓ Port ditemukan: $discoveredPort" else "Mencari port pairing otomatis…"
                setTextColor(if (discoveredPort > 0) Color.GREEN else Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
            }

            val pairBtn = Button(this).apply {
                text = "Hubungkan Sekarang"
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0EA5E9"))
                    cornerRadius = 8 * dp
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    val p = portEt.text.toString().trim().toIntOrNull() ?: discoveredPort
                    val c = codeEt.text.toString().trim()
                    if (p <= 0) {
                        portEt.error = "Isi port!"
                        return@setOnClickListener
                    }
                    if (c.length < 6) {
                        codeEt.error = "6 digit!"
                        return@setOnClickListener
                    }
                    isEnabled = false
                    statusTv.text = "Menghubungkan ke $currentHost:$p..."
                    statusTv.setTextColor(Color.YELLOW)
                    handlePairSubmission(currentHost, p, c)
                }
            }

            card.addView(header)
            card.addView(portEt)
            card.addView(codeEt)
            card.addView(statusTv)
            card.addView(pairBtn)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                (300 * dp).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (40 * dp).toInt()
            }

            // Fitur Drag Touch agar bisa digeser jika menutupi pop-up
            card.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            wm?.updateViewLayout(card, params)
                            return true
                        }
                    }
                    return false
                }
            })

            try {
                wm?.addView(card, params)
                floatView = card
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memasang floating view dari service", e)
            }
        }
    }

    private fun handlePairSubmission(host: String, port: Int, code: String) {
        Thread {
            val (ok, reason) = AdbPairingController.pair(host, port, code)
            main.post {
                val notifManager = getSystemService(NotificationManager::class.java)
                if (ok) {
                    Toast.makeText(this, "✓ ADB Berhasil Dipasangkan!", Toast.LENGTH_LONG).show()
                    AgentLog.event("ADB Pairing Berhasil ($host:$port)")
                    notifManager.notify(NOTIF_ID, buildNotification("✓ Selesai: Otomasi Lanjutan Terhubung!"))
                    main.postDelayed({ cleanupAndStop() }, 2000)
                } else {
                    Toast.makeText(this, "✗ Pairing Gagal: $reason", Toast.LENGTH_LONG).show()
                    AgentLog.event("ADB Pairing Gagal: $reason")
                    updateOverlayStatus("✗ Gagal: ${reason.take(50)}", Color.parseColor("#F87171"))
                    notifManager.notify(NOTIF_ID, buildNotification("✗ Gagal: $reason (Buka untuk coba lagi)"))
                }
            }
        }.start()
    }

    private fun updateOverlayStatus(msg: String, color: Int) {
        floatView?.let { v ->
            val statusTv = v.findViewWithTag<TextView>("statusTv")
            statusTv?.text = msg
            statusTv?.setTextColor(color)
            val btn = v.findViewById<Button>(View.NO_ID)
            btn?.isEnabled = true
        }
    }

    private fun startMdns() {
        try {
            adbMdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
                if (host != null && port > 0) {
                    currentHost = host.hostAddress ?: currentHost
                    discoveredPort = port
                    main.post {
                        floatView?.let { v ->
                            val portEt = v.findViewWithTag<EditText>("portEt")
                            if (portEt != null && portEt.text.isEmpty()) {
                                portEt.setText(port.toString())
                            }
                            updateOverlayStatus("✓ Port terdeteksi: $port", Color.GREEN)
                        }
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIF_ID, buildNotification("✓ Port pairing terdeteksi ($port). Masukkan kode:"))
                    }
                }
            }
            adbMdns?.start()
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery error", e)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, AdbPairingService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val remoteInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel("Ketik 6 angka kode pairing")
            .build()

        val inputIntent = Intent(ACTION_INPUT_CODE).setPackage(packageName)
        val pInput = PendingIntent.getBroadcast(this, 2, inputIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val replyAction = Notification.Action.Builder(
            null, "Ketik Kode Pairing", pInput
        ).addRemoteInput(remoteInput).build()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("⚡ Pairing ADB GoSosmed")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(replyAction)
            .addAction(Notification.Action.Builder(null, "Batal", pStop).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADB Wireless Pairing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi interaktif untuk memasukkan kode pairing ADB"
                enableLights(true)
                lightColor = Color.CYAN
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun removeFloatingView() {
        floatView?.let { v ->
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
            floatView = null
        }
    }

    private fun cleanupAndStop() {
        try {
            adbMdns?.stop()
            adbMdns = null
        } catch (_: Exception) {}
        removeFloatingView()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(codeReceiver)
        } catch (_: Exception) {}
        cleanupAndStop()
        super.onDestroy()
    }

    private fun createInputBg(dp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#020617")) // Slate 950
        cornerRadius = 6 * dp
        setStroke((1 * dp).toInt(), Color.parseColor("#334155")) // Slate 700
    }
}
