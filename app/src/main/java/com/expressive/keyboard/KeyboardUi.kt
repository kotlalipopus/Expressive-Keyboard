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
// 8. UI КЛАВИАТУРЫ (MATERIAL 3 EXPRESSIVE ПО МАКЕТУ)
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

enum class Glyph { SHIFT, CAPS, BACKSPACE, ARROW, RETURN, GLOBE, EMOJI, GRID }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawGlyph(glyph, color)
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

/** Доля скругления от меньшей стороны: в покое → при нажатии (морфинг формы). */
internal fun shapeFractions(type: KeyType): Pair<Float, Float> = when (type) {
    KeyType.CHARACTER, KeyType.SHIFT -> 0.38f to 0.5f
    KeyType.BACKSPACE -> 0.5f to 0.28f
    KeyType.ENTER -> 0.42f to 0.24f
    KeyType.SPACE -> 0.4f to 0.5f
    KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE -> 0.3f to 0.5f
    else -> 0.33f to 0.5f
}

@Stable
class CoordsHolder {
    var coords: LayoutCoordinates? = null
}

@Immutable
data class PopupState(
    val owner: Any,
    val anchorX: Float,
    val anchorTop: Float,
    val primary: String,
    val variants: List<String>,
    val selected: Int,
    val expanded: Boolean
)

/**
 * Где стоят варианты буквы: левый край ряда и шаг ячейки (в пикселях слоя клавиатуры). Одно и то же и для
 * рисования, и для выбора пальцем: раньше выбор шёл шагами 44dp от места касания, а ряд прижимался к краю экрана
 * ячейками по 50dp — у крайних клавиш («a», «p») часть вариантов была недосягаема.
 */
internal fun popupRow(n: Int, anchorX: Float, layerWidth: Float, density: Float): Pair<Float, Float> {
    val margin = 4f * density
    val slot = min(50f * density, (layerWidth - 2f * margin) / max(1, n))
    val width = n * slot
    val left = (anchorX - width / 2f).coerceIn(margin, max(margin, layerWidth - width - margin))
    return left to slot
}

/** Состояние всплывашки читает ТОЛЬКО слой всплывашки — клавиатура при нажатии не перекомпонуется. */
@Stable
class PopupController {
    var state by mutableStateOf<PopupState?>(null)
        private set

    fun show(s: PopupState) {
        state = s
    }

    fun select(owner: Any, index: Int) {
        val c = state ?: return
        if (c.owner === owner && c.selected != index) state = c.copy(selected = index)
    }

    fun clear(owner: Any) {
        if (state?.owner === owner) state = null
    }
}

/** Чем закончилось касание буквы до выбора варианта. */
internal enum class KeyGesture { RELEASED, CANCELED, HOLD, SLIDE, GLIDE, SWIPE_UP }

internal suspend fun AwaitPointerEventScope.awaitPressEnd(id: PointerId): Boolean {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) return true
    }
}

internal val VARIANT_KEY = KeyModel("")
internal const val DELETE_WORD = "\u0000word"

internal fun hintFor(label: String, variants: List<String>): String? {
    if (variants.size <= 1) return null
    return variants.firstOrNull { it.length == 1 && it[0].isDigit() }
        ?: variants.firstOrNull { !it.equals(label, ignoreCase = true) }
}

/** Положение клавиши относительно фона клавиатуры — нужно стеклу, чтобы «видеть», что под ним. */
internal fun keyPosIn(holder: CoordsHolder, backdrop: Backdrop?): Offset? {
    val k = holder.coords ?: return null
    val r = backdrop?.coords ?: return null
    return r.safePositionOf(k)
}

@Composable
internal fun KeyView(
    key: KeyModel,
    label: String,
    upper: Boolean,
    shift: ShiftState,
    enterAction: Int,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController,
    actions: KeyboardActions,
    glideEnabled: Boolean,
    numberRow: Boolean,
    modifier: Modifier,
    /** Символ по удержанию в режиме «как в Gboard» (null — обычные варианты). */
    holdSymbol: String? = null,
    /** Промежутки между клавишами (по горизонтали и между рядами): касание в половине промежутка — тоже эта клавиша. */
    gapX: Dp = 0.dp,
    gapY: Dp = 0.dp
) {
    val pluginState = PluginRepository.active
    // с рядом цифр цифра под буквой верхнего ряда лишняя — она и так на виду
    val noDigits = settings.showNumberRow && !numberRow && key.type == KeyType.CHARACTER && key.label.firstOrNull()?.isLetter() == true
    val hold = holdSymbol?.takeUnless { noDigits && it.length == 1 && it[0].isDigit() }
    val variants = remember(key, upper, pluginState, hold, noDigits) {
        if (hold == null) key.variants(upper).let { v -> if (noDigits) v.filterNot { it.length == 1 && it[0].isDigit() } else v }
        else {
            // символ — по умолчанию (в середине), рядом только варианты самой буквы (ё, ъ, буквы с ударением)
            val letters = key.variants(upper).filter { v -> v.length == 1 && v[0].isLetter() && !v.equals(key.label, ignoreCase = true) }
            val mid = (letters.size + 1) / 2
            letters.take(mid) + hold + letters.drop(mid)
        }
    }
    val output = remember(key, upper) { key.text(upper) }
    val holder = remember { CoordsHolder() }
    // область касания: клавиша вместе с половинами промежутков вокруг (раньше касание между клавишами терялось)
    val cell = remember { CoordsHolder() }
    val gx = with(LocalDensity.current) { gapX.roundToPx() }
    val gy = with(LocalDensity.current) { gapY.roundToPx() }
    // анимация считается законченной чуть раньше (порог 0.03): меньше кадров на каждое нажатие
    val anim = remember { Animatable(0f, 0.03f) }
    // внешний вид клавиши перерисовывается только при заметном изменении нажатия: «желейные» колебания после
    // отпускания двигают лишь масштаб слоя (дёшево), а не перерисовывают стекло и тени каждый кадр
    val drawP by remember { derivedStateOf { (anim.value.coerceIn(0f, 1f) * 8f).roundToInt() / 8f } }
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current
    val backdrop = LocalBackdrop.current
    val colors = remember(key.type, shift, palette) { keyColors(key.type, shift, palette) }
    val style = remember(skin, key.type, key.label, shift, colors) {
        val st = skin?.styleFor(key.type, key.label)
        // включённый Shift заливается акцентом, иначе в стекле и контурах его состояние не видно
        if (st != null && key.type == KeyType.SHIFT && shift != ShiftState.OFF) {
            st.copy(
                fill = SkinFill.Solid(if (st.material == SkinMaterial.GLASS) colors.bg.copy(alpha = 0.85f) else colors.bg),
                text = colors.fg, textGlow = null
            )
        } else st
    }
    val skinCache = remember { SkinKeyCache() }
    val isChar = key.type == KeyType.CHARACTER
    val upHint = if (isChar) remember(label, variants, hold) { hold ?: hintFor(label, variants) } else null
    val cKey by rememberUpdatedState(key)
    val cLabel by rememberUpdatedState(label)
    val cOutput by rememberUpdatedState(output)
    val cVariants by rememberUpdatedState(variants)
    val cSettings by rememberUpdatedState(settings)
    val cActions by rememberUpdatedState(actions)
    val cGlide by rememberUpdatedState(glideEnabled)
    val cShift by rememberUpdatedState(shift)
    val cUpHint by rememberUpdatedState(upHint)
    val cGx by rememberUpdatedState(gx)
    val cGy by rememberUpdatedState(gy)
    val fr = shapeFractions(key.type)
    val capPx = with(LocalDensity.current) { settings.cornerRadiusDp.dp.toPx() }
    val border = settings.showKeyBorders
    val borderColor = palette.outline
    // в энергосбережении (и с включённой экономией заряда) клавиши не анимируются: только смена цвета
    val press = if (settings.ecoMode && PowerState.saver) 0f else settings.pressAnimation
    // анимация нажатия: выбранная в настройках «Анимации» или из темы
    val pressAnim = remember(settings.animPress, settings.customAnims, style?.press, style?.pressScale) { Anims.forKey(settings, style) }
    val cPressAnim by rememberUpdatedState(pressAnim)
    val speed2 = settings.animSpeed * settings.animSpeed
    val cSpeed2 by rememberUpdatedState(speed2)
    val weight = LocalKeyWeight.current
    val hint = if (isChar && settings.showHints && !numberRow && style?.hideHint != true) upHint else null

    // клавиша видна модам: kb.keys(), kb.pressKey()
    val handle = remember(key, label) {
        KeyHandle(key, label, holder) {
            scope.launch {
                anim.snapTo(0f)
                anim.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 1800f))
                anim.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 600f))
            }
        }
    }
    DisposableEffect(handle) {
        KeyboardGeometry.register(handle)
        onDispose { KeyboardGeometry.unregister(handle) }
    }

    // буквы регистрируются для печати свайпом
    if (isChar && label.length == 1 && label[0].isLetter()) {
        DisposableEffect(label, glide) {
            val l = label.lowercase()
            glide.register(l, holder)
            onDispose { glide.unregister(l, holder) }
        }
    }

    fun anchor(): Offset? {
        val k = holder.coords ?: return null
        val l = layer.coords ?: return null
        val p = l.safePositionOf(k) ?: return null
        return Offset(p.x + k.size.width / 2f, p.y)
    }

    /** Левый верхний угол области касания в координатах клавиатуры. */
    fun origin(): Offset? {
        val k = cell.coords ?: return null
        val l = layer.coords ?: return null
        return l.safePositionOf(k)
    }

    Box(
        modifier = modifier
            .layout { m, c ->
                if (!c.hasBoundedWidth || !c.hasBoundedHeight || (gx == 0 && gy == 0)) {
                    val pl = m.measure(c)
                    return@layout layout(pl.width, pl.height) { pl.place(0, 0) }
                }
                val pl = m.measure(Constraints.fixed(c.maxWidth + gx, c.maxHeight + gy))
                layout(c.maxWidth, c.maxHeight) { pl.place(-gx / 2, -gy / 2) }
            }
            .onGloballyPositioned { cell.coords = it }
            .pointerInput(Unit) {
                val slop = 12.dp.toPx()
                val step = 44.dp.toPx()
                val swipe = 56.dp.toPx()
                val cursorStep = 14.dp.toPx()
                val wordStep = 36.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    val k = cKey
                    val act = cActions
                    // сначала отклик (вибрация, звук), потом всё остальное
                    act.keyDown(k)
                    pressed = true
                    // размер самой клавиши — без половин промежутков вокруг
                    val inset = Offset(cGx / 2f, cGy / 2f)
                    val iw = max(1f, size.width - cGx.toFloat())
                    val ih = max(1f, size.height - cGy.toFloat())
                    skinCache.touch = down.position - inset
                    // клавиша гаснет досрочно: палец уже жмёт следующую
                    val releaseEarly = {
                        pressed = false
                        popup.clear(holder)
                        scope.launch { anim.animateTo(0f, spring(dampingRatio = 1f, stiffness = 1500f * cSpeed2)) }
                    }
                    // Shift и ⌫ не «перекатываются», но нажатая до них буква срабатывает раньше них
                    if (k.type == KeyType.SHIFT || k.type == KeyType.BACKSPACE) Rollover.press(holder, null)
                    // нажатие видно в ближайшем же кадре: без запуска корутины «на потом» и без разгона пружины с нуля
                    if (cSettings.pressAnimation <= 0f || (cSettings.ecoMode && PowerState.saver)) scope.launch(start = CoroutineStart.UNDISPATCHED) { anim.snapTo(1f) }
                    else scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        anim.snapTo(max(anim.value, 0.6f))
                        anim.animateTo(1f, spring(dampingRatio = 1f, stiffness = 4000f * cSpeed2))
                    }
                    try {
                        when (k.type) {
                            KeyType.BACKSPACE -> {
                                act.commitKey(k, "")
                                var deadline = SystemClock.uptimeMillis() + 420L
                                var wordMode = false
                                var lastX = down.position.x
                                // свайп влево сначала выделяет слова (вправо — отпускает их обратно), а стирает
                                // только по отпусканию: случайный мазок больше не съедает текст
                                var words = 0
                                var released = false
                                try {
                                    while (true) {
                                        val left = deadline - SystemClock.uptimeMillis()
                                        val ev = if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                        if (ev == null) {
                                            if (!wordMode) {
                                                act.keyTick()
                                                act.commitKey(k, "")
                                            }
                                            deadline = SystemClock.uptimeMillis() + 55L
                                            continue
                                        }
                                        val ch = ev.changes.firstOrNull { it.id == id } ?: break
                                        if (!ch.pressed) {
                                            released = true
                                            break
                                        }
                                        if (!wordMode && cSettings.backspaceSwipe && down.position.x - ch.position.x > slop * 2) {
                                            wordMode = true
                                            lastX = ch.position.x
                                            words = 1
                                            act.keyTick()
                                            act.backspaceSelect(words)
                                        }
                                        if (wordMode) {
                                            val n = floor((lastX - ch.position.x) / wordStep + 1f).toInt().coerceAtLeast(0)
                                            if (n != words) {
                                                words = n
                                                act.keyTick()
                                                act.backspaceSelect(words)
                                            }
                                            deadline = Long.MAX_VALUE
                                        }
                                    }
                                } finally {
                                    if (wordMode) act.backspaceSelectDone(released && words > 0)
                                }
                            }
                            KeyType.SHIFT -> {
                                // Shift — в момент касания, как в Gboard: при быстром наборе палец уже жмёт букву,
                                // пока Shift ещё не отпущен, и раньше заглавная терялась
                                act.commitKey(k, cOutput)
                                while (true) {
                                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                    if (!ch.pressed) break
                                }
                            }
                            KeyType.SPACE -> {
                                val mode = cSettings.spaceSwipe
                                var swiped = false
                                var picker = false
                                var rolled = false
                                var lastX = down.position.x
                                Rollover.press(holder) {
                                    if (!swiped && !picker && !rolled) {
                                        rolled = true
                                        act.commitKey(k, " ")
                                        releaseEarly()
                                    }
                                }
                                // удержание пробела без движения — выбор клавиатуры
                                val lpAt = if (cSettings.spaceLongAction != "OFF") SystemClock.uptimeMillis() + 550L else Long.MAX_VALUE
                                while (true) {
                                    val waiting = !swiped && !picker && lpAt != Long.MAX_VALUE
                                    val ev = if (waiting) {
                                        val left = lpAt - SystemClock.uptimeMillis()
                                        if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                    } else awaitPointerEvent()
                                    if (ev == null) {
                                        if (rolled) continue
                                        picker = true
                                        Rollover.done(holder)
                                        act.keyTick()
                                        act.spaceHold()
                                        continue
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == id } ?: break
                                    if (!ch.pressed) {
                                        if (!swiped && !picker && !rolled) act.commitKey(k, " ")
                                        break
                                    }
                                    if (picker || rolled) continue
                                    val dx = ch.position.x - down.position.x
                                    when (mode) {
                                        "LANGUAGE" -> if (!swiped && abs(dx) > swipe) {
                                            swiped = true
                                            Rollover.done(holder)
                                            act.swipeSpace(if (dx > 0) 1 else -1)
                                        }
                                        "CURSOR" -> {
                                            if (!swiped && abs(dx) > slop) {
                                                swiped = true
                                                Rollover.done(holder)
                                                lastX = ch.position.x
                                            }
                                            if (swiped) {
                                                while (ch.position.x - lastX > cursorStep) {
                                                    lastX += cursorStep
                                                    act.swipeSpace(1)
                                                }
                                                while (lastX - ch.position.x > cursorStep) {
                                                    lastX -= cursorStep
                                                    act.swipeSpace(-1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                val vars = cVariants
                                val lbl = cLabel
                                val out = cOutput
                                val letterKey = k.type == KeyType.CHARACTER
                                val glideOn = letterKey && cGlide && lbl.length == 1 && lbl[0].isLetter()
                                // мгновенный ввод: буква/цифра уходит в поле в момент касания
                                val instant = letterKey && cSettings.instantInput && out.length in 1..2 && out.all { it.isLetterOrDigit() }
                                val upSymbol = if (letterKey && cSettings.swipeUpHint) cUpHint else null
                                val a0 = anchor()
                                // точка касания внутри клавиши (-0.5…0.5) — автоисправление учитывает, куда попал палец
                                var touchX = (down.position.x - inset.x) / iw - 0.5f
                                var touchY = (down.position.y - inset.y) / ih - 0.5f
                                // динамические зоны: у границы клавиш — буква, с которой слово вероятнее продолжится
                                var tapOut = out
                                var shownLabel = lbl
                                var shownAnchor = a0
                                if (letterKey && cSettings.dynamicZones && out == lbl && lbl.length == 1 && lbl[0].isLetter()) {
                                    val o0 = origin()
                                    val lc = layer.coords
                                    val hit = if (o0 != null && lc != null) DynamicZones.resolve(lbl[0].lowercaseChar(), o0 + down.position, glide, lc) else null
                                    if (hit != null) {
                                        tapOut = if (lbl[0].isUpperCase()) hit.label.uppercase() else hit.label.toString()
                                        shownLabel = tapOut
                                        shownAnchor = Offset(hit.center.x, hit.center.y - ih / 2f)
                                        touchX = hit.touch.dx
                                        touchY = hit.touch.dy
                                    }
                                }
                                if (letterKey && cSettings.keyPreview && shownAnchor != null) {
                                    popup.show(PopupState(holder, shownAnchor.x, shownAnchor.y, shownLabel, if (shownLabel == lbl) vars else listOf(shownLabel), 0, false))
                                }
                                if (instant) {
                                    act.keyTouch(touchX, touchY)
                                    act.commitKey(k, tapOut)
                                }
                                var rolled = false
                                Rollover.press(holder) {
                                    if (!rolled) {
                                        rolled = true
                                        if (!instant) {
                                            act.keyTouch(touchX, touchY)
                                            act.commitKey(k, tapOut)
                                        }
                                        releaseEarly()
                                    }
                                }
                                val glideStart = max(slop * 1.5f, iw * 0.75f)
                                val upDist = ih * 0.45f
                                // удержание кнопки языка может делать своё действие (настройка), а не открывать список языков
                                val globeAction = k.type == KeyType.GLOBE && cSettings.globeLongPress != "LANGUAGES"
                                val holdable = vars.size > 1 || (vars.size == 1 && vars[0] != out)
                                val holdAt = if ((holdable && !globeAction) || (globeAction && cSettings.globeLongPress != "OFF")) {
                                    SystemClock.uptimeMillis() + cSettings.longPressMs.toLong()
                                } else Long.MAX_VALUE
                                var gesture = KeyGesture.CANCELED
                                var last = down.position
                                // куда «перепрыгнул» палец: на старых экранах отпускание пальца иногда теряется, и
                                // следующее нажатие приходит тем же касанием — скачком через клавиатуру. Это два
                                // нажатия, а не росчерк: раньше из него угадывалось слово, и «При» становилось
                                // «При Привет»
                                var jumpTo: Offset? = null
                                while (true) {
                                    val ev = if (holdAt == Long.MAX_VALUE) awaitPointerEvent() else {
                                        val left = holdAt - SystemClock.uptimeMillis()
                                        if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                    }
                                    if (ev == null) {
                                        gesture = KeyGesture.HOLD
                                        break
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == id } ?: break
                                    val d = ch.position - down.position
                                    val prev = last
                                    last = ch.position
                                    if (letterKey && !rolled && ch.pressed && (prev - down.position).getDistance() < glideStart) {
                                        var from = prev
                                        var jump = 0f
                                        for (h in ch.historical) {
                                            jump = max(jump, (h.position - from).getDistance())
                                            from = h.position
                                        }
                                        jump = max(jump, (ch.position - from).getDistance())
                                        if (jump > iw * 1.3f) {
                                            jumpTo = ch.position
                                            gesture = KeyGesture.RELEASED
                                            break
                                        }
                                    }
                                    // клавишу уже «перекатили» — она сработала, ждём только отпускания
                                    if (rolled) {
                                        if (!ch.pressed) break
                                        continue
                                    }
                                    if (!ch.pressed) {
                                        gesture = if (upSymbol != null && d.y < -upDist && abs(d.x) < abs(d.y)) KeyGesture.SWIPE_UP else KeyGesture.RELEASED
                                        break
                                    }
                                    if (glideOn && d.getDistance() > glideStart) {
                                        gesture = KeyGesture.GLIDE
                                        break
                                    }
                                    if (!glideOn && !globeAction && vars.size > 1 && abs(d.x) > slop && abs(d.x) > -d.y) {
                                        gesture = KeyGesture.SLIDE
                                        break
                                    }
                                }
                                Rollover.done(holder)
                                if (rolled) {
                                    // удержание после «перекатывания» не открывает варианты — ждём отпускания
                                    if (gesture == KeyGesture.HOLD) while (true) {
                                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                        if (!ch.pressed) break
                                    }
                                    gesture = KeyGesture.CANCELED
                                }
                                when (gesture) {
                                    KeyGesture.RELEASED -> {
                                        if (!instant) {
                                            act.keyTouch(touchX, touchY)
                                            act.commitKey(k, tapOut)
                                        }
                                        jumpTo?.let { p ->
                                            popup.clear(holder)
                                            val o = origin()
                                            val next = if (o != null) KeyboardGeometry.charKeyNear(o + p) else null
                                            if (next != null) {
                                                act.keyDown(next.key)
                                                act.keyTouch(0f, 0f)
                                                act.commitKey(next.key, next.key.text(cShift == ShiftState.CAPS_LOCK))
                                                next.pulse()
                                            }
                                            // второй палец ещё на экране — его движения и отпускание ничего не вводят
                                            while (true) {
                                                val c2 = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                                if (!c2.pressed) break
                                            }
                                        }
                                    }
                                    KeyGesture.CANCELED -> Unit
                                    KeyGesture.SWIPE_UP -> {
                                        val sym = upSymbol ?: tapOut
                                        act.keyTick()
                                        if (instant) act.replaceLast(tapOut, sym) else act.commitKey(k, sym)
                                    }
                                    KeyGesture.HOLD, KeyGesture.SLIDE -> if (globeAction) {
                                        act.keyTick()
                                        act.globeHold()
                                        // дождаться отпускания, чтобы оно не сработало как нажатие
                                        while (true) {
                                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                            if (!ch.pressed) break
                                        }
                                    } else {
                                        val def = vars.size / 2
                                        var sel = def
                                        val a = anchor() ?: a0
                                        if (a != null) popup.show(PopupState(holder, a.x, a.y, lbl, vars, sel, true))
                                        act.keyTick()
                                        // выбор — по ячейке под пальцем: так достаются все варианты, даже когда ряд
                                        // упёрся в край экрана и стоит не над клавишей
                                        val row = a?.let { an -> layer.coords?.let { popupRow(vars.size, an.x, it.size.width.toFloat(), density) } }
                                        var moved = false
                                        while (true) {
                                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                            if (!ch.pressed) {
                                                val chosen = vars.getOrElse(sel) { out }
                                                if (instant) {
                                                    if (chosen != tapOut) act.replaceLast(tapOut, chosen)
                                                } else {
                                                    act.commitKey(if (k.type == KeyType.CHARACTER || k.type == KeyType.GLOBE) k else VARIANT_KEY, chosen)
                                                }
                                                break
                                            }
                                            if (!moved && abs(ch.position.x - down.position.x) > slop / 2f) moved = true
                                            val o = origin()
                                            val idx = if (row != null && o != null) {
                                                if (!moved) sel else ((o.x + ch.position.x - row.first) / row.second).toInt().coerceIn(0, vars.lastIndex)
                                            } else (def + ((ch.position.x - down.position.x) / step).roundToInt()).coerceIn(0, vars.lastIndex)
                                            if (idx != sel) {
                                                sel = idx
                                                popup.select(holder, idx)
                                                act.keyTick()
                                            }
                                        }
                                    }
                                    KeyGesture.GLIDE -> {
                                        popup.clear(holder)
                                        // буква мгновенного ввода остаётся, пока не ясно, что это слово свайпом:
                                        // раньше она стиралась в начале свайпа — буквы пропадали и появлялись
                                        val o = origin() ?: Offset.Zero
                                        glide.trail.clear()
                                        glide.trail.add(o + down.position)
                                        glide.trail.add(o + last)
                                        act.keyTick()
                                        var released = false
                                        while (true) {
                                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                            // промежуточные точки между кадрами: без них быстрый росчерк срезал углы
                                            for (h in ch.historical) glide.trail.add(o + h.position)
                                            glide.trail.add(o + ch.position)
                                            if (!ch.pressed) {
                                                released = true
                                                break
                                            }
                                            ch.consume()
                                        }
                                        val pts = glide.trail.toList()
                                        glide.trail.clear()
                                        if (released && pts.size >= 2) {
                                            val net = pts.last() - pts.first()
                                            var len = 0f
                                            for (i in 1 until pts.size) len += (pts[i] - pts[i - 1]).getDistance()
                                            if (upSymbol != null && net.y < -upDist && abs(net.x) < abs(net.y) * 0.7f && len < iw * 2.2f) {
                                                if (instant) act.replaceLast(tapOut, upSymbol) else act.commitKey(k, upSymbol)
                                            } else if (len < iw * 1.25f) {
                                                // короткий мазок при быстром наборе — это нажатие, а не слово
                                                if (!instant) {
                                                    act.keyTouch(touchX, touchY)
                                                    act.commitKey(k, tapOut)
                                                }
                                            } else {
                                                val snap = layer.coords?.let { glide.snapshot(it) }
                                                if (snap != null) act.commitGlide(pts, snap.first, snap.second, if (instant) tapOut else null)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        Rollover.done(holder)
                        pressed = false
                        popup.clear(holder)
                        if (k.type == KeyType.CHARACTER) glide.trail.clear()
                        act.keyUp()
                        val pa = cPressAnim
                        if (cSettings.pressAnimation <= 0f || (cSettings.ecoMode && PowerState.saver)) scope.launch { anim.snapTo(0f) }
                        else scope.launch { anim.animateTo(0f, spring(dampingRatio = pa.damping, stiffness = pa.stiffness * cSpeed2)) }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
    Box(
        modifier = Modifier.fillMaxSize()
            .padding(horizontal = gapX / 2, vertical = gapY / 2)
            .onGloballyPositioned { holder.coords = it }
            .graphicsLayer { Anims.apply(this, pressAnim, anim.value.coerceIn(-0.6f, 1.3f), press) }
            // тени и свечение — снаружи кэшируемого слоя (иначе обрежутся по краю клавиши)
            .drawBehind {
                val st = style ?: return@drawBehind
                val m = min(size.width, size.height)
                var rest = fr.first * m
                if (isChar) rest = min(rest, capPx)
                drawSkinKey(st, colors, drawP, rest, fr.second * m, skinCache, backdrop, SKIN_SHADOW) { keyPosIn(holder, backdrop) }
            }
            // сама клавиша оформления — в отдельном слое-текстуре: неизменившиеся клавиши не перерисовываются каждый кадр
            .then(if (style != null) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
            .drawBehind {
                val p = drawP
                val m = min(size.width, size.height)
                var rest = fr.first * m
                if (isChar) rest = min(rest, capPx)
                val st = style
                if (st != null) {
                    drawSkinKey(st, colors, p, rest, fr.second * m, skinCache, backdrop, SKIN_BODY) { keyPosIn(holder, backdrop) }
                } else {
                    val r = lerpF(rest, fr.second * m, p)
                    val cr = CornerRadius(r, r)
                    drawRoundRect(color = lerp(colors.bg, colors.activeBg, p), cornerRadius = cr)
                    if (border) drawRoundRect(color = borderColor, cornerRadius = cr, style = Stroke(1.dp.toPx()))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val st = style
        val fg = pressedLabelColor(st, colors, pressed)
        val scale = st?.fontScale ?: 1f
        val custom = st?.label
        val dens = LocalDensity.current.density
        val textStyle = remember(st, dens) {
            TextStyle(
                fontStyle = if (st?.italic == true) FontStyle.Italic else null,
                // жёсткая тень без размытия (Minecraft) или мягкое свечение
                shadow = st?.textShadow?.takeIf { it.alpha > 0.01f }?.let { Shadow(it, Offset(1.6f * dens, 1.6f * dens), 0f) }
                    ?: st?.textGlow?.let { Shadow(it, Offset.Zero, 18f) }
            )
        }
        val w = st?.fontWeight?.let { FontWeight(it) } ?: weight
        // буквы из точек, как на точечной матрице: текст рисуется в слой и вырезается сеткой точек
        val labelFx = if (st?.labelEffect == LabelEffect.DOTS) Modifier.dotMatrix() else Modifier
        when {
            custom != null && key.type != KeyType.CHARACTER -> Text(
                custom, color = fg, fontFamily = font, fontWeight = w, fontSize = (16f * scale).sp, maxLines = 1,
                softWrap = false, style = textStyle, modifier = labelFx
            )
            key.type == KeyType.SHIFT -> if (shift == ShiftState.CAPS_LOCK) {
                SlotIcon("caps", fg, (22f * scale).dp) { GlyphIcon(Glyph.CAPS, fg, (22f * scale).dp) }
            } else SlotIcon("shift", fg, (22f * scale).dp) { GlyphIcon(Glyph.SHIFT, fg, (22f * scale).dp) }
            key.type == KeyType.BACKSPACE -> SlotIcon("backspace", fg, (22f * scale).dp) { GlyphIcon(Glyph.BACKSPACE, fg, (22f * scale).dp) }
            key.type == KeyType.GLOBE -> SlotIcon("globe", fg, (24f * scale).dp) { GlyphIcon(Glyph.GLOBE, fg, (24f * scale).dp) }
            key.type == KeyType.STICKERS -> SlotIcon("emoji_key", fg, (26f * scale).dp) { GlyphIcon(Glyph.EMOJI, fg, (26f * scale).dp) }
            key.type == KeyType.ENTER -> when (enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> SlotIcon("search", fg, (26f * scale).dp) { Icon(Icons.Rounded.Search, null, tint = fg, modifier = Modifier.size((26f * scale).dp)) }
                EditorInfo.IME_ACTION_SEND -> SlotIcon("send", fg, (24f * scale).dp) { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = fg, modifier = Modifier.size((24f * scale).dp)) }
                EditorInfo.IME_ACTION_DONE -> SlotIcon("done", fg, (28f * scale).dp) { Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size((28f * scale).dp)) }
                ENTER_NEWLINE -> SlotIcon("enter", fg, (24f * scale).dp) { GlyphIcon(Glyph.RETURN, fg, (24f * scale).dp) }
                else -> SlotIcon("go", fg, (24f * scale).dp) { GlyphIcon(Glyph.ARROW, fg, (24f * scale).dp) }
            }
            key.type == KeyType.SPACE -> Text(
                label, color = fg.copy(alpha = 0.72f), fontSize = (13f * scale).sp, fontWeight = w,
                fontFamily = font, maxLines = 1, style = textStyle, modifier = labelFx
            )
            key.type == KeyType.SPACER -> Unit
            else -> Text(
                text = if (custom != null && !upper) custom else if (custom != null) custom.uppercase() else label,
                color = fg,
                fontFamily = font,
                fontWeight = w,
                fontSize = when {
                    key.type == KeyType.MODE_SYMBOLS || key.type == KeyType.SYMBOLS_PAGE -> (15f * scale).sp
                    numberRow -> (17f * settings.keyLabelScale * scale).sp
                    else -> ((settings.keyHeightDp * 0.4f).coerceIn(14f, 25f) * settings.keyLabelScale * scale).sp
                },
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = textStyle,
                modifier = labelFx
            )
        }
        if (hint != null) {
            Text(
                hint,
                // в самом углу клавиши, как в Gboard: подальше от буквы, чтобы не сливался с ней
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp),
                color = fg.copy(alpha = 0.5f),
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                maxLines = 1
            )
        }
    }
    }
}

/**
 * Цвет подписи клавиши. Нажатая клавиша темы со своим фоном нажатия сохраняет свой цвет текста: раньше подпись
 * перекрашивалась в «текст на акценте» (обычно белый), а фон нажатия у многих тем светлый — буква на время
 * нажатия пропадала (Liquid Glass, Классика, Неоморфизм).
 */
internal fun pressedLabelColor(st: KeyStyle?, colors: KeyColors, pressed: Boolean): Color = when {
    !pressed -> st?.text ?: colors.fg
    st == null -> colors.activeFg
    st.pressedText != null -> st.pressedText
    // фон нажатия задан темой — текст прежний; у контура и «без фона» подсветка полупрозрачная — тоже прежний;
    // иначе клавиша заливается акцентом, текст под него
    st.pressedFill != null || st.material == SkinMaterial.OUTLINE || st.material == SkinMaterial.NONE -> st.text ?: colors.fg
    else -> colors.activeFg
}

/** Подпись из точек: содержимое рисуется в отдельный слой и остаётся только там, где точки сетки. */
internal fun Modifier.dotMatrix(cell: Dp = 2.3.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(PatternTiles.dotMask(cell.toPx()), blendMode = BlendMode.DstIn)
    }

/** Слой всплывающих «печенек»: один Canvas, без shadow/border, не перехватывает касания. */
@Composable
internal fun BoxScope.PopupLayer(popup: PopupController, palette: KeyPalette, font: FontFamily, spec: WavySpec, animStyle: String = "POP", speed: Float = 1f) {
    // один измеритель текста на все подсказки: не создаётся заново на каждое нажатие
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val s = popup.state ?: return
    key(s.owner) { PopupBubbles(s, palette, font, spec, animStyle, speed, measurer) }
}

@Composable
internal fun BoxScope.PopupBubbles(
    s: PopupState, palette: KeyPalette, font: FontFamily, spec: WavySpec, animStyle: String = "POP", speed: Float = 1f,
    measurer: TextMeasurer = rememberTextMeasurer()
) {
    val skinPopup = LocalSkin.current?.popup
    val glass = skinPopup?.material == SkinMaterial.GLASS
    val baseFill = skinPopup?.fill?.primary ?: if (glass) Color.White.copy(alpha = 0.2f) else palette.accentSoft
    val selFill = skinPopup?.pressedFill?.primary ?: palette.accent
    val baseText = skinPopup?.text ?: palette.onAccentSoft
    val selText = skinPopup?.pressedText ?: palette.onAccent
    val boxRadius = skinPopup?.radius
    val popupBorder = skinPopup?.border?.primary
    val style = remember(font) { TextStyle(fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp) }
    val items = if (s.expanded) s.variants else listOf(s.primary)
    val layouts = remember(items, style) { items.map { measurer.measure(AnnotatedString(it), style = style) } }
    val selPos by animateFloatAsState(
        if (s.expanded) s.selected.toFloat() else 0f,
        spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "popupSel"
    )
    // появление: пружина, из точки, выезд снизу, проявление или сразу
    val appear = remember { Animatable(if (animStyle == "NONE") 1f else if (animStyle == "ZOOM") 0.1f else if (animStyle == "POP") 0.5f else 0f) }
    LaunchedEffect(Unit) {
        val k = speed * speed
        when (animStyle) {
            "NONE" -> Unit
            "ZOOM" -> appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 1300f * k))
            "SLIDE", "FADE" -> appear.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1600f * k))
            else -> appear.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 1500f * k))
        }
    }
    val path = remember { Path() }
    val neck = remember { Path() }
    val keyWidth = ((s.owner as? CoordsHolder)?.coords?.size?.width ?: 0).toFloat()
    Canvas(Modifier.matchParentSize().graphicsLayer {
        when (animStyle) {
            "SLIDE" -> {
                translationY = (1f - appear.value) * 14.dp.toPx()
                alpha = appear.value.coerceIn(0f, 1f)
            }
            "FADE" -> alpha = appear.value.coerceIn(0f, 1f)
        }
    }) {
        val n = layouts.size
        val (rowLeft, slot) = popupRow(n, s.anchorX, size.width, density)
        // много вариантов — ячейки уже, и кружки уменьшаются вместе с ними
        val fit = (slot / 50.dp.toPx()).coerceAtMost(1f)
        val baseR = 22.dp.toPx() * fit
        val selR = 29.dp.toPx() * fit
        val ring = 2.5.dp.toPx()
        val margin = 4.dp.toPx()
        val width = if (n == 1) selR * 2f else n * slot
        val left = if (n == 1) (s.anchorX - width / 2f).coerceIn(margin, max(margin, size.width - width - margin)) else rowLeft
        val baseCy = max(s.anchorTop - 4.dp.toPx() - selR, selR + margin)
        val emph = { i: Int -> if (n == 1) 1f else (1f - abs(i - selPos)).coerceIn(0f, 1f) }
        val sc = if (animStyle == "SLIDE" || animStyle == "FADE") 1f else appear.value
        // жидкое стекло: подсказка «вытекает» из клавиши каплей — перемычка с вогнутыми боками
        if (glass && n == 1 && keyWidth > 0f) {
            val r = selR * sc
            val cx = left + width / 2f
            val top = baseCy - 4.dp.toPx() + r * 0.55f
            val bottom = s.anchorTop + 8.dp.toPx()
            if (bottom > top) {
                val hwTop = r * 0.62f
                val hwBot = keyWidth * 0.36f
                val mid = (top + bottom) / 2f
                neck.reset()
                neck.moveTo(cx - hwTop, top)
                neck.cubicTo(cx - hwTop * 0.45f, mid, s.anchorX - hwBot * 0.55f, mid, s.anchorX - hwBot, bottom)
                neck.lineTo(s.anchorX + hwBot, bottom)
                neck.cubicTo(s.anchorX + hwBot * 0.55f, mid, cx + hwTop * 0.45f, mid, cx + hwTop, top)
                neck.close()
                drawPath(neck, baseFill)
                drawPath(neck, Brush.horizontalGradient(
                    0f to Color.White.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.White.copy(alpha = 0.25f),
                    startX = cx - hwTop, endX = cx + hwTop
                ))
            }
        }
        for (i in (0 until n).sortedBy { emph(it) }) {
            val e = emph(i)
            val r = lerpF(baseR, selR, e) * sc
            val cx = if (n == 1) left + width / 2f else left + slot * (i + 0.5f)
            val cy = baseCy - e * 4.dp.toPx()
            if (boxRadius != null) {
                path.reset()
                val hw = r * 0.82f
                val rr = min(boxRadius.dp.toPx(), hw)
                path.addRoundRect(RoundRect(Rect(cx - hw, cy - r, cx + hw, cy + r), CornerRadius(rr, rr)))
            } else buildWavyPath(spec, cx, cy, r, path)
            if (!glass) drawPath(path, palette.background, style = Stroke(ring * 2f))
            if (boxRadius != null && !glass) {
                // мягкая тень под прямоугольной подсказкой
                translate(top = 1.5.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.18f)) }
            }
            drawPath(path, lerp(baseFill, selFill, e))
            if (glass) drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(1.2.dp.toPx()))
            else if (popupBorder != null) drawPath(path, popupBorder, style = Stroke(1.2.dp.toPx()))
            val lay = layouts[i]
            scale(scale = r / selR, pivot = Offset(cx, cy)) {
                drawText(
                    lay,
                    color = lerp(baseText, selText, e),
                    topLeft = Offset(cx - lay.size.width / 2f, cy - lay.size.height / 2f)
                )
            }
        }
    }
}

/** Фон чипа подсказок/панели инструментов: обычный цвет или стиль "chips" из оформления. */
internal fun Modifier.chipBackground(style: KeyStyle?, fallback: Color, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val clipped = this.clip(shape)
    if (style == null) return clipped.background(fallback)
    if (style.material == SkinMaterial.NONE && style.fill == null) return clipped
    return clipped.drawBehind {
        val rect = Rect(Offset.Zero, size)
        when {
            style.material == SkinMaterial.GLASS -> drawGlassChip(rect, min(size.height / 2f, 22.dp.toPx()), style.glass?.tint ?: style.fill?.primary)
            style.fill != null -> drawRect(style.fill.brush(rect))
            else -> drawRect(fallback)
        }
        val bw = style.borderWidth ?: 0f
        if (bw > 0f && style.border != null) {
            val r = min(size.height / 2f, 22.dp.toPx())
            drawRoundRect(style.border.brush(rect), cornerRadius = CornerRadius(r, r), style = Stroke(bw.dp.toPx()))
        }
    }
}

@Composable
internal fun CookieButton(
    spec: WavySpec,
    color: Color,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "cookieScale")
    val rot by animateFloatAsState(if (pressed) 25f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cookieRot")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .wavyBackground(spec, color) { rot }
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Контур по верху клавиатуры: линия вдоль края и скруглений, к бокам гаснет. */
internal fun DrawScope.drawTopEdge(color: Color, cornerPx: Float) {
    val w = size.width
    val stroke = 1.dp.toPx()
    val half = stroke / 2f
    val r = cornerPx.coerceIn(0f, min(w / 2f, size.height))
    val tail = r + 18.dp.toPx()
    val path = Path().apply {
        moveTo(half, min(size.height, tail))
        lineTo(half, r)
        if (r > 0f) arcTo(Rect(half, half, 2 * r - half, 2 * r - half), 180f, 90f, false)
        lineTo(w - r, half)
        if (r > 0f) arcTo(Rect(w - 2 * r + half, half, w - half, 2 * r - half), 270f, 90f, false)
        lineTo(w - half, min(size.height, tail))
    }
    val brush = Brush.verticalGradient(0f to color, (r / tail).coerceIn(0f, 1f) to color.copy(alpha = color.alpha * 0.6f), 1f to Color.Transparent, endY = tail)
    drawPath(path, brush, style = Stroke(stroke))
}

/** Окно клавиатуры больше самой клавиатуры — размытие окна вышло бы за её края. */
object WindowBlur {
    var overflow by mutableStateOf(false)
}

/** Система разрешает размывать то, что под окном (Android 12+, не в режиме энергосбережения). */
@Composable
internal fun rememberWindowBlurSupported(): Boolean {
    if (Build.VERSION.SDK_INT < 31) return false
    val context = LocalContext.current
    val wm = remember { context.getSystemService(android.view.WindowManager::class.java) }
    var enabled by remember { mutableStateOf(wm?.isCrossWindowBlurEnabled == true) }
    DisposableEffect(wm) {
        val listener = java.util.function.Consumer<Boolean> { enabled = it }
        runCatching { wm?.addCrossWindowBlurEnabledListener(listener) }
        onDispose { runCatching { wm?.removeCrossWindowBlurEnabledListener(listener) } }
    }
    return enabled && !WindowBlur.overflow
}

/** Подложка панели (эмодзи, буфер, голос…) в стиле оформления; без оформления — обычная тональная. */
internal fun Modifier.panelSurface(palette: KeyPalette, radius: Dp = 24.dp): Modifier = composed {
    val st = LocalSkin.current?.panel
    if (st == null) return@composed this.clip(RoundedCornerShape(radius)).background(palette.panel)
    val backdrop = LocalBackdrop.current
    val holder = remember { CoordsHolder() }
    val cache = remember { SkinKeyCache() }
    val base = remember(palette) { KeyColors(palette.panel, palette.onLetter, palette.panel, palette.onLetter) }
    val r = st.radius?.dp ?: radius
    this.onGloballyPositioned { holder.coords = it }
        .drawBehind { drawSkinKey(st, base, 0f, r.toPx(), r.toPx(), cache, backdrop) { keyPosIn(holder, backdrop) } }
        .clip(RoundedCornerShape(r))
}

/** Круглая подложка большой кнопки (микрофон) в стиле кнопок оформления; active — залита акцентом. */
internal fun Modifier.skinButtonSurface(style: KeyStyle, palette: KeyPalette, active: Boolean): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val holder = remember { CoordsHolder() }
    val cache = remember { SkinKeyCache() }
    val st = remember(style, active, palette) {
        val m = style.material ?: SkinMaterial.SOLID
        style.copy(
            shape = SkinShape.PILL,
            // «только значок» для большой кнопки выглядит пусто — даём тональную подложку
            material = if (m == SkinMaterial.NONE || (active && m == SkinMaterial.OUTLINE)) SkinMaterial.SOLID else m,
            fill = when {
                active -> SkinFill.Solid(if (m == SkinMaterial.GLASS) palette.accent.copy(alpha = 0.8f) else palette.accent)
                m == SkinMaterial.NONE -> SkinFill.Solid(palette.func)
                else -> style.fill
            }
        )
    }
    val base = remember(palette) { KeyColors(palette.func, palette.onFunc, palette.accent, palette.onAccent) }
    this.onGloballyPositioned { holder.coords = it }
        .drawBehind { drawSkinKey(st, base, 0f, size.minDimension / 2f, size.minDimension / 2f, cache, backdrop) { keyPosIn(holder, backdrop) } }
}

/** Круглая кнопка строки подсказок в стиле оформления: стекло, контур, неоморфизм или просто значок. */
@Composable
internal fun SkinButton(
    style: KeyStyle,
    palette: KeyPalette,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val p by animateFloatAsState(if (down) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "skinButton")
    val holder = remember { CoordsHolder() }
    val cache = remember { SkinKeyCache() }
    val backdrop = LocalBackdrop.current
    val st = remember(style) { style.copy(shape = style.shape ?: SkinShape.PILL) }
    val base = remember(palette) { KeyColors(palette.func, palette.onFunc, palette.accentSoft, palette.onAccentSoft) }
    Box(
        modifier = Modifier
            .size(size)
            .padding(3.dp)
            .onGloballyPositioned { holder.coords = it }
            .graphicsLayer {
                val s = 1f - 0.08f * p
                scaleX = s
                scaleY = s
            }
            .drawBehind { drawSkinKey(st, base, p, size.toPx() / 2f, size.toPx() / 2f, cache, backdrop) { keyPosIn(holder, backdrop) } }
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Что сейчас в строке над клавиатурой. */
internal enum class HeaderMode { TOOLS, CANDIDATES, INLINE, OFFER, SUGGESTIONS }

@Composable
internal fun HeaderBar(
    state: KeyboardState,
    actions: KeyboardActions,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    spec: WavySpec
) {
    val panel = state.panel
    val candidates = state.candidates
    val showCandidates = candidates.isNotEmpty() && (panel == ActivePanel.KEYBOARD)
    // пароли и логины от службы автозаполнения — пока ничего не набрано; потом уступают подсказкам слов
    val inline = state.inlineSuggestions.takeIf {
        it.isNotEmpty() && panel == ActivePanel.KEYBOARD && !state.toolbarOpen && !showCandidates &&
            state.suggestions.none { c -> !c.isEmpty && !c.isUndo && c.isKeepExact && c.isPrimary }
    }
    // только что скопированное — предложить вставить, даже если подсказки выключены
    val offer = state.clipOffer.takeIf { inline == null && panel == ActivePanel.KEYBOARD && !state.toolbarOpen && !showCandidates }
    // пустое поле — меню функций вместо подсказок
    // в переводчике — подсказки для набираемого там слова
    val translating = panel == ActivePanel.TRANSLATE && settings.showSuggestions
    val tools = !showCandidates && offer == null && inline == null &&
        (state.toolbarOpen || (!translating && (panel != ActivePanel.KEYBOARD || !settings.showSuggestions || (state.fieldEmpty && !state.toolsDismissed))))
    val button = if (settings.landscape) 38.dp else 46.dp
    val skinButtons = LocalSkin.current?.buttons
    val btnFg = skinButtons?.let { it.text ?: palette.onFunc } ?: palette.onAccent
    val headerButton: @Composable (() -> Unit, @Composable BoxScope.() -> Unit) -> Unit = { onClick, content ->
        if (skinButtons != null) SkinButton(skinButtons, palette, button, onClick, content = content)
        else CookieButton(spec, palette.accent, button, onClick, content = content)
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(if (settings.landscape) 40.dp else 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // кнопку панели функций можно убрать; «назад» в панелях остаётся всегда
        if (panel != ActivePanel.KEYBOARD || settings.toolsButton) headerButton({ actions.headerMenu() }) {
            if (panel != ActivePanel.KEYBOARD) {
                SlotIcon("back", btnFg, 22.dp) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = btnFg, modifier = Modifier.size(22.dp)) }
            } else {
                SlotIcon("menu", btnFg, 22.dp) { GlyphIcon(Glyph.GRID, btnFg, 22.dp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val mode = when {
                tools -> HeaderMode.TOOLS
                showCandidates -> HeaderMode.CANDIDATES
                inline != null -> HeaderMode.INLINE
                offer != null -> HeaderMode.OFFER
                else -> HeaderMode.SUGGESTIONS
            }
            // каждое состояние рисует только своё: раньше уходящая строка на время перехода показывала то же,
            // что и новая, — две копии поверх друг друга мелькали
            val content: @Composable (HeaderMode) -> Unit = { m ->
                when (m) {
                    HeaderMode.TOOLS -> ToolbarPill(state, actions, settings, palette)
                    HeaderMode.CANDIDATES -> CandidateStrip(candidates, actions, palette, font)
                    HeaderMode.INLINE -> inline?.let { InlineSuggestionStrip(it) }
                    HeaderMode.OFFER -> state.clipOffer?.let { ClipOfferStrip(it, actions, palette, font) }
                    HeaderMode.SUGGESTIONS -> SuggestionStrip(state, actions, palette, font)
                }
            }
            if (settings.panelAnimations && mode != HeaderMode.INLINE) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        // окно только появилось — без анимации; иначе короткое проявление
                        if (state.instantUi) EnterTransition.None togetherWith ExitTransition.None
                        else fadeIn(tween(120)) togetherWith fadeOut(tween(70))
                    },
                    label = "headerSwap"
                ) { m -> content(m) }
            } else content(mode)
        }
        if (settings.showMicButton) {
            // удержание — Rambler: диктовка без спешки, ИИ убирает «э-э», повторы и оговорки
            val onMic = { actions.togglePanel(ActivePanel.VOICE) }
            val onRambler = { actions.rambler() }
            val mic: @Composable BoxScope.() -> Unit = {
                SlotIcon("mic", btnFg, 22.dp) { Icon(Icons.Outlined.Mic, null, tint = btnFg, modifier = Modifier.size(22.dp)) }
            }
            if (skinButtons != null) SkinButton(skinButtons, palette, button, onMic, onRambler, mic)
            else CookieButton(spec, palette.accent, button, onMic, onRambler, mic)
        }
    }
}

@Composable
internal fun SuggestionStrip(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val chips = state.suggestions
    val skin = LocalSkin.current
    if (skin?.plainChips == true) {
        val plainFg = skin.chips?.text ?: palette.onLetter
        PlainSuggestionStrip(chips, actions, plainFg, font, skin.chips?.pressedText ?: plainFg)
        return
    }
    val chipStyle = skin?.chips
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val chip = chips.getOrNull(i) ?: SuggestionChipModel.EMPTY
            val primary = i == 1
            val fg = chipStyle?.let { if (primary) it.pressedText ?: it.text else it.text } ?: if (primary) palette.onAccentSoft else palette.onFunc
            val interaction = remember { MutableInteractionSource() }
            val down by interaction.collectIsPressedAsState()
            val corner by animateDpAsState(if (down) 10.dp else 20.dp, spring(dampingRatio = 0.6f, stiffness = 800f), label = "chipCorner")
            val chipFill = chipStyle?.let { if (primary && it.pressedFill != null) it.copy(fill = it.pressedFill) else it }
            Box(
                modifier = Modifier
                    .weight(if (primary) 1.15f else 1f)
                    .height(40.dp)
                    .chipBackground(chipFill, if (primary) palette.accentSoft else palette.func, RoundedCornerShape(corner))
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = !chip.isEmpty,
                        // долгое нажатие — забыть выученное слово
                        onLongClick = { actions.forgetSuggestion(chip.commitWord) }
                    ) { actions.pickSuggestion(chip) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (chip.isEmpty) "…" else chip.displayText,
                    color = if (chip.isEmpty) fg.copy(alpha = 0.5f) else fg,
                    fontFamily = font,
                    fontSize = 15.sp,
                    fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Улучшение текста в стиле Material You: клавиши мягко притушены, в центре — тональная плашка
 * с морфящимся индикатором (как LoadingIndicator из Material 3 Expressive). Готово — индикатор
 * сменяется галочкой, плашка ненадолго задерживается и уходит.
 */
@Composable
internal fun BoxScope.AiGlowOverlay(busy: Boolean, done: Int, palette: KeyPalette, font: FontFamily?) {
    var doneShown by remember { mutableStateOf(false) }
    LaunchedEffect(done) {
        if (done == 0) return@LaunchedEffect
        doneShown = true
        delay(900)
        doneShown = false
    }
    val visible = busy || doneShown
    val scrim by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 250 else 400), label = "aiScrim")
    if (scrim > 0.001f) Box(Modifier.matchParentSize().background(palette.background.copy(alpha = 0.55f * scrim)))
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.Center),
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f),
        exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.92f)
    ) {
        Row(
            Modifier
                .shadow(6.dp, RoundedCornerShape(50), clip = false)
                .clip(RoundedCornerShape(50))
                .background(palette.accentSoft)
                .padding(start = 14.dp, end = 22.dp, top = 12.dp, bottom = 12.dp)
                .animateContentSize(spring(stiffness = Spring.StiffnessMedium)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Crossfade(doneShown && !busy, label = "aiState") { ok ->
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    if (ok) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = palette.onAccent, modifier = Modifier.size(20.dp))
                        }
                    } else MorphIndicator(palette.accent, Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                tr(if (doneShown && !busy) "Готово" else "Улучшаю текст…"),
                color = palette.onAccentSoft, fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1
            )
        }
    }
}

/** Индикатор загрузки: «печенька», которая вращается и перетекает между 4 и 8 лепестками. */
@Composable
private fun MorphIndicator(color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "morph")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val m by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "m")
    val path = remember { Path() }
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.86f
        path.reset()
        val n = 96
        for (k in 0..n) {
            val th = k * 2f * PI.toFloat() / n
            val lobes = (1f - m) * 0.11f * cos(4f * th) + m * 0.07f * cos(8f * th)
            val rr = r * (0.9f + lobes) * (0.92f + 0.08f * m)
            val x = c.x + rr * cos(th)
            val y = c.y + rr * sin(th)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        rotate(spin, c) { drawPath(path, color) }
    }
}

/** Пароли и логины от службы автозаполнения: система сама рисует чипы, мы их только расставляем. */
@Composable
internal fun InlineSuggestionStrip(views: List<android.view.View>) {
    // подсказки автозаполнения рисует система на отдельной поверхности поверх окна: обрезка Compose на неё
    // не действует, и при прокрутке они наезжали на кнопки меню и микрофона. Поэтому без прокрутки:
    // показываем только те, что целиком помещаются, остальные уводим за пределы экрана
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    androidx.compose.ui.layout.Layout(
        content = {
            for (v in views) {
                key(v) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { (v.parent as? android.view.ViewGroup)?.removeView(v); v },
                        modifier = Modifier.wrapContentSize()
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(40.dp).clipToBounds()
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            var x = 0
            var hidden = false
            for (p in placeables) {
                val y = (constraints.maxHeight - p.height) / 2
                if (!hidden && x + p.width <= constraints.maxWidth) {
                    p.place(x, y)
                    x += p.width + gap
                } else {
                    hidden = true
                    p.place(OFFSCREEN_PX, y)
                }
            }
        }
    }
}

/** Куда уводить подсказки, которые не поместились: за любой экран. */
private const val OFFSCREEN_PX = 100_000

/** Панель редактирования: курсор, выделение, копировать и вставить. Стрелки повторяются при удержании. */
@Composable
internal fun EditBar(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette) {
    val tint = LocalSkin.current?.buttons?.text ?: palette.onFunc
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditButton(Icons.Rounded.FirstPage, "home", tint, palette, actions)
        EditButton(Icons.Rounded.SelectAll, "select_all", tint, palette, actions)
        EditButton(Icons.Rounded.KeyboardArrowUp, "up", tint, palette, actions, repeat = true)
        EditButton(Icons.Rounded.KeyboardArrowLeft, "left", tint, palette, actions, repeat = true)
        EditButton(Icons.Rounded.TextFields, "select", tint, palette, actions, selected = state.selectMode)
        EditButton(Icons.Rounded.KeyboardArrowRight, "right", tint, palette, actions, repeat = true)
        EditButton(Icons.Rounded.KeyboardArrowDown, "down", tint, palette, actions, repeat = true)
        EditButton(Icons.Rounded.ContentCopy, "copy", tint, palette, actions)
        EditButton(Icons.Rounded.ContentPaste, "paste", tint, palette, actions)
        EditButton(Icons.Rounded.LastPage, "end", tint, palette, actions)
    }
}

@Composable
private fun EditButton(
    icon: ImageVector,
    id: String,
    tint: Color,
    palette: KeyPalette,
    actions: KeyboardActions,
    repeat: Boolean = false,
    selected: Boolean = false
) {
    val cActions by rememberUpdatedState(actions)
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.size(width = 36.dp, height = 36.dp).clip(CircleShape)
            .background(if (selected) palette.accent else if (down) tint.copy(alpha = 0.14f) else Color.Transparent)
            .pointerInput(id, repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true
                    cActions.editKey(id)
                    try {
                        if (repeat) {
                            // удержание стрелки — курсор едет дальше, всё быстрее
                            var delayMs = 380L
                            while (true) {
                                val up = withTimeoutOrNull(delayMs) { waitForUpOrCancellation() }
                                if (up != null) break
                                if (currentEvent.changes.none { it.pressed }) break
                                cActions.editKey(id)
                                delayMs = 60L
                            }
                        } else waitForUpOrCancellation()
                    } finally {
                        down = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (selected) palette.onAccent else tint, modifier = Modifier.size(22.dp))
    }
}

/** Только что скопированное: нажатие вставляет, ✕ убирает. Как в Gboard. */
@Composable
internal fun ClipOfferStrip(entry: String, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val image = ClipImages.isImage(entry)
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f, fill = false).height(38.dp).clip(RoundedCornerShape(19.dp)).background(palette.accentSoft)
                .clickable { actions.pasteClipOffer() }.padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (image) {
                ClipThumb(entry, Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)), palette.func, maxPx = 96)
            } else {
                Box(Modifier.size(28.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ContentPaste, null, tint = palette.onAccent, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                if (image) tr("Вставить картинку") else entry.replace('\n', ' '),
                color = palette.onAccentSoft, fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable { actions.dismissClipOffer() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Close, tr("Скрыть"), tint = palette.onFunc.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
    }
}

/** Подсказки как в Gboard и iOS: три слова через тонкие разделители, главное — жирное по центру. */
@Composable
internal fun PlainSuggestionStrip(chips: List<SuggestionChipModel>, actions: KeyboardActions, fg: Color, font: FontFamily, primaryFg: Color = fg) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val chip = chips.getOrNull(i) ?: SuggestionChipModel.EMPTY
            val primary = i == 1
            if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(fg.copy(alpha = 0.18f)))
            val interaction = remember { MutableInteractionSource() }
            val down by interaction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (down) fg.copy(alpha = 0.1f) else Color.Transparent)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = !chip.isEmpty,
                        onLongClick = { actions.forgetSuggestion(chip.commitWord) }
                    ) { actions.pickSuggestion(chip) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!chip.isEmpty) {
                    Text(
                        text = chip.displayText,
                        color = if (primary) primaryFg else fg.copy(alpha = 0.82f),
                        fontFamily = font,
                        fontSize = 16.sp,
                        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Варианты китайского и японского ввода: прокручиваемая лента, первый вариант — главный (его вставит пробел). */
@Composable
internal fun CandidateStrip(candidates: List<String>, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val listState = rememberLazyListState()
    val plain = LocalSkin.current?.plainChips == true
    LaunchedEffect(candidates) { listState.scrollToItem(0) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(if (plain) Color.Transparent else palette.func),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        itemsIndexed(candidates) { i, c ->
            val first = i == 0
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (first) palette.accentSoft else Color.Transparent)
                    .combinedClickable(onLongClick = { actions.forgetSuggestion(c) }) { actions.pickCandidate(i) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    c, color = if (first) palette.onAccentSoft else palette.onFunc, fontFamily = font,
                    fontSize = 18.sp, fontWeight = if (first) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

/** Кнопка панели инструментов: встроенная, закреплённое действие плагина или кнопка скрипта. */
@Immutable
data class ToolDef(val id: String, val title: String, val slot: String?, val icon: ImageVector?, val spec: String?)

object Toolbar {
    /** Все кнопки в порядке по умолчанию. Читает состояние плагинов — вызывать из композиции или главного потока. */
    fun catalog(): List<ToolDef> = buildList {
        add(ToolDef("plugins", "Плагины", "plugins", Icons.Rounded.Extension, null))
        for (p in PluginRepository.active) {
            p.actions.forEachIndexed { i, a ->
                if (a.pinned) add(ToolDef("pin:${p.id}#$i", a.title, if (a.icon == "@layout_swap") "layout_swap" else null, null, a.icon))
            }
        }
        for (b in ScriptHost.buttons) if (b.toolbar) add(ToolDef("btn:${b.pluginId}:${b.id}", b.title, null, null, b.icon))
        add(ToolDef("translate", "Переводчик", "translate", Icons.Rounded.Translate, null))
        add(ToolDef("ai_fix", "Исправить текст", "ai_fix", Icons.Rounded.AutoFixHigh, null))
        add(ToolDef("gif", "GIF", "gif", Icons.Rounded.GifBox, null))
        add(ToolDef("clipboard", "Буфер обмена", "clipboard", Icons.Rounded.ContentPaste, null))
        add(ToolDef("emoji", "Эмодзи", "emoji", Icons.Rounded.EmojiEmotions, null))
        add(ToolDef("one_handed", "Одна рука", "one_handed", Icons.Rounded.SwapHoriz, null))
        add(ToolDef("incognito", "Инкогнито", "incognito", Icons.Rounded.VisibilityOff, null))
        add(ToolDef("settings", "Настройки", "settings", Icons.Rounded.Settings, null))
        add(ToolDef("hide", "Скрыть клавиатуру", "hide", Icons.Rounded.KeyboardHide, null))
    }

    /** Сохранённый порядок; новые кнопки встают рядом со своими соседями по умолчанию. */
    fun arrange(available: List<String>, order: List<String>, hidden: Set<String>, includeHidden: Boolean = false): List<String> {
        val result = ArrayList<String>()
        for (id in order) if (id in available && id !in result) result += id
        available.forEachIndexed { i, id ->
            if (id in result) return@forEachIndexed
            val prev = available.subList(0, i).lastOrNull { it in result }
            result.add(if (prev == null) 0 else result.indexOf(prev) + 1, id)
        }
        return if (includeHidden) result else result.filter { it !in hidden }
    }

    /** Видимые кнопки в новом порядке; скрытые остаются на своих местах в конце списка. */
    fun save(context: Context, visible: List<String>, hidden: Set<String>) {
        val all = visible + catalog().map { it.id }.filter { it in hidden && it !in visible }
        KeyboardPrefs.edit(context) { putString("toolbar_order", all.joinToString(",")) }
    }

    fun setHidden(context: Context, hidden: Set<String>) =
        KeyboardPrefs.edit(context) { putString("toolbar_hidden", hidden.joinToString(",")) }
}

/** Долгое нажатие на кнопку и перетаскивание меняет порядок; порядок также настраивается в настройках. */
@Composable
internal fun ToolbarPill(state: KeyboardState, actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette) {
    val context = LocalContext.current
    val chipStyle = LocalSkin.current?.chips
    val fg = chipStyle?.text ?: palette.onFunc
    val catalog = Toolbar.catalog()
    val byId = remember(catalog) { catalog.associateBy { it.id } }
    val ids = remember(catalog, settings.toolbarOrder, settings.toolbarHidden) {
        Toolbar.arrange(catalog.map { it.id }, settings.toolbarOrderList, settings.toolbarHiddenSet)
    }
    var order by remember(ids) { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    // отпускание после долгого нажатия/перетаскивания не считается нажатием
    var dragEndedAt by remember { mutableLongStateOf(0L) }
    val step = with(LocalDensity.current) { 42.dp.toPx() }
    val hidden = settings.toolbarHiddenSet

    fun click(id: String) {
        if (dragging != null || SystemClock.uptimeMillis() - dragEndedAt < 400L) return
        when {
            id == "plugins" -> actions.togglePanel(ActivePanel.PLUGINS)
            id == "translate" -> actions.togglePanel(ActivePanel.TRANSLATE)
            id == "ai_fix" -> actions.togglePanel(ActivePanel.AI_WRITE)
            id == "gif" -> actions.togglePanel(ActivePanel.GIF)
            id == "clipboard" -> actions.togglePanel(ActivePanel.CLIPBOARD)
            id == "emoji" -> actions.togglePanel(ActivePanel.EMOJI)
            id == "one_handed" -> KeyboardPrefs.edit(context) { putString("one_handed", if (settings.oneHanded == "OFF") "RIGHT" else "OFF") }
            id == "incognito" -> {
                KeyboardPrefs.edit(context) { putBoolean("incognito", !settings.incognito) }
                Toast.makeText(context, tr(if (settings.incognito) "Инкогнито выключено" else "Инкогнито: слова и буфер не запоминаются"), Toast.LENGTH_SHORT).show()
            }
            id == "settings" -> actions.openSettings()
            id == "hide" -> actions.hideKeyboard()
            id.startsWith("pin:") -> {
                val rest = id.removePrefix("pin:")
                actions.runPluginAction(rest.substringBeforeLast('#'), rest.substringAfterLast('#').toIntOrNull() ?: return)
            }
            id.startsWith("btn:") -> {
                val rest = id.removePrefix("btn:")
                actions.runScriptButton(rest.substringBefore(':'), rest.substringAfter(':'))
            }
        }
    }

    // жест — на всём ряду: координаты ряда не меняются, когда кнопки меняются местами
    val gapPx = with(LocalDensity.current) { 2.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp).chipBackground(chipStyle, palette.func, CircleShape)) {
    val minWidth = maxWidth
    Row(
        // кнопок мало — они стоят по центру; много — ряд прокручивается
        modifier = Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()).widthIn(min = minWidth).padding(horizontal = 4.dp)
            .pointerInput(ids) {
                detectDragGesturesAfterLongPress(
                    onDragStart = start@{ at ->
                        val used = order.size * step - gapPx
                        val offset = max(0f, (size.width - used) / 2f)
                        val i = ((at.x - offset) / step).toInt()
                        dragging = order.getOrNull(i) ?: return@start
                        dragDx = 0f
                        actions.keyTick()
                    },
                    onDrag = drag@{ change, amount ->
                        val id = dragging ?: return@drag
                        change.consume()
                        dragDx += amount.x
                        val cur = order.indexOf(id)
                        if (dragDx > step / 2f && cur in 0 until order.lastIndex) {
                            order = order.toMutableList().also { java.util.Collections.swap(it, cur, cur + 1) }
                            dragDx -= step
                            actions.keyTick()
                        } else if (dragDx < -step / 2f && cur > 0) {
                            order = order.toMutableList().also { java.util.Collections.swap(it, cur, cur - 1) }
                            dragDx += step
                            actions.keyTick()
                        }
                    },
                    onDragEnd = {
                        if (dragging != null) Toolbar.save(context, order, hidden)
                        dragging = null
                        dragDx = 0f
                        dragEndedAt = SystemClock.uptimeMillis()
                    },
                    onDragCancel = {
                        dragging = null
                        dragDx = 0f
                        dragEndedAt = SystemClock.uptimeMillis()
                    }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (id in order) {
            val def = byId[id] ?: continue
            key(id) {
                val selected = when (id) {
                    "plugins" -> state.panel == ActivePanel.PLUGINS
                    "translate" -> state.panel == ActivePanel.TRANSLATE
                    "ai_fix" -> state.aiBusy || state.panel == ActivePanel.AI_WRITE || state.panel == ActivePanel.AI_PROMPT
                    "gif" -> state.panel == ActivePanel.GIF || state.panel == ActivePanel.GIF_SEARCH
                    "clipboard" -> state.panel == ActivePanel.CLIPBOARD
                    "emoji" -> state.panel == ActivePanel.EMOJI || state.panel == ActivePanel.EMOJI_SEARCH
                    "one_handed" -> settings.oneHanded != "OFF"
                    "incognito" -> settings.incognito
                    else -> false
                }
                val isDrag = dragging == id
                Box(
                    Modifier
                        .zIndex(if (isDrag) 1f else 0f)
                        .graphicsLayer {
                            if (isDrag) {
                                translationX = dragDx
                                scaleX = 1.18f
                                scaleY = 1.18f
                            }
                        }
                ) {
                    ToolButton(def, selected || isDrag, palette, fg) { click(id) }
                }
            }
        }
    }
    }
}

@Composable
internal fun ToolButton(def: ToolDef, selected: Boolean, palette: KeyPalette, tint: Color, onClick: () -> Unit) {
    val corner by animateDpAsState(if (selected) 12.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "toolCorner")
    val color = if (selected) palette.onAccent else tint
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 36.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (selected) palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val slot = def.slot
        when {
            slot != null && def.icon != null -> SlotIcon(slot, color, 20.dp) { Icon(def.icon, def.title, tint = color, modifier = Modifier.size(20.dp)) }
            def.spec != null -> SpecIcon(def.spec, color, 20.dp)
            def.icon != null -> Icon(def.icon, def.title, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector, selected: Boolean, palette: KeyPalette, tint: Color = palette.onFunc, onClick: () -> Unit) {
    val corner by animateDpAsState(if (selected) 12.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "toolCorner")
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 36.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (selected) palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (selected) palette.onAccent else tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun ToolText(text: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 40.dp, height = 36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tint, fontSize = if (text.length > 2) 11.sp else 17.sp, maxLines = 1, fontWeight = FontWeight.Bold)
    }
}

/** onBackground(цвет, прозрачная навигация) — фон под системной панелью навигации. */
@Composable
fun KeyboardRoot(
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    onBackground: (Color, Boolean) -> Unit,
    onWindowBlur: (Int, Color, Float) -> Unit = { _, _, _ -> }
) {
    val raw by KeyboardPrefs.observe()
    val systemDark = isSystemInDarkTheme()
    val dark = when (raw.themeMode) {
        "DARK", "AMOLED" -> true
        "LIGHT" -> false
        else -> systemDark
    }
    val activePlugins = PluginRepository.active
    val scriptSkin = ScriptHost.skinOverride
    // тема с её настройками и в акцентном цвете пользователя; пересчёт — только когда что-то из этого меняется
    val skin = remember(activePlugins, scriptSkin, raw.skinPlugin, raw.skinOptions, raw.userAccent, raw.colorAccentText, raw.keyOpacity, dark) {
        PluginRepository.resolvedSkin(raw, dark)
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val settings = remember(raw, skin, landscape, configuration.screenHeightDp) {
        val s = skin?.applyColors(raw) ?: raw
        if (landscape) s.forLandscape(configuration.screenHeightDp) else s
    }
    ExpressiveTheme(settings) {
        val palette = rememberKeyPalette(settings)
        val baseFont = LocalKeyFont.current
        val font = remember(baseFont, skin) { skin?.fontFamily(baseFont) ?: baseFont }
        val image = if (settings.hasBackgroundImage) rememberBackgroundBitmap(settings.bgImageVersion) else null
        val density = LocalDensity.current
        SideEffect { PatternTiles.density = density.density }
        val backdrop = remember { Backdrop() }
        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        // настоящее стекло: окно клавиатуры размывает приложение под собой (Android 12+, если система разрешает)
        val blurSupported = rememberWindowBlurSupported()
        // размытие: как в оформлении, или включено/выключено для любой темы
        val wantBlur = when (settings.windowBlur) {
            "ON" -> settings.windowBlurDp
            "OFF" -> null
            else -> skin?.windowBlur?.let { if (raw.windowBlurDp != 32f) raw.windowBlurDp else it }
        }
        val blurDp = wantBlur?.takeIf { it > 0f && blurSupported && image == null }
        // дымка поверх размытия: у темы своя, иначе — полупрозрачный фон клавиатуры
        val blurTint0 = skin?.blurTint ?: palette.background.copy(alpha = if (palette.background.luminance() < 0.5f) 0.62f else 0.55f)
        // прозрачный фон: сквозь клавиатуру видно приложение, остаются только клавиши
        val bgAlpha = 1f - settings.bgTransparency
        val blurTint = blurTint0.copy(alpha = blurTint0.alpha * bgAlpha)
        val decorated = image != null || skin?.hasBackground == true || blurDp != null || bgAlpha < 0.99f
        LaunchedEffect(palette.background, decorated) { onBackground(palette.background, decorated) }
        // без верхней панели скруглять верх фона нечем: первый ряд клавиш стоял бы впритык
        // к вырезу, и углы выглядели обрезанными — делаем верх листа прямым
        val corner = if (settings.showHeader) skin?.corner ?: 28f else 0f
        LaunchedEffect(blurDp, blurTint, corner) {
            onWindowBlur(if (blurDp == null) 0 else with(density) { blurDp.dp.roundToPx() }, blurTint, corner)
        }
        // подложку для стекла считаем в фоне и только когда оформление стеклянное (при размытии окна она не нужна)
        LaunchedEffect(skin, image, palette.background, settings.bgDim, rootSize, blurDp) {
            if (skin == null || !skin.usesGlass || blurDp != null || rootSize.width == 0 || rootSize.height == 0) {
                backdrop.bitmap = null
                return@LaunchedEffect
            }
            val (bmp, s) = withContext(Dispatchers.Default) {
                renderBackdrop(rootSize.width, rootSize.height, density, skin, palette.background, image, if (image != null) settings.bgDim else 0f)
            }
            backdrop.scale = s
            backdrop.bitmap = bmp
        }
        CompositionLocalProvider(LocalSkin provides skin, LocalBackdrop provides backdrop) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = corner.dp, topEnd = corner.dp),
                // при размытии окна фон рисует само окно (дымка поверх размытия)
                color = if (blurDp != null) Color.Transparent else palette.background.copy(alpha = palette.background.alpha * bgAlpha)
            ) {
                Box(
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        backdrop.coords = it
                        if (it.size != rootSize) rootSize = it.size
                    }
                ) {
                    if (skin != null && skin.hasBackground && blurDp == null && bgAlpha > 0.01f) {
                        Canvas(Modifier.matchParentSize().graphicsLayer { alpha = bgAlpha }) { drawSkinBackground(skin, size) }
                    }
                    if (image != null && bgAlpha > 0.01f) {
                        val blur = settings.bgBlurDp
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = bgAlpha,
                            modifier = Modifier.matchParentSize()
                                .then(if (blur > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur.dp) else Modifier)
                        )
                        if (settings.bgDim > 0f) Box(Modifier.matchParentSize().background(palette.background.copy(alpha = settings.bgDim * bgAlpha)))
                    }
                    // фон — до самых краёв экрана, клавиши — в стороне от выреза камеры, боковой панели навигации
                    // и скруглённых углов экрана; отступы одинаковые с обеих сторон, иначе в альбомной одна
                    // половина упиралась в край, а у другой был пустой вырез
                    val cut = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                    val side = with(density) {
                        val l = cut.getLeft(this, LayoutDirection.Ltr)
                        val r = cut.getRight(this, LayoutDirection.Ltr)
                        max(max(l, r).toDp(), if (settings.landscape) 20.dp else 0.dp)
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                            .padding(horizontal = side)
                            .padding(bottom = settings.bottomOffsetDp.dp)
                    ) {
                        KeyboardScreen(state, actions, voice, settings, palette, font)
                    }
                    // ободок стеклянного листа: светлая линия по верхнему краю, по скруглениям уходит в ничто
                    skin?.edge?.let { edge ->
                        Canvas(Modifier.matchParentSize()) { drawTopEdge(edge, corner.dp.toPx()) }
                    }
                    // улучшение текста: плашка с индикатором поверх клавиатуры
                    AiGlowOverlay(state.aiBusy, state.aiDone, palette, font)
                }
            }
        }
    }
}

@Composable
internal fun KeyboardScreen(
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily
) {
    val popup = remember { PopupController() }
    val glide = remember { GlideController() }
    val layer = remember { CoordsHolder() }
    val spec = remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent) { settings.accentSpec() }
    val panel = state.panel
    val numbers = settings.showNumberRow && state.symbolsPage == 0
    val bodyHeight = (settings.keyHeightDp * (if (numbers) 4.78f else 4f) + settings.rowSpacingDp * (if (numbers) 4f else 3f)).dp
    val trailColor = LocalSkin.current?.trail ?: palette.accent.copy(alpha = 0.85f)
    val density = LocalDensity.current
    SideEffect {
        KeyboardGeometry.layer = layer
        KeyboardGeometry.density = density
        KeyboardGeometry.actions = actions
        KeyboardGeometry.palette = palette
    }
    DisposableEffect(layer) {
        onDispose { if (KeyboardGeometry.layer === layer) KeyboardGeometry.layer = null }
    }
    val gap = if (settings.landscape) 4.dp else 8.dp

    Box(Modifier.fillMaxWidth().onGloballyPositioned { layer.coords = it }) {
        // слой мода под клавишами — первым, чтобы клавиши легли поверх
        ScriptBackgroundLayer()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = settings.sidePaddingDp.dp, vertical = gap),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            if (settings.editBar && !settings.landscape) EditBar(state, actions, palette)
            // верхнюю панель можно убрать совсем; в панелях она остаётся ради кнопки «назад»,
            // а панель инструментов показывается, если её открыли вручную
            if (settings.showHeader || panel != ActivePanel.KEYBOARD || state.toolbarOpen) {
                HeaderBar(state, actions, settings, palette, font, spec)
            }
            if (panel == ActivePanel.TRANSLATE) TranslatorBar(state, actions, settings, palette, font)
            if (panel == ActivePanel.EMOJI_SEARCH) EmojiSearchBar(state, actions, settings, palette, font)
            if (panel == ActivePanel.GIF_SEARCH) GifSearchBar(state, actions, palette, font)
            if (panel == ActivePanel.AI_WRITE || panel == ActivePanel.AI_PROMPT) AiWriteBar(state, actions, palette, font)
            if (panel == ActivePanel.SCRIPT_INPUT) ScriptInputBar(actions, palette, font)
            Box(Modifier.fillMaxWidth().height(bodyHeight)) {
                val body = if (panel == ActivePanel.TRANSLATE || panel == ActivePanel.EMOJI_SEARCH || panel == ActivePanel.GIF_SEARCH ||
                    panel == ActivePanel.AI_WRITE || panel == ActivePanel.AI_PROMPT || panel == ActivePanel.SCRIPT_INPUT) {
                    ActivePanel.KEYBOARD
                } else panel
                if (settings.panelAnimations && settings.animPanel != "NONE") {
                    val k = settings.animSpeed
                    val style = settings.animPanel
                    AnimatedContent(
                        targetState = body,
                        transitionSpec = {
                            val fadeMs = (160 / k).toInt()
                            // панель сменилась, пока клавиатура была скрыта: при появлении — сразу, без проявления
                            when {
                                state.instantUi -> EnterTransition.None togetherWith ExitTransition.None
                                style == "SLIDE" -> (slideInVertically(spring(dampingRatio = 0.85f, stiffness = 600f * k * k)) { it / 5 } + fadeIn(tween(fadeMs))) togetherWith
                                    (slideOutVertically(spring(dampingRatio = 1f, stiffness = 900f * k * k)) { -it / 8 } + fadeOut(tween((100 / k).toInt())))
                                style == "FADE" -> fadeIn(tween(fadeMs)) togetherWith fadeOut(tween((110 / k).toInt()))
                                else -> (fadeIn(tween(fadeMs)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = 500f * k * k), initialScale = 0.94f)) togetherWith
                                    fadeOut(tween((100 / k).toInt()))
                            }
                        },
                        label = "body"
                    ) { target -> PanelBody(target, state, actions, voice, settings, palette, font, spec, popup, layer, glide) }
                } else {
                    PanelBody(body, state, actions, voice, settings, palette, font, spec, popup, layer, glide)
                }
            }
        }
        GlideTrailLayer(glide, trailColor, settings.glideTrail)
        ScriptOverlayLayer()
        PopupLayer(popup, palette, font, spec, settings.animPopup, settings.animSpeed)
        ScriptFrameTicker()
    }
}

@Composable
internal fun PanelBody(
    target: ActivePanel,
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    spec: WavySpec,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController
) {
    when (target) {
        ActivePanel.EMOJI -> EmojiPanel(actions, settings, palette, font)
        ActivePanel.GIF -> GifPanel(state, actions, settings, palette, font)
        ActivePanel.CLIPBOARD -> ClipboardPanel(state, actions, palette, font)
        ActivePanel.VOICE -> VoicePanel(state, actions, voice, palette, font, spec)
        ActivePanel.PLUGINS -> PluginsPanel(state, actions, palette, font)
        else -> KeyArea(state, actions, settings, palette, font, popup, layer, glide)
    }
}

/** Режим одной руки: клавиатура сжимается к краю, на свободном месте — кнопки «перенести» и «развернуть». */
@Composable
internal fun KeyArea(
    state: KeyboardState,
    actions: KeyboardActions,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController
) {
    val mode = settings.oneHanded
    if (mode != "LEFT" && mode != "RIGHT") {
        KeyGrid(state, actions, settings, palette, font, popup, layer, glide)
        return
    }
    val context = LocalContext.current
    val w = settings.oneHandedWidth
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode == "RIGHT") OneHandRail(Modifier.weight(1f - w), palette, mode, context)
        Box(Modifier.weight(w).fillMaxHeight()) { KeyGrid(state, actions, settings, palette, font, popup, layer, glide) }
        if (mode == "LEFT") OneHandRail(Modifier.weight(1f - w), palette, mode, context)
    }
}

@Composable
internal fun OneHandRail(modifier: Modifier, palette: KeyPalette, mode: String, context: Context) {
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(palette.func)
                .clickable { KeyboardPrefs.edit(context) { putString("one_handed", if (mode == "LEFT") "RIGHT" else "LEFT") } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.SwapHoriz, "Перенести", tint = palette.onFunc) }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(palette.accentSoft)
                .clickable { KeyboardPrefs.edit(context) { putString("one_handed", "OFF") } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.OpenInFull, "Развернуть", tint = palette.onAccentSoft) }
    }
}

@Composable
internal fun KeyGrid(
    state: KeyboardState,
    actions: KeyboardActions,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController
) {
    // своя раскладка языка из настроек (тот же язык — другие ряды)
    val lang = remember(state.language, settings.langLayouts) {
        Languages.applyUserLayouts(settings.langLayouts)
        Languages.withUser(state.language)
    }
    val page = state.symbolsPage
    val shift = state.shift
    val enter = state.enterAction
    val pluginLayouts = PluginRepository.layouts
    val scriptLayouts = ScriptHost.layoutOverrides
    val langLabels = remember(settings.languages, Languages.extra) { Languages.enabled(settings).map { it.label } }
    val split = settings.landscape && settings.landscapeSplit
    val baseRows = remember(
        lang, lang.rows, page, settings.showNumberRow, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
        settings.spaceLeftKey, settings.spaceRightKey, langLabels, pluginLayouts, scriptLayouts, settings.userLayouts
    ) {
        KeyboardLayouts.rows(
            lang, page, settings.showNumberRow, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
            langLabels, settings.spaceLeftKey, settings.spaceRightKey, settings.userLayoutMap
        )
    }
    val rows = remember(baseRows, split) { if (split) KeyboardLayouts.split(baseRows) else baseRows }
    // удержание как в Gboard: символ с того же места на странице «?123»
    val holdSymbols = remember(lang, page, settings.holdMode, baseRows, pluginLayouts, settings.userLayouts) {
        if (settings.holdMode != "SYMBOLS" || page != 0) emptyMap()
        else KeyboardLayouts.holdSymbols(baseRows, KeyboardLayouts.rows(lang, 1, false, user = settings.userLayoutMap))
    }
    val upper = shift != ShiftState.OFF && page == 0
    val glideEnabled = settings.glideTyping && state.glideAllowed && page == 0 && lang.composer == Composer.NONE
    val dense = page == 0 && rows.any { r -> r.keys.count { it.type == KeyType.CHARACTER } >= 11 }
    val hSpace = (if (dense) min(settings.keySpacingDp, 4f) else settings.keySpacingDp).dp

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(settings.rowSpacingDp.dp)) {
        rows.forEach { row ->
            val isNumberRow = row.heightWeight < 1f
            Row(Modifier.fillMaxWidth().weight(row.heightWeight), horizontalArrangement = Arrangement.spacedBy(hSpace)) {
                for (key in row.keys) {
                    if (key.type == KeyType.SPACER) {
                        Spacer(Modifier.weight(key.weight))
                        continue
                    }
                    val isChar = key.type == KeyType.CHARACTER
                    KeyView(
                        key = key,
                        label = when {
                            key.type == KeyType.SPACE && settings.incognito -> "🕶 ${key.label}"
                            key.type == KeyType.GLOBE -> lang.label
                            isChar -> key.shownLabel(upper)
                            else -> key.label
                        },
                        upper = upper && isChar,
                        shift = if (key.type == KeyType.SHIFT) shift else ShiftState.OFF,
                        enterAction = if (key.type == KeyType.ENTER) enter else 0,
                        settings = settings,
                        palette = palette,
                        font = font,
                        popup = popup,
                        layer = layer,
                        glide = glide,
                        actions = actions,
                        glideEnabled = glideEnabled,
                        numberRow = isNumberRow,
                        modifier = Modifier.weight(key.weight).fillMaxHeight(),
                        holdSymbol = if (isChar) holdSymbols[key] else null,
                        gapX = hSpace,
                        gapY = settings.rowSpacingDp.dp
                    )
                }
            }
        }
    }
}
