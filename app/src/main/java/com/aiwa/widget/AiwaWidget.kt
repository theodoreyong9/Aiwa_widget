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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

// One compact row of buttons — [A: opens the Aiwa app] [session ▾] [grey
// mic with a red recording dot] [model ▾] and, once there is a session to
// read, [Claude ↗].
// No conversation text: a cloud session's replies can't be read back by a
// program, so they live in the Claude app and "Claude ↗" opens them. Each ▾
// button opens a small floating picker window (Pickers.kt): a widget cannot
// draw an overlay dropdown.
class AiwaWidget : GlanceAppWidget() {
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
    Row(
        modifier = GlanceModifier.fillMaxSize()
            .background(rgb(android.graphics.Color.rgb(22, 22, 28)))
            .cornerRadius(24.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
}
