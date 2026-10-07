package com.gososmed.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Menerima broadcast sistem BOOT_COMPLETED saat HP dinyalakan/reboot (Extreme Stability).
 * Menjalankan AgentForegroundService agar perangkat langsung online, memulihkan koneksi
 * WebSocket ke server GoSosmed, dan mengaktifkan auto-reconnect ADB nirkabel secara otonom
 * tanpa perlu dibuka secara manual oleh pemilik HP.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GoAgentBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Device boot terdeteksi ($action) — memulai AgentForegroundService secara otomatis")
            try {
                val serviceIntent = Intent(context, AgentForegroundService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal menjalankan startForegroundService saat boot: ${t.message}", t)
            }
        }
    }
}
