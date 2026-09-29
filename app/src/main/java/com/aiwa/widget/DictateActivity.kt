package com.aiwa.widget
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The widget's own mic, now backed by StopPhraseListener instead of a
 * one-shot RecognizerIntent — reported live, twice, insistently: "le
 * micro enregistre et envoie lorsqu'il entend 'c'est bon vas-y'", not
 * as soon as a natural pause is detected. StopPhraseListener's own
 * HONEST LIMIT applies directly here: raw SpeechRecognizer has no
 * default visible dialog, so this Activity — previously fully invisible
 * on purpose (reported live: opening the full app before dictation
 * looked slow and "moche") — now shows a small translucent indicator
 * instead of nothing, since silently listening with no feedback at all
 * would leave the user unsure whether they're still being heard. It is
 * NOT the main app UI: same Theme.Dictate translucent/no-title-bar
 * window as before, just no longer fully blank inside it.
 */
class DictateActivity : ComponentActivity() {
    private var listener: StopPhraseListener? = null

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private var lastPartial = ""

    private fun startListening() {
        val partialState = mutableStateOf("")
        setContent { ListeningOverlay(partialState.value, onCancel = { finishListening() }, onSendNow = { finishListening(force = true) }) }
        listener = StopPhraseListener(
            context = this,
            onPartial = { partialState.value = it; lastPartial = it },
            onFinalText = { text -> sendAndFinish(text) },
            onGiveUp = { finish() },
        )
        listener?.start()
    }

    private fun finishListening(force: Boolean = false) {
        // "Envoyer maintenant" is the fallback for when speech
        // recognition mangles the stop phrase — reported concern was
        // premature sending, not "no way out" if the phrase just isn't
        // recognized; cancel just discards whatever was heard so far.
        listener?.cancel()
        if (force && lastPartial.isNotBlank()) sendAndFinish(lastPartial) else finish()
    }

    private fun sendAndFinish(text: String) {
        val appContext = applicationContext
        if (text.isNotBlank()) {
            CoroutineScope(Dispatchers.Default).launch {
                sendAndTrack(appContext, LocalClaudeBridge(), text)
                AiwaWidget().updateAll(appContext)
            }
        }
        finish()
    }

    override fun onDestroy() {
        listener?.cancel()
        super.onDestroy()
    }
}

@Composable
private fun ListeningOverlay(partialText: String, onCancel: () -> Unit, onSendNow: () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.padding(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("🎙️ Écoute… dis « c'est bon vas-y » pour envoyer")
                    if (partialText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(partialText)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = onCancel) { Text("Annuler") }
                        TextButton(onClick = onSendNow) { Text("Envoyer maintenant") }
                    }
                }
            }
        }
    }
}
