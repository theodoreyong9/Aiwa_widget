package com.aiwa.widget
import android.content.Context
import android.content.Intent

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

// Runs inside Termux via `bash -c`. Inline on purpose: reported live, a
// script that lives in the repo checkout (backend/open-session.sh) simply
// wasn't there on the phone because the checkout was stale, and Termux
// answered "executable regular file not found" (its error 150). Nothing
// here depends on a file the app itself can't guarantee exists.
//
// Fast-forwards the checkout, restarts the server only if the code
// actually changed (or the app asked: $1 = "restart") so the warm claude
// process isn't thrown away on every app open, and skips launching a
// second server when one already answers. `[a]iwa_server.py` keeps pkill
// from matching this very script's own command line.
private val START_SCRIPT = listOf(
    // What this script does, step by step, in ~/aiwa_start.log (overwritten at
    // each run): its output otherwise goes nowhere, and "the backend did not
    // start" could not be told apart from "Termux never ran the script".
    "exec > \"\$HOME/aiwa_start.log\" 2>&1",
    "echo \"=== \$(date) — Aiwa asked Termux to start the backend (arg: '\$1') ===\"",
    "cd \"\$HOME/aiwa_widget\" || { echo \"no ~/aiwa_widget checkout: run bootstrap.sh once\"; exit 1; }",
    "old=\$(git rev-parse HEAD 2>/dev/null)",
    // Bounded: a slow network must not hold the start back for long.
    "timeout 15 git pull --ff-only -q || echo \"git pull failed or timed out (code \$?), keeping the checkout as is\"",
    "new=\$(git rev-parse HEAD 2>/dev/null)",
    "echo \"checkout \$old -> \$new\"",
    "if [ \"\$old\" != \"\$new\" ] || [ \"\$1\" = \"restart\" ]; then",
    "  pkill -f \"[a]iwa_server.py\" >/dev/null 2>&1",
    // Wait for the old server to be really gone (usually well under a
    // second) instead of a fixed pause.
    "  for i in 1 2 3 4 5 6 7 8; do curl -sf http://127.0.0.1:8787/api/status >/dev/null 2>&1 || break; sleep 0.5; done",
    "fi",
    "if curl -sf http://127.0.0.1:8787/api/status >/dev/null 2>&1; then echo \"already answering\"; exit 0; fi",
    "echo \"starting backend/start.sh (log: ~/aiwa_backend.log)\"",
    "exec bash \"\$HOME/aiwa_widget/backend/start.sh\"",
).joinToString("\n")

/**
 * Starts (and keeps up to date) the Aiwa backend inside Termux via its
 * RUN_COMMAND automation API, in the background.
 *
 * Two real gates this cannot bypass: Android's runtime permission
 * com.termux.permission.RUN_COMMAND, and Termux's own opt-in
 * `allow-external-apps=true` in ~/.termux/termux.properties.
 */
fun startAiwaBackendViaTermux(context: Context, forceRestart: Boolean = false): Result<Unit> = try {
    val intent = Intent(ACTION_RUN_COMMAND).apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", START_SCRIPT, "_", if (forceRestart) "restart" else ""))
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
    }
    context.startService(intent)
    // Only when the backend was known to be down: launching this script while it
    // runs is a no-op, and must not make the widget claim it is starting.
    if (AiwaRepository.state.value.backend == "down") AiwaRepository.markBackendStarting()
    Result.success(Unit)
} catch (err: Exception) {
    Result.failure(err)
}

// LocalClaudeBridge.withClearConnectionError's message always starts
// this way; it arrives wrapped as a plain IllegalStateException.
private const val BACKEND_NOT_REACHABLE_PREFIX = "Backend not reachable"

fun isBackendUnreachable(err: Throwable): Boolean =
    err.message?.startsWith(BACKEND_NOT_REACHABLE_PREFIX) == true

/**
 * Fires the auto-start from the widget/trampoline side, so someone who
 * only ever uses the widget doesn't need to open the app first. A
 * SecurityException (RUN_COMMAND never granted) is caught inside
 * startAiwaBackendViaTermux and surfaced as a clear ask.
 */
fun autoStartBackendMessage(context: Context): String {
    val result = startAiwaBackendViaTermux(context)
    return if (result.isSuccess) {
        "Backend pas démarré — lancement automatique en cours, réessaie dans 10-15 secondes."
    } else {
        "Backend pas démarré et impossible de le lancer depuis le widget (${result.exceptionOrNull()?.message}) — ouvre l'app Aiwa une fois pour autoriser le démarrage automatique."
    }
}
