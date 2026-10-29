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
// 11. ЭКРАН НАСТРОЕК В СТИЛЕ MATERIAL 3 EXPRESSIVE (ANDROID 16)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardPrefs.ensureInit(this)
        WordPredictorEngine.ensureInitialized(this)
        IconStore.ensureLoaded(this)
        PluginRepository.ensureLoaded(this)
        EmojiCatalog.warmUp(this)
        enableEdgeToEdge()
        setContent {
            val settings by KeyboardPrefs.observe()
            ExpressiveTheme(settings, appUi = true) { SettingsScreen(settings) }
        }
    }
}

enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector, val shape: ExpressiveShapePreset) {
    HOME("Expressive Keyboard", "", Icons.Rounded.Keyboard, ExpressiveShapePreset.COOKIE_12),
    THEMES("Темы", "Светлая или тёмная; Minecraft, Samsung, Nothing, стекло; настройки темы и свои темы", Icons.Rounded.Style, ExpressiveShapePreset.FLOWER_6),
    THEME_EDITOR("Редактор темы", "Всё оформление: фон, клавиши, форма, тени, шрифт", Icons.Rounded.Brush, ExpressiveShapePreset.SUNNY_8),
    COLORS("Цвета и фон", "Акцентный цвет, палитра Material You, цвета клавиш, картинка и размытие", Icons.Rounded.ColorLens, ExpressiveShapePreset.FLOWER_6),
    KEYS("Клавиши и шрифт", "Размер, шрифт, кнопки у пробела, ряд цифр, одна рука, свои места клавиш", Icons.Rounded.Keyboard, ExpressiveShapePreset.CLOVER_4),
    LAYOUT("Свои места клавиш", "Поменяйте местами любые клавиши: буквы, цифры, пробел, запятую, эмодзи…", Icons.Rounded.SwapHoriz, ExpressiveShapePreset.SUNNY_8),
    ANIMATIONS("Анимации", "Нажатие клавиш, свои анимации, скорость, подсказки и панели", Icons.Rounded.Animation, ExpressiveShapePreset.CLOVER_4),
    TOOLBAR("Панель, эмодзи и иконки", "Кнопки панели инструментов, стиль эмодзи, свои иконки", Icons.Rounded.DragIndicator, ExpressiveShapePreset.FLOWER_6),
    LANGUAGES("Языки", "Раскладки, китайский и японский ввод, словари подсказок", Icons.Rounded.Language, ExpressiveShapePreset.COOKIE_12),
    TYPING("Подсказки и исправления", "Автоисправление, тире, заглавные буквы, ИИ-исправление, инкогнито", Icons.Rounded.Spellcheck, ExpressiveShapePreset.SUNNY_8),
    GESTURES("Жесты и скорость", "Свайп-набор, свайпы по пробелу и ⌫, удержание, парные скобки", Icons.Rounded.Gesture, ExpressiveShapePreset.CLOVER_4),
    CLIPBOARD("Буфер обмена и GIF", "История, сколько хранить, скриншоты в буфере, GIF", Icons.Rounded.ContentPaste, ExpressiveShapePreset.FLOWER_6),
    FEEDBACK("Вибрация и звук", "Сила, длительность и характер отклика", Icons.Rounded.Vibration, ExpressiveShapePreset.COOKIE_12),
    PLUGINS("Плагины и моды", "Скрипты, свои панели и кнопки, рисование поверх клавиатуры, темы", Icons.Rounded.Extension, ExpressiveShapePreset.FLOWER_6),
    BACKUP("Резервная копия", "Экспорт и импорт настроек, сброс, выученные слова", Icons.Rounded.Backup, ExpressiveShapePreset.CLOVER_4),
    ABOUT("О приложении", "Версия, возможности и лицензии", Icons.Rounded.Info, ExpressiveShapePreset.CIRCLE);

    /** Куда ведёт «Назад»: вложенные страницы — в свой раздел, остальные — на главную. */
    val parent: SettingsPage
        get() = when (this) {
            THEME_EDITOR -> THEMES
            LAYOUT -> KEYS
            else -> HOME
        }
}

private const val FEATURE_AVAILABLE = com.google.mlkit.genai.common.FeatureStatus.AVAILABLE
private const val FEATURE_DOWNLOADABLE = com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE
private const val FEATURE_DOWNLOADING = com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING

internal fun isImeEnabled(context: Context): Boolean = runCatching {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)

internal fun isImeSelected(context: Context): Boolean = runCatching {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
    id.substringBefore('/') == context.packageName
}.getOrDefault(false)

internal fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

internal fun importCustomFont(context: Context, uri: Uri): String? = runCatching {
    var name = "CustomFont.ttf"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
    }
    val temp = File(context.cacheDir, "font_check.ttf")
    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return null
    if (AndroidTypeface.Builder(temp).build() == null) {
        temp.delete()
        return null
    }
    temp.copyTo(File(context.filesDir, KeyboardPrefs.CUSTOM_FONT_FILENAME), overwrite = true)
    temp.delete()
    name
}.getOrNull()

/** Картинка уменьшается до ~1600 px и пересжимается — клавиатура не держит в памяти 50-мегапиксельное фото. */
internal fun importBackground(context: Context, uri: Uri): Boolean = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val maxSide = max(bounds.outWidth, bounds.outHeight)
    if (maxSide <= 0) return false
    var sample = 1
    while (maxSide / (sample * 2) >= 1600) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return false
    File(context.filesDir, KeyboardPrefs.BG_IMAGE_FILENAME).outputStream().use {
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
    }
    bmp.recycle()
    true
}.getOrDefault(false)

@Composable
fun SettingsScreen(settings: KeyboardSettings) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    androidx.activity.compose.BackHandler(enabled = page != SettingsPage.HOME) {
        page = page.parent
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = initialState == targetState.parent || initialState == SettingsPage.HOME
            val enterSpring = spring<IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
            if (forward) {
                (slideInHorizontally(enterSpring) { it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(enterSpring) { -it / 6 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(enterSpring) { -it / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(enterSpring) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "settingsPage"
    ) { p ->
        SettingsPageScaffold(p, settings, onNavigate = { page = it })
    }
}

@Composable
internal fun SettingsPageScaffold(page: SettingsPage, settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    var testText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val showTest = page != SettingsPage.ABOUT && page != SettingsPage.BACKUP
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = cs.surfaceContainer,
        topBar = {
            LargeTopAppBar(
                title = { Text(tr(page.title), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (page != SettingsPage.HOME) {
                        FilledTonalIconButton(onClick = { onNavigate(page.parent) }, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = cs.surfaceContainer, scrolledContainerColor = cs.surfaceContainer),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showTest) {
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text(tr("Проверьте клавиатуру здесь…")) },
                    leadingIcon = { Icon(Icons.Rounded.Keyboard, null) },
                    trailingIcon = {
                        if (testText.isNotEmpty()) IconButton(onClick = { testText = "" }) { Icon(Icons.Rounded.Close, null) }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                when (page) {
                    SettingsPage.HOME -> HomePage(settings, onNavigate)
                    SettingsPage.THEMES -> ThemesPage(settings, onNavigate)
                    SettingsPage.THEME_EDITOR -> ThemeEditorPage(settings, onNavigate)
                    SettingsPage.ANIMATIONS -> AnimationsPage(settings)
                    SettingsPage.COLORS -> ColorsPage(settings)
                    SettingsPage.KEYS -> KeysPage(settings, onNavigate)
                    SettingsPage.LAYOUT -> LayoutEditorPage(settings)
                    SettingsPage.TOOLBAR -> {
                        ToolbarPage(settings)
                        IconsPage(settings)
                    }
                    SettingsPage.LANGUAGES -> LanguagesPage(settings)
                    SettingsPage.TYPING -> TypingPage(settings, SettingsPage.TYPING)
                    SettingsPage.GESTURES -> TypingPage(settings, SettingsPage.GESTURES)
                    SettingsPage.CLIPBOARD -> TypingPage(settings, SettingsPage.CLIPBOARD)
                    SettingsPage.FEEDBACK -> FeedbackPage(settings)
                    SettingsPage.PLUGINS -> PluginsPage()
                    SettingsPage.BACKUP -> BackupPage()
                    SettingsPage.ABOUT -> AboutPage()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
internal fun rememberSpec(settings: KeyboardSettings) =
    remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent) { settings.accentSpec() }

/** Предпросмотр клавиатуры: с выбранной темой — её настоящие клавиши (раньше всегда показывалась стандартная). */
@Composable
internal fun PreviewSection(settings: KeyboardSettings) {
    val dark = keyboardIsDark(settings)
    val plugin = PluginRepository.skinPlugin(settings)
    SettingsSection("Предпросмотр") {
        GroupItem {
            val base = plugin?.skin
            if (plugin != null && base != null) {
                val skin = remember(plugin, settings.skinOptions, settings.userAccent, settings.colorAccentText, settings.keyOpacity, dark) {
                    runCatching { ThemeOptions.build(plugin, base, ThemeOptions.values(settings, plugin.id), settings, dark) }.getOrNull()
                }
                SkinMiniPreview(skin, settings, Modifier.fillMaxWidth().height(170.dp).padding(12.dp), interactive = true)
            } else StylePreview(settings, rememberSpec(settings))
        }
    }
}

// ----------------------------------------------------------------------- главная

@Composable
internal fun HomePage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var imeSelected by remember { mutableStateOf(isImeSelected(context)) }
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    // окно получает фокус после возврата из системных настроек и после закрытия выбора клавиатуры
    LaunchedEffect(focused) {
        if (focused) {
            imeEnabled = isImeEnabled(context)
            imeSelected = isImeSelected(context)
            micGranted = hasMicPermission(context)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    AuthorLine()
    UpdateCard()
    SettingsSection(null) {
        ChoiceItem(
            Icons.Rounded.Translate, "Язык приложения", null,
            listOf("SYSTEM" to "Как в системе", "ru" to "Русский", "en" to "English"), settings.appLanguage
        ) { v -> KeyboardPrefs.edit(context) { putString("app_language", v) } }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(tr("Найти настройку: вибрация, шрифт, свайп…")) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) } },
        singleLine = true
    )
    if (query.isNotBlank()) {
        val found = SettingsSearch.find(query)
        SettingsSection(tr("Найдено: {0}", found.size)) {
            if (found.isEmpty()) GroupItem { Text(tr("Ничего не нашлось"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            found.forEach { (title, p) ->
                ActionItem(p.icon, title, tr(p.title), onClick = { onNavigate(p) })
            }
        }
        return
    }
    HeroCard(
        spec = rememberSpec(settings),
        imeEnabled = imeEnabled,
        imeSelected = imeSelected,
        micGranted = micGranted,
        onEnable = {
            runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        onSelect = { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() },
        onMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    )
    PreviewSection(settings)
    val groups = listOf(
        "Внешний вид" to listOf(SettingsPage.THEMES, SettingsPage.COLORS, SettingsPage.KEYS, SettingsPage.LAYOUT, SettingsPage.ANIMATIONS, SettingsPage.TOOLBAR),
        "Набор текста" to listOf(SettingsPage.LANGUAGES, SettingsPage.TYPING, SettingsPage.GESTURES, SettingsPage.CLIPBOARD),
        "Отклик и расширения" to listOf(SettingsPage.FEEDBACK, SettingsPage.PLUGINS),
        "Прочее" to listOf(SettingsPage.BACKUP, SettingsPage.ABOUT)
    )
    var i = 0
    for ((title, pages) in groups) {
        SettingsSection(title) {
            for (p in pages) {
                val idx = i++
                CategoryTile(p, idx) { onNavigate(p) }
            }
        }
    }
}

// ----------------------------------------------------------------------- внешний вид

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
            value = settings.keyHeightDp, range = 32f..68f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_height_dp", v) } }
        )
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

@Composable
internal fun TypingPage(settings: KeyboardSettings, part: SettingsPage) {
    val context = LocalContext.current
    var giphyDialog by remember { mutableStateOf(false) }
    var klipyDialog by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf<String?>(null) }
    // поддерживает ли телефон исправление на устройстве (Gemini Nano) — для подписи
    var nanoStatus by remember { mutableStateOf<Int?>(-1) }
    LaunchedEffect(part) {
        if (part == SettingsPage.TYPING) nanoStatus = AiFix.nanoStatus(context, "en")
    }
    if (part == SettingsPage.GESTURES) SettingsSection("Скорость") {
        ToggleItem(Icons.Rounded.Bolt, "Мгновенный ввод", "Буква появляется в момент касания, а не при отпускании пальца", settings.instantInput) {
            KeyboardPrefs.edit(context) { putBoolean("instant_input", it) }
        }
    }
    if (part == SettingsPage.GESTURES) SettingsSection("Печать без отрыва пальца") {
        ToggleItem(Icons.Rounded.Gesture, "Свайп-набор", "Ведите палец от буквы к букве — слово вставится само, ещё варианты — в подсказках", settings.glideTyping) {
            KeyboardPrefs.edit(context) { putBoolean("glide_typing", it) }
        }
        AnimatedVisibility(settings.glideTyping) {
            ToggleItem(Icons.Rounded.Timeline, "След за пальцем", null, settings.glideTrail) {
                KeyboardPrefs.edit(context) { putBoolean("glide_trail", it) }
            }
        }
    }
    if (part == SettingsPage.GESTURES) SettingsSection("Удобства") {
        ToggleItem(Icons.Rounded.North, "Свайп вверх по клавише", "Вводит маленький символ-подсказку без удержания", settings.swipeUpHint) {
            KeyboardPrefs.edit(context) { putBoolean("swipe_up_hint", it) }
        }
        val spaceActions = listOf(
            "PICKER" to "Выбор клавиатуры", "AI_FIX" to "Улучшить текст", "VOICE" to "Голосовой ввод", "EMOJI" to "Эмодзи",
            "CLIPBOARD" to "Буфер обмена", "TOOLBAR" to "Панель функций", "SETTINGS" to "Настройки", "OFF" to "Ничего"
        )
        ChoiceItem(
            Icons.Rounded.SpaceBar, "Удержание пробела",
            spaceActions.firstOrNull { it.first == settings.spaceLongAction }?.second ?: "Выбор клавиатуры",
            spaceActions, settings.spaceLongAction
        ) { v ->
            KeyboardPrefs.edit(context) {
                putString("space_long_action", v)
                putBoolean("space_long_press", v != "OFF")
            }
        }
        ToggleItem(Icons.Rounded.DataArray, "Парные скобки", "( [ { « сразу закрываются, курсор встаёт внутрь", settings.autoPair) {
            KeyboardPrefs.edit(context) { putBoolean("auto_pair", it) }
        }
    }
    if (part == SettingsPage.TYPING) SettingsSection("Исправление текста ИИ") {
        InfoCard(
            Icons.Rounded.AutoFixHigh,
            "Кнопка ✨ на панели инструментов открывает панель ИИ: исправить, деловой или дружелюбный тон, короче, эмоциональнее, эмодзи, " +
                "подробнее, дописать и своё указание. Работает с выделенным текстом, а если ничего не выделено — со всем текстом в поле. " +
                "Исправлять умеют все сервисы, остальное — только ИИ по ключу. Облачные сервисы получают обрабатываемый текст."
        )
        val providers = AiProvider.entries.map { it.id to it.title }
        val nanoText = when (nanoStatus) {
            -1 -> "проверяю…"
            FEATURE_AVAILABLE -> "готово"
            FEATURE_DOWNLOADABLE, FEATURE_DOWNLOADING -> "скачается при первом исправлении"
            else -> "этот телефон не поддерживает"
        }
        ChoiceItem(
            Icons.Rounded.AutoFixHigh, "Кто исправляет",
            when (AiProvider.from(settings.aiProvider)) {
                AiProvider.AUTO -> tr("Сначала на телефоне, потом сервис с ключом, потом LanguageTool")
                AiProvider.NANO -> tr("Gemini Nano на телефоне: без интернета, английский и ещё 6 языков (без русского) — {0}", tr(nanoText))
                AiProvider.GEMINI -> tr("Google Gemini — бесплатный ключ в Google AI Studio")
                AiProvider.CLAUDE -> tr("Claude от Anthropic — платный ключ")
                AiProvider.OPENAI -> tr("OpenAI — платный ключ")
                AiProvider.GROQ -> tr("Groq — быстрый, есть бесплатный ключ")
                AiProvider.LANGUAGETOOL -> tr("Бесплатная проверка без ключа (не ИИ)")
            },
            providers, settings.aiProvider
        ) { v -> KeyboardPrefs.edit(context) { putString("ai_provider", v) } }
        ActionItem(
            Icons.Rounded.Key, "Ключ Gemini",
            if (settings.geminiKey.isBlank()) "Бесплатно: aistudio.google.com → Get API key" else "Задан",
            onClick = { keyDialog = "gemini_key" }
        )
        ActionItem(
            Icons.Rounded.Key, "Ключ Claude API",
            if (settings.anthropicKey.isBlank()) "console.anthropic.com" else "Задан",
            onClick = { keyDialog = "anthropic_key" }
        )
        ActionItem(
            Icons.Rounded.Key, "Ключ OpenAI",
            if (settings.openaiKey.isBlank()) "platform.openai.com" else "Задан",
            onClick = { keyDialog = "openai_key" }
        )
        ActionItem(
            Icons.Rounded.Key, "Ключ Groq",
            if (settings.groqKey.isBlank()) "Бесплатно: console.groq.com" else "Задан",
            onClick = { keyDialog = "groq_key" }
        )
        ToggleItem(
            Icons.Rounded.AutoFixHigh, "Чистая диктовка (ИИ)",
            "Как Rambler в Pixel: из голосового ввода убираются «э-э», повторы и оговорки, расставляются знаки. Нужен ключ ИИ",
            settings.aiVoiceCleanup
        ) { KeyboardPrefs.edit(context) { putBoolean("ai_voice_cleanup", it) } }
    }
    keyDialog?.let { k ->
        val (title, current, hint) = when (k) {
            "gemini_key" -> Triple("Ключ Gemini", settings.geminiKey, "Ключ с aistudio.google.com")
            "openai_key" -> Triple("Ключ OpenAI", settings.openaiKey, "Ключ с platform.openai.com")
            "groq_key" -> Triple("Ключ Groq", settings.groqKey, "Ключ с console.groq.com")
            else -> Triple("Ключ Claude API", settings.anthropicKey, "Ключ с console.anthropic.com")
        }
        TextInputDialog(title, current, hint, onDismiss = { keyDialog = null }) { v ->
            KeyboardPrefs.edit(context) { putString(k, SecretBox.encrypt(v.trim())) }
            keyDialog = null
        }
    }
    if (part == SettingsPage.TYPING) SettingsSection("Исправления") {
        ToggleItem(
            Icons.Rounded.Spellcheck, "Автоисправление",
            "Исправляет опечатки по пробелу с учётом того, куда попал палец, и частые ошибки («извени», «знаеш»). ⌫ сразу после — отмена",
            settings.autoCorrect
        ) {
            KeyboardPrefs.edit(context) { putBoolean("auto_correct_enabled", it) }
        }
        ToggleItem(
            Icons.Rounded.TouchApp, "Динамические зоны клавиш",
            "Как на iPhone: касание у края клавиши засчитывается за букву, которая лучше подходит к слову. Середина клавиши — всегда она сама",
            settings.dynamicZones
        ) { KeyboardPrefs.edit(context) { putBoolean("dynamic_zones", it) } }
        AnimatedVisibility(settings.autoCorrect) {
            ToggleItem(
                Icons.Rounded.Apps, "Исправлять во всех приложениях",
                "Некоторые приложения просят клавиатуру не исправлять текст. Пароли, почта и адреса не исправляются никогда",
                settings.correctEverywhere
            ) { KeyboardPrefs.edit(context) { putBoolean("correct_everywhere", it) } }
        }
        AnimatedVisibility(settings.autoCorrect) {
            ChoiceItem(
                Icons.Rounded.Tune, "Насколько смело исправлять",
                if (settings.autoCorrectStrength == "NORMAL") "Исправляет и неоднозначные опечатки — как Gboard"
                else "Только явные опечатки: соседняя клавиша, пропущенная или лишняя буква",
                listOf("SAFE" to "Осторожно", "NORMAL" to "Активно"), settings.autoCorrectStrength
            ) { v -> KeyboardPrefs.edit(context) { putString("auto_correct_strength", v) } }
        }
        val spellName = remember { SystemSpell.currentName(context) }
        ToggleItem(
            Icons.Rounded.Spellcheck, "Системная проверка правописания",
            (if (spellName != null) tr("Сейчас: {0}.", spellName) + " " else "") +
                tr("Исправления и варианты берутся из проверки телефона — обычно это словарь Gboard. Выбрать её: Настройки → Система → Язык и ввод → Проверка правописания"),
            settings.systemSpellcheck
        ) {
            KeyboardPrefs.edit(context) { putBoolean("system_spellcheck", it) }
        }
        ToggleItem(Icons.Rounded.MoreHoriz, "Знаки после пробела", "«слово ,» → «слово, » — пробел переезжает за запятую или точку", settings.smartPunctuation) {
            KeyboardPrefs.edit(context) { putBoolean("smart_punctuation", it) }
        }
        ToggleItem(Icons.Rounded.SpaceBar, "Пробел после запятой", "После «,» «!» «?» «;» «:» за словом пробел ставится сам — следующий пробел не удвоится", settings.autoSpacePunct) {
            KeyboardPrefs.edit(context) { putBoolean("auto_space_punct", it) }
        }
        ToggleItem(Icons.Rounded.KeyboardCapslock, "Заглавная в начале предложения", null, settings.autoCapitalize) {
            KeyboardPrefs.edit(context) { putBoolean("auto_capitalize", it) }
        }
        ToggleItem(Icons.Rounded.MoreHoriz, "Точка двойным пробелом", null, settings.doubleSpacePeriod) {
            KeyboardPrefs.edit(context) { putBoolean("double_space_period", it) }
        }
        ToggleItem(Icons.Rounded.HorizontalRule, "Два дефиса — длинное тире", "«--» сразу превращается в «—»", settings.dashMerge) {
            KeyboardPrefs.edit(context) { putBoolean("dash_merge", it) }
        }
    }
    if (part == SettingsPage.TYPING) SettingsSection("Приватность") {
        ToggleItem(Icons.Rounded.VisibilityOff, "Инкогнито", "Не учить новые слова и не запоминать буфер. Кнопка есть и на панели инструментов", settings.incognito) {
            KeyboardPrefs.edit(context) { putBoolean("incognito", it) }
        }
    }
    if (part == SettingsPage.GESTURES) SettingsSection("Жесты") {
        ChoiceItem(
            Icons.Rounded.Swipe, "Свайп по пробелу",
            when (settings.spaceSwipe) {
                "CURSOR" -> "Ведите палец по пробелу — курсор двигается"
                "LANGUAGE" -> "Свайп меняет язык"
                else -> "Жест выключен"
            },
            listOf("CURSOR" to "Курсор", "LANGUAGE" to "Язык", "OFF" to "Выкл"), settings.spaceSwipe
        ) { v -> KeyboardPrefs.edit(context) { putString("space_swipe", v) } }
        ToggleItem(Icons.AutoMirrored.Rounded.Backspace, "Свайп влево по ⌫", "Удаляет целые слова", settings.backspaceSwipe) {
            KeyboardPrefs.edit(context) { putBoolean("backspace_swipe", it) }
        }
        SliderItem(
            icon = Icons.Rounded.Timer, title = "Задержка удержания",
            value = settings.longPressMs, range = 180f..700f,
            format = { "${it.roundToInt()} " + tr("мс") },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("long_press_ms", v) } }
        )
    }
    if (part == SettingsPage.CLIPBOARD) SettingsSection("Буфер обмена") {
        ToggleItem(
            Icons.Rounded.ContentPaste, "История буфера",
            "Хранится только на телефоне и не пропадает, когда клавиатура закрывается. Пароли не сохраняются", settings.clipboardHistory
        ) {
            KeyboardPrefs.edit(context) { putBoolean("clipboard_history", it) }
        }
        AnimatedVisibility(settings.clipboardHistory) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ChoiceItem(
                    Icons.Rounded.Schedule, "Сколько хранить", "Закреплённые записи хранятся всегда",
                    listOf("1H" to "Час", "1D" to "День", "7D" to "Неделю", "30D" to "Месяц", "FOREVER" to "Всегда"), settings.clipboardKeep
                ) { v -> KeyboardPrefs.edit(context) { putString("clipboard_keep", v) } }
                val unlimited = settings.clipboardSize == Int.MAX_VALUE
                ToggleItem(
                    Icons.Rounded.AllInclusive, "Без ограничения записей", "Старые записи не удаляются, пока их не сотрёте сами", unlimited
                ) { KeyboardPrefs.edit(context) { putInt("clipboard_size", if (it) 0 else 50) } }
                if (!unlimited) SliderItem(
                    icon = Icons.Rounded.History, title = "Сколько записей",
                    value = settings.clipboardSize.toFloat(), range = 5f..1000f,
                    format = { "${it.roundToInt()}" },
                    onCommit = { v -> KeyboardPrefs.edit(context) { putInt("clipboard_size", v.roundToInt()) } }
                )
                ScreenshotClipItem(settings)
            }
        }
    }
    if (part == SettingsPage.CLIPBOARD) SettingsSection("GIF") {
        val builtIn = BuildConfig.KLIPY_API_KEY.isNotBlank() || BuildConfig.GIPHY_API_KEY.isNotBlank()
        if (builtIn) InfoCard(Icons.Rounded.GifBox, "Поиск GIF уже работает — свой ключ не нужен. Можно указать свой, если хотите.")
        ActionItem(
            Icons.Rounded.GifBox, "Ключ KLIPY",
            if (settings.klipyKey.isBlank()) "Бесплатно на partner.klipy.com — поиск любых GIF" else "Задан",
            onClick = { klipyDialog = true }
        )
        ActionItem(
            Icons.Rounded.GifBox, "Ключ GIPHY API",
            if (settings.giphyKey.isBlank()) "Необязательно — запасной источник" else "Задан",
            onClick = { giphyDialog = true }
        )
    }
    if (klipyDialog) {
        TextInputDialog("Ключ KLIPY", settings.klipyKey, "Ключ с partner.klipy.com", onDismiss = { klipyDialog = false }) { v ->
            KeyboardPrefs.edit(context) { putString("klipy_key", SecretBox.encrypt(v.trim())) }
            klipyDialog = false
        }
    }
    if (giphyDialog) {
        TextInputDialog("Ключ GIPHY API", settings.giphyKey, "Ключ с developers.giphy.com", onDismiss = { giphyDialog = false }) { v ->
            KeyboardPrefs.edit(context) { putString("giphy_key", SecretBox.encrypt(v.trim())) }
            giphyDialog = false
        }
    }
}

/** «Скриншоты в буфер»: переключатель и запрос доступа к фото, без которого новые скриншоты не видны. */
@Composable
private fun ScreenshotClipItem(settings: KeyboardSettings) {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var granted by remember { mutableStateOf(ClipStore.hasPhotoAccess(context)) }
    LaunchedEffect(focused) { if (focused) granted = ClipStore.hasPhotoAccess(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ClipStore.hasPhotoAccess(context)
        if (granted) KeyboardPrefs.edit(context) { putBoolean("clip_screenshots", true) }
    }
    ToggleItem(
        Icons.Rounded.Screenshot, "Скриншоты в буфер",
        if (!granted) "Нужен доступ к фото — нажмите, чтобы разрешить" else "Новый скриншот сразу появляется в буфере и предлагается вставить",
        settings.clipScreenshots && granted
    ) { on ->
        if (on && !granted) launcher.launch(ClipStore.photoPermissions())
        else KeyboardPrefs.edit(context) { putBoolean("clip_screenshots", on) }
    }
}

// ----------------------------------------------------------------------- вибрация

@Composable
internal fun FeedbackPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // свой звук: копируем файл к себе — доступ к выбранному файлу потом пропадает
    val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val out = java.io.File(context.filesDir, HapticsEngine.SOUND_FILE)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size in 1..1_048_576)
                        out.writeBytes(bytes)
                    } ?: error("no input")
                    true
                }.getOrDefault(false)
            }
            if (ok) KeyboardPrefs.edit(context) { putLong("custom_sound_version", System.currentTimeMillis()) }
            else Toast.makeText(context, tr("Не удалось открыть звук (нужен файл до 1 МБ)"), Toast.LENGTH_LONG).show()
        }
    }
    SettingsSection("Вибрация") {
        ToggleItem(Icons.Rounded.Vibration, "Вибрация при нажатии", null, settings.hapticsEnabled) {
            KeyboardPrefs.edit(context) { putBoolean("haptics_enabled", it) }
            if (it) HapticsEngine.preview(context, HapticMode.fromId(settings.hapticMode), settings.vibrationMs, settings.vibrationAmplitude)
        }
        AnimatedVisibility(
            visible = settings.hapticsEnabled,
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ChoiceItem(
                    Icons.Rounded.TouchApp, "Характер отклика", null,
                    HapticMode.entries.map { it.id to it.title }, settings.hapticMode
                ) { id ->
                    KeyboardPrefs.edit(context) { putString("haptic_mode", id) }
                    HapticsEngine.preview(context, HapticMode.fromId(id), settings.vibrationMs, settings.vibrationAmplitude)
                }
                AnimatedVisibility(visible = settings.hapticMode == HapticMode.CUSTOM.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SliderItem(
                            icon = Icons.Rounded.Timer, title = "Длительность",
                            value = settings.vibrationMs, range = 1f..80f,
                            format = { "${it.roundToInt()} " + tr("мс") },
                            onDrag = { v -> HapticsEngine.preview(context, HapticMode.CUSTOM, v, settings.vibrationAmplitude) },
                            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("vibration_ms", v) } }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Bolt, title = "Сила",
                            value = settings.vibrationAmplitude, range = 1f..255f,
                            format = { "${(it / 2.55f).roundToInt()}%" },
                            onDrag = { v -> HapticsEngine.preview(context, HapticMode.CUSTOM, settings.vibrationMs, v) },
                            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("vibration_amplitude", v) } }
                        )
                    }
                }
                ToggleItem(Icons.Rounded.Keyboard, "Сильнее на Shift, Enter, пробеле", null, settings.strongSpecialKeys) {
                    KeyboardPrefs.edit(context) { putBoolean("strong_special_keys", it) }
                }
                ToggleItem(Icons.Rounded.SwapVert, "Отклик при отпускании", null, settings.vibrateOnRelease) {
                    KeyboardPrefs.edit(context) { putBoolean("vibrate_on_release", it) }
                }
            }
        }
    }
    SettingsSection("Звук") {
        ToggleItem(Icons.Rounded.MusicNote, "Звук нажатия", null, settings.soundEnabled) {
            KeyboardPrefs.edit(context) { putBoolean("sound_enabled", it) }
        }
        AnimatedVisibility(settings.soundEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SliderItem(
                    icon = Icons.Rounded.GraphicEq, title = "Громкость",
                    value = settings.soundVolume, range = 0.05f..1f,
                    format = { "${(it * 100).roundToInt()}%" },
                    onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("sound_volume", v) } }
                )
                ActionItem(
                    Icons.Rounded.AudioFile,
                    if (settings.customSoundVersion != 0L) "Сменить свой звук" else "Свой звук нажатия",
                    "Короткий аудиофайл (до 1 МБ): mp3, ogg, wav",
                    onClick = { soundLauncher.launch("audio/*") }
                )
                if (settings.customSoundVersion != 0L) {
                    ActionItem(Icons.Rounded.RestartAlt, "Вернуть системный звук", null, onClick = {
                        java.io.File(context.filesDir, HapticsEngine.SOUND_FILE).delete()
                        KeyboardPrefs.edit(context) { putLong("custom_sound_version", 0L) }
                    })
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- плагины

@Composable
internal fun PluginsPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val plugins = PluginRepository.plugins
    val errors = PluginRepository.errors
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<KeyboardPlugin?>(null) }
    var pendingNetwork by remember { mutableStateOf<KeyboardPlugin?>(null) }

    fun onImported(r: Result<KeyboardPlugin>) {
        r.onSuccess { p ->
            Toast.makeText(context, tr("Плагин «{0}» установлен", p.name), Toast.LENGTH_SHORT).show()
            expanded = p.id
            if (p.usesNetwork || p.permissions.any { it in setOf("read_text", "clipboard", "launch", "ai") }) pendingNetwork = p
        }.onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
    }
    val modExampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/javascript")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.assets.open("examples/my-mod.js").use { it.readBytes() }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Пример мода сохранён — отредактируйте и импортируйте") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { onImported(withContext(Dispatchers.IO) { PluginRepository.importFrom(context, uri) }) }
    }
    val exampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(PluginRepository.EXAMPLE.toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Пример сохранён — отредактируйте и импортируйте") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }

    InfoCard(
        Icons.Rounded.Extension,
        "Как установить: нажмите «Импорт из файла» и выберите плагин (.json, .js или .zip) или скопируйте его текст " +
            "и нажмите «Импорт из буфера обмена». Плагин появится в списке ниже — его можно включить, выключить или удалить.\n\n" +
            "Как пользоваться: на клавиатуре нажмите кнопку с сеткой слева вверху, затем 🧩 — откроются вкладки плагинов " +
            "с их кнопками и панелями. Кнопки, отмеченные плагином, появляются прямо на панели инструментов.\n\n" +
            "Как сделать свой: сохраните пример кнопкой ниже, отредактируйте и импортируйте. Описание формата — в файле PLUGINS.md."
    )
    SettingsSection("Установка") {
        ActionItem(Icons.Rounded.FileOpen, "Импорт из файла", ".js (мод), .zip (мод с картинками) или .json", onClick = {
            importLauncher.launch(arrayOf("application/json", "application/javascript", "text/javascript", "application/zip", "text/plain", "application/octet-stream", "*/*"))
        })
        ActionItem(Icons.Rounded.Code, "Сохранить пример мода (.js)", "Своя панель, кнопка, реакция на слова и хранилище", onClick = {
            modExampleLauncher.launch("my-mod.js")
        })
        ActionItem(Icons.Rounded.ContentPaste, "Импорт из буфера обмена", "Скопируйте JSON плагина или код мода и нажмите", onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (text.isBlank()) Toast.makeText(context, tr("Буфер обмена пуст"), Toast.LENGTH_SHORT).show()
            else onImported(PluginRepository.importText(context, text))
        })
        ActionItem(Icons.Rounded.Download, "Сохранить пример плагина", "Шаблон со всеми возможностями формата", onClick = {
            exampleLauncher.launch("my-plugin.json")
        })
    }
    if (errors.isNotEmpty()) {
        SettingsSection("Не загрузились") {
            errors.forEach { e ->
                GroupItem {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = cs.error)
                        Text(e, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    }
                }
            }
        }
    }
    val user = plugins.filter { !it.builtIn }
    val builtIn = plugins.filter { it.builtIn }
    if (user.isNotEmpty()) {
        SettingsSection("Мои плагины") {
            user.forEach { p ->
                PluginCard(p, expanded == p.id, onExpand = { expanded = if (expanded == p.id) null else p.id }, onDelete = { confirmDelete = p })
            }
        }
    }
    SettingsSection("Встроенные") {
        builtIn.forEach { p ->
            PluginCard(p, expanded == p.id, onExpand = { expanded = if (expanded == p.id) null else p.id }, onDelete = null)
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(tr("Удалить «{0}»?", p.name)) },
            text = { Text(tr("Файл плагина будет удалён. Его можно импортировать заново.")) },
            confirmButton = {
                Button(onClick = {
                    PluginRepository.delete(context, p.id)
                    confirmDelete = null
                }) { Text(tr("Удалить")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Отмена")) } }
        )
    }
    pendingNetwork?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingNetwork = null },
            icon = { Icon(Icons.Rounded.Security, null) },
            title = { Text(tr("Разрешения плагина")) },
            text = {
                Text(
                    tr("«{0}» просит:", p.name) + "\n\n" + buildList {
                        if ("read_text" in p.permissions) add("• " + tr("читать набираемый текст и текст в поле"))
                        if ("clipboard" in p.permissions) add("• " + tr("читать историю буфера обмена"))
                        if ("launch" in p.permissions) add("• " + tr("открывать ссылки, приложения и окно «Поделиться»"))
                        if ("ai" in p.permissions) add("• " + tr("отправлять запросы ИИ по вашему ключу (расходует лимит ключа)"))
                        if ("network" in p.permissions) add(
                            "• " + tr("выходить в интернет") + p.actions.filter { it.type == PluginActionType.HTTP }
                                .joinToString("") { "\n   ${it.title}: ${Uri.parse(it.url).host}" }
                        )
                    }.joinToString("\n") + "\n\n" + tr("Устанавливайте моды только из источников, которым доверяете. Оставить плагин включённым?")
                )
            },
            confirmButton = { Button(onClick = { pendingNetwork = null }) { Text(tr("Оставить")) } },
            dismissButton = {
                TextButton(onClick = {
                    PluginRepository.setEnabled(context, p.id, false)
                    pendingNetwork = null
                }) { Text(tr("Выключить")) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginCard(p: KeyboardPlugin, expanded: Boolean, onExpand: () -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val settings by KeyboardPrefs.observe()
    val enabled = PluginRepository.isEnabled(p.id) && p.id !in PluginRepository.disabled
    val spec = remember(p.id) {
        val presets = ExpressiveShapePreset.entries
        val pr = presets[(p.id.hashCode() and 0x7fffffff) % presets.size]
        WavySpec(pr.profile, pr.defaultWaves, pr.defaultAmplitude / 100f)
    }
    GroupItem(onClick = onExpand) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).wavyBackground(spec, if (enabled) cs.primaryContainer else cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { SpecIcon(p.icon, if (enabled) cs.onPrimaryContainer else cs.onSurfaceVariant, 24.dp) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.usesNetwork) Icon(Icons.Rounded.Public, "Использует сеть", tint = cs.tertiary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        listOf("v${p.version}", p.author, p.summary).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { PluginRepository.setEnabled(context, p.id, it) },
                    thumbContent = if (enabled) {
                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    val perms = p.permissions.filter { it in PluginParser.PERMISSIONS }
                    if (perms.isNotEmpty()) {
                        Text(tr("Разрешения"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            perms.joinToString("  ·  ") {
                                when (it) {
                                    "read_text" -> tr("читает текст")
                                    "clipboard" -> tr("буфер обмена")
                                    "launch" -> tr("открывает приложения")
                                    "ai" -> tr("ИИ по вашему ключу")
                                    else -> tr("интернет")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    if (p.config.isNotEmpty()) {
                        Text(tr("Настройки мода"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        ModConfigEditor(p)
                    }
                    if (p.actions.isNotEmpty()) {
                        Text(tr("Действия"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.actions.joinToString("  ·  ") { if (it.icon.startsWith("@") || it.icon.startsWith("data:")) it.title else "${it.icon} ${it.title}" },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (p.script != null) {
                        val log = ScriptHost.logs[p.id].orEmpty()
                        Text(tr("Журнал скрипта"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            if (log.isEmpty()) tr(if (enabled) "Пусто" else "Мод выключен") else log.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(10.dp)
                        )
                    }
                    if (p.shortcuts.isNotEmpty()) {
                        Text(tr("Сокращения (по пробелу)"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.shortcuts.entries.take(12).joinToString("\n") { (k, v) -> "$k → ${v.replace("\n", "⏎").take(40)}" } +
                                if (p.shortcuts.size > 12) "\n… " + tr("ещё {0}", p.shortcuts.size - 12) else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (p.symbols.isNotEmpty()) {
                        Text(tr("Символы"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(p.symbols.joinToString("  ·  ") { "${it.icon} ${it.title} (${it.items.size})" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    if (p.layouts.isNotEmpty()) {
                        Text(tr("Раскладки"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.layouts.entries.joinToString("\n") { (id, rows) -> "$id: " + rows.joinToString(" / ") { r -> r.keys.joinToString("") { it.label.take(1) } } },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val skinOn = p.skin != null && settings.skinPlugin == p.id
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.hasLook && !skinOn) {
                            FilledTonalButton(onClick = {
                                if (!enabled) PluginRepository.setEnabled(context, p.id, true)
                                PluginRepository.applyLook(context, p)
                                Toast.makeText(context, tr("Оформление «{0}» применено", p.name), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Rounded.FormatPaint, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Применить оформление"))
                            }
                        }
                        if (skinOn) {
                            OutlinedButton(onClick = { PluginRepository.removeSkin(context) }) {
                                Icon(Icons.Rounded.FormatColorReset, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Снять оформление"))
                            }
                        }
                        if (onDelete != null) {
                            OutlinedButton(onClick = onDelete) {
                                Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = cs.error)
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Удалить"), color = cs.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- резервная копия

@Composable
internal fun BackupPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(KeyboardPrefs.exportJson(context).toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Настройки сохранены") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Файл не открылся")
                    KeyboardPrefs.importJson(context, raw)
                }
            }
            r.onSuccess { Toast.makeText(context, tr("Восстановлено параметров: {0}", it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    SettingsSection("Настройки") {
        ActionItem(Icons.Rounded.Upload, "Экспорт настроек", "Сохранить все параметры в .json", onClick = {
            exportLauncher.launch("expressive-keyboard-settings.json")
        })
        ActionItem(Icons.Rounded.Download, "Импорт настроек", "Восстановить из файла", onClick = {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        })
        ActionItem(Icons.Rounded.RestartAlt, "Сбросить настройки", "Словарь, плагины и недавние эмодзи сохранятся", onClick = { confirm = "settings" })
    }
    SettingsSection("Данные") {
        ActionItem(Icons.Rounded.AutoFixHigh, "Очистить выученные слова", "Подсказки забудут ваши слова и пары слов", onClick = { confirm = "dict" })
        ActionItem(Icons.Rounded.History, "Очистить недавние эмодзи", null, onClick = {
            EmojiRecents.clear(context)
            Toast.makeText(context, tr("Готово"), Toast.LENGTH_SHORT).show()
        })
        ActionItem(Icons.Rounded.DeleteForever, "Удалить все личные данные", "Выученные слова, история буфера, недавние эмодзи и ключи API", onClick = { confirm = "all" })
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.RestartAlt, null) },
            title = { Text(tr(when (what) { "dict" -> "Очистить словарь?"; "all" -> "Удалить личные данные?"; else -> "Сбросить настройки?" })) },
            text = {
                Text(
                    tr(when (what) {
                        "dict" -> "Все выученные слова будут удалены."
                        "all" -> "Клавиатура забудет ваши слова, историю буфера обмена, недавние эмодзи и ключи ИИ-сервисов и GIF. Настройки и темы останутся."
                        else -> "Размеры, цвета, тема, шрифт, фон и вибрация вернутся к значениям по умолчанию."
                    })
                )
            },
            confirmButton = {
                Button(onClick = {
                    when (what) {
                        "dict" -> {
                            WordPredictorEngine.clearUserData()
                            CjkUser.clear()
                        }
                        "all" -> {
                            PrivateData.wipe(context)
                            Toast.makeText(context, tr("Личные данные удалены"), Toast.LENGTH_SHORT).show()
                        }
                        else -> KeyboardPrefs.resetAll(context)
                    }
                    confirm = null
                }) { Text(tr(when (what) { "dict" -> "Очистить"; "all" -> "Удалить"; else -> "Сбросить" })) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Отмена")) } }
        )
    }
}

// ----------------------------------------------------------------------- о приложении

/** Настройки мода из его блока "config": переключатели, выбор, ползунки и текст. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModConfigEditor(p: KeyboardPlugin) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in p.config) {
            val value = remember(tick, o) { ModConfig.get(context, p, o) }
            fun set(v: Any?) {
                ModConfig.set(context, p, o.id, v)
                tick++
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (o.type) {
                    "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = value == true, onCheckedChange = { set(it) })
                    }
                    "choice" -> {
                        Text(o.title, style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((cid, ctitle) in o.choices) {
                                FilterChip(selected = value?.toString() == cid, onClick = { set(cid) }, label = { Text(ctitle) })
                            }
                        }
                    }
                    "slider" -> {
                        var v by remember(tick, o) { mutableFloatStateOf((value as? Number)?.toFloat() ?: o.min) }
                        Text("${o.title}: " + if (o.step >= 1f) v.roundToInt().toString() else "%.2f".format(v), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = v.coerceIn(o.min, o.max),
                            onValueChange = { x -> v = if (o.step > 0f) (kotlin.math.round((x - o.min) / o.step) * o.step + o.min).coerceIn(o.min, o.max) else x },
                            onValueChangeFinished = { set(v) },
                            valueRange = o.min..maxOf(o.max, o.min + 0.001f)
                        )
                    }
                    else -> {
                        var t by remember(tick, o) { mutableStateOf(value?.toString().orEmpty()) }
                        OutlinedTextField(
                            value = t, onValueChange = { t = it }, label = { Text(o.title) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (t != value?.toString().orEmpty()) IconButton(onClick = { set(t) }) { Icon(Icons.Rounded.Check, null) }
                            }
                        )
                    }
                }
                if (o.description.isNotEmpty()) Text(o.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Автор и канал — синие ссылки на Telegram. */
@Composable
internal fun AuthorLine() {
    val context = LocalContext.current
    val blue = androidx.compose.ui.graphics.Color(0xFF2AABEE)
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val label = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // две строки по центру: ник не переносится посреди слова, даже на узком экране и с крупным шрифтом
    @Composable
    fun Link(title: String, handle: String) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr(title), style = label, color = muted, maxLines = 1, softWrap = false)
            Text(
                "@$handle", style = label, fontWeight = FontWeight.Bold, color = blue, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open("https://t.me/$handle") }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Link("Автор:", "Shahedeer")
        Link("Канал:", "materialyouboss")
    }
}

@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val spec = remember { WavySpec(ShapeProfile.LOBES, 6, 0.1f) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(cs.primaryContainer).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(96.dp).wavyBackground(spec, cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Keyboard, null, tint = cs.onPrimary, modifier = Modifier.size(44.dp))
        }
        Text("Expressive Keyboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
        Text(tr("Версия {0}", version) + " · Material 3 Expressive", style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
    }
    AuthorLine()
    SettingsSection("Обновления") {
        val scope = rememberCoroutineScope()
        var auto by remember { mutableStateOf(Updater.autoEnabled(context)) }
        ActionItem(
            Icons.Rounded.SystemUpdate, "Проверить обновления",
            when {
                Updater.checking -> tr("Проверяю…")
                Updater.error != null -> Updater.error
                Updater.available != null -> tr("Доступна версия {0}", Updater.available!!.version)
                Updater.upToDate -> tr("У вас последняя версия")
                else -> tr("Новые версии приходят с GitHub")
            },
            onClick = { scope.launch { Updater.check(context) } }
        )
        ToggleItem(Icons.Rounded.Autorenew, "Проверять при запуске", "Раз за запуск настроек, без фоновой работы", auto) { v ->
            auto = v
            Updater.setAuto(context, v)
        }
    }
    UpdateCard()
    SettingsSection("Возможности") {
        listOf(
            Icons.Rounded.Palette to "Material You: цвета из обоев или из любого своего цвета",
            Icons.Rounded.Extension to "Моды на JavaScript: свои панели, кнопки, клавиши, события и подсказки",
            Icons.Rounded.Language to tr("Языков: {0} — с частотными словарями, пиньинем, каной, хангылем и Telex", Languages.all.size),
            Icons.Rounded.EmojiEmotions to "Все эмодзи Unicode 16 с поиском и встроенными стилями",
            Icons.Rounded.Brush to "Свои иконки для любых кнопок и вкладок эмодзи",
            Icons.Rounded.Swipe to "Курсор свайпом по пробелу, удаление слов свайпом по ⌫",
            Icons.Rounded.SwapHoriz to "Режим одной руки",
            Icons.Rounded.Translate to "Переводчик, GIF, буфер обмена, голосовой ввод",
            Icons.Rounded.Spellcheck to "Подсказки и автоисправление, которые учатся вашим словам"
        ).forEach { (icon, text) ->
            GroupItem {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon)
                    Text(tr(text), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                }
            }
        }
    }
    SettingsSection("Лицензии") {
        GroupItem {
            Text(
                "Twemoji © Twitter / jdecked — CC-BY 4.0\n" +
                    "Noto Emoji, Blobmoji © Google — Apache 2.0\n" +
                    "OpenMoji © HfG Schwäbisch Gmünd — CC BY-SA 4.0\n" +
                    "Emoji names: Unicode CLDR — Unicode License\n" +
                    "Pinyin: mozillazg/pinyin-data, phrase-pinyin-data — MIT; OpenCC — Apache 2.0\n" +
                    "Word frequencies: hermitdave/FrequencyWords — MIT\n" +
                    "Nunito font — SIL OFL 1.1",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
    }
}
