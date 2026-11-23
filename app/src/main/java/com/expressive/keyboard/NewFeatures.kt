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
    SettingsSection("Внешний вид") {
        ActionItem(Icons.Rounded.Videocam, "Видео на фоне клавиатуры", "Выберите видео из галереи в разделе «Цвета и фон».",
            onClick = { onNavigate(SettingsPage.COLORS) })
        ActionItem(Icons.Rounded.EmojiEmotions, "Размер эмодзи", "Чем крупнее эмодзи, тем меньше их помещается в ряду.",
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
