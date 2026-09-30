package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.AIWA_PROJECT_URL
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.YOURMINE_URL
import com.aiwa.bridge.aiwaContractUrl
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
 * The same button serves the "Aiwa" mode: what Claude sent is then a contract (name.aiwa.html) and
 * the page opened is the Aiwa wallet, whose "Publish as yourself" form gets the name and the code.
 * The YourMine sphere of that contract is not made here: it is pinned to the PUBLISHED contract
 * (its manifest id exists only after publication), so the Aiwa page offers it once published.
 *
 * Reading the code first is the job of the "</>" button next to it (CodeViewActivity); this one goes straight to the page.
 *
 * The code also goes to the clipboard, always: a page that does not yet know the hand-off
 * (its fragment is ignored there) simply opens, and the code is one paste away.
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
            val aiwa = sphere.kind == "aiwa"
            val contract = sphere.name.removeSuffix(".aiwa.html")
            val url = if (aiwa) aiwaContractUrl(contract, sphere.code) else yourMineSphereUrl(sphere.name, sphere.code)
            val opened = openUrl(app, url ?: if (aiwa) AIWA_PROJECT_URL else YOURMINE_URL)
            toastOnMain(
                app,
                when {
                    aiwa && !opened -> "App « $contract » copiée, mais rien n'a pu ouvrir la page Aiwa : ouvre-la et colle-la dans Actions → Smart contract."
                    aiwa && url == null -> "App « $contract » copiée (trop grosse pour l'adresse) : sur la page Aiwa, Actions → Smart contract, colle-la."
                    aiwa -> "App « $contract » envoyée à la page Aiwa : connecte ton wallet si besoin, publie-la dans Actions → Smart contract, la page te donnera ensuite la sphère YourMine (contrat copié aussi)."
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
