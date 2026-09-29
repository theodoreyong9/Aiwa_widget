package com.aiwa.widget

import com.aiwa.bridge.BackendOutdatedException
import com.aiwa.bridge.BackendStatus
import com.aiwa.bridge.ClaudeBridge
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * The single place that copies the backend's real state (current
 * session, model, session list) into AiwaRepository. Reported live: the
 * widget and the app kept disagreeing about the current session. Every
 * caller now goes through here instead of each writing its own guess.
 *
 * Overlapping refreshes can finish out of order; the one that STARTED
 * last always wins, so a slow, older answer can never overwrite a newer.
 */
object BackendSync {
    private val started = AtomicLong(0)
    private var applied = 0L
    private val lock = Any()

    suspend fun refresh(bridge: ClaudeBridge) {
        val generation = started.incrementAndGet()
        val status = try { bridge.status() } catch (err: Exception) { return }
        val fetched = try { bridge.listSessions() } catch (err: Exception) { null }
        val fetchedCloud = try { bridge.listCloudSessions() } catch (err: Exception) { null }
        val isCloud = status.mode == "cloud"
        // Copied out: a property of a class from another module can't be
        // smart-cast.
        val cloudId = status.cloudSession
        synchronized(lock) {
            if (generation < applied) return
            applied = generation
            AiwaRepository.update { current ->
                val sessions = fetched ?: current.sessions
                val cloudSessions = fetchedCloud ?: current.cloudSessions
                val label = if (isCloud) {
                    val title = cloudSessions.find { it.id == cloudId }?.title?.take(30)
                    "Cloud · " + (title ?: cloudId?.take(8) ?: "nouvelle")
                } else {
                    sessions.find { it.id == status.session }?.preview?.take(40)
                        ?: status.session?.take(8)
                        ?: "Nouvelle session"
                }
                current.copy(
                    session = label,
                    sessionId = status.session,
                    model = status.model,
                    sessions = sessions,
                    cloud = isCloud,
                    cloudSessionId = cloudId,
                    cloudSessions = cloudSessions,
                    backendVersion = status.version,
                )
            }
        }
        loadHistoryIfNeeded(bridge)
    }

    /**
     * Reported live, twice: resuming a session didn't bring back its
     * conversation, and the widget only showed new text after opening the
     * app and coming back. The history used to be loaded by an effect
     * inside the app's screen — so it only ever ran while that screen was
     * open, and the widget (a separate surface) never got it. Loading it
     * here, right after the backend's real session is known, covers every
     * surface at once. Skipped mid-request so it can't clobber a
     * streaming reply; the next refresh retries.
     */
    private suspend fun loadHistoryIfNeeded(bridge: ClaudeBridge) {
        val current = AiwaRepository.state.value
        // A cloud session has no history to load here: its conversation
        // lives in the Claude app.
        if (current.cloud) return
        val id = current.sessionId ?: return
        if (current.status == AiwaState.Status.WORKING || current.historyFor == id) return
        val history = try {
            bridge.fetchHistory(id)
        } catch (err: Exception) {
            val message = when (err) {
                is BackendOutdatedException -> err.message ?: "Backend obsolète"
                else -> "[historique indisponible : ${err.message}]"
            }
            AiwaRepository.update { if (it.sessionId == id && it.status != AiwaState.Status.WORKING) it.copy(output = message) else it }
            return
        }
        AiwaRepository.update {
            if (it.sessionId == id && it.status != AiwaState.Status.WORKING) it.copy(output = history ?: "", historyFor = id) else it
        }
    }
}

/** Polls until the backend answers (it may be starting up), or gives up. */
suspend fun awaitBackendStatus(bridge: ClaudeBridge, timeoutMs: Long): BackendStatus? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            return bridge.status()
        } catch (err: Exception) {
            delay(1500)
        }
    }
    return null
}
