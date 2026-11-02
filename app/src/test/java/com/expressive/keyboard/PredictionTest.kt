package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PredictionTest {
    private val rare = RareWords(
        listOf("аааа" to 1, "респондент" to 30, "респондентов" to 20, "респ" to 5, "ресурс" to 2, "сон" to 3).sortedBy { it.first }, 1000
    )

    @Test
    fun rareCompletionsAreOrderedByRankAndKeepPrefix() {
        assertEquals(listOf("респондентов" to 1020, "респондент" to 1030), rare.completions("респ", 3))
        assertEquals(emptyList<Pair<String, Int>>(), rare.completions("zzz", 3))
    }

    @Test
    fun noiseIsNotSuggested() {
        assertEquals(emptyList<Pair<String, Int>>(), rare.completions("аа", 3))
        assertTrue(RareWords.looksLikeNoise("а-а-а-а"))
        assertTrue(RareWords.looksLikeNoise("aaaa"))
        assertFalse(RareWords.looksLikeNoise("длинношеее".dropLast(2)))
        assertFalse(RareWords.looksLikeNoise("кто-то"))
    }

    @Test
    fun realDictionaryCompletes() {
        val entries = RareWords.parse(File("src/main/assets/dict/ru_more.txt").readLines())
        val r = RareWords(entries, 50_000)
        val out = r.completions("респонд", 3)
        assertTrue(out.toString(), out.isNotEmpty() && out.all { it.first.startsWith("респонд") })
    }

    @Test
    fun decayNeverUnlearnsWords() {
        val e = WordPredictorEngine
        assertEquals(50, e.decayedCount(100, 1, true))
        assertEquals(e.LEARNED_TYPO, e.decayedCount(20, 5, true))
        assertEquals(3, e.decayedCount(4, 3, true))
        assertEquals(0, e.decayedCount(1, 1, true))
        assertEquals(0, e.decayedCount(4, 3, false))
    }
}
