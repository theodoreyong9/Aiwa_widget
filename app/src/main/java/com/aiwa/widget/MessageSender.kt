package com.aiwa.widget
import android.content.Context
import com.aiwa.bridge.BackendOutdatedException
import com.aiwa.bridge.BusyException
import com.aiwa.bridge.ClaudeBridge

/**
 * The one real send path — shared by MainActivity's "Envoyer"/mic and
 * DictateActivity's own mic, instead of each duplicating the same
 * coroutine (a duplicated copy once drifted and never captured the real
 * session id).
 */
suspend fun sendAndTrack(context: Context, bridge: ClaudeBridge, text: String) {
    if (text.isBlank()) return
    try {
        var started = false
        bridge.sendMessage(text).collect { chunk ->
            if (!started) {
                started = true
                // The bridge only emits once the POST is actually
                // accepted, so reaching here means this send is real —
                // a busy-rejected attempt never touches any state (it
                // used to wipe the display of the request really in
                // flight, showing "the reply to the previous message").
                // Same layout as the backend's /api/history so the app
                // can swap in the on-disk transcript without a visible jump.
                AiwaRepository.update {
                    val separator = if (it.output.isBlank()) "" else "\n\n"
                    it.copy(
                        status = AiwaState.Status.WORKING,
                        output = it.output + separator + "🧑 $text\n\n🤖 ",
                        lastReply = "",
                    )
                }
            } else if (chunk.isNotEmpty()) {
                AiwaRepository.update { it.copy(output = it.output + chunk, lastReply = it.lastReply + chunk) }
            }
        }
        // DONE first, then sync: BackendSync may reveal a brand-new
        // session id, and the app only reloads history for a session
        // that is not mid-request.
        AiwaRepository.update { it.copy(status = AiwaState.Status.DONE) }
        BackendSync.refresh(bridge)
    } catch (err: BusyException) {
        // Not a failure: a real request is already in flight, and
        // nothing was reset above (see the `started` guard).
    } catch (err: Exception) {
        // A widget-only user never opens the app, so the backend's
        // auto-start there never runs — trigger it from here too.
        val message = when {
            isBackendUnreachable(err) -> autoStartBackendMessage(context)
            err is BackendOutdatedException -> err.message ?: "Backend obsolète"
            else -> "[erreur: ${err.message}]"
        }
        AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = it.output + "\n" + message, lastReply = message) }
    }
}
