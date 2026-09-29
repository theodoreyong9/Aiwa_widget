package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

// A compact row of buttons — [A: opens the Aiwa app] [session ▾] [grey
// mic with a red recording dot] [model ▾] and, once there is a session to
// read, [Claude ↗]. When the widget is tall enough a second row shows the
// GitHub repository the next session starts on and the push mode.
// No conversation text: a cloud session's replies can't be read back by a
// program, so they live in the Claude app and "Claude ↗" opens them. Each ▾
// button opens a small floating picker window (Pickers.kt): a widget cannot
// draw an overlay dropdown.
class AiwaWidget : GlanceAppWidget() {
    // Exact: the composition learns the real size (LocalSize), so the
    // GitHub row only shows when there is room for it.
    override val sizeMode: SizeMode = SizeMode.Exact

    @OptIn(FlowPreview::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
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

@Composable
private fun Content(state: AiwaState) {
    val fg = rgb(android.graphics.Color.rgb(228, 228, 235))
    val pill = rgb(android.graphics.Color.rgb(44, 44, 54))
    val micGrey = rgb(android.graphics.Color.rgb(84, 84, 94))
    val claudeOrange = rgb(android.graphics.Color.rgb(204, 120, 92))
    // While a message is on its way (creating a cloud session takes a few
    // seconds) the session button says so: the widget has no other place
    // to show progress.
    val sessionLabel = if (state.status == AiwaState.Status.WORKING) "Envoi…" else state.session.take(20)
    val green = rgb(android.graphics.Color.rgb(46, 125, 90))
    // Room for a second row (two rows of buttons plus the padding).
    val tall = LocalSize.current.height >= 96.dp
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(rgb(android.graphics.Color.rgb(22, 22, 28)))
            .cornerRadius(24.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = GlanceModifier.size(36.dp)
                .background(pill)
                .cornerRadius(18.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "$sessionLabel  ▾",
            style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
                .background(pill)
                .cornerRadius(20.dp)
                .padding(horizontal = 11.dp, vertical = 11.dp)
                .clickable(actionStartActivity<SessionPickerActivity>()),
        )
        Spacer(GlanceModifier.width(6.dp))
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
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = modelLabel(state.model) + "  ▾",
            style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier
                .background(pill)
                .cornerRadius(20.dp)
                .padding(horizontal = 11.dp, vertical = 11.dp)
                .clickable(actionStartActivity<ModelPickerActivity>()),
        )
        // Only once a session exists (Aiwa has created or selected one):
        // before that there is nothing to open.
        if (state.cloudSessionId != null) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = "Claude ↗",
                style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier
                    .background(claudeOrange)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 11.dp, vertical = 11.dp)
                    .clickable(actionStartActivity<OpenClaudeActivity>()),
            )
        }
    }
    if (tall) {
        Spacer(GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // GitHub: which repository the next session starts on, and
            // whether Claude pushes straight to its main branch.
            if (!state.githubConnected) {
                Text(
                    text = "GitHub : à connecter (ouvre Aiwa)",
                    style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                        .background(pill)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 11.dp, vertical = 11.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                )
            } else {
                val repoLabel = state.repo?.substringAfter('/')?.take(22) ?: "Aucun dépôt"
                Text(
                    text = "⎇ $repoLabel  ▾",
                    style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                        .background(pill)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 11.dp, vertical = 11.dp)
                        .clickable(actionStartActivity<RepoPickerActivity>()),
                )
                if (state.repo != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = if (state.pushMain) "Push direct ●" else "Push direct ○",
                        style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        modifier = GlanceModifier
                            .background(if (state.pushMain) green else pill)
                            .cornerRadius(20.dp)
                            .padding(horizontal = 11.dp, vertical = 11.dp)
                            .clickable(actionRunCallback<TogglePushMainCallback>()),
                    )
                }
            }
        }
    }
    }
}

// The widget's "Push direct" button: flips the mode without opening anything.
class TogglePushMainCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        switchPushMain(context, LocalClaudeBridge(), !AiwaRepository.state.value.pushMain)
    }
}
