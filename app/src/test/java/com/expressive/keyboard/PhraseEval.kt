package com.expressive.keyboard

import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Качество предсказаний на разговорных фразах (src/test/resources/phrases_*.txt), в обучении они не участвовали.
 * Отчёт: build/eval/phrases.txt. С TUNE=1 подбирает веса контекста координатным спуском (build/eval/tune_ctx.txt).
 * Запуск: ./gradlew :app:testDebugUnitTest --tests '*PhraseEval*'
 */
class PhraseEval {
    private fun words(code: String) = File("src/main/assets/dict/$code.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun model(code: String): BigramModel? =
        File("src/main/assets/dict/${code}_bi.txt").takeIf { it.exists() }?.let { BigramModel(it.readLines()) }

    /** Тройки: «слово слово следующее/вес» → контекст «слово|слово», как в Dictionaries.readTrigrams. */
    private fun trigrams(code: String): BigramModel? =
        File("src/main/assets/dict/${code}_tri.txt").takeIf { it.exists() }?.let { f ->
            BigramModel(f.readLines().mapNotNull { l -> val a = l.indexOf(' '); if (a <= 0) null else l.substring(0, a) + "|" + l.substring(a + 1) })
        }

    private fun phrases(code: String, file: String = "phrases_$code.txt") =
        File("src/test/resources/$file").takeIf { it.exists() }?.readLines().orEmpty()
            .map { it.trim().lowercase().split(' ').filter { w -> w.isNotEmpty() } }.filter { it.size > 1 }

    private fun install(lang: KeyboardLanguage, code: String, withModel: Boolean) {
        WordPredictorEngine.installDictionary(
            lang, words(code), bigrams = if (withModel) model(code) else null, trigrams = if (withModel) trigrams(code) else null
        )
    }

    private class Score(val nextTop1: Double, val nextTop3: Double, val prefixTop3: Double, val fixed: Double, val broken: Double)

    private fun score(lang: KeyboardLanguage, list: List<List<String>>): Score {
        var total = 0; var top1 = 0; var top3 = 0
        var prefixTotal = 0; var prefixHit = 0
        var typos = 0; var fixed = 0
        var clean = 0; var broken = 0
        val r = Random(5)
        val keys = lang.keyPositions
        for (phrase in list) for (i in 1 until phrase.size) {
            val prev = phrase[i - 1]
            val prev2 = if (i >= 2) phrase[i - 2] else ""
            val w = phrase[i]
            // следующее слово: основной вариант и все три подсказки
            val snap = WordPredictorEngine.predict("", prev, null, lang, false, "NORMAL", true, previous2 = prev2)
            val next = snap?.chips.orEmpty().map { it.commitWord.lowercase() }
            val primary = snap?.chips?.firstOrNull { it.isPrimary }?.commitWord?.lowercase()
            total++
            if (primary == w) top1++
            if (w in next) top3++
            // продолжение по двум первым буквам
            if (w.length > 3) {
                val c = WordPredictorEngine.predict(w.take(2), prev, null, lang, false, "NORMAL", false, previous2 = prev2)?.chips.orEmpty().map { it.commitWord.lowercase() }
                prefixTotal++
                if (w in c) prefixHit++
            }
            // чистый текст не должен меняться
            val touches = w.map { KeyTouch(((r.nextFloat() - 0.5f) * 0.3f), ((r.nextFloat() - 0.5f) * 0.3f)) }
            clean++
            val own = WordPredictorEngine.autoCorrectionFor(w, lang, "NORMAL", prev, touches)
            if (own != null && !own.equals(w, true)) broken++
            // опечатка: промах по соседней клавише в середине слова, контекст должен помочь её исправить
            if (w.length >= 4) {
                val at = 1 + r.nextInt(w.length - 2)
                val p = keys[w[at]] ?: continue
                val near = keys.entries.filter { it.key != w[at] && it.key.isLetter() && it.value.first == p.first && kotlin.math.abs(it.value.second - p.second) <= 1.01f }
                if (near.isEmpty()) continue
                val wrong = near[r.nextInt(near.size)].key
                val typed = w.substring(0, at) + wrong + w.substring(at + 1)
                val dx = if (keys.getValue(wrong).second > p.second) -0.45f else 0.45f
                val t = typed.indices.map { if (it == at) KeyTouch(dx, 0f) else KeyTouch(0f, 0f) }
                typos++
                if (WordPredictorEngine.autoCorrectionFor(typed, lang, "NORMAL", prev, t).equals(w, true)) fixed++
            }
        }
        return Score(
            100.0 * top1 / total, 100.0 * top3 / total, 100.0 * prefixHit / prefixTotal.coerceAtLeast(1),
            100.0 * fixed / typos.coerceAtLeast(1), 100.0 * broken / clean
        )
    }

    private fun fmt(s: Score) = "следующее: top-1 %.1f%%, top-3 %.1f%%; по 2 буквам top-3 %.1f%%; опечатки исправлены %.1f%%; чистые слова испорчены %.2f%%"
        .format(s.nextTop1, s.nextTop3, s.prefixTop3, s.fixed, s.broken)

    @Test
    fun evaluate() {
        val report = StringBuilder()
        for ((lang, code) in listOf(Languages.RU to "ru", Languages.EN to "en")) {
            val list = phrases(code)
            install(lang, code, false)
            report.appendLine("$code без модели: ${fmt(score(lang, list))}")
            if (model(code) != null) {
                install(lang, code, true)
                report.appendLine("$code с моделью:  ${fmt(score(lang, list))}")
                // большой отложенный набор (в обучении не участвовал): точнее, чем 80 фраз
                val held = phrases(code, "heldout_$code.txt")
                if (held.isNotEmpty()) report.appendLine("$code отложенные (${held.size}): ${fmt(score(lang, held))}")
            }
        }
        val out = File("build/eval/phrases.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
    }

    @Test
    fun tune() {
        if (System.getenv("TUNE") == null) return
        val lang = Languages.RU
        install(lang, "ru", true)
        val list = phrases("ru")
        val t = WordPredictorEngine.Typo
        // испорченное чистое слово намного хуже упущенного исправления
        fun value(s: Score) = s.nextTop3 + s.prefixTop3 + s.fixed - 20.0 * s.broken
        val params = listOf<Triple<String, () -> Float, (Float) -> Unit>>(
            Triple("ctxWeight", { t.ctxWeight }, { t.ctxWeight = it }),
            Triple("ctxCap", { t.ctxCap }, { t.ctxCap = it }),
            Triple("ctxCompletion", { t.ctxCompletion }, { t.ctxCompletion = it }),
            Triple("realWordMaxCost", { t.realWordMaxCost }, { t.realWordMaxCost = it }),
            Triple("realWordMaxEdit", { t.realWordMaxEdit }, { t.realWordMaxEdit = it }),
        )
        var best = value(score(lang, list))
        val log = StringBuilder("start $best\n")
        repeat(3) {
            for ((name, get, set) in params) {
                val base = get()
                var bestV = base
                for (k in listOf(0.6f, 0.8f, 1.25f, 1.6f)) {
                    set(base * k)
                    val v = value(score(lang, list))
                    if (v > best + 0.05) {
                        best = v
                        bestV = base * k
                        log.appendLine("$name=$bestV → $best")
                    }
                }
                set(bestV)
            }
        }
        log.appendLine("итог: " + params.joinToString { "${it.first}=${it.second()}" })
        log.appendLine(fmt(score(lang, list)))
        File("build/eval").mkdirs()
        File("build/eval/tune_ctx.txt").writeText(log.toString())
    }
}
