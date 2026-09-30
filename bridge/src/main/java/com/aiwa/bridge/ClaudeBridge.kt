package com.aiwa.bridge

// state: off (no repository), waiting (the address doesn't answer yet) or live.
// kind: "site" (the GitHub Pages address), "apk" (the download address of the
// Android APK, in the Android deploy mode) or "sphere" (no address: live means a sphere
// sent by Claude has not been opened yet).
data class SiteInfo(val url: String?, val state: String, val kind: String = "site")

// What Claude sent to the phone (its source: ClaudeBridge.sphereCode): kind "sphere" is a YourMine
// sphere (name.sphere.js), kind "aiwa" an Aiwa contract (name.aiwa.html, one self-contained
// index.html). seen: it was opened in YourMine / on the Aiwa wallet page already.
data class SphereInfo(val name: String, val size: Int, val ts: Long, val seen: Boolean, val kind: String = "sphere")
data class SphereCode(val name: String, val code: String, val kind: String = "sphere")

// The `claude auth login` the backend runs for the app. phase: idle / starting / url (the
// page to open is there, a code is awaited) / checking / done / failed; message: what the
// CLI said last, when it said something.
data class LoginStep(val phase: String, val url: String?, val message: String)

// state: running, success, failure or none — the verdict on the latest commit's runs together.
// fresh: a new green commit the user has not been told about yet. detail: which workflow, commit,
// event and author the verdict is about (so that a surprising "Prêt" can be explained).
data class CiInfo(val state: String, val url: String?, val fresh: Boolean = false, val detail: String? = null)

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
    // Other repositories Claude may ALSO work on (told to it with the next message).
    val extraRepos: List<String> = emptyList(),
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
    // Whether the CLI is logged in to a Claude account: ok / needed / unknown.
    val claudeLogin: String = "unknown",
    // Whether a command Claude runs in the cloud reaches the relay (its network access must
    // allow ntfy.sh): ok / untested / pending / missing.
    val relayCloud: String = "untested",
    val sphere: SphereInfo? = null,
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

    // Checks or unchecks a repository Claude may ALSO work on; null = none.
    suspend fun toggleExtraRepo(repo: String?)

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

    // ---- Connecting the CLI to a Claude account (the app's "Connecter Claude" window) ----

    // What the running login is up to (idle when none).
    suspend fun claudeLoginState(): LoginStep

    // Starts a login and waits for the page to open (phase url) — or for its failure.
    suspend fun claudeLoginStart(): LoginStep

    // Gives the code the login page shows; answers when the CLI has decided.
    suspend fun claudeLoginCode(code: String): LoginStep

    suspend fun claudeLoginCancel()

    // A fresh look at whether the CLI is logged in: ok / needed / unknown.
    suspend fun claudeCheck(): String

    // Asks the current session to run the relay test again. Throws with the reason.
    suspend fun relayRetest()

    // ---- The sphere Claude sent ----

    // Throws when none was received. asSphere: for an Aiwa contract, the YourMine sphere generated from it
    // instead (the same code, to open in YourMine).
    suspend fun sphereCode(asSphere: Boolean = false): SphereCode

    suspend fun sphereSeen()
}
