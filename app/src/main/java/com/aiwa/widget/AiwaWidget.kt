package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

// Exactly one compact row, by explicit request: [session ▾] [grey mic
// with a red recording dot] [model ▾]. No conversation text at all — the
// transcript lives in the app. Each ▾ button opens a small floating
// picker window (Pickers.kt): a widget cannot draw an overlay dropdown,
// and an inline list only ever got the few dp left under the row.
class AiwaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val bridge = LocalClaudeBridge()
        provideContent {
            // Observed, NOT captured: Glance keeps this composition alive
            // for a while and answers updateAll() by recomposing it, so a
            // value read once here would stay stale — which is why the
            // header used to lag behind the app until some later,
            // unrelated tap forced a brand-new composition.
            val state by AiwaRepository.state.collectAsState()
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
    Row(
        modifier = GlanceModifier.fillMaxSize()
            .background(rgb(android.graphics.Color.rgb(22, 22, 28)))
            .cornerRadius(24.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
}
