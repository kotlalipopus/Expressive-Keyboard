package com.expressive.keyboard

import kotlin.math.sqrt

internal class ShakeDetector {
    private val jolts = ArrayDeque<Pair<Long, Float>>()
    private var cooldownUntil = 0L

    fun feed(x: Float, y: Float, z: Float, now: Long): Float? {
        val force = sqrt(x * x + y * y + z * z) / 9.80665f
        if (!force.isFinite() || force < 2.3f || now < cooldownUntil) return null
        while (jolts.isNotEmpty() && now - jolts.first().first > 900L) jolts.removeFirst()
        val last = jolts.lastOrNull()
        if (last != null && now - last.first < 80L) return null
        jolts.addLast(now to force)
        if (jolts.size < 3) return null
        val strength = jolts.maxOf { it.second }
        jolts.clear()
        cooldownUntil = now + 1000L
        return strength
    }

    fun reset() {
        jolts.clear()
        cooldownUntil = 0L
    }
}

internal class TypingStats {
    private val characters = ArrayDeque<Long>()
    private val deletes = ArrayDeque<Long>()
    private var last: Long? = null
    private var burst = 0

    fun press(kind: String, now: Long): Map<String, Any> {
        val gap = last?.let { now - it } ?: 0L
        burst = if (last != null && gap > 700L) 1 else burst + 1
        last = now
        when (kind) {
            "letter", "enter", "space" -> characters.addLast(now)
            "backspace" -> deletes.addLast(now)
        }
        while (characters.isNotEmpty() && now - characters.first() > 2000L) characters.removeFirst()
        while (deletes.isNotEmpty() && now - deletes.first() > 2000L) deletes.removeFirst()
        return mapOf("kind" to kind, "cps" to characters.size / 2f, "backspaceCps" to deletes.size / 2f,
            "burst" to burst, "gapMs" to gap)
    }
}
