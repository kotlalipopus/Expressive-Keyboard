package com.expressive.keyboard

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class MyanmarInputTest {
    /** Печатает буквы по очереди, как клавиатура, и возвращает текст поля. */
    private fun type(keys: String, visual: Boolean = true, start: String = ""): String {
        var text = start
        var pending = 0
        for (c in keys) {
            val e = MyanmarInput.edit(text, c.toString(), visual, pending)
            text = text.dropLast(e.delete) + e.insert
            pending = e.pending
        }
        return text
    }

    @Test
    fun vowelTypedFirstMovesAfterConsonant() {
        // случай с фотографии: ေ, потом န
        assertEquals("နေ", type("ေန"))
        assertEquals("နေ", type("ေန", visual = false))
    }

    @Test
    fun wordInVisualOrder() {
        // ေ က ာ င ် း → ကောင်း
        assertEquals("ကောင်း", type("ေကာင်း"))
        // ြ မ န ် မ ာ → မြန်မာ
        assertEquals("မြန်မာ", type("ြမန်မာ"))
    }

    @Test
    fun midWordInVisualOrder() {
        // န, потом ေ က: ေ относится к က
        assertEquals("နကေ", type("နေက"))
    }

    @Test
    fun bothPrefixesKeepUnicodeOrder() {
        assertEquals("ကြေ", type("ေြက"))
        assertEquals("ကြေ", type("ြေက"))
    }

    @Test
    fun medialAfterSyllableGoesBeforeVowel() {
        assertEquals("ကျေ", type("ေကျ"))
        assertEquals("ကြေ", type("ေကြ"))
        assertEquals("ကျွ", type("ကွျ"))
    }

    @Test
    fun stackedConsonantStaysBeforeVowel() {
        assertEquals("က္တေ", type("ေက္တ"))
    }

    @Test
    fun logicalOrderKeepsTypedOrder() {
        // န ေ က при логическом порядке: နေက
        assertEquals("နေက", type("နေက", visual = false))
        assertEquals("ကြေ", type("ကြေ", visual = false))
    }

    @Test
    fun vowelWithoutConsonantWaitsAfterSpace() {
        assertEquals("က နေ", type("က ေန", visual = false))
    }

    @Test
    fun otherLanguagesCharactersPassThrough() {
        assertEquals("abc", type("abc"))
    }

    @Test
    fun erasedPendingVowelIsNotMoved() {
        // ေ стёрли: перед курсором уже нет ждущей буквы
        val e = MyanmarInput.edit("က", "န", true, 1)
        assertEquals(MyanmarInput.Edit(0, "န", 0), e)
    }

    @Test
    fun neighborGap() {
        val a = Rect(0f, 0f, 30f, 40f)
        assertEquals(6f, GlassMerge.gapBetween(a, Rect(36f, 0f, 66f, 40f)), 0f)
        assertEquals(8f, GlassMerge.gapBetween(a, Rect(0f, 48f, 30f, 88f)), 0f)
        assertEquals(8f, GlassMerge.gapBetween(a, Rect(36f, 48f, 66f, 88f)), 0f)
    }
}
