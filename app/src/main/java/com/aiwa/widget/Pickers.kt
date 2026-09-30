package com.aiwa.widget
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.RepoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Reported live: the model list in the widget showed only "Auto". A
// widget cannot draw an overlay dropdown, so the list had to fit in
// whatever few dp were left under the row — about one entry. Each of the
// widget's two list buttons now opens one of these small floating
// windows (same translucent, own-task setup as DictateActivity) instead,
// so the widget itself can stay a single compact row.

// header: a section title, not something to tap. lines: how many lines the label may take.
class PickerEntry(val label: String, val active: Boolean, val header: Boolean = false, val lines: Int = 2, val onClick: () -> Unit)

@Composable
private fun PickerSheet(entries: List<PickerEntry>, onDismiss: () -> Unit) {
    MaterialTheme {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.padding(24.dp).fillMaxWidth().heightIn(max = 460.dp),
            ) {
                LazyColumn(Modifier.padding(vertical = 8.dp)) {
                    items(entries) { entry ->
                        if (entry.header) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        } else {
                            Text(
                                text = (if (entry.active) "●  " else "    ") + entry.label,
                                color = if (entry.active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                maxLines = entry.lines,
                                modifier = Modifier.fillMaxWidth().clickable { entry.onClick() }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

class SessionPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            // Fresh list from the backend every time the picker opens (which
            // is started first if it isn't running).
            LaunchedEffect(Unit) {
                val bridge = LocalClaudeBridge()
                ensureBackend(applicationContext, bridge)
                BackendSync.refresh(bridge)
            }
            val entries = buildList {
                add(PickerEntry("+  Nouvelle session", state.cloudSessionId == null) { pickCloud("new") })
                state.cloudSessions.forEach { c ->
                    add(PickerEntry(c.title.take(60), c.id == state.cloudSessionId) { pickCloud(c.id) })
                }
                // The CLI has no command to list the account's cloud
                // sessions, so one made elsewhere is added by pasting its
                // link (last entry: the rarely used one).
                add(PickerEntry("⎘  Ajouter une session existante (lien copié)", false) { addFromClipboard() })
            }
            PickerSheet(entries) { finish() }
        }
    }

    // finish() first, work in a scope that outlives this activity.
    private fun pickCloud(target: String) {
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchCloud(appContext, LocalClaudeBridge(), target) }
    }

    // Read here, on the main thread of the focused activity: that is the
    // only place Android hands the clipboard over.
    private fun addFromClipboard() {
        val text = clipboardText(this)
        if (text.isNullOrBlank()) {
            toastOnMain(this, EMPTY_CLIPBOARD_FOR_SESSION)
            finish()
            return
        }
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { addCloudSession(appContext, LocalClaudeBridge(), text) }
    }
}

// Model and effort, like the Claude app's own chip ("Sonnet 5.5 · Moyen"):
// both are sent to the open session (/model, /effort) and remembered for the
// next new one.
class ModelPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            LaunchedEffect(Unit) {
                val bridge = LocalClaudeBridge()
                ensureBackend(applicationContext, bridge)
                BackendSync.refresh(bridge)
            }
            val entries = buildList {
                add(PickerEntry("Modèle — envoyé à la session (/model)", false, header = true) { })
                MODEL_CHOICES.forEach { choice ->
                    add(PickerEntry(choice.label, choice.id == state.model) { pickModel(choice.id) })
                }
                add(PickerEntry("Effort de raisonnement", false, header = true) { })
                EFFORT_CHOICES.forEach { choice ->
                    add(PickerEntry(choice.label, choice.id == state.effort) { pickEffort(choice.id) })
                }
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun pickModel(modelId: String?) {
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchModel(appContext, LocalClaudeBridge(), modelId) }
    }

    private fun pickEffort(level: String?) {
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchEffort(appContext, LocalClaudeBridge(), level) }
    }
}

// The repository the next NEW session starts on (Claude works there and
// pushes to it). The list is discovered on its own by the backend: the
// public repositories of the owner of the Aiwa checkout and of the owners
// of repositories already used, plus the ones of an existing `gh` login.
class RepoPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            var repos by remember { mutableStateOf<List<RepoInfo>?>(null) }
            var starting by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val bridge = LocalClaudeBridge()
                // The backend may not be running (a widget tap never went
                // through the app): start it and wait, don't show an empty list.
                val up = try { bridge.status(); true } catch (err: Exception) { !isBackendUnreachable(err) }
                if (!up) {
                    starting = true
                    ensureBackend(applicationContext, bridge)
                    starting = false
                }
                BackendSync.refresh(bridge)
                repos = try { bridge.githubRepos() } catch (err: Exception) { emptyList() }
            }
            val entries = buildList {
                add(PickerEntry("Aucun dépôt (chat libre)", state.repo == null) { pickRepo(null) })
                val list = repos
                if (list == null) {
                    add(PickerEntry(if (starting) "Démarrage du backend (Termux)… quelques secondes" else "Chargement des dépôts…", false) { })
                } else {
                    list.forEach { r ->
                        add(PickerEntry(r.name + if (r.isPrivate) "  (privé)" else "", r.name == state.repo) { pickRepo(r.name) })
                    }
                    if (list.isEmpty()) add(PickerEntry("Aucun dépôt trouvé", false) { })
                }
                // Repositories Claude may ALSO work on, told to it with the next message.
                if (state.repo != null) {
                    val n = state.extraRepos.size
                    add(PickerEntry("＋  Autres dépôts où Claude peut intervenir" + (if (n > 0) " ($n)" else "") + "…", n > 0) { openExtraRepos() })
                }
                // Claude Code reaches GitHub with ITS OWN connection, made in Claude's
                // settings: Aiwa never logs in to GitHub and cannot tell whether it is
                // connected. So ONE entry, which opens the page of Claude's connectors —
                // it shows the real state and offers Connect or Disconnect accordingly.
                add(PickerEntry("🔗  Connexion GitHub de Claude : connecter, changer, déconnecter ↗", false) { openClaudeSettings(CLAUDE_CONNECTORS_URL) })
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun pickRepo(repo: String?) {
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchRepo(appContext, LocalClaudeBridge(), repo) }
    }

    private fun openExtraRepos() {
        startActivity(Intent(this, ExtraReposPickerActivity::class.java))
        finish()
    }

    private fun openClaudeSettings(url: String) {
        if (!openUrl(applicationContext, url)) toastOnMain(applicationContext, "Impossible d'ouvrir le navigateur.")
        finish()
    }
}

// Claude's list of connectors: GitHub is connected, switched to another account or
// disconnected there, and the page shows which of those applies.
private const val CLAUDE_CONNECTORS_URL = "https://claude.ai/customize/connectors"

/**
 * The repositories Claude may ALSO work on, checked here (the picker stays open:
 * several can be checked). The primary repository is the one the session starts on;
 * these are only NAMED to Claude in the instructions of the next message, with the
 * request to attach them itself (`add_repo`) when it needs them — the platform, not
 * the text, decides whether it can reach them (the GitHub connection of the Claude
 * account).
 */
class ExtraReposPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            var repos by remember { mutableStateOf<List<RepoInfo>?>(null) }
            LaunchedEffect(Unit) {
                val bridge = LocalClaudeBridge()
                BackendSync.refresh(bridge)
                repos = try { bridge.githubRepos() } catch (err: Exception) { emptyList() }
            }
            val entries = buildList {
                add(PickerEntry("Claude peut aussi intervenir sur (dès le prochain message) — ${state.repo?.substringAfter('/') ?: "?"} reste le dépôt principal", false, header = true) { })
                val list = repos
                if (list == null) {
                    add(PickerEntry("Chargement des dépôts…", false) { })
                } else {
                    list.filter { it.name != state.repo }.forEach { r ->
                        add(PickerEntry(r.name + if (r.isPrivate) "  (privé)" else "", r.name in state.extraRepos) { toggle(r.name) })
                    }
                    if (state.extraRepos.isNotEmpty()) add(PickerEntry("Tout décocher", false) { toggle(null) })
                }
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun toggle(repo: String?) {
        val appContext = applicationContext
        CoroutineScope(Dispatchers.Default).launch { switchExtraRepo(appContext, LocalClaudeBridge(), repo) }
    }
}

/**
 * "État d'Aiwa": what works and what is missing, each line saying what to do about it. Opened
 * from the widget's status line when Claude's cloud environment does not reach the relay (the
 * alerts "Claude attend ta réponse"), and from the app.
 *
 * The one thing Aiwa cannot do for the user: the relay (ntfy.sh) must be allowed in the network
 * access of the cloud ENVIRONMENT, a setting that only claude.ai/code can change — there is no
 * API or CLI for it. So the entry copies "ntfy.sh", opens claude.ai/code and says what to click.
 */
class HealthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            LaunchedEffect(Unit) {
                val bridge = LocalClaudeBridge()
                ensureBackend(applicationContext, bridge)
                BackendSync.refresh(bridge)
            }
            val entries = buildList {
                add(PickerEntry("État d'Aiwa", false, header = true) { })
                add(
                    PickerEntry(
                        if (state.backend == "up") "✓  Backend (Termux) : en marche"
                        else "⚠  Backend " + (if (state.backend == "starting") "en démarrage…" else "arrêté") + " — toucher pour relancer",
                        state.backend == "up",
                    ) { restartBackend() },
                )
                when (state.claudeLogin) {
                    "ok" -> add(PickerEntry("✓  Claude : connecté", true) { })
                    "needed" -> add(PickerEntry("⚠  Claude n'est pas connecté — connecter…", false) { openLogin() })
                    else -> add(PickerEntry("…  Claude : connexion pas vérifiée — vérifier ou connecter…", false) { openLogin() })
                }
                when (state.relayCloud) {
                    "ok" -> add(PickerEntry("✓  Alertes du cloud : le relais ntfy.sh répond", true) { })
                    "pending" -> add(PickerEntry("⏳  Alertes du cloud : test en cours (la réponse de Claude peut prendre une minute)…", false, lines = 3) { })
                    "missing" -> {
                        add(PickerEntry("⚠  Alertes du cloud bloquées : le cloud ne joint pas ntfy.sh — autoriser…", false, lines = 3) { allowRelay() })
                        add(
                            PickerEntry(
                                "Sur claude.ai/code : ton environnement (la roue crantée) → Accès réseau « Custom » → Domaines autorisés : " +
                                    "ajoute ntfy.sh (et coche « Also include default list » pour garder les autres). « ntfy.sh » est copié quand tu touches la ligne du dessus ; ensuite, « Retester ».",
                                false, lines = 7,
                            ) { },
                        )
                        add(PickerEntry("Retester maintenant (demandé à la session en cours)", false) { retest() })
                        add(PickerEntry("Ne plus le rappeler dans le widget", false) { dismissHint() })
                    }
                    else -> {
                        add(PickerEntry("…  Alertes du cloud : pas encore testées (le test part avec le premier message d'une nouvelle session)", false, lines = 3) { })
                        add(PickerEntry("Tester maintenant (demandé à la session en cours)", false) { retest() })
                    }
                }
                // What the "Prêt" button and the Actions button are about: when "Prêt" shows up unexpectedly,
                // this says which run it was.
                state.ciDetail?.let { detail ->
                    add(PickerEntry((if (state.ciFresh) "« Prêt » vient de : " else "Dernier résultat GitHub suivi : ") + detail, false, lines = 4) { })
                }
                add(PickerEntry("Permissions Termux et notifications…", false) { go(SetupActivity::class.java) })
                add(PickerEntry("Choisir la session…", false) { go(SessionPickerActivity::class.java) })
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun go(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }

    private fun openLogin() = go(ClaudeLoginActivity::class.java)

    private fun restartBackend() {
        startAiwaBackendViaTermux(applicationContext, forceRestart = true)
        toastOnMain(this, "Relance du backend demandée à Termux (10 à 20 s).")
        finish()
    }

    private fun allowRelay() {
        copyToClipboard(this, "ntfy.sh")
        if (!openUrl(applicationContext, "https://claude.ai/code")) toastOnMain(this, "Impossible d'ouvrir le navigateur : va sur claude.ai/code.")
        else toastOnMain(this, "« ntfy.sh » est copié : colle-le dans les domaines autorisés de ton environnement.")
        finish()
    }

    private fun retest() {
        val app = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch {
            val bridge = LocalClaudeBridge()
            try {
                bridge.relayRetest()
                toastOnMain(app, "Test demandé à la session : la réponse de Claude peut prendre une minute.")
            } catch (err: Exception) {
                toastOnMain(app, "Test non envoyé : ${err.message}")
            }
            BackendSync.refresh(bridge)
            AiwaWidget().updateAll(app)
        }
    }

    private fun dismissHint() {
        val app = applicationContext
        dismissRelayHint(app)
        finish()
        CoroutineScope(Dispatchers.Default).launch { AiwaWidget().updateAll(app) }
    }
}

/**
 * What to do with what Claude sent, in the "Aiwa" mode: read its code first, or go and open it. A contract
 * (HTML) is read here and published on the Aiwa wallet page, which then offers the YourMine sphere pinned to
 * it (that sphere exists only once the contract is published); a sphere (JS) is read here and opened in
 * YourMine's submission field. Opened by OpenSphereActivity, for the widget, the card and the notification alike.
 */
class SphereTargetPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            val received = state.sphere
            val contract = received?.kind == "aiwa"
            val name = received?.name?.removeSuffix(".aiwa.html") ?: "ce que Claude a envoyé"
            val entries = buildList {
                add(PickerEntry(if (contract) "« $name » — contrat Aiwa (HTML)" else "« $name » — sphère YourMine (JS)", false, header = true) { })
                add(PickerEntry(if (contract) "Voir le code du contrat (HTML)" else "Voir le code de la sphère (JS)", false) { view() })
                add(
                    PickerEntry(
                        if (contract) "Publier dans Aiwa : la page wallet, contrat pré-rempli — la sphère YourMine s'y affiche après la publication"
                        else "Ouvrir dans YourMine : le champ de soumission, sphère pré-remplie — rien n'est soumis pour toi",
                        false, lines = 4,
                    ) { openIt() },
                )
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun view() {
        startActivity(Intent(this, CodeViewActivity::class.java))
        finish()
    }

    private fun openIt() {
        startActivity(Intent(this, OpenSphereActivity::class.java).putExtra(EXTRA_OPEN_NOW, true))
        finish()
    }
}

/**
 * The documents of the "Aiwa" mode (DOC_CHOICES): the round button next to the mic opens this list, one
 * entry opens one document in the browser (the PDFs are files: the browser downloads them and offers to
 * open them). One button and a list rather than one button per document.
 */
class DocsPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val entries = buildList {
                add(PickerEntry("Documents Aiwa", false, header = true) { })
                DOC_CHOICES.forEach { doc -> add(PickerEntry(doc.label, false, lines = 3) { open(doc.id) }) }
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun open(address: String?) {
        if (address == null || !openUrl(applicationContext, address)) toastOnMain(this, "Impossible d'ouvrir le navigateur.")
        finish()
    }
}

/**
 * What Claude is asked to produce with the work: the widget's Deploy chip opens this list (it used
 * to cycle through the four states at each tap). Told to Claude with the next message.
 */
class DeployPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            val entries = buildList {
                add(PickerEntry("Que doit produire Claude ? (dès le prochain message)", false, header = true) { })
                DEPLOY_CHOICES.forEach { choice ->
                    add(PickerEntry(choice.label, choice.id == state.deploy, lines = 3) { pick(choice.id) })
                }
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun pick(mode: String?) {
        val app = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchOptions(app, LocalClaudeBridge(), deploy = mode) }
    }
}
