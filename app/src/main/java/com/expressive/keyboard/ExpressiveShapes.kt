@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

// ============================================================================
// 1. ФИГУРЫ MATERIAL 3 EXPRESSIVE
// Формы — официальные MaterialShapes из material3 (androidx.graphics.shapes.RoundedPolygon).
// Если пользователь сам поменял число волн или их глубину, фигура строится тем же способом,
// что и в MaterialShapes: RoundedPolygon.star со скруглёнными вершинами.
// ============================================================================

/** SINE/LOBES — волны, число и глубину можно менять; CIRCLE — круг; FIXED — готовая форма MaterialShapes, как есть. */
enum class ShapeProfile { SINE, LOBES, CIRCLE, FIXED }

enum class ExpressiveShapePreset(
    val id: String,
    val title: String,
    val profile: ShapeProfile,
    val defaultWaves: Int,
    val defaultAmplitude: Float
) {
    COOKIE_12("COOKIE_12", "Печенька", ShapeProfile.SINE, 12, 6f),
    SUNNY_8("WAVY_8", "Солнце", ShapeProfile.SINE, 8, 8f),
    FLOWER_6("FLOWER_6", "Цветок", ShapeProfile.LOBES, 6, 10f),
    CLOVER_4("CLOVER_4", "Клевер", ShapeProfile.LOBES, 4, 12f),
    CIRCLE("CIRCLE", "Круг", ShapeProfile.CIRCLE, 12, 0f),
    SQUARE("SQUARE", "Квадрат", ShapeProfile.FIXED, 12, 6f),
    SLANTED("SLANTED", "Наклон", ShapeProfile.FIXED, 12, 6f),
    ARCH("ARCH", "Арка", ShapeProfile.FIXED, 12, 6f),
    SEMICIRCLE("SEMICIRCLE", "Полукруг", ShapeProfile.FIXED, 12, 6f),
    OVAL("OVAL", "Овал", ShapeProfile.FIXED, 12, 6f),
    PILL("PILL", "Таблетка", ShapeProfile.FIXED, 12, 6f),
    TRIANGLE("TRIANGLE", "Треугольник", ShapeProfile.FIXED, 12, 6f),
    ARROW("ARROW", "Стрелка", ShapeProfile.FIXED, 12, 6f),
    FAN("FAN", "Веер", ShapeProfile.FIXED, 12, 6f),
    DIAMOND("DIAMOND", "Ромб", ShapeProfile.FIXED, 12, 6f),
    CLAMSHELL("CLAMSHELL", "Ракушка", ShapeProfile.FIXED, 12, 6f),
    PENTAGON("PENTAGON", "Пятиугольник", ShapeProfile.FIXED, 12, 6f),
    GEM("GEM", "Самоцвет", ShapeProfile.FIXED, 12, 6f),
    VERY_SUNNY("VERY_SUNNY", "Яркое солнце", ShapeProfile.FIXED, 12, 6f),
    COOKIE_4("COOKIE_4", "Печенька 4", ShapeProfile.FIXED, 12, 6f),
    COOKIE_6("COOKIE_6", "Печенька 6", ShapeProfile.FIXED, 12, 6f),
    COOKIE_7("COOKIE_7", "Печенька 7", ShapeProfile.FIXED, 12, 6f),
    COOKIE_9("COOKIE_9", "Печенька 9", ShapeProfile.FIXED, 12, 6f),
    CLOVER_8("CLOVER_8", "Клевер 8", ShapeProfile.FIXED, 12, 6f),
    BURST("BURST", "Вспышка", ShapeProfile.FIXED, 12, 6f),
    SOFT_BURST("SOFT_BURST", "Мягкая вспышка", ShapeProfile.FIXED, 12, 6f),
    BOOM("BOOM", "Бум", ShapeProfile.FIXED, 12, 6f),
    SOFT_BOOM("SOFT_BOOM", "Мягкий бум", ShapeProfile.FIXED, 12, 6f),
    PUFFY("PUFFY", "Пышная", ShapeProfile.FIXED, 12, 6f),
    PUFFY_DIAMOND("PUFFY_DIAMOND", "Пышный ромб", ShapeProfile.FIXED, 12, 6f),
    GHOSTISH("GHOSTISH", "Привидение", ShapeProfile.FIXED, 12, 6f),
    PIXEL_CIRCLE("PIXEL_CIRCLE", "Пиксельный круг", ShapeProfile.FIXED, 12, 6f),
    PIXEL_TRIANGLE("PIXEL_TRIANGLE", "Пиксельный треугольник", ShapeProfile.FIXED, 12, 6f),
    BUN("BUN", "Булочка", ShapeProfile.FIXED, 12, 6f),
    HEART("HEART", "Сердце", ShapeProfile.FIXED, 12, 6f);

    /** Та же фигура из набора MaterialShapes. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val material: RoundedPolygon
        get() = when (this) {
            COOKIE_12 -> MaterialShapes.Cookie12Sided
            SUNNY_8 -> MaterialShapes.Sunny
            FLOWER_6 -> MaterialShapes.Flower
            CLOVER_4 -> MaterialShapes.Clover4Leaf
            CIRCLE -> MaterialShapes.Circle
            SQUARE -> MaterialShapes.Square
            SLANTED -> MaterialShapes.Slanted
            ARCH -> MaterialShapes.Arch
            SEMICIRCLE -> MaterialShapes.SemiCircle
            OVAL -> MaterialShapes.Oval
            PILL -> MaterialShapes.Pill
            TRIANGLE -> MaterialShapes.Triangle
            ARROW -> MaterialShapes.Arrow
            FAN -> MaterialShapes.Fan
            DIAMOND -> MaterialShapes.Diamond
            CLAMSHELL -> MaterialShapes.ClamShell
            PENTAGON -> MaterialShapes.Pentagon
            GEM -> MaterialShapes.Gem
            VERY_SUNNY -> MaterialShapes.VerySunny
            COOKIE_4 -> MaterialShapes.Cookie4Sided
            COOKIE_6 -> MaterialShapes.Cookie6Sided
            COOKIE_7 -> MaterialShapes.Cookie7Sided
            COOKIE_9 -> MaterialShapes.Cookie9Sided
            CLOVER_8 -> MaterialShapes.Clover8Leaf
            BURST -> MaterialShapes.Burst
            SOFT_BURST -> MaterialShapes.SoftBurst
            BOOM -> MaterialShapes.Boom
            SOFT_BOOM -> MaterialShapes.SoftBoom
            PUFFY -> MaterialShapes.Puffy
            PUFFY_DIAMOND -> MaterialShapes.PuffyDiamond
            GHOSTISH -> MaterialShapes.Ghostish
            PIXEL_CIRCLE -> MaterialShapes.PixelCircle
            PIXEL_TRIANGLE -> MaterialShapes.PixelTriangle
            BUN -> MaterialShapes.Bun
            HEART -> MaterialShapes.Heart
        }

    /** Можно ли менять число и глубину волн (у готовых форм и круга ползунки не нужны). */
    val tunable: Boolean get() = profile == ShapeProfile.SINE || profile == ShapeProfile.LOBES

    companion object {
        fun fromId(id: String): ExpressiveShapePreset = entries.firstOrNull { it.id == id } ?: COOKIE_12

        /** Компактные «круглые» формы для украшений: значки плагинов и плиток (полукруг, стрелка и т.п. там смотрятся плохо). */
        val decorative: List<ExpressiveShapePreset> = listOf(
            COOKIE_12, SUNNY_8, FLOWER_6, CLOVER_4, COOKIE_6, COOKIE_7, COOKIE_9, CLOVER_8,
            SOFT_BURST, VERY_SUNNY, PUFFY, GEM, PENTAGON, SQUARE
        )
    }
}

@Immutable
data class WavySpec(val profile: ShapeProfile, val waves: Int, val amplitude: Float, val preset: ExpressiveShapePreset? = null) {
    companion object {
        val Circle = WavySpec(ShapeProfile.CIRCLE, 12, 0f, ExpressiveShapePreset.CIRCLE)
        fun of(preset: ExpressiveShapePreset) = WavySpec(preset.profile, preset.defaultWaves, preset.defaultAmplitude / 100f, preset)
    }
}

/**
 * Сколько выразительных форм в интерфейсе (настройка «Формы» в первой настройке и в «Цвета и фон»):
 * FULL — везде, как задумано в Expressive; ACCENTS — только у главных кнопок (меню и микрофон
 * клавиатуры, главная карточка), остальное — круги и скругления; NONE — без волнистых форм.
 */
object ShapeAmount {
    const val FULL = "FULL"
    const val ACCENTS = "ACCENTS"
    const val NONE = "NONE"
}

/** Форма для главного акцента: исчезает только в режиме «без форм». */
fun WavySpec.accent(settings: KeyboardSettings): WavySpec = if (settings.shapeAmount == ShapeAmount.NONE) WavySpec.Circle else this

/** Форма для украшений (плитки, цвета, всплывающие буквы): только в режиме «много форм». */
fun WavySpec.decor(settings: KeyboardSettings): WavySpec = if (settings.shapeAmount == ShapeAmount.FULL) this else WavySpec.Circle

/** Нормализованная (0..1) фигура в виде android-пути — строится один раз на фигуру. */
private val unitPaths = ConcurrentHashMap<WavySpec, android.graphics.Path>()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun polygonFor(spec: WavySpec): RoundedPolygon {
    val preset = spec.preset
    if (spec.profile == ShapeProfile.CIRCLE) return MaterialShapes.Circle
    // готовые формы: волны и глубина на них не влияют
    if (spec.profile == ShapeProfile.FIXED) return (preset ?: ExpressiveShapePreset.CIRCLE).material
    if (preset != null && spec.waves == preset.defaultWaves && abs(spec.amplitude - preset.defaultAmplitude / 100f) < 0.001f) {
        return preset.material
    }
    val waves = spec.waves.coerceIn(3, 24)
    val amp = spec.amplitude.coerceIn(0f, 0.16f)
    return when (spec.profile) {
        // «печенька»: вершины-волны со скруглением, как Cookie9Sided / Cookie12Sided
        ShapeProfile.SINE -> RoundedPolygon.star(
            numVerticesPerRadius = waves, radius = 1f, innerRadius = (1f - amp * 2.2f).coerceIn(0.5f, 1f),
            rounding = CornerRounding(0.5f), innerRounding = CornerRounding(0.5f)
        )
        // «цветок» и «клевер»: круглые лепестки и острые впадины между ними
        else -> RoundedPolygon.star(
            numVerticesPerRadius = waves, radius = 1f, innerRadius = (1f - amp * 3.5f).coerceIn(0.35f, 0.95f),
            rounding = CornerRounding(1f), innerRounding = CornerRounding(0.15f)
        )
    }.normalized()
}

private fun isPresetDefault(spec: WavySpec): Boolean {
    val preset = spec.preset ?: return false
    return spec.waves == preset.defaultWaves && abs(spec.amplitude - preset.defaultAmplitude / 100f) < 0.001f
}

/**
 * Своя «печенька»/«солнце» (число или глубина волн изменены): синусоида по кругу, как до 11.1.10.
 * RoundedPolygon.star со скруглением 0.5 превращал глубокие волны в кляксу — звёздочки (5 волн, большая глубина)
 * стало не сделать; а при глубине 0 внутренний радиус равнялся внешнему, и star() ронял клавиатуру.
 */
private fun sinePath(spec: WavySpec, out: android.graphics.Path) {
    val waves = spec.waves.coerceIn(3, 24)
    val amp = spec.amplitude.coerceIn(0f, 0.16f)
    val steps = (waves * 20).coerceIn(120, 400)
    for (i in 0 until steps) {
        val t = i.toDouble() / steps * 2.0 * PI
        val k = 1f - amp + amp * cos(waves * t).toFloat()
        val a = t - PI / 2.0
        val x = 0.5f + 0.5f * k * cos(a).toFloat()
        val y = 0.5f + 0.5f * k * sin(a).toFloat()
        if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
    }
    out.close()
}

private fun unitPath(spec: WavySpec): android.graphics.Path = unitPaths.getOrPut(spec) {
    android.graphics.Path().also { p ->
        if (spec.profile == ShapeProfile.SINE && !isPresetDefault(spec)) sinePath(spec, p)
        else runCatching { polygonFor(spec).toPath(p) }.onFailure { p.reset(); p.addOval(0f, 0f, 1f, 1f, android.graphics.Path.Direction.CW) }
    }
}

private val shapeMatrix = ThreadLocal.withInitial { android.graphics.Matrix() }

fun buildWavyPath(spec: WavySpec, cx: Float, cy: Float, radius: Float, path: Path = Path()): Path {
    path.reset()
    val m = shapeMatrix.get()!!
    m.setScale(radius * 2f, radius * 2f)
    m.postTranslate(cx - radius, cy - radius)
    unitPath(spec).transform(m, path.asAndroidPath())
    return path
}

/** Форма как Shape для clip/background — из той же фигуры. */
class WavyShape(private val spec: WavySpec) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(buildWavyPath(spec, size.width / 2f, size.height / 2f, min(size.width, size.height) / 2f))
}

/** Волнистый фон без clip/shadow: путь кэшируется по размеру, вращение читается только при отрисовке. */
fun Modifier.wavyBackground(spec: WavySpec, color: Color, rotation: () -> Float = { 0f }): Modifier =
    this.drawWithCache {
        val path = buildWavyPath(spec, size.width / 2f, size.height / 2f, min(size.width, size.height) / 2f)
        onDrawBehind {
            rotate(rotation(), center) { drawPath(path, color) }
        }
    }
