package com.gososmed.agent.privileged

import android.util.Log

data class DisplayMetricsResult(
    val current: String?,
    val isOverridden: Boolean,
    val physical: String?
)

/**
 * Pengendali Display dan Daya Layar Sistem (Diadopsi 1:1 dari Shizuku-Next DisplayScreen & PowerScreen).
 *
 * Mengapa ini penting:
 *  1. Mengatur resolusi (wm size) dan density (wm density) secara native via shell UID 2000.
 *  2. Mengirimkan keyevent tidur (223 / KEYCODE_SLEEP) dan bangun (224 / KEYCODE_WAKEUP).
 *  3. Me-restart SystemUI jika status bar / navbar macet tanpa me-reboot HP (pkill -f com.android.systemui).
 */
object SystemDisplayController {

    private const val TAG = "GoAgent"

    private fun log(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
    }

    /**
     * Parsing output `wm size`.
     * Contoh: "Physical size: 1220x2712\nOverride size: 1080x2400"
     */
    fun parseWmSize(output: String?): DisplayMetricsResult {
        val text = output ?: return DisplayMetricsResult(null, false, null)
        val physical = Regex("Physical size:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        val override = Regex("Override size:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        return DisplayMetricsResult(
            current = override ?: physical,
            isOverridden = override != null,
            physical = physical ?: override
        )
    }

    /**
     * Parsing output `wm density`.
     * Contoh: "Physical density: 440\nOverride density: 400"
     */
    fun parseWmDensity(output: String?): DisplayMetricsResult {
        val text = output ?: return DisplayMetricsResult(null, false, null)
        val physical = Regex("Physical density:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        val override = Regex("Override density:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        return DisplayMetricsResult(
            current = override ?: physical,
            isOverridden = override != null,
            physical = physical ?: override
        )
    }

    fun getDisplaySize(shell: PrivilegedShell = PrivilegedShellHolder.get()): DisplayMetricsResult {
        if (!shell.status().available) return DisplayMetricsResult(null, false, null)
        val res = shell.exec("wm size")
        return parseWmSize(res.stdout)
    }

    fun setDisplaySize(width: Int, height: Int, shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("wm size ${width}x${height}")
        log("setDisplaySize(${width}x${height}) => ok=${res.ok}")
        return res.ok && res.exitCode == 0
    }

    fun resetDisplaySize(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("wm size reset")
        log("resetDisplaySize => ok=${res.ok}")
        return res.ok && res.exitCode == 0
    }

    fun getDensity(shell: PrivilegedShell = PrivilegedShellHolder.get()): DisplayMetricsResult {
        if (!shell.status().available) return DisplayMetricsResult(null, false, null)
        val res = shell.exec("wm density")
        return parseWmDensity(res.stdout)
    }

    fun setDensity(dpi: Int, shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("wm density $dpi")
        log("setDensity($dpi) => ok=${res.ok}")
        return res.ok && res.exitCode == 0
    }

    fun resetDensity(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("wm density reset")
        log("resetDensity => ok=${res.ok}")
        return res.ok && res.exitCode == 0
    }

    /**
     * Menidurkan layar (Clean Sleep) menggunakan keyevent 223 (KEYCODE_SLEEP).
     * Diadopsi dari Shizuku-Next PowerAction.SCREEN_OFF.
     */
    fun sleepScreen(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("input keyevent 223")
        log("sleepScreen => ok=${res.ok}")
        return res.ok
    }

    /**
     * Membangunkan layar menggunakan keyevent 224 (KEYCODE_WAKEUP).
     */
    fun wakeScreen(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("input keyevent 224")
        log("wakeScreen => ok=${res.ok}")
        return res.ok
    }

    /**
     * Me-restart SystemUI jika antarmuka status bar macet tanpa me-reboot HP.
     * Diadopsi dari Shizuku-Next PowerAction.RESTART_SYSTEM_UI.
     */
    fun restartSystemUi(shell: PrivilegedShell = PrivilegedShellHolder.get()): Boolean {
        if (!shell.status().available) return false
        val res = shell.exec("pkill -f com.android.systemui")
        log("restartSystemUi => ok=${res.ok}")
        return res.ok
    }
}
