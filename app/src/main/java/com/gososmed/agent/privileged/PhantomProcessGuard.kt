package com.gososmed.agent.privileged

import android.util.Log

/**
 * Penonaktif Phantom Process Killer Android 12+ (Diadopsi 1:1 dari Shizuku-Next PhantomProcessKiller).
 *
 * Mengapa ini krusial:
 * Android 12+ secara agresif memantau proses background yang dijalankan oleh shell/aplikasi.
 * Jika proses melebihi batas atau memakan siklus CPU, sistem operasi mengirimkan `SIGKILL`
 * tanpa jejak error di log aplikasi.
 *
 * Solusi:
 * Mengunci setting sistem global `settings_enable_monitor_phantom_procs = 0` dan menaikkan
 * `max_phantom_processes = 2147483647` melalui shell UID 2000.
 */
object PhantomProcessGuard {

    private const val TAG = "GoAgent"

    internal const val SETTING_MONITOR = "settings_enable_monitor_phantom_procs"

    const val DISABLE_COMMAND = "settings put global $SETTING_MONITOR 0; device_config put activity_manager max_phantom_processes 2147483647"
    const val RESTORE_COMMAND = "settings delete global $SETTING_MONITOR; device_config delete activity_manager max_phantom_processes"

    /**
     * Memeriksa apakah nilai teks mengindikasikan monitor phantom process dimatikan.
     */
    fun parseIsDisabled(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return false
        return normalized in setOf("0", "false")
    }

    private fun log(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
    }
    fun disable(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) {
            logWarn("PhantomProcessGuard: shell tidak tersedia untuk disable")
            return false
        }
        val res = shell.exec(DISABLE_COMMAND)
        val success = res.ok && res.exitCode == 0
        log("PhantomProcessGuard: disable phantom process killer => ok=$success")
        return success
    }

    fun restore(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) {
            logWarn("PhantomProcessGuard: shell tidak tersedia untuk restore")
            return false
        }
        val res = shell.exec(RESTORE_COMMAND)
        val success = res.ok && res.exitCode == 0
        log("PhantomProcessGuard: restore phantom process killer => ok=$success")
        return success
    }
    fun isMonitorDisabled(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("settings get global $SETTING_MONITOR")
        return res.ok && parseIsDisabled(res.stdout)
    }
}
