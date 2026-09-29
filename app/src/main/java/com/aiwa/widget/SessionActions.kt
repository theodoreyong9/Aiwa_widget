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

/** modelId null = the CLI's own default. Applies to the next NEW cloud session. */
suspend fun switchModel(context: Context, bridge: ClaudeBridge, modelId: String?) {
    try {
        bridge.selectModel(modelId)
    } catch (err: BusyException) {
        // Same as above: the refresh below shows what is real.
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "changer de modèle"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
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
