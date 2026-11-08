package com.expressive.keyboard

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// КИТАЙСКИЙ (ПИНЬИНЬ) И ЯПОНСКИЙ (РОМАДЗИ → КАНА)
// Набранные латинские буквы остаются в поле как «составляемый» текст, а в строке подсказок —
// варианты: иероглифы, слова и целые фразы. Вариант может покрыть только начало набранного —
// тогда остаток продолжает набираться.
// ============================================================================

/** text — что вставить, consumed — сколько набранных букв он покрывает. */
@Immutable
data class CjkCandidate(val text: String, val consumed: Int)

/**
 * Выученный ввод: «язык:набранное без '» → слово → сколько раз выбрано. Выбранные слова и фразы
 * поднимаются в начало ленты, а фразы, собранные по частям (我 + 爱你), запоминаются целиком.
 */
object CjkUser {
    private const val KEY = "cjk_user_words"
    private val words = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)
    @Volatile private var app: Context? = null
    @Volatile private var loaded = false
    private var saveJob: Job? = null

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        app = context.applicationContext
        scope.launch {
            runCatching {
                val raw = context.applicationContext.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null) ?: return@launch
                val o = JSONObject(raw)
                for (k in o.keys()) {
                    val m = o.getJSONObject(k)
                    val map = words.getOrPut(k) { ConcurrentHashMap() }
                    for (w in m.keys()) map[w] = m.optInt(w, 1)
                }
            }
        }
    }

    private fun key(lang: KeyboardLanguage, raw: String) = lang.id + ":" + raw.replace("'", "").lowercase()

    fun learn(lang: KeyboardLanguage, raw: String, word: String) {
        if (raw.isBlank() || word.isBlank() || word.equals(raw, ignoreCase = true)) return
        val map = words.getOrPut(key(lang, raw)) { ConcurrentHashMap() }
        map[word] = (map[word] ?: 0) + 1
        save()
    }

    fun forget(lang: KeyboardLanguage, word: String) {
        val prefix = lang.id + ":"
        for ((k, m) in words) if (k.startsWith(prefix)) m.remove(word)
        save()
    }

    /** Выученные слова для набранного, самые частые первыми. */
    fun lookup(lang: KeyboardLanguage, raw: String): List<String> =
        words[key(lang, raw)]?.entries?.sortedByDescending { it.value }?.map { it.key }.orEmpty()

    fun clear() {
        words.clear()
        save()
    }

    fun export(context: Context): String? =
        context.applicationContext.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Из резервной копии: счётчики складываются по большему, выученное на этом телефоне остаётся. */
    fun merge(context: Context, raw: String) {
        runCatching {
            val o = JSONObject(raw)
            val prefs = context.applicationContext.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)
            val cur = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
            for (k in o.keys()) {
                val m = o.getJSONObject(k)
                val target = cur.optJSONObject(k) ?: JSONObject().also { cur.put(k, it) }
                val mem = if (loaded) words.getOrPut(k) { ConcurrentHashMap() } else null
                for (w in m.keys()) {
                    val v = m.optInt(w, 1)
                    target.put(w, maxOf(v, target.optInt(w, 0)))
                    mem?.merge(w, v, ::maxOf)
                }
            }
            prefs.edit().putString(KEY, cur.toString()).commit()
        }
    }

    private fun save() {
        val ctx = app ?: return
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(2000L)
                runCatching {
                    val o = JSONObject()
                    // не больше 3000 сочетаний: самые используемые
                    words.entries.sortedByDescending { e -> e.value.values.sum() }.take(3000).forEach { (k, m) ->
                        if (m.isNotEmpty()) o.put(k, JSONObject(m.toMap()))
                    }
                    ctx.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
                }
            }
        }
    }
}

object Pinyin {
    private class Dict(
        /** Ключи без «'», отсортированы; words/ranks/syllables — параллельные массивы. */
        val keys: Array<String>,
        val words: Array<String>,
        val ranks: IntArray,
        val syllables: IntArray,
        /** Все слоги из словаря — для разбора набранного на слоги. */
        val valid: Set<String>,
        val prefixes: Set<String>
    )

    private val dicts = ConcurrentHashMap<String, Dict>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)

    private fun variant(lang: KeyboardLanguage) = if (lang.id == "zh-tw") "zh_tw" else "zh_cn"

    fun prepare(context: Context, lang: KeyboardLanguage) {
        if (lang.composer != Composer.PINYIN) return
        val v = variant(lang)
        if (dicts.containsKey(v) || !loading.add(v)) return
        val app = context.applicationContext
        scope.launch {
            runCatching { dicts[v] = load(app, v) }
            loading.remove(v)
        }
    }

    private fun load(context: Context, v: String): Dict {
        val entries = ArrayList<Triple<String, String, Int>>(48_000)
        val valid = HashSet<String>(512)
        context.assets.open("zh/$v.txt").bufferedReader().useLines { lines ->
            var rank = 0
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val reading = line.substring(tab + 1)
                val syl = reading.split('\'')
                valid += syl
                entries += Triple(reading.replace("'", ""), line.substring(0, tab), rank * 64 + syl.size)
                rank++
            }
        }
        entries.sortBy { it.first }
        val prefixes = HashSet<String>(2048)
        for (s in valid) for (i in 1..s.length) prefixes += s.substring(0, i)
        return Dict(
            Array(entries.size) { entries[it].first },
            Array(entries.size) { entries[it].second },
            IntArray(entries.size) { entries[it].third / 64 },
            IntArray(entries.size) { entries[it].third % 64 },
            valid, prefixes
        )
    }

    fun ready(lang: KeyboardLanguage) = dicts.containsKey(variant(lang))

    /** Разбор на слоги: самые длинные подходящие слоги, последний может быть недописан ("nih" → ni + h). */
    private fun segment(d: Dict, s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\'') { i++; continue }
            var best = -1
            var j = minOf(s.length, i + 6)
            while (j > i) {
                val part = s.substring(i, j)
                if ('\'' !in part && part in d.valid) { best = j; break }
                j--
            }
            if (best < 0) {
                // недописанный слог: самый длинный префикс какого-нибудь слога
                j = minOf(s.length, i + 6)
                while (j > i && (s.substring(i, j).contains('\'') || s.substring(i, j) !in d.prefixes)) j--
                best = if (j > i) j else i + 1
            }
            out += s.substring(i, best)
            i = best
        }
        return out
    }

    /** Слова из n слогов, чьё чтение — key (последний слог может быть начат: partial). */
    private fun lookup(d: Dict, key: String, n: Int, partial: Boolean, limit: Int): List<Int> {
        var lo = 0
        var hi = d.keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (d.keys[mid] < key) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Int>()
        var i = lo
        while (i < d.keys.size && d.keys[i].startsWith(key)) {
            if (d.syllables[i] == n && (partial || d.keys[i].length == key.length)) found += i
            i++
            if (!partial) {
                if (d.keys.getOrNull(i)?.length != key.length) break
            }
        }
        return found.sortedBy { d.ranks[it] }.take(limit)
    }

    fun candidates(lang: KeyboardLanguage, raw: String): List<CjkCandidate> {
        val d = dicts[variant(lang)] ?: return listOf(CjkCandidate(raw, raw.length))
        val segs = segment(d, raw)
        if (segs.isEmpty()) return emptyList()
        // где в набранном кончается каждый слог (с учётом «'»)
        val ends = IntArray(segs.size)
        var pos = 0
        segs.forEachIndexed { k, sgm ->
            while (pos < raw.length && raw[pos] == '\'') pos++
            pos += sgm.length
            ends[k] = pos
        }
        val lastPartial = segs.last() !in d.valid
        val out = LinkedHashMap<String, CjkCandidate>()
        fun add(text: String, consumed: Int) {
            if (text.isNotEmpty() && text !in out) out[text] = CjkCandidate(text, consumed)
        }
        // выученное — первым: целиком, затем по началу набранного
        CjkUser.lookup(lang, raw).forEach { add(it, raw.length) }
        val whole = lookup(d, segs.joinToString(""), segs.size, lastPartial, 12)
        // фраза из нескольких слов, если всё целиком одним словом не найдено
        if (segs.size > 1) {
            val sentence = StringBuilder()
            var i = 0
            while (i < segs.size) {
                var k = segs.size - i
                var hit: Int? = null
                while (k >= 1) {
                    val partial = lastPartial && i + k == segs.size
                    hit = lookup(d, segs.subList(i, i + k).joinToString(""), k, partial, 1).firstOrNull()
                    if (hit != null) break
                    k--
                }
                if (hit == null) { sentence.clear(); break }
                sentence.append(d.words[hit])
                i += k
            }
            if (whole.isEmpty() && sentence.isNotEmpty()) add(sentence.toString(), raw.length)
        }
        whole.forEach { add(d.words[it], raw.length) }
        // слова, покрывающие только начало набранного
        for (k in segs.size - 1 downTo 1) {
            CjkUser.lookup(lang, raw.substring(0, ends[k - 1])).forEach { add(it, ends[k - 1]) }
            lookup(d, segs.subList(0, k).joinToString(""), k, false, if (k == 1) 24 else 8).forEach { add(d.words[it], ends[k - 1]) }
        }
        if (out.isEmpty()) add(raw, raw.length)
        return out.values.take(40)
    }
}

/** Ромадзи → хирагана: "konnichiha" → こんにちは, "kitte" → きって. */
object Kana {
    private val table: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        val vowels = "aiueo"
        val hira = "あいうえお"
        for (i in 0 until 5) m[vowels[i].toString()] = hira[i].toString()
        val rows = mapOf(
            "k" to "かきくけこ", "g" to "がぎぐげご", "s" to "さしすせそ", "z" to "ざじずぜぞ", "t" to "たちつてと",
            "d" to "だぢづでど", "n" to "なにぬねの", "h" to "はひふへほ", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ",
            "m" to "まみむめも", "r" to "らりるれろ"
        )
        for ((c, k) in rows) for (i in 0 until 5) m[c + vowels[i]] = k[i].toString()
        m["ya"] = "や"; m["yu"] = "ゆ"; m["yo"] = "よ"; m["wa"] = "わ"; m["wo"] = "を"
        m["shi"] = "し"; m["chi"] = "ち"; m["tsu"] = "つ"; m["fu"] = "ふ"; m["ji"] = "じ"
        m["nn"] = "ん"; m["n'"] = "ん"; m["-"] = "ー"
        m["xa"] = "ぁ"; m["xi"] = "ぃ"; m["xu"] = "ぅ"; m["xe"] = "ぇ"; m["xo"] = "ぉ"; m["xtsu"] = "っ"
        m["xya"] = "ゃ"; m["xyu"] = "ゅ"; m["xyo"] = "ょ"
        // слоги с маленькими ゃゅょ: kya, sha, cha, ja…
        val yo = mapOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ")
        val ib = mapOf(
            "ky" to "き", "gy" to "ぎ", "sh" to "し", "sy" to "し", "j" to "じ", "jy" to "じ", "zy" to "じ", "ch" to "ち",
            "cy" to "ち", "ty" to "ち", "dy" to "ぢ", "ny" to "に", "hy" to "ひ", "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り"
        )
        for ((c, base) in ib) for ((v, small) in yo) m.putIfAbsent(c + v, base + small)
        m["she"] = "しぇ"; m["je"] = "じぇ"; m["che"] = "ちぇ"
        m["fa"] = "ふぁ"; m["fi"] = "ふぃ"; m["fe"] = "ふぇ"; m["fo"] = "ふぉ"
        m["ti"] = "ち"; m["si"] = "し"; m["tu"] = "つ"; m["hu"] = "ふ"; m["zi"] = "じ"; m["di"] = "ぢ"; m["du"] = "づ"
        m["wi"] = "うぃ"; m["we"] = "うぇ"; m["va"] = "ゔぁ"; m["vi"] = "ゔぃ"; m["vu"] = "ゔ"; m["ve"] = "ゔぇ"; m["vo"] = "ゔぉ"
        m
    }

    fun compose(raw: String): String {
        val s = raw.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // двойная согласная → っ
            if (i + 1 < s.length && c == s[i + 1] && c !in "aiueon" && c.isLetter()) {
                out.append('っ'); i++; continue
            }
            // n перед согласной (кроме y) → ん
            if (c == 'n' && i + 1 < s.length && s[i + 1] !in "aiueoy'n") {
                out.append('ん'); i++; continue
            }
            var matched = false
            for (len in 4 downTo 1) {
                if (i + len > s.length) continue
                val v = table[s.substring(i, i + len)] ?: continue
                out.append(v)
                i += len
                matched = true
                break
            }
            if (!matched) { out.append(s[i]); i++ }
        }
        return out.toString()
    }

    fun katakana(hira: String): String = buildString {
        for (ch in hira) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
    }

    fun candidates(lang: KeyboardLanguage, raw: String): List<CjkCandidate> {
        val h = compose(raw)
        val learned = CjkUser.lookup(lang, raw).map { CjkCandidate(it, raw.length) }
        return (learned + listOf(CjkCandidate(h, raw.length), CjkCandidate(katakana(h), raw.length), CjkCandidate(raw, raw.length)))
            .distinctBy { it.text }
    }
}

object Cjk {
    fun prepare(context: Context, lang: KeyboardLanguage) {
        CjkUser.load(context)
        Pinyin.prepare(context, lang)
    }

    fun isCjk(c: Composer) = c == Composer.PINYIN || c == Composer.KANA

    /** Что показать в поле, пока слово набирается. */
    fun display(c: Composer, raw: String): String = if (c == Composer.KANA) Kana.compose(raw) else raw

    fun candidates(lang: KeyboardLanguage, raw: String): List<CjkCandidate> = when (lang.composer) {
        Composer.PINYIN -> Pinyin.candidates(lang, raw)
        Composer.KANA -> Kana.candidates(lang, raw)
        else -> emptyList()
    }

    /** Китайская и японская пунктуация: , → ，  . → 。 */
    fun punctuation(c: Composer, text: String): String = when (text) {
        "," -> if (c == Composer.KANA) "、" else "，"
        "." -> "。"
        "?" -> "？"
        "!" -> "！"
        ":" -> if (c == Composer.KANA) ":" else "："
        ";" -> if (c == Composer.KANA) ";" else "；"
        else -> text
    }
}
