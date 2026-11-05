package com.expressive.keyboard

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Апострофы в английском: слова из словаря, исправление «dont» → «don't» и свайп, где апостроф прозрачен.
 */
class ContractionsTest {
    private val keyW = 100f
    private val keyH = 190f

    private fun enWords(): List<String> =
        File("src/main/assets/dict/en.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun contractionsAreInsertedAmongFrequentWords() {
        val words = WordPredictorEngine.addEnglishContractions(LinkedHashSet(enWords())).toList()
        assertTrue(words.contains("don't"))
        assertTrue(words.contains("it's"))
        assertTrue(words.contains("you're"))
        // не в конец словаря: иначе считались бы редкими и не выигрывали у «dont»
        assertTrue(words.indexOf("don't") < 400)
        assertTrue(words.indexOf("don't") < words.indexOf("dont"))
    }

    @Test
    fun swipeDecodesContractionWithTransparentApostrophe() {
        val keys = Languages.EN.keyPositions.filterKeys { it.isLetter() }
            .mapValues { (_, p) -> Offset((p.second + 0.5f) * keyW, (p.first + 0.5f) * keyH) }
        val path = listOf('d', 'o', 'n', 't').map { keys.getValue(it) }
        // «dont» в словаре тоже есть, но правильное слово частотнее — как после добавления сокращений
        val lexicon = listOf("don't" to 1f, "dont" to 5f, "done" to 9f)
        assertEquals("don't", GlideDecoder.decode(path, keys, keyW, lexicon, 1).firstOrNull())
    }

    @Test
    fun dontIsCorrectedAndDonTApostropheIsKept() {
        val en = Languages.EN
        val words = WordPredictorEngine.addEnglishContractions(LinkedHashSet(enWords())).toList()
        WordPredictorEngine.installDictionary(en, words, typos = mapOf("dont" to "don't"))
        WordPredictorEngine.clearUserData()
        assertEquals("don't", WordPredictorEngine.autoCorrectionFor("dont", en, "NORMAL"))
        assertNull(WordPredictorEngine.autoCorrectionFor("don't", en, "NORMAL"))
    }
}
