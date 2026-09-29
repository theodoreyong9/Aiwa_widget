package com.aiwa.bridge
import kotlinx.coroutines.flow.Flow
data class SessionInfo(val id:String,val preview:String)
data class BackendStatus(val session:String?,val model:String?,val version:Int,val mode:String,val cloudSession:String?)
data class CloudSessionInfo(val id:String,val title:String,val url:String)
data class CloudSendResult(val ok:Boolean,val sessionId:String?,val url:String?,val error:String?)
interface ClaudeBridge{fun sendMessage(text:String):Flow<String>;suspend fun sendBackgroundInstruction(text:String);suspend fun listSessions():List<SessionInfo>;suspend fun selectSession(id:String?);suspend fun currentSessionId():String?;suspend fun fetchHistory(sessionId:String):String?;suspend fun status():BackendStatus;suspend fun selectModel(id:String?);suspend fun listCloudSessions():List<CloudSessionInfo>;suspend fun selectCloud(target:String);suspend fun sendCloud(text:String):CloudSendResult}
