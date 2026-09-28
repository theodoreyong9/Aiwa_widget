package com.aiwa.widget
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge

val SessionIdKey = ActionParameters.Key<String>("sessionId")
val SessionPreviewKey = ActionParameters.Key<String>("sessionPreview")

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

/**
 * Runs when a row in the widget's own session list is tapped. Reported
 * live: "je veux la liste de mes véritables sessions Claude Code ou
 * bien en créer une nouvelle" (a real list, brought back after an
 * earlier over-simplification) AND "pouvoir cliquer dessus pour
 * l'ouvrir dans Claude Code (l'ouvrir dans l'app Aiwa je m'en fous)".
 *
 * A blank/missing SessionIdKey means "+ Nouvelle session" was tapped —
 * selects a fresh conversation (nothing to open in Termux for that).
 * A real id selects it on the backend (so the mic sends into it
 * either way) AND fires Termux's real RUN_COMMAND to open a real,
 * interactive `claude --resume <id>` terminal directly — the actual
 * Claude Code CLI, not Aiwa's own chat view.
 *
 * HONEST LIMIT: RUN_COMMAND_BACKGROUND=false plus
 * RUN_COMMAND_SESSION_ACTION are what should bring Termux's own session
 * to the foreground per Termux's documented API, but — like every other
 * RUN_COMMAND integration in this project — the exact foregrounding
 * behavior is unverified against a real device.
 */
class OpenSessionInTermuxAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val sessionId = parameters[SessionIdKey]?.takeIf { it.isNotBlank() }
        val preview = parameters[SessionPreviewKey] ?: "nouvelle"
        try {
            LocalClaudeBridge().selectSession(sessionId)
            AiwaRepository.update { it.copy(session = preview.take(30), sessionId = sessionId) }
        } catch (err: Exception) {
            AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = "Impossible de changer de session : ${err.message}") }
        }
        if (sessionId != null) {
            try {
                val intent = Intent(ACTION_RUN_COMMAND).apply {
                    setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                    putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_HOME/aiwa_widget/backend/open-session.sh")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(sessionId))
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "1")
                }
                context.startService(intent)
            } catch (err: Exception) {
                AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = "Impossible d'ouvrir Termux : ${err.message}") }
            }
        }
        AiwaWidget().updateAll(context)
    }
}
