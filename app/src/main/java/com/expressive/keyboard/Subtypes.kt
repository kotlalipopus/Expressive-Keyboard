package com.expressive.keyboard

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

// ============================================================================
// ПОДТИПЫ ВВОДА ПО ЯЗЫКАМ
// Проверка правописания в приложениях (красные подчёркивания) берёт язык из текущего подтипа клавиатуры.
// Был английский подтип, а печатали по-русски — всё русское помечалось ошибкой. Теперь у каждого
// включённого языка свой подтип, и при смене языка клавиатура переключает подтип вместе с ним.
// ============================================================================

object Subtypes {
    private const val EXTRA = "lang="
    /** Подтипы, объявленные в method.xml: для этих языков дополнительные не нужны. */
    private val DECLARED = setOf("ru_RU", "en_US", "ar_EG")

    @Volatile private var registeredFor: String? = null
    @Volatile private var info: InputMethodInfo? = null
    /** Подтип, на который клавиатура переключилась сама: его ответный вызов не меняет язык. */
    @Volatile private var lastSwitched: InputMethodSubtype? = null

    private fun imm(context: Context) = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun imeId(context: Context) = ComponentName(context, ComposeKeyboardService::class.java).flattenToShortString()

    /** "ru-RU" → "ru_RU": так локаль записывается в подтипах. */
    private fun localeOf(lang: KeyboardLanguage) = lang.locale.replace('-', '_')

    private fun build(lang: KeyboardLanguage): InputMethodSubtype {
        val b = InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale(localeOf(lang))
            .setLanguageTag(lang.locale)
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue(EXTRA + lang.id)
            .setIsAsciiCapable(lang.rows.flatten().any { s -> s.any { it in 'a'..'z' } })
            .setSubtypeId(("subtype:" + lang.id).hashCode())
        if (Build.VERSION.SDK_INT >= 34) b.setSubtypeNameOverride(lang.title)
        return b.build()
    }

    /** Свои подтипы для включённых языков — один раз на набор языков (вызов дорогой: система перестраивает список клавиатур). */
    fun register(context: Context, languages: List<KeyboardLanguage>) {
        val extra = languages.filter { it.locale.isNotBlank() && localeOf(it) !in DECLARED }
        val signature = extra.joinToString(",") { it.id + "/" + it.locale }
        if (signature == registeredFor) return
        val prefs = context.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)
        runCatching {
            if (prefs.getString("subtypes", null) != signature) {
                @Suppress("DEPRECATION")
                imm(context)?.setAdditionalInputMethodSubtypes(imeId(context), extra.map(::build).toTypedArray())
                prefs.edit().putString("subtypes", signature).apply()
            }
            registeredFor = signature
            info = null
        }
    }

    private fun ownInfo(context: Context): InputMethodInfo? {
        info?.let { return it }
        val id = imeId(context)
        return runCatching { imm(context)?.inputMethodList?.firstOrNull { it.id == id } }.getOrNull()?.also { info = it }
    }

    private fun subtypes(i: InputMethodInfo): List<InputMethodSubtype> = (0 until i.subtypeCount).map { i.getSubtypeAt(it) }

    /** Подтип языка: свой (по коду языка), иначе объявленный с той же локалью. */
    private fun subtypeFor(context: Context, lang: KeyboardLanguage): InputMethodSubtype? {
        val all = ownInfo(context)?.let(::subtypes) ?: return null
        return all.firstOrNull { it.extraValue == EXTRA + lang.id }
            ?: all.firstOrNull { it.locale == localeOf(lang) }
    }

    /** Язык подтипа из списка включённых; null — подтип не наш или язык выключен. */
    fun languageOf(subtype: InputMethodSubtype?, enabled: List<KeyboardLanguage>, current: KeyboardLanguage): KeyboardLanguage? {
        subtype ?: return null
        val extra = subtype.extraValue.orEmpty()
        if (extra.startsWith(EXTRA)) {
            val id = extra.removePrefix(EXTRA)
            return enabled.firstOrNull { it.id == id }
        }
        val locale = subtype.locale.orEmpty()
        if (locale.isEmpty()) return null
        // объявленный подтип: тот же язык, что сейчас, — не меняем (en_US подходит и к English, и к English UK)
        if (current.locale.replace('-', '_') == locale) return current
        return enabled.firstOrNull { localeOf(it) == locale }
            ?: enabled.firstOrNull { localeOf(it).substringBefore('_') == locale.substringBefore('_') }
    }

    /** Это ответ на наше собственное переключение. */
    fun isOwnSwitch(subtype: InputMethodSubtype?): Boolean = subtype != null && subtype == lastSwitched

    /**
     * Переключить подтип под язык клавиатуры (без смены самой клавиатуры).
     * verify — сначала спросить систему, какой подтип сейчас: его могли сменить, пока клавиатура была не активна.
     */
    fun switchTo(service: InputMethodService, lang: KeyboardLanguage, verify: Boolean = false) {
        runCatching {
            val i = ownInfo(service) ?: return
            val target = subtypeFor(service, lang) ?: return
            if (verify) lastSwitched = imm(service)?.currentInputMethodSubtype
            if (target == lastSwitched) return
            lastSwitched = target
            if (Build.VERSION.SDK_INT >= 28) {
                service.switchInputMethod(i.id, target)
            } else {
                val token = service.window?.window?.attributes?.token ?: return
                @Suppress("DEPRECATION")
                imm(service)?.setInputMethodAndSubtype(token, i.id, target)
            }
        }
    }

    /** Подтип сменили извне (системная кнопка, выбор клавиатуры): запомнить, чтобы не переключать обратно зря. */
    fun noteCurrent(subtype: InputMethodSubtype?) {
        lastSwitched = subtype
    }
}
