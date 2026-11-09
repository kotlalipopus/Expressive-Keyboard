package com.expressive.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

// Страницы «Новое» и «Энергосбережение».

/** Версия, о которой рассказывает «Новое»: страницу обновляем к каждому выпуску. */
private const val NEW_IN_VERSION = "11.2.3"

/** Размер клавиатуры в альбомной: и в «Клавишах», и в «Новом». */
@Composable
internal fun LandscapeScaleItem(settings: KeyboardSettings) {
    val context = LocalContext.current
    SliderItem(
        icon = Icons.Rounded.ScreenRotation, title = "Размер клавиатуры в альбомной",
        value = settings.landscapeScale, range = 0.7f..1.8f,
        format = { "${(it * 100).roundToInt()}%" },
        onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("landscape_scale", v) } }
    )
}

@Composable
internal fun WhatsNewPage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current

    InfoCard(Icons.Rounded.NewReleases, tr("В версии {0}", NEW_IN_VERSION))

    SettingsSection("Японский: кандзи") {
        InfoCard(Icons.Rounded.Translate, "Хирагана превращается в кандзи: にほんご → 日本語. Слова и целые фразы, словарь встроен")
    }
    SettingsSection("Размер и закрытие") {
        InfoCard(Icons.Rounded.SwipeDown, "Свайп вниз по строке подсказок закрывает клавиатуру (можно выключить в «Жестах»)")
        InfoCard(Icons.Rounded.OpenInFull, "Плавающая клавиатура: уголок слева на ручке меняет ширину (влево-вправо) и высоту клавиш (вверх-вниз)")
        InfoCard(Icons.Rounded.KeyboardReturn, "Ширина клавиши ввода и высота ряда цифр — в «Оформлении»")
        InfoCard(Icons.Rounded.ContentCut, "Панель правки: добавлены «Вырезать» и «⌫»")
    }
    SettingsSection("Испанский интерфейс") {
        InfoCard(Icons.Rounded.Language, "Язык интерфейса: Español")
        InfoCard(Icons.Rounded.Public, "Бенгальский (Бангладеш)")
    }
    SettingsSection("Обновление без переустановки") {
        InfoCard(Icons.Rounded.SystemUpdate, "Версия ставится поверх и 11.2.0, и 11.1.9 и старше — удалять клавиатуру больше не нужно")
        ActionItem(
            Icons.Rounded.Backup, "Словарь Т9 не пропадает",
            "Автокопия в папку и системная копия Android: после переустановки слова возвращаются",
            onClick = { onNavigate(SettingsPage.BACKUP) }
        )
    }

    SettingsSection("Material 3 Expressive") {
        ActionItem(
            Icons.Rounded.Style, "Новые дизайны и формы",
            "Material 3 Expressive, Material 3, Liquid Glass, Classic, Neon, Samsung, Nothing — в один тап",
            onClick = { onNavigate(SettingsPage.THEMES) }
        )
        ActionItem(
            Icons.Rounded.ColorLens, "Сколько выразительных форм",
            "Много форм, только акценты или совсем без них",
            onClick = { onNavigate(SettingsPage.COLORS) }
        )
    }
    SettingsSection("Эмодзи") {
        ToggleItem(
            Icons.Rounded.Keyboard, "Возврат к буквам после эмодзи",
            "Выключено: панель остаётся открытой, и можно выбрать несколько эмодзи подряд",
            settings.emojiReturnToKeyboard
        ) { on -> KeyboardPrefs.edit(context) { putBoolean("emoji_return_keyboard", on) } }
    }
    SettingsSection("Звуки нажатия") {
        ActionItem(
            Icons.Rounded.VolumeUp, "Новые звуки клавиш",
            "Создаются прямо в приложении, отдельно для букв и для пробела, Enter и ⌫",
            onClick = { onNavigate(SettingsPage.FEEDBACK) }
        )
    }
    SettingsSection("Первый запуск") {
        InfoCard(Icons.Rounded.AutoAwesome, "Новые пользователи выбирают тему, количество форм и нужные функции при первом запуске")
    }
}

@Composable
private fun EcoWhenItem(settings: KeyboardSettings) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PowerState.update(context) }
    val on = when (settings.ecoWhen) {
        "ALWAYS" -> true
        "NEVER" -> false
        else -> PowerState.saver
    }
    ChoiceItem(
        Icons.Rounded.BatterySaver, "Когда включать", if (on) "Сейчас включено" else "Сейчас выключено",
        listOf("SYSTEM" to "Вместе с системой", "ALWAYS" to "Всегда", "NEVER" to "Никогда"), settings.ecoWhen
    ) { v -> KeyboardPrefs.edit(context) { putString("eco_when", v) } }
}

@Composable
internal fun PowerPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    fun set(key: String, v: Boolean) = KeyboardPrefs.edit(context) { putBoolean(key, v) }
    SettingsSection(null) { EcoWhenItem(settings) }
    SettingsSection("Что выключается") {
        ToggleItem(Icons.Rounded.BlurOn, "Размытие и жидкое стекло", "Заметнее всего на заряде: размытие приложения и линзы на клавишах", settings.ecoBlur) { set("eco_blur", it) }
        ToggleItem(Icons.Rounded.Animation, "Анимация нажатия клавиш", null, settings.ecoPress) { set("eco_press", it) }
        ToggleItem(Icons.Rounded.Animation, "Анимация панелей", null, settings.ecoPanels) { set("eco_panels", it) }
        ToggleItem(Icons.Rounded.Gesture, "След при свайп-наборе", null, settings.ecoTrail) { set("eco_trail", it) }
        ToggleItem(Icons.Rounded.TouchApp, "Всплывающая буква при нажатии", null, settings.ecoPreview) { set("eco_preview", it) }
        ToggleItem(Icons.Rounded.Vibration, "Вибрация", null, settings.ecoHaptics) { set("eco_haptics", it) }
        ToggleItem(Icons.Rounded.VolumeUp, "Звук нажатия клавиш", null, settings.ecoSound) { set("eco_sound", it) }
        ToggleItem(Icons.Rounded.Spellcheck, "Подсказки слов", null, settings.ecoSuggestions) { set("eco_suggestions", it) }
    }
}
