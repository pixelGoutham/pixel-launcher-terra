package com.pixel.pixellauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaTurntableService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickBestController(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        syncUnreadBadges()
        try {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = mgr
            val comp = ComponentName(this, MediaTurntableService::class.java)
            mgr.addOnActiveSessionsChangedListener(sessionListener, comp)
            pickBestController(mgr.getActiveSessions(comp))
        } catch (_: SecurityException) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        syncUnreadBadges()
        refreshSessions()
        val extras: Bundle = sbn?.notification?.extras ?: return
        val body = (extras.getCharSequence("android.text") ?: "").toString()
        if (body.contains("OTP", ignoreCase = true) || body.contains("password", ignoreCase = true)) {
            val match = Regex("\\b\\d{4,8}\\b").find(body)
            if (match != null) {
                latestOtp = match.value
                onNotificationUpdate?.invoke()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        syncUnreadBadges()
        refreshSessions()
    }

    private fun syncUnreadBadges() {
        try {
            val map = mutableMapOf<String, Int>()
            for (sbn in activeNotifications ?: emptyArray()) {
                if (!sbn.isOngoing) {
                    val pkg = sbn.packageName
                    val count = sbn.notification.number.coerceAtLeast(1)
                    map[pkg] = (map[pkg] ?: 0) + count
                }
            }
            unreadCounts.clear()
            unreadCounts.putAll(map)
            onNotificationUpdate?.invoke()
        } catch (_: Exception) {}
    }

    fun refreshSessions() {
        try {
            val mgr = sessionManager ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
            val comp = ComponentName(this, MediaTurntableService::class.java)
            pickBestController(mgr.getActiveSessions(comp))
        } catch (_: SecurityException) {}
    }

    private fun pickBestController(controllers: List<MediaController>?) {
        val picked = controllers?.firstOrNull {
            it.packageName.contains("youtube.music")
        } ?: controllers?.firstOrNull()
        bindController(picked)
    }

    private fun bindController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) {
            pullMetadata()
            return
        }
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller
        activeController?.registerCallback(mediaCallback)
        pullMetadata()
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pullMetadata()
        override fun onMetadataChanged(metadata: MediaMetadata?) = pullMetadata()
    }

    private fun pullMetadata() {
        val ctrl = activeController
        val meta = ctrl?.metadata
        val state = ctrl?.playbackState

        isPlaying = state?.state == PlaybackState.STATE_PLAYING
        trackTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "YT Music Ready"
        trackArtist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Rotate disc L/R to skip * Tap to Play"
        albumArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        onStateChanged?.invoke()
    }

    companion object {
        var instance: MediaTurntableService? = null
        private var activeController: MediaController? = null

        var isPlaying: Boolean = false
        var trackTitle: String = "YT Music Ready"
        var trackArtist: String = "Tap to launch * Rotate disc to skip"
        var albumArt: Bitmap? = null
        var latestOtp: String? = null
        val unreadCounts = mutableMapOf<String, Int>()
        var onStateChanged: (() -> Unit)? = null
        var onNotificationUpdate: (() -> Unit)? = null

        fun togglePlayPause(): Boolean {
            val ctrl = activeController ?: return false
            if (isPlaying) ctrl.transportControls.pause() else ctrl.transportControls.play()
            return true
        }

        fun skipNext(): Boolean {
            val ctrl = activeController ?: return false
            ctrl.transportControls.skipToNext()
            return true
        }

        fun skipPrev(): Boolean {
            val ctrl = activeController ?: return false
            ctrl.transportControls.skipToPrevious()
            return true
        }
    }
}