package com.aiwa.widget
import android.content.ActivityNotFoundException
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

/** The widget has no message area, so its errors are toasts. */
fun toastOnMain(context: Context, text: String) {
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).post { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
}

private fun describeFailure(context: Context, err: Exception, what: String): String = when {
    isBackendUnreachable(err) -> autoStartBackendMessage(context)
    err is BackendOutdatedException -> err.message ?: "Backend obsolète"
    else -> "Impossible de $what : ${err.message}"
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
        toastOnMain(context, describeFailure(context, err, "changer de session"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/** Adds an existing cloud session from its link/id (copied from the Claude app) and selects it. */
suspend fun addCloudSession(context: Context, bridge: ClaudeBridge, link: String) {
    try {
        bridge.addCloud(link)
        AiwaRepository.update { it.copy(notice = null) }
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "ajouter la session"))
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
        toastOnMain(context, describeFailure(context, err, "changer de modèle"))
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
        toastOnMain(context, describeFailure(context, err, "transmettre le modèle à la session"))
    }
}

/** null = the plain chat. The next message starts a NEW session on that repository. */
suspend fun switchRepo(context: Context, bridge: ClaudeBridge, repo: String?) {
    try {
        bridge.selectRepo(repo)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "changer de dépôt"))
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
    autodeploy: Boolean? = null,
    notify: Boolean? = null,
    extra: String? = null,
) {
    try {
        bridge.setOptions(pushMain, autodeploy, notify, extra)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "changer les consignes"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/** A repository given as a GitHub link or owner/name (e.g. copied from the browser): remembered and selected. */
suspend fun addRepoFromText(context: Context, bridge: ClaudeBridge, text: String) {
    try {
        bridge.addRepo(text)
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "ajouter le dépôt"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
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
 * Opens the current cloud session where its conversation actually lives:
 * the Claude app (Code tab). Aiwa can't show cloud replies itself, so this
 * is the one way to read them. The app is asked first (it may claim
 * claude.ai/code links); without it, whatever handles the link — the
 * browser — gets it. Returns false when there is no session to open or
 * nothing could open the link.
 */
fun openClaudeApp(context: Context): Boolean {
    val state = AiwaRepository.state.value
    val sessionId = state.cloudSessionId ?: return false
    val url = state.cloudSessions.find { it.id == sessionId }?.url
    val target = Uri.parse(url?.takeIf { it.startsWith("https://claude.ai/") } ?: "https://claude.ai/code/$sessionId")
    fun view() = Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
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
}
