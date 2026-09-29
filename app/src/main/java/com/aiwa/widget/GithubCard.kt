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
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLAUDE_GITHUB_URL = "https://claude.ai/connect-github"
private const val CLAUDE_CODE_URL = "https://claude.ai/code"

// "il y a 3 min" — when Claude last pinged the relay (epoch seconds), or that it never did.
private fun alertText(state: AiwaState): String {
    val at = state.alertAt ?: return "Aucun ping reçu pour l'instant."
    val seconds = (System.currentTimeMillis() / 1000 - at).coerceAtLeast(0)
    val ago = when {
        seconds < 90 -> "à l'instant"
        seconds < 3600 -> "il y a ${seconds / 60} min"
        seconds < 86400 -> "il y a ${seconds / 3600} h"
        else -> "il y a ${seconds / 86400} j"
    }
    return "Dernier ping reçu : $ago."
}

private fun siteText(state: AiwaState): String = when (state.siteState) {
    "live" -> "En ligne : l'adresse répond."
    "waiting" -> "Pas de réponse pour l'instant (pas encore déployé, ou déploiement en cours)."
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
    var manual by remember { mutableStateOf(false) }
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
    LaunchedEffect(state.repo, state.pushMain, state.autodeploy, state.extra, state.cloudSessionId) {
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
            // The list in the widget is discovered on its own; this is only for
            // a repository that isn't in it (a private one, for instance).
            TextButton(onClick = { manual = !manual }) { Text(if (manual) "Masquer la saisie" else "Un dépôt absent de la liste ?") }
            if (manual) {
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
            }
            if (state.repo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Push direct sur la branche principale", Modifier.weight(1f))
                    Switch(checked = state.pushMain, onCheckedChange = { next -> scope.launch { switchOptions(context, bridge, pushMain = next) } })
                }
                Text(
                    "Claude travaille toujours sur sa propre branche. « Push: main » : à chaque changement terminé il intègre toute sa branche dans la " +
                        "branche principale et la pousse (push direct, sinon pull request fusionnée aussitôt), après avoir vérifié qu'aucune modification " +
                        "parallèle n'entre en conflit ; s'il y en a une, ou au moindre doute, il n'intègre rien et te pose la question. " +
                        "« Push: branche » : il ne pousse que sa branche, la principale n'est pas touchée, et tout sera intégré au prochain passage en « main ». " +
                        "Il ne force jamais un push. S'applique dès ton prochain message, même dans la session en cours.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Déployer avec GitHub Pages", Modifier.weight(1f))
                    Switch(checked = state.autodeploy, onCheckedChange = { next -> scope.launch { switchOptions(context, bridge, autodeploy = next) } })
                }
                Text(
                    "Aiwa ne déploie rien lui-même : cette option ajoute à ton prochain message une consigne demandant à Claude de publier le site " +
                        "avec un workflow GitHub Actions (sans branche gh-pages). Si Claude n'a pas le droit d'activer Pages ou de créer le workflow, " +
                        "il te dit le réglage à faire. Le bouton « Site ↗ » apparaît quand l'adresse publique répond.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // The state of the GitHub Actions (public data), and the run itself.
                val repoName = state.repo
                val runText = when (state.ciState) {
                    "success" -> "Dernière exécution des Actions : réussie."
                    "failure" -> "Dernière exécution des Actions : échec."
                    "running" -> "Une exécution des Actions est en cours."
                    "none" -> "Aucune exécution des Actions pour l'instant."
                    else -> "État des Actions inconnu (dépôt privé ?) : le lien ouvre la page."
                }
                Text(runText, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { openUrl(context, state.ciUrl ?: "https://github.com/$repoName/actions") }) { Text("Voir les Actions sur GitHub ↗") }
                // The address is known as soon as a repository is chosen.
                val url = state.siteUrl
                if (url != null) {
                    Text("Adresse du site : $url")
                    val status = siteText(state)
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openUrl(context, url) }) { Text("Ouvrir ↗") }
                        Button(onClick = {
                            copyToClipboard(context, url)
                            toastOnMain(context, "Adresse copiée")
                        }) { Text("Copier") }
                    }
                }
            }
            Text("Alerte « Claude attend »", style = MaterialTheme.typography.titleSmall)
            Text("Toujours active : le premier message d'une session demande à Claude d'envoyer un ping à la fin de chacune de ses réponses, et le bouton Claude du widget passe au rouge tant que tu n'as pas répondu ou ouvert la session.")
            Text(alertText(state))
            Button(onClick = {
                scope.launch {
                    try {
                        bridge.waitingTest()
                        toastOnMain(context, "Ping envoyé : dans quelques secondes le bouton Claude du widget passe au rouge.")
                    } catch (err: Exception) {
                        toastOnMain(context, "Relais injoignable : ${err.message}")
                    }
                    delay(2_500)
                    BackendSync.refresh(bridge)
                    AiwaWidget().updateAll(context)
                }
            }) { Text("Tester l'alerte (côté téléphone)") }
            Text("Pour que Claude puisse envoyer le ping depuis sa session cloud, l'environnement doit autoriser le domaine ntfy.sh : menu de l'environnement (barre de titre de la session) → Modifier → Accès réseau : Personnalisé → ajouter ntfy.sh. Sans cela le ping échoue, sans danger, et le bouton reste normal.")
            TextButton(onClick = { openUrl(context, CLAUDE_CODE_URL) }) { Text("Ouvrir claude.ai/code ↗") }
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
                Text("Ce que Claude Code reçoit avec le premier message d'une session (ensuite, seulement ce que tu changes) :", style = MaterialTheme.typography.labelMedium)
                Text(preview, style = MaterialTheme.typography.bodySmall)
            }
            val problem = state.githubError
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
        }
    }
}
