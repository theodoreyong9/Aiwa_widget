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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.SessionInfo
import kotlinx.coroutines.launch
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{AiwaScreen()}}}}
// "Travail…" alone left every message looking stuck for the first
// 20-30s (claude's real, confirmed cold-start delay before its first
// token) — this sets the right expectation instead of looking hung.
private fun statusLabel(status:AiwaState.Status):String=when(status){AiwaState.Status.READY->"Prêt";AiwaState.Status.WORKING->"Travail… (jusqu'à 30s, patiente)";AiwaState.Status.WAITING->"En attente de réponse";AiwaState.Status.DONE->"Terminé";AiwaState.Status.ERROR->"Erreur"}
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
// Reported live: "the mic fills the text field but I still have to
// tap Envoyer myself — that's ugly, it should be automatic". Both the
// manual "➤ Envoyer" button and dictation completing now go through
// this single function instead of duplicating the send coroutine.
// Reported live SEPARATELY: the widget's own mic (DictateActivity) had
// its OWN duplicate copy of this logic that never fetched the real
// session id — a real drift bug. sendAndTrack (MessageSender.kt) is
// now the one shared implementation both use, so that can't recur.
fun doSend(text:String){
// Reported live: "quand je passe du widget à l'app il y a un
// décalage... la réponse concerne l'avant-dernier input" — root cause:
// this used to reset status/output to WORKING/"" HERE, unconditionally,
// before even knowing whether the send would be accepted — so a
// busy-rejected attempt (a real request from elsewhere still in
// flight) still wiped that real request's own in-progress display,
// with nothing to restore it until that request finished. sendAndTrack
// now owns this reset entirely, and only performs it once the backend
// has actually confirmed acceptance (see its own comment).
scope.launch{
sendAndTrack(context,bridge,text)
refreshWidget()
}
}
// Real speech-to-text via Android's own system recognizer — the
// previous 🎙️ icon (on the widget, and implicitly here) never called
// any STT API at all; this is the first real implementation. Needs
// RECORD_AUDIO (see AndroidManifest.xml) and a real speech-recognition
// service on the device (Google's app on most real phones); if
// neither is present the launched intent itself will fail visibly
// rather than silently doing nothing.
val speechLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
val heard=result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
if(!heard.isNullOrBlank()){
input=heard
doSend(heard)
input=""
}
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
// runtime request, which only an Activity can show — this is why the
// backend auto-launch below lives here rather than directly in a
// widget ActionCallback, which has no such context.
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
LaunchedEffect(Unit){
// Reported live: "I have to restart the app to send the first
// message" — a real bug, not just the (separate, expected) ~20-30s
// cold-start wait. If the coroutine below sending a previous message
// got torn down without reaching its own catch block (Activity
// destroyed/recreated by Android while backgrounded, a config change,
// etc.), AiwaRepository's status stayed WORKING forever — a
// process-wide singleton, so it survives the Activity being recreated
// and permanently disables "Envoyer" (enabled=status!=WORKING) until
// the whole app PROCESS dies, which is the only thing that actually
// resets it. A fresh screen appearing is never mid-user-action, so
// any leftover WORKING here is stale by definition — safe to clear.
if(AiwaRepository.state.value.status==AiwaState.Status.WORKING){
AiwaRepository.update{it.copy(status=AiwaState.Status.READY)}
}
startTermuxBackend()
}
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
// Reported live: "je ne vois pas le texte total de la session" led
// to output accumulating a real transcript instead of being wiped
// per message — but switching to a genuinely DIFFERENT conversation
// should still start that transcript fresh, or old text bleeds into
// a session it was never part of.
try{bridge.selectSession(null);AiwaRepository.update{it.copy(session="nouvelle",sessionId=null,output="")}}
catch(err:Exception){reportError(err)}
}
})
for(s in sessions){
DropdownMenuItem(text={Text(s.preview)},onClick={
sessionMenuExpanded=false
scope.launch{
try{bridge.selectSession(s.id);AiwaRepository.update{it.copy(session=s.preview,sessionId=s.id,output="")}}
catch(err:Exception){reportError(err)}
}
})
}
}
}
Text(statusLabel(state.status))
// Reported live: "pourquoi je ne vois pas le texte total de la
// session en scroll" — output now accumulates the real conversation
// transcript (see sendAndTrack), but a plain Text with no scroll
// modifier just clips anything past the screen. weight(1f) lets this
// take whatever space the header/status/input/buttons around it
// don't use, and verticalScroll makes long text actually scrollable.
Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())){
Text(state.output.ifBlank{"La réponse Claude apparaîtra ici."})
}
// The explicit button here became redundant once LaunchedEffect above
// started firing the same launch automatically on every app open —
// reported live as a fair "might as well remove it" once that was
// confirmed. The widget itself was later stripped down to just
// session/mic/response (explicit request for a minimal design), so it
// no longer has its own separate start-backend button either —
// StartBackendAction.kt was removed as dead code.
// Reported live: "je les veux à droite du champ écrire, pas invisible
// quand le clavier s'ouvre" — the mic/send buttons used to sit in
// their own Row BELOW the full-width text field, which the keyboard
// could push off-screen entirely while typing. Same Row as the field
// now, field taking the remaining width via weight(1f), so the
// buttons stay next to it and visible regardless of the keyboard.
Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.weight(1f),label={Text("Message")})
Button(onClick={startDictation()}){Text("🎙️")}
Button(enabled=state.status!=AiwaState.Status.WORKING,onClick={
doSend(input)
input=""
}){Text("➤")}
// "Fond" (background instruction) is deliberately not exposed here:
// the backend's own /api/background always rejects it
// ({"accepted": false, "reason": "not wired yet"}) until its real
// transport is verified — see docs/claude-code.md. A button that
// always silently fails is worse than no button.
}
}
}
