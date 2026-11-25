package com.expressive.keyboard

import kotlin.math.min

/**
 * Bộ máy gõ tiếng Việt Telex dựa trên mô hình âm tiết có cấu trúc (C1, V, C2, T, M):
 * - C1: phụ âm đầu (`b, c, ch, d, đ, g, gh, gi, h, k, kh, l, m, n, ng, ngh, nh, p, ph, qu, r, s, t, th, tr, v, x`)
 * - V : vần/cụm nguyên âm kèm metadata dấu mũ/râu/trăng (`M`)
 * - C2: phụ âm cuối (`c, ch, m, n, ng, nh, p, t`)
 * - T : thanh điệu (`0` ngang, `1` sắc, `2` huyền, `3` hỏi, `4` ngã, `5` nặng), đặt theo chuẩn kiểu mới (`hòa`, `hủy`)
 * - Kèm bộ lọc chính tả tiếng Việt, lưu `raw` để tự động khôi phục (Auto-Restore), double-tap undo + bypass,
 *   xóa từng ký tự hiển thị cuối khi Backspace và tính diff tối thiểu.
 */
object Telex {

    enum class VowelMod { NONE, CIRCUMFLEX, BREVE, HORN }

    /** Thay đổi tối thiểu từ chuỗi đang hiển thị sang chuỗi mới: giữ [keepPrefix], xóa [delete], chèn [insert]. */
    data class Diff(val keepPrefix: Int, val delete: Int, val insert: String)

    private data class VowelSlot(
        val base: Char,
        val upper: Boolean,
        var mod: VowelMod = VowelMod.NONE,
        val explicitBase: Boolean = true
    )

    private const val VOWEL_BASES = "aeiouy"
    private const val BASE = "aăâeêioôơuưy"
    private val TONES = listOf(
        "aăâeêioôơuưy",
        "áắấéếíóốớúứý", // 1: s
        "àằầèềìòồờùừỳ", // 2: f
        "ảẳẩẻểỉỏổởủửỷ", // 3: r
        "ãẵẫẽễĩõỗỡũữỹ", // 4: x
        "ạặậẹệịọộợụựỵ"  // 5: j
    )
    private const val TONE_KEYS = "sfrxj"

    private val VALID_C1 = setOf(
        "", "b", "c", "ch", "d", "g", "gh", "gi", "h", "k", "kh",
        "l", "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r", "s", "t", "th", "tr", "v", "x"
    )
    private val STOP_CODAS = setOf("c", "ch", "p", "t")

    /** Các vần đóng cho phép kèm tập phụ âm cuối C2 hợp lệ ở lúc commit. */
    private val V_CODA_MAP: Map<String, Set<String>> = mapOf(
        "a" to setOf("c", "ch", "m", "n", "ng", "nh", "p", "t"),
        "ă" to setOf("c", "m", "n", "ng", "p", "t"),
        "â" to setOf("c", "m", "n", "ng", "p", "t"),
        "e" to setOf("c", "m", "n", "ng", "p", "t"),
        "ê" to setOf("c", "ch", "m", "n", "ng", "nh", "p", "t"),
        "i" to setOf("ch", "m", "n", "nh", "p", "t"),
        "o" to setOf("c", "m", "n", "ng", "p", "t"),
        "ô" to setOf("c", "m", "n", "ng", "p", "t"),
        "ơ" to setOf("c", "m", "n", "ng", "p", "t"),
        "u" to setOf("c", "m", "n", "ng", "p", "t"),
        "ư" to setOf("c", "m", "n", "ng", "p", "t"),
        "oa" to setOf("c", "ch", "m", "n", "ng", "nh", "p", "t"),
        "oă" to setOf("c", "m", "n", "ng", "p", "t"),
        "oe" to setOf("n", "t"),
        "uâ" to setOf("n", "ng", "t"),
        "uê" to setOf("ch", "n", "nh", "t"),
        "uy" to setOf("ch", "n", "nh", "p", "t"),
        "iê" to setOf("c", "m", "n", "ng", "p", "t"),
        "yê" to setOf("m", "n", "t"),
        "uô" to setOf("c", "m", "n", "ng", "t"),
        "ươ" to setOf("c", "m", "n", "ng", "p", "t"),
        "uyê" to setOf("n", "t")
    )

    /** Tiền tố vần đóng tạm thời khi đang gõ dở trước khi gõ phím `e`/`o`/`w` tiếp theo. */
    private val MID_ONLY_CODA_MAP: Map<String, Set<String>> = mapOf(
        "ie" to setOf("c", "m", "n", "ng", "p", "t"),
        "ye" to setOf("m", "n", "t"),
        "uo" to setOf("c", "m", "n", "ng", "p", "t"),
        "ưo" to setOf("c", "m", "n", "ng", "p", "t"),
        "uye" to setOf("n", "t")
    )

    /** Vần mở hợp lệ (không cần C2, giữ nguyên lúc commit). */
    private val VALID_OPEN_V = setOf(
        "a", "e", "i", "o", "u", "y", "ê", "ô", "ơ", "ư",
        "ai", "ao", "au", "ay", "âu", "ây", "eo", "êu", "ia", "iu",
        "oa", "oe", "oi", "ôi", "ơi", "ua", "ui", "uơ", "uy", "ưa", "ưi", "ưu",
        "iêu", "oai", "oao", "oay", "oeo", "uây", "uôi", "uya", "uyu", "ươi", "ươu", "yêu"
    )

    /** Vần mở tạm thời khi đang gõ dở (cần thêm nguyên âm lướt, C2 hoặc dấu `^`/`w` mới hoàn chỉnh ở commit). */
    private val MID_ONLY_OPEN_V = setOf(
        "ă", "â", "oă", "uâ", "iê", "yê", "uô", "ươ", "uyê",
        "eu", "ie", "ye", "uo", "ưo", "uye", "uoi", "ưoi", "uou", "ưou", "ieu", "yeu"
    )

    fun diff(old: String, new: String): Diff {
        val limit = min(old.length, new.length)
        var lcp = 0
        while (lcp < limit && old[lcp] == new[lcp]) lcp++
        while (lcp > 0 && old[lcp - 1].isHighSurrogate()) lcp--
        return Diff(lcp, old.length - lcp, new.substring(lcp))
    }

    /** Chuyển đổi một chuỗi phím thô thành chuỗi hiển thị đang gõ (tiện cho unit test và gọi một lần). */
    fun compose(raw: String): String {
        val s = Session()
        for (ch in raw) s.input(ch)
        return s.display
    }

    private fun modifiedVowel(base: Char, mod: VowelMod): Char = when (base) {
        'a' -> when (mod) {
            VowelMod.CIRCUMFLEX -> 'â'
            VowelMod.BREVE -> 'ă'
            else -> 'a'
        }
        'e' -> if (mod == VowelMod.CIRCUMFLEX) 'ê' else 'e'
        'o' -> when (mod) {
            VowelMod.CIRCUMFLEX -> 'ô'
            VowelMod.HORN -> 'ơ'
            else -> 'o'
        }
        'u' -> if (mod == VowelMod.HORN) 'ư' else 'u'
        else -> base
    }

    private fun withTone(base: Char, tone: Int, upper: Boolean): Char {
        val i = BASE.indexOf(base)
        val c = if (i < 0 || tone <= 0) base else TONES[tone][i]
        return if (upper) c.uppercaseChar() else c
    }

    /**
     * Vị trí đặt thanh điệu theo chuẩn kiểu mới (`hòa`, `hủy`):
     * - `ươ` → luôn đặt trên `ơ`
     * - Có nguyên âm mang dấu phụ (`ăâêôơư`) → đặt lên nguyên âm mang dấu phụ
     * - 2 nguyên âm không dấu phụ: âm tiết mở → chữ đầu (`hòa`, `hòe`, `hủy`), âm tiết khép → chữ thứ hai (`hoàn`, `hoét`, `huỳnh`)
     * - 3 nguyên âm không dấu phụ → chữ giữa (`ngoài`, `ngoáy`, `khuya`)
     */
    private fun tonePosition(vowels: CharSequence, hasCoda: Boolean): Int {
        if (vowels.isEmpty()) return -1
        if (vowels.length == 1) return 0
        val uoIdx = vowels.indexOf("ươ")
        if (uoIdx >= 0) return uoIdx + 1
        for (i in vowels.indices) {
            if (vowels[i] in "ăâêôơư") return i
        }
        return when {
            vowels.length == 2 -> if (hasCoda) 1 else 0
            else -> 1
        }
    }

    private fun allowedCodas(vStr: String, c1Low: String, midWord: Boolean): Set<String>? {
        if (vStr == "y" && c1Low == "qu") return setOf("ch", "n", "nh", "p", "t")
        if ((vStr == "yê" || (midWord && vStr == "ye")) && c1Low != "" && c1Low != "qu") return null
        return V_CODA_MAP[vStr] ?: if (midWord) MID_ONLY_CODA_MAP[vStr] else null
    }

    private fun isValidOpenVowel(vStr: String, c1Low: String, midWord: Boolean): Boolean {
        if (vStr == "yu" && c1Low == "qu") return true
        if ((vStr == "yêu" || vStr == "yê" || vStr == "ye") && c1Low != "" && c1Low != "qu") return false
        return vStr in VALID_OPEN_V || (midWord && vStr in MID_ONLY_OPEN_V)
    }

    private fun isValidOnsetWithFirstVowel(c1Low: String, firstVowel: Char): Boolean = when (c1Low) {
        "c" -> firstVowel !in "eiy"
        "g" -> firstVowel != 'e'
        "gh", "ngh" -> firstVowel in "ei"
        "ng" -> firstVowel !in "ei"
        "qu" -> firstVowel != 'u'
        "gi" -> firstVowel !in "iy"
        else -> true
    }

    private fun isValidVowelCluster(slots: List<VowelSlot>, c1Low: String): Boolean {
        if (slots.isEmpty()) return true
        if (!isValidOnsetWithFirstVowel(c1Low, slots[0].base)) return false
        val vStr = buildString(slots.size) { for (s in slots) append(modifiedVowel(s.base, s.mod)) }
        return isValidOpenVowel(vStr, c1Low, midWord = true) || allowedCodas(vStr, c1Low, midWord = true) != null
    }

    private fun isValidSyllable(
        c1Low: String,
        slots: List<VowelSlot>,
        codaLow: String,
        tone: Int,
        midWord: Boolean
    ): Boolean {
        if (slots.isEmpty()) {
            if (codaLow.isNotEmpty()) return false
            if (midWord && c1Low == "q") return true
            return c1Low in VALID_C1 && (midWord || c1Low != "qu")
        }
        if (c1Low !in VALID_C1) return false
        if (!isValidOnsetWithFirstVowel(c1Low, slots[0].base)) return false
        val vStr = buildString(slots.size) { for (s in slots) append(modifiedVowel(s.base, s.mod)) }
        if (codaLow.isEmpty()) return isValidOpenVowel(vStr, c1Low, midWord)

        val codas = allowedCodas(vStr, c1Low, midWord) ?: return false
        val codaValid = codaLow in codas || (midWord && codaLow == "c" && "ch" in codas)
        if (!codaValid) return false
        if (codaLow in STOP_CODAS) {
            if (tone in 2..4) return false
            if (!midWord && tone == 0) return false
        }
        return true
    }

    /** Trạng thái gõ theo từng từ cho một phiên Telex. */
    class Session {
        private val rawBuf = StringBuilder()
        private val c1 = StringBuilder()
        private var c1Barred = false
        private val v = ArrayList<VowelSlot>(3)
        private val c2 = StringBuilder()
        private var tone = 0
        private var bypassed = false
        private var displayBuf = ""

        val raw: String get() = rawBuf.toString()
        val display: String get() = displayBuf
        val isBypassed: Boolean get() = bypassed

        fun reset() {
            rawBuf.setLength(0)
            c1.setLength(0)
            c1Barred = false
            v.clear()
            c2.setLength(0)
            tone = 0
            bypassed = false
            displayBuf = ""
        }

        fun input(ch: Char): String {
            rawBuf.append(ch)
            if (bypassed) {
                displayBuf += ch
                return displayBuf
            }
            val lower = ch.lowercaseChar()
            val upper = ch.isUpperCase()
            if (lower !in 'a'..'z') return failToRaw()

            if (c1.isEmpty() && v.isEmpty()) {
                if (lower in "fjwz") return failToRaw()
                if (lower in VOWEL_BASES) {
                    v.add(VowelSlot(lower, upper, VowelMod.NONE, explicitBase = true))
                } else {
                    c1.append(ch)
                }
                return syncOrFail()
            }

            val c1Low = c1.toString().lowercase()
            if (v.isEmpty()) {
                return handleBeforeVowel(ch, lower, upper, c1Low)
            }

            if (lower == 'z') {
                if (tone == 0) return failToRaw()
                tone = 0
                return syncOrFail()
            }

            val toneIdx = TONE_KEYS.indexOf(lower) + 1
            if (toneIdx > 0) {
                if (tone == toneIdx) {
                    // Double-tap phím dấu: hủy thanh điệu, giữ chữ cái vừa gõ và khóa biến đổi từ hiện tại
                    tone = 0
                    return enterBypass(renderSyllable() + ch)
                }
                val codaLow = c2.toString().lowercase()
                if (codaLow in STOP_CODAS && toneIdx in 2..4) return failToRaw()
                tone = toneIdx
                return syncOrFail()
            }

            if (lower == 'w') return handleSmartW(ch, c1Low)

            if (lower in VOWEL_BASES) return handleVowelKey(ch, lower, upper, c1Low)

            // Phụ âm sau vần: nạp vào C2
            if (v.size == 1 && !v[0].explicitBase && v[0].base == 'u' && v[0].mod == VowelMod.HORN && c2.isEmpty()) {
                // `n-w-c-s` -> tự mở rộng `ư` viết tắt thành `ươ` khi thêm phụ âm cuối
                v.add(VowelSlot('o', upper && v[0].upper, VowelMod.HORN, explicitBase = false))
            }
            c2.append(ch)
            return syncOrFail()
        }

        private fun handleBeforeVowel(ch: Char, lower: Char, upper: Boolean, c1Low: String): String {
            if (c1Low == "d" && lower == 'd') {
                if (!c1Barred) {
                    c1Barred = true
                    return syncOrFail()
                }
                c1Barred = false
                c1.append(ch)
                return enterBypass(renderSyllable())
            }
            if (c1Low == "q") {
                if (lower == 'u') {
                    c1.append(ch)
                    return syncOrFail()
                }
                return failToRaw()
            }
            if (lower == 'w') {
                if (c1Low !in VALID_C1 || c1Low == "qu") return failToRaw()
                v.add(VowelSlot('u', upper, VowelMod.HORN, explicitBase = false))
                return syncOrFail()
            }
            if (lower in VOWEL_BASES) {
                if (c1Low !in VALID_C1 || !isValidOnsetWithFirstVowel(c1Low, lower)) return failToRaw()
                v.add(VowelSlot(lower, upper, VowelMod.NONE, explicitBase = true))
                return syncOrFail()
            }
            if (c1Barred) return failToRaw()
            val nextC1 = c1Low + lower
            if (nextC1 !in VALID_C1) return failToRaw()
            c1.append(ch)
            return syncOrFail()
        }

        private fun handleSmartW(ch: Char, c1Low: String): String {
            if (!v[0].explicitBase) {
                if (c2.isEmpty() && v.size == 1) {
                    v.clear()
                    tone = 0
                    return enterBypass(renderSyllable() + ch)
                }
                return failToRaw()
            }

            // 1. Cụm `uo` / `ưo` / `ươ` -> `ươ` (hoặc double-tap undo nếu cả hai đã có râu)
            for (i in 0 until v.size - 1) {
                if (v[i].base == 'u' && v[i + 1].base == 'o') {
                    if (v[i].mod == VowelMod.HORN && v[i + 1].mod == VowelMod.HORN) {
                        if (c2.isEmpty()) {
                            v[i].mod = VowelMod.NONE
                            v[i + 1].mod = VowelMod.NONE
                            return enterBypass(renderSyllable() + ch)
                        }
                        return failToRaw()
                    }
                    val oldI = v[i].mod
                    val oldNext = v[i + 1].mod
                    v[i].mod = VowelMod.HORN
                    v[i + 1].mod = VowelMod.HORN
                    if (isValidSyllable(c1Low, v, c2.toString().lowercase(), tone, midWord = true)) {
                        displayBuf = renderSyllable()
                        return displayBuf
                    }
                    v[i].mod = oldI
                    v[i + 1].mod = oldNext
                    return failToRaw()
                }
            }

            // 2. Nếu nguyên âm mục tiêu đã mang trăng/râu (`ă, ơ, ư`) -> double-tap undo
            for (i in v.indices.reversed()) {
                val s = v[i]
                if ((s.base == 'a' && s.mod == VowelMod.BREVE) ||
                    ((s.base == 'o' || s.base == 'u') && s.mod == VowelMod.HORN)
                ) {
                    if (c2.isEmpty()) {
                        s.mod = VowelMod.NONE
                        return enterBypass(renderSyllable() + ch)
                    }
                    return failToRaw()
                }
            }

            // 3. Thử đặt `ă` / `ơ` / `ư` từ phải sang trái sao cho vần vẫn hợp lệ (vd: `oaw -> oă`, `uaw -> ưa`, `uiw -> ưi`)
            val codaLow = c2.toString().lowercase()
            for (i in v.indices.reversed()) {
                val s = v[i]
                val targetMod = when (s.base) {
                    'a' -> VowelMod.BREVE
                    'o', 'u' -> VowelMod.HORN
                    else -> null
                } ?: continue
                if (s.mod != VowelMod.NONE) continue
                s.mod = targetMod
                if (isValidSyllable(c1Low, v, codaLow, tone, midWord = true)) {
                    displayBuf = renderSyllable()
                    return displayBuf
                }
                s.mod = VowelMod.NONE
            }

            return failToRaw()
        }

        private fun handleVowelKey(ch: Char, lower: Char, upper: Boolean, c1Low: String): String {
            if (c2.isNotEmpty()) return failToRaw()

            if (lower in "aeo") {
                val idx = v.indexOfFirst { it.base == lower }
                if (idx >= 0) {
                    val slot = v[idx]
                    if (slot.mod == VowelMod.CIRCUMFLEX) {
                        slot.mod = VowelMod.NONE
                        return enterBypass(renderSyllable() + ch)
                    }
                    val oldMod = slot.mod
                    slot.mod = VowelMod.CIRCUMFLEX
                    if (isValidVowelCluster(v, c1Low)) {
                        displayBuf = renderSyllable()
                        return displayBuf
                    }
                    slot.mod = oldMod
                }
            }

            // `g` + `i` + nguyên âm khác -> gộp `i` vào phụ âm đầu `gi`
            if (c1Low == "g" && !c1Barred && v.size == 1 && v[0].base == 'i' && v[0].mod == VowelMod.NONE) {
                if (lower in "iy") return failToRaw()
                c1.append(if (v[0].upper) 'I' else 'i')
                v.clear()
                v.add(VowelSlot(lower, upper, VowelMod.NONE, explicitBase = true))
                return syncOrFail()
            }

            v.add(VowelSlot(lower, upper, VowelMod.NONE, explicitBase = true))
            return syncOrFail()
        }

        /** Xóa đúng một ký tự hiển thị cuối trong Syllable Model và tính lại vị trí dấu. */
        fun backspace(): String {
            if (rawBuf.isEmpty() && displayBuf.isEmpty()) return ""
            if (bypassed) {
                if (displayBuf.isNotEmpty()) displayBuf = displayBuf.dropLast(1)
                if (rawBuf.isNotEmpty()) rawBuf.deleteCharAt(rawBuf.length - 1)
                if (displayBuf.isEmpty()) reset()
                return displayBuf
            }
            when {
                c2.isNotEmpty() -> c2.deleteCharAt(c2.length - 1)
                v.isNotEmpty() -> {
                    v.removeAt(v.lastIndex)
                    if (v.isEmpty()) {
                        if (c1.toString().lowercase() == "gi" && !c1Barred) {
                            val iChar = c1[1]
                            c1.deleteCharAt(1)
                            v.add(VowelSlot('i', iChar.isUpperCase(), VowelMod.NONE, explicitBase = true))
                        } else {
                            tone = 0
                        }
                    }
                }
                c1.isNotEmpty() -> {
                    c1Barred = false
                    c1.deleteCharAt(c1.length - 1)
                }
            }
            if (c1.isEmpty() && v.isEmpty() && c2.isEmpty()) {
                reset()
                return ""
            }
            rebuildRawFromSyllable()
            displayBuf = renderSyllable()
            return displayBuf
        }

        /** Kiểm tra toàn âm tiết khi ngắt từ (Space / Enter / dấu câu); nếu vi phạm, trả về `raw`. */
        fun commit(): String {
            if (bypassed || rawBuf.isEmpty()) return displayBuf
            val valid = isValidSyllable(
                c1.toString().lowercase(),
                v,
                c2.toString().lowercase(),
                tone,
                midWord = false
            )
            if (!valid) {
                bypassed = true
                displayBuf = rawBuf.toString()
            }
            return displayBuf
        }

        private fun syncOrFail(): String {
            if (!isValidSyllable(c1.toString().lowercase(), v, c2.toString().lowercase(), tone, midWord = true)) {
                return failToRaw()
            }
            displayBuf = renderSyllable()
            return displayBuf
        }

        private fun failToRaw(): String = enterBypass(rawBuf.toString())

        private fun enterBypass(text: String): String {
            bypassed = true
            displayBuf = text
            return displayBuf
        }

        private fun renderSyllable(): String {
            val out = StringBuilder()
            if (c1Barred && c1.isNotEmpty()) {
                out.append(if (c1[0].isUpperCase()) 'Đ' else 'đ')
            } else {
                out.append(c1)
            }
            val vChars = CharArray(v.size) { i -> modifiedVowel(v[i].base, v[i].mod) }
            val toneIdx = tonePosition(String(vChars), c2.isNotEmpty())
            for (i in v.indices) {
                val t = if (i == toneIdx) tone else 0
                out.append(withTone(vChars[i], t, v[i].upper))
            }
            out.append(c2)
            return out.toString()
        }

        private fun rebuildRawFromSyllable() {
            rawBuf.setLength(0)
            if (c1Barred && c1.isNotEmpty()) {
                rawBuf.append(c1[0]).append('d')
            } else {
                rawBuf.append(c1)
            }
            for (s in v) {
                val ch = if (s.upper) s.base.uppercaseChar() else s.base
                if (!s.explicitBase && s.base == 'u' && s.mod == VowelMod.HORN) {
                    rawBuf.append(if (s.upper) 'W' else 'w')
                    continue
                }
                if (!s.explicitBase && s.base == 'o' && s.mod == VowelMod.HORN) {
                    continue
                }
                rawBuf.append(ch)
                when (s.mod) {
                    VowelMod.CIRCUMFLEX -> rawBuf.append(ch)
                    VowelMod.BREVE, VowelMod.HORN -> rawBuf.append(if (s.upper) 'W' else 'w')
                    VowelMod.NONE -> Unit
                }
            }
            rawBuf.append(c2)
            if (tone in 1..TONE_KEYS.length) {
                rawBuf.append(TONE_KEYS[tone - 1])
            }
        }
    }
}
