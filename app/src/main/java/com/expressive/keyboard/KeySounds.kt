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
enum class KeySoundStyle(val id: String, val title: String, val pack: String? = null) {
    SYSTEM("SYSTEM", "Системный"),
    SOFT("SOFT", "Мягкий"),
    BUBBLE("BUBBLE", "Пузырёк"),
    WOOD("WOOD", "Дерево"),
    MECHANICAL("MECHANICAL", "Механика"),
    GLASS("GLASS", "Стекло"),
    CLICKY("CLICKY", "Щелчок"),
    THOCK("THOCK", "Глухой стук"),
    TYPEWRITER("TYPEWRITER", "Печатная машинка"),
    MEMBRANE("MEMBRANE", "Мембрана"),
    POP("POP", "Лёгкий тик"),
    CUSTOM("CUSTOM", "Свой файл"),
    /** Записанные наборы из assets/sounds/<pack>: у каждой клавиши свой звук, у части наборов есть и звук отпускания (*_up). */
    PACK_0("PACK_0", "Клавиатура 1", "p0"),
    PACK_1("PACK_1", "Клавиатура 2", "p1"),
    PACK_2("PACK_2", "Клавиатура 3", "p2"),
    PACK_3("PACK_3", "Клавиатура 4", "p3"),
    PACK_4("PACK_4", "Клавиатура 5", "p4"),
    PACK_5("PACK_5", "Клавиатура 6", "p5");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
        /** Синтезированные — то, что можно выбрать без файла. */
        val synthesized = listOf(SOFT, BUBBLE, WOOD, MECHANICAL, GLASS, CLICKY, THOCK, TYPEWRITER, MEMBRANE, POP)
    }
}

object KeySounds {
    private const val RATE = 44_100
    /** Меняется, если поменялись формулы звуков: старые файлы в cache перезаписываются. */
    private const val VERSION = 1

    private val LETTERS = ('a'..'z').toList()

    /**
     * Имя файла в наборе для клавиши: a…z, comma, period, num (цифры), space, shift, back, enter,
     * sym (переключатели и прочие служебные клавиши). Знаки и буквы не-латиницы берут звук буквы по коду символа.
     */
    fun packName(key: KeyModel): String = when (key.type) {
        KeyType.SPACE -> "space"
        KeyType.ENTER -> "enter"
        KeyType.BACKSPACE -> "back"
        KeyType.SHIFT -> "shift"
        KeyType.CHARACTER -> {
            val c = key.label.firstOrNull()?.lowercaseChar()
            when {
                c == null -> "a"
                c in 'a'..'z' -> c.toString()
                c == ',' -> "comma"
                c == '.' -> "period"
                c.isDigit() -> "num"
                else -> LETTERS[c.code % LETTERS.size].toString()
            }
        }
        else -> "sym"
    }

    /** Загружает все *.ogg набора из assets в SoundPool: имя файла без расширения → id звука. */
    fun loadPack(context: Context, sp: android.media.SoundPool, pack: String): Map<String, Int> {
        val am = context.applicationContext.assets
        val names = runCatching { am.list("sounds/$pack") }.getOrNull().orEmpty()
        val out = HashMap<String, Int>()
        for (n in names) {
            if (!n.endsWith(".ogg")) continue
            val id = runCatching { am.openFd("sounds/$pack/$n").use { sp.load(it, 1) } }.getOrDefault(0)
            if (id != 0) out[n.removeSuffix(".ogg")] = id
        }
        return out
    }

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
            KeySoundStyle.CLICKY -> 70
            KeySoundStyle.THOCK -> 110
            KeySoundStyle.TYPEWRITER -> 120
            KeySoundStyle.MEMBRANE -> 50
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
                // кликающий переключатель («синий»): острый щелчок, второй — при ударе колпачка, и короткий корпус
                KeySoundStyle.CLICKY -> {
                    val noise = rnd.nextFloat() * 2f - 1f
                    lp += 0.65f * (noise - lp)
                    lp2 += 0.12f * (noise - lp2)
                    val hp = lp - lp2
                    val click1 = hp * exp(-t / 0.0010f)
                    val t2 = t - 0.009f
                    val click2 = if (t2 < 0f) 0f else hp * 0.8f * exp(-t2 / 0.0012f)
                    val t3 = t - 0.011f
                    val body = if (t3 < 0f) 0f else sin(2 * PI * 260f * pitch * t3).toFloat() * 0.5f * exp(-t3 / 0.010f)
                    1.2f * click1 + click2 + body
                }
                // глубокий «thock»: низкий тон с падающей высотой и мягкий шорох
                KeySoundStyle.THOCK -> {
                    val f = (115f + 90f * exp(-t / 0.008f)) * pitch
                    phase += 2 * PI * f / RATE
                    val noise = rnd.nextFloat() * 2f - 1f
                    lp += 0.12f * (noise - lp)
                    (0.9f * sin(phase).toFloat() * exp(-t / 0.028f) + 0.35f * lp * exp(-t / 0.006f)) * min(1f, t / 0.0006f)
                }
                // печатная машинка: удар литеры, металлический звон и глухой стук каретки
                KeySoundStyle.TYPEWRITER -> {
                    val noise = rnd.nextFloat() * 2f - 1f
                    lp += 0.55f * (noise - lp)
                    lp2 += 0.1f * (noise - lp2)
                    val strike = (lp - lp2) * exp(-t / 0.0015f)
                    val ring = (sin(2 * PI * 1850f * pitch * t).toFloat() * 0.35f +
                        sin(2 * PI * 3170f * pitch * t).toFloat() * 0.2f) * exp(-t / 0.018f)
                    val thud = sin(2 * PI * 130f * pitch * t).toFloat() * 0.6f * exp(-t / 0.02f)
                    (1.3f * strike + ring + thud) * min(1f, t / 0.0004f)
                }
                // мембранная клавиатура ноутбука: мягкий приглушённый шорох
                KeySoundStyle.MEMBRANE -> {
                    val noise = rnd.nextFloat() * 2f - 1f
                    lp += 0.3f * (noise - lp)
                    val tone = sin(2 * PI * 320f * pitch * t).toFloat() * exp(-t / 0.007f)
                    (0.9f * lp * exp(-t / 0.007f) + 0.5f * tone) * min(1f, t / 0.0008f)
                }
                // короткий «тик» с падающей высотой
                KeySoundStyle.POP -> {
                    val f = (900f + 700f * exp(-t / 0.004f)) * pitch
                    phase += 2 * PI * f / RATE
                    sin(phase).toFloat() * exp(-t / 0.012f) * min(1f, t / 0.0005f)
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
