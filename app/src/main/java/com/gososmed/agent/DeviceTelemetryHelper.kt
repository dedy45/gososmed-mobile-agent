package com.gososmed.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager

/**
 * Mengumpulkan data telemetri kesehatan fisik perangkat secara live
 * (Baterai, Suhu, Jaringan, dan deteksi platform sosial media terpasang).
 */
object DeviceTelemetryHelper {

    data class TelemetrySnapshot(
        val batteryPct: Int,
        val isCharging: Boolean,
        val temperatureCelsius: Float,
        val wifiSsid: String,
        val pingLatencyMs: Long,
        val isTiktokInstalled: Boolean,
        val isInstagramInstalled: Boolean,
        val isFacebookInstalled: Boolean,
        val isThreadsInstalled: Boolean,
        val isYoutubeInstalled: Boolean
    )

    fun getSnapshot(context: Context): TelemetrySnapshot {
        // 1. Baterai & Suhu
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempRaw / 10.0f

        // 2. Info Wi-Fi
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wm?.connectionInfo
        val rawSsid = wifiInfo?.ssid?.replace("\"", "").orEmpty()
        val wifiSsid = if (rawSsid.isEmpty() || rawSsid == "<unknown ssid>") "Terhubung Wi-Fi" else rawSsid

        // 3. Latensi Ping real-time
        val pingLatency = AgentWsClient.lastPingLatencyMs

        // 4. Deteksi aplikasi terpasang
        val pm = context.packageManager
        fun isPkgInstalled(pkg: String): Boolean = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        return TelemetrySnapshot(
            batteryPct = batteryPct,
            isCharging = isCharging,
            temperatureCelsius = tempCelsius,
            wifiSsid = wifiSsid,
            pingLatencyMs = pingLatency,
            isTiktokInstalled = isPkgInstalled("com.ss.android.ugc.trill"),
            isInstagramInstalled = isPkgInstalled("com.instagram.android"),
            isFacebookInstalled = isPkgInstalled("com.facebook.katana"),
            isThreadsInstalled = isPkgInstalled("com.instagram.barcelona"),
            isYoutubeInstalled = isPkgInstalled("com.google.android.youtube")
        )
    }
}
