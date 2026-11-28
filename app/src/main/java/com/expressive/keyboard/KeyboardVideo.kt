package com.expressive.keyboard

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.view.Surface
import android.view.TextureView
import android.view.View
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal fun importBackgroundVideo(context: Context, uri: Uri): Boolean = runCatching {
    val retriever = MediaMetadataRetriever()
    val rotation: Int
    try {
        retriever.setDataSource(context, uri)
        check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    } finally {
        retriever.release()
    }
    val dest = File(context.filesDir, KeyboardPrefs.BG_VIDEO_FILENAME)
    // Перепаковываем без аудиодорожки (без перекодирования): MediaPlayer не открывает AudioTrack
    // и не дёргает синхронизацию звука на старте и при зацикливании.
    val tmp = File(context.filesDir, "${KeyboardPrefs.BG_VIDEO_FILENAME}.mux.tmp")
    val remuxed = remuxVideoOnly(context, uri, tmp, rotation)
    if (remuxed) {
        if (!tmp.renameTo(dest)) {
            val file = AtomicFile(dest)
            val stream = file.startWrite()
            try {
                tmp.inputStream().use { it.copyTo(stream) }
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            } finally {
                tmp.delete()
            }
        }
        return@runCatching true
    }
    tmp.delete()
    val file = AtomicFile(dest)
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

/** Копирует только видеодорожку в MP4 без перекодирования. Возвращает false для неподдерживаемых контейнеров. */
private fun remuxVideoOnly(context: Context, uri: Uri, outFile: File, rotation: Int): Boolean {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    return try {
        extractor.setDataSource(context, uri, null)
        var videoTrack = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrack = i
                videoFormat = fmt
                break
            }
        }
        if (videoTrack < 0 || videoFormat == null) return false
        extractor.selectTrack(videoTrack)
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation in listOf(0, 90, 180, 270)) muxer.setOrientationHint(rotation)
        val outTrack = muxer.addTrack(videoFormat)
        muxer.start()
        started = true
        val maxInput = runCatching { videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(1024 * 1024)
        val buf = ByteBuffer.allocate(maxInput.coerceIn(256 * 1024, 4 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            info.size = extractor.readSampleData(buf, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            @Suppress("WrongConstant")
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buf, info)
            extractor.advance()
        }
        true
    } catch (_: Exception) {
        false
    } finally {
        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { extractor.release() }
    }
}

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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    if (alpha <= 0.01f) return
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
                it.setPlaying(started)
            }
        )
    }
}

private class VideoBackgroundView(context: Context, private val file: File) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private val gen = AtomicInteger(0)
    @Volatile private var wantPlay = false
    @Volatile private var released = false

    // Обращение к player, prepared и surface — только из videoExecutor, чтобы не блокировать главный поток.
    private var player: MediaPlayer? = null
    private var prepared = false
    private var activeSurface: Surface? = null

    private var videoSize = Size.Zero
    var frame = PhotoFrame()
        set(value) {
            if (field == value) return
            field = value
            updateTransform()
        }

    init {
        isOpaque = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Android 15+ (Pixel 9 120Hz LTPO): видеофон не должен сбрасывать частоту экрана клавиатуры до 30/60 Гц.
        if (Build.VERSION.SDK_INT >= 35) {
            requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
        }
        surfaceTextureListener = this
    }

    fun setPlaying(play: Boolean) {
        if (wantPlay == play) return
        wantPlay = play
        val token = gen.get()
        videoExecutor.execute {
            if (released || token != gen.get()) return@execute
            val media = player ?: return@execute
            if (!prepared) return@execute
            runCatching {
                if (play) {
                    if (!media.isPlaying) media.start()
                } else {
                    if (media.isPlaying) media.pause()
                }
            }
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val token = gen.incrementAndGet()
        val surface = Surface(texture).also { clearFrameRateVote(it) }
        videoExecutor.execute {
            if (released || token != gen.get()) {
                runCatching { surface.release() }
                return@execute
            }
            val existing = player
            if (existing != null && prepared) {
                val oldSurface = activeSurface
                activeSurface = surface
                val reattached = runCatching {
                    existing.setSurface(surface)
                    clearFrameRateVote(surface)
                    if (wantPlay && !existing.isPlaying) existing.start()
                }.isSuccess
                runCatching { oldSurface?.release() }
                if (reattached) return@execute
                // Если плеер не принял новую поверхность — пересоздаём его в фоне без задержки UI
                releaseOnExecutor()
                activeSurface = surface
            } else {
                releaseOnExecutor()
                activeSurface = surface
            }
            prepareOnExecutor(token, surface)
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        updateTransform()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val token = gen.incrementAndGet()
        // Возвращаем false и освобождаем SurfaceTexture ПОСЛЕ setSurface(null) в фоновом потоке,
        // иначе BufferQueue умирает раньше отключения декодера и ломает плеер при следующем открытии.
        videoExecutor.execute {
            if (token == gen.get()) {
                val media = player
                if (media != null && prepared) {
                    runCatching { if (media.isPlaying) media.pause() }
                    runCatching { media.setSurface(null) }
                }
                runCatching { activeSurface?.release() }
                activeSurface = null
            }
            runCatching { texture.release() }
        }
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun releasePlayer() {
        released = true
        gen.incrementAndGet()
        videoExecutor.execute { releaseOnExecutor() }
    }

    private fun clearFrameRateVote(surface: Surface) {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    private fun deselectAudioTracks(media: MediaPlayer) {
        runCatching {
            media.trackInfo.forEachIndexed { idx, info ->
                if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    runCatching { media.deselectTrack(idx) }
                }
            }
        }
    }

    private fun prepareOnExecutor(token: Int, surface: Surface) {
        val media = MediaPlayer()
        player = media
        prepared = false
        try {
            media.setSurface(surface)
            file.inputStream().use { media.setDataSource(it.fd) }
            media.setVolume(0f, 0f)
            media.isLooping = true
            media.setOnVideoSizeChangedListener { _, w, h ->
                post {
                    videoSize = Size(w.toFloat(), h.toFloat())
                    updateTransform()
                }
            }
            media.setOnPreparedListener { mp ->
                videoExecutor.execute {
                    if (released || token != gen.get() || player !== mp) return@execute
                    prepared = true
                    deselectAudioTracks(mp)
                    activeSurface?.let(::clearFrameRateVote)
                    if (wantPlay) {
                        runCatching { mp.start() }.onFailure { releaseOnExecutor() }
                    }
                }
            }
            media.setOnErrorListener { mp, _, _ ->
                videoExecutor.execute {
                    if (player === mp) releaseOnExecutor()
                }
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            releaseOnExecutor()
        }
    }

    private fun releaseOnExecutor() {
        prepared = false
        runCatching { player?.release() }
        player = null
        runCatching { activeSurface?.release() }
        activeSurface = null
    }

    private fun updateTransform() {
        if (width == 0 || height == 0 || videoSize.width <= 0f || videoSize.height <= 0f) return
        val bounds = backgroundFrame(videoSize, Size(width.toFloat(), height.toFloat()), frame)
        setTransform(Matrix().apply {
            setScale(bounds.width / width, bounds.height / height)
            postTranslate(bounds.left, bounds.top)
        })
    }

    companion object {
        private val videoExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "keyboard-video-bg").apply { isDaemon = true }
        }
    }
}
