package com.expressive.keyboard

import org.junit.Assert.*
import org.junit.Test

class PluginInputEventsTest {
    @Test fun shakeRequiresSeparateJoltsAndObservesCooldown() {
        val detector = ShakeDetector()
        assertNull(detector.feed(0f, 0f, 25f, 100))
        assertNull(detector.feed(0f, 0f, 32f, 130))
        assertNull(detector.feed(0f, 0f, 28f, 230))
        assertEquals(28f / 9.80665f, requireNotNull(detector.feed(0f, 0f, 25f, 330)), 0.01f)
        assertNull(detector.feed(0f, 0f, 30f, 1000))
        detector.reset()
        assertNull(detector.feed(0f, 0f, 30f, 1500))
        assertNull(detector.feed(0f, 0f, 30f, 2600))
    }

    @Test fun typingCountsDeletesSeparatelyAndExpiresOldSamples() {
        val stats = TypingStats()
        stats.press("letter", 100)
        val second = stats.press("backspace", 200)
        assertEquals(0.5f, second["cps"])
        assertEquals(0.5f, second["backspaceCps"])
        assertEquals(2, second["burst"])
        val late = stats.press("shift", 2500)
        assertEquals(0f, late["cps"])
        assertEquals(0f, late["backspaceCps"])
        assertEquals(1, late["burst"])
    }

    @Test fun pluginLinkChecksVersionsNumericallyAndRewritesGitHubFileLinks() {
        assertTrue(PluginLink.newer("1.10", "1.9"))
        assertFalse(PluginLink.newer("v1.2.0", "1.2"))
        assertEquals("https://raw.githubusercontent.com/team/mod/main/plugin.js",
            PluginLink.normalize("https://github.com/team/mod/blob/main/plugin.js").toExternalForm())
        assertTrue(runCatching { PluginLink.normalize("http://example.org/mod.js") }.isFailure)
    }

    @Test fun archiveCopyEnforcesTheLimitBeforeWriting() {
        val output = java.io.ByteArrayOutputStream()
        assertEquals(128L, PluginArchive.copy(ByteArray(128).inputStream(), output, 128L))
        val limited = java.io.ByteArrayOutputStream()
        assertTrue(runCatching { PluginArchive.copy(ByteArray(129).inputStream(), limited, 128L) }.isFailure)
        assertEquals(0, limited.size())
    }
}
