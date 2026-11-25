package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm tra 5 thành phần kiến trúc Telex:
 * 1. Structured Syllable Model (C1, V, C2, T, M) & gõ freestyle
 * 2. Dynamic Tone Positioning (chuẩn kiểu mới: hòa, hủy), tone overwriting & Smart w
 * 3. Phonotactic Validator (loại âm tiết phi tiếng Việt)
 * 4. Raw Buffer Auto-Restore & Double-tap Undo / Bypass
 * 5. Backspace theo ký tự hiển thị trên Syllable Model & Diff Rendering
 */
class TelexTest {

    @Test
    fun hornAfterFinalConsonant() {
        assertEquals("trường", Telex.compose("truongwf"))
        assertEquals("nước", Telex.compose("nuocws"))
    }

    @Test
    fun hornAfterFinalConsonantWithoutTone() {
        assertEquals("trương", Telex.compose("truongw"))
        assertEquals("nươc", Telex.compose("nuocw"))
    }

    @Test
    fun quDigraphKeepsPlainU() {
        // 'quốc' dùng oo (ô); kiểm tra u trong qu không nhận dấu râu
        assertEquals("quớc", Telex.compose("quocws"))
        assertEquals("quý", Telex.compose("quys"))
        assertEquals("quỳnh", Telex.compose("quynhf"))
        assertEquals("quyền", Telex.compose("quyeenf"))
    }

    @Test
    fun existingRules() {
        assertEquals("ư", Telex.compose("uw"))
        assertEquals("ơ", Telex.compose("ow"))
        assertEquals("ă", Telex.compose("aw"))
        assertEquals("đ", Telex.compose("dd"))
        assertEquals("tiếng", Telex.compose("tieengs"))
        assertEquals("bàn", Telex.compose("banf"))
    }

    @Test
    fun freestyleSyllableModelOrder() {
        assertEquals("tối", Telex.compose("toiso"))
        assertEquals("tối", Telex.compose("tosio"))
        assertEquals("tối", Telex.compose("toois"))
        assertEquals("cấu", Telex.compose("cauas"))
        assertEquals("cấy", Telex.compose("caysa"))
        assertEquals("nếu", Telex.compose("neuse"))
        assertEquals("muối", Telex.compose("musoio"))
        assertEquals("giá", Telex.compose("gisa"))
        assertEquals("giữa", Telex.compose("giuwax"))
        assertEquals("giường", Telex.compose("giuongwf"))
        assertEquals("TỐI", Telex.compose("TOISO"))
        assertEquals("Hoàn", Telex.compose("Hoafn"))
        assertEquals("Đà", Telex.compose("DDaf"))
    }

    @Test
    fun dynamicToneNewStyleAndOverwriting() {
        // Chuẩn kiểu mới: 2 nguyên âm mở -> dấu trên chữ đầu (hòa, hủy, khỏe); đóng -> chữ sau (hoàn)
        assertEquals("hòa", Telex.compose("hoaf"))
        assertEquals("hòe", Telex.compose("hoef"))
        assertEquals("khỏe", Telex.compose("khoer"))
        assertEquals("hủy", Telex.compose("huyr"))
        assertEquals("thủy", Telex.compose("thuyr"))
        assertEquals("túy", Telex.compose("tuys"))

        // Thêm phụ âm cuối -> tự dịch dấu từ o sang a
        val s = Telex.Session()
        assertEquals("h", s.input('h'))
        assertEquals("ho", s.input('o'))
        assertEquals("hoa", s.input('a'))
        assertEquals("hòa", s.input('f'))
        assertEquals("hoàn", s.input('n'))

        // Ghi đè thanh điệu
        assertEquals("hòa", Telex.compose("hoasf"))
        assertEquals("hóa", Telex.compose("hoafs"))
        assertEquals("hoan", Telex.compose("hoafnz"))
    }

    @Test
    fun smartWAndContextMapping() {
        assertEquals("ươ", Telex.compose("uow"))
        assertEquals("dược", Telex.compose("duowcj"))
        assertEquals("được", Telex.compose("dduowcj"))
        assertEquals("được", Telex.compose("dduocwj"))
        assertEquals("dược", Telex.compose("duocwj"))
        assertEquals("nước", Telex.compose("nwcs"))
        assertEquals("nước", Telex.compose("nwsc"))
        assertEquals("nước", Telex.compose("nuowcs"))
        assertEquals("nức", Telex.compose("nuwcs"))
        assertEquals("tướng", Telex.compose("tusongw"))
        assertEquals("tương", Telex.compose("tuwowng"))
        assertEquals("hươu", Telex.compose("huouw"))
        assertEquals("cười", Telex.compose("cuoiwf"))
        assertEquals("gửi", Telex.compose("guirw"))
        assertEquals("mưa", Telex.compose("muaw"))
        assertEquals("chơi", Telex.compose("choiw"))
        assertEquals("quơ", Telex.compose("quow"))
    }

    @Test
    fun phonotacticValidatorAndAutoRestore() {
        // t-e-x -> "tẽ", thêm 't' (C2="t" không đi được với dấu ngã) -> khôi phục nguyên văn "text"
        val s = Telex.Session()
        assertEquals("t", s.input('t'))
        assertEquals("te", s.input('e'))
        assertEquals("tẽ", s.input('x'))
        assertEquals("text", s.input('t'))
        assertTrue(s.isBypassed)

        // Phụ âm đầu không hợp lệ / từ tiếng Anh phổ biến
        assertEquals("window", Telex.compose("window"))
        assertEquals("word", Telex.compose("word"))
        assertEquals("was", Telex.compose("was"))
        assertEquals("fast", Telex.compose("fast"))
        assertEquals("just", Telex.compose("just"))
        assertEquals("zoom", Telex.compose("zoom"))
        assertEquals("program", Telex.compose("program"))
        assertEquals("street", Telex.compose("street"))
        assertEquals("sweet", Telex.compose("sweet"))
        assertEquals("ghost", Telex.compose("ghost"))
        assertEquals("center", Telex.compose("center"))
        assertEquals("general", Telex.compose("general"))
        assertEquals("soft", Telex.compose("soft"))
        assertEquals("android", Telex.compose("android"))
        assertEquals("cool", Telex.compose("cool"))
        assertEquals("about", Telex.compose("about"))
        assertEquals("house", Telex.compose("house"))
        assertEquals("data", Telex.compose("data"))
        assertEquals("meme", Telex.compose("meme"))
        assertEquals("banana", Telex.compose("banana"))
        assertEquals("batf", Telex.compose("batf"))
        assertEquals("batsf", Telex.compose("batsf"))
    }

    @Test
    fun commitTimeValidationRestoresEnglishWords() {
        fun commitWord(raw: String): String {
            val s = Telex.Session()
            for (ch in raw) s.input(ch)
            return s.commit()
        }

        // Giữa chừng "să" chờ phụ âm, khi ngắt từ phải trả về "saw"
        assertEquals("să", Telex.compose("saw"))
        assertEquals("saw", commitWord("saw"))
        assertEquals("law", commitWord("law"))
        assertEquals("deep", commitWord("deep"))
        assertEquals("keep", commitWord("keep"))
        assertEquals("meet", commitWord("meet"))
        assertEquals("yes", commitWord("yes"))
        assertEquals("pics", commitWord("pics"))

        // Từ tiếng Việt hợp lệ giữ nguyên khi commit
        assertEquals("hoàn", commitWord("hoafn"))
        assertEquals("nước", commitWord("nuocws"))
        assertEquals("lịch", commitWord("lichj"))
    }

    @Test
    fun doubleTapUndoAndBypass() {
        assertEquals("aa", Telex.compose("aaa"))
        assertEquals("aaa", Telex.compose("aaaa"))
        assertEquals("taan", Telex.compose("taaan"))
        assertEquals("as", Telex.compose("ass"))
        assertEquals("ass", Telex.compose("asss"))
        assertEquals("has", Telex.compose("hass"))
        assertEquals("seen", Telex.compose("seeen"))
        assertEquals("xoong", Telex.compose("xooong"))
        assertEquals("aw", Telex.compose("aww"))
        assertEquals("ow", Telex.compose("oww"))
        assertEquals("uw", Telex.compose("uww"))
        assertEquals("dd", Telex.compose("ddd"))
        assertEquals("nw", Telex.compose("nww"))
        assertEquals("tuow", Telex.compose("tuoww"))
        assertEquals("hoaf", Telex.compose("hoaff"))
    }

    @Test
    fun backspaceRemovesLastVisibleSyllableCharacterAndShiftsTone() {
        // hoàn -> hòa -> hò -> h -> ""
        val s = Telex.Session()
        "hoafn".forEach { s.input(it) }
        assertEquals("hoàn", s.display)
        assertEquals("hòa", s.backspace())
        assertEquals("hò", s.backspace())
        assertEquals("h", s.backspace())
        assertEquals("", s.backspace())
        assertFalse(s.isBypassed)

        // nước -> nướ -> nứ -> n -> ""
        "nuocws".forEach { s.input(it) }
        assertEquals("nước", s.display)
        assertEquals("nướ", s.backspace())
        assertEquals("nứ", s.backspace())
        assertEquals("n", s.backspace())
        assertEquals("", s.backspace())

        // giữa -> giữ -> gĩ -> g -> ""
        "giuwax".forEach { s.input(it) }
        assertEquals("giữa", s.display)
        assertEquals("giữ", s.backspace())
        assertEquals("gĩ", s.backspace())
        assertEquals("g", s.backspace())
        assertEquals("", s.backspace())

        // Xóa hết từ bypass sẽ reset để từ mới gõ tiếng Việt bình thường
        "text".forEach { s.input(it) }
        assertEquals("text", s.display)
        assertEquals("tex", s.backspace())
        assertEquals("te", s.backspace())
        assertEquals("t", s.backspace())
        assertEquals("", s.backspace())
        assertFalse(s.isBypassed)
        assertEquals("tối", "toiso".fold("") { _, c -> s.input(c) })
    }

    @Test
    fun diffRenderingMinimalEdits() {
        assertEquals(Telex.Diff(keepPrefix = 1, delete = 2, insert = "oàn"), Telex.diff("hòa", "hoàn"))
        assertEquals(Telex.Diff(keepPrefix = 1, delete = 3, insert = "òa"), Telex.diff("hoàn", "hòa"))
        assertEquals(Telex.Diff(keepPrefix = 2, delete = 0, insert = "a"), Telex.diff("hò", "hòa"))
        assertEquals(Telex.Diff(keepPrefix = 2, delete = 1, insert = ""), Telex.diff("hòa", "hò"))
        assertEquals(Telex.Diff(keepPrefix = 1, delete = 1, insert = "ext"), Telex.diff("tẽ", "text"))
    }
}
