package com.gososmed.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Membantu pengguna membuka menu perizinan khusus OEM (Xiaomi HyperOS, Samsung, Oppo, Vivo)
 * dalam 1 klik tanpa harus mencari-cari di dalam puluhan submenu Setelan OS.
 */
object OemOptimizationHelper {
    private const val TAG = "GoAgentOEM"

    val manufacturer: String = Build.MANUFACTURER.lowercase()
    val brand: String = Build.BRAND.lowercase()

    val isXiaomi: Boolean = manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    val isSamsung: Boolean = manufacturer.contains("samsung")
    val isOppo: Boolean = manufacturer.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")
    val isVivo: Boolean = manufacturer.contains("vivo") || brand.contains("iqoo")
    val isHuawei: Boolean = manufacturer.contains("huawei") || brand.contains("honor")

    fun getOemName(): String = when {
        isXiaomi -> "Xiaomi / HyperOS"
        isSamsung -> "Samsung"
        isOppo -> "Oppo / Realme"
        isVivo -> "Vivo / iQOO"
        isHuawei -> "Huawei"
        else -> Build.MANUFACTURER
    }

    /**
     * Membuka menu perizinan latar belakang spesifik pabrikan HP (Background Pop-up / Autostart).
     */
    fun openOemBackgroundSettings(context: Context): Boolean {
        val packageName = context.packageName
        val intents = mutableListOf<Intent>()

        when {
            isXiaomi -> {
                // 1) Menu Perizinan Lainnya (Display pop-up windows & Show on lockscreen)
                intents.add(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", packageName)
                })
                // 2) Menu Autostart
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                })
            }
            isSamsung -> {
                // Samsung Device Care / Never sleeping apps
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity")
                })
            }
            isOppo -> {
                // Oppo ColorOS Startup / Background apps
                intents.add(Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
                })
            }
            isVivo -> {
                // Vivo iQOO Whitelist / Background power
                intents.add(Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
                })
            }
            isHuawei -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                })
            }
        }

        // Coba intent OEM satu per satu
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Berhasil membuka menu OEM: ${intent.component}")
                return true
            } catch (t: Throwable) {
                Log.d(TAG, "Intent OEM gagal dicoba: ${intent.component} (${t.message})")
            }
        }

        // Fallback standar Android: App details settings
        return try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
