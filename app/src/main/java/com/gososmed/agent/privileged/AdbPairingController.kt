package com.gososmed.agent.privileged

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v0.9.0 — orkestrator transport ADB: inisialisasi, pairing, sambung, putus.
 *
 * KENAPA ADA LAPISAN INI:
 * [AdbLocalShell] hanya berbicara protokol. Yang perlu tahu urutan kerja
 * (generate kunci DULU → buat shell → pasang ke [PrivilegedShellHolder] →
 * coba sambung), menyimpan status antar-restart, dan menyediakan API untuk UI
 * serta command server adalah kelas ini. Pemisahan ini yang membuat
 * penghapusan Shizuku tidak menyebar ke UI.
 *
 * ALUR SAAT APLIKASI HIDUP ([bootstrap]):
 *  1. Generate/muat kunci ADB (LAMBAT — ratusan ms; karena itu di thread IO).
 *  2. Pasang [AdbLocalShell] ke [PrivilegedShellHolder] sehingga
 *     `capabilities` langsung melaporkan field `adb_*` yang benar.
 *  3. Coba sambung diam-diam ke adbd. Kalau Debug nirkabel mati, ini gagal
 *     dengan kode `adb_*` yang jujur — TIDAK ada percobaan berulang agresif,
 *     karena menyambung tanpa Debug nirkabel tidak akan pernah berhasil.
 *
 * STATUS YANG DISIMPAN: apakah perangkat pernah berhasil pairing. Ini yang
 * membedakan pesan "belum pernah dihubungkan" dari "sesi terputus (HP baru
 * reboot)" — dua hal berbeda yang butuh tindakan berbeda dari pengguna.
 */
object AdbPairingController {

    private const val TAG = "GoAgentAdbCtl"

    private const val PREFS = "agent"
    private const val KEY_PAIRED_ONCE = "adb_paired_once"

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gososmed-adb-init").apply { isDaemon = true }
    }

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var shell: AdbLocalShell? = null

    /** Diisi bila inisialisasi gagal; dilaporkan apa adanya ke `capabilities`. */
    @Volatile
    private var initError = ""

    @Volatile
    private var initializing = false

    // ------------------------------------------------------------------ bootstrap

    /**
     * Siapkan transport sekali per proses. Aman dipanggil berkali-kali
     * (idempoten) — service Android sering dibuat ulang.
     */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        appContext = app
        synchronized(lock) {
            if (shell != null || initializing) return
            initializing = true
        }
        io.execute {
            try {
                // 1) Kunci ADB. LAMBAT saat pertama kali → wajib di thread IO.
                val keys = AdbKeyStore.loadOrCreate(app)
                val instance = AdbLocalShell(app, keys)
                // 2) Pulihkan status "pernah dipasangkan" supaya UI bisa
                //    membedakan "belum pernah" vs "terputus".
                instance.markPaired(prefs(app).getBoolean(KEY_PAIRED_ONCE, false))
                // 3) Pasang ke holder → capabilities & semua pemanggil langsung
                //    memakai transport nyata (walau belum tersambung).
                shell = instance
                PrivilegedShellHolder.install(instance)
                initError = ""
                Log.i(TAG, "transport ADB siap (belum tentu tersambung)")
                // 4) Coba sambung. Kegagalan TIDAK dicoba ulang di sini; caller
                //    (UI/command) yang memicu percobaan berikutnya, supaya tidak
                //    ada loop latar belakang saat Debug nirkabel memang mati.
                instance.connectNow()
                if (instance.status().connected) {
                    rememberPaired(app, true)
                }
            } catch (t: Throwable) {
                initError = "adb_init_failed: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, "inisialisasi transport ADB gagal", t)
            } finally {
                synchronized(lock) { initializing = false }
            }
        }
    }

    // -------------------------------------------------------------------- aksi

    /**
     * Pairing dengan kode 6 digit.
     *
     * PENTING — kenapa TIDAK menyambung secara sinkron di sini:
     * Command agent dibatasi 30 dtk di server (`agenthub.DefaultTimeout`).
     * Pairing saja memakai sampai ~15 dtk + 2 dtk kelonggaran; menambahkan
     * `connectNow()` (discovery mDNS 5 dtk + socket 6 dtk) secara sinkron akan
     * membuat durasi terburuk melewati 30 dtk. Akibatnya server melaporkan
     * GAGAL padahal pairing sudah berhasil dan tersimpan — kegagalan palsu
     * yang menyesatkan pemilik HP.
     *
     * Karena itu: pairing dikembalikan sebagai hasil (itu intinya), lalu
     * penyambungan dijalankan di BELAKANGAN. Status koneksi yang sebenarnya
     * dibaca UI/`capabilities` pada polling berikutnya, apa adanya.
     *
     * Blocking hanya selama pairing. Aman dipanggil dari thread non-main.
     */
    fun pair(host: String, port: Int, code: String): Pair<Boolean, String> {
        val ctx = appContext
            ?: return false to "adb_init_failed: transport belum disiapkan"
        val instance = ensureShell(ctx)
            ?: return false to (initError.ifEmpty { "adb_init_failed: kunci ADB tidak bisa disiapkan" })

        if (port <= 0) {
            return false to "adb_pair_failed: port pairing tidak valid"
        }
        if (code.isBlank()) {
            return false to "adb_pair_failed: kode pairing kosong"
        }

        val ok = instance.pairNow(host.ifBlank { "127.0.0.1" }, port, code.trim())
        if (!ok) {
            return false to instance.status().error.ifEmpty { "adb_pair_failed: pairing gagal" }
        }
        // Pairing berhasil = kunci sudah diotorisasi adbd. Simpan statusnya
        // SEKARANG (sebelum penyambungan), karena inilah hasil yang bermakna.
        rememberPaired(ctx, true)
        // Sambung di belakang; kegagalannya dilaporkan jujur oleh status().
        instance.connectAsync()
        return true to ""
    }

    /** Coba sambung dengan penemuan otomatis; blocking. */
    fun connect(): Boolean {
        val ctx = appContext ?: return false
        val instance = ensureShell(ctx) ?: return false
        val ok = instance.connectNow()
        if (ok) rememberPaired(ctx, true)
        return ok
    }

    /** Sambung dengan host+port manual (jalur cadangan bila mDNS diblokir). */
    fun connectTo(host: String, port: Int): Boolean {
        val ctx = appContext ?: return false
        val instance = ensureShell(ctx) ?: return false
        val ok = instance.connectTo(host, port)
        if (ok) rememberPaired(ctx, true)
        return ok
    }

    /**
     * Putuskan sesi. Kunci TETAP tersimpan sehingga pairing tidak perlu
     * diulang saat Debug nirkabel dinyalakan lagi.
     */
    fun disconnect() {
        shell?.disconnectNow()
    }

    /**
     * Lupakan identitas sepenuhnya (kunci dihapus). Dipakai bila pengguna
     * ingin memulai bersih — konsekuensinya pairing harus diulang.
     */
    fun forget() {
        val ctx = appContext ?: return
        shell?.disconnectNow()
        AdbKeyStore.clear(ctx)
        rememberPaired(ctx, false)
        shell?.markPaired(false)
    }

    /** Status untuk `capabilities` dan UI. Tidak pernah melempar. */
    fun status(): ShellStatus {
        val instance = shell
            ?: return ShellStatus(
                available = false,
                paired = false,
                connected = false,
                uid = -1,
                error = initError.ifEmpty { "adb_not_paired: otomasi lanjutan belum disiapkan" },
            )
        return instance.status()
    }

    /** true bila perangkat pernah berhasil pairing (dibaca dari prefs). */
    fun everPaired(): Boolean {
        val ctx = appContext ?: return false
        return prefs(ctx).getBoolean(KEY_PAIRED_ONCE, false)
    }

    /**
     * v0.9.8 — PISAHKAN KEGAGALAN TEKNIS DARI KEGAGALAN KODE.
     *
     * ================== MENGAPA INI PENTING ==================
     *
     * Sebelum v0.9.8, setiap kegagalan pairing disajikan ke pengguna dengan
     * kalimat "kode salah atau kedaluwarsa — buat kode baru lalu coba lagi".
     * Untuk kegagalan yang sebenarnya bersifat TEKNIS (mis. kelas library tidak
     * ada, metode tersembunyi tidak bisa direfleksikan), kalimat itu MENYESATKAN
     * TOTAL: pengguna lalu berulang kali membuat kode baru dan mengetiknya,
     * padahal kodenya tidak pernah salah.
     *
     * Itu persis yang terjadi pada v0.9.7: kegagalan
     * `NoSuchMethodException: com.android.org.conscrypt.Conscrypt
     * .exportKeyingMaterial` ditampilkan sebagai "buat kode baru", sehingga
     * akar masalahnya (Conscrypt tidak dibundel) tidak pernah terlihat.
     *
     * Penanda di bawah menandai kegagalan yang TIDAK mungkin diperbaiki dengan
     * kode baru. Dipakai oleh UI aplikasi (tab Setup) MAUPUN oleh notifikasi dan
     * kartu melayang, supaya pesannya konsisten di semua permukaan.
     */
    fun isTechnicalFailure(reason: String): Boolean {
        if (reason.isEmpty()) return false
        val markers = listOf(
            // Kelas/metode library tidak ada atau tidak bisa diakses.
            "NoSuchMethodException",
            "NoSuchFieldException",
            "ClassNotFoundException",
            "NoClassDefFoundError",
            // Pustaka native gagal dimuat.
            "UnsatisfiedLinkError",
            "ExceptionInInitializerError",
            // Transport belum siap — bukan salah kode pengguna.
            "adb_init_failed",
        )
        return markers.any { reason.contains(it, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ internal

    /**
     * Pastikan shell sudah ada. Bila bootstrap belum jalan (mis. command
     * datang terlalu awal), jalankan inisialisasi sinkron di thread pemanggil —
     * sudah dipastikan pemanggil bukan main thread.
     */
    private fun ensureShell(ctx: Context): AdbLocalShell? {
        shell?.let { return it }
        return try {
            val keys = AdbKeyStore.loadOrCreate(ctx)
            val instance = AdbLocalShell(ctx, keys)
            instance.markPaired(prefs(ctx).getBoolean(KEY_PAIRED_ONCE, false))
            shell = instance
            PrivilegedShellHolder.install(instance)
            initError = ""
            instance
        } catch (t: Throwable) {
            initError = "adb_init_failed: ${t.message ?: t.javaClass.simpleName}"
            Log.e(TAG, "penyiapan transport ADB gagal", t)
            null
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rememberPaired(ctx: Context, value: Boolean) {
        try {
            prefs(ctx).edit().putBoolean(KEY_PAIRED_ONCE, value).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "gagal menyimpan status paired: ${t.message}")
        }
        shell?.markPaired(value)
    }

    /**
     * Tunggu inisialisasi selesai (dipakai unit test / pemanggil yang butuh
     * kepastian). Mengembalikan false bila lewat batas.
     */
    fun awaitReady(timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (shell != null) return true
            try {
                TimeUnit.MILLISECONDS.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return shell != null
    }
}
