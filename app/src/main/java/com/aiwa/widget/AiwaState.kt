package com.aiwa.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AiwaState(
    // Reported live: the app showed the literal placeholder "Aiwa"
    // while the widget showed "aucune session" for the exact same real
    // state (no session established yet) — two different-looking
    // placeholders for one meaning was confusing ("aucun des deux
    // n'est bon"). One consistent default fixes both call sites at
    // once.
    val session: String = "aucune session",
    // The real, full backend session id — `session` above is only ever
    // a short display label (truncated to 8 chars once a real id is
    // known). Reported live: opening a session directly in Termux
    // needs the REAL id for `claude --resume`, not the truncated
    // label, so this is tracked separately instead of re-deriving it.
    val sessionId: String? = null,
    val status: Status = Status.READY,
    val output: String = "",
    val question: String? = null,
) {
    enum class Status { READY, WORKING, WAITING, DONE, ERROR }
}

/**
 * The one real, process-wide source of truth MainActivity's own Compose
 * UI and the home-screen widget both read from. They run in the same
 * process (no android:process override in the manifest) but are
 * otherwise unconnected — Glance only re-renders when explicitly told
 * to via AiwaWidget().updateAll(context), never just because some
 * unrelated Compose state changed elsewhere in the process. Every real
 * mutation here is meant to be followed by a real updateAll() call —
 * see MainActivity's own use of this for the pattern.
 */
object AiwaRepository {
    private val _state = MutableStateFlow(AiwaState())
    val state: StateFlow<AiwaState> = _state

    fun update(transform: (AiwaState) -> AiwaState) {
        _state.value = transform(_state.value)
    }
}
