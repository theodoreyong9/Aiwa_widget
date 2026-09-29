package com.aiwa.widget
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLAUDE_GITHUB_URL = "https://claude.ai/connect-github"

private fun siteText(state: AiwaState): String = when (state.siteState) {
    "live" -> "Site en ligne : ${state.siteUrl}"
    "waiting" -> "Site pas encore en ligne (${state.siteUrl}) : Claude Code le publie, le lien apparaîtra dans le widget dès que l'adresse répond."
    else -> ""
}

/**
 * Instructions integrated into the conversation. Claude Code does all of
 * it itself (clone, commit, push, deployment, alerts) with its own GitHub
 * access; Aiwa only remembers what the user wants and adds it — in words —
 * to what is sent. The bottom of the card shows that exact text.
 */
@Composable
fun GithubCard(state: AiwaState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { LocalClaudeBridge() }
    var repoText by remember { mutableStateOf("") }
    var extraText by remember(state.extra) { mutableStateOf(state.extra) }
    var preview by remember { mutableStateOf("") }
    // While the card is on screen, follow the backend (the site link
    // appears here as soon as the address answers).
    LaunchedEffect(Unit) {
        while (true) {
            val visible = (context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
            if (visible) BackendSync.refresh(bridge)
            delay(8_000)
        }
    }
    // The exact text Claude Code receives, reloaded whenever a setting changes.
    LaunchedEffect(state.repo, state.pushMain, state.autodeploy, state.notifyAsk, state.extra, state.cloudSessionId) {
        preview = try { bridge.instructions() } catch (err: Exception) { "" }
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Consignes pour Claude Code", style = MaterialTheme.typography.titleMedium)
            Text("Claude Code s'occupe de tout (clone, commit, push, déploiement) avec son propre accès GitHub : Aiwa ajoute seulement ces consignes à la conversation.")
            TextButton(onClick = { openUrl(context, CLAUDE_GITHUB_URL) }) { Text("Autoriser Claude sur GitHub (une fois) ↗") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dépôt : ${state.repo ?: "aucun (chat libre)"}", Modifier.weight(1f))
                Button(onClick = { context.startActivity(Intent(context, RepoPickerActivity::class.java)) }) { Text("Choisir") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repoText,
                    onValueChange = { repoText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("github.com/proprietaire/nom") },
                )
                Button(
                    enabled = repoText.isNotBlank(),
                    onClick = {
                        val text = repoText
                        repoText = ""
                        scope.launch { addRepoFromText(context, bridge, text) }
                    },
                ) { Text("Ajouter") }
            }
            if (state.repo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Push direct sur la branche principale (sinon : une branche de travail)", Modifier.weight(1f))
                    Switch(checked = state.pushMain, onCheckedChange = { next -> scope.launch { switchOptions(context, bridge, pushMain = next) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Déployer avec GitHub Pages (GitHub Actions)", Modifier.weight(1f))
                    Switch(checked = state.autodeploy, onCheckedChange = { next -> scope.launch { switchOptions(context, bridge, autodeploy = next) } })
                }
                val site = siteText(state)
                if (site.isNotEmpty()) Text(site)
                val url = state.siteUrl
                if (state.siteState == "live" && url != null) {
                    Button(onClick = { openUrl(context, url) }) { Text("Ouvrir le site ↗") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Me prévenir (notification push) quand Claude attend une réponse", Modifier.weight(1f))
                Switch(checked = state.notifyAsk, onCheckedChange = { next -> scope.launch { switchOptions(context, bridge, notify = next) } })
            }
            OutlinedTextField(
                value = extraText,
                onValueChange = { extraText = it.take(600) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Consigne perso ajoutée aux messages") },
            )
            Button(
                enabled = extraText.trim() != state.extra,
                onClick = { scope.launch { switchOptions(context, bridge, extra = extraText.trim()) } },
            ) { Text("Enregistrer la consigne") }
            if (preview.isNotBlank()) {
                Text("Ce que Claude Code reçoit avec le prochain message :", style = MaterialTheme.typography.labelMedium)
                Text(preview, style = MaterialTheme.typography.bodySmall)
            }
            val problem = state.githubError
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
        }
    }
}
