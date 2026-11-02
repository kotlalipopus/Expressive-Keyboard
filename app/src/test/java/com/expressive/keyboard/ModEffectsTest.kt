package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModEffectsTest {
    private val none = FloatArray(0)

    private fun step(s: ParticleSystem, colliders: Colliders? = null, frames: Int = 120) {
        repeat(frames) { s.step(0.016f, 360f, 300f, colliders, none, none, 0, 0f, 0f) }
    }

    @Test
    fun particlesDoNotPassThroughKey() {
        val s = ParticleSystem(ParticleConfig(max = 500, gravityY = 800f, bounce = 0.3f))
        val key = floatArrayOf(0f, 100f, 360f, 140f)
        s.emit(500, 0f, 0f, 360f, 50f, 0f, 0f, 0f)
        step(s, Colliders(listOf(key)))
        assertEquals(500, s.count)
        for (i in 0 until s.count) assertTrue("частица ${s.y(i)} прошла сквозь клавишу", s.y(i) <= 100f)
    }

    @Test
    fun withoutCollidersParticlesFallToTheBottom() {
        val s = ParticleSystem(ParticleConfig(max = 100, gravityY = 800f, bounce = 0f))
        s.emit(100, 0f, 0f, 360f, 10f, 0f, 0f, 0f)
        step(s, frames = 200)
        for (i in 0 until s.count) assertEquals(300f, s.y(i), 1f)
    }

    @Test
    fun lifeRemovesParticles() {
        val s = ParticleSystem(ParticleConfig(max = 100, life = 0.5f))
        s.emit(100, 10f, 10f, 0f, 0f, 0f, 0f, 0f)
        step(s, frames = 40)
        assertEquals(0, s.count)
    }

    @Test
    fun capacityIsNeverExceeded() {
        val s = ParticleSystem(ParticleConfig(max = 50))
        s.emit(40, 0f, 0f, 1f, 1f, 0f, 0f, 0f)
        s.emit(40, 0f, 0f, 1f, 1f, 0f, 0f, 0f)
        assertEquals(50, s.count)
        s.configure(ParticleConfig(max = 10))
        assertEquals(10, s.count)
        step(s, frames = 2)
    }

    @Test
    fun liquidSpreadsApart() {
        val s = ParticleSystem(ParticleConfig(max = 300, liquid = true, liquidRadius = 14f, stiffness = 900f, drag = 1f))
        s.emit(300, 180f, 150f, 2f, 2f, 0f, 0f, 0f)
        step(s, frames = 120)
        var far = 0
        for (i in 0 until s.count) if (kotlin.math.abs(s.x(i) - 180f) > 14f || kotlin.math.abs(s.y(i) - 150f) > 14f) far++
        assertTrue("жидкость осталась в точке: разошлось $far", far > 100)
        for (i in 0 until s.count) assertFalse(s.x(i).isNaN() || s.y(i).isNaN())
    }

    @Test
    fun touchPushesParticlesAway() {
        val s = ParticleSystem(ParticleConfig(max = 1, touchForce = 5000f, touchRadius = 100f, bounds = "wrap"))
        s.add(110f, 100f, 0f, 0f)
        repeat(10) { s.step(0.016f, 360f, 300f, null, floatArrayOf(100f), floatArrayOf(100f), 1, 0f, 0f) }
        assertTrue(s.x(0) > 110f)
    }

    @Test
    fun shaderUniformsAreFound() {
        val u = ModShaders.declared("uniform shader content;\nuniform float iTime; uniform float2 size;\nlayout(color) uniform half4 tint;")
        assertEquals("shader", u["content"])
        assertEquals("float", u["iTime"])
        assertEquals("float2", u["size"])
    }

    @Test
    fun audioLevelsStayInRange() {
        val silence = ShortArray(512)
        assertEquals(0f, ModAudio.levelOf(silence, 512), 0f)
        val tone = ShortArray(512) { (kotlin.math.sin(2 * Math.PI * 800 * it / 16000) * 20000).toInt().toShort() }
        assertTrue(ModAudio.levelOf(tone, 512) > 0.5f)
        val at = ModAudio.goertzel(tone, 512, 800f, 16000f)
        val off = ModAudio.goertzel(tone, 512, 4000f, 16000f)
        assertTrue("800 Гц: $at, 4000 Гц: $off", at > off)
    }

    private fun option(type: String) = ModOption("x", "x", type, null, min = 0f, max = 10f)

    @Test
    fun presetValuesFitTheOptionType() {
        val toggle = option("toggle")
        assertEquals(true, ModConfig.normalize(toggle, 1.0))
        assertEquals(false, ModConfig.normalize(toggle, 0))
        assertEquals(true, ModConfig.normalize(toggle, "true"))
        val slider = option("slider")
        assertEquals(5f, ModConfig.normalize(slider, 5.0))
        assertEquals(2.5f, ModConfig.normalize(slider, "2,5"))
        assertEquals(10f, ModConfig.normalize(slider, 99))
        assertEquals(null, ModConfig.normalize(slider, "мимо"))
        assertEquals("7", ModConfig.normalize(option("text"), 7))
    }

    @Test
    fun showIfRules() {
        val v = mapOf<String, Any?>("fire" to true, "off" to false, "style" to "cat", "n" to 0f)
        assertTrue(modOptionVisible("", v))
        assertTrue(modOptionVisible("fire", v))
        assertFalse(modOptionVisible("!fire", v))
        assertTrue(modOptionVisible("!off", v))
        assertTrue(modOptionVisible("style=cat", v))
        assertFalse(modOptionVisible("style=dog", v))
        assertFalse(modOptionVisible("n", v))
    }
}
