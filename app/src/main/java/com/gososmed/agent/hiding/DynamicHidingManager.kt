package com.gososmed.agent.hiding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gososmed.agent.privileged.PrivilegedShell
import com.gososmed.agent.privileged.PrivilegedShellHolder

/**
 * Engine Penyamaran Dinamis (Dynamic Anti-Detection Hiding Engine).
 * Diadopsi 1:1 dari Shizuku-Next Hiding.kt.
 *
 * Mengapa ini penting:
 * Banyak aplikasi sosial, perbankan, dan pembayaran memeriksa setting sistem berikut:
 *  1. `development_settings_enabled = 1`
 *  2. `adb_enabled = 1` (USB Debugging)
 *  3. `adb_wifi_enabled = 1` (Wireless Debugging)
 *  4. `accessibility_enabled = 1` (Aksesibilitas)
 *
 * Cara Kerja:
 * Saat aplikasi target berada di foreground, sinyal tersebut disembunyikan secara dinamis
 * (diubah ke nilai aman "0"). Nilai asli disimpan di [HidingStore].
 * Begitu aplikasi target ditutup atau berpindah ke Home, nilai asli dikembalikan secara instan.
 */
class DynamicHidingManager(
    private val store: HidingStore = MemoryHidingStore(),
    private val shellProvider: () -> PrivilegedShell = { PrivilegedShellHolder.get() }
) {

    private val tag = "GoAgentHiding"

    private fun log(msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) {}
    }

    /**
     * Menyembunyikan satu sinyal (misalnya mematikan status developer options).
     * Nilai asli dicatat sebelum diubah.
     */
    fun hideSignal(signal: Signal, shell: PrivilegedShell = shellProvider()): Boolean {
        if (!shell.status().available) {
            logWarn("hideSignal: shell tidak tersedia untuk sinyal ${signal.name}")
            return false
        }

        var allSuccess = true
        for (key in signal.keys) {
            // 1. Simpan nilai asli jika belum pernah disimpan
            if (store.getOriginal(key.id) == null) {
                val current = shell.exec("settings get ${key.namespace} ${key.name}")
                if (current.ok && current.stdout.isNotBlank() && current.stdout.trim() != "null") {
                    store.saveOriginal(key.id, current.stdout.trim())
                }
            }

            // 2. Tulis nilai tersembunyi
            val putRes = shell.exec("settings put ${key.namespace} ${key.name} ${key.hiddenValue}")
            if (!putRes.ok || putRes.exitCode != 0) {
                allSuccess = false
            }
        }
        log("hideSignal ${signal.name} => success=$allSuccess")
        return allSuccess
    }

    /**
     * Mengembalikan satu sinyal ke nilai aslinya.
     */
    fun restoreSignal(signal: Signal, shell: PrivilegedShell = shellProvider()): Boolean {
        if (!shell.status().available) {
            logWarn("restoreSignal: shell tidak tersedia untuk sinyal ${signal.name}")
            return false
        }

        var allSuccess = true
        for (key in signal.keys) {
            val original = store.getOriginal(key.id) ?: key.defaultVisibleValue
            if (original != null) {
                val putRes = shell.exec("settings put ${key.namespace} ${key.name} $original")
                if (!putRes.ok || putRes.exitCode != 0) {
                    allSuccess = false
                }
            } else {
                shell.exec("settings delete ${key.namespace} ${key.name}")
            }
            store.clearOriginal(key.id)
        }
        log("restoreSignal ${signal.name} => success=$allSuccess")
        return allSuccess
    }

    /**
     * Mengembalikan seluruh sinyal yang sedang disembunyikan ke pengaturan semula.
     */
    fun restoreAll(shell: PrivilegedShell = shellProvider()): Boolean {
        var allOk = true
        for (signal in Signal.values()) {
            val ok = restoreSignal(signal, shell)
            if (!ok) allOk = false
        }
        store.clearAll()
        log("restoreAll complete => allOk=$allOk")
        return allOk
    }

    /**
     * Logika rekonsiliasi otomatis berdasarkan package aplikasi di foreground:
     * - Jika [currentPackage] terdaftar dalam [protectedPackages]: sembunyikan sinyal yang diminta.
     * - Jika tidak: pulihkan seluruh sinyal yang sempat disembunyikan.
     */
    fun reconcile(
        currentPackage: String?,
        protectedPackages: Set<String>,
        signalsToHide: Set<Signal> = setOf(Signal.DEVELOPER_OPTIONS, Signal.USB_DEBUGGING, Signal.WIRELESS_DEBUGGING),
        shell: PrivilegedShell = shellProvider()
    ) {
        if (currentPackage != null && currentPackage in protectedPackages) {
            for (sig in signalsToHide) {
                hideSignal(sig, shell)
            }
        } else {
            if (store.getAllSavedKeys().isNotEmpty()) {
                restoreAll(shell)
            }
        }
    }

    /**
     * Implementasi penyimpanan persisten berbasis SharedPreferences untuk produksi.
     */
    class PrefsHidingStore(context: Context) : HidingStore {
        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences("hiding_originals", Context.MODE_PRIVATE)

        override fun saveOriginal(keyId: String, value: String) {
            if (!prefs.contains(keyId)) {
                prefs.edit().putString(keyId, value).apply()
            }
        }

        override fun getOriginal(keyId: String): String? = prefs.getString(keyId, null)

        override fun clearOriginal(keyId: String) {
            prefs.edit().remove(keyId).apply()
        }

        override fun getAllSavedKeys(): Set<String> = prefs.all.keys

        override fun clearAll() {
            prefs.edit().clear().apply()
        }
    }
}
