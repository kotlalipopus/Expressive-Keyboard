package com.expressive.keyboard

// ============================================================================
// ЭФИОПСКОЕ ПИСЬМО (амхарский, тигринья): латиница → слоги фиделя, как в SERA / Keyman / Google Input Tools.
// Согласная сразу даёт слог 6-го порядка (без гласной): «l» → ል. Гласная после неё меняет порядок:
// e → ለ, u → ሉ, i → ሊ, a → ላ, E (или ie) → ሌ, o → ሎ; «wa» после согласной → ሏ. Привычные сочетания:
// sh → ሸ, ch → ቸ, ny → ኘ, zh → ዠ. Заглавные — другие согласные: H ሐ, T ጠ, C ጨ, P ጰ, S ጸ, N ኘ, Z ዠ, Q ቐ, K ኸ, X ኀ.
// Гласная в начале слога — свой слог: a → አ, u → ኡ, i → ኢ, e → እ, E → ኤ, o → ኦ.
// ============================================================================

object Ethiopic {
    /** Первый слог серии (порядок 1, «ä»): к нему прибавляется номер порядка 0…7. */
    private val CONS: Map<Char, Int> = mapOf(
        'h' to 0x1200, 'l' to 0x1208, 'H' to 0x1210, 'm' to 0x1218, 'r' to 0x1228, 's' to 0x1230, 'x' to 0x1238,
        'q' to 0x1240, 'Q' to 0x1250, 'b' to 0x1260, 'v' to 0x1268, 't' to 0x1270, 'c' to 0x1278, 'X' to 0x1280,
        'n' to 0x1290, 'N' to 0x1298, 'k' to 0x12A8, 'K' to 0x12B8, 'w' to 0x12C8, 'z' to 0x12D8, 'Z' to 0x12E0,
        'y' to 0x12E8, 'd' to 0x12F0, 'D' to 0x12F8, 'j' to 0x1300, 'g' to 0x1308, 'G' to 0x1318, 'T' to 0x1320,
        'C' to 0x1328, 'P' to 0x1330, 'S' to 0x1338, 'f' to 0x1348, 'F' to 0x1348, 'p' to 0x1350,
        // у остальных заглавных нет своей согласной — как строчные
        'B' to 0x1260, 'V' to 0x1268, 'L' to 0x1208, 'M' to 0x1218, 'R' to 0x1228, 'Y' to 0x12E8, 'J' to 0x1300, 'W' to 0x12C8
    )

    /** Порядок слога для гласной после согласной. */
    private val ORDER: Map<Char, Int> = mapOf('e' to 0, 'u' to 1, 'U' to 1, 'i' to 2, 'I' to 2, 'a' to 3, 'A' to 3, 'E' to 4, 'o' to 6, 'O' to 6)

    /** Гласная в начале слога: серия «አ» (0x12A0). */
    private val ALONE: Map<Char, Char> = mapOf(
        'a' to 'አ', 'A' to 'ኣ', 'u' to 'ኡ', 'U' to 'ኡ', 'i' to 'ኢ', 'I' to 'እ', 'e' to 'እ', 'E' to 'ኤ', 'o' to 'ኦ', 'O' to 'ኦ'
    )

    /** Двухбуквенные согласные: вторая буква переделывает только что набранный слог 6-го порядка. */
    private val DIGRAPH: Map<String, Int> = mapOf("sh" to 0x1238, "ch" to 0x1278, "ny" to 0x1298, "zh" to 0x12E0, "Sh" to 0x1238, "Ch" to 0x1328)

    private fun isFidel(c: Char) = c in 'ሀ'..'፿'
    private fun order(c: Char) = (c.code - 0x1200) % 8
    private fun series(c: Char) = c.code - order(c)
    private fun exists(code: Int) = Character.getType(code) != Character.UNASSIGNED.toInt()

    fun isLatinInput(c: Char) = c in CONS || c in ORDER || c in ALONE

    fun compose(raw: String): String {
        val out = StringBuilder()
        var prev: Char? = null
        // последний слог набран латиницей (а не вставлен готовым) — его можно переделывать
        var live = false
        for (ch in raw) {
            val last = out.lastOrNull()
            val cons = CONS[ch]
            when {
                cons != null -> {
                    val di = prev?.let { DIGRAPH["$it${ch.lowercaseChar()}"] ?: DIGRAPH["$it$ch"] }
                    if (live && di != null && last != null && isFidel(last) && order(last) == 5) {
                        out.setCharAt(out.length - 1, (di + 5).toChar())
                    } else {
                        out.append((cons + 5).toChar())
                    }
                    live = true
                }
                ch in ORDER -> {
                    val ord = ORDER.getValue(ch)
                    when {
                        // «wa» после согласной: ሏ, ሟ… (если у серии есть такой слог)
                        live && (ch == 'a' || ch == 'A') && last == 'ው' && out.length >= 2 && prev == 'w' &&
                            isFidel(out[out.length - 2]) && order(out[out.length - 2]) == 5 &&
                            exists(series(out[out.length - 2]) + 7) -> {
                            val base = series(out[out.length - 2])
                            out.setLength(out.length - 2)
                            out.append((base + 7).toChar())
                            live = false
                        }
                        // согласная без гласной + гласная → слог нужного порядка
                        live && last != null && isFidel(last) && order(last) == 5 && exists(series(last) + ord) -> {
                            out.setCharAt(out.length - 1, (series(last) + ord).toChar())
                            live = ord == 2 // «i» + «e» → 5-й порядок (ሌ)
                        }
                        live && ch == 'e' && prev == 'i' && last != null && isFidel(last) && order(last) == 2 -> {
                            out.setCharAt(out.length - 1, (series(last) + 4).toChar())
                            live = false
                        }
                        else -> {
                            out.append(ALONE[ch] ?: ch)
                            live = false
                        }
                    }
                }
                else -> {
                    out.append(ch)
                    live = false
                }
            }
            prev = ch
        }
        return out.toString()
    }
}
