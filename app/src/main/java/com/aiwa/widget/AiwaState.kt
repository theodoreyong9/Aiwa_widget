package com.aiwa.widget

import android.content.Context
import android.content.SharedPreferences
import com.aiwa.bridge.CloudSessionInfo
import com.aiwa.bridge.SphereInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bump together with BACKEND_VERSION in backend/aiwa_server.py whenever
// the app starts relying on a new backend feature.
const val EXPECTED_BACKEND_VERSION = 16

data class ModelChoice(val id: String?, val label: String)

// The models Claude Code documents (code.claude.com, model-config), with
// their exact ids. The choice is REAL in two places: `claude --model` when a
// new cloud session is created, and `/model <id>` sent to the open session
// (confirmed on a device: the session answers "Set model to ..."). null id =
// the account's default ("/model default", currently Opus 5.5). The Claude
// app's own model chip lagged one step behind /model at first; the user reports
// it follows now (unverified here).
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

// What Claude is asked to produce with the work (the widget's project-type chip, a list).
val DEPLOY_CHOICES = listOf(
    ModelChoice("none", "Aucun déploiement"),
    ModelChoice("pages", "Site web — publié avec GitHub Pages"),
    ModelChoice("android", "Application Android — l'APK dans une release GitHub"),
    ModelChoice("sphere", "Sphère YourMine — écrite par Claude et ouverte dans YourMine (rien sur GitHub)"),
    ModelChoice("aiwa", "Aiwa — une app (contrat) publiée avec ton identité Aiwa, depuis la page du wallet (rien sur GitHub)"),
)

// The three documents in the list the button next to the mic opens while the "Aiwa" mode is on (id = address):
// the yellow paper (the protocol's specification, at the root of aiwa_project), the PDF in Jobber's docs, and
// the 16-page AIWA carousel (aiwa_project/docs).
val DOC_CHOICES = listOf(
    ModelChoice("https://github.com/theodoreyong9/aiwa_project/blob/main/YELLOWPAPER.md", "Yellow paper — la spécification du protocole Aiwa"),
    ModelChoice("https://github.com/theodoreyong9/Jobber/raw/main/docs/value-ontology.pdf", "Jobber — value-ontology (PDF)"),
    ModelChoice("https://github.com/theodoreyong9/aiwa_project/raw/main/docs/AIWA_carousel_16_pages_square_final.pdf", "Carrousel AIWA — 16 pages (PDF)"),
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
    // Other repositories Claude may ALSO work on (checked in the repository picker).
    val extraRepos: List<String> = emptyList(),
    val pushMain: Boolean = true,
    // none / pages (GitHub Pages) / android (the APK as a GitHub release) / sphere (a
    // YourMine sphere sent to the phone, no GitHub).
    val deploy: String = "none",
    val extra: String = "",
    // Claude pinged the relay: it waits for an answer (the widget shows it).
    // alertAt: epoch seconds of the last ping ever received, null = never.
    val waiting: Boolean = false,
    val alertAt: Long? = null,
    val effort: String? = null,
    val siteUrl: String? = null,
    val siteState: String = "off",
    // "site", "apk" or "sphere": what siteUrl is (see SiteInfo).
    val siteKind: String = "site",
    // Whether the CLI is logged in to a Claude account (ok / needed / unknown), whether Claude's
    // cloud environment reaches the relay (ok / untested / pending / missing), and the last
    // sphere Claude sent (null = none yet).
    val claudeLogin: String = "unknown",
    val relayCloud: String = "untested",
    val sphere: SphereInfo? = null,
    // The latest GitHub Actions run of the repository: running / success /
    // failure / none, with the link to that run (null = unknown).
    val ciState: String? = null,
    val ciUrl: String? = null,
    // A new green run the user has not been told about: there is something to look at.
    val ciFresh: Boolean = false,
    // Which workflow / commit / event / author the GitHub verdict is about (the "État d'Aiwa" list says it).
    val ciDetail: String? = null,
    val githubError: String? = null,
    // Whether the local backend answers: "unknown" (not asked yet), "up", "down",
    // or "starting" (Termux was asked to start it, since backendStartedAt).
    val backend: String = "unknown",
    val backendStartedAt: Long = 0L,
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
    private var prefs: SharedPreferences? = null

    fun update(transform: (AiwaState) -> AiwaState) {
        _state.value = transform(_state.value)
    }

    fun markBackendStarting() {
        update { it.copy(backend = "starting", backendStartedAt = System.currentTimeMillis()) }
    }

    /**
     * The last thing the backend told us, restored when the process starts
     * (AiwaApp). Reported live: with the backend down or slow to start, a
     * freshly started process knew nothing — no session, no repository — so
     * the widget lost its "Claude ↗" button until the backend answered.
     * The backend stays the source of truth: the first refresh overwrites this.
     */
    fun restore(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("aiwa_state", Context.MODE_PRIVATE)
        prefs = store
        val raw = store.getString("snapshot", null) ?: return
        try {
            val json = JSONObject(raw)
            fun text(key: String): String? = if (json.isNull(key)) null else json.optString(key).ifEmpty { null }
            val sessions = json.optJSONArray("sessions") ?: JSONArray()
            update {
                it.copy(
                    session = text("session") ?: it.session,
                    cloudSessionId = text("cloud"),
                    lastSessionId = text("last"),
                    cloudSessions = (0 until sessions.length()).map { index ->
                        val entry = sessions.getJSONObject(index)
                        CloudSessionInfo(entry.getString("id"), entry.optString("title"), entry.optString("url"), if (entry.isNull("repo")) null else entry.optString("repo"))
                    },
                    model = text("model"),
                    effort = text("effort"),
                    repo = text("repo"),
                    extraRepos = json.optJSONArray("extraRepos")?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: emptyList(),
                    pushMain = json.optBoolean("pushMain", true),
                    deploy = text("deploy") ?: if (json.optBoolean("autodeploy", false)) "pages" else "none",
                    extra = text("extra") ?: "",
                    siteUrl = text("siteUrl"),
                    siteState = text("siteState") ?: "off",
                    siteKind = text("siteKind") ?: "site",
                    ciState = text("ciState"),
                    ciUrl = text("ciUrl"),
                )
            }
        } catch (err: Exception) {
            // An unreadable snapshot is just ignored.
        }
    }

    fun persist() {
        val store = prefs ?: return
        val s = _state.value
        val json = JSONObject()
            .put("session", s.session).put("cloud", s.cloudSessionId).put("last", s.lastSessionId)
            .put("model", s.model).put("effort", s.effort).put("repo", s.repo).put("extraRepos", JSONArray(s.extraRepos))
            .put("pushMain", s.pushMain).put("deploy", s.deploy).put("extra", s.extra)
            .put("siteUrl", s.siteUrl).put("siteState", s.siteState).put("siteKind", s.siteKind).put("ciState", s.ciState).put("ciUrl", s.ciUrl)
        val sessions = JSONArray()
        s.cloudSessions.forEach { c ->
            sessions.put(JSONObject().put("id", c.id).put("title", c.title).put("url", c.url).put("repo", c.repo))
        }
        json.put("sessions", sessions)
        store.edit().putString("snapshot", json.toString()).apply()
    }
}
