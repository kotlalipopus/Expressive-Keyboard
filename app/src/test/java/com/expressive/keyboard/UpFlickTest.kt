package com.expressive.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpFlickTest {
    @Test fun accidentalFastMovementDoesNotSelectSymbol() {
        assertFalse(isIntentionalUpFlick(0f, -40f, 20f, 40L))
        assertFalse(isIntentionalUpFlick(0f, -10f, 20f, 100L))
        assertFalse(isIntentionalUpFlick(30f, -40f, 20f, 100L))
    }

    @Test fun deliberateUpwardSwipeSelectsSymbol() {
        assertTrue(isIntentionalUpFlick(5f, -40f, 20f, 100L))
    }
}
