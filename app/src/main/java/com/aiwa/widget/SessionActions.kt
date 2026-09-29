package com.aiwa.widget
import android.content.ClipboardManager
import android.content.Context
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
 * message creates a session, otherwise the id of an existing one. The
 * transcript here is only what Aiwa sent (cloud replies can't be read
 * back), so it is cleared on a real change.
 */
suspend fun switchCloud(context: Context, bridge: ClaudeBridge, target: String) {
    try {
        bridge.selectCloud(target)
        AiwaRepository.update {
            val same = if (target == "new") it.cloudSessionId == null else it.cloudSessionId == target
            if (same) it else it.copy(output = "")
        }
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
        AiwaRepository.update { it.copy(output = "") }
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
