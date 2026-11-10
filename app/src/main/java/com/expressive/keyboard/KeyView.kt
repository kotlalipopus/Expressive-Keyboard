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
// КЛАВИША И ВСПЛЫВАЮЩИЕ БУКВЫ
// ============================================================================

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
    // свои символы при удержании (настройка) важнее и режима «как в Gboard», и правила про цифры
    val userSet = remember(key.label, settings.keyVariants) { UserVariants.has(key.label) }
    val noDigits = !userSet && settings.showNumberRow && !numberRow && key.type == KeyType.CHARACTER && key.label.firstOrNull()?.isLetter() == true
    val hold = holdSymbol?.takeUnless { userSet || (noDigits && it.length == 1 && it[0].isDigit()) }
    val variants = remember(key, upper, pluginState, hold, noDigits, settings.keyVariants) {
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
    // без подложки плашки нет у обычных клавиш; Enter и включённый Shift остаются акцентными
    val plate = settings.keyBackground
    val accentKey = key.type == KeyType.ENTER || (key.type == KeyType.SHIFT && shift != ShiftState.OFF)
    val colors = remember(key.type, shift, palette, plate) {
        val c = keyColors(key.type, shift, palette)
        if (!plate && !accentKey) c.copy(bg = Color.Transparent) else c
    }
    val style = remember(skin, key.type, key.label, shift, colors, plate) {
        val st = skin?.styleFor(key.type, key.label)
        // включённый Shift заливается акцентом, иначе в стекле и контурах его состояние не видно
        val merged = if (st != null && key.type == KeyType.SHIFT && shift != ShiftState.OFF) {
            st.copy(
                fill = SkinFill.Solid(if (st.material == SkinMaterial.GLASS) colors.bg.copy(alpha = 0.85f) else colors.bg),
                text = colors.fg, textGlow = null
            )
        } else st
        if (merged != null && !plate && !accentKey) merged.withoutPlate() else merged
    }
    val skinCache = remember { SkinKeyCache() }
    // слияние с соседними клавишами: только у стекла, и только пока идёт анимация нажатия
    val merges = settings.glassMerge && style?.material == SkinMaterial.GLASS && Build.VERSION.SDK_INT >= 33
    if (merges) {
        LaunchedEffect(holder) { snapshotFlow { anim.value }.collect { GlassMerge.update(holder, it) } }
        DisposableEffect(holder) { onDispose { GlassMerge.remove(holder) } }
    }
    val isChar = key.type == KeyType.CHARACTER
    val upHint = if (isChar) remember(label, variants, hold, userSet) {
        hold ?: (if (userSet) UserVariants.primary(key.label) else null) ?: hintFor(label, variants)
    } else null
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
    // в энергосбережении pressAnimation = 0: только смена цвета
    val press = settings.pressAnimation
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
                    if (cSettings.pressAnimation <= 0f) scope.launch(start = CoroutineStart.UNDISPATCHED) { anim.snapTo(1f) }
                    else scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        anim.snapTo(max(anim.value, 0.6f))
                        anim.animateTo(1f, spring(dampingRatio = 1f, stiffness = 4000f * cSpeed2))
                    }
                    try {
                        when (k.type) {
                            KeyType.BACKSPACE -> {
                                // со свайпом по ⌫ первый символ стирается по отпусканию (или когда удержание
                                // переходит в автоповтор): иначе свайп сначала откусывал последнюю букву слова
                                val deferFirst = cSettings.backspaceSwipe
                                var deleted = !deferFirst
                                if (!deferFirst) act.commitKey(k, "")
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
                                                if (deleted) act.keyTick()
                                                act.commitKey(k, "")
                                                deleted = true
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
                                    else if (released && !deleted) act.commitKey(k, "")
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
                                var cursorMode = false
                                var langIntent = false
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
                                        // быстрый свайп — язык; задержал палец и повёл — курсор (без ожидания удержания
                                        // для смены языка: решает, сколько палец простоял до начала движения)
                                        "BOTH" -> {
                                            if (!swiped && !cursorMode && abs(dx) > slop &&
                                                ch.uptimeMillis - down.uptimeMillis >= BOTH_HOLD_MS && !langIntent
                                            ) {
                                                cursorMode = true
                                                swiped = true
                                                Rollover.done(holder)
                                                act.keyTick()
                                                lastX = ch.position.x
                                            } else if (!swiped && abs(dx) > slop) langIntent = true
                                            if (cursorMode) {
                                                while (ch.position.x - lastX > cursorStep) {
                                                    lastX += cursorStep
                                                    act.spaceCursor(1)
                                                }
                                                while (lastX - ch.position.x > cursorStep) {
                                                    lastX -= cursorStep
                                                    act.spaceCursor(-1)
                                                }
                                            } else if (!swiped && langIntent && abs(dx) > swipe) {
                                                swiped = true
                                                Rollover.done(holder)
                                                act.swipeSpace(if (dx > 0) 1 else -1)
                                            }
                                        }
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
                                                    act.spaceCursor(1)
                                                }
                                                while (lastX - ch.position.x > cursorStep) {
                                                    lastX -= cursorStep
                                                    act.spaceCursor(-1)
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
                                var lastT = down.uptimeMillis
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
                                    lastT = ch.uptimeMillis
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
                                                val x = o.x + ch.position.x
                                                val w = layer.coords?.size?.width?.toFloat() ?: Float.MAX_VALUE
                                                // у самого края экрана палец не дотягивается до крайней ячейки
                                                // (рамка экрана, жесты навигации): край — значит крайний вариант
                                                if (!moved) sel
                                                else if (x >= w - row.second * 0.6f) vars.lastIndex
                                                else if (x <= row.second * 0.6f) 0
                                                else ((x - row.first) / row.second).toInt().coerceIn(0, vars.lastIndex)
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
                                            lastT = ch.uptimeMillis
                                        }
                                        val pts = glide.trail.toList()
                                        glide.trail.clear()
                                        if (released && pts.size >= 2) {
                                            val net = pts.last() - pts.first()
                                            var len = 0f
                                            for (i in 1 until pts.size) len += (pts[i] - pts[i - 1]).getDistance()
                                            val snap = layer.coords?.let { glide.snapshot(it) }
                                            if (upSymbol != null && net.y < -upDist && abs(net.x) < abs(net.y) * 0.7f && len < iw * 2.2f) {
                                                if (instant) act.replaceLast(tapOut, upSymbol) else act.commitKey(k, upSymbol)
                                            } else if (snap == null || len < iw * 1.25f || !looksLikeGlide(pts, snap.first, iw, lastT - down.uptimeMillis)) {
                                                // короткий или слишком быстрый мазок при быстром наборе — это нажатие, а не слово:
                                                // раньше палец, чуть съехавший с «п» на «а», вставлял «пап» и «папа»
                                                if (!instant) {
                                                    act.keyTouch(touchX, touchY)
                                                    act.commitKey(k, tapOut)
                                                }
                                            } else {
                                                act.commitGlide(pts, snap.first, snap.second, if (instant) tapOut else null)
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
                        if (cSettings.pressAnimation <= 0f) scope.launch { anim.snapTo(0f) }
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
            .graphicsLayer {
                Anims.apply(this, pressAnim, anim.value.coerceIn(-0.6f, 1.3f), press)
                applyKeyFx(KeyboardGeometry.nameOf(key, label), key.type == KeyType.CHARACTER)
            }
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
        val labelFx = (if (st?.labelEffect == LabelEffect.DOTS) Modifier.dotMatrix() else Modifier)
            .then(if (ModFx.labelActive) Modifier.modLabel(KeyboardGeometry.nameOf(key, label), key.type == KeyType.CHARACTER) else Modifier)
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
                    // цифры верхнего ряда — того же размера, что буквы: раньше были заметно мельче,
                    // а «Размер букв» масштабировал их от другого основания
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
            // у скруглённой клавиши угол «съедает» подсказку: отступ растёт вместе со скруглением,
            // иначе «@» у правого верхнего края вылезает за плашку
            val hintPad = 2.dp + (settings.cornerRadiusDp * 0.25f).dp
            Text(
                hint,
                // в самом углу клавиши, как в Gboard: подальше от буквы, чтобы не сливался с ней
                modifier = Modifier.align(Alignment.TopEnd).padding(top = hintPad, end = hintPad),
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
internal fun BoxScope.PopupLayer(popup: PopupController, palette: KeyPalette, font: FontFamily, spec: WavySpec, animStyle: String = "POP", speed: Float = 1f, popupStyle: String = "SHAPE") {
    // один измеритель текста на все подсказки: не создаётся заново на каждое нажатие
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val s = popup.state ?: return
    key(s.owner) { PopupBubbles(s, palette, font, spec, animStyle, speed, measurer, popupStyle) }
}

@Composable
internal fun BoxScope.PopupBubbles(
    s: PopupState, palette: KeyPalette, font: FontFamily, spec: WavySpec, animStyle: String = "POP", speed: Float = 1f,
    measurer: TextMeasurer = rememberTextMeasurer(),
    popupStyle: String = "SHAPE"
) {
    val skinPopup = LocalSkin.current?.popup
    val glass = skinPopup?.material == SkinMaterial.GLASS
    val baseFill = skinPopup?.fill?.primary ?: if (glass) Color.White.copy(alpha = 0.2f) else palette.accentSoft
    val selFill = skinPopup?.pressedFill?.primary ?: palette.accent
    val baseText = skinPopup?.text ?: palette.onAccentSoft
    val selText = skinPopup?.pressedText ?: palette.onAccent
    // без волнистых форм всплывающая буква — скруглённый прямоугольник, как в M3
    val boxRadius = skinPopup?.radius ?: when {
        popupStyle == "BOX" || popupStyle == "PANEL" -> 14f
        popupStyle == "CIRCLE" -> null
        spec.profile == ShapeProfile.CIRCLE -> 16f
        // полукруг, стрелка и т.п. для буквы не годятся
        spec.profile == ShapeProfile.FIXED && spec.preset !in ExpressiveShapePreset.decorative -> 16f
        else -> null
    }
    val shapeSpec = if (popupStyle == "CIRCLE") WavySpec.Circle else spec
    // одна панель со всеми символами (как в Gboard) вместо отдельной фигуры на каждый
    val panel = popupStyle == "PANEL" && skinPopup?.radius == null
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
        if (panel && n > 1) {
            val h = selR * 2f
            val top = baseCy - selR
            val rr = 16.dp.toPx()
            path.reset()
            path.addRoundRect(RoundRect(Rect(left, top, left + width, top + h), CornerRadius(rr, rr)))
            if (!glass) {
                drawPath(path, palette.background, style = Stroke(ring * 2f))
                translate(top = 1.5.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.18f)) }
            }
            drawPath(path, baseFill)
            if (popupBorder != null) drawPath(path, popupBorder, style = Stroke(1.2.dp.toPx()))
            // выбранная ячейка — плашка, плавно переезжающая за пальцем
            val inset = 3.dp.toPx()
            val sx = left + slot * selPos
            path.reset()
            path.addRoundRect(RoundRect(Rect(sx + inset, top + inset, sx + slot - inset, top + h - inset), CornerRadius(rr - inset, rr - inset)))
            drawPath(path, selFill)
            for (i in 0 until n) {
                val e = emph(i)
                val cx = left + slot * (i + 0.5f)
                val lay = layouts[i]
                scale(scale = (fit * 0.85f).coerceAtMost(1f), pivot = Offset(cx, baseCy)) {
                    drawText(lay, color = lerp(baseText, selText, e), topLeft = Offset(cx - lay.size.width / 2f, baseCy - lay.size.height / 2f))
                }
            }
            return@Canvas
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
            } else buildWavyPath(shapeSpec, cx, cy, r, path)
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

/** Режим пробела «свайп — язык, удержание — курсор»: сколько палец должен простоять, чтобы двигать курсор. */
private const val BOTH_HOLD_MS = 250L
