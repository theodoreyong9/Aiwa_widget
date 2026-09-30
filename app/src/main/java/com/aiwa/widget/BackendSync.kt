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
        val status = try {
            bridge.status()
        } catch (err: Exception) {
            if (isBackendUnreachable(err)) markBackendDown()
            return
        }
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
                    backend = "up",
                    session = title ?: cloudId?.take(12) ?: "Nouvelle session",
                    cloudSessionId = cloudId,
                    lastSessionId = status.lastSession,
                    cloudSessions = sessions,
                    model = status.model,
                    backendVersion = status.version,
                    repo = status.repo,
                    extraRepos = status.extraRepos,
                    pushMain = status.pushMain,
                    deploy = status.deploy,
                    extra = status.extra,
                    waiting = status.waiting,
                    alertAt = status.alertLast,
                    effort = status.effort,
                    siteUrl = status.site.url,
                    siteState = status.site.state,
                    siteKind = status.site.kind,
                    ciState = status.ci?.state,
                    ciUrl = status.ci?.url,
                    ciFresh = status.ci?.fresh ?: false,
                    githubError = status.githubError,
                    claudeLogin = status.claudeLogin,
                    relayCloud = status.relayCloud,
                    sphere = status.sphere,
                )
            }
        }
        AiwaRepository.persist()
    }

    // Down — unless Termux was asked to start it a moment ago (then it is
    // "starting", for at most a minute).
    private fun markBackendDown() {
        AiwaRepository.update {
            if (it.backend == "starting" && System.currentTimeMillis() - it.backendStartedAt < 60_000) it
            else it.copy(backend = "down")
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
    AiwaRepository.update { it.copy(backend = "down") }
    startAiwaBackendViaTermux(context)
    return awaitBackendStatus(bridge, 30_000) != null
}

/**
 * Polls until the backend runs AT LEAST this version, or gives up. When an
 * update replaces the server, the old one still answers for a few seconds
 * first: that must not be taken for "outdated" (which used to trigger a
 * second, useless restart).
 */
suspend fun awaitBackendVersion(bridge: ClaudeBridge, expected: Int, timeoutMs: Long): BackendStatus? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            val status = bridge.status()
            if (status.version >= expected) return status
        } catch (err: Exception) {
            // not up yet
        }
        delay(1500)
    }
    return null
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
