package com.aiwa.widget
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll

/** Runs when the widget's own "▶ Démarrer" button is tapped — fires
 * Termux's RUN_COMMAND automation directly from the widget, with no
 * Activity ever opening, then reflects whether that at least dispatched
 * successfully (not whether the backend actually came up — this app has
 * no way to know that yet beyond the next real message succeeding or
 * failing against it). */
class StartBackendAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val result = startAiwaBackendViaTermux(context)
        AiwaRepository.update {
            if (result.isSuccess) {
                it.copy(status = AiwaState.Status.WORKING, output = "Démarrage de Termux en arrière-plan…")
            } else {
                it.copy(status = AiwaState.Status.ERROR, output = "Impossible de lancer Termux : ${result.exceptionOrNull()?.message}")
            }
        }
        AiwaWidget().updateAll(context)
    }
}
