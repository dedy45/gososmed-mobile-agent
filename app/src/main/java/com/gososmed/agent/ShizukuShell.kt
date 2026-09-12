package com.gososmed.agent

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.BufferedReader
import rikka.shizuku.Shizuku

/**
 * v0.8.0 — TRANSPORT TIER 1: eksekusi perintah sebagai user `shell` (uid 2000)
 * lewat Shizuku.
 *
 * LATAR BELAKANG (kenapa ini ada):
 * Semua kegagalan produksi 2026-09-11 di 5 platform berakar pada satu hal:
 * AccessibilityService TIDAK punya izin `INJECT_EVENTS` dan TIDAK boleh
 * memulai activity dari background (BAL). Akibatnya:
 *   - startApp "berhasil" tapi layar tetap com.miui.home;
 *   - tap gesture kadang tidak sampai ke app target;
 *   - force-stop mustahil (killBackgroundProcesses bukan force-stop),
 *     sehingga kondisi awal layar tidak pernah deterministik.
 *
 * Jalur shell membebaskan ketiganya sekaligus:
 *   - `am start` dari uid shell BUKAN background activity launch;
 *   - `am force-stop` benar-benar mematikan app (reset deterministik);
 *   - `input tap` memakai InputManager dengan izin INJECT_EVENTS.
 *
 * Pola ini yang dipakai proyek yang terbukti produksi (scrcpy menjalankan
 * server-nya lewat app_process sebagai shell; openatx/atx-agent dan
 * appium-uiautomator2-server memakai uid shell yang sama).
 *
 * DESAIN KRITIS — kelas ini WAJIB tidak pernah membuat agent crash:
 *  - Semua akses ke kelas Shizuku dibungkus try/catch Throwable, termasuk
 *    NoClassDefFoundError, agar APK tetap jalan walau Shizuku tidak dipasang.
 *  - Bila Shizuku tidak siap, pemanggil HARUS jatuh ke jalur accessibility.
 *    Tidak ada satu pun jalur yang menjadi mati karena kelas ini gagal.
 *
 * CATATAN API (fakta, bukan asumsi): sejak Shizuku API 13, `Shizuku.newProcess`
 * dijadikan privat (RikkaApps/Shizuku-API issue #211). Refleksi adalah satu-
 * satunya cara memakainya tanpa membangun UserService + AIDL. Kita memakai
 * refleksi TAPI memperlakukan kegagalannya sebagai kondisi normal (fallback),
 * sehingga bila API benar-benar dihapus di masa depan agent tetap berfungsi
 * dengan jalur accessibility, hanya kembali ke tingkat stabilitas lama.
 */
object ShizukuShell {

    private const val TAG = "GoAgentShizuku"

    /** Kode permintaan izin Shizuku; nilainya bebas, hanya untuk mencocokkan callback. */
    const val PERMISSION_REQUEST_CODE = 4919

    /** Batas aman satu perintah shell. `am force-stop` app berat bisa ~2 dtk. */
    private const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * Perintah yang boleh dijalankan server. Ini BATAS KEAMANAN: agent berjalan
     * di HP pribadi pemilik akun, jadi server tidak boleh bisa menjalankan
     * shell sembarangan (mis. `rm`, `pm uninstall`, membaca file pribadi).
     * Menambah entri di sini adalah keputusan sadar, bukan kebetulan.
     */
    private val ALLOWED_BINARIES = setOf("am", "input", "monkey", "pm", "dumpsys", "wm", "settings", "cmd")

    data class ShellResult(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        /** Diisi bila perintah tidak pernah berjalan (Shizuku mati/ditolak). */
        val failure: String? = null,
    )

    /** true bila layanan Shizuku hidup DAN sudah memberi izin ke agent ini. */
    fun ready(): Boolean = binderAlive() && hasPermission()

    /** true bila aplikasi Shizuku berjalan (belum tentu sudah diizinkan). */
    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        if (!Shizuku.pingBinder()) {
            false
        } else if (Shizuku.isPreV11()) {
            // Shizuku lama memakai model izin berbeda; tidak didukung.
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    } catch (t: Throwable) {
        false
    }

    /** true bila user memilih "Tolak dan jangan tanya lagi" — UI harus
     *  mengarahkan ke aplikasi Shizuku, bukan meminta ulang tanpa hasil. */
    fun permissionPermanentlyDenied(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
            Shizuku.shouldShowRequestPermissionRationale()
    } catch (t: Throwable) {
        false
    }

    /** Memunculkan dialog izin Shizuku. Aman dipanggil walau Shizuku mati. */
    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "requestPermission gagal", t)
        false
    }

    /** uid pemberi hak: 0 = root (Sui), 2000 = adb/shell. -1 bila tidak tahu. */
    fun privilegeUid(): Int = try {
        if (Shizuku.pingBinder()) Shizuku.getUid() else -1
    } catch (t: Throwable) {
        -1
    }

    fun serverVersion(): Int = try {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else -1
    } catch (t: Throwable) {
        -1
    }

    /** true bila paket manajer Shizuku terpasang tapi mungkin belum jalan —
     *  membedakan "belum dipasang" (harus install) dari "belum start"
     *  (harus pairing wireless debugging setelah reboot). */
    fun managerInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Menjalankan satu perintah shell. Mengembalikan hasil apa adanya —
     * TIDAK PERNAH melempar exception ke pemanggil.
     *
     * [command] harus sudah berupa argumen lengkap, mis. "am force-stop com.x".
     * Perintah dijalankan lewat `sh -c` agar kutip/argumen bekerja seperti adb.
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val binary = command.trim().substringBefore(' ').substringAfterLast('/')
        if (binary !in ALLOWED_BINARIES) {
            return ShellResult(false, -1, "", "", "blocked: perintah '$binary' tidak ada di daftar izin agent")
        }
        if (!binderAlive()) {
            return ShellResult(false, -1, "", "", "shizuku_unavailable: layanan Shizuku tidak berjalan")
        }
        if (!hasPermission()) {
            return ShellResult(false, -1, "", "", "shizuku_denied: agent belum diizinkan di aplikasi Shizuku")
        }
        return try {
            val process = newProcess(arrayOf("sh", "-c", command))
                ?: return ShellResult(false, -1, "", "", "shizuku_api_unavailable: Shizuku.newProcess tidak tersedia di versi ini")

            // Watchdog: proses remote yang menggantung tidak boleh membekukan
            // agent. destroy() membebaskan pembacaan stream di bawah.
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    process.destroy()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            watchdog.isDaemon = true
            watchdog.start()

            val out = readAll(process.inputStream?.bufferedReader())
            val err = readAll(process.errorStream?.bufferedReader())
            val code = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
            watchdog.interrupt()
            try {
                process.destroy()
            } catch (t: Throwable) {
                // proses sudah mati — abaikan
            }
            ShellResult(code == 0, code, out, err, null)
        } catch (t: Throwable) {
            Log.w(TAG, "exec gagal: $command", t)
            ShellResult(false, -1, "", "", "shizuku_exec_error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readAll(reader: BufferedReader?): String = try {
        reader?.use { it.readText() }?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /**
     * Refleksi ke `Shizuku.newProcess` (privat sejak API 13). Nilai balik
     * di-cast ke java.lang.Process karena ShizukuRemoteProcess memang turunan
     * Process — jadi kita tidak bergantung pada tipe internal Shizuku.
     *
     * null = API tidak ada; pemanggil memperlakukannya sebagai fallback biasa.
     */
    private fun newProcess(cmd: Array<String>): Process? {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            Log.w(TAG, "newProcess tidak tersedia", t)
            null
        }
    }
}
