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
        BackendStatus(
            model = if (json.isNull("model")) null else json.optString("model").ifEmpty { null },
            version = json.optInt("version", 0),
            cloudSession = if (json.isNull("cloud_session")) null else json.optString("cloud_session").ifEmpty { null },
        )
    }

    // The model passed to `claude --model` when a NEW cloud session is
    // created; null = the CLI's own default.
    override suspend fun selectModel(id: String?) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/model", id ?: ""))
    }

    override suspend fun listCloudSessions(): List<CloudSessionInfo> = withContext(Dispatchers.IO) {
        val array = JSONArray(getText("/api/cloud/sessions"))
        (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            CloudSessionInfo(
                id = entry.getString("id"),
                title = entry.optString("title", entry.getString("id")),
                url = entry.optString("url"),
            )
        }
    }

    // target: "new" (the next message creates a cloud session) or the id
    // of one of listCloudSessions().
    override suspend fun selectCloud(target: String) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/cloud/select", target))
    }

    // An EXISTING cloud session, given its link or id (the CLI can't list
    // them); it becomes the current one.
    override suspend fun addCloud(link: String) = withContext(Dispatchers.IO) {
        requireAccepted(postText("/api/cloud/add", link))
    }

    // Synchronous on the backend: creating a session can take a while
    // (the cloud machine starts). HONEST LIMIT (documented by Anthropic):
    // the CLI only queues the message — there is no way to read the reply
    // back, so it is read in the Claude app.
    override suspend fun sendCloud(text: String): CloudSendResult = withContext(Dispatchers.IO) {
        val json = JSONObject(postText("/api/cloud/message", text))
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
}
