package com.expressive.keyboard

import android.opengl.GLES20
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// Three helpers for heavy mods:
//  1. GlBatch    - { type: "batch", shapes: [...] }: many rects, circles and lines drawn in ONE OpenGL call.
//  2. OverlayGroups - kb.defineGroup(key, items) + { type: "group", key }: send a set of shapes once, then reference it.
//  3. ScriptView - kb.panZoom(): the keyboard itself pans and zooms the groups marked world: true, with no script round trip.
// ============================================================================

/** Shapes turned into one triangle mesh with a built-in shader, so hundreds of shapes cost a single draw call. */
internal object GlBatch {
    private const val MAX_SHAPES = 6000
    private const val MAX_VERTS = 400_000

    /** Positions are in dp from the layer corner; the shader converts them to clip space with the layer size. */
    const val VERT = "attribute vec2 aPos;\n" +
        "attribute vec4 aCol;\n" +
        "uniform vec3 iResolution;\n" +
        "uniform float iDensity;\n" +
        "varying vec4 vCol;\n" +
        "void main() {\n" +
        "  vec2 dp = iResolution.xy / iDensity;\n" +
        "  vec2 p = aPos / dp;\n" +
        "  gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);\n" +
        "  vCol = aCol;\n" +
        "}\n"

    /** Colours are premultiplied by alpha (the TextureView composes premultiplied pixels). */
    const val FRAG = "precision mediump float;\n" +
        "varying vec4 vCol;\n" +
        "void main() { gl_FragColor = vCol; }\n"

    private class Buf {
        var pos = FloatArray(4096)
        var col = FloatArray(8192)
        var n = 0

        fun vert(x: Float, y: Float, c: FloatArray) {
            if (n >= MAX_VERTS) return
            if (n * 2 + 2 > pos.size) {
                pos = pos.copyOf(pos.size * 2)
                col = col.copyOf(col.size * 2)
            }
            pos[n * 2] = x
            pos[n * 2 + 1] = y
            col[n * 4] = c[0]
            col[n * 4 + 1] = c[1]
            col[n * 4 + 2] = c[2]
            col[n * 4 + 3] = c[3]
            n++
        }

        fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, c: FloatArray) {
            if (n + 3 > MAX_VERTS) return
            vert(ax, ay, c)
            vert(bx, by, c)
            vert(cx, cy, c)
        }

        fun quad(p: FloatArray, i: Int, j: Int, q: FloatArray, c: FloatArray) {
            // ring segment between outer points p[i], p[j] and inner points q[i], q[j]
            tri(p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], q[j * 2], q[j * 2 + 1], c)
            tri(p[i * 2], p[i * 2 + 1], q[j * 2], q[j * 2 + 1], q[i * 2], q[i * 2 + 1], c)
        }
    }

    private fun premul(color: Color, alpha: Float): FloatArray {
        val a = (color.alpha * alpha).coerceIn(0f, 1f)
        return floatArrayOf(color.red * a, color.green * a, color.blue * a, a)
    }

    /** Rotates the flat x,y array around (cx, cy) by [deg] degrees (clockwise on the screen). */
    private fun rotate(p: FloatArray, cx: Float, cy: Float, deg: Float) {
        if (deg == 0f) return
        val r = deg * PI.toFloat() / 180f
        val cs = cos(r)
        val sn = sin(r)
        var i = 0
        while (i < p.size) {
            val dx = p[i] - cx
            val dy = p[i + 1] - cy
            p[i] = cx + dx * cs - dy * sn
            p[i + 1] = cy + dx * sn + dy * cs
            i += 2
        }
    }

    /** Outline of a rounded rectangle: always 4 * (seg + 1) points, so an outer and an inner outline pair up one to one. */
    private fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, seg: Int): FloatArray {
        val r = radius.coerceIn(0f, min(w, h) / 2f)
        val out = FloatArray(4 * (seg + 1) * 2)
        val centers = floatArrayOf(x + w - r, y + r, x + w - r, y + h - r, x + r, y + h - r, x + r, y + r)
        var k = 0
        for (corner in 0 until 4) {
            val cx = centers[corner * 2]
            val cy = centers[corner * 2 + 1]
            val start = (corner - 1) * 90f
            for (s in 0..seg) {
                val a = (start + 90f * s / seg) * PI.toFloat() / 180f
                out[k++] = cx + r * cos(a)
                out[k++] = cy + r * sin(a)
            }
        }
        return out
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            out[i * 2] = cx + rx * cos(a).toFloat()
            out[i * 2 + 1] = cy + ry * sin(a).toFloat()
        }
        return out
    }

    private fun fan(b: Buf, p: FloatArray, cx: Float, cy: Float, c: FloatArray) {
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            b.tri(cx, cy, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], c)
        }
    }

    private fun ring(b: Buf, outer: FloatArray, inner: FloatArray, c: FloatArray) {
        val n = outer.size / 2
        for (i in 0 until n) b.quad(outer, i, (i + 1) % n, inner, c)
    }

    private fun shape(b: Buf, s: OverlayItem.Shape) {
        val c = premul(s.color, s.alpha)
        if (c[3] <= 0f || s.w <= 0f || s.h <= 0f) return
        val cx = s.x + s.w / 2f
        val cy = s.y + s.h / 2f
        val st = s.stroke
        val round = s.kind == "circle" || s.kind == "oval"
        val outer: FloatArray
        var inner: FloatArray? = null
        if (round) {
            val big = max(s.w, s.h) / 2f
            val n = (big * 0.8f + 10f).toInt().coerceIn(12, 64)
            outer = ellipse(cx, cy, s.w / 2f, s.h / 2f, n)
            if (st > 0f && s.w - 2 * st > 0f && s.h - 2 * st > 0f) inner = ellipse(cx, cy, s.w / 2f - st, s.h / 2f - st, n)
        } else {
            val seg = if (s.radius > 0f) (s.radius * 0.5f + 3f).toInt().coerceIn(3, 10) else 1
            outer = roundRect(s.x, s.y, s.w, s.h, s.radius, seg)
            if (st > 0f && s.w - 2 * st > 0f && s.h - 2 * st > 0f) {
                inner = roundRect(s.x + st, s.y + st, s.w - 2 * st, s.h - 2 * st, max(s.radius - st, 0f), seg)
            }
        }
        rotate(outer, cx, cy, s.rotation)
        if (st > 0f && inner != null) {
            rotate(inner, cx, cy, s.rotation)
            ring(b, outer, inner, c)
        } else {
            fan(b, outer, cx, cy, c)
        }
    }

    private fun line(b: Buf, l: OverlayItem.Line) {
        val c = premul(l.color, l.alpha)
        val pts = l.points
        if (c[3] <= 0f || pts.size < 2) return
        val flat = FloatArray(pts.size * 2)
        for (i in pts.indices) {
            flat[i * 2] = pts[i].x
            flat[i * 2 + 1] = pts[i].y
        }
        rotate(flat, flat[0], flat[1], l.rotation)
        val n = pts.size
        if (l.fill) {
            // a fan from the centre: right for convex and star-shaped polygons
            var mx = 0f
            var my = 0f
            for (i in 0 until n) {
                mx += flat[i * 2]
                my += flat[i * 2 + 1]
            }
            fan(b, flat, mx / n, my / n, c)
            return
        }
        val hw = max(l.width, 0.5f) / 2f
        val segs = if (l.closed) n else n - 1
        for (i in 0 until segs) {
            val j = (i + 1) % n
            val ax = flat[i * 2]
            val ay = flat[i * 2 + 1]
            val bx = flat[j * 2]
            val by = flat[j * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.001f) continue
            val nx = -dy / len * hw
            val ny = dx / len * hw
            b.tri(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, c)
            b.tri(ax + nx, ay + ny, bx - nx, by - ny, ax - nx, ay - ny, c)
        }
        // round joints and caps, like StrokeCap.Round in the normal drawing
        if (l.width >= 3f) {
            val k = if (l.width > 10f) 12 else 8
            for (i in 0 until n) {
                fan(b, ellipse(flat[i * 2], flat[i * 2 + 1], hw, hw, k), flat[i * 2], flat[i * 2 + 1], c)
            }
        }
    }

    /**
     * { type: "batch", shapes: [rect / circle / oval / line elements], key?, x?, y?, w?, h?, alpha?, rotation?, id? }.
     * With w and h 0 the batch covers the whole drawing area and the shapes use the same coordinates as other elements.
     * With a key and no shapes the mesh built earlier under that key is drawn again (the shapes are not sent twice).
     */
    fun parse(o: Map<*, *>, x: Float, y: Float, w: Float, h: Float, alpha: Float, rot: Float): OverlayItem.GlSurface? {
        val key = o["key"]?.toString()?.take(64)?.takeIf { it.isNotBlank() }
        val raw = o["shapes"] as? List<*>
        val id = o["id"]?.toString()?.take(64)
        val mesh: GlMesh
        if (raw == null) {
            if (key == null) return null
            mesh = GlMesh(GLES20.GL_TRIANGLES, 0, emptyMap(), null, key, true)
        } else {
            val b = Buf()
            for (v in raw.take(MAX_SHAPES)) {
                if ((v as? Map<*, *>)?.get("type") !in setOf("rect", "circle", "oval", "line")) continue
                when (val p = OverlayItem.parse(v)) {
                    is OverlayItem.Shape -> shape(b, p)
                    is OverlayItem.Line -> line(b, p)
                    else -> Unit
                }
            }
            if (b.n < 3) return null
            mesh = GlMesh(
                GLES20.GL_TRIANGLES, b.n,
                mapOf("aPos" to GlAttrib(2, b.pos.copyOf(b.n * 2)), "aCol" to GlAttrib(4, b.col.copyOf(b.n * 4))),
                null, key, false
            )
        }
        return OverlayItem.GlSurface(
            x, y, w, h, FRAG, VERT, emptyMap(), mesh, 1f, 30, false, Color.Transparent, alpha, rot, id, "", true
        )
    }
}

/** Sets of elements sent once with kb.defineGroup and drawn by reference: { type: "group", key, x, y, scale, rotation, alpha, world }. */
object OverlayGroups {
    private const val MAX_GROUPS = 64
    private val map: MutableMap<String, List<OverlayItem>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<OverlayItem>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OverlayItem>>?): Boolean = size > MAX_GROUPS
        }
    )

    /** Compose state read by the drawing code, so a changed group redraws the layer without recomposing anything. */
    private val tick = mutableIntStateOf(0)
    val version: Int get() = tick.intValue

    fun put(owner: String, key: String, items: List<OverlayItem>) {
        map[owner + "/" + key] = items
        tick.intValue++
    }

    fun remove(owner: String, key: String): Boolean {
        val had = map.remove(owner + "/" + key) != null
        if (had) tick.intValue++
        return had
    }

    fun get(owner: String, key: String): List<OverlayItem>? = map[owner + "/" + key]

    fun clear() {
        map.clear()
        tick.intValue++
    }
}

/**
 * The camera of the "world" groups: screen position (dp) = world position * zoom + pan.
 * With kb.panZoom() the keyboard changes it from finger gestures by itself; the script can read and set it with kb.view().
 */
object ScriptView {
    var panX by mutableStateOf(0f)
    var panY by mutableStateOf(0f)
    var zoom by mutableStateOf(1f)

    /** The keyboard handles pan and zoom gestures (only while touches are captured with kb.captureTouches). */
    var enabled by mutableStateOf(false)

    /** "two": two fingers pan and zoom, one finger still goes to the script. "any": one finger drags too (a tap still reaches the script). */
    @Volatile var mode = "two"
    @Volatile var minZoom = 0.2f
    @Volatile var maxZoom = 8f

    fun set(x: Float?, y: Float?, z: Float?) {
        if (x != null && x.isFinite()) panX = x
        if (y != null && y.isFinite()) panY = y
        if (z != null && z.isFinite()) zoom = z.coerceIn(minZoom, maxZoom)
    }

    fun reset() {
        panX = 0f
        panY = 0f
        zoom = 1f
        enabled = false
        mode = "two"
        minZoom = 0.2f
        maxZoom = 8f
    }

    fun toMap(): Map<String, Any?> = mapOf("x" to panX.toDouble(), "y" to panY.toDouble(), "zoom" to zoom.toDouble())
}

/**
 * Finger gestures for [ScriptView]. [onEvent] returns true when the events still go to the script and false when the
 * keyboard keeps them for itself; when it takes over a gesture the script gets a "cancel" for its pointers first.
 */
internal class ViewGesture {
    private val forwarded = HashMap<Long, Offset>()
    private var host = false
    private var origin: Offset? = null

    fun onEvent(changes: List<PointerInputChange>, d: Float, zx: Float, zy: Float, cancel: (Offset, Int) -> Unit): Boolean {
        fun pos(c: PointerInputChange, prev: Boolean): Offset {
            val p = if (prev) c.previousPosition else c.position
            return Offset(p.x / d + zx, p.y / d + zy)
        }
        val pressed = changes.filter { it.pressed }
        if (pressed.isEmpty()) {
            // everything is up: the next touch starts fresh
            val wasHost = host
            host = false
            origin = null
            forwarded.clear()
            if (wasHost) ScriptHost.viewChanged(true)
            return !wasHost
        }
        fun takeOver() {
            if (!host) {
                for ((id, p) in forwarded) cancel(p, id.toInt())
                forwarded.clear()
                host = true
            }
        }
        if (pressed.size >= 2) {
            takeOver()
            val moving = pressed.filter { it.previousPressed }
            if (moving.size >= 2) pinch(moving.map { pos(it, true) }, moving.map { pos(it, false) })
            return false
        }
        val c = pressed[0]
        if (host) {
            // one finger is left after a pinch, or the drag of "any" mode goes on
            if (ScriptView.mode == "any" && c.previousPressed) drag(c, d)
            return false
        }
        if (ScriptView.mode == "any") {
            if (!c.previousPressed) origin = pos(c, false)
            val o = origin
            if (o != null) {
                val p = pos(c, false)
                val dx = p.x - o.x
                val dy = p.y - o.y
                if (dx * dx + dy * dy > 64f) {
                    // moved more than 8 dp: this is a drag of the view, not a tap
                    origin = null
                    takeOver()
                    ScriptView.panX += dx
                    ScriptView.panY += dy
                    ScriptHost.viewChanged(false)
                    return false
                }
            }
        }
        for (ch in changes) {
            if (ch.pressed) forwarded[ch.id.value] = pos(ch, false) else forwarded.remove(ch.id.value)
        }
        return true
    }

    private fun drag(c: PointerInputChange, d: Float) {
        ScriptView.panX += (c.position.x - c.previousPosition.x) / d
        ScriptView.panY += (c.position.y - c.previousPosition.y) / d
        ScriptHost.viewChanged(false)
    }

    private fun pinch(prev: List<Offset>, cur: List<Offset>) {
        val n = prev.size
        var pcx = 0f
        var pcy = 0f
        var ccx = 0f
        var ccy = 0f
        for (i in 0 until n) {
            pcx += prev[i].x
            pcy += prev[i].y
            ccx += cur[i].x
            ccy += cur[i].y
        }
        pcx /= n
        pcy /= n
        ccx /= n
        ccy /= n
        var sp = 0f
        var sc = 0f
        for (i in 0 until n) {
            sp += sqrt((prev[i].x - pcx) * (prev[i].x - pcx) + (prev[i].y - pcy) * (prev[i].y - pcy))
            sc += sqrt((cur[i].x - ccx) * (cur[i].x - ccx) + (cur[i].y - ccy) * (cur[i].y - ccy))
        }
        val z0 = ScriptView.zoom
        val z1 = if (sp > 2f * n && sc > 0f) (z0 * sc / sp).coerceIn(ScriptView.minZoom, ScriptView.maxZoom) else z0
        // the world point under the old centre stays under the new centre
        val wx = (pcx - ScriptView.panX) / z0
        val wy = (pcy - ScriptView.panY) / z0
        ScriptView.zoom = z1
        ScriptView.panX = ccx - wx * z1
        ScriptView.panY = ccy - wy * z1
        ScriptHost.viewChanged(false)
    }
}
