package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The widget's "Claude ↗" button: a no-UI trampoline (same translucent,
 * own-task setup as the pickers) that opens the current cloud session in
 * the Claude app, where its conversation lives. An Activity because a
 * widget can only fire an intent, and the session to open is only known
 * from Aiwa's state at tap time.
 */
class OpenClaudeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        if (openClaudeApp(this)) {
            finish()
            return
        }
        // The process may have restarted since the widget last drew: ask
        // the backend which session is current before giving up.
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) { BackendSync.refresh(LocalClaudeBridge()) }
            if (!openClaudeApp(this@OpenClaudeActivity)) {
                val message = if (AiwaRepository.state.value.cloudSessionId == null && AiwaRepository.state.value.lastSessionId == null) {
                    "Pas encore de session Claude : envoie d'abord un message."
                } else {
                    "Impossible d'ouvrir l'appli Claude."
                }
                toastOnMain(this@OpenClaudeActivity, message)
            }
            finish()
        }
    }
}
