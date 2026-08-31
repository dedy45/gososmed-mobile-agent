package com.gososmed.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Keeps the agent process in the FOREGROUND.
 *
 * This is required for two reasons:
 *  1. Android 12+ blocks background BroadcastReceiver execution — without a
 *     foreground service, `adb shell am broadcast ... cmd=dump` would be
 *     rejected with "Background execution not allowed".
 *  2. Production shape: the agent is a persistent device-side service (BYOD
 *     model), NOT an Activity. The service keeps the WS connection and the
 *     AccessibilityService alive even when the user is in another app.
 */
class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "GoAgent"
        private const val CHANNEL_ID = "agent"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ForegroundService created")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GoSosmed Agent", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GoSosmed Agent")
            .setContentText("Perangkat siap di-drive dari server")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system kills us, restart with null intent so
        // the agent keeps serving (auto-reconnect in AgentWsClient handles
        // the WS re-establishment).
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ForegroundService destroyed")
        super.onDestroy()
    }
}
