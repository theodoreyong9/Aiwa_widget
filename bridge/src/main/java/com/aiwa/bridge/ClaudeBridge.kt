package com.aiwa.bridge

// state: off (no repository), waiting (the address doesn't answer yet) or live.
// kind: "site" (the GitHub Pages address) or "apk" (the download address of the
// Android APK, in the Android deploy mode).
data class SiteInfo(val url: String?, val state: String, val kind: String = "site")

// state: running, success, failure or none.
// fresh: a new green run the user has not been told about yet.
data class CiInfo(val state: String, val url: String?, val fresh: Boolean = false)

data class BackendStatus(
    val model: String?,
    // low / medium / high / xhigh / max, null = automatic.
    val effort: String? = null,
    val version: Int,
    val cloudSession: String?,
    // The session most recently in use, kept when the next message will
    // start a new one — so its conversation can still be opened.
    val lastSession: String? = null,
    val repo: String? = null,
    val pushMain: Boolean = true,
    // none / pages (GitHub Pages) / android (the APK as a GitHub release).
    val deploy: String = "none",
    val extra: String = "",
    // Claude pinged the relay: it is waiting for an answer. alertLast: epoch
    // seconds of the last ping ever received (null = never).
    val waiting: Boolean = false,
    val alertLast: Long? = null,
    val site: SiteInfo = SiteInfo(null, "off"),
    val ci: CiInfo? = null,
    val githubError: String? = null,
)

data class CloudSessionInfo(val id: String, val title: String, val url: String, val repo: String? = null)
data class CloudSendResult(val ok: Boolean, val sessionId: String?, val url: String?, val error: String?)
data class RepoInfo(val name: String, val isPrivate: Boolean)

interface ClaudeBridge {
    suspend fun status(): BackendStatus
    suspend fun selectModel(id: String?)
    suspend fun selectEffort(level: String?)
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
    suspend fun setOptions(pushMain: Boolean?, deploy: String?, extra: String?)

    // The user went to look at the latest Actions run: it is no longer news.
    suspend fun ciSeen()

    // The user opened the session (or answered): the waiting indicator goes off.
    suspend fun waitingClear()

    // A ping sent by the backend itself, to check the phone side of the alert.
    // Throws when the relay can't be reached.
    suspend fun waitingTest()

    // The block of instructions Claude Code would receive with the next message.
    suspend fun instructions(): String
}
