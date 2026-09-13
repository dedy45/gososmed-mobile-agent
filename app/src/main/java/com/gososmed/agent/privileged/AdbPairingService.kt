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
import java.net.InetAddress

/**
 * v0.9.6 — PAIRING ENGINE ala SHIZUKU (Notifikasi RemoteInput = JALUR UTAMA,
 * Floating Window = jalur bantu OPSIONAL).
 *
 * ============================ KONTEKS MASALAH ============================
 *
 * Laporan lapangan pada v0.9.4/v0.9.5:
 *
 *  1. "Klik Hubungkan ADB → overlay tidak muncul."
 *  2. "Wireless debugging ditutup, pindah ke app GoSosmed — kode sudah
 *      berubah/berganti."
 *  3. "IP tidak 127.0.0.1 — bagaimana deteksi otomatis menyesuaikan IP
 *      wireless HP?"
 *
 * -------------------------------------------------------------------------
 * JAWABAN 3 — SOAL IP (INI YANG PALING SERING DISALAHPAHAMI)
 * -------------------------------------------------------------------------
 * Pairing ADB di sini adalah SELF-PAIRING: HP memasangkan dirinya SENDIRI
 * dengan `adbd` yang berjalan DI HP yang sama. Karena itu:
 *
 *   • Host untuk PAIRING = `127.0.0.1` (loopback). SELALU. Tidak pernah
 *     IP Wi-Fi.
 *   • Untuk CONNECT setelah pairing berhasil, libadb memakai penemuan mDNS
 *     (`_adb-tls-connect._tcp`) dan MENGABAIKAN nilai `setHostAddress(...)`
 *     sepenuhnya. Jadi IP Wi-Fi tidak perlu diketik oleh pengguna.
 *   • IP Wi-Fi HANYA dipakai pada jalur cadangan MANUAL
 *     (`AdbPairingController.connectTo(host, port)`), ketika pengguna
 *     menyalin sendiri IP:PORT dari layar "Debug nirkabel".
 *
 * CACAT v0.9.4 yang kami perbaiki di sini: kode memakai
 * `AndroidUtils.getHostIpAddress(this)` dengan asumsi ia mengembalikan IP
 * Wi-Fi. FAKTA DARI SUMBER LIBRARY (libadb-android 3.1.1,
 * `AndroidUtils.java:39-57`): fungsi itu mengembalikan
 * `InetAddress.getLoopbackAddress()` = `127.0.0.1`; pada EMULATOR ia
 * mengembalikan `10.0.2.2`. Jadi "deteksi IP dinamis" v0.9.4 bukan sekadar
 * tidak berguna — pada emulator ia MERUSAK pairing. Karena kita memang
 * butuh loopback, jalur benar adalah menuliskannya eksplisit.
 *
 * -------------------------------------------------------------------------
 * JAWABAN 2 — SOAL KODE BERUBAH
 * -------------------------------------------------------------------------
 * Kode 6 digit itu berumur pendek dan sekali pakai (single-use, time-limited,
 * dihasilkan oleh `adbd`). Ia BERGANTI saat dialog "Pasangkan perangkat"
 * ditutup — mis. karena pengguna ditarik keluar ke app GoSosmed untuk
 * mengetik kode. Itu perilaku SISTEM, bukan bug app kita.
 *
 * SOLUSINYA justru yang dipakai Shizuku: pengguna TIDAK PERNAH meninggalkan
 * dialog pairing. Ia menarik panel notifikasi di atas dialog yang masih
 * terbuka, lalu mengetik kode lewat RemoteInput di baris notifikasi. Dialog
 * tetap hidup → kode tidak berubah.
 *
 * -------------------------------------------------------------------------
 * JAWABAN 1 — SOAL OVERLAY TIDAK MUNCUL
 * -------------------------------------------------------------------------
 * Tiga sebab, ketiganya diperbaiki:
 *   a) Parameter jendela kurang `FLAG_NOT_FOCUSABLE` (view tidak fokus).
 *   b) Overlay dipasang ASINKRON (`main.post{}`) sementara `MainActivity`
 *      meluncurkan Settings pada frame yang sama → `addView` bisa tiba saat
 *      fokus OS sudah berpindah dan ditolak.
 *   c) MIUI/HyperOS punya izin OEM tersendiri ("Tampilkan jendela
 *      sembulan saat berjalan di latar belakang") yang TERPISAH dari
 *      `Settings.canDrawOverlays()`.
 *
 * Karena (c) tidak bisa dijamin, Notifikasi RemoteInput-lah yang menjadi
 * JALUR UTAMA, dan overlay menjadi bonus. Inilah desain Shizuku yang benar:
 * notifikasi tidak bisa diblokir OEM selama izin POST_NOTIFICATIONS ada.
 *
 * ============================ ALUR YANG BENAR ============================
 *
 *   1. Pengguna menekan "Mulai Pairing" di app GoSosmed.
 *   2. Service ini `startForeground()` → notifikasi interaktif muncul
 *      SEKETIKA, dan mDNS `_adb-tls-pairing._tcp` mulai menyimak di latar.
 *   3. Pengguna masuk Setelan → Debug nirkabel → "Pasangkan perangkat
 *      dengan kode pairing". Dialog kode tampil.
 *   4. PENGATURAN NAVIGASI BARU dijalankan SETELAH notifikasi siap.
 *   5. Pengguna menarik panel notifikasi, mengetik 6 digit di baris
 *      "Ketik Kode Pairing" → RemoteInput → BroadcastReceiver di sini.
 *   6. Port dideteksi otomatis dari mDNS; pairing dijalankan; notifikasi
 *      berubah "✓ Berhasil" lalu bersih sendiri.
 *   7. Jaringan dibawa naik: `AdbPairingController.bootstrap()` melakukan
 *      connect (mDNS, bukan IP manual).
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "GoAgentPairSvc"
        private const val CHANNEL_ID = "adb_pairing_channel"
        private const val NOTIF_ID = 4919

        /**
         * v0.9.6 — Host untuk SELF-PAIRING. WAJIB loopback (lihat KDoc kelas).
         * Jangan pernah diisi IP Wi-Fi: pairing selalu ke `adbd` lokal.
         */
        const val LOOPBACK_HOST = "127.0.0.1"

        const val ACTION_START = "com.gososmed.agent.START_PAIRING"
        const val ACTION_STOP = "com.gososmed.agent.STOP_PAIRING"
        const val ACTION_INPUT_CODE = "com.gososmed.agent.INPUT_PAIR_CODE"
        const val ACTION_OPEN_ADB_SETTINGS = "com.gososmed.agent.OPEN_ADB_SETTINGS"
        const val EXTRA_CODE = "key_pairing_code"

        /** v0.9.6 — dipakai untuk tombol "Buka Debug Nirkabel" di notifikasi. */
        private const val ACTION_OPEN_WIRELESS =
            "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        private const val ACTION_DEV_SETTINGS =
            "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

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
            if (intent?.action != ACTION_INPUT_CODE) return
            val results = RemoteInput.getResultsFromIntent(intent) ?: return
            val code = results.getCharSequence(EXTRA_CODE)?.toString()?.trim()
            if (code.isNullOrEmpty()) return

            AgentLog.event("ADB pairing: kode dari notifikasi diterima (${code.length} digit)")

            // v0.9.6 — BALAPAN (race) yang wajib ditangani.
            // Pengguna bisa mengetik kode SEBELUM mDNS menemukan port (biasanya
            // butuh ~1-2 detik). v0.9.5 langsung memanggil
            // handlePairSubmission(host, -1, code) dan gagal "port invalid" —
            // pengguna lalu mengira kodenya salah padahal bukan.
            //
            // Solusi: beri tenggat tunggu singkat untuk port, dengan snapshot
            // nilai terkini setiap kali. Jika tetap tidak ada, beri pesan yang
            // BENAR (bukan "kode salah").
            val work = Runnable {
                var waited = 0
                val step = 200
                val maxWait = 6_000
                while (discoveredPort <= 0 && waited < maxWait) {
                    try {
                        Thread.sleep(step.toLong())
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    waited += step
                }
                val port = discoveredPort
                if (port <= 0) {
                    main.post {
                        updateOverlayStatus(
                            "Port belum terdeteksi — pastikan Debug nirkabel ON, lalu coba lagi",
                            Color.parseColor("#FBBF24")
                        )
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(
                            NOTIF_ID,
                            buildNotification("Port pairing belum terdeteksi. Aktifkan Debug nirkabel lalu coba lagi.")
                        )
                        // v0.9.6 — `this@AdbPairingService`, BUKAN `this`.
                        // Blok ini berada di dalam `codeReceiver = object :
                        // BroadcastReceiver()`, sehingga `this` menunjuk ke
                        // BroadcastReceiver (bukan Context) dan
                        // `Toast.makeText(this, ...)` TIDAK dapat dikompilasi
                        // ("None of the following candidates is applicable").
                        // Itulah tepatnya error kompilasi yang menjatuhkan CI
                        // build-apk (step Unit tests) & release (step Build
                        // release APK) pada commit 8ccce13.
                        Toast.makeText(
                            this@AdbPairingService,
                            "Port pairing belum terdeteksi. Aktifkan 'Debug nirkabel' lalu coba lagi.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    main.post { handlePairSubmission(currentHost, port, code) }
                }
            }
            Thread(work, "gososmed-adb-pair-notif").start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // v0.9.6 — RECEIVER_EXPORTED, BUKAN NOT_EXPORTED.
        //
        // RemoteInput dari notifikasi dikirim oleh SYSTEM (SystemUI), bukan
        // oleh proses kita sendiri, karena PendingIntent broadcast dieksekusi
        // oleh pengirim — SystemUI. Dengan RECEIVER_NOT_EXPORTED (v0.9.5),
        // Android 13+/14 MENOLAK siaran itu secara senyap dan kode yang
        // diketik pengguna tidak pernah sampai: notifikasi tampak "tidak
        // berfungsi". Itulah sebab keluhan "RemoteInput tidak jalan".
        //
        // Karena aksi ini dideklarasikan hanya untuk paket kita sendiri
        // (setPackage(packageName) pada Intent), mengekspornya tidak membuka
        // permukaan serangan yang berarti — kita tetap memvalidasi isinya.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(codeReceiver, IntentFilter(ACTION_INPUT_CODE), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(codeReceiver, IntentFilter(ACTION_INPUT_CODE))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanupAndStop()
            }
            ACTION_OPEN_ADB_SETTINGS -> {
                // v0.9.6 — tombol aksi di notifikasi: buka langsung layar Debug
                // nirkabel bila ada, jika tidak jatuh ke Opsi Pengembang.
                openWirelessDebugging()
            }
            else -> {
                // v0.9.6 — URUTAN PENTING. `startForeground()` HARUS selesai
                // (notifikasi benar-benar terpasang) SEBELUM apa pun yang bisa
                // memindahkan fokus OS. Karena itu overlay dipasang SETELAH ini
                // dan SEKARANG sinkron pada main thread.
                if (!promoteToForeground()) {
                    // v0.9.6 — PENGAMAN: bila FGS ditolak (izin notifikasi
                    // dicabut / OEM aneh), JANGAN diam lalu mati senyap seperti
                    // v0.9.5. Beri tahu pengguna apa yang harus diperbaiki dan
                    // hentikan dengan bersih. Kegagalan senyap adalah akar
                    // keluhan "klik tapi tidak terjadi apa-apa".
                    Toast.makeText(
                        this,
                        "Tidak bisa memulai pairing: izinkan Notifikasi untuk app ini, lalu coba lagi.",
                        Toast.LENGTH_LONG
                    ).show()
                    AgentLog.event("ADB pairing gagal start: foreground service ditolak (izin notifikasi?)")
                    cleanupAndStop()
                    return START_NOT_STICKY
                }

                // v0.9.6 — IP pairing = LOOPBACK, bukan IP Wi-Fi.
                // (v0.9.4 memakai AndroidUtils.getHostIpAddress() yang terbukti
                //  mengembalikan loopback/10.0.2.2 — lihat KDoc kelas.)
                currentHost = LOOPBACK_HOST

                startMdns()
                showFloatingOverlay()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * v0.9.6 — Promosi ke foreground dengan penanganan tipe yang benar.
     *
     * Pada Android 14 (API 34) `startForeground(id, notif)` untuk service
     * bertipe `specialUse` sebaiknya memakai overload bertipe eksplisit. Kami
     * mencobanya lebih dulu, lalu jatuh ke overload lama bila vendor belum
     * mengenali tipe tersebut. Apa pun yang terjadi, kami TIDAK membiarkan
     * exception menembus keluar `onStartCommand` — bila itu terjadi service
     * dibunuh dan pengguna tidak melihat kesalahan apa pun.
     */
    private fun promoteToForeground(): Boolean {
        val notif = buildNotification("Menyimak port pairing (mDNS)…")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground gagal", e)
            // Fallback terakhir: overload tanpa tipe.
            try {
                startForeground(NOTIF_ID, notif)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback juga gagal", e2)
                false
            }
        }
    }

    /**
     * v0.9.6 — Overlay OPSIONAL.
     *
     * Ini BUKAN jalur utama (lihat KDoc kelas): notifikasi RemoteInput-lah
     * yang utama. Overlay dipasang sebaik mungkin; jika gagal, pengguna tetap
     * bisa menyelesaikan pairing lewat notifikasi. Kegagalan di sini TIDAK
     * boleh menghentikan proses.
     *
     * Tiga perbaikan atas v0.9.5:
     *
     *  1. Dipasang SINKRON pada main thread (`onStartCommand` sudah berjalan di
     *     main thread). `main.post{}` v0.9.5 menunda pemasangan ke frame
     *     berikutnya, dan `MainActivity` meluncurkan Settings pada frame itu —
     *     fokus berpindah lebih dulu, `addView` ditolak.
     *  2. FLAG dipilih dengan sadar: `FLAG_NOT_TOUCH_MODAL` +
     *     `FLAG_WATCH_OUTSIDE_TOUCH` + `FLAG_LAYOUT_NO_LIMITS`, TANPA
     *     `FLAG_NOT_FOCUSABLE` — sebab kartu ini memuat `EditText` yang harus
     *     bisa menerima fokus agar keyboard muncul saat diketuk. Yang benar
     *     bukan menonaktifkan fokus, melainkan memasang view lebih awal
     *     (poin 1). Selain itu ditambahkan verifikasi `isAttachedToWindow`
     *     supaya penolakan sistem tercatat, bukan senyap seperti v0.9.5.
     *  3. Deteksi izin OEM MIUI yang terpisah dari `canDrawOverlays()` dan
     *     tuliskan pesan diagnosa ke log supaya kegagalan tidak senyap.
     */
    private fun showFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Izin overlay belum diberikan — jalur notifikasi tetap aktif (normal)")
            return
        }
        if (isXiaomiFamily() && !miuiBackgroundPopupAllowed()) {
            Log.w(
                TAG,
                "MIUI/HyperOS: izin OEM 'Tampilkan jendela sembulan saat berjalan di " +
                    "latar belakang' kemungkinan BELUM aktif — overlay bisa ditolak sistem. " +
                    "Jalur notifikasi tetap aktif (normal)."
            )
        }

        // Sinkron: onStartCommand berjalan di main thread.
        installOverlayNow()
    }

    private fun installOverlayNow() {
        removeFloatingView()

        wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
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
            text = if (discoveredPort > 0) "✓ Port ditemukan: $discoveredPort" else "Menyimak port pairing (mDNS)…"
            setTextColor(if (discoveredPort > 0) Color.GREEN else Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
        }

        val hintTv = TextView(this).apply {
            text = "Tip: kode juga bisa diketik dari notifikasi (tanpa menutup dialog pairing)."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(0, 0, 0, (6 * dp).toInt())
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
            tag = "pairBtn"
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
                statusTv.text = "Menghubungkan ke $currentHost:$p…"
                statusTv.setTextColor(Color.YELLOW)
                handlePairSubmission(currentHost, p, c)
            }
        }

        card.addView(header)
        card.addView(portEt)
        card.addView(codeEt)
        card.addView(statusTv)
        card.addView(hintTv)
        card.addView(pairBtn)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // v0.9.6 — FLAG dipilih dengan sadar:
        //   * FLAG_NOT_TOUCH_MODAL → sentuhan di luar kartu diteruskan ke
        //     Setelan di bawahnya, sehingga pengguna bisa menyalin port.
        //   * FLAG_WATCH_OUTSIDE_TOUCH → kami tahu saat pengguna menyentuh luar
        //     (dipakai untuk tidak menelan gesture).
        // FLAG_NOT_FOCUSABLE SENGAJA TIDAK dipakai: kartu ini memuat EditText
        // yang harus bisa menerima fokus agar keyboard muncul saat diketuk.
        // Yang benar bukan menonaktifkan fokus, melainkan memasang view pada
        // main thread sebelum fokus OS berpindah (lihat installOverlayNow).
        val params = WindowManager.LayoutParams(
            (300 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Hanya geser bila jari benar-benar bergerak (bukan ketukan
                        // pada EditText) agar tidak menelan interaksi input.
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (dx != 0 || dy != 0) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                wm?.updateViewLayout(card, params)
                            } catch (_: Exception) {}
                            return true
                        }
                    }
                }
                return false
            }
        })

        try {
            wm?.addView(card, params)
            floatView = card
            // Verifikasi attach: `isAttachedToWindow` membuktikan view benar-benar
            // masuk hierarki. Jika false, sistem menolaknya secara senyap —
            // kita catat supaya diagnosa lapangan tidak menebak-nebak.
            if (card.isAttachedToWindow) {
                Log.i(TAG, "Overlay pairing berhasil dipasang (attached)")
            } else {
                Log.w(TAG, "Overlay addView tidak error tetapi TIDAK attached — "
                    + "kemungkinan ditolak izin OEM. Notifikasi tetap jadi jalur utama.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memasang floating view dari service — "
                + "notifikasi tetap jadi jalur utama", e)
        }
    }

    /**
     * v0.9.6 — Deteksi izin OEM MIUI/HyperOS "Tampilkan jendela sembulan saat
     * berjalan di latar belakang". Ini terpisah dari `canDrawOverlays()`.
     *
     * Kami membaca `AppOpsManager` OP_SYSTEM_ALERT_WINDOW sebagai proksi;
     * nilai `MODE_ALLOWED` berarti kedua level (AOSP + OEM) sudah lolos.
     * Kegagalan deteksi diperlakukan sebagai "diizinkan" supaya kami tidak
     * pernah menghalangi pemasangan overlay hanya karena tebak-tebakan.
     */
    private fun miuiBackgroundPopupAllowed(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                ?: return true
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    android.os.Process.myUid(),
                    packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            true
        }
    }

    private fun isXiaomiFamily(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /**
     * v0.9.6 — Buka layar Debug nirkabel bila OEM menyediakannya, jika tidak
     * jatuh ke Opsi Pengembang. Dipakai oleh aksi notifikasi.
     */
    private fun openWirelessDebugging() {
        val candidates = listOf(ACTION_OPEN_WIRELESS, ACTION_DEV_SETTINGS)
        for (action in candidates) {
            try {
                val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (_: Exception) {
                // lanjut ke kandidat berikutnya
            }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Buka Setelan → Opsi Pengembang → Debug nirkabel", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePairSubmission(host: String, port: Int, code: String) {
        if (port <= 0) {
            // Pengaman terakhir: jangan pernah memanggil pair() dengan port
            // tidak valid — pesannya akan menyesatkan ("kode salah").
            Log.w(TAG, "handlePairSubmission: port tidak valid ($port), dibatalkan")
            updateOverlayStatus("Port belum valid — tunggu deteksi otomatis", Color.parseColor("#FBBF24"))
            return
        }
        val normalized = code.filter { it.isDigit() }
        if (normalized.length < 6) {
            Log.w(TAG, "handlePairSubmission: kode < 6 digit, dibatalkan")
            updateOverlayStatus("Kode harus 6 digit angka", Color.parseColor("#FBBF24"))
            return
        }

        val work = Runnable {
            val result = try {
                AdbPairingController.pair(host, port, normalized)
            } catch (t: Throwable) {
                Log.e(TAG, "pair() melempar", t)
                false to "adb_pair_failed: ${t.message ?: t.javaClass.simpleName}"
            }
            val (ok, reason) = result
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
                    updateOverlayStatus(
                        "✗ Gagal: ${reason.take(60)} — buka ulang dialog pairing untuk kode baru",
                        Color.parseColor("#F87171")
                    )
                    notifManager.notify(
                        NOTIF_ID,
                        buildNotification("✗ Gagal: $reason — buka ulang dialog pairing, lalu ketik kode BARU di sini")
                    )
                }
            }
        }
        Thread(work, "gososmed-adb-pair-exec").start()
    }

    private fun updateOverlayStatus(msg: String, color: Int) {
        floatView?.let { v ->
            val statusTv = v.findViewWithTag<TextView>("statusTv")
            statusTv?.text = msg
            statusTv?.setTextColor(color)
            // v0.9.6 — BUG FIX: v0.9.5 memakai `findViewById<Button>(View.NO_ID)`
            // yang selalu mengembalikan null, sehingga tombol "Hubungkan
            // Sekarang" TIDAK PERNAH di-aktifkan kembali setelah kegagalan.
            // Pengguna terjebak dengan tombol mati. Kami menandai tombol
            // dengan tag yang sama seperti saat dibuat.
            val btn = v.findViewWithTag<Button>("pairBtn")
            btn?.isEnabled = true
        }
    }

    private fun startMdns() {
        try {
            // v0.9.6 — mDNS `_adb-tls-pairing._tcp` memberi PORT pairing
            // otomatis. Ini yang menggantikan "deteksi IP" yang dulu dicari:
            // yang sebenarnya dibutuhkan pengguna adalah PORT, bukan IP —
            // dan host pairing tetap loopback.
            adbMdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
                if (port > 0) {
                    // PENTING: `host` dari mDNS pada SELF-pairing bisa berupa
                    // alamat Wi-Fi perangkat sendiri. Kami TIDAK memakainya
                    // untuk pairing — host pairing tetap loopback. Nilai mDNS
                    // hanya dipakai sebagai konfirmasi bahwa adbd lokal hidup.
                    discoveredPort = port
                    main.post {
                        floatView?.let { v ->
                            val portEt = v.findViewWithTag<EditText>("portEt")
                            if (portEt != null && portEt.text.isEmpty()) {
                                portEt.setText(port.toString())
                            }
                        }
                        updateOverlayStatus("✓ Port terdeteksi: $port", Color.GREEN)
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(
                            NOTIF_ID,
                            buildNotification("✓ Port $port terdeteksi. Ketik 6 angka kode pairing:")
                        )
                    }
                    AgentLog.event("ADB pairing: port $port terdeteksi via mDNS")
                }
            }
            adbMdns?.start()
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery error", e)
        }
    }

    /**
     * v0.9.6 — NOTIFIKASI = JALUR UTAMA pairing (desain Shizuku).
     *
     * Mengapa notifikasi, bukan overlay:
     *  - Notifikasi tidak bisa diblokir OEM selama izin POST_NOTIFICATIONS ada.
     *    Overlay bisa ditahan MIUI/HyperOS tanpa error apa pun.
     *  - RemoteInput memungkinkan pengguna MENGETIK KODE TANPA MENINGGALKAN
     *    dialog "Pasangkan perangkat" di Setelan. Karena dialog tidak pernah
     *    ditutup, kode tidak pernah berganti — inilah akar keluhan #2.
     *
     * Struktur notifikasi:
     *  - Baris 1: aksi "Ketik Kode Pairing" + RemoteInput (input angka).
     *  - Baris 2: aksi "Buka Debug Nirkabel" (memandu pengguna).
     *  - Baris 3: aksi "Batal" (menghentikan service + mDNS).
     *
     * Catatan PendingIntent: `pInput` WAJIB `FLAG_MUTABLE` karena RemoteInput
     * menuliskan hasil ke dalam Intent tersebut. `pStop`/`pOpen` immutabel.
     */
    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, AdbPairingService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = Intent(this, AdbPairingService::class.java).apply {
            action = ACTION_OPEN_ADB_SETTINGS
        }
        val pOpen = PendingIntent.getService(
            this, 3, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val remoteInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel("Ketik 6 angka kode pairing")
            .setAllowFreeFormInput(true)
            .build()

        // v0.9.6 — PendingIntent broadcast yang SAMA (requestCode 2) dipakai
        // ulang. `FLAG_UPDATE_CURRENT | FLAG_MUTABLE` wajib: RemoteInput harus
        // bisa menulis hasil ke Intent; tanpa MUTABLE sistem melempar
        // IllegalArgumentException pada Android 12+.
        val inputIntent = Intent(ACTION_INPUT_CODE).setPackage(packageName)
        val pInput = PendingIntent.getBroadcast(
            this, 2, inputIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "$statusText\n\nBuka Setelan → Opsi Pengembang → Debug nirkabel → " +
                        "\"Pasangkan perangkat dengan kode pairing\". Biarkan layar kode tetap " +
                        "terbuka, lalu tarik panel notifikasi ini dan ketik 6 angka di baris " +
                        "\"Ketik Kode Pairing\"."
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(replyAction)
            .addAction(Notification.Action.Builder(null, "Buka Debug Nirkabel", pOpen).build())
            .addAction(Notification.Action.Builder(null, "Batal", pStop).build())
            .setContentIntent(pOpen)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADB Wireless Pairing",
                // v0.9.6 — IMPORTANCE_HIGH + sound: notifikasi ini adalah JALUR
                // UTAMA pairing, bukan pemberitahuan pasif. Ia harus terlihat
                // dan terdengar agar pengguna tidak melewatkannya saat berada
                // di layar Setelan.
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi interaktif untuk memasukkan kode pairing ADB"
                enableLights(true)
                lightColor = Color.CYAN
                enableVibration(false)
                setShowBadge(true)
                // Muncul di layar kunci juga, supaya bisa dipakai walau HP
                // sempat terkunci saat pengguna menyalin kode.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun removeFloatingView() {
        floatView?.let { v ->
            try {
                if (v.isAttachedToWindow) wm?.removeView(v)
            } catch (_: Exception) {}
            floatView = null
        }
    }

    @Volatile private var stopping = false

    private fun cleanupAndStop() {
        // v0.9.6 — GUARD anti-rekursi. v0.9.5 memanggil `cleanupAndStop()` dari
        // `onDestroy()`, dan `cleanupAndStop()` memanggil `stopSelf()` yang
        // memicu `onDestroy()` lagi → `removeView`/`stopForeground` berjalan
        // berulang. Flag ini memastikan pembersihan dieksekusi tepat sekali.
        if (stopping) return
        stopping = true

        try {
            adbMdns?.stop()
            adbMdns = null
        } catch (_: Exception) {}
        discoveredPort = -1
        removeFloatingView()
        try {
            stopForeground(true)
        } catch (_: Exception) {}
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
