package com.aiwa.widget
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Reported live: the model list in the widget showed only "Auto". A
// widget cannot draw an overlay dropdown, so the list had to fit in
// whatever few dp were left under the row — about one entry. Each of the
// widget's two list buttons now opens one of these small floating
// windows (same translucent, own-task setup as DictateActivity) instead,
// so the widget itself can stay a single compact row.

class PickerEntry(val label: String, val active: Boolean, val onClick: () -> Unit)

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
                modifier = Modifier.padding(24.dp).fillMaxWidth().heightIn(max = 420.dp),
            ) {
                LazyColumn(Modifier.padding(vertical = 8.dp)) {
                    items(entries) { entry ->
                        Text(
                            text = (if (entry.active) "●  " else "    ") + entry.label,
                            color = if (entry.active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().clickable { entry.onClick() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

class SessionPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            // Fresh list from the backend every time the picker opens.
            LaunchedEffect(Unit) { BackendSync.refresh(LocalClaudeBridge()) }
            val entries = buildList {
                add(PickerEntry("+  Nouvelle session", state.sessionId == null) { pick(null) })
                state.sessions.forEach { s ->
                    add(PickerEntry(s.preview.take(70), s.id == state.sessionId) { pick(s.id) })
                }
            }
            PickerSheet(entries) { finish() }
        }
    }

    // finish() first, work in a scope that outlives this activity.
    private fun pick(sessionId: String?) {
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchSession(appContext, LocalClaudeBridge(), sessionId) }
    }
}

class ModelPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by AiwaRepository.state.collectAsState()
            LaunchedEffect(Unit) { BackendSync.refresh(LocalClaudeBridge()) }
            val entries = MODEL_CHOICES.map { choice ->
                PickerEntry(choice.label, choice.id == state.model) { pick(choice.id) }
            }
            PickerSheet(entries) { finish() }
        }
    }

    private fun pick(modelId: String?) {
        val appContext = applicationContext
        finish()
        CoroutineScope(Dispatchers.Default).launch { switchModel(appContext, LocalClaudeBridge(), modelId) }
    }
}
