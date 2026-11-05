@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.zIndex
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.composed
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
import kotlinx.coroutines.CoroutineStart
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// ПАЛИТРА КЛАВИШ, ЗНАЧКИ, ЦВЕТА И ДОЛИ ФОРМЫ
// ============================================================================

@Immutable
data class KeyPalette(
    val background: Color,
    val letter: Color, val onLetter: Color,
    val func: Color, val onFunc: Color,
    val accent: Color, val onAccent: Color,
    val accentSoft: Color, val onAccentSoft: Color,
    val panel: Color, val outline: Color, val muted: Color
)

/** Читаемый цвет текста поверх произвольного фона. */
fun contentColorFor(bg: Color): Color = if (bg.luminance() > 0.42f) Color(0xFF1B1B1F) else Color(0xFFF5F5F7)

/** Палитра клавиатуры: Monet/свой цвет + ручные переопределения + прозрачность клавиш. */
fun buildKeyPalette(cs: ColorScheme, s: KeyboardSettings): KeyPalette {
    fun pick(v: Int, auto: Color) = if (v == 0) auto else Color(v)
    val bg = pick(s.colorBackground, cs.surfaceContainer)
    val letter = pick(s.colorKey, cs.surfaceBright)
    val onLetter = when {
        s.colorKeyText != 0 -> Color(s.colorKeyText)
        s.colorKey != 0 -> contentColorFor(letter)
        else -> cs.onSurface
    }
    val func = pick(s.colorFunc, cs.secondaryContainer)
    val onFunc = when {
        s.colorFuncText != 0 -> Color(s.colorFuncText)
        s.colorFunc != 0 -> contentColorFor(func)
        else -> cs.onSecondaryContainer
    }
    val accent = pick(s.colorAccent, cs.primary)
    val onAccent = when {
        s.colorAccentText != 0 -> Color(s.colorAccentText)
        s.colorAccent != 0 -> contentColorFor(accent)
        else -> cs.onPrimary
    }
    val accentSoft = if (s.colorAccent != 0) lerp(accent, bg, 0.62f) else cs.primaryContainer
    val onAccentSoft = if (s.colorAccent != 0) contentColorFor(accentSoft) else cs.onPrimaryContainer
    val panel = if (s.colorBackground != 0) lerp(bg, letter, 0.35f) else cs.surfaceContainerHigh
    val a = s.keyOpacity
    return KeyPalette(
        background = bg,
        letter = letter.copy(alpha = letter.alpha * a), onLetter = onLetter,
        func = func.copy(alpha = func.alpha * a), onFunc = onFunc,
        accent = accent, onAccent = onAccent,
        accentSoft = accentSoft.copy(alpha = accentSoft.alpha * (0.5f + a / 2f)), onAccentSoft = onAccentSoft,
        panel = panel.copy(alpha = panel.alpha * (0.4f + a * 0.6f)),
        outline = if (s.colorBackground != 0 || s.colorKeyText != 0) onLetter.copy(alpha = 0.16f) else cs.outlineVariant,
        muted = if (s.colorKeyText != 0 || s.colorKey != 0) onLetter.copy(alpha = 0.66f) else cs.onSurfaceVariant
    )
}

@Composable
fun rememberKeyPalette(settings: KeyboardSettings): KeyPalette {
    val cs = MaterialTheme.colorScheme
    return remember(
        cs, settings.colorBackground, settings.colorKey, settings.colorKeyText, settings.colorFunc,
        settings.colorFuncText, settings.colorAccent, settings.colorAccentText, settings.keyOpacity
    ) { buildKeyPalette(cs, settings) }
}

/** Фоновая картинка декодируется в фоне один раз на версию файла. */
@Composable
fun rememberBackgroundBitmap(version: Long): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(null, version) {
        value = if (version == 0L) null else withContext(Dispatchers.IO) {
            runCatching {
                val f = File(context.filesDir, KeyboardPrefs.BG_IMAGE_FILENAME)
                if (!f.exists()) null else {
                    // файл уже уменьшен при выборе, но на всякий случай — не больше ~2000 px по стороне
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                    var sample = 1
                    while (maxOf(o.outWidth, o.outHeight) / sample > 2048) sample *= 2
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return state.value
}

enum class Glyph { SHIFT, CAPS, BACKSPACE, BACKSPACE_TAG, ARROW, RETURN, GLOBE, EMOJI, GRID }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp) {
    // в темах (Material, Samsung, Классика…) ⌫ — привычный «ярлычок» с крестиком; голый × остаётся у Expressive
    val g = if (glyph == Glyph.BACKSPACE && LocalSkin.current != null) Glyph.BACKSPACE_TAG else glyph
    Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawGlyph(g, color)
    }
}

internal fun DrawScope.drawGlyph(g: Glyph, color: Color) {
    val u = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    val round = Stroke(width = 3.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        Glyph.SHIFT, Glyph.CAPS -> {
            val p = Path().apply {
                moveTo(12f * u, 3.5f * u); lineTo(20.5f * u, 12f * u); lineTo(15.5f * u, 12f * u)
                lineTo(15.5f * u, 18f * u); lineTo(8.5f * u, 18f * u); lineTo(8.5f * u, 12f * u)
                lineTo(3.5f * u, 12f * u); close()
            }
            drawPath(p, color)
            drawPath(p, color, style = Stroke(width = 2.6f * u, join = StrokeJoin.Round))
            if (g == Glyph.CAPS) drawLine(color, o(8f, 21.6f), o(16f, 21.6f), 2.6f * u, StrokeCap.Round)
        }
        Glyph.BACKSPACE -> {
            drawLine(color, o(6.5f, 6.5f), o(17.5f, 17.5f), 3.4f * u, StrokeCap.Round)
            drawLine(color, o(17.5f, 6.5f), o(6.5f, 17.5f), 3.4f * u, StrokeCap.Round)
        }
        Glyph.BACKSPACE_TAG -> {
            val tag = Path().apply {
                moveTo(8.2f * u, 5f * u); lineTo(19.5f * u, 5f * u)
                quadraticTo(22f * u, 5f * u, 22f * u, 7.5f * u); lineTo(22f * u, 16.5f * u)
                quadraticTo(22f * u, 19f * u, 19.5f * u, 19f * u); lineTo(8.2f * u, 19f * u)
                lineTo(2f * u, 12f * u); close()
            }
            val line = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(tag, color, style = line)
            drawLine(color, o(11.5f, 9f), o(17.5f, 15f), 2.2f * u, StrokeCap.Round)
            drawLine(color, o(17.5f, 9f), o(11.5f, 15f), 2.2f * u, StrokeCap.Round)
        }
        Glyph.ARROW -> {
            drawLine(color, o(4.5f, 12f), o(18.5f, 12f), 3.4f * u, StrokeCap.Round)
            val p = Path().apply { moveTo(12.5f * u, 6f * u); lineTo(18.8f * u, 12f * u); lineTo(12.5f * u, 18f * u) }
            drawPath(p, color, style = Stroke(width = 3.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Glyph.RETURN -> {
            val p = Path().apply { moveTo(19f * u, 5f * u); lineTo(19f * u, 13f * u); lineTo(5.5f * u, 13f * u) }
            drawPath(p, color, style = round)
            val h = Path().apply { moveTo(10f * u, 8.5f * u); lineTo(5.5f * u, 13f * u); lineTo(10f * u, 17.5f * u) }
            drawPath(h, color, style = round)
        }
        Glyph.GLOBE -> {
            // простой глобус той же толщины, что и остальные значки: окружность, меридиан и экватор
            val r = 9f
            val line = Stroke(width = 2.6f * u, cap = StrokeCap.Round)
            drawCircle(color, r * u, o(12f, 12f), style = line)
            drawOval(color, o(12f - 3.9f, 12f - r), Size(7.8f * u, 2f * r * u), style = line)
            drawLine(color, o(12f - r, 12f), o(12f + r, 12f), 2.6f * u, StrokeCap.Round)
        }
        Glyph.EMOJI -> {
            drawCircle(color, 10.5f * u, o(12f, 12f))
            drawCircle(Color.Black, 1.7f * u, o(8.6f, 9.2f), blendMode = BlendMode.Clear)
            drawCircle(Color.Black, 1.7f * u, o(15.4f, 9.2f), blendMode = BlendMode.Clear)
            drawArc(
                Color.Black, 0f, 180f, true, topLeft = o(6.8f, 8.6f), size = Size(10.4f * u, 9.8f * u),
                blendMode = BlendMode.Clear
            )
        }
        Glyph.GRID -> {
            drawRoundRect(color, o(3f, 3f), Size(8f * u, 8f * u), CornerRadius(2.4f * u, 2.4f * u))
            drawCircle(color, 4f * u, o(17f, 7f))
            drawCircle(color, 4f * u, o(7f, 17f))
            drawRoundRect(color, o(13f, 13f), Size(8f * u, 8f * u), CornerRadius(2.4f * u, 2.4f * u))
        }
    }
}

@Immutable
data class KeyColors(val bg: Color, val fg: Color, val activeBg: Color, val activeFg: Color)

internal fun keyColors(type: KeyType, shift: ShiftState, p: KeyPalette): KeyColors = when (type) {
    KeyType.CHARACTER, KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE, KeyType.GLOBE, KeyType.STICKERS ->
        KeyColors(p.letter, p.onLetter, p.accent, p.onAccent)
    KeyType.SHIFT -> if (shift == ShiftState.OFF) KeyColors(p.accentSoft, p.onAccentSoft, p.accent, p.onAccent)
    else KeyColors(p.accent, p.onAccent, p.accentSoft, p.onAccentSoft)
    KeyType.BACKSPACE -> KeyColors(p.func, p.onFunc, p.accent, p.onAccent)
    KeyType.SPACE -> KeyColors(p.func, p.onFunc, p.accentSoft, p.onAccentSoft)
    KeyType.ENTER -> KeyColors(p.accent, p.onAccent, lerp(p.accent, p.onAccent, 0.18f), p.onAccent)
    KeyType.SPACER -> KeyColors(Color.Transparent, Color.Transparent, Color.Transparent, Color.Transparent)
}

/**
 * Клавиша без подложки: ни плашки, ни тени, ни обводки — только подпись на фоне клавиатуры.
 * Нажатие при этом по-прежнему подсвечивается акцентом (или цветом нажатия из темы).
 */
internal fun KeyStyle.withoutPlate(): KeyStyle = copy(
    material = SkinMaterial.NONE,
    fill = null,
    border = null,
    borderWidth = 0f,
    shadow = 0f,
    shadowColor = null,
    glow = null,
    glass = null,
    bevel = 0f,
    inset = 0f
)

/** Доля скругления от меньшей стороны: в покое → при нажатии (морфинг формы). */
internal fun shapeFractions(type: KeyType): Pair<Float, Float> = when (type) {
    KeyType.CHARACTER, KeyType.SHIFT -> 0.38f to 0.5f
    KeyType.BACKSPACE -> 0.5f to 0.28f
    KeyType.ENTER -> 0.42f to 0.24f
    KeyType.SPACE -> 0.4f to 0.5f
    KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE -> 0.3f to 0.5f
    else -> 0.33f to 0.5f
}
