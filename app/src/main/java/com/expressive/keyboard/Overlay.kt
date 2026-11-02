package com.expressive.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================================
// СЛОЙ МОДОВ ПОВЕРХ КЛАВИАТУРЫ
// Скрипт рисует фигуры, пиксель-арт, текст и картинки поверх клавиш (kb.overlay), знает, где какая
// клавиша (kb.keys), может «нажать» клавишу (kb.pressKey) и анимироваться покадрово (событие "frame").
// Слой не перехватывает касания — печатать он не мешает.
// ============================================================================

/**
 * Положение [child] в координатах этого слоя — или null, если один из них уже не на экране или они из разных
 * окон. Окно клавиатуры Android пересоздаёт (поворот, смена темы), и на мгновение старые и новые клавиши
 * живут одновременно: localPositionOf между ними бросает «layouts are not part of the same hierarchy».
 */
internal fun LayoutCoordinates.safePositionOf(child: LayoutCoordinates): Offset? {
    if (!isAttached || !child.isAttached) return null
    return try {
        localPositionOf(child, Offset.Zero)
    } catch (_: RuntimeException) {
        null
    }
}

/** Клавиша на экране: модель, подпись и место; pulse() проигрывает анимацию нажатия. */
class KeyHandle(val key: KeyModel, val label: String, val holder: CoordsHolder, val pulse: () -> Unit)

/** Где сейчас клавиатура и её клавиши — для API модов. Заполняется из композиции. */
object KeyboardGeometry {
    @Volatile var layer: CoordsHolder? = null
    @Volatile var density: Density? = null
    @Volatile var actions: KeyboardActions? = null
    @Volatile var palette: KeyPalette? = null
    val keys = ArrayList<KeyHandle>()

    fun register(h: KeyHandle) = synchronized(keys) { keys += h }
    fun unregister(h: KeyHandle) = synchronized(keys) { keys.remove(h) }

    /** Прямоугольник клавиши в dp относительно клавиатуры. */
    fun rectOf(h: KeyHandle): Rect? {
        val k = h.holder.coords ?: return null
        val l = layer?.coords ?: return null
        val d = density?.density ?: return null
        val p = l.safePositionOf(k) ?: return null
        return Rect(p.x / d, p.y / d, (p.x + k.size.width) / d, (p.y + k.size.height) / d)
    }

    fun size(): Size? {
        val l = layer?.coords?.takeIf { it.isAttached } ?: return null
        val d = density?.density ?: return null
        return Size(l.size.width / d, l.size.height / d)
    }

    fun snapshot(): List<KeyHandle> = synchronized(keys) { keys.toList() }

    /** Клавиши, которые сейчас на экране: без старых, оставшихся от прошлого окна или уходящей панели. */
    fun visible(): List<Pair<KeyHandle, Rect>> = snapshot().mapNotNull { h -> rectOf(h)?.let { h to it } }

    /** Подпись клавиши для модов: буква или имя служебной клавиши. */
    fun nameOf(h: KeyHandle): String = nameOf(h.key, h.label)

    fun nameOf(key: KeyModel, label: String = key.label): String = when (key.type) {
        KeyType.CHARACTER -> label
        KeyType.SHIFT -> "shift"
        KeyType.BACKSPACE -> "backspace"
        KeyType.MODE_SYMBOLS -> "symbols"
        KeyType.SYMBOLS_PAGE -> "page"
        KeyType.GLOBE -> "globe"
        KeyType.STICKERS -> "emoji"
        KeyType.SPACE -> "space"
        KeyType.ENTER -> "enter"
        KeyType.SPACER -> ""
    }

    /** Буквенная клавиша, ближайшая к точке p (px в координатах клавиатуры), — не дальше ширины клавиши от центра. */
    fun charKeyNear(p: Offset): KeyHandle? {
        val l = layer?.coords ?: return null
        var best: KeyHandle? = null
        var bestD = Float.MAX_VALUE
        for (h in snapshot()) {
            if (h.key.type != KeyType.CHARACTER) continue
            val k = h.holder.coords ?: continue
            val pos = l.safePositionOf(k) ?: continue
            val dx = (p.x - (pos.x + k.size.width / 2f)) / k.size.width.coerceAtLeast(1)
            val dy = (p.y - (pos.y + k.size.height / 2f)) / k.size.height.coerceAtLeast(1)
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = h
            }
        }
        return best?.takeIf { bestD < 1f }
    }

    fun find(name: String): KeyHandle? {
        val all = visible().map { it.first }
        return all.firstOrNull { nameOf(it) == name } ?: all.firstOrNull { nameOf(it).equals(name, ignoreCase = true) }
    }
}

@Immutable
sealed interface OverlayItem {
    val alpha: Float
    val rotation: Float

    data class Shape(
        val kind: String, val x: Float, val y: Float, val w: Float, val h: Float, val color: Color,
        val radius: Float, val stroke: Float, override val alpha: Float, override val rotation: Float
    ) : OverlayItem

    data class Line(
        val points: List<Offset>, val color: Color, val width: Float, val closed: Boolean, val fill: Boolean,
        override val alpha: Float, override val rotation: Float
    ) : OverlayItem

    data class Label(
        val x: Float, val y: Float, val text: String, val size: Float, val color: Color, val bold: Boolean,
        val center: Boolean, override val alpha: Float, override val rotation: Float
    ) : OverlayItem

    /** Пиксель-арт: строки символов, каждый символ — цвет из palette, пробел и «.» — пусто. */
    data class Pixels(
        val x: Float, val y: Float, val scale: Float, val rows: List<String>, val colors: Map<Char, Color>,
        val flipX: Boolean, override val alpha: Float, override val rotation: Float
    ) : OverlayItem

    data class Picture(
        val x: Float, val y: Float, val w: Float, val h: Float, val image: ImageBitmap, val flipX: Boolean,
        override val alpha: Float, override val rotation: Float
    ) : OverlayItem

    /** Шейдер AGSL (Android 13+): w и h 0 — вся область рисования. */
    data class Shader(
        val x: Float, val y: Float, val w: Float, val h: Float, val program: ShaderProgram, val uniforms: Map<String, Any?>,
        override val alpha: Float, override val rotation: Float
    ) : OverlayItem

    companion object {
        private fun f(o: Map<*, *>, k: String, def: Float) = (o[k] as? Number)?.toFloat()?.takeIf { !it.isNaN() } ?: def
        private fun color(o: Map<*, *>, k: String, def: Color) =
            PluginParser.parseColor(o[k]?.toString())?.let { Color(it) } ?: def

        /** Элемент из скрипта; координаты и размеры — в dp от левого верхнего угла клавиатуры. */
        fun parse(v: Any?): OverlayItem? {
            val o = v as? Map<*, *> ?: return null
            val alpha = f(o, "alpha", 1f).coerceIn(0f, 1f)
            val rot = f(o, "rotation", 0f)
            val x = f(o, "x", 0f)
            val y = f(o, "y", 0f)
            return when (val type = o["type"]?.toString() ?: "rect") {
                "rect", "circle", "oval" -> Shape(
                    type, x, y, f(o, "w", f(o, "r", 10f) * 2), f(o, "h", f(o, "w", f(o, "r", 10f) * 2)),
                    color(o, "color", Color.White), f(o, "radius", 0f), f(o, "stroke", 0f), alpha, rot
                )
                "line", "path" -> {
                    val pts = (o["points"] as? List<*>)?.mapNotNull { p ->
                        when (p) {
                            is List<*> -> Offset((p.getOrNull(0) as? Number)?.toFloat() ?: 0f, (p.getOrNull(1) as? Number)?.toFloat() ?: 0f)
                            is Map<*, *> -> Offset(f(p, "x", 0f), f(p, "y", 0f))
                            else -> null
                        }
                    }?.take(2000) ?: listOf(Offset(x, y), Offset(f(o, "x2", x), f(o, "y2", y)))
                    Line(pts, color(o, "color", Color.White), f(o, "width", 2f), o["closed"] == true, o["fill"] == true, alpha, rot)
                }
                "text" -> Label(
                    x, y, o["text"]?.toString().orEmpty().take(500), f(o, "size", 14f).coerceIn(4f, 96f),
                    color(o, "color", Color.White), o["bold"] == true, o["center"] == true, alpha, rot
                )
                "pixels" -> {
                    val rows = (o["rows"] as? List<*>)?.map { it.toString().take(128) }?.take(128) ?: return null
                    val colors = HashMap<Char, Color>()
                    (o["palette"] as? Map<*, *>)?.forEach { (k, c) ->
                        val ch = k.toString().firstOrNull() ?: return@forEach
                        PluginParser.parseColor(c?.toString())?.let { colors[ch] = Color(it) }
                    }
                    Pixels(x, y, f(o, "scale", 3f).coerceIn(0.5f, 40f), rows, colors, o["flipX"] == true, alpha, rot)
                }
                "image" -> {
                    val img = IconStore.decodeDataUri(o["src"]?.toString().orEmpty()) ?: return null
                    Picture(x, y, f(o, "w", 32f), f(o, "h", f(o, "w", 32f)), img, o["flipX"] == true, alpha, rot)
                }
                "shader" -> {
                    val prog = ModShaders.program(o["src"]?.toString().orEmpty()) ?: return null
                    val uniforms = (o["uniforms"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
                    Shader(x, y, f(o, "w", 0f), f(o, "h", 0f), prog, uniforms, alpha, rot)
                }
                else -> null
            }
        }
    }
}

private fun DrawScope.withItem(it: OverlayItem, pivot: Offset, block: DrawScope.() -> Unit) {
    if (it.rotation != 0f) rotate(it.rotation, pivot) { block() } else block()
}

@Composable
internal fun BoxScope.ScriptOverlayLayer() {
    val items = ScriptHost.overlayItems
    val capture = ScriptHost.touchCapture
    ModParticleLayer(overlay = true)
    if (items.isEmpty() && !capture) return
    val measurer = rememberTextMeasurer()
    val path = remember { Path() }
    // мод попросил касания (kb.captureTouches): слой ловит их вместо клавиш — для игр и рисования
    val touch = if (capture) Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                val d = density
                for (c in ev.changes) {
                    val type = when {
                        c.pressed && !c.previousPressed -> "down"
                        !c.pressed && c.previousPressed -> "up"
                        c.pressed -> "move"
                        else -> continue
                    }
                    ScriptHost.touch(type, c.position.x / d, c.position.y / d, c.id.value.toInt())
                    c.consume()
                }
            }
        }
    } else Modifier
    Canvas(Modifier.matchParentSize().then(touch)) { drawOverlayItems(items, measurer, path) }
    // выход всегда есть: клавиши под слоем мода не нажимаются, а мод может и не убрать перехват сам
    if (capture) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).clip(CircleShape).background(Color(0x99000000))
                .clickable { ScriptHost.releaseTouches() },
            contentAlignment = Alignment.Center
        ) { Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Слой мода под клавишами (kb.background): рисуется после фона клавиатуры, но до клавиш. */
@Composable
internal fun BoxScope.ScriptBackgroundLayer() {
    val items = ScriptHost.backgroundItems
    ModParticleLayer(overlay = false)
    if (items.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val path = remember { Path() }
    Canvas(Modifier.matchParentSize()) { drawOverlayItems(items, measurer, path) }
}

/** Элементы kb.overlay (и холста на панели мода): координаты — в dp от угла области рисования. */
internal fun DrawScope.drawOverlayItems(items: List<OverlayItem>, measurer: TextMeasurer, path: Path) {
        val d = density
        for (it in items) {
            when (it) {
                is OverlayItem.Shape -> {
                    val tl = Offset(it.x * d, it.y * d)
                    val sz = Size(it.w * d, it.h * d)
                    val c = it.color.copy(alpha = it.color.alpha * it.alpha)
                    val style = if (it.stroke > 0f) Stroke(it.stroke * d) else androidx.compose.ui.graphics.drawscope.Fill
                    withItem(it, tl + Offset(sz.width / 2f, sz.height / 2f)) {
                        when (it.kind) {
                            "circle", "oval" -> drawOval(c, tl, sz, style = style)
                            else -> drawRoundRect(c, tl, sz, CornerRadius(it.radius * d, it.radius * d), style = style)
                        }
                    }
                }
                is OverlayItem.Line -> {
                    if (it.points.size < 2) continue
                    path.reset()
                    path.moveTo(it.points[0].x * d, it.points[0].y * d)
                    for (i in 1 until it.points.size) path.lineTo(it.points[i].x * d, it.points[i].y * d)
                    if (it.closed || it.fill) path.close()
                    val c = it.color.copy(alpha = it.color.alpha * it.alpha)
                    val first = Offset(it.points[0].x * d, it.points[0].y * d)
                    withItem(it, first) {
                        if (it.fill) drawPath(path, c)
                        else drawPath(path, c, style = Stroke(it.width * d, cap = StrokeCap.Round))
                    }
                }
                is OverlayItem.Label -> {
                    val lay = measurer.measure(
                        AnnotatedString(it.text),
                        TextStyle(fontSize = it.size.sp, fontWeight = if (it.bold) FontWeight.Bold else FontWeight.Normal)
                    )
                    val at = Offset(it.x * d - if (it.center) lay.size.width / 2f else 0f, it.y * d - if (it.center) lay.size.height / 2f else 0f)
                    withItem(it, Offset(it.x * d, it.y * d)) {
                        drawText(lay, color = it.color, topLeft = at, alpha = it.alpha)
                    }
                }
                is OverlayItem.Pixels -> {
                    val px = it.scale * d
                    val w = it.rows.maxOf { r -> r.length } * px
                    val h = it.rows.size * px
                    val tl = Offset(it.x * d, it.y * d)
                    withItem(it, tl + Offset(w / 2f, h / 2f)) {
                        it.rows.forEachIndexed { ry, row ->
                            row.forEachIndexed { rx, ch ->
                                val c = it.colors[ch] ?: return@forEachIndexed
                                val cx = if (it.flipX) row.length - 1 - rx else rx
                                // +0.5 px: соседние пиксели без щелей
                                drawRect(c.copy(alpha = c.alpha * it.alpha), tl + Offset(cx * px, ry * px), Size(px + 0.5f, px + 0.5f))
                            }
                        }
                    }
                }
                is OverlayItem.Shader -> withItem(it, Offset((it.x + it.w / 2f) * d, (it.y + it.h / 2f) * d)) { drawShaderItem(it) }
                is OverlayItem.Picture -> {
                    val tl = Offset(it.x * d, it.y * d)
                    val w = (it.w * d).roundToInt()
                    val h = (it.h * d).roundToInt()
                    withItem(it, tl + Offset(w / 2f, h / 2f)) {
                        val draw: DrawScope.() -> Unit = {
                            drawImage(
                                it.image, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = IntSize(max(1, w), max(1, h)),
                                alpha = it.alpha
                            )
                        }
                        if (it.flipX) scale(-1f, 1f, tl + Offset(w / 2f, h / 2f)) { draw() } else draw()
                    }
                }
            }
        }
}

/** Покадровое событие "frame" для модов: ~30 раз в секунду, пока клавиатура на экране и есть подписчики. */
@Composable
internal fun ScriptFrameTicker() {
    val active = ScriptHost.wantsFrames
    if (!active) return
    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = now - last
            if (dt >= 32L) {
                last = now
                ScriptHost.frame(dt.coerceAtMost(250L))
            }
        }
    }
}
