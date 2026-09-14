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
import io.github.muntashirakon.adb.android.AdbMdns
import java.net.InetAddress

/**
 * v0.9.7 — PAIRING ENGINE. Pola yang dipakai adalah pola produksi AppManager
 * (aplikasi yang menulis `libadb-android`, library yang kita pakai juga).
 *
 * ================== MENGAPA DITULIS ULANG (v0.9.4–v0.9.6 gagal) ==================
 *
 * Laporan lapangan setelah v0.9.6 dipasang:
 *
 *   1. "Masih tidak bisa konek wireless debug — tidak menampilkan overlay
 *       atau pengisian kode untuk pairing."
 *   2. "Klik Hubungkan di Langkah 3 → Langkah 1 mati / status belum aktif,
 *       padahal di Setelan aksesibilitas sudah ON."
 *
 * ---------------------------------------------------------------------------
 * SEBAB 1 — TIDAK ADA UI SAMA SEKALI
 * ---------------------------------------------------------------------------
 * v0.9.4–v0.9.6 memasang SATU-SATUNYA overlay dengan
 * `TYPE_APPLICATION_OVERLAY`, yang menuntut izin `SYSTEM_ALERT_WINDOW` DAN
 * saklar OEM MIUI/HyperOS yang terpisah. Bila salah satu belum aktif,
 * `showFloatingOverlay()` langsung `return` TANPA memberi tahu pengguna —
 * kegagalan senyap. Pengguna melihat persis: "tidak ada overlay apa pun".
 *
 * PERBAIKAN: overlay sekarang dipasang lewat
 * `TYPE_ACCESSIBILITY_OVERLAY` oleh [AgentAccessibilityService] — jenis jendela
 * yang **tidak memerlukan izin apa pun** dan tidak terkena penjagaan pop-up
 * latar belakang OEM. `TYPE_APPLICATION_OVERLAY` tetap ada sebagai cadangan.
 * Notifikasi RemoteInput tetap jalur yang selalu tersedia.
 *
 * ---------------------------------------------------------------------------
 * SEBAB 2 — KODE DARI NOTIFIKASI TIDAK PERNAH SAMPAI
 * ---------------------------------------------------------------------------
 * v0.9.5/v0.9.6 mengirim hasil RemoteInput lewat
 * `PendingIntent.getBroadcast(...)` + `BroadcastReceiver` yang didaftarkan
 * dinamis dengan `RECEIVER_EXPORTED`. Rantai itu punya banyak titik gagal
 * (flag ekspor, receiver yang sudah dilepas saat service dibuat ulang, paket
 * yang dibatasi) dan kegagalannya SENYAP: notifikasi tampak "tidak berfungsi".
 *
 * AppManager — referensi produksi — TIDAK memakai broadcast sama sekali:
 *   • aksinya `PendingIntent.getForegroundService(...)` yang menunjuk ke
 *     SERVICE INI SENDIRI, dengan flag MUTABLE (wajib: SystemUI menuliskan
 *     hasil RemoteInput ke Intent itu);
 *   • hasilnya dibaca di `onStartCommand()` lewat
 *     `RemoteInput.getResultsFromIntent(intent)`.
 * Rantai itu lebih pendek, tidak bergantung pada flag ekspor, dan sudah
 * terbukti di produksi. Itulah yang sekarang kami pakai.
 *
 * ---------------------------------------------------------------------------
 * SEBAB 3 — LANGKAH 1 "MATI" SAAT LANGKAH 3 DIKLIK
 * ---------------------------------------------------------------------------
 * `AdbPairingService`, `AgentForegroundService`, `AgentAccessibilityService`
 * dan `MainActivity` berada di PROSES YANG SAMA. Satu exception yang lolos dari
 * `onStartCommand()` (mis. dari `buildNotification()` atau pembuatan view, yang
 * di v0.9.6 TIDAK dibungkus try/catch) mematikan SELURUH proses — dan
 * `AccessibilityService` ikut mati bersama prosesnya. Itulah sebab keluhan
 * "klik Hubungkan → Langkah 1 mati", dan sebab tidak ada overlay maupun
 * notifikasi yang muncul.
 *
 * PERBAIKAN BERLAPIS:
 *   • SELURUH `onCreate`/`onStartCommand` dibungkus try/catch — jalur pairing
 *     tidak boleh bisa menjatuhkan proses.
 *   • [com.gososmed.agent.AgentApp] memasang penangkap exception terakhir yang
 *     MENULIS sebab crash ke disk, sehingga bila tetap terjadi kita tidak buta.
 *   • Pembacaan status Langkah 1 dipindah ke API resmi
 *     `AccessibilityManager.getEnabledAccessibilityServiceList()` (lihat
 *     [AgentAccessibilityService.osEnabled]) — bukan lagi mengandalkan
 *     `rootInActiveWindow` yang berkedip null.
 *
 * ================== JAWABAN SOAL IP (sering disalahpahami) ==================
 * Pairing di sini SELF-PAIRING: HP memasangkan dirinya sendiri dengan `adbd`
 * di HP yang sama. Karena itu host pairing SELALU `127.0.0.1` (loopback), tidak
 * pernah IP Wi-Fi. Yang benar-benar dibutuhkan pengguna adalah PORT, dan itu
 * ditemukan otomatis lewat mDNS `_adb-tls-pairing._tcp`. IP Wi-Fi hanya dipakai
 * pada jalur cadangan manual `AdbPairingController.connectTo(host, port)`.
 *
 * ================== ALUR YANG BENAR ==================
 *  1. Pengguna menekan "Hubungkan" (Langkah 3).
 *  2. Service `startForeground()` → notifikasi interaktif muncul SEKETIKA, dan
 *     mDNS mulai menyimak port pairing di latar.
 *  3. Kartu melayang dipasang lewat aksesibilitas (tanpa izin apa pun).
 *  4. Pengguna membuka Setelan → Debug nirkabel → "Pasangkan perangkat dengan
 *     kode pairing". Layar kode tampil — BIARKAN TERBUKA.
 *  5. Pengguna mengetik 6 angka: di kartu melayang, ATAU di baris notifikasi
 *     (RemoteInput). Layar kode tidak pernah ditutup → kode tidak berganti.
 *  6. Port diambil dari mDNS; pairing dijalankan; notifikasi jadi "✓ Berhasil".
 *  7. Penyambungan berjalan di belakang lewat `AdbPairingController.pair()`.
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "GoAgentPairSvc"
        private const val CHANNEL_ID = "adb_pairing_channel"
        private const val NOTIF_ID = 4919

        /** Batas umur sesi pairing (sama seperti AppManager). */
        private const val PAIRING_TIMEOUT_MS = 10 * 60 * 1000L

        /** Berapa lama menunggu port mDNS setelah kode dikirim. */
        private const val PORT_WAIT_MS = 8_000

        /** Host pairing = loopback. SELALU. Lihat KDoc kelas. */
        const val LOOPBACK_HOST = "127.0.0.1"

        const val ACTION_START = "com.gososmed.agent.START_PAIRING"
        const val ACTION_STOP = "com.gososmed.agent.STOP_PAIRING"
        const val ACTION_INPUT_CODE = "com.gososmed.agent.INPUT_PAIR_CODE"
        const val ACTION_OPEN_ADB_SETTINGS = "com.gososmed.agent.OPEN_ADB_SETTINGS"
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
                // startForegroundService dari latar bisa ditolak (Android 12+).
                // Jangan biarkan itu menjatuhkan proses pemanggil.
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

        /** v0.9.7 — dipakai MainActivity untuk menampilkan kartu sebelum navigasi. */
        fun isRunning(): Boolean = running

        @Volatile
        private var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var adbMdns: AdbMdns? = null
    private var overlay: PairingOverlay.Card? = null
    private var mdnsRunning = false

    @Volatile private var currentHost: String = LOOPBACK_HOST
    @Volatile private var discoveredPort: Int = -1
    @Volatile private var stopping = false

    private val timeoutRunnable = Runnable {
        AgentLog.event("pairing: batas waktu 10 menit tercapai, sesi ditutup")
        cleanupAndStop()
    }

    // --------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // v0.9.7 — dibungkus: kegagalan di sini dulu menjatuhkan seluruh proses
        // (dan ikut mematikan AccessibilityService).
        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            Log.e(TAG, "createNotificationChannel gagal", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.9.7 — PAGAR UTAMA. Jalur pairing TIDAK BOLEH bisa menjatuhkan
        // proses: proses ini juga menjalankan AccessibilityService, jadi crash
        // di sini tampak oleh pengguna sebagai "Langkah 1 mati".
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

            ACTION_OPEN_ADB_SETTINGS -> {
                // PendingIntent ini dibuat dengan getForegroundService(), jadi
                // startForeground() wajib dipanggil lebih dulu.
                promoteToForeground("Membuka pengaturan Debug nirkabel…")
                openWirelessDebugging()
            }

            ACTION_INPUT_CODE -> onCodeSubmitted(intent)

            else -> startSession()
        }
    }

    private fun startSession() {
        if (!promoteToForeground("Menyimak port pairing (mDNS)…")) {
            toast("Tidak bisa memulai pairing: izinkan Notifikasi untuk app ini, lalu coba lagi.")
            AgentLog.event("pairing gagal start: foreground service ditolak")
            cleanupAndStop()
            return
        }
        currentHost = LOOPBACK_HOST
        startMdns()
        showOverlay()
        main.removeCallbacks(timeoutRunnable)
        main.postDelayed(timeoutRunnable, PAIRING_TIMEOUT_MS)
    }

    /**
     * v0.9.7 — kode dikirim dari notifikasi (RemoteInput) atau dari kartu.
     *
     * WAJIB memanggil `startForeground()` lebih dulu: PendingIntent aksi
     * notifikasi dibuat dengan `getForegroundService()`, sehingga sistem
     * menuntut `startForeground()` dalam 5 detik — jika tidak, service dibunuh
     * dengan `ForegroundServiceDidNotStartInTimeException`. AppManager
     * melakukan hal yang sama (lihat `startPairing()` di AdbPairingService-nya).
     */
    private fun onCodeSubmitted(intent: Intent) {
        promoteToForeground("Memasangkan…")

        val code = try {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(EXTRA_CODE)
                ?.toString()
                ?.trim()
                .orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "tidak bisa membaca hasil RemoteInput", t)
            ""
        }

        if (code.isEmpty()) {
            publish(
                "Kode kosong — ketuk \"Ketik Kode Pairing\" lalu isi 6 angka",
                Color.parseColor("#FBBF24")
            )
            return
        }

        val hint = intent.getIntExtra(EXTRA_PORT, -1)
        if (!mdnsRunning) startMdns()

        Thread({
            val port = resolvePort(hint)
            main.post {
                if (port <= 0) {
                    reportNoPort()
                } else {
                    handlePairSubmission(currentHost, port, code)
                }
            }
        }, "gososmed-adb-pair-notif").start()
    }

    // ------------------------------------------------------------------- mDNS

    private fun startMdns() {
        if (mdnsRunning) return
        mdnsRunning = true
        try {
            adbMdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _: InetAddress?, port: Int ->
                if (port > 0) {
                    // mDNS pada self-pairing bisa melaporkan alamat Wi-Fi
                    // perangkat sendiri; nilai itu TIDAK dipakai sebagai host
                    // (host tetap loopback). Yang kita ambil hanya PORT-nya.
                    discoveredPort = port
                    main.post {
                        PairingOverlay.setPort(overlay, port)
                        publish(
                            "✓ Port $port terdeteksi — ketik 6 angka kode pairing",
                            Color.parseColor("#4ADE80")
                        )
                    }
                    AgentLog.event("pairing: port $port terdeteksi via mDNS")
                }
            }
            adbMdns?.start()
        } catch (t: Throwable) {
            mdnsRunning = false
            Log.w(TAG, "mDNS discovery gagal", t)
            AgentLog.event("pairing: mDNS gagal — ${t.javaClass.simpleName}")
        }
    }

    private fun stopMdns() {
        mdnsRunning = false
        try {
            adbMdns?.stop()
        } catch (_: Throwable) {
        }
        adbMdns = null
    }

    /** Tunggu port bila kode diketik sebelum mDNS menemukannya. */
    private fun resolvePort(hint: Int): Int {
        if (hint > 0) return hint
        var waited = 0
        while (discoveredPort <= 0 && waited < PORT_WAIT_MS) {
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

    /**
     * v0.9.7 — DUA jalur overlay, dicoba berurutan.
     *
     * 1. `TYPE_ACCESSIBILITY_OVERLAY` lewat [AgentAccessibilityService] —
     *    TIDAK butuh izin apa pun dan tidak terkena penjagaan OEM. Ini jalur
     *    utama, dan sebabnya overlay akhirnya muncul.
     * 2. `TYPE_APPLICATION_OVERLAY` langsung dari service ini — butuh
     *    `SYSTEM_ALERT_WINDOW`. Cadangan bila aksesibilitas belum ter-bind.
     *
     * Bila keduanya gagal, notifikasi RemoteInput tetap bekerja; pengguna
     * diberi tahu lewat log, bukan dibiarkan menebak.
     */
    private fun showOverlay() {
        hideOverlay()

        val svc = AgentAccessibilityService.instance
        val svcWm = svc?.overlayWindowManager()
        if (svc != null && svcWm != null) {
            try {
                val card = PairingOverlay.build(svc, svcWm, ::onOverlayPair, ::cleanupAndStop)
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
            AgentLog.event(
                "overlay tidak dipasang: aksesibilitas belum aktif DAN izin " +
                    "\"Tampilkan di atas aplikasi lain\" belum ada — pakai baris notifikasi"
            )
            // v0.9.9 — JANGAN diam. Dulu kasus ini hanya masuk log, sehingga
            // pengguna menunggu kartu yang tidak pernah datang. Sekarang
            // keadaannya diumumkan, dan diarahkan ke notifikasi.
            publish(
                "Kartu tidak tersedia — ketik kode di baris notifikasi",
                Color.parseColor("#FBBF24"),
                "Kartu melayang tidak bisa dipasang: layanan aksesibilitas belum aktif dan " +
                    "izin \"tampilkan di atas aplikasi lain\" belum diberikan. " +
                    "Ketik 6 angka kode pairing lewat tombol \"Ketik Kode Pairing\" " +
                    "di notifikasi ini — jalur itu selalu tersedia."
            )
            return
        }
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val card = PairingOverlay.build(this, wm, ::onOverlayPair, ::cleanupAndStop)
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
            Log.w(TAG, "overlay TYPE_APPLICATION_OVERLAY gagal", t)
            AgentLog.event("overlay ditolak sistem (${t.javaClass.simpleName}) — pakai baris notifikasi")
        }
    }

    /**
     * v0.9.9 — lepas kartu, dan JANGAN buang referensinya sebelum benar-benar
     * terlepas.
     *
     * ================== MENGAPA DITULIS ULANG ==================
     * Versi lama melakukan `overlay = null` lebih dulu, lalu melepas lewat
     * `AgentAccessibilityService.instance?.detachAccessibilityOverlay(...)`.
     * Tanda tanya itu membuat kegagalan SENYAP: bila layanan aksesibilitas
     * terputus (`instance == null`), pelepasan batal — dan karena referensi
     * `overlay` sudah dibuang, tidak ada kesempatan mencoba lagi. Hasilnya
     * persis keluhan lapangan: **kartu pairing tidak bisa ditutup**, menutupi
     * layar terus-menerus walau sesi pairing sudah selesai dan notifikasinya
     * sudah bertuliskan "terhubung".
     *
     * Sekarang:
     *  • referensi hanya dibuang SETELAH pelepasan terbukti berhasil, sehingga
     *    `onDestroy()` masih bisa mencoba lagi;
     *  • jalur utama memakai `card.wm` — WindowManager yang memasang kartu —
     *    sehingga tetap berfungsi meski `instance` sudah null;
     *  • dua jalur cadangan bila jalur utama menolak.
     */
    private fun hideOverlay() {
        val card = overlay ?: return
        if (tryRemove(card)) {
            overlay = null
        } else {
            Log.w(TAG, "kartu pairing belum bisa dilepas — dicoba lagi saat service dihancurkan")
        }
    }

    /** Coba lepas [card] lewat semua jalur yang mungkin. true bila berhasil. */
    private fun tryRemove(card: PairingOverlay.Card): Boolean {
        // 1) Jalur utama: WindowManager yang MEMASANG kartu ini.
        try {
            card.wm.removeView(card.view)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "removeView lewat wm pemasang gagal", t)
        }
        // 2) Cadangan: lewat layanan aksesibilitas (jendela aksesibilitas).
        if (card.params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            val svc = AgentAccessibilityService.instance
            if (svc != null) {
                try {
                    svc.detachAccessibilityOverlay(card.view)
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "detach lewat aksesibilitas gagal", t)
                }
            }
        }
        // 3) Cadangan terakhir: WindowManager aplikasi.
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) wm.removeView(card.view)
            wm != null
        } catch (t: Throwable) {
            Log.w(TAG, "removeView lewat wm aplikasi gagal", t)
            false
        }
    }

    /**
     * v0.9.9 — terapkan keadaan yang sudah diketahui ke kartu yang BARU dipasang.
     *
     * mDNS dimulai SEBELUM kartu dipasang ([startSession] memanggil
     * `startMdns()` lalu `showOverlay()`), jadi port sering sudah ketemu saat
     * kartu muncul. Dulu port hanya diisi bila kebetulan kartu sudah ada saat
     * callback mDNS berjalan; bila belum, `setPort` tidak melakukan apa-apa dan
     * pengguna harus mengetik port secara manual. Sekarang port yang tersimpan
     * selalu diterapkan begitu kartu terpasang — pengguna cukup mengetik kode.
     */
    private fun onOverlayReady() {
        val card = overlay ?: return
        val port = discoveredPort
        if (port > 0) {
            PairingOverlay.setPort(card, port)
            publish(
                "✓ Port $port terdeteksi — tinggal ketik 6 angka kode pairing",
                Color.parseColor("#4ADE80")
            )
        } else {
            publish("Menyimak port pairing (mDNS)…", Color.parseColor("#38BDF8"))
        }
    }

    private fun onOverlayPair(port: Int, code: String) {
        publish("Menghubungkan…", Color.parseColor("#FBBF24"))
        Thread({
            val p = if (port > 0) port else resolvePort(-1)
            main.post {
                if (p <= 0) reportNoPort() else handlePairSubmission(currentHost, p, code)
            }
        }, "gososmed-adb-pair-overlay").start()
    }

    // ------------------------------------------------------------- pairing

    private fun reportNoPort() {
        publish(
            "Port belum terdeteksi — nyalakan \"Debug nirkabel\", lalu coba lagi",
            Color.parseColor("#FBBF24"),
            "Nyalakan \"Debug nirkabel\" di Opsi Pengembang, lalu ketik kode lagi di sini."
        )
        toast("Port pairing belum terdeteksi. Aktifkan 'Debug nirkabel' lalu coba lagi.")
    }

    private fun handlePairSubmission(host: String, port: Int, code: String) {
        if (port <= 0) {
            reportNoPort()
            return
        }
        val normalized = code.filter { it.isDigit() }
        if (normalized.length < 6) {
            PairingOverlay.status(overlay, "Kode harus 6 digit angka", Color.parseColor("#FBBF24"))
            return
        }

        publish("Memasangkan ke $host:$port…", Color.parseColor("#FBBF24"))

        Thread({
            val (ok, reason) = try {
                AdbPairingController.pair(host, port, normalized)
            } catch (t: Throwable) {
                Log.e(TAG, "pair() melempar", t)
                false to "adb_pair_failed: ${t.message ?: t.javaClass.simpleName}"
            }
            main.post {
                if (ok) {
                    publish("✓ Berhasil — menyambung…", Color.parseColor("#4ADE80"))
                    toast("✓ ADB berhasil dipasangkan!")
                    AgentLog.event("pairing ADB berhasil ($host:$port)")
                    main.postDelayed({ cleanupAndStop() }, 2_500)
                } else {
                    // v0.9.8 — JANGAN sarankan "kode baru" untuk kegagalan
                    // TEKNIS. Kalimat itu menyesatkan dan membuat pengguna
                    // berputar-putar membuat kode baru padahal kodenya benar
                    // (persis yang terjadi pada v0.9.7 dengan NoSuchMethodException
                    // Conscrypt). Lihat AdbPairingController.isTechnicalFailure.
                    if (AdbPairingController.isTechnicalFailure(reason)) {
                        publish(
                            "✗ Gagal TEKNIS — kode Anda TIDAK salah: ${reason.take(70)}",
                            Color.parseColor("#F87171"),
                            "Masalah TEKNIS di APK ini — membuat kode baru tidak akan menolong. " +
                                "Detail: $reason"
                        )
                    } else {
                        publish(
                            "✗ Gagal: ${reason.take(70)} — buat kode baru lalu coba lagi",
                            Color.parseColor("#F87171"),
                            "$reason\n\nBuka ulang dialog pairing untuk kode BARU, lalu ketik " +
                                "di sini tanpa menutup layar kode."
                        )
                    }
                    toast("✗ Pairing gagal: $reason")
                    AgentLog.event("pairing ADB gagal: $reason")
                }
            }
        }, "gososmed-adb-pair-exec").start()
    }

    // --------------------------------------------------------- notification

    /**
     * v0.9.7 — notifikasi = jalur yang selalu tersedia.
     *
     * Aksi RemoteInput dibuat dengan `PendingIntent.getForegroundService()` ke
     * service INI (bukan broadcast), persis pola AppManager. Flag MUTABLE wajib:
     * SystemUI menuliskan hasil ketikan ke dalam Intent tersebut.
     */
    private fun buildNotification(statusText: String): Notification {
        val codeIntent = Intent(this, AdbPairingService::class.java).apply {
            action = ACTION_INPUT_CODE
            putExtra(EXTRA_PORT, discoveredPort)
        }
        val pCode = PendingIntent.getForegroundService(
            this, 2, codeIntent, mutableFlags()
        )

        val remoteInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel("Ketik 6 angka kode pairing")
            .setAllowFreeFormInput(true)
            .build()

        val codeAction = NotificationCompat.Action.Builder(
            null, "Ketik Kode Pairing", pCode
        ).addRemoteInput(remoteInput).build()

        val openAction = NotificationCompat.Action.Builder(
            null, "Buka Debug Nirkabel", PendingIntent.getForegroundService(
                this, 3,
                Intent(this, AdbPairingService::class.java).apply { action = ACTION_OPEN_ADB_SETTINGS },
                immutableFlags()
            )
        ).build()

        val stopAction = NotificationCompat.Action.Builder(
            null, "Batal", PendingIntent.getForegroundService(
                this, 1,
                Intent(this, AdbPairingService::class.java).apply { action = ACTION_STOP },
                immutableFlags()
            )
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ Pairing ADB GoSosmed")
            .setContentText(statusText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$statusText\n\nSetelan → Opsi Pengembang → Debug nirkabel → " +
                        "\"Pasangkan perangkat dengan kode pairing\". BIARKAN layar kode terbuka, " +
                        "lalu tarik panel notifikasi ini dan ketik 6 angka di baris " +
                        "\"Ketik Kode Pairing\". Kode berganti bila layar itu ditutup."
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(codeAction)
            .addAction(openAction)
            .addAction(stopAction)
            .build()
    }

    private fun refreshNotification(statusText: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(statusText))
        } catch (t: Throwable) {
            Log.w(TAG, "gagal memperbarui notifikasi", t)
        }
    }

    /**
     * v0.9.9 — SATU sumber kebenaran untuk status pairing.
     *
     * ================== MENGAPA ADA FUNGSI INI ==================
     * Sebelum ini kartu melayang dan notifikasi diperbarui sendiri-sendiri, dan
     * beberapa jalur hanya memperbarui salah satunya — terutama jalur kode dari
     * notifikasi (`onCodeSubmitted`), yang tidak pernah menyentuh kartu sama
     * sekali. Akibatnya kedua permukaan bisa menampilkan hal yang berlawanan:
     * notifikasi "terhubung" sementara kartu masih "Menghubungkan…". Pengguna
     * lalu bertanya "yang dipakai yang mana?" — kebingungan yang sepenuhnya
     * diciptakan aplikasi, bukan oleh keadaan pairing yang sebenarnya.
     *
     * Semua pembaruan status kini wajib lewat sini, sehingga kartu dan
     * notifikasi SELALU menampilkan teks yang sama.
     */
    private fun publish(statusText: String, color: Int, notifExtra: String = "") {
        PairingOverlay.status(overlay, statusText, color)
        // Inti status SELALU identik di kedua permukaan. `notifExtra` hanya
        // menambahkan panduan di bawahnya, jadi tidak pernah bisa menghasilkan
        // dua keadaan yang saling bertentangan.
        refreshNotification(
            if (notifExtra.isEmpty()) statusText else "$statusText\n\n$notifExtra"
        )
    }

    /**
     * Promosikan ke foreground. Tidak pernah melempar.
     *
     * `ServiceCompat.startForeground` dipakai agar tipe layanan diteruskan
     * dengan benar di Android 14 (targetSdk 34) — tanpa tipe yang cocok dengan
     * manifest, sistem melempar MissingForegroundServiceTypeException.
     */
    private fun promoteToForeground(statusText: String): Boolean {
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
            enableLights(true)
            lightColor = Color.CYAN
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    // -------------------------------------------------------------- helpers

    private fun openWirelessDebugging() {
        for (action in listOf(ACTION_OPEN_WIRELESS, ACTION_DEV_SETTINGS)) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            toast("Buka Setelan → Opsi Pengembang → Debug nirkabel")
        }
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
        // Operasi jendela WAJIB di main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cleanupAndStop() }
            return
        }
        if (stopping) return
        stopping = true

        main.removeCallbacks(timeoutRunnable)
        stopMdns()
        discoveredPort = -1
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
        stopMdns()
        hideOverlay()
        super.onDestroy()
    }
}
