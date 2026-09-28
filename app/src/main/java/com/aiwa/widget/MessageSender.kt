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
    AiwaRepository.update { it.copy(status = AiwaState.Status.WORKING, output = "") }
    try {
        bridge.sendMessage(text).collect { chunk -> AiwaRepository.update { it.copy(output = it.output + chunk) } }
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
        // Leaving status untouched (still WORKING, from the real
        // request) avoids the busy-rejection retry-storm bug this
        // project already hit once.
    } catch (err: Exception) {
        AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = it.output + "\n[erreur: ${err.message}]") }
    }
}
