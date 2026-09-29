package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The widget's "● Prêt à voir ↗" button: a no-UI trampoline (same
 * translucent, own-task setup as the pickers) that opens the result of the
 * work — the site, or the Actions run — and marks the news as seen.
 */
class OpenResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        if (!openResult(this)) toastOnMain(this, "Rien à ouvrir pour l'instant.")
        finish()
    }
}
