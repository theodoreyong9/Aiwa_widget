package com.aiwa.widget
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

private const val CHANNEL_ID = "aiwa_keepalive"
private const val NOTIFICATION_ID = 1

/**
 * Reported live: the widget's mic (DictateActivity) briefly flashing
 * the app open, on the first tap after Android evicted Aiwa's process
 * for being idle in the background, is real OS process-eviction
 * behavior — not something fixable by changing DictateActivity itself
 * (already tried: Theme.Dictate's windowDisablePreview only hides the
 * cold-start preview window, it can't skip the cold start itself).
 * Explicitly accepted trade-off: a real foreground service is the only
 * way to keep Aiwa's own process resident so that cold start basically
 * never has to happen. This costs a permanent low-priority notification
 * (Android requires one for any foreground service — cannot be hidden
 * entirely) and a small, constant memory/battery footprint. It does
 * nothing itself beyond existing — no work loop, no extra wake locks.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Aiwa actif", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aiwa")
            .setContentText("Prêt pour le micro du widget")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
