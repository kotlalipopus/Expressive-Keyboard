package com.expressive.keyboard

/**
 * Очистка диктовки без ИИ и интернета, когда ключа нет: убирает слова-паразиты («э-э», «ну», «um»), заикания и
 * подряд повторённые слова или пары слов, чинит пробелы и заглавную. Оговорки («в два, нет, в три») по правилам
 * надёжно не поймать — их чистит только ИИ по ключу.
 */
object LocalSpeechClean {
    private val RU_FILLERS = listOf(
        "э+м*", "э-э+", "м+м*", "а+м+", "х+м+", "ну+", "типа", "как бы", "это самое", "так сказать", "короче", "в общем-то", "собственно"
    )
    private val EN_FILLERS = listOf("u+m+", "u+h+m*", "e+r+m*", "a+h+", "h+m+", "you know", "i mean", "sort of", "kind of")

    private fun fillersFor(lang: String): List<String> = when (lang) {
        "ru", "uk", "be" -> RU_FILLERS
        "en" -> EN_FILLERS
        else -> emptyList()
    }

    fun clean(text: String, lang: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        for (f in fillersFor(lang)) {
            // слово-паразит целиком, вместе с запятой после него; «ну» в начале фразы и «как бы» внутри — оба случая
            t = Regex("(?<![\\p{L}\\p{N}-])$f(?![\\p{L}\\p{N}-])\\s*,?", RegexOption.IGNORE_CASE).replace(t, " ")
        }
        t = dropRepeats(t)
        t = t.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("([,;:])\\s*(?=[.!?])"), "")
            .replace(Regex(",\\s*,+"), ",")
            .trim()
            .trimStart(',', ' ')
        if (t.isEmpty()) return t
        // фраза после очистки начинается с заглавной, если и исходная начиналась так
        if (text.trim().first().isUpperCase()) t = t.replaceFirstChar { it.uppercase() }
        return t
    }

    /** «я я пошёл» → «я пошёл»; «в магазин в магазин» → «в магазин». */
    private fun dropRepeats(text: String): String {
        val w = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        fun key(s: String) = s.trim(',', '.', '!', '?', ';', ':').lowercase()
        var i = 0
        while (i < w.size) {
            // сначала пары слов, потом одиночные
            if (i + 3 < w.size && key(w[i]) == key(w[i + 2]) && key(w[i + 1]) == key(w[i + 3]) && key(w[i]).isNotEmpty()) {
                w.removeAt(i); w.removeAt(i)
                continue
            }
            if (i + 1 < w.size && key(w[i]).isNotEmpty() && key(w[i]) == key(w[i + 1]) && !key(w[i]).all { it.isDigit() }) {
                w.removeAt(i)
                continue
            }
            i++
        }
        return w.joinToString(" ")
    }
}
