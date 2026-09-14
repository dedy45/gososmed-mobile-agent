package com.gososmed.agent.privileged

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import com.gososmed.agent.AgentAccessibilityService
import com.gososmed.agent.AgentLog
import com.gososmed.agent.PairingOverlay
import com.gososmed.agent.R

/**
 * Pairing engine ADB lokal.
 *
 * Host pairing SELALU `127.0.0.1`: ini self-pairing — HP memasangkan dirinya
 * dengan `adbd` di HP yang sama. Nilai dinamis yang dibutuhkan hanyalah PORT
 * pairing dan kode 6 angka dari dialog Debug nirkabel.
 *
 * Tiga permukaan UI dikelola sebagai SATU state:
 *
 *  1. **Kartu overlay** — nyaman, tetapi bisa ditutup kapan saja. Menutup kartu
 *     HANYA menyembunyikan kartu; sesi dan notifikasi tetap hidup.
 *  2. **Notifikasi RemoteInput** — fallback yang selalu tersedia. Aksi input
 *     hanya dipasang setelah port diketahui, mengikuti pola produksi AppManager;
 *     notifikasi tidak dibangun ulang selama pengguna mungkin sedang mengetik.
 *  3. **Pembaca dialog Setelan via AccessibilityService** — best-effort dan
 *     opt-in oleh sesi ini. Bila kode+port terbaca, kartu diisi otomatis dan
 *     pengguna cukup menekan satu tombol. Bila kartu tidak tersedia, pasangan
 *     itu boleh langsung dipakai karena pengguna sudah memulai sesi pairing.
 *
 * Semua entry point dibungkus try/catch: service ini se-proses dengan
 * AccessibilityService, sehingga exception yang lolos akan mematikan keduanya.
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "GoAgentPairSvc"
        private const val CHANNEL_ID = "adb_pairing_channel"
        private const val NOTIF_ID = 4919
        private const val PAIRING_TIMEOUT_MS = 10 * 60 * 1000L
        private const val PORT_WAIT_MS = 8_000L

        const val LOOPBACK_HOST = "127.0.0.1"

        const val ACTION_START = "com.gososmed.agent.START_PAIRING"
        const val ACTION_STOP = "com.gososmed.agent.STOP_PAIRING"
        const val ACTION_INPUT_CODE = "com.gososmed.agent.INPUT_PAIR_CODE"
        const val ACTION_OPEN_ADB_SETTINGS = "com.gososmed.agent.OPEN_ADB_SETTINGS"
        const val ACTION_SHOW_OVERLAY = "com.gososmed.agent.SHOW_PAIRING_OVERLAY"
        const val ACTION_RETRY = "com.gososmed.agent.RETRY_PAIRING"
        const val EXTRA_CODE = "key_pairing_code"
        const val EXTRA_PORT = "key_pairing_port"

        private const val ACTION_OPEN_WIRELESS = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        private const val ACTION_DEV_SETTINGS = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

        fun start(context: Context) {
            try {
                val intent = Intent(context, AdbPairingService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "tidak bisa memulai AdbPairingService", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, AdbPairingService::class.java).apply { action = ACTION_STOP }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "tidak bisa menghentikan AdbPairingService", t)
            }
        }

        fun isRunning(): Boolean = running

        @Volatile
        private var running = false
    }

    /** State notifikasi. INPUT sengaja tidak di-refresh untuk status kecil. */
    private enum class Stage {
        SEARCHING,   // port belum ada; tampilkan aksi buka Setelan + batal
        INPUT,       // port ada; tampilkan RemoteInput dan jangan ganggu pengetikan
        WORKING,     // pairing sedang berjalan; bersihkan aksi segera
        RESULT       // hasil akhir; tawarkan retry hanya untuk kegagalan non-teknis
    }

    private val main = Handler(Looper.getMainLooper())
    private var portDiscovery: AdbPairingPortDiscovery? = null
    private var overlay: PairingOverlay.Card? = null
    private var discoveryRunning = false

    @Volatile private var currentHost: String = LOOPBACK_HOST
    @Volatile private var discoveredPort: Int = -1
    @Volatile private var detectedCode: String? = null
    @Volatile private var stage: Stage = Stage.SEARCHING
    @Volatile private var pairingInFlight = false
    @Volatile private var stopping = false
    @Volatile private var sessionGeneration = 0
    @Volatile private var overlayUnavailable = false
    @Volatile private var userDismissedOverlay = false

    private var notificationAllowsRetry = false
    private var lastAutoAttempt = ""

    private val timeoutRunnable = Runnable {
        AgentLog.event("pairing: batas waktu 10 menit tercapai, sesi ditutup")
        cleanupAndStop()
    }

    // --------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            Log.e(TAG, "createNotificationChannel gagal", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            handle(intent)
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand gagal — sesi pairing dihentikan dengan bersih", t)
            AgentLog.event("pairing error: ${t.javaClass.simpleName}: ${t.message}")
            try {
                toast("Pairing gagal dimulai: ${t.javaClass.simpleName}")
            } catch (_: Throwable) {
            }
            cleanupAndStop()
            START_NOT_STICKY
        }
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP -> cleanupAndStop()
            ACTION_RETRY -> startSession(reset = true)
            ACTION_OPEN_ADB_SETTINGS -> {
                promoteToForeground("Membuka pengaturan Debug nirkabel…", preserveInputNotification = true)
                openWirelessDebugging()
            }
            ACTION_SHOW_OVERLAY -> {
                // Jangan rebuild notifikasi saat kolom inline mungkin sedang
                // terbuka; service sudah foreground pada Stage.INPUT.
                promoteToForeground("Menampilkan kartu pairing…", preserveInputNotification = true)
                showOverlay()
            }
            ACTION_INPUT_CODE -> onCodeSubmitted(intent)
            ACTION_START -> startSession(reset = true)
            else -> startSession(reset = false)
        }
    }

    private fun startSession(reset: Boolean) {
        // Generasi baru membuat hasil thread lama dibuang; pairing bisa lambat
        // dan tidak boleh menimpa state sesi yang sudah dimulai ulang.
        sessionGeneration++
        if (reset) resetSessionState()
        stage = Stage.SEARCHING
        overlayUnavailable = false
        userDismissedOverlay = false
        notificationAllowsRetry = false
        if (!promoteToForeground("Menyimak port pairing (mDNS + dialog Setelan)…")) {
            toast("Tidak bisa memulai pairing: izinkan Notifikasi untuk app ini, lalu coba lagi.")
            AgentLog.event("pairing gagal start: foreground service ditolak")
            cleanupAndStop()
            return
        }

        currentHost = LOOPBACK_HOST
        registerDialogScanner()
        startPortDiscovery()
        showOverlay()

        main.removeCallbacks(timeoutRunnable)
        main.postDelayed(timeoutRunnable, PAIRING_TIMEOUT_MS)
    }

    private fun resetSessionState() {
        stopPortDiscovery()
        AgentAccessibilityService.setPairingDialogListener(null)
        discoveredPort = -1
        detectedCode = null
        lastAutoAttempt = ""
        pairingInFlight = false
        stage = Stage.SEARCHING
    }

    // ------------------------------------------------------------- remote input

    /**
     * RemoteInput dibaca dari Intent foreground-service, pola yang sama dengan
     * AppManager. `stage` dipindah ke WORKING SEBELUM `startForeground()` supaya
     * aksi inline langsung hilang; kalau tidak, SystemUI bisa membiarkan spinner
     * input berputar dan pengguna mengira angkanya tidak terkirim.
     */
    private fun onCodeSubmitted(intent: Intent) {
        stage = Stage.WORKING
        notificationAllowsRetry = false
        promoteToForeground("Kode diterima — memeriksa port…")

        val remoteBundle = try {
            RemoteInput.getResultsFromIntent(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "tidak bisa membaca hasil RemoteInput", t)
            null
        }
        val fromRemote = remoteBundle
            ?.getCharSequence(EXTRA_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()
        val fromExtra = intent.getStringExtra(EXTRA_CODE)?.trim().orEmpty()
        val code = fromRemote.ifEmpty { fromExtra }

        AgentLog.event(
            "pairing: aksi notifikasi diterima " +
                "(remoteInput=${remoteBundle != null}, portHint=${intent.getIntExtra(EXTRA_PORT, -1)})"
        )

        if (code.isEmpty()) {
            restoreInputStage()
            publish(
                "Kode kosong — ketuk \"Ketik Kode Pairing\" lalu isi 6 angka",
                Color.parseColor("#FBBF24"),
                "Jika kolom inline di HP ini tidak mau terbuka, gunakan kartu melayang: " +
                    "ketuk isi notifikasi ini untuk menampilkannya kembali.",
                forceNotification = true
            )
            return
        }

        submitPairingCode(code, intent.getIntExtra(EXTRA_PORT, -1), source = "notifikasi")
    }

    private fun restoreInputStage() {
        stage = if (discoveredPort > 0) Stage.INPUT else Stage.SEARCHING
        notificationAllowsRetry = false
    }

    // ---------------------------------------------------------------- discovery

    private fun startPortDiscovery() {
        if (discoveryRunning) return
        discoveryRunning = true
        portDiscovery = AdbPairingPortDiscovery(
            this,
            onPort = { port ->
                main.post { onPortDiscovered(port, "mDNS") }
            },
            onDiagnostic = { message ->
                main.post { AgentLog.event("pairing: $message") }
            }
        )
        try {
            portDiscovery?.start()
        } catch (t: Throwable) {
            discoveryRunning = false
            Log.w(TAG, "discovery port pairing gagal", t)
            AgentLog.event("pairing: mDNS gagal — ${t.javaClass.simpleName}")
        }
    }

    private fun stopPortDiscovery() {
        discoveryRunning = false
        try {
            portDiscovery?.stop()
        } catch (_: Throwable) {
        }
        portDiscovery = null
    }

    private fun onPortDiscovered(port: Int, source: String) {
        if (stopping || port !in 1..65535) return
        val changed = discoveredPort != port
        discoveredPort = port
        PairingOverlay.setPort(overlay, port)

        if (stage != Stage.WORKING && stage != Stage.RESULT) {
            stage = Stage.INPUT
            publish(
                "✓ Port $port terdeteksi — ketik 6 angka kode pairing",
                Color.parseColor("#4ADE80"),
                forceNotification = changed
            )
        }
        if (changed) AgentLog.event("pairing: port $port terdeteksi via $source")
    }

    private fun registerDialogScanner() {
        AgentAccessibilityService.setPairingDialogListener { snapshot ->
            main.post { onPairingDialogSnapshot(snapshot) }
        }
        // Dialog mungkin sudah terbuka sebelum service sempat mendaftar.
        main.postDelayed({
            AgentAccessibilityService.scanPairingDialogNow()?.let(::onPairingDialogSnapshot)
        }, 500L)
    }

    /**
     * Kode dari layar Setelan TIDAK PERNAH dicatat. Parser hanya menerima
     * kombinasi port+kode pada window Setelan, jadi layar utama Debug nirkabel
     * (yang memuat port CONNECT, bukan port pairing) tidak disalahartikan.
     */
    private fun onPairingDialogSnapshot(snapshot: PairingDialogParser.Snapshot) {
        if (stopping || stage == Stage.WORKING || !snapshot.isComplete) return
        val code = snapshot.code ?: return

        onPortDiscovered(snapshot.port, "dialog Setelan")
        detectedCode = code

        val card = overlay
        if (card != null) {
            PairingOverlay.setCode(card, code)
            publish(
                "✓ Port & kode terbaca otomatis — ketuk Hubungkan Sekarang",
                Color.parseColor("#4ADE80")
            )
            AgentLog.event("pairing: port+kode terbaca otomatis dari dialog Setelan (nilai kode tidak dilog)")
        } else if (overlayUnavailable && !userDismissedOverlay) {
            // Auto-submit HANYA bila sistem memang tidak bisa memasang kartu.
            // Bila pengguna sendiri menekan ✕, pilihan itu dihormati: sesi
            // menunggu input sadar lewat notifikasi, bukan pairing diam-diam.
            val signature = "${snapshot.port}:$code"
            if (signature != lastAutoAttempt) {
                lastAutoAttempt = signature
                publish(
                    "✓ Port & kode terbaca otomatis — memasangkan…",
                    Color.parseColor("#4ADE80"),
                    forceNotification = true
                )
                AgentLog.event("pairing: auto-submit dari dialog Setelan (kode tidak dilog)")
                submitPairingCode(code, snapshot.port, source = "dialog sistem")
            }
        }
    }

    /** Tunggu port bila pengguna mengirim kode tepat sebelum discovery selesai. */
    private fun resolvePort(hint: Int): Int {
        if (hint > 0) return hint
        var waited = 0L
        while (discoveredPort <= 0 && waited < PORT_WAIT_MS && !stopping) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            waited += 200
        }
        return discoveredPort
    }

    // --------------------------------------------------------------- overlay

    private fun showOverlay() {
        hideOverlay()
        overlayUnavailable = false
        userDismissedOverlay = false

        val svc = AgentAccessibilityService.instance
        val svcWm = svc?.overlayWindowManager()
        if (svc != null && svcWm != null) {
            try {
                val card = PairingOverlay.build(svc, svcWm, ::onOverlayPair, ::dismissOverlay)
                card.params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                if (svc.attachAccessibilityOverlay(card.view, card.params)) {
                    overlay = card
                    AgentLog.event("overlay pairing dipasang lewat aksesibilitas (tanpa izin tambahan)")
                    onOverlayReady()
                    return
                }
                AgentLog.event("overlay aksesibilitas ditolak — mencoba jalur izin overlay biasa")
            } catch (t: Throwable) {
                Log.w(TAG, "overlay aksesibilitas gagal", t)
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            overlayUnavailable = true
            AgentLog.event(
                "overlay tidak dipasang: aksesibilitas belum aktif DAN izin " +
                    "\"Tampilkan di atas aplikasi lain\" belum ada — pakai baris notifikasi"
            )
            publish(
                "Kartu tidak tersedia — ketik kode di baris notifikasi",
                Color.parseColor("#FBBF24"),
                "Kartu melayang tidak bisa dipasang: layanan aksesibilitas belum aktif dan " +
                    "izin \"tampilkan di atas aplikasi lain\" belum diberikan. " +
                    "Jika kode sudah terbaca otomatis, pairing akan langsung dicoba.",
                forceNotification = true
            )
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val card = PairingOverlay.build(this, wm, ::onOverlayPair, ::dismissOverlay)
            card.params.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            wm.addView(card.view, card.params)
            overlay = card
            AgentLog.event("overlay pairing dipasang lewat izin 'tampilkan di atas app lain'")
            onOverlayReady()
        } catch (t: Throwable) {
            overlayUnavailable = true
            Log.w(TAG, "overlay TYPE_APPLICATION_OVERLAY gagal", t)
            AgentLog.event("overlay ditolak sistem (${t.javaClass.simpleName}) — pakai baris notifikasi")
        }
    }

    private fun onOverlayReady() {
        val card = overlay ?: return
        val port = discoveredPort
        val code = detectedCode
        if (port > 0) PairingOverlay.setPort(card, port)
        if (!code.isNullOrEmpty()) PairingOverlay.setCode(card, code)

        when {
            port > 0 && !code.isNullOrEmpty() -> publish(
                "✓ Port & kode terbaca otomatis — ketuk Hubungkan Sekarang",
                Color.parseColor("#4ADE80")
            )
            port > 0 -> publish(
                "✓ Port $port terdeteksi — tinggal ketik 6 angka kode pairing",
                Color.parseColor("#4ADE80")
            )
            else -> publish("Menyimak port pairing (mDNS + dialog Setelan)…", Color.parseColor("#38BDF8"))
        }
    }

    private fun onOverlayPair(port: Int, code: String) {
        submitPairingCode(code, port, source = "kartu")
    }

    /**
     * Tombol ✕ = sembunyikan kartu, BUKAN batalkan sesi. Notifikasi tetap
     * menjadi jalur input yang aman; aksi "Batal" di notifikasi yang menghentikan
     * seluruh sesi. Pemisahan ini penting agar "close" tidak terasa merusak alur.
     */
    private fun dismissOverlay() {
        userDismissedOverlay = true
        if (hideOverlay()) {
            publish(
                "Kartu disembunyikan — lanjutkan dari notifikasi",
                Color.parseColor("#38BDF8")
            )
            AgentLog.event("overlay pairing disembunyikan; sesi tetap aktif lewat notifikasi")
        } else {
            publish(
                "Kartu belum bisa dilepas — coba lagi, atau Batal dari notifikasi",
                Color.parseColor("#F87171"),
                forceNotification = true
            )
            AgentLog.event("overlay pairing gagal dilepas saat tombol tutup ditekan")
        }
    }

    /** Mengembalikan true bila tidak ada kartu lagi yang terpasang. */
    private fun hideOverlay(): Boolean {
        val card = overlay ?: return true
        return if (tryRemove(card)) {
            overlay = null
            true
        } else {
            Log.w(TAG, "kartu pairing belum bisa dilepas — menjadwalkan percobaan ulang")
            scheduleOverlayRemovalRetries()
            false
        }
    }

    private fun scheduleOverlayRemovalRetries() {
        for ((index, delay) in listOf(250L, 1_000L).withIndex()) {
            main.postDelayed({
                val stale = overlay ?: return@postDelayed
                if (tryRemove(stale)) {
                    overlay = null
                    AgentLog.event("overlay pairing akhirnya berhasil dilepas (retry ${index + 1})")
                }
            }, delay)
        }
    }

    /** Coba lepas [card] lewat semua jalur yang mungkin, sinkron di main thread. */
    private fun tryRemove(card: PairingOverlay.Card): Boolean {
        if (!card.view.isAttachedToWindow) return true

        try {
            card.wm.removeViewImmediate(card.view)
        } catch (t: Throwable) {
            Log.w(TAG, "removeViewImmediate lewat wm pemasang gagal", t)
        }
        if (!card.view.isAttachedToWindow) return true

        if (card.params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            val svc = AgentAccessibilityService.instance
            if (svc != null) {
                try {
                    svc.detachAccessibilityOverlay(card.view)
                } catch (t: Throwable) {
                    Log.w(TAG, "detach lewat aksesibilitas gagal", t)
                }
            }
        }
        if (!card.view.isAttachedToWindow) return true

        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.removeViewImmediate(card.view)
            !card.view.isAttachedToWindow
        } catch (t: Throwable) {
            Log.w(TAG, "removeViewImmediate lewat wm aplikasi gagal", t)
            !card.view.isAttachedToWindow
        }
    }

    // ------------------------------------------------------------- pairing

    private fun submitPairingCode(code: String, portHint: Int, source: String) {
        val normalized = code.filter { it.isDigit() }
        if (normalized.length < 6) {
            restoreInputStage()
            publish(
                "Kode harus 6 digit angka",
                Color.parseColor("#FBBF24"),
                forceNotification = true
            )
            return
        }
        if (portHint > 0) onPortDiscovered(portHint, source)
        if (!discoveryRunning) startPortDiscovery()

        val generation = sessionGeneration
        Thread({
            val port = resolvePort(portHint)
            main.post {
                if (generation != sessionGeneration || stopping) return@post
                if (port <= 0) {
                    reportNoPort()
                } else {
                    startPairExecution(currentHost, port, normalized.take(6))
                }
            }
        }, "gososmed-adb-pair-submit").start()
    }

    private fun reportNoPort() {
        restoreInputStage()
        publish(
            "Port belum terdeteksi — nyalakan \"Debug nirkabel\", lalu coba lagi",
            Color.parseColor("#FBBF24"),
            "Nyalakan \"Debug nirkabel\" di Opsi Pengembang, buka dialog " +
                "\"Pasangkan perangkat dengan kode pairing\", lalu coba lagi. " +
                "Jika mDNS dibatasi router/OEM, port akan dibaca dari dialog itu.",
            forceNotification = true
        )
        toast("Port pairing belum terdeteksi. Aktifkan 'Debug nirkabel' lalu coba lagi.")
    }

    private fun startPairExecution(host: String, port: Int, code: String) {
        if (pairingInFlight) return
        pairingInFlight = true
        stage = Stage.WORKING
        notificationAllowsRetry = false
        val generation = sessionGeneration

        publish(
            "Memasangkan perangkat…",
            Color.parseColor("#FBBF24"),
            forceNotification = true,
            enablePairButton = false
        )

        Thread({
            val (ok, reason) = try {
                AdbPairingController.pair(host, port, code)
            } catch (t: Throwable) {
                Log.e(TAG, "pair() melempar", t)
                false to "adb_pair_failed: ${t.message ?: t.javaClass.simpleName}"
            }
            main.post {
                if (generation != sessionGeneration || stopping) return@post
                pairingInFlight = false
                if (ok) {
                    stage = Stage.RESULT
                    notificationAllowsRetry = false
                    publish(
                        "✓ Berhasil — menyambung…",
                        Color.parseColor("#4ADE80"),
                        forceNotification = true
                    )
                    toast("✓ ADB berhasil dipasangkan!")
                    AgentLog.event("pairing ADB berhasil ($host:$port)")
                    main.postDelayed({ cleanupAndStop() }, 2_500)
                } else {
                    stage = Stage.RESULT
                    val technical = AdbPairingController.isTechnicalFailure(reason)
                    notificationAllowsRetry = !technical
                    if (technical) {
                        publish(
                            "✗ Gagal TEKNIS — kode Anda TIDAK salah: ${reason.take(70)}",
                            Color.parseColor("#F87171"),
                            "Masalah TEKNIS di APK ini — membuat kode baru tidak akan menolong. " +
                                "Detail: $reason",
                            forceNotification = true
                        )
                    } else {
                        publish(
                            "✗ Gagal: ${reason.take(70)} — buat kode baru lalu coba lagi",
                            Color.parseColor("#F87171"),
                            "$reason\n\nBuka ulang dialog pairing untuk kode BARU. " +
                                "Jika kode terbaca otomatis, cukup ketuk Hubungkan Sekarang.",
                            forceNotification = true
                        )
                    }
                    toast("✗ Pairing gagal: $reason")
                    AgentLog.event("pairing ADB gagal: $reason")
                }
            }
        }, "gososmed-adb-pair-exec").start()
    }

    // --------------------------------------------------------- notification

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getForegroundService(
            this, 5,
            Intent(this, AdbPairingService::class.java).apply { action = ACTION_SHOW_OVERLAY },
            immutableFlags()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ Pairing ADB GoSosmed")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText + notificationGuidance()))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)

        when (stage) {
            Stage.SEARCHING -> {
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        null, "Buka Debug Nirkabel", settingsPendingIntent()
                    ).build()
                )
                builder.addAction(stopAction())
            }

            Stage.INPUT -> {
                val codeIntent = Intent(this, AdbPairingService::class.java).apply {
                    action = ACTION_INPUT_CODE
                    putExtra(EXTRA_PORT, discoveredPort)
                }
                val pCode = PendingIntent.getForegroundService(this, 2, codeIntent, mutableFlags())
                val remoteInput = RemoteInput.Builder(EXTRA_CODE)
                    .setLabel("Ketik 6 angka kode pairing")
                    .setAllowFreeFormInput(true)
                    .build()
                builder.addAction(
                    NotificationCompat.Action.Builder(null, "Ketik Kode Pairing", pCode)
                        .addRemoteInput(remoteInput)
                        .build()
                )
                builder.addAction(stopAction())
            }

            Stage.WORKING -> Unit

            Stage.RESULT -> {
                if (notificationAllowsRetry) {
                    builder.addAction(
                        NotificationCompat.Action.Builder(
                            null, "Coba Lagi", servicePendingIntent(4, ACTION_RETRY, false)
                        ).build()
                    )
                }
                builder.addAction(stopAction())
            }
        }

        return builder.build()
    }

    private fun notificationGuidance(): String {
        return when (stage) {
            Stage.SEARCHING ->
                "\n\nBuka Setelan → Opsi Pengembang → Debug nirkabel → " +
                    "\"Pasangkan perangkat dengan kode pairing\". Biarkan layar kode terbuka."
            Stage.INPUT ->
                "\n\nPort sudah terdeteksi. Ketik 6 angka dari layar Setelan di baris notifikasi, " +
                    "atau ketuk notifikasi ini untuk memakai kartu melayang."
            Stage.WORKING ->
                "\n\nKode sedang diperiksa. Jangan tutup layar kode sampai hasil muncul."
            Stage.RESULT ->
                "\n\nKetuk notifikasi untuk menampilkan kartu, atau gunakan aksi yang tersedia."
        }
    }

    private fun stopAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            null, "Batal", servicePendingIntent(1, ACTION_STOP, false)
        ).build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String, mutable: Boolean): PendingIntent {
        return PendingIntent.getForegroundService(
            this,
            requestCode,
            Intent(this, AdbPairingService::class.java).apply { this.action = action },
            if (mutable) mutableFlags() else immutableFlags()
        )
    }

    private fun refreshNotification(statusText: String, notifExtra: String = "") {
        try {
            val fullText = if (notifExtra.isEmpty()) statusText else "$statusText\n\n$notifExtra"
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(fullText))
        } catch (t: Throwable) {
            Log.w(TAG, "gagal memperbarui notifikasi", t)
        }
    }

    /**
     * Satu sumber kebenaran untuk kartu + notifikasi.
     *
     * Pengecualian penting: saat Stage.INPUT, status kecil hanya mengubah kartu.
     * Memanggil notify() saat kolom inline terbuka akan menutup kolom dan
     * menghapus ketikan pengguna di banyak OEM — akar lapangan "input notifikasi
     * tidak berfungsi". Perubahan state besar tetap memakai forceNotification.
     */
    private fun publish(
        statusText: String,
        color: Int,
        notifExtra: String = "",
        forceNotification: Boolean = false,
        enablePairButton: Boolean = true,
    ) {
        PairingOverlay.status(overlay, statusText, color, enablePairButton)
        if (forceNotification || stage != Stage.INPUT) {
            refreshNotification(statusText, notifExtra)
        }
    }

    private fun promoteToForeground(
        statusText: String,
        preserveInputNotification: Boolean = false,
    ): Boolean {
        if (preserveInputNotification && stage == Stage.INPUT) {
            // Sudah foreground; membangun ulang notifikasi di state ini bisa
            // menutup kolom inline yang sedang dipakai pengguna.
            return true
        }
        val notif = try {
            buildNotification(statusText)
        } catch (t: Throwable) {
            Log.e(TAG, "buildNotification gagal", t)
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground bertipe gagal, mencoba tanpa tipe", t)
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground cadangan juga gagal", t2)
                false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB Wireless Pairing",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifikasi interaktif untuk memasukkan kode pairing ADB"
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    // -------------------------------------------------------------- helpers

    private fun openWirelessDebugging() {
        try {
            startActivity(resolveSettingsIntent())
        } catch (_: Throwable) {
            toast("Buka Setelan → Opsi Pengembang → Debug nirkabel")
        }
    }

    /**
     * Aksi notifikasi yang membuka Activity harus memakai PendingIntent
     * getActivity langsung. Bila ia membuka service dulu lalu service memanggil
     * startActivity, pengecualian Background Activity Launch dari tap notifikasi
     * tidak ikut berpindah — di Android 10+ tap tampak "tidak berfungsi".
     */
    private fun settingsPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(this, 3, resolveSettingsIntent(), immutableFlags())
    }

    private fun resolveSettingsIntent(): Intent {
        for (action in listOf(ACTION_OPEN_WIRELESS, ACTION_DEV_SETTINGS)) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                @Suppress("DEPRECATION")
                if (packageManager.resolveActivity(intent, 0) != null) return intent
            } catch (_: Throwable) {
            }
        }
        return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    private fun cleanupAndStop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cleanupAndStop() }
            return
        }
        if (stopping) return
        stopping = true
        sessionGeneration++

        main.removeCallbacks(timeoutRunnable)
        AgentAccessibilityService.setPairingDialogListener(null)
        stopPortDiscovery()
        discoveredPort = -1
        detectedCode = null
        hideOverlay()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(timeoutRunnable)
        AgentAccessibilityService.setPairingDialogListener(null)
        stopPortDiscovery()
        hideOverlay()
        super.onDestroy()
    }
}
