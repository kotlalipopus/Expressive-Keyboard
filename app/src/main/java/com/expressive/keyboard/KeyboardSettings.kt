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
// 2. НАСТРОЙКИ (ОДИН СЛУШАТЕЛЬ НА ВЕСЬ ПРОЦЕСС)
// ============================================================================

enum class HapticMode(val id: String, val title: String) {
    CLICK("CLICK", "Клик"),
    TICK("TICK", "Тик"),
    HEAVY("HEAVY", "Сильный"),
    CUSTOM("CUSTOM", "Свой");

    companion object {
        fun fromId(id: String): HapticMode = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

@Immutable
data class KeyboardSettings(
    val themeMode: String = "SYSTEM",
    val keyHeightDp: Float = 54f,
    val cornerRadiusDp: Float = 16f,
    val keySpacingDp: Float = 5f,
    val rowSpacingDp: Float = 8f,
    val bottomOffsetDp: Float = 8f,
    val shapePreset: String = ExpressiveShapePreset.COOKIE_12.id,
    val waveCount: Int = 12,
    val waveAmplitudePercent: Float = 6f,
    val showNumberRow: Boolean = false,
    val showKeyBorders: Boolean = false,
    val keyPreview: Boolean = true,
    val autoCapitalize: Boolean = true,
    val autoCorrect: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val hapticMode: String = HapticMode.CUSTOM.id,
    val vibrationMs: Float = 16f,
    val vibrationAmplitude: Float = 135f,
    val vibrateOnRelease: Boolean = false,
    val strongSpecialKeys: Boolean = true,
    val soundEnabled: Boolean = false,
    val longPressMs: Float = 320f,
    val fontStyle: String = "ROUNDED",
    val customFontName: String = "",
    val customFontVersion: Long = 0L,
    // --- цвета ---
    val colorSource: String = "DYNAMIC",          // DYNAMIC (обои) | SEED (свой цвет)
    val seedColor: Int = 0xFF6750A4.toInt(),
    val colorBackground: Int = 0,                 // 0 = авто
    val colorKey: Int = 0,
    val colorKeyText: Int = 0,
    val colorFunc: Int = 0,
    val colorFuncText: Int = 0,
    val colorAccent: Int = 0,
    val colorAccentText: Int = 0,
    val keyOpacity: Float = 1f,
    /** Прозрачность фона клавиатуры: 0 — обычный фон, 1 — фона нет, видно только клавиши. */
    val bgTransparency: Float = 0f,
    // --- фон ---
    val bgImageVersion: Long = 0L,
    val bgDim: Float = 0.35f,
    val bgBlurDp: Float = 0f,
    // --- клавиши ---
    val keyLabelScale: Float = 1f,
    val fontWeight: Int = 700,
    val showHints: Boolean = true,
    val sidePaddingDp: Float = 6f,
    val oneHanded: String = "OFF",                // OFF | LEFT | RIGHT
    val oneHandedWidth: Float = 0.7f,
    /** Верхняя панель над клавиатурой: подсказки, микрофон и кнопка меню. */
    val showHeader: Boolean = true,
    /** Поля чисел и телефонов получают цифровую клавиатуру 4×4, как у системной. */
    val numberPad: Boolean = true,
    /** «Жидкое стекло» на любой теме: клавиши-линзы с объёмными бликами (Android 13+). */
    val liquidGlass: Boolean = false,
    val showSuggestions: Boolean = true,
    val showMicButton: Boolean = true,
    val showEmojiKey: Boolean = true,
    val showGlobeKey: Boolean = true,
    val pressAnimation: Float = 1f,               // 0 = без анимации нажатия
    val panelAnimations: Boolean = true,
    /** Экономия заряда: в режиме энергосбережения системы клавиши нажимаются без анимаций. */
    val ecoMode: Boolean = true,
    /** Анимация нажатия: THEME — из темы, id готовой или своей (Animations.kt). */
    val animPress: String = "THEME",
    /** Скорость всех анимаций клавиатуры. */
    val animSpeed: Float = 1f,
    /** Появление подсказки над клавишей: POP | ZOOM | SLIDE | FADE | NONE */
    val animPopup: String = "POP",
    /** Переход между панелями: SCALE | SLIDE | FADE | NONE */
    val animPanel: String = "SCALE",
    /** Свои анимации нажатия (JSON-массив). */
    val customAnims: String = "",
    // --- ввод ---
    val spaceSwipe: String = "CURSOR",            // CURSOR | LANGUAGE | OFF
    /** Удержание кнопки языка: LANGUAGES | SYSTEM | EMOJI | CLIPBOARD | VOICE | SETTINGS | ONE_HANDED | OFF */
    val globeLongPress: String = "LANGUAGES",
    /** Панель редактирования над клавиатурой: курсор, выделение, копировать/вставить. */
    val editBar: Boolean = false,
    /** Удержание кнопки панели функций: PIN — закрепить панель над подсказками | EDIT_BAR — панель редактирования. */
    val toolsLongPress: String = "PIN",
    /** Размытие приложения под клавиатурой: THEME — как в оформлении | ON | OFF. По умолчанию выключено: оно греет телефон. */
    val windowBlur: String = "OFF",
    /** Сила размытия, dp. */
    val windowBlurDp: Float = 32f,
    val backspaceSwipe: Boolean = true,           // свайп влево по ⌫ удаляет слово
    val clipboardHistory: Boolean = true,
    val clipboardSize: Int = 50,
    /** Сколько хранить записи буфера: 1H | 1D | 7D | 30D | FOREVER. */
    val clipboardKeep: String = "FOREVER",
    /** Новые скриншоты сами попадают в буфер (нужен доступ к фото). */
    val clipScreenshots: Boolean = true,
    val soundVolume: Float = 0.6f,
    val giphyKey: String = "",
    /** Свой ключ KLIPY (бесплатный, замена Tenor). */
    val klipyKey: String = "",
    /** Кто исправляет текст кнопкой ✨: AUTO | NANO | GEMINI | CLAUDE | OPENAI | GROQ | LANGUAGETOOL. */
    val aiProvider: String = "AUTO",
    /** Ключи ИИ-сервисов (секреты: не попадают в экспорт настроек). */
    val anthropicKey: String = "",
    val geminiKey: String = "",
    val openaiKey: String = "",
    val groqKey: String = "",
    /** Свои модели ИИ (пусто — по умолчанию). */
    val geminiModel: String = "",
    val claudeModel: String = "",
    val openaiModel: String = "",
    val groqModel: String = "",
    // --- скорость и жесты ---
    val instantInput: Boolean = true,             // буква появляется в момент касания, а не при отпускании
    val glideTyping: Boolean = true,              // печать, не отрывая пальца
    val glideTrail: Boolean = true,
    val swipeUpHint: Boolean = true,              // свайп вверх по клавише вводит символ-подсказку
    val spaceLongPress: Boolean = true,           // удержание пробела включено
    /** Удержание пробела: PICKER | AI_FIX | VOICE | EMOJI | CLIPBOARD | TOOLBAR | SETTINGS | OFF */
    val spaceLongAction: String = "PICKER",
    /** Свой звук нажатия: версия файла (0 — системный звук). */
    val customSoundVersion: Long = 0L,
    val autoPair: Boolean = false,                // ( → () и курсор внутрь
    val incognito: Boolean = false,               // не учить слова и не запоминать буфер
    // --- оформление из плагина ---
    val skinPlugin: String = "",
    /** Настройки тем: JSON { "id темы": { "id настройки": значение } }. */
    val skinOptions: String = "",
    // --- языки и подсказки ---
    val languages: String = "ru,en",              // включённые языки по порядку
    val autoCorrectStrength: String = "NORMAL",   // SAFE — только уверенные исправления | NORMAL — как Gboard
    val dictAutoDownload: Boolean = true,         // скачивать словарь языка при включении
    val punctuationKeys: Boolean = true,          // «,» и «.» по бокам пробела, как в Gboard
    val spaceLeftKey: String = "",                // своя клавиша слева от пробела на буквах: "" — нет
    val spaceRightKey: String = "",               // …и справа
    val systemSpellcheck: Boolean = true,         // спрашивать системную проверку правописания (Gboard, Samsung)
    val smartPunctuation: Boolean = true,         // «слово ,» → «слово,»
    /** Пробел сам ставится после запятой, «!», «?», «;» и «:» за словом. */
    val autoSpacePunct: Boolean = true,
    /** Два дефиса подряд превращаются в длинное тире: «--» → «—». */
    val dashMerge: Boolean = true,
    /** Исправлять и там, где приложение просит клавиатуру не исправлять (кроме паролей, почты и адресов). */
    val correctEverywhere: Boolean = true,
    /** Динамические зоны клавиш, как на iPhone: у границы клавиш срабатывает буква, вероятнее подходящая к слову. */
    val dynamicZones: Boolean = true,
    /** Голосовой ввод чистится ИИ (без «э-э», повторов и оговорок), если задан ключ ИИ. */
    val aiVoiceCleanup: Boolean = true,
    /** Кнопка панели функций в строке подсказок. Без неё и без подсказок панель функций видна всегда. */
    val toolsButton: Boolean = true,
    /** Панель функций закреплена отдельной строкой над подсказками (удержание кнопки панели функций). */
    val pinnedTools: Boolean = false,
    /** Что по удержанию буквы: VARIANTS — варианты буквы и символы (как раньше), SYMBOLS — символ с того же места на «?123», как в Gboard. */
    val holdMode: String = "VARIANTS",
    /** В альбомной ориентации клавиатура делится на две половины — до букв легко дотянуться большими пальцами. */
    val landscapeSplit: Boolean = true,
    /** Свои раскладки языков: JSON { "код языка": ["ряд", "ряд", …] }. */
    val langLayouts: String = "",
    val translateTarget: String = "en",
    // --- панель инструментов ---
    val toolbarOrder: String = "",
    val toolbarHidden: String = "",
    /** Свои места клавиш: JSON «раскладка → порядок символьных клавиш». */
    val userLayouts: String = "",
    // --- эмодзи ---
    val emojiFontVersion: Long = 0L,
    /** SYSTEM, CUSTOM (свой шрифт) или встроенный набор: twemoji, noto, blob, openmoji. */
    val emojiStyle: String = "SYSTEM",
    // --- интерфейс ---
    val appLanguage: String = "SYSTEM",           // SYSTEM | ru | en
    val designStyle: String = "EXPRESSIVE",
    /** Не хранится: клавиатура в альбомной ориентации — ниже клавиши и шапка. */
    val landscape: Boolean = false
) {
    val toolbarOrderList: List<String> get() = toolbarOrder.split(',').filter { it.isNotBlank() }
    val toolbarHiddenSet: Set<String> get() = toolbarHidden.split(',').filter { it.isNotBlank() }.toSet()
    val userLayoutMap: Map<String, List<String>> by lazy { UserLayouts.parse(userLayouts) }

    val hasBackgroundImage: Boolean get() = bgImageVersion != 0L

    /**
     * Акцент, который выбрал сам пользователь: «Акцент» в цветах или «Свой цвет» палитры.
     * В него перекрашивается любая тема; 0 — пользователь ничего не выбирал, у темы свои цвета.
     */
    val userAccent: Int get() = when {
        colorAccent != 0 -> colorAccent
        colorSource == "SEED" -> seedColor
        else -> 0
    }

    /** В альбомной ориентации клавиатура занимает не больше ~55% высоты экрана. */
    fun forLandscape(screenHeightDp: Int): KeyboardSettings {
        val rows = if (showNumberRow) 4.78f else 4f
        val row = min(rowSpacingDp, 5f)
        val bottom = min(bottomOffsetDp, 2f)
        val budget = screenHeightDp * 0.55f - 40f - 16f - bottom - row * (rows - 1f)
        return copy(
            keyHeightDp = (budget / rows).coerceIn(30f, keyHeightDp),
            rowSpacingDp = row, bottomOffsetDp = bottom, landscape = true
        )
    }

    /** Ключи API не попадают ни в логи, ни в отчёты об ошибках. */
    override fun toString(): String {
        fun mask(k: String) = if (k.isEmpty()) "" else "•••" + k.takeLast(3)
        return "KeyboardSettings(theme=$themeMode, skin=$skinPlugin, languages=$languages, ai=$aiProvider, " +
            "anthropicKey=${mask(anthropicKey)}, geminiKey=${mask(geminiKey)}, openaiKey=${mask(openaiKey)}, groqKey=${mask(groqKey)}, " +
            "giphyKey=${mask(giphyKey)}, klipyKey=${mask(klipyKey)})"
    }

    fun accentSpec(): WavySpec {
        val p = ExpressiveShapePreset.fromId(shapePreset)
        return WavySpec(p.profile, waveCount, waveAmplitudePercent / 100f)
    }
}

object KeyboardPrefs {
    const val PREFS_NAME = "expressive_keyboard_v7_prefs"
    const val CUSTOM_FONT_FILENAME = "custom_keyboard_font.ttf"

    private val state = mutableStateOf(KeyboardSettings())
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureInit(context: Context) {
        if (listener != null) return
        val sp = prefs(context)
        SecretBox.migrate(sp)
        state.value = read(sp)
        I18n.apply(state.value.appLanguage)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            state.value = read(p)
            I18n.apply(state.value.appLanguage)
        }
        listener = l
        sp.registerOnSharedPreferenceChangeListener(l)
    }

    fun current(context: Context): KeyboardSettings {
        ensureInit(context)
        return state.value
    }

    @Composable
    fun observe(): State<KeyboardSettings> {
        ensureInit(LocalContext.current)
        return state
    }

    fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val e = prefs(context).edit()
        e.block()
        e.apply()
    }

    private fun read(p: SharedPreferences): KeyboardSettings {
        val font = p.getString("font_style", "ROUNDED")
        return KeyboardSettings(
            themeMode = p.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
            keyHeightDp = p.getFloat("key_height_dp", 54f).coerceIn(32f, 68f),
            cornerRadiusDp = p.getFloat("corner_radius_dp", 16f).coerceIn(6f, 30f),
            keySpacingDp = p.getFloat("key_spacing_dp", 5f).coerceIn(2f, 8f),
            rowSpacingDp = p.getFloat("row_spacing_dp", 8f).coerceIn(2f, 12f),
            bottomOffsetDp = p.getFloat("bottom_offset_dp", 8f).coerceIn(0f, 64f),
            shapePreset = p.getString("accent_shape_preset", ExpressiveShapePreset.COOKIE_12.id)
                ?: ExpressiveShapePreset.COOKIE_12.id,
            waveCount = p.getInt("wave_count", 12).coerceIn(4, 18),
            waveAmplitudePercent = p.getFloat("wave_amplitude_percent", 6f).coerceIn(0f, 14f),
            showNumberRow = p.getBoolean("show_number_row", false),
            showKeyBorders = p.getBoolean("show_key_borders", false),
            keyPreview = p.getBoolean("key_preview", true),
            autoCapitalize = p.getBoolean("auto_capitalize", true),
            autoCorrect = p.getBoolean("auto_correct_enabled", true),
            doubleSpacePeriod = p.getBoolean("double_space_period", true),
            hapticsEnabled = p.getBoolean("haptics_enabled", true),
            hapticMode = p.getString("haptic_mode", HapticMode.CUSTOM.id) ?: HapticMode.CUSTOM.id,
            vibrationMs = p.getFloat("vibration_ms", 16f).coerceIn(1f, 80f),
            vibrationAmplitude = p.getFloat("vibration_amplitude", 135f).coerceIn(1f, 255f),
            vibrateOnRelease = p.getBoolean("vibrate_on_release", false),
            strongSpecialKeys = p.getBoolean("strong_special_keys", true),
            soundEnabled = p.getBoolean("sound_enabled", false),
            longPressMs = p.getFloat("long_press_ms", 320f).coerceIn(180f, 700f),
            fontStyle = if (font == null || font == "DEFAULT") "ROUNDED" else font,
            customFontName = p.getString("custom_font_name", "") ?: "",
            customFontVersion = p.getLong("custom_font_version", 0L),
            colorSource = p.getString("color_source", "DYNAMIC") ?: "DYNAMIC",
            seedColor = p.getInt("seed_color", 0xFF6750A4.toInt()),
            colorBackground = p.getInt("color_background", 0),
            colorKey = p.getInt("color_key", 0),
            colorKeyText = p.getInt("color_key_text", 0),
            colorFunc = p.getInt("color_func", 0),
            colorFuncText = p.getInt("color_func_text", 0),
            colorAccent = p.getInt("color_accent", 0),
            colorAccentText = p.getInt("color_accent_text", 0),
            keyOpacity = p.getFloat("key_opacity", 1f).coerceIn(0.2f, 1f),
            bgTransparency = p.getFloat("bg_transparency", 0f).coerceIn(0f, 1f),
            bgImageVersion = p.getLong("bg_image_version", 0L),
            bgDim = p.getFloat("bg_dim", 0.35f).coerceIn(0f, 0.9f),
            bgBlurDp = p.getFloat("bg_blur_dp", 0f).coerceIn(0f, 24f),
            keyLabelScale = p.getFloat("key_label_scale", 1f).coerceIn(0.7f, 1.4f),
            fontWeight = p.getInt("font_weight", 700).coerceIn(300, 900),
            showHints = p.getBoolean("show_hints", true),
            sidePaddingDp = p.getFloat("side_padding_dp", 6f).coerceIn(0f, 32f),
            oneHanded = p.getString("one_handed", "OFF") ?: "OFF",
            oneHandedWidth = p.getFloat("one_handed_w", 0.7f).coerceIn(0.55f, 0.9f),
            showHeader = p.getBoolean("show_header", true),
            numberPad = p.getBoolean("number_pad", true),
            liquidGlass = p.getBoolean("liquid_glass", false),
            showSuggestions = p.getBoolean("show_suggestions", true),
            showMicButton = p.getBoolean("show_mic_button", true),
            showEmojiKey = p.getBoolean("show_emoji_key", true),
            showGlobeKey = p.getBoolean("show_globe_key", true),
            pressAnimation = p.getFloat("press_animation", 1f).coerceIn(0f, 2f),
            panelAnimations = p.getBoolean("panel_animations", true),
            ecoMode = p.getBoolean("eco_mode", true),
            animPress = p.getString("anim_press", "THEME") ?: "THEME",
            animSpeed = p.getFloat("anim_speed", 1f).coerceIn(0.5f, 2f),
            animPopup = p.getString("anim_popup", "POP") ?: "POP",
            animPanel = p.getString("anim_panel", "SCALE") ?: "SCALE",
            customAnims = p.getString("custom_anims", "") ?: "",
            spaceSwipe = p.getString("space_swipe", "CURSOR") ?: "CURSOR",
            globeLongPress = p.getString("globe_long_press", "LANGUAGES") ?: "LANGUAGES",
            editBar = p.getBoolean("edit_bar", false),
            toolsLongPress = p.getString("tools_long_press", "PIN") ?: "PIN",
            windowBlur = p.getString("window_blur", "OFF") ?: "OFF",
            windowBlurDp = p.getFloat("window_blur_dp", 32f).coerceIn(4f, 100f),
            backspaceSwipe = p.getBoolean("backspace_swipe", true),
            clipboardHistory = p.getBoolean("clipboard_history", true),
            // 0 — без ограничения числа записей
            clipboardSize = p.getInt("clipboard_size", 50).let { if (it <= 0) Int.MAX_VALUE else it.coerceIn(5, 5000) },
            clipboardKeep = p.getString("clipboard_keep", "FOREVER") ?: "FOREVER",
            clipScreenshots = p.getBoolean("clip_screenshots", true),
            soundVolume = p.getFloat("sound_volume", 0.6f).coerceIn(0.05f, 1f),
            giphyKey = SecretBox.decrypt(p.getString("giphy_key", "") ?: ""),
            klipyKey = SecretBox.decrypt(p.getString("klipy_key", "") ?: ""),
            anthropicKey = SecretBox.decrypt(p.getString("anthropic_key", "") ?: ""),
            aiProvider = p.getString("ai_provider", "AUTO") ?: "AUTO",
            geminiKey = SecretBox.decrypt(p.getString("gemini_key", "") ?: ""),
            openaiKey = SecretBox.decrypt(p.getString("openai_key", "") ?: ""),
            groqKey = SecretBox.decrypt(p.getString("groq_key", "") ?: ""),
            geminiModel = (p.getString("gemini_model", "") ?: "").trim(),
            claudeModel = (p.getString("anthropic_model", "") ?: "").trim(),
            openaiModel = (p.getString("openai_model", "") ?: "").trim(),
            groqModel = (p.getString("groq_model", "") ?: "").trim(),
            instantInput = p.getBoolean("instant_input", true),
            glideTyping = p.getBoolean("glide_typing", true),
            glideTrail = p.getBoolean("glide_trail", true),
            swipeUpHint = p.getBoolean("swipe_up_hint", true),
            spaceLongPress = p.getBoolean("space_long_press", true),
            spaceLongAction = if (!p.getBoolean("space_long_press", true)) "OFF" else p.getString("space_long_action", "PICKER") ?: "PICKER",
            customSoundVersion = p.getLong("custom_sound_version", 0L),
            autoPair = p.getBoolean("auto_pair", false),
            incognito = p.getBoolean("incognito", false),
            skinPlugin = p.getString("skin_plugin", "") ?: "",
            skinOptions = p.getString("skin_options", "") ?: "",
            languages = p.getString("languages", "ru,en")?.ifBlank { "ru,en" } ?: "ru,en",
            autoCorrectStrength = p.getString("auto_correct_strength", "NORMAL") ?: "NORMAL",
            dictAutoDownload = p.getBoolean("dict_auto_download", true),
            punctuationKeys = p.getBoolean("punctuation_keys", true),
            spaceLeftKey = p.getString("space_left_key", "") ?: "",
            spaceRightKey = p.getString("space_right_key", "") ?: "",
            systemSpellcheck = p.getBoolean("system_spellcheck", true),
            smartPunctuation = p.getBoolean("smart_punctuation", true),
            autoSpacePunct = p.getBoolean("auto_space_punct", true),
            dashMerge = p.getBoolean("dash_merge", true),
            correctEverywhere = p.getBoolean("correct_everywhere", true),
            dynamicZones = p.getBoolean("dynamic_zones", true),
            aiVoiceCleanup = p.getBoolean("ai_voice_cleanup", true),
            toolsButton = p.getBoolean("tools_button", true),
            pinnedTools = p.getBoolean("pinned_tools", false),
            holdMode = p.getString("hold_mode", "VARIANTS") ?: "VARIANTS",
            landscapeSplit = p.getBoolean("landscape_split", true),
            langLayouts = p.getString("lang_layouts", "") ?: "",
            translateTarget = p.getString("translate_target", "en") ?: "en",
            toolbarOrder = p.getString("toolbar_order", "") ?: "",
            toolbarHidden = p.getString("toolbar_hidden", "") ?: "",
            userLayouts = p.getString("user_layouts", "") ?: "",
            emojiFontVersion = p.getLong("emoji_font_version", 0L),
            emojiStyle = p.getString("emoji_style", null) ?: if (p.getLong("emoji_font_version", 0L) != 0L) "CUSTOM" else "SYSTEM",
            appLanguage = p.getString("app_language", "SYSTEM") ?: "SYSTEM",
            designStyle = p.getString("design_style", "EXPRESSIVE") ?: "EXPRESSIVE"
        )
    }

    const val EMOJI_FONT_FILENAME = "emoji_font.ttf"

    fun setEmojiFont(context: Context) = edit(context) {
        putLong("emoji_font_version", System.currentTimeMillis())
        putString("emoji_style", "CUSTOM")
    }

    fun resetEmojiFont(context: Context) {
        File(context.filesDir, EMOJI_FONT_FILENAME).delete()
        edit(context) {
            putLong("emoji_font_version", 0L)
            putString("emoji_style", "SYSTEM")
        }
    }

    /** Типы ключей настроек — плагин может менять любой из них блоком "settings". */
    val KEY_TYPES: Map<String, Char> = buildMap {
        listOf(
            "key_height_dp", "corner_radius_dp", "key_spacing_dp", "row_spacing_dp", "bottom_offset_dp",
            "wave_amplitude_percent", "vibration_ms", "vibration_amplitude", "long_press_ms", "key_opacity", "bg_transparency", "bg_dim",
            "bg_blur_dp", "window_blur_dp", "key_label_scale", "side_padding_dp", "one_handed_w", "press_animation", "sound_volume", "anim_speed"
        ).forEach { put(it, 'f') }
        listOf(
            "wave_count", "seed_color", "color_background", "color_key", "color_key_text", "color_func",
            "color_func_text", "color_accent", "color_accent_text", "font_weight", "clipboard_size"
        ).forEach { put(it, 'i') }
        listOf(
            "show_number_row", "show_key_borders", "key_preview", "auto_capitalize", "auto_correct_enabled",
            "double_space_period", "haptics_enabled", "vibrate_on_release", "strong_special_keys", "sound_enabled",
            "show_hints", "show_header", "number_pad", "liquid_glass", "show_suggestions", "show_mic_button", "show_emoji_key", "show_globe_key", "panel_animations",
            "backspace_swipe", "clipboard_history", "instant_input", "glide_typing", "glide_trail", "swipe_up_hint",
            "space_long_press", "auto_pair", "incognito", "dict_auto_download", "punctuation_keys", "smart_punctuation",
            "system_spellcheck", "edit_bar", "auto_space_punct", "eco_mode", "dash_merge", "clip_screenshots", "correct_everywhere", "dynamic_zones", "ai_voice_cleanup", "tools_button", "pinned_tools", "landscape_split"
        ).forEach { put(it, 'b') }
        listOf(
            "theme_mode", "accent_shape_preset", "haptic_mode", "font_style", "color_source", "one_handed", "space_swipe",
            "globe_long_press", "tools_long_press", "window_blur", "hold_mode", "ai_provider", "space_long_action", "languages", "auto_correct_strength", "translate_target", "gemini_model", "anthropic_model", "openai_model", "groq_model", "toolbar_order", "toolbar_hidden", "user_layouts",
            "space_left_key", "space_right_key", "emoji_style", "clipboard_keep", "app_language", "design_style", "skin_options", "anim_press", "anim_popup", "anim_panel", "custom_anims", "lang_layouts"
        ).forEach { put(it, 's') }
    }

    /** Применяет блок "settings" плагина; неизвестные ключи и неверные типы пропускаются. */
    fun applyPluginSettings(context: Context, values: Map<String, Any>): Int {
        var n = 0
        edit(context) {
            for ((k, v) in values) {
                val ok = when (KEY_TYPES[k]) {
                    'f' -> (v as? Number)?.let { putFloat(k, it.toFloat()) } != null
                    'i' -> when (v) {
                        is Number -> { putInt(k, v.toInt()); true }
                        is String -> PluginParser.parseColor(v)?.let { putInt(k, it) } != null
                        else -> false
                    }
                    'b' -> (v as? Boolean)?.let { putBoolean(k, it) } != null
                    's' -> (v as? String)?.takeIf { !(k == "font_style" && it == "CUSTOM") }?.let { putString(k, it) } != null
                    else -> false
                }
                if (ok) n++
            }
        }
        return n
    }

    const val BG_IMAGE_FILENAME = "keyboard_background.jpg"

    fun setBackgroundImage(context: Context) = edit(context) { putLong("bg_image_version", System.currentTimeMillis()) }

    fun clearBackgroundImage(context: Context) {
        File(context.filesDir, BG_IMAGE_FILENAME).delete()
        edit(context) { putLong("bg_image_version", 0L) }
    }

    fun resetColors(context: Context) = edit(context) {
        listOf(
            "color_background", "color_key", "color_key_text", "color_func",
            "color_func_text", "color_accent", "color_accent_text", "key_opacity", "bg_transparency"
        ).forEach { remove(it) }
    }

    /** Резервная копия: все настройки с типами, без словаря и файлов. */
    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("format", "expressive-keyboard-settings")
        root.put("version", 1)
        val values = JSONObject()
        for ((k, v) in prefs(context).all) {
            // ключи API в файл настроек не попадают: его могут переслать или выложить
            if (k in SecretBox.SECRET_PREFS) continue
            val item = JSONObject()
            when (v) {
                is Boolean -> item.put("t", "b").put("v", v)
                is Int -> item.put("t", "i").put("v", v)
                is Long -> item.put("t", "l").put("v", v)
                is Float -> item.put("t", "f").put("v", v.toDouble())
                is String -> item.put("t", "s").put("v", v)
                else -> continue
            }
            values.put(k, item)
        }
        root.put("values", values)
        return root.toString(2)
    }

    /** Возвращает число применённых значений; файлы шрифта/фона из копии не переносятся. */
    fun importJson(context: Context, raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "expressive-keyboard-settings") { "Это не файл настроек клавиатуры" }
        val values = root.getJSONObject("values")
        var n = 0
        edit(context) {
            val keys = values.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "bg_image_version" || k == "custom_font_version" || k == "custom_font_name" || k == "emoji_font_version") continue
                if (k in SecretBox.SECRET_PREFS) continue
                val item = values.optJSONObject(k) ?: continue
                when (item.optString("t")) {
                    "b" -> putBoolean(k, item.optBoolean("v"))
                    "i" -> putInt(k, item.optInt("v"))
                    "l" -> putLong(k, item.optLong("v"))
                    "f" -> putFloat(k, item.optDouble("v").toFloat())
                    "s" -> putString(k, item.optString("v"))
                    else -> continue
                }
                n++
            }
            if (values.optJSONObject("font_style")?.optString("v") == "CUSTOM") putString("font_style", "ROUNDED")
            // файлы шрифта эмодзи и фона из копии не переносятся — свой стиль эмодзи без файла не включаем
            if (values.optJSONObject("emoji_style")?.optString("v") == "CUSTOM") putString("emoji_style", "SYSTEM")
        }
        return n
    }

    fun applyPreset(context: Context, preset: ExpressiveShapePreset) = edit(context) {
        putString("accent_shape_preset", preset.id)
        putInt("wave_count", preset.defaultWaves)
        putFloat("wave_amplitude_percent", preset.defaultAmplitude)
    }

    fun saveCustomFont(context: Context, name: String) = edit(context) {
        putString("font_style", "CUSTOM")
        putString("custom_font_name", name)
        putLong("custom_font_version", System.currentTimeMillis())
    }

    fun resetCustomFont(context: Context) {
        File(context.filesDir, CUSTOM_FONT_FILENAME).delete()
        edit(context) {
            putString("font_style", "ROUNDED")
            putString("custom_font_name", "")
            putLong("custom_font_version", System.currentTimeMillis())
        }
    }

    fun resetAll(context: Context) {
        File(context.filesDir, CUSTOM_FONT_FILENAME).delete()
        File(context.filesDir, EMOJI_FONT_FILENAME).delete()
        File(context.filesDir, BG_IMAGE_FILENAME).delete()
        edit(context) { clear() }
    }
}
