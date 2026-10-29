package com.expressive.keyboard

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

// ============================================================================
// ДИЗАЙН: ГОТОВЫЕ СТИЛИ ВСЕЙ КЛАВИАТУРЫ ОДНИМ НАЖАТИЕМ
// Стиль — набор настроек (форма акцентов, шрифт, скругление, толщина букв) плюс встроенное
// оформление (skin) из assets/plugins. После выбора всё можно донастроить вручную.
// ============================================================================

enum class DesignStyle(
    val id: String,
    val title: String,
    val subtitle: String,
    val skin: String,
    val shape: ExpressiveShapePreset,
    val font: String,
    val corner: Float,
    val weight: Int
) {
    EXPRESSIVE(
        "EXPRESSIVE", "Material 3 Expressive", "Волнистые «печеньки» и морфинг формы",
        "", ExpressiveShapePreset.COOKIE_12, "ROUNDED", 16f, 700
    ),
    MATERIAL(
        "MATERIAL", "Material 3 · Google", "Как Gboard: цвета Material You, строгие клавиши, подсказки текстом",
        "builtin.material", ExpressiveShapePreset.CIRCLE, "SYSTEM", 8f, 500
    ),
    GLASS(
        "GLASS", "Liquid Glass", "Прозрачное стекло с преломлением и бликами, светлое и тёмное",
        "builtin.liquid-glass", ExpressiveShapePreset.CIRCLE, "SYSTEM", 14f, 500
    ),
    CLASSIC(
        "CLASSIC", "Классика", "Белые клавиши на сером, синий Enter — привычно и спокойно",
        "builtin.classic", ExpressiveShapePreset.CIRCLE, "SYSTEM", 6f, 400
    ),
    NEON("NEON", "Неон", "Почти чёрный фон, тонкие светящиеся контуры", "builtin.neon", ExpressiveShapePreset.CIRCLE, "SYSTEM", 10f, 500),
    NEUMORPH("NEUMORPH", "Неоморфизм", "Мягкие выпуклые клавиши из материала фона", "builtin.neumorph", ExpressiveShapePreset.CIRCLE, "ROUNDED", 14f, 600),
    MINECRAFT("MINECRAFT", "Minecraft", "Каменные кнопки из пикселей, земля, пиксельный шрифт и зелёный Enter", "builtin.minecraft", ExpressiveShapePreset.CIRCLE, "SYSTEM", 0f, 500),
    SAMSUNG("SAMSUNG", "Samsung One UI", "Плоские светлые клавиши и синий акцент, как у клавиатуры Samsung", "builtin.samsung", ExpressiveShapePreset.CIRCLE, "SYSTEM", 9f, 400),
    NOTHING("NOTHING", "Nothing", "Монохром, буквы и контуры из точек, красный Enter", "builtin.nothing", ExpressiveShapePreset.CIRCLE, "ROUNDED", 14f, 700);

    fun apply(context: Context) {
        if (skin.isNotEmpty() && !PluginRepository.isEnabled(skin)) PluginRepository.setEnabled(context, skin, true)
        KeyboardPrefs.edit(context) {
            putString("design_style", id)
            putString("skin_plugin", skin)
            putString("accent_shape_preset", shape.id)
            putInt("wave_count", shape.defaultWaves)
            putFloat("wave_amplitude_percent", shape.defaultAmplitude)
            putFloat("corner_radius_dp", corner)
            // шрифт и толщину букв тема не трогает — их выбирает сам пользователь (в том числе свой шрифт)
        }
    }

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: EXPRESSIVE
    }
}

/** Карточки стилей с мини-клавиатурой в каждом — вверху главного экрана настроек. */
@Composable
internal fun DesignPicker(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dark = keyboardIsDark(settings)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DesignStyle.entries.forEach { d ->
            // выбрана та, чьё оформление стоит сейчас (своя тема — ни одна из этих)
            val sel = settings.skinPlugin == d.skin
            val plugin = PluginRepository.plugins.firstOrNull { it.id == d.skin }
            val corner by animateDpAsState(if (sel) 28.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "designCorner")
            val bg by animateColorAsState(if (sel) cs.primaryContainer else cs.surfaceContainerHigh, label = "designBg")
            Column(
                Modifier.width(156.dp).clip(RoundedCornerShape(corner)).background(bg)
                    .then(if (sel) Modifier.border(2.dp, cs.primary, RoundedCornerShape(corner)) else Modifier)
                    .clickable { d.apply(context) }.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (plugin?.skin != null) {
                    val skin = remember(plugin, settings.skinOptions, settings.userAccent, settings.colorAccentText, settings.keyOpacity, settings.liquidGlass, dark) {
                        runCatching { ThemeOptions.build(plugin, plugin.skin, ThemeOptions.values(settings, plugin.id), settings, dark) }.getOrNull()
                    }
                    SkinMiniPreview(skin, settings, Modifier.fillMaxWidth().height(78.dp))
                } else {
                    Canvas(Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(14.dp))) {
                        drawMiniKeyboard(d, dark, cs.primary, cs.surfaceContainer, cs.surfaceBright, cs.secondaryContainer)
                    }
                }
                Text(
                    tr(d.title), style = MaterialTheme.typography.titleSmall,
                    color = if (sel) cs.onPrimaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    tr(d.subtitle), style = MaterialTheme.typography.bodySmall,
                    color = if (sel) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant, maxLines = 3, minLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Схематичная клавиатура стиля: фон, три ряда клавиш, пробел и Enter — в тех же цветах, что и настоящая. */
private fun DrawScope.drawMiniKeyboard(d: DesignStyle, dark: Boolean, primary: Color, surface: Color, key: Color, func: Color) {
    val w = size.width
    val h = size.height
    val bg: Brush
    val keyColor: Color
    val funcColor: Color
    val enter: Brush
    var outline: Color? = null
    var funcOutline: Color? = null
    var shadow: Color? = null
    var light: Color? = null
    val orbs = ArrayList<Pair<Color, Offset>>()
    when (d) {
        DesignStyle.GLASS -> if (dark) {
            bg = Brush.verticalGradient(listOf(Color(0xFF0F121B), Color(0xFF171A26)))
            orbs += Color(0x592E5BFF) to Offset(0.15f, 0.2f)
            orbs += Color(0x408E4DFF) to Offset(0.85f, 0.35f)
            keyColor = Color.White.copy(alpha = 0.12f)
            funcColor = Color.White.copy(alpha = 0.08f)
            enter = Brush.verticalGradient(listOf(Color(0xE63D7BFF), Color(0xE62F5BFF)))
            outline = Color.White.copy(alpha = 0.22f)
        } else {
            bg = Brush.verticalGradient(listOf(Color(0xFFEEF1F7), Color(0xFFE1E6F0)))
            orbs += Color(0xFFB4C6FF) to Offset(0.12f, 0.2f)
            orbs += Color(0xFFFFC9DE) to Offset(0.88f, 0.35f)
            keyColor = Color.White.copy(alpha = 0.6f)
            funcColor = Color.White.copy(alpha = 0.4f)
            enter = Brush.verticalGradient(listOf(Color(0xE63D7BFF), Color(0xE62F5BFF)))
            outline = Color.White.copy(alpha = 0.9f)
        }
        DesignStyle.CLASSIC -> if (dark) {
            bg = SolidColor(Color(0xFF1C1C1E))
            keyColor = Color(0xFF4A4A4E)
            funcColor = Color(0xFF2E2E31)
            enter = SolidColor(Color(0xFF0A84FF))
            shadow = Color.Black.copy(alpha = 0.5f)
        } else {
            bg = SolidColor(Color(0xFFD1D4DA))
            keyColor = Color.White
            funcColor = Color(0xFFACB1BB)
            enter = SolidColor(Color(0xFF0A7AFF))
            shadow = Color.Black.copy(alpha = 0.25f)
        }
        DesignStyle.NEON -> {
            bg = Brush.verticalGradient(listOf(Color(0xFF0C0A17), Color(0xFF120E22)))
            orbs += Color(0x26A78BFA) to Offset(0.85f, 1.1f)
            keyColor = Color.White.copy(alpha = 0.05f)
            funcColor = keyColor
            enter = Brush.horizontalGradient(listOf(Color(0xFF22D3EE), Color(0xFFA78BFA)))
            outline = Color(0xB322D3EE)
            funcOutline = Color(0xB3A78BFA)
        }
        DesignStyle.NEUMORPH -> if (dark) {
            bg = SolidColor(Color(0xFF262A32))
            keyColor = Color(0xFF292D35)
            funcColor = keyColor
            enter = Brush.linearGradient(listOf(Color(0xFF8B9BFF), Color(0xFF6576F0)))
            shadow = Color(0xD9121418)
            light = Color.White.copy(alpha = 0.07f)
        } else {
            bg = SolidColor(Color(0xFFE6EAF1))
            keyColor = Color(0xFFE9EDF3)
            funcColor = keyColor
            enter = Brush.linearGradient(listOf(Color(0xFF8396FF), Color(0xFF5F73EA)))
            shadow = Color(0x8FA3AFC4)
            light = Color.White.copy(alpha = 0.9f)
        }
        else -> {
            bg = SolidColor(surface)
            keyColor = key
            funcColor = func
            enter = SolidColor(primary)
        }
    }
    drawRect(bg)
    for ((c, o) in orbs) {
        val center = Offset(o.x * w, o.y * h)
        drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, w * 0.45f), w * 0.45f, center)
    }
    val pad = w * 0.04f
    val gap = w * 0.02f
    val rows = 4
    val rowH = (h - pad * 2 - gap * (rows - 1)) / rows
    val r = when (d) {
        DesignStyle.CLASSIC -> rowH * 0.2f
        DesignStyle.MATERIAL, DesignStyle.NEON -> rowH * 0.28f
        DesignStyle.GLASS, DesignStyle.NEUMORPH -> rowH * 0.38f
        else -> rowH * 0.45f
    }
    val sh = rowH * 0.08f
    fun key(tl: Offset, sz: Size, fill: Brush, cr: Float, line: Color?) {
        val c = CornerRadius(cr, cr)
        light?.let { drawRoundRect(it, tl - Offset(sh, sh), sz, c) }
        shadow?.let { drawRoundRect(it, tl + Offset(if (light != null) sh else 0f, sh), sz, c) }
        drawRoundRect(fill, tl, sz, c)
        line?.let { drawRoundRect(it, tl, sz, c, style = Stroke(1.2f)) }
    }
    for (row in 0 until 3) {
        val n = if (row == 2) 7 else 10 - row
        val kw = (w - pad * 2 - gap * (n - 1)) / n
        for (i in 0 until n) {
            key(Offset(pad + i * (kw + gap), pad + row * (rowH + gap)), Size(kw, rowH), SolidColor(keyColor), r, outline)
        }
    }
    val y = pad + 3 * (rowH + gap)
    val unit = (w - pad * 2 - gap * 3) / 8f
    val pill = if (d == DesignStyle.EXPRESSIVE || d == DesignStyle.GLASS) rowH / 2f else r
    key(Offset(pad, y), Size(unit * 1.5f, rowH), SolidColor(funcColor), r, funcOutline ?: outline)
    key(Offset(pad + unit * 1.5f + gap, y), Size(unit * 4.5f, rowH), SolidColor(keyColor), pill, outline)
    val ex = pad + unit * 6f + gap * 2
    val er = if (d == DesignStyle.MATERIAL) rowH / 2f else min(pill, rowH / 2f)
    key(Offset(ex, y), Size(w - pad - ex, rowH), enter, er, null)
}
