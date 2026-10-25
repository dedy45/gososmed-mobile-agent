package com.gososmed.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Menerima broadcast sistem BOOT_COMPLETED dan LOCKED_BOOT_COMPLETED saat HP dinyalakan/reboot (Direct Boot & Extreme Reliability).
 * Diadopsi 1:1 dari Shizuku-Next LockedBootStartService & BootCompleteReceiver.
 *
 * Menjalankan AgentForegroundService agar perangkat langsung online, memulihkan koneksi
 * WebSocket ke server GoSosmed, dan mengaktifkan auto-reconnect ADB nirkabel secara otonom
 * bahkan sebelum PIN/kunci layar dimasukkan pertama kali (pre-unlock state).
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GoAgentBoot"

        fun isUserUnlocked(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(UserManager::class.java)
                um?.isUserUnlocked == true
            } else true
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Device boot terdeteksi ($action) — memulai AgentForegroundService secara otomatis (Direct Boot Aware)")
            try {
                val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isUserUnlocked(context)) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                val serviceIntent = Intent(safeContext, AgentForegroundService::class.java)
                safeContext.startForegroundService(serviceIntent)
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal menjalankan startForegroundService saat boot: ${t.message}", t)
            }
        }
    }
}
