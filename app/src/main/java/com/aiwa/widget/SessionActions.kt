package com.aiwa.widget
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.BackendOutdatedException
import com.aiwa.bridge.BusyException
import com.aiwa.bridge.ClaudeBridge
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The widget has no message area, so its errors are toasts. */
fun toastOnMain(context: Context, text: String) {
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).post { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
}

private fun describeFailure(context: Context, err: Exception, what: String): String = when {
    isBackendUnreachable(err) -> autoStartBackendMessage(context)
    err is BackendOutdatedException -> err.message ?: "Backend obsolète"
    else -> "$what : ${err.message}"
}

// Same shape the backend accepts (session_… / cse_… ids).
val CLOUD_ID_IN_TEXT = Regex("(?:session|cse)_[A-Za-z0-9]+")

// A GitHub repository in a link or a git remote, or a plain owner/name —
// the shapes the backend accepts.
val GITHUB_REPO_IN_TEXT = Regex("github\\.com[/:][A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+|^\\s*[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\s*$")

/**
 * The text on the clipboard, if any. Must be called from a foreground
 * activity on the main thread (Android only hands the clipboard to the
 * app in focus).
 */
fun clipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * The one way to change the current cloud session, shared by the widget's
 * picker and the app's dropdown (a duplicated copy would drift, as
 * sendAndTrack's once did). It only ASKS the backend, then re-reads the
 * backend's real state — never a local guess. target "new" = the next
 * message creates a session, otherwise the id of an existing one.
 */
suspend fun switchCloud(context: Context, bridge: ClaudeBridge, target: String) {
    try {
        bridge.selectCloud(target)
        AiwaRepository.update { it.copy(notice = null) }
    } catch (err: BusyException) {
        // Nothing to do: the refresh below shows what is real.
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de changer de session"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/** Adds an existing cloud session from its link/id (copied from the Claude app) and selects it. */
suspend fun addCloudSession(context: Context, bridge: ClaudeBridge, link: String) {
    // A branch name is looked up in the repositories: that can take a few seconds.
    if (!CLOUD_ID_IN_TEXT.containsMatchIn(link)) toastOnMain(context, "Recherche de la session à partir de la branche…")
    try {
        bridge.addCloud(link)
        AiwaRepository.update { it.copy(notice = null) }
    } catch (err: Exception) {
        val message = describeFailure(context, err, "Impossible d'ajouter la session")
        toastOnMain(context, message)
        AiwaRepository.update { it.copy(notice = message) }
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/**
 * modelId null = the CLI's own default. The backend remembers it for the
 * next NEW cloud session (`claude --model`); when a session is already
 * open the choice is also sent to it as `/model <alias>`, the documented
 * way to change a cloud session's model. That message is queued like any
 * other, so the result is confirmed in the Claude app, not here.
 */
suspend fun switchModel(context: Context, bridge: ClaudeBridge, modelId: String?) {
    var accepted = false
    try {
        bridge.selectModel(modelId)
        accepted = true
    } catch (err: BusyException) {
        // Same as above: the refresh below shows what is real.
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de changer de modèle"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
    if (!accepted || AiwaRepository.state.value.cloudSessionId == null) return
    val label = modelLabel(modelId)
    try {
        val result = bridge.sendCloudCommand("/model " + (modelId ?: "default"))
        toastOnMain(
            context,
            if (result.ok) "Modèle « $label » demandé à la session (à confirmer dans Claude ↗)"
            else "Modèle « $label » non transmis à la session : ${result.error}",
        )
    } catch (err: BusyException) {
        toastOnMain(context, "Un envoi est en cours : le modèle « $label » n'a pas été transmis à la session, choisis-le à nouveau.")
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de transmettre le modèle à la session"))
    }
}

/** null = the plain chat. The next message starts a NEW session on that repository. */
suspend fun switchRepo(context: Context, bridge: ClaudeBridge, repo: String?) {
    try {
        bridge.selectRepo(repo)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de changer de dépôt"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/**
 * Changes the instructions integrated into the conversation; only the
 * arguments that are not null. They apply to the next message (and, for
 * the push mode, to the next new session).
 */
suspend fun switchOptions(
    context: Context,
    bridge: ClaudeBridge,
    pushMain: Boolean? = null,
    deploy: String? = null,
    extra: String? = null,
) {
    try {
        bridge.setOptions(pushMain, deploy, extra)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de changer les consignes"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/**
 * Checks or unchecks a repository Claude may ALSO work on (null = none). Told to it
 * with the next message; whether it can reach the repository is up to the platform.
 */
suspend fun switchExtraRepo(context: Context, bridge: ClaudeBridge, repo: String?) {
    try {
        bridge.toggleExtraRepo(repo)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de changer les dépôts supplémentaires"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/**
 * Effort level (null = automatic): remembered for the next NEW session
 * (`claude --effort`) and, when a session is open, sent to it as
 * `/effort <level>` — cloud sessions document /effort as taking its value
 * as an argument, like /model.
 */
suspend fun switchEffort(context: Context, bridge: ClaudeBridge, level: String?) {
    var accepted = false
    try {
        bridge.selectEffort(level)
        accepted = true
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de changer l'effort"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
    if (!accepted || AiwaRepository.state.value.cloudSessionId == null) return
    val label = EFFORT_CHOICES.find { it.id == level }?.label ?: "Auto"
    try {
        val result = bridge.sendCloudCommand("/effort " + (level ?: "auto"))
        toastOnMain(
            context,
            if (result.ok) "Effort « $label » demandé à la session (à confirmer dans Claude ↗)"
            else "Effort « $label » non transmis à la session : ${result.error}",
        )
    } catch (err: BusyException) {
        toastOnMain(context, "Un envoi est en cours : l'effort « $label » n'a pas été transmis à la session, choisis-le à nouveau.")
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible de transmettre l'effort à la session"))
    }
}

/** The clipboard is empty: say what to copy. Anything else is judged by the backend, which explains itself. */
const val EMPTY_CLIPBOARD_FOR_SESSION =
    "Le presse-papiers est vide. Dans Claude Code, copie le lien de la session (claude.ai/code/session_…) ou le nom de sa branche (claude/…)."

/** A repository given as a GitHub link or owner/name (e.g. copied from the browser): remembered and selected. */
suspend fun addRepoFromText(context: Context, bridge: ClaudeBridge, text: String) {
    try {
        bridge.addRepo(text)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "Impossible d'ajouter le dépôt"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/**
 * "Ready to see": opens the result of the work — the site when its address
 * answers, otherwise the latest Actions run (or the Actions page) — and,
 * when that was news (a new green run), tells the backend the user went to look.
 */
fun openResult(context: Context): Boolean {
    val state = AiwaRepository.state.value
    val repo = state.repo ?: return false
    val site = state.siteUrl
    val target = if (state.siteState == "live" && site != null) site else state.ciUrl ?: "https://github.com/$repo/actions"
    val opened = openUrl(context, target)
    if (opened && state.ciFresh) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val bridge = LocalClaudeBridge()
            try { bridge.ciSeen() } catch (err: Exception) { }
            BackendSync.refresh(bridge)
            AiwaWidget().updateAll(appContext)
        }
    }
    return opened
}

/** Puts text on the clipboard. */
fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Aiwa", text))
}

/** Opens a link in whatever handles it (the browser). */
fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (err: ActivityNotFoundException) {
    false
}

private const val CLAUDE_APP_PACKAGE = "com.anthropic.claude"

/**
 * Opens the current cloud session (or, when the next message will start a
 * new one, the last one) where its conversation actually lives:
 * the Claude app (Code tab). Aiwa can't show cloud replies itself, so this
 * is the one way to read them. The app is asked first (it may claim
 * claude.ai/code links); without it, whatever handles the link — the
 * browser — gets it. Returns false when there is no session to open or
 * nothing could open the link.
 */
fun openClaudeApp(context: Context): Boolean {
    val state = AiwaRepository.state.value
    val sessionId = state.cloudSessionId ?: state.lastSessionId ?: return false
    val url = state.cloudSessions.find { it.id == sessionId }?.url
    val target = Uri.parse(url?.takeIf { it.startsWith("https://claude.ai/") } ?: "https://claude.ai/code/$sessionId")
    fun view() = Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = try {
        context.startActivity(view().setPackage(CLAUDE_APP_PACKAGE))
        true
    } catch (err: ActivityNotFoundException) {
        try {
            context.startActivity(view())
            true
        } catch (err2: ActivityNotFoundException) {
            false
        }
    }
    if (opened && state.waiting) {
        // The user goes to answer: the waiting indicator has done its job.
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val bridge = LocalClaudeBridge()
            try { bridge.waitingClear() } catch (err: Exception) { }
            BackendSync.refresh(bridge)
            AiwaWidget().updateAll(appContext)
        }
    }
    return opened
}
