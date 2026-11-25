package com.expressive.keyboard

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Android uses this component to authorize media-session access. */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() = ScriptHost.refreshMediaAccess()
    override fun onListenerDisconnected() = MediaBridge.stop()
}

internal object MediaBridge {
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controllers = emptyList<MediaController>()
    private var artKey: List<Any?>? = null
    private var artUri = ""

    val EMPTY = mapOf(
        "access" to false, "active" to false, "playing" to false,
        "title" to "", "artist" to "", "album" to "", "app" to "",
        "position" to 0.0, "duration" to 0.0
    )

    private fun component(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)
    fun hasAccess(ctx: Context): Boolean {
        val listener = component(ctx)
        if (Build.VERSION.SDK_INT >= 27) {
            return ctx.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(listener) == true
        }
        return Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == listener } == true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plugin_media_access", Context.MODE_PRIVATE)
    fun isGranted(ctx: Context, id: String) = prefs(ctx).getBoolean(id, false)
    fun setGranted(ctx: Context, id: String, granted: Boolean) {
        prefs(ctx).edit().apply { if (granted) putBoolean(id, true) else remove(id) }.apply()
        ScriptHost.refreshMediaAccess()
    }

    private fun session(ctx: Context): MediaController? {
        if (!hasAccess(ctx)) return null
        return runCatching {
            pick(ctx.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(component(ctx)).orEmpty())
        }.getOrNull()
    }

    private fun pick(list: List<MediaController>) =
        list.firstOrNull { isPlaying(it.playbackState) } ?: list.firstOrNull()

    private fun isPlaying(state: PlaybackState?) = state?.state == PlaybackState.STATE_PLAYING

    private fun describe(controller: MediaController?): Map<String, Any> {
        if (controller == null) return EMPTY + ("access" to true)
        val metadata = controller.metadata
        val playback = controller.playbackState
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0)
        var position = (playback?.position ?: 0L).coerceAtLeast(0)
        if (isPlaying(playback) && playback != null && playback.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime)
                .coerceAtLeast(0) * playback.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        return mapOf(
            "access" to true, "active" to true, "playing" to isPlaying(playback),
            "title" to metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().take(200),
            "artist" to metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(200),
            "album" to metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().take(200),
            "app" to controller.packageName, "position" to position.toDouble(), "duration" to duration.toDouble()
        )
    }

    fun state(ctx: Context): Map<String, Any> =
        if (hasAccess(ctx)) runCatching { describe(session(ctx)) }.getOrDefault(EMPTY) else EMPTY

    fun art(ctx: Context): String = runCatching {
        val controller = session(ctx) ?: return ""
        val metadata = controller.metadata ?: return ""
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return ""
        val key = listOf(controller.sessionToken, metadata.description.mediaId, metadata.description.title,
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), bitmap.generationId)
        if (artKey == key) return artUri
        val ratio = 128f / maxOf(bitmap.width, bitmap.height)
        val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) return ""
        artUri = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        artKey = key
        artUri
    }.getOrDefault("")

    fun control(ctx: Context, command: String, position: Long = 0): Boolean = runCatching {
        val controller = session(ctx) ?: return false
        val transport = controller.transportControls
        when (command) {
            "play" -> transport.play()
            "pause" -> transport.pause()
            "toggle" -> if (isPlaying(controller.playbackState)) transport.pause() else transport.play()
            "next" -> transport.skipToNext()
            "previous" -> transport.skipToPrevious()
            "seek" -> transport.seekTo(position.coerceAtLeast(0))
            else -> return false
        }
        true
    }.getOrDefault(false)

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = queuePublish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = queuePublish()
        override fun onSessionDestroyed() = queuePublish()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        if (manager != null) attach(list.orEmpty())
    }
    private val publish = Runnable {
        if (manager != null) runCatching { describe(pick(controllers)) }.onSuccess(ScriptHost::mediaChanged)
    }

    private fun queuePublish() {
        main.removeCallbacks(publish)
        main.post(publish)
    }

    fun sync(ctx: Context, wanted: Boolean) {
        if (!wanted || !hasAccess(ctx)) {
            stop()
            return
        }
        if (manager != null) return
        val service = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            service.addOnActiveSessionsChangedListener(sessionsListener, component(ctx), main)
            manager = service
            attach(service.getActiveSessions(component(ctx)))
        }.onFailure { stop() }
    }

    fun stop() {
        val service = manager ?: return
        manager = null
        runCatching { service.removeOnActiveSessionsChangedListener(sessionsListener) }
        attach(emptyList())
        main.removeCallbacks(publish)
        artKey = null
        artUri = ""
    }

    private fun attach(next: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = next
        next.forEach { runCatching { it.registerCallback(callback, main) } }
        queuePublish()
    }
}
