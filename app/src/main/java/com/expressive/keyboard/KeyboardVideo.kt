package com.expressive.keyboard

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import java.io.File

internal fun importBackgroundVideo(context: Context, uri: Uri): Boolean = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
    } finally {
        retriever.release()
    }
    val file = AtomicFile(File(context.filesDir, KeyboardPrefs.BG_VIDEO_FILENAME))
    val stream = file.startWrite()
    try {
        checkNotNull(context.contentResolver.openInputStream(uri)).use { it.copyTo(stream) }
        file.finishWrite(stream)
    } catch (error: Exception) {
        file.failWrite(stream)
        throw error
    }
    true
}.getOrDefault(false)

internal fun readBackgroundVideoFrame(context: Context): ImageBitmap? {
    val file = File(context.filesDir, KeyboardPrefs.BG_VIDEO_FILENAME)
    val retriever = MediaMetadataRetriever()
    return try {
        file.inputStream().use { retriever.setDataSource(it.fd) }
        if (Build.VERSION.SDK_INT >= 27) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if (width <= 0 || height <= 0) return null
            val rotated = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in listOf("90", "270")
            val scale = minOf(1f, 512f / maxOf(width, height))
            val w = ((if (rotated) height else width) * scale).toInt().coerceAtLeast(1)
            val h = ((if (rotated) width else height) * scale).toInt().coerceAtLeast(1)
            retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)?.asImageBitmap()
        } else {
            retriever.frameAtTime?.asImageBitmap()
        }
    } finally {
        retriever.release()
    }
}

@Composable
internal fun KeyboardVideo(settings: KeyboardSettings, modifier: Modifier, alpha: Float) {
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    if (!started || alpha <= 0.01f) return
    val context = LocalContext.current
    val file = remember(context) { File(context.filesDir, KeyboardPrefs.BG_VIDEO_FILENAME) }
    key(settings.bgImageVersion) {
        AndroidView(
            factory = { VideoBackgroundView(it, file) },
            modifier = if (settings.bgBlurDp > 0f && Build.VERSION.SDK_INT >= 31) {
                modifier.blur(settings.bgBlurDp.dp)
            } else modifier,
            onRelease = { it.releasePlayer() },
            update = {
                it.alpha = alpha
                it.frame = settings.photoFrame
            }
        )
    }
}

private class VideoBackgroundView(context: Context, private val file: File) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var videoSize = Size.Zero
    var frame = PhotoFrame()
        set(value) {
            field = value
            updateTransform()
        }

    init {
        isOpaque = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val media = MediaPlayer()
        player = media
        try {
            Surface(texture).let { surface ->
                try {
                    media.setSurface(surface)
                } finally {
                    surface.release()
                }
            }
            file.inputStream().use { media.setDataSource(it.fd) }
            media.setVolume(0f, 0f)
            media.isLooping = true
            media.setOnVideoSizeChangedListener { _, w, h ->
                videoSize = Size(w.toFloat(), h.toFloat())
                updateTransform()
            }
            media.setOnPreparedListener {
                runCatching { it.start() }.onFailure { releasePlayer() }
            }
            media.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        updateTransform()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun updateTransform() {
        if (width == 0 || height == 0 || videoSize.width <= 0f || videoSize.height <= 0f) return
        val bounds = backgroundFrame(videoSize, Size(width.toFloat(), height.toFloat()), frame)
        setTransform(Matrix().apply {
            setScale(bounds.width / width, bounds.height / height)
            postTranslate(bounds.left, bounds.top)
        })
    }
}
