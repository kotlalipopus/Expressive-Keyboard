package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineCalculatorTest {
    @Test
    fun evaluatesTheExpressionImmediatelyBeforeEquals() {
        assertEquals("322", Calc.resultAfterEquals("199+123="))
        assertEquals("14", Calc.resultAfterEquals("Итого: 2 + 3×4="))
        assertEquals("1.5", Calc.resultAfterEquals("x = (2,5 + 0,5)/2="))
    }

    @Test
    fun ignoresIncompleteInvalidAndUnboundedExpressions() {
        assertNull(Calc.resultAfterEquals("199+123"))
        assertNull(Calc.resultAfterEquals("3/0="))
        assertNull(Calc.resultAfterEquals("7+*2="))
        assertNull(Calc.resultAfterEquals("abc199+123="))
        assertNull(Calc.resultAfterEquals("1".repeat(Calc.EDITOR_LOOKBACK) + "+2="))
    }
}
