package com.aiwa.widget
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.LoginStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Connecter Claude": logs the CLI in to a Claude account without opening Termux. The
 * backend runs `claude auth login` and hands over the page it prints; this window opens
 * it in the browser, where the user approves, and the page shows a CODE (the redirect goes
 * to platform.claude.com, not to the phone — seen with the real CLI). The code is taken
 * from the clipboard when the user comes back — and submitted at once when it is the one
 * this login expects (it ends with the login's own `state`) — or pasted by hand.
 *
 * It stays in the recent apps (no excludeFromRecents) and is single-task: the user leaves
 * for the browser and has to come back to the SAME window, whose login is still waiting
 * (the widget's status line and the lock-screen card open it again too).
 */
class ClaudeLoginActivity : ComponentActivity() {
    // Counts each time this window gets the focus: the moment Android lets an app read the
    // clipboard, e.g. right after the user copied the code in the browser.
    private val focusTick = mutableIntStateOf(0)
    private var connected = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent { LoginWindow(focusTick.intValue, onConnected = { connected = true }, onClose = { finish() }) }
    }

    override fun onDestroy() {
        // Closing the window ends a login still waiting for its code.
        if (isFinishing && !connected) {
            CoroutineScope(Dispatchers.IO).launch { try { LocalClaudeBridge().claudeLoginCancel() } catch (err: Exception) { } }
        }
        super.onDestroy()
    }
}

// What the login page shows: one string, no spaces (the backend refuses anything else anyway).
private fun looksLikeCode(text: String) = text.length in 20..2000 && text.none { it.isWhitespace() } && !text.startsWith("http")

private fun friendly(message: String): String =
    if (message.contains("400") || message.contains("invalid", ignoreCase = true) || message.contains("expired", ignoreCase = true)) {
        "Code refusé ou expiré (le CLI dit : $message). Touche « Recommencer » pour une nouvelle page."
    } else {
        message
    }

@Composable
private fun LoginWindow(focusTick: Int, onConnected: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val bridge = remember { LocalClaudeBridge() }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<LoginStep?>(null) }
    var busy by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    // What the clipboard held when the page was opened: a code is only taken from it when it changed.
    var baseline by remember { mutableStateOf<String?>(null) }

    suspend fun connectedNow() {
        connected = true
        busy = false
        onConnected()
        BackendSync.refresh(bridge)
        AiwaWidget().updateAll(app)
        delay(1200)
        onClose()
    }

    suspend fun begin() {
        busy = true
        note = null
        code = ""
        val next = try { bridge.claudeLoginStart() } catch (err: Exception) { LoginStep("failed", null, err.message ?: "erreur") }
        step = next
        busy = false
        val url = next.url
        if (next.phase == "url" && url != null) {
            baseline = clipboardText(context)
            if (!openUrl(context, url)) note = "Aucun navigateur pour ouvrir la page : touche « Copier le lien » et ouvre-la toi-même."
        }
    }

    suspend fun submit() {
        if (busy) return
        busy = true
        note = null
        val next = try { bridge.claudeLoginCode(code.trim()) } catch (err: Exception) { LoginStep("failed", null, err.message ?: "erreur") }
        step = next
        busy = false
        if (next.phase == "done") connectedNow() else if (next.message.isNotEmpty()) note = friendly(next.message)
    }

    LaunchedEffect(Unit) {
        try { ensureBackend(app, bridge) } catch (err: Exception) { }
        val logged = try { bridge.claudeCheck() } catch (err: Exception) { "unknown" }
        if (logged == "ok") {
            connectedNow()
            return@LaunchedEffect
        }
        // Back from the browser through the recent apps, or a second tap on the widget: the login
        // that is waiting for its code is the one to go on with, not a new one.
        val running = try { bridge.claudeLoginState() } catch (err: Exception) { null }
        if (running != null && running.phase == "url" && running.url != null) {
            step = running
            busy = false
        } else {
            begin()
        }
    }

    LaunchedEffect(focusTick) {
        val current = step ?: return@LaunchedEffect
        if (current.phase != "url" || busy) return@LaunchedEffect
        val text = clipboardText(context)?.trim() ?: return@LaunchedEffect
        if (text == baseline || text == code || !looksLikeCode(text)) return@LaunchedEffect
        code = text
        val state = current.url?.let { Uri.parse(it).getQueryParameter("state") }
        if (state != null && text.endsWith("#$state")) submit()
    }

    MaterialTheme {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                // Taps inside the card must not reach the scrim, which closes the window.
                modifier = Modifier.padding(24.dp).fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connecter Claude", style = MaterialTheme.typography.titleMedium)
                    val current = step
                    when {
                        connected -> Text("✓ Claude est connecté. Cette fenêtre se ferme.")
                        current == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Préparation de la connexion…")
                        }
                        current.phase == "url" || current.phase == "checking" -> {
                            Text(
                                "1. Une page s'est ouverte : connecte-toi à Claude et appuie sur « Autoriser ».\n" +
                                    "2. Copie le code qu'elle affiche.\n" +
                                    "3. Reviens ici : le code est repris tout seul (sinon, colle-le ci-dessous).",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { current.url?.let { openUrl(context, it) } }, modifier = Modifier.weight(1f)) { Text("Rouvrir la page") }
                                OutlinedButton(
                                    onClick = {
                                        current.url?.let { copyToClipboard(context, it) }
                                        toastOnMain(context, "Lien copié")
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Copier le lien") }
                            }
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                singleLine = true,
                                enabled = !busy,
                                label = { Text("Code affiché par la page") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { clipboardText(context)?.trim()?.let { code = it } },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Coller") }
                                Button(onClick = { scope.launch { submit() } }, enabled = code.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                                    Text(if (busy) "Vérification…" else "Valider")
                                }
                            }
                            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        else -> {
                            Text(note ?: current.message.ifEmpty { "La connexion n'a pas abouti." }, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { scope.launch { begin() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Recommencer") }
                        }
                    }
                }
            }
        }
    }
}
