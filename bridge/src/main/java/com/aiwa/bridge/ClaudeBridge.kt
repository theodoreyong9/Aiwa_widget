package com.aiwa.bridge

data class BackendStatus(
    val model: String?,
    val version: Int,
    val cloudSession: String?,
    val repo: String? = null,
    val pushMain: Boolean = true,
    val githubConnected: Boolean = false,
    val githubLogin: String? = null,
    val githubError: String? = null,
)

data class CloudSessionInfo(val id: String, val title: String, val url: String, val repo: String? = null)
data class CloudSendResult(val ok: Boolean, val sessionId: String?, val url: String?, val error: String?)
data class RepoInfo(val name: String, val isPrivate: Boolean)

interface ClaudeBridge {
    suspend fun status(): BackendStatus
    suspend fun selectModel(id: String?)
    suspend fun listCloudSessions(): List<CloudSessionInfo>
    suspend fun selectCloud(target: String)
    suspend fun addCloud(link: String)
    suspend fun sendCloud(text: String): CloudSendResult
    suspend fun sendCloudCommand(text: String): CloudSendResult
    suspend fun githubRepos(): List<RepoInfo>
    suspend fun selectRepo(repo: String?)
    suspend fun setPushMain(pushMain: Boolean)
}
