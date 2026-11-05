package com.expressive.keyboard

import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Звуки нажатия, синтезированные прямо в приложении: файлов в APK нет, лицензий тоже. Каждый звук — два варианта:
 * для букв и пониже, «тяжелее» — для пробела, Enter и ⌫. Готовые WAV кладутся в cache и грузятся в SoundPool.
 */
enum class KeySoundStyle(val id: String, val title: String) {
    SYSTEM("SYSTEM", "Системный"),
    SOFT("SOFT", "Мягкий"),
    BUBBLE("BUBBLE", "Пузырёк"),
    WOOD("WOOD", "Дерево"),
    MECHANICAL("MECHANICAL", "Механика"),
    GLASS("GLASS", "Стекло"),
    CUSTOM("CUSTOM", "Свой файл");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
        /** Синтезированные — то, что можно выбрать без файла. */
        val synthesized = listOf(SOFT, BUBBLE, WOOD, MECHANICAL, GLASS)
    }
}

object KeySounds {
    private const val RATE = 44_100
    /** Меняется, если поменялись формулы звуков: старые файлы в cache перезаписываются. */
    private const val VERSION = 1

    /** Файлы звука: [0] — буквы, [1] — пробел, Enter, ⌫. Создаются при первом обращении. */
    fun files(context: Context, style: KeySoundStyle): List<File>? {
        if (style !in KeySoundStyle.synthesized) return null
        val dir = File(context.cacheDir, "key_sounds").apply { mkdirs() }
        return listOf(false, true).map { special ->
            val f = File(dir, "${style.id.lowercase()}_${if (special) "s" else "n"}_v$VERSION.wav")
            if (!f.exists() || f.length() < 64) runCatching { f.writeBytes(wav(synth(style, special))) }
            f
        }
    }

    internal fun synth(style: KeySoundStyle, special: Boolean): FloatArray {
        val pitch = if (special) 0.8f else 1f
        val lenMs = when (style) {
            KeySoundStyle.SOFT -> 45
            KeySoundStyle.BUBBLE -> 70
            KeySoundStyle.WOOD -> 90
            KeySoundStyle.MECHANICAL -> 70
            KeySoundStyle.GLASS -> 160
            else -> 40
        } * (if (special) 1.2f else 1f)
        val n = (RATE * lenMs / 1000f).toInt()
        val out = FloatArray(n)
        val rnd = java.util.Random(7L + style.ordinal)
        var lp = 0f
        var lp2 = 0f
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val v: Float = when (style) {
                // глухой мягкий «тук»: низкий тон с падающей высотой и чуть шороха в начале
                KeySoundStyle.SOFT -> {
                    val f = (170f + 230f * exp(-t / 0.006f)) * pitch
                    phase += 2 * PI * f / RATE
                    val noise = rnd.nextFloat() * 2f - 1f
                    lp += 0.18f * (noise - lp)
                    (0.75f * sin(phase).toFloat() * exp(-t / 0.011f) + 0.5f * lp * exp(-t / 0.003f))
                }
                // «блуп»: тон быстро взлетает вверх, как лопнувший пузырёк
                KeySoundStyle.BUBBLE -> {
                    val f = (380f + 900f * (1f - exp(-t / 0.018f))) * pitch
                    phase += 2 * PI * f / RATE
                    val env = min(1f, t / 0.003f) * exp(-t / 0.02f)
                    sin(phase).toFloat() * env
                }
                // деревянный брусок: основной тон и негармонический обертон, который гаснет быстрее
                KeySoundStyle.WOOD -> {
                    val f = 620f * pitch
                    val a = sin(2 * PI * f * t).toFloat() * exp(-t / 0.022f)
                    val b = 0.45f * sin(2 * PI * f * 2.76 * t).toFloat() * exp(-t / 0.007f)
                    val c = 0.2f * sin(2 * PI * f * 5.4 * t).toFloat() * exp(-t / 0.003f)
                    (a + b + c) * min(1f, t / 0.0008f)
                }
                // механическая клавиатура: щелчок переключателя и через 14 мс — удар колпачка о плату
                KeySoundStyle.MECHANICAL -> {
                    val noise = rnd.nextFloat() * 2f - 1f
                    // щелчок — высокий шум (разность фильтров убирает низы)
                    lp += 0.5f * (noise - lp)
                    lp2 += 0.08f * (noise - lp2)
                    val click = (lp - lp2) * exp(-t / 0.0018f)
                    val t2 = t - 0.014f
                    val thock = if (t2 < 0f) 0f else {
                        val f = 210f * pitch
                        (sin(2 * PI * f * t2).toFloat() * 0.7f + 0.35f * lp2) * exp(-t2 / 0.012f)
                    }
                    1.3f * click + thock
                }
                // лёгкий стеклянный «дзынь»: два чистых тона с мягкой атакой
                KeySoundStyle.GLASS -> {
                    val f = 1568f * pitch
                    val env = min(1f, t / 0.002f)
                    (sin(2 * PI * f * t).toFloat() * exp(-t / 0.05f) + 0.35f * sin(2 * PI * f * 2.0 * t).toFloat() * exp(-t / 0.025f) +
                        0.2f * sin(2 * PI * f * 2.98 * t).toFloat() * exp(-t / 0.012f)) * env
                }
                else -> 0f
            }
            out[i] = v
        }
        // без щелчка в конце: короткое затухание, затем выравнивание громкости между стилями
        val fade = min(n, RATE * 4 / 1000)
        for (i in 0 until fade) out[n - 1 - i] *= i / fade.toFloat()
        var peak = 1e-6f
        for (v in out) peak = max(peak, kotlin.math.abs(v))
        val gain = (if (style == KeySoundStyle.GLASS) 0.55f else 0.85f) / peak
        for (i in out.indices) out[i] *= gain
        return out
    }

    /** 16-битный моно WAV. */
    private fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }
}
