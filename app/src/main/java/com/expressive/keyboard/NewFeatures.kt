package com.expressive.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

// Страницы «Новое» и «Энергосбережение».

/** Версия, о которой рассказывает «Новое»: страницу обновляем к каждому выпуску. */
private const val NEW_IN_VERSION = "11.1.9"

private val ENGLISH_VARIANTS = listOf(
    "en-gb" to "Великобритания", "en-au" to "Австралия", "en-ca" to "Канада", "en-in" to "Индия",
    "en-ie" to "Ирландия", "en-nz" to "Новая Зеландия", "en-za" to "ЮАР"
)

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
    var dialog by remember { mutableStateOf<String?>(null) }
    val enabledIds = settings.languages.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    InfoCard(Icons.Rounded.NewReleases, tr("В версии {0}", NEW_IN_VERSION))

    SettingsSection("Подсказки умнее") {
        ActionItem(
            Icons.Rounded.Spellcheck, "Подсказки по смыслу фразы",
            "Следующее слово и исправления учитывают предыдущее слово (русский и английский)",
            onClick = { onNavigate(SettingsPage.TYPING) }
        )
    }
    SettingsSection("Свои клавиши") {
        ActionItem(
            Icons.Rounded.AddCircleOutline, "Добавить клавишу",
            "Новая клавиша с любым символом, словом или смайлом. Её можно перетащить на любое место",
            onClick = { onNavigate(SettingsPage.LAYOUT) }
        )
    }
    SettingsSection("Планшеты") {
        SliderItem(
            icon = Icons.Rounded.Straighten, title = "Ширина клавиатуры",
            value = settings.keyboardWidth, range = 0.5f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("keyboard_width", if (v > 0.98f) 1f else v) } }
        )
    }
    SettingsSection("Свои ИИ-сервисы") {
        ActionItem(
            Icons.Rounded.Hub, "Добавить свой сервис",
            "DeepSeek, Mistral, xAI, Ollama, LM Studio или любой API как у OpenAI или Anthropic",
            onClick = { onNavigate(SettingsPage.TYPING) }
        )
    }
    SettingsSection("Слияние клавиш") {
        ToggleItem(
            Icons.Rounded.BlurOn, "Слияние клавиш", "Соседние стеклянные клавиши перетекают друг в друга при нажатии",
            settings.glassMerge
        ) { KeyboardPrefs.edit(context) { putBoolean("glass_merge", it) } }
    }
    SettingsSection("Моды") {
        ActionItem(
            Icons.Rounded.Extension, "API модов 4",
            "Частицы, шейдеры, эффекты клавиш, вкладки и пресеты в настройках мода",
            onClick = { onNavigate(SettingsPage.PLUGINS) }
        )
    }

    SettingsSection("Размер клавиатуры в альбомной") {
        LandscapeScaleItem(settings)
    }
    SettingsSection("Удержание пробела и верхняя панель") {
        ChoiceItem(
            Icons.Rounded.SpaceBar, "Удержание пробела", null,
            listOf("PICKER" to "Выбор клавиатуры", "LANGUAGE" to "Смена языка"), settings.spaceLongAction
        ) { v ->
            KeyboardPrefs.edit(context) {
                putString("space_long_action", v)
                putBoolean("space_long_press", true)
            }
        }
        SliderItem(
            icon = Icons.Rounded.Height, title = "Толщина верхней панели",
            value = settings.headerHeightDp, range = 40f..60f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("header_height_dp", v) } }
        )
    }
    SettingsSection("Энергосбережение") {
        EcoWhenItem(settings)
        ActionItem(Icons.Rounded.BatterySaver, "Что выключается", null, onClick = { onNavigate(SettingsPage.POWER) })
    }
    SettingsSection("Мой словарь") {
        ActionItem(Icons.Rounded.MenuBook, "Открыть «Мой словарь»", "Добавляйте слова вручную или загружайте файл", onClick = { onNavigate(SettingsPage.MY_WORDS) })
    }
    SettingsSection("OpenRouter — сотни моделей по одному ключу") {
        ToggleItem(
            Icons.Rounded.AutoFixHigh, "Использовать OpenRouter", "Исправлять текст через OpenRouter",
            settings.aiProvider == AiProvider.OPENROUTER.id
        ) { on -> KeyboardPrefs.edit(context) { putString("ai_provider", if (on) AiProvider.OPENROUTER.id else AiProvider.AUTO.id) } }
        ActionItem(
            Icons.Rounded.Key, "Ключ OpenRouter",
            if (settings.openrouterKey.isBlank()) "openrouter.ai/keys — есть бесплатные модели" else "Задан",
            onClick = { dialog = "key" }
        )
        ActionItem(
            Icons.Rounded.Tune, "Модель OpenRouter", settings.openrouterModel.ifBlank { tr("По умолчанию: {0}", AiFix.OPENROUTER_MODEL) },
            onClick = { dialog = "model" }
        )
    }
    SettingsSection("Варианты английского") {
        ENGLISH_VARIANTS.forEach { (id, title) ->
            ToggleItem(Icons.Rounded.Language, title, "Своя метка на клавише и свой язык голосового ввода", id in enabledIds) { on ->
                val next = if (on) enabledIds + id else enabledIds - id
                KeyboardPrefs.edit(context) { putString("languages", next.joinToString(",")) }
            }
        }
    }
    SettingsSection("Стекло") {
        ActionItem(Icons.Rounded.BlurOn, "Преломление и зернистость теперь заметны", "Ползунки — в настройках темы со стеклом", onClick = { onNavigate(SettingsPage.THEMES) })
    }
    SettingsSection("Импорт настроек") {
        ActionItem(Icons.Rounded.Download, "Импорт настроек", "Исправлен импорт, который на части телефонов ничего не делал", onClick = { onNavigate(SettingsPage.BACKUP) })
    }

    when (dialog) {
        "key" -> TextInputDialog("Ключ OpenRouter", settings.openrouterKey, "Ключ с openrouter.ai/keys", onDismiss = { dialog = null }) { v ->
            KeyboardPrefs.edit(context) { putString("openrouter_key", SecretBox.encrypt(v.trim())) }
            dialog = null
        }
        "model" -> TextInputDialog("Модель OpenRouter", settings.openrouterModel, "Пусто — модель по умолчанию", onDismiss = { dialog = null }) { v ->
            KeyboardPrefs.edit(context) { putString("openrouter_model", v.trim()) }
            dialog = null
        }
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
