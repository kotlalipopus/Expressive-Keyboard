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
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.UserDictionary
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 6. СЛОВАРЬ, ПОДСКАЗКИ И АВТОИСПРАВЛЕНИЕ
// Частотные словари на ~50 000 слов (ru/en встроены, остальные скачиваются),
// поиск опечаток с учётом соседних клавиш и осторожные правила замены:
// слово из словаря или выученное (см. LEARNED) не исправляется.
// ============================================================================

/** Куда попал палец: смещение от центра нажатой клавиши в долях её ширины и высоты (-0.5…0.5). */
data class KeyTouch(val dx: Float, val dy: Float)

data class UndoAutoCorrectState(val originalWord: String, val committedText: String)

@Immutable
data class SuggestionChipModel(
    val displayText: String,
    val commitWord: String,
    val isPrimary: Boolean = false,
    val isKeepExact: Boolean = false,
    val isUndo: Boolean = false
) {
    val isEmpty: Boolean get() = commitWord.isEmpty() && !isUndo

    companion object {
        val EMPTY = SuggestionChipModel("", "")
        val EMPTY_ROW = listOf(EMPTY, EMPTY.copy(isPrimary = true), EMPTY)
    }
}

/**
 * [autoCorrection] — что заменит пробел (своё исправление, сведённое с системной проверкой),
 * [ownCorrection] — только своё: если системный ответ придёт позже, по пробелу их сводят заново.
 */
data class PredictionSnapshot(
    val chips: List<SuggestionChipModel>,
    val autoCorrection: String?,
    val forWord: String,
    val ownCorrection: String? = autoCorrection,
    val usedSystem: Boolean = false,
    /** Слово есть в словаре (или его подтвердила системная проверка). */
    val known: Boolean = false
)

/**
 * Редкие слова языка (формы слов, имена — ещё ~200 000): только «есть ли слово» и его место по частоте.
 * Хранятся компактно — по байту на букву, поиск двоичный: без них незнакомую форму слова («испытательного»)
 * исправление превращало в соседнюю знакомую («испытательном»).
 */
class RareWords(entries: List<Pair<String, Int>>, private val firstRank: Int) {
    private val codes = HashMap<Char, Int>()
    private val data: ByteArray
    private val starts: IntArray
    private val ranks: IntArray
    val size: Int get() = starts.size - 1

    init {
        val chars = HashSet<Char>()
        var total = 0
        for ((w, _) in entries) {
            total += w.length
            for (c in w) chars += c
        }
        // больше 254 разных букв в одном языке не бывает; если вдруг — лишние слова пропускаются
        chars.sorted().take(254).forEachIndexed { i, c -> codes[c] = i + 1 }
        val ok = entries.filter { (w, _) -> w.all { codes.containsKey(it) } }
        // файл уже отсортирован (по кодам символов) — сортируем, только если это не так
        val sorted = if ((1 until ok.size).all { compare(ok[it - 1].first, ok[it].first) <= 0 }) ok
            else ok.sortedWith { x, y -> compare(x.first, y.first) }
        data = ByteArray(total)
        starts = IntArray(sorted.size + 1)
        ranks = IntArray(sorted.size)
        var k = 0
        sorted.forEachIndexed { n, (w, r) ->
            starts[n] = k
            ranks[n] = r
            for (c in w) data[k++] = codes.getValue(c).toByte()
        }
        starts[sorted.size] = k
    }

    companion object {
        /** Строки «слово место36» (dict/<code>_more.txt). */
        fun parse(lines: List<String>): List<Pair<String, Int>> = lines.mapNotNull { l ->
            val sp = l.indexOf(' ')
            if (sp <= 0) null else l.substring(0, sp) to (l.substring(sp + 1).trim().toIntOrNull(36) ?: return@mapNotNull null)
        }
    }

    private fun compare(a: String, b: String): Int {
        val n = min(a.length, b.length)
        for (i in 0 until n) {
            val d = (codes[a[i]] ?: 0) - (codes[b[i]] ?: 0)
            if (d != 0) return d
        }
        return a.length - b.length
    }

    /** Сравнение слова номер n (в порядке сортировки) с w. */
    private fun compareAt(n: Int, w: String): Int {
        val s = starts[n]
        val len = starts[n + 1] - s
        val m = min(len, w.length)
        for (i in 0 until m) {
            val c = codes[w[i]] ?: return -1
            val d = (data[s + i].toInt() and 0xFF) - c
            if (d != 0) return d
        }
        return len - w.length
    }

    /** Место слова в частотном списке языка или -1. */
    fun rankOf(w: String): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, w)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return firstRank + ranks[mid]
            }
        }
        return -1
    }
}

/** Словарь одного языка: слова по убыванию частоты, редкие формы и частые ошибки написания. */
class LangDict(words: List<String>, rare: RareWords? = null, typos: Map<String, String> = emptyMap()) {
    /** Дочитываются после частых слов: подсказки работают сразу, не дожидаясь их. */
    @Volatile var rare: RareWords? = rare
    @Volatile var typos: Map<String, String> = typos
    val words: Array<String> = words.toTypedArray()
    val rank: HashMap<String, Int> = HashMap<String, Int>(words.size * 2).also { m -> words.forEachIndexed { i, w -> m.putIfAbsent(w, i) } }
    /** По алфавиту — для поиска продолжений по префиксу. */
    val sorted: Array<String> = this.words.copyOf().also { it.sort() }
    /**
     * Индекс опечаток SymSpell («симметричное удаление»): для 40 000 самых частых слов — хэши самого слова и
     * всех его вариантов без одной буквы. Похожие слова находятся парой десятков двоичных поисков вместо
     * перебора тысяч слов, и опечатка в первой букве ловится так же, как в остальных.
     * Элемент: (хэш << 32) | номер слова, массив отсортирован.
     */
    private val deletes: LongArray = run {
        val n = min(this.words.size, SYMSPELL_WORDS)
        var count = 0
        for (i in 0 until n) if (this.words[i].length <= SYMSPELL_MAX_LEN) count += this.words[i].length + 1
        val out = LongArray(count)
        var k = 0
        for (i in 0 until n) {
            val w = this.words[i]
            if (w.length > SYMSPELL_MAX_LEN) continue
            out[k++] = pack(hashSkipping(w, -1, -1), i)
            for (j in w.indices) out[k++] = pack(hashSkipping(w, j, -1), i)
        }
        out.sort()
        out
    }

    /** Номера слов, похожих на [lower]: совпадают после удаления не более одной буквы с каждой стороны или двух у набранного. */
    fun similar(lower: String, out: MutableSet<Int>) {
        val n = lower.length
        if (n == 0 || n > SYMSPELL_MAX_LEN + 2) return
        lookup(hashSkipping(lower, -1, -1), out)
        for (a in 0 until n) {
            lookup(hashSkipping(lower, a, -1), out)
            // две лишние/ошибочные буквы — только в длинных словах, в коротких слишком много ложных совпадений
            if (n >= 5) for (b in a + 1 until n) lookup(hashSkipping(lower, a, b), out)
        }
    }

    /** Только совпадение и одна лишняя/ошибочная буква с каждой стороны — для вариантов набранного (дёшево). */
    fun similarNear(lower: String, out: MutableSet<Int>) {
        val n = lower.length
        if (n == 0 || n > SYMSPELL_MAX_LEN + 2) return
        lookup(hashSkipping(lower, -1, -1), out)
        for (a in 0 until n) lookup(hashSkipping(lower, a, -1), out)
    }

    private fun lookup(hash: Int, out: MutableSet<Int>) {
        val key = hash.toLong() shl 32
        var lo = 0
        var hi = deletes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (deletes[mid] < key) lo = mid + 1 else hi = mid
        }
        while (lo < deletes.size && (deletes[lo] ushr 32).toInt() == hash) {
            out += (deletes[lo] and 0xFFFFFFFFL).toInt()
            lo++
        }
    }

    val size: Int get() = words.size

    /** Место слова по частоте среди частых и редких; -1 — слова нет. */
    fun rankOf(w: String): Int = rank[w] ?: rare?.rankOf(w) ?: -1

    companion object {
        private const val SYMSPELL_WORDS = 40_000
        private const val SYMSPELL_MAX_LEN = 20

        private fun pack(hash: Int, index: Int): Long = (hash.toLong() shl 32) or index.toLong()

        /** String.hashCode() строки без символов a и b — без создания самой строки. */
        private fun hashSkipping(s: String, a: Int, b: Int): Int {
            var h = 0
            for (i in s.indices) if (i != a && i != b) h = 31 * h + s[i].code
            return h
        }
    }
}

object Dictionaries {
    private const val DIR = "dicts"
    private const val BASE = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
    private const val LIMIT = 50_000

    /** code → "загрузка…" / текст ошибки; готовые словари определяются по файлу. */
    var status by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var version by mutableIntStateOf(0)
        private set
    private val tried = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var bundledList: Set<String>? = null

    fun bundled(context: Context, code: String): Boolean {
        val list = bundledList ?: runCatching { context.assets.list("dict")?.toSet() }.getOrNull().orEmpty().also { bundledList = it }
        return "$code.txt" in list
    }

    fun file(context: Context, code: String) = File(File(context.applicationContext.filesDir, DIR), "$code.txt")

    fun isInstalled(context: Context, code: String) = bundled(context, code) || file(context, code).let { it.exists() && it.length() > 0 }

    fun read(context: Context, code: String): List<String>? = runCatching {
        val f = file(context, code)
        when {
            f.exists() && f.length() > 0 -> f.readLines()
            bundled(context, code) -> context.assets.open("dict/$code.txt").bufferedReader().use { it.readLines() }
            else -> null
        }
    }.getOrNull()

    /** Встроенные в приложение редкие слова языка (dict/<code>_more.txt) — только для ru и en. */
    fun readRare(context: Context, code: String): List<Pair<String, Int>>? = runCatching {
        if (!bundled(context, code + "_more")) null
        else RareWords.parse(context.assets.open("dict/${code}_more.txt").bufferedReader().use { it.readLines() })
    }.getOrNull()

    /** Частые ошибки написания «ошибка правильно» (dict/<code>_typos.txt). */
    fun readTypos(context: Context, code: String): Map<String, String> = runCatching {
        if (!bundled(context, code + "_typos")) emptyMap()
        else context.assets.open("dict/${code}_typos.txt").bufferedReader().use { r ->
            r.readLines().mapNotNull { l -> l.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }
    }.getOrDefault(emptyMap())

    private val WORD = Regex("^\\p{L}[\\p{L}\\p{M}'\\-]*$")

    /** Скачивает частотный список (OpenSubtitles, FrequencyWords) и оставляет 50 000 настоящих слов. */
    suspend fun download(context: Context, code: String): Result<Int> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        status = status + (code to "загрузка…")
        val r = runCatching {
            var lastError: Throwable? = null
            for (suffix in listOf("50k", "full")) {
                val res = runCatching {
                    val conn = (URL("$BASE/$code/${code}_$suffix.txt").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                    }
                    try {
                        if (conn.responseCode != 200) throw IllegalStateException("сервер ответил ${conn.responseCode}")
                        val out = LinkedHashSet<String>()
                        conn.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (out.size >= LIMIT) break
                                val w = line.trim().substringBefore(' ').lowercase()
                                if (w.isNotEmpty() && WORD.matches(w) && !w.endsWith("'") && !w.endsWith("-")) out += w
                            }
                        }
                        require(out.size >= 500) { "слишком маленький словарь" }
                        out
                    } finally {
                        conn.disconnect()
                    }
                }
                val words = res.getOrElse { lastError = it; null } ?: continue
                val f = file(app, code)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "$code.tmp")
                tmp.writeText(words.joinToString("\n"))
                // при обновлении словаря старый файл мешает переименованию на части файловых систем
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) throw IllegalStateException("не удалось сохранить словарь")
                }
                return@runCatching words.size
            }
            throw lastError ?: IllegalStateException("словарь не найден")
        }
        status = if (r.isSuccess) status - code else status + (code to (r.exceptionOrNull()?.message ?: "ошибка"))
        withContext(Dispatchers.Main) { version++ }
        WordPredictorEngine.invalidate(code)
        r
    }

    /** Один раз за запуск: если словаря языка нет — скачать в фоне. */
    fun ensureAsync(context: Context, lang: KeyboardLanguage) {
        val code = lang.dict ?: return
        if (isInstalled(context, code) || !tried.add(code)) return
        if (!KeyboardPrefs.current(context).dictAutoDownload) return
        CoroutineScope(Dispatchers.IO + BackgroundErrors).launch { download(context, code) }
    }

    fun delete(context: Context, code: String) {
        file(context, code).delete()
        version++
        WordPredictorEngine.invalidate(code)
    }
}

/**
 * Системная проверка правописания (TextServicesManager). На большинстве телефонов это проверка Gboard
 * или Samsung — та же модель опечаток и тот же словарь, что у их клавиатур. Слово отправляется на каждое
 * нажатие, ответ приходит за 5–30 мс в своём потоке, так что к пробелу он обычно уже готов.
 */
object SystemSpell {
    class Result(val inDictionary: Boolean, val typo: Boolean, val recommended: Boolean, val suggestions: List<String>) {
        val hasOpinion: Boolean get() = inDictionary || typo
    }

    private val NONE = Result(false, false, false, emptyList())
    private const val ATTRS = SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY or
        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS

    private class Pending(val latch: CountDownLatch, val at: Long)

    private val handler: Handler by lazy { Handler(HandlerThread("system-spell").also { it.start() }.looper) }
    private val main: Handler by lazy { Handler(android.os.Looper.getMainLooper()) }
    private val cache = object : LinkedHashMap<String, Result>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 500
    }
    private val pending = ConcurrentHashMap<String, Pending>()

    // только в потоке handler
    private var session: SpellCheckerSession? = null
    private var sessionLocale = ""
    private val failedAt = HashMap<String, Long>()
    private val queue = ArrayDeque<Pair<Int, String>>()
    private var seq = 0

    /** Пришёл ответ для слова — вызывается в главном потоке. */
    @Volatile var onResult: ((String) -> Unit)? = null

    private fun key(locale: String, word: String) = "$locale|$word"

    fun cached(lang: KeyboardLanguage, word: String): Result? =
        synchronized(cache) { cache[key(lang.locale, word)] }?.takeIf { it !== NONE }

    private val debounceToken = Any()
    /** Запрос, отложенный до паузы в наборе: (ключ, задача). */
    private var queued: Pair<String, Runnable>? = null

    /**
     * debounceMs > 0 — отправить, только если за это время не нажали следующую букву: проверка живёт в чужом
     * процессе (Gboard, Samsung), и запрос на каждую букву при быстром наборе грел телефон впустую.
     */
    fun request(context: Context, lang: KeyboardLanguage, word: String, debounceMs: Long = 0L) {
        if (word.length < 2 || word.length > 40 || !word.all(WordPredictorEngine::isWordChar)) return
        val k = key(lang.locale, word)
        if (synchronized(cache) { cache.containsKey(k) }) return
        val now = SystemClock.uptimeMillis()
        val p = pending[k]
        if (p != null && now - p.at < 1500L) return
        pending[k] = Pending(CountDownLatch(1), now)
        val app = context.applicationContext
        lateinit var task: Runnable
        task = Runnable {
            synchronized(debounceToken) { if (queued?.second === task) queued = null }
            runCatching { send(app, lang.locale, word, k) }.onFailure { finish(k, null) }
        }
        if (debounceMs <= 0L) {
            handler.post(task)
            return
        }
        synchronized(debounceToken) {
            // прошлая буква ещё не ушла в проверку — она уже не нужна
            queued?.let { (oldKey, oldTask) ->
                handler.removeCallbacks(oldTask, debounceToken)
                pending.remove(oldKey)?.latch?.countDown()
            }
            queued = k to task
            handler.postAtTime(task, debounceToken, SystemClock.uptimeMillis() + debounceMs)
        }
    }

    /** Отложенный запрос — сейчас же (пробел: ответ нужен немедленно). */
    fun flush() {
        val t = synchronized(debounceToken) { queued?.second } ?: return
        handler.removeCallbacks(t, debounceToken)
        handler.postAtFrontOfQueue(t)
    }

    /** Ответ для слова; если запрос ещё в пути — ждёт его не дольше timeoutMs (по пробелу, обычно 0 мс). */
    fun await(lang: KeyboardLanguage, word: String, timeoutMs: Long): Result? {
        cached(lang, word)?.let { return it }
        val p = pending[key(lang.locale, word)] ?: return null
        flush()
        runCatching { p.latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return cached(lang, word)
    }

    fun close() = handler.post {
        runCatching { session?.close() }
        session = null
        sessionLocale = ""
        while (queue.isNotEmpty()) finish(queue.removeFirst().second, null)
    }

    /** Название включённой проверки правописания (Android 12+), null — неизвестно или выключена. */
    fun currentName(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT < 31) return null
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        if (!tsm.isSpellCheckerEnabled) return null
        tsm.currentSpellCheckerInfo?.loadLabel(context.packageManager)?.toString()
    }.getOrNull()

    private fun finish(k: String, r: Result?) {
        synchronized(cache) { cache[k] = r ?: NONE }
        pending.remove(k)?.latch?.countDown()
        if (r != null && r.hasOpinion) {
            val word = k.substringAfter('|')
            main.post { onResult?.invoke(word) }
        }
    }

    private fun sessionFor(app: Context, locale: String): SpellCheckerSession? {
        session?.let { if (sessionLocale == locale) return it }
        runCatching { session?.close() }
        session = null
        sessionLocale = ""
        failedAt[locale]?.let { if (SystemClock.uptimeMillis() - it < 30_000L) return null }
        val tsm = app.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        val loc = Locale.forLanguageTag(locale)
        val s = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val params = SpellCheckerSession.SpellCheckerSessionParams.Builder()
                    .setLocale(loc)
                    .setShouldReferToSpellCheckerLanguageSettings(false)
                    .setSupportedAttributes(ATTRS)
                    .build()
                tsm.newSpellCheckerSession(params, { handler.post(it) }, listener)
            } else {
                // ответы придут в поток, где создана сессия, — то есть сюда
                @Suppress("DEPRECATION")
                tsm.newSpellCheckerSession(null, loc, listener, false)
            }
        }.getOrNull()
        if (s == null) failedAt[locale] = SystemClock.uptimeMillis() else failedAt.remove(locale)
        session = s
        sessionLocale = if (s != null) locale else ""
        return s
    }

    private fun send(app: Context, locale: String, word: String, k: String) {
        val s = sessionFor(app, locale)
        if (s == null) {
            finish(k, null)
            return
        }
        val id = ++seq
        queue.addLast(id to k)
        // ответы потерялись (сервис перезапустился) — не копим очередь
        while (queue.size > 16) finish(queue.removeFirst().second, null)
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(word, id, id)), 5) }.onFailure {
            queue.removeAll { it.first == id }
            finish(k, null)
        }
    }

    private fun deliver(info: SuggestionsInfo?) {
        if (queue.isEmpty()) return
        val idx = info?.let { i -> queue.indexOfFirst { it.first == i.sequence } }?.takeIf { it >= 0 } ?: 0
        // всё, что было до этого ответа, уже не придёт
        repeat(idx) { finish(queue.removeFirst().second, null) }
        val k = queue.removeFirst().second
        if (info == null) {
            finish(k, null)
            return
        }
        val a = info.suggestionsAttributes
        val word = k.substringAfter('|')
        val list = (0 until max(0, info.suggestionsCount))
            .mapNotNull { info.getSuggestionAt(it)?.trim() }
            .filter { it.isNotEmpty() && it != word }
            .distinct()
        finish(
            k, Result(
                inDictionary = a and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0,
                typo = a and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0,
                recommended = a and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
                suggestions = list
            )
        )
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        // ответы приходят в фоновом потоке: исключение здесь уронило бы всю клавиатуру
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            runCatching { results?.forEach { deliver(it) } }
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            runCatching { sentences(results) }
        }

        private fun sentences(results: Array<out SentenceSuggestionsInfo>?) {
            // пустой ответ — тоже ответ, иначе пробел ждал бы его до таймаута
            if (results.isNullOrEmpty()) return deliver(null)
            for (s in results) {
                if (s == null || s.suggestionsCount <= 0) {
                    deliver(null)
                    continue
                }
                // отправляем по одному слову, поэтому берём часть, покрывающую слово с начала (или первую)
                val i = (0 until s.suggestionsCount).firstOrNull { s.getOffsetAt(it) == 0 } ?: 0
                deliver(s.getSuggestionsInfoAt(i))
            }
        }
    }
}

object WordPredictorEngine {
    const val DATA_PREFS = "expressive_keyboard_v8_data"
    private const val KEY_WORDS = "user_words"
    private const val KEY_BIGRAMS = "user_bigrams"

    /** "ru:слово" → сколько раз набрано (см. LEARNED). */
    private val userWords = ConcurrentHashMap<String, Int>()
    private val userBigrams = ConcurrentHashMap<String, Int>()
    private val dicts = ConcurrentHashMap<String, LangDict>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    /** Слова, добавленные плагинами: язык → слова. */
    @Volatile var pluginWords: Map<String, List<String>> = emptyMap()
        set(value) {
            field = value
            dicts.clear()
        }
    @Volatile private var started = false
    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)
    private var saveJob: Job? = null
    private val glideCache = ConcurrentHashMap<String, List<Pair<String, Float>>>()

    /** Система просит память, а клавиатура скрыта: сбрасываем то, что пересчитается само. */
    fun trimMemory() {
        glideCache.clear()
    }
    /** Вызывается в главном потоке, когда словарь загрузился — клавиатура обновляет подсказки. */
    @Volatile var onDictionaryLoaded: (() -> Unit)? = null

    private const val RU_SEED =
        "я ты он она оно мы вы они меня тебя его её нас вас их мне тебе ему ей нам вам им мной тобой " +
        "привет здравствуйте спасибо пожалуйста извини прости хорошо отлично супер класс круто ясно понятно ладно окей ок давай " +
        "сегодня завтра вчера сейчас потом скоро уже ещё еще всегда никогда иногда " +
        "приветик спасибки нормалек погнали увидимся созвонимся спишемся"

    private const val EN_SEED =
        "hello hi hey thanks thank please sorry yes yeah okay ok sure maybe today tomorrow tonight " +
        "dont doesnt didnt isnt arent wont cant im youre"

    /** С чего обычно начинают сообщение — пока ничего не набрано. */
    private val SENTENCE_STARTERS = mapOf(
        "ru" to listOf("я", "привет", "да"),
        "uk" to listOf("я", "привіт", "так"),
        "en" to listOf("i", "hi", "the"),
        "de" to listOf("ich", "hallo", "ja"),
        "es" to listOf("hola", "yo", "sí"),
        "fr" to listOf("je", "salut", "oui")
    )

    private val ruBigrams: Map<String, List<String>> = mapOf(
        "привет" to listOf("как", "что", "дела"),
        "здравствуйте" to listOf("как", "подскажите", "я"),
        "как" to listOf("дела", "ты", "жизнь"),
        "дела" to listOf("хорошо", "отлично", "у"),
        "что" to listOf("делаешь", "нового", "случилось"),
        "доброе" to listOf("утро", "время", "здоровье"),
        "добрый" to listOf("день", "вечер", "человек"),
        "спокойной" to listOf("ночи", "дороги", "смены"),
        "спасибо" to listOf("большое", "тебе", "за"),
        "большое" to listOf("спасибо", "тебе", "дело"),
        "пожалуйста" to listOf("не", "помоги", "скажи"),
        "я" to listOf("не", "сейчас", "люблю"),
        "ты" to listOf("где", "как", "сегодня"),
        "мы" to listOf("сегодня", "уже", "будем"),
        "не" to listOf("знаю", "могу", "хочу"),
        "очень" to listOf("хорошо", "круто", "вкусно"),
        "давай" to listOf("завтра", "сегодня", "потом"),
        "хорошего" to listOf("дня", "вечера", "настроения"),
        "у" to listOf("меня", "тебя", "нас"),
        "меня" to listOf("все", "нет", "есть"),
        "тебя" to listOf("люблю", "как", "жду"),
        "мне" to listOf("кажется", "нужно", "нравится"),
        "это" to listOf("очень", "просто", "правда"),
        "все" to listOf("хорошо", "отлично", "будет"),
        "всё" to listOf("хорошо", "отлично", "понятно"),
        // частые продолжения в переписке: подсказки следующего слова осмысленны сразу, ещё до обучения
        "где" to listOf("ты", "вы", "мы"),
        "когда" to listOf("будешь", "ты", "придёшь"),
        "почему" to listOf("ты", "не", "так"),
        "сколько" to listOf("стоит", "времени", "можно"),
        "можно" to listOf("я", "мне", "ли"),
        "нужно" to listOf("будет", "было", "что"),
        "надо" to listOf("будет", "было", "подумать"),
        "хочу" to listOf("спать", "есть", "тебя"),
        "буду" to listOf("дома", "через", "ждать"),
        "будешь" to listOf("дома", "завтра", "сегодня"),
        "иду" to listOf("домой", "спать", "гулять"),
        "пойдём" to listOf("гулять", "домой", "завтра"),
        "скоро" to listOf("буду", "приду", "увидимся"),
        "уже" to listOf("иду", "дома", "еду"),
        "еду" to listOf("домой", "на", "в"),
        "сейчас" to listOf("приду", "буду", "занят"),
        "потом" to listOf("напишу", "позвоню", "расскажу"),
        "напиши" to listOf("мне", "когда", "как"),
        "позвони" to listOf("мне", "когда", "как"),
        "перезвоню" to listOf("позже", "через", "тебе"),
        "увидимся" to listOf("завтра", "скоро", "вечером"),
        "доброй" to listOf("ночи", "ночки"),
        "приятного" to listOf("аппетита", "вечера", "дня"),
        "с" to listOf("днём", "праздником", "тобой"),
        "в" to listOf("общем", "смысле", "итоге"),
        "на" to listOf("работе", "связи", "улице"),
        "по" to listOf("дороге", "поводу", "работе"),
        "до" to listOf("завтра", "встречи", "свидания"),
        "без" to listOf("проблем", "понятия", "разницы"),
        "так" to listOf("и", "что", "же"),
        "ну" to listOf("да", "ладно", "вот"),
        "да" to listOf("конечно", "нет", "давай"),
        "нет" to listOf("не", "проблем", "спасибо"),
        "ладно" to listOf("давай", "хорошо", "пока"),
        "хорошо" to listOf("давай", "спасибо", "что"),
        "понятно" to listOf("спасибо", "а", "хорошо"),
        "конечно" to listOf("да", "можно", "давай"),
        "извини" to listOf("пожалуйста", "что", "не"),
        "прости" to listOf("пожалуйста", "меня", "что"),
        "люблю" to listOf("тебя", "вас", "очень"),
        "скучаю" to listOf("по", "очень", "тебе"),
        "жду" to listOf("тебя", "ответа", "встречи"),
        "думаю" to listOf("что", "о", "да"),
        "знаю" to listOf("что", "как", "это"),
        "могу" to listOf("я", "ли", "сегодня"),
        "был" to listOf("на", "в", "дома"),
        "была" to listOf("на", "в", "дома"),
        "этот" to listOf("раз", "день", "момент"),
        "эта" to listOf("неделя", "тема", "песня"),
        "сегодня" to listOf("вечером", "не", "буду"),
        "завтра" to listOf("утром", "вечером", "буду"),
        "вчера" to listOf("вечером", "был", "была"),
        "вы" to listOf("где", "когда", "можете"),
        "они" to listOf("уже", "не", "сказали"),
        "он" to listOf("сказал", "не", "уже"),
        "она" to listOf("сказала", "не", "уже"),
        "тебе" to listOf("нравится", "понравится", "удобно"),
        "вам" to listOf("удобно", "нужно", "спасибо"),
        "мой" to listOf("друг", "номер", "адрес"),
        "моя" to listOf("мама", "подруга", "работа"),
        "через" to listOf("час", "минут", "пять"),
        "минут" to listOf("через", "пять", "десять")
    )

    private val enBigrams: Map<String, List<String>> = mapOf(
        "hello" to listOf("how", "there", "everyone"),
        "hi" to listOf("how", "there", "guys"),
        "how" to listOf("are", "is", "about"),
        "are" to listOf("you", "we", "they"),
        "you" to listOf("are", "can", "know"),
        "thank" to listOf("you", "goodness", "so"),
        "thanks" to listOf("for", "a", "so"),
        "i" to listOf("am", "have", "think"),
        "what" to listOf("is", "are", "about"),
        "good" to listOf("morning", "night", "idea"),
        "see" to listOf("you", "what", "if")
    )

    private val ruTypos = mapOf(
        "привкт" to "привет", "првиет" to "привет", "привте" to "привет", "пирвет" to "привет",
        "превет" to "привет", "кка" to "как", "аак" to "как", "длеа" to "дела",
        "спсибо" to "спасибо", "спаисбо" to "спасибо", "сапсибо" to "спасибо", "пожалуста" to "пожалуйста",
        "пожалуйтса" to "пожалуйста", "хоршо" to "хорошо", "хрошо" to "хорошо", "хорощо" to "хорошо",
        "сегодян" to "сегодня", "сегдня" to "сегодня", "сйечас" to "сейчас", "сейчса" to "сейчас",
        "потмо" to "потом", "чот" to "что", "чьо" to "что", "жто" to "это", "наврено" to "наверное",
        "кнечно" to "конечно", "коненчо" to "конечно", "любюл" to "люблю", "тбея" to "тебя"
    )

    private val enTypos = mapOf(
        "teh" to "the", "hte" to "the", "adn" to "and", "nad" to "and", "taht" to "that",
        "tihs" to "this", "waht" to "what", "hwat" to "what", "hwo" to "how", "yuo" to "you",
        "uoy" to "you", "thnak" to "thank", "thnaks" to "thanks", "plaese" to "please",
        "plesae" to "please", "becuase" to "because", "awsome" to "awesome",
        "tomorow" to "tomorrow", "freind" to "friend", "recieve" to "receive", "dont" to "don't",
        "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't", "cant" to "can't", "wont" to "won't", "im" to "I'm"
    )

    private fun isCyrillic(c: Char): Boolean = c in 'а'..'я' || c == 'ё'

    /** Вариант системной проверки на алфавите этого языка: английские варианты к русскому слову не нужны. */
    private fun sameScript(lang: KeyboardLanguage, w: String): Boolean {
        val c = w.firstOrNull { it.isLetter() }?.lowercaseChar() ?: return false
        return lang.keyPositions.containsKey(c) || lang.keyPositions.containsKey(baseLetter(c))
    }

    fun ensureInitialized(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        scope.launch {
            runCatching {
                val data = app.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE)
                var wordsRaw = data.getString(KEY_WORDS, null)
                var bigramsRaw = data.getString(KEY_BIGRAMS, null)
                if (wordsRaw == null) {
                    // перенос словаря из v7, где он лежал в файле настроек
                    val legacy = KeyboardPrefs.prefs(app)
                    wordsRaw = legacy.getString("user_learned_words_v7", null)
                    bigramsRaw = legacy.getString("user_learned_bigrams_v7", null)
                    if (wordsRaw != null) legacy.edit().remove("user_learned_words_v7").remove("user_learned_bigrams_v7").apply()
                }
                parseInto(wordsRaw, userWords)
                parseInto(bigramsRaw, userBigrams)
            }
            // словари только включённых языков: лишний словарь — это память и полсекунды работы процессора при каждом запуске
            val enabled = runCatching { Languages.enabled(KeyboardPrefs.current(app)) }.getOrDefault(listOf(KeyboardLanguage.RU, KeyboardLanguage.EN))
            enabled.forEach { prepare(app, it) }
        }
    }

    /** Личный словарь Android ("ru:слово", "*:слово" — для всех языков): такие слова никогда не исправляются. */
    @Volatile private var personal: Set<String> = emptySet()
    /** Сокращения из личного словаря: "омв" → "on my way". */
    @Volatile var personalShortcuts: Map<String, String> = emptyMap()
        private set
    @Volatile private var personalLoadedAt = 0L

    /**
     * Читает личный словарь Android (Настройки → Язык → Личный словарь). Доступен только активной клавиатуре,
     * поэтому вызывается из сервиса, не чаще раза в минуту.
     */
    fun loadPersonalDictionary(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (personalLoadedAt != 0L && now - personalLoadedAt < 60_000L) return
        personalLoadedAt = now
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val words = HashSet<String>()
                val shortcuts = HashMap<String, String>()
                app.contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE, UserDictionary.Words.SHORTCUT),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext() && words.size < 20_000) {
                        val w = c.getString(0)?.trim().orEmpty()
                        if (w.isEmpty()) continue
                        val lang = c.getString(1)?.substringBefore('_')?.substringBefore('-')?.lowercase().orEmpty().ifEmpty { "*" }
                        words += "$lang:${w.lowercase()}"
                        val sc = c.getString(2)?.trim()?.lowercase()
                        if (!sc.isNullOrEmpty()) shortcuts[sc] = w
                    }
                }
                personal = words
                personalShortcuts = shortcuts
            }
        }
    }

    private fun isPersonal(lang: KeyboardLanguage, lower: String): Boolean {
        val p = personal
        return p.isNotEmpty() && ("*:$lower" in p || "${lang.id}:$lower" in p || "${lang.locale.substringBefore('-')}:$lower" in p)
    }

    /** Старые ключи без языка ("слово") получают язык по алфавиту. */
    private fun parseInto(raw: String?, target: ConcurrentHashMap<String, Int>) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val key = if (':' in k) k else (if (k.firstOrNull()?.let(::isCyrillic) == true) "ru:" else "en:") + k
                target[key] = json.optInt(k, 1)
            }
        }
    }

    /** Загружает словарь языка в фоне (если ещё нет). */
    fun prepare(context: Context, lang: KeyboardLanguage) {
        val app = context.applicationContext
        appContext = appContext ?: app
        val code = lang.dict ?: lang.id
        if (dicts.containsKey(code) || !loading.add(code)) return
        scope.launch {
            try {
                val words = LinkedHashSet<String>()
                Dictionaries.read(app, code)?.let { list -> list.forEach { if (it.isNotBlank()) words += it.trim() } }
                when (code) {
                    "ru" -> RU_SEED.split(' ').forEach { if (it.isNotBlank()) words += it }
                    "en" -> EN_SEED.split(' ').forEach { if (it.isNotBlank()) words += it }
                }
                pluginWords[lang.id]?.let { words += it.map { w -> w.lowercase() } }
                val main = words.toList()
                // сначала частые слова — подсказки работают сразу; редкие формы дочитываются следом
                val dict = LangDict(main)
                dicts[code] = dict
                glideCache.remove(code)
                withContext(Dispatchers.Main) { onDictionaryLoaded?.invoke() }
                dict.typos = Dictionaries.readTypos(app, code)
                dict.rare = Dictionaries.readRare(app, code)?.let { RareWords(it, main.size) }
            } finally {
                loading.remove(code)
            }
        }
        if (lang.dict != null) Dictionaries.ensureAsync(app, lang)
    }

    /** Словарь без чтения файлов — для проверки качества исправлений на компьютере (app/src/test). */
    internal fun installDictionary(
        lang: KeyboardLanguage,
        words: List<String>,
        rare: List<Pair<String, Int>> = emptyList(),
        typos: Map<String, String> = emptyMap()
    ) {
        dicts[lang.dict ?: lang.id] = LangDict(words, rare.takeIf { it.isNotEmpty() }?.let { RareWords(it, words.size) }, typos)
        glideCache.remove(lang.dict ?: lang.id)
    }

    fun invalidate(code: String) {
        dicts.remove(code)
        glideCache.remove(code)
        appContext?.let { ctx -> Languages.all.filter { (it.dict ?: it.id) == code }.forEach { prepare(ctx, it) } }
    }

    private fun dictFor(lang: KeyboardLanguage): LangDict? {
        val d = dicts[lang.dict ?: lang.id]
        if (d == null) appContext?.let { prepare(it, lang) }
        return d
    }

    /** Есть ли полноценный словарь (а не только пара сотен слов-затравок). */
    fun hasDictionary(lang: KeyboardLanguage): Boolean = (dicts[lang.dict ?: lang.id]?.size ?: 0) >= 3000

    private fun uKey(lang: KeyboardLanguage, w: String) = "${lang.id}:$w"

    /** Буква, знак огласовки (хинди, тайский) или апостроф/дефис внутри слова. */
    fun isWordChar(c: Char): Boolean {
        if (c.isLetter() || c == '\'' || c == '-') return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun learn(lang: KeyboardLanguage, previous: String, word: String, boost: Int = 1) {
        val w = word.trim().lowercase()
        if (w.length < 2 || !w.all(::isWordChar)) return
        val k = uKey(lang, w)
        userWords[k] = (userWords[k] ?: 0) + boost
        val p = previous.trim().lowercase()
        if (p.isNotEmpty() && p.all(::isWordChar)) {
            val key = "${lang.id}:$p|$w"
            userBigrams[key] = (userBigrams[key] ?: 0) + 1
        }
        scheduleSave(3000L)
    }

    /** Забыть слово (долгое нажатие на подсказку). */
    fun forget(lang: KeyboardLanguage, word: String) {
        val w = word.trim().lowercase()
        userWords.remove(uKey(lang, w))
        val suffix = "|$w"
        userBigrams.keys.filter { it.startsWith("${lang.id}:") && it.endsWith(suffix) }.forEach { userBigrams.remove(it) }
        scheduleSave(500L)
    }

    fun flush() = scheduleSave(0L)

    fun clearUserData() {
        userWords.clear()
        userBigrams.clear()
        glideCache.clear()
        scheduleSave(0L)
    }

    private fun scheduleSave(delayMs: Long) {
        val app = appContext ?: return
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                runCatching {
                    val wj = JSONObject()
                    userWords.entries.sortedByDescending { it.value }.take(6000).forEach { wj.put(it.key, it.value) }
                    val bj = JSONObject()
                    userBigrams.entries.sortedByDescending { it.value }.take(6000).forEach { bj.put(it.key, it.value) }
                    app.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_WORDS, wj.toString())
                        .putString(KEY_BIGRAMS, bj.toString())
                        .apply()
                }
            }
        }
    }

    private fun userCount(lang: KeyboardLanguage, lower: String) = userWords[uKey(lang, lower)] ?: 0

    /**
     * Незнакомое слово становится «своим» (не исправляется и подсказывается), когда набрано 3 раза
     * или его явно оставили, выбрав «как есть» в подсказках.
     */
    private const val LEARNED = 3
    /**
     * Похожее на опечатку частого слова («привит» рядом с «привет») становится своим только после стольких наборов:
     * раньше одной отмены исправления ⌫ хватало, чтобы опечатка навсегда перестала исправляться.
     */
    const val LEARNED_TYPO = 8
    private val typoLike = ConcurrentHashMap<String, Boolean>()

    /** Слово выучено: набрано достаточно раз (опечатке частого слова — заметно больше). */
    private fun learned(lang: KeyboardLanguage, lower: String): Boolean {
        val n = userCount(lang, lower)
        if (n < LEARNED) return false
        return n >= LEARNED_TYPO || !looksLikeTypo(lang, lower)
    }

    /** В одной правке (другая буква, лишняя/пропущенная буква, перестановка) от частого слова словаря. */
    private fun looksLikeTypo(lang: KeyboardLanguage, lower: String): Boolean = typoLike.getOrPut(uKey(lang, lower)) {
        val d = dictFor(lang) ?: return@getOrPut false
        if (lower.length < 3 || d.rankOf(lower) in 0 until 20_000) return@getOrPut false
        fun frequent(w: String) = d.rank[w]?.let { it < 15_000 } == true
        val letters = lang.keyPositions.keys.filter { it.isLetter() && it.isLowerCase() }
        val sb = StringBuilder(lower)
        for (i in lower.indices) {
            // лишняя буква
            if (frequent(lower.removeRange(i, i + 1))) return@getOrPut true
            // другая буква: соседняя клавиша или созвучная («привит» — «привет»)
            for (c in letters) {
                if (c == lower[i]) continue
                sb.setCharAt(i, c)
                if (frequent(sb.toString())) return@getOrPut true
            }
            sb.setCharAt(i, lower[i])
            if (i + 1 < lower.length) {
                val sw = lower.substring(0, i) + lower[i + 1] + lower[i] + lower.substring(i + 2)
                if (frequent(sw)) return@getOrPut true
            }
        }
        // пропущенная буква
        for (i in 0..lower.length) for (c in letters) {
            if (frequent(lower.substring(0, i) + c + lower.substring(i))) return@getOrPut true
        }
        false
    }

    /** Словарь для печати свайпом: слово → штраф за редкость (раньше в словаре и чаще набрано — меньше). */
    fun glideLexicon(lang: KeyboardLanguage): List<Pair<String, Float>> {
        val code = lang.dict ?: lang.id
        val base = glideCache[code] ?: run {
            val d = dictFor(lang)
            val list = ArrayList<Pair<String, Float>>()
            if (d != null) {
                for (i in 0 until min(d.size, 20_000)) {
                    val w = d.words[i]
                    if (w.length >= 2 && w.all { it.isLetter() }) list += w to kotlin.math.ln(1f + i / 20f)
                }
            }
            if (d != null) glideCache[code] = list
            list
        }
        val prefix = "${lang.id}:"
        val out = LinkedHashMap<String, Float>(base.size + 64)
        for ((w, p) in base) out[w] = p
        for ((k, n) in userWords) {
            if (!k.startsWith(prefix)) continue
            val w = k.substring(prefix.length)
            if (w.length < 2 || !w.all { it.isLetter() } || (n < LEARNED && w !in out)) continue
            // выученное слово — как одно из пары сотен самых частых, и тем частотнее, чем чаще набрано
            val pen = max(0f, 3f - kotlin.math.ln(1f + n))
            out[w] = min(out[w] ?: Float.MAX_VALUE, pen)
        }
        return out.toList()
    }

    /** Слово знакомо: есть в словаре любого загруженного языка с тем же алфавитом или набрано вами хотя бы дважды. */
    private fun isKnown(lower: String, lang: KeyboardLanguage): Boolean {
        if (learned(lang, lower) || isPersonal(lang, lower)) return true
        val own = dictFor(lang)
        if (own != null && own.rankOf(lower) >= 0 && own.typos[lower] == null) return true
        for ((_, d) in dicts) if (d !== own && d.rankOf(lower) >= 0) return true
        return false
    }

    private fun applyCase(word: String, cap: Boolean, allCaps: Boolean): String = when {
        word.isEmpty() -> word
        allCaps -> word.uppercase()
        cap -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    // ---------------------------------------------------------------- опечатки

    /**
     * Недавно отменённые исправления: слово → до какого времени его не трогать (даже в режиме инкогнито).
     * Раньше отмена действовала, пока жив процесс клавиатуры (днями) — одна отмена выключала исправление слова.
     */
    private val rejectedUntil = ConcurrentHashMap<String, Long>()
    private const val REJECT_MS = 10 * 60_000L

    /** Для тестов: забыть недавние отмены исправлений. */
    internal fun clearRejections() = rejectedUntil.clear()

    private fun isRejected(key: String): Boolean {
        val t = rejectedUntil[key] ?: return false
        if (System.currentTimeMillis() < t) return true
        rejectedUntil.remove(key)
        return false
    }
    /** Своё исправление уверенное (см. correctionLower). */
    private val confident = ConcurrentHashMap<String, Boolean>()
    /**
     * Сколько раз системная проверка назвала «словом из словаря» явную опечатку. Без словаря языка она так
     * отвечает на всё подряд — после трёх таких ответов её мнение для языка не учитывается до перезапуска.
     */
    private val systemMisses = ConcurrentHashMap<String, MutableSet<String>>()

    fun rejectCorrection(lang: KeyboardLanguage, word: String) {
        rejectedUntil[uKey(lang, word.trim().lowercase())] = System.currentTimeMillis() + REJECT_MS
    }

    private fun baseLetter(c: Char): Char {
        if (c == 'ё') return 'е'
        if (c.code < 0xC0) return c
        val d = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        return d.firstOrNull() ?: c
    }

    private fun adjacent(lang: KeyboardLanguage, a: Char, b: Char): Boolean {
        val pa = lang.keyPositions[a] ?: return false
        val pb = lang.keyPositions[b] ?: return false
        return abs(pa.first - pb.first) <= 1f && abs(pa.second - pb.second) <= 1.01f
    }

    /**
     * Модель опечаток. Все цены — в «натах» (−ln вероятности), как в Gboard: цена слова = цена ошибок пальца
     * (по геометрии касаний) + редкость слова − контекст. Слово из словаря сравнивается с тем, что набрано,
     * на одной шкале, поэтому порог «исправлять или нет» один и понятный. Числа подобраны на проверке
     * app/src/test (AutocorrectEval): тысячи слов, «напечатанных» с разбросом касаний живого пальца.
     */
    internal object Typo {
        /** Разброс касаний вокруг центра клавиши — в долях её ширины и высоты. */
        @JvmField var sigmaX = 0.33f
        @JvmField var sigmaY = 0.25f
        @JvmField var subFar = 9.4f
        @JvmField var subNear = 2.8f
        @JvmField var subSound = 4.0f
        @JvmField var subAccent = 0.9f
        @JvmField var subMin = 0.25f
        @JvmField var del = 6.0f
        @JvmField var delDouble = 3.4f
        @JvmField var delNeighbor = 5.0f
        @JvmField var ins = 3.5f
        @JvmField var insDouble = 2.0f
        @JvmField var insSoft = 3.5f
        @JvmField var swap = 3.2f
        /** Вес редкости слова: ln(1 + место в частотном списке / lmK). */
        @JvmField var lmScale = 1.12f
        @JvmField var lmK = 20f
        /** Цена «набранное — не опечатка, а незнакомое слово»: базовая и за каждую букву после четвёртой. */
        @JvmField var keepNormal = 9.45f
        @JvmField var keepSafe = 7.8f
        @JvmField var keepPerChar = 0.58f
        /** Два варианта ближе этого — в осторожном режиме не угадываем. */
        @JvmField var ambiguousSafe = 1.0f
        /** Слово из словаря исправляется, только если вариант вероятнее на столько. */
        @JvmField var knownMargin = 3.0f
        @JvmField var knownMinRank = 4000
        @JvmField var split = 6.0f
    }

    /** −ln P(касание | клавиша target) с точностью до константы: касание по клавише typed со смещением touch. */
    private fun touchCost(lang: KeyboardLanguage, typed: Char, touch: KeyTouch, target: Char): Float {
        val p = lang.keyPositions[typed] ?: return Float.MAX_VALUE
        val q = lang.keyPositions[target] ?: return Float.MAX_VALUE
        val dx = (p.second + touch.dx - q.second) / Typo.sigmaX
        val dy = (p.first + touch.dy - q.first) / Typo.sigmaY
        return 0.5f * (dx * dx + dy * dy)
    }

    /**
     * Буквы, которые путают на слух, а не пальцем: «спосибо», «сигодня», «дила».
     * Такие ошибки — самые частые в русском, поэтому стоят дешевле промаха по далёкой клавише.
     */
    private val SOUND_ALIKE: Set<String> = buildSet {
        val ru = listOf("ао", "еи", "ея", "иы", "еэ", "юу", "ёо", "шщ", "ьъ", "зс", "дт", "бп", "вф", "гк", "жш", "цс", "ия")
        val latin = listOf("ae", "ei", "io", "ou", "ua", "ai", "eo", "yi", "ck", "cs", "sz", "sc")
        for (p in ru + latin) {
            add(p)
            add(p.reversed())
        }
    }

    private fun soundAlike(a: Char, b: Char) = "$a$b" in SOUND_ALIKE

    /** Мягкие буквы, которые чаще всего пропускают при быстром наборе. */
    private fun softLetter(c: Char) = c == 'ь' || c == 'ъ' || c == 'й' || c == '\'' || c == '-'

    /**
     * Цена того, что вместо b нажали a. Если известно, куда попал палец, решает геометрия: касание у границы
     * «а» и «о» делает замену почти бесплатной, удар точно в центр «а» — дорогой.
     */
    private fun subCost(lang: KeyboardLanguage, a: Char, b: Char, touch: KeyTouch?): Float {
        if (a == b) return 0f
        if (baseLetter(a) == baseLetter(b)) return Typo.subAccent
        val sound = if (soundAlike(a, b)) Typo.subSound else Typo.subFar
        if (touch != null && lang.keyPositions.containsKey(a) && lang.keyPositions.containsKey(b)) {
            val spatial = touchCost(lang, a, touch, b) - touchCost(lang, a, touch, a)
            return min(sound, spatial.coerceIn(Typo.subMin, Typo.subFar))
        }
        return min(sound, if (adjacent(lang, a, b)) Typo.subNear else Typo.subFar)
    }

    /** Цена превращения набранного typed в слово dict (взвешенное расстояние Дамерау–Левенштейна), выход после limit. */
    private fun editCost(lang: KeyboardLanguage, typed: String, dict: String, limit: Float, touches: List<KeyTouch?>?): Float {
        val n = typed.length
        val m = dict.length
        if (n > 30 || m > 30) return Float.MAX_VALUE
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1)
        // пропущенная буква: удвоенную («класный» → «классный») и мягкий знак пропускают чаще всего
        val insCost = FloatArray(m + 1)
        for (j in 1..m) {
            val b = dict[j - 1]
            insCost[j] = when {
                j >= 2 && dict[j - 2] == b -> Typo.insDouble
                softLetter(b) -> Typo.insSoft
                else -> Typo.ins
            }
            prev[j] = prev[j - 1] + insCost[j]
        }
        var cur = FloatArray(m + 1)
        // перестановка смотрит на два ряда назад — выходим, только если оба ряда уже дороже лимита
        var prevMin = 0f
        for (i in 1..n) {
            val a = typed[i - 1]
            val touch = touches?.getOrNull(i - 1)
            // лишняя буква: дешевле, если это дубль или соседняя клавиша (задели две сразу)
            val delCost = when {
                i >= 2 && typed[i - 2] == a -> Typo.delDouble
                (i >= 2 && adjacent(lang, typed[i - 2], a)) || (i < n && adjacent(lang, typed[i], a)) -> Typo.delNeighbor
                else -> Typo.del
            }
            cur[0] = prev[0] + delCost
            var rowMin = cur[0]
            for (j in 1..m) {
                val b = dict[j - 1]
                var v = min(
                    prev[j - 1] + subCost(lang, a, b, touch),
                    min(prev[j] + delCost, cur[j - 1] + insCost[j])
                )
                if (i >= 2 && j >= 2 && a == dict[j - 2] && typed[i - 2] == b) v = min(v, prev2[j - 2] + Typo.swap)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit && prevMin > limit) return Float.MAX_VALUE
            prevMin = rowMin
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** Редкость слова: −ln частоты по закону Ципфа, с точностью до константы. */
    private fun freqCost(rank: Int): Float = Typo.lmScale * kotlin.math.ln(1f + rank / Typo.lmK)

    /** Бонус за то, что слово часто идёт после предыдущего («доброе» → «утро»). */
    private fun contextBonus(lang: KeyboardLanguage, prev: String, w: String): Float {
        if (prev.isEmpty()) return 0f
        var bonus = 0f
        val n = userBigrams["${lang.id}:$prev|$w"] ?: 0
        if (n > 0) bonus += min(2.5f, 0.9f + 0.6f * kotlin.math.ln(1f + n))
        val builtin = when (lang.id) {
            "ru" -> ruBigrams[prev]
            "en" -> enBigrams[prev]
            else -> null
        }
        if (builtin?.contains(w) == true) bonus += 1.2f
        return bonus
    }

    private val nextCache = object : LinkedHashMap<String, Map<Char, Float>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Char, Float>>?) = size > 96
    }

    fun cachedNextLetters(lang: KeyboardLanguage, prefix: String): Map<Char, Float>? =
        synchronized(nextCache) { nextCache["${lang.id}:${prefix.lowercase()}"] }

    /**
     * ln P(следующая буква | начало слова) по частотному словарю и вашим словам — для динамических зон клавиш.
     * Пусто — такого начала в словаре нет (имя, сленг): тогда зоны ничего не сдвигают.
     */
    fun nextLetters(lang: KeyboardLanguage, prefix: String): Map<Char, Float> {
        val p = prefix.lowercase()
        val key = "${lang.id}:$p"
        cachedNextLetters(lang, p)?.let { return it }
        val d = dictFor(lang)
        if (d == null || d.size < 3000) return emptyMap()
        val weights = HashMap<Char, Float>()
        var lo = 0
        var hi = d.sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (d.sorted[mid] < p) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i < d.sorted.size && d.sorted[i].startsWith(p)) {
            val w = d.sorted[i]
            if (w.length > p.length) {
                val r = d.rank[w] ?: Int.MAX_VALUE / 2
                val c = w[p.length]
                weights[c] = (weights[c] ?: 0f) + 1f / (r + 30f)
            }
            i++
        }
        val prefixKey = "${lang.id}:$p"
        for ((k, n) in userWords) {
            if (n < LEARNED || k.length <= prefixKey.length || !k.startsWith(prefixKey)) continue
            val c = k[prefixKey.length]
            weights[c] = (weights[c] ?: 0f) + 0.01f * n
        }
        val total = weights.values.sum()
        val out = if (total <= 0f) emptyMap() else weights.mapValues { (_, v) -> kotlin.math.ln(v / total) }
        synchronized(nextCache) { nextCache[key] = out }
        return out
    }

    /** Бонус слову свайпа за предыдущее слово («доброе» → «утро»). */
    fun glideContext(lang: KeyboardLanguage, previous: String): (String) -> Float {
        val p = previous.trim().lowercase()
        return { w -> if (p.isEmpty()) 0f else contextBonus(lang, p, w) }
    }

    /** Свои слова: набранное много раз дешевле. */
    private fun userBonus(lang: KeyboardLanguage, w: String): Float = 0.8f * kotlin.math.ln(1f + userCount(lang, w))

    private class Candidate(val word: String, val cost: Float, val score: Float, val rank: Int)

    /** Расчёт для устаревшего набора прерывается: пока считали, пользователь уже нажал следующую букву. */
    private class Cancelled : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private val cancelCheck = ThreadLocal<(() -> Boolean)?>()

    private fun checkCancelled() {
        if (cancelCheck.get()?.invoke() == true) throw Cancelled()
    }

    /** Ближайшие слова словаря к набранному: score = цена ошибок + редкость − контекст (меньше — вероятнее). */
    private fun nearWords(
        lower: String,
        lang: KeyboardLanguage,
        maxCost: Float,
        limit: Int = 4,
        prev: String = "",
        touches: List<KeyTouch?>? = null
    ): List<Candidate> {
        val d = dictFor(lang) ?: return emptyList()
        val maxLenDiff = if (lower.length <= 4) 1 else 2
        val p = prev.trim().lowercase()
        val best = ArrayList<Candidate>()
        checkCancelled()
        // кандидаты из индекса SymSpell; «ё» ищем и как «е»
        val idx = HashSet<Int>()
        d.similar(lower, idx)
        val plain = lower.map(::baseLetter).joinToString("")
        if (plain != lower) d.similar(plain, idx)
        // две ошибки пальцем («скпзатб» → «сказать») индекс с одной удалённой буквой не находит:
        // ищем ещё от вариантов набранного, где одна буква заменена правдоподобной соседней
        if (lower.length >= 3) {
            val chars = lower.toCharArray()
            for (i in chars.indices) {
                val a = chars[i]
                for (c in plausibleKeys(lang, a, touches?.getOrNull(i))) {
                    chars[i] = c
                    d.similarNear(String(chars), idx)
                }
                chars[i] = a
            }
        }
        checkCancelled()
        for (i in idx) {
            val w = d.words[i]
            if (abs(w.length - lower.length) > maxLenDiff || w == lower) continue
            val cost = editCost(lang, lower, w, maxCost, touches)
            if (cost > maxCost) continue
            val score = cost + freqCost(i) - userBonus(lang, w) - contextBonus(lang, p, w)
            best += Candidate(w, cost, score, i)
        }
        best.sortBy { it.score }
        return best.distinctBy { it.word }.take(limit)
    }

    /** Клавиши, которые палец мог иметь в виду вместо нажатой a: соседние (по касанию — только близкие) и похожие на слух. */
    private fun plausibleKeys(lang: KeyboardLanguage, a: Char, touch: KeyTouch?): List<Char> {
        val out = ArrayList<Char>(6)
        for (c in lang.keyPositions.keys) {
            if (c == a || !c.isLetter()) continue
            val ok = if (adjacent(lang, a, c)) {
                touch == null || touchCost(lang, a, touch, c) - touchCost(lang, a, touch, a) < 5f
            } else soundAlike(a, c)
            if (ok) out += c
        }
        return out
    }

    /** Однобуквенные слова, которые можно отделить пропущенным пробелом. */
    private fun singleLetterWord(lang: KeyboardLanguage, s: String): Boolean = when (lang.id) {
        "ru" -> s in RU_SINGLE
        "en" -> s == "a" || s == "i"
        else -> false
    }

    private val RU_SINGLE = setOf("в", "с", "и", "к", "у", "я", "а", "о")

    /** Пропущенный пробел: «приветкак» → «привет как», «вкино» → «в кино». Возвращает пару и её цену. */
    private fun splitWords(lower: String, lang: KeyboardLanguage, normal: Boolean): Pair<String, Float>? {
        if (lower.length < (if (normal) 5 else 6)) return null
        val d = dictFor(lang) ?: return null
        // обе части — частые слова: иначе «вкусняшка» превращается в «вкус няшка»
        val maxRank = if (normal) 15_000 else 5_000
        var bestText: String? = null
        var bestScore = Float.MAX_VALUE
        for (k in 1 until lower.length) {
            val l = lower.substring(0, k)
            val r = lower.substring(k)
            if (l.length == 1 && !singleLetterWord(lang, l)) continue
            if (r.length == 1 && !singleLetterWord(lang, r)) continue
            val rl = d.rank[l]?.takeIf { it <= maxRank } ?: continue
            val rr = d.rank[r]?.takeIf { it <= maxRank } ?: continue
            val score = Typo.split + freqCost(rl) + freqCost(rr) - contextBonus(lang, l, r)
            if (score < bestScore) {
                bestScore = score
                bestText = "$l $r"
            }
        }
        val text = bestText ?: return null
        return text to bestScore
    }

    /** Цена того, что набранное — не опечатка, а слово, которого нет в словаре (имя, сленг, новое слово). */
    private fun keepCost(len: Int, normal: Boolean): Float =
        (if (normal) Typo.keepNormal else Typo.keepSafe) + Typo.keepPerChar * max(0, len - 4)

    /**
     * Автоисправление незнакомого слова: лучший вариант по сумме «цена опечатки + редкость − контекст»,
     * если он вероятнее, чем «это не опечатка, а незнакомое слово». Касания учитываются — промах у края
     * клавиши исправляется уверенно, а точное попадание почти никогда.
     */
    private fun correctionLower(
        lower: String,
        lang: KeyboardLanguage,
        strength: String,
        prev: String,
        touches: List<KeyTouch?>?
    ): String? {
        if (lower.length < 2 || lower.any { !it.isLetter() && it != '\'' && it != '-' }) return null
        if (!lang.autoCorrect || !hasDictionary(lang)) return null
        val normal = strength == "NORMAL"
        val hasTouch = touches?.any { it != null } == true
        val len = lower.length
        // короткие слова часто сленг и сокращения («спс», «оч»): без данных о касаниях их не трогаем
        if (len == 2 && (!normal || !hasTouch)) return null
        if (len == 3 && !normal && !hasTouch) return null
        var keep = keepCost(len, normal)
        // двухбуквенное — только явный промах мимо очень частого слова («мв» → «мы»)
        if (len == 2) keep -= 3.5f
        val cands = nearWords(lower, lang, keep + 1f, 4, prev, touches)
            .filter { if (len == 2) it.word.length == 2 && it.rank < 400 else true }
        val best = cands.firstOrNull()
        val split = splitWords(lower, lang, normal)
        if (split != null && split.second <= keep && (best == null || split.second < best.score)) return split.first
        if (best == null || best.score > keep) return null
        val second = cands.getOrNull(1)
        // в осторожном режиме два почти равных варианта — не угадываем
        if (!normal && second != null && second.score - best.score < Typo.ambiguousSafe) return null
        // уверенное исправление — его не отменяет системная проверка
        confident[uKey(lang, lower)] = keep - best.score >= 3f && (second == null || second.score - best.score >= 1.5f)
        return best.word
    }

    /**
     * Слово есть в словаре, но редкое, а палец явно промахнулся мимо частого соседа («люби» в центре «д»).
     * Срабатывает только при данных о касаниях и только если вы сами это слово никогда не набирали.
     */
    private fun rareWordFix(
        lower: String,
        lang: KeyboardLanguage,
        strength: String,
        prev: String,
        touches: List<KeyTouch?>?
    ): String? {
        if (touches == null || touches.none { it != null } || lower.length < 3) return null
        if (!lang.autoCorrect || !hasDictionary(lang) || userCount(lang, lower) > 0 || isPersonal(lang, lower)) return null
        if (lower.any { !it.isLetter() }) return null
        val d = dictFor(lang) ?: return null
        val r = d.rankOf(lower).takeIf { it >= 0 } ?: return null
        val normal = strength == "NORMAL"
        if (r < (if (normal) Typo.knownMinRank else Typo.knownMinRank * 2)) return null
        val margin = if (normal) Typo.knownMargin else Typo.knownMargin + 1.5f
        val keep = freqCost(r) - contextBonus(lang, prev, lower)
        val best = nearWords(lower, lang, keep - margin, 2, prev, touches).firstOrNull() ?: return null
        return if (keep - best.score >= margin) best.word else null
    }

    /**
     * Одна буква, которая словом не бывает («т» между словами): палец промахнулся мимо соседней «и».
     * Только активный режим и только если касание было у границы с этой буквой.
     */
    private fun singleLetterFix(clean: String, lang: KeyboardLanguage, strength: String, touch: KeyTouch?): String? {
        if (strength != "NORMAL" || touch == null || !lang.autoCorrect || !hasDictionary(lang)) return null
        val c = clean[0]
        val lower = c.lowercaseChar()
        if (!lower.isLetter() || singleLetterWord(lang, lower.toString())) return null
        if (isRejected(uKey(lang, lower.toString()))) return null
        val singles = when (lang.id) {
            "ru" -> RU_SINGLE.map { it[0] }
            "en" -> listOf('a', 'i')
            else -> return null
        }
        val best = singles.minByOrNull { subCost(lang, lower, it, touch) } ?: return null
        if (subCost(lang, lower, best, touch) > 2.5f) return null
        return if (c.isUpperCase() || (lang.id == "en" && best == 'i')) best.uppercase() else best.toString()
    }

    /** Исправление из таблицы частых ошибок (встроенной и из словаря языка). */
    private fun typoFix(lang: KeyboardLanguage, lower: String): String? {
        val fix = when (lang.id) {
            "ru" -> ruTypos[lower]
            "en" -> enTypos[lower]
            else -> null
        } ?: dictFor(lang)?.typos?.get(lower) ?: return null
        if (learned(lang, lower) || isPersonal(lang, lower)) return null
        return fix
    }

    fun autoCorrectionFor(
        word: String,
        lang: KeyboardLanguage,
        strength: String = "SAFE",
        previous: String = "",
        touches: List<KeyTouch?>? = null
    ): String? {
        val clean = word.trim()
        if (clean.length == 1) return singleLetterFix(clean, lang, strength, touches?.singleOrNull())
        if (clean.length < 2) return null
        if (clean.any { it.isDigit() }) return null
        // аббревиатуры и слова с заглавной внутри (iPhone, ООО) не трогаем
        if (clean.drop(1).any { it.isUpperCase() } && !clean.all { it.isUpperCase() }) return null
        // «кто-» или «rock'» — слово ещё не дописано, а не опечатка
        if (clean.last() == '-' || clean.last() == '\'') return null
        val lower = clean.lowercase()
        if (isRejected(uKey(lang, lower))) return null
        val t = touches?.takeIf { it.size == clean.length }
        val prev = previous.trim().lowercase()
        // частая ошибка написания («пожалуста», «извени») — сразу, если вы сами так не пишете
        val fix = typoFix(lang, lower)
            ?: if (isKnown(lower, lang)) rareWordFix(lower, lang, strength, prev, t)
            else correctionLower(lower, lang, strength, prev, t)
        fix ?: return null
        return applyCase(fix, clean.first().isUpperCase(), clean.length > 1 && clean.all { it.isUpperCase() })
    }

    /**
     * Сводит своё исправление с ответом системной проверки (Gboard, Samsung): слово есть в её словаре — не трогаем,
     * уверенная рекомендация — берём её, иначе решает своё. Выученные, личные и отменённые слова — всегда за нами.
     */
    fun mergeSystem(word: String, own: String?, sys: SystemSpell.Result?, lang: KeyboardLanguage, strength: String): String? {
        if (sys == null || !sys.hasOpinion || !lang.autoCorrect) return own
        val clean = word.trim()
        if (clean.length < 2 || clean.last() == '-' || clean.last() == '\'') return own
        if (clean.drop(1).any { it.isUpperCase() } && !clean.all { it.isUpperCase() }) return own
        val lower = clean.lowercase()
        // слово другой письменности (английское на русской раскладке) системная проверка этого языка не поймёт
        if (!lang.keyPositions.containsKey(lower[0])) return own
        if (isRejected(uKey(lang, lower)) || learned(lang, lower) || isPersonal(lang, lower)) return own
        if (typoFix(lang, lower) != null) return own
        if ((systemMisses[lang.id]?.size ?: 0) >= 3) return own
        if (!sys.typo) {
            if (own != null && confident[uKey(lang, lower)] == true) {
                systemMisses.getOrPut(lang.id) { ConcurrentHashMap.newKeySet() } += lower
                return own
            }
            return null
        }
        val first = sys.suggestions.firstOrNull { sameScript(lang, it) } ?: return own
        val cap = clean.first().isUpperCase()
        val allCaps = clean.length > 1 && clean.all { it.isUpperCase() }
        if (own != null && sys.suggestions.take(3).any { it.equals(own, ignoreCase = true) }) return own
        if (sys.recommended) return applyCase(first, cap, allCaps)
        if (own != null) return own
        // слово из своего словаря без уверенной рекомендации не трогаем
        if (strength != "NORMAL" || lower.length < 3 || isKnown(lower, lang)) return null
        // без рекомендации первый вариант берём, только если это действительно близкая опечатка
        val limit = when {
            lower.length <= 4 -> 4.5f
            lower.length <= 7 -> 7f
            else -> 9.5f
        }
        return if (editCost(lang, lower, first.lowercase(), limit, null) <= limit) applyCase(first, cap, allCaps) else null
    }

    // ---------------------------------------------------------------- подсказки

    /** Продолжения по префиксу: сначала ваши частые слова, затем самые частые в языке. */
    private fun completions(lower: String, lang: KeyboardLanguage, limit: Int): List<String> {
        val scored = HashMap<String, Float>()
        val prefix = "${lang.id}:$lower"
        for ((k, n) in userWords) {
            if (k.length > prefix.length && k.startsWith(prefix)) {
                val w = k.substring(lang.id.length + 1)
                // случайно набранное один раз (часто — опечатка) в подсказки не лезет
                if (n < LEARNED && dictFor(lang)?.rank?.containsKey(w) != true) continue
                scored[w] = min(scored[w] ?: Float.MAX_VALUE, 3f - kotlin.math.ln(1f + n))
            }
        }
        val d = dictFor(lang)
        if (d != null) {
            var lo = 0
            var hi = d.sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (d.sorted[mid] < lower) lo = mid + 1 else hi = mid
            }
            val top = ArrayList<Pair<String, Int>>()
            var i = lo
            while (i < d.sorted.size && d.sorted[i].startsWith(lower)) {
                val w = d.sorted[i]
                if (w.length > lower.length) top += w to (d.rank[w] ?: Int.MAX_VALUE)
                i++
            }
            top.sortBy { it.second }
            for ((w, r) in top.take(limit * 2)) {
                val s = kotlin.math.ln(2f + r) - 0.7f * kotlin.math.ln(1f + userCount(lang, w))
                scored[w] = min(scored[w] ?: Float.MAX_VALUE, s)
            }
        }
        return scored.entries.sortedBy { it.value }.take(limit).map { it.key }
    }

    fun predict(
        word: String,
        previous: String,
        undo: UndoAutoCorrectState?,
        lang: KeyboardLanguage,
        capitalizeNext: Boolean,
        strength: String = "SAFE",
        autoCorrect: Boolean = true,
        touches: List<KeyTouch?>? = null,
        sys: SystemSpell.Result? = null,
        cancelled: (() -> Boolean)? = null
    ): PredictionSnapshot? {
        cancelCheck.set(cancelled)
        return try {
            predictInner(word, previous, undo, lang, capitalizeNext, strength, autoCorrect, touches, sys)
        } catch (_: Cancelled) {
            null
        } finally {
            cancelCheck.set(null)
        }
    }

    private fun predictInner(
        word: String,
        previous: String,
        undo: UndoAutoCorrectState?,
        lang: KeyboardLanguage,
        capitalizeNext: Boolean,
        strength: String,
        autoCorrect: Boolean,
        touches: List<KeyTouch?>?,
        sys: SystemSpell.Result?
    ): PredictionSnapshot {
        val clean = word.trim()
        if (clean.isEmpty()) return nextWords(previous, undo, lang, capitalizeNext)

        val lower = clean.lowercase()
        val allCaps = clean.length > 1 && clean.all { it.isUpperCase() }
        val cap = clean.first().isUpperCase()
        val sysOk = sys != null && sys.inDictionary && !sys.typo
        val known = isKnown(lower, lang) || sysOk
        val own = if (autoCorrect) autoCorrectionFor(clean, lang, strength, previous, touches) else null
        val fix = if (autoCorrect) mergeSystem(clean, own, sys, lang, strength) else null
        // варианты системной проверки — когда она считает слово опечаткой
        val sysAlt = if (sys != null && sys.typo) {
            sys.suggestions.filter { sameScript(lang, it) }.map { applyCase(it, cap, allCaps) }.filter { it != clean && it != fix }
        } else emptyList()
        val comps = completions(lower, lang, 3).map { applyCase(it, cap, allCaps) }.filter { it != clean && it != fix }

        val chips = when {
            fix != null -> listOf(
                SuggestionChipModel("«$clean»", clean, isKeepExact = true),
                SuggestionChipModel(fix, fix, isPrimary = true),
                (sysAlt.firstOrNull() ?: comps.firstOrNull() ?: alternativeTo(fix, lower, lang, previous, touches, cap, allCaps))
                    ?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            sysAlt.isNotEmpty() -> listOf(
                SuggestionChipModel(sysAlt[0], sysAlt[0]),
                SuggestionChipModel(clean, clean, isPrimary = true, isKeepExact = true),
                (sysAlt.getOrNull(1) ?: comps.firstOrNull())?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            known -> listOf(
                comps.getOrNull(0)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY,
                SuggestionChipModel(clean, clean, isPrimary = true, isKeepExact = true),
                comps.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            comps.isNotEmpty() -> listOf(
                SuggestionChipModel("«$clean»", clean, isKeepExact = true),
                SuggestionChipModel(comps[0], comps[0], isPrimary = true),
                comps.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            else -> {
                // похожие слова — только как подсказки, без замены по пробелу
                val near = if (clean.length >= 3) nearWords(lower, lang, 9f, 2, previous, touches?.takeIf { it.size == clean.length }).map { applyCase(it.word, cap, allCaps) } else emptyList()
                listOf(
                    near.getOrNull(0)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY,
                    SuggestionChipModel(clean, clean, isPrimary = true, isKeepExact = true),
                    near.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
                )
            }
        }
        return PredictionSnapshot(chips, fix, clean, own, sys != null, known)
    }

    /** Второй по вероятности вариант исправления — для правой подсказки. */
    private fun alternativeTo(
        fix: String,
        lower: String,
        lang: KeyboardLanguage,
        previous: String,
        touches: List<KeyTouch?>?,
        cap: Boolean,
        allCaps: Boolean
    ): String? {
        if (lower.length < 3) return null
        val t = touches?.takeIf { it.size == lower.length }
        return nearWords(lower, lang, 9f, 3, previous.trim().lowercase(), t)
            .map { applyCase(it.word, cap, allCaps) }
            .firstOrNull { !it.equals(fix, ignoreCase = true) }
    }

    private fun nextWords(
        previous: String,
        undo: UndoAutoCorrectState?,
        lang: KeyboardLanguage,
        cap: Boolean
    ): PredictionSnapshot {
        val prev = previous.trim().lowercase()
        val set = LinkedHashSet<String>()
        if (prev.isNotEmpty()) {
            val prefix = "${lang.id}:$prev|"
            // связка, набранная хотя бы дважды, — уже привычка, а не случайность
            userBigrams.entries.asSequence()
                .filter { it.key.startsWith(prefix) && it.value >= 2 }
                .sortedByDescending { it.value }
                .take(3)
                .forEach { set.add(it.key.substringAfter('|')) }
            when (lang.id) {
                "ru" -> ruBigrams[prev]?.let { set.addAll(it) }
                "en" -> enBigrams[prev]?.let { set.addAll(it) }
            }
            // связки, набранные хотя бы раз
            if (set.size < 3) userBigrams.entries.asSequence()
                .filter { it.key.startsWith(prefix) }
                .sortedByDescending { it.value }
                .take(3)
                .forEach { set.add(it.key.substringAfter('|')) }
        }
        // остальное — самые частые слова языка, чтобы строка подсказок не стояла пустой («…»)
        if (set.size < 3) {
            val starters = if (prev.isEmpty()) SENTENCE_STARTERS[lang.id].orEmpty() else emptyList()
            for (w in starters) if (set.size < 3) set.add(w)
            val top = dictFor(lang)?.words
            if (top != null) {
                var i = 0
                while (set.size < 3 && i < min(top.size, 60)) {
                    val w = top[i++]
                    if (w != prev && w.isNotBlank() && w.all { it.isLetter() }) set.add(w)
                }
            }
        }
        val words = set.filter { it.isNotBlank() }.map { if (lang.id == "en" && it == "i") "I" else applyCase(it, cap, false) }
        val a = words.getOrElse(0) { "" }
        val b = words.getOrElse(1) { "" }
        val c = words.getOrElse(2) { "" }
        val left = if (undo != null) {
            SuggestionChipModel("↶ ${undo.originalWord}", undo.originalWord, isUndo = true)
        } else SuggestionChipModel(b, b)
        val right = if (undo != null) SuggestionChipModel(b, b) else SuggestionChipModel(c, c)
        return PredictionSnapshot(listOf(left, SuggestionChipModel(a, a, isPrimary = true), right), null, "")
    }
}
