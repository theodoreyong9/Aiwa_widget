package com.aiwa.widget

import android.content.Context
import com.aiwa.bridge.BackendStatus
import com.aiwa.bridge.ClaudeBridge
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * The single place that copies the backend's real state (current cloud
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
        val fetched = try { bridge.listCloudSessions() } catch (err: Exception) { null }
        // Copied out: a property of a class from another module can't be
        // smart-cast.
        val cloudId = status.cloudSession
        synchronized(lock) {
            if (generation < applied) return
            applied = generation
            AiwaRepository.update { current ->
                val sessions = fetched ?: current.cloudSessions
                val title = sessions.find { it.id == cloudId }?.title?.take(30)
                current.copy(
                    session = title ?: cloudId?.take(12) ?: "Nouvelle session",
                    cloudSessionId = cloudId,
                    cloudSessions = sessions,
                    model = status.model,
                    backendVersion = status.version,
                    repo = status.repo,
                    pushMain = status.pushMain,
                    autodeploy = status.autodeploy,
                    extra = status.extra,
                    waiting = status.waiting,
                    alertAt = status.alertLast,
                    effort = status.effort,
                    siteUrl = status.site.url,
                    siteState = status.site.state,
                    githubError = status.githubError,
                )
            }
        }
    }
}

/**
 * A widget tap doesn't go through the app, so the backend may simply not be
 * running (Termux was stopped): a picker then showed an empty list and told
 * the user to open Aiwa — which starts it. Starts it here (Termux
 * RUN_COMMAND) and waits for it, so the picker shows real data. Returns
 * whether the backend answers.
 */
suspend fun ensureBackend(context: Context, bridge: ClaudeBridge): Boolean {
    try {
        bridge.status()
        return true
    } catch (err: Exception) {
        if (!isBackendUnreachable(err)) return true
    }
    startAiwaBackendViaTermux(context)
    return awaitBackendStatus(bridge, 30_000) != null
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
