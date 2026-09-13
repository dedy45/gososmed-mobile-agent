package com.gososmed.agent.privileged

/**
 * v0.9.0 — SATU-SATUNYA pintu ke transport shell (uid 2000).
 *
 * KENAPA ABSTRAKSI INI ADA:
 * v0.8.0 memanggil Shizuku langsung dari banyak tempat
 * (`AgentAccessibilityService`). Cara itu membuat transport tidak bisa diganti
 * tanpa menyentuh pemanggil, dan membuat penghapusan Shizuku menyebar ke
 * seluruh berkas. Sekarang pemanggil hanya tahu antarmuka ini.
 *
 * Implementasi:
 *  - [AdbLocalShell] (F3) — klien ADB lokal, transport produksi v0.9.0.
 *  - [UnavailableShell] — dipakai bila belum ada implementasi; melaporkan
 *    ketidaktersediaan secara JUJUR, bukan gagal senyap.
 *
 * PRINSIP YANG DIPEGANG:
 *  1. Tidak pernah melempar exception ke pemanggil. Kegagalan dikembalikan
 *     sebagai [ShellResult.failure] berisi kode `adb_*`.
 *  2. Tidak pernah melaporkan sukses palsu. `ok` hanya true bila perintah
 *     benar-benar dijalankan dan exit code 0.
 *  3. Tier 2 (accessibility) tetap jalur utama; kelas ini hanya menambah
 *     determinisme bila tersedia.
 */
interface PrivilegedShell {

    /** Status transport saat ini. Dipakai UI dan `capabilities`. */
    fun status(): ShellStatus

    /**
     * Jalankan satu perintah shell.
     *
     * [command] harus berupa argumen lengkap, mis. `am force-stop com.x`.
     * Perintah yang binernya di luar [ALLOWED_BINARIES] DITOLAK sebelum
     * dijalankan — ini batas keamanan, bukan saran.
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult

    companion object {
        /** Batas aman satu perintah. `am force-stop` app berat bisa ~2 dtk. */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /**
         * Batas keamanan: biner yang boleh dijalankan server.
         *
         * Agent berjalan di HP pribadi pemilik akun, jadi server TIDAK BOLEH
         * menjalankan shell sembarangan (mis. `rm`, `pm uninstall`, membaca
         * berkas pribadi). Menambah entri di sini adalah keputusan sadar.
         *
         * Daftar ini TIDAK berubah dari v0.8.0 — kontrak §3.1.
         */
        val ALLOWED_BINARIES = setOf(
            "am", "input", "monkey", "pm", "dumpsys", "wm", "settings", "cmd"
        )
    }
}

/**
 * Status transport shell. Nama field sengaja netral (`adb_*`) supaya tidak
 * terikat pada merek implementasi — kontrak §5.
 *
 * @param available  true bila perintah shell BISA dijalankan sekarang.
 * @param paired     kunci sudah diotorisasi (pairing pernah berhasil).
 * @param connected  sesi sedang hidup.
 * @param uid        2000 = hak adb/shell, 0 = root, -1 = belum terhubung.
 * @param error      kode `adb_*` bila tidak tersedia; "" bila sehat.
 */
data class ShellStatus(
    val available: Boolean,
    val paired: Boolean,
    val connected: Boolean,
    val uid: Int,
    val error: String,
)

/**
 * Hasil satu perintah shell.
 *
 * @param failure diisi bila perintah TIDAK PERNAH berjalan (belum pair,
 *   sesi putus, biner diblokir). Berisi kode `adb_*` atau `blocked`.
 */
data class ShellResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val failure: String? = null,
)

/**
 * Implementasi jujur "belum tersedia".
 *
 * Dipakai selama F2 (Shizuku sudah dihapus, ADB belum dibangun di F3).
 * Perilakunya SENGAJA sama dengan kegagalan nyata: pemanggil harus sudah
 * menangani kondisi "shell tidak ada" sejak sekarang, sehingga saat F3
 * memasang implementasi nyata tidak ada jalur baru yang belum diuji.
 *
 * Kode error yang dikembalikan: `adb_not_paired` — sesuai kontrak §4.1,
 * artinya "otomasi lanjutan belum dihubungkan", bukan kerusakan.
 */
object UnavailableShell : PrivilegedShell {

    override fun status(): ShellStatus = ShellStatus(
        available = false,
        paired = false,
        connected = false,
        uid = -1,
        error = "adb_not_paired",
    )

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        // Whitelist tetap ditegakkan walau transport tidak ada, supaya pesan
        // "blocked" tidak berubah menjadi "adb_not_paired" saat F3 dipasang.
        val binary = command.trim().substringBefore(' ').substringAfterLast('/')
        if (binary !in PrivilegedShell.ALLOWED_BINARIES) {
            return ShellResult(
                ok = false, exitCode = -1, stdout = "", stderr = "",
                failure = "blocked: perintah '$binary' tidak ada di daftar izin agent",
            )
        }
        return ShellResult(
            ok = false, exitCode = -1, stdout = "", stderr = "",
            failure = "adb_not_paired: otomasi lanjutan belum dihubungkan di HP ini",
        )
    }
}

/**
 * Pemegang implementasi aktif. Satu titik ganti: F3 memanggil
 * [install] dengan `AdbLocalShell`, dan seluruh pemanggil ikut berubah
 * tanpa diedit.
 *
 * `@Volatile` karena ditulis dari UI/service thread dan dibaca dari thread
 * reader OkHttp (pola sama dengan `AgentWsClient.pairingCode`).
 */
object PrivilegedShellHolder {
    @Volatile
    private var impl: PrivilegedShell = UnavailableShell

    fun get(): PrivilegedShell = impl

    fun install(shell: PrivilegedShell) {
        impl = shell
    }

    /** Kembalikan ke kondisi "belum tersedia" (mis. saat sesi diputus). */
    fun reset() {
        impl = UnavailableShell
    }
}
