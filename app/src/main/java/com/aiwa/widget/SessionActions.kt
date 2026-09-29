package com.aiwa.widget
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.BackendOutdatedException
import com.aiwa.bridge.BusyException
import com.aiwa.bridge.ClaudeBridge

/** The widget has no message area (explicit request), so its errors are toasts. */
fun toastOnMain(context: Context, text: String) {
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).post { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
}

private fun describeFailure(context: Context, err: Exception, what: String): String = when {
    isBackendUnreachable(err) -> autoStartBackendMessage(context)
    err is BackendOutdatedException -> err.message ?: "Backend obsolète"
    else -> "Impossible de $what : ${err.message}"
}

/**
 * The one way to switch session, shared by the widget's picker and the
 * app's dropdown (a duplicated copy would drift, as sendAndTrack's once
 * did). It only ASKS the backend to switch, then re-reads the backend's
 * real state — never a local guess — so a refused switch (a message is
 * in flight) simply leaves the real session showing. sessionId null =
 * a brand-new session.
 */
suspend fun switchSession(context: Context, bridge: ClaudeBridge, sessionId: String?) {
    try {
        bridge.selectSession(sessionId)
        // Only wipe the transcript when the session really changed;
        // BackendSync then loads the real history of the new one.
        // Leaving cloud mode always counts as a change: the transcript
        // then held a cloud conversation's local echo, not this session.
        AiwaRepository.update { if (!it.cloud && it.sessionId == sessionId) it else it.copy(output = "", historyFor = null) }
    } catch (err: BusyException) {
        // Refused because a request is in flight; the refresh below keeps
        // showing the session that is actually current.
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "changer de session"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/**
 * Switches to cloud mode: target "new" starts a new cloud session with
 * the next message, otherwise the id of an existing cloud session (see
 * CloudSessionInfo). Cloud conversations are read in the Claude app —
 * Aiwa only sends (documented CLI limit) — so the transcript here is
 * cleared, not loaded.
 */
suspend fun switchCloud(context: Context, bridge: ClaudeBridge, target: String) {
    try {
        bridge.selectCloud(target)
        AiwaRepository.update {
            val same = it.cloud && (if (target == "new") it.cloudSessionId == null else it.cloudSessionId == target)
            if (same) it else it.copy(output = "", historyFor = null)
        }
    } catch (err: BusyException) {
        // Nothing to do: the refresh below shows what is real.
    } catch (err: Exception) {
        toastOnMain(context, describeFailure(context, err, "passer en session cloud"))
    }
    BackendSync.refresh(bridge)
    AiwaWidget().updateAll(context)
}

/** modelId null = the CLI's own default. */
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
