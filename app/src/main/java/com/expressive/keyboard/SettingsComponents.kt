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

// ============================================================================
// 13. КОМПОНЕНТЫ НАСТРОЕК (MATERIAL 3 EXPRESSIVE)
// ============================================================================

@Composable
internal fun HeroCard(
    spec: WavySpec,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    micGranted: Boolean,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onMic: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val ready = imeEnabled && imeSelected
    // вращаем «печеньку», только пока клавиатура не настроена — привлекает внимание и не тратит кадры потом
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(ready) {
        if (!ready) while (true) rotation.animateTo(rotation.value + 360f, tween(16000, easing = LinearEasing))
        else rotation.animateTo(rotation.value + 45f, spring(dampingRatio = 0.5f, stiffness = 120f))
    }
    val container by animateColorAsState(if (ready) cs.primaryContainer else cs.tertiaryContainer, label = "heroBg")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(container).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(84.dp).wavyBackground(spec, if (ready) cs.primary else cs.tertiary) { rotation.value % 360f },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (ready) Icons.Rounded.Check else Icons.Rounded.Keyboard, null,
                    tint = if (ready) cs.onPrimary else cs.onTertiary, modifier = Modifier.size(38.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    tr(if (ready) "Всё готово" else "Почти готово"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (ready) cs.onPrimaryContainer else cs.onTertiaryContainer
                )
                Text(
                    tr(if (ready) "Клавиатура включена и выбрана" else "Включите клавиатуру и выберите её по умолчанию"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = (if (ready) cs.onPrimaryContainer else cs.onTertiaryContainer).copy(alpha = 0.8f)
                )
            }
        }
        AnimatedVisibility(
            visible = !ready || !micGranted,
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 400f)) + fadeOut()
        ) {
            Column(Modifier.clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StepRow(1, "Включить в системе", imeEnabled, "Включить", onEnable)
                StepRow(2, "Выбрать по умолчанию", imeSelected, "Выбрать", onSelect)
                StepRow(3, "Микрофон для голосового ввода", micGranted, "Разрешить", onMic)
            }
        }
    }
}

@Composable
internal fun StepRow(n: Int, title: String, done: Boolean, action: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceBright.copy(alpha = 0.9f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) cs.primary else cs.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            else Text("$n", color = cs.onSecondaryContainer, fontWeight = FontWeight.ExtraBold)
        }
        Text(tr(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        if (!done) {
            Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text(tr(action), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Группа в стиле Android 16: общие крупные скругления снаружи, мелкие между пунктами. */
@Composable
internal fun SettingsSection(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                tr(title),
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

/** Пункт группы: при нажатии углы «набухают» — морфинг формы M3 Expressive. */
@Composable
internal fun GroupItem(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 22.dp else 6.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "itemCorner")
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceBright)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                else Modifier
            )
    ) { content() }
}

@Composable
internal fun IconBadge(icon: ImageVector, container: Color = MaterialTheme.colorScheme.secondaryContainer, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ItemTitle(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(tr(subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ToggleItem(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupItem(onClick = { onChange(!checked) }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f)) { ItemTitle(icon, title, subtitle) }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null
            )
        }
    }
}

@Composable
internal fun ActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    GroupItem(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f)) { ItemTitle(icon, title, subtitle) }
            if (trailing != null) trailing()
            else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Пока ползунок тянут — значение локальное; в настройки пишется один раз при отпускании. */
@Composable
internal fun SliderItem(
    icon: ImageVector,
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
    steps: Int = 0,
    onDrag: ((Float) -> Unit)? = null
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    var lastPreview by remember { mutableLongStateOf(0L) }
    GroupItem {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(icon)
                Text(tr(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Slider(
                value = local,
                onValueChange = { v ->
                    local = v
                    if (onDrag != null) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPreview > 90L) {
                            lastPreview = now
                            onDrag(v)
                        }
                    }
                },
                onValueChangeFinished = { onCommit(local) },
                valueRange = range,
                steps = steps,
                modifier = Modifier.padding(start = 56.dp)
            )
        }
    }
}

/** Связанная группа кнопок M3 Expressive: выбранная кнопка становится «таблеткой» и шире соседей. */
@Composable
internal fun ButtonGroup(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, (id, label) ->
            val isSel = id == selected
            val first = i == 0
            val last = i == options.lastIndex
            val inner by animateDpAsState(if (isSel) 24.dp else 8.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "bgInner")
            val weight by animateFloatAsState(if (isSel) 1.25f else 1f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "bgWeight")
            val bg by animateColorAsState(if (isSel) cs.primary else cs.secondaryContainer, label = "bgColor")
            val start = if (first) 24.dp else inner
            val end = if (last) 24.dp else inner
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(bg)
                    .clickable { onSelect(id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    tr(label),
                    color = if (isSel) cs.onPrimary else cs.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
internal fun ChoiceItem(icon: ImageVector, title: String, subtitle: String?, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    GroupItem {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ItemTitle(icon, title, subtitle)
            // много вариантов в одну строку не помещаются — по два в ряд
            if (options.size > 4) options.chunked(2).forEach { ButtonGroup(it, selected, onSelect) }
            else ButtonGroup(options, selected, onSelect)
        }
    }
}

@Composable
internal fun ShapePicker(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExpressiveShapePreset.entries.forEach { preset ->
            val isSel = settings.shapePreset == preset.id
            val spec = if (isSel) settings.accentSpec()
            else WavySpec(preset.profile, preset.defaultWaves, preset.defaultAmplitude / 100f)
            val scale by animateFloatAsState(if (isSel) 1f else 0.82f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "shapeScale")
            val rot by animateFloatAsState(if (isSel) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 200f), label = "shapeRot")
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(20.dp))
                    .clickable { KeyboardPrefs.applyPreset(context, preset) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(56.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }.wavyBackground(spec, if (isSel) cs.primary else cs.secondaryContainer) { rot },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSel) Icon(Icons.Rounded.Check, null, tint = cs.onPrimary)
                }
                Text(
                    tr(preset.title), style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) cs.primary else cs.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

/** Живой мини-предпросмотр клавиатуры: цвета, фон, форма, шрифт и размеры берутся из настроек. */
@Composable
internal fun StylePreview(settings: KeyboardSettings, spec: WavySpec) {
    val palette = rememberKeyPalette(settings)
    val font = LocalKeyFont.current
    val weight = LocalKeyWeight.current
    val image = if (settings.hasBackgroundImage) rememberBackgroundBitmap(settings.bgImageVersion) else null
    val keyH = (settings.keyHeightDp * 0.9f).dp
    val gap = settings.keySpacingDp.dp
    val corner = settings.cornerRadiusDp.dp
    val labelSize = (20f * settings.keyLabelScale).sp
    Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(palette.background)) {
        if (image != null) {
            Image(
                image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
                    .then(if (settings.bgBlurDp > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(settings.bgBlurDp.dp) else Modifier)
            )
            Box(Modifier.matchParentSize().background(palette.background.copy(alpha = settings.bgDim)))
        }
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(settings.rowSpacingDp.dp)) {
            if (settings.showHeader) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(38.dp).wavyBackground(spec, palette.accent), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.GRID, palette.onAccent, 18.dp)
                    }
                    if (settings.showSuggestions) {
                        listOf("как", "привет", "дела").forEachIndexed { i, w ->
                            Box(
                                Modifier.weight(1f).height(34.dp).clip(CircleShape).background(if (i == 1) palette.accentSoft else palette.func),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    w, color = if (i == 1) palette.onAccentSoft else palette.onFunc, fontFamily = font,
                                    fontWeight = if (i == 1) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1
                                )
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).height(34.dp).clip(CircleShape).background(palette.func))
                    }
                    if (settings.showMicButton) {
                        Box(Modifier.size(38.dp).wavyBackground(spec, palette.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Mic, null, tint = palette.onAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(gap)) {
                (if (I18n.isEnglish) listOf("h", "e", "l", "l", "o", "!") else listOf("п", "р", "и", "в", "е", "т")).forEachIndexed { i, ch ->
                    val pressed = i == 2
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(if (pressed) CircleShape else RoundedCornerShape(corner))
                            .background(if (pressed) palette.accent else palette.letter)
                            .then(
                                if (settings.showKeyBorders) Modifier.border(1.dp, palette.outline, RoundedCornerShape(corner)) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(ch, color = if (pressed) palette.onAccent else palette.onLetter, fontFamily = font, fontWeight = weight, fontSize = labelSize)
                        if (settings.showHints && !pressed) {
                            val hint = hintFor(ch, KeyVariantsCatalog.getVariantsFor(ch, false))
                            if (hint != null) {
                                Text(
                                    hint, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp),
                                    color = palette.onLetter.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = font
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(1.3f).fillMaxHeight().clip(RoundedCornerShape(corner)).background(palette.accentSoft), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.SHIFT, palette.onAccentSoft, 20.dp)
                }
                if (settings.showEmojiKey) {
                    Box(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(corner)).background(palette.letter), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.EMOJI, palette.onLetter, 22.dp)
                    }
                }
                Box(Modifier.weight(3f).fillMaxHeight().clip(CircleShape).background(palette.func), contentAlignment = Alignment.Center) {
                    Text(if (I18n.isEnglish) "English" else "Русский", color = palette.onFunc.copy(alpha = 0.72f), fontFamily = font, fontWeight = weight, fontSize = 13.sp)
                }
                Box(Modifier.weight(1.3f).fillMaxHeight().clip(CircleShape).background(palette.func), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACKSPACE, palette.onFunc, 20.dp)
                }
                Box(Modifier.weight(1.6f).fillMaxHeight().clip(RoundedCornerShape(corner)).background(palette.accent), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.ARROW, palette.onAccent, 22.dp)
                }
            }
        }
    }
}

/** Плитка категории на главном экране: иконка в фигуре Expressive своего цвета. */
@Composable
internal fun CategoryTile(page: SettingsPage, index: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (index % 3) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    val expressive = KeyboardPrefs.observe().value.designStyle == DesignStyle.EXPRESSIVE.id
    val spec = remember(page, expressive) {
        if (expressive) WavySpec(page.shape.profile, page.shape.defaultWaves, page.shape.defaultAmplitude / 100f)
        else WavySpec(ShapeProfile.CIRCLE, 12, 0f)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rot by animateFloatAsState(if (pressed) 40f else 0f, spring(dampingRatio = 0.5f, stiffness = 300f), label = "tileRot")
    val corner by animateDpAsState(if (pressed) 22.dp else 6.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "tileCorner")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(corner)).background(cs.surfaceBright)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(48.dp).wavyBackground(spec, bg) { rot }, contentAlignment = Alignment.Center) {
            Icon(page.icon, null, tint = fg, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tr(page.title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(tr(page.subtitle), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------------- выбор цвета

internal val SWATCHES = listOf(
    0xFF6750A4, 0xFF8E4EC6, 0xFFB5338A, 0xFFD32F5B, 0xFFE5484D, 0xFFF76B15,
    0xFFFFA000, 0xFFE2B714, 0xFF8BC34A, 0xFF30A46C, 0xFF12A594, 0xFF00A2C7,
    0xFF0090FF, 0xFF3E63DD, 0xFF5B5BD6, 0xFF795548, 0xFF607D8B, 0xFF1C1B1F
).map { it.toInt() }

/** Цвет в виде «печеньки» с галочкой, если выбран. */
@Composable
internal fun ColorDot(color: Color, selected: Boolean, size: Dp = 44.dp, onClick: () -> Unit) {
    val spec = remember { WavySpec(ShapeProfile.SINE, 9, 0.07f) }
    val scale by animateFloatAsState(if (selected) 1f else 0.86f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "dotScale")
    val rot by animateFloatAsState(if (selected) 40f else 0f, spring(dampingRatio = 0.6f, stiffness = 200f), label = "dotRot")
    Box(
        Modifier.size(size).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.wavyBackground(if (selected) spec else WavySpec(ShapeProfile.CIRCLE, 12, 0f), color) { rot }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = contentColorFor(color), modifier = Modifier.size(20.dp))
    }
}

/** Полоса с градиентом и бегунком: тап или перетаскивание задают значение 0..1. */
@Composable
internal fun GradientBar(value: Float, brush: Brush, thumb: Color, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().height(32.dp).clip(CircleShape).background(brush)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    cb((down.position.x / w).coerceIn(0f, 1f))
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        cb((ch.position.x / w).coerceIn(0f, 1f))
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val r = size.height / 2f
                val x = r + (size.width - 2 * r) * value.coerceIn(0f, 1f)
                drawCircle(Color.White, r - 3.dp.toPx(), Offset(x, r))
                drawCircle(thumb, r - 7.dp.toPx(), Offset(x, r))
            }
    )
}

internal fun hsvColor(h: Float, s: Float, v: Float): Color = Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))

@Composable
internal fun ColorPickerDialog(
    title: String,
    initial: Int,
    allowAuto: Boolean,
    onDismiss: () -> Unit,
    withAlpha: Boolean = false,
    autoLabel: String = "Авто",
    onPick: (Int) -> Unit
) {
    val start = remember { FloatArray(3).also { AndroidColor.colorToHSV(if (initial == 0) 0xFF6750A4.toInt() else initial, it) } }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    var alpha by remember { mutableFloatStateOf(if (initial == 0 || !withAlpha) 1f else (initial ushr 24) / 255f) }
    val color = hsvColor(h, s, v).copy(alpha = alpha)
    var hex by remember { mutableStateOf(String.format("%06X", color.toArgb() and 0xFFFFFF)) }
    fun setFrom(argb: Int) {
        val t = FloatArray(3)
        AndroidColor.colorToHSV(argb, t)
        h = t[0]; s = t[1]; v = t[2]
        hex = String.format("%06X", argb and 0xFFFFFF)
    }
    fun syncHex() {
        hex = String.format("%06X", hsvColor(h, s, v).toArgb() and 0xFFFFFF)
    }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val spec = remember { WavySpec(ShapeProfile.SINE, 12, 0.06f) }
                    Box(Modifier.size(72.dp).wavyBackground(spec, color), contentAlignment = Alignment.Center) {
                        Text(tr("Аа"), color = contentColorFor(color), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    }
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            val clean = t.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hex = clean
                            if (clean.length == 6) {
                                val argb = (0xFF000000 or clean.toLong(16)).toInt()
                                val tmp = FloatArray(3)
                                AndroidColor.colorToHSV(argb, tmp)
                                h = tmp[0]; s = tmp[1]; v = tmp[2]
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        label = { Text("HEX") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(tr("Оттенок"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                GradientBar(h / 360f, remember { Brush.horizontalGradient((0..6).map { hsvColor(it * 60f, 1f, 1f) }) }, hsvColor(h, 1f, 1f)) {
                    h = it * 360f
                    syncHex()
                }
                Text(tr("Насыщенность"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                GradientBar(s, Brush.horizontalGradient(listOf(hsvColor(h, 0f, v), hsvColor(h, 1f, v))), color) {
                    s = it
                    syncHex()
                }
                Text(tr("Яркость"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                GradientBar(v, Brush.horizontalGradient(listOf(Color.Black, hsvColor(h, s, 1f))), color) {
                    v = it
                    syncHex()
                }
                if (withAlpha) {
                    Text(tr("Непрозрачность") + " · ${(alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    GradientBar(alpha, Brush.horizontalGradient(listOf(hsvColor(h, s, v).copy(alpha = 0f), hsvColor(h, s, v))), color) { alpha = it }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SWATCHES.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { c -> ColorDot(Color(c), c == color.copy(alpha = 1f).toArgb(), 38.dp) { setFrom(c) } }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(color.toArgb()) }) { Text(tr("Готово")) } },
        dismissButton = {
            Row {
                if (allowAuto) TextButton(onClick = { onPick(0) }) { Text(tr(autoLabel)) }
                TextButton(onClick = onDismiss) { Text(tr("Отмена")) }
            }
        }
    )
}

/** Строка «элемент → цвет»; «Авто» показывает цвет темы пунктиром. */
@Composable
internal fun ColorRow(title: String, value: Int, auto: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    GroupItem(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val shown = if (value == 0) auto else Color(value)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(shown)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(tr(title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(
                    if (value == 0) tr("Авто (из темы)") else "#" + String.format("%06X", value and 0xFFFFFF),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                )
            }
            Icon(Icons.Rounded.Edit, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
internal fun TextInputDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text(tr(hint)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onDone(text.trim()) }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

@Composable
internal fun InfoCard(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.secondaryContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = cs.onSecondaryContainer)
        Text(tr(text), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
    }
}
