package com.aiwa.widget
import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.BackendOutdatedException
import com.aiwa.bridge.BusyException
import com.aiwa.bridge.LocalClaudeBridge

val SessionIdKey = ActionParameters.Key<String>("sessionId")
val ModelIdKey = ActionParameters.Key<String>("modelId")
val MenuKey = ActionParameters.Key<String>("menu")

// Which dropdown is open in THIS widget instance: "", "sessions" or
// "models". Glance-native per-instance state, so it survives recompositions.
val OpenMenuKey = stringPreferencesKey("openMenu")

private suspend fun closeMenu(context: Context, glanceId: GlanceId) {
    updateAppWidgetState(context, glanceId) { prefs -> prefs[OpenMenuKey] = "" }
    AiwaWidget().update(context, glanceId)
}

// The widget has no message area of its own, so failures go into the
// reply area — the only place a real error can be seen there.
private fun reportWidgetError(context: Context, err: Exception, what: String) {
    val message = when {
        isBackendUnreachable(err) -> autoStartBackendMessage(context)
        err is BackendOutdatedException -> err.message ?: "Backend obsolète"
        else -> "Impossible de $what : ${err.message}"
    }
    AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, lastReply = message) }
}

class ToggleMenuAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val target = parameters[MenuKey] ?: return
        var opened = false
        updateAppWidgetState(context, glanceId) { prefs ->
            opened = prefs[OpenMenuKey] != target
            prefs[OpenMenuKey] = if (opened) target else ""
        }
        AiwaWidget().update(context, glanceId)
        // Opening a menu refreshes the real list behind it first.
        if (opened) {
            BackendSync.refresh(LocalClaudeBridge())
            AiwaWidget().updateAll(context)
        }
    }
}

/** A blank/missing SessionIdKey means "+ Nouvelle session". */
class SelectSessionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val sessionId = parameters[SessionIdKey]?.takeIf { it.isNotBlank() }
        closeMenu(context, glanceId)
        val bridge = LocalClaudeBridge()
        try {
            bridge.selectSession(sessionId)
            // Only wipe the displayed content when the session really
            // changed; the app reloads the real history for a new id.
            AiwaRepository.update {
                if (it.sessionId == sessionId) it
                else it.copy(output = "", lastReply = "", status = AiwaState.Status.READY)
            }
        } catch (err: BusyException) {
            // A message is in flight and the backend refused the switch;
            // the refresh below simply keeps showing the real session.
        } catch (err: Exception) {
            reportWidgetError(context, err, "changer de session")
        }
        BackendSync.refresh(bridge)
        AiwaWidget().updateAll(context)
    }
}

class SelectModelAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val modelId = parameters[ModelIdKey]?.takeIf { it.isNotBlank() }
        closeMenu(context, glanceId)
        val bridge = LocalClaudeBridge()
        try {
            bridge.selectModel(modelId)
        } catch (err: BusyException) {
            // Same as for sessions: the refresh below shows what is real.
        } catch (err: Exception) {
            reportWidgetError(context, err, "changer de modèle")
        }
        BackendSync.refresh(bridge)
        AiwaWidget().updateAll(context)
    }
}

/** Opt-in: opens the current session in the real Claude Code CLI (Termux). */
class OpenInClaudeCodeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val sessionId = AiwaRepository.state.value.sessionId
        closeMenu(context, glanceId)
        if (sessionId == null) return
        openSessionInClaudeCode(context, sessionId).onFailure { failure ->
            AiwaRepository.update {
                it.copy(status = AiwaState.Status.ERROR, lastReply = "Impossible d'ouvrir Termux : ${failure.message}")
            }
        }
        AiwaWidget().updateAll(context)
    }
}
