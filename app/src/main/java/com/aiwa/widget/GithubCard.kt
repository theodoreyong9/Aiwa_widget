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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLAUDE_GITHUB_URL = "https://claude.ai/connect-github"
private const val LOGIN_COMMAND = "proot-distro login ubuntu -- bash -lc 'apt-get install -y gh; gh auth login -s repo'"

/**
 * GitHub, in the app: which repository Claude works on and whether it
 * pushes straight to the main branch. Two accesses are involved and the
 * user grants both themselves, once:
 *  - Aiwa's, to list repositories and prepare the session: the user's own
 *    `gh` login in Termux (Aiwa never starts that authorization);
 *  - Claude's, so the cloud session can clone and push: claude.ai/connect-github.
 */
@Composable
fun GithubCard(state: AiwaState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { LocalClaudeBridge() }
    // While the card is on screen, follow the backend (a login made in
    // Termux shows up here without reopening the app).
    LaunchedEffect(Unit) {
        while (true) {
            val visible = (context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
            if (visible) BackendSync.refresh(bridge)
            delay(8_000)
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GitHub", style = MaterialTheme.typography.titleMedium)
            if (!state.githubConnected) {
                Text("Pour que Claude travaille dans un de tes dépôts et y pousse directement, connecte d'abord GitHub, une seule fois, dans Termux (tu valides toi-même sur github.com) :")
                Text(LOGIN_COMMAND, style = MaterialTheme.typography.bodySmall)
                Text("Cette carte se met à jour toute seule quand c'est fait.")
            } else {
                Text("Connecté : ${state.githubLogin ?: "compte GitHub"}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dépôt : ${state.repo ?: "aucun (chat libre)"}", Modifier.weight(1f))
                    Button(onClick = { context.startActivity(Intent(context, RepoPickerActivity::class.java)) }) { Text("Choisir") }
                }
                if (state.repo != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Push direct sur la branche principale (sinon : une branche de travail)", Modifier.weight(1f))
                        Switch(checked = state.pushMain, onCheckedChange = { next -> scope.launch { switchPushMain(context, bridge, next) } })
                    }
                    Text("Le dépôt choisi s'applique à la prochaine nouvelle session ; chaque message y ajoute la consigne de commit et de push. Il faut aussi que Claude ait accès à ce dépôt :")
                    TextButton(onClick = { openUrl(context, CLAUDE_GITHUB_URL) }) { Text("Autoriser Claude sur GitHub ↗") }
                }
            }
            val problem = state.githubError
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
        }
    }
}
