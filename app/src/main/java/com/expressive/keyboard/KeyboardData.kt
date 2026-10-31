@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 7. РАСКЛАДКИ, ВАРИАНТЫ ПРИ УДЕРЖАНИИ, ЭМОДЗИ, GIF, ПЕРЕВОДЧИК
// ============================================================================

enum class KeyType { CHARACTER, SHIFT, BACKSPACE, MODE_SYMBOLS, SYMBOLS_PAGE, GLOBE, STICKERS, SPACE, ENTER, SPACER }

enum class ShiftState { OFF, SINGLE, CAPS_LOCK }

/** EMOJI_SEARCH / GIF_SEARCH — поиск: клавиатура печатает в строку поиска. */
/** AI_WRITE — панель «ИИ» над клавиатурой, AI_PROMPT — клавиатура печатает своё указание для ИИ. */
/** SCRIPT_INPUT — клавиатура печатает в поле ввода мода (kb.prompt и элемент input на панели мода). */
enum class ActivePanel { KEYBOARD, EMOJI, EMOJI_SEARCH, TRANSLATE, GIF, GIF_SEARCH, CLIPBOARD, VOICE, PLUGINS, AI_WRITE, AI_PROMPT, SCRIPT_INPUT }

@Immutable
data class KeyModel(
    val label: String,
    val type: KeyType = KeyType.CHARACTER,
    val weight: Float = 1f,
    val extraVariants: List<String> = emptyList(),
    /** Что вводит клавиша, если это не её подпись (клавиши из плагинов: "→" вводит "->"). */
    val output: String? = null,
    /** Свои варианты при удержании из плагина; null — из каталога. */
    val customVariants: List<String>? = null,
    /** Буква с Shift у письменностей без регистра (хинди, грузинский, корейский). */
    val upperLabel: String? = null,
    /** Варианты из описания языка (диакритика, цифра верхнего ряда). */
    val langVariants: List<String>? = null,
    /** Команда скрипта плагина "pluginId/команда" — клавиша вызывает её вместо ввода текста. */
    val action: String? = null
) {
    fun variants(upper: Boolean): List<String> {
        if (customVariants != null) return if (upper) customVariants.map { upperOf(it) } else customVariants
        if (type != KeyType.CHARACTER) return extraVariants
        PluginRepository.variants[label.lowercase()]?.let { v -> return if (upper) v.map { upperOf(it) } else v }
        langVariants?.let { v -> return if (upper) v.map { upperOf(it) } else v }
        return KeyVariantsCatalog.getVariantsFor(label, upper)
    }

    private fun upperOf(s: String): String = if (s == label && upperLabel != null) upperLabel else s.uppercase()

    fun shownLabel(upper: Boolean): String = if (upper && type == KeyType.CHARACTER) upperLabel ?: label.uppercase() else label

    fun text(upper: Boolean): String {
        if (upper && type == KeyType.CHARACTER && output == null && upperLabel != null) return upperLabel
        val t = output ?: label
        return if (upper && type == KeyType.CHARACTER) t.uppercase() else t
    }
}

@Immutable
data class KeyRow(val keys: List<KeyModel>, val heightWeight: Float = 1f)

object KeyVariantsCatalog {
    private val variantsMap: Map<String, List<String>> = mapOf(
        "и" to listOf("ї", "й", "і"), "е" to listOf("5", "є", "ё", "э"), "ь" to listOf("ы", "ъ", "ѣ"),
        "ш" to listOf("щ", "8", "ш"), "щ" to listOf("ш", "9", "щ"), "й" to listOf("ї", "1", "і"),
        "ц" to listOf("@", "2", "ц"), "у" to listOf("ў", "3", "у"), "к" to listOf("$", "4", "к"),
        "н" to listOf("%", "6", "н"), "г" to listOf("ґ", "7", "г"), "з" to listOf("№", "0", "з"),
        "х" to listOf("[", "ъ", "]"), "ф" to listOf("~", "ф", "|"), "ы" to listOf("і", "ы", "ї"),
        "в" to listOf("#", "в", "№"), "а" to listOf("@", "а", "&"), "п" to listOf("§", "п", "¶"),
        "р" to listOf("₽", "р", "®"), "о" to listOf("°", "о", "º"), "л" to listOf("/", "л", "\\"),
        "д" to listOf(":", "д", ";"), "ж" to listOf("\"", "ж", "'"), "э" to listOf("є", "э", "ё"),
        "я" to listOf("«", "я", "»"), "ч" to listOf("<", "ч", ">"), "с" to listOf("©", "с", "$"),
        "м" to listOf("µ", "м", "№"), "т" to listOf("™", "т", "+"), "б" to listOf("„", "б", "“"),
        "ю" to listOf("…", "ю", "•"),
        "a" to listOf("à", "á", "â", "ä", "æ", "ã"), "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"), "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"), "c" to listOf("ç", "ć", "č"), "n" to listOf("ñ", "ń", "ň"),
        "s" to listOf("ß", "ś", "š"), "y" to listOf("ÿ", "ý", "¥"), "z" to listOf("ž", "ź", "ż"),
        "q" to listOf("!", "1", "@"), "w" to listOf("@", "2", "#"), "r" to listOf("$", "4", "®"),
        "t" to listOf("%", "5", "™"), "p" to listOf("(", "0", ")"), "d" to listOf("ď", "đ", "$"),
        "f" to listOf("%", "f", "&"), "g" to listOf("ğ", "g", "*"), "h" to listOf("(", "h", ")"),
        "j" to listOf("-", "j", "+"), "k" to listOf(":", "k", ";"), "l" to listOf("ł", "l", "/"),
        "x" to listOf("×", "x", "÷"), "v" to listOf("!", "v", "?"), "b" to listOf("\"", "b", "'"),
        "m" to listOf("µ", "m", "№"),
        "1" to listOf("¹", "½", "⅓", "¼"), "2" to listOf("²", "⅔", "№"), "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴", "₽", "$", "€"), "5" to listOf("⁵", "‰", "⅝"), "6" to listOf("⁶", "^", "↑"),
        "7" to listOf("⁷", "&", "⅞"), "8" to listOf("⁸", "∞", "×"), "9" to listOf("⁹", "(", "«"),
        "0" to listOf("°", "∅", "⁰"), "." to listOf("…", "!", "?", ",", ":"),
        "," to listOf(";", "!", "?", "—", "…"), "$" to listOf("₽", "€", "£", "¥", "₿"),
        "-" to listOf("–", "—", "•", "±"), "+" to listOf("±", "×", "÷", "="),
        // сам знак стоит в середине — это вариант по умолчанию (ровно посередине выбирается при удержании и сдвиге);
        // раньше его не было в списке, и лёгкий сдвиг пальца на «"» вводил «„» или ««»
        "\"" to listOf("«", "»", "\"", "„", "“"), "'" to listOf("‘", "’", "'", "`"),
        "?" to listOf("¿", "?", "‽"), "!" to listOf("¡", "!", "‼")
    )

    fun getVariantsFor(label: String, upper: Boolean): List<String> {
        val base = PluginRepository.variants[label.lowercase()] ?: variantsMap[label.lowercase()] ?: listOf(label)
        return if (upper) base.map { it.uppercase() } else base
    }
}

object KeyboardLayouts {
    /** Цифровая клавиатура 4×4 для полей чисел и телефонов: 1-2-3-⌫ / 4-5-6-Enter / 7-8-9-«.» / знаки-0-знаки. */
    fun numberPad(phone: Boolean): List<KeyRow> {
        fun c(l: String, vararg more: String) = KeyModel(l, customVariants = more.toList().ifEmpty { null })
        val back = KeyModel("⌫", KeyType.BACKSPACE)
        val enter = KeyModel("↵", KeyType.ENTER)
        return listOf(
            KeyRow(listOf(c("1"), c("2"), c("3"), back)),
            KeyRow(listOf(c("4"), c("5"), c("6"), enter)),
            KeyRow(listOf(c("7"), c("8"), c("9"), if (phone) c("*") else c(".", "-", ","))),
            KeyRow(
                if (phone) listOf(c("+"), c("0"), c("#"), c("-"))
                else listOf(c("-"), c("0"), c("+"), c(","))
            )
        )
    }

    private fun c(vararg s: String): List<KeyModel> = s.map { KeyModel(it) }
    private fun gap(w: Float) = KeyModel("", KeyType.SPACER, w)
    private fun shift(w: Float) = KeyModel("⇧", KeyType.SHIFT, w)
    private fun back(w: Float) = KeyModel("⌫", KeyType.BACKSPACE, w)
    private val punctuation = listOf("!", ",", ".", "?", ":")

    /** Удержание «,» и «.» — как в Gboard: вариант по умолчанию стоит в середине. */
    private val COMMA_VARIANTS = listOf(";", ":", ",", "'", "\"")
    private val PERIOD_VARIANTS = listOf("?", "!", "…", ".", ",", ":", "-")

    /** Своя клавиша у пробела на буквенной раскладке: «,» и «.» с вариантами как в Gboard, остальное — из каталога. */
    private fun slotKey(symbol: String): KeyModel? {
        val s = symbol.trim().take(4)
        if (s.isEmpty()) return null
        return KeyModel(
            s, weight = 1f, customVariants = when (s) {
                "," -> COMMA_VARIANTS
                "." -> PERIOD_VARIANTS
                else -> null
            }
        )
    }

    /**
     * Нижний ряд: ?123 · 🌐 · 😀 · [своя клавиша] · пробел · [своя клавиша] · ввод;
     * на страницах символов вместо своих клавиш «,» и «.» по бокам пробела.
     * Скрытые кнопки отдают ширину пробелу.
     * Удержание 🌐 — список языков.
     */
    private fun bottom(
        mode: String, space: String, letters: Boolean, globe: Boolean, emoji: Boolean, punct: Boolean,
        languages: List<String>, leftSlot: String = "", rightSlot: String = ""
    ): KeyRow {
        // «,» и «.» — только на страницах символов (?123), на буквах — клавиши, выбранные в настройках
        val withPunct = punct && !letters
        val left = if (letters) slotKey(leftSlot) else null
        val right = if (letters) slotKey(rightSlot) else null
        // ряд — 11 долей, как ряд из 11 букв; пробел забирает всё, что осталось от остальных клавиш. Раньше каждая
        // добавленная клавиша («,» у пробела) отнимала ширину только у пробела, и он становился совсем узким
        val small = listOf(globe, emoji, withPunct, withPunct, left != null, right != null).count { it }
        val tight = small >= 3
        val modeW = if (tight) 1.3f else 1.5f
        val enterW = if (tight) 1.4f else 1.6f
        val sideW = if (tight) 0.9f else 1f
        val others = modeW + enterW + (if (globe) sideW else 0f) + (if (emoji) sideW else 0f) + (if (withPunct) 2 * sideW else 0f) +
            (if (left != null) sideW else 0f) + (if (right != null) sideW else 0f)
        val spaceWeight = max(4.4f, 11f - others)
        return KeyRow(
            buildList {
                add(KeyModel(mode, KeyType.MODE_SYMBOLS, modeW, if (letters && !withPunct) punctuation else emptyList()))
                if (globe) add(KeyModel("globe", KeyType.GLOBE, sideW, if (languages.size > 1) languages else emptyList()))
                if (emoji) add(KeyModel("emoji", KeyType.STICKERS, sideW))
                if (withPunct) add(KeyModel(",", weight = sideW, customVariants = COMMA_VARIANTS))
                left?.let { add(it.copy(weight = sideW)) }
                add(KeyModel(space, KeyType.SPACE, spaceWeight))
                right?.let { add(it.copy(weight = sideW)) }
                if (withPunct) add(KeyModel(".", weight = sideW, customVariants = PERIOD_VARIANTS))
                add(KeyModel("enter", KeyType.ENTER, enterW))
            }
        )
    }

    private val numberRow = KeyRow(c("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), 0.78f)

    private val englishLetters = listOf(
        KeyRow(c("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")),
        KeyRow(listOf(gap(0.45f)) + c("a", "s", "d", "f", "g", "h", "j", "k", "l") + gap(0.45f)),
        KeyRow(listOf(shift(1.35f), gap(0.2f)) + c("z", "x", "c", "v", "b", "n", "m") + listOf(gap(0.2f), back(1.55f)))
    )

    private val russianLetters = listOf(
        KeyRow(c("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х")),
        KeyRow(c("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э")),
        KeyRow(listOf(shift(1.3f)) + c("я", "ч", "с", "м", "и", "т", "ь", "б", "ю") + back(1.5f))
    )

    private val symbols1 = listOf(
        KeyRow(c("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")),
        KeyRow(c("@", "#", "₽", "_", "&", "-", "+", "(", ")", "/")),
        KeyRow(listOf(KeyModel("=\\<", KeyType.SYMBOLS_PAGE, 1.45f)) + c("*", "\"", "'", ":", ";", "!", "?") + back(1.5f))
    )

    private val symbols2 = listOf(
        KeyRow(c("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")),
        KeyRow(c("£", "€", "$", "¥", "^", "°", "=", "{", "}", "\\")),
        KeyRow(listOf(KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.45f)) + c("%", "©", "®", "™", "№", "[", "]") + back(1.5f))
    )

    /** Ряды по языку и его буквам: своя раскладка того же языка — другой ключ. */
    private val languageRows = java.util.concurrent.ConcurrentHashMap<Pair<String, List<List<String>>>, List<KeyRow>>()

    private fun lettersFor(lang: KeyboardLanguage): List<KeyRow> = when {
        lang.id == "ru" && lang.fromPlugin == null && !lang.customLayout -> russianLetters
        lang.id == "en" && lang.fromPlugin == null && !lang.customLayout -> englishLetters
        else -> languageRows.getOrPut(lang.id to lang.rows) { Languages.buildRows(lang) }
    }

    fun rows(
        lang: KeyboardLanguage,
        page: Int,
        withNumbers: Boolean,
        globe: Boolean = true,
        emoji: Boolean = true,
        punct: Boolean = true,
        languages: List<String> = emptyList(),
        leftSlot: String = "",
        rightSlot: String = "",
        user: Map<String, List<String>> = emptyMap()
    ): List<KeyRow> {
        // раскладка из плагина: без {space} заменяет только верхние ряды, с {space} — всю клавиатуру
        val id = layoutId(lang, page)
        val custom = ScriptHost.layoutOverrides[id] ?: PluginRepository.layouts[id]
        if (custom != null && custom.any { r -> r.keys.any { it.type == KeyType.SPACE } }) {
            return custom.map { r ->
                KeyRow(r.keys.map {
                    when (it.type) {
                        KeyType.SPACE -> it.copy(label = lang.spaceLabel)
                        KeyType.GLOBE -> it.copy(extraVariants = if (languages.size > 1) languages else emptyList())
                        else -> it
                    }
                }, r.heightWeight)
            }
        }
        // свои места клавиш (настройки → «Свои места клавиш»)
        val order = user[id]
        return when (page) {
            1 -> UserLayouts.apply(custom ?: symbols1, order, bottom("ABC", lang.spaceLabel, false, globe, emoji, punct, languages))
            2 -> UserLayouts.apply(custom ?: symbols2, order, bottom("ABC", lang.spaceLabel, false, globe, emoji, punct, languages))
            else -> {
                val base = UserLayouts.apply(
                    custom ?: lettersFor(lang), order,
                    bottom("?123", lang.spaceLabel, true, globe, emoji, punct, languages, leftSlot, rightSlot)
                )
                if (withNumbers) UserLayouts.apply(listOf(numberRow), user[UserLayouts.NUMBERS]) + base else base
            }
        }
    }

    /**
     * Раздельная клавиатура (альбомная ориентация): в каждом ряду посередине промежуток, пробел делится на два —
     * по половине под каждый большой палец. Промежуток одинаковой доли во всех рядах, поэтому половины ровные.
     */
    fun split(rows: List<KeyRow>, gapShare: Float = 0.3f): List<KeyRow> = rows.map { row ->
        val total = row.keys.sumOf { it.weight.toDouble() }.toFloat()
        val gapW = total * gapShare / (1f - gapShare)
        val keys = row.keys
        val space = keys.indexOfFirst { it.type == KeyType.SPACE }
        val out = ArrayList<KeyModel>(keys.size + 2)
        if (space >= 0) {
            val sp = keys[space]
            keys.forEachIndexed { i, k ->
                if (i == space) {
                    out += sp.copy(weight = sp.weight / 2f)
                    out += KeyModel("", KeyType.SPACER, gapW)
                    out += sp.copy(weight = sp.weight / 2f)
                } else out += k
            }
        } else {
            // промежуток — там, где ряд делится пополам по ширине
            var acc = 0f
            var at = keys.size / 2
            for ((i, k) in keys.withIndex()) {
                if (acc + k.weight / 2f >= total / 2f) {
                    at = i
                    break
                }
                acc += k.weight
            }
            keys.forEachIndexed { i, k ->
                if (i == at) out += KeyModel("", KeyType.SPACER, gapW)
                out += k
            }
        }
        KeyRow(out, row.heightWeight)
    }

    /**
     * Удержание как в Gboard: буква → символ с того же места на странице «?123» (й/q → 1, ф/a → @ …).
     * Ряды букв без ряда цифр и нижнего ряда сопоставляются с рядами символов по порядку, клавиши — по месту слева.
     */
    fun holdSymbols(letterRows: List<KeyRow>, symbolRows: List<KeyRow>): Map<KeyModel, String> {
        val letters = letterRows.filter { r -> r.heightWeight >= 1f && r.keys.none { it.type == KeyType.SPACE } }
            .map { r -> r.keys.filter { it.type == KeyType.CHARACTER } }
        val symbols = symbolRows.filter { r -> r.keys.none { it.type == KeyType.SPACE } }
            .map { r -> r.keys.filter { it.type == KeyType.CHARACTER }.map { it.text(false) } }
        val out = HashMap<KeyModel, String>()
        letters.forEachIndexed { ri, row ->
            val sym = symbols.getOrNull(ri)?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            // по месту слева направо, как в Gboard: й→1 … з→0; лишним буквам ряда символа нет — у них обычные варианты
            row.forEachIndexed { ci, k -> sym.getOrNull(ci)?.let { out[k] = it } }
        }
        return out
    }

    fun layoutId(lang: KeyboardLanguage, page: Int): String = when (page) {
        1 -> "symbols"
        2 -> "symbols2"
        else -> lang.id
    }

    /**
     * Ряд из плагина. Строка — обычная клавиша; "{shift}", "{backspace:1.5}", "{space}", "{enter}", "{symbols}",
     * "{page}", "{globe}", "{emoji}", "{gap:0.5}" — служебные; объект {"label","output","weight","variants"} — своя клавиша.
     */
    fun parseRow(arr: JSONArray, punctuationOnMode: Boolean, pluginId: String = ""): KeyRow {
        val keys = ArrayList<KeyModel>()
        var height = 1f
        for (i in 0 until min(arr.length(), 20)) {
            val o = arr.opt(i)
            if (o is JSONObject) {
                if (o.has("rowHeight")) {
                    height = o.optDouble("rowHeight", 1.0).toFloat().coerceIn(0.5f, 1.6f)
                    continue
                }
                val label = o.optString("label").take(12)
                if (label.isEmpty()) continue
                val vars = o.optJSONArray("variants")?.let { a -> (0 until min(a.length(), 12)).map { a.optString(it) }.filter { it.isNotEmpty() } }
                keys += KeyModel(
                    label = label,
                    weight = o.optDouble("weight", 1.0).toFloat().coerceIn(0.2f, 8f),
                    output = o.optString("output").takeIf { o.has("output") }?.take(200),
                    customVariants = vars?.takeIf { it.isNotEmpty() },
                    action = o.optString("action").trim().takeIf { it.isNotEmpty() && pluginId.isNotEmpty() }?.let { "$pluginId/$it" }
                )
                continue
            }
            val s = o?.toString() ?: continue
            if (s.isEmpty()) continue
            val m = Regex("^\\{([a-z]+)(?::([0-9.]+))?\\}$").find(s)
            if (m == null) {
                keys += KeyModel(s.take(12))
                continue
            }
            val w = m.groupValues[2].toFloatOrNull()?.coerceIn(0.1f, 8f)
            keys += when (m.groupValues[1]) {
                "shift" -> shift(w ?: 1.3f)
                "backspace" -> back(w ?: 1.5f)
                "space" -> KeyModel("", KeyType.SPACE, w ?: 4f)
                "enter" -> KeyModel("enter", KeyType.ENTER, w ?: 1.8f)
                "symbols" -> KeyModel("?123", KeyType.MODE_SYMBOLS, w ?: 1.5f, if (punctuationOnMode) punctuation else emptyList())
                "abc" -> KeyModel("ABC", KeyType.MODE_SYMBOLS, w ?: 1.5f)
                "page" -> KeyModel("=\\<", KeyType.SYMBOLS_PAGE, w ?: 1.45f)
                "globe" -> KeyModel("globe", KeyType.GLOBE, w ?: 1.1f)
                "emoji" -> KeyModel("emoji", KeyType.STICKERS, w ?: 1.1f)
                "gap" -> gap(w ?: 0.5f)
                else -> KeyModel(s.take(12))
            }
        }
        return KeyRow(keys, height)
    }
}

object EmojiRecents {
    var items by mutableStateOf(listOf("😂", "❤️", "🔥", "✨", "👍", "🥹", "😍", "🎉"))
        private set
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString("recent_emojis", null)
        if (!raw.isNullOrBlank()) items = raw.split(",").filter { it.isNotBlank() }
    }

    fun clear(context: Context) {
        items = emptyList()
        prefs(context).edit().remove("recent_emojis").apply()
    }

    fun add(context: Context, emoji: String) {
        items = (listOf(emoji) + items.filter { it != emoji }).take(32)
        prefs(context).edit().putString("recent_emojis", items.joinToString(",")).apply()
    }
}

@Immutable
data class TenorGifResult(val id: String, val title: String, val previewUrl: String, val fullGifUrl: String, val category: String)

object GifImageLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
            .also { loader = it }
    }
}

/** Страница результатов: гифки и «откуда продолжить» (null — дальше ничего нет). */
@Immutable
data class GifPage(val items: List<TenorGifResult>, val next: String?, val error: String? = null)

/**
 * GIF из сети. KLIPY (бесплатная замена закрытого Tenor, совместимый с Tenor API) или GIPHY — по ключу:
 * свой ключ из настроек важнее ключа, вшитого при сборке. Без ключей — встроенная подборка.
 */
object GifCatalog {
    val categories: List<Pair<String, String>> = listOf(
        "🔥 Топ" to "",
        "👋 Привет" to "hello",
        "😂 Смешно" to "lol funny",
        "❤️ Люблю" to "love",
        "👍 Да" to "yes",
        "🙅 Нет" to "no",
        "🙏 Спасибо" to "thank you",
        "😢 Грустно" to "sad",
        "😮 Вау" to "wow",
        "🎉 Праздник" to "party celebrate",
        "💃 Танец" to "dance",
        "😎 Мемы" to "meme",
        "🐱 Котики" to "cute cat",
        "🐶 Собачки" to "cute dog",
        "🤔 Хм" to "thinking",
        "😴 Сплю" to "sleepy tired"
    )

    private fun g(id: String, title: String, cat: String): TenorGifResult {
        val url = "https://media.giphy.com/media/$id/giphy.gif"
        return TenorGifResult(id, title, url, url, cat)
    }

    private val builtIn = listOf(
        g("Ju7l5y9osyymQ", "Rickroll Dance", "💃 Танец"),
        g("3o7abKhOpu0NwenH3O", "Awesome Thumbs Up", "👍 Да"),
        g("l0MYt5jPR6QX5pnqM", "Party Celebration", "🎉 Праздник"),
        g("26ufdipQqU2lhNA4g", "Mind Blown Galaxy", "😮 Вау"),
        g("xT5LMHxhOfscxPfIfm", "Homer Simpson Yes", "👍 Да"),
        g("5GoVLqeAOo6PK", "Excited Jonah Hill", "😮 Вау"),
        g("QMHoU66sBXqqLqYvGO", "This Is Fine Dog", "😎 Мемы"),
        g("YnkMcHgNIMW4Yfmjxr", "Stonks Up", "😎 Мемы"),
        g("a93jwI0wkWTQs", "Homer Backing Into Bushes", "😎 Мемы"),
        g("3o7527pa7qs9kCG78A", "Confused Dog", "🤔 Хм"),
        g("d3mlE7uhX8KFgEmY", "Smart Thinking", "🤔 Хм"),
        g("10UeedrT5MIfPG", "Laughing Hysterically", "😂 Смешно"),
        g("pUeXcg80cO8I8", "Michael Jackson Popcorn", "😂 Смешно"),
        g("7rj2ZgttvgomY", "Slow Clap Applause", "🎉 Праздник"),
        g("JIX9t2j0ZTN9S", "Hacker Cat Typing", "🐱 Котики"),
        g("MDJ9IbxxvDUQM", "Cat Sending Love Kiss", "🐱 Котики"),
        g("VbnUQpnihPSIgIXuZv", "Computer Cat Glasses", "🐱 Котики"),
        g("3M4NpbLCTxBqU", "Warm Ghost Hug", "❤️ Люблю"),
        g("l4pTdcifPZLpDjL1e", "Sending Big Love Hearts", "❤️ Люблю")
    )

    /** Встроенная подборка: по категории или по словам названия. */
    fun fallback(category: String, query: String = ""): List<TenorGifResult> {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) return builtIn.filter { g -> q.split(' ').any { it.isNotBlank() && g.title.lowercase().contains(it) } }
        return builtIn.filter { it.category == category }.ifEmpty { builtIn }
    }

    /** Ключ: свой из настроек → вшитый при сборке. */
    private fun klipyKey(user: String) = user.trim().ifBlank { BuildConfig.KLIPY_API_KEY }
    private fun giphyKey(user: String) = user.trim().ifBlank { BuildConfig.GIPHY_API_KEY }

    /** Есть ли вообще чем искать в сети. */
    fun online(klipyUser: String, giphyUser: String) = klipyKey(klipyUser).isNotBlank() || giphyKey(giphyUser).isNotBlank()

    private const val PAGE = 30

    suspend fun fetch(
        category: String,
        query: String,
        next: String?,
        klipyUser: String,
        giphyUser: String
    ): GifPage = withContext(Dispatchers.IO) {
        val klipy = klipyKey(klipyUser)
        val giphy = giphyKey(giphyUser)
        if (klipy.isBlank() && giphy.isBlank()) {
            return@withContext GifPage(if (next == null) fallback(category, query) else emptyList(), null)
        }
        val r = if (klipy.isNotBlank()) runCatching { klipy(klipy, category, query, next) } else null
        if (r != null && r.isSuccess && r.getOrThrow().items.isNotEmpty()) return@withContext r.getOrThrow()
        val g = if (giphy.isNotBlank()) runCatching { giphy(giphy, category, query, next) } else null
        if (g != null && g.isSuccess) return@withContext g.getOrThrow()
        val err = (g?.exceptionOrNull() ?: r?.exceptionOrNull())?.message ?: "нет ответа"
        GifPage(if (next == null) fallback(category, query) else emptyList(), null, err)
    }

    private fun get(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "ExpressiveKeyboard")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readLimited().orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun locale(): String = java.util.Locale.getDefault().let { "${it.language}_${it.country.ifEmpty { it.language.uppercase() }}" }

    /** KLIPY в формате Tenor v2: results[].media_formats.{tinygif, gif…}, продолжение — поле next. */
    private fun klipy(key: String, category: String, query: String, next: String?): GifPage {
        val k = URLEncoder.encode(key, "UTF-8")
        val base = if (query.isBlank()) "https://api.klipy.com/v2/featured?key=$k"
        else "https://api.klipy.com/v2/search?key=$k&q=${URLEncoder.encode(query, "UTF-8")}"
        val url = "$base&limit=$PAGE&client_key=expressive_keyboard&contentfilter=medium&locale=${locale()}" +
            "&media_filter=tinygif,gif,mediumgif" + (next?.let { "&pos=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val root = get(url)
        val results = root.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val f = item.optJSONObject("media_formats") ?: return@mapNotNull null
            fun u(name: String) = f.optJSONObject(name)?.optString("url").orEmpty()
            val preview = u("tinygif").ifEmpty { u("nanogif") }.ifEmpty { u("gif") }
            val full = u("mediumgif").ifEmpty { u("gif") }.ifEmpty { preview }
            if (preview.isEmpty()) null
            else TenorGifResult(item.optString("id", "k$i"), item.optString("content_description", item.optString("title", "GIF")), preview, full, category)
        }
        val nxt = root.optString("next").takeIf { it.isNotBlank() && it != "0" && items.isNotEmpty() }
        return GifPage(items, nxt)
    }

    /** GIPHY: offset для продолжения, превью — fixed_width_downsampled. */
    private fun giphy(key: String, category: String, query: String, next: String?): GifPage {
        val k = URLEncoder.encode(key, "UTF-8")
        val offset = next?.toIntOrNull() ?: 0
        val lang = java.util.Locale.getDefault().language
        val url = if (query.isBlank()) "https://api.giphy.com/v1/gifs/trending?api_key=$k&limit=$PAGE&offset=$offset&rating=pg-13"
        else "https://api.giphy.com/v1/gifs/search?api_key=$k&q=${URLEncoder.encode(query, "UTF-8")}&limit=$PAGE&offset=$offset&rating=pg-13&lang=$lang"
        val root = get(url)
        val data = root.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { i ->
            val item = data.getJSONObject(i)
            val images = item.optJSONObject("images")
            val preview = images?.optJSONObject("fixed_width_downsampled")?.optString("url")
                ?: images?.optJSONObject("fixed_height")?.optString("url").orEmpty()
            val full = images?.optJSONObject("downsized")?.optString("url")
                ?: images?.optJSONObject("original")?.optString("url") ?: preview
            if (preview.isNullOrEmpty()) null
            else TenorGifResult(item.optString("id", "g$i"), item.optString("title", "GIF"), preview, full, category)
        }
        val p = root.optJSONObject("pagination")
        val total = p?.optInt("total_count", 0) ?: 0
        val end = offset + (p?.optInt("count", items.size) ?: items.size)
        return GifPage(items, if (items.isNotEmpty() && end < total && end < 4999) end.toString() else null)
    }
}

object Translator {
    /**
     * Перевод через несколько бесплатных адресов по очереди: основной адрес Google у части операторов
     * и в части стран недоступен или отвечает ошибкой на частые запросы — тогда пробуем следующий.
     */
    suspend fun translate(text: String, from: String, target: String, hints: List<String> = emptyList()): Result<String> = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext Result.success("")
        // "en-GB" и другие разновидности английского: сервис переводит на английский, а написание правим сами
        val to = serviceCode(target)
        val src = if (from == "auto") guessSource(clean, to, hints) else from
        var last: Throwable? = null
        for ((attempt, limit) in listOf(::viaGtx to 4500, ::viaDictExt to 1500, ::viaMyMemory to 450)) {
            // длинное сообщение режем по абзацам и предложениям: у запасных адресов лимит в сотни символов
            val r = runCatching {
                splitText(clean, limit).joinToString("") { piece ->
                    val trail = piece.takeLastWhile { it.isWhitespace() }
                    attempt(piece.trim(), src, to).trim() + trail
                }
            }
            val out = r.getOrNull()?.trim()
            if (!out.isNullOrEmpty()) return@withContext Result.success(EnglishVariants.apply(out, target))
            last = r.exceptionOrNull() ?: last
        }
        Result.failure(last ?: java.io.IOException("empty translation"))
    }

    /** Режет текст на куски не длиннее max по абзацам, предложениям и пробелам; склеенные вместе они дают исходный текст. */
    internal fun splitText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val cut = listOf(window.lastIndexOf('\n'), window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf(' '))
                    .firstOrNull { it > max / 3 }
                if (cut != null) end = start + cut + 1
            }
            // пробелы после куска остаются в нём — при склейке текст не меняется
            while (end < text.length && text[end].isWhitespace() && text[end] != '\n') end++
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    /** Код языка перевода из панели → код для сервиса перевода. */
    fun serviceCode(target: String): String = when {
        target.startsWith("en-") -> "en"
        target == "zh" -> "zh-CN"
        target == "zh-tw" -> "zh-TW"
        else -> target
    }

    private val CYRILLIC_LANGS = setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "tt", "ba", "mn")

    /**
     * Язык текста угадываем сами, если по буквам он ясен: короткое слово Google определяет наугад — «попа»
     * уходила как болгарское («священника») и переводилась как «pop». Кириллица — это язык раскладки
     * пользователя (из hints), а не любой кириллический язык мира. Не уверены — пусть решает сервис ("auto").
     */
    private fun guessSource(text: String, to: String, hints: List<String>): String {
        val guess = when {
            text.any { it in 'Ѐ'..'ӿ' } -> when {
                text.any { it in "іїєґІЇЄҐ" } -> "uk"
                text.any { it in "ўЎ" } -> "be"
                else -> hints.firstOrNull { it in CYRILLIC_LANGS && it != "uk" && it != "be" } ?: "ru"
            }
            else -> return "auto"
        }
        // переводим на тот же язык — значит, текст скорее на другом; пусть определяет сервис
        return if (guess == to) "auto" else guess
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    }

    private fun HttpURLConnection.body(): String = try {
        if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode")
        inputStream.readLimited()
    } finally {
        disconnect()
    }

    /** Основной адрес; текст — в теле запроса, чтобы длинные фразы не упирались в длину адреса. */
    private fun viaGtx(text: String, from: String, to: String): String {
        val conn = open("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&ie=UTF-8&oe=UTF-8")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
        val parts = JSONArray(conn.body()).optJSONArray(0) ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                if (!p.isNull(0)) append(p.optString(0))
            }
        }
    }

    /** Запасной адрес Google (тот, что у расширения браузера): ответ [["перевод","язык"]] или ["перевод"]. */
    private fun viaDictExt(text: String, from: String, to: String): String {
        val raw = open(
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$from&tl=$to&q=" + URLEncoder.encode(text, "UTF-8")
        ).body()
        val root = JSONArray(raw)
        return buildString {
            for (i in 0 until root.length()) {
                when (val v = root.opt(i)) {
                    is String -> append(v)
                    is JSONArray -> append(v.optString(0))
                }
            }
        }
    }

    /** Не Google: MyMemory. Ему нужен исходный язык — угадываем по алфавиту. */
    private fun viaMyMemory(text: String, from: String, to: String): String {
        val src = if (from != "auto") from else when {
            text.any { it in 'Ѐ'..'ӿ' } -> if (text.any { it in "іїєґІЇЄҐ" }) "uk" else "ru"
            text.any { it in '一'..'鿿' } -> "zh"
            text.any { it in '぀'..'ヿ' } -> "ja"
            text.any { it in '가'..'힯' } -> "ko"
            text.any { it in '؀'..'ۿ' } -> "ar"
            else -> "en"
        }
        if (src == to) return text
        val raw = open(
            "https://api.mymemory.translated.net/get?langpair=$src%7C$to&q=" + URLEncoder.encode(text, "UTF-8")
        ).body()
        val o = JSONObject(raw)
        if (o.optInt("responseStatus", 200) != 200) return ""
        return o.optJSONObject("responseData")?.optString("translatedText").orEmpty()
    }
}

/**
 * Разновидности английского для переводчика. Сервис отдаёт американский английский, поэтому британское,
 * австралийское, канадское и индийское написание получаем заменой слов: -or → -our, -er → -re, -ize → -ise и т. п.
 */
object EnglishVariants {
    /** Код → (название, пояснение). "en" — американский, как отдаёт сервис. */
    val LIST: List<Triple<String, String, String>> = listOf(
        Triple("en", "English", "США"),
        Triple("en-GB", "English", "Великобритания"),
        Triple("en-AU", "English", "Австралия"),
        Triple("en-CA", "English", "Канада"),
        Triple("en-IN", "English", "Индия")
    )

    /** Как назвать вариант ИИ; null — американский английский, его отдаёт сам сервис перевода. */
    fun regionName(code: String): String? = when (code) {
        "en-GB" -> "British English (United Kingdom)"
        "en-AU" -> "Australian English"
        "en-CA" -> "Canadian English"
        "en-IN" -> "Indian English"
        else -> null
    }

    private class Rules(val our: Boolean, val re: Boolean, val ise: Boolean, val ence: Boolean, val ogue: Boolean, val ll: Boolean, val misc: Boolean, val vocab: Boolean)

    private val rules = mapOf(
        "en-GB" to Rules(our = true, re = true, ise = true, ence = true, ogue = true, ll = true, misc = true, vocab = true),
        "en-AU" to Rules(our = true, re = true, ise = true, ence = true, ogue = true, ll = true, misc = true, vocab = true),
        "en-IN" to Rules(our = true, re = true, ise = true, ence = true, ogue = true, ll = true, misc = true, vocab = false),
        "en-CA" to Rules(our = true, re = true, ise = false, ence = true, ogue = true, ll = true, misc = true, vocab = false)
    )

    private val OUR = "color favor honor humor labor neighbor behavior flavor harbor rumor savior vapor odor armor rigor vigor valor endeavor clamor tumor parlor splendor".split(' ')
    private val OUR_SUFFIX = listOf("", "s", "ed", "ing", "ful", "less", "able", "ably", "ite", "ites")
    private val IZE = ("organize realize recognize apologize criticize emphasize summarize specialize optimize customize utilize minimize maximize " +
        "authorize categorize centralize finalize generalize legalize memorize normalize personalize prioritize standardize visualize civilize " +
        "colonize modernize harmonize symbolize synchronize hospitalize familiarize materialize mobilize neutralize patronize penalize privatize " +
        "publicize randomize rationalize revolutionize scrutinize socialize stabilize sterilize stigmatize sympathize terrorize theorize " +
        "trivialize vandalize capitalize characterize commercialize computerize criminalize democratize digitize dramatize economize energize " +
        "equalize evangelize fertilize globalize hypothesize idealize immunize industrialize internalize italicize jeopardize localize magnetize " +
        "marginalize monetize naturalize notarize").split(' ')
    private val IZE_SUFFIX = listOf("e", "es", "ed", "ing", "ation", "ations", "er", "ers")
    private val YZE = "analyze paralyze catalyze".split(' ')
    private val RE = "center theater liter fiber caliber somber luster centimeter millimeter kilometer".split(' ')
    private val ENCE = "defense offense pretense".split(' ')
    private val OGUE = "catalog dialog analog".split(' ')
    private val LL = ("traveler travelers traveling traveled canceled canceling labeled labeling modeling modeled fueled fueling signaled signaling " +
        "marveled marveling counselor counselors leveled leveling totaled totaling").split(' ')
    private val MISC = mapOf(
        "gray" to "grey", "jewelry" to "jewellery", "pajamas" to "pyjamas", "aluminum" to "aluminium", "plow" to "plough", "plowed" to "ploughed",
        "mold" to "mould", "skeptic" to "sceptic", "skeptical" to "sceptical", "sulfur" to "sulphur", "cozy" to "cosy", "donut" to "doughnut",
        "donuts" to "doughnuts", "aging" to "ageing", "artifact" to "artefact", "mustache" to "moustache", "maneuver" to "manoeuvre"
    )
    private val VOCAB = mapOf("mom" to "mum", "moms" to "mums", "gotten" to "got", "sidewalk" to "pavement", "sidewalks" to "pavements", "vacation" to "holiday", "vacations" to "holidays")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Regex, Map<String, String>>>()

    private fun build(code: String): Pair<Regex, Map<String, String>>? {
        val r = rules[code] ?: return null
        val m = LinkedHashMap<String, String>()
        if (r.our) for (w in OUR) for (s in OUR_SUFFIX) m[w + s] = w.dropLast(2) + "our" + s
        if (r.re) for (w in RE) { m[w] = w.dropLast(2) + "re"; m[w + "s"] = w.dropLast(2) + "res" }
        if (r.ise) {
            for (w in IZE) { val st = w.dropLast(1); for (s in IZE_SUFFIX) m[st + s] = st.dropLast(1) + "s" + s }
            for (w in YZE) { val st = w.dropLast(1); for (s in listOf("e", "es", "ed", "ing")) m[st + s] = st.dropLast(1) + "s" + s }
        }
        if (r.ence) for (w in ENCE) { m[w] = w.dropLast(2) + "ce"; m[w + "s"] = w.dropLast(2) + "ces" }
        if (r.ogue) for (w in OGUE) { m[w] = w + "ue"; m[w + "s"] = w + "ues" }
        if (r.ll) for (w in LL) m[w] = ll(w)
        if (r.misc) m.putAll(MISC)
        if (r.vocab) m.putAll(VOCAB)
        val re = Regex("\\b(" + m.keys.sortedByDescending { it.length }.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)
        return re to m
    }

    /** traveler → traveller, canceled → cancelled, counselor → counsellor. */
    private fun ll(w: String): String = when {
        w.startsWith("counselor") -> w.replaceFirst("counselor", "counsellor")
        w.endsWith("ed") -> w.dropLast(2) + "led"
        w.endsWith("ing") -> w.dropLast(3) + "ling"
        w.endsWith("ers") -> w.dropLast(3) + "lers"
        w.endsWith("er") -> w.dropLast(2) + "ler"
        else -> w
    }

    fun apply(text: String, code: String): String {
        val (re, map) = cache[code] ?: (build(code) ?: return text).also { cache[code] = it }
        return re.replace(text) { mr ->
            val src = mr.value
            val to = map[src.lowercase()] ?: return@replace src
            when {
                src.length > 1 && src.all { !it.isLetter() || it.isUpperCase() } -> to.uppercase()
                src.first().isUpperCase() -> to.replaceFirstChar { it.uppercase() }
                else -> to
            }
        }
    }
}
