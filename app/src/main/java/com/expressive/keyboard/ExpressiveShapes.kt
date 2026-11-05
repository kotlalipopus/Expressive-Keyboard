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

enum class ShapeProfile { SINE, LOBES, CIRCLE }

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
    CIRCLE("CIRCLE", "Круг", ShapeProfile.CIRCLE, 12, 0f);

    /** Та же фигура из набора MaterialShapes. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val material: RoundedPolygon
        get() = when (this) {
            COOKIE_12 -> MaterialShapes.Cookie12Sided
            SUNNY_8 -> MaterialShapes.Sunny
            FLOWER_6 -> MaterialShapes.Flower
            CLOVER_4 -> MaterialShapes.Clover4Leaf
            CIRCLE -> MaterialShapes.Circle
        }

    companion object {
        fun fromId(id: String): ExpressiveShapePreset = entries.firstOrNull { it.id == id } ?: COOKIE_12
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

private fun unitPath(spec: WavySpec): android.graphics.Path = unitPaths.getOrPut(spec) {
    android.graphics.Path().also { polygonFor(spec).toPath(it) }
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
