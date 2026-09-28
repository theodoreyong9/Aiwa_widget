package com.aiwa.widget
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.SessionInfo
import kotlinx.coroutines.launch
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{AiwaScreen()}}}}
private fun statusLabel(status:AiwaState.Status):String=when(status){AiwaState.Status.READY->"Prêt";AiwaState.Status.WORKING->"Travail…";AiwaState.Status.WAITING->"En attente de réponse";AiwaState.Status.DONE->"Terminé";AiwaState.Status.ERROR->"Erreur"}
@Composable private fun AiwaScreen(){
val context=LocalContext.current
val bridge=remember{LocalClaudeBridge()}
val scope=rememberCoroutineScope()
val state by AiwaRepository.state.collectAsState()
var input by remember{mutableStateOf("")}
var sessions by remember{mutableStateOf(listOf<SessionInfo>())}
var sessionMenuExpanded by remember{mutableStateOf(false)}
fun refreshWidget(){scope.launch{AiwaWidget().updateAll(context)}}
fun reportError(err:Exception){AiwaRepository.update{it.copy(status=AiwaState.Status.ERROR,output=it.output+"\n[erreur: ${err.message}]")}}
// Real speech-to-text via Android's own system recognizer — the
// previous 🎙️ icon (on the widget, and implicitly here) never called
// any STT API at all; this is the first real implementation. Needs
// RECORD_AUDIO (see AndroidManifest.xml) and a real speech-recognition
// service on the device (Google's app on most real phones); if
// neither is present the launched intent itself will fail visibly
// rather than silently doing nothing.
val speechLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
val heard=result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
if(!heard.isNullOrBlank())input=heard
}
fun launchDictation(){
val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)}
try{speechLauncher.launch(intent)}catch(err:Exception){reportError(err)}
}
val micPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)launchDictation()}
fun startDictation(){
val granted=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
if(granted)launchDictation() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}
// Reported live: "Not allowed to start service Intent ... without
// permission com.termux.permission.RUN_COMMAND" — declaring the
// permission in the manifest was never enough on its own; like
// RECORD_AUDIO above, a dangerous permission still needs an actual
// runtime request, which this button never did. The widget's own "▶"
// button (StartBackendAction.kt) has no Activity context to show a
// permission dialog from, so it can only ever work AFTER this one has
// been granted at least once here — permissions are per-app, not
// per-component, so one grant covers both.
fun launchTermuxBackend(){
val result=startAiwaBackendViaTermux(context)
AiwaRepository.update{
// Reported live: using WORKING here left the "Envoyer" button
// permanently disabled (enabled=status!=WORKING), because nothing
// ever clears it afterward — there's no real signal for "Termux
// actually finished starting the server", only whether the
// launch intent itself was accepted. WORKING must stay reserved
// for an actual in-flight Claude request (see the send button's
// own coroutine below); this only ever reports acceptance, so it
// keeps status READY and lets the user just try sending — a
// connection error there is the real, honest signal either way.
if(result.isSuccess)it.copy(status=AiwaState.Status.READY,output="Termux démarré en arrière-plan — laisse-lui quelques secondes puis essaie d'envoyer un message.")
else it.copy(status=AiwaState.Status.ERROR,output="Impossible de lancer Termux : ${result.exceptionOrNull()?.message}")
}
refreshWidget()
}
val termuxPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
if(granted)launchTermuxBackend()
else AiwaRepository.update{it.copy(status=AiwaState.Status.ERROR,output="Permission Termux refusée — impossible de démarrer le backend automatiquement.")}
}
fun startTermuxBackend(){
val granted=ContextCompat.checkSelfPermission(context,"com.termux.permission.RUN_COMMAND")==PackageManager.PERMISSION_GRANTED
if(granted)launchTermuxBackend() else termuxPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
}
// Reported live as a genuine usability question: "is tapping this
// button every time mandatory, why isn't it automatic?" — it can be:
// firing this once when the screen first appears removes the manual
// step entirely on every later app open. If the backend is already
// running, the fresh attempt this fires just fails harmlessly inside
// Termux (port already bound) without disturbing the one already up —
// see backend/start.sh. The manual button stays too, for an explicit
// retry after intentionally stopping the backend.
LaunchedEffect(Unit){startTermuxBackend()}
Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
Text("AIWA",style=MaterialTheme.typography.headlineMedium)
Box{
Text("Session : ${state.session}",modifier=Modifier.clickable{
scope.launch{
try{sessions=bridge.listSessions();sessionMenuExpanded=true}
catch(err:Exception){reportError(err)}
}
})
DropdownMenu(expanded=sessionMenuExpanded,onDismissRequest={sessionMenuExpanded=false}){
DropdownMenuItem(text={Text("Nouvelle session")},onClick={
sessionMenuExpanded=false
scope.launch{
try{bridge.selectSession(null);AiwaRepository.update{it.copy(session="nouvelle")}}
catch(err:Exception){reportError(err)}
}
})
for(s in sessions){
DropdownMenuItem(text={Text(s.preview)},onClick={
sessionMenuExpanded=false
scope.launch{
try{bridge.selectSession(s.id);AiwaRepository.update{it.copy(session=s.preview)}}
catch(err:Exception){reportError(err)}
}
})
}
}
}
Text(statusLabel(state.status))
Text(state.output.ifBlank{"La réponse Claude apparaîtra ici."})
// The explicit button here became redundant once LaunchedEffect above
// started firing the same launch automatically on every app open —
// reported live as a fair "might as well remove it" once that was
// confirmed. The widget's own "▶" (StartBackendAction.kt) stays: it
// has real, different value, letting the backend start from the home
// screen without ever opening the app at all.
OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth(),label={Text("Message")})
Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
Button(onClick={startDictation()}){Text("🎙️")}
Button(enabled=state.status!=AiwaState.Status.WORKING,onClick={
val text=input
scope.launch{
AiwaRepository.update{it.copy(status=AiwaState.Status.WORKING,output="")}
refreshWidget()
try{
bridge.sendMessage(text).collect{chunk->AiwaRepository.update{it.copy(output=it.output+chunk)}}
// Reported live as confusing: the app kept showing the "Aiwa"
// placeholder label forever, so the real backend-assigned
// conversation id (which DOES persist correctly — see
// current_session in aiwa_server.py) was never visible anywhere in
// the app, only ever in the picker's own list. Surfacing it here,
// once a message actually completes, makes the displayed session
// reflect reality instead of a name that was never real.
val realSessionId=try{bridge.currentSessionId()}catch(err:Exception){null}
AiwaRepository.update{it.copy(status=AiwaState.Status.DONE,session=realSessionId?.take(8)?:it.session)}
}catch(err:Exception){
reportError(err)
}
refreshWidget()
}
}){Text("➤ Envoyer")}
// "Fond" (background instruction) is deliberately not exposed here:
// the backend's own /api/background always rejects it
// ({"accepted": false, "reason": "not wired yet"}) until its real
// transport is verified — see docs/claude-code.md. A button that
// always silently fails is worse than no button.
}
}
}
