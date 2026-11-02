package com.expressive.keyboard

/**
 * Набор по-бирмански в Unicode. На экране ေ и ြ стоят слева от согласной, но в Unicode хранятся после неё:
 * «нэ» — это န + ေ. Многие печатают в порядке, как видят (ေ, потом န), и без перестановки получают
 * ေ◌န с пунктирным кружком. Этот код решает, что вставить, чтобы в поле был правильный Unicode.
 */
object MyanmarInput {
    private const val E = 'ေ'      // ေ
    private const val MEDIAL_R = 'ြ' // ြ
    private const val STACK = '္'  // ္
    private const val MEDIALS = "ျြွှ" // ျ ြ ွ ှ в порядке Unicode

    /** Удалить delete символов перед курсором и вставить insert; pending — сколько букв ждёт следующую согласную. */
    data class Edit(val delete: Int, val insert: String, val pending: Int)

    private fun isConsonant(c: Char) = c in 'က'..'အ' || c == 'ဿ'
    private fun isMedial(c: Char) = c in MEDIALS
    private fun isPrefix(c: Char) = c == E || c == MEDIAL_R

    /** ြ раньше ေ: так их хранит Unicode. */
    private fun canonical(run: String) = run.toList().sortedBy { if (it == MEDIAL_R) 0 else 1 }.joinToString("")

    /**
     * @param before текст перед курсором
     * @param visual порядок «как на экране»: ေ и ြ всегда ждут следующую согласную.
     * Иначе ждут, только если им не к чему относиться (начало слова), а после согласной остаются на месте.
     * @param pending сколько букв ждало согласную после прошлого нажатия
     */
    fun edit(before: String, typed: String, visual: Boolean, pending: Int): Edit {
        if (typed.length != 1) return Edit(0, typed, 0)
        val c = typed[0]
        // ждущие буквы: только если они всё ещё стоят перед курсором (курсор могли переставить, букву стереть)
        val run = if (pending > 0 && before.length >= pending && before.takeLast(pending).all(::isPrefix)) before.takeLast(pending) else ""
        val last = before.lastOrNull()
        val prev = before.getOrNull(before.length - 2)
        when {
            isConsonant(c) -> {
                if (run.isNotEmpty()) return Edit(run.length, c + canonical(run), 0)
                // вторая согласная стека идёт до ေ: က္ေ + တ → က္တေ
                if (last == E && prev == STACK) return Edit(1, "$c$E", 0)
                return Edit(0, typed, 0)
            }
            isPrefix(c) && run.isNotEmpty() -> return Edit(0, typed, run.length + 1)
            // слог с ေ уже готов: ြ после него — медиаль этого слога
            c == MEDIAL_R && last == E && prev != null && (isConsonant(prev) || isMedial(prev)) -> return medial(before, c)
            isPrefix(c) && visual -> return Edit(0, typed, 1)
            isPrefix(c) && !attaches(last) -> return Edit(0, typed, 1)
            isMedial(c) -> return medial(before, c)
            c == STACK && last == E -> return Edit(1, "$c$E", 0)
        }
        return Edit(0, typed, 0)
    }

    private fun attaches(c: Char?) = c != null && (isConsonant(c) || isMedial(c) || c == STACK)

    /** Медиали идут после согласной в порядке ျ ြ ွ ှ и всегда до ေ. */
    private fun medial(before: String, c: Char): Edit {
        val hasE = before.lastOrNull() == E
        val head = if (hasE) before.dropLast(1) else before
        val tail = head.takeLastWhile(::isMedial)
        val base = head.getOrNull(head.length - tail.length - 1)
        if (base == null || !isConsonant(base)) return Edit(0, c.toString(), 0)
        val sorted = (tail + c).toList().distinct().sortedBy { MEDIALS.indexOf(it) }.joinToString("")
        return Edit(tail.length + (if (hasE) 1 else 0), sorted + if (hasE) E.toString() else "", 0)
    }
}
