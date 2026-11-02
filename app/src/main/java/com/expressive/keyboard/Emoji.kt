package com.expressive.keyboard

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// ЭМОДЗИ: ПОЛНЫЙ КАТАЛОГ UNICODE 16, ПОИСК, ТОНА КОЖИ И ВСТРОЕННЫЕ СТИЛИ
// Каталог и листы стилей собираются скриптом tools/emoji/build_emoji.py.
// ============================================================================

@Immutable
class EmojiItem(
    val emoji: String,
    /** Номер в листах стилей; у вариантов тона номера идут сразу после основного. */
    val index: Int,
    val variants: List<String>,
    val nameEn: String,
    val nameRu: String,
    /** Имена и ключевые слова в нижнем регистре, слова через пробел — для поиска. */
    val haystack: String,
    /** Системный шрифт умеет рисовать этот эмодзи (новые эмодзи на старом Android — нет). */
    val system: Boolean
) {
    // Имена в нижнем регистре считаются один раз при загрузке каталога: поиск идёт на каждую введённую букву,
    // и раньше lowercase() вызывался для каждого из ~3800 эмодзи на каждый запрос.
    val nameEnLower: String = nameEn.lowercase()
    val nameRuLower: String = nameRu.lowercase()
}

@Immutable
data class EmojiCategory(val id: String, val icon: String, val items: List<EmojiItem>) {
    val title: String get() = tr(TITLES[id] ?: id)

    companion object {
        val TITLES = mapOf(
            "smileys" to "Смайлы и эмоции", "people" to "Люди", "nature" to "Животные и природа",
            "food" to "Еда и напитки", "travel" to "Путешествия и места", "activities" to "Занятия",
            "objects" to "Предметы", "symbols" to "Символы", "flags" to "Флаги"
        )
        val ICONS = mapOf(
            "smileys" to "😀", "people" to "👋", "nature" to "🐻", "food" to "🍔", "travel" to "✈️",
            "activities" to "⚽", "objects" to "💡", "symbols" to "💕", "flags" to "🏳️"
        )
    }
}

object EmojiCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + BackgroundErrors)
    var categories by mutableStateOf<List<EmojiCategory>>(emptyList())
        private set
    @Volatile private var started = false
    /** Эмодзи и его варианты (с FE0F и без) → номер в листах. */
    @Volatile private var indices: Map<String, Int> = emptyMap()
    @Volatile private var items: Map<String, EmojiItem> = emptyMap()
    /** Плоский список основных эмодзи: поиск не обходит категории и списки вариантов. */
    @Volatile private var allItems: List<EmojiItem> = emptyList()

    /** Панель эмодзи открыта — грузить сейчас же, без отсрочки. */
    @Volatile private var urgent = false

    /**
     * Каталог (≈3800 эмодзи с проверкой, какие умеет рисовать система) собирается в фоне. При запуске клавиатуры —
     * с отсрочкой, чтобы не мешать первым нажатиям; если панель эмодзи открыли раньше — сразу.
     */
    fun warmUp(context: Context, now: Boolean = false) {
        if (now) urgent = true
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            var waited = 0
            while (!urgent && waited < 4000) {
                delay(100)
                waited += 100
            }
            val list = runCatching { load(app) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { categories = list }
        }
    }

    private fun load(context: Context): List<EmojiCategory> {
        val paint = AndroidPaint()
        val out = ArrayList<EmojiCategory>()
        val idx = HashMap<String, Int>(8192)
        val byEmoji = HashMap<String, EmojiItem>(4096)
        val flat = ArrayList<EmojiItem>(4096)
        var group: String? = null
        var groupItems = ArrayList<EmojiItem>()
        var n = 0
        fun flush() {
            val g = group ?: return
            out += EmojiCategory(g, EmojiCategory.ICONS[g] ?: "😀", groupItems)
        }
        context.assets.open("emoji/emoji.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                if (line[0] == '#') {
                    flush()
                    group = line.substring(1)
                    groupItems = ArrayList()
                    continue
                }
                val p = line.split('\t')
                if (p.size < 5) continue
                val emoji = p[0]
                val variants = if (p[1].isEmpty()) emptyList() else p[1].split(' ')
                val hay = " " + (p[2] + " " + p[3] + " " + p[4]).lowercase().replace(" | ", " ") + " "
                val ok = runCatching { paint.hasGlyph(emoji) }.getOrDefault(true)
                val item = EmojiItem(emoji, n, variants, p[2], p[3], hay, ok)
                groupItems += item
                flat += item
                byEmoji[emoji] = item
                idx[emoji] = n
                idx.putIfAbsent(emoji.replace("️", ""), n)
                n++
                for (v in variants) {
                    byEmoji[v] = item
                    idx[v] = n
                    idx.putIfAbsent(v.replace("️", ""), n)
                    n++
                }
            }
        }
        flush()
        indices = idx
        items = byEmoji
        allItems = flat
        return out
    }

    fun indexOf(emoji: String): Int = indices[emoji] ?: indices[emoji.replace("️", "")] ?: -1

    /** Основной эмодзи (для варианта тона — его основа). */
    fun item(emoji: String): EmojiItem? = items[emoji]

    /**
     * Последние запросы: панель поиска пересчитывается на каждую введённую букву, а полный перебор каталога
     * с сортировкой — самая дорогая часть. Кэш маленький, поэтому память не растёт.
     */
    private val cache = object : LinkedHashMap<String, List<EmojiItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EmojiItem>>?): Boolean = size > 24
    }

    /** Разбиение запроса по пробелам без Regex: он компилировался на каждый вызов поиска. */
    private fun words(query: String): List<String> {
        val out = ArrayList<String>(4)
        val sb = StringBuilder()
        for (ch in query) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            } else sb.append(ch)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Поиск по названиям и ключевым словам на английском и русском: «кот», «heart», «смех». */
    fun search(query: String, limit: Int = 120): List<EmojiItem> {
        val tokens = words(query.trim().lowercase())
        val list = allItems
        if (tokens.isEmpty() || list.isEmpty()) return emptyList()
        val key = "$limit|${tokens.joinToString(" ")}"
        synchronized(cache) { cache[key] }?.let { return it }
        val scored = ArrayList<Pair<Int, EmojiItem>>()
        for (it in list) {
            var score = 0
            for (t in tokens) {
                val s = when {
                    it.nameRuLower == t || it.nameEnLower == t -> 0
                    it.haystack.contains(" $t ") -> 1
                    it.haystack.contains(" $t") -> 2
                    it.haystack.contains(t) -> 4
                    else -> -1
                }
                if (s < 0) { score = -1; break }
                score += s
            }
            if (score >= 0) scored += score to it
        }
        val result = scored.sortedWith(compareBy({ it.first }, { it.second.index })).take(limit).map { it.second }
        synchronized(cache) { cache[key] = result }
        return result
    }
}

/** Встроенные стили эмодзи — картинки в листах 16×16, чтобы не скачивать шрифты. */
object EmojiStyles {
    const val SYSTEM = "SYSTEM"
    const val CUSTOM = "CUSTOM"
    private const val CELL = 64
    private const val GRID = 16

    /** id → (название, лицензия). */
    val builtIn: List<Triple<String, String, String>> = listOf(
        Triple("twemoji", "Twemoji (Discord, X)", "CC-BY 4.0"),
        Triple("noto", "Noto (Google, Android)", "Apache 2.0"),
        Triple("blob", "Blob (Android 7)", "Apache 2.0"),
        Triple("openmoji", "OpenMoji", "CC BY-SA 4.0")
    )

    fun isImageStyle(style: String) = builtIn.any { it.first == style }

    private val have = ConcurrentHashMap<String, BitSet>()
    private val sheets = LruCache<String, ImageBitmap>(4)

    private fun index(context: Context, style: String): BitSet = have.getOrPut(style) {
        val bits = BitSet()
        runCatching {
            val s = context.assets.open("emoji/$style/index.txt").bufferedReader().use { it.readText() }
            for (i in s.indices) if (s[i] == '1') bits.set(i)
        }
        bits
    }

    fun has(context: Context, style: String, index: Int): Boolean = index >= 0 && index(context, style).get(index)

    fun cached(style: String, sheet: Int): ImageBitmap? = sheets.get("$style/$sheet")

    suspend fun sheet(context: Context, style: String, sheet: Int): ImageBitmap? {
        val key = "$style/$sheet"
        sheets.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            sheets.get(key) ?: runCatching {
                context.assets.open("emoji/$key.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()?.also { sheets.put(key, it) }
        }
    }

    fun srcOffset(index: Int): IntOffset {
        val i = index % (GRID * GRID)
        return IntOffset((i % GRID) * CELL, (i / GRID) * CELL)
    }

    val srcSize = IntSize(CELL, CELL)
}

/** Стиль эмодзи из настроек: встроенный набор картинок, свой шрифт или системный шрифт. */
@Composable
fun EmojiGlyph(emoji: String, size: Dp, style: String, font: FontFamily?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val index = remember(emoji, EmojiCatalog.categories) { EmojiCatalog.indexOf(emoji) }
    val image = EmojiStyles.isImageStyle(style) && EmojiStyles.has(context, style, index)
    if (image) {
        val sheetNo = index / 256
        val sheet by produceState(EmojiStyles.cached(style, sheetNo), style, sheetNo) {
            if (value == null) value = EmojiStyles.sheet(context, style, sheetNo)
        }
        val s = sheet
        if (s != null) {
            val src = EmojiStyles.srcOffset(index)
            Canvas(modifier.size(size)) {
                drawImage(
                    s, srcOffset = src, srcSize = EmojiStyles.srcSize,
                    dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()), filterQuality = FilterQuality.Medium
                )
            }
            return
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.78f).sp, fontFamily = if (style == EmojiStyles.CUSTOM) font else null, maxLines = 1)
    }
}
