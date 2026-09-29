package com.aiwa.widget
import android.content.Context
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.BackendOutdatedException
import com.aiwa.bridge.BusyException
import com.aiwa.bridge.ClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one real send path — shared by MainActivity's "Envoyer"/mic and
 * DictateActivity's own mic, instead of each duplicating the same
 * coroutine (a duplicated copy once drifted).
 *
 * The message goes to the current Claude Code cloud session (a new one
 * when none is selected). Documented limit: the CLI only queues it — the
 * reply can't be read back — so Aiwa shows no conversation at all: the
 * exchange lives in the Claude app, one tap away ("Claude ↗"). Refused up
 * front while another send is running.
 */
suspend fun sendAndTrack(context: Context, bridge: ClaudeBridge, text: String, toastErrors: Boolean = false) {
    if (text.isBlank()) return
    if (AiwaRepository.state.value.status == AiwaState.Status.WORKING) {
        if (toastErrors) toastOnMain(context, "Un envoi est déjà en cours.")
        return
    }
    AiwaRepository.update { it.copy(status = AiwaState.Status.WORKING, notice = null) }
    // Fire-and-forget: makes sure a widget composition is alive to show
    // the WORKING state and the result.
    CoroutineScope(Dispatchers.Default).launch { AiwaWidget().updateAll(context) }
    var failure: String? = null
    try {
        val result = bridge.sendCloud(text)
        if (!result.ok) failure = "Envoi cloud impossible : ${result.error}"
    } catch (err: BusyException) {
        failure = "Un envoi cloud est déjà en cours."
    } catch (err: Exception) {
        // A widget-only user never opens the app, so the backend's
        // auto-start there never runs — trigger it from here too.
        failure = when {
            isBackendUnreachable(err) -> autoStartBackendMessage(context)
            err is BackendOutdatedException -> err.message ?: "Backend obsolète"
            else -> "[erreur: ${err.message}]"
        }
    }
    AiwaRepository.update {
        it.copy(status = if (failure == null) AiwaState.Status.DONE else AiwaState.Status.ERROR, notice = failure)
    }
    if (toastErrors) toastOnMain(context, failure ?: "Envoyé — réponse dans l'appli Claude")
    BackendSync.refresh(bridge)
}
