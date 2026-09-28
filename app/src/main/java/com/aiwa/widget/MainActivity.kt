package com.aiwa.widget
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.launch
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{AiwaScreen()}}}}
@Composable private fun AiwaScreen(){val bridge=remember{LocalClaudeBridge()};val scope=rememberCoroutineScope();var input by remember{mutableStateOf("")};var output by remember{mutableStateOf("")};var status by remember{mutableStateOf("Prêt")};Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("AIWA",style=MaterialTheme.typography.headlineMedium);Text("Session : Aiwa");Text(status);Text(output.ifBlank{"La réponse Claude apparaîtra ici."});OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth(),label={Text("Message")});Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={scope.launch{status="Travail…";output="";bridge.sendMessage(input).collect{output+=it};status="Terminé"}}){Text("➤ Envoyer")};Button(onClick={scope.launch{bridge.sendBackgroundInstruction(input);status="Instruction envoyée"}}){Text("🧠 Fond")}}}}
