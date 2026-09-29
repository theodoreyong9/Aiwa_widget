package com.aiwa.bridge

// state: off (no deployment asked), waiting (the Pages address doesn't
// answer yet) or live.
data class SiteInfo(val url: String?, val state: String)

data class BackendStatus(
    val model: String?,
    val version: Int,
    val cloudSession: String?,
    val repo: String? = null,
    val pushMain: Boolean = true,
    val autodeploy: Boolean = false,
    val notify: Boolean = false,
    val extra: String = "",
    val site: SiteInfo = SiteInfo(null, "off"),
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
    suspend fun addRepo(text: String)

    // The instructions integrated into the conversation. Only the ones that
    // are not null are changed.
    suspend fun setOptions(pushMain: Boolean?, autodeploy: Boolean?, notify: Boolean?, extra: String?)

    // The block of instructions Claude Code would receive with the next message.
    suspend fun instructions(): String
}
