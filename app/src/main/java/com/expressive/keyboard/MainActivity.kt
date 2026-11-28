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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
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
    COLORS("Цвета", "Палитра Material You и цвета клавиш", Icons.Rounded.ColorLens, ExpressiveShapePreset.FLOWER_6),
    BACKGROUND("Фон клавиатуры", "Фото или видео из галереи", Icons.Rounded.Image, ExpressiveShapePreset.FLOWER_6),
    EMOJI_STYLE("Эмодзи и иконки", "Стиль и размер эмодзи, свои иконки", Icons.Rounded.EmojiEmotions, ExpressiveShapePreset.SUNNY_8),
    FONT("Шрифт", "Начертание, толщина, размер букв и свой шрифт", Icons.Rounded.TextFields, ExpressiveShapePreset.SUNNY_8),
    BUTTON_SHAPES("Форма кнопок", "«Печеньки», цветы, волны и готовые фигуры", Icons.Rounded.Interests, ExpressiveShapePreset.COOKIE_12),
    SIZE("Размер и положение", "Высота, ширина, плавающий режим и режим одной руки", Icons.Rounded.Straighten, ExpressiveShapePreset.CLOVER_4),
    KEYS("Клавиши и кнопки", "Ряд цифр, кнопки у пробела, символы при удержании", Icons.Rounded.Keyboard, ExpressiveShapePreset.CLOVER_4),
    LAYOUT("Свои места клавиш", "Поменяйте местами любые клавиши: буквы, цифры, пробел, запятую, эмодзи…", Icons.Rounded.SwapHoriz, ExpressiveShapePreset.SUNNY_8),
    LONG_PRESS("Символы при удержании", "Свои символы на любой клавише: ғ на «г», $ на «d»", Icons.Rounded.TouchApp, ExpressiveShapePreset.FLOWER_6),
    ANIMATIONS("Анимации", "Нажатие клавиш, свои анимации, скорость, подсказки и панели", Icons.Rounded.Animation, ExpressiveShapePreset.CLOVER_4),
    TOOLBAR("Панель инструментов", "Порядок и видимость кнопок", Icons.Rounded.DragIndicator, ExpressiveShapePreset.FLOWER_6),
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
internal fun importBackground(context: Context, uri: Uri, fileName: String = KeyboardPrefs.BG_IMAGE_FILENAME): Boolean = runCatching {
    val bmp = if (Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > 1600) decoder.setTargetSize(
                (info.size.width * (1600f / edge)).toInt().coerceAtLeast(1),
                (info.size.height * (1600f / edge)).toInt().coerceAtLeast(1)
            )
        }
    } else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        if (maxSide <= 0) return false
        var sample = 1
        while (maxSide / (sample * 2) >= 1600) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return false
    }
    val file = android.util.AtomicFile(File(context.filesDir, fileName))
    try {
        val stream = file.startWrite()
        try {
            check(bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, stream))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    } finally {
        bmp.recycle()
    }
    true
}.getOrDefault(false)

@Composable
fun SettingsScreen(settings: KeyboardSettings) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        SettingsNavigation(settings)
    }
}

@Composable
private fun SettingsNavigation(settings: KeyboardSettings) {
    var history by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(arrayListOf<SettingsPage>()) }
    val page = history.lastOrNull() ?: SettingsPage.HOME
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsTab.APPEARANCE) }
    val back: () -> Unit = { history = ArrayList(history.dropLast(1)) }
    val navigate: (SettingsPage) -> Unit = { next ->
        if (next == SettingsPage.HOME) history = arrayListOf()
        else if (next != page) history = ArrayList(history + next)
    }
    val selectSection: (SettingsTab) -> Unit = {
        section = it
        history = arrayListOf()
    }
    // M3, классы размера окна: от 840 dp (expanded) — две панели «список — подробности»,
    // слева разделы, справа выбранный; на 600–839 dp — одна панель, но не шире 720 dp (строки не растягиваются)
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (widthDp >= 840) {
        val detail = if (page == SettingsPage.HOME) section.pages.firstOrNull() ?: SettingsPage.THEMES else page
        androidx.activity.compose.BackHandler(enabled = history.isNotEmpty(), onBack = back)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(if (widthDp >= 1200) 420.dp else 360.dp).fillMaxHeight()) {
                SettingsPageScaffold(SettingsPage.HOME, settings, onNavigate = navigate, selected = detail,
                    section = section, onSection = selectSection)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = detail,
                    transitionSpec = { (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = 380f), 0.96f)) togetherWith fadeOut(tween(120)) },
                    label = "detailPane"
                ) { p ->
                    SettingsPageScaffold(p, settings, onNavigate = {
                        if (history.isEmpty() && it != SettingsPage.HOME) history = arrayListOf(p, it)
                        else navigate(it)
                    }, showBack = history.isNotEmpty(), onBack = back)
                }
            }
        }
        return
    }
    androidx.activity.compose.BackHandler(enabled = history.isNotEmpty(), onBack = back)
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = initialState == SettingsPage.HOME || initialState in history.dropLast(1)
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp)) {
                SettingsPageScaffold(p, settings, onNavigate = navigate, section = section, onSection = selectSection, onBack = back)
            }
        }
    }
}

@Composable
private fun SettingsPageScaffold(
    page: SettingsPage, settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit,
    selected: SettingsPage? = null, showBack: Boolean = page != SettingsPage.HOME,
    section: SettingsTab = SettingsTab.APPEARANCE, onSection: (SettingsTab) -> Unit = {},
    onBack: () -> Unit = { onNavigate(page.parent) }
) {
    val home = page == SettingsPage.HOME
    val showBottomBar = home && !settings.classicSettingsUi
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    var testText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val showTest = page in setOf(SettingsPage.SIZE, SettingsPage.KEYS, SettingsPage.FONT, SettingsPage.BUTTON_SHAPES, SettingsPage.LAYOUT, SettingsPage.LANGUAGES,
        SettingsPage.GESTURES, SettingsPage.TYPING, SettingsPage.FEEDBACK, SettingsPage.ANIMATIONS)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = cs.surfaceContainer,
        bottomBar = { if (showBottomBar) SettingsTabBar(section, settings, onSection) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tr(page.title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (showBack) SettingsBackButton(onBack)
                },
                actions = {
                    if (home) {
                        Row(
                            Modifier.padding(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(onClick = { onNavigate(SettingsPage.PLUGINS) }) {
                                Icon(Icons.Rounded.Extension, tr("Плагины и моды"))
                            }
                            FilledTonalIconButton(onClick = { onNavigate(SettingsPage.WHATS_NEW) }) {
                                Icon(Icons.Rounded.NewReleases, tr("Новое"))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        val topBarPad = padding.calculateTopPadding()
        val topBlur = if (showTest) topBarPad + 64.dp else topBarPad
        val bottomBlur = if (showBottomBar) padding.calculateBottomPadding() else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .settingsEdgeBlur(top = topBlur, bottom = bottomBlur, tint = cs.surfaceContainer)
                    .verticalScroll(androidx.compose.runtime.key(if (home) (if (settings.classicSettingsUi) "classic" else section) else page) { rememberScrollState() })
                    .padding(top = if (showTest) topBarPad + 72.dp else topBarPad)
                    .then(if (showBottomBar) Modifier.padding(bottom = padding.calculateBottomPadding()) else Modifier.navigationBarsPadding())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                when (page) {
                    SettingsPage.HOME -> HomePage(settings, onNavigate, selected, section)
                    SettingsPage.WHATS_NEW -> WhatsNewPage(onNavigate)
                    SettingsPage.POWER -> PowerPage(settings)
                    SettingsPage.THEMES -> ThemesPage(settings, onNavigate)
                    SettingsPage.THEME_EDITOR -> ThemeEditorPage(settings, onNavigate)
                    SettingsPage.ANIMATIONS -> AnimationsPage(settings)
                    SettingsPage.COLORS -> ColorsPage(settings)
                    SettingsPage.BACKGROUND -> BackgroundPage(settings)
                    SettingsPage.EMOJI_STYLE -> {
                        EmojiSettings(settings)
                        IconsPage(settings)
                    }
                    SettingsPage.FONT -> FontPage(settings)
                    SettingsPage.BUTTON_SHAPES -> ButtonShapesPage(settings)
                    SettingsPage.SIZE -> KeyboardSizePage(settings)
                    SettingsPage.KEYS -> KeysPage(settings, onNavigate)
                    SettingsPage.LAYOUT -> LayoutEditorPage(settings)
                    SettingsPage.LONG_PRESS -> LongPressPage(settings)
                    SettingsPage.TOOLBAR -> {
                        ToolbarPage(settings)
                        SettingsSection(null) {
                            ActionItem(Icons.Rounded.EmojiEmotions, "Эмодзи и иконки", null,
                                onClick = { onNavigate(SettingsPage.EMOJI_STYLE) })
                        }
                    }
                    SettingsPage.LANGUAGES -> LanguagesPage(settings)
                    SettingsPage.MY_WORDS -> MyWordsPage(settings)
                    SettingsPage.TYPING -> TypingPage(settings, SettingsPage.TYPING)
                    SettingsPage.GESTURES -> TypingPage(settings, SettingsPage.GESTURES)
                    SettingsPage.CLIPBOARD -> TypingPage(settings, SettingsPage.CLIPBOARD)
                    SettingsPage.FEEDBACK -> FeedbackPage(settings)
                    SettingsPage.PLUGINS -> PluginsPage()
                    SettingsPage.BACKUP -> BackupPage()
                    SettingsPage.ABOUT -> AboutPage(settings, onNavigate)
                }
                Spacer(Modifier.height(24.dp))
            }
            if (showTest) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = topBarPad + 4.dp, start = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = cs.surfaceContainerHighest,
                    shadowElevation = 6.dp,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.24f))
                ) {
                    PillField(
                        testText, { testText = it }, "Проверьте клавиатуру здесь…", Icons.Rounded.Keyboard,
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberSpec(settings: KeyboardSettings) =
    remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent, settings.shapeAmount) { settings.accentSpec().accent(settings) }

// ----------------------------------------------------------------------- главная

@Composable
private fun HomePage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit, selected: SettingsPage?, section: SettingsTab) {
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
    PillField(query, { query = it }, "Найти настройку: вибрация, шрифт, свайп…", Icons.Rounded.Search, tonal = true)
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
    var i = 0
    val layoutOwner = PluginRepository.settingsLayoutOwner
    val arranger = PluginRepository.active.firstOrNull { it.id == layoutOwner && it.settingsLayout != null }
    val layout = arranger?.settingsLayout
    val expressive = settings.designStyle == DesignStyle.EXPRESSIVE.id && settings.shapeAmount == ShapeAmount.FULL
    if (!imeEnabled || !imeSelected) HeroCard(
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
    if (settings.classicSettingsUi) {
        if (settings.showKeyboardPreview) PreviewSection(settings)
        if (arranger != null && layout != null) {
            SettingsSection(tr("Закреплённые разделы")) {
                for (p in layout.pinned) {
                    CategoryTile(p, i++, selected = p == selected || p == selected?.parent, expressive = expressive) { onNavigate(p) }
                }
                ActionItem(Icons.Rounded.Extension, "Вернуть обычный порядок", arranger.name,
                    onClick = { PluginRepository.setSettingsLayoutOwner(context, null) })
            }
        }
        SettingsSection("Внешний вид") {
            val appearancePages = layout?.arrange(SettingsTab.APPEARANCE.pages) ?: SettingsTab.APPEARANCE.pages
            for (p in appearancePages) {
                val idx = i++
                val subtitle = if (p == SettingsPage.THEMES) {
                    PluginRepository.skinPlugin(settings)?.name ?: DesignStyle.fromId(settings.designStyle).title
                } else p.subtitle
                CategoryTile(p, idx, selected = p == selected || p == selected?.parent, expressive = expressive,
                    subtitle = subtitle) { onNavigate(p) }
            }
        }
        SettingsSection("Клавиатура") {
            val keyboardPages = layout?.arrange(SettingsTab.KEYBOARD.pages) ?: SettingsTab.KEYBOARD.pages
            for (p in keyboardPages) {
                CategoryTile(p, i++, selected = p == selected || p == selected?.parent, expressive = expressive) { onNavigate(p) }
            }
            if (imeSelected && !micGranted) {
                ActionItem(Icons.Rounded.Mic, "Микрофон для голосового ввода", null,
                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            }
        }
        AboutContent(settings = settings, onNavigate = onNavigate, selected = selected, expressive = expressive)
        return
    }
    if (section == SettingsTab.APP) {
        if (arranger != null && layout != null) {
            SettingsSection(tr("Закреплённые разделы")) {
                for (p in layout.pinned) {
                    CategoryTile(p, i++, selected = p == selected || p == selected?.parent, expressive = expressive) { onNavigate(p) }
                }
                ActionItem(Icons.Rounded.Extension, "Вернуть обычный порядок", arranger.name,
                    onClick = { PluginRepository.setSettingsLayoutOwner(context, null) })
            }
        }
        AboutContent(settings = settings, onNavigate = onNavigate, selected = selected, expressive = expressive)
        return
    }
    if (section == SettingsTab.APPEARANCE && settings.showKeyboardPreview) {
        PreviewSection(settings)
    }
    val pages = layout?.arrange(section.pages) ?: section.pages
    if (pages.isNotEmpty()) {
        SettingsSection(null) {
            for (p in pages) {
                val idx = i++
                val subtitle = if (p == SettingsPage.THEMES) {
                    PluginRepository.skinPlugin(settings)?.name ?: DesignStyle.fromId(settings.designStyle).title
                } else p.subtitle
                CategoryTile(p, idx, selected = p == selected || p == selected?.parent, expressive = expressive,
                    subtitle = subtitle) { onNavigate(p) }
            }
            if (SettingsPage.TOOLBAR in pages && imeSelected && !micGranted) {
                ActionItem(Icons.Rounded.Mic, "Микрофон для голосового ввода", null,
                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            }
        }
    }
}

@Composable
internal fun PreviewSection(settings: KeyboardSettings) {
    val skin = PluginRepository.skinPlugin(settings)?.skin
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        SkinMiniPreview(
            skin = skin,
            settings = settings,
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .padding(8.dp),
            interactive = true,
            showHeader = settings.showHeader,
            clipShape = RoundedCornerShape(18.dp)
        )
    }
}

private enum class SettingsTab(val title: String, val icon: ImageVector, val pages: List<SettingsPage>) {
    APPEARANCE("Внешний вид", Icons.Rounded.Palette, listOf(
        SettingsPage.THEMES, SettingsPage.COLORS, SettingsPage.BACKGROUND,
        SettingsPage.FONT, SettingsPage.BUTTON_SHAPES, SettingsPage.ANIMATIONS, SettingsPage.EMOJI_STYLE
    )),
    KEYBOARD("Клавиатура", Icons.Rounded.Keyboard, listOf(
        SettingsPage.KEYS, SettingsPage.SIZE, SettingsPage.LAYOUT, SettingsPage.LONG_PRESS, SettingsPage.TOOLBAR,
        SettingsPage.LANGUAGES, SettingsPage.GESTURES, SettingsPage.TYPING,
        SettingsPage.CLIPBOARD, SettingsPage.FEEDBACK, SettingsPage.MY_WORDS, SettingsPage.POWER
    )),
    APP("Настройки", Icons.Rounded.Settings, listOf(
        SettingsPage.BACKUP
    ))
}

@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад"))
    }
}

@Composable
private fun Modifier.settingsEdgeBlur(top: Dp, bottom: Dp, tint: Color): Modifier = drawWithContent {
    drawContent()
    val topPx = top.toPx().coerceIn(0f, size.height)
    val bottomPx = bottom.toPx().coerceIn(0f, size.height)
    val topFade = if (topPx > 0f) (topPx + 28.dp.toPx()).coerceAtMost(size.height * 0.42f) else 0f
    val bottomFade = if (bottomPx > 0f) (bottomPx + 34.dp.toPx()).coerceAtMost(size.height * 0.42f) else 0f
    if (topFade > 0f) {
        drawRect(
            Brush.verticalGradient(
                0.00f to tint.copy(alpha = 0.98f),
                0.22f to tint.copy(alpha = 0.92f),
                0.44f to tint.copy(alpha = 0.76f),
                0.64f to tint.copy(alpha = 0.48f),
                0.80f to tint.copy(alpha = 0.22f),
                0.92f to tint.copy(alpha = 0.06f),
                1.00f to Color.Transparent,
                startY = 0f,
                endY = topFade
            ),
            size = Size(size.width, topFade)
        )
    }
    if (bottomFade > 0f) {
        val bStart = size.height - bottomFade
        drawRect(
            Brush.verticalGradient(
                0.00f to Color.Transparent,
                0.08f to tint.copy(alpha = 0.06f),
                0.20f to tint.copy(alpha = 0.22f),
                0.36f to tint.copy(alpha = 0.48f),
                0.56f to tint.copy(alpha = 0.76f),
                0.78f to tint.copy(alpha = 0.92f),
                1.00f to tint.copy(alpha = 0.98f),
                startY = bStart,
                endY = size.height
            ),
            topLeft = Offset(0f, bStart),
            size = Size(size.width, bottomFade)
        )
    }
}

@Composable
private fun SettingsTabBar(section: SettingsTab, settings: KeyboardSettings, onSection: (SettingsTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val context = LocalContext.current
    val select: (SettingsTab) -> Unit = {
        if (it != section) {
            HapticsEngine.tick(context, view, settings)
            onSection(it)
        }
    }
    val pillBg = lerp(colors.surfaceContainerHigh, colors.secondaryContainer, 0.28f)
    val pillBorder = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.24f))
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = pillBg,
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                border = pillBorder
            ) {
                Row(
                    Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsTabButton(SettingsTab.APPEARANCE, section, select)
                    SettingsTabButton(SettingsTab.KEYBOARD, section, select)
                }
            }
            val appSelected = section == SettingsTab.APP
            val appInteraction = remember { MutableInteractionSource() }
            val appPressed by appInteraction.collectIsPressedAsState()
            val settingsCorner by animateDpAsState(
                targetValue = when {
                    appPressed -> 14.dp
                    appSelected -> 24.dp
                    else -> 18.dp
                },
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                label = "settingsCorner"
            )
            val appBg by animateColorAsState(
                targetValue = if (appSelected) colors.primary else colors.secondaryContainer,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                label = "settingsBg"
            )
            val appFg by animateColorAsState(
                targetValue = if (appSelected) colors.onPrimary else colors.onSecondaryContainer,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                label = "settingsFg"
            )
            Surface(
                shape = RoundedCornerShape(settingsCorner),
                color = appBg,
                shadowElevation = 8.dp,
                border = if (appSelected) null else pillBorder
            ) {
                Box(
                    Modifier.size(64.dp)
                        .selectable(
                            selected = appSelected,
                            interactionSource = appInteraction,
                            indication = LocalIndication.current,
                            role = Role.Tab
                        ) { select(SettingsTab.APP) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        SettingsTab.APP.icon,
                        contentDescription = tr(SettingsTab.APP.title),
                        tint = appFg,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabButton(
    tab: SettingsTab, section: SettingsTab, onSelect: (SettingsTab) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val selected = tab == section
    val bg by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "tabBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "tabFg"
    )
    val hPad by animateDpAsState(
        targetValue = if (selected) 18.dp else 14.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f),
        label = "tabPad"
    )
    Row(
        Modifier.height(52.dp).widthIn(min = 52.dp)
            .clip(CircleShape)
            .background(bg)
            .selectable(selected, role = Role.Tab) { onSelect(tab) }
            .padding(horizontal = hPad.coerceAtLeast(0.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            tab.icon,
            contentDescription = if (selected) null else tr(tab.title),
            tint = fg,
            modifier = Modifier.size(22.dp)
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(spring(dampingRatio = 0.72f, stiffness = 420f)) + fadeIn(tween(180)),
            exit = shrinkHorizontally(spring(dampingRatio = 0.82f, stiffness = 480f)) + fadeOut(tween(120))
        ) {
            Text(
                tr(tab.title),
                Modifier.padding(start = 8.dp).widthIn(max = 132.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun AppLanguageItem(settings: KeyboardSettings) {
    val context = LocalContext.current
    ChoiceItem(
        Icons.Rounded.Translate, "Язык приложения", null,
        listOf("SYSTEM" to "Как в системе", "ru" to "Русский", "en" to "English"), settings.appLanguage
    ) { v -> KeyboardPrefs.edit(context) { putString("app_language", v) } }
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
internal fun AboutPage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    AboutContent(settings = settings, onNavigate = onNavigate)
}

@Composable
internal fun AboutContent(
    settings: KeyboardSettings,
    onNavigate: (SettingsPage) -> Unit,
    selected: SettingsPage? = null,
    expressive: Boolean = true
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    // Аккуратная карточка приложения и автора в стиле M3 Expressive (без «нейрослопных» заглушек)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceBright,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.24f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Keyboard, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Expressive Keyboard",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = CircleShape,
                        color = cs.secondaryContainer
                    ) {
                        Text(
                            "v$version",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSecondaryContainer
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = { openUrl("https://t.me/Shahedeer") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.Person, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("@Shahedeer", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = { openUrl("https://t.me/materialyouboss") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("@materialyouboss", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    // Резервная копия сразу после плашки автора
    SettingsSection("Приложение") {
        CategoryTile(
            SettingsPage.BACKUP,
            index = 0,
            selected = selected == SettingsPage.BACKUP,
            expressive = expressive
        ) { onNavigate(SettingsPage.BACKUP) }
        AppLanguageItem(settings)
        ToggleItem(
            Icons.Rounded.ViewAgenda,
            "Классический вид настроек",
            "Без нижней панели — все разделы одним списком",
            settings.classicSettingsUi
        ) { KeyboardPrefs.edit(context) { putBoolean("classic_settings_ui", it) } }
        ToggleItem(
            Icons.Rounded.Visibility,
            "Предпросмотр клавиатуры",
            "Показывать мини-клавиатуру в разделах оформления",
            settings.showKeyboardPreview
        ) { KeyboardPrefs.edit(context) { putBoolean("show_keyboard_preview", it) } }
    }
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
                    "Russian word pairs and triples: OpenSubtitles 2018 (OPUS, opus.nlpl.eu, P. Lison & J. Tiedemann); " +
                    "Leipzig Corpora Collection (web 2019, news 2023, Wikipedia 2021) — CC BY 4.0\n" +
                    "Amharic word list: Leipzig Corpora Collection (Wikipedia 2021, community 2017) — CC BY 4.0\n" +
                    "English word pairs: Peter Norvig, count_1w / count_2w (from Google Web 1T)\n" +
                    "Nunito font — SIL OFL 1.1",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
    }
}
