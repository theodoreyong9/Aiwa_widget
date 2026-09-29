package com.aiwa.widget
import android.content.Context
import android.content.Intent

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
// Termux's own real home path — fixed by Termux itself, not
// configurable, unlike bootstrap.sh's $HOME which resolves to this
// same value when actually run inside Termux.
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

/**
 * Fires Termux's own real RUN_COMMAND automation intent to start the
 * Aiwa backend (backend/start.sh) inside Termux, in the background,
 * without the user manually opening Termux or typing anything.
 *
 * Two real, separate gates this does NOT and cannot bypass:
 *  - Android's own permission for com.termux.permission.RUN_COMMAND
 *    (declared in the manifest, but still a real runtime grant prompt
 *    the user has to accept once, like any dangerous permission).
 *  - Termux's OWN separate opt-in: "allow-external-apps=true" in
 *    ~/.termux/termux.properties, which the user has to set inside
 *    Termux itself. This exists for real security reasons (any app
 *    could otherwise run arbitrary shell commands in your Termux
 *    environment) and nothing here can set it on the user's behalf.
 *
 * HONEST LIMIT: this whole integration is unverified against a real
 * device — the general RUN_COMMAND mechanism is a well-documented,
 * real Termux feature, but the exact extra keys below have not been
 * confirmed to work end-to-end by this project.
 */
fun startAiwaBackendViaTermux(context: Context): Result<Unit> = try {
    val intent = Intent(ACTION_RUN_COMMAND).apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_HOME/aiwa_widget/backend/start.sh")
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
    }
    context.startService(intent)
    Result.success(Unit)
} catch (err: Exception) {
    Result.failure(err)
}

// LocalClaudeBridge.withClearConnectionError's own message, always
// starting this way — see its comment. Matched by prefix rather than
// exception type since it arrives wrapped as a plain IllegalStateException.
private const val BACKEND_NOT_REACHABLE_PREFIX = "Backend not reachable"

/**
 * Reported live via a widget screenshot: tapping a session row DID
 * show a real error ("Backend not reachable ... it isn't started"),
 * proving the widget's own action code was never the problem — the
 * backend just only ever auto-started from MainActivity's own
 * LaunchedEffect, which never runs for someone using ONLY the widget.
 */
fun isBackendUnreachable(err: Throwable): Boolean =
    err.message?.startsWith(BACKEND_NOT_REACHABLE_PREFIX) == true

/**
 * Same auto-start this widget action or the mic just failed to reach,
 * fired directly from the widget/trampoline side instead of requiring
 * the user to open MainActivity first. Already-granted RUN_COMMAND
 * permission (the normal case, once the app has been opened at least
 * once) makes this silent; a fresh install with the permission never
 * granted yet fails with a SecurityException, caught inside
 * startAiwaBackendViaTermux itself — surfaced here as a clear ask
 * instead of another confusing connection error.
 */
fun autoStartBackendMessage(context: Context): String {
    val result = startAiwaBackendViaTermux(context)
    return if (result.isSuccess) {
        "Backend pas démarré — lancement automatique en cours, réessaie dans 10-15 secondes."
    } else {
        "Backend pas démarré et impossible de le lancer depuis le widget (${result.exceptionOrNull()?.message}) — ouvre l'app Aiwa une fois pour autoriser le démarrage automatique."
    }
}
