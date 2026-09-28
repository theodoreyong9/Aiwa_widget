package com.aiwa.widget
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A real, invisible "trampoline" Activity for the widget's own mic
 * button. Reported live: tapping it used to open MainActivity's full
 * UI first and only then start dictation — visibly slow and "moche".
 * Android requires a real Activity to show a runtime permission prompt
 * or launch the system speech recognizer at all (a widget's own
 * ActionCallback has no such context) — this one has NO visible UI of
 * its own (see its manifest entry's Translucent.NoTitleBar theme), so
 * the only thing the user ever actually sees is the system
 * recognizer's own dialog appearing directly, as if the widget itself
 * had a working mic button.
 *
 * finish() is only called once the recognizer result actually comes
 * back — ending this Activity any earlier would tear down the
 * ActivityResultContracts callback before Android could deliver that
 * result to it.
 */
class DictateActivity : ComponentActivity() {
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchDictation() else finish()
    }
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) {
            val appContext = applicationContext
            AiwaRepository.update { it.copy(status = AiwaState.Status.WORKING, output = "") }
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    LocalClaudeBridge().sendMessage(heard).collect { chunk ->
                        AiwaRepository.update { it.copy(output = it.output + chunk) }
                    }
                    AiwaRepository.update { it.copy(status = AiwaState.Status.DONE) }
                } catch (err: Exception) {
                    AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = "[erreur: ${err.message}]") }
                }
                AiwaWidget().updateAll(appContext)
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) launchDictation() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        try {
            speechLauncher.launch(intent)
        } catch (err: Exception) {
            AiwaRepository.update { it.copy(status = AiwaState.Status.ERROR, output = "[erreur micro: ${err.message}]") }
            finish()
        }
    }
}
