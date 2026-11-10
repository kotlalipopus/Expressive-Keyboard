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
// 8. UI КЛАВИАТУРЫ (MATERIAL 3 EXPRESSIVE ПО МАКЕТУ)
// ============================================================================

/** onBackground(цвет, прозрачная навигация) — фон под системной панелью навигации. */
@Composable
fun KeyboardRoot(
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    onBackground: (Color, Boolean) -> Unit,
    onWindowBlur: (Int, Color, Float) -> Unit = { _, _, _ -> },
    /** Плавающая клавиатура: её границы в окне (только там окно ловит касания); null — обычная, внизу. */
    onFloatBounds: (android.graphics.Rect?) -> Unit = {}
) {
    val prefs by KeyboardPrefs.observe()
    val raw = remember(prefs, PowerState.saver) { prefs.forPower(PowerState.saver) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (raw.themeMode) {
        "DARK", "AMOLED" -> true
        "LIGHT" -> false
        else -> systemDark
    }
    val activePlugins = PluginRepository.active
    val scriptSkin = ScriptHost.skinOverride
    // тема с её настройками и в акцентном цвете пользователя; пересчёт — только когда что-то из этого меняется
    val skin = remember(activePlugins, scriptSkin, raw.skinPlugin, raw.skinOptions, raw.userAccent, raw.colorAccentText, raw.keyOpacity, raw.liquidGlass, dark) {
        PluginRepository.resolvedSkin(raw, dark)
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val floating = raw.floating
    val settings = remember(raw, skin, landscape, configuration.screenHeightDp) {
        val s0 = skin?.applyColors(raw) ?: raw
        val s = if (landscape) s0.forLandscape(configuration.screenHeightDp) else s0
        // плавающая — компактнее: клавиши ниже, без режима одной руки и своей ширины (ширину задаёт окно)
        if (s.floating) s.copy(
            keyHeightDp = (s.keyHeightDp * 0.86f).coerceAtLeast(28f), bottomOffsetDp = min(s.bottomOffsetDp, 4f),
            oneHanded = "OFF", keyboardWidth = 1f
        ) else s
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
        // размытие окна плавающей клавиатуре не подходит: окно во весь экран, размылось бы всё приложение
        val blurDp = wantBlur?.takeIf { it > 0f && blurSupported && image == null && !floating }
        // дымка поверх размытия: у темы своя, иначе — полупрозрачный фон клавиатуры
        val blurTint0 = skin?.blurTint ?: palette.background.copy(alpha = if (palette.background.luminance() < 0.5f) 0.62f else 0.55f)
        // прозрачный фон: сквозь клавиатуру видно приложение, остаются только клавиши
        val bgAlpha = 1f - settings.bgTransparency
        val blurTint = blurTint0.copy(alpha = blurTint0.alpha * bgAlpha)
        val decorated = image != null || skin?.hasBackground == true || blurDp != null || bgAlpha < 0.99f || floating
        LaunchedEffect(palette.background, decorated) { onBackground(palette.background, decorated) }
        // без верхней панели скруглять верх фона нечем: первый ряд клавиш стоял бы впритык
        // к вырезу, и углы выглядели обрезанными — делаем верх листа прямым
        val corner = if (floating) min(skin?.corner ?: 24f, 24f) else if (settings.showHeader) skin?.corner ?: 28f else 0f
        LaunchedEffect(blurDp, blurTint, corner) {
            onWindowBlur(if (blurDp == null) 0 else with(density) { blurDp.dp.roundToPx() }, blurTint, corner)
        }
        // подложку для стекла считаем в фоне и только когда оформление стеклянное (при размытии окна она не нужна)
        LaunchedEffect(skin, image, palette.background, settings.bgDim, rootSize, blurDp) {
            val needsBackdrop = skin != null && skin.usesGlass && (blurDp == null || (skin.usesLiquid && Build.VERSION.SDK_INT >= 33))
            backdrop.overWindowBlur = blurDp != null
            if (skin == null || !needsBackdrop || rootSize.width == 0 || rootSize.height == 0) {
                backdrop.bitmap = null
                return@LaunchedEffect
            }
            // окно раскладывается за несколько кадров: пауза не даёт перерисовывать подложку на каждый промежуточный размер
            delay(50L)
            val (bmp, s) = withContext(Dispatchers.Default) {
                renderBackdrop(rootSize.width, rootSize.height, density, skin, palette.background, image, if (image != null) settings.bgDim else 0f)
            }
            backdrop.scale = s
            backdrop.bitmap = bmp
        }
        CompositionLocalProvider(LocalSkin provides skin, LocalBackdrop provides backdrop) {
          FloatingFrame(floating, settings, raw.keyHeightDp, palette, corner, onFloatBounds) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = if (floating) RectangleShape else RoundedCornerShape(topStart = corner.dp, topEnd = corner.dp),
                // при размытии окна фон рисует само окно (дымка поверх размытия)
                // размытие просили, но система его не даёт — фон «матовый»: почти непрозрачный, а не прозрачный
                color = if (blurDp != null) Color.Transparent
                else palette.background.copy(alpha = palette.background.alpha * if (wantBlur != null && wantBlur > 0f && image == null) max(bgAlpha, 0.92f) else bgAlpha)
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
                            // плавающая не стоит у края экрана: отступы под панель навигации и вырез ей не нужны
                            .then(
                                if (floating) Modifier
                                else Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(horizontal = side)
                            )
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
}

/**
 * Плавающая клавиатура: окно клавиатуры во весь экран и прозрачное, сама клавиатура — карточка, которую
 * таскают за ручку сверху. Касания мимо карточки уходят приложению (границы сообщаются сервису).
 * Положение читается только при раскладке: перетаскивание не перекомпоновывает клавиатуру.
 */
@Composable
internal fun FloatingFrame(
    enabled: Boolean,
    settings: KeyboardSettings,
    /** Высота клавиш из настроек: в плавающем режиме на экране она меньше (×0.86), а сохранять надо исходную. */
    baseKeyHeightDp: Float,
    palette: KeyPalette,
    cornerDp: Float,
    onBounds: (android.graphics.Rect?) -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        LaunchedEffect(Unit) { onBounds(null) }
        content()
        return
    }
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val conf = LocalConfiguration.current
        // на случай прошивки, где окно клавиатуры не ограничивает высоту: берём высоту экрана
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else with(density) { conf.screenWidthDp.dp.toPx() }
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else with(density) { conf.screenHeightDp.dp.toPx() }
        // ширину можно тянуть за уголок на ручке: во время жеста — живая, при отпускании сохраняется
        val liveWidth = remember(settings.floatWidth) { mutableStateOf(settings.floatWidth) }
        val widthPx = maxW * liveWidth.value
        val kbH = remember { mutableIntStateOf(0) }
        val pos = remember(maxW, maxH) { mutableStateOf<Offset?>(null) }
        // пока тянут уголок: правый и нижний края карточки стоят на месте, растёт она влево-вверх, к пальцу
        val anchorRight = remember { mutableStateOf<Float?>(null) }
        val anchorBottom = remember { mutableStateOf<Float?>(null) }
        val fx = settings.floatX
        val fy = settings.floatY
        fun place(): Offset {
            val freeX = (maxW - widthPx).coerceAtLeast(0f)
            val freeY = (maxH - kbH.intValue).coerceAtLeast(0f)
            val base = pos.value ?: Offset(freeX * fx, freeY * (1f - fy))
            val p = Offset(anchorRight.value?.let { it - widthPx } ?: base.x, anchorBottom.value?.let { it - kbH.intValue } ?: base.y)
            return Offset(p.x.coerceIn(0f, freeX), p.y.coerceIn(0f, freeY))
        }
        fun save() {
            val p = place()
            val freeX = (maxW - widthPx).coerceAtLeast(1f)
            val freeY = (maxH - kbH.intValue).coerceAtLeast(1f)
            KeyboardPrefs.edit(context) {
                putFloat("float_x", (p.x / freeX).coerceIn(0f, 1f))
                putFloat("float_y", (1f - p.y / freeY).coerceIn(0f, 1f))
            }
        }
        val shape = RoundedCornerShape(cornerDp.dp)
        val handleBg = palette.background.copy(alpha = max(palette.background.alpha, 0.94f))
        Column(
            Modifier
                .offset { place().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .width(with(density) { widthPx.toDp() })
                .onSizeChanged { kbH.intValue = it.height }
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    onBounds(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.roundToInt(), b.bottom.roundToInt()))
                }
                .shadow(10.dp, shape)
                .clip(shape)
        ) {
            Box(
                Modifier.fillMaxWidth().height(26.dp).background(handleBg)
                    .pointerInput(maxW, maxH, widthPx) {
                        detectDragGestures(onDragEnd = { save() }, onDragCancel = { save() }) { change, drag ->
                            change.consume()
                            pos.value = place() + drag
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(palette.onFunc.copy(alpha = 0.45f)))
                // уголок (левый верхний угол карточки): влево — шире, вверх — выше клавиши; размер меняется сразу.
                // Ключ жеста — только ширина экрана: высота клавиш пишется в настройки по ходу жеста, и с ней в
                // ключах жест обрывался бы на первом же шаге
                val keyH by rememberUpdatedState(baseKeyHeightDp)
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 2.dp).size(width = 36.dp, height = 26.dp)
                        .pointerInput(maxW) {
                            var startW = 0f
                            var startH = 0f
                            var lastH = 0f
                            var dxSum = 0f
                            var dySum = 0f
                            fun finish() {
                                val p = place()
                                anchorRight.value = null
                                anchorBottom.value = null
                                pos.value = p
                                KeyboardPrefs.edit(context) {
                                    putFloat("float_width", liveWidth.value)
                                    if (lastH != startH) putFloat("key_height_dp", lastH)
                                }
                                save()
                            }
                            detectDragGestures(
                                onDragStart = {
                                    val p = place()
                                    startW = liveWidth.value
                                    startH = keyH
                                    lastH = keyH
                                    dxSum = 0f
                                    dySum = 0f
                                    anchorRight.value = p.x + maxW * startW
                                    anchorBottom.value = p.y + kbH.intValue
                                },
                                onDragEnd = { finish() },
                                onDragCancel = { finish() }
                            ) { change, drag ->
                                change.consume()
                                dxSum += drag.x
                                dySum += drag.y
                                liveWidth.value = (startW - dxSum / maxW).coerceIn(0.5f, 1f)
                                // ~4 ряда клавиш: палец вверх на N dp — клавиатура выше примерно на столько же
                                val h = ((startH - dySum / density.density / 4f) * 2f).roundToInt() / 2f
                                val hc = h.coerceIn(32f, 68f)
                                if (hc != lastH) {
                                    lastH = hc
                                    KeyboardPrefs.edit(context) { putFloat("key_height_dp", hc) }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(palette.func), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.OpenInFull, tr("Изменить размер"), tint = palette.onFunc, modifier = Modifier.size(14.dp))
                    }
                }
                // вернуть клавиатуру вниз, на обычное место
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(22.dp).clip(CircleShape).background(palette.func)
                        .clickable { KeyboardPrefs.edit(context) { putBoolean("floating_keyboard", false) } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.VerticalAlignBottom, tr("Закрепить внизу"), tint = palette.onFunc, modifier = Modifier.size(15.dp))
                }
            }
            content()
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
    val spec = remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent, settings.shapeAmount) { settings.accentSpec().accent(settings) }
    val panel = state.panel
    val numbers = settings.showNumberRow && state.symbolsPage == 0 && !state.numberPad
    val bodyHeight = (settings.keyHeightDp * (if (numbers) 4f + settings.numberRowScale else 4f) + settings.rowSpacingDp * (if (numbers) 4f else 3f)).dp
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

    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { layer.coords = it }
            .then(if (ModFx.wantsPointer) Modifier.modPointerWatch() else Modifier)
    ) {
        // слой мода под клавишами — первым, чтобы клавиши легли поверх
        ScriptBackgroundLayer()
        GlassMergeLayer(
            settings.glassMerge && LocalSkin.current?.usesGlass == true,
            palette.background,
            settings.cornerRadiusDp
        )
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (ModFx.layerActive) Modifier.modKeyboardLayer() else Modifier)
                .padding(horizontal = settings.sidePaddingDp.dp, vertical = gap),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            if (settings.editBar && !settings.landscape) EditBar(state, actions, palette)
            // закреплённая панель функций — одна: если строку инструментов открыли в заголовке, вторая не нужна
            if (settings.pinnedTools && panel == ActivePanel.KEYBOARD && !state.toolbarOpen) {
                Box(Modifier.fillMaxWidth().height((if (settings.landscape) settings.headerHeightDp - 8f else settings.headerHeightDp).dp), contentAlignment = Alignment.Center) {
                    ToolbarPill(state, actions, settings, palette)
                }
            }
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
        PopupLayer(popup, palette, font, spec.decor(settings), settings.animPopup, settings.animSpeed, settings.popupStyle)
        ScriptFrameTicker()
        ModFxTicker()
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
        // своя ширина (на планшете клавиши во всю ширину слишком длинные) — клавиши по центру
        if (settings.keyboardWidth < 0.99f) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(settings.keyboardWidth)) { KeyGrid(state, actions, settings, palette, font, popup, layer, glide) }
            }
        } else KeyGrid(state, actions, settings, palette, font, popup, layer, glide)
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
    val letterRows = remember(
        lang, lang.rows, page, settings.showNumberRow, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
        settings.spaceLeftKey, settings.spaceRightKey, langLabels, pluginLayouts, scriptLayouts, settings.userLayouts
    ) {
        KeyboardLayouts.rows(
            lang, page, settings.showNumberRow, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
            langLabels, settings.spaceLeftKey, settings.spaceRightKey, settings.userLayoutMap
        )
    }
    val padRows = remember(state.phoneField) { KeyboardLayouts.numberPad(state.phoneField) }
    val baseRows = if (state.numberPad) padRows else letterRows
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
            val isNumberRow = row.heightWeight == KeyboardLayouts.NUMBER_ROW_WEIGHT
            Row(Modifier.fillMaxWidth().weight(if (isNumberRow) settings.numberRowScale else row.heightWeight), horizontalArrangement = Arrangement.spacedBy(hSpace)) {
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
                        modifier = Modifier.weight(if (key.type == KeyType.ENTER) key.weight * settings.enterWidth else key.weight).fillMaxHeight(),
                        holdSymbol = if (isChar) holdSymbols[key] else null,
                        gapX = hSpace,
                        gapY = settings.rowSpacingDp.dp
                    )
                }
            }
        }
    }
}
