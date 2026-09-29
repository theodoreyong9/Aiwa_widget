package com.aiwa.bridge

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The backend refused because a send is already running. Not a failure:
 * callers must not undo the request really in flight. */
class BusyException(message: String) : Exception(message)

/** The backend answered 404: it is older than this app expects. Distinct
 * from "not reachable" so callers can say what to actually do. */
class BackendOutdatedException(message: String) : Exception(message)

// A JSON null (or a missing key, or "") is Kotlin null; JSONObject.optString
// alone would turn a JSON null into the text "null".
private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

class LocalClaudeBridge(private val baseUrl: String = "http://127.0.0.1:8787") : ClaudeBridge {

    // A raw ConnectException's message is plumbing, not something that
    // tells anyone what to do; not being able to reach the local backend
    // is by far the most common failure, so it gets an actionable message.
    private fun <T> withClearConnectionError(block: () -> T): T = try {
        block()
    } catch (err: java.net.ConnectException) {
        throw IllegalStateException(
            "Backend not reachable at $baseUrl — it isn't started. Run `python3 backend/aiwa_server.py` on THIS device first (see README's \"Running this for real\").",
            err,
        )
    } catch (err: java.io.FileNotFoundException) {
        throw BackendOutdatedException(
            "Backend obsolète : il ne connaît pas cette fonction. Dans Termux : cd ~/aiwa_widget && git pull, puis rouvre Aiwa.",
        )
    }

    private fun postText(path: String, text: String): String = withClearConnectionError {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.outputStream.use { it.write(text.toByteArray()) }
        connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun getText(path: String): String = withClearConnectionError {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        connection.inputStream.bufferedReader().use { it.readText() }
    }

    // The backend answers {"accepted": false, ...} for a request it
    // refuses; ignoring that made callers believe something had changed.
    private fun requireAccepted(raw: String) {
        if (raw.contains("\"accepted\": false") || raw.contains("\"accepted\":false")) {
            if (raw.contains("busy")) throw BusyException("backend busy: $raw")
            throw IllegalStateException("backend refused: $raw")
        }
    }

    override suspend fun status(): BackendStatus = withContext(Dispatchers.IO) {
        val json = JSONObject(getText("/api/status"))
        val site = json.optJSONObject("site")
        val run = json.optJSONObject("ci")
        BackendStatus(
            model = json.str("model"),
            effort = json.str("effort"),
            version = json.optInt("version", 0),
            cloudSession = json.str("cloud_session"),
            lastSession = json.str("last_session"),
            repo = json.str("repo"),
            pushMain = json.optBoolean("push_main", true),
            // An older backend only says yes/no: yes was GitHub Pages.
            deploy = json.str("deploy") ?: if (json.optBoolean("autodeploy", false)) "pages" else "none",
            extra = json.str("extra") ?: "",
            waiting = json.optBoolean("waiting", false),
            alertLast = if (json.isNull("alert_last")) null else json.optLong("alert_last"),
            site = SiteInfo(site?.str("url"), site?.str("state") ?: "off", site?.str("kind") ?: "site"),
            ci = run?.let { CiInfo(it.str("state") ?: "none", it.str("url"), it.optBoolean("fresh", false)) },
            githubError = json.str("github_error"),
        )
    }

    // The model passed to `claude --model` when a NEW cloud session is
    // created; null = the CLI's own default.
    override suspend fun selectModel(id: String?) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/model", id ?: ""))
    }

    // low / medium / high / xhigh / max; null = automatic. Passed to
    // `claude --effort` when a NEW cloud session is created.
    override suspend fun selectEffort(level: String?) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/effort", level ?: ""))
    }

    override suspend fun listCloudSessions(): List<CloudSessionInfo> = withContext(Dispatchers.IO) {
        val array = JSONArray(getText("/api/cloud/sessions"))
        (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            CloudSessionInfo(
                id = entry.getString("id"),
                title = entry.optString("title", entry.getString("id")),
                url = entry.optString("url"),
                repo = entry.str("repo"),
            )
        }
    }

    // target: "new" (the next message creates a cloud session) or the id
    // of one of listCloudSessions().
    override suspend fun selectCloud(target: String) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/cloud/select", target))
    }

    // An EXISTING cloud session, given its link or id — or the name of its
    // branch (claude/…), which the backend looks up in the repositories it
    // knows (the CLI can't list sessions); it becomes the current one.
    // Throws with the reason when it can't be found.
    override suspend fun addCloud(link: String) = withContext(Dispatchers.IO) {
        val json = JSONObject(postText("/api/cloud/add", link))
        if (!json.optBoolean("accepted", false)) throw IllegalStateException(json.optString("reason", "session introuvable"))
    }

    // Synchronous on the backend: creating a session can take a while
    // (the cloud machine starts). HONEST LIMIT (documented by Anthropic):
    // the CLI only queues the message — there is no way to read the reply
    // back, so it is read in the Claude app.
    override suspend fun sendCloud(text: String): CloudSendResult = postCloud("/api/cloud/message", text)

    // A slash command (e.g. "/model opus") for the CURRENT cloud session:
    // never creates a session, fails when there is none.
    override suspend fun sendCloudCommand(text: String): CloudSendResult = postCloud("/api/cloud/command", text)

    private suspend fun postCloud(path: String, text: String): CloudSendResult = withContext(Dispatchers.IO) {
        val json = JSONObject(postText(path, text))
        if (json.optBoolean("ok", false)) {
            CloudSendResult(
                ok = true,
                sessionId = json.optString("session_id").ifEmpty { null },
                url = json.optString("url").ifEmpty { null },
                error = null,
            )
        } else {
            val error = json.optString("error", "erreur inconnue")
            if (error == "busy") throw BusyException("a cloud message is already being sent")
            CloudSendResult(ok = false, sessionId = null, url = null, error = error)
        }
    }

    // The repositories the connected GitHub account can push to.
    override suspend fun githubRepos(): List<RepoInfo> = withContext(Dispatchers.IO) {
        val json = JSONObject(getText("/api/github/repos"))
        if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "liste des dépôts indisponible"))
        val array = json.getJSONArray("repos")
        (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            RepoInfo(name = entry.getString("name"), isPrivate = entry.optBoolean("private", false))
        }
    }

    // null = the plain chat. Changing repository means the next message
    // starts a NEW session: a session's repository is fixed when it starts.
    override suspend fun selectRepo(repo: String?) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/repo", repo ?: ""))
    }

    // A repository given as a GitHub link or owner/name: remembered and selected.
    override suspend fun addRepo(text: String) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/github/add", text))
    }

    // Only the options that are not null are changed.
    override suspend fun setOptions(pushMain: Boolean?, deploy: String?, extra: String?) = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (pushMain != null) body.put("push_main", pushMain)
        if (deploy != null) body.put("deploy", deploy)
        if (extra != null) body.put("extra", extra)
        requireAccepted(postText("/api/options", body.toString()))
    }

    override suspend fun ciSeen() = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/ci/seen", ""))
    }

    override suspend fun waitingClear() = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/waiting/clear", ""))
    }

    override suspend fun waitingTest() = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/waiting/test", ""))
    }

    override suspend fun instructions(): String = withContext(Dispatchers.IO) {
        JSONObject(getText("/api/instructions")).optString("text", "")
    }
}
