package com.expressive.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

// ============================================================================
// ДИНАМИЧЕСКИЕ ЗОНЫ КЛАВИШ (как Dynamic Type Zones на iPhone)
// Невидимые границы клавиш сдвигаются по набираемому слову: касание у границы «а» и «о» после «пр»
// засчитывается за ту букву, с которой слово вероятнее продолжится. Касание в середине клавиши — всегда она сама.
// ============================================================================

object DynamicZones {
    /** ln P(следующая буква) для набираемого слова; null — зоны выключены (пароль, нет словаря, настройка). */
    @Volatile var model: Map<Char, Float>? = null
        private set

    private var pendingKey: Pair<String, String>? = null
    private var pendingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + BackgroundErrors)

    /** Разброс касаний вокруг центра клавиши — в шагах клавиш. */
    private const val SIGMA = 0.4f
    /** Вес вероятности буквы против геометрии касания: зоны сдвигаются только у самого края клавиш. */
    private const val BETA = 0.35f
    /**
     * Нижняя граница ln P: буквы, которых после этого начала в словаре нет (имена, сленг), не должны становиться
     * недосягаемыми — раньше такую букву удавалось набрать только точно в центр клавиши.
     */
    private const val FLOOR = -4f
    /** Середина клавиши (доля шага от центра) — здесь клавиша срабатывает всегда. */
    private const val CORE = 0.3f

    @Synchronized
    fun disable() {
        pendingJob?.cancel()
        pendingJob = null
        pendingKey = null
        model = null
    }

    /** Новое начало слова: модель из кэша сразу, иначе считается в фоне (пока считается — зон нет). */
    @Synchronized
    fun update(lang: KeyboardLanguage, prefix: String) {
        val key = lang.id to prefix
        if (pendingKey == key) return
        disable()
        val cached = WordPredictorEngine.cachedNextLetters(lang, prefix)
        if (cached != null) {
            model = cached
            return
        }
        pendingKey = key
        pendingJob = scope.launch {
            // Rapid typing supersedes intermediate prefixes before they reach the dictionary.
            delay(12L)
            val job = coroutineContext[Job]!!
            val next = WordPredictorEngine.nextLetters(lang, prefix, job::ensureActive)
            synchronized(this@DynamicZones) {
                if (pendingJob === job) {
                    model = next
                    pendingJob = null
                    pendingKey = null
                }
            }
        }
    }

    class Hit(val label: Char, val center: Offset, val touch: KeyTouch)

    /**
     * Какая буква на самом деле: касание p (в координатах клавиатуры) по клавише typed.
     * null — та же клавиша (или зоны выключены).
     */
    fun resolve(typed: Char, p: Offset, glide: GlideController, layer: LayoutCoordinates): Hit? {
        val m = model ?: return null
        if (m.isEmpty()) return null
        val (keys, keyW) = glide.snapshot(layer) ?: return null
        val own = keys[typed] ?: return null
        val (px, py) = glide.pitch(keys, keyW)
        val ox = (p.x - own.x) / px
        val oy = (p.y - own.y) / py
        if (abs(ox) < CORE && abs(oy) < CORE) return null
        // касание пришло в эту клавишу — значит, оно на ней (с половинами промежутков). Иначе координаты
        // разошлись (клавиатура перестроилась, сдвинулась, анимация): раньше тогда касание «а» печатало «л» или «ш»
        if (abs(ox) > 0.62f || abs(oy) > 0.62f) return null
        fun score(c: Char, dx: Float, dy: Float) =
            -(dx * dx + dy * dy) / (2 * SIGMA * SIGMA) + BETA * max(m[c] ?: FLOOR, FLOOR)
        val ownScore = score(typed, ox, oy)
        var best: Char? = null
        var bestScore = ownScore
        var bestCenter = own
        for ((c, q) in keys) {
            if (c == typed) continue
            val dx = (p.x - q.x) / px
            val dy = (p.y - q.y) / py
            // только соседи, к которым палец действительно близко
            if (dx * dx + dy * dy > 0.75f * 0.75f) continue
            // и только клавиши вплотную к нажатой (по ряду или в соседнем ряду)
            val nx = (q.x - own.x) / px
            val ny = (q.y - own.y) / py
            if (abs(nx) > 1.2f || abs(ny) > 1.2f) continue
            // палец должен быть на стороне этого соседа
            if (nx * ox + ny * oy <= 0f) continue
            val sc = score(c, dx, dy)
            if (sc > bestScore + 0.3f) {
                best = c
                bestScore = sc
                bestCenter = q
            }
        }
        val c = best ?: return null
        return Hit(c, bestCenter, KeyTouch(((p.x - bestCenter.x) / px).coerceIn(-0.5f, 0.5f), ((p.y - bestCenter.y) / py).coerceIn(-0.5f, 0.5f)))
    }
}

/**
 * Быстрый набор двумя пальцами: следующая клавиша нажата, пока прежняя ещё не отпущена. Прежняя срабатывает
 * сразу (буквы не меняются местами) и гаснет — увеличенными больше не остаются обе клавиши.
 */
object Rollover {
    private var owner: Any? = null
    private var action: (() -> Unit)? = null

    /** Новое нажатие; onRoll — что сделать этой клавише, если её «перекатят» (null — нечего). */
    fun press(who: Any, onRoll: (() -> Unit)?) {
        val prev = action
        val prevOwner = owner
        owner = who
        action = onRoll
        if (prev != null && prevOwner !== who) prev()
    }

    /** Нажатие закончилось или перешло в долгое/свайп — его больше не перекатывают. */
    fun done(who: Any) {
        if (owner === who) {
            owner = null
            action = null
        }
    }
}
