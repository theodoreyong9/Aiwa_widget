package com.aiwa.widget

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
        synchronized(lock) {
            if (generation < applied) return
            applied = generation
            AiwaRepository.update { current ->
                val sessions = fetched ?: current.sessions
                val label = sessions.find { it.id == status.session }?.preview?.take(40)
                    ?: status.session?.take(8)
                    ?: "Nouvelle session"
                current.copy(
                    session = label,
                    sessionId = status.session,
                    model = status.model,
                    sessions = sessions,
                    backendVersion = status.version,
                )
            }
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
