package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

// Compact by request: ONE row — [session ▾] [grey mic with a red
// recording dot] [model ▾]. Tapping a pill opens its list in the space
// under the row (a widget cannot draw an overlay dropdown, so the widget
// needs to be at least two rows tall to show one; at one row it is just
// the three buttons). Below the row, when no list is open, sits the
// latest reply only — the full transcript lives in the app.
class AiwaWidget : GlanceAppWidget() {
    @OptIn(FlowPreview::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val bridge = LocalClaudeBridge()
        provideContent {
            // Observed, NOT captured: Glance keeps this composition alive
            // for a while and answers updateAll() by recomposing it, so a
            // value read once here would stay stale — which is exactly
            // why the header used to lag behind the app until some later,
            // unrelated tap forced a brand-new composition. Sampled so a
            // streaming reply doesn't push a widget update per chunk.
            val flow = remember { AiwaRepository.state.sample(500) }
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
    val menu = currentState<Preferences>()[OpenMenuKey] ?: ""
    val accent = rgb(android.graphics.Color.rgb(122, 162, 255))
    val dim = rgb(android.graphics.Color.rgb(150, 150, 160))
    val fg = rgb(android.graphics.Color.rgb(228, 228, 235))
    val pill = rgb(android.graphics.Color.rgb(44, 44, 54))
    val micGrey = rgb(android.graphics.Color.rgb(84, 84, 94))
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(rgb(android.graphics.Color.rgb(22, 22, 28)))
            .cornerRadius(24.dp)
            .padding(10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.session.take(24) + "  ▾",
                style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
                    .background(pill)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .clickable(actionRunCallback<ToggleMenuAction>(actionParametersOf(MenuKey to "sessions"))),
            )
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier.size(44.dp)
                    .background(micGrey)
                    .cornerRadius(22.dp)
                    .clickable(actionStartActivity<DictateActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.rec_dot),
                    contentDescription = "Dicter un message",
                    modifier = GlanceModifier.size(16.dp),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = modelLabel(state.model) + "  ▾",
                style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier
                    .background(pill)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .clickable(actionRunCallback<ToggleMenuAction>(actionParametersOf(MenuKey to "models"))),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        when (menu) {
            "sessions" -> LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (state.sessionId != null) {
                    item {
                        Text(
                            "↗  Ouvrir dans Claude Code",
                            style = TextStyle(color = accent, fontSize = 13.sp),
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
                                .clickable(actionRunCallback<OpenInClaudeCodeAction>()),
                        )
                    }
                }
                item {
                    Text(
                        "+  Nouvelle session",
                        style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(actionRunCallback<SelectSessionAction>()),
                    )
                }
                items(state.sessions) { s ->
                    val active = s.id == state.sessionId
                    Text(
                        (if (active) "●  " else "    ") + s.preview.take(40),
                        style = TextStyle(color = if (active) accent else fg, fontSize = 13.sp),
                        maxLines = 1,
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(actionRunCallback<SelectSessionAction>(actionParametersOf(SessionIdKey to s.id))),
                    )
                }
            }
            "models" -> LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(MODEL_CHOICES) { m ->
                    val active = m.id == state.model
                    Text(
                        (if (active) "●  " else "    ") + m.label,
                        style = TextStyle(color = if (active) accent else fg, fontSize = 13.sp),
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(actionRunCallback<SelectModelAction>(actionParametersOf(ModelIdKey to (m.id ?: "")))),
                    )
                }
            }
            else -> LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (state.lastReply.isNotBlank()) {
                    item {
                        Text(state.lastReply, style = TextStyle(color = fg, fontSize = 13.sp))
                    }
                }
            }
        }
    }
}
