package com.aiwa.widget
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// One forced backend restart per app process at most: if the checkout
// can't be updated (no network), restarting again would only throw away
// the warm claude process every time for nothing.
private var restartedOutdatedBackend=false
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
var sessionMenuExpanded by remember{mutableStateOf(false)}
fun refreshWidget(){scope.launch{AiwaWidget().updateAll(context)}}
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
// Reported live, twice, insistently: "le micro enregistre et envoie
// lorsqu'il entend 'c'est bon vas-y'" — the previous one-shot
// RecognizerIntent sent as soon as ITS OWN silence-detection decided
// the user had stopped talking, which fired on a normal thinking pause
// just as easily as on actually being done. StopPhraseListener (shared
// with the widget's own mic, DictateActivity) keeps re-listening until
// the stop phrase is actually heard. listeningPartial!=null means it's
// currently active; its own text is shown live below instead of in the
// message field, since nothing has actually been "typed" yet.
var micListener by remember{mutableStateOf<StopPhraseListener?>(null)}
var listeningPartial by remember{mutableStateOf<String?>(null)}
fun stopListening(){micListener?.cancel();micListener=null;listeningPartial=null}
fun launchDictation(){
listeningPartial=""
micListener=StopPhraseListener(
context=context,
onPartial={listeningPartial=it},
onFinalText={text->listeningPartial=null;micListener=null;doSend(text)},
onGiveUp={listeningPartial=null;micListener=null},
)
micListener?.start()
}
val micPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)launchDictation()}
fun startDictation(){
val granted=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
if(granted)launchDictation() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}
DisposableEffect(Unit){onDispose{micListener?.cancel()}}
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
// No "Termux démarré…" message on success any more: it overwrote
// the transcript (and the history just loaded) on every app open,
// and the user asked for no informational messages.
if(result.isSuccess)it.copy(status=AiwaState.Status.READY)
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
// Reported live: "je veux que tu implémentes le micro widget sans
// ouvrir l'application dès la première utilisation" — explicitly
// accepted trade-off (see KeepAliveService.kt): a real foreground
// service keeps Aiwa's process resident so the widget's mic doesn't
// need a cold start after the process has been idle a while. Starting
// it here means it's running from the first time the app is opened,
// same bootstrap spot as the Termux auto-start above.
try{ContextCompat.startForegroundService(context,Intent(context,KeepAliveService::class.java))}catch(err:Exception){}
// Reported live: features silently missing because the backend
// running on the phone was older than this app (history never
// loaded — it predated /api/history). The backend reports its
// version. startAiwaBackendViaTermux above already pulls and restarts
// it when the checkout changed, so an old version seen right away may
// just be that restart still pending: wait, look again, and only then
// force one restart (at most once per process).
var backendStatus=awaitBackendStatus(bridge,30_000)
if(backendStatus!=null&&backendStatus.version<EXPECTED_BACKEND_VERSION){
delay(10_000)
backendStatus=awaitBackendStatus(bridge,30_000)
if(backendStatus!=null&&backendStatus.version<EXPECTED_BACKEND_VERSION&&!restartedOutdatedBackend){
restartedOutdatedBackend=true
startAiwaBackendViaTermux(context,forceRestart=true)
delay(5000)
awaitBackendStatus(bridge,30_000)
}
}
BackendSync.refresh(bridge)
val versionNow=AiwaRepository.state.value.backendVersion
if(backendStatus==null){
AiwaRepository.update{it.copy(status=AiwaState.Status.ERROR,output="Backend injoignable après 30 s. Vérifie que Termux est installé, que allow-external-apps=true est dans ~/.termux/termux.properties et que bootstrap.sh a déjà été lancé une fois.")}
}else if(versionNow<EXPECTED_BACKEND_VERSION){
AiwaRepository.update{it.copy(status=AiwaState.Status.ERROR,output="Backend obsolète (version $versionNow, il faut $EXPECTED_BACKEND_VERSION) et mise à jour automatique impossible. Dans Termux : cd ~/aiwa_widget && git pull && pkill -f aiwa_server.py, puis rouvre Aiwa.")}
}
}
Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
Text("AIWA",style=MaterialTheme.typography.headlineMedium)
Box{
Text("Session : ${state.session}",modifier=Modifier.clickable{
scope.launch{
BackendSync.refresh(bridge)
sessionMenuExpanded=true
}
})
DropdownMenu(expanded=sessionMenuExpanded,onDismissRequest={sessionMenuExpanded=false}){
// Both entries only ask the backend to switch and then re-read the
// backend's real state (BackendSync) — never a local guess. The
// history of a picked session is loaded by the effect above once
// the session id actually changes.
DropdownMenuItem(text={Text("Nouvelle session")},onClick={
sessionMenuExpanded=false
scope.launch{switchSession(context,bridge,null)}
})
for(s in state.sessions){
DropdownMenuItem(text={Text(s.preview)},onClick={
sessionMenuExpanded=false
scope.launch{switchSession(context,bridge,s.id)}
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
// Reported live, twice: dictation must not send on a normal pause —
// listeningPartial!=null replaces the input row with a live "still
// listening" card instead, since StopPhraseListener has no default
// dialog of its own (see its own HONEST LIMIT comment) and nothing
// visible would leave the user unsure whether they're still heard.
if(listeningPartial!=null){
Surface(shape=MaterialTheme.shapes.medium,tonalElevation=4.dp){
Column(Modifier.padding(12.dp)){
Text("🎙️ Écoute… dis « c'est bon vas-y » pour envoyer")
if(!listeningPartial.isNullOrBlank()){
Spacer(Modifier.height(4.dp))
Text(listeningPartial!!)
}
Spacer(Modifier.height(4.dp))
Row{
TextButton(onClick={stopListening()}){Text("Annuler")}
TextButton(onClick={
val heard=listeningPartial
stopListening()
if(!heard.isNullOrBlank())doSend(heard)
}){Text("Envoyer maintenant")}
}
}
}
} else {
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
}
