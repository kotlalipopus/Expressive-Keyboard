package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class UserLayoutsTest {
    private val rows = listOf(
        KeyRow(listOf(KeyModel("a"), KeyModel("b"), KeyModel("c"))),
        KeyRow(listOf(KeyModel("", KeyType.SPACER, 0.5f), KeyModel("d"), KeyModel("e"), KeyModel("", KeyType.SPACER, 0.5f))),
    )
    private val tail = KeyRow(listOf(KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.5f), KeyModel(" ", KeyType.SPACE, 5f), KeyModel("", KeyType.ENTER, 1.5f)))

    private fun labels(out: List<KeyRow>) = out.map { r -> r.keys.joinToString(" ") { if (it.type == KeyType.SPACER) "_" else UserLayouts.token(it) } }

    private fun encode(vararg r: List<String>) = r.toList().flatMapIndexed { i, x -> (if (i > 0) listOf("§/") else emptyList()) + x }

    @Test
    fun addedKeyGoesBeforeTrailingSpacer() {
        val order = encode(listOf("a", "b", "c"), listOf("d", "e", "ё"), listOf("§SYMBOLS_PAGE", "§SPACE", "§ENTER"))
        val out = UserLayouts.apply(rows, order, tail)
        assertEquals("_ d e ё _", labels(out)[1])
        assertEquals(3, out.size)
    }

    @Test
    fun keyInsertedInMiddleKeepsOrder() {
        val order = encode(listOf("a", "x", "b", "c"), listOf("d", "e"), listOf("§SYMBOLS_PAGE", "§SPACE", "§ENTER"))
        assertEquals("a x b c", labels(UserLayouts.apply(rows, order, tail))[0])
    }

    @Test
    fun serviceKeyMovedPastEndStays() {
        val order = encode(listOf("a", "b", "c"), listOf("d", "e"), listOf("§SYMBOLS_PAGE", "§SPACE", ".", "§ENTER"))
        assertEquals("§SYMBOLS_PAGE §SPACE . §ENTER", labels(UserLayouts.apply(rows, order, tail))[2])
    }

    @Test
    fun oldFormatStillWorks() {
        val out = UserLayouts.apply(rows, listOf("b", "a", "c", "e", "d"), tail)
        assertEquals("b a c", labels(out)[0])
        assertEquals("_ e d _", labels(out)[1])
    }

    @Test
    fun numberRowWithExtraKey() {
        val nums = listOf(KeyRow(listOf(KeyModel("1"), KeyModel("2"))))
        assertEquals("1 2 3", labels(UserLayouts.apply(nums, listOf("1", "2", "3")))[0])
    }

    /** Замена символа применяется, даже если порядок сохранён до включения новой служебной клавиши. */
    @Test
    fun replacementAppliesWhenOrderIsStale() {
        val tailWithEmoji = KeyRow(
            listOf(
                KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.5f),
                KeyModel("emoji", KeyType.STICKERS),
                KeyModel(" ", KeyType.SPACE, 5f),
                KeyModel("", KeyType.ENTER, 1.5f)
            )
        )
        val order = encode(listOf("a", "z", "c"), listOf("d", "e"), listOf("§SYMBOLS_PAGE", "§SPACE", "§ENTER"))
        val out = UserLayouts.apply(rows, order, tailWithEmoji)
        assertEquals("a z c", labels(out)[0])
        assertEquals("§SYMBOLS_PAGE §STICKERS §SPACE §ENTER", labels(out)[2])
    }

    /** Добавленная в нижний ряд клавиша не сдвигает пробел: пробел остаётся пробелом. */
    @Test
    fun addedKeyInBottomRowKeepsSpacePlace() {
        val tail = KeyRow(
            listOf(
                KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.5f),
                KeyModel("globe", KeyType.GLOBE, 1f),
                KeyModel(" ", KeyType.SPACE, 5f),
                KeyModel("", KeyType.ENTER, 1.5f)
            )
        )
        val order = encode(listOf("a", "b", "c"), listOf("d", "e"), listOf("§SYMBOLS_PAGE", "§GLOBE", "★", "§SPACE", "§ENTER"))
        val out = UserLayouts.apply(rows, order, tail)
        assertEquals("§SYMBOLS_PAGE §GLOBE ★ §SPACE §ENTER", labels(out)[2])
        assertEquals(5f, out[2].keys.first { it.type == KeyType.SPACE }.weight)
    }

    /** Включили клавишу 🌐 после сохранения порядка — она возвращается на своё место, пробел остаётся широким. */
    @Test
    fun newServiceKeyReturnsToItsPlace() {
        val tail = KeyRow(
            listOf(
                KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.5f),
                KeyModel("globe", KeyType.GLOBE, 1f),
                KeyModel(" ", KeyType.SPACE, 5f),
                KeyModel("", KeyType.ENTER, 1.5f)
            )
        )
        val order = encode(listOf("a", "b", "c"), listOf("d", "e"), listOf("§SYMBOLS_PAGE", "§SPACE", "§ENTER"))
        val out = UserLayouts.apply(rows, order, tail)
        assertEquals("§SYMBOLS_PAGE §GLOBE §SPACE §ENTER", labels(out)[2])
        assertEquals(5f, out[2].keys.first { it.type == KeyType.SPACE }.weight)
    }

    /** Замена знака у пробела сохраняет узкое место, а не растягивает клавишу на всю ширину буквы. */
    @Test
    fun replacedPunctuationKeepsPlaceWidth() {
        val tail = KeyRow(
            listOf(
                KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.5f),
                KeyModel(",", weight = 0.9f),
                KeyModel(" ", KeyType.SPACE, 5f),
                KeyModel("", KeyType.ENTER, 1.5f)
            )
        )
        val order = encode(listOf("a", "b", "c"), listOf("d", "e"), listOf("§SYMBOLS_PAGE", "?", "§SPACE", "§ENTER"))
        val out = UserLayouts.apply(rows, order, tail)
        assertEquals("§SYMBOLS_PAGE ? §SPACE §ENTER", labels(out)[2])
        assertEquals(0.9f, out[2].keys[1].weight)
    }
}
