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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
// ПЕРВАЯ НАСТРОЙКА: после установки — что включить, какая тема, сколько форм, какие функции.
// Показывается только новым пользователям (тем, кто обновился, — нет). Всё можно поменять потом.
// ============================================================================

object Onboarding {
    private const val KEY = "onboarding_done"

    /** Нужна ли первая настройка: только при свежей установке и только один раз. */
    fun needed(context: Context): Boolean {
        val sp = KeyboardPrefs.prefs(context)
        if (sp.getBoolean(KEY, false)) return false
        val fresh = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(true)
        // обновились со старой версии — у них всё уже настроено, мастер не мешает
        if (!fresh) sp.edit().putBoolean(KEY, true).apply()
        return fresh
    }

    fun finish(context: Context) = KeyboardPrefs.prefs(context).edit().putBoolean(KEY, true).apply()
}

private enum class OnbStep { WELCOME, ENABLE, THEME, SHAPES, FEATURES, DONE }

@Composable
fun OnboardingScreen(settings: KeyboardSettings, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(OnbStep.WELCOME) }
    val steps = OnbStep.entries
    val cs = MaterialTheme.colorScheme
    fun go(d: Int) { step = steps[(step.ordinal + d).coerceIn(0, steps.lastIndex)] }
    androidx.activity.compose.BackHandler(enabled = step != OnbStep.WELCOME) { go(-1) }
    // новичкам — спокойная панель: ИИ, переводчик, GIF и плагины выключены, пока их не включат
    LaunchedEffect(Unit) {
        val sp = KeyboardPrefs.prefs(context)
        if (!sp.contains("toolbar_hidden")) KeyboardPrefs.edit(context) { putString("toolbar_hidden", ONB_EXTRAS.joinToString(",") { it.first }) }
    }
    Scaffold(containerColor = cs.surfaceContainer) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize().widthIn(max = 720.dp).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                if (step != OnbStep.WELCOME) {
                    val progress by animateFloatAsState(
                        step.ordinal / steps.lastIndex.toFloat(), MaterialTheme.motionScheme.slowSpatialSpec(), label = "onbProgress"
                    )
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val fwd = targetState.ordinal > initialState.ordinal
                        val spec = spring<IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
                        (slideInHorizontally(spec) { if (fwd) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(spec) { if (fwd) -it / 6 else it / 6 } + fadeOut(tween(140)))
                    },
                    label = "onbStep"
                ) { s ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Spacer(Modifier.height(8.dp))
                        when (s) {
                            OnbStep.WELCOME -> WelcomeStep(settings)
                            OnbStep.ENABLE -> EnableStep()
                            OnbStep.THEME -> ThemeStep(settings)
                            OnbStep.SHAPES -> ShapesStep(settings)
                            OnbStep.FEATURES -> FeaturesStep(settings)
                            OnbStep.DONE -> DoneStep(settings)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (step == OnbStep.WELCOME) TextButton(onClick = onFinish) { Text(tr("Пропустить")) }
                    else OutlinedButton(onClick = { go(-1) }, shapes = ButtonDefaults.shapes()) { Text(tr("Назад")) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (step == OnbStep.DONE) onFinish() else go(1) },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.MediumContentPadding,
                        modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
                    ) {
                        Text(
                            tr(when (step) { OnbStep.WELCOME -> "Начать"; OnbStep.DONE -> "Готово"; else -> "Далее" }),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (step != OnbStep.DONE) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Крупная фигура MaterialShapes с иконкой — «лицо» шага; медленно вращается. */
@Composable
private fun StepBadge(icon: ImageVector, preset: ExpressiveShapePreset, settings: KeyboardSettings) {
    val cs = MaterialTheme.colorScheme
    val spec = remember(preset, settings.shapeAmount) { WavySpec.of(preset).accent(settings) }
    val t = rememberInfiniteTransition(label = "badge")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "badgeRot")
    Box(Modifier.size(112.dp).wavyBackground(spec, cs.primaryContainer) { rot }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(title), style = MaterialTheme.typography.headlineLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        Text(tr(subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WelcomeStep(settings: KeyboardSettings) {
    val context = LocalContext.current
    Spacer(Modifier.height(32.dp))
    StepBadge(Icons.Rounded.Keyboard, ExpressiveShapePreset.COOKIE_12, settings)
    StepTitle(
        "Привет! Настроим клавиатуру под вас",
        "Пять коротких шагов: включить клавиатуру, выбрать тему, сколько будет форм и какие функции нужны. Всё это потом можно поменять в настройках."
    )
    SettingsSection(null) {
        ChoiceItem(
            Icons.Rounded.Translate, "Язык приложения", null,
            listOf("SYSTEM" to "Как в системе", "ru" to "Русский", "en" to "English"), settings.appLanguage
        ) { v -> KeyboardPrefs.edit(context) { putString("app_language", v) } }
    }
}

@Composable
private fun EnableStep() {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var enabled by remember { mutableStateOf(isImeEnabled(context)) }
    var selected by remember { mutableStateOf(isImeSelected(context)) }
    LaunchedEffect(focused) {
        if (focused) {
            enabled = isImeEnabled(context)
            selected = isImeSelected(context)
        }
    }
    StepTitle(
        "Включите клавиатуру",
        "Android спрашивает об этом один раз. Набранный текст никуда не отправляется, кроме функций, которые вы включите сами (например, исправления ИИ)."
    )
    SettingsSection(null) {
        OnbCheckItem(1, "Включить в системе", "Откроются настройки Android: включите Expressive Keyboard", enabled) {
            runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        OnbCheckItem(2, "Выбрать по умолчанию", "Выберите Expressive Keyboard в списке", selected, active = enabled) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }
    if (enabled && selected) {
        Text(tr("Готово — можно идти дальше"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun OnbCheckItem(n: Int, title: String, subtitle: String, done: Boolean, active: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    GroupItem(onClick = if (done || !active) null else onClick) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val bg by animateColorAsState(if (done) cs.primary else cs.secondaryContainer, label = "onbCheck")
            Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Rounded.Check, null, tint = cs.onPrimary)
                else Text("$n", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(tr(title), style = MaterialTheme.typography.titleMedium, color = if (active || done) cs.onSurface else cs.onSurfaceVariant)
                Text(tr(subtitle), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (!done && active) FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(tr("Открыть")) }
        }
    }
}

@Composable
private fun ThemeStep(settings: KeyboardSettings) {
    StepTitle("Какая тема?", "Нажмите на карточку — клавиатура сразу перекрасится. Ниже видно, как она выглядит на самом деле.")
    SettingsSection(null) { ThemeModeItem(settings) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceBright)) {
        DesignPicker(settings)
    }
    PreviewSection(settings)
}

@Composable
private fun ShapesStep(settings: KeyboardSettings) {
    StepTitle(
        "Сколько форм?",
        "В Material 3 Expressive много выразительных форм: «печеньки», цветы, клевер. Если для вас это перебор, выберите, сколько оставить."
    )
    ShapeAmountPicker(settings)
    PreviewSection(settings)
}

/** Функции на панели над клавиатурой, которые новичку не нужны с первого дня. */
internal val ONB_EXTRAS = listOf(
    "ai_fix" to "Исправление текста ИИ",
    "translate" to "Переводчик",
    "gif" to "GIF",
    "plugins" to "Плагины и моды"
)

@Composable
private fun FeaturesStep(settings: KeyboardSettings) {
    val context = LocalContext.current
    StepTitle(
        "Что вам нужно?",
        "Оставьте только то, чем будете пользоваться. Выключенное не мешает и не тратит заряд, а включить его можно в любой момент."
    )
    SettingsSection("Набор текста") {
        ToggleItem(Icons.Rounded.Lightbulb, "Подсказки слов", "Строка с вариантами над клавишами", settings.showSuggestions) { v ->
            KeyboardPrefs.edit(context) { putBoolean("show_suggestions", v) }
        }
        ToggleItem(Icons.Rounded.Gesture, "Свайп-набор", "Ведите пальцем по буквам, не отрывая", settings.glideTyping) { v ->
            KeyboardPrefs.edit(context) { putBoolean("glide_typing", v) }
        }
        ToggleItem(Icons.Rounded.Mic, "Голосовой ввод", "Кнопка микрофона в строке подсказок", settings.showMicButton) { v ->
            KeyboardPrefs.edit(context) { putBoolean("show_mic_button", v) }
        }
        ToggleItem(Icons.Rounded.ContentPaste, "История буфера обмена", "Последние скопированные тексты под рукой", settings.clipboardHistory) { v ->
            KeyboardPrefs.edit(context) { putBoolean("clipboard_history", v) }
        }
    }
    SettingsSection("Отклик") {
        ToggleItem(Icons.Rounded.Vibration, "Вибрация", "Лёгкий отклик на каждое нажатие", settings.hapticsEnabled) { v ->
            KeyboardPrefs.edit(context) { putBoolean("haptics_enabled", v) }
        }
        ToggleItem(Icons.AutoMirrored.Rounded.VolumeUp, "Звук клавиш", null, settings.soundEnabled) { v ->
            KeyboardPrefs.edit(context) { putBoolean("sound_enabled", v) }
        }
    }
    SettingsSection("Дополнительно на панели") {
        val hidden = settings.toolbarHiddenSet
        val icons = mapOf(
            "ai_fix" to Icons.Rounded.AutoFixHigh, "translate" to Icons.Rounded.Translate,
            "gif" to Icons.Rounded.GifBox, "plugins" to Icons.Rounded.Extension
        )
        ONB_EXTRAS.forEach { (id, title) ->
            ToggleItem(icons[id] ?: Icons.Rounded.Extension, title, null, id !in hidden) { on ->
                val next = if (on) hidden - id else hidden + id
                KeyboardPrefs.edit(context) { putString("toolbar_hidden", next.joinToString(",")) }
            }
        }
    }
}

@Composable
private fun DoneStep(settings: KeyboardSettings) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Spacer(Modifier.height(16.dp))
    StepBadge(Icons.Rounded.Check, ExpressiveShapePreset.SUNNY_8, settings)
    StepTitle("Всё готово", "Попробуйте клавиатуру прямо здесь. Тему, формы и функции можно поменять в настройках, они откроются после «Готово».")
    PillField(
        text, { text = it }, "Напишите что-нибудь…", Icons.Rounded.Keyboard,
        singleLine = false, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    )
}

// ----------------------------------------------------------------------- выбор количества форм

/** Три карточки: много форм, только акценты, без форм — с маленькими образцами. Есть и в «Цвета и фон». */
@Composable
internal fun ShapeAmountPicker(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val options = listOf(
        Triple(ShapeAmount.FULL, "Много форм", "Как задумано в Expressive"),
        Triple(ShapeAmount.ACCENTS, "Только акценты", "Формы у главных кнопок"),
        Triple(ShapeAmount.NONE, "Без форм", "Круги и скругления")
    )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, title, sub) ->
            val sel = settings.shapeAmount == id
            val bg by animateColorAsState(if (sel) cs.primaryContainer else cs.surfaceBright, label = "amountBg")
            val corner by animateDpAsState(if (sel) 32.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "amountCorner")
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(corner)).background(bg)
                    .then(if (sel) Modifier.border(2.dp, cs.primary, RoundedCornerShape(corner)) else Modifier)
                    .clickable { KeyboardPrefs.edit(context) { putString("shape_amount", id) } }
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val samples = when (id) {
                    ShapeAmount.FULL -> listOf(ExpressiveShapePreset.COOKIE_12, ExpressiveShapePreset.FLOWER_6, ExpressiveShapePreset.CLOVER_4)
                    ShapeAmount.ACCENTS -> listOf(ExpressiveShapePreset.COOKIE_12, ExpressiveShapePreset.CIRCLE, ExpressiveShapePreset.CIRCLE)
                    else -> listOf(ExpressiveShapePreset.CIRCLE, ExpressiveShapePreset.CIRCLE, ExpressiveShapePreset.CIRCLE)
                }
                Row(Modifier.height(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    samples.forEachIndexed { i, p ->
                        val color = if (i == 0) cs.primary else if (sel) cs.onPrimaryContainer.copy(alpha = 0.35f) else cs.outlineVariant
                        Box(Modifier.size(if (i == 0) 28.dp else 20.dp).wavyBackground(WavySpec.of(p), color))
                    }
                }
                Text(
                    tr(title), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                    color = if (sel) cs.onPrimaryContainer else cs.onSurface, maxLines = 2
                )
                Text(
                    tr(sub), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = if (sel) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant, maxLines = 2, minLines = 2
                )
            }
        }
    }
}
