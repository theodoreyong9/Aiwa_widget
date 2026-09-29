package com.aiwa.widget
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * nothing itself beyond existing — no extra wake locks.
 *
 * One small job since: every few seconds it asks the local backend for its
 * state and redraws the widget when something the widget shows has changed
 * (Claude waiting for an answer, the site going live, the current session,
 * the backend going down or starting). It also restarts the backend, through
 * Termux, when it finds it down, or older than this app expects. Every way
 * Aiwa's process starts (wakeAiwa in AiwaApp.kt) starts this service.
 * Without it the widget only ever refreshed when it was tapped.
 */
class KeepAliveService : Service() {
    private var poller: Job? = null

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
        if (poller?.isActive != true) poller = CoroutineScope(Dispatchers.IO).launch { pollBackend() }
    }

    // Keeps the widget honest: the backend is the source of truth for what
    // it shows, and nothing else tells the widget that Claude started to wait.
    private suspend fun pollBackend() {
        val bridge = LocalClaudeBridge()
        var shown = widgetKey()
        var lastLaunch = 0L
        var versionKicked = false
        while (true) {
            try {
                BackendSync.refresh(bridge)
                // An older server still answers after an app update (nothing opened
                // the app to replace it): update and restart it, once.
                val current = AiwaRepository.state.value
                if (!versionKicked && current.backend == "up" && current.backendVersion in 1 until EXPECTED_BACKEND_VERSION) {
                    versionKicked = true
                    lastLaunch = System.currentTimeMillis()
                    startAiwaBackendViaTermux(applicationContext, forceRestart = true)
                    AiwaRepository.markBackendStarting()
                }
                // Down: bring it back on its own (at most every 90 s), so it is
                // usually up already when you tap — a cold start takes a while.
                if (AiwaRepository.state.value.backend == "down" && System.currentTimeMillis() - lastLaunch > 90_000) {
                    lastLaunch = System.currentTimeMillis()
                    startAiwaBackendViaTermux(applicationContext)
                }
                val now = widgetKey()
                if (now != shown) {
                    shown = now
                    AiwaWidget().updateAll(applicationContext)
                }
            } catch (err: Exception) {
                // Backend not up (yet): try again next round.
            }
            delay(10_000)
        }
    }

    private fun widgetKey(): List<Any?> {
        val state = AiwaRepository.state.value
        return listOf(state.backend, state.waiting, state.ciFresh, state.ciState, state.siteState, state.cloudSessionId, state.repo, state.model, state.pushMain, state.autodeploy)
    }

    override fun onDestroy() {
        poller?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
