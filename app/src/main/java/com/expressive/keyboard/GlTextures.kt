package com.expressive.keyboard

/**
 * One image a GL layer asked for: `textures: { iTex0: "brick.png" }` or
 * `textures: { iTex0: { src: "brick.png", wrap: "repeat", filter: "nearest", mipmap: true } }`.
 * [src] is a file of the plugin's .zip or a `data:image/...` URI. Plain logic with no Android classes, so it is unit-tested.
 */
data class GlTexSpec(
    /** Name of the `uniform sampler2D` in the shader. `<name>Size` (a `vec2`) is filled with the image size in pixels if the shader declares it. */
    val name: String,
    val src: String,
    val wrap: Wrap = Wrap.CLAMP,
    val nearest: Boolean = false,
    val mipmap: Boolean = false
) {
    enum class Wrap(val gl: Int) { CLAMP(0x812F), REPEAT(0x2901), MIRROR(0x8370) }

    /** Cache key: the same file with other parameters is another texture object. Computed once per spec. */
    val key: String = Integer.toHexString(src.hashCode()) + "|" + src.length + "|" + wrap.ordinal + (if (nearest) "n" else "l") + (if (mipmap) "m" else "")

    val minFilter: Int get() = when {
        mipmap && nearest -> 0x2700 // GL_NEAREST_MIPMAP_NEAREST
        mipmap -> 0x2703 // GL_LINEAR_MIPMAP_LINEAR
        nearest -> 0x2600 // GL_NEAREST
        else -> 0x2601 // GL_LINEAR
    }
    val magFilter: Int get() = if (nearest) 0x2600 else 0x2601

    /** An empty source: the plugin has no picture for this slot yet (an `image` setting nobody filled in). The shader gets a 1x1 transparent pixel. */
    val isEmpty: Boolean get() = src.isEmpty()
}

object GlTextures {
    const val MAX_PER_LAYER = 4
    /** Longest side of an uploaded image; bigger images are scaled down. */
    const val MAX_SIDE = 2048
    /** A file of the plugin's archive read for a texture. */
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    /** GL_SAMPLER_2D */
    const val SAMPLER_2D = 0x8B5E
    private const val MAX_DATA_URI = 22_000_000
    private const val MAX_PATH = 200

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,31}")
    private val OPTION_ID = Regex("[A-Za-z0-9_.-]{1,40}")

    /** Source of a picture the user chose in an `image` setting: `user:<setting id>:<stamp>`. */
    const val USER_PREFIX = "user:"

    /** The value stored for an `image` setting. The stamp changes with every new picture, so the texture is uploaded again. */
    fun userValue(optionId: String, stamp: Long): String = "$USER_PREFIX$optionId:$stamp"

    /** The id of the `image` setting a source points at, or null when it is not a `user:` source. */
    fun userOption(src: String): String? {
        if (!src.startsWith(USER_PREFIX)) return null
        val rest = src.substring(USER_PREFIX.length)
        val i = rest.lastIndexOf(':')
        if (i <= 0) return null
        return rest.substring(0, i).takeIf { OPTION_ID.matches(it) }
    }

    /** Uniforms the keyboard fills in itself: a texture may not take these names. */
    private val RESERVED = hashSetOf("iPrev", "iTime", "iFrame", "iResolution", "iDensity", "iTouch", "iTilt", "iAudio")

    fun sizeUniform(name: String): String = name + "Size"

    /** The `textures` option of a gl element → at most [MAX_PER_LAYER] valid specs. Anything malformed is skipped. */
    fun parse(raw: Any?): List<GlTexSpec> {
        val map = raw as? Map<*, *> ?: return emptyList()
        val out = ArrayList<GlTexSpec>()
        for ((k, v) in map) {
            if (out.size >= MAX_PER_LAYER) break
            val name = k?.toString().orEmpty()
            if (!NAME.matches(name) || name in RESERVED) continue
            val spec = when (v) {
                is String -> make(name, v, null, null, false)
                is Map<*, *> -> make(name, v["src"]?.toString().orEmpty(), v["wrap"]?.toString(), v["filter"]?.toString(), v["mipmap"] == true)
                else -> null
            }
            if (spec != null) out.add(spec)
        }
        return out
    }

    private fun make(name: String, rawSrc: String, wrap: String?, filter: String?, mip: Boolean): GlTexSpec? {
        // an empty source is kept: it is an empty slot, not a mistake (see GlTexSpec.isEmpty)
        val src = if (rawSrc.isBlank()) "" else cleanSrc(rawSrc) ?: return null
        val w = when (wrap?.lowercase()) {
            "repeat" -> GlTexSpec.Wrap.REPEAT
            "mirror", "mirrored" -> GlTexSpec.Wrap.MIRROR
            else -> GlTexSpec.Wrap.CLAMP
        }
        return GlTexSpec(name, src, w, filter?.lowercase() == "nearest", mip)
    }

    /** A `data:` URI stays as it is; a file name loses a leading `./` and `/`. Blank, too long or `..` paths are refused. */
    fun cleanSrc(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("data:")) return if (s.length <= MAX_DATA_URI && s.contains(',')) s else null
        val p = s.replace('\\', '/').removePrefix("./").trimStart('/')
        if (p.isEmpty() || p.length > MAX_PATH || p.split('/').any { it == ".." }) return null
        return p
    }

    fun isPot(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    fun nextPot(n: Int): Int {
        var p = 1
        while (p < n && p < (1 shl 30)) p = p shl 1
        return p
    }

    private fun prevPot(n: Int): Int {
        var p = 1
        while ((p shl 1) <= n) p = p shl 1
        return p
    }

    /**
     * The size an image is uploaded at: the longest side is at most [maxSide], and with [needPot] (OpenGL ES 2 with repeat or mipmaps,
     * where non-power-of-two images are not allowed) both sides become powers of two without going over [maxSide].
     */
    fun targetSize(w: Int, h: Int, maxSide: Int, needPot: Boolean): Pair<Int, Int> {
        var tw = maxOf(1, w)
        var th = maxOf(1, h)
        val longest = maxOf(tw, th)
        if (longest > maxSide) {
            val s = maxSide.toDouble() / longest
            tw = maxOf(1, (tw * s).toInt())
            th = maxOf(1, (th * s).toInt())
        }
        if (needPot) {
            tw = nextPot(tw)
            th = nextPot(th)
            val cap = prevPot(maxSide)
            if (tw > cap) tw = cap
            if (th > cap) th = cap
        }
        return tw to th
    }
}
