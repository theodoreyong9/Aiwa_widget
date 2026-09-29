package com.aiwa.widget

import com.aiwa.bridge.CloudSessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bump together with BACKEND_VERSION in backend/aiwa_server.py whenever
// the app starts relying on a new backend feature.
const val EXPECTED_BACKEND_VERSION = 6

data class ModelChoice(val id: String?, val label: String)

// The aliases Claude Code documents (code.claude.com, model-config). The
// choice is REAL in two places: `claude --model` when a new cloud session
// is created, and `/model <alias>` sent to the current session (cloud
// sessions document /model as taking its value as an argument). null id =
// the CLI's own default ("/model default" in a running session). Fable is
// given by full id: its alias may not exist in an older CLI.
val MODEL_CHOICES = listOf(
    ModelChoice(null, "Auto"),
    ModelChoice("claude-fable-5-1", "Fable"),
    ModelChoice("opus", "Opus"),
    ModelChoice("sonnet", "Sonnet"),
    ModelChoice("haiku", "Haiku"),
    ModelChoice("fable[1m]", "Fable 1M"),
    ModelChoice("opus[1m]", "Opus 1M"),
    ModelChoice("sonnet[1m]", "Sonnet 1M"),
    ModelChoice("opusplan", "Opus plan"),
)

fun modelLabel(id: String?): String = MODEL_CHOICES.find { it.id == id }?.label ?: id ?: "Auto"

data class AiwaState(
    // Display name of the current cloud session, derived by BackendSync
    // from the backend's real answer — never guessed locally.
    val session: String = "Nouvelle session",
    val cloudSessionId: String? = null,
    val cloudSessions: List<CloudSessionInfo> = emptyList(),
    val model: String? = null,
    val backendVersion: Int = 0,
    // Instructions integrated into the conversation — Claude Code does the
    // work itself: the repository new sessions start on (null = the plain
    // chat), push straight to the main branch, publish with GitHub Pages,
    // alert when it needs an answer, plus the user's own text. siteState:
    // off / waiting / live (whether the Pages address answers).
    val repo: String? = null,
    val pushMain: Boolean = true,
    val autodeploy: Boolean = false,
    val notifyAsk: Boolean = false,
    val extra: String = "",
    val siteUrl: String? = null,
    val siteState: String = "off",
    val githubError: String? = null,
    val status: Status = Status.READY,
    // The last problem worth telling the user about, shown in the app only
    // (the widget has no message area: its errors are toasts). There is no
    // conversation text at all: cloud replies can't be read back by a
    // program (see aiwa_server.py), so they are read in the Claude app.
    val notice: String? = null,
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
