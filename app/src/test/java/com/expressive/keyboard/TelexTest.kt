package com.expressive.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TelexTest {
    @After
    fun reset() {
        Telex.modernTone = false
    }

    private fun check(vararg pairs: Pair<String, String>) {
        for ((raw, expected) in pairs) assertEquals("compose(\"$raw\")", expected, Telex.compose(raw))
    }

    @Test
    fun composesLikeGboard() {
        Telex.modernTone = false
        check(
            "tieengs" to "tiếng",
            "Vieetj" to "Việt",
            "Nam" to "Nam",
            "dduwowcj" to "được",
            "duwowcj" to "dược",
            "nguwowif" to "người",
            "nguoiwf" to "người",
            "huowng" to "hương",
            "truongwf" to "trường",
            "toanf" to "toàn",
            "hoaf" to "hòa",
            "hoafn" to "hoàn",
            "thuys" to "thúy",
            "quas" to "quá",
            "quys" to "quý",
            "gias" to "giá",
            "gif" to "gì",
            "khoer" to "khỏe",
            "muaw" to "mưa",
            "muwa" to "mưa",
            "xoawn" to "xoăn",
            "aa" to "â",
            "aaa" to "aa",
            "ass" to "as",
            "asss" to "ass",
            "hoawcj" to "hoặc",
            "ddi" to "đi",
            "ddd" to "dd",
            "w" to "ư",
            "ww" to "w",
            "tw" to "tư",
            "Dduwowcj" to "Được",
            "DDUWOWCJ" to "ĐƯỢC",
            "chuyeenr" to "chuyển",
            "nghieemj" to "nghiệm",
            "yeeu" to "yêu",
            "tuooir" to "tuổi",
            "ngoaif" to "ngoài",
            "tienge" to "tiêng",
            "cana" to "cân",
            "ddieeuf" to "điều",
            "ddaay" to "đây",
            "rooif" to "rồi",
            "sawcs" to "sắc",
            "tiếng" to "tiếng",
            "toas" to "tóa",
            "toasn" to "toán",
            "lamf" to "làm",
            "muoons" to "muốn",
            "ddoongj" to "động",
            "hocj" to "học",
            "hoocj" to "hộc",
            "bieets" to "biết",
            "khuyeens" to "khuyến",
            "ddeef" to "đề",
            "quaanr" to "quẩn",
            "quynhf" to "quỳnh",
            "Ddaij" to "Đại",
            "uw" to "ư",
            "thuw" to "thư",
            "aw" to "ă",
            "ow" to "ơ",
            "uww" to "uw",
            "ViEetj" to "ViỆt",
            "TIEENGS" to "TIẾNG",
            "chaof" to "chào",
            "nhaats" to "nhất",
            "gif" to "gì",
            "ginf" to "gìn",
            "thuowr" to "thuở",
            "quowr" to "quở",
            "thuowngr" to "thưởng",
            "huowng" to "hương",
            "thuowngf" to "thường"
        )
    }

    @Test
    fun modernTonePlacement() {
        Telex.modernTone = true
        check(
            "hoaf" to "hoà",
            "thuys" to "thuý",
            "khoer" to "khoẻ",
            "hoafn" to "hoàn",
            "maif" to "mài"
        )
    }

    @Test
    fun finalizeRestoresNonVietnameseWords() {
        Telex.modernTone = false
        val cases = listOf(
            "windows" to "windows",
            "fix" to "fix",
            "next" to "next",
            "google" to "google",
            "boss" to "boss",
            "user" to "user",
            "wifi" to "wifi",
            "tieengs" to "tiếng",
            "hello" to "hello",
            "Vieetj" to "Việt",
            "dduwowcj" to "được",
            "gif" to "gì",
            "Gif" to "Gì",
            "ginf" to "gìn",
            "gis" to "gí",
            "thuowr" to "thuở",
            "boss" to "boss",
            "gift" to "gift"
        )
        for ((raw, expected) in cases) assertEquals("finalize(\"$raw\")", expected, Telex.finalize(raw))
    }

    /** Từ [raw] bấm ⌫ liên tục, ghi lại chữ hiện ra sau mỗi lần (kể cả lúc đầu và lúc hết). */
    private fun backspaceSteps(raw: String): List<String> {
        var r = raw
        val out = mutableListOf(Telex.compose(r))
        var guard = 0
        while (r.isNotEmpty() && guard++ < 50) {
            r = Telex.backspace(r)
            out.add(Telex.compose(r))
        }
        return out
    }

    @Test
    fun backspaceDeletesLastVisibleLetterKeepingTone() {
        Telex.modernTone = false
        assertEquals(listOf("tiếng", "tiến", "tiế", "tí", "t", ""), backspaceSteps("tieengs"))
        assertEquals(listOf("toán", "tóa", "tó", "t", ""), backspaceSteps("toasn"))
        assertEquals(listOf("được", "đượ", "đự", "đ", ""), backspaceSteps("dduwowcj"))
        assertEquals(listOf("người", "ngườ", "ngừ", "ng", "n", ""), backspaceSteps("nguoiwf"))
        assertEquals(listOf("thường", "thườn", "thườ", "thừ", "th", "t", ""), backspaceSteps("thuowngf"))
        assertEquals(listOf("Việt", "Việ", "Vị", "V", ""), backspaceSteps("Vieetj"))
        assertEquals(listOf("ĐƯỢC", "ĐƯỢ", "ĐỰ", "Đ", ""), backspaceSteps("DDUWOWCJ"))
        // chữ gõ bằng hai phím (aa, dd) mất cả hai; gõ đúp hoàn tác (ass → as) cũng vậy
        assertEquals(listOf("â", ""), backspaceSteps("aa"))
        assertEquals(listOf("đi", "đ", ""), backspaceSteps("ddi"))
        assertEquals(listOf("as", "a", ""), backspaceSteps("ass"))
    }

    @Test
    fun backspaceRemovesExactlyOneLetterEachTime() {
        Telex.modernTone = false
        val words = listOf(
            "tieengs", "Vieetj", "nguoiwf", "dduwowcj", "huowngs", "truwowngf", "chuyeenr", "nghieemj", "khuyeens",
            "quynhf", "hoafn", "thuys", "gif", "ginf", "thuowr", "thuowngr", "ddaay", "yeeus", "tuooir", "xoawn"
        )
        for (raw in words) {
            val steps = backspaceSteps(raw)
            for (i in 1 until steps.size) {
                assertEquals("$raw: ${steps[i - 1]} → ${steps[i]}", steps[i - 1].length - 1, steps[i].length)
            }
            assertEquals("$raw ends empty", "", steps.last())
        }
    }
}
