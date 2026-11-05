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
}
