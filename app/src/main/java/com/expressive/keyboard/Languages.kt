package com.expressive.keyboard

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

// ============================================================================
// 13. ЯЗЫКИ: РАСКЛАДКИ, ВАРИАНТЫ ПРИ УДЕРЖАНИИ, СЛОВАРИ, НАБОР КОРЕЙСКОГО И ВЬЕТНАМСКОГО
// Язык — это ряды букв (shift и ⌫ добавляются в последний ряд сами), что даёт Shift
// для письменностей без регистра, варианты при удержании и код словаря подсказок.
// ============================================================================

/** Как из набранных клавиш получается слово: просто буквы, слоги хангыля или Telex. */
enum class Composer { NONE, HANGUL, TELEX, PINYIN, KANA, ETHIOPIC }

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
        val vi = I18n.lang == "vi"
        val name = runCatching { java.util.Locale.forLanguageTag(locale).getDisplayName(if (vi) java.util.Locale.forLanguageTag("vi") else java.util.Locale.ENGLISH) }.getOrNull()
        if (vi) when (composer) {
            Composer.PINYIN -> return "Tiếng Trung ${if (id == "zh-tw") "phồn thể " else ""}(Pinyin)"
            Composer.KANA -> return "Tiếng Nhật (Romaji → Kana)"
            Composer.TELEX -> return "Tiếng Việt (Telex)"
            Composer.ETHIOPIC -> return if (id == "ti") "Tiếng Tigrinya" else "Tiếng Amhara"
            else -> {}
        }
        return when (composer) {
            Composer.PINYIN -> "Chinese ${if (id == "zh-tw") "Traditional " else ""}(Pinyin)"
            Composer.KANA -> "Japanese (Romaji → Kana)"
            Composer.TELEX -> "Vietnamese (Telex)"
            Composer.ETHIOPIC -> if (id == "ti") "Tigrinya" else "Amharic"
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
    private val ARABIC_ROWS = listOf(
        "ض ص ث ق ف غ ع ه خ ح ج د",
        "ش س ي ب ل ا ت ن م ك ط",
        "ئ ء ؤ ر لا ى ة و ز ظ"
    )

    /** Амхарский и тигринья: на латинской клавише мелко — её слог (удержанием вставляется сразу). */
    private const val ETHIOPIC_ACCENTS = "hህሕ lል mም rር sስሽጽ xሽ qቅ bብ vቭ tትጥ cችጭ nንኝ kክ wው zዝዥ yይ dድ jጅ gግ fፍ pፕጵ aአ uኡ iኢ eእኤ oኦ"

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
            ARABIC_ROWS,
            "اأإآ يى هة وؤ", dict = "ar", hasCase = false, rtl = true
        ),
        L(
            "ar-eg", "EG", "العربية (مصر)", "Арабский (Египет)", "ar-EG", ARABIC_ROWS,
            "اأإآ يى هة وؤ ًَ ٌُ ٍِ", shift = "ضَ صً ثُ قٌ فِ غٍ عْ هّ خـ اٱ يئ",
            dict = "ar", hasCase = false, rtl = true
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
        // эфиопское письмо: латиница превращается в слоги (sel → ሰል, selam → ሰላም), см. Ethiopic.
        // Мелкая подпись и удержание — сам слог: его можно вставить и сразу
        L(
            "am", "AM", "አማርኛ", "Амхарский", "am-ET", QWERTY, ETHIOPIC_ACCENTS,
            dict = "am", hasCase = false, composer = Composer.ETHIOPIC, autoCorrect = false
        ),
        L(
            "ti", "TI", "ትግርኛ", "Тигринья", "ti-ER", QWERTY, ETHIOPIC_ACCENTS,
            dict = null, hasCase = false, composer = Composer.ETHIOPIC, autoCorrect = false
        ),
        // ---- языки России и соседей (кириллица)
        L("myv", "MYV", "Эрзянь", "Эрзянский", "myv-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("mdf", "MDF", "Мокшень", "Мокшанский", "mdf-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), "аӓ ыә", dict = null),
        L("xal", "XAL", "Хальмг", "Калмыцкий", "xal-RU", listOf("әҗңөүһ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("alt", "ALT", "Алтай", "Алтайский", "alt-RU", listOf("јҥӧӱ", RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("krc", "KRC", "Къарачай-Малкъар", "Карачаево-балкарский", "krc-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), "уў", dict = null),
        L("kum", "KUM", "Къумукъ", "Кумыкский", "kum-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), dict = null),
        L("nog", "NOG", "Ногай", "Ногайский", "nog-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2, RU_ROWS_3 + "ё"), "аӓ оӧ уӱ", dict = null),
        L("ady", "ADY", "Адыгабзэ", "Адыгейский", "ady-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("inh", "INH", "Гӏалгӏай", "Ингушский", "inh-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("lez", "LEZ", "Лезги", "Лезгинский", "lez-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("dar", "DAR", "Дарган", "Даргинский", "dar-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("lbe", "LBE", "Лакку", "Лакский", "lbe-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("tab", "TAB", "Табасаран", "Табасаранский", "tab-RU", listOf(RU_ROWS_1 + "ъ", RU_ROWS_2 + "ӏ", RU_ROWS_3 + "ё"), dict = null),
        L("ab", "AB", "Аԥсуа", "Абхазский", "ab-GE", listOf("ҕҙҟҭҳҷҽҿ", RU_ROWS_1 + "ъ", RU_ROWS_2 + "ԥ", RU_ROWS_3 + "ә"), dict = null),
        L("kaa", "KAA", "Qaraqalpaq", "Каракалпакский", "kaa-UZ", QWERTY, "aá gǵ iı nń oó uú", dict = null),
        // ---- Европа (латиница)
        L("hsb", "HSB", "Hornjoserbšćina", "Верхнелужицкий", "hsb-DE", QWERTZ, "cčć eě lł nń oó rř sš zž", dict = null),
        L("dsb", "DSB", "Dolnoserbšćina", "Нижнелужицкий", "dsb-DE", QWERTZ, "cčć eě lł nń oó rŕ sšś zžź", dict = null),
        L("szl", "SZL", "Ślōnsko", "Силезский", "szl-PL", QWERTY, "aã oōôõŏ uů cć lł nń sś zżź", dict = null),
        L("csb", "CSB", "Kaszëbsczi", "Кашубский", "csb-PL", QWERTY, "aąã eéë oòóô uù lł nń zż", dict = null),
        L("se", "SE", "Davvisámegiella", "Северносаамский", "se-NO", listOf("áqwertyuiopå", "asdfghjklöä", "zčŧcvbnmđŋš"), "zž", dict = null),
        L("kl", "KL", "Kalaallisut", "Гренландский", "kl-GL", listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm"), dict = null),
        L("wa", "WA", "Walon", "Валлонский", "wa-BE", listOf("azertyuiop", "qsdfghjklm", "wxcvbn"), "aâå eèéêë iî oô uû", dict = null),
        L("li", "LI", "Limburgs", "Лимбургский", "li-NL", QWERTY, "eèéë oó", dict = null),
        L("scn", "SCN", "Sicilianu", "Сицилийский", "scn-IT", QWERTY, "aàâ eèê iìî oòô uùû", dict = null),
        L("vec", "VEC", "Vèneto", "Венетский", "vec-IT", QWERTY, "aà eèé iì oòó uù", dict = null),
        L("fur", "FUR", "Furlan", "Фриульский", "fur-IT", QWERTY, "aàâ eèê iìî oòô uùû cç", dict = null),
        L("lij", "LIJ", "Lìgure", "Лигурский", "lij-IT", QWERTY, "aàâ eèéê iì oòóô uùû", dict = null),
        L("an", "AN", "Aragonés", "Арагонский", "an-ES", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "aá eé ií oó uúü", dict = null),
        L("ia", "IA", "Interlingua", "Интерлингва", "ia-001", QWERTY, dict = null),
        L("io", "IO", "Ido", "Идо", "io-001", QWERTY, dict = null),
        // ---- Африка, Океания, Америка (латиница)
        L("lg", "LG", "Luganda", "Луганда", "lg-UG", QWERTY, "nŋ", dict = null),
        L("rn", "RN", "Ikirundi", "Кирунди", "rn-BI", QWERTY, dict = null),
        L("ki", "KI", "Gĩkũyũ", "Кикуйю", "ki-KE", QWERTY, "iĩ uũ", dict = null),
        L("nso", "NSO", "Sepedi", "Северный сото", "nso-ZA", QWERTY, "eê oô sš", dict = null),
        L("ss", "SS", "SiSwati", "Свати", "ss-SZ", QWERTY, dict = null),
        L("ve", "VE", "Tshivenḓa", "Венда", "ve-ZA", QWERTY, "dḓ lḽ nṋṅ tṱ", dict = null),
        L("ts", "TS", "Xitsonga", "Тсонга", "ts-ZA", QWERTY, dict = null),
        L("sg", "SG", "Sängö", "Санго", "sg-CF", QWERTY, "aâä eêë iîï oôö uûü", dict = null),
        L("kg", "KG", "Kikongo", "Конго", "kg-CD", QWERTY, dict = null),
        L("tpi", "TPI", "Tok Pisin", "Ток-писин", "tpi-PG", QWERTY, dict = null),
        L("bi", "BI", "Bislama", "Бислама", "bi-VU", QWERTY, dict = null),
        L("ty", "TY", "Reo Tahiti", "Таитянский", "ty-PF", QWERTY, "aā eē iī oō uū", dict = null),
        L("ch", "CH", "Chamoru", "Чаморро", "ch-GU", QWERTY, "aå nñ", dict = null),
        L("ay", "AY", "Aymar aru", "Аймара", "ay-BO", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "aä iï uü", dict = null),
        L("nah", "NAH", "Nāhuatl", "Науатль", "nah-MX", QWERTY, "aā eē iī oō", dict = null),
        L("pap", "PAP", "Papiamentu", "Папьяменто", "pap-CW", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "aá eèé ií oòó uùúü", dict = null),
        // ---- Азия: письменности
        L(
            "sd", "SD", "سنڌي", "Синдхи", "sd-PK",
            listOf("ق ص ي ر ت ٽ ث ع ڳ و ڦ", "ا س د ف گ ه ج ڪ ل ک", "ز خ ط ڄ ب ن م ڇ چ"),
            "دڏڌڊڍ جڃڄ بٻڀ نڻ گڱ ڪک اآ يئ", dict = null, hasCase = false, rtl = true
        ),
        L(
            "dv", "DV", "ދިވެހި", "Мальдивский", "dv-MV",
            listOf("ަ ާ ި ީ ު ޫ ެ ޭ ޮ ޯ ް", "ހ ށ ނ ރ ބ ޅ ކ އ", "ވ މ ފ ދ ތ ލ ގ ޏ", "ސ ޑ ޒ ޓ ޔ ޕ ޖ ޗ"),
            "ހޙޚ ތޘ ޒޛޜޡ ސޝޞ ދޟ ޓޠ އޢ ގޣ ކޤ ވޥ",
            dict = null, hasCase = false, rtl = true, autoCorrect = false
        ),
        L(
            "bo", "BO", "བོད་ཡིག", "Тибетский", "bo-CN",
            listOf("ཀ ཁ ག ང ཅ ཆ ཇ ཉ ཏ ཐ", "ད ན པ ཕ བ མ ཙ ཚ ཛ ཝ", "ཞ ཟ འ ཡ ར ལ ཤ ས ཧ ཨ", "ི ུ ེ ོ ་ ། ྱ ྲ ླ"),
            shift = "ཀྐ ཁྑ གྒ ངྔ ཅྕ ཆྖ ཇྗ ཉྙ ཏྟ ཐྠ དྡ ནྣ པྤ ཕྥ བྦ མྨ ཙྩ ཚྪ ཛྫ ཝྭ ཞྮ ཟྯ འྰ ཡྻ རྼ ལླ ཤྵ སྶ ཧྷ ཨྸ",
            dict = null, hasCase = false, autoCorrect = false
        ),
        L("yi", "YI", "ייִדיש", "Идиш", "yi-001", listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"), "אאַאָ ווּװױ ייִײײַ פפּפֿ ככּ ששׂ תתּ", dict = null, hasCase = false, rtl = true),
        indic("sa", "SA", "संस्कृतम्", "Санскрит", "sa-IN", 0, null),
        indic("mai", "MAI", "मैथिली", "Майтхили", "mai-IN", 0, null),
        indic("as", "AS", "অসমীয়া", "Ассамский", "as-IN", 0x80, null),
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
    private val userCache = HashMap<String, Pair<KeyboardLanguage, KeyboardLanguage>>()

    @Synchronized
    fun applyUserLayouts(raw: String) {
        if (raw == userRaw) return
        userRows = parseUserLayouts(raw)
        userCache.clear()
        userRaw = raw
    }

    fun parseUserLayouts(raw: String): Map<String, List<List<String>>> = runCatching {
        if (raw.isBlank()) return emptyMap()
        val o = JSONObject(raw)
        o.keys().asSequence().mapNotNull { id ->
            val saved = o.optJSONArray(id) ?: return@mapNotNull null
            val rows = (0 until saved.length()).mapNotNull { index ->
                when (val row = saved.opt(index)) {
                    is JSONArray -> (0 until row.length()).mapNotNull { row.opt(it) as? String }.takeIf { keys -> keys.none { it.isBlank() } }
                    is String -> parseRowText(row)
                    else -> null
                }?.takeIf { it.isNotEmpty() }
            }
            if (rows.size in 1..5 && rows.all { it.size <= 14 }) id to rows else null
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Язык со своей раскладкой, если она задана; иначе он сам. */
    @Synchronized
    fun withUser(lang: KeyboardLanguage): KeyboardLanguage {
        val base = original(lang.id) ?: lang
        val rows = userRows[base.id] ?: return base
        val cached = userCache[base.id]
        if (cached?.first === base) return cached.second
        return base.withRows(rows).also { userCache[base.id] = base to it }
    }

    /** «qwerty» — по символу, «sh ch q» — по клавишам, ["sh"] — одна клавиша. */
    fun rowToText(row: List<String>): String {
        val spaced = row.joinToString(" ")
        val compact = row.joinToString("")
        return when {
            parseRowText(compact) == row -> compact
            parseRowText(spaced) == row -> spaced
            else -> JSONArray(row).toString()
        }
    }

    fun parseRowText(text: String): List<String> {
        val row = text.trim()
        if (row.startsWith('[') && row.endsWith(']')) {
            runCatching {
                val keys = JSONArray(row)
                (0 until keys.length()).map { keys.get(it) as String }.takeIf { it.none(String::isBlank) }
            }.getOrNull()?.let { return it }
        }
        return if (row.any(Char::isWhitespace)) row.split(Regex("\\s+")).filter { it.isNotBlank() } else r(row)
    }

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

    internal const val ZERO_WIDTH_NON_JOINER = '\u200C'
    internal const val PERSIAN_HALF_SPACE_LABEL = "نیم‌فاصله"

    /** Служебная клавиша в последнем ряду и отступы по краям коротких рядов. */
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
                keys += if (lang.id == "fa") {
                    KeyModel(PERSIAN_HALF_SPACE_LABEL, weight = side, output = ZERO_WIDTH_NON_JOINER.toString(),
                        customVariants = emptyList())
                } else KeyModel("⇧", KeyType.SHIFT, side)
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

/**
 * Telex kiểu Gboard. Từ đang gõ được tính lại từ chuỗi phím thô mỗi lần bấm, nên:
 *  - aa→â, aw→ă, ee→ê, oo→ô, ow→ơ, uw→ư, dd→đ; w đứng một mình sau phụ âm → ư;
 *  - s f r x j là dấu sắc/huyền/hỏi/ngã/nặng, z xóa dấu;
 *  - bỏ dấu/mũ muộn: toanf → toàn, nguoiwf → người, duocw → dược, tiene → tiên;
 *  - dấu tự dời đúng chỗ khi gõ thêm chữ: toas → tóa, toasn → toán;
 *  - dấu ở cuối từ khi chưa bỏ mũ: tiengs → tiếng, viets → viết, nhieuf → nhiều; uwo + âm cuối → ươ: duwocj → dược;
 *  - gõ đúp để hoàn tác: aaa → aa, ass → as, ww → w, ddd → dd;
 *  - kiểu bỏ dấu cũ (hòa, thúy) hoặc mới (hoà, thuý) — xem [modernTone];
 *  - khi chốt từ, nếu không phải âm tiết tiếng Việt (windows, fix, next) thì trả lại đúng phím đã gõ ([finalize]).
 */
object Telex {
    /** false — kiểu cũ (hòa, thúy); true — kiểu mới (hoà, thuý). */
    @Volatile var modernTone = false

    private const val VOW = "aăâeêioôơuưy"
    private const val TONE_KEYS = "sfrxj"
    private val TONES = listOf(
        "aăâeêioôơuưy",
        "áắấéếíóốớúứý", // s
        "àằầèềìòồờùừỳ", // f
        "ảẳẩẻểỉỏổởủửỷ", // r
        "ãẵẫẽễĩõỗỡũữỹ", // x
        "ạặậẹệịọộợụựỵ"  // j
    )
    private val CODAS = setOf("", "c", "ch", "m", "n", "ng", "nh", "p", "t")
    private val ONSETS = setOf(
        "", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l", "m", "n", "ng", "ngh",
        "nh", "p", "ph", "qu", "r", "s", "t", "th", "tr", "v", "x"
    )
    private val NUCLEI: Set<String> = (
        "a ă â e ê i o ô ơ u ư y " +
            "ai ao au âu ay ây eo êu ia iê iu oa oă oe oi ôi ơi oo ua uâ uê uô uơ ui uy ưa ưi ươ ưu yê " +
            "iêu oai oao oay oeo uây uôi ươi ươu uya uyê uyu yêu uêu"
        ).split(" ").toSet()
    /** Âm chính bắt buộc phải có âm cuối (tiên, muốn, ươm, xoăn…). */
    private val NEED_CODA = setOf("ă", "oă", "iê", "uô", "ươ", "yê", "uyê")

    /** Một chữ cái: base thường; mark: 0 không, 1 mũ (â ê ô), 2 trăng (ă), 3 râu (ơ ư), 4 gạch (đ). */
    private class Lt(var base: Char, var mark: Int = 0, var upper: Boolean = false, var fromW: Boolean = false, var auto: Boolean = false) {
        fun sym(): Char = when (mark) {
            1 -> when (base) {
                'a' -> 'â'
                'e' -> 'ê'
                'o' -> 'ô'
                else -> base
            }
            2 -> if (base == 'a') 'ă' else base
            3 -> when (base) {
                'o' -> 'ơ'
                'u' -> 'ư'
                else -> base
            }
            4 -> if (base == 'd') 'đ' else base
            else -> base
        }
    }

    private class Dec(val l: Lt, val tone: Int)
    private class Built(val w: ArrayList<Lt>, val tone: Int)

    /** Chữ tiếng Việt đã dựng sẵn (ế, ự, đ…) → chữ + dấu thanh. */
    private fun decode(ch: Char): Dec? {
        val low = ch.lowercaseChar()
        if (low == 'đ') return Dec(Lt('d', 4, ch.isUpperCase()), 0)
        val n = java.text.Normalizer.normalize(low.toString(), java.text.Normalizer.Form.NFD)
        val base = n[0]
        if (!base.isLetter()) return null
        var mark = 0
        var tone = 0
        for (i in 1 until n.length) {
            when (n[i].code) {
                0x0301 -> tone = 1
                0x0300 -> tone = 2
                0x0309 -> tone = 3
                0x0303 -> tone = 4
                0x0323 -> tone = 5
                0x0302 -> mark = 1
                0x0306 -> mark = 2
                0x031B -> mark = 3
            }
        }
        if (mark == 1 && base !in "aeo") return null
        if (mark == 2 && base != 'a') return null
        if (mark == 3 && base !in "ou") return null
        return Dec(Lt(base, mark, ch.isUpperCase()), tone)
    }

    private fun part(w: List<Lt>, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to) sb.append(w[i].sym())
        return sb.toString()
    }

    /** Âm chính [từ, đến) của âm tiết, không tính u của «qu» và i của «gi»; null nếu chưa có nguyên âm. */
    private fun nucleus(w: List<Lt>): IntArray? {
        var e = w.size - 1
        while (e >= 0 && w[e].sym() !in VOW) e--
        if (e < 0) return null
        var b = e
        while (b - 1 >= 0 && w[b - 1].sym() in VOW) b--
        if (b == 1) {
            val s0 = w[0].sym()
            val s1 = w[1].sym()
            if (e > b && ((s0 == 'g' && s1 == 'i') || (s0 == 'q' && s1 == 'u'))) b = 2
            else if (e == b && s0 == 'q' && s1 == 'u') return null
        }
        return intArrayOf(b, e + 1)
    }

    /** Có phải âm tiết tiếng Việt không. strict = false: cho phép từ còn gõ dở; true: kiểm tra đủ (khi chốt từ). */
    private fun valid(w: List<Lt>, tone: Int, strict: Boolean): Boolean {
        val r = nucleus(w)
        if (r == null) {
            val on = part(w, 0, w.size)
            return on in ONSETS || on == "q"
        }
        val onset = part(w, 0, r[0])
        val nuc = part(w, r[0], r[1])
        val coda = part(w, r[1], w.size)
        if (onset !in ONSETS || nuc !in NUCLEI || coda !in CODAS) return false
        // âm cuối c, ch, p, t chỉ đi với dấu sắc và nặng
        if ((coda == "c" || coda == "ch" || coda == "p" || coda == "t") && tone != 0 && tone != 1 && tone != 5) return false
        if ((coda == "ch" || coda == "nh") && nuc.last() !in "aêiy") return false
        if (strict) {
            if (coda.isEmpty() && nuc in NEED_CODA) return false
            val f = nuc[0]
            if (onset == "k" && f !in "eêiy") return false
            if (onset == "c" && f in "eêiy") return false
            if ((onset == "gh" || onset == "ngh") && f !in "eêi") return false
            // «gì», «gìn»: i ở đây thuộc vần «gi» nên g + i là hợp lệ; còn ge, gê, ngi, nge… thì không
            if (onset == "g" && f in "eê") return false
            if (onset == "ng" && f in "eêi") return false
        }
        return true
    }

    /** Dấu thanh đặt ở chữ nào: chữ có mũ/trăng/râu, nếu không thì theo quy tắc cụm nguyên âm. */
    private fun tonePos(w: List<Lt>, modern: Boolean): Int {
        val r = nucleus(w) ?: return -1
        val ns = r[0]
        val ne = r[1]
        var marked = -1
        for (i in ns until ne) if (w[i].sym() in "ăâêôơư") marked = i
        if (marked >= 0) return marked
        val size = ne - ns
        val closed = ne < w.size
        if (size == 1) return ns
        if (closed) return ne - 1
        if (size >= 3) return ns + 1
        if (modern) {
            val p = part(w, ns, ne)
            if (p == "oa" || p == "oe" || p == "uy") return ns + 1
        }
        return ns
    }

    private fun render(w: List<Lt>, tone: Int, modern: Boolean): String {
        val p = if (tone > 0) tonePos(w, modern) else -1
        val sb = StringBuilder()
        for (i in w.indices) {
            val l = w[i]
            var c = l.sym()
            if (i == p) {
                val j = TONES[0].indexOf(c)
                if (j >= 0) c = TONES[tone][j]
            }
            sb.append(if (l.upper) c.uppercaseChar() else c)
        }
        return sb.toString()
    }

    private fun build(raw: String, fix: Boolean = true): Built {
        val w = ArrayList<Lt>()
        var tone = 0
        // phím đã hoàn tác bằng gõ đúp: các lần bấm sau là chữ thường
        val lock = HashSet<Char>()
        for (ch in raw) {
            val low = ch.lowercaseChar()
            val up = ch.isUpperCase()
            if (!ch.isLetter()) {
                w.add(Lt(ch))
                continue
            }
            if (ch.code > 127) {
                val d = decode(ch)
                if (d != null) {
                    w.add(d.l)
                    if (d.tone > 0) tone = d.tone
                } else w.add(Lt(low, 0, up))
                continue
            }
            // ---- dấu thanh
            if (low in TONE_KEYS || low == 'z') {
                val r = nucleus(w)
                if (r == null || low in lock) {
                    w.add(Lt(low, 0, up))
                    continue
                }
                if (low == 'z') {
                    if (tone != 0) tone = 0 else w.add(Lt(low, 0, up))
                    continue
                }
                val t = TONE_KEYS.indexOf(low) + 1
                if (tone == t) {
                    // bấm lại cùng dấu: bỏ dấu và gõ chữ thường
                    tone = 0
                    lock.add(low)
                    w.add(Lt(low, 0, up))
                } else if (valid(w, t, false)) tone = t
                else if (autoCircumflex(w, t)) tone = t
                else w.add(Lt(low, 0, up))
                continue
            }
            // ---- aa ee oo → â ê ô (cho phép gõ muộn sau âm cuối: tiene → tiên)
            if ((low == 'a' || low == 'e' || low == 'o') && low !in lock) {
                val r = nucleus(w)
                var done = false
                if (r != null) {
                    var tgt = -1
                    for (i in r[1] - 1 downTo r[0]) {
                        if (w[i].base == low && (w[i].mark == 0 || w[i].mark == 1)) {
                            tgt = i
                            break
                        }
                    }
                    if (tgt >= 0) {
                        if (w[tgt].mark == 1) {
                            w[tgt].mark = 0
                            lock.add(low)
                            w.add(Lt(low, 0, up))
                            done = true
                        } else {
                            w[tgt].mark = 1
                            if (valid(w, tone, false)) done = true else w[tgt].mark = 0
                        }
                    }
                }
                // «uw» + «o» → «ươ» ngay (duwocj → dược, nguwoif → người, huwong → hương)
                if (!done && low == 'o') {
                    val prev = w.lastOrNull()
                    if (prev != null && prev.base == 'u' && prev.mark == 3) {
                        w.add(Lt('o', 3, up, false, true))
                        if (valid(w, tone, false)) done = true else w.removeAt(w.size - 1)
                    }
                }
                if (!done) w.add(Lt(low, 0, up))
                continue
            }
            // ---- dd → đ
            if (low == 'd' && low !in lock) {
                val last = w.lastOrNull()
                if (last != null && last.base == 'd' && (last.mark == 0 || last.mark == 4)) {
                    if (last.mark == 0) last.mark = 4
                    else {
                        last.mark = 0
                        lock.add('d')
                        w.add(Lt(low, 0, up))
                    }
                } else w.add(Lt(low, 0, up))
                continue
            }
            // ---- w → ă ơ ư (và ươ)
            if (low == 'w' && low !in lock) {
                val r = nucleus(w)
                if (r == null) {
                    // w đứng một mình ở đầu từ hoặc sau phụ âm → ư
                    if (w.none { it.sym() in VOW }) w.add(Lt('u', 3, up, true)) else w.add(Lt(low, 0, up))
                    continue
                }
                val ns = r[0]
                val ne = r[1]
                var applied = false
                val nuc = part(w, ns, ne)
                // ơ vừa được tự thêm sau «uw» (duwowcj): w gõ thừa ngay sau đó chỉ bị nuốt, không hoàn tác
                var swallowed = false
                for (i in ns until ne) if (w[i].auto && w[i].mark == 3) { w[i].auto = false; swallowed = true }
                if (swallowed) continue
                if (nuc == "uo" || nuc == "uoi" || nuc == "uou" || nuc == "ưo" || nuc == "uơ" || nuc == "uô") {
                    val u = w[ns]
                    val o = w[ns + 1]
                    if (u.base == 'u' && o.base == 'o' && (u.mark == 0 || o.mark == 0 || o.mark == 1)) {
                        val su = u.mark
                        val so = o.mark
                        u.mark = 3
                        o.mark = 3
                        if (valid(w, tone, false)) applied = true else {
                            u.mark = su
                            o.mark = so
                        }
                    }
                }
                if (!applied) {
                    for (i in ns until ne) {
                        val l = w[i]
                        if (l.mark == 0 && (l.base == 'a' || l.base == 'o' || l.base == 'u')) {
                            l.mark = if (l.base == 'a') 2 else 3
                            if (valid(w, tone, false)) {
                                applied = true
                                break
                            }
                            l.mark = 0
                        }
                    }
                }
                if (!applied) {
                    // đã có râu/trăng rồi: bấm w lần nữa để hoàn tác
                    val fw = (ns until ne).firstOrNull { w[it].fromW }
                    if (fw != null) {
                        w[fw] = Lt('w', 0, w[fw].upper)
                        lock.add('w')
                        applied = true
                    } else {
                        val hit = (ns until ne).filter { w[it].mark == 2 || w[it].mark == 3 }
                        if (hit.isNotEmpty()) {
                            for (i in hit) w[i].mark = 0
                            lock.add('w')
                            w.add(Lt(low, 0, up))
                            applied = true
                        }
                    }
                }
                if (!applied) w.add(Lt(low, 0, up))
                continue
            }
            w.add(Lt(low, 0, up))
        }
        if (fix) fixOpenUo(w, tone)
        return Built(w, tone)
    }

    /**
     * Dấu thanh gõ ở cuối từ khi chưa bỏ mũ: tiengs → tiếng, viets → viết, nhieuf → nhiều, yeus → yếu.
     * Chỉ áp cho ie/ye + âm cuối và ieu/yeu — tiếng Việt không có vần «ie», «ye», «ieu», «yeu» không mũ.
     * Trả về false (và không đổi gì) nếu thêm mũ không ra âm tiết hợp lệ.
     */
    private fun autoCircumflex(w: ArrayList<Lt>, tone: Int): Boolean {
        val r = nucleus(w) ?: return false
        val n = r[1] - r[0]
        if (n < 2) return false
        val ei = when {
            n == 3 && w[r[1] - 1].base == 'u' -> r[1] - 2
            r[1] < w.size -> r[1] - 1
            else -> return false
        }
        val e = w[ei]
        if (e.base != 'e' || e.mark != 0 || ei < 1 || w[ei - 1].base !in "iy") return false
        e.mark = 1
        e.auto = true
        if (valid(w, tone, false)) return true
        e.mark = 0
        e.auto = false
        return false
    }

    /**
     * «thuở» (thuowr): th + «uơ» + dấu hỏi, không có âm cuối, không phải «ươ» như trong thường, thưởng. Âm cuối hoặc
     * nguyên âm thứ ba đến sau (thuowngr → thưởng) thì dựng lại từ phím thô nên vẫn ra «ươ».
     * Chỉ áp cho đúng tổ hợp này để gõ hương, thương không bị nháy u ↔ ư giữa chừng.
     */
    private fun fixOpenUo(w: List<Lt>, tone: Int) {
        if (tone != 3) return
        val r = nucleus(w) ?: return
        if (r[1] != w.size || r[1] - r[0] != 2) return
        val u = w[r[0]]
        val o = w[r[0] + 1]
        if (u.base != 'u' || u.mark != 3 || o.base != 'o' || o.mark != 3) return
        if (part(w, 0, r[0]) == "th") u.mark = 0
    }

    /**
     * Phím ⌫: xóa đúng chữ cuối đang thấy, giữ nguyên dấu thanh — tiếng → tiến → tiế (không phải tiếng → tiêng).
     * Trả về chuỗi phím thô mới sao cho [compose] ra đúng chữ hiện tại bớt chữ cuối. Chữ có mũ/râu/đ (aa, ow, dd)
     * hay cặp ass → as bị xóa cùng lúc hai phím, chữ có cả mũ/râu lẫn dấu thanh (ự) — ba phím. Không tìm được thì
     * bỏ phím cuối như trước.
     */
    fun backspace(raw: String): String {
        if (raw.isEmpty()) return raw
        val full = build(raw)
        if (full.w.isEmpty()) return raw.dropLast(1)
        val keep = full.w.size - 1
        val syms = CharArray(keep) { full.w[it].sym() }
        val ups = BooleanArray(keep) { full.w[it].upper }
        fun fits(b: Built): Boolean {
            if (b.w.size != keep) return false
            for (i in 0 until keep) if (b.w[i].sym() != syms[i] || b.w[i].upper != ups[i]) return false
            return b.tone == (if (nucleus(b.w) != null) full.tone else 0)
        }
        // «thưởn» → bỏ n: phím thô còn lại hiện «thuở», nhưng chữ cần giữ là «thưở» — nhận cả hai dạng
        fun matches(cand: String): Boolean = fits(build(cand)) || fits(build(cand, false))
        for (i in raw.length - 1 downTo 0) {
            val cand = StringBuilder(raw).deleteCharAt(i).toString()
            if (matches(cand)) return cand
        }
        for (j in raw.length - 1 downTo 1) {
            for (i in j - 1 downTo 0) {
                val cand = StringBuilder(raw).deleteCharAt(j).deleteCharAt(i).toString()
                if (matches(cand)) return cand
            }
        }
        // chữ có cả mũ/râu lẫn dấu thanh (ự = u + w + j) cần ba phím
        if (raw.length <= 24) {
            for (k in raw.length - 1 downTo 2) {
                for (j in k - 1 downTo 1) {
                    for (i in j - 1 downTo 0) {
                        val cand = StringBuilder(raw).deleteCharAt(k).deleteCharAt(j).deleteCharAt(i).toString()
                        if (matches(cand)) return cand
                    }
                }
            }
        }
        return raw.dropLast(1)
    }

    fun compose(raw: String): String {
        val b = build(raw)
        return render(b.w, b.tone, modernTone)
    }

    /**
     * Từ khi chốt (dấu cách, dấu câu). Nếu kết quả không phải âm tiết tiếng Việt hợp lệ
     * (windows, fix, next, google…) thì trả lại đúng các phím đã gõ, như Gboard.
     */
    fun finalize(raw: String): String {
        val b = build(raw)
        val c = render(b.w, b.tone, modernTone)
        if (c == raw) return c
        if (b.w.any { !it.base.isLetter() }) return c
        return if (valid(b.w, b.tone, true)) c else raw
    }
}
