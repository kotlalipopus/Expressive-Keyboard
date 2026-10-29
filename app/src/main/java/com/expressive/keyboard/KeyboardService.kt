@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
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
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InlineSuggestionsRequest
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
// 10. СОСТОЯНИЕ, ГОЛОС И СЕРВИС КЛАВИАТУРЫ
// ============================================================================

@Stable
class KeyboardState {
    var language by mutableStateOf(KeyboardLanguage.RU)
    var shift by mutableStateOf(ShiftState.OFF)
    var symbolsPage by mutableIntStateOf(0)
    var panel by mutableStateOf(ActivePanel.KEYBOARD)
    /** Голосовая панель открыта удержанием микрофона — режим Rambler. */
    var rambler by mutableStateOf(false)
    var toolbarOpen by mutableStateOf(false)
    var suggestions by mutableStateOf(SuggestionChipModel.EMPTY_ROW)
    /** Варианты китайского/японского ввода: показываются лентой вместо трёх подсказок. */
    var candidates by mutableStateOf<List<String>>(emptyList())
    var enterAction by mutableIntStateOf(EditorInfo.IME_ACTION_UNSPECIFIED)
    var translatorInput by mutableStateOf("")
    var translatorResult by mutableStateOf("")
    var translatorLoading by mutableStateOf(false)
    var emojiQuery by mutableStateOf("")
    /** Подсказки автозаполнения (пароли, логины из Google Паролей и т. п.) — готовые View от системы. */
    var inlineSuggestions by mutableStateOf<List<View>>(emptyList())
    /** Идёт исправление текста — кнопка на панели крутится, по клавиатуре бежит сияние. */
    var aiBusy by mutableStateOf(false)
    /** Текст только что исправлен — короткая вспышка. Число растёт с каждым исправлением. */
    var aiDone by mutableIntStateOf(0)
    /** Своё указание для ИИ, которое печатается на клавиатуре (панель AI_PROMPT). */
    var aiPrompt by mutableStateOf("")
    /** Что ИИ делает сейчас (id задачи) — эта кнопка панели крутится. */
    var aiTask by mutableStateOf("")
    /** Последнюю правку ИИ можно отменить кнопкой на панели. */
    var aiCanUndo by mutableStateOf(false)
    /** Поле пустое: вместо подсказок — панель функций. */
    var fieldEmpty by mutableStateOf(false)
    /** На пустом поле панель функций убрали кнопкой меню — вместо неё подсказки первого слова. */
    var toolsDismissed by mutableStateOf(false)
    /** Режим выделения на панели редактирования: стрелки расширяют выделение, как с зажатым Shift. */
    var selectMode by mutableStateOf(false)
    /** Запрос поиска GIF: набирается в строке над клавиатурой, по Enter — результаты в панели GIF. */
    var gifQuery by mutableStateOf("")
    var pluginTab by mutableStateOf("")
    var pluginStatus by mutableStateOf("")
    /** Печать свайпом разрешена в этом поле (не пароль, не почта/адрес). */
    var glideAllowed by mutableStateOf(true)
    val clipboard = mutableStateListOf<String>()
    /** Только что скопированное: предлагается вставить одним нажатием над клавиатурой. */
    var clipOffer by mutableStateOf<String?>(null)
    /** Закреплённые записи буфера: хранятся на диске и не стираются кнопкой «Очистить». */
    val pinnedClips = mutableStateListOf<String>()
    /** Окно клавиатуры только что появилось: панели и строка подсказок встают на место без анимаций. */
    var instantUi by mutableStateOf(false)
}

@Stable
interface KeyboardActions {
    fun keyDown(key: KeyModel)
    fun keyUp()
    fun keyTick()
    fun commitKey(key: KeyModel, text: String)
    /** Где именно нажата следующая клавиша: смещение от её центра в долях ширины и высоты (-0.5…0.5). */
    fun keyTouch(fx: Float, fy: Float) {}
    fun swipeSpace(direction: Int)
    fun pickSuggestion(chip: SuggestionChipModel)
    fun commitText(text: String)
    fun commitEmoji(emoji: String)
    fun deleteBackward()
    fun commitGif(gif: TenorGifResult)
    fun headerMenu()
    fun togglePanel(panel: ActivePanel)
    fun toggleVoice()
    fun openSettings()
    fun openVoiceFallback()
    fun hideKeyboard()
    fun clearClipboard()
    fun insertTranslation()
    fun commitSymbol(text: String)
    fun runPluginAction(pluginId: String, index: Int)
    /** Мгновенный ввод: буква уже вставлена при касании — заменить её вариантом (или убрать, если new пустой). */
    fun replaceLast(old: String, new: String)
    /** tapLetter — буква, уже вставленная мгновенным вводом с первой клавиши свайпа: слово заменит её. */
    fun commitGlide(path: List<Offset>, keys: Map<Char, Offset>, keyWidth: Float, tapLetter: String? = null)
    fun switchKeyboard()
    fun runScriptButton(pluginId: String, id: String)
    fun removeClip(text: String)
    fun togglePinClip(text: String)
    /** Долгое нажатие на подсказку: забыть выученное слово. */
    fun forgetSuggestion(word: String)
    /** Выбор варианта иероглифов/каны из ленты. */
    fun pickCandidate(index: Int) {}
    /** Вставить запись буфера: текст или картинку. */
    fun commitClip(entry: String) {}
    fun pasteClipOffer() {}
    fun dismissClipOffer() {}
    /** Удержание кнопки языка, если в настройках выбрано не «список языков». */
    fun globeHold() {}
    /** Кнопка панели редактирования: home, end, up, down, left, right, select, select_all, copy, paste. */
    fun editKey(id: String) {}
    /** Исправить выделенный текст (или весь текст поля): Claude по ключу или бесплатная проверка. */
    fun aiFix() {}
    /** Кнопка панели ИИ: id задачи (AiTask), "prompt" — набрать своё указание, "send" — выполнить его, "undo". */
    fun aiTool(id: String) {}
    /** Удержание пробела — действие из настроек. */
    fun spaceHold() {}
    /** Свайп влево по ⌫: выделить столько слов перед курсором (0 — снять выделение). */
    fun backspaceSelect(words: Int) {}
    /** Свайп по ⌫ закончился: delete — стереть выделенное, иначе оставить текст как был. */
    fun backspaceSelectDone(delete: Boolean) {}
    /** Удержание микрофона: Rambler — диктовка без спешки, ИИ убирает «э-э», повторы и оговорки. */
    fun rambler() {}
    /** Поле ввода мода: submit — отправить набранное (✓), иначе закрыть без отправки (←). */
    fun scriptPromptDone(submit: Boolean) {}
}

/**
 * Все действия клавиатуры — через эту обёртку: ошибка в одном нажатии (поле ввода другого приложения,
 * словарь, мод) пишется в журнал, а не роняет клавиатуру вместе с приложением.
 */
internal class SafeActions(private val a: KeyboardActions, private val onError: () -> Unit) : KeyboardActions {
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ExpressiveKeyboard", "action failed", e)
            runCatching(onError)
        }
    }

    override fun keyDown(key: KeyModel) = guard { a.keyDown(key) }
    override fun keyUp() = guard { a.keyUp() }
    override fun keyTick() = guard { a.keyTick() }
    override fun commitKey(key: KeyModel, text: String) = guard { a.commitKey(key, text) }
    override fun keyTouch(fx: Float, fy: Float) = guard { a.keyTouch(fx, fy) }
    override fun swipeSpace(direction: Int) = guard { a.swipeSpace(direction) }
    override fun pickSuggestion(chip: SuggestionChipModel) = guard { a.pickSuggestion(chip) }
    override fun commitText(text: String) = guard { a.commitText(text) }
    override fun commitEmoji(emoji: String) = guard { a.commitEmoji(emoji) }
    override fun deleteBackward() = guard { a.deleteBackward() }
    override fun commitGif(gif: TenorGifResult) = guard { a.commitGif(gif) }
    override fun headerMenu() = guard { a.headerMenu() }
    override fun togglePanel(panel: ActivePanel) = guard { a.togglePanel(panel) }
    override fun toggleVoice() = guard { a.toggleVoice() }
    override fun openSettings() = guard { a.openSettings() }
    override fun openVoiceFallback() = guard { a.openVoiceFallback() }
    override fun hideKeyboard() = guard { a.hideKeyboard() }
    override fun clearClipboard() = guard { a.clearClipboard() }
    override fun insertTranslation() = guard { a.insertTranslation() }
    override fun commitSymbol(text: String) = guard { a.commitSymbol(text) }
    override fun runPluginAction(pluginId: String, index: Int) = guard { a.runPluginAction(pluginId, index) }
    override fun replaceLast(old: String, new: String) = guard { a.replaceLast(old, new) }
    override fun commitGlide(path: List<Offset>, keys: Map<Char, Offset>, keyWidth: Float, tapLetter: String?) =
        guard { a.commitGlide(path, keys, keyWidth, tapLetter) }
    override fun switchKeyboard() = guard { a.switchKeyboard() }
    override fun runScriptButton(pluginId: String, id: String) = guard { a.runScriptButton(pluginId, id) }
    override fun removeClip(text: String) = guard { a.removeClip(text) }
    override fun togglePinClip(text: String) = guard { a.togglePinClip(text) }
    override fun forgetSuggestion(word: String) = guard { a.forgetSuggestion(word) }
    override fun pickCandidate(index: Int) = guard { a.pickCandidate(index) }
    override fun commitClip(entry: String) = guard { a.commitClip(entry) }
    override fun pasteClipOffer() = guard { a.pasteClipOffer() }
    override fun dismissClipOffer() = guard { a.dismissClipOffer() }
    override fun globeHold() = guard { a.globeHold() }
    override fun editKey(id: String) = guard { a.editKey(id) }
    override fun aiFix() = guard { a.aiFix() }
    override fun aiTool(id: String) = guard { a.aiTool(id) }
    override fun spaceHold() = guard { a.spaceHold() }
    override fun backspaceSelect(words: Int) = guard { a.backspaceSelect(words) }
    override fun backspaceSelectDone(delete: Boolean) = guard { a.backspaceSelectDone(delete) }
    override fun rambler() = guard { a.rambler() }
    override fun scriptPromptDone(submit: Boolean) = guard { a.scriptPromptDone(submit) }
}

@Stable
class VoiceInput(
    private val context: Context,
    private val onResult: (String) -> Unit,
    /** Службы распознавания нет: попробовать системное окно распознавания. true — запущено. */
    private val onNoEngine: () -> Boolean = { false }
) {
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf(tr("Нажмите на микрофон и говорите"))
        private set
    var partial by mutableStateOf("")
        private set
    var rms by mutableFloatStateOf(0f)
        private set
    /** Встроенное распознавание не работает — показать кнопку другого голосового ввода. */
    var needsFallback by mutableStateOf(false)
        private set
    private var recognizer: SpeechRecognizer? = null
    /** Кто сейчас распознаёт: null — системный сервис по умолчанию, иначе явный компонент. */
    private var engine: ComponentName? = null
    private var onDevice = false
    /** Варианты сервисов для этого запуска: если один падает сразу, пробуем следующий. */
    private var options: List<Pair<ComponentName?, Boolean>> = emptyList()
    private var optionIndex = 0
    private var heardSomething = false
    private var lastLanguage: KeyboardLanguage? = null
    /** Идёт запуск/распознавание; после stop() поздние ошибки отмены не показываем. */
    private var active = false
    /** Rambler: длинные паузы не обрывают запись, после каждой фразы слушаем дальше, пока не нажмут на микрофон. */
    private var continuous = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            status = tr("Говорите…")
        }
        override fun onBeginningOfSpeech() {
            heardSomething = true
        }
        override fun onRmsChanged(rmsdB: Float) {
            rms = rmsdB
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            listening = false
            rms = 0f
            status = tr("Распознаю…")
        }
        override fun onError(error: Int) {
            listening = false
            rms = 0f
            if (!active) return
            // сервис не смог даже начать — пробуем следующий установленный
            val broken = error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER ||
                error == 11 /* ERROR_SERVER_DISCONNECTED */ || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ || error == 13 /* ERROR_LANGUAGE_UNAVAILABLE */
            if (broken && !heardSomething && optionIndex + 1 < options.size) {
                optionIndex++
                destroyRecognizer()
                lastLanguage?.let { launch(it) }
                return
            }
            active = false
            status = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr("Не расслышал. Нажмите на микрофон ещё раз")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr("Нет интернета для распознавания речи")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tr("Нужен доступ к микрофону")
                SpeechRecognizer.ERROR_AUDIO -> tr("Микрофон занят другим приложением")
                12, 13 -> tr("Этот язык не поддерживается распознаванием")
                else -> {
                    needsFallback = true
                    tr("Распознавание не сработало. Попробуйте голосовой ввод другой клавиатуры")
                }
            }
            if (broken) destroyRecognizer()
        }
        override fun onResults(results: Bundle?) {
            active = false
            listening = false
            rms = 0f
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            // вариант без «звёздочек» вместо слов — если сервис всё-таки прикрыл часть слов, а часть вариантов нет
            val best = (all.firstOrNull { '*' !in it } ?: all.firstOrNull())?.trim().orEmpty()
            if (best.isNotEmpty()) {
                partial = ""
                status = tr("Вставлено: {0}", best)
                onResult(best)
            } else status = tr("Речь не распознана")
            if (continuous) lastLanguage?.let { launch(it) }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (p.isNotEmpty()) partial = p
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start(language: KeyboardLanguage, rambler: Boolean = false) {
        continuous = rambler
        if (!hasPermission()) {
            status = tr("Нужен доступ к микрофону")
            return
        }
        needsFallback = false
        options = findEngines()
        optionIndex = 0
        if (options.isEmpty()) {
            if (onNoEngine()) return
            needsFallback = true
            status = tr("На устройстве нет службы распознавания речи. Нажмите «Другой голосовой ввод»")
            return
        }
        launch(language)
    }

    /**
     * Все способы распознавания по порядку. На многих телефонах (Xiaomi, Honor, без сервисов Google по умолчанию)
     * системный сервис не назначен и isRecognitionAvailable() возвращает false, хотя распознавание Google
     * установлено, — тогда обращаемся к нему напрямую.
     */
    private fun findEngines(): List<Pair<ComponentName?, Boolean>> {
        val out = ArrayList<Pair<ComponentName?, Boolean>>()
        if (runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)) out += null to false
        val services = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        services.sortedBy { s -> preferred.indexOf(s.serviceInfo.packageName).let { if (it < 0) 99 else it } }.forEach { s ->
            val c = ComponentName(s.serviceInfo.packageName, s.serviceInfo.name)
            if (out.none { it.first == c }) out += c to false
        }
        if (Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) {
            out += null to true
        }
        return out
    }

    private fun launch(language: KeyboardLanguage) {
        active = true
        lastLanguage = language
        heardSomething = false
        val (component, local) = options[optionIndex]
        val r = recognizer?.takeIf { engine == component && onDevice == local } ?: run {
            destroyRecognizer()
            val created = runCatching {
                when {
                    local && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    component != null -> SpeechRecognizer.createSpeechRecognizer(context, component)
                    else -> SpeechRecognizer.createSpeechRecognizer(context)
                }
            }.getOrNull()
            if (created == null) {
                if (optionIndex + 1 < options.size) {
                    optionIndex++
                    launch(language)
                } else {
                    needsFallback = true
                    status = tr("Не удалось запустить распознавание")
                }
                return
            }
            created.setRecognitionListener(listener)
            engine = component
            onDevice = local
            recognizer = created
            created
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            VoiceBridge.uncensored(this)
            if (continuous) {
                // подумать посреди фразы можно: запись не обрывается на первой же паузе
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L)
            }
        }
        partial = ""
        listening = true
        status = if (continuous) tr("Rambler: говорите свободно, ИИ уберёт лишнее") else tr("Слушаю ({0})…", language.label)
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            destroyRecognizer()
            if (optionIndex + 1 < options.size) {
                optionIndex++
                launch(language)
            } else {
                needsFallback = true
                status = tr("Не удалось запустить распознавание")
            }
        }
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        engine = null
        onDevice = false
    }

    fun stop() {
        active = false
        continuous = false
        runCatching { recognizer?.cancel() }
        listening = false
        rms = 0f
    }

    fun release() {
        destroyRecognizer()
        listening = false
    }
}

/** Сколько живёт предложение вставить только что скопированное (или только что сделанный скриншот). */
private const val CLIP_OFFER_MS = 5 * 60_000L
/** Сколько минимум показывается анимация улучшения текста. */
private const val MIN_AI_ANIM_MS = 700L

private val OFFER_BREAKERS = setOf(KeyType.CHARACTER, KeyType.SPACE, KeyType.BACKSPACE, KeyType.ENTER)

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '«' to '»')

internal data class FieldFlags(
    val isPassword: Boolean = false,
    val suggestions: Boolean = true,
    val autoCorrect: Boolean = true,
    val autoCap: Boolean = false
)

class ComposeKeyboardService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, KeyboardActions, ScriptBridge {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    // ошибка в фоновой задаче (подсказки, буфер, GIF) пишется в журнал, а не роняет клавиатуру
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("ExpressiveKeyboard", "background task failed", e) }
    )

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val kb = KeyboardState()
    /** Действия для интерфейса: сбой одного нажатия сбрасывает набор, а не роняет клавиатуру. */
    private val safeActions: KeyboardActions = SafeActions(this) { recoverInputState() }
    private lateinit var voice: VoiceInput
    private var clipboardManager: ClipboardManager? = null
    /** Время копирования последней записи буфера, которую уже видели. */
    private var lastClipStamp = -1L
    private var clipOfferAt = 0L
    /** Слово, на которое нажали: его варианты в строке подсказок, выбор заменяет слово целиком. */
    private data class TapWord(val start: Int, val end: Int, val word: String)
    private var tapWord: TapWord? = null
    private var inputView: View? = null
    private var composeView: ComposeView? = null
    private var navColor: Int? = null
    /** Размытие под окном клавиатуры (px) и дымка поверх него — «Жидкое стекло». */
    private var blurRadius = 0
    private var blurTint = AndroidColor.TRANSPARENT
    private var blurCornerDp = 28f
    private var navLight = false
    /** Что уже поставлено окну: фон и размытие меняем только при изменении — иначе окно перекладывается
     *  при каждом показе, и приложение под клавиатурой дёргается и прокручивается. */
    private var appliedBg: Any? = null
    private var appliedWindow: Window? = null
    private var appliedEco: Boolean? = null
    /** Фон окна, который мы поставили для размытия: если система или прошивка его подменили, ставим заново. */
    private var blurDrawable: android.graphics.drawable.Drawable? = null
    private val blurWatch = object : Runnable {
        override fun run() {
            val v = inputView ?: return
            if (!v.isAttachedToWindow) return
            if (isInputViewShown) {
                fitBlurToKeyboard()
                restoreBlurIfLost()
            }
            v.postDelayed(this, 1500L)
        }
    }

    /** Режим энергосбережения системы: анимации нажатий отключаются. */
    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = PowerState.update(this@ComposeKeyboardService)
    }

    // --- отслеживание текста (без синхронных IPC-запросов к полю ввода) ---
    private var composing = ""
    private var composingStart = -1
    private var expSelStart = -1
    private var expSelEnd = -1
    private var lastCommittedWord = ""
    private var lastTypedChar: Char? = null
    private var undo: UndoAutoCorrectState? = null
    private var autoSpace = false
    private var lastActionWasSpace = false
    private var charBeforeSpace: Char? = null
    private var charBeforeAutoSpace: Char? = null
    /** Пробел поставили сами после запятой/«!»/«?» — его убирает следующий «/», «@» или скобка. */
    private var punctSpace = false
    private var lastSpaceTime = 0L
    private var lastShiftTime = 0L
    private var lastDeleteAt = 0L
    private var flags = FieldFlags()
    private var predictionJob: Job? = null
    private var precomputed: String? = null
    private var precomputedOwn: String? = null
    private var precomputedFor = ""
    /** В текущем «слове» до курсора есть не-буквы (например «:date») — только тогда автозамене нужно читать поле. */
    private var tokenHasSymbol = true
    /** Shift включила сама клавиатура (начало предложения), а не пользователь. */
    private var autoShift = false
    /** Слово начато с заглавной вручную (имя, название) или после @ # / — его не исправляем. */
    private var noCorrectWord = false
    /** Последнее слово вставлено свайпом: следующая буква начнёт новое слово, ⌫ удалит его целиком. */
    private var glideWord = false
    private var glideJob: Job? = null
    /** Регистр до последней буквы — чтобы вернуть его, если касание превратилось в свайп. */
    private var shiftBeforeKey = ShiftState.OFF
    /** Нажатые клавиши текущего слова для корейского (слоги) и вьетнамского (Telex): composing = compose(raw). */
    private var rawComposing = ""
    /** Точка касания последней клавиши и касания по буквам текущего слова (для автоисправления). */
    private var pendingTouch: KeyTouch? = null
    private val wordTouches = ArrayList<KeyTouch?>()
    private var touchesFor = ""
    /** Варианты для текущего набора пиньинем/ромадзи и для какого набора они посчитаны. */
    private var cjk: List<CjkCandidate> = emptyList()
    private var cjkFor = ""
    /** Фраза, собираемая по частям из одного набора: запомнится целиком, когда набор кончится. */
    private var cjkPhraseRaw = ""
    private val cjkPhrase = StringBuilder()
    private var cjkPieces = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { syncClipboard() }

    private val settings: KeyboardSettings get() = KeyboardPrefs.current(this)

    /** Строка, в которую сейчас печатает клавиатура вместо поля: переводчик или поиск эмодзи. */
    private var panelInput: String?
        get() = when (kb.panel) {
            ActivePanel.TRANSLATE -> kb.translatorInput
            ActivePanel.EMOJI_SEARCH -> kb.emojiQuery
            ActivePanel.GIF_SEARCH -> kb.gifQuery
            ActivePanel.AI_PROMPT -> kb.aiPrompt
            ActivePanel.SCRIPT_INPUT -> ScriptHost.promptText
            else -> null
        }
        set(v) {
            when (kb.panel) {
                ActivePanel.TRANSLATE -> kb.translatorInput = v.orEmpty()
                ActivePanel.EMOJI_SEARCH -> kb.emojiQuery = v.orEmpty()
                ActivePanel.GIF_SEARCH -> kb.gifQuery = v.orEmpty()
                ActivePanel.AI_PROMPT -> kb.aiPrompt = v.orEmpty()
                ActivePanel.SCRIPT_INPUT -> ScriptHost.promptChanged(v.orEmpty())
                else -> Unit
            }
            // в переводчике — подсказки и исправления для набираемого слова, как в обычном поле
            if (kb.panel == ActivePanel.TRANSLATE) schedulePanelPredictions()
        }

    /** Исправление последнего слова в переводчике: (слово → исправление), считается заранее. */
    private var panelCorrection: Pair<String, String>? = null
    /** Пробел в переводчике исправил слово: (текст после, текст до) — ⌫ возвращает как было. */
    private var panelUndo: Pair<String, String>? = null

    private val panelWordRe = Regex("[\\p{L}\\p{M}'’-]+$")

    private fun schedulePanelPredictions() {
        predictionJob?.cancel()
        if (!settings.showSuggestions) return
        val text = kb.translatorInput
        val word = panelWordRe.find(text)?.value.orEmpty()
        val prev = Regex("([\\p{L}\\p{M}'’-]+)\\s+$").find(text.dropLast(word.length))?.groupValues?.get(1).orEmpty()
        val lang = Languages.withUser(kb.language)
        val s = settings
        predictionJob = serviceScope.launch {
            val snap = withContext(Dispatchers.Default) {
                val me = coroutineContext[Job]
                WordPredictorEngine.predict(word, prev, null, lang, false, s.autoCorrectStrength, s.autoCorrect, null, null) { me?.isActive == false }
            } ?: return@launch
            if (kb.translatorInput != text || kb.panel != ActivePanel.TRANSLATE) return@launch
            kb.suggestions = snap.chips
            panelCorrection = snap.autoCorrection?.takeIf { word.isNotEmpty() && it != word && s.autoCorrect }?.let { word to it }
        }
    }

    /** Пробел в переводчике: слово с ошибкой исправляется, ⌫ сразу после — отменяет. */
    private fun panelSpace(t: String) {
        val word = panelWordRe.find(t)?.value.orEmpty()
        val fix = panelCorrection?.takeIf { it.first == word && word.isNotEmpty() }?.second
        if (kb.panel == ActivePanel.TRANSLATE && fix != null) {
            val next = t.dropLast(word.length) + fix + " "
            panelUndo = next to "$t "
            panelInput = next
        } else panelInput = "$t "
    }

    private fun dataPrefs() = getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        KeyboardPrefs.ensureInit(this)
        WordPredictorEngine.ensureInitialized(this)
        EmojiCatalog.warmUp(this)
        EmojiRecents.load(this)
        IconStore.ensureLoaded(this)
        ScriptHost.bridge = this
        PluginRepository.ensureLoaded(this)
        // язык, на котором закончили в прошлый раз
        val enabled = Languages.enabled(settings)
        kb.language = dataPrefs().getString("last_language", null)?.let { id -> enabled.firstOrNull { it.id == id } } ?: enabled.first()
        WordPredictorEngine.onDictionaryLoaded = { if (composing.isEmpty() || precomputedFor != composing) schedulePredictions() }
        // ответ системной проверки пришёл, пока слово ещё набирается, — обновить подсказки
        SystemSpell.onResult = { w -> if (composing == w || (composing.isEmpty() && tapWord?.word == w)) schedulePredictions() }
        WordPredictorEngine.prepare(this, kb.language)
        Cjk.prepare(this, kb.language)
        runCatching {
            val arr = JSONArray(dataPrefs().getString("pinned_clips", "[]"))
            for (i in 0 until arr.length()) kb.pinnedClips += arr.getString(i)
        }
        // история буфера переживает закрытие клавиатуры системой
        if (settings.clipboardHistory && !settings.incognito) kb.clipboard += ClipStore.load(this, settings).take(settings.clipboardSize)
        ClipImages.cleanup(this, kb.pinnedClips + kb.clipboard)
        voice = VoiceInput(this, onResult = ::commitVoice, onNoEngine = ::launchVoiceWindow)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        syncClipboard()
        PowerState.update(this)
        runCatching { registerReceiver(powerReceiver, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) }
        Subtypes.register(this, enabled)
        // события модов о смене панели и Shift — из состояния, где бы его ни поменяли
        serviceScope.launch {
            var lastPanel = kb.panel
            androidx.compose.runtime.snapshotFlow { kb.panel }.collect { p ->
                if (lastPanel == ActivePanel.SCRIPT_INPUT && p != ActivePanel.SCRIPT_INPUT) ScriptHost.promptClosed()
                lastPanel = p
                if (ScriptHost.has("panel")) ScriptHost.emit("panel", p.name.lowercase())
            }
        }
        serviceScope.launch {
            androidx.compose.runtime.snapshotFlow { kb.shift }.collect { s ->
                if (ScriptHost.has("shift")) ScriptHost.emit("shift", scriptShift())
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ScriptHost.has("device")) ScriptHost.emit("device", ScriptHost.device())
    }

    /** Новый скриншот: в историю буфера и, если он свежий, — предложение вставить над клавиатурой. */
    private fun onScreenshot(uri: Uri, mime: String, at: Long) {
        val s = settings
        if (!s.clipboardHistory || !s.clipScreenshots || s.incognito) return
        serviceScope.launch {
            val entry = ClipImages.save(this@ComposeKeyboardService, uri, mime, at) ?: return@launch
            if (kb.clipboard.contains(entry) || kb.pinnedClips.contains(entry)) return@launch
            addClip(entry, at)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        // Android пересоздаёт окно клавиатуры (поворот, смена темы): старая разметка живёт до уничтожения сервиса,
        // и её клавиши путаются с новыми — освобождаем её явно
        composeView?.disposeComposition()
        window?.window?.decorView?.let { d ->
            d.setViewTreeLifecycleOwner(this)
            d.setViewTreeViewModelStoreOwner(this)
            d.setViewTreeSavedStateRegistryOwner(this)
        }
        val view = ComposeView(this).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@ComposeKeyboardService)
            setViewTreeViewModelStoreOwner(this@ComposeKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@ComposeKeyboardService)
            setContent {
                KeyboardRoot(kb, safeActions, voice, onBackground = { bg, transparent ->
                    navColor = if (transparent) AndroidColor.TRANSPARENT else bg.toArgb()
                    navLight = bg.luminance() > 0.5f
                    applyWindowColors()
                }, onWindowBlur = { radius, tint, corner ->
                    blurRadius = radius
                    blurTint = tint.toArgb()
                    blurCornerDp = corner
                    applyWindowColors()
                })
            }
        }
        composeView = view
        // под клавиатурой — невидимая поверхность в 1 px (см. BlurGuard), сама клавиатура поверх
        val root = android.widget.FrameLayout(this).apply {
            if (Build.VERSION.SDK_INT >= 31) {
                addView(BlurGuard(this@ComposeKeyboardService), android.widget.FrameLayout.LayoutParams(1, 1, android.view.Gravity.BOTTOM or android.view.Gravity.START))
            }
            addView(view, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        inputView = root
        applyWindowColors()
        return root
    }

    private fun moveLifecycle(target: Lifecycle.State) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = target
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // приложение снова в фокусе после окна распознавания — вставить сказанное (чуть позже: поле должно ожить)
        if (VoiceBridge.hasPending) serviceScope.launch {
            delay(150)
            commitPendingVoice()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        moveLifecycle(Lifecycle.State.RESUMED)
        // окно только появляется: панели и строка подсказок встают на место сразу, без анимаций
        if (!isInputViewShown) kb.instantUi = true
        WordPredictorEngine.loadPersonalDictionary(this)
        if (PrivateData.wipeClipboard) {
            PrivateData.wipeClipboard = false
            kb.clipboard.clear()
            kb.pinnedClips.clear()
            kb.clipOffer = null
            ClipStore.clear(this)
        }
        resetForInput(info, restarting)
        applyWindowColors()
        // подтип ввода (язык проверки правописания в приложении) — под текущий язык клавиатуры
        val enabled = Languages.enabled(settings)
        // после показа окна: переключение подтипа в самом начале ввода сбивало бы приложение
        serviceScope.launch(Dispatchers.Main) {
            Subtypes.register(this@ComposeKeyboardService, enabled)
            Subtypes.switchTo(this@ComposeKeyboardService, kb.language, verify = !restarting)
        }
        ClipStore.scanScreenshots(this, settings, ::onScreenshot)
    }

    // ------------------------------------------------------------------ автозаполнение (Android 11+)

    /** Просим у службы автозаполнения (Google Пароли и др.) подсказки для поля — как Gboard. */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? = runCatching {
        val d = resources.displayMetrics.density
        val p = KeyboardGeometry.palette
        val chipColor = (p?.func ?: androidx.compose.ui.graphics.Color(0xFF3A3F4B)).toArgb()
        val textColor = (p?.onFunc ?: androidx.compose.ui.graphics.Color.White).toArgb()
        val chip = androidx.autofill.inline.common.ViewStyle.Builder()
            .setBackground(android.graphics.drawable.Icon.createWithResource(this, R.drawable.inline_chip).setTint(chipColor))
            .setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            .build()
        val style = androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(chip)
            .setChipStyle(chip)
            .setTitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(textColor).setTextSize(14f).build())
            .setSubtitleStyle(
                androidx.autofill.inline.common.TextViewStyle.Builder()
                    .setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 170)).setTextSize(12f).build()
            )
            .setStartIconStyle(androidx.autofill.inline.common.ImageViewStyle.Builder().setLayoutMargin(0, 0, (6 * d).toInt(), 0).build())
            .build()
        val styles = androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = android.widget.inline.InlinePresentationSpec.Builder(
            // не шире места между кнопками меню и микрофона: широкая подсказка там бы не поместилась
            android.util.Size((64 * d).toInt(), (34 * d).toInt()),
            android.util.Size((200 * d).toInt(), (38 * d).toInt())
        ).setStyle(styles).build()
        InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(6).build()
    }.getOrNull()

    /** Подсказки пришли: раздуваем их в View и показываем в строке подсказок. */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val list = response.inlineSuggestions
        if (list.isEmpty()) {
            kb.inlineSuggestions = emptyList()
            return true
        }
        val views = arrayOfNulls<View>(list.size)
        var left = list.size
        val size = android.util.Size(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        list.forEachIndexed { i, s ->
            runCatching {
                s.inflate(this, size, mainExecutor) { v ->
                    views[i] = v
                    if (--left == 0) kb.inlineSuggestions = views.filterNotNull()
                }
            }.onFailure { if (--left == 0) kb.inlineSuggestions = views.filterNotNull() }
        }
        return true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        kb.inlineSuggestions = emptyList()
        composing = ""
        composingStart = -1
        rawComposing = ""
        undo = null
        autoSpace = false
        if (kb.panel == ActivePanel.VOICE) voice.stop()
        WordPredictorEngine.flush()
        ScriptHost.emit("finish")
    }

    override fun onWindowShown() {
        super.onWindowShown()
        moveLifecycle(Lifecycle.State.RESUMED)
        syncClipboard()
        if (kb.clipOffer != null && System.currentTimeMillis() - clipOfferAt > CLIP_OFFER_MS) kb.clipOffer = null
        expireClips()
        applyWindowColors()
        // высоту окна сравниваем с клавиатурой один раз, когда окно уже показалось: раньше это делалось при каждой
        // перекладке, и посреди анимации ответ на миг менялся — размытие выключалось и включалось, вся клавиатура
        // перерисовывалась и «прыгала»
        inputView?.postDelayed({ fitBlurToKeyboard() }, 450L)
        // проверка раз в полторы секунды, пока клавиатура на экране: размытие и подложка не должны пропадать
        // «в случайный момент», а высота окна могла измениться уже после первой проверки
        inputView?.let { it.removeCallbacks(blurWatch); it.postDelayed(blurWatch, 1500L) }
        ScriptHost.setVisible(true)
        ScriptHost.emit("show")
        if (kb.instantUi) serviceScope.launch {
            delay(400L)
            kb.instantUi = false
        }
    }

    /** Записи старше срока из настройки «Хранить» уходят из истории (закреплённые остаются). */
    private fun expireClips() {
        val old = ClipStore.expired(kb.clipboard, settings).filter { it !in kb.pinnedClips }
        if (old.isEmpty()) return
        kb.clipboard.removeAll(old.toSet())
        old.forEach {
            ClipStore.forget(it)
            if (it != kb.clipOffer) ClipImages.delete(it)
        }
        ClipStore.save(this, kb.clipboard)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        inputView?.removeCallbacks(blurWatch)
        voice.stop()
        kb.panel = ActivePanel.KEYBOARD
        kb.toolbarOpen = false
        WordPredictorEngine.flush()
        moveLifecycle(Lifecycle.State.CREATED)
        ScriptHost.emit("hide")
        ScriptHost.setVisible(false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !isInputViewShown) WordPredictorEngine.trimMemory()
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        applyWindowColors()
    }

    override fun onDestroy() {
        if (ScriptHost.bridge === this) ScriptHost.bridge = null
        WordPredictorEngine.onDictionaryLoaded = null
        SystemSpell.onResult = null
        SystemSpell.close()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        ClipStore.stop(this)
        runCatching { unregisterReceiver(powerReceiver) }
        predictionJob?.cancel()
        voice.release()
        WordPredictorEngine.flush()
        serviceScope.cancel()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    private fun applyWindowColors() {
        val w = window?.window ?: return
        runCatching {
            val blur = Build.VERSION.SDK_INT >= 31 && blurRadius > 0
            val bg = if (blur) Triple(blurRadius, blurTint, blurCornerDp) else 0
            if (w !== appliedWindow) {
                // один раз на окно: повторный вызов заново раздаёт отступы окна, и клавиатура на кадр сдвигалась
                WindowCompat.setDecorFitsSystemWindows(w, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) w.isNavigationBarContrastEnforced = false
                // в альбомной ориентации окно заходит и под вырез камеры — по бокам не видно приложения под клавиатурой
                if (Build.VERSION.SDK_INT >= 30) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (bg != appliedBg || w !== appliedWindow) {
                appliedWindow = w
                appliedBg = bg
                if (blur) {
                    // размытие повторяет форму фона окна: скруглённый сверху прямоугольник с дымкой
                    val r = blurCornerDp * resources.displayMetrics.density
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor(blurTint)
                        // одинаковый радиус у всех углов: только тогда контур фона — скруглённый прямоугольник,
                        // и система скругляет по нему размытие (иначе оно торчит за углами клавиатуры)
                        cornerRadius = r
                    }
                    blurDrawable = shape
                    w.setBackgroundDrawable(shape)
                    w.setBackgroundBlurRadius(blurRadius)
                } else {
                    blurDrawable = null
                    w.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                    if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(0)
                }
            }
            // частоту экрана клавиатура не трогает: ограничение до 60 Гц переводило в 60 Гц весь экран
            // (на телефонах 90/120 Гц всё начинало «лагать»), а переключение частоты давало мелькание
            if (appliedEco != false) {
                appliedEco = false
                if (w.attributes.preferredRefreshRate != 0f) w.attributes = w.attributes.apply { preferredRefreshRate = 0f }
            }
            navColor?.let {
                @Suppress("DEPRECATION")
                if (w.navigationBarColor != it) w.navigationBarColor = it
            }
            val insets = WindowInsetsControllerCompat(w, w.decorView)
            if (insets.isAppearanceLightNavigationBars != navLight) insets.isAppearanceLightNavigationBars = navLight
        }
    }

    /**
     * Размытие делает система по границам окна. На части прошивок окно клавиатуры выше самой клавиатуры —
     * тогда размытие легло бы на весь экран; в этом случае отключаем его и рисуем обычный фон темы.
     */
    private fun restoreBlurIfLost() {
        if (Build.VERSION.SDK_INT < 31 || blurRadius <= 0) return
        val w = window?.window ?: return
        // фон окна подменили (смена окна, конфигурации, прошивка) — размытие осталось бы без подложки и дымки:
        // клавиатура выглядела бы просто прозрачной. Ставим всё заново.
        if (w.decorView.background !== blurDrawable) {
            appliedBg = null
            applyWindowColors()
        }
    }

    private fun fitBlurToKeyboard() {
        if (Build.VERSION.SDK_INT < 31) return
        val decor = window?.window?.decorView ?: return
        val v = inputView ?: return
        if (!v.isAttachedToWindow || v.height == 0 || decor.height == 0 || !isInputViewShown) return
        val d = resources.displayMetrics.density
        // снизу окно может быть выше клавиатуры на полосу навигации, которую с Android 13 рисует сама система
        // в окне клавиатуры: размытие под ней только продолжает фон вниз. Раньше из-за этого размытие
        // выключалось у многих (жестовая навигация, Android 13+). Выключаем, только если окно намного выше.
        val overflow = decor.height - v.height > (96 * d).toInt() || decor.width - v.width > (4 * d).toInt()
        if (overflow != WindowBlur.overflow) WindowBlur.overflow = overflow
    }

    // ------------------------------------------------------------------ поле ввода

    private fun computeFlags(info: EditorInfo?): FieldFlags {
        if (info == null) return FieldFlags()
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val text = cls == InputType.TYPE_CLASS_TEXT
        val password = (text && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val addressLike = text && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI)
        val noSuggest = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val capFlags = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val suggestions = text && !password && !addressLike
        return FieldFlags(
            isPassword = password,
            suggestions = suggestions,
            // многие приложения ставят «без подсказок» в обычные поля сообщений — из-за этого исправления
            // работали лишь в части приложений; по настройке исправляем везде, кроме паролей, почты и адресов
            autoCorrect = suggestions && (!noSuggest || settings.correctEverywhere),
            autoCap = text && !password && !addressLike && (type and capFlags) != 0
        )
    }

    private fun resetForInput(info: EditorInfo?, restarting: Boolean) {
        flags = computeFlags(info)
        // в поле пароля и в инкогнито моды не видят ни набираемого текста, ни буфера
        ScriptHost.privateMode = flags.isPassword || settings.incognito
        kb.glideAllowed = flags.suggestions
        glideWord = false
        tokenHasSymbol = true
        composing = ""
        composingStart = -1
        rawComposing = ""
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        lastTypedChar = null
        precomputed = null
        // язык могли выключить в настройках
        val enabled = Languages.enabled(settings)
        if (enabled.none { it == kb.language }) kb.language = enabled.first()
        expSelStart = info?.initialSelStart ?: -1
        expSelEnd = info?.initialSelEnd ?: -1
        val imeOptions = info?.imeOptions ?: 0
        kb.enterAction = if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) ENTER_NEWLINE
        else imeOptions and EditorInfo.IME_MASK_ACTION
        if (!restarting) {
            kb.panel = ActivePanel.KEYBOARD
            kb.toolbarOpen = false
            kb.toolsDismissed = false
            kb.translatorInput = ""
        }
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        kb.symbolsPage = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) 1 else 0
        tapWord = null
        kb.selectMode = false
        kb.shift = if (autoCapAllowed() && startsSentence(info)) ShiftState.SINGLE else ShiftState.OFF
        autoShift = kb.shift == ShiftState.SINGLE
        noCorrectWord = false
        schedulePredictions()
        if (ScriptHost.has("start")) ScriptHost.emit("start", scriptField())
    }

    /**
     * Курсор стоит в начале предложения. Смотрим на сам текст: многие приложения отдают initialCapsMode
     * и initialSelStart как попало, и после скрытия клавиатуры посреди фразы она открывалась с заглавной.
     */
    private fun startsSentence(info: EditorInfo?): Boolean {
        val before = runCatching { currentInputConnection?.getTextBeforeCursor(64, 0) }.getOrNull()
            ?: return (info?.initialCapsMode ?: 0) != 0 || (info?.initialSelStart ?: -1) == 0
        return capsAfter(before)
    }

    private fun capsAfter(before: CharSequence): Boolean {
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return true
        if (before.lastOrNull() == '\n') return true
        // заглавная — только после знака конца предложения и пробела
        return trimmed.length < before.length && trimmed.last() in ".!?…"
    }

    /** У письменностей без заглавных Shift включает второй ряд букв — его нельзя включать автоматически. */
    private fun autoCapAllowed() = flags.autoCap && settings.autoCapitalize && kb.language.hasCase

    private fun requestAutoCap() {
        if (autoCapAllowed() && kb.shift == ShiftState.OFF) {
            kb.shift = ShiftState.SINGLE
            autoShift = true
        }
    }

    /** Поле в нашем же приложении: обработка сдвигов курсора отложена до конца текущей правки. */
    private val selectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var deferredSelection = false

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        // поле в этом же приложении (проверка клавиатуры в настройках): система зовёт onUpdateSelection прямо изнутри
        // setComposingText — раньше, чем клавиатура запомнила, что набрала. Сдвиг курсора выглядел чужим, слово
        // «отпускалось», и следующая буква вставляла его заново: «ПрПривПривет». Разбираем такие сдвиги после правки
        if (!deferredSelection && currentInputEditorInfo?.packageName == packageName) {
            selectionHandler.post {
                deferredSelection = true
                try {
                    onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
                } finally {
                    deferredSelection = false
                }
            }
            return
        }
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (ScriptHost.has("selection")) ScriptHost.emit("selection", mapOf("start" to newSelStart, "end" to newSelEnd))
        // в поле появился текст — панель функций уступает подсказкам сразу, без ожидания предсказаний
        if (kb.fieldEmpty && (newSelStart > 0 || newSelEnd > 0 || candidatesEnd > 0)) kb.fieldEmpty = false
        if (expSelStart < 0 || expSelEnd < 0) {
            if (composing.isNotEmpty()) {
                composingStart = if (candidatesStart >= 0) candidatesStart else newSelStart
                expect(composingStart + composing.length)
            } else {
                expSelStart = newSelStart
                expSelEnd = newSelEnd
            }
            return
        }
        if (isBelatedUpdate(oldSelStart, newSelStart, oldSelEnd, newSelEnd)) return
        // приложение сдвинуло текст по-своему (что-то вставило перед словом, пересчитало позиции), но набираемое
        // слово цело и курсор сразу за ним — это всё ещё наш набор. Раньше слово тут обрывалось: подсказки и
        // исправления в таких приложениях почти не работали
        if (composing.isNotEmpty() && candidatesStart >= 0 && candidatesEnd - candidatesStart == composing.length &&
            newSelStart == newSelEnd && newSelStart == candidatesEnd
        ) {
            composingStart = candidatesStart
            expect(candidatesEnd)
            return
        }
        // курсор переместил пользователь или само приложение
        tokenHasSymbol = true
        expSelStart = newSelStart
        expSelEnd = newSelEnd
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing = ""
            composingStart = -1
        }
        lastCommittedWord = ""
        lastTypedChar = null
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        glideWord = false
        tapWord = null
        if (newSelStart == newSelEnd && newSelStart >= 0) inspectCursor(newSelStart)
        schedulePredictions()
    }

    /** Курсор переставили: Shift по месту в предложении и слово под курсором для исправления. */
    private fun inspectCursor(pos: Int) {
        val ic = currentInputConnection ?: return
        val before = runCatching { ic.getTextBeforeCursor(48, 0) }.getOrNull() ?: return
        if (autoCapAllowed() && kb.shift != ShiftState.CAPS_LOCK) {
            val cap = capsAfter(before)
            if (cap != (kb.shift == ShiftState.SINGLE)) {
                kb.shift = if (cap) ShiftState.SINGLE else ShiftState.OFF
                autoShift = cap
            }
        }
        if (!flags.suggestions || flags.isPassword || kb.language.composer != Composer.NONE) return
        val after = runCatching { ic.getTextAfterCursor(48, 0) }.getOrNull() ?: return
        var l = before.length
        while (l > 0 && (isWordLetter(before[l - 1]) || before[l - 1] in "'’")) l--
        var r = 0
        while (r < after.length && (isWordLetter(after[r]) || after[r] in "'’")) r++
        // слово должно уместиться в прочитанный кусок целиком
        if ((l == 0 && before.length == 48) || r == 48) return
        val word = (before.substring(l) + after.substring(0, r)).trim('\'', '’')
        if (word.length < 2 || word.any { it.isDigit() }) return
        val left = before.substring(l).takeWhile { it in "'’" }.length
        val start = pos - (before.length - l) + left
        tapWord = TapWord(start, start + word.length, word)
    }

    private fun isBelatedUpdate(oldStart: Int, newStart: Int, oldEnd: Int, newEnd: Int): Boolean {
        if (expSelStart == newStart && expSelEnd == newEnd) return true
        if (expSelStart == oldStart && expSelEnd == oldEnd && (oldStart != newStart || oldEnd != newEnd)) return false
        return newStart == newEnd &&
            (newStart - oldStart).toLong() * (expSelStart - newStart) >= 0 &&
            (newEnd - oldEnd).toLong() * (expSelEnd - newEnd) >= 0
    }

    private fun expect(pos: Int) {
        expSelStart = pos
        expSelEnd = pos
    }

    private fun expectUnknown() {
        expSelStart = -1
        expSelEnd = -1
    }

    private fun selectionStart(): Int = if (expSelStart < 0) -1 else min(expSelStart, expSelEnd)

    private fun icSetComposing(ic: InputConnection, word: String) {
        tapWord = null
        if (composing.isEmpty() || composingStart < 0) composingStart = selectionStart()
        ic.setComposingText(word, 1)
        composing = word
        lastTypedChar = word.lastOrNull()
        if (composingStart >= 0) expect(composingStart + word.length) else expectUnknown()
    }

    private fun icCommit(ic: InputConnection, text: String) {
        tapWord = null
        val base = if (composing.isNotEmpty()) composingStart else selectionStart()
        ic.commitText(text, 1)
        composing = ""
        composingStart = -1
        if (text.isNotEmpty()) lastTypedChar = text.last()
        if (base >= 0) expect(base + text.length) else expectUnknown()
    }

    private fun icDeleteBefore(ic: InputConnection, count: Int) {
        tapWord = null
        ic.deleteSurroundingText(count, 0)
        if (expSelStart >= 0 && expSelStart == expSelEnd) expect(max(0, expSelStart - count)) else expectUnknown()
        lastTypedChar = null
    }

    private fun resolveCorrection(word: String): String? {
        if (!settings.autoCorrect || !flags.autoCorrect) return null
        if (word == "i" && kb.language.id == "en") return "I"
        if (noCorrectWord) return null
        val lang = Languages.withUser(kb.language)
        val strength = settings.autoCorrectStrength
        val own = if (precomputedFor == word) precomputedOwn else runCatching {
            WordPredictorEngine.autoCorrectionFor(word, lang, strength, lastCommittedWord, touchesOf(word))
        }.getOrNull()
        // ответ системной проверки обычно уже пришёл; ждём его (недолго) только если собираемся менять слово —
        // иначе каждый пробел при быстром наборе подвешивал клавиатуру
        val sys = if (!systemSpellAllowed()) null
        else SystemSpell.cached(lang, word) ?: if (own != null) SystemSpell.await(lang, word, 8L) else null
        var c = if (systemSpellAllowed()) WordPredictorEngine.mergeSystem(word, own, sys, lang, strength) else own
        // мод может отменить исправление (false) или предложить своё (строка)
        if (ScriptHost.has("autocorrect") && !flags.isPassword) {
            for (r in ScriptHost.emit("autocorrect", mapOf("word" to word, "correction" to c))) {
                if (r == false) return null
                if (r is String && r.isNotEmpty()) c = r
            }
        }
        return if (c != null && c != word) c else null
    }

    /** Системная проверка правописания: не для паролей, инкогнито и письменностей без пробелов или со сборкой слогов. */
    private fun systemSpellAllowed(): Boolean {
        val s = settings
        return s.systemSpellcheck && !s.incognito && flags.suggestions && !flags.isPassword &&
            kb.language.autoCorrect && kb.language.composer == Composer.NONE
    }

    /** Касания по буквам слова — только если они точно относятся к нему (слово набрано по буквам). */
    private fun touchesOf(word: String): List<KeyTouch?>? =
        if (word.isNotEmpty() && touchesFor == word && wordTouches.size == word.length) wordTouches.toList() else null

    private fun trackTouch(before: String, after: String, touch: KeyTouch?) {
        if (touchesFor != before || wordTouches.size != before.length) {
            wordTouches.clear()
            repeat(before.length) { wordTouches.add(null) }
        }
        repeat(after.length - before.length) { wordTouches.add(touch) }
        touchesFor = after
    }

    private fun trimTouches(before: String, after: String) {
        if (touchesFor != before) return
        while (wordTouches.size > after.length) wordTouches.removeAt(wordTouches.lastIndex)
        touchesFor = after
    }

    private fun schedulePredictions() {
        if (kb.panel == ActivePanel.TRANSLATE) {
            schedulePanelPredictions()
            return
        }
        // в поле мода подсказки для текста приложения были бы не к месту
        if (kb.panel == ActivePanel.SCRIPT_INPUT) {
            predictionJob?.cancel()
            if (scriptPickRow == null || kb.suggestions !== scriptPickRow) kb.suggestions = SuggestionChipModel.EMPTY_ROW
            return
        }
        predictionJob?.cancel()
        val comp = kb.language.composer
        if (Cjk.isCjk(comp) && flags.suggestions && composing.isNotEmpty() && rawComposing.isNotEmpty()) {
            val raw = rawComposing
            val lang = Languages.withUser(kb.language)
            kb.suggestions = SuggestionChipModel.EMPTY_ROW
            predictionJob = serviceScope.launch {
                val list = withContext(Dispatchers.Default) { Cjk.candidates(lang, raw) }
                cjk = list
                cjkFor = raw
                kb.candidates = list.map { it.text }
            }
            return
        }
        if (kb.candidates.isNotEmpty()) kb.candidates = emptyList()
        updateZones()
        if (!flags.suggestions) {
            kb.suggestions = SuggestionChipModel.EMPTY_ROW
            precomputed = null
            precomputedOwn = null
            precomputedFor = ""
            return
        }
        val tw = tapWord
        updateFieldEmpty()
        if (tw != null && composing.isEmpty()) {
            scheduleTapWord(tw)
            return
        }
        val word = composing
        val prev = lastCommittedWord
        val u = undo
        val lang = Languages.withUser(kb.language)
        val capNext = kb.shift != ShiftState.OFF && lang.hasCase
        val s = settings
        val correct = s.autoCorrect && flags.autoCorrect && !(noCorrectWord && word.isNotEmpty())
        val touches = touchesOf(word)
        val shortcut = if (word.isNotEmpty()) PluginRepository.shortcuts[word.lowercase()] else null
        val personalShortcut = if (word.isNotEmpty() && shortcut == null) WordPredictorEngine.personalShortcuts[word.lowercase()] else null
        val sys = if (word.isNotEmpty() && systemSpellAllowed()) {
            // в чужой процесс — только когда пальцы остановились; к пробелу запрос уходит сразу (flush)
            SystemSpell.request(this, lang, word, debounceMs = 90L)
            SystemSpell.cached(lang, word)
        } else null
        // подсказки модов (событие "suggest") встают по бокам
        val extra = if (ScriptHost.has("suggest")) ScriptHost.suggestions(word, prev) else emptyList()
        predictionJob = serviceScope.launch {
            val snap = withContext(Dispatchers.Default) {
                val me = coroutineContext[Job]
                WordPredictorEngine.predict(word, prev, u, lang, capNext, s.autoCorrectStrength, correct, touches, sys) { me?.isActive == false }
            } ?: return@launch
            val chips = snap.chips.toMutableList()
            if (extra.isNotEmpty()) {
                chips[0] = SuggestionChipModel(extra[0], extra[0], isKeepExact = true)
                extra.getOrNull(1)?.let { chips[2] = SuggestionChipModel(it, it, isKeepExact = true) }
            }
            if (shortcut != null) {
                // сокращение плагина показываем главной подсказкой
                val expanded = PluginTemplates.expand(shortcut, clipboard = firstTextClip())
                chips[1] = SuggestionChipModel("→ " + expanded.replace('\n', ' ').take(24), expanded, isPrimary = true, isKeepExact = true)
            } else if (personalShortcut != null) {
                // сокращение из личного словаря Android
                chips[1] = SuggestionChipModel("→ " + personalShortcut.take(24), personalShortcut, isPrimary = true, isKeepExact = true)
            }
            kb.suggestions = chips
            precomputed = snap.autoCorrection
            precomputedOwn = snap.ownCorrection
            precomputedFor = snap.forWord
        }
    }

    /** Динамические зоны клавиш — по набираемому слову; в паролях, почте и без словаря их нет. */
    private fun updateZones() {
        val lang = kb.language
        val ok = settings.dynamicZones && flags.suggestions && !flags.isPassword && lang.composer == Composer.NONE &&
            kb.symbolsPage == 0 && panelInput == null && (composing.isEmpty() || composing.all { isWordLetter(it) })
        if (!ok || !WordPredictorEngine.hasDictionary(Languages.withUser(lang))) DynamicZones.disable()
        else DynamicZones.update(Languages.withUser(lang), composing)
    }

    /** Пусто ли поле: запрос к полю — только когда курсор в самом начале или неизвестен. */
    private fun updateFieldEmpty() {
        val empty = composing.isEmpty() && rawComposing.isEmpty() && !flags.isPassword && run {
            val pos = selectionStart()
            if (pos > 0) return@run false
            // позиция неизвестна — не спрашиваем поле на каждой букве, оставляем как было
            if (pos < 0) return@run kb.fieldEmpty
            val ic = currentInputConnection ?: return@run false
            runCatching {
                ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()
            }.getOrDefault(false)
        }
        if (kb.fieldEmpty != empty) kb.fieldEmpty = empty
        if (!empty && kb.toolsDismissed) kb.toolsDismissed = false
    }

    /** Варианты для слова, на которое нажали: только если оно с ошибкой или незнакомо. */
    private fun scheduleTapWord(tw: TapWord) {
        val lang = Languages.withUser(kb.language)
        val s = settings
        val sys = if (systemSpellAllowed()) {
            SystemSpell.request(this, lang, tw.word)
            SystemSpell.cached(lang, tw.word)
        } else null
        // строку подсказок не очищаем заранее: пустая строка на миг, а потом варианты — мелькание
        predictionJob = serviceScope.launch {
            val snap = withContext(Dispatchers.Default) {
                val me = coroutineContext[Job]
                WordPredictorEngine.predict(tw.word, "", null, lang, false, s.autoCorrectStrength, true, null, sys) { me?.isActive == false }
            } ?: return@launch
            if (tapWord != tw) return@launch
            val useful = !(snap.known && snap.autoCorrection == null) && snap.chips.any { !it.isEmpty && it.commitWord != tw.word }
            kb.suggestions = if (useful) snap.chips else SuggestionChipModel.EMPTY_ROW
        }
    }

    /** Заменить слово под курсором выбранным вариантом. */
    private fun replaceTapWord(ic: InputConnection, tw: TapWord, chip: SuggestionChipModel) {
        val word = chip.commitWord
        if (chip.isKeepExact && word == tw.word) {
            WordPredictorEngine.rejectCorrection(kb.language, word)
            learn("", word, WordPredictorEngine.LEARNED_TYPO)
        } else {
            ic.beginBatchEdit()
            ic.setComposingRegion(tw.start, tw.end)
            ic.commitText(word, 1)
            ic.endBatchEdit()
            expect(tw.start + word.length)
            learn("", word)
        }
        tapWord = null
        // дальше — подсказки следующего слова, а не пустая строка до первой буквы
        lastCommittedWord = word
        schedulePredictions()
    }

    private fun learn(previous: String, word: String, boost: Int = 1) {
        // латиница пиньиня/ромадзи — не слова языка
        if (Cjk.isCjk(kb.language.composer)) return
        if (!flags.isPassword && flags.suggestions && !settings.incognito) WordPredictorEngine.learn(kb.language, previous, word, boost)
        if (ScriptHost.has("word") && !flags.isPassword && !settings.incognito) ScriptHost.emit("word", mapOf("word" to word, "previous" to previous))
    }

    // ------------------------------------------------------------------ корейский и Telex

    private fun composeRaw(c: Composer, raw: String): String = when (c) {
        Composer.HANGUL -> Hangul.compose(raw)
        Composer.TELEX -> Telex.compose(raw)
        Composer.KANA -> Kana.compose(raw)
        Composer.PINYIN, Composer.NONE -> raw
    }

    /** Буква или знак огласовки (хинди, тайский) — продолжают слово. */
    private fun isWordLetter(ch: Char): Boolean {
        if (ch.isLetter()) return true
        val t = Character.getType(ch)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    // ------------------------------------------------------------------ набор текста

    private fun typeCharacter(text: String, touch: KeyTouch? = null) {
        val ic = currentInputConnection ?: return
        // может понадобиться стереть поставленный сам пробел — тогда стирание и ввод одним действием,
        // иначе пробел в поле на кадр пропадал и появлялся
        val batch = punctSpace
        if (batch) ic.beginBatchEdit()
        try {
            typeCharacterIn(ic, text, touch)
        } finally {
            if (batch) ic.endBatchEdit()
        }
    }

    private fun typeCharacterIn(ic: InputConnection, text: String, touch: KeyTouch?) {
        val afterSpace = lastActionWasSpace
        lastActionWasSpace = false
        val ch = text.singleOrNull()
        // «http:» + «/» или «слово,» + «@»: поставленный сам пробел здесь лишний
        if (punctSpace) {
            punctSpace = false
            if (autoSpace && composing.isEmpty() && ch != null && ch in "/\\@)]}") {
                icDeleteBefore(ic, 1)
                autoSpace = false
            }
        }
        if (ch == null || (!isWordLetter(ch) && !ch.isWhitespace())) tokenHasSymbol = true
        if (ch != null && ch.isWhitespace()) tokenHasSymbol = false
        // буква сразу после слова из свайпа начинает новое слово
        if (glideWord && composing.isNotEmpty() && ch != null && ch.isLetter()) {
            learn(lastCommittedWord, composing)
            lastCommittedWord = composing
            icCommit(ic, "$composing ")
        }
        glideWord = false
        if (ch != null && settings.autoPair && tryAutoPair(ic, ch)) return
        // «--» → «—» (не в паролях, адресах и почте: там два дефиса — это два дефиса)
        if (ch == '-' && flags.suggestions && settings.dashMerge && mergeDash(ic)) return
        val comp = kb.language.composer
        if (!flags.suggestions && (comp == Composer.NONE || Cjk.isCjk(comp))) {
            icCommit(ic, composing + text)
            undo = null
            autoSpace = false
            return
        }
        if (Cjk.isCjk(comp)) {
            typeCjk(ic, comp, text, ch)
            return
        }
        if (ch != null && (isWordLetter(ch) || ((ch == '\'' || ch == '-') && composing.isNotEmpty()))) {
            undo = null
            autoSpace = false
            if (comp != Composer.NONE) {
                // слово собирается из нажатых клавиш: ㅎㅏㄴ → 한, tieengs → tiếng
                if (composeRaw(comp, rawComposing) != composing) rawComposing = composing
                rawComposing += text
                icSetComposing(ic, composeRaw(comp, rawComposing))
            } else {
                val before = composing
                if (before.isEmpty()) {
                    noCorrectWord = kb.shift == ShiftState.CAPS_LOCK || (kb.shift == ShiftState.SINGLE && !autoShift) ||
                        lastTypedChar?.let { it in "@#/_\\" } == true
                }
                icSetComposing(ic, before + text)
                trackTouch(before, composing, touch)
            }
            schedulePredictions()
            return
        }
        if (ch != null && ch in ".,!?:;") {
            // после слова (не после цифры: «1,5», «12:30») за запятой, «!», «?», «;» и «:» сразу встаёт пробел
            var afterWord = composing.isNotEmpty() || (afterSpace && charBeforeSpace?.let { isWordLetter(it) } == true) ||
                (autoSpace && charBeforeAutoSpace?.let { isWordLetter(it) } == true) || lastTypedChar?.let { isWordLetter(it) } == true
            // курсор поставили после слова, набранного раньше: что перед ним — спрашиваем у поля (один символ)
            if (!afterWord && !afterSpace && !autoSpace && settings.autoSpacePunct && ch in ",!?;:" && composing.isEmpty()) {
                afterWord = runCatching { ic.getTextBeforeCursor(1, 0)?.lastOrNull() }.getOrNull()?.let { isWordLetter(it) } == true
            }
            val spaceAfter = settings.autoSpacePunct && ch in ",!?;:" && afterWord
            // «слово ,» → «слово, »: пробел после слова переезжает за знак, как в Gboard
            if (settings.smartPunctuation && afterSpace && !autoSpace && composing.isEmpty() && charBeforeSpace?.isLetterOrDigit() == true) {
                ic.beginBatchEdit()
                icDeleteBefore(ic, 1)
                icCommit(ic, "$text ")
                ic.endBatchEdit()
                tokenHasSymbol = true
                undo = null
                markPunctSpace(ch)
                schedulePredictions()
                return
            }
            commitWithBoundary(ic, if (spaceAfter) "$text " else text)
            if (spaceAfter) markPunctSpace(ch)
            return
        }
        val word = composing
        if (word.isNotEmpty()) {
            learn(lastCommittedWord, word)
            lastCommittedWord = word
        }
        icCommit(ic, word + text)
        undo = null
        autoSpace = false
        schedulePredictions()
    }

    /** Второй дефис подряд: пара «--» становится длинным тире «—». */
    private fun mergeDash(ic: InputConnection): Boolean {
        if (composing.isNotEmpty()) {
            // «слово-» набирается как одно слово — дефис в конце его и есть первый из пары
            if (!composing.endsWith('-') || kb.language.composer != Composer.NONE) return false
            val word = composing.dropLast(1)
            ic.beginBatchEdit()
            if (word.isNotEmpty()) {
                learn(lastCommittedWord, word)
                lastCommittedWord = word
            }
            icCommit(ic, "$word—")
            ic.endBatchEdit()
        } else {
            if (lastTypedChar != '-') return false
            if (runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull()?.toString() != "-") return false
            ic.beginBatchEdit()
            icDeleteBefore(ic, 1)
            icCommit(ic, "—")
            ic.endBatchEdit()
        }
        undo = null
        autoSpace = false
        tokenHasSymbol = true
        schedulePredictions()
        return true
    }

    /** Пиньинь и ромадзи: латинские буквы копятся в набираемом, остальное сначала вставляет лучший вариант. */
    private fun typeCjk(ic: InputConnection, comp: Composer, text: String, ch: Char?) {
        undo = null
        autoSpace = false
        val latin = ch != null && (ch in 'a'..'z' || ch in 'A'..'Z')
        val joiner = composing.isNotEmpty() && ((ch == '\'' && comp == Composer.PINYIN) || (ch == '-' && comp == Composer.KANA))
        if (latin || joiner) {
            if (composing.isEmpty()) {
                rawComposing = ""
                cjkPieces = 0
                cjkPhrase.clear()
            }
            rawComposing += ch!!.lowercaseChar()
            icSetComposing(ic, Cjk.display(comp, rawComposing))
            schedulePredictions()
            return
        }
        ic.beginBatchEdit()
        if (composing.isNotEmpty() && rawComposing.isNotEmpty()) commitCjk(ic, 0, all = true)
        icCommit(ic, Cjk.punctuation(comp, text))
        ic.endBatchEdit()
        schedulePredictions()
    }

    /** Вставляет вариант; если он покрыл только начало набранного, остаток продолжает набираться. */
    private fun commitCjk(ic: InputConnection, index: Int, all: Boolean = false) {
        val raw = rawComposing
        val list = if (cjkFor == raw && cjk.isNotEmpty()) cjk else Cjk.candidates(kb.language, raw)
        val c = list.getOrNull(index) ?: CjkCandidate(raw, raw.length)
        var text = c.text
        var rest = raw.drop(c.consumed).trimStart('\'')
        // перед знаком препинания вставляется всё набранное: лучший вариант и остаток как есть
        if (all && rest.isNotEmpty()) {
            text += Cjk.display(kb.language.composer, rest)
            rest = ""
        }
        ic.beginBatchEdit()
        icCommit(ic, text)
        rawComposing = rest
        if (rest.isNotEmpty()) icSetComposing(ic, Cjk.display(kb.language.composer, rest))
        ic.endBatchEdit()
        cjk = emptyList()
        cjkFor = ""
        // запомнить выбор: и кусок, и всю фразу, если её собирали по частям
        if (!flags.isPassword && !settings.incognito) {
            val lang = kb.language
            val usedRaw = raw.substring(0, minOf(raw.length, raw.length - rest.length))
            if (c.text != raw) CjkUser.learn(lang, usedRaw.trimEnd('\''), c.text)
            if (cjkPieces == 0) cjkPhraseRaw = raw
            cjkPhrase.append(c.text)
            cjkPieces++
            if (rest.isEmpty()) {
                if (cjkPieces > 1) CjkUser.learn(lang, cjkPhraseRaw, cjkPhrase.toString())
                cjkPhrase.clear()
                cjkPieces = 0
            }
        }
    }

    override fun pickCandidate(index: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty() || rawComposing.isEmpty()) return
        HapticsEngine.keyPress(this, inputView, settings, false)
        commitCjk(ic, index)
        schedulePredictions()
    }

    /** «(» вставляет «()» и ставит курсор внутрь; закрывающая скобка перед такой же просто перешагивает её. */
    private fun tryAutoPair(ic: InputConnection, ch: Char): Boolean {
        val close = PAIRS[ch]
        if (close != null) {
            val word = composing
            if (word.isNotEmpty()) {
                learn(lastCommittedWord, word)
                lastCommittedWord = word
            }
            icCommit(ic, "$word$ch$close")
            val pos = expSelStart
            if (pos > 0) {
                ic.setSelection(pos - 1, pos - 1)
                expect(pos - 1)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                expectUnknown()
            }
            undo = null
            autoSpace = false
            schedulePredictions()
            return true
        }
        if (ch in PAIRS.values && composing.isEmpty() && ic.getTextAfterCursor(1, 0)?.toString() == ch.toString()) {
            val pos = expSelStart
            if (pos >= 0) {
                ic.setSelection(pos + 1, pos + 1)
                expect(pos + 1)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                expectUnknown()
            }
            lastTypedChar = ch
            schedulePredictions()
            return true
        }
        return false
    }

    /** Пробел за знаком уже стоит: следующий пробел не удвоит его, «!» и «?» начинают предложение. */
    private fun markPunctSpace(ch: Char) {
        autoSpace = true
        punctSpace = true
        charBeforeAutoSpace = ch
        lastActionWasSpace = false
        if (ch in ".!?") requestAutoCap()
    }

    private fun commitWithBoundary(ic: InputConnection, suffix: String) {
        if (suffix.isNotBlank()) tokenHasSymbol = true
        val word = composing
        if (word.isEmpty()) {
            if (autoSpace && suffix.firstOrNull()?.let { it in ".,!?:;" } == true) {
                ic.beginBatchEdit()
                icDeleteBefore(ic, 1)
                icCommit(ic, suffix.trimEnd() + " ")
                ic.endBatchEdit()
            } else {
                icCommit(ic, suffix)
                autoSpace = false
            }
            undo = null
            if (suffix.firstOrNull()?.let { it in ".!?" } == true && autoSpace) requestAutoCap()
            schedulePredictions()
            return
        }
        val correction = resolveCorrection(word)
        if (correction != null) {
            val committed = correction + suffix
            icCommit(ic, committed)
            undo = UndoAutoCorrectState(word, committed)
            // исправленное слово тоже учится — растут частота и пары слов, как в Gboard
            if (' ' !in correction) learn(lastCommittedWord, correction)
            lastCommittedWord = correction.substringAfterLast(' ')
        } else {
            icCommit(ic, word + suffix)
            undo = null
            learn(lastCommittedWord, word)
            lastCommittedWord = word
        }
        autoSpace = false
        schedulePredictions()
    }

    /** Автозамена сокращений из плагинов по пробелу. IPC к полю — только если есть сокращения с символами. */
    private fun tryExpandShortcut(ic: InputConnection): Boolean {
        val map = PluginRepository.shortcuts
        if (map.isEmpty() || flags.isPassword) return false
        var token: String? = composing.takeIf { it.isNotEmpty() && map.containsKey(it.lowercase()) }
        if (token == null && PluginRepository.hasSymbolShortcuts && tokenHasSymbol) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            token = Regex("\\S+$").find(before)?.value?.takeIf { map.containsKey(it.lowercase()) }
        }
        val t = token ?: return false
        val template = map[t.lowercase()] ?: return false
        val text = PluginTemplates.expand(template, clipboard = firstTextClip())
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing = ""
            composingStart = -1
        }
        ic.deleteSurroundingText(t.length, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        lastTypedChar = ' '
        schedulePredictions()
        return true
    }

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        glideWord = false
        if (ScriptHost.has("space") && ScriptHost.cancelled("space")) return
        if (Cjk.isCjk(kb.language.composer) && composing.isNotEmpty() && rawComposing.isNotEmpty()) {
            commitCjk(ic, 0)
            schedulePredictions()
            return
        }
        if (tryExpandShortcut(ic)) {
            tokenHasSymbol = false
            return
        }
        tokenHasSymbol = false
        val now = SystemClock.uptimeMillis()
        if (composing.isEmpty() && lastActionWasSpace && settings.doubleSpacePeriod &&
            now - lastSpaceTime < 450L && charBeforeSpace?.isLetterOrDigit() == true && !autoSpace
        ) {
            ic.beginBatchEdit()
            icDeleteBefore(ic, 1)
            icCommit(ic, ". ")
            ic.endBatchEdit()
            undo = null
            lastActionWasSpace = false
            requestAutoCap()
            schedulePredictions()
            return
        }
        val prevChar = if (autoSpace && composing.isEmpty()) charBeforeAutoSpace else composing.lastOrNull() ?: lastTypedChar
        if (composing.isNotEmpty()) {
            commitWithBoundary(ic, " ")
        } else if (autoSpace) {
            autoSpace = false // пробел после подсказки уже стоит
        } else {
            icCommit(ic, " ")
            undo = null
        }
        charBeforeSpace = prevChar
        if (prevChar != null && prevChar in ".!?") requestAutoCap()
        lastActionWasSpace = true
        lastSpaceTime = now
        if (kb.symbolsPage != 0 && flags.suggestions) kb.symbolsPage = 0
        schedulePredictions()
    }

    override fun deleteBackward() {
        panelInput?.let { t ->
            val u = panelUndo
            panelUndo = null
            if (u != null && u.first == t) panelInput = u.second.dropLast(1)
            else if (t.isNotEmpty()) panelInput = t.dropLast(1)
            return
        }
        val ic = currentInputConnection ?: return
        if (ScriptHost.has("backspace") && ScriptHost.cancelled("backspace")) return
        lastActionWasSpace = false
        if (glideWord && composing.isNotEmpty()) {
            // слово из свайпа стирается целиком, как в Gboard
            glideWord = false
            icCommit(ic, "")
            schedulePredictions()
            return
        }
        glideWord = false
        if (composing.isNotEmpty()) {
            val comp = kb.language.composer
            if (comp != Composer.NONE && rawComposing.isNotEmpty() && composeRaw(comp, rawComposing) == composing) {
                // стираем последнюю нажатую клавишу: 한 → 하
                rawComposing = rawComposing.dropLast(1)
                val c = composeRaw(comp, rawComposing)
                if (c.isEmpty()) icCommit(ic, "") else icSetComposing(ic, c)
                schedulePredictions()
                return
            }
            val before = composing
            val trimmed = if (composing.length >= 2 && composing.last().isLowSurrogate()) composing.dropLast(2) else composing.dropLast(1)
            if (trimmed.isEmpty()) icCommit(ic, "") else icSetComposing(ic, trimmed)
            trimTouches(before, trimmed)
            schedulePredictions()
            return
        }
        val u = undo
        if (u != null) {
            ic.beginBatchEdit()
            icDeleteBefore(ic, u.committedText.length)
            icSetComposing(ic, u.originalWord)
            ic.endBatchEdit()
            undo = null
            // отменённое исправление какое-то время не повторяется; навсегда слово не запоминается — ⌫ часто жмут
            // не ради отмены, и одной случайной отмены хватало, чтобы опечатка («привит») больше не исправлялась
            WordPredictorEngine.rejectCorrection(kb.language, u.originalWord)
            schedulePredictions()
            return
        }
        // при удержании ⌫ (повтор каждые ~55 мс) поле не читаем: лишний запрос к приложению на каждый символ
        val now = SystemClock.uptimeMillis()
        val repeating = now - lastDeleteAt < 250L
        lastDeleteAt = now
        if (!repeating && resumeWordAfterDelete(ic)) return
        autoSpace = false
        tokenHasSymbol = true
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (expSelStart >= 0) {
            if (expSelStart != expSelEnd) expect(min(expSelStart, expSelEnd)) else expect(max(0, expSelStart - 1))
        }
        lastTypedChar = null
        if (expSelStart == 0) requestAutoCap()
        schedulePredictions()
    }

    /**
     * «привет,» + ⌫ → «привет» снова набирается: подсказки дополняют это слово, а выбранная подсказка
     * заменяет его целиком. Раньше подсказка приклеивалась к нему: «прив» + «привет» → «привпривет».
     */
    private fun resumeWordAfterDelete(ic: InputConnection): Boolean {
        if (!flags.suggestions || kb.language.composer != Composer.NONE) return false
        val pos = expSelStart
        if (pos < 2 || expSelEnd != pos) return false
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return false
        val last = before.lastOrNull() ?: return false
        if (last.isSurrogate() || isWordLetter(last) || last.isDigit()) return false
        val rest = before.dropLast(1)
        val word = Regex("[\\p{L}\\p{M}]+(?:['’-][\\p{L}\\p{M}]+)*$").find(rest)?.value ?: return false
        // слово целиком: перед ним начало текста или не-буква
        if (rest.length > word.length && isWordLetter(rest[rest.length - word.length - 1])) return false
        val end = pos - 1
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.setComposingRegion(end - word.length, end)
        ic.endBatchEdit()
        composing = word
        composingStart = end - word.length
        rawComposing = ""
        expect(end)
        lastTypedChar = word.last()
        lastCommittedWord = Regex("([\\p{L}\\p{M}'’-]+)\\s+$").find(rest.dropLast(word.length))?.groupValues?.get(1).orEmpty()
        undo = null
        autoSpace = false
        tokenHasSymbol = false
        schedulePredictions()
        return true
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        if (ScriptHost.has("enter") && ScriptHost.cancelled("enter")) return
        glideWord = false
        if (Cjk.isCjk(kb.language.composer) && composing.isNotEmpty() && rawComposing.isNotEmpty()) {
            // Enter во время набора вставляет набранное как есть: латиницу пиньиня или кану
            icCommit(ic, Cjk.display(kb.language.composer, rawComposing))
            rawComposing = ""
            schedulePredictions()
            return
        }
        if (composing.isNotEmpty()) {
            learn(lastCommittedWord, composing)
            ic.finishComposingText()
            composing = ""
            composingStart = -1
        }
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val actionable = !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (actionable) ic.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        expectUnknown()
        lastTypedChar = '\n'
        tokenHasSymbol = false
        requestAutoCap()
        schedulePredictions()
    }

    private fun toggleShift() {
        autoShift = false
        val now = SystemClock.uptimeMillis()
        kb.shift = when {
            kb.shift == ShiftState.CAPS_LOCK -> ShiftState.OFF
            now - lastShiftTime < 300L -> ShiftState.CAPS_LOCK
            kb.shift == ShiftState.SINGLE -> ShiftState.OFF
            else -> ShiftState.SINGLE
        }
        lastShiftTime = now
        schedulePredictions()
    }

    /** Следующий включённый язык (или предыдущий при direction < 0). */
    private fun toggleLanguage(direction: Int = 1) {
        val list = Languages.enabled(settings)
        val i = list.indexOf(kb.language)
        setLanguage(list[(i + (if (direction < 0) -1 else 1)).mod(list.size)])
    }

    /**
     * Системная кнопка «Сменить язык» (в панели навигации, в шторке, Ctrl+Пробел) переключает подтип ввода.
     * Подтипов у клавиатуры два, а языков может быть больше, — поэтому листаем свои языки по кругу.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        runCatching {
            // ответ на собственное переключение подтипа при смене языка
            if (Subtypes.isOwnSwitch(newSubtype)) return
            Subtypes.noteCurrent(newSubtype)
            val enabled = Languages.enabled(settings)
            val lang = Subtypes.languageOf(newSubtype, enabled, kb.language)
            if (lang != null) {
                if (lang != kb.language) {
                    if (isInputViewShown) HapticsEngine.tick(this, inputView, settings)
                    setLanguage(lang, syncSubtype = false)
                }
                return
            }
            // при выборе клавиатуры из списка подтип тоже «меняется» — язык листаем, только когда она на экране
            if (!isInputViewShown || enabled.size < 2) return
            HapticsEngine.tick(this, inputView, settings)
            toggleLanguage()
        }
    }

    private fun setLanguage(lang: KeyboardLanguage, syncSubtype: Boolean = true) {
        currentInputConnection?.let { ic ->
            if (composing.isNotEmpty()) {
                learn(lastCommittedWord, composing)
                lastCommittedWord = composing
                ic.finishComposingText()
                composing = ""
                composingStart = -1
            }
        }
        rawComposing = ""
        undo = null
        if (kb.language == lang) return
        kb.language = lang
        if (!lang.hasCase && kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        WordPredictorEngine.prepare(this, lang)
        Cjk.prepare(this, lang)
        // проверка правописания в приложении следует за подтипом — переключаем его вместе с языком
        if (syncSubtype) Subtypes.switchTo(this, lang)
        dataPrefs().edit().putString("last_language", lang.id).apply()
        schedulePredictions()
        ScriptHost.emit("language", lang.id)
    }

    // ------------------------------------------------------------------ KeyboardActions

    override fun keyDown(key: KeyModel) {
        val special = key.type == KeyType.SPACE || key.type == KeyType.ENTER ||
            key.type == KeyType.BACKSPACE || key.type == KeyType.SHIFT
        HapticsEngine.keyPress(this, inputView, settings, special)
        if (ScriptHost.has("press") && !flags.isPassword) {
            ScriptHost.emit("press", mapOf("label" to KeyboardGeometry.nameOf(key), "type" to key.type.name.lowercase()))
        }
    }

    override fun keyUp() = HapticsEngine.release(this, settings)

    override fun keyTick() = HapticsEngine.tick(this, inputView, settings)

    override fun commitKey(key: KeyModel, text: String) {
        // ошибка в обработке одного нажатия (поле ввода, словарь, мод) не должна ронять всю клавиатуру
        try {
            commitKeySafe(key, text)
        } catch (e: RuntimeException) {
            android.util.Log.e("ExpressiveKeyboard", "key ${key.type} failed", e)
            recoverInputState()
        }
    }

    /** После сбоя — забыть, что набиралось: следующее нажатие начнётся с чистого состояния. */
    private fun recoverInputState() {
        runCatching { currentInputConnection?.finishComposingText() }
        composing = ""
        composingStart = -1
        rawComposing = ""
        undo = null
        autoSpace = false
        tapWord = null
        expectUnknown()
        runCatching { schedulePredictions() }
    }

    private fun commitKeySafe(key: KeyModel, text: String) {
        // любое нажатие меняет текст — слово под курсором и его границы больше не верны
        tapWord = null
        if (kb.fieldEmpty && panelInput == null && (key.type == KeyType.CHARACTER || key.type == KeyType.SPACE)) kb.fieldEmpty = false
        if (kb.clipOffer != null && key.type in OFFER_BREAKERS && panelInput == null) kb.clipOffer = null
        when (key.type) {
            KeyType.CHARACTER -> {
                // клавиша-команда из раскладки плагина
                key.action?.let { if (ScriptHost.runCommand(it)) return }
                shiftBeforeKey = kb.shift
                val wasSingle = kb.shift == ShiftState.SINGLE
                val pi = panelInput
                if (pi != null) {
                    panelInput = if (text == "-" && pi.endsWith('-') && settings.dashMerge) pi.dropLast(1) + "—" else pi + text
                } else {
                    val t = if (ScriptHost.has("key") && !flags.isPassword) ScriptHost.filterKey(text) ?: return else text
                    if (t.isNotEmpty()) typeCharacter(t, pendingTouch)
                }
                pendingTouch = null
                if (wasSingle && kb.shift == ShiftState.SINGLE && text.any { isWordLetter(it) || it.isDigit() }) kb.shift = ShiftState.OFF
            }
            KeyType.SHIFT -> toggleShift()
            KeyType.BACKSPACE -> if (text == DELETE_WORD) deleteWord() else deleteBackward()
            KeyType.MODE_SYMBOLS -> {
                kb.symbolsPage = if (kb.symbolsPage == 0) 1 else 0
                if (kb.shift == ShiftState.SINGLE && kb.symbolsPage != 0) kb.shift = ShiftState.OFF
            }
            KeyType.SYMBOLS_PAGE -> kb.symbolsPage = if (kb.symbolsPage == 1) 2 else 1
            // нажатие — следующий язык, удержание — выбор из списка (text = код языка)
            KeyType.GLOBE -> Languages.enabled(settings).firstOrNull { it.label == text }?.let { setLanguage(it) } ?: toggleLanguage()
            KeyType.STICKERS -> togglePanel(ActivePanel.EMOJI)
            KeyType.SPACE -> panelInput?.let { panelSpace(it) } ?: typeSpace()
            KeyType.ENTER -> when {
                kb.panel == ActivePanel.EMOJI_SEARCH -> kb.panel = ActivePanel.EMOJI
                kb.panel == ActivePanel.GIF_SEARCH -> kb.panel = ActivePanel.GIF
                kb.panel == ActivePanel.TRANSLATE && kb.translatorResult.isNotBlank() -> insertTranslation()
                kb.panel == ActivePanel.AI_PROMPT -> aiTool("send")
                kb.panel == ActivePanel.SCRIPT_INPUT -> scriptPromptDone(true)
                else -> performEnter()
            }
            KeyType.SPACER -> Unit
        }
    }

    override fun keyTouch(fx: Float, fy: Float) {
        pendingTouch = KeyTouch(fx, fy)
    }

    override fun swipeSpace(direction: Int) {
        if (settings.spaceSwipe == "CURSOR" && panelInput == null) moveCursor(direction) else toggleLanguage(direction)
    }

    private fun finishComposing(ic: InputConnection) {
        if (composing.isEmpty()) return
        learn(lastCommittedWord, composing)
        ic.finishComposingText()
        composing = ""
        composingStart = -1
    }

    private fun moveCursor(direction: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        expectUnknown()
        sendDownUpKeyEvents(if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        HapticsEngine.tick(this, inputView, settings)
        schedulePredictions()
    }

    private fun deleteWord() {
        panelInput?.let { t ->
            if (t.isNotEmpty()) panelInput = t.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
            return
        }
        val ic = currentInputConnection ?: return
        lastActionWasSpace = false
        undo = null
        autoSpace = false
        if (composing.isNotEmpty()) {
            icCommit(ic, "")
            rawComposing = ""
            schedulePredictions()
            return
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
            expectUnknown()
            schedulePredictions()
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val n = Regex("(\\S+\\s*|\\s+)$").find(before)?.value?.length ?: 1
        icDeleteBefore(ic, n)
        if (expSelStart == 0 || before.length == n) requestAutoCap()
        schedulePredictions()
    }

    // ------------------------------------------------------------------ выделение свайпом по ⌫

    /** Где стоял курсор, когда начался свайп по ⌫ (-1 — свайпа нет), и текст перед ним. */
    private var swipeAnchor = -1
    private var swipeBefore = ""
    private var swipeLength = 0
    private var swipeWords = 0
    /** Выделение было ещё до свайпа — стираем его целиком, слова не считаем. */
    private var swipeHadSelection = false

    override fun backspaceSelect(words: Int) {
        swipeWords = words
        if (panelInput != null) return
        val ic = currentInputConnection ?: return
        if (swipeAnchor < 0) {
            finishComposing(ic)
            rawComposing = ""
            undo = null
            autoSpace = false
            lastActionWasSpace = false
            swipeHadSelection = !runCatching { ic.getSelectedText(0) }.getOrNull().isNullOrEmpty()
            if (swipeHadSelection) {
                swipeAnchor = 0
                return
            }
            val known = if (expSelStart >= 0 && expSelStart == expSelEnd) expSelStart else -1
            swipeAnchor = if (known >= 0) known else runCatching {
                ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.let { it.startOffset + it.selectionEnd }
            }.getOrNull() ?: -1
            if (swipeAnchor < 0) return
            swipeBefore = runCatching { ic.getTextBeforeCursor(2000, 0)?.toString() }.getOrNull().orEmpty()
            swipeLength = 0
        }
        if (swipeHadSelection) return
        // границы слов — как у стирания слова: слово вместе с пробелами после него
        var len = 0
        repeat(words) {
            val rest = swipeBefore.substring(0, swipeBefore.length - len)
            if (rest.isEmpty()) return@repeat
            len += Regex("(\\S+\\s*|\\s+)$").find(rest)?.value?.length ?: 1
        }
        if (len == swipeLength) return
        swipeLength = len
        val start = swipeAnchor - len
        ic.setSelection(start, swipeAnchor)
        expSelStart = start
        expSelEnd = swipeAnchor
    }

    override fun backspaceSelectDone(delete: Boolean) {
        val anchor = swipeAnchor
        val len = swipeLength
        val hadSelection = swipeHadSelection
        swipeAnchor = -1
        swipeBefore = ""
        swipeLength = 0
        swipeHadSelection = false
        panelInput?.let {
            if (delete) repeat(swipeWords) { deleteWord() }
            return
        }
        val ic = currentInputConnection ?: return
        if (hadSelection) {
            if (delete) {
                ic.commitText("", 1)
                expectUnknown()
                schedulePredictions()
            }
            return
        }
        if (anchor < 0) {
            // поле не сказало, где курсор: стираем по словам, как раньше
            if (delete) repeat(swipeWords) { deleteWord() }
            return
        }
        ic.beginBatchEdit()
        ic.setSelection(anchor, anchor)
        if (delete && len > 0) ic.deleteSurroundingText(len, 0)
        ic.endBatchEdit()
        expect(if (delete) anchor - len else anchor)
        if (delete && len > 0) {
            HapticsEngine.tick(this, inputView, settings)
            if (anchor - len == 0) requestAutoCap()
        }
        schedulePredictions()
    }

    override fun pickSuggestion(chip: SuggestionChipModel) {
        if (chip.isEmpty) return
        // подсказки, которые показал мод со своим обработчиком: выбор достаётся ему
        val pick = scriptPick
        if (pick != null && kb.suggestions === scriptPickRow) {
            HapticsEngine.keyPress(this, inputView, settings, false)
            pick(chip.commitWord, kb.suggestions.indexOf(chip).let { listOf(1, 0, 2).getOrElse(it) { it } })
            return
        }
        if (ScriptHost.has("pick") && !flags.isPassword && ScriptHost.cancelled("pick", mapOf("word" to chip.commitWord))) return
        HapticsEngine.keyPress(this, inputView, settings, false)
        // подсказка в переводчике заменяет набираемое там слово
        if (kb.panel == ActivePanel.TRANSLATE) {
            val t = kb.translatorInput
            val word = panelWordRe.find(t)?.value.orEmpty()
            panelUndo = null
            panelInput = t.dropLast(word.length) + chip.commitWord + " "
            return
        }
        val ic = currentInputConnection ?: return
        glideWord = false
        val tw = tapWord
        if (tw != null && composing.isEmpty() && !chip.isUndo) {
            replaceTapWord(ic, tw, chip)
            return
        }
        val u = undo
        if (chip.isUndo && u != null) {
            ic.beginBatchEdit()
            icDeleteBefore(ic, u.committedText.length)
            icCommit(ic, u.originalWord + " ")
            ic.endBatchEdit()
            WordPredictorEngine.rejectCorrection(kb.language, u.originalWord)
            learn("", u.originalWord, WordPredictorEngine.LEARNED_TYPO)
            lastCommittedWord = u.originalWord
        } else {
            var word = chip.commitWord
            // «оставить как есть» — это слово больше не исправлять
            if (chip.isKeepExact && composing.isNotEmpty() && word == composing) WordPredictorEngine.rejectCorrection(kb.language, word)
            if (composing.isEmpty() && kb.shift != ShiftState.OFF) word = word.replaceFirstChar { it.uppercase() }
            // курсор стоит вплотную к слову (его переставили) — следующее слово не должно к нему приклеиться
            val glue = composing.isEmpty() && ic.getTextBeforeCursor(1, 0)?.lastOrNull()?.let { isWordLetter(it) || it.isDigit() } == true
            icCommit(ic, (if (glue) " " else "") + "$word ")
            charBeforeAutoSpace = word.lastOrNull()
            learn(lastCommittedWord, word, if (chip.isKeepExact) WordPredictorEngine.LEARNED_TYPO else 1)
            lastCommittedWord = word
        }
        undo = null
        autoSpace = true
        lastActionWasSpace = false
        if (kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        schedulePredictions()
    }

    override fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        // набор пиньинем/ромадзи сначала превращается в иероглифы/кану, а не уходит латиницей
        if (Cjk.isCjk(kb.language.composer) && composing.isNotEmpty() && rawComposing.isNotEmpty()) commitCjk(ic, 0, all = true)
        val word = composing
        if (word.isNotEmpty()) {
            learn(lastCommittedWord, word)
            lastCommittedWord = word
        }
        icCommit(ic, word + text)
        tokenHasSymbol = true
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        schedulePredictions()
    }

    override fun commitEmoji(emoji: String) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        EmojiRecents.add(this, emoji)
        commitText(emoji)
    }

    override fun headerMenu() {
        HapticsEngine.keyPress(this, inputView, settings, false)
        if (kb.panel != ActivePanel.KEYBOARD) {
            if (kb.panel == ActivePanel.VOICE) voice.stop()
            val wasTranslate = kb.panel == ActivePanel.TRANSLATE
            kb.panel = ActivePanel.KEYBOARD
            kb.toolbarOpen = false
            if (wasTranslate) schedulePredictions()
        } else if (kb.toolbarOpen || (kb.fieldEmpty && !kb.toolsDismissed)) {
            // панель открыта сама (поле пустое) или вручную — убираем, на пустом поле остаются подсказки
            kb.toolbarOpen = false
            if (kb.fieldEmpty) kb.toolsDismissed = true
        } else kb.toolbarOpen = true
    }

    override fun togglePanel(panel: ActivePanel) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        val old = kb.panel
        val next = if (old == panel) ActivePanel.KEYBOARD else panel
        if (old == ActivePanel.VOICE && next != ActivePanel.VOICE) voice.stop()
        if (next == ActivePanel.CLIPBOARD) syncClipboard()
        if (next == ActivePanel.EMOJI || next == ActivePanel.EMOJI_SEARCH) EmojiCatalog.warmUp(this, now = true)
        if (next == ActivePanel.EMOJI_SEARCH) {
            kb.emojiQuery = ""
            kb.symbolsPage = 0
        }
        if (next == ActivePanel.GIF_SEARCH) {
            kb.gifQuery = ""
            kb.symbolsPage = 0
        }
        if (next == ActivePanel.AI_PROMPT) {
            kb.aiPrompt = ""
            kb.symbolsPage = 0
        }
        kb.panel = next
        if (next == ActivePanel.KEYBOARD) kb.toolbarOpen = false
        if (next == ActivePanel.VOICE) {
            kb.rambler = false
            voice.start(kb.language)
        }
        // подсказки переводчика или обычного поля — по тому, куда теперь печатаем
        if (next == ActivePanel.TRANSLATE || old == ActivePanel.TRANSLATE) schedulePredictions()
    }

    override fun toggleVoice() {
        HapticsEngine.keyPress(this, inputView, settings, true)
        if (voice.listening) voice.stop() else voice.start(kb.language, kb.rambler)
    }

    override fun rambler() {
        HapticsEngine.tick(this, inputView, settings)
        val s = settings
        val keys = AiKeys(AiProvider.from(s.aiProvider), s.geminiKey, s.anthropicKey, s.openaiKey, s.groqKey)
        if (!AiFix.hasLlm(keys)) {
            Toast.makeText(this, tr("Rambler чистит речь через ИИ: добавьте ключ в настройках «ИИ»"), Toast.LENGTH_LONG).show()
        }
        if (kb.panel == ActivePanel.VOICE) voice.stop()
        kb.rambler = true
        kb.panel = ActivePanel.VOICE
        kb.toolbarOpen = false
        voice.start(kb.language, rambler = true)
    }

    override fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Распознавание через окно другого приложения; текст вставится, когда поле снова получит фокус. */
    private fun launchVoiceWindow(): Boolean {
        if (!VoiceBridge.available(this)) return false
        if (!VoiceBridge.launch(this, kb.language.locale)) return false
        kb.panel = ActivePanel.KEYBOARD
        return true
    }

    /** Текст из окна распознавания, если он пришёл, пока поле было без фокуса. */
    private fun commitPendingVoice() {
        val text = VoiceBridge.take() ?: return
        runCatching { commitVoice(text) }
    }

    /**
     * Распознанная фраза: в начале предложения — с заглавной (сервис часто отдаёт всё строчными),
     * вплотную к слову — через пробел, чтобы не склеилась с ним.
     */
    private fun commitVoice(text: String) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        var t = text.trim()
        if (t.isEmpty()) return
        // мод может заменить распознанное (строка) или не вставлять его (false)
        if (ScriptHost.has("voice")) for (r in ScriptHost.emit("voice", mapOf("text" to t))) {
            if (r == false) return
            if (r is String) t = r.trim()
        }
        if (t.isEmpty()) return
        if (kb.shift != ShiftState.OFF && kb.language.hasCase) t = t.replaceFirstChar { it.uppercase() }
        val before = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull()?.lastOrNull()
        if (before != null && !before.isWhitespace() && before !in "([{«\"'„“") t = " $t"
        commitText("$t ")
        if (kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        cleanVoice("$t ")
    }

    /**
     * Чистая диктовка, как Rambler в Pixel: фраза сразу в поле, а ИИ в фоне убирает «э-э», повторы и оговорки и
     * расставляет знаки. Заменяем, только если фраза всё ещё стоит прямо перед курсором (её не успели изменить).
     */
    private fun cleanVoice(committed: String) {
        val s = settings
        // в Rambler чистим всегда, даже короткие фразы и с выключенной очисткой диктовки
        val rambler = kb.rambler
        if ((!s.aiVoiceCleanup && !rambler) || flags.isPassword || committed.trim().split(' ').size < (if (rambler) 2 else 3)) return
        val keys = AiKeys(AiProvider.from(s.aiProvider), s.geminiKey, s.anthropicKey, s.openaiKey, s.groqKey)
        if (!AiFix.hasLlm(keys)) return
        serviceScope.launch {
            val r = AiFix.run(this@ComposeKeyboardService, committed.trim(), keys, kb.language.id.substringBefore('-'), AiTask.DICTATION)
            val clean = r.getOrNull()?.first?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
            val ic = currentInputConnection ?: return@launch
            val lead = committed.takeWhile { it == ' ' }
            val out = lead + clean + " "
            if (out == committed) return@launch
            val b = runCatching { ic.getTextBeforeCursor(committed.length, 0)?.toString() }.getOrNull()
            if (b != committed || composing.isNotEmpty()) return@launch
            ic.beginBatchEdit()
            ic.deleteSurroundingText(committed.length, 0)
            ic.commitText(out, 1)
            ic.endBatchEdit()
            expectUnknown()
        }
    }

    override fun openVoiceFallback() {
        if (launchVoiceWindow()) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        // клавиатура с голосовым режимом (Google voice typing, Gboard и т. п.): переключаемся прямо в него
        val voiceIme = runCatching {
            imm.enabledInputMethodList.filter { it.packageName != packageName }.firstNotNullOfOrNull { ime ->
                imm.getEnabledInputMethodSubtypeList(ime, true).firstOrNull { it.mode == "voice" }?.let { ime to it }
            }
        }.getOrNull()
        if (voiceIme != null && Build.VERSION.SDK_INT >= 28) {
            runCatching { switchInputMethod(voiceIme.first.id, voiceIme.second) }.onSuccess { return }
        }
        // распознавать нечем совсем — предложить установить распознавание речи Google
        val store = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.tts"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(store) }.isSuccess) {
            Toast.makeText(this, tr("Установите «Распознавание и синтез речи Google»"), Toast.LENGTH_LONG).show()
            return
        }
        imm.showInputMethodPicker()
    }

    override fun hideKeyboard() {
        requestHideSelf(0)
    }

    override fun clearClipboard() {
        val keep = kb.pinnedClips.toSet()
        kb.clipboard.filter { it !in keep }.forEach {
            ClipImages.delete(it)
            ClipStore.forget(it)
        }
        kb.clipboard.retainAll(keep)
        if (kb.clipOffer?.let { it !in keep } == true) kb.clipOffer = null
        ClipStore.save(this, kb.clipboard)
    }

    override fun removeClip(text: String) {
        HapticsEngine.tick(this, inputView, settings)
        kb.clipboard.remove(text)
        if (kb.pinnedClips.remove(text)) savePinned()
        if (kb.clipOffer == text) kb.clipOffer = null
        ClipImages.delete(text)
        ClipStore.forget(text)
        ClipStore.save(this, kb.clipboard)
    }

    /** Вставленная запись поднимается наверх — последние использованные всегда под рукой. */
    private fun bumpClip(entry: String) {
        ClipStore.touch(entry)
        val p = kb.pinnedClips.indexOf(entry)
        if (p > 0) {
            kb.pinnedClips.removeAt(p)
            kb.pinnedClips.add(0, entry)
            savePinned()
        }
        val i = kb.clipboard.indexOf(entry)
        if (i > 0) {
            kb.clipboard.removeAt(i)
            kb.clipboard.add(0, entry)
        }
        if (i >= 0 && !settings.incognito) ClipStore.save(this, kb.clipboard)
    }

    override fun commitClip(entry: String) {
        bumpClip(entry)
        if (!ClipImages.isImage(entry)) {
            commitText(entry)
            return
        }
        HapticsEngine.keyPress(this, inputView, settings, false)
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val mime = ClipImages.mime(entry)
        val accepts = EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }
        if (!accepts) {
            Toast.makeText(this, tr("Это поле не принимает картинки"), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", ClipImages.file(entry))
            val content = InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mime)), null)
            InputConnectionCompat.commitContent(ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
        if (!ok) Toast.makeText(this, tr("Не удалось вставить картинку"), Toast.LENGTH_SHORT).show()
    }

    override fun pasteClipOffer() {
        val entry = kb.clipOffer ?: return
        kb.clipOffer = null
        commitClip(entry)
    }

    override fun spaceHold() {
        when (settings.spaceLongAction) {
            "AI_FIX" -> aiFix()
            "VOICE" -> togglePanel(ActivePanel.VOICE)
            "EMOJI" -> togglePanel(ActivePanel.EMOJI)
            "CLIPBOARD" -> togglePanel(ActivePanel.CLIPBOARD)
            "TOOLBAR" -> headerMenu()
            "SETTINGS" -> openSettings()
            "OFF" -> Unit
            else -> switchKeyboard()
        }
    }

    /** Что ИИ вставил последним и что было до этого: (вставленный текст перед курсором, прежний текст). */
    private var aiBackup: Pair<String, String>? = null

    override fun aiTool(id: String) {
        when (id) {
            "prompt" -> {
                togglePanel(ActivePanel.AI_PROMPT)
                return
            }
            "undo" -> {
                val (inserted, original) = aiBackup ?: return
                val ic = currentInputConnection ?: return
                HapticsEngine.keyPress(this, inputView, settings, false)
                val b = runCatching { ic.getTextBeforeCursor(inserted.length, 0)?.toString() }.getOrNull()
                if (b != inserted) {
                    Toast.makeText(this, tr("Текст изменился — нажмите ещё раз"), Toast.LENGTH_SHORT).show()
                } else {
                    finishComposing(ic)
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(inserted.length, 0)
                    ic.commitText(original, 1)
                    ic.endBatchEdit()
                    expectUnknown()
                    schedulePredictions()
                }
                aiBackup = null
                kb.aiCanUndo = false
                return
            }
            "send" -> {
                val instruction = kb.aiPrompt.trim()
                if (instruction.isEmpty()) return
                kb.panel = ActivePanel.AI_WRITE
                aiRun(AiTask.CUSTOM, instruction)
            }
            else -> aiRun(AiTask.from(id), "")
        }
    }

    private fun aiRun(task: AiTask, instruction: String) {
        if (kb.aiBusy) return
        val ic = currentInputConnection ?: return
        if (flags.isPassword) return
        HapticsEngine.keyPress(this, inputView, settings, false)
        val t = runCatching {
            if (task == AiTask.CONTINUE) {
                finishComposing(ic)
                val b = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
                if (b.isBlank()) null else TextTarget(b, false, "", "", "")
            } else takeTarget(ic, "selection_or_text")
        }.getOrNull()
        if ((t == null || t.text.isBlank()) && task != AiTask.CUSTOM) {
            Toast.makeText(this, tr("Нет текста для исправления"), Toast.LENGTH_SHORT).show()
            return
        }
        val s = settings
        val keys = AiKeys(AiProvider.from(s.aiProvider), s.geminiKey, s.anthropicKey, s.openaiKey, s.groqKey)
        kb.aiBusy = true
        kb.aiTask = task.id
        val started = SystemClock.uptimeMillis()
        serviceScope.launch {
            val r = try {
                AiFix.run(this@ComposeKeyboardService, t?.text.orEmpty(), keys, kb.language.id.substringBefore('-'), task, instruction).also {
                    if (it.isSuccess) delay(MIN_AI_ANIM_MS - (SystemClock.uptimeMillis() - started))
                }
            } finally {
                kb.aiBusy = false
                kb.aiTask = ""
            }
            val c = currentInputConnection ?: return@launch
            r.onSuccess { (out0, _) ->
                when {
                    // дописать и написать с нуля — вставка у курсора
                    task == AiTask.CONTINUE || t == null -> {
                        finishComposing(c)
                        val before = runCatching { c.getTextBeforeCursor(1, 0)?.toString() }.getOrNull().orEmpty()
                        var out = out0
                        if (before.isNotEmpty() && !before.last().isWhitespace() && out.firstOrNull()?.let { it.isLetterOrDigit() } == true) out = " $out"
                        if (out.isBlank()) return@onSuccess
                        c.commitText(out, 1)
                        expectUnknown()
                        aiBackup = out to ""
                        kb.aiCanUndo = true
                        kb.aiDone++
                        schedulePredictions()
                    }
                    out0 == t.text -> Toast.makeText(this@ComposeKeyboardService, tr(if (task == AiTask.PROOFREAD) "Ошибок не найдено" else "Текст не изменился"), Toast.LENGTH_SHORT).show()
                    !targetStillValid(c, t) -> Toast.makeText(this@ComposeKeyboardService, tr("Текст изменился — нажмите ещё раз"), Toast.LENGTH_SHORT).show()
                    else -> {
                        replaceTarget(c, t, out0)
                        aiBackup = if (t.selection) out0 to t.text
                        else if (t.afterText.isEmpty()) (out0 + t.trailing) to (t.beforeText) else null
                        kb.aiCanUndo = aiBackup != null
                        kb.aiDone++
                    }
                }
            }.onFailure { e ->
                Toast.makeText(this@ComposeKeyboardService, e.message ?: tr("Не удалось обработать текст"), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun aiFix() {
        if (kb.aiBusy) return
        val ic = currentInputConnection ?: return
        if (flags.isPassword) return
        HapticsEngine.keyPress(this, inputView, settings, false)
        val t = runCatching { takeTarget(ic, "selection_or_text") }.getOrNull()
        if (t == null || t.text.isBlank()) {
            Toast.makeText(this, tr("Нет текста для исправления"), Toast.LENGTH_SHORT).show()
            return
        }
        val s = settings
        val keys = AiKeys(AiProvider.from(s.aiProvider), s.geminiKey, s.anthropicKey, s.openaiKey, s.groqKey)
        kb.aiBusy = true
        val started = SystemClock.uptimeMillis()
        serviceScope.launch {
            val r = try {
                AiFix.fix(this@ComposeKeyboardService, t.text, keys, kb.language.id.substringBefore('-')).also {
                    // быстрый ответ — дать анимации «зарядиться», чтобы она не мелькнула и не пропала
                    if (it.isSuccess) delay(MIN_AI_ANIM_MS - (SystemClock.uptimeMillis() - started))
                }
            } finally {
                kb.aiBusy = false
            }
            val c = currentInputConnection ?: return@launch
            r.onSuccess { (out, _) ->
                when {
                    out == t.text -> Toast.makeText(this@ComposeKeyboardService, tr("Ошибок не найдено"), Toast.LENGTH_SHORT).show()
                    // пока ждали ответ, текст поменяли — не затираем то, что пользователь успел набрать
                    !targetStillValid(c, t) -> Toast.makeText(this@ComposeKeyboardService, tr("Текст изменился — нажмите ещё раз"), Toast.LENGTH_SHORT).show()
                    else -> {
                        replaceTarget(c, t, out)
                        kb.aiDone++
                    }
                }
            }.onFailure { e ->
                Toast.makeText(this@ComposeKeyboardService, e.message ?: tr("Не удалось исправить текст"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun editKey(id: String) {
        val ic = currentInputConnection ?: return
        HapticsEngine.tick(this, inputView, settings)
        if (id == "select") {
            kb.selectMode = !kb.selectMode
            return
        }
        val arrow = when (id) {
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            else -> null
        }
        runCatching {
            if (arrow != null && kb.selectMode) {
                // стрелка с Shift — выделение растёт или сжимается
                finishComposing(ic)
                sendChord(ic, arrow, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
                expectUnknown()
                undo = null
                autoSpace = false
                lastCommittedWord = ""
                schedulePredictions()
            } else {
                pluginKey(ic, id)
                // скопировали или вставили — выделение больше не нужно
                if (id == "copy" || id == "paste") kb.selectMode = false
            }
        }
    }

    override fun globeHold() {
        when (settings.globeLongPress) {
            "SYSTEM" -> (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            "EMOJI" -> togglePanel(ActivePanel.EMOJI)
            "CLIPBOARD" -> togglePanel(ActivePanel.CLIPBOARD)
            "VOICE" -> togglePanel(ActivePanel.VOICE)
            "SETTINGS" -> openSettings()
            "ONE_HANDED" -> KeyboardPrefs.edit(this) { putString("one_handed", if (settings.oneHanded == "OFF") "RIGHT" else "OFF") }
        }
    }

    override fun dismissClipOffer() {
        HapticsEngine.tick(this, inputView, settings)
        kb.clipOffer = null
    }

    /** Первая текстовая запись буфера — для шаблонов {clipboard}. */
    private fun firstTextClip(): String = kb.clipboard.firstOrNull { !ClipImages.isImage(it) }.orEmpty()

    private fun addClip(entry: String, stamp: Long) {
        val s = settings
        ClipStore.touch(entry, if (stamp > 0) stamp else System.currentTimeMillis())
        if (kb.clipboard.firstOrNull() != entry) {
            kb.clipboard.remove(entry)
            kb.clipboard.add(0, entry)
            while (kb.clipboard.size > s.clipboardSize) {
                val gone = kb.clipboard.removeAt(kb.clipboard.lastIndex)
                ClipStore.forget(gone)
                if (gone !in kb.pinnedClips && gone != kb.clipOffer) ClipImages.delete(gone)
            }
        }
        ClipStore.save(this, kb.clipboard)
        // скопировали только что — предложить вставить (как в Gboard)
        val now = System.currentTimeMillis()
        if (stamp > 0 && now - stamp in 0..CLIP_OFFER_MS && !flags.isPassword) {
            kb.clipOffer = entry
            clipOfferAt = stamp
        }
    }

    override fun togglePinClip(text: String) {
        HapticsEngine.tick(this, inputView, settings)
        if (!kb.pinnedClips.remove(text)) kb.pinnedClips.add(0, text)
        savePinned()
    }

    private fun savePinned() {
        val arr = JSONArray()
        kb.pinnedClips.forEach { arr.put(it) }
        dataPrefs().edit().putString("pinned_clips", arr.toString()).apply()
    }

    override fun forgetSuggestion(word: String) {
        if (word.isBlank()) return
        HapticsEngine.tick(this, inputView, settings)
        WordPredictorEngine.forget(kb.language, word)
        if (Cjk.isCjk(kb.language.composer)) CjkUser.forget(kb.language, word)
        Toast.makeText(this, tr("«{0}» удалено из выученных слов", word), Toast.LENGTH_SHORT).show()
        schedulePredictions()
    }

    override fun runScriptButton(pluginId: String, id: String) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        ScriptHost.runButton(pluginId, id)
    }

    override fun insertTranslation() {
        val result = kb.translatorResult
        if (result.isBlank()) return
        kb.translatorInput = ""
        kb.translatorResult = ""
        commitText("$result ")
    }

    private fun syncClipboard() {
        val s = settings
        // инкогнито и поле пароля: скопированное не попадает ни в историю, ни к модам
        if (s.incognito || flags.isPassword) return
        if (!s.clipboardHistory) {
            if (kb.clipboard.isNotEmpty()) {
                kb.clipboard.clear()
                ClipStore.clear(this)
            }
            return
        }
        runCatching {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            // пароли, помеченные системой как чувствительные, в историю не попадают
            // (менеджеры паролей ставят этот флаг и на старых Android — проверяем всегда)
            if (desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
            val stamp = if (Build.VERSION.SDK_INT >= 26) desc?.timestamp ?: 0L else 0L
            // эту запись уже обработали (stamp 0 — старые Android: сравниваем по содержимому ниже)
            if (stamp != 0L && stamp == lastClipStamp) return
            val item = clip.getItemAt(0)
            val uri = item.uri
            val imageMime = desc?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") } }
            if (uri != null && imageMime != null) {
                lastClipStamp = stamp
                serviceScope.launch {
                    val entry = ClipImages.save(this@ComposeKeyboardService, uri, imageMime, if (stamp > 0) stamp else System.currentTimeMillis())
                    if (entry != null) addClip(entry, stamp)
                }
                return
            }
            val text = item.coerceToText(this)?.toString()?.trim()
            // пока клавиатура скрыта, система может отдать пустую запись — перечитаем, когда она появится
            if (text.isNullOrEmpty()) return
            lastClipStamp = stamp
            if (stamp == 0L && kb.clipboard.firstOrNull() == text) return
            // очень длинный текст в историю не кладём целиком — это не заметки, а буфер
            val entry = if (text.length > 20_000) text.take(20_000) else text
            addClip(entry, stamp)
            if (!ScriptHost.privateMode) ScriptHost.emit("clipboard", entry)
        }
    }

    override fun commitSymbol(text: String) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        commitText(text)
    }

    // ------------------------------------------------------------------ мгновенный ввод и свайп

    override fun replaceLast(old: String, new: String) {
        panelInput?.let { t ->
            if (t.endsWith(old)) panelInput = t.dropLast(old.length) + new
            if (new.isEmpty()) kb.shift = shiftBeforeKey
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty() && composing.endsWith(old)) {
            val rest = composing.dropLast(old.length)
            if (rest.isEmpty()) icCommit(ic, "") else icSetComposing(ic, rest)
        } else {
            icDeleteBefore(ic, old.length)
        }
        if (new.isNotEmpty()) typeCharacter(new)
        ic.endBatchEdit()
        if (new.isEmpty()) {
            kb.shift = shiftBeforeKey
            schedulePredictions()
        }
    }

    override fun commitGlide(path: List<Offset>, keys: Map<Char, Offset>, keyWidth: Float, tapLetter: String?) {
        val lang = kb.language
        // буква с первой клавиши уже сняла одиночный Shift — слово берёт регистр, каким он был до неё
        val shift = if (tapLetter != null) shiftBeforeKey else kb.shift
        // слово перед свайпом: набираемое (оно сейчас станет предыдущим) или последнее вставленное
        val prev = if (panelInput == null) composing.ifEmpty { lastCommittedWord } else ""
        glideJob?.cancel()
        glideJob = serviceScope.launch {
            val words = withContext(Dispatchers.Default) {
                GlideDecoder.decode(path, keys, keyWidth, WordPredictorEngine.glideLexicon(lang), 3, WordPredictorEngine.glideContext(lang, prev))
            }
            if (words.isEmpty()) {
                // слово не распозналось — остаётся буква, с которой начали, ничего не теряется
                HapticsEngine.tick(this@ComposeKeyboardService, inputView, settings)
                return@launch
            }
            insertGlideWords(words.map { w ->
                when (shift) {
                    ShiftState.CAPS_LOCK -> w.uppercase()
                    ShiftState.SINGLE -> w.replaceFirstChar { it.uppercase() }
                    ShiftState.OFF -> w
                }
            }, tapLetter)
        }
    }

    private fun insertGlideWords(words: List<String>, tapLetter: String? = null) {
        var best = words.first()
        if (ScriptHost.has("glide")) for (r in ScriptHost.emit("glide", mapOf("word" to best, "alternatives" to words))) {
            if (r == false) return
            if (r is String && r.isNotBlank()) best = r
        }
        panelInput?.let { t0 ->
            val t = if (tapLetter != null && t0.endsWith(tapLetter)) t0.dropLast(tapLetter.length) else t0
            panelInput = if (t.isEmpty() || t.endsWith(' ')) t + best else "$t $best"
            return
        }
        val ic = currentInputConnection ?: return
        // что стоит перед словом — читаем ДО правок: посреди своей же правки многие приложения отдают старый текст
        // (ещё с буквой, снятой ниже), и перед словом вставлялся второй пробел
        val removeTap = tapLetter != null && (
            (composing.isNotEmpty() && composing.endsWith(tapLetter)) ||
                (composing.isEmpty() && lastTypedChar?.toString() == tapLetter.takeLast(1)))
        val keepComposing = if (removeTap && composing.isNotEmpty()) composing.dropLast(tapLetter!!.length) else composing
        val beforeWord: Char? = if (keepComposing.isNotEmpty()) ' ' else {
            val drop = if (removeTap) tapLetter!!.length else composing.length
            val t = runCatching { ic.getTextBeforeCursor(drop + 1, 0)?.toString() }.getOrNull().orEmpty()
            t.dropLast(min(drop, t.length)).lastOrNull()
        }
        ic.beginBatchEdit()
        // убрать букву, вставленную касанием в начале свайпа, — одним действием со вставкой слова
        if (removeTap) {
            if (composing.isNotEmpty()) {
                if (keepComposing.isEmpty()) icCommit(ic, "") else icSetComposing(ic, keepComposing)
            } else {
                icDeleteBefore(ic, tapLetter!!.length)
            }
        }
        if (composing.isNotEmpty()) {
            learn(lastCommittedWord, composing)
            lastCommittedWord = composing
            icCommit(ic, "$composing ")
        } else if (beforeWord != null && !beforeWord.isWhitespace() && beforeWord !in "([{«\"'„“/@#") {
            icCommit(ic, " ")
        }
        icSetComposing(ic, best)
        ic.endBatchEdit()
        glideWord = true
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        tokenHasSymbol = false
        if (kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        // остальные варианты — в подсказки; выбор подсказки заменит слово
        predictionJob?.cancel()
        precomputed = null
        precomputedOwn = null
        precomputedFor = best
        kb.suggestions = listOf(
            words.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY,
            SuggestionChipModel(best, best, isPrimary = true),
            words.getOrNull(2)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
        )
    }

    override fun switchKeyboard() {
        HapticsEngine.tick(this, inputView, settings)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    // ------------------------------------------------------------------ плагины

    private class TextTarget(val text: String, val selection: Boolean, val beforeText: String, val afterText: String, val trailing: String)

    private fun takeTarget(ic: InputConnection, scope: String): TextTarget? {
        finishComposing(ic)
        val sel = ic.getSelectedText(0)?.toString().orEmpty()
        if (scope != "text" && scope != "word" && sel.isNotEmpty()) return TextTarget(sel, true, "", "", "")
        if (scope == "selection") return null
        if (scope == "text" || scope == "selection_or_text") {
            val b = ic.getTextBeforeCursor(20000, 0)?.toString().orEmpty()
            val a = ic.getTextAfterCursor(20000, 0)?.toString().orEmpty()
            return if (b.isEmpty() && a.isEmpty()) null else TextTarget(b + a, false, b, a, "")
        }
        val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        val m = Regex("(\\S+)(\\s*)$").find(before) ?: return null
        return TextTarget(m.groupValues[1], false, m.value, "", m.groupValues[2])
    }

    private fun targetStillValid(ic: InputConnection, t: TextTarget): Boolean = runCatching {
        if (t.selection) return ic.getSelectedText(0)?.toString() == t.text
        val b = ic.getTextBeforeCursor(t.beforeText.length, 0)?.toString().orEmpty()
        val a = if (t.afterText.isEmpty()) "" else ic.getTextAfterCursor(t.afterText.length, 0)?.toString().orEmpty()
        b == t.beforeText && a == t.afterText
    }.getOrDefault(false)

    private fun replaceTarget(ic: InputConnection, t: TextTarget, out: String) {
        ic.beginBatchEdit()
        if (t.selection) {
            ic.commitText(out, 1)
        } else {
            ic.deleteSurroundingText(t.beforeText.length, t.afterText.length)
            ic.commitText(out + t.trailing, 1)
        }
        ic.endBatchEdit()
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        schedulePredictions()
    }

    private fun sendChord(ic: InputConnection, code: Int, meta: Int) {
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun pluginKey(ic: InputConnection, key: String) {
        if (key == "enter") return performEnter()
        if (key == "delete_word") return deleteWord()
        finishComposing(ic)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        when (key) {
            "tab" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            "left" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            "home" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
            "end" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
            "escape" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
            "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "undo" -> sendChord(ic, KeyEvent.KEYCODE_Z, ctrl)
            "redo" -> sendChord(ic, KeyEvent.KEYCODE_Z, ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        }
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        schedulePredictions()
    }

    override fun runPluginAction(pluginId: String, index: Int) {
        val plugin = PluginRepository.active.firstOrNull { it.id == pluginId } ?: return
        val a = plugin.actions.getOrNull(index) ?: return
        HapticsEngine.keyPress(this, inputView, settings, false)
        val ic = currentInputConnection ?: return
        val clip = firstTextClip()
        runCatching {
            when (a.type) {
                PluginActionType.INSERT -> {
                    val sel = ic.getSelectedText(0)?.toString().orEmpty()
                    commitText(PluginTemplates.expand(a.text, sel, clip))
                    kb.pluginStatus = "✓ ${a.title}"
                }
                PluginActionType.TRANSFORM -> {
                    val t = takeTarget(ic, a.scope)
                    if (t == null) {
                        kb.pluginStatus = "Нет текста для «${a.title}»"
                    } else {
                        replaceTarget(ic, t, PluginTransforms.apply(a, t.text))
                        kb.pluginStatus = "✓ ${a.title}"
                    }
                }
                PluginActionType.KEY -> {
                    pluginKey(ic, a.key)
                    kb.pluginStatus = "✓ ${a.title}"
                }
                PluginActionType.HTTP -> {
                    val t = takeTarget(ic, a.scope)
                    kb.pluginStatus = "⏳ ${a.title}…"
                    serviceScope.launch {
                        val r = PluginHttp.run(a, t?.text.orEmpty(), clip)
                        val ic2 = currentInputConnection ?: return@launch
                        r.onSuccess { out ->
                            if (a.output == "replace" && t != null && targetStillValid(ic2, t)) replaceTarget(ic2, t, out)
                            else commitText(out)
                            kb.pluginStatus = "✓ ${a.title}"
                        }.onFailure { kb.pluginStatus = "✕ ${a.title}: ${it.message ?: "ошибка сети"}" }
                    }
                }
            }
        }.onFailure { kb.pluginStatus = "✕ ${a.title}: ${it.message ?: "ошибка"}" }
    }

    // ------------------------------------------------------------------ ScriptBridge: что могут скрипты модов

    override fun scriptCommit(text: String) = commitText(text)

    override fun scriptDelete(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.deleteSurroundingText(before, after)
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        schedulePredictions()
    }

    override fun scriptTextBefore(n: Int): String = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    override fun scriptTextAfter(n: Int): String = currentInputConnection?.getTextAfterCursor(n, 0)?.toString().orEmpty()

    override fun scriptSelected(): String = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()

    override fun scriptSetSelection(start: Int, end: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.setSelection(max(0, start), max(0, end))
        expectUnknown()
        schedulePredictions()
    }

    override fun scriptKey(name: String) {
        val ic = currentInputConnection ?: return
        when (name) {
            "space" -> typeSpace()
            "backspace", "delete" -> deleteBackward()
            "shift" -> toggleShift()
            "language", "globe" -> toggleLanguage()
            else -> pluginKey(ic, name)
        }
    }

    override fun scriptLanguage(): String = kb.language.id

    override fun scriptSetLanguage(id: String): Boolean {
        val lang = Languages.byId(id) ?: return false
        setLanguage(lang)
        return true
    }

    override fun scriptShift(): String = when (kb.shift) {
        ShiftState.OFF -> "off"
        ShiftState.SINGLE -> "single"
        ShiftState.CAPS_LOCK -> "caps"
    }

    override fun scriptSetShift(state: String) {
        kb.shift = when (state.lowercase()) {
            "single", "on" -> ShiftState.SINGLE
            "caps", "lock" -> ShiftState.CAPS_LOCK
            else -> ShiftState.OFF
        }
    }

    override fun scriptField(): Map<String, Any?> {
        val info = currentInputEditorInfo
        val type = info?.inputType ?: 0
        return mapOf(
            "package" to (info?.packageName ?: ""),
            "inputType" to type,
            "password" to flags.isPassword,
            "multiLine" to ((type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0),
            "action" to when (kb.enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> "search"
                EditorInfo.IME_ACTION_SEND -> "send"
                EditorInfo.IME_ACTION_GO -> "go"
                EditorInfo.IME_ACTION_DONE -> "done"
                EditorInfo.IME_ACTION_NEXT -> "next"
                else -> "enter"
            },
            "hint" to (info?.hintText?.toString() ?: "")
        )
    }

    override fun scriptOpenPanel(id: String) {
        if (id.startsWith("plugin:")) {
            // "plugin:<id плагина>/<id панели>" — сразу нужная вкладка
            val spec = id.removePrefix("plugin:")
            kb.pluginTab = if ('/' in spec) "${spec.substringBefore('/')}#p:${spec.substringAfter('/')}" else "$spec#a"
            if (kb.panel != ActivePanel.PLUGINS) togglePanel(ActivePanel.PLUGINS)
            return
        }
        val panel = when (id.lowercase()) {
            "emoji" -> ActivePanel.EMOJI
            "clipboard" -> ActivePanel.CLIPBOARD
            "translate", "translator" -> ActivePanel.TRANSLATE
            "gif" -> ActivePanel.GIF
            "voice" -> ActivePanel.VOICE
            "plugins" -> ActivePanel.PLUGINS
            else -> ActivePanel.KEYBOARD
        }
        if (panel == ActivePanel.KEYBOARD) {
            if (kb.panel == ActivePanel.VOICE) voice.stop()
            kb.panel = ActivePanel.KEYBOARD
            kb.toolbarOpen = false
        } else if (kb.panel != panel) togglePanel(panel)
    }

    override fun scriptStatus(text: String) {
        kb.pluginStatus = text
    }

    override fun scriptVibrate(ms: Int) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun scriptClipboard(): List<String> = (kb.pinnedClips + kb.clipboard).distinct().filter { !ClipImages.isImage(it) }

    override fun scriptCopy(text: String) {
        runCatching { clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("plugin", text)) }
    }

    /** Подсказки, которые мод показал со своим обработчиком выбора, и сама эта строка подсказок. */
    private var scriptPick: ((String, Int) -> Unit)? = null
    private var scriptPickRow: List<SuggestionChipModel>? = null

    override fun scriptSuggestions(words: List<String>, pick: ((String, Int) -> Unit)?) {
        val w = words.filter { it.isNotBlank() }
        val row = listOf(
            w.getOrNull(1)?.let { SuggestionChipModel(it, it, isKeepExact = true) } ?: SuggestionChipModel.EMPTY,
            w.getOrNull(0)?.let { SuggestionChipModel(it, it, isPrimary = true, isKeepExact = true) } ?: SuggestionChipModel.EMPTY.copy(isPrimary = true),
            w.getOrNull(2)?.let { SuggestionChipModel(it, it, isKeepExact = true) } ?: SuggestionChipModel.EMPTY
        )
        kb.suggestions = row
        scriptPick = pick
        scriptPickRow = if (pick != null) row else null
    }

    override fun scriptHide() = requestHideSelf(0)

    /** Своё «набираемое» слово мода (подчёркнутое, как у обычного набора): так моды делают свои способы ввода. */
    override fun scriptSetComposing(text: String) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.setComposingText(text, 1)
        expectUnknown()
    }

    override fun scriptFinishComposing() {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.finishComposingText()
        expectUnknown()
        schedulePredictions()
    }

    override fun scriptCursor(): List<Int> {
        val ic = currentInputConnection ?: return emptyList()
        val et = runCatching { ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull() ?: return emptyList()
        return listOf(et.startOffset + et.selectionStart, et.startOffset + et.selectionEnd)
    }

    override fun scriptAllText(): String {
        val ic = currentInputConnection ?: return ""
        val et = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 100_000 }, 0)
        }.getOrNull()
        et?.text?.let { return it.toString() }
        return scriptTextBefore(50_000) + scriptSelected() + scriptTextAfter(50_000)
    }

    override fun scriptReplaceSelection(text: String) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.commitText(text, 1)
        expectUnknown()
        schedulePredictions()
    }

    override fun scriptReplaceAll(text: String) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        expectUnknown()
        schedulePredictions()
    }

    /** Настоящее нажатие клавиши (с Ctrl, Alt, Shift, Meta): Ctrl+Z, Ctrl+B, F5, стрелки — что поймёт приложение. */
    override fun scriptSendKey(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        expectUnknown()
    }

    override fun scriptEditorAction(name: String) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        val action = when (name.lowercase()) {
            "search" -> EditorInfo.IME_ACTION_SEARCH
            "send" -> EditorInfo.IME_ACTION_SEND
            "go" -> EditorInfo.IME_ACTION_GO
            "next" -> EditorInfo.IME_ACTION_NEXT
            "previous" -> EditorInfo.IME_ACTION_PREVIOUS
            else -> EditorInfo.IME_ACTION_DONE
        }
        ic.performEditorAction(action)
    }

    /** Картинка от мода — как картинка из буфера: файлом в папке буфера и через commitContent. */
    override fun scriptCommitImage(bytes: ByteArray, mime: String): Boolean {
        val info = currentInputEditorInfo ?: return false
        val ic = currentInputConnection ?: return false
        if (EditorInfoCompat.getContentMimeTypes(info).none { ClipDescription.compareMimeTypes(mime, it) }) return false
        return runCatching {
            val dir = java.io.File(filesDir, "clips").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("mod_") && System.currentTimeMillis() - f.lastModified() > 3_600_000L }?.forEach { it.delete() }
            val ext = mime.substringAfter('/').substringBefore('+').ifEmpty { "png" }
            val f = java.io.File(dir, "mod_${System.currentTimeMillis()}.$ext")
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val content = InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mime)), null)
            InputConnectionCompat.commitContent(ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
    }

    /** Куда вернуться, когда поле ввода мода закроется: на панель мода или к клавиатуре. */
    private var promptBack = ActivePanel.KEYBOARD

    /** Поле ввода мода: клавиатура печатает в него, пока оно открыто. */
    override fun scriptPrompt(open: Boolean) {
        if (open) {
            if (kb.panel == ActivePanel.VOICE) voice.stop()
            if (kb.panel != ActivePanel.SCRIPT_INPUT) promptBack = kb.panel
            kb.symbolsPage = 0
            kb.panel = ActivePanel.SCRIPT_INPUT
            kb.toolbarOpen = false
            schedulePredictions()
        } else if (kb.panel == ActivePanel.SCRIPT_INPUT) {
            kb.panel = promptBack
        }
    }

    override fun scriptPromptDone(submit: Boolean) {
        // обработчик мог сразу открыть следующее поле (диалог в несколько шагов) — тогда остаёмся в нём
        val reopened = if (submit) ScriptHost.promptSubmit() else ScriptHost.promptClosed()
        if (!reopened && kb.panel == ActivePanel.SCRIPT_INPUT) kb.panel = promptBack
    }

    override fun commitGif(gif: TenorGifResult) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val supports = EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes(it, "image/gif") || ClipDescription.compareMimeTypes(it, "image/*")
        }
        if (!supports) {
            commitText("${gif.fullGifUrl} ")
            Toast.makeText(this, tr("Поле не принимает GIF — вставлена ссылка"), Toast.LENGTH_SHORT).show()
            return
        }
        serviceScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "gifs").apply { mkdirs() }
                    val file = File(dir, "gif_${gif.id}.gif")
                    if (!file.exists() || file.length() == 0L) {
                        URL(gif.fullGifUrl).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                    val uri = FileProvider.getUriForFile(this@ComposeKeyboardService, "$packageName.fileprovider", file)
                    val content = InputContentInfoCompat(uri, ClipDescription(gif.title, arrayOf("image/gif")), Uri.parse(gif.fullGifUrl))
                    uri to content
                }.getOrNull()
            }
            val committed = ok != null && InputConnectionCompat.commitContent(
                ic, info, ok.second, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
            )
            if (!committed) commitText("${gif.fullGifUrl} ")
        }
    }
}

/**
 * Невидимая поверхность в 1 px под окном клавиатуры. Когда клавиатура размывает приложение и при этом всё время
 * перерисовывается (анимированные моды), система (замечено на Android 17, Pixel) «запекает» картинку приложения
 * вместе с размытием в кэш и при вводе на кадр показывает этот старый кэш: набранный текст исчезает и появляется.
 * Так система делает, только если между приложением и размывающим окном нет других слоёв — этот слой и есть
 * «другой»: кэш строится обычным образом, без размытия, и мигание пропадает.
 */
private class BlurGuard(context: Context) : android.view.SurfaceView(context), android.view.SurfaceHolder.Callback {
    init {
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun paint(h: android.view.SurfaceHolder) {
        val c = runCatching { h.lockCanvas() }.getOrNull() ?: return
        try {
            // почти прозрачный пиксель: слой с пустым буфером система может не учитывать
            c.drawColor(AndroidColor.argb(1, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC)
        } finally {
            runCatching { h.unlockCanvasAndPost(c) }
        }
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) = paint(holder)
    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = paint(holder)
    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
}
