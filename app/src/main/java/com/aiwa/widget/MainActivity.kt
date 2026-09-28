package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.launch
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{AiwaScreen()}}}}
private fun statusLabel(status:AiwaState.Status):String=when(status){AiwaState.Status.READY->"Prêt";AiwaState.Status.WORKING->"Travail…";AiwaState.Status.WAITING->"En attente de réponse";AiwaState.Status.DONE->"Terminé";AiwaState.Status.ERROR->"Erreur"}
@Composable private fun AiwaScreen(){
val context=LocalContext.current
val bridge=remember{LocalClaudeBridge()}
val scope=rememberCoroutineScope()
val state by AiwaRepository.state.collectAsState()
var input by remember{mutableStateOf("")}
fun refreshWidget(){scope.launch{AiwaWidget().updateAll(context)}}
Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
Text("AIWA",style=MaterialTheme.typography.headlineMedium)
Text("Session : ${state.session}")
Text(statusLabel(state.status))
Text(state.output.ifBlank{"La réponse Claude apparaîtra ici."})
OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth(),label={Text("Message")})
Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
Button(enabled=state.status!=AiwaState.Status.WORKING,onClick={
val text=input
scope.launch{
AiwaRepository.update{it.copy(status=AiwaState.Status.WORKING,output="")}
refreshWidget()
try{
bridge.sendMessage(text).collect{chunk->AiwaRepository.update{it.copy(output=it.output+chunk)}}
AiwaRepository.update{it.copy(status=AiwaState.Status.DONE)}
}catch(err:Exception){
AiwaRepository.update{it.copy(status=AiwaState.Status.ERROR,output=it.output+"\n[erreur: ${err.message}]")}
}
refreshWidget()
}
}){Text("➤ Envoyer")}
Button(onClick={
scope.launch{
try{bridge.sendBackgroundInstruction(input)}
catch(err:Exception){AiwaRepository.update{it.copy(status=AiwaState.Status.ERROR,output=it.output+"\n[erreur: ${err.message}]")}}
}
}){Text("🧠 Fond")}
}
}
}
