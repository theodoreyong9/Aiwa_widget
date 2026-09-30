package com.aiwa.widget
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one-time set-up, without opening the app. Asked for: the permission that lets
 * Aiwa start the backend in Termux used to be requested only when the Aiwa app was
 * opened. Two ways in, both from the widget:
 *  - the widget's CONFIGURATION activity (android:configure in aiwa_widget_info.xml):
 *    the launcher opens this by itself right after the widget is put on the screen;
 *  - the widget itself, which says "Autoriser Aiwa" and opens this on a tap while the
 *    Termux permission is missing (a widget already on the screen when it was added).
 * It asks the Termux permission, then the notification one (the lock-screen card),
 * starts the backend, redraws the widget and closes. Whatever the answers, it
 * finishes with RESULT_OK when it is the configuration: the widget is kept, and says
 * what is missing. No UI of its own: only the system's permission dialogs.
 */
class SetupActivity : ComponentActivity() {
    private val termuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askNotifications() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finishSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        if (hasTermuxPermission(this)) askNotifications() else termuxPermission.launch(TERMUX_RUN_COMMAND_PERMISSION)
    }

    private fun askNotifications() {
        val needed = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        if (needed) notificationPermission.launch("android.permission.POST_NOTIFICATIONS") else finishSetup()
    }

    private fun finishSetup() {
        val app = applicationContext
        if (hasTermuxPermission(app)) startAiwaBackendViaTermux(app)
        CoroutineScope(Dispatchers.Default).launch { AiwaWidget().updateAll(app) }
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        finish()
    }
}
