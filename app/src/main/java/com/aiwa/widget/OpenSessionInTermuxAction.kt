package com.aiwa.widget
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

/**
 * Runs when the widget's own session id is tapped. Reported live as
 * explicitly wanted: "je veux pouvoir cliquer dessus pour l'ouvrir dans
 * Claude Code (l'ouvrir dans l'app Aiwa je m'en fous)" — this selects
 * the session on the backend (so the mic keeps sending into the same
 * conversation either way) AND fires Termux's real RUN_COMMAND to open
 * a REAL, visible, interactive `claude --resume <id>` session directly
 * — the actual Claude Code CLI, not Aiwa's own chat view of it.
 *
 * HONEST LIMIT: RUN_COMMAND_BACKGROUND=false plus
 * RUN_COMMAND_SESSION_ACTION are what should bring Termux's own session
 * to the foreground per Termux's documented API, but — like every other
 * RUN_COMMAND integration in this project — the exact foregrounding
 * behavior is unverified against a real device.
 */
class OpenSessionInTermuxAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val sessionId = AiwaRepository.state.value.sessionId
        if (sessionId == null) {
            AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = "Aucune session active à ouvrir — envoie d'abord un message.") }
            AiwaWidget().updateAll(context)
            return
        }
        try {
            LocalClaudeBridge().selectSession(sessionId)
        } catch (err: Exception) {
            // Non-fatal on its own — still worth trying to open the real
            // terminal even if telling the backend which session is
            // "active" failed (e.g. backend not reachable right now).
        }
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
        AiwaWidget().updateAll(context)
    }
}
