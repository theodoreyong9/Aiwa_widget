package com.aiwa.bridge
data class BackendStatus(val model:String?,val version:Int,val cloudSession:String?)
data class CloudSessionInfo(val id:String,val title:String,val url:String)
data class CloudSendResult(val ok:Boolean,val sessionId:String?,val url:String?,val error:String?)
interface ClaudeBridge{suspend fun status():BackendStatus;suspend fun selectModel(id:String?);suspend fun listCloudSessions():List<CloudSessionInfo>;suspend fun selectCloud(target:String);suspend fun addCloud(link:String);suspend fun sendCloud(text:String):CloudSendResult}
