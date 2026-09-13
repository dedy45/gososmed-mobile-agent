package com.gososmed.agent.privileged

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * v0.9.0 — TRANSPORT TIER 1: eksekusi perintah sebagai user `shell` (uid 2000)
 * lewat ADB lokal di dalam HP itu sendiri.
 *
 * MENGGANTIKAN `ShizukuShell` v0.8.0. Perbedaannya: v0.8.0 bergantung pada
 * aplikasi pihak ketiga (Shizuku); kelas ini membawa klien ADB-nya sendiri,
 * sehingga pengguna cukup memasang SATU aplikasi.
 *
 * CARA KERJA (dan kenapa tetap butuh Debug nirkabel):
 *  1. `adbd` (daemon ADB di HP) hanya menerima klien yang terautentikasi.
 *     Autentikasi itu memakai pasangan kunci RSA + sertifikat ([AdbKeyStore]).
 *  2. Kali pertama, `adbd` belum mengenal kunci kita → alur PAIRING dengan
 *     kode 6 digit dari layar Opsi Pengembang > Debug nirkabel.
 *  3. Setelah dipasangkan, koneksi berikutnya memakai kunci yang sama tanpa
 *     pairing — SELAMA Debug nirkabel masih hidup.
 *  4. Android mematikan Debug nirkabel setiap HP reboot → pairing harus
 *     diulang. Ini batas platform, bukan kekurangan implementasi.
 *
 * INVARIAN KELAS INI:
 *  - Tidak pernah melempar exception ke pemanggil. Semua kegagalan menjadi
 *    [ShellResult.failure] berisi kode `adb_*`.
 *  - Tidak pernah melaporkan sukses palsu: exit code -1 (tak terbaca) atau
 *    exit code non-nol BUKAN sukses.
 *  - Setiap operasi blocking dibatasi waktu. Tidak ada jalur yang bisa
 *    menggantung selamanya.
 *
 * THREADING — PENTING:
 * Semua operasi ADB berjalan di SATU thread IO milik kelas ini (`gososmed-adb-io`).
 * Pemanggil (termasuk yang dari main thread) diblokir paling lama
 * `timeout + GRACE_MS`. Ini SENGAJA: kontrak `PrivilegedShell.exec` bersifat
 * sinkron, sama seperti v0.8.0.
 *
 * CATATAN JUJUR soal main thread (sebagian sudah diperbaiki di v0.9.0):
 *  - Command `shell` dan `adbPair` dari server kini dijalankan di thread IO
 *    (lihat `AgentCommand.SERVICE_FREE_COMMANDS` + `AgentWsClient`), jadi
 *    keduanya TIDAK memblokir main thread.
 *  - MASIH memblokir main thread: pemanggilan shell dari dalam command
 *    aksesibilitas (`tap`, `startApp`, `wakeScreen`, `killAppMode`). Itu
 *    diwarisi dari v0.8.0 dan perbaikannya (memindahkan dispatch perintah
 *    ke thread IO) dicatat sebagai pekerjaan lanjutan, bukan bagian F3.
 *
 * VISIBILITAS: `internal` karena konstruktornya menerima
 * [AdbKeyStore.KeyMaterial] yang juga internal — deklarasi publik tidak boleh
 * membocorkan tipe internal. Tidak ada konsumen di luar modul ini; pemanggil
 * luar memakai [AdbPairingController] dan antarmuka [PrivilegedShell].
 */
internal class AdbLocalShell(
    private val appContext: Context,
    private val keys: AdbKeyStore.KeyMaterial,
) : AbsAdbConnectionManager(), PrivilegedShell {

    companion object {
        private const val TAG = "GoAgentAdbShell"
        private const val DEVICE_NAME = "GoSosmed Agent"

        /**
         * ANGGARAN WAKTU (ditemukan lewat tinjauan ulang sebelum uji perangkat).
         *
         * Kenapa ini dipisah dan bukan satu angka:
         * `connectTls(context, timeoutMillis)` memakai `timeoutMillis` untuk
         * mDNS DISCOVERY, lalu masih menambah timeout SOCKET sendiri
         * (`setTimeout`). Jadi durasi terburuknya = DISCOVERY + SOCKET, bukan
         * `timeoutMillis` saja.
         *
         * BUG YANG DIPERBAIKI: sebelumnya batas luar (`runBounded`) hanya
         * `timeoutMs + GRACE` (10 dtk) sementara bagian dalam bisa berjalan
         * sampai 16 dtk. Akibatnya kita menyerah lebih dulu, dan — karena
         * socket blocking TIDAK bisa diinterupsi — tugas itu tetap menempati
         * SATU-SATUNYA thread IO, sehingga perintah berikutnya ikut macet.
         * Sekarang batas luar SELALU >= durasi terburuk bagian dalam.
         */
        const val DISCOVERY_TIMEOUT_MS = 5_000L

        /** Connect socket loopback; 6 dtk sudah sangat longgar untuk 127.0.0.1. */
        const val SOCKET_TIMEOUT_MS = 6_000L

        /**
         * Batas alur pairing (SPAKE2 + TLS handshake) ke `127.0.0.1`.
         * SPAKE2 lokal normalnya < 2 dtk; 15 dtk hanya jaring pengaman.
         *
         * Angka ini SENGAJA tidak lebih besar: pairing berjalan di dalam satu
         * command agent, dan server membatasi setiap command 30 dtk
         * (`agenthub.DefaultTimeout`). Lihat [AdbPairingController.pair] yang
         * TIDAK menyambung secara sinkron setelah pairing justru karena batas
         * ini — menggabungkan keduanya akan melewati 30 dtk dan membuat server
         * melaporkan gagal padahal pairing berhasil.
         */
        const val PAIR_TIMEOUT_MS = 15_000L

        /**
         * Kelonggaran di atas timeout perintah supaya penutupan stream dan
         * pembersihan sempat berjalan sebelum kita menyatakan timeout.
         */
        private const val GRACE_MS = 2_000L

        /** Durasi terburuk satu percobaan connect (discovery + socket + bersih-bersih). */
        private const val CONNECT_WORST_CASE_MS =
            DISCOVERY_TIMEOUT_MS + SOCKET_TIMEOUT_MS + GRACE_MS

        private const val READ_CHUNK = 8 * 1024

        /** Probe uid dibatasi pendek — ini hanya satu baris keluaran. */
        private const val UID_PROBE_TIMEOUT_MS = 3_000L

        /**
         * v0.9.6 — ALAMAT PAIRING YANG BENAR.
         *
         * Agent memasangkan dirinya sendiri ke `adbd` di HP yang sama, jadi
         * alamatnya adalah LOOPBACK. Ini satu-satunya nilai yang benar untuk
         * [pairNow]; jangan diganti dengan IP Wi-Fi (lihat komentar di `init`).
         *
         * Nilai ini HANYA dipakai jalur pairing. Jalur connect memakai mDNS
         * dan library mengabaikan alamat ini.
         */
        const val LOOPBACK_HOST = "127.0.0.1"
    }

    /** Satu thread untuk SEMUA operasi ADB. Serialisasi mencegah dua stream
     *  bersamaan pada satu koneksi, yang bisa saling merusak paket. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gososmed-adb-io").apply { isDaemon = true }
    }

    /** true bila adbd pernah menerima kunci kita (pairing sukses). */
    @Volatile
    private var paired = false

    /** Kode `adb_*` terakhir; "" bila sehat. Dibaca UI lewat `capabilities`. */
    @Volatile
    private var lastError = ""

    /** uid efektif hasil probe; -1 = belum diketahui. */
    @Volatile
    private var cachedUid = -1

    /**
     * SALINAN status koneksi milik kita sendiri.
     *
     * KENAPA TIDAK MEMANGGIL `isConnected()` LIBRARY (bug yang diperbaiki):
     * `AbsAdbConnectionManager.autoConnect()` menahan `synchronized (mLock)`
     * SELAMA SELURUH discovery + socket connect (sampai ~11 dtk), dan
     * `isConnected()` meminta kunci yang SAMA. Jadi memanggil `isConnected()`
     * dari main thread saat penyambungan latar berjalan akan MEMBLOKIR main
     * thread sampai 11 dtk — ANR.
     *
     * Ini menjadi nyata justru karena F8 memindahkan penyambungan ke latar
     * (`connectAsync`): sebelumnya connect selalu sinkron di dalam satu command,
     * sehingga tidak ada `status()` yang berjalan bersamaan.
     *
     * `status()` dan `exec()` (keduanya bisa dipanggil dari main thread) kini
     * HANYA membaca field volatil ini — tanpa kunci, tanpa IO. Otoritasnya
     * dipegang thread IO: di-set true hanya setelah connect sukses, di-set
     * false saat putus/timeout. Bila nilainya keliru positif, `openStream`
     * gagal dan dilaporkan jujur sebagai `adb_disconnected` — bukan sukses palsu.
     */
    @Volatile
    private var linkUp = false

    /** Stream yang sedang dibaca; ditutup paksa saat timeout agar read() lepas. */
    @Volatile
    private var activeStream: AdbStream? = null

    init {
        setApi(android.os.Build.VERSION.SDK_INT)
        // v0.9.6 — KOREKSI IP YANG SALAH (root cause "IP tidak 127.0.0.1").
        //
        // FAKTA DARI SUMBER LIBRARY (libadb-android 3.1.1,
        // AndroidUtils.getHostIpAddress()):
        //     if (SDK >= KITKAT) ipAddress = InetAddress.getLoopbackAddress().getHostAddress();
        // Jadi fungsi itu MEMANG mengembalikan 127.0.0.1 — bukan IP Wi-Fi.
        // v0.9.4 mengira ia membaca "IP Wi-Fi aktual" dan menggantinya ke
        // `setHostAddress(detectedIp)`. Nilainya kebetulan sama di kebanyakan
        // HP, TETAPI:
        //   1. Ia bisa mengembalikan "::1" (IPv6 loopback) yang kemudian
        //      disubstitusi library menjadi "127.0.0.1" — perilaku tidak
        //      deterministik antar-OEM.
        //   2. Di emulator ia mengembalikan "10.0.2.2" (host gateway), BUKAN
        //      loopback — pairing ke 10.0.2.2 akan GAGAL karena adbd tidak
        //      mendengarkan di gateway.
        //   3. Komentar kode lama menyatakan "IP Wi-Fi aktual", menciptakan
        //      keyakinan palsu yang membuat bug berikutnya sulit dilihat.
        //
        // KEBENARAN ARSITEKTUR: agent memasangkan DIRINYA SENDIRI ke adbd yang
        // berjalan di HP yang sama. Alamat yang benar untuk `pair()` adalah
        // LOOPBACK (`127.0.0.1`), bukan IP Wi-Fi — Wi-Fi tidak diperlukan untuk
        // pairing lokal dan justru menambah titik gagal (Wi-Fi mati, AP isolasi
        // klien, subnet berbeda).
        //
        // Untuk CONNECT (after pairing), libadb MEMAKAI mDNS dan MENGABAIKAN
        // nilai ini sepenuhnya — lihat AbsAdbConnectionManager.connectTls():
        // "Host address set by setHostAddress(String) is ignored."
        // Jadi `setHostAddress` HANYA relevan untuk pairing (dan `pair(port)`),
        // bukan untuk jalur connect normal.
        setHostAddress(LOOPBACK_HOST)
        setTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Kita INGIN tahu saat adbd menolak kunci (butuh pairing ulang) supaya
        // bisa melaporkan `adb_auth_failed`, bukan gagal diam-diam.
        setThrowOnUnauthorised(true)
    }

    // ---------------------------------------------------------------- abstraksi

    override fun getPrivateKey(): PrivateKey = keys.privateKey

    override fun getCertificate(): Certificate = keys.certificate

    override fun getDeviceName(): String = DEVICE_NAME

    // -------------------------------------------------------------- PrivilegedShell

    override fun status(): ShellStatus {
        // HANYA field volatil — tidak menyentuh kunci/IO library.
        // Lihat komentar [linkUp] untuk alasan (mencegah ANR main thread).
        val connected = linkUp
        return ShellStatus(
            available = connected,
            paired = paired,
            connected = connected,
            uid = if (connected) cachedUid else -1,
            error = if (connected) "" else notConnectedReason(),
        )
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        // Batas keamanan ditegakkan SEBELUM apa pun dijalankan — sama seperti
        // v0.8.0, daftar putihnya tidak berubah (kontrak §3.1).
        val binary = command.trim().substringBefore(' ').substringAfterLast('/')
        if (binary !in PrivilegedShell.ALLOWED_BINARIES) {
            return ShellResult(
                ok = false, exitCode = -1, stdout = "", stderr = "",
                failure = "blocked: perintah '$binary' tidak ada di daftar izin agent",
            )
        }
        // Cek cepat TANPA kunci library (lihat [linkUp]). Bila nilainya keliru
        // positif, openStream di thread IO akan gagal dan dilaporkan jujur.
        if (!linkUp) {
            return ShellResult(
                ok = false, exitCode = -1, stdout = "", stderr = "",
                failure = notConnectedReason(),
            )
        }
        val future: Future<ShellResult> = io.submit(Callable { execOnIo(command, timeoutMs) })
        return try {
            future.get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Batalkan upaya + tutup stream supaya read() yang menggantung lepas.
            future.cancel(true)
            closeActiveStream()
            ShellResult(
                ok = false, exitCode = -1, stdout = "", stderr = "",
                failure = "adb_timeout: perintah tidak selesai dalam ${timeoutMs}ms",
            )
        } catch (e: Exception) {
            ShellResult(
                ok = false, exitCode = -1, stdout = "", stderr = "",
                failure = "adb_disconnected: ${e.cause?.message ?: e.message ?: "gagal"}",
            )
        }
    }

    // ------------------------------------------------------------------ pairing

    /**
     * Pairing ke adbd dengan kode 6 digit dari layar Debug nirkabel.
     *
     * [host] SELALU `127.0.0.1` (lihat [LOOPBACK_HOST]) — HP memasangkan
     * dirinya sendiri. Jangan diisi IP Wi-Fi; pairing lokal tidak melewati
     * jaringan dan Wi-Fi hanya menambah titik gagal.
     *
     * Blocking sampai selesai atau timeout. Selalu aman dipanggil dari thread
     * apa pun.
     */
    fun pairNow(host: String, port: Int, code: String, timeoutMs: Long = PAIR_TIMEOUT_MS): Boolean {
        val done = runBounded(timeoutMs + GRACE_MS) {
            try {
                pair(host, port, code)
                paired = true
                lastError = ""
                Log.i(TAG, "pairing ADB berhasil ke $host:$port")
                true
            } catch (t: Throwable) {
                paired = false
                lastError = "adb_pair_failed: ${t.message ?: t.javaClass.simpleName}"
                Log.w(TAG, "pairing ADB gagal", t)
                false
            }
        }
        if (done == null) {
            lastError = "adb_pair_failed: pairing tidak selesai dalam ${timeoutMs}ms"
        }
        return done == true
    }

    /**
     * Sambung ke adbd. Port ditemukan otomatis lewat mDNS (TLS connect service).
     *
     * Blocking bagi PEMANGGIL: bodi penyambungan dijalankan di thread IO
     * ([doConnect]) dan pemanggil menunggu paling lama [CONNECT_WORST_CASE_MS].
     *
     * Bila penemuan otomatis gagal (sering dibatasi OEM), pemanggil bisa
     * memakai [connectTo] dengan host+port manual dari layar Debug nirkabel.
     */
    fun connectNow(discoveryTimeoutMs: Long = DISCOVERY_TIMEOUT_MS): Boolean {
        if (linkUp) return true
        return runBounded(CONNECT_WORST_CASE_MS) { doConnect(discoveryTimeoutMs) } == true
    }

    /**
     * Sambung di BELAKANGAN, tanpa menahan pemanggil.
     *
     * BUG YANG DIPERBAIKI (self-deadlock): versi sebelumnya berbunyi
     * `io.execute { connectNow() }` — dan `connectNow` sendiri memakai
     * [runBounded] yang MENYERAHKAN tugas ke executor yang SAMA lalu menunggu.
     * Karena executor-nya satu thread, tugas dalam tidak akan pernah berjalan
     * selagi tugas luar menunggu: hasilnya SELALU timeout ~13 dtk dan
     * dilaporkan gagal. Artinya sesi SETELAH pairing sukses tidak pernah bisa
     * terbentuk. Sekarang [doConnect] dipanggil langsung di dalam tugas itu
     * (tanpa penyerahan bersarang).
     *
     * Dipakai setelah pairing berhasil: `pair` + `connect` secara sinkron bisa
     * melewati batas 30 dtk command agent (`agenthub.DefaultTimeout`).
     */
    fun connectAsync(discoveryTimeoutMs: Long = DISCOVERY_TIMEOUT_MS) {
        io.execute { doConnect(discoveryTimeoutMs) }
    }

    /** Sambung dengan host+port eksplisit (jalur cadangan bila mDNS diblokir). */
    fun connectTo(host: String, port: Int): Boolean {
        if (linkUp) return true
        // Tanpa discovery mDNS, jadi durasi terburuk = socket + grace.
        return runBounded(SOCKET_TIMEOUT_MS + GRACE_MS) { doConnectManual(host, port) } == true
    }

    /**
     * Bodi penyambungan OTOMATIS — WAJIB dijalankan di thread IO.
     *
     * Tidak memakai [runBounded] sendiri (itu tugas pemanggil): inilah yang
     * membuat [connectAsync] tidak lagi menyerahkan tugas bersarang.
     */
    private fun doConnect(discoveryTimeoutMs: Long): Boolean = try {
        val ok = connectTls(appContext, discoveryTimeoutMs)
        if (ok) {
            paired = true
            lastError = ""
            linkUp = true
            Log.i(TAG, "tersambung ke adbd")
        } else {
            linkUp = false
            lastError = "adb_disconnected: koneksi ke adbd tidak terbentuk"
        }
        ok
    } catch (t: Throwable) {
        linkUp = false
        classifyConnectFailure(t)
        false
    }

    /** Bodi penyambungan MANUAL (host+port eksplisit) — WAJIB di thread IO. */
    private fun doConnectManual(host: String, port: Int): Boolean = try {
        val ok = connect(host, port)
        if (ok) {
            paired = true
            lastError = ""
            linkUp = true
        } else {
            linkUp = false
            lastError = "adb_disconnected: koneksi ke $host:$port tidak terbentuk"
        }
        ok
    } catch (t: Throwable) {
        linkUp = false
        classifyConnectFailure(t)
        false
    }

    /**
     * Putuskan sesi (kunci TETAP tersimpan, jadi pairing tidak perlu diulang).
     *
     * TIDAK MEMBLOKIR PEMANGGIL (bug yang diperbaiki di v0.9.2):
     * `disconnect()` pada library meminta `synchronized (mLock)` — kunci yang
     * bisa sedang ditahan `autoConnect()` sampai ~11 dtk. Metode ini dipanggil
     * dari MAIN THREAD (tombol "Putuskan" di tab Setup), jadi memanggilnya
     * langsung berisiko ANR. Sekarang flag status diturunkan SEKETIKA (UI
     * langsung melihat "terputus"), sedangkan penutupan socket sebenarnya
     * dijadwalkan ke thread IO.
     */
    fun disconnectNow() {
        // Status dulu: UI harus langsung jujur bahwa sesi sudah tidak dipakai.
        linkUp = false
        cachedUid = -1
        // Penutupan sebenarnya di thread IO — tidak menahan pemanggil.
        io.execute {
            closeActiveStream()
            try {
                disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect gagal", t)
            }
        }
    }

    /**
     * Bersihkan resource permanen (saat ini TIDAK dipanggil siapa pun; disediakan
     * untuk kelengkapan siklus hidup proses).
     *
     * PENTING: sengaja TIDAK memanggil `close()` dari kelas induk, karena
     * `close()` memusnahkan kunci privat (`PrivateKey.destroy()`), yang membuat
     * koneksi berikutnya mustahil tanpa generate ulang. Kita hanya memutus sesi.
     *
     * Karena [disconnectNow] MENJADWALKAN penutupan ke thread IO, kita beri
     * tenggang singkat agar tugas itu sempat berjalan sebelum executor dimatikan —
     * tanpa itu socket bisa tertinggal terbuka sampai proses berakhir.
     */
    fun shutdown() {
        disconnectNow()
        try {
            io.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        io.shutdownNow()
    }

    /** Dipakai [AdbPairingController] untuk memulihkan status "pernah dipasangkan". */
    fun markPaired(value: Boolean) {
        paired = value
    }

    // ------------------------------------------------------------------ internal

    private fun execOnIo(command: String, timeoutMs: Long): ShellResult {
        var stream: AdbStream? = null
        val result = try {
            stream = openStream("shell:${AdbShellOutput.wrap(command)}")
            activeStream = stream
            val raw = readAll(stream, timeoutMs)
            val (output, code) = AdbShellOutput.parse(raw)
            ShellResult(
                ok = code == 0,
                exitCode = code,
                stdout = output,
                // stderr digabung ke stdout pada layanan `shell:` ADB — lihat
                // AdbShellOutput.wrap() dan catatan kontrak §3.1.
                stderr = "",
                failure = null,
            )
        } catch (t: Throwable) {
            // Kegagalan saat membuka/membaca stream hampir selalu berarti
            // koneksi sudah tidak sehat. Turunkan [linkUp] supaya `status()`
            // melaporkan keadaan yang benar pada polling berikutnya, tanpa
            // perlu memanggil `isConnected()` library (yang memblokir).
            if (t is java.io.IOException) linkUp = false
            ShellResult(
                ok = false, exitCode = -1, stdout = "", stderr = "",
                failure = classifyExecFailure(t),
            )
        } finally {
            activeStream = null
            try {
                stream?.close()
            } catch (_: Throwable) {
                // stream sudah ditutup peer / koneksi sudah hilang — abaikan.
            }
        }
        // Probe uid di LUAR blok finally di atas: stream perintah sudah
        // ditutup lebih dulu, sehingga probe memiliki siklus hidupnya sendiri
        // dan tidak bertabrakan dengan [activeStream]. Hanya dijalankan setelah
        // perintah benar-benar sukses (sesi terbukti hidup).
        if (result.ok && cachedUid == -1) probeUid()
        return result
    }

    /**
     * Baca keluaran sampai penanda exit muncul, stream ditutup peer, atau
     * deadline lewat.
     *
     * Berhenti begitu penanda terbaca (tidak menunggu stream ditutup) menghemat
     * satu round-trip untuk perintah yang sudah jelas selesai.
     */
    private fun readAll(stream: AdbStream, timeoutMs: Long): String {
        val out = StringBuilder()
        val buffer = ByteArray(READ_CHUNK)
        val input = stream.openInputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.append(String(buffer, 0, read, Charsets.UTF_8))
            if (out.contains(AdbShellOutput.MARKER)) break
        }
        return out.toString()
    }

    /**
     * Probe uid efektif — DIPANGGIL HANYA DARI [execOnIo] (thread IO), dan
     * hanya setelah satu perintah sukses pada sesi ini.
     *
     * KENAPA DIHAPUS DARI JALUR CONNECT (v0.9.2): `openStream()` pada library
     * TIDAK menerima parameter timeout dan menahan `synchronized (mLock)`.
     * Bila `adbd` tidak menjawab, panggilan itu bisa MENGGANTUNG dan menempati
     * SATU-SATUNYA thread IO selamanya — seluruh perintah shell berikutnya lalu
     * timeout tanpa henti. Dipanggil dari jalur connect, risikonya tidak
     * sepadan: `adb_uid` hanya INFORMASI (tidak ada logika backend/frontend
     * yang bercabang atas nilainya; sudah diperiksa).
     *
     * Dengan memanggilnya di sini, dua syarat terpenuhi:
     *  1. sesi sudah terbukti hidup (satu perintah sukses sebelumnya), jadi
     *     kemungkinan menggantung jauh lebih kecil;
     *  2. stream-nya didaftarkan ke [activeStream] sehingga [exec] yang
     *     timeout bisa menutupnya paksa — inilah yang tidak dimiliki versi lama.
     *
     * Bila tetap gagal, nilai tetap -1 ("belum diketahui") — jujur, bukan tebakan.
     */
    private fun probeUid() {
        if (cachedUid != -1) return
        cachedUid = try {
            val stream = openStream("shell:id -u")
            activeStream = stream
            try {
                val sb = StringBuilder()
                val buffer = ByteArray(64)
                val input = stream.openInputStream()
                val deadline = System.currentTimeMillis() + UID_PROBE_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sb.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                sb.toString().trim().toIntOrNull() ?: -1
            } finally {
                activeStream = null
                try {
                    stream.close()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "probe uid gagal (dibiarkan -1 = belum diketahui)", t)
            -1
        }
    }

    // CATATAN: helper `safeIsConnected()` DIHAPUS (v0.9.2). Ia memanggil
    // `isConnected()` library yang meminta `synchronized (mLock)` — kunci yang
    // ditahan selama penyambungan berjalan, sehingga pemanggilan dari main
    // thread bisa memblokir sampai ~11 dtk. Diganti field [linkUp] yang
    // volatil dan tidak pernah menyentuh kunci/IO.

    private fun closeActiveStream() {
        try {
            activeStream?.close()
        } catch (_: Throwable) {
            // sudah tertutup — abaikan
        }
    }

    /** Jalankan [block] di thread IO dengan batas waktu; null bila lewat batas. */
    private fun <T> runBounded(timeoutMs: Long, block: () -> T): T? {
        val future: Future<T> = io.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Terjemahkan kegagalan koneksi menjadi kode kontrak §4.1.
     *
     * Sengaja memakai nama kelas (bukan `instanceof`) supaya kelas ini tetap
     * bisa dimuat walau versi library berubah dan exception-nya berganti
     * hierarki — kegagalan pemetaan hanya menurunkan kualitas pesan, bukan
     * membuat agent crash.
     */
    private fun classifyConnectFailure(t: Throwable) {
        val name = t.javaClass.simpleName
        val message = t.message.orEmpty()
        lastError = when {
            name.contains("Authentication") ->
                "adb_auth_failed: kunci agent ditolak adbd - jalankan pairing ulang"
            name.contains("Pairing") -> {
                paired = false
                "adb_not_paired: adbd meminta pairing - jalankan pairing"
            }
            t is InterruptedException || message.contains("Timed out", ignoreCase = true) ->
                "adb_port_unknown: port Debug nirkabel tidak ditemukan - nyalakan Debug nirkabel atau isi host:port manual"
            message.contains("Could not find", ignoreCase = true) ->
                "adb_port_unknown: tidak ada host/port ADB yang ditemukan"
            else -> "adb_disconnected: ${message.ifEmpty { name }}"
        }
        Log.w(TAG, "koneksi ADB gagal: $lastError", t)
    }

    private fun classifyExecFailure(t: Throwable): String {
        val name = t.javaClass.simpleName
        val message = t.message.orEmpty()
        return when {
            name.contains("Pairing") -> {
                paired = false
                "adb_not_paired: adbd meminta pairing saat perintah dikirim"
            }
            name.contains("Authentication") ->
                "adb_auth_failed: kunci agent ditolak adbd"
            name.contains("Interrupted") -> "adb_disconnected: perintah terputus"
            else -> "adb_disconnected: ${message.ifEmpty { name }}"
        }
    }

    private fun notConnectedReason(): String =
        if (lastError.isNotEmpty()) lastError
        else "adb_not_paired: otomasi lanjutan belum dihubungkan di HP ini"
}
