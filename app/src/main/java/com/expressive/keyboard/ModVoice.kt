package com.expressive.keyboard

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.EnvironmentalReverb
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class ModVoice(
    private val path: String,
    private val loop: Boolean,
    private val gapless: Boolean,
    private val onEnd: () -> Unit
) {
    private var current: MediaPlayer? = null
    private var next: MediaPlayer? = null
    private var connected = false
    private var ready = false
    private var reverb: EnvironmentalReverb? = null
    private var volume = 1f
    private var pan = 0f
    private var rate = 1f
    private var pitch = 1f
    private var distance = 0f
    private var maxDistance = 20f
    private var send = 0f
    private var room = 0.5f
    private var configuredRoom = -1f

    fun update(options: Map<*, *>) {
        fun number(key: String, value: Float, low: Float, high: Float) =
            (options[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(low, high) ?: value
        volume = number("volume", volume, 0f, 1f)
        pan = number("pan", pan, -1f, 1f)
        rate = number("rate", rate, 0.25f, 4f)
        pitch = number("pitch", pitch, 0.25f, 4f)
        distance = number("distance", distance, 0f, 10000f)
        maxDistance = number("maxDistance", maxDistance, 1f, 10000f)
        send = number("reverb", send, 0f, 1f)
        room = number("reverbSize", room, 0f, 1f)
        if (ready) current?.let { apply(it, speed = true) }
        if (connected) next?.let { apply(it, speed = false) }
    }

    fun start() {
        prepare(queued = false)
    }

    private fun prepare(queued: Boolean) {
        val player = MediaPlayer()
        if (queued) next = player else current = player
        try {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            player.setDataSource(path)
            player.isLooping = loop && !gapless
            player.setOnErrorListener { failed, _, _ ->
                if (current === failed) finish()
                else if (next === failed) discardNext()
                true
            }
            player.setOnCompletionListener(::completed)
            player.setOnPreparedListener { prepared ->
                if (current === prepared) {
                    ready = true
                    runCatching { prepared.start() }.onFailure { finish() }
                    if (current === prepared) {
                        apply(prepared, speed = true)
                        if (loop && gapless) prepare(queued = true)
                    }
                } else if (next === prepared) {
                    apply(prepared, speed = false)
                    connected = runCatching { requireNotNull(current).setNextMediaPlayer(prepared) }.isSuccess
                    if (!connected) discardNext()
                }
            }
            player.prepareAsync()
        } catch (error: Exception) {
            if (queued) discardNext()
            else {
                stop()
                throw error
            }
        }
    }

    private fun completed(player: MediaPlayer) {
        if (player !== current) return
        if (!loop || !gapless) {
            finish()
            return
        }
        player.release()
        current = null
        ready = false
        if (connected) {
            current = next
            next = null
            connected = false
            ready = true
            current?.let { apply(it, speed = true) }
            prepare(queued = true)
        } else {
            discardNext()
            runCatching { start() }.onFailure { finish() }
        }
    }

    private fun apply(player: MediaPlayer, speed: Boolean) {
        val angle = (pan + 1) * (Math.PI / 4).toFloat()
        val attenuation = if (maxDistance <= 1f) 1f else {
            val d = distance.coerceIn(1f, maxDistance)
            ((1f / d - 1f / maxDistance) / (1f - 1f / maxDistance)).coerceIn(0f, 1f)
        }
        val gain = volume * attenuation
        runCatching { player.setVolume(gain * (sqrt(2f) * cos(angle)).coerceAtMost(1f), gain * (sqrt(2f) * sin(angle)).coerceAtMost(1f)) }
        if (speed) runCatching { player.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(rate) }
        runCatching {
            if (send > 0f) {
                val effect = reverb ?: EnvironmentalReverb(0, 0).also { reverb = it }
                if (room != configuredRoom) {
                    effect.decayTime = (400 + 5600 * room).toInt()
                    effect.reverbLevel = (-300 + 300 * room).toInt().toShort()
                    effect.enabled = true
                    configuredRoom = room
                }
                player.attachAuxEffect(effect.id)
            }
            player.setAuxEffectSendLevel(send)
        }
    }

    private fun finish() {
        stop()
        onEnd()
    }

    private fun discardNext() {
        next?.release()
        next = null
        connected = false
    }

    fun stop() {
        current?.release()
        current = null
        ready = false
        discardNext()
        reverb?.release()
        reverb = null
    }
}
