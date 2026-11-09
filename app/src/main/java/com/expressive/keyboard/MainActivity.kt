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
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        KeyboardPrefs.ensureInit(this)
        WordPredictorEngine.ensureInitialized(this)
        IconStore.ensureLoaded(this)
        PluginRepository.ensureLoaded(this)
        EmojiCatalog.warmUp(this)
        enableEdgeToEdge()
        setContent {
            val settings by KeyboardPrefs.observe()
            ExpressiveTheme(settings, appUi = true) {
                // «Разрешить микрофон» из панели голосового ввода: системный запрос сразу при открытии
                val micPromptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(MicPermissionPrompt.pending) {
                    if (MicPermissionPrompt.pending) {
                        MicPermissionPrompt.pending = false
                        if (!hasMicPermission(this@MainActivity)) micPromptLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                // новым пользователям — сначала первая настройка: тема, формы, нужные функции
                var onboarding by remember { mutableStateOf(Onboarding.needed(this@MainActivity)) }
                if (onboarding) OnboardingScreen(settings) {
                    Onboarding.finish(this@MainActivity)
                    onboarding = false
                } else SettingsScreen(settings)
            }
        }
    }
}

enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector, val shape: ExpressiveShapePreset) {
    HOME("Expressive Keyboard", "", Icons.Rounded.Keyboard, ExpressiveShapePreset.COOKIE_12),
    WHATS_NEW("Новое", "Всё, что появилось в этом обновлении, можно настроить прямо здесь", Icons.Rounded.NewReleases, ExpressiveShapePreset.SUNNY_8),
    THEMES("Темы", "Светлая или тёмная; Minecraft, Samsung, Nothing, стекло; настройки темы и свои темы", Icons.Rounded.Style, ExpressiveShapePreset.FLOWER_6),
    THEME_EDITOR("Редактор темы", "Всё оформление: фон, клавиши, форма, тени, шрифт", Icons.Rounded.Brush, ExpressiveShapePreset.SUNNY_8),
    COLORS("Цвета и фон", "Акцентный цвет, палитра Material You, цвета клавиш, картинка и размытие", Icons.Rounded.ColorLens, ExpressiveShapePreset.FLOWER_6),
    KEYS("Клавиши и шрифт", "Размер, шрифт, кнопки у пробела, ряд цифр, одна рука, свои места клавиш", Icons.Rounded.Keyboard, ExpressiveShapePreset.CLOVER_4),
    LAYOUT("Свои места клавиш", "Поменяйте местами любые клавиши: буквы, цифры, пробел, запятую, эмодзи…", Icons.Rounded.SwapHoriz, ExpressiveShapePreset.SUNNY_8),
    ANIMATIONS("Анимации", "Нажатие клавиш, свои анимации, скорость, подсказки и панели", Icons.Rounded.Animation, ExpressiveShapePreset.CLOVER_4),
    TOOLBAR("Панель, эмодзи и иконки", "Кнопки панели инструментов, стиль эмодзи, свои иконки", Icons.Rounded.DragIndicator, ExpressiveShapePreset.FLOWER_6),
    LANGUAGES("Языки", "Раскладки, китайский и японский ввод, словари подсказок", Icons.Rounded.Language, ExpressiveShapePreset.COOKIE_12),
    MY_WORDS("Мой словарь", "Добавьте свои слова: с диакритикой, имена, термины", Icons.Rounded.MenuBook, ExpressiveShapePreset.SUNNY_8),
    TYPING("Подсказки и исправления", "Автоисправление, тире, заглавные буквы, ИИ-исправление, инкогнито", Icons.Rounded.Spellcheck, ExpressiveShapePreset.SUNNY_8),
    GESTURES("Жесты и скорость", "Свайп-набор, свайпы по пробелу и ⌫, удержание, парные скобки", Icons.Rounded.Gesture, ExpressiveShapePreset.CLOVER_4),
    CLIPBOARD("Буфер обмена и GIF", "История, сколько хранить, скриншоты в буфере, GIF", Icons.Rounded.ContentPaste, ExpressiveShapePreset.FLOWER_6),
    FEEDBACK("Вибрация и звук", "Сила, длительность и характер отклика", Icons.Rounded.Vibration, ExpressiveShapePreset.COOKIE_12),
    POWER("Энергосбережение", "Когда включается и что при этом выключается", Icons.Rounded.BatterySaver, ExpressiveShapePreset.CLOVER_4),
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

internal const val FEATURE_AVAILABLE = com.google.mlkit.genai.common.FeatureStatus.AVAILABLE
internal const val FEATURE_DOWNLOADABLE = com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE
internal const val FEATURE_DOWNLOADING = com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING

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

/** «Разрешить микрофон» из клавиатуры: открыть настройки и сразу показать системный запрос. */
internal object MicPermissionPrompt {
    var pending by mutableStateOf(false)
}

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
    // M3, классы размера окна: от 840 dp (expanded) — две панели «список — подробности»,
    // слева разделы, справа выбранный; на 600–839 dp — одна панель, но не шире 720 dp (строки не растягиваются)
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (widthDp >= 840) {
        val detail = if (page == SettingsPage.HOME) SettingsPage.THEMES else page
        androidx.activity.compose.BackHandler(enabled = detail.parent != SettingsPage.HOME) { page = detail.parent }
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(Modifier.width(if (widthDp >= 1200) 420.dp else 360.dp).fillMaxHeight()) {
                SettingsPageScaffold(SettingsPage.HOME, settings, onNavigate = { page = it }, selected = detail)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = detail,
                    transitionSpec = { (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = 380f), 0.96f)) togetherWith fadeOut(tween(120)) },
                    label = "detailPane"
                ) { p ->
                    SettingsPageScaffold(p, settings, onNavigate = { page = it }, showBack = p.parent != SettingsPage.HOME)
                }
            }
        }
        return
    }
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
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp)) { SettingsPageScaffold(p, settings, onNavigate = { page = it }) }
        }
    }
}

@Composable
internal fun SettingsPageScaffold(
    page: SettingsPage, settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit,
    selected: SettingsPage? = null, showBack: Boolean = page != SettingsPage.HOME
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    var testText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // в двух панелях поле проверки — только в правой, чтобы не было двух одинаковых полей
    val showTest = page != SettingsPage.ABOUT && page != SettingsPage.BACKUP && selected == null
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = cs.surfaceContainer,
        topBar = {
            LargeTopAppBar(
                title = { Text(tr(page.title), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (showBack) {
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
                PillField(
                    testText, { testText = it }, "Проверьте клавиатуру здесь…", Icons.Rounded.Keyboard,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                when (page) {
                    SettingsPage.HOME -> HomePage(settings, onNavigate, selected)
                    SettingsPage.WHATS_NEW -> WhatsNewPage(settings, onNavigate)
                    SettingsPage.POWER -> PowerPage(settings)
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
                    SettingsPage.MY_WORDS -> MyWordsPage(settings)
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
    remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent, settings.shapeAmount) { settings.accentSpec().accent(settings) }

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
internal fun HomePage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit, selected: SettingsPage? = null) {
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
    // возврат из системных настроек: разрешение могли выдать, а окно об этом ещё не знает
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeEnabled = isImeEnabled(context)
                imeSelected = isImeSelected(context)
                micGranted = hasMicPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // автор и канал — сверху; поиск — сразу под ними; язык — внизу, в «Прочем»
    AuthorLine()
    PillField(query, { query = it }, "Найти настройку: вибрация, шрифт, свайп…", Icons.Rounded.Search, tonal = true)
    UpdateCard()
    if (query.isNotBlank()) {
        val found = SettingsSearch.find(query)
        SettingsSection(tr("Найдено: {0}", found.size)) {
            if (found.isEmpty()) GroupItem { Text(tr("Ничего не нашлось"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            found.forEach { (title, p) ->
                // найден сам раздел — под заголовком его описание, а не то же название ещё раз
                ActionItem(p.icon, title, if (title == tr(p.title)) tr(p.subtitle) else tr(p.title), onClick = { onNavigate(p) })
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
    val groups = listOf(
        "Обновление" to listOf(SettingsPage.WHATS_NEW),
        "Внешний вид" to listOf(SettingsPage.THEMES, SettingsPage.COLORS, SettingsPage.KEYS, SettingsPage.LAYOUT, SettingsPage.ANIMATIONS, SettingsPage.TOOLBAR),
        "Набор текста" to listOf(SettingsPage.LANGUAGES, SettingsPage.MY_WORDS, SettingsPage.TYPING, SettingsPage.GESTURES, SettingsPage.CLIPBOARD),
        "Отклик и расширения" to listOf(SettingsPage.FEEDBACK, SettingsPage.POWER, SettingsPage.PLUGINS),
        "Прочее" to listOf(SettingsPage.BACKUP, SettingsPage.ABOUT)
    )
    var i = 0
    for ((title, pages) in groups) {
        SettingsSection(title) {
            for (p in pages) {
                val idx = i++
                CategoryTile(p, idx, selected = p == selected || p == selected?.parent) { onNavigate(p) }
            }
            if (SettingsPage.BACKUP in pages) ChoiceItem(
                Icons.Rounded.Translate, "Язык приложения", null,
                listOf("SYSTEM" to "Как в системе", "ru" to "Русский", "en" to "English", "es" to "Español"), settings.appLanguage
            ) { v -> KeyboardPrefs.edit(context) { putString("app_language", v) } }
        }
    }
}

// ----------------------------------------------------------------------- внешний вид

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
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    crash?.let { report ->
        SettingsSection("Последний сбой") {
            InfoCard(Icons.Rounded.BugReport, report.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }?.take(200) ?: report.take(200))
            ActionItem(Icons.Rounded.ContentCopy, "Скопировать отчёт о сбое", "Отправьте его автору — по нему можно найти причину", onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                Toast.makeText(context, tr("Скопировано"), Toast.LENGTH_SHORT).show()
            })
            ActionItem(Icons.Rounded.Delete, "Убрать отчёт", null, onClick = {
                CrashLog.clear(context)
                crash = null
            })
        }
    }
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
                    "Russian word pairs: Google Books Ngram Viewer (v20200217) — CC BY 3.0\n" +
                    "English word pairs: Peter Norvig, count_1w / count_2w (from Google Web 1T)\n" +
                    "Nunito font — SIL OFL 1.1",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
    }
}
