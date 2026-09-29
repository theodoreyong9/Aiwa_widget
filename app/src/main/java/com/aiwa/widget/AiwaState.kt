package com.aiwa.widget

import com.aiwa.bridge.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bump together with BACKEND_VERSION in backend/aiwa_server.py whenever
// the app starts relying on a new backend feature.
const val EXPECTED_BACKEND_VERSION = 2

data class ModelChoice(val id: String?, val label: String)

// null id = don't pass --model at all (the CLI's own default). The
// aliases resolve to the latest model of that family inside the CLI;
// Fable is given by full id since its alias may not exist in an older CLI.
val MODEL_CHOICES = listOf(
    ModelChoice(null, "Auto"),
    ModelChoice("claude-fable-5-1", "Fable"),
    ModelChoice("opus", "Opus"),
    ModelChoice("sonnet", "Sonnet"),
    ModelChoice("haiku", "Haiku"),
)

fun modelLabel(id: String?): String = MODEL_CHOICES.find { it.id == id }?.label ?: id ?: "Auto"

data class AiwaState(
    // Display name of the current session, derived by BackendSync from
    // the backend's real answer — never guessed locally.
    val session: String = "Nouvelle session",
    // The real, full backend session id (`session` above is only a label).
    val sessionId: String? = null,
    val model: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val backendVersion: Int = 0,
    val status: Status = Status.READY,
    // The full transcript — shown in the app only. The widget shows no
    // conversation text at all (explicit request).
    val output: String = "",
    val question: String? = null,
) {
    enum class Status { READY, WORKING, WAITING, DONE, ERROR }
}

/**
 * The one process-wide source of truth both the app's Compose UI and the
 * widget read. The widget observes it reactively (collectAsState inside
 * its composition) — it must NOT capture a snapshot in provideGlance:
 * Glance keeps a composition alive for a while and answers updateAll()
 * by recomposing that same composition, so a captured snapshot stays
 * stale (reported live as the widget header not following the app).
 */
object AiwaRepository {
    private val _state = MutableStateFlow(AiwaState())
    val state: StateFlow<AiwaState> = _state

    fun update(transform: (AiwaState) -> AiwaState) {
        _state.value = transform(_state.value)
    }
}
