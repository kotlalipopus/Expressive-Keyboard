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
            "nhaats" to "nhất"
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
            "dduwowcj" to "được"
        )
        for ((raw, expected) in cases) assertEquals("finalize(\"$raw\")", expected, Telex.finalize(raw))
    }
}
