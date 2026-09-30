package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.YOURMINE_URL
import com.aiwa.bridge.yourMineSphereUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The widget's ⬡ button (and the notification of a new sphere): opens YourMine on Build →
 * Apps with the sphere Claude sent already in the code field — and does nothing else: the
 * user reads it and presses "Sign & Submit" themself. A no-UI trampoline, like the other
 * buttons of the widget.
 *
 * The code also goes to the clipboard, always: a YourMine that does not yet know the hand-off
 * (its `#aiwa=` fragment is ignored there) simply opens, and the code is one paste away.
 */
class OpenSphereActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val bridge = LocalClaudeBridge()
            val sphere = try {
                withContext(Dispatchers.IO) { bridge.sphereCode() }
            } catch (err: Exception) {
                val message = if (isBackendUnreachable(err)) autoStartBackendMessage(app) else err.message ?: "aucune sphère reçue"
                toastOnMain(app, "Pas de sphère à ouvrir : $message")
                finish()
                return@launch
            }
            copyToClipboard(this@OpenSphereActivity, sphere.code)
            val url = yourMineSphereUrl(sphere.name, sphere.code)
            val opened = openUrl(app, url ?: YOURMINE_URL)
            toastOnMain(
                app,
                when {
                    !opened -> "Sphère « ${sphere.name} » copiée, mais rien n'a pu ouvrir YourMine : ouvre-le et colle-la dans Build → Apps."
                    url == null -> "Sphère « ${sphere.name} » copiée (trop grosse pour l'adresse) : dans YourMine, Build → Apps, colle-la."
                    else -> "Sphère « ${sphere.name} » envoyée à YourMine — copiée aussi, au cas où le champ resterait vide."
                },
            )
            withContext(Dispatchers.IO) { try { bridge.sphereSeen() } catch (err: Exception) { } }
            BackendSync.refresh(bridge)
            AiwaWidget().updateAll(app)
            finish()
        }
    }
}
