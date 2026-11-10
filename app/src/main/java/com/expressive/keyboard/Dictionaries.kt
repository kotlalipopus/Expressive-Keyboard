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
// СЛОВАРИ, МОДЕЛЬ ПАР СЛОВ, СИСТЕМНАЯ ПРОВЕРКА ПРАВОПИСАНИЯ
// ============================================================================

/**
 * Редкие слова языка (формы слов, имена — ещё ~200 000): только «есть ли слово» и его место по частоте.
 * Хранятся компактно — по байту на букву, поиск двоичный: без них незнакомую форму слова («испытательного»)
 * исправление превращало в соседнюю знакомую («испытательном»).
 */
class RareWords private constructor(
    private val codes: HashMap<Char, Int>,
    private val data: ByteArray,
    private val starts: IntArray,
    private val ranks: IntArray,
    private val firstRank: Int
) {
    val size: Int get() = starts.size - 1

    constructor(entries: List<Pair<String, Int>>, firstRank: Int) : this(Packed(entries), firstRank)
    private constructor(p: Packed, firstRank: Int) : this(p.codes, p.data, p.starts, p.ranks, firstRank)

    /** Готовые массивы в файл: при следующем запуске не нужно разбирать мегабайты текста. */
    fun save(file: java.io.File) {
        java.io.DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeInt(CACHE_VERSION)
            o.writeInt(codes.size)
            for ((c, i) in codes) { o.writeChar(c.code); o.writeInt(i) }
            o.writeInt(data.size)
            o.write(data)
            o.writeInt(ranks.size)
            val b = java.nio.ByteBuffer.allocate((starts.size + ranks.size) * 4)
            b.asIntBuffer().also { it.put(starts); it.put(ranks) }
            o.write(b.array())
        }
    }

    private class Packed(entries: List<Pair<String, Int>>) {
    val codes = HashMap<Char, Int>()
    val data: ByteArray
    val starts: IntArray
    val ranks: IntArray

    private fun compare(a: String, b: String): Int {
        val n = min(a.length, b.length)
        for (i in 0 until n) {
            val d = (codes[a[i]] ?: 0) - (codes[b[i]] ?: 0)
            if (d != 0) return d
        }
        return a.length - b.length
    }

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
    }

    companion object {
        private const val CACHE_VERSION = 1

        /** null — файла нет или он другого формата: тогда разбираем текст. */
        fun load(file: java.io.File, firstRank: Int): RareWords? = runCatching {
            java.io.DataInputStream(file.inputStream().buffered(1 shl 16)).use { i ->
                if (i.readInt() != CACHE_VERSION) return null
                val codes = HashMap<Char, Int>()
                repeat(i.readInt()) { val c = i.readChar(); codes[c] = i.readInt() }
                val data = ByteArray(i.readInt()).also { i.readFully(it) }
                val n = i.readInt()
                val bytes = ByteArray((n + 1 + n) * 4).also { i.readFully(it) }
                val ints = java.nio.ByteBuffer.wrap(bytes).asIntBuffer()
                val starts = IntArray(n + 1).also { ints.get(it) }
                val ranks = IntArray(n).also { ints.get(it) }
                RareWords(codes, data, starts, ranks, firstRank)
            }
        }.getOrNull()

        /** Растянутое междометие из субтитров: «а-а-а», «aaaa», «нееет». */
        fun looksLikeNoise(w: String): Boolean {
            var run = 1
            for (i in 1 until w.length) {
                run = if (w[i] == w[i - 1]) run + 1 else 1
                if (run >= 3) return true
            }
            val parts = w.split('-')
            return parts.size >= 3 && parts.distinct().size == 1
        }

        /** Строки «слово место36» (dict/<code>_more.txt). */
        fun parse(lines: List<String>): List<Pair<String, Int>> = lines.mapNotNull { l ->
            val sp = l.indexOf(' ')
            if (sp <= 0) null else l.substring(0, sp) to (l.substring(sp + 1).trim().toIntOrNull(36) ?: return@mapNotNull null)
        }
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

    /** Самые частые продолжения префикса (слово, место); мусор вроде «а-а-а-а» не предлагается. */
    fun completions(prefix: String, limit: Int): List<Pair<String, Int>> {
        val p = ByteArray(prefix.length) { i -> (codes[prefix[i]] ?: return emptyList()).toByte() }
        fun below(n: Int): Boolean {
            val s = starts[n]
            val len = starts[n + 1] - s
            for (i in 0 until min(len, p.size)) {
                val d = (data[s + i].toInt() and 0xFF) - (p[i].toInt() and 0xFF)
                if (d != 0) return d < 0
            }
            return len < p.size
        }
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (below(mid)) lo = mid + 1 else hi = mid
        }
        fun hasPrefix(n: Int): Boolean {
            val s = starts[n]
            if (starts[n + 1] - s < p.size) return false
            for (i in p.indices) if (data[s + i] != p[i]) return false
            return true
        }
        val top = java.util.PriorityQueue<Int>(compareByDescending { ranks[it] })
        val keep = limit * 3 // запас: часть лучших может оказаться мусором
        var n = lo
        while (n < size && hasPrefix(n)) {
            if (starts[n + 1] - starts[n] > p.size) {
                top += n
                if (top.size > keep) top.poll()
            }
            n++
        }
        val letters = CharArray(codes.size + 1)
        for ((c, i) in codes) letters[i] = c
        return top.sortedBy { ranks[it] }.map { i ->
            val sb = StringBuilder()
            for (k in starts[i] until starts[i + 1]) sb.append(letters[data[k].toInt() and 0xFF])
            sb.toString() to firstRank + ranks[i]
        }.filter { !looksLikeNoise(it.first) }.take(limit)
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

/**
 * Пары слов из Google Books Ngrams (dict/<code>_bi.txt): что идёт за предыдущим словом и насколько вероятно.
 * Хранится массивами, а не картой строк: 200 000 пар занимают единицы мегабайт памяти.
 * Строка файла: «слово следующее/вес следующее/вес …», вес = round(-2·ln P), варианты по убыванию вероятности.
 */
class BigramModel(lines: List<String>) {
    private val vocab: Array<String>
    private val starts: IntArray
    private val prevs: IntArray
    private val nexts: IntArray
    private val weights: ByteArray

    init {
        val all = java.util.TreeSet<String>()
        val rows = lines.mapNotNull { l -> l.split(' ').takeIf { it.size >= 2 } }
        for (r in rows) {
            all += r[0]
            for (i in 1 until r.size) all += r[i].substringBefore('/')
        }
        vocab = all.toTypedArray()
        val sorted = rows.sortedBy { it[0] }
        val total = sorted.sumOf { it.size - 1 }
        prevs = IntArray(sorted.size)
        starts = IntArray(sorted.size + 1)
        nexts = IntArray(total)
        weights = ByteArray(total)
        var k = 0
        sorted.forEachIndexed { n, r ->
            prevs[n] = java.util.Arrays.binarySearch(vocab, r[0])
            starts[n] = k
            for (i in 1 until r.size) {
                val cut = r[i].lastIndexOf('/')
                nexts[k] = java.util.Arrays.binarySearch(vocab, r[i].substring(0, cut))
                weights[k] = (r[i].substring(cut + 1).toIntOrNull() ?: 40).toByte()
                k++
            }
        }
        starts[sorted.size] = k
    }

    private fun row(prev: String): Int {
        val id = java.util.Arrays.binarySearch(vocab, prev)
        return if (id < 0) -1 else java.util.Arrays.binarySearch(prevs, id)
    }

    /** -ln P(w | prev) или отрицательное, если такой пары в модели нет. */
    fun cost(prev: String, w: String): Float {
        val r = row(prev)
        if (r < 0) return -1f
        val id = java.util.Arrays.binarySearch(vocab, w)
        if (id < 0) return -1f
        for (i in starts[r] until starts[r + 1]) if (nexts[i] == id) return weights[i] / 2f
        return -1f
    }

    fun known(prev: String): Boolean = row(prev) >= 0

    /** Следующие слова по убыванию вероятности. */
    fun next(prev: String): List<String> {
        val r = row(prev)
        if (r < 0) return emptyList()
        return (starts[r] until starts[r + 1]).map { vocab[nexts[it]] }
    }
}

/** Словарь одного языка: слова по убыванию частоты, редкие формы и частые ошибки написания. */
class LangDict(words: List<String>, rare: RareWords? = null, typos: Map<String, String> = emptyMap()) {
    /** Дочитываются после частых слов: подсказки работают сразу, не дожидаясь их. */
    @Volatile var rare: RareWords? = rare
    @Volatile var typos: Map<String, String> = typos
    @Volatile var bigrams: BigramModel? = null
    /** Тройки слов: контекст «слово|слово» → следующие (только там, где они меняют выбор пары). */
    @Volatile var trigrams: BigramModel? = null
    /** Слова, которые пишутся с заглавной: имена, города. */
    @Volatile var names: Set<String> = emptySet()
    /** «Мой словарь»: слово в нижнем регистре → как его записал пользователь («анна» → «Анна»). */
    @Volatile var mine: Map<String, String> = emptyMap()
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
    /** Сколько редких слов (кроме 50 000 частых) оставляем у скачанного словаря. */
    private const val MORE_LIMIT = 600_000
    /** Версия набора редких слов: у словарей, скачанных по старой схеме, она другая — их докачают. */
    private const val MORE_VERSION = "v3"

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
    fun moreMarker(context: Context, code: String) = File(File(context.applicationContext.filesDir, DIR), "${code}_more.$MORE_VERSION")

    fun moreFile(context: Context, code: String) = File(File(context.applicationContext.filesDir, DIR), "${code}_more.txt")

    fun readRare(context: Context, code: String): List<Pair<String, Int>>? = runCatching {
        val own = moreFile(context, code)
        if (own.exists() && own.length() > 0) RareWords.parse(own.readLines())
        else if (!bundled(context, code + "_more")) null
        else RareWords.parse(context.assets.open("dict/${code}_more.txt").bufferedReader().use { it.readLines() })
    }.getOrNull()

    /** Редкие слова: из кэша, а при первом запуске новой версии — из текста, с сохранением в кэш. */
    fun loadRare(context: Context, code: String, firstRank: Int): RareWords? {
        val own = moreFile(context, code)
        val stamp = if (own.exists() && own.length() > 0) own.lastModified()
            else runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val dir = java.io.File(context.cacheDir, "dict").apply { mkdirs() }
        val cache = java.io.File(dir, "$code-$stamp.bin")
        RareWords.load(cache, firstRank)?.let { return it }
        val rare = readRare(context, code)?.let { RareWords(it, firstRank) } ?: return null
        runCatching {
            dir.listFiles()?.filter { it.name.startsWith("$code-") }?.forEach { it.delete() }
            val tmp = java.io.File(dir, cache.name + ".tmp")
            rare.save(tmp)
            tmp.renameTo(cache)
        }
        return rare
    }

    fun readBigrams(context: Context, code: String): BigramModel? = runCatching {
        if (!bundled(context, code + "_bi")) null
        else BigramModel(context.assets.open("dict/${code}_bi.txt").bufferedReader().use { it.readLines() })
    }.getOrNull()

    /** Тройки: строки «слово слово следующее/вес …» → модель с контекстом «слово|слово». */
    fun readTrigrams(context: Context, code: String): BigramModel? = runCatching {
        if (!bundled(context, code + "_tri")) null
        else BigramModel(context.assets.open("dict/${code}_tri.txt").bufferedReader().use { r ->
            r.readLines().mapNotNull { l ->
                val a = l.indexOf(' ')
                if (a <= 0) null else l.substring(0, a) + "|" + l.substring(a + 1)
            }
        })
    }.getOrNull()

    fun readNames(context: Context, code: String): Set<String> = runCatching {
        if (!bundled(context, code + "_names")) emptySet()
        else context.assets.open("dict/${code}_names.txt").bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() }.toHashSet() }
    }.getOrDefault(emptySet())

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
        var moreLines: List<String> = emptyList()
        val r = runCatching {
            var lastError: Throwable? = null
            for (suffix in listOf("full", "50k")) {
                val res = runCatching {
                    val conn = (URL("$BASE/$code/${code}_$suffix.txt").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                    }
                    try {
                        if (conn.responseCode != 200) throw IllegalStateException("сервер ответил ${conn.responseCode}")
                        val out = LinkedHashSet<String>()
                        val seen = HashSet<String>()
                        val more = ArrayList<String>()
                        conn.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (out.size >= LIMIT && more.size >= MORE_LIMIT) break
                                val parts = line.trim().split(' ')
                                val w = parts[0].lowercase()
                                if (w.isEmpty() || !WORD.matches(w) || w.endsWith("'") || w.endsWith("-") || !seen.add(w)) continue
                                if (out.size < LIMIT) out += w
                                else {
                                    // редкие слова — в отдельный файл: «слово место», место считается после частых; опечатки
                                    // из субтитров встречаются один раз, поэтому слова с одним упоминанием не берём
                                    val count = parts.getOrNull(1)?.toIntOrNull() ?: 2
                                    if (count < 3) break
                                    more += w + " " + more.size.toString(36)
                                }
                            }
                        }
                        require(out.size >= 500) { "слишком маленький словарь" }
                        moreLines = more
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
                val mf = moreFile(app, code)
                if (moreLines.isNotEmpty()) {
                    val mtmp = File(mf.parentFile, "${code}_more.tmp")
                    mtmp.writeText(moreLines.joinToString("\n"))
                    if (!mtmp.renameTo(mf)) {
                        mf.delete()
                        mtmp.renameTo(mf)
                    }
                } else mf.delete()
                runCatching { moreMarker(app, code).writeText("ok") }
                return@runCatching words.size + moreLines.size
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
        if (!tried.add(code)) return
        // словарь, скачанный раньше (50 000 слов без редких форм), обновляем до большого — один раз
        val old = !bundled(context, code) && file(context, code).let { it.exists() && it.length() > 0 } && !moreMarker(context, code).exists()
        if (isInstalled(context, code) && !old) return
        if (!KeyboardPrefs.current(context).dictAutoDownload) return
        CoroutineScope(Dispatchers.IO + BackgroundErrors).launch { download(context, code) }
    }

    fun delete(context: Context, code: String) {
        file(context, code).delete()
        moreFile(context, code).delete()
        moreMarker(context, code).delete()
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

    /** Системный экран «Проверка правописания»; если прошивка его не даёт — «Язык и ввод». */
    fun openSettings(context: Context) {
        val tries = listOf(
            android.content.Intent().setClassName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity"),
            android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

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
