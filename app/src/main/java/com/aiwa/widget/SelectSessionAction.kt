package com.aiwa.widget
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge

val SessionIdKey = ActionParameters.Key<String>("sessionId")
val SessionPreviewKey = ActionParameters.Key<String>("sessionPreview")

/** Runs when a session row in the widget's own list is tapped —
 * selects it on the backend directly, same as MainActivity's picker,
 * without ever opening the app. Reported live as the actually expected
 * behavior for the widget, not just an app-opening shortcut. */
class SelectSessionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[SessionIdKey]
        val preview = parameters[SessionPreviewKey] ?: id ?: "session"
        try {
            LocalClaudeBridge().selectSession(id)
            AiwaRepository.update { it.copy(session = preview.take(30)) }
        } catch (err: Exception) {
            AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = "Impossible de changer de session : ${err.message}") }
        }
        AiwaWidget().updateAll(context)
    }
}
