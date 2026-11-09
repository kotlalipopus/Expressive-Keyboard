package com.expressive.keyboard

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

// ============================================================================
// 13. ЯЗЫКИ: РАСКЛАДКИ, ВАРИАНТЫ ПРИ УДЕРЖАНИИ, СЛОВАРИ, НАБОР КОРЕЙСКОГО И ВЬЕТНАМСКОГО
// Язык — это ряды букв (shift и ⌫ добавляются в последний ряд сами), что даёт Shift
// для письменностей без регистра, варианты при удержании и код словаря подсказок.
// ============================================================================

/** Как из набранных клавиш получается слово: просто буквы, слоги хангыля или Telex. */
enum class Composer { NONE, HANGUL, TELEX, PINYIN, KANA }

@Immutable
class KeyboardLanguage(
    val id: String,
    /** Короткий код на клавише смены языка: "RU". */
    val label: String,
    /** Название на пробеле — на самом языке. */
    val spaceLabel: String,
    /** Название для настроек (по-русски). */
    val title: String,
    /** Язык для голосового ввода и перевода. */
    val locale: String,
    val rows: List<List<String>>,
    /** Что вводит клавиша с Shift, если у письменности нет заглавных (хинди, тайский, грузинский, корейский). */
    val shiftMap: Map<String, String> = emptyMap(),
    /** Буквы с диакритикой при удержании: ключ — буква на клавише. */
    val accents: Map<String, List<String>> = emptyMap(),
    /** Код словаря FrequencyWords; null — словаря нет, подсказки только из выученных слов. */
    val dict: String? = null,
    val hasCase: Boolean = true,
    val composer: Composer = Composer.NONE,
    /** Пробелы между словами не ставятся (тайский) — автоисправление по пробелу бессмысленно. */
    val autoCorrect: Boolean = true,
    val rtl: Boolean = false,
    val fromPlugin: String? = null,
    /** Ряды заменены своей раскладкой из настроек. */
    val customLayout: Boolean = false
) {
    /** Тот же язык (словарь, голос, перевод) с другими рядами букв. */
    fun withRows(newRows: List<List<String>>): KeyboardLanguage = KeyboardLanguage(
        id, label, spaceLabel, title, locale, newRows, shiftMap, accents, dict, hasCase, composer, autoCorrect, rtl, fromPlugin, true
    )

    /** Язык правил и словаря: все английские варианты ведут себя как "en". */
    val baseId: String get() = if (dict == "en") "en" else id

    override fun equals(other: Any?): Boolean = other is KeyboardLanguage && other.id == id && other.fromPlugin == fromPlugin
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    /** Название для настроек на языке интерфейса: по-русски из описания, по-английски — из системы. */
    fun displayTitle(): String {
        if (!I18n.isEnglish) return title
        val name = runCatching { java.util.Locale.forLanguageTag(locale).getDisplayName(java.util.Locale.ENGLISH) }.getOrNull()
        return when (composer) {
            Composer.PINYIN -> "Chinese ${if (id == "zh-tw") "Traditional " else ""}(Pinyin)"
            Composer.KANA -> "Japanese (Romaji → Kana)"
            Composer.TELEX -> "Vietnamese (Telex)"
            else -> name?.takeIf { it.isNotBlank() && it != locale } ?: title
        }
    }

    /** Все буквы раскладки — для свайп-набора и поиска соседних клавиш. */
    val letters: Set<String> by lazy { rows.flatten().toSet() }

    /** Позиции клавиш (ряд, столбец со сдвигом ряда) — соседние клавиши дешевле при исправлении опечаток. */
    val keyPositions: Map<Char, Pair<Float, Float>> by lazy {
        val m = HashMap<Char, Pair<Float, Float>>()
        val widest = rows.maxOfOrNull { it.size } ?: 10
        rows.forEachIndexed { r, row ->
            val shift = (widest - row.size) / 2f
            row.forEachIndexed { c, k -> if (k.length == 1) m[k[0]] = r.toFloat() to (c + shift) }
        }
        m
    }

    companion object {
        val RU: KeyboardLanguage get() = Languages.RU
        val EN: KeyboardLanguage get() = Languages.EN
    }
}

object Languages {
    /** "qwerty" → по символу; "ض ص لا" (с пробелами) → по словам. */
    private fun r(s: String): List<String> =
        if (' ' in s) s.split(' ').filter { it.isNotEmpty() }
        else buildList { var i = 0; while (i < s.length) { val cp = s.codePointAt(i); add(String(Character.toChars(cp))); i += Character.charCount(cp) } }

    /** "aàá eé" → a: [à, á], e: [é]. */
    private fun acc(s: String): Map<String, List<String>> =
        s.split(' ').filter { it.isNotEmpty() }.associate { t -> val p = r(t); p.first() to p.drop(1) }

    /** "aA bB" → a→A, b→B (Shift для письменностей без регистра). */
    private fun sh(s: String): Map<String, String> =
        s.split(' ').filter { it.isNotEmpty() }.associate { t -> val p = r(t); p[0] to p.drop(1).joinToString("") }

    private fun L(
        id: String, label: String, space: String, title: String, locale: String, rows: List<String>,
        accents: String = "", shift: String = "", dict: String? = id, hasCase: Boolean = true,
        composer: Composer = Composer.NONE, autoCorrect: Boolean = true, rtl: Boolean = false
    ) = KeyboardLanguage(
        id, label, space, title, locale, rows.map(::r), sh(shift), acc(accents), dict, hasCase, composer, autoCorrect, rtl
    )

    private val QWERTY = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** InScript (хинди): у других индийских письменностей те же клавиши — блоки Unicode идут параллельно. */
    private val INSCRIPT_ROWS = listOf("ौ ै ा ी ू ब ह ग द ज ड", "ो े ् ि ु प र क त च ट", "ं म न व ल स य")
    private const val INSCRIPT_SHIFT = "ौऔ ैऐ ाआ ीई ूऊ बभ हङ गघ दध जझ डढ ोओ ेए ्अ िइ ुउ पफ रऱ कख तथ चछ टठ ंँ मण नञ वऋ लळ सश यष"

    /** Раскладка InScript в другой письменности: сдвиг кода символа; несуществующих там букв просто нет. */
    private fun indic(id: String, label: String, space: String, title: String, locale: String, offset: Int, dict: String?): KeyboardLanguage {
        fun conv(t: String): String? {
            val r = t.map { c -> if (c in '\u0900'..'\u097F') c + offset else c }.joinToString("")
            return r.takeIf { it.all { ch -> Character.getType(ch) != Character.UNASSIGNED.toInt() } }
        }
        fun convAll(s: String) = s.split(' ').filter { it.isNotEmpty() }.mapNotNull(::conv).joinToString(" ")
        return L(id, label, space, title, locale, INSCRIPT_ROWS.map(::convAll), shift = convAll(INSCRIPT_SHIFT), dict = dict, hasCase = false)
    }
    private val QWERTZ = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
    private const val RU_ROWS_1 = "йцукенгшщзх"
    private const val RU_ROWS_2 = "фывапролджэ"
    private const val RU_ROWS_3 = "ячсмитьбю"

    val RU = L("ru", "RU", "Русский", "Русский", "ru-RU", listOf(RU_ROWS_1, RU_ROWS_2, RU_ROWS_3), dict = "ru")
    val EN = L("en", "EN", "English", "Английский", "en-US", QWERTY, dict = "en")

    val builtIn: List<KeyboardLanguage> = listOf(
        RU, EN,
        // варианты английского: та же раскладка и словарь, свой язык голосового ввода и перевода
        L("en-gb", "GBR", "English (UK)", "Английский (Великобритания)", "en-GB", QWERTY, dict = "en"),
        L("en-au", "AUS", "English (AU)", "Английский (Австралия)", "en-AU", QWERTY, dict = "en"),
        L("en-ca", "CAN", "English (CA)", "Английский (Канада)", "en-CA", QWERTY, dict = "en"),
        L("en-in", "IND", "English (IN)", "Английский (Индия)", "en-IN", QWERTY, dict = "en"),
        L("en-ie", "IRL", "English (IE)", "Английский (Ирландия)", "en-IE", QWERTY, dict = "en"),
        L("en-nz", "NZL", "English (NZ)", "Английский (Новая Зеландия)", "en-NZ", QWERTY, dict = "en"),
        L("en-za", "ZAF", "English (ZA)", "Английский (ЮАР)", "en-ZA", QWERTY, dict = "en"),
        L("uk", "UK", "Українська", "Украинский", "uk-UA", listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"), "гґ ь'ʼ"),
        L("be", "BE", "Беларуская", "Белорусский", "be-BY", listOf("йцукенгшўзх'", "фывапролджэ", "ячсмітьбю"), "её гґ", dict = null),
        L("kk", "KK", "Қазақ", "Казахский", "kk-KZ", listOf("әіңғүұқөһ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = "kk"),
        L("ky", "KY", "Кыргызча", "Киргизский", "ky-KG", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), "нң оө уү", dict = null),
        L("tg", "TG", "Тоҷикӣ", "Таджикский", "tg-TJ", listOf("ғӣқӯҳҷ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("tt", "TT", "Татарча", "Татарский", "tt-RU", listOf("әөүҗңһ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("ba", "BA", "Башҡортса", "Башкирский", "ba-RU", listOf("әөүғҡңҙҫһ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("mn", "MN", "Монгол", "Монгольский", "mn-MN", listOf("фцужэнгшүзкъ", "йыбөахролдп", "ячёсмитьвю"), "её", dict = null),
        L("bg", "BG", "Български", "Болгарский", "bg-BG", listOf("явертъуиопшщ", "асдфгхйклчю", "зьцжбнм"), "иѝ", dict = "bg"),
        L("sr", "SR", "Српски", "Сербский", "sr-RS", listOf("љњертзуиопшђ", "асдфгхјклчћ", "џцвбнмж"), dict = "sr"),
        L("mk", "MK", "Македонски", "Македонский", "mk-MK", listOf("љњертѕуиопшѓ", "асдфгхјклчќ", "зџцвбнмж"), "иѝ еѐ", dict = "mk"),
        L("de", "DE", "Deutsch", "Немецкий", "de-DE", listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"), "sß eéè aàâ oô", dict = "de"),
        L("fr", "FR", "Français", "Французский", "fr-FR", listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"), "aàâæä eéèêë iîï oôœö uùûü cç yÿ", dict = "fr"),
        L("es", "ES", "Español", "Испанский", "es-ES", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "aá eé ií oó uúü nñ", dict = "es"),
        L("it", "IT", "Italiano", "Итальянский", "it-IT", QWERTY, "aà eèé iì oò uù", dict = "it"),
        L("pt", "PT", "Português", "Португальский", "pt-BR", listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"), "aãáàâ eéê ií oõóô uúü", dict = "pt"),
        L("nl", "NL", "Nederlands", "Нидерландский", "nl-NL", QWERTY, "eéëè iïí oóö uúü aá", dict = "nl"),
        L("pl", "PL", "Polski", "Польский", "pl-PL", QWERTY, "aą cć eę lł nń oó sś zżź", dict = "pl"),
        L("cs", "CS", "Čeština", "Чешский", "cs-CZ", listOf("qwertzuiopú", "asdfghjklů", "yxcvbnm"), "aá cč dď eéě ií nň oó rř sš tť uúů yý zž", dict = "cs"),
        L("sk", "SK", "Slovenčina", "Словацкий", "sk-SK", listOf("qwertzuiopú", "asdfghjklô", "yxcvbnmä"), "aáä cč dď eé ií lĺľ nň oóô rŕ sš tť uú yý zž", dict = "sk"),
        L("sl", "SL", "Slovenščina", "Словенский", "sl-SI", listOf("qwertzuiopš", "asdfghjklčž", "yxcvbnm"), "cčć sš zž", dict = "sl"),
        L("hr", "HR", "Hrvatski", "Хорватский", "hr-HR", listOf("qwertzuiopšđ", "asdfghjklčćž", "yxcvbnm"), dict = "hr"),
        L("bs", "BS", "Bosanski", "Боснийский", "bs-BA", listOf("qwertzuiopšđ", "asdfghjklčćž", "yxcvbnm"), dict = "bs"),
        L("sq", "SQ", "Shqip", "Албанский", "sq-AL", listOf("qwertzuiopç", "asdfghjklë", "yxcvbnm"), dict = "sq"),
        L("hu", "HU", "Magyar", "Венгерский", "hu-HU", listOf("qwertzuiopőú", "asdfghjkléáű", "yxcvbnmöüó"), "aá eé ií oóöő uúüű", dict = "hu"),
        L("ro", "RO", "Română", "Румынский", "ro-RO", QWERTY, "aăâ iî sș tț", dict = "ro"),
        L("tr", "TR", "Türkçe", "Турецкий", "tr-TR", listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"), "aâ iî uû", dict = "tr"),
        L("az", "AZ", "Azərbaycan", "Азербайджанский", "az-AZ", listOf("qwertyuiopöğ", "asdfghjklıə", "zxcvbnmçş"), "uü", dict = null),
        L("uz", "UZ", "Oʻzbek", "Узбекский", "uz-UZ", listOf("qwertyuiopʻ", "asdfghjkl", "zxcvbnm"), "oʻ gʻ", dict = null),
        L("sv", "SV", "Svenska", "Шведский", "sv-SE", listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm"), "eé aá", dict = "sv"),
        L("fi", "FI", "Suomi", "Финский", "fi-FI", listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm"), "sš zž", dict = "fi"),
        L("no", "NO", "Norsk", "Норвежский", "nb-NO", listOf("qwertyuiopå", "asdfghjkløæ", "zxcvbnm"), "eéè oóòô aàá", dict = "no"),
        L("da", "DA", "Dansk", "Датский", "da-DK", listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm"), "eé aá oó", dict = "da"),
        L("is", "IS", "Íslenska", "Исландский", "is-IS", listOf("qwertyuiopð", "asdfghjklæ", "zxcvbnmþ"), "aá eé ií oóö uú yý", dict = "is"),
        L("et", "ET", "Eesti", "Эстонский", "et-EE", listOf("qwertyuiopüõ", "asdfghjklöä", "zxcvbnm"), "sš zž", dict = "et"),
        L("lv", "LV", "Latviešu", "Латышский", "lv-LV", QWERTY, "aā cč eē gģ iī kķ lļ nņ sš uū zž", dict = "lv"),
        L("lt", "LT", "Lietuvių", "Литовский", "lt-LT", listOf("ąčęėįšųūž", "qwertyuiop", "asdfghjkl", "zxcvbnm"), dict = "lt"),
        L("ca", "CA", "Català", "Каталанский", "ca-ES", listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"), "aà eéè ií oóò uúü l·", dict = "ca"),
        L("eu", "EU", "Euskara", "Баскский", "eu-ES", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), dict = "eu"),
        L("gl", "GL", "Galego", "Галисийский", "gl-ES", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "aá eé ií oó uúü", dict = "gl"),
        L("eo", "EO", "Esperanto", "Эсперанто", "eo", QWERTY, "cĉ gĝ hĥ jĵ sŝ uŭ", dict = "eo"),
        L("mt", "MT", "Malti", "Мальтийский", "mt-MT", listOf("qwertyuiopġħ", "asdfghjklċ", "zxcvbnmż"), "aà eè iì oò uù", dict = null),
        L("ga", "GA", "Gaeilge", "Ирландский", "ga-IE", QWERTY, "aá eé ií oó uú", dict = null),
        L("cy", "CY", "Cymraeg", "Валлийский", "cy-GB", QWERTY, "aâ eê iî oô uû wŵ yŷ", dict = null),
        L("af", "AF", "Afrikaans", "Африкаанс", "af-ZA", QWERTY, "eéèêë iîï oôö uûü yý", dict = "af"),
        L("id", "ID", "Indonesia", "Индонезийский", "id-ID", QWERTY, dict = "id"),
        L("ms", "MS", "Melayu", "Малайский", "ms-MY", QWERTY, dict = "ms"),
        L("tl", "TL", "Filipino", "Филиппинский", "fil-PH", QWERTY, "nñ", dict = "tl"),
        L("sw", "SW", "Kiswahili", "Суахили", "sw-KE", QWERTY, dict = null),
        L("vi", "VI", "Tiếng Việt", "Вьетнамский (Telex)", "vi-VN", QWERTY, "aăâ dđ eê oôơ uư", dict = "vi", composer = Composer.TELEX, autoCorrect = false),
        L(
            "el", "EL", "Ελληνικά", "Греческий", "el-GR", listOf("ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ"),
            "αά εέ ηή ιίϊΐ οό υύϋΰ ωώ σς", dict = "el"
        ),
        L(
            "ka", "KA", "ქართული", "Грузинский", "ka-GE", listOf("ქწერტყუიოპ", "ასდფგჰჯკლ", "ზხცვბნმ"),
            shift = "წჭ რღ ტთ სშ ჯჟ ზძ ცჩ", dict = "ka", hasCase = false
        ),
        L(
            "hy", "HY", "Հայերեն", "Армянский", "hy-AM", listOf("էթփձջրչճժծ", "քոեռտըւիօպ", "ասդֆգհյկլխ", "զղցվբնմշ"),
            "եև", dict = "hy"
        ),
        L("he", "HE", "עברית", "Иврит", "he-IL", listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"), dict = "he", hasCase = false, rtl = true),
        L(
            "ar", "AR", "العربية", "Арабский", "ar-SA",
            listOf("ض ص ث ق ف غ ع ه خ ح ج د", "ش س ي ب ل ا ت ن م ك ط", "ئ ء ؤ ر لا ى ة و ز ظ"),
            "اأإآ يى هة وؤ", dict = "ar", hasCase = false, rtl = true
        ),
        L(
            "fa", "FA", "فارسی", "Персидский", "fa-IR",
            listOf("ض ص ث ق ف غ ع ه خ ح ج چ", "ش س ی ب ل ا ت ن م ک گ", "ظ ط ز ر ذ د پ و ژ"),
            "اآأ یئ هۀ وؤ", dict = "fa", hasCase = false, rtl = true
        ),
        L(
            "ur", "UR", "اردو", "Урду", "ur-PK",
            listOf("ط ص ھ د ٹ پ ت ب ج ح", "م و ر ن ل ہ ا ک ی", "ق ف ے س ش غ ع"),
            "دڈذ رڑزژ نں سث تث ضظ حخ جچ کگ اآ یئ ہۃ", dict = "ur", hasCase = false, rtl = true
        ),
        L(
            "hi", "HI", "हिन्दी", "Хинди", "hi-IN",
            listOf("ौ ै ा ी ू ब ह ग द ज ड", "ो े ् ि ु प र क त च ट", "ं म न व ल स य"),
            accents = "रृऋ",
            shift = "ौऔ ैऐ ाआ ीई ूऊ बभ हङ गघ दध जझ डढ ोओ ेए ्अ िइ ुउ पफ रऱ कख तथ चछ टठ ंँ मण नञ वऋ लळ सश यष",
            dict = "hi", hasCase = false
        ),
        L(
            "mr", "MR", "मराठी", "Маратхи", "mr-IN",
            listOf("ौ ै ा ी ू ब ह ग द ज ड", "ो े ् ि ु प र क त च ट", "ं म न व ल स य"),
            accents = "रृऋ लळ",
            shift = "ौऔ ैऐ ाआ ीई ूऊ बभ हङ गघ दध जझ डढ ोओ ेए ्अ िइ ुउ पफ रऱ कख तथ चछ टठ ंँ मण नञ वऋ लळ सश यष",
            dict = null, hasCase = false
        ),
        L(
            "ne", "NE", "नेपाली", "Непальский", "ne-NP",
            listOf("ौ ै ा ी ू ब ह ग द ज ड", "ो े ् ि ु प र क त च ट", "ं म न व ल स य"),
            shift = "ौऔ ैऐ ाआ ीई ूऊ बभ हङ गघ दध जझ डढ ोओ ेए ्अ िइ ुउ पफ रऱ कख तथ चछ टठ ंँ मण नञ वऋ लळ सश यष",
            dict = null, hasCase = false
        ),
        L(
            "th", "TH", "ไทย", "Тайский", "th-TH",
            listOf("ๅภถุึคตจขช", "ๆไำพะัีรนยบลฃ", "ฟหกดเ้่าสวง", "ผปแอิืทมใฝ"),
            shift = "ๅ+ ภ๑ ถ๒ ุ๓ ึ๔ คู ต฿ จ๕ ข๖ ช๗ ไ\" ำฎ พฑ ะธ ัํ ี๊ รณ นฯ ยญ บฐ ฃฅ ฟฤ หฆ กฏ ดโ เฌ ้็ ่๋ าษ สศ วซ ผ( ป) แฉ อฮ ิฺ ื์ มฒ ใฬ ฝฦ",
            dict = "th", hasCase = false, autoCorrect = false
        ),
        // бирманский (мьянманский): как Myanmar в Windows и Gboard — буквы в логическом порядке Unicode
        L(
            "my", "MY", "မြန်မာ", "Бирманский (мьянманский)", "my-MM",
            listOf("ဆ တ န မ အ ပ က င သ စ ဟ", "ေ ျ ိ ် ါ ့ ြ ု ူ း ဒ", "ဖ ထ ခ လ ဘ ည ာ ယ ရ"),
            accents = "း၊။ ဧ၏ ယ၎",
            shift = "ဆဈ တဝ နဣ မ၎ အဤ ပ၌ ကဥ င၍ သဿ စဏ ဟဧ ေဗ ျှ ိီ ်္ ါွ ့ံ ြဲ ုဍ ူဓ းဂ ဒဎ ဖဇ ထဌ ခဃ လဠ ဘဋ ညဉ ာဦ ယဩ ရဪ",
            dict = null, hasCase = false, autoCorrect = false
        ),
        // кхмерский: раскладка NiDA
        L(
            "km", "KM", "ខ្មែរ", "Кхмерский", "km-KH",
            listOf("ឆ ឹ េ រ ត យ ុ ិ ោ ផ ៀ ឪ", "ា ស ដ ថ ង ហ ្ ក ល ើ ់", "ឋ ខ ច វ ប ន ម ។ ៊"),
            accents = "អឯឰឱឲឳ ។ៗ៕",
            shift = "ឆឈ ឹឺ េែ រឬ តទ យួ ុូ ិី ោៅ ផភ ៀឿ ឪឧ ាាំ សៃ ដឌ ថធ ងអ ហះ ្ញ កគ លឡ ើោះ ់៉ ឋឍ ខឃ ចជ វេះ បព នណ មំ ។៕ ៊ុះ",
            dict = null, hasCase = false, autoCorrect = false
        ),
        // лаосский: верхний ряд — буквы, которые на обычной лаосской клавиатуре стоят на цифрах
        L(
            "lo", "LO", "ລາວ", "Лаосский", "lo-LA",
            listOf("ຢ ຟ ໂ ຖ ຸ ູ ຄ ຕ ຈ ຂ ຊ ໍ", "ົ ໄ ຳ ພ ະ ິ ີ ຮ ນ ຍ ບ ລ", "ັ ຫ ກ ດ ເ ້ ່ າ ສ ວ ງ", "ຜ ປ ແ ອ ຶ ື ທ ມ ໃ ຝ"),
            shift = "ຫໜ ມໝ ້໊ ່໋ ຍຽ ໃຼ",
            dict = null, hasCase = false, autoCorrect = false
        ),
        // сингальский: раскладка Wijesekara
        L(
            "si", "SI", "සිංහල", "Сингальский", "si-LK",
            listOf("ු අ ැ ර එ හ ම ස ද ච ඤ", "් ි ා ෙ ට ය ව න ක ත", "ං ජ ඩ ඉ බ ප ල ග"),
            accents = "අආඇඈ එඒඓ ඔඕඖ ෙෛොෝෞ දඳ ඩඬ ගඟ ජඦ ංඃ",
            shift = "ුූ අඋ ැෑ රඍ එඔ හශ මඹ සෂ දධ චඡ ඤඥ ිී ාෘ ෙෆ ටඨ ය්‍ය වළු නණ කඛ තථ ංඞ ජඣ ඩඪ ඉඊ බභ පඵ ලළ ගඝ",
            dict = "si", hasCase = false
        ),
        L(
            "ps", "PS", "پښتو", "Пушту", "ps-AF",
            listOf("ض ص ث ق ف غ ع ه خ ح ج چ", "ش س ی ب ل ا ت ن م ک ګ", "ظ ط ز ر ذ د پ و ږ ژ"),
            "تټ دډ رړ سښ نڼ یۍېئ جځ حڅ اآ", dict = null, hasCase = false, rtl = true
        ),
        L(
            "ckb", "CKB", "کوردی", "Курдский (сорани)", "ckb-IQ",
            listOf("ق و ە ر ت ی ئ ح ۆ پ ع", "ا س ش د ف گ ھ ژ ک ل", "ز خ ج چ ڤ ب ن م غ"),
            "رڕ لڵ یێ", dict = null, hasCase = false, rtl = true
        ),
        L(
            "ug", "UG", "ئۇيغۇرچە", "Уйгурский", "ug-CN",
            listOf("چ ۋ ې ر ت ي ۇ ڭ و پ ف ج", "ھ س د ا ە ى ق ك ل گ خ", "ز ش غ ۈ ب ن م ۆ ژ"),
            "ىئ", dict = null, hasCase = false, rtl = true
        ),
        // языки России и соседей на кириллице: русская раскладка и ряд своих букв
        L("cv", "CV", "Чӑвашла", "Чувашский", "cv-RU", listOf("ӑӗҫӳ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("sah", "SAH", "Саха тыла", "Якутский", "sah-RU", listOf("ҕҥөһү", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("os", "OS", "Ирон", "Осетинский", "os-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), "аӕ", dict = null),
        L("ce", "CE", "Нохчийн", "Чеченский", "ce-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("kbd", "KBD", "Адыгэбзэ", "Кабардинский", "kbd-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("av", "AV", "Магӏарул", "Аварский", "av-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("udm", "UDM", "Удмурт", "Удмуртский", "udm-RU", listOf("ӝӟӥӧӵ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("mhr", "MHR", "Марий", "Марийский", "mhr-RU", listOf("ӓҥӧӱ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("kv", "KV", "Коми", "Коми", "kv-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), "иі оӧ", dict = null),
        L("bua", "BUA", "Буряад", "Бурятский", "bua-RU", listOf("үөһ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("tyv", "TYV", "Тыва", "Тувинский", "tyv-RU", listOf("ңөү", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("kjh", "KJH", "Хакас", "Хакасский", "kjh-RU", listOf("іғңӧӱӌ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("crh", "CRH", "Qırımtatar", "Крымскотатарский", "crh-UA", QWERTY, "cç gğ iı nñ oö sş uü", dict = null),
        L("gag", "GAG", "Gagauz", "Гагаузский", "gag-MD", QWERTY, "aä eê iı oö sş tţ uü", dict = null),
        // языки Европы на латинице
        L("oc", "OC", "Occitan", "Окситанский", "oc-FR", QWERTY, "aàá eèé iíï oòó uúü cç", dict = null),
        L("co", "CO", "Corsu", "Корсиканский", "co-FR", QWERTY, "aà eè iì oò uù", dict = null),
        L("ast", "AST", "Asturianu", "Астурийский", "ast-ES", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "aá eé ií oó uúü hḥ lḷ", dict = null),
        L("rm", "RM", "Rumantsch", "Романшский", "rm-CH", QWERTZ, "aàä eèéë iì oòö uùü", dict = null),
        L("sc", "SC", "Sardu", "Сардинский", "sc-IT", QWERTY, "aà eè iì oò uù", dict = null),
        // Африка
        L("rw", "RW", "Kinyarwanda", "Киньяруанда", "rw-RW", QWERTY, dict = null),
        L("sn", "SN", "chiShona", "Шона", "sn-ZW", QWERTY, dict = null),
        L("ny", "NY", "Chichewa", "Чева (ньянджа)", "ny-MW", QWERTY, "wŵ", dict = null),
        L("st", "ST", "Sesotho", "Сесото", "st-ZA", QWERTY, dict = null),
        L("tn", "TN", "Setswana", "Тсвана", "tn-BW", QWERTY, "eê oô", dict = null),
        L("ig", "IG", "Igbo", "Игбо", "ig-NG", QWERTY, "iị oọ uụ nṅ", dict = null),
        L("om", "OM", "Afaan Oromoo", "Оромо", "om-ET", QWERTY, dict = null),
        L("ln", "LN", "Lingála", "Лингала", "ln-CD", QWERTY, "eɛé oɔó aá ií uú", dict = null),
        L("ak", "AK", "Twi", "Тви (акан)", "ak-GH", QWERTY, "eɛ oɔ nŋ", dict = null),
        L("ee", "EE", "Eʋegbe", "Эве", "ee-GH", QWERTY, "dɖ fƒ gɣ vʋ eɛ oɔ nŋ", dict = null),
        L("ff", "FF", "Pulaar", "Фула", "ff-SN", QWERTY, "bɓ dɗ nŋñ yƴ", dict = null),
        L("bm", "BM", "Bamanankan", "Бамбара", "bm-ML", QWERTY, "eɛ oɔ nɲŋ", dict = null),
        L("wo", "WO", "Wolof", "Волоф", "wo-SN", QWERTY, "aà eéë oó nñŋ", dict = null),
        L("kab", "KAB", "Taqbaylit", "Кабильский", "kab-DZ", QWERTY, "cč dḍ gǧɣ hḥ rṛ sṣ tṭ zẓ eɛ", dict = null),
        // Азия и Океания на латинице
        L("hmn", "HMN", "Hmoob", "Хмонг", "hmn", QWERTY, dict = null),
        L("sm", "SM", "Gagana Samoa", "Самоанский", "sm-WS", QWERTY, "aā eē iī oō uū", dict = null),
        L("to", "TO", "Lea faka-Tonga", "Тонганский", "to-TO", QWERTY, "aā eē iī oō uū", dict = null),
        L("fj", "FJ", "Vosa Vakaviti", "Фиджийский", "fj-FJ", QWERTY, dict = null),
        // Америка
        L("qu", "QU", "Runa Simi", "Кечуа", "qu-PE", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), dict = null),
        L("gn", "GN", "Avañeʼẽ", "Гуарани", "gn-PY", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnmʼ"), "aã eẽ iĩ oõ uũ yỹ", dict = null),
        indic("bn", "BN", "বাংলা", "Бенгальский", "bn-IN", 0x80, "bn"),
        indic("bn-bd", "BD", "বাংলা (বাংলাদেশ)", "Бенгальский (Бангладеш)", "bn-BD", 0x80, "bn"),
        indic("pa", "PA", "ਪੰਜਾਬੀ", "Панджаби", "pa-IN", 0x100, null),
        indic("gu", "GU", "ગુજરાતી", "Гуджарати", "gu-IN", 0x180, null),
        indic("or", "OR", "ଓଡ଼ିଆ", "Ория", "or-IN", 0x200, null),
        indic("ta", "TA", "தமிழ்", "Тамильский", "ta-IN", 0x280, "ta"),
        indic("te", "TE", "తెలుగు", "Телугу", "te-IN", 0x300, "te"),
        indic("kn", "KN", "ಕನ್ನಡ", "Каннада", "kn-IN", 0x380, null),
        indic("ml", "ML", "മലയാളം", "Малаялам", "ml-IN", 0x400, "ml"),
        L("zh", "中", "中文 · 拼音", "Китайский (пиньинь)", "zh-CN", QWERTY, dict = null, hasCase = false, composer = Composer.PINYIN, autoCorrect = false),
        L("zh-tw", "繁", "中文 · 繁體", "Китайский традиционный (пиньинь)", "zh-TW", QWERTY, dict = null, hasCase = false, composer = Composer.PINYIN, autoCorrect = false),
        L("ja", "あ", "日本語", "Японский (ромадзи → кана)", "ja-JP", QWERTY, dict = null, hasCase = false, composer = Composer.KANA, autoCorrect = false),
        L("br", "BR", "Brezhoneg", "Бретонский", "br-FR", QWERTY, "eê uùü nñ", dict = "br"),
        L("la", "LA", "Latina", "Латынь", "la", QWERTY, "aā eē iī oō uū", dict = null),
        L("lb", "LB", "Lëtzebuergesch", "Люксембургский", "lb-LU", QWERTZ, "eéëè aä uü", dict = null),
        L("fy", "FY", "Frysk", "Фризский", "fy-NL", QWERTY, "aâ eêé oô uûú", dict = null),
        L("fo", "FO", "Føroyskt", "Фарерский", "fo-FO", listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnmð"), "aá ií oó uú yý", dict = null),
        L("gd", "GD", "Gàidhlig", "Шотландский гэльский", "gd-GB", QWERTY, "aà eè iì oò uù", dict = null),
        L("ht", "HT", "Kreyòl", "Гаитянский креольский", "ht-HT", QWERTY, "aà eè oò", dict = null),
        L("mi", "MI", "Māori", "Маори", "mi-NZ", QWERTY, "aā eē iī oō uū", dict = null),
        L("haw", "HAW", "ʻŌlelo Hawaiʻi", "Гавайский", "haw-US", QWERTY, "aā eē iī oō uū", dict = null),
        L("so", "SO", "Soomaali", "Сомали", "so-SO", QWERTY, dict = null),
        L("yo", "YO", "Yorùbá", "Йоруба", "yo-NG", QWERTY, "eẹéè oọóò sṣ aáà iíì uúù", dict = null),
        L("ha", "HA", "Hausa", "Хауса", "ha-NG", QWERTY, "bɓ dɗ kƙ yƴ", dict = null),
        L("zu", "ZU", "isiZulu", "Зулу", "zu-ZA", QWERTY, dict = null),
        L("xh", "XH", "isiXhosa", "Коса", "xh-ZA", QWERTY, dict = null),
        L("jv", "JV", "Jawa", "Яванский", "jv-ID", QWERTY, "eéè", dict = null),
        L("su", "SU", "Sunda", "Сунданский", "su-ID", QWERTY, "eé", dict = null),
        L("ceb", "CEB", "Cebuano", "Себуанский", "ceb-PH", QWERTY, dict = null),
        L("mg", "MG", "Malagasy", "Малагасийский", "mg-MG", QWERTY, "oô", dict = null),
        L("tk", "TK", "Türkmen", "Туркменский", "tk-TM", listOf("qwertyuiopňä", "asdfghjklşü", "zxcvbnmçöý"), "zž", dict = null),
        L("ku", "KU", "Kurdî", "Курдский (курманджи)", "ku-TR", QWERTY, "eê iî uû cç sş", dict = null),
        L(
            "ko", "KO", "한국어", "Корейский", "ko-KR",
            listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ"),
            shift = "ㅂㅃ ㅈㅉ ㄷㄸ ㄱㄲ ㅅㅆ ㅐㅒ ㅔㅖ",
            dict = "ko", hasCase = false, composer = Composer.HANGUL, autoCorrect = false
        )
    )

    /** Языки из плагинов (поле "languages"). */
    var extra by mutableStateOf<List<KeyboardLanguage>>(emptyList())

    val all: List<KeyboardLanguage> get() = builtIn + extra.filter { e -> builtIn.none { it.id == e.id } }

    fun byId(id: String): KeyboardLanguage? = (extra.firstOrNull { it.id == id } ?: builtIn.firstOrNull { it.id == id })?.let(::withUser)

    /** Язык без своей раскладки — как он задан в приложении или плагине. */
    fun original(id: String): KeyboardLanguage? = extra.firstOrNull { it.id == id } ?: builtIn.firstOrNull { it.id == id }

    fun enabled(settings: KeyboardSettings): List<KeyboardLanguage> {
        applyUserLayouts(settings.langLayouts)
        return settings.languages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().mapNotNull(::byId)
            .ifEmpty { listOf(RU, EN).map(::withUser) }
    }

    // ------------------------------------------------------------------ свои раскладки

    @Volatile private var userRaw = ""
    /** Код языка → ряды своей раскладки (настройка "lang_layouts"). */
    @Volatile var userRows: Map<String, List<List<String>>> = emptyMap()
        private set
    private val userCache = java.util.concurrent.ConcurrentHashMap<String, KeyboardLanguage>()

    fun applyUserLayouts(raw: String) {
        if (raw == userRaw) return
        userRows = parseUserLayouts(raw)
        userCache.clear()
        userRaw = raw
    }

    fun parseUserLayouts(raw: String): Map<String, List<List<String>>> = runCatching {
        if (raw.isBlank()) return emptyMap()
        val o = JSONObject(raw)
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).map { r(a.getString(it).trim()) }.filter { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    /** Язык со своей раскладкой, если она задана; иначе он сам. */
    fun withUser(lang: KeyboardLanguage): KeyboardLanguage {
        val base = if (lang.customLayout) original(lang.id) ?: lang else lang
        val rows = userRows[base.id] ?: return base
        return userCache.getOrPut(base.id) { base.withRows(rows) }
    }

    /** Ряд текстом: «qwerty» — по символу, «sh ch q» (с пробелами) — по клавишам. */
    fun rowToText(row: List<String>): String = if (row.all { it.length == 1 }) row.joinToString("") else row.joinToString(" ")

    fun parseRowText(text: String): List<String> = r(text.trim())

    /** Готовые раскладки: известные латинские и кириллические и раскладки всех языков. */
    val presets: List<Pair<String, List<String>>> by lazy {
        listOf(
            "QWERTY" to QWERTY,
            "QWERTZ" to QWERTZ,
            "AZERTY" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
            "Colemak" to listOf("qwfpgjluy", "arstdhneio", "zxcvbkm"),
            "Dvorak" to listOf("pyfgcrl", "aoeuidhtns", "qjkxbmwvz"),
            "ЙЦУКЕН" to listOf(RU_ROWS_1, RU_ROWS_2, RU_ROWS_3),
            "ЙЦУКЕН + ё ъ" to listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"),
            "ЯВЕРТЫ (фонетическая)" to listOf("явертыуиопшщ", "асдфгчйклэю", "зьцжбнмх")
        )
    }

    /** Язык из плагина: { "id", "name", "label", "locale", "rows": ["...", ...], "shift": {..}, "variants": {..} }. */
    fun parse(o: JSONObject, pluginId: String): KeyboardLanguage {
        val id = o.optString("id").trim().lowercase()
        require(Regex("^[a-z]{2,3}([-_][a-z0-9]{2,8})?$").matches(id)) { "languages: id вида «xx» или «xx-yy»" }
        val rowsArr = o.optJSONArray("rows") ?: throw IllegalArgumentException("languages.$id: нужны rows")
        require(rowsArr.length() in 1..5) { "languages.$id: от 1 до 5 рядов" }
        val rows = (0 until rowsArr.length()).map { i ->
            val v = rowsArr.get(i)
            if (v is org.json.JSONArray) (0 until min(v.length(), 14)).map { v.optString(it) }.filter { it.isNotEmpty() }
            else r(v.toString()).take(14)
        }.filter { it.isNotEmpty() }
        val shift = HashMap<String, String>()
        o.optJSONObject("shift")?.let { s -> s.keys().forEach { k -> shift[k] = s.optString(k) } }
        val accents = HashMap<String, List<String>>()
        o.optJSONObject("variants")?.let { s ->
            s.keys().forEach { k -> s.optJSONArray(k)?.let { a -> accents[k] = (0 until min(a.length(), 12)).map { a.optString(it) } } }
        }
        val name = o.optString("name", id).take(30)
        return KeyboardLanguage(
            id = id,
            label = o.optString("label", id.take(2).uppercase()).take(4),
            spaceLabel = name,
            title = o.optString("title", name).take(30),
            locale = o.optString("locale", id),
            rows = rows,
            shiftMap = shift,
            accents = accents,
            dict = if (o.isNull("dictionary")) null else o.optString("dictionary").takeIf { it.isNotBlank() },
            hasCase = o.optBoolean("hasCase", shift.isEmpty()),
            composer = Composer.NONE,
            autoCorrect = o.optBoolean("autoCorrect", true),
            rtl = o.optBoolean("rtl", false),
            fromPlugin = pluginId
        )
    }

    /** Варианты при удержании: главный (цифра в верхнем ряду или первая буква с диакритикой) встаёт по центру. */
    fun variantsFor(lang: KeyboardLanguage, key: String, digit: String?): List<String>? {
        val acc = lang.accents[key].orEmpty()
        val shifted = lang.shiftMap[key]
        val primary: String
        val others = ArrayList<String>()
        when {
            digit != null -> {
                primary = digit
                others += key
                if (shifted != null && !lang.hasCase) others += shifted
                others += acc
            }
            acc.isNotEmpty() -> {
                primary = acc.first()
                others += key
                others += acc.drop(1)
            }
            shifted != null && !lang.hasCase -> {
                primary = shifted
                others += key
            }
            else -> return null
        }
        val list = others.distinct().filter { it != primary }.take(8).toMutableList()
        list.add(min((list.size + 1) / 2, list.size), primary)
        return list
    }

    /** Ширина shift/⌫ в последнем ряду и отступы по краям коротких рядов — как в Gboard. */
    fun buildRows(lang: KeyboardLanguage): List<KeyRow> {
        val widest = max(10, lang.rows.maxOf { it.size })
        val last = lang.rows.lastIndex
        val digits = "1234567890"
        return lang.rows.mapIndexed { ri, row ->
            val keys = ArrayList<KeyModel>()
            val digitRow = ri == 0 && row.size >= 8
            val models = row.mapIndexed { ci, k ->
                val digit = if (digitRow && ci < 10) digits[ci].toString() else null
                KeyModel(
                    label = k,
                    upperLabel = lang.shiftMap[k],
                    langVariants = variantsFor(lang, k, digit)
                )
            }
            if (ri == last) {
                val side = max(1.2f, (widest - row.size) / 2f)
                keys += KeyModel("⇧", KeyType.SHIFT, side)
                keys += models
                keys += KeyModel("⌫", KeyType.BACKSPACE, side + 0.1f)
            } else {
                val pad = (widest - row.size) / 2f
                if (pad > 0f) keys += KeyModel("", KeyType.SPACER, pad)
                keys += models
                if (pad > 0f) keys += KeyModel("", KeyType.SPACER, pad)
            }
            KeyRow(keys)
        }
    }
}

// ----------------------------------------------------------------------------- хангыль

object Hangul {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = "\u0000ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val VOWEL_PAIRS = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
    private val FINAL_PAIRS = mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ',
        "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ'
    )
    private val FINAL_SPLIT = FINAL_PAIRS.entries.associate { (k, v) -> v to (k[0] to k[1]) }

    fun isJamo(c: Char) = c in 'ㄱ'..'ㅣ'

    /** Последовательность букв 2-beolsik → слоги: ㅎㅏㄴㄱㅡㄹ → 한글. */
    fun compose(raw: String): String {
        val out = StringBuilder()
        var l = -1
        var v = -1
        var t = 0
        fun flush() {
            when {
                l >= 0 && v >= 0 -> out.append((0xAC00 + (l * 21 + v) * 28 + t).toChar())
                l >= 0 -> out.append(CHO[l])
                v >= 0 -> out.append(JUNG[v])
            }
            l = -1; v = -1; t = 0
        }
        for (c in raw) {
            if (!isJamo(c)) {
                flush(); out.append(c); continue
            }
            val ci = CHO.indexOf(c)
            val vi = JUNG.indexOf(c)
            if (vi < 0) { // согласная
                when {
                    v < 0 -> { flush(); l = ci }
                    l < 0 -> { flush(); l = ci }
                    t == 0 -> {
                        val ti = JONG.indexOf(c)
                        if (ti > 0) t = ti else { flush(); l = ci }
                    }
                    else -> {
                        val pair = FINAL_PAIRS["${JONG[t]}$c"]
                        if (pair != null) t = JONG.indexOf(pair) else { flush(); l = ci }
                    }
                }
            } else { // гласная
                when {
                    v < 0 -> v = vi
                    t == 0 -> {
                        val pair = VOWEL_PAIRS["${JUNG[v]}$c"]
                        if (pair != null) v = JUNG.indexOf(pair) else { flush(); v = vi }
                    }
                    else -> {
                        // последняя согласная уходит в начало нового слога: 한+ㅏ → 하나
                        val fin = JONG[t]
                        val split = FINAL_SPLIT[fin]
                        val moved: Char
                        if (split != null) { t = JONG.indexOf(split.first); moved = split.second } else { t = 0; moved = fin }
                        flush()
                        l = CHO.indexOf(moved)
                        v = vi
                    }
                }
            }
        }
        flush()
        return out.toString()
    }
}

// ----------------------------------------------------------------------------- Telex

/** Упрощённый Telex: aa→â, aw→ă, ee→ê, oo→ô, ow→ơ, uw/w→ư, dd→đ; s f r x j — тоны, z — убрать тон. */
object Telex {
    private const val BASE = "aăâeêioôơuưy"
    private val TONES = listOf(
        "aăâeêioôơuưy",
        "áắấéếíóốớúứý", // s
        "àằầèềìòồờùừỳ", // f
        "ảẳẩẻểỉỏổởủửỷ", // r
        "ãẵẫẽễĩõỗỡũữỹ", // x
        "ạặậẹệịọộợụựỵ"  // j
    )
    private val TONE_KEYS = "sfrxj"

    private fun splitTone(c: Char): Pair<Char, Int> {
        val lower = c.lowercaseChar()
        for ((ti, row) in TONES.withIndex()) {
            val i = row.indexOf(lower)
            if (i >= 0) return BASE[i] to ti
        }
        return lower to -1
    }

    private fun withTone(base: Char, tone: Int, upper: Boolean): Char {
        val i = BASE.indexOf(base)
        val c = if (i < 0 || tone <= 0) base else TONES[tone][i]
        return if (upper) c.uppercaseChar() else c
    }

    private fun isVowel(c: Char) = splitTone(c).first in BASE

    fun compose(raw: String): String {
        val w = StringBuilder()
        for (ch in raw) {
            val lower = ch.lowercaseChar()
            val up = ch.isUpperCase()
            val last = w.lastOrNull()
            val (lastBase, lastTone) = last?.let { splitTone(it) } ?: (' ' to -1)
            val lastUp = last?.isUpperCase() == true
            val mod: Char? = when {
                lower == 'a' && lastBase == 'a' -> 'â'
                lower == 'w' && lastBase == 'a' -> 'ă'
                lower == 'e' && lastBase == 'e' -> 'ê'
                lower == 'o' && lastBase == 'o' -> 'ô'
                lower == 'w' && lastBase == 'o' -> 'ơ'
                lower == 'w' && lastBase == 'u' -> 'ư'
                else -> null
            }
            if (mod != null && last != null) {
                w.setCharAt(w.length - 1, withTone(mod, lastTone, lastUp))
                continue
            }
            if (lower == 'd' && last?.lowercaseChar() == 'd') {
                w.setCharAt(w.length - 1, if (lastUp) 'Đ' else 'đ')
                continue
            }
            val tone = TONE_KEYS.indexOf(lower) + 1
            if ((tone > 0 || lower == 'z') && w.any { isVowel(it) }) {
                val pos = tonePosition(w)
                if (pos >= 0) {
                    val (b, cur) = splitTone(w[pos])
                    val target = if (lower == 'z') 0 else tone
                    if (cur == target && target != 0) {
                        // повтор тона — снять его и напечатать букву
                        w.setCharAt(pos, withTone(b, 0, w[pos].isUpperCase()))
                        w.append(ch)
                    } else w.setCharAt(pos, withTone(b, target, w[pos].isUpperCase()))
                    continue
                }
            }
            if (lower == 'w' && (last == null || !isVowel(last))) {
                w.append(if (up) 'Ư' else 'ư')
                continue
            }
            w.append(ch)
        }
        return w.toString()
    }

    /** Куда ставить тон: на ăâêôơư, иначе по правилам кластера гласных (qu/gi — не гласные). */
    private fun tonePosition(w: CharSequence): Int {
        val s = w.toString().lowercase()
        var start = 0
        if (s.startsWith("qu") || (s.startsWith("gi") && s.length > 2 && isVowel(s[2]))) start = 2
        var end = s.length - 1
        while (end >= start && !isVowel(s[end])) end--
        if (end < start) return -1
        var begin = end
        while (begin - 1 >= start && isVowel(s[begin - 1])) begin--
        for (i in begin..end) if (splitTone(s[i]).first in "ăâêôơư") return i
        val cluster = end - begin + 1
        val closed = end < s.length - 1
        return when {
            cluster == 1 -> begin
            closed -> end
            cluster >= 3 -> begin + 1
            else -> begin
        }
    }
}
