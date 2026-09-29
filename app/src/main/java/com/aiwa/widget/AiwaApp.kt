package com.aiwa.widget
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Makes sure the keep-alive service runs: it polls the backend and brings it
 * back through Termux when it is down. Reported live: the backend only came up
 * after opening the Aiwa app, because that was the one thing that started the
 * service — a widget that was merely tapped (or redrawn) never did. Now every
 * way Aiwa's process comes to life calls this. Android may refuse a service
 * start from the background (a widget redraw with no user action); that is
 * harmless, the next tap or app open does it.
 */
fun wakeAiwa(context: Context) {
    try {
        ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
    } catch (err: Exception) {
        // Refused in the background: retried at the next widget tap or app open.
    }
}

/** Runs in every process Aiwa starts (widget, service, activity): brings back the last known state. */
class AiwaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AiwaRepository.restore(this)
        wakeAiwa(this)
    }
}
