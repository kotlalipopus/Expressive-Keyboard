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
// ПОДЛОЖКИ: ЧИПЫ, КНОПКИ-ПЕЧЕНЬКИ, РАЗМЫТИЕ, ПАНЕЛИ
// ============================================================================

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
    val scope = rememberCoroutineScope()
    DisposableEffect(wm) {
        var off: Job? = null
        val listener = java.util.function.Consumer<Boolean> { on ->
            off?.cancel()
            if (on) enabled = true
            else {
                // система на миг выключает размытие (смена приложения, анимации, нагрев) — клавиатура не должна
                // из-за этого мигать прозрачным фоном: выключаем, только если запрет держится дольше
                off = scope.launch { delay(800L); enabled = false }
            }
        }
        runCatching { wm?.addCrossWindowBlurEnabledListener(listener) }
        onDispose { off?.cancel(); runCatching { wm?.removeCrossWindowBlurEnabledListener(listener) } }
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
