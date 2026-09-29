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

// One compact row of buttons — [session ▾] [grey mic with a red
// recording dot] [model ▾] — and the conversation under it (scrollable,
// resizable down to just the row). Each ▾ button opens a small floating
// picker window (Pickers.kt): a widget cannot draw an overlay dropdown,
// and an inline list only ever got the few dp left under the row.
//
// The conversation is shown NEWEST EXCHANGE FIRST: a widget's list always
// opens scrolled to its top, so in reading order the reply you are
// waiting for would sit off-screen below the whole history. Scrolling
// down goes back in time; the app shows the same text in reading order.
class AiwaWidget : GlanceAppWidget() {
    @OptIn(FlowPreview::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val bridge = LocalClaudeBridge()
        provideContent {
            // Observed, NOT captured: Glance keeps this composition alive
            // for a while and answers updateAll() by recomposing it, so a
            // value read once here would stay stale — which is why the
            // header used to lag behind the app until some later,
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

private val EXCHANGE_START = Regex("(?=🧑 )")

// One item per exchange (question + answer), latest first, capped so the
// widget's RemoteViews payload stays small however long the session is.
private fun exchangesNewestFirst(transcript: String): List<String> =
    EXCHANGE_START.split(transcript).map { it.trim() }.filter { it.isNotEmpty() }.takeLast(30).reversed()

@Composable
private fun Content(state: AiwaState) {
    val fg = rgb(android.graphics.Color.rgb(228, 228, 235))
    val pill = rgb(android.graphics.Color.rgb(44, 44, 54))
    val micGrey = rgb(android.graphics.Color.rgb(84, 84, 94))
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(rgb(android.graphics.Color.rgb(22, 22, 28)))
            .cornerRadius(24.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                .clickable(actionStartActivity<SessionPickerActivity>()),
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
                .clickable(actionStartActivity<ModelPickerActivity>()),
        )
    }
    Spacer(GlanceModifier.height(6.dp))
    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
        items(exchangesNewestFirst(state.output)) { exchange ->
            Text(
                exchange,
                style = TextStyle(color = fg, fontSize = 13.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
    }
    }
}
