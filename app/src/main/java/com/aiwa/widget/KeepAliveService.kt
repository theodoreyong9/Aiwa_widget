package com.aiwa.widget
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.glance.appwidget.updateAll
import com.aiwa.bridge.LocalClaudeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A new channel: the old one ("aiwa_keepalive") was created with the lowest
// importance, which keeps a notification off the lock screen, and a channel's
// importance cannot be raised from code once it exists.
private const val CHANNEL_ID = "aiwa_lockscreen"
private const val OLD_CHANNEL_ID = "aiwa_keepalive"
private const val NOTIFICATION_ID = 1
// A sphere Claude sent gets a notification of its own (with a sound and on the lock screen): the
// card below is silent and permanent, and a new sphere is news.
private const val SPHERE_CHANNEL_ID = "aiwa_sphere"
private const val SPHERE_NOTIFICATION_ID = 2

/** What the lock-screen card says, and what a tap on it opens. */
private data class Card(val title: String, val status: String, val detail: String, val target: Class<*>)

/**
 * Reported live: the widget's mic (DictateActivity) briefly flashing
 * the app open, on the first tap after Android evicted Aiwa's process
 * for being idle in the background, is real OS process-eviction
 * behavior — not something fixable by changing DictateActivity itself
 * (already tried: Theme.Dictate's windowDisablePreview only hides the
 * cold-start preview window, it can't skip the cold start itself).
 * Explicitly accepted trade-off: a real foreground service is the only
 * way to keep Aiwa's own process resident so that cold start basically
 * never has to happen. This costs a permanent low-priority notification
 * (Android requires one for any foreground service — cannot be hidden
 * entirely) and a small, constant memory/battery footprint. It does
 * nothing itself beyond existing — no extra wake locks.
 *
 * One small job since: every few seconds it asks the local backend for its
 * state and redraws the widget when something the widget shows has changed
 * (Claude waiting for an answer, the site going live, the current session,
 * the backend going down or starting). It also restarts the backend, through
 * Termux, when it finds it down, or older than this app expects. Every way
 * Aiwa's process starts (wakeAiwa in AiwaApp.kt) starts this service.
 * Without it the widget only ever refreshed when it was tapped.
 *
 * The lock screen: this service's own notification is a MEDIA notification, backed by
 * a MediaSession — the mechanism a web page uses when its audio shows a player on
 * the lock screen (the YourMine radio sphere does exactly that). It is a card with
 * the name of the session, what is going on ("Prêt", "Claude attend ta réponse"…), and
 * the logo; tapping it opens the dictation, or Claude's conversation while Claude
 * waits, or the fix when Claude is not connected, or the sphere Claude sent (the phone
 * asks to unlock first). It is silent and permanent. No audio ever
 * plays: the session only reports a non-advancing "playing" state so that the
 * system keeps the card (a paused one is dropped after ten minutes).
 */
class KeepAliveService : Service() {
    private var poller: Job? = null
    private var watcher: Job? = null
    private var session: MediaSession? = null
    private var logo: Bitmap? = null
    private var shown: Card? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(CHANNEL_ID, "Aiwa sur l'écran verrouillé", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(channel)
        val sphereChannel = NotificationChannel(SPHERE_CHANNEL_ID, "Sphère reçue de Claude", NotificationManager.IMPORTANCE_HIGH)
        sphereChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(sphereChannel)
        logo = BitmapFactory.decodeResource(resources, R.drawable.yourmine_logo)
        val mediaSession = MediaSession(this, "Aiwa")
        mediaSession.setCallback(object : MediaSession.Callback() {})
        // Set once: a session that goes back to "playing" would claim the headphone
        // buttons again from whatever the user is really listening to.
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(0L)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build(),
        )
        mediaSession.isActive = true
        session = mediaSession
        publish(cardFor(AiwaRepository.state.value), first = true)
        // The card follows the state at once, not at the next poll.
        watcher = CoroutineScope(Dispatchers.Default).launch {
            AiwaRepository.state.collect {
                publish(cardFor(it), first = false)
                announceSphere(it)
            }
        }
        if (poller?.isActive != true) poller = CoroutineScope(Dispatchers.IO).launch { pollBackend() }
    }

    // The same story as the widget's first band.
    private fun cardFor(state: AiwaState): Card {
        val hasSession = state.cloudSessionId != null || state.lastSessionId != null
        val loginNeeded = state.claudeLogin == "needed"
        val sphereReady = state.deploy == "sphere" && state.sphere?.seen == false
        val status = when {
            state.backend == "starting" -> "⏳ Démarrage du backend…"
            state.backend == "down" -> "⚠ Backend arrêté — relance en cours"
            loginNeeded -> "⚠ Claude n'est pas connecté — touche pour le connecter"
            state.status == AiwaState.Status.WORKING -> "Envoi en cours…"
            state.waiting -> "● Claude attend ta réponse"
            sphereReady -> "⬡ Sphère prête — touche pour l'ouvrir dans YourMine"
            else -> "Prêt"
        }
        val deploy = when (state.deploy) {
            "pages" -> "Deploy ●"
            "android" -> "Android ●"
            "sphere" -> "Sphère ●"
            else -> "Deploy ○"
        }
        val target: Class<*> = when {
            loginNeeded -> ClaudeLoginActivity::class.java
            state.waiting && hasSession -> OpenClaudeActivity::class.java
            sphereReady -> OpenSphereActivity::class.java
            else -> DictateActivity::class.java
        }
        val detail = state.repo?.let { "⎇ ${it.substringAfter('/')} · " + (if (state.pushMain) "Push main" else "Push branche") + " · $deploy" } ?: "Aucun dépôt choisi"
        return Card(state.session, status, detail, target)
    }

    // A new sphere (not yet opened, not yet announced): a notification that opens it.
    private fun announceSphere(state: AiwaState) {
        val sphere = state.sphere ?: return
        if (sphere.seen) return
        val prefs = getSharedPreferences("aiwa_hints", MODE_PRIVATE)
        if (prefs.getLong("sphere_told", 0L) == sphere.ts) return
        prefs.edit().putLong("sphere_told", sphere.ts).apply()
        val tap = PendingIntent.getActivity(
            this, 1, Intent(this, OpenSphereActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, SPHERE_CHANNEL_ID)
            .setContentTitle("Sphère prête : ${sphere.name}")
            .setContentText("Touche pour l'ouvrir dans YourMine")
            .setSmallIcon(R.drawable.ic_sphere)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(SPHERE_NOTIFICATION_ID, notification)
    }

    @Synchronized
    private fun publish(card: Card, first: Boolean) {
        if (!first && card == shown) return
        shown = card
        val mediaSession = session ?: return
        // A tap opens what the card is about: the dictation, Claude's conversation while Claude
        // waits, the login when Claude is not connected, or the sphere Claude sent.
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, card.target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession.setSessionActivity(tap)
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, card.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, card.status)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, card.detail)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, logo)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, logo)
                .build(),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(card.title)
            .setContentText(card.status)
            .setSubText(card.detail)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setLargeIcon(logo)
            .setContentIntent(tap)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .build()
        if (first) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    // Keeps the widget honest: the backend is the source of truth for what
    // it shows, and nothing else tells the widget that Claude started to wait.
    private suspend fun pollBackend() {
        val bridge = LocalClaudeBridge()
        var shown = widgetKey()
        var lastLaunch = 0L
        var versionKicked = false
        while (true) {
            try {
                BackendSync.refresh(bridge)
                // An older server still answers after an app update (nothing opened
                // the app to replace it): update and restart it, once.
                val current = AiwaRepository.state.value
                if (!versionKicked && current.backend == "up" && current.backendVersion in 1 until EXPECTED_BACKEND_VERSION) {
                    versionKicked = true
                    lastLaunch = System.currentTimeMillis()
                    startAiwaBackendViaTermux(applicationContext, forceRestart = true)
                    AiwaRepository.markBackendStarting()
                }
                // Down: bring it back on its own (at most every 90 s), so it is
                // usually up already when you tap — a cold start takes a while.
                if (AiwaRepository.state.value.backend == "down" && System.currentTimeMillis() - lastLaunch > 90_000) {
                    lastLaunch = System.currentTimeMillis()
                    startAiwaBackendViaTermux(applicationContext)
                }
                val now = widgetKey()
                if (now != shown) {
                    shown = now
                    AiwaWidget().updateAll(applicationContext)
                }
            } catch (err: Exception) {
                // Backend not up (yet): try again next round.
            }
            delay(10_000)
        }
    }

    private fun widgetKey(): List<Any?> {
        val state = AiwaRepository.state.value
        return listOf(state.backend, state.waiting, state.ciFresh, state.ciState, state.siteState, state.cloudSessionId, state.repo, state.extraRepos, state.model, state.pushMain, state.deploy, state.siteKind, state.claudeLogin, state.relayCloud, state.sphere)
    }

    override fun onDestroy() {
        poller?.cancel()
        watcher?.cancel()
        session?.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
