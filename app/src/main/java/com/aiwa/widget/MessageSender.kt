package com.aiwa.widget
import com.aiwa.bridge.BusyException
import com.aiwa.bridge.ClaudeBridge

/**
 * The one real send path — shared by MainActivity's "Envoyer"/mic and
 * DictateActivity's own mic, instead of each duplicating the same
 * coroutine. Reported live as the actual cause of a real bug: the
 * widget's mic (DictateActivity) had its own copy of this logic that
 * never fetched/stored the real session id, so the app kept showing
 * the "Aiwa" placeholder forever whenever a message was sent via the
 * widget instead of the app. One shared function means that kind of
 * drift can't happen again.
 */
suspend fun sendAndTrack(bridge: ClaudeBridge, text: String) {
    if (text.isBlank()) return
    try {
        var started = false
        bridge.sendMessage(text).collect { chunk ->
            if (!started) {
                started = true
                // Reported live: this used to reset state UNCONDITIONALLY
                // before even attempting the send — so a busy-rejected
                // attempt (a real request from elsewhere already in
                // flight) still wiped whatever that real request was
                // showing, with nothing to restore it until that request
                // finished — surfacing as "the response is for the
                // wrong/previous input". The bridge now only emits at all
                // once the POST is actually accepted (see
                // LocalClaudeBridge's own comment on this), so reaching
                // here means this specific send is real — safe to reset.
                // Appends rather than replaces: reported live as
                // "je ne vois pas le texte total de la session" — the
                // whole point of the scrollable areas (app + widget) is
                // a real transcript, not just the latest reply.
                AiwaRepository.update { it.copy(status = AiwaState.Status.WORKING, output = it.output + "\n\n🧑 $text\n🤖 ") }
            } else if (chunk.isNotEmpty()) {
                AiwaRepository.update { it.copy(output = it.output + chunk) }
            }
        }
        val realSessionId = try { bridge.currentSessionId() } catch (err: Exception) { null }
        AiwaRepository.update {
            it.copy(
                status = AiwaState.Status.DONE,
                session = realSessionId?.take(8) ?: it.session,
                sessionId = realSessionId ?: it.sessionId,
            )
        }
    } catch (err: BusyException) {
        // Reported live and confirmed via server-side tracing: this is
        // NOT a failure — claude takes ~20-30s before its first token,
        // and an impatient extra send during that silent wait
        // correctly gets this back (a real request IS in flight).
        // Nothing was ever reset above (see the `started` guard), so
        // there is genuinely nothing to undo here.
    } catch (err: Exception) {
        AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = it.output + "\n[erreur: ${err.message}]") }
    }
}
