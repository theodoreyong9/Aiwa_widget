package com.aiwa.widget

import com.aiwa.bridge.CloudSessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bump together with BACKEND_VERSION in backend/aiwa_server.py whenever
// the app starts relying on a new backend feature.
const val EXPECTED_BACKEND_VERSION = 10

data class ModelChoice(val id: String?, val label: String)

// The models Claude Code documents (code.claude.com, model-config), with
// their exact ids. The choice is REAL in two places: `claude --model` when a
// new cloud session is created, and `/model <id>` sent to the open session
// (confirmed on a device: the session answers "Set model to ..."). null id =
// the account's default ("/model default", currently Opus 5.5). The Claude
// app's own model chip does NOT follow /model — Aiwa can't drive it.
val MODEL_CHOICES = listOf(
    ModelChoice(null, "Auto (défaut du compte)"),
    ModelChoice("claude-fable-5-1", "Fable 5.1"),
    ModelChoice("claude-fable-5", "Fable 5"),
    ModelChoice("claude-opus-5-5", "Opus 5.5"),
    ModelChoice("claude-opus-5", "Opus 5"),
    ModelChoice("claude-opus-4-8", "Opus 4.8"),
    ModelChoice("claude-opus-4-7", "Opus 4.7"),
    ModelChoice("claude-opus-4-6", "Opus 4.6"),
    ModelChoice("claude-sonnet-5-5", "Sonnet 5.5"),
    ModelChoice("claude-sonnet-5", "Sonnet 5"),
    ModelChoice("claude-sonnet-4-6", "Sonnet 4.6"),
    ModelChoice("claude-haiku-4-5", "Haiku 4.5"),
    ModelChoice("claude-fable-5-1[1m]", "Fable 5.1 · 1M"),
    ModelChoice("claude-opus-4-8[1m]", "Opus 4.8 · 1M"),
    ModelChoice("claude-sonnet-4-6[1m]", "Sonnet 4.6 · 1M"),
    ModelChoice("opusplan", "Opus plan (Opus, puis Sonnet)"),
)

fun modelLabel(id: String?): String = MODEL_CHOICES.find { it.id == id }?.label?.substringBefore(" (") ?: id ?: "Auto"

// The effort levels `/effort` and `claude --effort` accept (null = automatic).
// Not every model has all of them (Haiku's are not documented).
val EFFORT_CHOICES = listOf(
    ModelChoice(null, "Auto"),
    ModelChoice("low", "Faible"),
    ModelChoice("medium", "Moyen"),
    ModelChoice("high", "Élevé"),
    ModelChoice("xhigh", "Très élevé"),
    ModelChoice("max", "Max"),
)

data class AiwaState(
    // Display name of the current cloud session, derived by BackendSync
    // from the backend's real answer — never guessed locally.
    val session: String = "Nouvelle session",
    val cloudSessionId: String? = null,
    // The session most recently in use: still there when a repository was
    // chosen (the next message starts a NEW session) — "Claude ↗" opens it.
    val lastSessionId: String? = null,
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
    val extra: String = "",
    // Claude pinged the relay: it waits for an answer (the widget shows it).
    // alertAt: epoch seconds of the last ping ever received, null = never.
    val waiting: Boolean = false,
    val alertAt: Long? = null,
    val effort: String? = null,
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
