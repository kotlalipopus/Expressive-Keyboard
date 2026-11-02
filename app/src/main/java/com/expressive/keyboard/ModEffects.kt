package com.expressive.keyboard

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// ГРАФИКА И ДАТЧИКИ ДЛЯ МОДОВ
// JavaScript на интерпретаторе не успевает считать тысячи объектов за кадр, поэтому тяжёлое делает этот файл:
// скрипт только задаёт параметры, а частицы, шейдеры и наклон клавиш считаются здесь, вне скрипта.
// ============================================================================

/** Наклон, сдвиг и эффекты подписи одной клавиши (kb.keyEffects). */
@Immutable
data class KeyFx(
    val rotationX: Float = 0f, val rotationY: Float = 0f, val rotationZ: Float = 0f, val scale: Float = 1f,
    val x: Float = 0f, val y: Float = 0f, val alpha: Float = 1f, val camera: Float = 8f,
    val labelAlpha: Float = 1f, val labelBlur: Float = 0f, val labelX: Float = 0f, val labelY: Float = 0f, val labelScale: Float = 1f
) {
    val touchesLabel: Boolean
        get() = labelAlpha != 1f || labelBlur != 0f || labelX != 0f || labelY != 0f || labelScale != 1f

    companion object {
        fun parse(o: Map<*, *>): KeyFx {
            fun f(k: String, def: Float, lo: Float, hi: Float) =
                (o[k] as? Number)?.toFloat()?.takeIf { !it.isNaN() }?.coerceIn(lo, hi) ?: def
            return KeyFx(
                f("rotationX", 0f, -80f, 80f), f("rotationY", 0f, -80f, 80f), f("rotationZ", 0f, -360f, 360f),
                f("scale", 1f, 0.2f, 2f), f("x", 0f, -200f, 200f), f("y", 0f, -200f, 200f), f("alpha", 1f, 0f, 1f),
                f("camera", 8f, 1f, 64f), f("labelAlpha", 1f, 0f, 1f), f("labelBlur", 0f, 0f, 40f),
                f("labelX", 0f, -60f, 60f), f("labelY", 0f, -60f, 60f), f("labelScale", 1f, 0.2f, 3f)
            )
        }
    }
}

/** Шейдер AGSL: исходник, разобранный список uniform-ов и кэш. */
class ShaderProgram(val src: String, internal val shader: Any, val uniforms: Map<String, String>) {
    val hasContent: Boolean get() = uniforms["content"] == "shader"
}

object ModShaders {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33
    private val UNIFORM = Regex("""uniform\s+(?:layout\s*\([^)]*\)\s*)?(\w+)\s+(\w+)""")
    private val cache = object : LinkedHashMap<String, Result<ShaderProgram>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<ShaderProgram>>) = size > 24
    }

    internal fun declared(src: String): Map<String, String> =
        UNIFORM.findAll(src).associate { it.groupValues[2] to it.groupValues[1] }

    /** Null: Android старше 13 или шейдер не собрался (текст ошибки — в [error]). */
    fun program(src: String): ShaderProgram? = result(src)?.getOrNull()

    fun error(src: String): String? = result(src)?.exceptionOrNull()?.message

    private fun result(src: String): Result<ShaderProgram>? {
        if (!supported || src.isEmpty() || src.length > 64_000) return null
        return cache.getOrPut(src) { runCatching { ShaderProgram(src, RuntimeShader(src), declared(src)) } }
    }

    /** Значения uniform-ов: свои от мода и общие iTime, iResolution, iTouch, iTilt, iAudio. */
    internal fun bind(p: ShaderProgram, user: Map<String, Any?>, w: Float, h: Float, originX: Float, originY: Float, density: Float): RuntimeShader {
        val rs = p.shader as RuntimeShader
        for ((name, type) in p.uniforms) {
            if (type == "shader") continue
            runCatching {
                when (name) {
                    "iTime" -> rs.setFloatUniform(name, ModFx.seconds())
                    "iResolution" -> rs.setFloatUniform(name, w, h)
                    "iTouch" -> {
                        val down = ModFx.pointerCount > 0
                        rs.setFloatUniform(name, ModFx.lastX * density - originX, ModFx.lastY * density - originY, if (down) 1f else 0f)
                    }
                    "iTilt" -> rs.setFloatUniform(name, ModSensors.tiltX, ModSensors.tiltY)
                    "iAudio" -> rs.setFloatUniform(name, ModAudio.level)
                    else -> when (val v = user[name]) {
                        is Number -> if (type == "int") rs.setIntUniform(name, v.toInt()) else rs.setFloatUniform(name, v.toFloat())
                        is List<*> -> rs.setFloatUniform(name, v.map { (it as? Number)?.toFloat() ?: 0f }.toFloatArray())
                        is String -> PluginParser.parseColor(v)?.let { c ->
                            rs.setFloatUniform(name, ((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, ((c ushr 24) and 255) / 255f)
                        }
                    }
                }
            }
        }
        return rs
    }
}

/** Эффект на всю клавиатуру: шейдер получает её изображение как uniform shader content. */
class PostEffect(val program: ShaderProgram, val uniforms: Map<String, Any?>)

/** Настройки системы частиц. Всё в dp и секундах. */
data class ParticleConfig(
    val max: Int = 2000,
    val size: Float = 3f,
    val colors: IntArray = intArrayOf(0xFFFFFFFF.toInt()),
    val gravityX: Float = 0f,
    val gravityY: Float = 0f,
    val drag: Float = 0f,
    val bounce: Float = 0.5f,
    val collideKeys: Boolean = false,
    /** Сила тяжести от наклона телефона, dp/с² на единицу наклона. */
    val tilt: Float = 0f,
    /** Сколько живёт частица, 0 — пока не удалят. */
    val life: Float = 0f,
    val fade: Boolean = true,
    /** bounce — отскок от краёв клавиатуры, kill — исчезает за краем, wrap — выходит с другой стороны. */
    val bounds: String = "bounce",
    /** Положительная сила отталкивает от пальца, отрицательная притягивает. */
    val touchForce: Float = 0f,
    val touchRadius: Float = 80f,
    /** Сколько частиц рождается под пальцем при каждом движении. */
    val emitOnTouch: Int = 0,
    /** Постоянный поток: частиц в секунду из прямоугольника rateX, rateY, rateW, rateH. */
    val rate: Float = 0f,
    val rateX: Float = 0f,
    val rateY: Float = 0f,
    val rateW: Float = 0f,
    val rateH: Float = 0f,
    val speed: Float = 0f,
    /** Жидкость: частицы расталкивают друг друга и текут вместе. */
    val liquid: Boolean = false,
    val liquidRadius: Float = 14f,
    val stiffness: Float = 900f,
    val viscosity: Float = 2f,
    val overlay: Boolean = false
) {
    companion object {
        const val MAX = 20_000
        const val MAX_LIQUID = 3_000

        fun parse(o: Map<*, *>): ParticleConfig {
            fun f(k: String, def: Float) = (o[k] as? Number)?.toFloat()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: def
            val liquid = o["liquid"] == true
            val colors = ((o["colors"] as? List<*>) ?: listOfNotNull(o["color"]))
                .mapNotNull { PluginParser.parseColor(it?.toString()) }.take(8)
            val g = o["gravity"] as? List<*>
            return ParticleConfig(
                max = f("max", 2000f).toInt().coerceIn(1, if (liquid) MAX_LIQUID else MAX),
                size = f("size", 3f).coerceIn(0.5f, 64f),
                colors = if (colors.isEmpty()) intArrayOf(0xFFFFFFFF.toInt()) else colors.toIntArray(),
                gravityX = (g?.getOrNull(0) as? Number)?.toFloat() ?: 0f,
                gravityY = (g?.getOrNull(1) as? Number)?.toFloat() ?: (o["gravity"] as? Number)?.toFloat() ?: 0f,
                drag = f("drag", 0f).coerceIn(0f, 50f),
                bounce = f("bounce", 0.5f).coerceIn(0f, 1f),
                collideKeys = o["collideKeys"] == true,
                tilt = f("tilt", 0f).coerceIn(-5000f, 5000f),
                life = f("life", 0f).coerceIn(0f, 600f),
                fade = o["fade"] != false,
                bounds = o["bounds"]?.toString()?.takeIf { it == "kill" || it == "wrap" } ?: "bounce",
                touchForce = f("touchForce", 0f).coerceIn(-20000f, 20000f),
                touchRadius = f("touchRadius", 80f).coerceIn(4f, 1000f),
                emitOnTouch = f("emitOnTouch", 0f).toInt().coerceIn(0, 200),
                rate = f("rate", 0f).coerceIn(0f, 5000f),
                rateX = f("rateX", 0f),
                rateY = f("rateY", 0f),
                rateW = f("rateW", 0f).coerceAtLeast(0f),
                rateH = f("rateH", 0f).coerceAtLeast(0f),
                speed = f("speed", 0f).coerceIn(0f, 5000f),
                liquid = liquid,
                liquidRadius = f("liquidRadius", 14f).coerceIn(2f, 80f),
                stiffness = f("stiffness", 900f).coerceIn(0f, 20000f),
                viscosity = f("viscosity", 2f).coerceIn(0f, 50f),
                overlay = o["layer"] == "overlay"
            )
        }
    }
}

/** Клавиши как препятствия: прямоугольники в dp и сетка, чтобы не проверять все клавиши для каждой частицы. */
class Colliders(rects: List<FloatArray>) {
    private val cell = 32f
    private val all = rects
    private val grid = HashMap<Long, MutableList<FloatArray>>()

    init {
        for (r in rects) {
            val x0 = (r[0] / cell).toInt()
            val x1 = (r[2] / cell).toInt()
            val y0 = (r[1] / cell).toInt()
            val y1 = (r[3] / cell).toInt()
            for (cx in x0..x1) for (cy in y0..y1) grid.getOrPut(key(cx, cy)) { ArrayList(2) }.add(r)
        }
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    fun at(x: Float, y: Float): List<FloatArray>? = grid[key((x / cell).toInt(), (y / cell).toInt())]

    val isEmpty: Boolean get() = all.isEmpty()
}

/** dp/с: при шаге 33 мс это 30 dp за кадр, меньше клавиши. */
private const val MAX_COLLIDE_SPEED = 900f

/** Частицы считаются здесь, а скрипт только задаёт параметры. */
class ParticleSystem(var cfg: ParticleConfig) {
    var count = 0
        private set
    private var capacity = cfg.max
    private var px = FloatArray(capacity)
    private var py = FloatArray(capacity)
    private var vx = FloatArray(capacity)
    private var vy = FloatArray(capacity)
    private var age = FloatArray(capacity)
    private var tint = ByteArray(capacity)
    private var rateDebt = 0f
    private val rnd = java.util.Random()

    // соседи для жидкости
    private var head = IntArray(0)
    private var next = IntArray(0)

    fun configure(c: ParticleConfig) {
        cfg = c
        if (c.max == capacity) return
        val keep = min(count, c.max)
        px = px.copyOf(c.max)
        py = py.copyOf(c.max)
        vx = vx.copyOf(c.max)
        vy = vy.copyOf(c.max)
        age = age.copyOf(c.max)
        tint = tint.copyOf(c.max)
        capacity = c.max
        count = keep
    }

    fun clear() {
        count = 0
    }

    fun x(i: Int) = px[i]
    fun y(i: Int) = py[i]

    fun add(x: Float, y: Float, velX: Float, velY: Float) {
        // поток не должен душить новое: при переполнении заменяем случайную старую частицу
        val i = if (count < capacity) count++ else rnd.nextInt(capacity)
        px[i] = x
        py[i] = y
        vx[i] = velX
        vy[i] = velY
        age[i] = 0f
        tint[i] = rnd.nextInt(cfg.colors.size).toByte()
    }

    /** Выброс из точки или прямоугольника: speed — скорость, angle и spread — направление в градусах. */
    fun emit(n: Int, x: Float, y: Float, w: Float, h: Float, speed: Float, angle: Float, spread: Float) {
        repeat(n.coerceIn(0, capacity)) {
            val a = Math.toRadians((angle + (rnd.nextFloat() - 0.5f) * spread).toDouble())
            val s = speed * (0.5f + rnd.nextFloat() * 0.5f)
            add(x + rnd.nextFloat() * w, y + rnd.nextFloat() * h, (cos(a) * s).toFloat(), (sin(a) * s).toFloat())
        }
    }

    fun step(
        dt: Float, width: Float, height: Float, colliders: Colliders?,
        pointerX: FloatArray, pointerY: FloatArray, pointers: Int, tiltX: Float, tiltY: Float
    ) {
        val c = cfg
        if (c.rate > 0f) {
            rateDebt += c.rate * dt
            val n = rateDebt.toInt()
            rateDebt -= n
            emit(n, c.rateX, c.rateY, c.rateW, c.rateH, c.speed, 90f, 60f)
        }
        if (count == 0) return
        if (c.liquid) liquidForces(dt, c)
        val ax = c.gravityX + c.tilt * tiltX
        val ay = c.gravityY + c.tilt * tiltY
        val damp = (1f - c.drag * dt).coerceIn(0f, 1f)
        val tr2 = c.touchRadius * c.touchRadius
        var i = 0
        while (i < count) {
            var dead = false
            age[i] += dt
            if (c.life > 0f && age[i] >= c.life) dead = true
            if (!dead) {
                var nvx = (vx[i] + ax * dt) * damp
                var nvy = (vy[i] + ay * dt) * damp
                if (c.touchForce != 0f) {
                    for (p in 0 until pointers) {
                        val dx = px[i] - pointerX[p]
                        val dy = py[i] - pointerY[p]
                        val d2 = dx * dx + dy * dy
                        if (d2 < tr2 && d2 > 0.01f) {
                            val d = sqrt(d2)
                            val k = c.touchForce * (1f - d / c.touchRadius) * dt / d
                            nvx += dx * k
                            nvy += dy * k
                        }
                    }
                }
                if (colliders != null) {
                    // за кадр частица не должна перепрыгнуть клавишу целиком
                    nvx = nvx.coerceIn(-MAX_COLLIDE_SPEED, MAX_COLLIDE_SPEED)
                    nvy = nvy.coerceIn(-MAX_COLLIDE_SPEED, MAX_COLLIDE_SPEED)
                }
                var nx = px[i] + nvx * dt
                var ny = py[i] + nvy * dt
                if (colliders != null) {
                    val rects = colliders.at(nx, ny)
                    if (rects != null) for (r in rects) {
                        if (nx <= r[0] || nx >= r[2] || ny <= r[1] || ny >= r[3]) continue
                        // сторона — та, с которой частица прилетела: ближайшая к точке выталкивала её вбок,
                        // а у края клавиатуры частица так проскакивала сквозь клавишу
                        val left = nx - r[0]
                        val right = r[2] - nx
                        val top = ny - r[1]
                        val bottom = r[3] - ny
                        val nearest = min(min(left, right), min(top, bottom))
                        val side = when {
                            py[i] <= r[1] -> 2
                            py[i] >= r[3] -> 3
                            px[i] <= r[0] -> 0
                            px[i] >= r[2] -> 1
                            nearest == left -> 0
                            nearest == right -> 1
                            nearest == top -> 2
                            else -> 3
                        }
                        when (side) {
                            0 -> { nx = r[0] - 0.01f; if (nvx > 0f) nvx = -nvx * c.bounce }
                            1 -> { nx = r[2] + 0.01f; if (nvx < 0f) nvx = -nvx * c.bounce }
                            2 -> { ny = r[1] - 0.01f; if (nvy > 0f) nvy = -nvy * c.bounce }
                            else -> { ny = r[3] + 0.01f; if (nvy < 0f) nvy = -nvy * c.bounce }
                        }
                    }
                }
                when (c.bounds) {
                    "kill" -> if (nx < 0f || ny < 0f || nx > width || ny > height) dead = true
                    "wrap" -> {
                        if (nx < 0f) nx += width else if (nx > width) nx -= width
                        if (ny < 0f) ny += height else if (ny > height) ny -= height
                    }
                    else -> {
                        if (nx < 0f) { nx = 0f; nvx = abs(nvx) * c.bounce } else if (nx > width) { nx = width; nvx = -abs(nvx) * c.bounce }
                        if (ny < 0f) { ny = 0f; nvy = abs(nvy) * c.bounce } else if (ny > height) { ny = height; nvy = -abs(nvy) * c.bounce }
                    }
                }
                px[i] = nx
                py[i] = ny
                vx[i] = nvx
                vy[i] = nvy
            }
            if (dead) {
                val last = --count
                px[i] = px[last]
                py[i] = py[last]
                vx[i] = vx[last]
                vy[i] = vy[last]
                age[i] = age[last]
                tint[i] = tint[last]
            } else i++
        }
    }

    /** Давление и вязкость между соседями: не уравнения Навье — Стокса, а их быстрое приближение на частицах. */
    private fun liquidForces(dt: Float, c: ParticleConfig) {
        val h = c.liquidRadius
        val buckets = 1024
        if (head.size != buckets) head = IntArray(buckets)
        if (next.size < capacity) next = IntArray(capacity)
        head.fill(-1)
        fun slot(cx: Int, cy: Int) = ((cx * 73856093) xor (cy * 19349663)) and (buckets - 1)
        for (i in 0 until count) {
            val s = slot((px[i] / h).toInt(), (py[i] / h).toInt())
            next[i] = head[s]
            head[s] = i
        }
        for (i in 0 until count) {
            val cx = (px[i] / h).toInt()
            val cy = (py[i] / h).toInt()
            var checks = 0
            for (ox in -1..1) for (oy in -1..1) {
                var j = head[slot(cx + ox, cy + oy)]
                while (j >= 0 && checks < 48) {
                    if (j > i) {
                        checks++
                        val dx = px[j] - px[i]
                        val dy = py[j] - py[i]
                        val d2 = dx * dx + dy * dy
                        if (d2 < h * h && d2 > 1e-4f) {
                            val d = sqrt(d2)
                            val q = 1f - d / h
                            val push = c.stiffness * q * dt / d
                            val visc = c.viscosity * q * dt
                            val rvx = (vx[j] - vx[i]) * visc
                            val rvy = (vy[j] - vy[i]) * visc
                            vx[i] += -dx * push + rvx
                            vy[i] += -dy * push + rvy
                            vx[j] += dx * push - rvx
                            vy[j] += dy * push - rvy
                        }
                    }
                    j = next[j]
                }
            }
        }
    }

    private var points = FloatArray(0)
    private val levels = 4

    /** Группы одного цвета и прозрачности рисуются одним вызовом: это и позволяет выводить десятки тысяч точек. */
    fun draw(canvas: android.graphics.Canvas, paint: Paint, density: Float) {
        val n = count
        if (n == 0) return
        if (points.size < n * 2) points = FloatArray(capacity * 2)
        val c = cfg
        val groups = c.colors.size * levels
        val counts = IntArray(groups)
        fun group(i: Int): Int {
            val level = if (c.fade && c.life > 0f) ((1f - age[i] / c.life) * levels).toInt().coerceIn(0, levels - 1) else levels - 1
            return (tint[i] % c.colors.size) * levels + level
        }
        for (i in 0 until n) counts[group(i)]++
        val starts = IntArray(groups)
        var sum = 0
        for (g in 0 until groups) {
            starts[g] = sum
            sum += counts[g]
        }
        val fill = starts.copyOf()
        for (i in 0 until n) {
            val at = fill[group(i)]++ * 2
            points[at] = px[i] * density
            points[at + 1] = py[i] * density
        }
        paint.strokeWidth = c.size * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        for (g in 0 until groups) {
            if (counts[g] == 0) continue
            val color = c.colors[g / levels]
            val a = ((color ushr 24) and 255) * ((g % levels) + 1) / levels
            paint.color = (a shl 24) or (color and 0xFFFFFF)
            canvas.drawPoints(points, starts[g] * 2, counts[g] * 2, paint)
        }
    }
}

/** Общее состояние эффектов: читают его слои клавиатуры, пишет ScriptHost. */
object ModFx {
    /** Часы анимации: чтение при рисовании перерисовывает слой каждый кадр. */
    var clockNanos by mutableLongStateOf(0L)
        private set
    var keyFx by mutableStateOf<Map<String, KeyFx>>(emptyMap())
        private set
    var labelActive by mutableStateOf(false)
        private set
    var keyboardFx by mutableStateOf<KeyFx?>(null)
    var post by mutableStateOf<PostEffect?>(null)
    var shaderItems by mutableStateOf(false)
    var particlesOn by mutableStateOf(false)
        private set
    /** Кто-то хочет знать, где палец: без этого за касаниями слой не следит. */
    var wantsPointer by mutableStateOf(false)
        private set

    val ticking: Boolean get() = shaderItems || post != null || particlesOn
    val layerActive: Boolean get() = keyboardFx != null || post != null

    private val systems = LinkedHashMap<String, ParticleSystem>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pointerIds = LongArray(MAX_POINTERS)
    private val pointerX = FloatArray(MAX_POINTERS)
    private val pointerY = FloatArray(MAX_POINTERS)
    var pointerCount = 0
        private set
    var lastX = 0f
        private set
    var lastY = 0f
        private set

    private var colliders: Colliders? = null
    private var collidersAt = 0L
    private var startNanos = 0L
    private var skipFrame = false

    fun seconds(): Float = if (startNanos == 0L) 0f else ((clockNanos - startNanos) / 1_000_000_000.0 % 3600.0).toFloat()

    fun reset() {
        systems.clear()
        keyFx = emptyMap()
        labelActive = false
        keyboardFx = null
        post = null
        shaderItems = false
        particlesOn = false
        wantsPointer = false
        pointerCount = 0
    }

    fun updateKeyFx(map: Map<String, KeyFx>) {
        if (map == keyFx) return
        keyFx = map
        labelActive = map.values.any { it.touchesLabel }
    }

    /** Подходящий стиль: сначала точная подпись, затем letter или func, затем «*». */
    fun fxFor(name: String, isChar: Boolean): KeyFx? {
        val m = keyFx
        if (m.isEmpty()) return null
        return m[name] ?: (if (name.isNotEmpty() && name[0].isUpperCase()) m[name.lowercase()] else null)
            ?: m[if (isChar) "letter" else "func"] ?: m["*"]
    }

    fun system(key: String): ParticleSystem? = systems[key]

    fun setSystem(key: String, cfg: ParticleConfig): ParticleSystem {
        val s = systems[key]?.also { it.configure(cfg) } ?: ParticleSystem(cfg).also { systems[key] = it }
        particlesOn = systems.isNotEmpty()
        return s
    }

    fun removeSystem(key: String) {
        systems.remove(key)
        particlesOn = systems.isNotEmpty()
    }

    fun systemsOf(prefix: String): Int = systems.keys.count { it.startsWith(prefix) }

    fun usesPointer(scriptWants: Boolean) {
        wantsPointer = scriptWants || systems.values.any { it.cfg.touchForce != 0f || it.cfg.emitOnTouch > 0 } ||
            post?.program?.uniforms?.containsKey("iTouch") == true || shaderItems
    }

    /** Клавиатуру скрыли: палец, который её коснулся, отпускания уже не пришлёт. */
    fun clearPointers() {
        pointerCount = 0
    }

    fun usesTilt(): Boolean = systems.values.any { it.cfg.tilt != 0f }

    /** Палец: следит слой клавиатуры, не забирая касание. Эффекты получают точку, а скрипт — только с read_text. */
    fun pointer(type: String, x: Float, y: Float, id: Long) {
        lastX = x
        lastY = y
        val at = (0 until pointerCount).firstOrNull { pointerIds[it] == id }
        when {
            type == "up" -> if (at != null) {
                val last = --pointerCount
                pointerIds[at] = pointerIds[last]
                pointerX[at] = pointerX[last]
                pointerY[at] = pointerY[last]
            }
            at != null -> {
                pointerX[at] = x
                pointerY[at] = y
            }
            pointerCount < MAX_POINTERS -> {
                pointerIds[pointerCount] = id
                pointerX[pointerCount] = x
                pointerY[pointerCount] = y
                pointerCount++
            }
        }
        if (type != "up") for (s in systems.values) {
            if (s.cfg.emitOnTouch > 0) s.emit(s.cfg.emitOnTouch, x, y, 0f, 0f, maxOf(s.cfg.speed, 60f), 0f, 360f)
        }
        ScriptHost.pointerEvent(type, x, y, id.toInt())
    }

    fun tick(now: Long, dtNanos: Long) {
        if (startNanos == 0L) startNanos = now
        // в режиме энергосбережения — каждый второй кадр
        if (PowerState.saver) {
            skipFrame = !skipFrame
            if (skipFrame) return
        }
        clockNanos = now
        if (systems.isEmpty()) return
        val sz = KeyboardGeometry.size() ?: return
        val up = SystemClock.uptimeMillis()
        val needCollide = systems.values.any { it.cfg.collideKeys }
        if (needCollide && up - collidersAt > 400L) {
            collidersAt = up
            colliders = Colliders(
                KeyboardGeometry.visible().mapNotNull { (h, r) ->
                    if (h.key.type == KeyType.SPACER) null else floatArrayOf(r.left, r.top, r.right, r.bottom)
                }
            )
        }
        // большой шаг после паузы разносит частицы сквозь клавиши
        val dt = (dtNanos / 1_000_000_000f).coerceIn(0.001f, 0.033f)
        for (s in systems.values) {
            s.step(
                dt, sz.width, sz.height, if (s.cfg.collideKeys) colliders else null,
                pointerX, pointerY, pointerCount, ModSensors.tiltX, ModSensors.tiltY
            )
        }
    }

    internal fun DrawScope.drawParticles(overlay: Boolean) {
        // чтение часов: новый кадр — новая картинка
        if (clockNanos < 0L) return
        val canvas = drawContext.canvas.nativeCanvas
        for (s in systems.values) if (s.cfg.overlay == overlay) s.draw(canvas, paint, density)
    }

    private const val MAX_POINTERS = 10
}

/** Часы эффектов: идут, только пока есть шейдер или частицы и клавиатура на экране. */
@Composable
internal fun ModFxTicker() {
    if (!ModFx.ticking) return
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            ModFx.tick(now, now - last)
            last = now
        }
    }
}

/** Частицы над фоном или над клавишами. */
@Composable
internal fun BoxScope.ModParticleLayer(overlay: Boolean) {
    if (!ModFx.particlesOn) return
    Canvas(Modifier.matchParentSize()) { with(ModFx) { drawParticles(overlay) } }
}

/** Следит за пальцем, не забирая касание: клавиши и свайп работают как обычно. */
internal fun Modifier.modPointerWatch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val d = density
            for (c in ev.changes) {
                val type = when {
                    c.pressed && !c.previousPressed -> "down"
                    !c.pressed && c.previousPressed -> "up"
                    c.pressed -> "move"
                    else -> continue
                }
                ModFx.pointer(type, c.position.x / d, c.position.y / d, c.id.value)
            }
        }
    }
}

/** Наклон и сдвиг клавиши: вызывается после анимации нажатия. */
internal fun GraphicsLayerScope.applyKeyFx(name: String, isChar: Boolean) {
    val fx = ModFx.fxFor(name, isChar) ?: return
    applyFx(fx)
}

private fun GraphicsLayerScope.applyFx(fx: KeyFx) {
    rotationX += fx.rotationX
    rotationY += fx.rotationY
    rotationZ += fx.rotationZ
    scaleX *= fx.scale
    scaleY *= fx.scale
    translationX += fx.x * density
    translationY += fx.y * density
    alpha *= fx.alpha
    cameraDistance = fx.camera * density
}

/** Подпись клавиши: прозрачность, сдвиг, масштаб и размытие (Android 12+). */
internal fun Modifier.modLabel(name: String, isChar: Boolean): Modifier = graphicsLayer {
    val fx = ModFx.fxFor(name, isChar) ?: return@graphicsLayer
    alpha = fx.labelAlpha
    translationX = fx.labelX * density
    translationY = fx.labelY * density
    scaleX = fx.labelScale
    scaleY = fx.labelScale
    if (fx.labelBlur > 0f && Build.VERSION.SDK_INT >= 31) {
        val r = fx.labelBlur * density
        renderEffect = BlurEffect(r, r, TileMode.Decal)
    }
}

/** Наклон всей клавиатуры и шейдер поверх её изображения. */
internal fun Modifier.modKeyboardLayer(): Modifier = graphicsLayer {
    ModFx.keyboardFx?.let { applyFx(it) }
    val p = ModFx.post
    if (p != null && Build.VERSION.SDK_INT >= 33) {
        val rs = ModShaders.bind(p.program, p.uniforms, size.width, size.height, 0f, 0f, density)
        renderEffect = RenderEffect.createRuntimeShaderEffect(rs, "content").asComposeRenderEffect()
    }
}

/** Элемент {type:"shader"}: координаты шейдера начинаются от угла элемента. */
internal fun DrawScope.drawShaderItem(it: OverlayItem.Shader) {
    if (Build.VERSION.SDK_INT < 33 || ModFx.clockNanos < 0L) return
    val d = density
    val w = if (it.w > 0f) it.w * d else size.width
    val h = if (it.h > 0f) it.h * d else size.height
    val ox = it.x * d
    val oy = it.y * d
    val rs = ModShaders.bind(it.program, it.uniforms, w, h, ox, oy, d)
    translate(ox, oy) { drawRect(ShaderBrush(rs), Offset.Zero, Size(w, h), alpha = it.alpha) }
}

/** Наклон телефона и вращение. Датчики работают, только пока клавиатура на экране и они кому-то нужны. */
object ModSensors : SensorEventListener {
    @Volatile var tiltX = 0f
        private set
    @Volatile var tiltY = 0f
        private set
    @Volatile var tiltZ = 0f
        private set
    private var manager: SensorManager? = null
    private var lastTilt = 0L
    private var lastGyro = 0L

    fun available(context: Context): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null || sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    private var wantTilt = false
    private var wantGyro = false

    fun sync(context: Context, tilt: Boolean, gyro: Boolean) {
        if (tilt == wantTilt && gyro == wantGyro) return
        wantTilt = tilt
        wantGyro = gyro
        val sm = manager ?: context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sm.unregisterListener(this)
        manager = null
        if (tilt) {
            val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (s != null && sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)) manager = sm
        } else {
            tiltX = 0f
            tiltY = 0f
            tiltZ = 0f
        }
        if (gyro) {
            val s = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (s != null && sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)) manager = sm
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (now - lastGyro < 33L) return
                lastGyro = now
                ScriptHost.emit("gyro", mapOf("x" to e.values[0], "y" to e.values[1], "z" to e.values[2]))
            }
            else -> {
                // сглаживание: акселерометр без него дрожит
                val k = if (e.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.15f
                val g = SensorManager.GRAVITY_EARTH
                tiltX += (-e.values[0] / g - tiltX) * k
                tiltY += (e.values[1] / g - tiltY) * k
                tiltZ += (e.values[2] / g - tiltZ) * k
                if (now - lastTilt < 33L) return
                lastTilt = now
                ScriptHost.emit("tilt", mapOf("x" to tiltX, "y" to tiltY, "z" to tiltZ))
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
}

/** Громкость и полосы частот с микрофона. Скрипту никогда не уходит сам звук — только эти числа, 20 раз в секунду. */
object ModAudio {
    private const val RATE = 16_000
    private const val FRAME = 512
    private val FREQS = floatArrayOf(100f, 200f, 400f, 800f, 1500f, 2500f, 4000f, 6500f)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var level = 0f
        private set
    private var thread: Thread? = null
    @Volatile private var running = false

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun sync(context: Context, want: Boolean) {
        if (want == running) return
        if (!want) {
            running = false
            thread = null
            level = 0f
            return
        }
        if (!hasPermission(context)) return
        running = true
        lateinit var t: Thread
        t = Thread({ loop(t) }, "mod-audio")
        thread = t
        t.isDaemon = true
        t.start()
    }

    @Suppress("MissingPermission")
    private fun loop(self: Thread) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            if (thread === self) running = false
            return
        }
        val buf = ShortArray(FRAME)
        var lastSent = 0L
        try {
            rec.startRecording()
            // свой поток сравнивается, чтобы быстрое выключение и включение не оставило две записи сразу
            while (running && thread === self) {
                val n = rec.read(buf, 0, FRAME)
                if (n < 0) break
                if (n == 0) continue
                level = levelOf(buf, n)
                val now = SystemClock.uptimeMillis()
                if (now - lastSent < 50L) continue
                lastSent = now
                val l = level
                val bands = FREQS.map { goertzel(buf, n, it, RATE.toFloat()).toDouble() }
                main.post { ScriptHost.emit("audio", mapOf("level" to l, "bands" to bands)) }
            }
        } catch (_: RuntimeException) {
            // микрофон занял голосовой ввод или система его не дала
        } finally {
            runCatching { rec.stop() }
            rec.release()
            if (thread === self) {
                level = 0f
                running = false
            }
        }
    }

    /** Громкость 0..1: −50 дБ и тише — ноль, 0 дБ — единица. */
    internal fun levelOf(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += (buf[i] / 32768.0) * (buf[i] / 32768.0)
        val rms = sqrt(sum / n.coerceAtLeast(1))
        if (rms < 1e-6) return 0f
        return ((20 * log10(rms) + 50) / 50).toFloat().coerceIn(0f, 1f)
    }

    /** Сила одной частоты 0..1. */
    internal fun goertzel(buf: ShortArray, n: Int, freq: Float, rate: Float): Float {
        val w = 2.0 * PI * freq / rate
        val k = 2.0 * cos(w)
        var a = 0.0
        var b = 0.0
        for (i in 0 until n) {
            val x = buf[i] / 32768.0
            val s = x + k * a - b
            b = a
            a = s
        }
        val power = a * a + b * b - k * a * b
        val mag = 2.0 * sqrt(power.coerceAtLeast(0.0)) / n.coerceAtLeast(1)
        if (mag < 1e-5) return 0f
        return ((20 * log10(mag) + 60) / 60).toFloat().coerceIn(0f, 1f)
    }
}

/** Приложение, в котором печатают: основной цвет берётся из его значка. */
object ModApps {
    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    /** Средний цвет значка: насыщенные пиксели весят больше, белый и серый фон почти не считаются. */
    fun color(context: Context, pkg: String): String? = runCatching {
        val icon = context.packageManager.getApplicationIcon(pkg)
        val size = 48
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        icon.setBounds(0, 0, size, size)
        icon.draw(canvas)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until size) for (x in 0 until size) {
            val c = bmp.getPixel(x, y)
            if ((c ushr 24) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val w = 0.02 + hsv[1] * hsv[2] * hsv[1] * hsv[2]
            r += ((c shr 16) and 255) * w
            g += ((c shr 8) and 255) * w
            b += (c and 255) * w
            total += w
        }
        bmp.recycle()
        if (total <= 0.0) null else String.format("#%02X%02X%02X", (r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    }.getOrNull()
}
