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
// ВЕРХНЯЯ СТРОКА: ПОДСКАЗКИ, АВТОЗАПОЛНЕНИЕ, ПАНЕЛЬ ИНСТРУМЕНТОВ
// ============================================================================

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
        (state.toolbarOpen || (!translating && (panel != ActivePanel.KEYBOARD || !settings.showSuggestions || (state.fieldEmpty && !state.toolsDismissed && !settings.pinnedTools))))
    val headerH = (if (settings.landscape) settings.headerHeightDp - 8f else settings.headerHeightDp).dp
    val button = headerH - 2.dp
    val skinButtons = LocalSkin.current?.buttons
    val btnFg = skinButtons?.let { it.text ?: palette.onFunc } ?: palette.onAccent
    val headerButton: @Composable (() -> Unit, (() -> Unit)?, @Composable BoxScope.() -> Unit) -> Unit = { onClick, onLong, content ->
        if (skinButtons != null) SkinButton(skinButtons, palette, button, onClick, onLong, content)
        else CookieButton(spec, palette.accent, button, onClick, onLong, content)
    }
    val context = LocalContext.current
    // «подсказкам вся ширина»: пока в строке есть слова, кнопки меню и микрофона уходят
    val wordsShown = panel == ActivePanel.KEYBOARD && !tools && !showCandidates && inline == null && offer == null &&
        state.suggestions.any { !it.isEmpty }
    val sideHidden = settings.suggestionsFullWidth && wordsShown
    Row(
        modifier = Modifier.fillMaxWidth().height(headerH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (sideHidden) 0.dp else 6.dp)
    ) {
        // кнопку панели функций можно убрать; «назад» в панелях остаётся всегда
        if ((panel != ActivePanel.KEYBOARD || settings.toolsButton) && !sideHidden) headerButton({ actions.headerMenu() }, {
            // удержание на клавиатуре: по настройке — панель функций строкой над подсказками или панель редактирования
            if (panel == ActivePanel.KEYBOARD) {
                if (settings.toolsLongPress == "EDIT_BAR") KeyboardPrefs.edit(context) { putBoolean("edit_bar", !settings.editBar) }
                else KeyboardPrefs.edit(context) { putBoolean("pinned_tools", !settings.pinnedTools) }
            }
        }) {
            if (panel != ActivePanel.KEYBOARD) {
                SlotIcon("back", btnFg, 22.dp) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = btnFg, modifier = Modifier.size(22.dp)) }
            } else {
                SlotIcon("menu", btnFg, 22.dp) { GlyphIcon(Glyph.GRID, btnFg, 22.dp) }
            }
        }
        val hideOnSwipe = settings.swipeDownHide && panel == ActivePanel.KEYBOARD
        val cActionsHide by rememberUpdatedState(actions)
        Box(
            Modifier.weight(1f).fillMaxHeight().then(
                if (!hideOnSwipe) Modifier else Modifier.pointerInput(Unit) {
                    // свайп вниз закрывает клавиатуру; события не забираем — подсказки и их прокрутка работают как раньше
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val threshold = 28.dp.toPx()
                        var fired = false
                        while (!fired) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val dy = ch.position.y - down.position.y
                            val dx = kotlin.math.abs(ch.position.x - down.position.x)
                            if (dy > threshold && dy > dx * 1.5f) {
                                fired = true
                                cActionsHide.hideKeyboard()
                            } else if (dx > threshold * 1.5f) break
                        }
                    }
                }
            ),
            contentAlignment = Alignment.Center
        ) {
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
                    HeaderMode.OFFER -> state.clipOffer?.let { ClipOfferStrip(it, actions, palette, font, secret = state.clipOfferSecret) }
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
        if (settings.showMicButton && !sideHidden) {
            // правая кнопка: микрофон или другое действие из настроек («Кнопка справа»)
            val act = settings.micAction
            val onMic: () -> Unit = when (act) {
                "EMOJI" -> { { actions.togglePanel(ActivePanel.EMOJI) } }
                "CLIPBOARD" -> { { actions.togglePanel(ActivePanel.CLIPBOARD) } }
                "TRANSLATE" -> { { actions.togglePanel(ActivePanel.TRANSLATE) } }
                "GIF" -> { { actions.togglePanel(ActivePanel.GIF) } }
                "UNDO" -> { { actions.editKey("undo") } }
                "HIDE" -> { { actions.hideKeyboard() } }
                else -> { { actions.togglePanel(ActivePanel.VOICE) } }
            }
            // удержание — Rambler: диктовка без спешки, ИИ убирает «э-э», повторы и оговорки (у микрофона);
            // у других действий удержание — обычный голосовой ввод, чтобы он не потерялся совсем
            val onRambler: () -> Unit = if (act == "VOICE") { { actions.rambler() } } else { { actions.togglePanel(ActivePanel.VOICE) } }
            val micIcon = when (act) {
                "EMOJI" -> Icons.Rounded.EmojiEmotions
                "CLIPBOARD" -> Icons.Rounded.ContentPaste
                "TRANSLATE" -> Icons.Rounded.Translate
                "GIF" -> Icons.Rounded.Gif
                "UNDO" -> Icons.AutoMirrored.Rounded.Undo
                "HIDE" -> Icons.Rounded.KeyboardHide
                else -> Icons.Outlined.Mic
            }
            val mic: @Composable BoxScope.() -> Unit = {
                if (act == "VOICE") SlotIcon("mic", btnFg, 22.dp) { Icon(micIcon, null, tint = btnFg, modifier = Modifier.size(22.dp)) }
                else Icon(micIcon, null, tint = btnFg, modifier = Modifier.size(22.dp))
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
                    // длинное слово получает больше места, короткие соседи — меньше
                    .weight((if (primary) 1.15f else 1f) * chipWeight(chip))
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
                FitText(
                    text = if (chip.isEmpty) "…" else chip.displayText,
                    color = if (chip.isEmpty) fg.copy(alpha = 0.5f) else fg,
                    font = font,
                    maxSize = 15f,
                    weight = if (primary) FontWeight.ExtraBold else FontWeight.SemiBold
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

/** Индикатор загрузки M3 Expressive: фигура, которая вращается и перетекает между формами MaterialShapes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MorphIndicator(color: Color, modifier: Modifier) {
    LoadingIndicator(modifier = modifier, color = color)
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
        EditButton(Icons.Rounded.ContentCut, "cut", tint, palette, actions)
        EditButton(Icons.Rounded.ContentCopy, "copy", tint, palette, actions)
        EditButton(Icons.Rounded.ContentPaste, "paste", tint, palette, actions)
        EditButton(Icons.AutoMirrored.Rounded.Backspace, "backspace", tint, palette, actions, repeat = true)
        EditButton(Icons.Rounded.LastPage, "end", tint, palette, actions)
    }
}

@Composable
private fun RowScope.EditButton(
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
        Modifier.weight(1f).height(40.dp)
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
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (selected) palette.accent else if (down) tint.copy(alpha = 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = if (selected) palette.onAccent else tint, modifier = Modifier.size(21.dp))
        }
    }
}

/** Только что скопированное: нажатие вставляет, ✕ убирает. Как в Gboard. */
@Composable
internal fun ClipOfferStrip(entry: String, actions: KeyboardActions, palette: KeyPalette, font: FontFamily, secret: Boolean = false) {
    val image = !secret && ClipImages.isImage(entry)
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
                if (image) tr("Вставить картинку") else if (secret) tr("Вставить пароль") + "  ••••••••" else entry.replace('\n', ' '),
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
                    .weight(chipWeight(chip))
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .clip(CircleShape)
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
                    FitText(
                        text = chip.displayText,
                        color = if (primary) primaryFg else fg.copy(alpha = 0.82f),
                        font = font,
                        maxSize = 16f,
                        weight = if (primary) FontWeight.Bold else FontWeight.Normal
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
        add(ToolDef("plugins", tr("Плагины"), "plugins", Icons.Rounded.Extension, null))
        for (p in PluginRepository.active) {
            p.actions.forEachIndexed { i, a ->
                if (a.pinned) add(ToolDef("pin:${p.id}#$i", tr(a.title), if (a.icon == "@layout_swap") "layout_swap" else null, null, a.icon))
            }
        }
        for (b in ScriptHost.buttons) if (b.toolbar) add(ToolDef("btn:${b.pluginId}:${b.id}", tr(b.title), null, null, b.icon))
        add(ToolDef("translate", tr("Переводчик"), "translate", Icons.Rounded.Translate, null))
        add(ToolDef("ai_fix", tr("Исправить текст"), "ai_fix", Icons.Rounded.AutoFixHigh, null))
        add(ToolDef("gif", "GIF", "gif", Icons.Rounded.GifBox, null))
        add(ToolDef("clipboard", tr("Буфер обмена"), "clipboard", Icons.Rounded.ContentPaste, null))
        add(ToolDef("emoji", tr("Эмодзи"), "emoji", Icons.Rounded.EmojiEmotions, null))
        add(ToolDef("one_handed", tr("Одна рука"), "one_handed", Icons.Rounded.SwapHoriz, null))
        add(ToolDef("floating", tr("Плавающая клавиатура"), null, Icons.Rounded.PictureInPictureAlt, null))
        add(ToolDef("incognito", tr("Инкогнито"), "incognito", Icons.Rounded.VisibilityOff, null))
        add(ToolDef("settings", tr("Настройки"), "settings", Icons.Rounded.Settings, null))
        add(ToolDef("hide", tr("Скрыть клавиатуру"), "hide", Icons.Rounded.KeyboardHide, null))
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
            id == "floating" -> KeyboardPrefs.edit(context) { putBoolean("floating_keyboard", !settings.floating) }
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
                    "floating" -> settings.floating
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
    val color by animateColorAsState(if (selected) palette.onAccent else tint, tween(160), label = "toolFg")
    Box(
        modifier = Modifier.toolHighlight(selected, palette.accent).clickable(onClick = onClick),
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
    val color by animateColorAsState(if (selected) palette.onAccent else tint, tween(160), label = "toolFg")
    Box(
        modifier = Modifier.toolHighlight(selected, palette.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/**
 * Кнопка панели 40×36 в «таблетке» 44 dp: подсветка выбранной — тоже таблетка с отступом 4 dp, то есть повторяет
 * скругление панели и у краёв не торчит квадратом. Появляется пружинкой из центра, а не скачком.
 */
@Composable
private fun Modifier.toolHighlight(selected: Boolean, accent: Color): Modifier {
    val t by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "toolSel")
    return this
        .size(width = 40.dp, height = 36.dp)
        .clip(CircleShape)
        .drawBehind {
            if (t > 0.01f) {
                val s = 0.6f + 0.4f * t
                val w = size.width * s
                val h = size.height * s
                drawRoundRect(
                    accent.copy(alpha = accent.alpha * t.coerceIn(0f, 1f)),
                    topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                    size = Size(w, h), cornerRadius = CornerRadius(h / 2f)
                )
            }
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

/** Доля ширины подсказки: длинное слово (до 1,8×) теснит короткие, чтобы не обрезаться. */
private fun chipWeight(chip: SuggestionChipModel): Float =
    if (chip.isEmpty) 0.8f else (chip.displayText.length / 7f).coerceIn(1f, 1.8f)

/**
 * Подсказка целиком: если слово не влезает, шрифт плавно уменьшается (до 10 sp), и только потом — «…».
 * Раньше длинные подсказки просто обрезались.
 */
@Composable
private fun FitText(text: String, color: Color, font: FontFamily, maxSize: Float, weight: FontWeight) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = androidx.compose.ui.text.TextStyle(color = color, fontFamily = font, fontWeight = weight, fontSize = maxSize.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = maxSize.sp, stepSize = 0.5.sp)
    )
}
