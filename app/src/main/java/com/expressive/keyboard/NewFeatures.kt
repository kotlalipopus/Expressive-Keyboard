package com.expressive.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

// Страницы «Новое» и «Энергосбережение».

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
internal fun WhatsNewPage(onNavigate: (SettingsPage) -> Unit) {
    InfoCard(Icons.Rounded.NewReleases, tr("В версии {0}", BuildConfig.VERSION_NAME.substringBefore('-')))
    SettingsSection("Ввод") {
        ActionItem(Icons.Rounded.Calculate, "Ответ после знака «=»",
            "Например, 199+123= показывает 322. Нажмите на ответ, чтобы вставить его.",
            onClick = { onNavigate(SettingsPage.TYPING) })
        ActionItem(Icons.Rounded.Send, "Отправка долгим нажатием Enter",
            "Если приложение поддерживает IME-действие отправки",
            onClick = { onNavigate(SettingsPage.GESTURES) })
        InfoCard(Icons.Rounded.Keyboard, "Исправлены быстрый набор с Shift и устаревшие подсказки при повороте экрана")
    }
    SettingsSection("Внешний вид") {
        ActionItem(Icons.Rounded.Wallpaper, "Размещение фото",
            "Масштаб, ширина, высота и положение фото под клавишами",
            onClick = { onNavigate(SettingsPage.COLORS) })
        ActionItem(Icons.Rounded.Palette, "Создать тему",
            "Разделы редактора, предпросмотр и сохранение отдельной копии темы",
            onClick = { onNavigate(SettingsPage.THEMES) })
    }
    SettingsSection("Клавиатура") {
        InfoCard(Icons.Rounded.EmojiEmotions, "Недавние эмодзи меняют порядок при следующем открытии меню")
        ActionItem(Icons.Rounded.Language, "Арабский (Египет)",
            "Новая раскладка и обновлённый редактор рядов",
            onClick = { onNavigate(SettingsPage.LANGUAGES) })
        ActionItem(Icons.Rounded.Straighten, "Размер и положение",
            "Одинаковая высота букв и символов. Свои размеры плавающей клавиатуры.",
            onClick = { onNavigate(SettingsPage.SIZE) })
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
