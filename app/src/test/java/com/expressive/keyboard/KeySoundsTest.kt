package com.expressive.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeySoundsTest {
    @Test
    fun everySoundIsShortAudibleAndClean() {
        for (style in KeySoundStyle.synthesized) for (special in listOf(false, true)) {
            val s = KeySounds.synth(style, special)
            val name = "${style.id}/${if (special) "special" else "normal"}"
            assertTrue("$name: длина ${s.size}", s.size in 1_000..10_000)
            assertTrue("$name: NaN", s.none { it.isNaN() })
            val peak = s.maxOf { abs(it) }
            assertTrue("$name: пик $peak", peak in 0.3f..0.9f)
            // конец затухает — без щелчка при обрыве
            assertTrue("$name: хвост ${s.last()}", abs(s.last()) < 0.01f)
        }
    }

    @Test
    fun packNamesMapKeysToFiles() {
        fun n(label: String, type: KeyType = KeyType.CHARACTER) = KeySounds.packName(KeyModel(label, type))
        org.junit.Assert.assertEquals("a", n("a"))
        org.junit.Assert.assertEquals("q", n("Q"))
        org.junit.Assert.assertEquals("comma", n(","))
        org.junit.Assert.assertEquals("period", n("."))
        org.junit.Assert.assertEquals("num", n("7"))
        org.junit.Assert.assertEquals("space", n(" ", KeyType.SPACE))
        org.junit.Assert.assertEquals("enter", n("", KeyType.ENTER))
        org.junit.Assert.assertEquals("back", n("", KeyType.BACKSPACE))
        org.junit.Assert.assertEquals("shift", n("", KeyType.SHIFT))
        org.junit.Assert.assertEquals("sym", n("?123", KeyType.MODE_SYMBOLS))
        // знаки и не-латиница тоже получают звук буквы
        assertTrue(n("ạ").length == 1 && n("-").length == 1)
    }

    @Test
    fun everyPackHasAllKeySounds() {
        val root = java.io.File("src/main/assets/sounds")
        val need = ('a'..'z').map { it.toString() } + listOf("comma", "period", "num", "space", "shift", "back", "enter", "sym")
        for (style in KeySoundStyle.entries.filter { it.pack != null }) {
            val dir = java.io.File(root, style.pack!!)
            for (name in need) assertTrue("${style.id}: нет $name.ogg", java.io.File(dir, "$name.ogg").exists())
        }
    }
}
