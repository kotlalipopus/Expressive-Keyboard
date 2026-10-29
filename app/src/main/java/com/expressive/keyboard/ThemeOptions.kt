package com.expressive.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

// ============================================================================
// НАСТРОЙКИ ТЕМЫ
// У каждой темы свои настройки: тема сама объявляет их в "skin" → "options" (выбор текстуры, точки,
// контуры…), а общие (скругление, тени, прозрачность, стекло, анимация, шрифт, акцент) есть у всех.
// Выбор — заплатка JSON поверх оформления, ползунок — «ручка», которая меняет уже разобранное оформление.
// Значения хранятся в настройке "skin_options": { "id темы": { "id настройки": значение } }.
// ============================================================================

enum class OptionType { SLIDER, TOGGLE, CHOICE }

data class SkinOptionChoice(val id: String, val title: String, val patch: JSONObject?)

data class SkinOption(
    val id: String,
    val title: String,
    val type: OptionType,
    val default: Any,
    val min: Float = 0f,
    val max: Float = 1f,
    /** Для ползунков: что меняет (см. ThemeOptions.applyKnob). */
    val knob: String? = null,
    val choices: List<SkinOptionChoice> = emptyList(),
    /** Для переключателей: заплатки во включённом и выключенном положении. */
    val on: JSONObject? = null,
    val off: JSONObject? = null,
    val format: String = "percent"
)

object ThemeOptions {
    const val PREF = "skin_options"

    /** Настройки, объявленные самой темой. */
    fun declared(skinJson: String?): List<SkinOption> {
        val o = skinJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val arr = o.optJSONArray("options") ?: return emptyList()
        return (0 until minOf(arr.length(), 16)).mapNotNull { i -> runCatching { parseOption(arr.getJSONObject(i)) }.getOrNull() }
    }

    private fun parseOption(o: JSONObject): SkinOption {
        val id = o.getString("id")
        val title = o.optString("title", id)
        return when (o.optString("type").lowercase()) {
            "choice" -> {
                val arr = o.getJSONArray("choices")
                val choices = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    SkinOptionChoice(c.getString("id"), c.optString("title", c.getString("id")), c.optJSONObject("patch"))
                }
                SkinOption(id, title, OptionType.CHOICE, o.optString("default", choices.firstOrNull()?.id ?: ""), choices = choices)
            }
            "toggle" -> SkinOption(id, title, OptionType.TOGGLE, o.optBoolean("default", false), on = o.optJSONObject("on"), off = o.optJSONObject("off"))
            else -> SkinOption(
                id, title, OptionType.SLIDER, o.optDouble("default", 1.0).toFloat(),
                o.optDouble("min", 0.0).toFloat(), o.optDouble("max", 1.0).toFloat(), o.optString("knob").ifEmpty { null },
                format = o.optString("format", "percent")
            )
        }
    }

    /** Общие настройки, которые есть у любой темы (что-то — только если тема это использует). */
    fun common(skin: KeyboardSkin): List<SkinOption> = buildList {
        val all = skin.styles.values + listOfNotNull(skin.dark?.styles?.values).flatten()
        add(SkinOption("radiusScale", "Скругление клавиш в теме", OptionType.SLIDER, 1f, 0f, 2f, "radiusScale"))
        if (all.any { (it.shadow ?: 0f) > 0f || it.material == SkinMaterial.NEUMORPH }) {
            add(SkinOption("shadowScale", "Тени", OptionType.SLIDER, 1f, 0f, 2f, "shadowScale"))
        }
        if (all.any { it.glow != null || it.textGlow != null }) add(SkinOption("glowScale", "Свечение", OptionType.SLIDER, 1f, 0f, 2f, "glowScale"))
        if (all.any { it.borderWidth != null || it.material == SkinMaterial.OUTLINE }) {
            add(SkinOption("borderScale", "Толщина контура", OptionType.SLIDER, 1f, 0f, 3f, "borderScale"))
        }
        if (skin.usesGlass || skin.dark?.usesGlass == true) {
            val g = all.firstNotNullOfOrNull { it.glass } ?: GlassSpec()
            add(SkinOption("glass.opacity", "Непрозрачность клавиш", OptionType.SLIDER, 1f, 0.1f, 1f, "glass.opacity"))
            add(SkinOption("glass.frost", "Размытие клавиш", OptionType.SLIDER, max(g.frost, 0.35f), 0f, 1f, "glass.frost"))
            add(SkinOption("glass.grain", "Зернистость стекла", OptionType.SLIDER, g.grain, 0f, 1f, "glass.grain"))
            add(SkinOption("glass.refraction", "Преломление", OptionType.SLIDER, g.refraction, 0f, 1f, "glass.refraction"))
            add(SkinOption("glass.highlight", "Блик", OptionType.SLIDER, g.highlight, 0f, 1f, "glass.highlight"))
            add(SkinOption("glass.rim", "Светлый ободок", OptionType.SLIDER, g.rim, 0f, 1f, "glass.rim"))
            add(SkinOption("glass.dispersion", "Радужная кайма", OptionType.SLIDER, g.dispersion, 0f, 1f, "glass.dispersion"))
        }
        add(
            SkinOption(
                "font", "Шрифт в этой теме", OptionType.CHOICE, "theme",
                choices = listOf(
                    SkinOptionChoice("theme", "Как в теме", null), SkinOptionChoice("app", "Из настроек", null),
                    SkinOptionChoice("sans", "Системный", null), SkinOptionChoice("pixel", "Пиксели", null),
                    SkinOptionChoice("mono", "Моно", null), SkinOptionChoice("serif", "Serif", null)
                )
            )
        )
        add(SkinOption("hideHints", "Убрать подсказки на клавишах", OptionType.TOGGLE, false))
        add(SkinOption("followAccent", "Красить в мой акцентный цвет", OptionType.TOGGLE, true))
    }

    // ------------------------------------------------------------------ хранение

    @Volatile private var parsedRaw = ""
    @Volatile private var parsed: Map<String, Map<String, Any>> = emptyMap()

    fun values(settings: KeyboardSettings, skinId: String): Map<String, Any> {
        val raw = settings.skinOptions
        if (raw != parsedRaw) {
            parsed = runCatching {
                val o = JSONObject(raw.ifBlank { "{}" })
                o.keys().asSequence().associateWith { k ->
                    val inner = o.getJSONObject(k)
                    inner.keys().asSequence().associateWith { inner.get(it) }
                }
            }.getOrDefault(emptyMap())
            parsedRaw = raw
        }
        return parsed[skinId].orEmpty()
    }

    fun set(context: android.content.Context, settings: KeyboardSettings, skinId: String, optionId: String, value: Any?) {
        val o = runCatching { JSONObject(settings.skinOptions.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val inner = o.optJSONObject(skinId) ?: JSONObject()
        if (value == null) inner.remove(optionId) else inner.put(optionId, value)
        if (inner.length() == 0) o.remove(skinId) else o.put(skinId, inner)
        KeyboardPrefs.edit(context) { putString(PREF, o.toString()) }
    }

    fun reset(context: android.content.Context, settings: KeyboardSettings, skinId: String) {
        val o = runCatching { JSONObject(settings.skinOptions.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        o.remove(skinId)
        KeyboardPrefs.edit(context) { putString(PREF, o.toString()) }
    }

    // ------------------------------------------------------------------ применение

    /** Глубокое слияние: объекты сливаются, остальное заменяется. */
    fun merge(base: JSONObject, patch: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        patch.keys().forEach { k ->
            val pv = patch.get(k)
            val bv = out.opt(k)
            if (pv is JSONObject && bv is JSONObject) out.put(k, merge(bv, pv)) else out.put(k, pv)
        }
        return out
    }

    private data class CacheKey(
        val plugin: KeyboardPlugin, val values: Map<String, Any>, val dark: Boolean, val accent: Int, val accentText: Int, val opacity: Float
    )

    @Volatile private var cacheKey: CacheKey? = null
    @Volatile private var cacheValue: KeyboardSkin? = null

    /** Оформление с настройками темы, светлое или тёмное, перекрашенное в акцент пользователя. */
    fun resolve(plugin: KeyboardPlugin, settings: KeyboardSettings, dark: Boolean): KeyboardSkin? {
        val base = plugin.skin ?: return null
        val values = values(settings, plugin.id)
        val key = CacheKey(plugin, values, dark, settings.userAccent, settings.colorAccentText, settings.keyOpacity)
        if (key == cacheKey) return cacheValue
        val result = runCatching { build(plugin, base, values, settings, dark) }.getOrElse { base.forMode(dark) }
        cacheKey = key
        cacheValue = result
        return result
    }

    internal fun build(plugin: KeyboardPlugin, base: KeyboardSkin, values: Map<String, Any>, settings: KeyboardSettings, dark: Boolean): KeyboardSkin {
        val declared = declared(plugin.skinJson)
        // 1) заплатки выбора и переключателей → новый JSON → разбор
        var json: JSONObject? = null
        for (opt in declared) {
            val v = values[opt.id] ?: opt.default
            val patch = when (opt.type) {
                OptionType.CHOICE -> opt.choices.firstOrNull { it.id == v.toString() }?.patch
                OptionType.TOGGLE -> if (v == true || v.toString() == "true") opt.on else opt.off
                OptionType.SLIDER -> null
            } ?: continue
            json = merge(json ?: JSONObject(plugin.skinJson ?: "{}"), patch)
        }
        var skin = (json?.let { SkinParser.parse(it) } ?: base).forMode(dark)
        // 2) ручки: свои ползунки темы и общие
        for (opt in declared) if (opt.type == OptionType.SLIDER && opt.knob != null) {
            val v = (values[opt.id] as? Number)?.toFloat() ?: (opt.default as Float)
            skin = applyKnob(skin, opt.knob, v)
        }
        for (opt in common(base)) {
            val v = values[opt.id] ?: continue
            skin = when (opt.type) {
                OptionType.SLIDER -> applyKnob(skin, opt.knob ?: continue, (v as? Number)?.toFloat() ?: continue)
                OptionType.CHOICE -> applyChoice(skin, opt.id, v.toString())
                OptionType.TOGGLE -> if (opt.id == "hideHints" && v == true) skin.mapStyles { it.copy(hideHint = true) } else skin
            }
        }
        // 3) «Непрозрачность клавиш» из «Цвета и фон» действует и на клавиши темы (раньше у темы был свой ползунок)
        if (settings.keyOpacity < 0.999f) {
            val a = settings.keyOpacity
            skin = skin.copy(styles = skin.styles.mapValues { (_, st) -> st.copy(fill = st.fill?.mapColors { c -> c.copy(alpha = c.alpha * a) }) })
        }
        // «Непрозрачность клавиш» действует и на стеклянные клавиши (у них нет заливки, только тонировка)
        if (settings.keyOpacity < 0.999f) {
            val a = settings.keyOpacity
            skin = skin.mapStyles { st -> st.glass?.let { st.copy(glass = it.copy(opacity = it.opacity * a)) } ?: st }
        }
        // 4) акцент пользователя
        val follow = values["followAccent"]?.let { it == true || it.toString() == "true" } ?: true
        if (follow && settings.userAccent != 0) skin = recolor(skin, Color(settings.userAccent), settings.colorAccentText)
        return skin
    }

    /**
     * «Жидкое стекло» поверх любой темы (и без темы): клавиши становятся стеклянными линзами. У тем с обычной
     * заливкой заливка становится полупрозрачной, чтобы стекло было видно.
     */
    fun liquefy(skin: KeyboardSkin?, settings: KeyboardSettings): KeyboardSkin {
        val a = settings.keyOpacity
        fun liquid(st: KeyStyle?): KeyStyle {
            val s = st ?: KeyStyle()
            val g = s.glass ?: GlassSpec(refraction = 0.5f, dispersion = 0.08f, highlight = 0.6f, rim = 0.7f)
            return if (s.material == SkinMaterial.GLASS) s.copy(glass = g.copy(liquid = 1f, opacity = g.opacity * a))
            else s.copy(
                material = SkinMaterial.GLASS,
                glass = g.copy(liquid = 1f, opacity = a),
                fill = s.fill?.mapColors { it.copy(alpha = it.alpha * 0.4f) },
                pressedFill = s.pressedFill
            )
        }
        if (skin == null) {
            return KeyboardSkin(styles = mapOf("*" to liquid(null)), windowBlur = 36f)
        }
        val styles = LinkedHashMap(skin.styles)
        styles["*"] = liquid(styles["*"])
        for (k in styles.keys.toList()) if (k != "*") styles[k] = liquid(styles[k])
        return skin.copy(styles = styles, windowBlur = skin.windowBlur ?: 36f)
    }

    private fun applyChoice(skin: KeyboardSkin, id: String, v: String): KeyboardSkin = when (id) {
        "press" -> if (v == "theme") skin else {
            val effect = PressEffect.entries.firstOrNull { it.name.equals(v, true) } ?: return skin
            skin.mapStyles { it.copy(press = effect) }
        }
        "font" -> when (v) {
            "theme" -> skin
            "app" -> skin.copy(font = null)
            else -> skin.copy(font = v)
        }
        else -> skin
    }

    fun applyKnob(skin: KeyboardSkin, knob: String, v: Float): KeyboardSkin = when (knob) {
        "radiusScale" -> skin.mapStyles { s ->
            // у клавиш без своего скругления — скругление из настроек, его тема не трогает
            s.copy(radius = s.radius?.let { it * v }, pressedRadius = s.pressedRadius?.let { it * v })
        }.let { if (it.styles["*"]?.radius == null && v != 1f) it.copy(styles = it.styles + ("*" to ((it.styles["*"] ?: KeyStyle()).copy(radius = 12f * v)))) else it }
        "shadowScale" -> skin.mapStyles { s ->
            val sh = s.shadow ?: if (s.material == SkinMaterial.NEUMORPH) 7f else null
            s.copy(shadow = sh?.let { (it * v).coerceIn(0f, 24f) }, shadowY = s.shadowY?.let { it * v })
        }
        "glowScale" -> skin.mapStyles { s ->
            s.copy(glow = s.glow?.let { it.copy(alpha = (it.alpha * v).coerceIn(0f, 1f)) }, textGlow = s.textGlow?.let { it.copy(alpha = (it.alpha * v).coerceIn(0f, 1f)) })
        }
        "borderScale" -> skin.mapStyles { s ->
            val bw = s.borderWidth ?: if (s.material == SkinMaterial.OUTLINE) 1.5f else null
            s.copy(borderWidth = bw?.let { (it * v).coerceIn(0f, 8f) })
        }
        "keyAlpha" -> skin.mapStyles { s -> s.copy(fill = s.fill?.mapColors { c -> c.copy(alpha = c.alpha * v) }) }.copy(keyAlpha = v)
        "windowBlur" -> skin.copy(windowBlur = v)
        else -> if (knob.startsWith("glass.")) {
            val field = knob.removePrefix("glass.")
            skin.mapStyles { s ->
                val g = s.glass ?: return@mapStyles s
                s.copy(
                    glass = when (field) {
                        "frost" -> g.copy(frost = v)
                        "grain" -> g.copy(grain = v)
                        "refraction" -> g.copy(refraction = v)
                        "highlight" -> g.copy(highlight = v)
                        "rim" -> g.copy(rim = v)
                        "dispersion" -> g.copy(dispersion = v)
                        "opacity" -> g.copy(opacity = v)
                        "blur" -> g.copy(blur = v)
                        else -> g
                    }
                )
            }
        } else skin
    }

    // ------------------------------------------------------------------ акцент

    /**
     * Перекраска в акцент: цвета темы близкие по оттенку к её акценту (Enter, подсветка, свечение) получают
     * оттенок и насыщенность акцента пользователя; яркость и прозрачность остаются — светлые и тёмные места
     * темы сохраняются. Серые и почти белые/чёрные цвета не меняются.
     */
    fun recolor(skin: KeyboardSkin, accent: Color, accentText: Int): KeyboardSkin {
        val onAcc = Color(onAccent(accent, accentText))
        val t = hsl(accent)
        val from = skin.colors["accent"]?.let { Color(it) }
        val f = from?.let(::hsl)
        // цвета, близкие по оттенку к акценту темы (подсветка, свечение, контуры, след свайпа), получают оттенок акцента
        val near: (Color) -> Color = near@{ c ->
            if (f == null || f[1] < 0.12f) return@near c
            val h = hsl(c)
            val d = abs(((h[0] - f[0] + 540f) % 360f) - 180f)
            if (h[1] < 0.18f || h[2] < 0.06f || h[2] > 0.97f || d > 38f) c
            else {
                h[0] = (t[0] + (h[0] - f[0]) + 360f) % 360f
                h[1] = (h[1] * (t[1] / max(f[1], 0.05f))).coerceIn(0f, 1f)
                // яркость тянется к акценту настолько же, насколько этот цвет был близок к акценту темы
                h[2] = (h[2] + (t[2] - f[2]) * (1f - d / 38f)).coerceIn(0.02f, 0.98f)
                Color(ColorUtils.HSLToColor(h)).copy(alpha = c.alpha)
            }
        }
        var out = skin.mapColors(near, mapColorsMap = false)
        out = out.copy(styles = recolorEnter(out.styles, accent, onAcc), dark = out.dark?.let { d -> d.copy(styles = recolorEnter(d.styles, accent, onAcc)) })
        val colors = skin.colors.mapValues { (k, v) -> if (k == "accent" || k == "accentText") v else near(Color(v)).toArgb() }
        // акцент должен быть заметен не только на Enter: значки кнопок меню и микрофона, главная подсказка
        // и след свайпа — тоже акцентом (читаемым на своём фоне). Раньше в большинстве тем они оставались
        // цветами темы, и смена акцента почти не была видна
        val bg = skin.colors["background"]?.let { Color(it) }
        fun on(fill: SkinFill?): Color? {
            val f = fill?.primary ?: return bg
            if (f.alpha > 0.85f || bg == null) return f.copy(alpha = 1f)
            return Color(ColorUtils.compositeColors(f.toArgb(), bg.copy(alpha = 1f).toArgb()))
        }
        val buttons = out.buttons?.let { b -> b.copy(text = readable(accent, on(b.fill)), textGlow = b.textGlow?.let { accent.copy(alpha = it.alpha) }) }
        val chips = out.chips?.let { c -> c.copy(pressedText = readable(accent, if (c.material == SkinMaterial.NONE) bg else on(c.pressedFill ?: c.fill))) }
        return out.copy(
            colors = colors + ("accent" to accent.toArgb()) + ("accentText" to onAcc.toArgb()),
            buttons = buttons, chips = chips, trail = accent.copy(alpha = 0.85f)
        )
    }

    /** Акцент, подправленный по яркости, чтобы читался на фоне bg (контраст не меньше 2.6). */
    private fun readable(accent: Color, bg: Color?): Color {
        if (bg == null) return accent
        val b = bg.toArgb()
        if (ColorUtils.calculateContrast(accent.copy(alpha = 1f).toArgb(), b) >= 2.6) return accent
        val toward = if (bg.luminance() < 0.5f) Color.White else Color.Black
        var t = 0.15f
        while (t < 0.9f) {
            val c = androidx.compose.ui.graphics.lerp(accent, toward, t)
            if (ColorUtils.calculateContrast(c.copy(alpha = 1f).toArgb(), b) >= 2.6) return c
            t += 0.15f
        }
        return androidx.compose.ui.graphics.lerp(accent, toward, 0.9f)
    }

    private fun hsl(c: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }

    private fun saturated(c: Color): Boolean {
        val h = hsl(c)
        return h[1] >= 0.18f && h[2] in 0.06f..0.97f
    }

    /**
     * Enter — главная акцентная клавиша, он перекрашивается всегда: цветной (в том числе градиент и узор)
     * получает оттенок акцента с сохранением переходов, серый (Samsung, Nothing без красного) — заливку акцентом.
     * Раньше перекрашивались только цвета, близкие к акценту темы, и во многих темах Enter оставался прежним.
     */
    private fun recolorEnter(styles: Map<String, KeyStyle>, accent: Color, onAcc: Color): Map<String, KeyStyle> {
        // своего стиля у Enter нет, а у всех клавиш есть заливка — без отдельного стиля Enter был бы как буквы
        val st = styles["enter"] ?: styles["*"]?.takeIf { it.fill != null }?.let { KeyStyle() } ?: return styles
        val fill = st.fill
        val base = fill?.primary
        val recolored = if (base != null && saturated(base)) {
            val f = hsl(base)
            val t = hsl(accent)
            val shift: (Color) -> Color = { c ->
                if (!saturated(c)) c else {
                    val h = hsl(c)
                    h[0] = (t[0] + (h[0] - f[0]) + 720f) % 360f
                    h[1] = (h[1] * (t[1] / max(f[1], 0.05f))).coerceIn(0f, 1f)
                    h[2] = (h[2] + (t[2] - f[2])).coerceIn(0.03f, 0.97f)
                    Color(ColorUtils.HSLToColor(h)).copy(alpha = c.alpha)
                }
            }
            st.copy(
                fill = fill.mapColors(shift), pressedFill = st.pressedFill?.mapColors(shift), glow = st.glow?.let(shift),
                border = st.border?.mapColors(shift), shadowColor = st.shadowColor?.let(shift), textGlow = st.textGlow?.let(shift),
                text = if (st.text != null && saturated(st.text)) shift(st.text) else onAcc
            )
        } else {
            val alpha = base?.alpha ?: 1f
            st.copy(
                fill = SkinFill.Solid(accent.copy(alpha = max(alpha, 0.85f))),
                pressedFill = SkinFill.Solid(androidx.compose.ui.graphics.lerp(accent, onAcc, 0.2f)),
                text = onAcc, pressedText = onAcc
            )
        }
        return styles + ("enter" to recolored)
    }

    private fun onAccent(accent: Color, user: Int): Int =
        if (user != 0) user else if (accent.luminance() > 0.5f) 0xFF111318.toInt() else 0xFFFFFFFF.toInt()

    // ------------------------------------------------------------------ для экрана настроек

    fun valueOf(opt: SkinOption, values: Map<String, Any>): Any = values[opt.id] ?: opt.default

    fun format(opt: SkinOption, v: Float): String = when (opt.format) {
        "dp" -> "${v.toInt()} dp"
        "x" -> String.format("×%.1f", v)
        else -> "${(v * 100).toInt()}%"
    }

    fun exportValues(values: Map<String, Any>): JSONObject = JSONObject().apply { values.forEach { (k, v) -> put(k, v) } }

    @Suppress("unused")
    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
}
