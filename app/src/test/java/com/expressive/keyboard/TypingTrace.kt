package com.expressive.keyboard

import org.junit.Test
import java.io.File

/**
 * Что видит человек в строке подсказок, пока печатает фразу: подсказки после каждой буквы и после пробела.
 * Отчёт: build/eval/trace.txt. Запуск: ./gradlew :app:testDebugUnitTest --tests '*TypingTrace*'
 */
class TypingTrace {
    private fun lines(name: String) = File("src/main/assets/dict/$name").takeIf { it.exists() }?.readLines().orEmpty()

    private fun install(lang: KeyboardLanguage, code: String) {
        val words = lines("$code.txt").map { it.trim() }.filter { it.isNotEmpty() }
        val tri = lines("${code}_tri.txt").mapNotNull { l -> val a = l.indexOf(' '); if (a <= 0) null else l.substring(0, a) + "|" + l.substring(a + 1) }
        WordPredictorEngine.installDictionary(
            lang, words,
            bigrams = lines("${code}_bi.txt").takeIf { it.isNotEmpty() }?.let { BigramModel(it) },
            trigrams = tri.takeIf { it.isNotEmpty() }?.let { BigramModel(it) }
        )
    }

    @Test
    fun trace() {
        val out = StringBuilder()
        val rnd = kotlin.random.Random(7)
        val sets = listOf(
            Languages.RU to "ru" to listOf(
                "привет как дела что делаешь сегодня вечером",
                "я не знаю что тебе сказать",
                "давай встретимся завтра в шесть",
                "спасибо большое за помощь",
                "ты уже дома или еще на работе",
                "скинь пожалуйста номер телефона",
                "мне кажется это не очень хорошая идея",
                // с опечатками
                "превет как дила",
                "я сигодня не смагу прийти",
                "позвани мне когда освабодишься"
            ),
            Languages.EN to "en" to listOf(
                "hello how are you doing today",
                "i do not know what to say",
                "see you tomorrow at the office",
                "thnaks for yuor help"
            )
        )
        for ((lc, phrases) in sets) {
            val (lang, code) = lc
            install(lang, code)
            for (p in phrases) {
                out.appendLine("== $p")
                val ws = p.split(' ')
                for (i in ws.indices) {
                    val prev = if (i > 0) ws[i - 1] else ""
                    val prev2 = if (i > 1) ws[i - 2] else ""
                    val w = ws[i]
                    val sb = StringBuilder()
                    // палец попадает рядом с центром клавиши, как при аккуратном наборе
                    val touches = w.map { KeyTouch((rnd.nextFloat() - 0.5f) * 0.5f, (rnd.nextFloat() - 0.5f) * 0.4f) }
                    for (n in 0..w.length) {
                        val snap = WordPredictorEngine.predict(w.take(n), prev, null, lang, false, "NORMAL", true, touches.take(n), previous2 = prev2)
                        val chips = snap?.chips.orEmpty().joinToString(" | ") { (if (it.isPrimary) "*" else "") + it.displayText.ifEmpty { "…" } }
                        sb.appendLine("   ${w.take(n).padEnd(14)} $chips" + (snap?.autoCorrection?.let { "   ⇒ $it" } ?: ""))
                    }
                    out.append(sb)
                }
            }
        }
        File("build/eval").mkdirs()
        File("build/eval/trace.txt").writeText(out.toString())
    }

    /**
     * Набор по буквам на отложенных фразах: как часто нужное слово уже есть в подсказках, сколько нажатий это
     * экономит и как часто посреди слова подсвечивается чужое «исправление». Отчёт: build/eval/midword.txt.
     */
    @Test
    fun midword() {
        val lang = Languages.RU
        install(lang, "ru")
        val phrases = File("src/test/resources/heldout_ru.txt").readLines()
            .map { it.trim().lowercase().split(' ').filter { w -> w.isNotEmpty() && w.all { c -> c.isLetter() } } }.filter { it.size > 1 }.take(700)
        val rnd = kotlin.random.Random(11)
        val keys = lang.keyPositions
        val hit = IntArray(5); val tot = IntArray(5)
        var chars = 0; var saved = 0
        var mid = 0; var bogus = 0; var early = 0
        var typoWords = 0; var typoShown = 0; var typoPrefix = 0; var typoPrefixHit = 0
        val bogusSamples = LinkedHashSet<String>()
        var ms = 0L; var calls = 0
        fun predict(typed: String, prev: String, prev2: String, t: List<KeyTouch>): PredictionSnapshot? {
            val t0 = System.nanoTime()
            val r = WordPredictorEngine.predict(typed, prev, null, lang, false, "NORMAL", true, t, previous2 = prev2)
            ms += System.nanoTime() - t0; calls++
            return r
        }
        for (ph in phrases) for (i in 1 until ph.size) {
            val prev = ph[i - 1]; val prev2 = if (i > 1) ph[i - 2] else ""
            val w = ph[i]
            if (w.any { keys[it] == null }) continue
            val touches = w.map { KeyTouch((rnd.nextFloat() - 0.5f) * 0.5f, (rnd.nextFloat() - 0.5f) * 0.4f) }
            chars += w.length
            var done = false
            for (n in 0 until w.length) {
                val snap = predict(w.take(n), prev, prev2, touches.take(n))
                val chips = snap?.chips.orEmpty().filter { !it.isKeepExact }.map { it.commitWord.lowercase() }
                val shown = w in chips
                if (n < 5) { tot[n]++; if (shown) hit[n]++ }
                if (shown && !done) { done = true; saved += w.length - n }
                if (n >= 2) {
                    mid++
                    val fix = snap?.autoCorrection?.lowercase()
                    if (fix != null) {
                        if (fix == w) early++ else { bogus++; if (bogusSamples.size < 60) bogusSamples += "${w.take(n)}→$fix($w)" }
                    }
                }
            }
            // опечатка: промах по соседней клавише; нужное слово должно появляться в подсказках ещё до конца слова
            if (w.length >= 5) {
                val at = 1 + rnd.nextInt(w.length - 2)
                val p = keys.getValue(w[at])
                val near = keys.entries.filter { it.key != w[at] && it.key.isLetter() && it.value.first == p.first && kotlin.math.abs(it.value.second - p.second) <= 1.01f }
                if (near.isNotEmpty()) {
                    val wrong = near[rnd.nextInt(near.size)].key
                    val typed = w.substring(0, at) + wrong + w.substring(at + 1)
                    val dx = if (keys.getValue(wrong).second > p.second) -0.4f else 0.4f
                    val t = typed.indices.map { if (it == at) KeyTouch(dx, 0f) else touches[it] }
                    typoWords++
                    val full = predict(typed, prev, prev2, t)
                    if (full?.chips.orEmpty().any { it.commitWord.lowercase() == w }) typoShown++
                    for (n in (at + 1) until typed.length) {
                        if (n < 3) continue
                        typoPrefix++
                        if (predict(typed.take(n), prev, prev2, t.take(n))?.chips.orEmpty().any { it.commitWord.lowercase() == w }) typoPrefixHit++
                    }
                }
            }
        }
        fun pc(a: Int, b: Int) = "%.1f%%".format(100.0 * a / b.coerceAtLeast(1))
        val out = buildString {
            appendLine("слово в подсказках после 0/1/2/3/4 букв: " + (0..4).joinToString(" / ") { pc(hit[it], tot[it]) })
            appendLine("экономия нажатий: ${pc(saved, chars)}")
            appendLine("посреди слова (от 2 букв): чужое исправление ${pc(bogus, mid)}, верное заранее ${pc(early, mid)}")
            appendLine("слово с опечаткой: в подсказках целиком ${pc(typoShown, typoWords)}, до конца слова ${pc(typoPrefixHit, typoPrefix)}")
            appendLine("время на подсказку: %.2f мс".format(ms / 1e6 / calls))
            appendLine("чужие исправления: $bogusSamples")
        }
        File("build/eval").mkdirs()
        File("build/eval/midword.txt").writeText(out)
        println(out)
    }
}
