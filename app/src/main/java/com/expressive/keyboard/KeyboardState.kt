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
// СОСТОЯНИЕ КЛАВИАТУРЫ, ДЕЙСТВИЯ И ГОЛОСОВОЙ ВВОД
// ============================================================================

@Stable
class KeyboardState {
    var language by mutableStateOf(KeyboardLanguage.RU)
    var shift by mutableStateOf(ShiftState.OFF)
    var symbolsPage by mutableIntStateOf(0)
    /** Поле числа или телефона: вместо букв — цифровая клавиатура. */
    var numberPad by mutableStateOf(false)
    var phoneField by mutableStateOf(false)
    var panel by mutableStateOf(ActivePanel.KEYBOARD)
    /** Голосовая панель открыта удержанием микрофона — режим Rambler. */
    var rambler by mutableStateOf(false)
    var toolbarOpen by mutableStateOf(false)
    var suggestions by mutableStateOf(SuggestionChipModel.EMPTY_ROW)
    /** Варианты китайского/японского ввода: показываются лентой вместо трёх подсказок. */
    var candidates by mutableStateOf<List<String>>(emptyList())
    var enterAction by mutableIntStateOf(EditorInfo.IME_ACTION_UNSPECIFIED)
    var translatorInput by mutableStateOf("")
    /** Позиция курсора в набранном тексте переводчика: можно вернуться и исправить любое слово. */
    var translatorCursor by mutableIntStateOf(0)
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
    /** Открыть настройки и сразу показать системный запрос доступа к микрофону. */
    fun requestMicPermission() {}
    fun hideKeyboard()
    fun clearClipboard()
    fun insertTranslation()
    /** Поставить курсор в тексте переводчика (тап по слову) и обновить подсказки. */
    fun setTranslatorCursor(pos: Int) {}
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
    override fun requestMicPermission() = guard { a.requestMicPermission() }
    override fun hideKeyboard() = guard { a.hideKeyboard() }
    override fun clearClipboard() = guard { a.clearClipboard() }
    override fun insertTranslation() = guard { a.insertTranslation() }
    override fun setTranslatorCursor(pos: Int) = guard { a.setTranslatorCursor(pos) }
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

    /** Разрешение на момент последней проверки: панель перерисовывается, когда оно меняется. */
    var permissionGranted by mutableStateOf(hasPermission())
        private set

    /** Перечитать разрешение: его могли выдать в настройках, пока клавиатура была скрыта. */
    fun refreshPermission() {
        val granted = hasPermission()
        if (permissionGranted != granted) permissionGranted = granted
        if (granted && status == tr("Нужен доступ к микрофону")) status = tr("Нажмите на микрофон и говорите")
    }

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
        refreshPermission()
        if (!permissionGranted) {
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
internal const val CLIP_OFFER_MS = 5 * 60_000L
/** Сколько минимум показывается анимация улучшения текста. */
internal const val MIN_AI_ANIM_MS = 700L
