package com.gososmed.agent.hiding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gososmed.agent.AgentLog
import com.gososmed.agent.privileged.PrivilegedShell
import com.gososmed.agent.privileged.PrivilegedShellHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Engine Penyamaran Dinamis (Dynamic Anti-Detection Hiding Engine).
 * Diadopsi 1:1 dari Shizuku-Next Hiding.kt.
 *
 * Mengapa ini penting:
 * Banyak aplikasi perbankan (Jenius, BCA, Jago, DANA) memeriksa setting sistem berikut:
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "GoAgentHiding"

    companion object {
        val DEFAULT_PROTECTED_PACKAGES: Set<String> = setOf(
            "com.btpn.dc",                         // Jenius (BTPN)
            "com.bca",                             // BCA mobile
            "com.jago.digitalBanking",              // Bank Jago
            "id.co.bankbkemobile.digitalbank",     // SeaBank
            "id.dana",                             // DANA
            "com.shopeepay.id",                    // ShopeePay
            "com.finaccel.android",                // Kredivo
            "com.binance.dev",                     // Binance
            "com.binance.cloud.tokocrypto",        // Tokocrypto
            "com.paypal.android.p2pmobile",        // PayPal
            "com.wallet.crypto.trustapp"           // Trust Wallet
        )

        @Volatile
        private var instance: DynamicHidingManager? = null

        fun getInstance(context: Context? = null): DynamicHidingManager {
            return instance ?: synchronized(this) {
                instance ?: DynamicHidingManager(
                    store = if (context != null) PrefsHidingStore(context) else MemoryHidingStore()
                ).also { instance = it }
            }
        }
    }

    private fun log(msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) {}
        try { AgentLog.event(msg) } catch (_: Throwable) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) {}
        try { AgentLog.event("WARN: $msg") } catch (_: Throwable) {}
    }

    /**
     * Menyembunyikan satu sinyal (misalnya mematikan status developer options).
     * Nilai asli dicatat sebelum diubah.
     */
    fun hideSignal(signal: Signal, shell: PrivilegedShell = shellProvider(), targetPackage: String = ""): Boolean {
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
        val targetDesc = if (targetPackage.isNotEmpty()) " untuk $targetPackage" else ""
        log("Anti-Deteksi: Sinyal ${signal.name} disembunyikan$targetDesc")
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
        log("Anti-Deteksi: Pengaturan sistem dipulihkan kembali")
        return allOk
    }
    @Volatile
    private var restartedFor: String? = null

    fun restartForLaunch(packageName: String, shell: PrivilegedShell, context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            val component = intent.component?.flattenToShortString() ?: return false
            log("Anti-Deteksi: Memulai ulang $packageName agar membaca setting yang baru disembunyikan...")

            shell.exec("am force-stop $packageName")
            Thread.sleep(200L)
            val res = shell.exec("am start -n $component")
            val success = res.ok && !res.stdout.contains("Error")
            if (success) {
                log("Anti-Deteksi: $packageName berhasil di-restart dengan memori bersih ✓")
            }
            success
        } catch (t: Throwable) {
            logWarn("restartForLaunch error: ${t.message}")
            false
        }
    }

    /**
     * Logika rekonsiliasi otomatis berdasarkan package aplikasi di foreground:
     * - Jika [currentPackage] terdaftar dalam [protectedPackages]: sembunyikan sinyal yang diminta
     *   dan lakukan restart-on-launch sekali agar proses aplikasi membaca memori bersih.
     * - Jika tidak: pulihkan seluruh sinyal yang sempat disembunyikan.
     */
    fun reconcile(
        currentPackage: String?,
        protectedPackages: Set<String> = DEFAULT_PROTECTED_PACKAGES,
        signalsToHide: Set<Signal> = setOf(Signal.DEVELOPER_OPTIONS, Signal.USB_DEBUGGING, Signal.WIRELESS_DEBUGGING),
        shell: PrivilegedShell = shellProvider(),
        context: Context? = null
    ) {
        if (currentPackage != null && currentPackage in protectedPackages) {
            for (sig in signalsToHide) {
                hideSignal(sig, shell, targetPackage = currentPackage)
            }

            if (restartedFor != currentPackage && shell.status().available && context != null) {
                restartedFor = currentPackage
                restartForLaunch(currentPackage, shell, context)
            }
        } else {
            restartedFor = null
            if (store.getAllSavedKeys().isNotEmpty()) {
                restoreAll(shell)
            }
        }
    }

    /**
     * Menjalankan rekonsiliasi secara asinkron di coroutine IO agar tidak memblokir event aksesibilitas.
     */
    fun reconcileAsync(
        currentPackage: String?,
        context: Context? = null,
        protectedPackages: Set<String> = DEFAULT_PROTECTED_PACKAGES
    ) {
        scope.launch {
            reconcile(currentPackage, protectedPackages, context = context)
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
