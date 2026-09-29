package com.aiwa.widget
import android.app.Application

/** Runs in every process Aiwa starts (widget, service, activity): brings back the last known state. */
class AiwaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AiwaRepository.restore(this)
    }
}
