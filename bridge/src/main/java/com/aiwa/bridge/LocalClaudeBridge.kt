package com.aiwa.bridge

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val POLL_INTERVAL_MS = 150L
private const val POLL_TIMEOUT_MS = 5 * 60_000L

class LocalClaudeBridge(private val baseUrl: String = "http://127.0.0.1:8787") : ClaudeBridge {

    private fun postText(path: String, text: String): String {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.outputStream.use { it.write(text.toByteArray()) }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun getText(path: String): String {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * The backend's own /api/events is a real poll-and-clear queue, not
     * a live stream — a single GET right after the POST returns only
     * whatever happened to land in that instant, and real work runs in
     * a background thread on the backend, so that GET always raced it.
     * That's why this used to silently produce nothing. This polls
     * repeatedly until a real "done" or "error" event arrives, bounded
     * by POLL_TIMEOUT_MS so a hung `claude` process can never poll
     * forever. Runs on Dispatchers.IO via flowOn below — every call
     * here is blocking network I/O.
     */
    override fun sendMessage(text: String): Flow<String> = flow {
        val acceptedRaw = postText("/api/message", text)
        if (!acceptedRaw.contains("\"accepted\":true") && !acceptedRaw.contains("\"accepted\": true")) {
            throw IllegalStateException("message not accepted: $acceptedRaw")
        }
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw TimeoutException("no response from Claude within ${POLL_TIMEOUT_MS}ms")
            }
            delay(POLL_INTERVAL_MS)
            val events = JSONArray(getText("/api/events"))
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                when (event.optString("type")) {
                    "text" -> {
                        val chunk = event.optString("text")
                        if (chunk.isNotEmpty()) emit(chunk)
                    }
                    "done" -> return@flow
                    "error" -> throw RuntimeException(event.optString("message", "unknown Claude error"))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun sendBackgroundInstruction(text: String) = withContext(Dispatchers.IO) {
        postText("/api/background", text)
        Unit
    }

    override suspend fun listSessions(): List<String> = withContext(Dispatchers.IO) {
        val array = JSONArray(getText("/api/sessions"))
        (0 until array.length()).map { array.getString(it) }
    }
}
