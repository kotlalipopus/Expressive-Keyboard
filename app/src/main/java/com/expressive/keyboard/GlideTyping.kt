package com.expressive.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ============================================================================
// 14. ПЕЧАТЬ БЕЗ ОТРЫВА ПАЛЬЦА (GLIDE / SWIPE TYPING)
// Палец ведётся от буквы к букве, по отпусканию траектория сравнивается
// с «идеальными» ломаными слов словаря (форма + проход рядом с каждой буквой
// + частотность). Лучшее слово вставляется, ещё два — в строку подсказок.
// ============================================================================

@Stable
class GlideController {
    private val keys = HashMap<String, CoordsHolder>()
    /** След пальца в координатах слоя клавиатуры; читается только при отрисовке. */
    val trail = mutableStateListOf<Offset>()

    fun register(label: String, holder: CoordsHolder) {
        keys[label] = holder
    }

    fun unregister(label: String, holder: CoordsHolder) {
        if (keys[label] === holder) keys.remove(label)
    }

    /** Центры буквенных клавиш и типичная ширина клавиши. */
    fun snapshot(layer: LayoutCoordinates): Pair<Map<Char, Offset>, Float>? {
        if (!layer.isAttached) return null
        val map = HashMap<Char, Offset>()
        val widths = ArrayList<Int>()
        for ((label, h) in keys) {
            val c = h.coords ?: continue
            if (label.length != 1 || !label[0].isLetter()) continue
            val p = layer.safePositionOf(c) ?: continue
            map[label[0]] = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f)
            widths += c.size.width
        }
        if (map.size < 10) return null
        widths.sort()
        return map to widths[widths.size / 2].toFloat()
    }
}

/**
 * Росчерк ли это, а не смазанное нажатие. При быстрой печати палец съезжает с клавиши на соседнюю, и такой мазок
 * (п→а→п) распознавался как слово «пап». Настоящий росчерк длится дольше и проходит минимум через три буквы.
 */
internal fun looksLikeGlide(path: List<Offset>, keys: Map<Char, Offset>, keyWidth: Float, durationMs: Long): Boolean {
    if (durationMs < 140L) return false
    val near = keyWidth * 0.5f
    var hit = 0
    for (c in keys.values) {
        var close = false
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val abx = b.x - a.x
            val aby = b.y - a.y
            val len2 = abx * abx + aby * aby
            val t = if (len2 <= 0f) 0f else (((c.x - a.x) * abx + (c.y - a.y) * aby) / len2).coerceIn(0f, 1f)
            if ((Offset(a.x + abx * t, a.y + aby * t) - c).getDistance() <= near) {
                close = true
                break
            }
        }
        if (close && ++hit >= 3) return true
    }
    return false
}

/**
 * Распознавание слова по росчерку. Буквы слова по порядку «прикладываются» к траектории (каждая — к ближайшей
 * точке не раньше предыдущей буквы), плюс форма росчерка, его длина, повороты пальца (каждый резкий поворот
 * должен быть у какой-то буквы слова) и частота слова. Расстояния — в шагах клавиш: по горизонтали в ширинах,
 * по вертикали в высотах рядов, поэтому высокие клавиши не «растягивают» промах по вертикали.
 * Числа подобраны на проверке app/src/test (GlideEval).
 */
object GlideDecoder {
    internal object P {
        /** Разброс прохода пальца мимо буквы — в шагах клавиш. */
        @JvmField var sigma = 0.7f
        /** Разброс начала и конца росчерка у первой и последней буквы. */
        @JvmField var endSigma = 0.54f
        @JvmField var lm = 0.45f
        @JvmField var shape = 3.0f
        @JvmField var corner = 2.9f
        @JvmField var length = 1.6f
        /** Первая и последняя буквы — не дальше этого от начала и конца росчерка. */
        @JvmField var endRadius = 1.35f
    }

    private val ALIASES = mapOf('ё' to 'е', 'ъ' to 'ь', 'й' to 'и')

    fun resample(src: List<Offset>, n: Int): Array<Offset> {
        if (src.size == 1) return Array(n) { src[0] }
        var total = 0f
        for (i in 1 until src.size) total += (src[i] - src[i - 1]).getDistance()
        if (total <= 0f) return Array(n) { src[0] }
        val step = total / (n - 1)
        val out = ArrayList<Offset>(n)
        out += src[0]
        var acc = 0f
        var prev = src[0]
        var i = 1
        while (i < src.size && out.size < n) {
            val cur = src[i]
            val d = (cur - prev).getDistance()
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                val q = prev + (cur - prev) * t
                out += q
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out += src.last()
        return out.toTypedArray()
    }

    /** Шаг клавиш по горизонтали и по вертикали (между рядами) по их центрам. */
    internal fun pitch(keys: Map<Char, Offset>, keyW: Float): Pair<Float, Float> {
        val pts = keys.values.toList()
        val dx = ArrayList<Float>()
        val rows = ArrayList<Float>()
        for ((pi, p) in pts.withIndex()) {
            var best = Float.MAX_VALUE
            for ((qi, q) in pts.withIndex()) {
                if (qi == pi) continue
                if (abs(q.y - p.y) < keyW * 0.3f) {
                    val d = abs(q.x - p.x)
                    if (d > keyW * 0.3f && d < best) best = d
                }
            }
            if (best < Float.MAX_VALUE) dx += best
            if (rows.none { abs(it - p.y) < keyW * 0.3f }) rows += p.y
        }
        dx.sort()
        rows.sort()
        val px = if (dx.isEmpty()) keyW else dx[dx.size / 2]
        val gaps = (1 until rows.size).map { rows[it] - rows[it - 1] }.sorted()
        val py = if (gaps.isEmpty()) px * 1.5f else gaps[gaps.size / 2]
        return px to py
    }

    private fun d2(a: Offset, b: Offset): Float {
        val x = a.x - b.x
        val y = a.y - b.y
        return x * x + y * y
    }

    /**
     * До limit слов, лучшее первым. lexicon: слово → редкость (ln места в частотном списке; 0 — самое частое).
     * context — бонус слову за предыдущее слово (в тех же единицах).
     */
    fun decode(
        path: List<Offset>,
        keys: Map<Char, Offset>,
        keyW: Float,
        lexicon: List<Pair<String, Float>>,
        limit: Int = 3,
        context: ((String) -> Float)? = null
    ): List<String> {
        if (path.size < 2 || keyW <= 0f || keys.isEmpty()) return emptyList()
        val (px, py) = pitch(keys, keyW)
        // всё — в шагах клавиш
        val norm = keys.mapValues { (_, o) -> Offset(o.x / px, o.y / py) }
        val raw = path.map { Offset(it.x / px, it.y / py) }
        var len = 0f
        for (i in 1 until raw.size) len += (raw[i] - raw[i - 1]).getDistance()
        val m = (len / 0.22f).toInt().coerceIn(16, 96)
        val pts = resample(raw, m)
        val start = pts.first()
        val end = pts.last()
        val shapePts = resample(raw, SHAPE_N)
        val corners = corners(pts)
        val sig2 = 2f * P.sigma * P.sigma
        val end2 = 2f * P.endSigma * P.endSigma
        val r2 = P.endRadius * P.endRadius
        val ideal = ArrayList<Offset>(24)
        val dp = FloatArray(m)
        val pref = FloatArray(m)
        val scored = ArrayList<Pair<String, Float>>()
        for ((word, prior) in lexicon) {
            if (word.length < 2) continue
            val f = norm[word[0]] ?: ALIASES[word[0]]?.let { norm[it] } ?: continue
            if (d2(f, start) > r2) continue
            val lc = word[word.length - 1]
            val l = norm[lc] ?: ALIASES[lc]?.let { norm[it] } ?: continue
            if (d2(l, end) > r2) continue
            ideal.clear()
            var ok = true
            for (ch in word) {
                val k = norm[ch] ?: ALIASES[ch]?.let { norm[it] }
                if (k == null) {
                    ok = false
                    break
                }
                if (ideal.isEmpty() || ideal.last() != k) ideal += k
            }
            if (!ok || ideal.size < 2) continue
            // длина: росчерк заметно короче или длиннее пути по буквам — не то слово
            var il = 0f
            for (i in 1 until ideal.size) il += (ideal[i] - ideal[i - 1]).getDistance()
            val ratio = ln((len + 0.3f) / (il + 0.3f))
            if (abs(ratio) > 1.1f) continue
            // буквы по порядку прикладываются к траектории
            val n = ideal.size
            val first = ideal[0]
            for (j in 0 until m) dp[j] = if (j == 0) d2(first, pts[0]) / end2 else Float.MAX_VALUE
            for (i in 1 until n) {
                var run = Float.MAX_VALUE
                for (j in 0 until m) {
                    if (dp[j] < run) run = dp[j]
                    pref[j] = run
                }
                val k = ideal[i]
                if (i == n - 1) {
                    for (j in 0 until m - 1) dp[j] = Float.MAX_VALUE
                    dp[m - 1] = pref[m - 1] + d2(k, pts[m - 1]) / end2
                } else {
                    for (j in 0 until m) dp[j] = if (pref[j] == Float.MAX_VALUE) Float.MAX_VALUE else pref[j] + d2(k, pts[j]) / sig2
                }
            }
            val align = dp[m - 1]
            if (align > 40f) continue
            // повороты пальца — у букв слова
            var turn = 0f
            for (c in corners) {
                var best = Float.MAX_VALUE
                for (k in ideal) best = min(best, d2(k, pts[c]))
                val d = sqrt(best)
                if (d > 0.6f) turn += d - 0.6f
            }
            // форма: росчерк и путь по буквам, разбитые на равные части, близки точка к точке
            val ip = resample(ideal, SHAPE_N)
            var sum = 0f
            for (i in 0 until SHAPE_N) sum += (shapePts[i] - ip[i]).getDistance()
            val shape = sum / SHAPE_N
            val score = align + P.shape * shape + P.corner * turn + P.length * ratio * ratio + P.lm * prior - (context?.invoke(word) ?: 0f)
            scored += word to score
        }
        scored.sortBy { it.second }
        return scored.take(limit).map { it.first }
    }

    private const val SHAPE_N = 24

    /** Резкие повороты траектории (больше ~60°): там палец обычно меняет направление у буквы. */
    private fun corners(pts: Array<Offset>): List<Int> {
        val w = 2
        val out = ArrayList<Int>()
        var lastAngle = 0f
        for (i in w until pts.size - w) {
            val a = pts[i] - pts[i - w]
            val b = pts[i + w] - pts[i]
            val la = a.getDistance()
            val lb = b.getDistance()
            if (la < 0.12f || lb < 0.12f) continue
            val cos = (a.x * b.x + a.y * b.y) / (la * lb)
            if (cos > 0.5f) continue
            val angle = 1f - cos
            // соседние точки одного поворота — один поворот
            if (out.isNotEmpty() && i - out.last() <= w) {
                if (angle > lastAngle) {
                    out[out.lastIndex] = i
                    lastAngle = angle
                }
                continue
            }
            out += i
            lastAngle = angle
        }
        return out
    }
}

/** След пальца: хвост сужается и тает к началу. */
@Composable
internal fun BoxScope.GlideTrailLayer(glide: GlideController, color: Color, enabled: Boolean) {
    if (!enabled) return
    Canvas(Modifier.matchParentSize()) {
        val pts = glide.trail
        val n = pts.size
        if (n < 2) return@Canvas
        val from = max(1, n - 60)
        val span = (n - from).coerceAtLeast(1)
        val maxW = 7.dp.toPx()
        for (i in from until n) {
            val t = (i - from + 1f) / span
            drawLine(color.copy(alpha = color.alpha * (0.15f + 0.75f * t)), pts[i - 1], pts[i], maxW * (0.3f + 0.7f * t), StrokeCap.Round)
        }
    }
}
