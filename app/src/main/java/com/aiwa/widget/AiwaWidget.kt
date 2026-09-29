package com.aiwa.widget
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
// The Intent overload of actionStartActivity lives in the appwidget module.
import androidx.glance.appwidget.action.actionStartActivity as actionStartIntent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

// Two rows on a dark card. Conversation: [A: opens the Aiwa app] [session ▾]
// [grey mic with a red recording dot] [model ▾] and, once there is a session,
// the Claude button (a round orange spark; a red pill with a dot, and a red edge
// on the card, while Claude waits for an answer). Project, when the widget is
// tall enough: [repository ▾] [Push main / branche] [Deploy] and the round
// buttons for the site (globe) and the GitHub Actions (their colour is how the
// last run went), or the mint "Prêt" once a new green run is there.
// EVERYTHING IS ALWAYS THERE at any width the widget can be resized to: the
// weighted chips (session, repository) give way, their text cut to what fits,
// nothing is dropped. No conversation text: a cloud session's replies can't be
// read back by a program, so they live in the Claude app. Each ▾ button opens a
// small floating picker window (Pickers.kt): a widget cannot draw an overlay
// dropdown.
class AiwaWidget : GlanceAppWidget() {
    // Exact: the composition learns the real size (LocalSize), so the
    // GitHub row only shows when there is room for it.
    override val sizeMode: SizeMode = SizeMode.Exact

    @OptIn(FlowPreview::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Placing the widget or the launcher redrawing it wakes the keep-alive
        // service too (and so the backend) — no need to open the app first.
        wakeAiwa(context)
        val bridge = LocalClaudeBridge()
        provideContent {
            // Observed, NOT captured: Glance keeps this composition alive
            // for a while and answers updateAll() by recomposing it, so a
            // value read once here would stay stale — which is why the
            // header used to lag behind the app until some later,
            // unrelated tap forced a brand-new composition.
            val flow = remember { AiwaRepository.state.sample(300) }
            val state by flow.collectAsState(initial = AiwaRepository.state.value)
            LaunchedEffect(Unit) { BackendSync.refresh(bridge) }
            Content(state)
        }
    }
}

// Glance's ColorProvider(Int) overload takes a @ColorRes RESOURCE id,
// not a packed color: passing android.graphics.Color.rgb(...) straight
// in compiles but crashes at real render time on device. Wrapping in
// Compose's own Color(Int) first is the real fix (confirmed live).
private fun rgb(colorInt: Int) = ColorProvider(androidx.compose.ui.graphics.Color(colorInt))

private const val GAP = 6f

// Text widths are estimates (12 sp: about 6.8 dp per plain character and 12 dp
// per symbol, scaled by the user's font size): enough to cut a name to what fits
// before the widget clips it in the middle of a letter.
private fun textWidth(text: String, fontScale: Float): Float {
    var width = 0f
    for (c in text) width += if (c.code < 0x250) 6.8f else 12f
    return width * fontScale
}

// A chip is its text plus 10 dp of padding on each side.
private fun chipWidth(text: String, fontScale: Float): Float = 20f + textWidth(text, fontScale)

private fun fitLabel(text: String, room: Float, fontScale: Float): String {
    if (textWidth(text, fontScale) <= room) return text
    var out = text
    while (out.isNotEmpty() && textWidth("$out…", fontScale) > room) out = out.dropLast(1)
    return if (out.isEmpty()) "…" else "$out…"
}

// Every button of the widget is one of these two shapes, 34 dp high.
@Composable
private fun Chip(
    text: String,
    background: ColorProvider,
    color: ColorProvider,
    action: Action,
    modifier: GlanceModifier = GlanceModifier,
    bold: Boolean = false,
    alignStart: Boolean = false,
) {
    Box(
        modifier = modifier.height(34.dp).background(background).cornerRadius(17.dp).padding(horizontal = 10.dp).clickable(action),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(color = color, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundButton(icon: Int, description: String, background: ColorProvider, action: Action, diameter: Dp = 34.dp) {
    Box(
        modifier = GlanceModifier.size(diameter).background(background).cornerRadius(diameter / 2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(provider = ImageProvider(icon), contentDescription = description, modifier = GlanceModifier.size(18.dp))
    }
}

@Composable
private fun Content(state: AiwaState) {
    val fg = rgb(android.graphics.Color.rgb(240, 240, 245))
    val pill = rgb(android.graphics.Color.rgb(44, 44, 54))
    val neutral = rgb(android.graphics.Color.rgb(58, 58, 70))
    val micGrey = rgb(android.graphics.Color.rgb(84, 84, 94))
    val claudeOrange = rgb(android.graphics.Color.rgb(204, 120, 92))
    val alertRed = rgb(android.graphics.Color.rgb(214, 69, 65))
    val green = rgb(android.graphics.Color.rgb(46, 125, 90))
    val mint = rgb(android.graphics.Color.rgb(221, 243, 230))
    val mintText = rgb(android.graphics.Color.rgb(17, 51, 31))
    val brand = rgb(android.graphics.Color.rgb(232, 150, 124))
    val size = LocalSize.current
    val fontScale = LocalContext.current.resources.configuration.fontScale
    // Room for the second row (two rows of buttons plus the padding).
    val tall = size.height >= 96.dp
    // The card's own padding takes 10 dp on each side.
    val avail = size.width.value - 20f
    val hasSession = state.cloudSessionId != null || state.lastSessionId != null

    // ---- row 1: the conversation -------------------------------------------
    // While a message is on its way (creating a cloud session takes a few
    // seconds) the session chip says so: the widget has no other place to show
    // progress. The backend's own state comes first: with it down or starting,
    // nothing else on the widget can be trusted to work, and a widget that just
    // sits there looks broken.
    val sessionLabel = when {
        state.backend == "starting" -> "⏳ Démarrage"
        state.backend == "down" -> "⚠ Arrêté"
        state.status == AiwaState.Status.WORKING -> "Envoi…"
        else -> state.session
    }
    val modelText = modelLabel(state.model).replace(" · ", "·").take(12) + " ▾"
    val pillText = "● Claude ↗"
    val fixedLeft = 36f + 40f + chipWidth(modelText, fontScale) + 3 * GAP // A, mic, model and the gaps before them
    // Claude waiting gets its words when there is room for them next to a
    // readable session name; otherwise it stays a round button, still red.
    val claudePill = hasSession && state.waiting && avail - fixedLeft - (chipWidth(pillText, fontScale) + GAP) >= 96f
    val claudeWidth = when {
        !hasSession -> 0f
        claudePill -> chipWidth(pillText, fontScale) + GAP
        else -> 36f + GAP
    }
    val sessionText = fitLabel(sessionLabel, avail - fixedLeft - claudeWidth - 20f - textWidth(" ▾", fontScale), fontScale) + " ▾"

    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ImageProvider(if (state.waiting) R.drawable.widget_bg_alert else R.drawable.widget_bg))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier.size(36.dp)
                    .background(neutral)
                    .cornerRadius(18.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", style = TextStyle(color = brand, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.width(GAP.dp))
            Chip(sessionText, pill, fg, actionStartActivity<SessionPickerActivity>(), GlanceModifier.defaultWeight(), bold = true, alignStart = true)
            Spacer(GlanceModifier.width(GAP.dp))
            Box(
                modifier = GlanceModifier.size(40.dp)
                    .background(micGrey)
                    .cornerRadius(20.dp)
                    .clickable(actionStartActivity<DictateActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.rec_dot),
                    contentDescription = "Dicter un message",
                    modifier = GlanceModifier.size(16.dp),
                )
            }
            Spacer(GlanceModifier.width(GAP.dp))
            Chip(modelText, pill, fg, actionStartActivity<ModelPickerActivity>())
            // Only once a session exists (Aiwa has created or selected one):
            // before that there is nothing to open. Red while Claude waits for
            // an answer (it pinged the relay): the alert is this button and the
            // card's edge, not a notification.
            if (hasSession) {
                Spacer(GlanceModifier.width(GAP.dp))
                if (claudePill) {
                    Chip(pillText, alertRed, fg, actionStartActivity<OpenClaudeActivity>(), bold = true)
                } else {
                    RoundButton(
                        icon = R.drawable.ic_claude,
                        description = if (state.waiting) "Claude attend une réponse : ouvrir la conversation" else "Ouvrir la conversation dans Claude",
                        background = if (state.waiting) alertRed else claudeOrange,
                        action = actionStartActivity<OpenClaudeActivity>(),
                        diameter = 36.dp,
                    )
                }
            }
        }
        if (tall) {
            Spacer(GlanceModifier.height(GAP.dp))
            // ---- row 2: the GitHub project -----------------------------------
            // The buttons appear as you go: the repository picker first, then
            // (once a repository is chosen) push mode and deployment, then the
            // site and the Actions. They are instructions integrated into the
            // conversation — Claude Code does the work itself.
            val hasRepo = state.repo != null
            val pushText = if (state.pushMain) "Push main" else "Push branche"
            val deployText = if (state.autodeploy) "Deploy ●" else "Deploy ○"
            val readyText = "● Prêt ↗"
            val site = state.siteUrl
            val live = state.siteState == "live"
            // A new green run the user hasn't seen: "Prêt" replaces the two round
            // buttons (a green run is what it says).
            val fresh = state.ciFresh
            // The address is known as soon as a repository is chosen
            // (https://<owner>.github.io/<repo>/): the globe is there once
            // deployment is asked for, or as soon as the address answers.
            val showSite = hasRepo && !fresh && site != null && (state.autodeploy || live)
            val showActions = hasRepo && !fresh && (state.autodeploy || state.ciState != null)
            var others = 0f
            if (hasRepo) others += GAP + chipWidth(pushText, fontScale) + GAP + chipWidth(deployText, fontScale)
            if (hasRepo && fresh) others += GAP + chipWidth(readyText, fontScale)
            if (showSite) others += GAP + 34f
            if (showActions) others += GAP + 34f
            val repoName = state.repo?.substringAfter('/')
            val repoText = if (repoName == null) {
                "⎇ Choisir un dépôt ▾"
            } else {
                "⎇ " + fitLabel(repoName, avail - others - 20f - textWidth("⎇  ▾", fontScale), fontScale) + " ▾"
            }
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip(repoText, pill, fg, actionStartActivity<RepoPickerActivity>(), GlanceModifier.defaultWeight(), alignStart = true)
                if (hasRepo) {
                    Spacer(GlanceModifier.width(GAP.dp))
                    Chip(pushText, if (state.pushMain) green else pill, fg, actionRunCallback<TogglePushMainCallback>())
                    Spacer(GlanceModifier.width(GAP.dp))
                    Chip(deployText, if (state.autodeploy) green else pill, fg, actionRunCallback<ToggleAutodeployCallback>())
                    if (fresh) {
                        Spacer(GlanceModifier.width(GAP.dp))
                        Chip(readyText, mint, mintText, actionStartActivity<OpenResultActivity>(), bold = true)
                    }
                    if (showSite && site != null) {
                        Spacer(GlanceModifier.width(GAP.dp))
                        // Orange = the address answers; grey = not (yet) — it still opens.
                        RoundButton(
                            icon = R.drawable.ic_globe,
                            description = "Ouvrir le site",
                            background = if (live) claudeOrange else pill,
                            action = actionStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse(site))),
                        )
                    }
                    if (showActions) {
                        Spacer(GlanceModifier.width(GAP.dp))
                        // The colour is how the last run went: green, red, grey (running or unknown).
                        RoundButton(
                            icon = R.drawable.ic_actions,
                            description = "GitHub Actions : " + when (state.ciState) {
                                "success" -> "réussi"
                                "failure" -> "échec"
                                "running" -> "en cours"
                                else -> "état inconnu"
                            },
                            background = when (state.ciState) { "success" -> green; "failure" -> alertRed; else -> pill },
                            action = actionStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse(state.ciUrl ?: "https://github.com/${state.repo}/actions"))),
                        )
                    }
                }
            }
        }
    }
}

// The widget's "Push" and "Deploy" buttons flip an instruction without opening anything.
class TogglePushMainCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        switchOptions(context, LocalClaudeBridge(), pushMain = !AiwaRepository.state.value.pushMain)
    }
}

class ToggleAutodeployCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        switchOptions(context, LocalClaudeBridge(), autodeploy = !AiwaRepository.state.value.autodeploy)
    }
}
