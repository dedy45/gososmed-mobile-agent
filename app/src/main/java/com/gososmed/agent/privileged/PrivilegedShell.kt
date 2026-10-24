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
         * Batas keamanan: biner yang DILARANG keras karena berisiko merusak sistem/perangkat (destructive).
         * Semua biner lain (am, pm, dumpsys, getprop, logcat, ps, ls, cat, service, uiautomator,
         * screencap, cp, mv, grep, echo, sleep, dll.) dibuka untuk kebutuhan otomasi agent.
         */
        val BLOCKED_BINARIES = setOf(
            // 1. Reboot & Shutdown (Mencegah HP restart tiba-tiba saat tugas berjalan)
            "reboot", "poweroff", "halt",

            // 2. Format / Raw Disk / Partition Wiper (Mencegah kerusakan storage fisik)
            "dd", "fdisk", "gdisk", "sgdisk", "parted",
            "mkfs", "mke2fs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.f2fs", "mkfs.erofs", "mkfs.exfat", "newfs_msdos", "mkswap",
            "wipe", "secdiscard", "tune2fs", "resize2fs",

            // 3. Firmware Flashing & Tampering Sistem
            "fastboot", "remount", "disable-verity", "enable-verity", "set-verity-state",
            "update_engine", "update_engine_client",

            // 4. Modifikasi Kernel
            "insmod", "rmmod", "modprobe",

            // 5. Root Exploit / Hak Akses Liar
            "su", "magisk", "daemonsu", "run-as",
        )

        /** Pola perintah berbahaya yang dapat merusak sistem atau menghapus seluruh partisi/data. */
        val BLOCKED_PATTERNS = listOf(
            Regex("""\bsys\.powerctl\b"""),
            Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*\s+(/\s*$|/data|/system|/vendor|/product|/sdcard\s*$)""")
        )

        @Deprecated("Gunakan BLOCKED_BINARIES dengan model denylist/blacklist")
        val ALLOWED_BINARIES = setOf(
            "am", "input", "monkey", "pm", "dumpsys", "wm", "settings", "cmd", "rm"
        )

        /**
         * Memvalidasi apakah perintah aman dijalankan.
         * Mengembalikan ShellResult dengan failure="blocked: ..." jika dilarang,
         * atau `null` jika perintah diizinkan untuk dieksekusi.
         */
        fun validateCommand(command: String): ShellResult? {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) {
                return ShellResult(
                    ok = false, exitCode = -1, stdout = "", stderr = "",
                    failure = "command_empty",
                )
            }
            val binary = trimmed.substringBefore(' ').substringAfterLast('/')
            if (binary in BLOCKED_BINARIES) {
                return ShellResult(
                    ok = false, exitCode = -1, stdout = "", stderr = "",
                    failure = "blocked: perintah '$binary' dilarang karena berisiko merusak sistem",
                )
            }
            for (pattern in BLOCKED_PATTERNS) {
                if (pattern.containsMatchIn(trimmed)) {
                    return ShellResult(
                        ok = false, exitCode = -1, stdout = "", stderr = "",
                        failure = "blocked: pola perintah berbahaya terdeteksi",
                    )
                }
            }
            return null
        }
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
        val blocked = PrivilegedShell.validateCommand(command)
        if (blocked != null) return blocked
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
