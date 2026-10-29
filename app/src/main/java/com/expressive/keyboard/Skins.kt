@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Color as AndroidColor
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
// 13. ОФОРМЛЕНИЕ ИЗ ПЛАГИНОВ (SKIN)
// Плагин описывает, как рисовать каждую клавишу: материал (сплошной, жидкое
// стекло, контур, неоморфизм), градиенты, тени, свечение, форму, анимацию
// нажатия, шрифт и подписи. Стекло преломляет фон клавиатуры: на Android 13+
// через AGSL-шейдер, на старых версиях — увеличенной размытой подложкой.
// ============================================================================

/** BEVEL — объёмная «пиксельная» кнопка: светлая кромка сверху слева, тёмная снизу справа (как в Minecraft). */
enum class SkinMaterial { SOLID, GLASS, OUTLINE, NEUMORPH, BEVEL, NONE }
enum class SkinShape { ROUNDED, PILL, CUT, SQUIRCLE, WAVY }
enum class PressEffect { SCALE, JELLY, BOUNCE, SINK, NONE }
/** Контур: сплошной, из точек (как у Nothing) или пунктир. */
enum class BorderStyle { SOLID, DOTS, DASHED }
/** Подпись клавиши: обычная или собранная из точек, как на точечной матрице. */
enum class LabelEffect { NONE, DOTS }

@Immutable
sealed interface SkinFill {
    @Immutable data class Solid(val color: Color) : SkinFill
    @Immutable data class Gradient(val colors: List<Color>, val angle: Float, val radial: Boolean) : SkinFill
    /**
     * Узор из повторяющейся плитки: "pixels" — случайные квадраты из цветов (земля, камень, доски),
     * "dots" — точки colors[1] на фоне colors[0], "planks" — доски с прожилками. cell — размер клетки, dp.
     */
    @Immutable data class Pattern(val kind: String, val colors: List<Color>, val cell: Float, val seed: Int) : SkinFill
}

val SkinFill.primary: Color
    get() = when (this) {
        is SkinFill.Solid -> color
        is SkinFill.Gradient -> colors.first()
        is SkinFill.Pattern -> colors.first()
    }

/** Все цвета заливки через f — для перекраски темы в акцент. */
fun SkinFill.mapColors(f: (Color) -> Color): SkinFill = when (this) {
    is SkinFill.Solid -> SkinFill.Solid(f(color))
    is SkinFill.Gradient -> copy(colors = colors.map(f))
    is SkinFill.Pattern -> copy(colors = colors.map(f))
}

/** Плитки узоров: рисуются один раз на узор и плотность экрана. */
object PatternTiles {
    @Volatile var density = 2.75f
    private val cache = java.util.concurrent.ConcurrentHashMap<Pair<SkinFill.Pattern, Float>, ImageBitmap>()

    fun brush(p: SkinFill.Pattern): Brush {
        val d = density
        val tile = cache.getOrPut(p to d) {
            if (cache.size > 24) cache.clear()
            render(p, d)
        }
        return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }

    private fun render(p: SkinFill.Pattern, density: Float): ImageBitmap {
        val cell = max(1, (p.cell * density).roundToInt())
        val rnd = java.util.Random(p.seed.toLong())
        val colors = p.colors.map { it.toArgb() }
        return when (p.kind) {
            "dots", "dotmask" -> {
                // точка в центре клетки: фон colors[0] (можно прозрачный), точка colors[1]
                val bmp = android.graphics.Bitmap.createBitmap(cell, cell, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp)
                cv.drawColor(colors[0])
                val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { color = colors.getOrElse(1) { AndroidColor.WHITE } }
                cv.drawCircle(cell / 2f, cell / 2f, cell * if (p.kind == "dotmask") 0.4f else 0.17f, paint)
                bmp.asImageBitmap()
            }
            "planks" -> {
                // доски: полосы по 4 клетки, у каждой свой оттенок, тёмная щель и редкие прожилки
                val n = 16
                val px = IntArray(n * n)
                for (row in 0 until n) {
                    val board = row / 4
                    val base = colors[(board + p.seed) % colors.size.coerceAtLeast(1)]
                    for (col in 0 until n) {
                        var c = base
                        if (row % 4 == 3) c = shade(base, 0.62f)
                        else if (rnd.nextInt(9) == 0) c = shade(base, 0.86f)
                        else if ((col + board * 5) % 16 == 0) c = shade(base, 0.7f)
                        px[row * n + col] = c
                    }
                }
                scaleCells(px, n, cell)
            }
            else -> {
                // "pixels": случайные клетки; первый цвет встречается чаще — он основной
                val n = 16
                val px = IntArray(n * n) {
                    val r = rnd.nextInt(100)
                    if (r < 45 || colors.size == 1) colors[0] else colors[1 + rnd.nextInt(colors.size - 1)]
                }
                scaleCells(px, n, cell)
            }
        }
    }

    private fun shade(argb: Int, k: Float): Int {
        val a = argb ushr 24
        val r = (((argb shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * k).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** n×n клеток → картинка, где каждая клетка — квадрат cell×cell пикселей (без сглаживания). */
    private fun scaleCells(px: IntArray, n: Int, cell: Int): ImageBitmap {
        val size = n * cell
        val out = IntArray(size * size)
        for (y in 0 until size) {
            val row = (y / cell) * n
            for (x in 0 until size) out[y * size + x] = px[row + x / cell]
        }
        return android.graphics.Bitmap.createBitmap(out, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Маска точечной матрицы для подписей: непрозрачные точки на прозрачном фоне. */
    fun dotMask(cellPx: Float): Brush {
        val p = SkinFill.Pattern("dotmask", listOf(Color.Transparent, Color.White), cellPx / density, 0)
        return brush(p)
    }

    /** Мелкое зерно матового стекла: светлые и тёмные крапинки. */
    val grain: Brush by lazy {
        val n = 96
        val rnd = java.util.Random(11)
        val px = IntArray(n * n) {
            val v = rnd.nextInt(256)
            val a = (rnd.nextInt(56) + 8)
            (a shl 24) or (if (v > 128) 0xFFFFFF else 0x000000)
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
    }
}

/** Угол в градусах: 0 — слева направо, 90 — сверху вниз. */
fun SkinFill.brush(rect: Rect): Brush = when (this) {
    is SkinFill.Solid -> SolidColor(color)
    is SkinFill.Pattern -> PatternTiles.brush(this)
    is SkinFill.Gradient -> if (radial) {
        Brush.radialGradient(colors, rect.center, (max(rect.width, rect.height) / 2f).coerceAtLeast(1f))
    } else {
        val rad = angle * PI / 180.0
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val half = (abs(dx) * rect.width + abs(dy) * rect.height) / 2f
        Brush.linearGradient(colors, rect.center - Offset(dx * half, dy * half), rect.center + Offset(dx * half, dy * half))
    }
}

@Immutable
data class GlassSpec(
    val blur: Float = 14f,          // dp, размытие фона под стеклом
    val refraction: Float = 0.6f,   // 0..1, насколько края «ломают» картинку
    val dispersion: Float = 0.25f,  // 0..1, радужная кайма на краях
    val highlight: Float = 0.7f,    // 0..1, блик сверху
    val rim: Float = 0.7f,          // 0..1, светлый ободок
    val frost: Float = 0f,          // 0..1, молочная дымка
    val tint: Color? = null,
    val grain: Float = 0f,          // 0..1, зерно матового стекла
    val liquid: Float = 0f,         // 0..1, «жидкое» стекло: линза, сглаживание фона, объёмные блики (Android 13+)
    val opacity: Float = 1f         // 0..1, непрозрачность клавиши целиком
)

@Immutable
data class KeyStyle(
    val material: SkinMaterial? = null,
    val fill: SkinFill? = null,
    val pressedFill: SkinFill? = null,
    val text: Color? = null,
    val pressedText: Color? = null,
    val border: SkinFill? = null,
    val borderWidth: Float? = null,
    val radius: Float? = null,
    val pressedRadius: Float? = null,
    val shape: SkinShape? = null,
    val shadow: Float? = null,
    val shadowColor: Color? = null,
    val shadowY: Float? = null,
    val glow: Color? = null,
    val textGlow: Color? = null,
    val glass: GlassSpec? = null,
    val fontScale: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val label: String? = null,
    val press: PressEffect? = null,
    val pressScale: Float? = null,
    val inset: Float? = null,
    val hideHint: Boolean? = null,
    val borderStyle: BorderStyle? = null,
    val labelEffect: LabelEffect? = null,
    /** Жёсткая тень подписи без размытия (как текст в Minecraft). */
    val textShadow: Color? = null,
    /** Толщина объёмной кромки BEVEL, dp. */
    val bevel: Float? = null
) {
    /** Поля этого стиля важнее полей base. */
    infix fun over(base: KeyStyle): KeyStyle = KeyStyle(
        material ?: base.material, fill ?: base.fill, pressedFill ?: base.pressedFill, text ?: base.text,
        pressedText ?: base.pressedText, border ?: base.border, borderWidth ?: base.borderWidth, radius ?: base.radius,
        pressedRadius ?: base.pressedRadius, shape ?: base.shape, shadow ?: base.shadow, shadowColor ?: base.shadowColor,
        shadowY ?: base.shadowY, glow ?: base.glow, textGlow ?: base.textGlow, glass ?: base.glass,
        fontScale ?: base.fontScale, fontWeight ?: base.fontWeight, italic ?: base.italic, label ?: base.label,
        press ?: base.press, pressScale ?: base.pressScale, inset ?: base.inset, hideHint ?: base.hideHint,
        borderStyle ?: base.borderStyle, labelEffect ?: base.labelEffect, textShadow ?: base.textShadow, bevel ?: base.bevel
    )

    /** Все цвета стиля через f. */
    fun mapColors(f: (Color) -> Color): KeyStyle = copy(
        fill = fill?.mapColors(f), pressedFill = pressedFill?.mapColors(f), text = text?.let(f), pressedText = pressedText?.let(f),
        border = border?.mapColors(f), shadowColor = shadowColor?.let(f), glow = glow?.let(f), textGlow = textGlow?.let(f),
        glass = glass?.let { g -> g.copy(tint = g.tint?.let(f)) }, textShadow = textShadow?.let(f)
    )
}

@Immutable
data class SkinOrb(val color: Color, val x: Float, val y: Float, val r: Float)

@Immutable
data class KeyboardSkin(
    val background: SkinFill? = null,
    val orbs: List<SkinOrb> = emptyList(),
    val styles: Map<String, KeyStyle> = emptyMap(),
    val popup: KeyStyle? = null,
    val chips: KeyStyle? = null,
    val trail: Color? = null,
    val colors: Map<String, Int> = emptyMap(),
    val corner: Float? = null,
    val font: String? = null,
    /** Круглые кнопки по краям строки подсказок (меню, микрофон). */
    val buttons: KeyStyle? = null,
    /** Подложка панелей: эмодзи, буфер, голос, переводчик, плагины. */
    val panel: KeyStyle? = null,
    /** Размытие приложения под клавиатурой (dp) — настоящее «стекло» на Android 12+. */
    val windowBlur: Float? = null,
    /** Дымка поверх размытия: насколько непрозрачен фон клавиатуры. */
    val blurTint: Color? = null,
    /** Светлый ободок по верхнему краю клавиатуры — край стеклянного листа. */
    val edge: Color? = null,
    /** Поправки для тёмной темы: накладываются поверх основного оформления. */
    val dark: KeyboardSkin? = null,
    /** Непрозрачность клавиш из настроек темы (умножается на настройку «Непрозрачность клавиш»). */
    val keyAlpha: Float = 1f
) {
    /** Подсказки текстом через разделители, без «таблеток». */
    val plainChips: Boolean get() = chips?.material == SkinMaterial.NONE

    /** Оформление под светлую или тёмную тему. */
    fun forMode(isDark: Boolean): KeyboardSkin {
        val d = dark ?: return this
        if (!isDark) return copy(dark = null)
        val merged = LinkedHashMap(styles)
        d.styles.forEach { (k, v) -> merged[k] = merged[k]?.let { v over it } ?: v }
        fun pick(a: KeyStyle?, b: KeyStyle?) = if (a == null) b else if (b == null) a else a over b
        return KeyboardSkin(
            background = d.background ?: background,
            orbs = d.orbs.ifEmpty { orbs },
            styles = merged,
            popup = pick(d.popup, popup),
            chips = pick(d.chips, chips),
            trail = d.trail ?: trail,
            colors = colors + d.colors,
            corner = d.corner ?: corner,
            font = d.font ?: font,
            buttons = pick(d.buttons, buttons),
            panel = pick(d.panel, panel),
            windowBlur = d.windowBlur ?: windowBlur,
            blurTint = d.blurTint ?: blurTint,
            edge = d.edge ?: edge,
            keyAlpha = keyAlpha
        )
    }

    val usesGlass: Boolean = styles.values.any { it.material == SkinMaterial.GLASS } ||
        popup?.material == SkinMaterial.GLASS || chips?.material == SkinMaterial.GLASS
    val usesLiquid: Boolean = styles.values.any { (it.glass?.liquid ?: 0f) > 0f }
    val glassBlur: Float = styles.values.mapNotNull { it.glass?.blur }.maxOrNull() ?: GlassSpec().blur
    val hasBackground: Boolean get() = background != null || orbs.isNotEmpty()

    /** Все цвета оформления через f (и тёмный вариант тоже) — так тема перекрашивается в акцент. */
    fun mapColors(f: (Color) -> Color, mapColorsMap: Boolean = true): KeyboardSkin = copy(
        background = background?.mapColors(f),
        orbs = orbs.map { it.copy(color = f(it.color)) },
        styles = styles.mapValues { it.value.mapColors(f) },
        popup = popup?.mapColors(f),
        chips = chips?.mapColors(f),
        trail = trail?.let(f),
        colors = if (mapColorsMap) colors.mapValues { f(Color(it.value)).toArgb() } else colors,
        buttons = buttons?.mapColors(f),
        panel = panel?.mapColors(f),
        blurTint = blurTint?.let(f),
        dark = dark?.mapColors(f, mapColorsMap)
    )

    /** Все стили через f: так применяются настройки темы (скругление, тени, прозрачность…). */
    fun mapStyles(f: (KeyStyle) -> KeyStyle): KeyboardSkin = copy(
        styles = styles.mapValues { f(it.value) },
        popup = popup?.let(f), chips = chips?.let(f), buttons = buttons?.let(f), panel = panel?.let(f),
        dark = dark?.mapStyles(f)
    )

    /** "*" → группа (letter/func) → digit → тип клавиши → "key:<подпись>". */
    fun styleFor(type: KeyType, label: String): KeyStyle? {
        val chain = ArrayList<String>(5)
        chain += "*"
        when (type) {
            KeyType.CHARACTER -> {
                chain += "letter"
                if (label.length == 1 && label[0].isDigit()) chain += "digit"
            }
            KeyType.SHIFT -> { chain += "func"; chain += "shift" }
            KeyType.BACKSPACE -> { chain += "func"; chain += "backspace" }
            KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE -> { chain += "func"; chain += "mode" }
            KeyType.GLOBE -> { chain += "func"; chain += "globe" }
            KeyType.STICKERS -> { chain += "func"; chain += "emoji" }
            KeyType.SPACE -> chain += "space"
            KeyType.ENTER -> chain += "enter"
            KeyType.SPACER -> return null
        }
        if (type == KeyType.CHARACTER) chain += "key:${label.lowercase()}"
        var acc: KeyStyle? = null
        for (sel in chain) {
            val s = styles[sel] ?: continue
            acc = if (acc == null) s else s over acc
        }
        return acc
    }

    fun fontFamily(fallback: FontFamily): FontFamily = when (font) {
        "serif" -> FontFamily.Serif
        "mono", "monospace" -> FontFamily.Monospace
        "sans" -> FontFamily.SansSerif
        "cursive" -> FontFamily.Cursive
        "pixel" -> FontRepository.pixel
        else -> fallback
    }

    /** Цвета оформления поверх настроек (как у темы, но без записи в настройки). */
    fun applyColors(s: KeyboardSettings): KeyboardSettings {
        val s = if (keyAlpha < 1f) s.copy(keyOpacity = s.keyOpacity * keyAlpha) else s
        if (colors.isEmpty()) return s
        return s.copy(
            colorBackground = colors["background"] ?: s.colorBackground,
            colorKey = colors["key"] ?: s.colorKey,
            colorKeyText = colors["keyText"] ?: s.colorKeyText,
            colorFunc = colors["func"] ?: s.colorFunc,
            colorFuncText = colors["funcText"] ?: s.colorFuncText,
            colorAccent = colors["accent"] ?: s.colorAccent,
            colorAccentText = colors["accentText"] ?: s.colorAccentText
        )
    }
}

val LocalSkin = staticCompositionLocalOf<KeyboardSkin?> { null }
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

// ------------------------------------------------------------------ разбор JSON

object SkinParser {
    private val SELECTORS = setOf("*", "letter", "digit", "func", "shift", "backspace", "mode", "globe", "emoji", "space", "enter")

    private fun color(s: String?, field: String): Color? {
        if (s.isNullOrBlank()) return null
        val c = PluginParser.parseColor(s) ?: throw IllegalArgumentException("$field: неверный цвет «$s»")
        return Color(c)
    }

    private fun colors(a: JSONArray, field: String): List<Color> {
        val list = (0 until min(a.length(), 8)).mapNotNull { color(a.optString(it), field) }
        require(list.isNotEmpty()) { "$field: пустой градиент" }
        return if (list.size == 1) list + list else list
    }

    fun fill(v: Any?, field: String): SkinFill? = when (v) {
        null, JSONObject.NULL -> null
        is String -> color(v, field)?.let { SkinFill.Solid(it) }
        is JSONArray -> SkinFill.Gradient(colors(v, field), 90f, false)
        is JSONObject -> {
            val arr = v.optJSONArray("colors")
            val pattern = v.optString("pattern").lowercase()
            if (pattern.isNotEmpty() && arr != null) {
                require(pattern in setOf("pixels", "dots", "planks")) { "$field.pattern: pixels, dots или planks" }
                SkinFill.Pattern(pattern, colors(arr, field), v.optDouble("cell", 3.0).toFloat().coerceIn(1f, 24f), v.optInt("seed", 7))
            } else if (arr == null) color(v.optString("color"), field)?.let { SkinFill.Solid(it) }
            else SkinFill.Gradient(colors(arr, field), v.optDouble("angle", 90.0).toFloat(), v.optString("type") == "radial")
        }
        else -> throw IllegalArgumentException("$field: ожидается цвет или градиент")
    }

    private fun JSONObject.f(name: String): Float? = if (has(name)) optDouble(name).toFloat().takeIf { !it.isNaN() } else null
    private fun JSONObject.b(name: String): Boolean? = if (has(name)) optBoolean(name) else null

    private inline fun <reified T : Enum<T>> JSONObject.e(name: String): T? {
        val raw = optString(name).uppercase()
        if (raw.isEmpty()) return null
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("$name: «${optString(name)}» — допустимо ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    fun glass(o: JSONObject): GlassSpec {
        val d = GlassSpec()
        return GlassSpec(
            blur = o.f("blur")?.coerceIn(0f, 40f) ?: d.blur,
            refraction = o.f("refraction")?.coerceIn(0f, 1f) ?: d.refraction,
            dispersion = o.f("dispersion")?.coerceIn(0f, 1f) ?: d.dispersion,
            highlight = o.f("highlight")?.coerceIn(0f, 1f) ?: d.highlight,
            rim = o.f("rim")?.coerceIn(0f, 1f) ?: d.rim,
            frost = o.f("frost")?.coerceIn(0f, 1f) ?: d.frost,
            tint = color(o.optString("tint"), "glass.tint"),
            grain = o.f("grain")?.coerceIn(0f, 1f) ?: d.grain,
            liquid = o.f("liquid")?.coerceIn(0f, 1f) ?: d.liquid,
            opacity = o.f("opacity")?.coerceIn(0f, 1f) ?: d.opacity
        )
    }

    fun style(o: JSONObject, name: String): KeyStyle {
        val material = o.e<SkinMaterial>("material")
        val border = fill(o.opt("border"), "$name.border")
        return KeyStyle(
            material = material,
            fill = fill(o.opt("background"), "$name.background"),
            pressedFill = fill(o.opt("pressedBackground"), "$name.pressedBackground"),
            text = color(o.optString("text"), "$name.text"),
            pressedText = color(o.optString("pressedText"), "$name.pressedText"),
            border = border,
            borderWidth = o.f("borderWidth")?.coerceIn(0f, 8f) ?: if (border != null) 1f else null,
            radius = o.f("radius")?.coerceIn(0f, 64f),
            pressedRadius = o.f("pressedRadius")?.coerceIn(0f, 64f),
            shape = o.e<SkinShape>("shape"),
            shadow = o.f("shadow")?.coerceIn(0f, 24f),
            shadowColor = color(o.optString("shadowColor"), "$name.shadowColor"),
            shadowY = o.f("shadowY")?.coerceIn(-16f, 16f),
            glow = color(o.optString("glow"), "$name.glow"),
            textGlow = color(o.optString("textGlow"), "$name.textGlow"),
            glass = o.optJSONObject("glass")?.let { glass(it) } ?: if (material == SkinMaterial.GLASS) GlassSpec() else null,
            fontScale = o.f("fontScale")?.coerceIn(0.4f, 2f),
            fontWeight = if (o.has("fontWeight")) o.optInt("fontWeight").coerceIn(100, 900) else null,
            italic = o.b("italic"),
            label = o.optString("label").takeIf { o.has("label") }?.take(12),
            press = o.e<PressEffect>("press"),
            pressScale = o.f("pressScale")?.coerceIn(0.6f, 1.3f),
            inset = o.f("inset")?.coerceIn(0f, 12f),
            hideHint = o.b("hideHint"),
            borderStyle = o.e<BorderStyle>("borderStyle"),
            labelEffect = o.e<LabelEffect>("labelEffect"),
            textShadow = color(o.optString("textShadow"), "$name.textShadow"),
            bevel = o.f("bevel")?.coerceIn(0f, 8f)
        )
    }

    fun parse(o: JSONObject, nested: Boolean = false): KeyboardSkin {
        val kb = o.optJSONObject("keyboard") ?: JSONObject()
        val orbs = kb.optJSONArray("orbs")?.let { a ->
            (0 until min(a.length(), 12)).mapNotNull { i ->
                val b = a.optJSONObject(i) ?: return@mapNotNull null
                val c = color(b.optString("color"), "keyboard.orbs") ?: return@mapNotNull null
                SkinOrb(
                    c, b.optDouble("x", 0.5).toFloat().coerceIn(-0.5f, 1.5f), b.optDouble("y", 0.5).toFloat().coerceIn(-0.5f, 1.5f),
                    b.optDouble("r", 0.35).toFloat().coerceIn(0.02f, 2f)
                )
            }
        }.orEmpty()
        val styles = LinkedHashMap<String, KeyStyle>()
        o.optJSONObject("keys")?.let { k ->
            k.keys().forEach { sel ->
                require(sel in SELECTORS || sel.startsWith("key:")) { "keys: неизвестный селектор «$sel»" }
                styles[sel] = style(k.getJSONObject(sel), sel)
            }
        }
        val colors = HashMap<String, Int>()
        o.optJSONObject("colors")?.let { c ->
            c.keys().forEach { key -> if (key in PluginParser.COLOR_KEYS) PluginParser.parseColor(c.optString(key))?.let { colors[key] = it } }
        }
        return KeyboardSkin(
            background = fill(kb.opt("background"), "keyboard.background"),
            orbs = orbs,
            styles = styles,
            popup = o.optJSONObject("popup")?.let { style(it, "popup") },
            chips = o.optJSONObject("chips")?.let { style(it, "chips") },
            trail = color(kb.optString("trail"), "keyboard.trail"),
            colors = colors,
            corner = kb.f("corner")?.coerceIn(0f, 48f),
            font = kb.optString("font").lowercase().takeIf { it.isNotEmpty() },
            buttons = o.optJSONObject("buttons")?.let { style(it, "buttons") },
            panel = o.optJSONObject("panel")?.let { style(it, "panel") },
            windowBlur = kb.f("windowBlur")?.coerceIn(0f, 150f),
            blurTint = color(kb.optString("blurTint"), "keyboard.blurTint"),
            edge = color(kb.optString("edge"), "keyboard.edge"),
            dark = if (nested) null else o.optJSONObject("dark")?.let { parse(it, true) }
        )
    }
}

// ------------------------------------------------------------------ подложка для стекла

/** Размытая копия фона клавиатуры: стеклянные клавиши «видят» её под собой. */
@Stable
class Backdrop {
    var coords: LayoutCoordinates? = null
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    /** Пикселей подложки на пиксель клавиатуры. */
    var scale = 1f
    /** Окно клавиатуры само размывает приложение: подложка тут только для линзы, клавиши остаются прозрачными. */
    var overWindowBlur = false
}

internal fun DrawScope.drawSkinBackground(skin: KeyboardSkin, area: Size) {
    val rect = Rect(Offset.Zero, area)
    skin.background?.let { drawRect(it.brush(rect), size = area) }
    for (o in skin.orbs) {
        val c = Offset(o.x * area.width, o.y * area.height)
        val r = o.r * max(area.width, area.height)
        drawCircle(Brush.radialGradient(listOf(o.color, o.color.copy(alpha = 0f)), c, r), r, c)
    }
}

/** Рисует фон в уменьшенную картинку и размывает её. Вызывается в фоне, раз на размер/фон. */
internal fun renderBackdrop(
    width: Int,
    height: Int,
    density: Density,
    skin: KeyboardSkin,
    bg: Color,
    image: ImageBitmap?,
    dim: Float
): Pair<ImageBitmap, Float> {
    val blurPx = with(density) { skin.glassBlur.dp.toPx() }
    val s = if (skin.glassBlur < 4f) 0.5f else 0.25f
    val bw = max(1, (width * s).roundToInt())
    val bh = max(1, (height * s).roundToInt())
    val bmp = ImageBitmap(bw, bh, ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bmp), Size(bw.toFloat(), bh.toFloat())) {
        drawRect(bg.copy(alpha = 1f))
        scale(s, s, pivot = Offset.Zero) {
            val area = Size(width.toFloat(), height.toFloat())
            drawSkinBackground(skin, area)
            if (image != null) {
                val k = max(width / image.width.toFloat(), height / image.height.toFloat())
                val sw = (width / k).roundToInt().coerceIn(1, image.width)
                val sh = (height / k).roundToInt().coerceIn(1, image.height)
                drawImage(
                    image, IntOffset((image.width - sw) / 2, (image.height - sh) / 2), IntSize(sw, sh),
                    IntOffset.Zero, IntSize(width, height), filterQuality = FilterQuality.Low
                )
                if (dim > 0f) drawRect(bg.copy(alpha = dim), size = area)
            }
        }
    }
    val r = (blurPx * s / 2f).roundToInt()
    if (r > 0) {
        val ab = bmp.asAndroidBitmap()
        val px = IntArray(bw * bh)
        ab.getPixels(px, 0, bw, 0, 0, bw, bh)
        repeat(3) { boxBlur(px, bw, bh, r) }
        ab.setPixels(px, 0, bw, 0, 0, bw, bh)
    }
    return bmp to s
}

/** Разделимое размытие «коробкой»; три прохода ≈ гауссово. */
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    pass(px, tmp, w, h, r, true)
    pass(tmp, px, w, h, r, false)
}

private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun idx(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) line * w + c else c * w + line
        }
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val p = src[idx(i)]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
        }
        for (i in 0 until len) {
            dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val out = src[idx(i - r)]
            val inp = src[idx(i + r + 1)]
            sr += ((inp shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inp shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inp and 0xFF) - (out and 0xFF)
        }
    }
}

// ------------------------------------------------------------------ отрисовка клавиши

/** Кэш одной клавиши: путь, шейдеры, Paint — без аллокаций на кадр. */
internal class SkinKeyCache {
    val path = Path()
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val matrix = AndroidMatrix()
    var runtime: Any? = null
    var liquid: Any? = null
    var bitmapShader: BitmapShader? = null
    var bitmapFor: ImageBitmap? = null
    /** Тени клавиши в покое — отрисованы один раз в картинку (размытие тени дорогое). */
    var shadowBmp: android.graphics.Bitmap? = null
    var shadowKey: Any? = null
    /** Куда нажал палец (в пикселях клавиши): блик жидкого стекла светится под пальцем. */
    var touch: Offset? = null
    /** Градиенты по размеру клавиши: создаются один раз, а не на каждый кадр. */
    private val brushes = HashMap<Any, Brush>()

    fun brush(key: Any, make: () -> Brush): Brush {
        brushes[key]?.let { return it }
        if (brushes.size > 24) brushes.clear()
        return make().also { brushes[key] = it }
    }

    fun fill(f: SkinFill, rect: Rect): Brush =
        if (f is SkinFill.Solid) SolidColor(f.color) else brush(Triple(f, rect.width, rect.height)) { f.brush(rect) }
}

internal fun buildKeyPath(path: Path, shape: SkinShape, rect: Rect, r: Float) {
    path.reset()
    when (shape) {
        SkinShape.ROUNDED -> path.addRoundRect(RoundRect(rect, CornerRadius(r, r)))
        SkinShape.PILL -> {
            val pr = min(rect.width, rect.height) / 2f
            path.addRoundRect(RoundRect(rect, CornerRadius(pr, pr)))
        }
        SkinShape.CUT -> {
            val c = r.coerceAtMost(min(rect.width, rect.height) / 2f)
            path.moveTo(rect.left + c, rect.top)
            path.lineTo(rect.right - c, rect.top)
            path.lineTo(rect.right, rect.top + c)
            path.lineTo(rect.right, rect.bottom - c)
            path.lineTo(rect.right - c, rect.bottom)
            path.lineTo(rect.left + c, rect.bottom)
            path.lineTo(rect.left, rect.bottom - c)
            path.lineTo(rect.left, rect.top + c)
            path.close()
        }
        SkinShape.SQUIRCLE -> {
            // суперэллипс |x|^4 + |y|^4 = 1, растянутый по клавише
            val cx = rect.center.x
            val cy = rect.center.y
            val a = rect.width / 2f
            val b = rect.height / 2f
            val n = 48
            for (i in 0 until n) {
                val t = i * 2.0 * PI / n
                val ct = cos(t)
                val st = sin(t)
                val x = cx + a * (abs(ct).pow(0.5) * if (ct < 0) -1 else 1).toFloat()
                val y = cy + b * (abs(st).pow(0.5) * if (st < 0) -1 else 1).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        SkinShape.WAVY -> {
            val tmp = buildWavyPath(WavySpec(ShapeProfile.SINE, 10, 0.06f), 0f, 0f, 1f)
            val m = AndroidMatrix()
            m.setScale(rect.width / 2f, rect.height / 2f)
            m.postTranslate(rect.center.x, rect.center.y)
            val ap = tmp.asAndroidPath()
            ap.transform(m)
            path.addPath(ap.asComposePath())
        }
    }
}

private fun DrawScope.outerShadow(c: SkinKeyCache, blur: Float, dx: Float, dy: Float, color: Color) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    drawIntoCanvas { cv -> shadowOn(cv.nativeCanvas, c, blur, dx, dy, color) }
}

private fun shadowOn(nc: android.graphics.Canvas, c: SkinKeyCache, blur: Float, dx: Float, dy: Float, color: Color) {
    if (blur <= 0.5f || color.alpha <= 0.01f) return
    val ap = c.path.asAndroidPath()
    nc.save()
    nc.clipOutPath(ap)
    c.paint.style = AndroidPaint.Style.FILL
    c.paint.shader = null
    c.paint.color = AndroidColor.BLACK
    c.paint.setShadowLayer(blur, dx, dy, color.toArgb())
    nc.drawPath(ap, c.paint)
    c.paint.clearShadowLayer()
    nc.restore()
}

/** Тени и свечение клавиши в покое: рисуются один раз в картинку, дальше она просто копируется. */
private fun DrawScope.restShadows(c: SkinKeyCache, key: Any, pad: Float, draw: (android.graphics.Canvas) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    var bmp = c.shadowBmp
    if (c.shadowKey != key || bmp == null) {
        val w = (size.width + 2 * pad).roundToInt().coerceAtLeast(1)
        val h = (size.height + 2 * pad).roundToInt().coerceAtLeast(1)
        bmp = bmp?.takeIf { it.width == w && it.height == h }?.also { it.eraseColor(AndroidColor.TRANSPARENT) }
            ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.translate(pad, pad)
        draw(cv)
        c.shadowBmp = bmp
        c.shadowKey = key
    }
    drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, -pad, -pad, null) }
}

/**
 * Клавиша в стиле плагина. p — прогресс нажатия 0..1, restR/pressR — скругление по умолчанию,
 * keyPos — положение клавиши относительно фона клавиатуры (для стекла).
 */
/** Что рисовать: тени и свечение (вне клавиши) и/или саму клавишу — их можно разнести по разным слоям. */
internal const val SKIN_SHADOW = 1
internal const val SKIN_BODY = 2
internal const val SKIN_ALL = 3

internal fun DrawScope.drawSkinKey(
    st: KeyStyle,
    base: KeyColors,
    p: Float,
    restR: Float,
    pressR: Float,
    c: SkinKeyCache,
    backdrop: Backdrop?,
    part: Int = SKIN_ALL,
    keyPos: () -> Offset?
) {
    val inset = (st.inset ?: 0f).dp.toPx()
    val rect = Rect(inset, inset, size.width - inset, size.height - inset)
    if (rect.width <= 1f || rect.height <= 1f) return
    val m = min(rect.width, rect.height)
    // скругление темы — не больше трети меньшей стороны: низкие клавиши (ряд цифр) не превращаются в капсулы
    val cap = m * 0.34f
    val r0 = st.radius?.dp?.toPx()?.coerceAtMost(cap) ?: restR
    val r1 = st.pressedRadius?.dp?.toPx()?.coerceAtMost(cap) ?: if (st.radius != null) r0 else pressR
    val r = lerpF(r0, r1, p).coerceIn(0f, m / 2f)
    buildKeyPath(c.path, st.shape ?: SkinShape.ROUNDED, rect, r)
    val material = st.material ?: SkinMaterial.SOLID
    val fill = st.fill ?: SkinFill.Solid(base.bg)
    val pressed = st.pressedFill ?: SkinFill.Solid(base.activeBg)
    val dark = base.bg.alpha < 0.05f || base.bg.luminance() < 0.4f

    // тень: нажатая клавиша «прижимается» к фону; свечение у контура горит всегда, у остальных — при нажатии
    val shadow = st.shadow ?: if (material == SkinMaterial.NEUMORPH) 7f else 0f
    val glowPx = 12.dp.toPx()
    val shadowColor = st.shadowColor ?: if (material == SkinMaterial.NEUMORPH) Color.Black.copy(alpha = if (dark) 0.55f else 0.22f)
    else Color.Black.copy(alpha = 0.3f)
    val lightColor = Color.White.copy(alpha = if (dark) 0.11f else 0.75f)
    fun shadows(cv: android.graphics.Canvas, p: Float) {
        if (shadow > 0f) {
            val blur = shadow.dp.toPx() * (1f - 0.5f * p)
            val off = (st.shadowY ?: shadow * 0.45f).dp.toPx() * (1f - 0.7f * p)
            if (material == SkinMaterial.NEUMORPH) {
                shadowOn(cv, c, blur, off, off, shadowColor)
                shadowOn(cv, c, blur, -off, -off, lightColor)
            } else {
                shadowOn(cv, c, blur, 0f, off, shadowColor)
            }
        }
        st.glow?.let { g ->
            val k = if (material == SkinMaterial.OUTLINE) 0.55f + 0.45f * p else p
            if (k > 0.01f) shadowOn(cv, c, glowPx, 0f, 0f, g.copy(alpha = g.alpha * k))
        }
    }
    if ((part and SKIN_SHADOW) != 0 && (shadow > 0f || st.glow != null)) {
        val draw: DrawScope.() -> Unit = {
            if (p < 0.01f) {
                // в покое — готовая картинка теней; живые тени — только у нажатой клавиши
                val pad = (shadow.dp.toPx() * 2.5f + glowPx * 2f).coerceAtLeast(1f)
                restShadows(c, listOf(size.width, size.height, r, st, dark), pad) { shadows(it, 0f) }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                drawIntoCanvas { shadows(it.nativeCanvas, p) }
            }
        }
        if (material == SkinMaterial.NEUMORPH) {
            // соседние клавиши рисуются позже и их светлая тень ложилась на край этой клавиши — контур
            // размывался (особенно в светлой теме). Тени не выходят за ячейку клавиши дальше отступа
            val spill = max(inset, 2.dp.toPx())
            clipRect(-spill, -spill, size.width + spill, size.height + spill) { draw() }
        } else draw()
    }

    if ((part and SKIN_BODY) == 0) return
    when (material) {
        SkinMaterial.SOLID, SkinMaterial.NEUMORPH -> {
            drawPath(c.path, c.fill(fill, rect))
            if (p > 0.001f) drawPath(c.path, c.fill(pressed, rect), alpha = p)
            if (material == SkinMaterial.NEUMORPH && st.borderWidth == null) {
                // чёткий край: свет сверху слева, тень снизу справа — клавиша не сливается с фоном
                val edge = c.brush(Triple("nedge", rect, dark)) {
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.07f else 0.8f), Color.Transparent,
                            shadowColor.copy(alpha = shadowColor.alpha * if (dark) 0.5f else 0.55f)
                        ),
                        rect.topLeft, rect.bottomRight
                    )
                }
                drawPath(c.path, edge, alpha = 1f - 0.6f * p, style = Stroke(1.dp.toPx()))
            }
        }
        SkinMaterial.OUTLINE, SkinMaterial.NONE -> {
            st.fill?.let { drawPath(c.path, c.fill(it, rect)) }
            if (p > 0.001f) drawPath(c.path, c.fill(pressed, rect), alpha = p * if (material == SkinMaterial.NONE) 0.6f else 0.4f)
        }
        SkinMaterial.GLASS -> drawGlass(st, st.glass ?: GlassSpec(), pressed, p, rect, r, c, backdrop, keyPos, dark)
        SkinMaterial.BEVEL -> if (r < 0.5f) {
            // пиксельная кнопка рисуется по целым пикселям сама, вместе с рамкой
            drawPixelBevel(st, fill, pressed, p, rect, c)
            return
        } else drawBevel(st, fill, pressed, p, rect, c)
    }

    val bw = st.borderWidth ?: if (material == SkinMaterial.OUTLINE) 1.5f else 0f
    if (bw > 0f) {
        val brush = c.fill(st.border ?: SkinFill.Solid(base.fg.copy(alpha = 0.55f)), rect)
        val w = bw.dp.toPx()
        val stroke = when (st.borderStyle) {
            // точки: штрих нулевой длины с круглым концом — ровный пунктир из точек, как в дизайне Nothing
            BorderStyle.DOTS -> Stroke(w, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 2.4f)))
            BorderStyle.DASHED -> Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3f, w * 2f)))
            else -> Stroke(w)
        }
        drawPath(c.path, brush, style = stroke)
    }
}

/**
 * Прямоугольная пиксельная кнопка (Minecraft): всё выровнено по целым пикселям экрана и рисуется прямоугольниками,
 * без сглаживания краёв. Раньше текстура обрезалась сглаженным контуром со сдвигом на полпикселя — по краю
 * каждой клавиши шла размытая светлая линия, а поверх лежала толстая обводка: текстура выглядела наклейкой.
 */
private fun DrawScope.drawPixelBevel(st: KeyStyle, fill: SkinFill, pressed: SkinFill, p: Float, rect0: Rect, c: SkinKeyCache) {
    val rect = Rect(rect0.left.roundToInt().toFloat(), rect0.top.roundToInt().toFloat(), rect0.right.roundToInt().toFloat(), rect0.bottom.roundToInt().toFloat())
    if (rect.width < 2f || rect.height < 2f) return
    drawRect(c.fill(fill, rect), rect.topLeft, rect.size)
    if (p > 0.001f) drawRect(c.fill(pressed, rect), rect.topLeft, rect.size, alpha = p)
    // рамка (если в теме задана) — по внешнему краю, целым числом пикселей
    val bw = st.borderWidth ?: 0f
    var inner = rect
    if (bw > 0f && st.border != null) {
        val w = max(1f, (bw.dp.toPx()).roundToInt().toFloat())
        val brush = c.fill(st.border, rect)
        drawRect(brush, rect.topLeft, Size(rect.width, w))
        drawRect(brush, Offset(rect.left, rect.bottom - w), Size(rect.width, w))
        drawRect(brush, Offset(rect.left, rect.top + w), Size(w, rect.height - 2 * w))
        drawRect(brush, Offset(rect.right - w, rect.top + w), Size(w, rect.height - 2 * w))
        inner = Rect(rect.left + w, rect.top + w, rect.right - w, rect.bottom - w)
    }
    val b = max(1f, ((st.bevel ?: 2.5f).dp.toPx()).roundToInt().toFloat())
    if (inner.width <= 2 * b || inner.height <= 2 * b) return
    // нажатая кнопка «вдавлена»: свет и тень меняются местами
    val inverted = p > 0.5f
    val light = Color.White.copy(alpha = 0.38f)
    val shade = Color.Black.copy(alpha = 0.34f)
    val tl = if (inverted) shade else light
    val br = if (inverted) light else shade
    drawRect(tl, inner.topLeft, Size(inner.width - b, b))
    drawRect(tl, Offset(inner.left, inner.top + b), Size(b, inner.height - 2 * b))
    drawRect(br, Offset(inner.left, inner.bottom - b), Size(inner.width, b))
    drawRect(br, Offset(inner.right - b, inner.top), Size(b, inner.height - b))
}

/** Объёмная кнопка из пикселей: светлая кромка сверху и слева, тёмная снизу и справа; при нажатии — наоборот. */
private fun DrawScope.drawBevel(st: KeyStyle, fill: SkinFill, pressed: SkinFill, p: Float, rect: Rect, c: SkinKeyCache) {
    drawPath(c.path, c.fill(fill, rect))
    if (p > 0.001f) drawPath(c.path, c.fill(pressed, rect), alpha = p)
    val b = (st.bevel ?: 2.5f).dp.toPx()
    val light = Color.White.copy(alpha = 0.42f)
    val dark = Color.Black.copy(alpha = 0.38f)
    // нажатая кнопка «вдавлена»: свет и тень меняются местами
    val inverted = p > 0.5f
    val tl = if (inverted) dark else light
    val br = if (inverted) light else dark
    clipPath(c.path) {
        drawRect(tl, rect.topLeft, Size(rect.width, b))
        drawRect(tl, rect.topLeft, Size(b, rect.height))
        drawRect(br, Offset(rect.left, rect.bottom - b), Size(rect.width, b))
        drawRect(br, Offset(rect.right - b, rect.top), Size(b, rect.height))
    }
}

private fun DrawScope.drawGlass(
    st: KeyStyle,
    g: GlassSpec,
    pressed: SkinFill,
    p: Float,
    rect: Rect,
    r: Float,
    c: SkinKeyCache,
    backdrop: Backdrop?,
    keyPos: () -> Offset?,
    dark: Boolean
) {
    if (g.opacity >= 0.99f) return drawGlassBody(st, g, pressed, p, rect, r, c, backdrop, keyPos, dark)
    // клавиша целиком полупрозрачная: рисуем в слой и накладываем с нужной непрозрачностью
    val paint = Paint().apply { alpha = g.opacity.coerceIn(0f, 1f) }
    drawIntoCanvas { cv ->
        cv.saveLayer(rect.inflate(4f), paint)
        drawGlassBody(st, g, pressed, p, rect, r, c, backdrop, keyPos, dark)
        cv.restore()
    }
}

private fun DrawScope.drawGlassBody(
    st: KeyStyle,
    g: GlassSpec,
    pressed: SkinFill,
    p: Float,
    rect: Rect,
    r: Float,
    c: SkinKeyCache,
    backdrop: Backdrop?,
    keyPos: () -> Offset?,
    dark: Boolean
) {
    val bmp = backdrop?.bitmap
    val pos = if (bmp != null) keyPos() else null
    if (bmp != null && pos != null) {
        val s = backdrop.scale
        val zoom = 1f + 0.1f * g.refraction + 0.04f * p // линза чуть сильнее под пальцем
        if (Build.VERSION.SDK_INT >= 33 && g.liquid > 0.01f && LiquidShader.draw(this, c, bmp, s, pos, rect, r, zoom, g, p, dark, backdrop.overWindowBlur)) {
            // нарисовано жидким стеклом
        } else if (Build.VERSION.SDK_INT >= 33 && g.refraction > 0.01f) {
            GlassShader.draw(this, c, bmp, s, pos, rect, r, zoom, g)
        } else {
            val sw = (rect.width * s / zoom).coerceIn(1f, bmp.width.toFloat())
            val sh = (rect.height * s / zoom).coerceIn(1f, bmp.height.toFloat())
            val sx = ((pos.x + rect.center.x) * s - sw / 2f).coerceIn(0f, bmp.width - sw)
            val sy = ((pos.y + rect.center.y) * s - sh / 2f).coerceIn(0f, bmp.height - sh)
            clipPath(c.path) {
                drawImage(
                    bmp, IntOffset(sx.roundToInt(), sy.roundToInt()),
                    IntSize(sw.roundToInt().coerceIn(1, bmp.width - sx.roundToInt()), sh.roundToInt().coerceIn(1, bmp.height - sy.roundToInt())),
                    IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    filterQuality = FilterQuality.Low
                )
            }
        }
    }
    // тонировка
    val fill = st.fill
    if (fill != null) drawPath(c.path, c.fill(fill, rect))
    else drawPath(c.path, g.tint ?: Color.White.copy(alpha = if (dark) 0.08f else 0.22f))
    // без своей подложки (окно размывает приложение под клавиатурой) клавиша — матовое стекло:
    // молочная дымка сверху вниз и зерно делают её «размытее» фона вокруг
    val frost = if (g.liquid > 0.01f) g.frost * (1f - g.liquid) else if (bmp == null) max(g.frost, 0.35f) else g.frost
    if (frost > 0f) {
        drawPath(c.path, c.brush(Triple("frost", rect, frost to dark)) {
            val k = if (dark) 0.55f else 1f
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.26f * frost * k),
                1f to Color.White.copy(alpha = 0.12f * frost * k),
                startY = rect.top, endY = rect.bottom
            )
        })
    }
    val grain = if (g.liquid > 0.01f) g.grain * (1f - g.liquid) else if (bmp == null) max(g.grain, 0.5f * frost) else g.grain
    if (grain > 0.01f) drawPath(c.path, PatternTiles.grain, alpha = (grain * if (dark) 0.5f else 0.7f).coerceAtMost(1f))
    if (p > 0.001f) drawPath(c.path, c.fill(pressed, rect), alpha = 0.55f * p)
    // жидкое стекло светится там, где палец: блик «перетекает» к точке нажатия
    val t = c.touch
    if (p > 0.01f && t != null) {
        val rad = max(rect.width, rect.height) * 0.85f
        drawPath(
            c.path,
            Brush.radialGradient(listOf(Color.White.copy(alpha = (if (dark) 0.3f else 0.5f) * p), Color.Transparent), t, rad)
        )
    }

    val hl = g.highlight
    if (hl > 0f) {
        drawPath(c.path, c.brush(Triple("hl", rect, hl)) {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.38f * hl),
                0.42f to Color.Transparent,
                0.85f to Color.Transparent,
                1f to Color.White.copy(alpha = 0.12f * hl),
                startY = rect.top, endY = rect.bottom
            )
        })
        drawPath(c.path, c.brush(Triple("spot", rect, hl)) {
            val spot = Offset(rect.left + rect.width * 0.3f, rect.top + rect.height * 0.12f)
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.2f * hl), Color.Transparent), spot, min(rect.width, rect.height) * 0.9f)
        })
    }
    val rim = g.rim
    if (!dark) {
        // белый ободок на светлом фоне не виден — тонкая тёмная кромка снаружи держит форму клавиши
        drawPath(c.path, Color(0xFF1B2A4A).copy(alpha = 0.13f), style = Stroke(1.dp.toPx()))
    }
    if (rim > 0f) {
        drawPath(
            c.path,
            c.brush(Triple("rim", rect, rim)) {
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.9f * rim), Color.White.copy(alpha = 0.12f * rim),
                        Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.5f * rim)
                    ),
                    rect.topLeft, rect.bottomRight
                )
            },
            style = Stroke(1.25.dp.toPx())
        )
    }
}

/**
 * «Жидкое стекло» как на iPhone: линза гнёт и размазывает фон под клавишей, по краю идёт объёмная кромка
 * (блик сверху-слева, тёмная кайма снизу-справа), цвета чуть насыщеннее. Если окно само размывает приложение
 * (overWindowBlur), клавиша остаётся полупрозрачной, и размытое приложение видно сквозь неё.
 */
@RequiresApi(33)
internal object LiquidShader {
    private const val SRC = """
uniform shader img;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float refr;
uniform float disp;
uniform float smear;
uniform float alpha;
uniform float press;
uniform float amount;
uniform float night;

float sdf(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 tap(float2 p) { return img.eval(p); }

half4 main(float2 fc) {
    float2 b = size * 0.5;
    float2 p = fc - origin - b;
    float d = sdf(p, b, radius);
    float2 e = float2(1.0, 0.0);
    float2 n = float2(sdf(p + e.xy, b, radius) - sdf(p - e.xy, b, radius),
                      sdf(p + e.yx, b, radius) - sdf(p - e.yx, b, radius));
    n = n / max(length(n), 0.0001);
    float band = min(b.x, b.y);
    float t = clamp(1.0 + d / (band * 0.9), 0.0, 1.0);
    float lens = t * t * (3.0 - 2.0 * t);
    float2 off = -n * refr * lens;
    // размазывание: несколько выборок вокруг точки, у края шире
    float sr = smear * (0.6 + 0.8 * lens);
    float2 q = fc + off;
    half4 c = tap(q) * 0.28;
    c += tap(q + float2(sr, 0.0)) * 0.18;
    c += tap(q - float2(sr, 0.0)) * 0.18;
    c += tap(q + float2(0.0, sr)) * 0.18;
    c += tap(q - float2(0.0, sr)) * 0.18;
    if (disp > 0.0) {
        c.r = (tap(q + off * disp * 2.0).r + c.r) * 0.5;
        c.b = (tap(q - off * disp * 2.0).b + c.b) * 0.5;
    }
    float lum = dot(c.rgb, half3(0.299, 0.587, 0.114));
    c.rgb = mix(half3(lum), c.rgb, 1.0 + 0.35 * amount);
    float edge = smoothstep(-band * 0.55, 0.0, d);
    float2 light = normalize(float2(-0.55, -0.83));
    float facing = dot(-n, light);
    float spec = pow(clamp(facing, 0.0, 1.0), 2.5) * edge;
    float back = pow(clamp(-facing, 0.0, 1.0), 2.5) * edge;
    float rimLine = smoothstep(-2.2, -0.4, d) * (1.0 - smoothstep(-0.4, 0.8, d));
    half3 col = c.rgb;
    col += half3(1.0) * (spec * (0.55 + 0.25 * press) * amount + rimLine * 0.30 * amount);
    col += half3(1.0) * back * 0.14 * amount;
    col *= 1.0 - 0.10 * edge * (1.0 - spec) * (0.5 + 0.5 * night);
    float a = clamp(alpha + edge * 0.30 + spec * 0.25 + rimLine * 0.25, 0.0, 1.0);
    return half4(col * a, a);
}
"""

    /** false — шейдер не удалось собрать, рисуем обычным стеклом. */
    fun draw(
        ds: DrawScope, c: SkinKeyCache, bmp: ImageBitmap, s: Float, pos: Offset, rect: Rect, r: Float,
        zoom: Float, g: GlassSpec, press: Float, dark: Boolean, overWindowBlur: Boolean
    ): Boolean {
        val rs = (c.liquid as? RuntimeShader) ?: runCatching { RuntimeShader(SRC) }.getOrNull()?.also { c.liquid = it } ?: return false
        if (c.bitmapFor !== bmp) {
            c.bitmapShader = BitmapShader(bmp.asAndroidBitmap(), AndroidShader.TileMode.CLAMP, AndroidShader.TileMode.CLAMP)
            c.bitmapFor = bmp
        }
        val bs = c.bitmapShader ?: return false
        c.matrix.reset()
        c.matrix.setScale(1f / s, 1f / s)
        c.matrix.postTranslate(-pos.x, -pos.y)
        c.matrix.postScale(zoom, zoom, rect.center.x, rect.center.y)
        bs.setLocalMatrix(c.matrix)
        val m = min(rect.width, rect.height)
        rs.setInputShader("img", bs)
        rs.setFloatUniform("origin", rect.left, rect.top)
        rs.setFloatUniform("size", rect.width, rect.height)
        rs.setFloatUniform("radius", r)
        rs.setFloatUniform("refr", g.refraction * m * 0.45f * (1f + 0.25f * press))
        rs.setFloatUniform("disp", g.dispersion * 0.5f)
        rs.setFloatUniform("smear", (1.5f + g.blur * 0.25f) * ds.density)
        rs.setFloatUniform("alpha", if (overWindowBlur) 0.34f else 0.92f)
        rs.setFloatUniform("press", press)
        rs.setFloatUniform("amount", g.liquid)
        rs.setFloatUniform("night", if (dark) 1f else 0f)
        ds.drawPath(c.path, ShaderBrush(rs))
        return true
    }
}

/** Настоящее преломление: края стекла «загибают» фон внутрь, с лёгкой радужной каймой. */
@RequiresApi(33)
internal object GlassShader {
    private const val SRC = """
uniform shader img;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float refr;
uniform float disp;

float sdf(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fc) {
    float2 b = size * 0.5;
    float2 p = fc - origin - b;
    float d = sdf(p, b, radius);
    float2 e = float2(1.0, 0.0);
    float2 n = float2(sdf(p + e.xy, b, radius) - sdf(p - e.xy, b, radius),
                      sdf(p + e.yx, b, radius) - sdf(p - e.yx, b, radius));
    n = n / max(length(n), 0.0001);
    float band = min(b.x, b.y);
    float t = clamp(1.0 + d / band, 0.0, 1.0);
    float2 off = -n * refr * t * t * t;
    half4 c = img.eval(fc + off);
    if (disp > 0.0) {
        c.r = img.eval(fc + off * (1.0 + disp)).r;
        c.b = img.eval(fc + off * (1.0 - disp)).b;
    }
    return half4(c.rgb, 1.0);
}
"""

    fun draw(ds: DrawScope, c: SkinKeyCache, bmp: ImageBitmap, s: Float, pos: Offset, rect: Rect, r: Float, zoom: Float, g: GlassSpec) {
        val rs = c.runtime as? RuntimeShader ?: RuntimeShader(SRC).also { c.runtime = it }
        if (c.bitmapFor !== bmp) {
            c.bitmapShader = BitmapShader(bmp.asAndroidBitmap(), AndroidShader.TileMode.CLAMP, AndroidShader.TileMode.CLAMP)
            c.bitmapFor = bmp
        }
        val bs = c.bitmapShader ?: return
        c.matrix.reset()
        c.matrix.setScale(1f / s, 1f / s)         // пиксели подложки → координаты клавиатуры
        c.matrix.postTranslate(-pos.x, -pos.y)    // → координаты клавиши
        c.matrix.postScale(zoom, zoom, rect.center.x, rect.center.y)
        bs.setLocalMatrix(c.matrix)
        rs.setInputShader("img", bs)
        rs.setFloatUniform("origin", rect.left, rect.top)
        rs.setFloatUniform("size", rect.width, rect.height)
        rs.setFloatUniform("radius", r)
        rs.setFloatUniform("refr", g.refraction * min(rect.width, rect.height) * 0.38f)
        rs.setFloatUniform("disp", g.dispersion * 0.4f)
        ds.drawPath(c.path, ShaderBrush(rs))
    }
}

/** Фон стеклянных панелей и чипов, где подложку не видно: светлая дымка и ободок. */
internal fun DrawScope.drawGlassChip(rect: Rect, radius: Float, tint: Color?) {
    val rr = RoundRect(rect, CornerRadius(radius, radius))
    val path = Path().apply { addRoundRect(rr) }
    drawPath(path, tint ?: Color.White.copy(alpha = 0.14f))
    drawPath(path, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.5f to Color.Transparent, startY = rect.top, endY = rect.bottom))
    drawPath(
        path,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.35f)), rect.topLeft, rect.bottomRight),
        style = Stroke(1.dp.toPx())
    )
}
