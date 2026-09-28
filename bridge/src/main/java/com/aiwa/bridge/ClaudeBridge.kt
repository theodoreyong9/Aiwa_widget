package com.aiwa.bridge
import kotlinx.coroutines.flow.Flow
interface ClaudeBridge{fun sendMessage(text:String):Flow<String>;suspend fun sendBackgroundInstruction(text:String);suspend fun listSessions():List<String>}
