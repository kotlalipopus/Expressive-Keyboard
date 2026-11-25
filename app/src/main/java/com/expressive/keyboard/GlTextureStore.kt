package com.expressive.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Base64

/**
 * Where the bytes of a plugin's texture file come from. The script engine registers a file when a gl layer names it
 * (`textures: { iTex0: "brick.png" }`), the GL thread reads it once, the first time the layer is drawn.
 * Nothing is decoded here: files stay base64 inside the plugin, or on disk for big ones.
 */
internal object GlTextureFiles {
    /** [stamp] changes when the file changes (plugin updated), so a texture uploaded earlier is replaced. */
    class Source(val stamp: Int, val load: () -> ByteArray?)

    private const val MAX = 64
    private val map: MutableMap<String, Source> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Source>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Source>?): Boolean = size > MAX
        }
    )

    /** false when the plugin has no such file (or, for a `user:` source, the user has not chosen a picture). */
    fun register(ctx: Context, plugin: KeyboardPlugin, path: String): Boolean {
        GlTextures.userOption(path)?.let { return registerUser(ctx.applicationContext, plugin, path, it) }
        val stored = plugin.files[path] ?: return false
        val app = ctx.applicationContext
        map[plugin.id + "/" + path] = Source(stored.hashCode()) {
            PluginArchive.bytes(app, plugin.id, stored, GlTextures.MAX_FILE_BYTES)
        }
        return true
    }

    /** A picture the user chose in one of the plugin's own `image` settings; a plugin can never name another plugin's picture. */
    private fun registerUser(app: Context, plugin: KeyboardPlugin, path: String, option: String): Boolean {
        if (plugin.config.none { it.id == option && it.type == "image" }) return false
        val selected = plugin.config.first { it.id == option && it.type == "image" }
        if (ModConfig.get(app, plugin, selected) != path) return false
        map[plugin.id + "/" + path] = Source(path.hashCode()) { UserPictures.readTexture(app, plugin, path) }
        return true
    }

    fun get(pluginId: String, path: String): Source? = map[pluginId + "/" + path]


}

/**
 * The textures of one GL renderer. Used on its GL thread only. A texture is created the first time a shader needs it,
 * kept while the layer still lists it, and deleted when the layer stops listing it or the file changes.
 * Pixel (0, 0) of an image is its bottom-left corner, like vUv, so `texture(iTex0, vUv)` shows the picture upright.
 * Colors are premultiplied by alpha, like everything the layer draws.
 */
internal class GlTexturePool(private val glVersion: () -> Int) {
    private class Tex(val id: Int, val w: Int, val h: Int, val stamp: Int, val shared: Boolean)

    class Entry(val index: Int, val spec: GlTexSpec)

    private val cache = HashMap<String, Tex>()
    private val errors = LinkedHashMap<String, String>()
    private var byName: Map<String, Entry> = emptyMap()
    private var synced: OverlayItem.GlSurface? = null
    private var fallback = 0
    private var blank = 0
    private var maxSide = 0

    /** Call at the start of every frame: cheap unless the layer item changed. */
    fun sync(item: OverlayItem.GlSurface) {
        if (item === synced) return
        synced = item
        byName = item.textures.withIndex().associate { it.value.name to Entry(it.index, it.value) }
        val live = HashMap<String, GlTexSpec>()
        for (s in item.textures) live[s.key] = s
        val entries = cache.entries.iterator()
        while (entries.hasNext()) {
            val e = entries.next()
            val spec = live[e.key]
            if (spec == null || e.value.stamp != stampOf(item, spec)) {
                drop(e.value)
                entries.remove()
            }
        }
        errors.keys.retainAll(item.textures.map { s -> s.name }.toSet())
    }

    fun entry(name: String): Entry? = byName[name]

    /** Binds the texture of [e] to its own unit (unit 0 belongs to iPrev) and points the sampler uniform at it. */
    fun bind(item: OverlayItem.GlSurface, e: Entry, loc: Int) {
        val tex = texture(item, e.spec)
        val unit = 1 + e.index
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
        GLES20.glUniform1i(loc, unit)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun size(item: OverlayItem.GlSurface, e: Entry): FloatArray {
        val tex = texture(item, e.spec)
        return floatArrayOf(tex.w.toFloat(), tex.h.toFloat())
    }

    /** The context is going away with its thread: the textures go with it. */
    fun release() {
        cache.clear()
        errors.clear()
        byName = emptyMap()
        synced = null
        fallback = 0
        blank = 0
    }

    private fun stampOf(item: OverlayItem.GlSurface, spec: GlTexSpec): Int =
        if (spec.isEmpty || spec.src.startsWith("data:")) 0 else GlTextureFiles.get(item.pluginId, spec.src)?.stamp ?: -1

    private fun drop(t: Tex) {
        if (!t.shared && t.id != 0) GLES20.glDeleteTextures(1, intArrayOf(t.id), 0)
    }

    private fun texture(item: OverlayItem.GlSurface, spec: GlTexSpec): Tex {
        cache[spec.key]?.let { return it }
        if (spec.isEmpty) return Tex(blankId(), 1, 1, 0, true).also { cache[spec.key] = it }
        val stamp = stampOf(item, spec)
        val tex = try {
            upload(item, spec, stamp)
        } catch (t: Throwable) {
            // a broken image is reported once and replaced by a 1x1 magenta pixel; it is not decoded again every frame
            errors[spec.name] = "texture ${spec.name}: ${t.message ?: t.javaClass.simpleName}"
            GlSupport.fail(item.frag, errors.values.joinToString("; "))
            Tex(fallbackId(), 1, 1, stamp, true)
        }
        cache[spec.key] = tex
        return tex
    }

    private fun limit(): Int {
        if (maxSide == 0) {
            val v = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0)
            maxSide = minOf(GlTextures.MAX_SIDE, if (v[0] > 0) v[0] else 2048)
        }
        return maxSide
    }

    private fun fallbackId(): Int {
        if (fallback == 0) fallback = onePixel(byteArrayOf(-1, 0, -1, -1)) // magenta: a broken image
        return fallback
    }

    /** A transparent pixel for a slot without a picture: the shader can see it by iTexNSize being 1x1. */
    private fun blankId(): Int {
        if (blank == 0) blank = onePixel(byteArrayOf(0, 0, 0, 0))
        return blank
    }

    private fun onePixel(rgba: ByteArray): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        val px = java.nio.ByteBuffer.allocateDirect(4).put(rgba)
        px.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun bytesOf(item: OverlayItem.GlSurface, spec: GlTexSpec): ByteArray {
        val src = spec.src
        if (src.startsWith("data:")) {
            val comma = src.indexOf(',')
            require(src.substring(0, comma).contains(";base64")) { "a data: URI must be base64" }
            return Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
        }
        val source = GlTextureFiles.get(item.pluginId, src) ?: throw IllegalArgumentException("no file \"$src\" in the plugin")
        val bytes = source.load() ?: throw IllegalArgumentException("file \"$src\" is missing")
        require(bytes.size <= GlTextures.MAX_FILE_BYTES) { "file \"$src\" is larger than 16 MB" }
        return bytes
    }

    private fun upload(item: OverlayItem.GlSurface, spec: GlTexSpec, stamp: Int): Tex {
        val bytes = bytesOf(item, spec)
        val bmp = decode(bytes, spec)
        try {
            val id = IntArray(1)
            GLES20.glGetError() // an old error flag must not be blamed on this image
            GLES20.glGenTextures(1, id, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, spec.minFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, spec.magFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, spec.wrap.gl)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, spec.wrap.gl)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            if (spec.mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, id, 0)
                throw IllegalStateException("OpenGL error 0x" + Integer.toHexString(err) + " while uploading the image")
            }
            return Tex(id[0], bmp.width, bmp.height, stamp, false)
        } finally {
            bmp.recycle()
        }
    }

    /** Decodes at most as large as needed, scales to the upload size and flips it so that the first row is the bottom one. */
    private fun decode(bytes: ByteArray, spec: GlTexSpec): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a PNG, JPEG or WebP image" }
        // OpenGL ES 2: repeat and mipmaps need power-of-two sizes
        val needPot = glVersion() < 3 && (spec.wrap != GlTexSpec.Wrap.CLAMP || spec.mipmap)
        val (tw, th) = GlTextures.targetSize(bounds.outWidth, bounds.outHeight, limit(), needPot)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IllegalArgumentException("the image could not be decoded")
        val m = Matrix().apply { postScale(tw.toFloat() / src.width, -th.toFloat() / src.height) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
