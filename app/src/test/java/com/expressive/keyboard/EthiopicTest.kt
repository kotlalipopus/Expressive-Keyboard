package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class EthiopicTest {
    @Test
    fun words() {
        assertEquals("ሰላም", Ethiopic.compose("selam"))
        assertEquals("አማርኛ", Ethiopic.compose("amarNa"))
        assertEquals("አማርኛ", Ethiopic.compose("amarnya"))
        assertEquals("ኢትዮጵያ", Ethiopic.compose("ityoPya"))
        assertEquals("ቡና", Ethiopic.compose("buna"))
        assertEquals("ሸ", Ethiopic.compose("she"))
        assertEquals("ቸ", Ethiopic.compose("che"))
        assertEquals("ሌ", Ethiopic.compose("lie"))
        assertEquals("ሌ", Ethiopic.compose("lE"))
        assertEquals("ሏ", Ethiopic.compose("lwa"))
        assertEquals("ጠና", Ethiopic.compose("Tena"))
        assertEquals("ል", Ethiopic.compose("l"))
        // готовые слоги (удержание клавиши) проходят как есть
        assertEquals("ሰላም", Ethiopic.compose("ሰlam"))
    }

    @Test
    fun backspaceIsPrefix() {
        // стирание — тот же разбор без последней буквы: «selam» → «sela» даёт ሰላ
        assertEquals("ሰላ", Ethiopic.compose("sela"))
        assertEquals("ሰል", Ethiopic.compose("sel"))
    }
}
