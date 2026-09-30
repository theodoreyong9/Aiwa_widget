package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.SphereCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows, read-only, the code of what Claude last sent — the contract (HTML) or the sphere (JS) — so it can
 * be read before anything is published or submitted. Fetched from the backend like the other buttons do
 * (the code is never put in an Intent: it can be large). Text can be selected; "Copier" puts it all on the
 * clipboard. Long lines are cut into pieces so the list stays light.
 */
class CodeViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAiwa(applicationContext)
        setContent {
            var shown by remember { mutableStateOf<SphereCode?>(null) }
            var problem by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    shown = withContext(Dispatchers.IO) { LocalClaudeBridge().sphereCode() }
                } catch (err: Exception) {
                    problem = if (isBackendUnreachable(err)) autoStartBackendMessage(applicationContext) else err.message ?: "aucun code reçu"
                }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        val code = shown
                        val failure = problem
                        val title = when {
                            code == null -> "Code reçu de Claude"
                            code.kind == "aiwa" -> "Contrat Aiwa : ${code.name}"
                            else -> "Sphère YourMine : ${code.name}"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when {
                                failure != null -> failure
                                code == null -> "Chargement…"
                                else -> "${code.code.length} caractères — lecture seule, le texte peut être sélectionné"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { if (code != null) copyToClipboard(this@CodeViewActivity, code.code) }, enabled = code != null) { Text("Copier") }
                            TextButton(onClick = { finish() }) { Text("Fermer") }
                        }
                        if (code != null) {
                            val lines = remember(code.code) {
                                code.code.lineSequence().flatMap { line -> if (line.isEmpty()) sequenceOf("") else line.chunked(400).asSequence() }.toList()
                            }
                            SelectionContainer(Modifier.weight(1f)) {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(lines) { line ->
                                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
