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
            // Same fix as MainActivity's identical button: WORKING must
            // stay reserved for an actual in-flight Claude request — it
            // permanently disabled the app's "Envoyer" button here,
            // since nothing ever clears it after a fire-and-forget
            // launch with no real completion signal.
            if (result.isSuccess) {
                it.copy(status = AiwaState.Status.READY, output = "Termux démarré en arrière-plan — laisse-lui quelques secondes puis essaie d'envoyer un message.")
            } else {
                it.copy(status = AiwaState.Status.ERROR, output = "Impossible de lancer Termux : ${result.exceptionOrNull()?.message}")
            }
        }
        AiwaWidget().updateAll(context)
    }
}
