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
// НАСТРОЙКИ: ТЕМА, ШРИФТ, ЦВЕТА И ФОН, КЛАВИШИ
// ============================================================================

/** Режим темы: светлая, тёмная, AMOLED или как в системе — вверху раздела «Темы». */
@Composable
internal fun ThemeModeItem(settings: KeyboardSettings) {
    val context = LocalContext.current
    ChoiceItem(
        Icons.Rounded.DarkMode, "Светлая или тёмная", "AMOLED — чистый чёрный для OLED-экранов",
        listOf("SYSTEM" to "Авто", "LIGHT" to "Светлая", "DARK" to "Тёмная", "AMOLED" to "AMOLED"),
        settings.themeMode
    ) { mode -> KeyboardPrefs.edit(context) { putString("theme_mode", mode) } }
}

/** Шрифт клавиш и форма кнопок-«печенек» — часть раздела «Клавиши и шрифт». */
@Composable
internal fun FontSections(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val name = withContext(Dispatchers.IO) { importCustomFont(context, uri) }
            if (name != null) {
                KeyboardPrefs.saveCustomFont(context, name)
                Toast.makeText(context, tr("Шрифт «{0}» применён", name), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, tr("Нужен корректный файл .ttf или .otf"), Toast.LENGTH_LONG).show()
        }
    }
    SettingsSection("Шрифт") {
        if (settings.skinPlugin.isNotEmpty()) {
            GroupItem {
                Text(
                    tr("У темы может быть свой шрифт — выбрать, какой важнее, можно в «Темы» → «Шрифт в этой теме»."),
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
        }
        ChoiceItem(
            Icons.Rounded.TextFields, "Начертание",
            if (settings.fontStyle == "CUSTOM") tr("Свой шрифт: {0}", settings.customFontName) else "Округлый — как на макете",
            listOf("ROUNDED" to "Округлый", "SYSTEM" to "Системный", "SERIF" to "Serif", "MONOSPACE" to "Mono", "PIXEL" to "Пиксели"),
            settings.fontStyle
        ) { f -> KeyboardPrefs.edit(context) { putString("font_style", f) } }
        SliderItem(
            icon = Icons.Rounded.FormatBold, title = "Толщина букв",
            value = settings.fontWeight.toFloat(), range = 300f..900f, steps = 5,
            format = {
                when (it.roundToInt() / 100 * 100) {
                    300 -> tr("Тонкий"); 400 -> tr("Обычный"); 500 -> tr("Средний"); 600 -> tr("Полужирный"); 700 -> tr("Жирный"); 800 -> tr("Очень жирный")
                    else -> tr("Чёрный")
                }
            },
            onCommit = { v -> KeyboardPrefs.edit(context) { putInt("font_weight", (v / 100f).roundToInt() * 100) } }
        )
        SliderItem(
            icon = Icons.Rounded.FormatSize, title = "Размер букв",
            value = settings.keyLabelScale, range = 0.7f..1.4f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_label_scale", v) } }
        )
        ActionItem(
            icon = Icons.Rounded.FontDownload,
            title = "Загрузить свой шрифт",
            subtitle = "Файл .ttf или .otf",
            onClick = { fontLauncher.launch("*/*") },
            trailing = if (settings.customFontName.isNotEmpty()) {
                {
                    IconButton(onClick = { KeyboardPrefs.resetCustomFont(context) }) {
                        Icon(Icons.Rounded.DeleteOutline, tr("Удалить шрифт"), tint = cs.error)
                    }
                }
            } else null
        )
    }
    // волнистая форма «печенек»: кнопки меню и микрофона, подсказка над клавишей (если у темы нет своих)
    SettingsSection("Форма кнопок") {
        GroupItem { Column(Modifier.padding(vertical = 16.dp)) { ShapePicker(settings) } }
        SliderItem(
            icon = Icons.Rounded.AutoAwesome, title = "Количество волн",
            value = settings.waveCount.toFloat(), range = 4f..18f, steps = 13,
            format = { "${it.roundToInt()}" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putInt("wave_count", v.roundToInt()) } }
        )
        SliderItem(
            icon = Icons.Rounded.Tune, title = "Глубина волн",
            value = settings.waveAmplitudePercent, range = 0f..14f,
            format = { "${it.roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("wave_amplitude_percent", v) } }
        )
    }
}

// ----------------------------------------------------------------------- цвета и фон

private data class ColorTarget(val key: String, val title: String)

@Composable
internal fun ColorsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<ColorTarget?>(null) }
    val auto = remember(cs) { buildKeyPalette(cs, KeyboardSettings()) }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { importBackground(context, uri) }
            if (ok) KeyboardPrefs.setBackgroundImage(context)
            else Toast.makeText(context, tr("Не удалось открыть картинку"), Toast.LENGTH_LONG).show()
        }
    }
    val monet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    PreviewSection(settings)
    // акцент — главный цвет клавиатуры: вверху и в одном месте (раньше он терялся среди цветов клавиш)
    val themeColors = PluginRepository.skinPlugin(settings)?.skin?.forMode(keyboardIsDark(settings))?.colors
    val themeAccent = themeColors?.get("accent")?.let { Color(it) }
    val themeAccentText = themeColors?.get("accentText")?.let { Color(it) }
    SettingsSection("Акцентный цвет") {
        ColorRow("Акцент: Enter, Shift, нажатие, подсветка", settings.colorAccent, themeAccent ?: auto.accent) { picker = ColorTarget("color_accent", "Акцент") }
        ColorRow("Текст на акценте", settings.colorAccentText, themeAccentText ?: auto.onAccent) { picker = ColorTarget("color_accent_text", "Текст на акценте") }
        GroupItem {
            Text(
                tr(
                    if (settings.skinPlugin.isNotEmpty()) "Акцент перекрашивает и выбранную тему: Enter, подсветку, свечение. Отключить для темы — «Темы» → «Красить в мой акцентный цвет». «Авто» — цвет самой темы."
                    else "«Авто» — цвет из палитры Material You ниже."
                ),
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
    }
    SettingsSection("Формы") {
        GroupItem {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShapeAmountPicker(settings)
                Text(
                    tr("«Печеньки», цветы и клевер из Material 3 Expressive. «Только акценты» оставляет их у меню, микрофона и главной карточки, «Без форм» убирает совсем."),
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
        }
    }
    SettingsSection("Палитра") {
        ChoiceItem(
            Icons.Rounded.Palette, "Палитра Material You",
            if (settings.colorSource == "SEED") "Вся тема строится из выбранного цвета"
            else if (monet) "Цвета берутся из обоев (Monet)" else "Monet доступен с Android 12 — используется базовая палитра",
            listOf("DYNAMIC" to "Из обоев", "SEED" to "Свой цвет"),
            settings.colorSource
        ) { v -> KeyboardPrefs.edit(context) { putString("color_source", v) } }
        AnimatedVisibility(
            visible = settings.colorSource == "SEED",
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            GroupItem {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { c ->
                                ColorDot(Color(c), settings.seedColor == c) { KeyboardPrefs.edit(context) { putInt("seed_color", c) } }
                            }
                        }
                    }
                    FilledTonalButton(onClick = { picker = ColorTarget("seed_color", "Основной цвет") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Выбрать любой цвет"))
                    }
                }
            }
        }
    }
    SettingsSection("Цвета клавиш") {
        if (settings.skinPlugin.isNotEmpty()) {
            GroupItem {
                Text(
                    tr("У темы свои цвета клавиш — здесь они меняются только там, где тема их не задаёт."),
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
        }
        ColorRow("Фон клавиатуры", settings.colorBackground, auto.background) { picker = ColorTarget("color_background", "Фон клавиатуры") }
        ColorRow("Клавиши букв", settings.colorKey, auto.letter) { picker = ColorTarget("color_key", "Клавиши букв") }
        ColorRow("Текст на буквах", settings.colorKeyText, auto.onLetter) { picker = ColorTarget("color_key_text", "Текст на буквах") }
        ColorRow("Служебные клавиши", settings.colorFunc, auto.func) { picker = ColorTarget("color_func", "Служебные клавиши") }
        ColorRow("Текст служебных", settings.colorFuncText, auto.onFunc) { picker = ColorTarget("color_func_text", "Текст служебных") }
        ToggleItem(
            Icons.Rounded.Layers, "Подложка клавиш",
            if (settings.keyBackground) "Плашки под буквами — как в теме"
            else "Без плашек: только буквы на фоне. Их не видно и при размытии",
            settings.keyBackground
        ) { on -> KeyboardPrefs.edit(context) { putBoolean("key_background", on) } }
        SliderItem(
            icon = Icons.Rounded.Opacity, title = "Непрозрачность клавиш",
            value = settings.keyOpacity, range = 0.2f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_opacity", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Opacity, title = "Прозрачность фона",
            value = settings.bgTransparency, range = 0f..1f,
            format = { if (it < 0.01f) tr("Нет") else "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bg_transparency", v) } }
        )
        ActionItem(Icons.Rounded.RestartAlt, "Вернуть цвета темы", "Сбросить акцент, ручные цвета и прозрачность", onClick = { KeyboardPrefs.resetColors(context) })
    }
    // система может запретить размытие окон: экономия заряда, «Уменьшить прозрачность», слабая видеокарта
    val blurAllowed = rememberWindowBlurSupported()
    SettingsSection("Размытие") {
        ChoiceItem(
            Icons.Rounded.BlurOn, "Размытие под клавиатурой",
            when {
                Build.VERSION.SDK_INT < 31 -> "Нужен Android 12 или новее"
                settings.windowBlur != "OFF" && !blurAllowed -> "Сейчас система не разрешает размытие окон: включена экономия заряда, «Уменьшить прозрачность/размытие» в спец. возможностях, или телефон его не поддерживает"
                settings.windowBlur == "ON" -> "Приложение под клавиатурой видно сквозь матовый фон. Расходует заряд: экран перерисовывается видеокартой"
                settings.windowBlur == "OFF" -> "Обычный фон — экономнее всего"
                else -> "Если тема его просит (например, «Жидкое стекло»)"
            },
            listOf("THEME" to "Как в теме", "ON" to "Вкл", "OFF" to "Выкл"), settings.windowBlur
        ) { v -> KeyboardPrefs.edit(context) { putString("window_blur", v) } }
        if (settings.windowBlur != "OFF") {
            SliderItem(
                Icons.Rounded.Opacity, "Сила размытия", settings.windowBlurDp, 4f..100f, { "${it.roundToInt()} dp" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("window_blur_dp", v) } }
            )
        }
    }
    SettingsSection("Фоновая картинка") {
        ActionItem(
            icon = Icons.Rounded.Wallpaper,
            title = if (settings.hasBackgroundImage) "Сменить картинку" else "Выбрать картинку",
            subtitle = "Фото из галереи под клавишами",
            onClick = { bgLauncher.launch("image/*") },
            trailing = if (settings.hasBackgroundImage) {
                {
                    IconButton(onClick = { KeyboardPrefs.clearBackgroundImage(context) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Убрать картинку", tint = cs.error)
                    }
                }
            } else null
        )
        AnimatedVisibility(settings.hasBackgroundImage) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SliderItem(
                    icon = Icons.Rounded.Brightness6, title = "Затемнение",
                    value = settings.bgDim, range = 0f..0.9f,
                    format = { "${(it * 100).roundToInt()}%" },
                    onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bg_dim", v) } }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SliderItem(
                        icon = Icons.Rounded.BlurOn, title = "Размытие",
                        value = settings.bgBlurDp, range = 0f..24f,
                        format = { if (it < 0.5f) tr("Нет") else "${it.roundToInt()} dp" },
                        onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bg_blur_dp", v) } }
                    )
                }
            }
        }
    }

    picker?.let { t ->
        val current = when (t.key) {
            "seed_color" -> settings.seedColor
            "color_background" -> settings.colorBackground
            "color_key" -> settings.colorKey
            "color_key_text" -> settings.colorKeyText
            "color_func" -> settings.colorFunc
            "color_func_text" -> settings.colorFuncText
            "color_accent" -> settings.colorAccent
            else -> settings.colorAccentText
        }
        ColorPickerDialog(t.title, current, allowAuto = t.key != "seed_color", onDismiss = { picker = null }) { c ->
            KeyboardPrefs.edit(context) {
                putInt(t.key, c)
                if (t.key == "seed_color") putString("color_source", "SEED")
            }
            picker = null
        }
    }
}

// ----------------------------------------------------------------------- клавиши

@Composable
internal fun KeysPage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    PreviewSection(settings)
    SettingsSection("Размеры") {
        SliderItem(
            icon = Icons.Rounded.Height, title = "Высота клавиш",
            // на планшете (от 600 dp) клавиши можно сделать заметно выше
            value = settings.keyHeightDp, range = 32f..(if (LocalConfiguration.current.smallestScreenWidthDp >= 600) 96f else 68f),
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_height_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Straighten, title = "Ширина клавиатуры",
            value = settings.keyboardWidth, range = 0.5f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("keyboard_width", if (v > 0.98f) 1f else v) } }
        )
        LandscapeScaleItem(settings)
        SliderItem(
            icon = Icons.Rounded.RoundedCorner, title = "Скругление клавиш",
            value = settings.cornerRadiusDp, range = 6f..30f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("corner_radius_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.SpaceBar, title = "Промежуток между клавишами",
            value = settings.keySpacingDp, range = 2f..8f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_spacing_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Menu, title = "Промежуток между рядами",
            value = settings.rowSpacingDp, range = 2f..12f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("row_spacing_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Straighten, title = "Отступы по бокам",
            value = settings.sidePaddingDp, range = 0f..32f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("side_padding_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.VerticalAlignBottom, title = "Отступ снизу",
            value = settings.bottomOffsetDp, range = 0f..64f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bottom_offset_dp", v) } }
        )
    }
    SettingsSection("Режим одной руки") {
        ChoiceItem(
            Icons.Rounded.SwapHoriz, "Положение", "Также включается кнопкой ⇄ на панели инструментов",
            listOf("OFF" to "Выкл", "LEFT" to "Слева", "RIGHT" to "Справа"), settings.oneHanded
        ) { v -> KeyboardPrefs.edit(context) { putString("one_handed", v) } }
        SliderItem(
            icon = Icons.Rounded.Straighten, title = "Ширина клавиатуры",
            value = settings.oneHandedWidth, range = 0.55f..0.9f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("one_handed_w", v) } }
        )
    }
    SettingsSection("Плавающая клавиатура") {
        ToggleItem(
            Icons.Rounded.PictureInPictureAlt, "Плавающая клавиатура",
            "Небольшое окно поверх приложения: перетаскивайте за полоску сверху. Также включается кнопкой на панели инструментов",
            settings.floating
        ) { KeyboardPrefs.edit(context) { putBoolean("floating_keyboard", it) } }
        AnimatedVisibility(settings.floating) {
            SliderItem(
                icon = Icons.Rounded.Straighten, title = "Ширина плавающей клавиатуры",
                value = settings.floatWidth, range = 0.5f..1f,
                format = { "${(it * 100).roundToInt()}%" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("float_width", v) } }
            )
        }
    }
    SettingsSection("Клавиши и кнопки") {
        ToggleItem(Icons.Rounded.Dialpad, "Ряд цифр", null, settings.showNumberRow) {
            KeyboardPrefs.edit(context) { putBoolean("show_number_row", it) }
        }
        ToggleItem(Icons.AutoMirrored.Rounded.Label, "Подсказки на клавишах", "Маленький символ, доступный по удержанию", settings.showHints) {
            KeyboardPrefs.edit(context) { putBoolean("show_hints", it) }
        }
        ChoiceItem(
            Icons.Rounded.PanTool, "Удержание буквы",
            if (settings.holdMode == "SYMBOLS") "Символ, который стоит на том же месте на странице «?123», — как в Gboard"
            else "Варианты буквы (ё, ъ, буквы с ударением) и символы",
            listOf("VARIANTS" to "Варианты", "SYMBOLS" to "Как в Gboard"), settings.holdMode
        ) { v -> KeyboardPrefs.edit(context) { putString("hold_mode", v) } }
        ToggleItem(
            Icons.Rounded.ScreenRotation, "Раздельная клавиатура в альбомной",
            "Повернули телефон — клавиатура делится на две половины по краям, до всех букв легко дотянуться",
            settings.landscapeSplit
        ) { KeyboardPrefs.edit(context) { putBoolean("landscape_split", it) } }
        ToggleItem(Icons.Rounded.TouchApp, "Всплывающая буква при нажатии", null, settings.keyPreview) {
            KeyboardPrefs.edit(context) { putBoolean("key_preview", it) }
        }
        ToggleItem(Icons.Rounded.BorderOuter, "Контур клавиш", null, settings.showKeyBorders) {
            KeyboardPrefs.edit(context) { putBoolean("show_key_borders", it) }
        }
        ToggleItem(Icons.Rounded.SelectAll, "Панель редактирования", "Над клавиатурой: курсор, выделение, копировать и вставить", settings.editBar) {
            KeyboardPrefs.edit(context) { putBoolean("edit_bar", it) }
        }
        ChoiceItem(
            Icons.Rounded.TouchApp, "Удержание кнопки панели функций",
            "Короткое нажатие открывает и закрывает панель инструментов",
            listOf("PIN" to "Закрепить панель", "EDIT_BAR" to "Панель редактирования"),
            settings.toolsLongPress
        ) { v ->
            KeyboardPrefs.edit(context) {
                putString("tools_long_press", v)
                // выбор «панель редактирования» освобождает кнопку от закреплённой строки инструментов
                if (v == "EDIT_BAR") putBoolean("pinned_tools", false)
            }
        }
        ToggleItem(Icons.Rounded.Language, "Кнопка смены языка", "Без неё язык меняется свайпом по пробелу", settings.showGlobeKey) {
            KeyboardPrefs.edit(context) { putBoolean("show_globe_key", it) }
        }
        if (settings.showGlobeKey) {
            val globeActions = listOf(
                "LANGUAGES" to "Список языков", "SYSTEM" to "Выбор клавиатуры", "EMOJI" to "Эмодзи", "CLIPBOARD" to "Буфер обмена",
                "VOICE" to "Голосовой ввод", "SETTINGS" to "Настройки", "ONE_HANDED" to "Режим одной руки", "OFF" to "Ничего"
            )
            ChoiceItem(
                Icons.Rounded.TouchApp, "Удержание кнопки языка",
                globeActions.firstOrNull { it.first == settings.globeLongPress }?.second ?: "Список языков",
                globeActions, settings.globeLongPress
            ) { v -> KeyboardPrefs.edit(context) { putString("globe_long_press", v) } }
        }
        ToggleItem(Icons.Rounded.MoreHoriz, "Запятая и точка на странице ?123", "По бокам пробела. Удержание — другие знаки: ; : ' \" ? ! …", settings.punctuationKeys) {
            KeyboardPrefs.edit(context) { putBoolean("punctuation_keys", it) }
        }
        ToggleItem(Icons.Rounded.EmojiEmotions, "Кнопка эмодзи", null, settings.showEmojiKey) {
            KeyboardPrefs.edit(context) { putBoolean("show_emoji_key", it) }
        }
        SpaceSlotItem(
            Icons.Rounded.SpaceBar, "Клавиша слева от пробела", settings.spaceLeftKey, "space_left_key",
            "Например, запятая. Удержание «,» — ; : ' \" и другие знаки"
        )
        SpaceSlotItem(
            Icons.Rounded.SpaceBar, "Клавиша справа от пробела", settings.spaceRightKey, "space_right_key",
            "Например, точка. Удержание «.» — ? ! … и другие знаки"
        )
        ToggleItem(Icons.Outlined.Mic, "Кнопка микрофона", null, settings.showMicButton) {
            KeyboardPrefs.edit(context) { putBoolean("show_mic_button", it) }
        }
        ToggleItem(
            Icons.Rounded.Dialpad, "Цифровая клавиатура для чисел",
            "В полях ввода числа и номера телефона — крупные цифры 4×4, как у системной клавиатуры",
            settings.numberPad
        ) { KeyboardPrefs.edit(context) { putBoolean("number_pad", it) } }
        ToggleItem(
            Icons.Rounded.VerticalAlignTop, "Верхняя панель",
            "Подсказки, микрофон и кнопки над клавиатурой. Если выключить, клавиатура станет ниже",
            settings.showHeader
        ) { KeyboardPrefs.edit(context) { putBoolean("show_header", it) } }
        ToggleItem(Icons.Rounded.Lightbulb, "Строка подсказок", "Если выключить, наверху всегда будут инструменты", settings.showSuggestions) {
            KeyboardPrefs.edit(context) { putBoolean("show_suggestions", it) }
        }
        ActionItem(Icons.Rounded.SwapHoriz, "Свои места клавиш", "Поменяйте местами буквы, цифры и знаки или поставьте свои", onClick = {
            onNavigate(SettingsPage.LAYOUT)
        })
    }
    FontSections(settings)
}

/** Своя клавиша у пробела на буквенной раскладке: нет, «,», «.» или любой символ (до 4 знаков). */
@Composable
private fun SpaceSlotItem(icon: ImageVector, title: String, value: String, prefKey: String, subtitle: String) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    val selected = when (value) {
        "", ",", "." -> value
        else -> "custom"
    }
    ChoiceItem(
        icon, title, if (selected == "custom") tr("Своя: «{0}»", value) else subtitle,
        listOf("" to "Нет", "," to ",", "." to ".", "custom" to "Своя…"), selected
    ) { v ->
        if (v == "custom") dialog = true else KeyboardPrefs.edit(context) { putString(prefKey, v) }
    }
    if (dialog) {
        TextInputDialog(title, if (selected == "custom") value else "", "Символ, буква или эмодзи — до 4 знаков", onDismiss = { dialog = false }) { v ->
            KeyboardPrefs.edit(context) { putString(prefKey, v.trim().take(4)) }
            dialog = false
        }
    }
}

// ----------------------------------------------------------------------- ввод
