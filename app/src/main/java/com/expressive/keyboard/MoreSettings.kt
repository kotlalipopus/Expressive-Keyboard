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
// 16. НАСТРОЙКИ: ЯЗЫКИ И СЛОВАРИ, ИКОНКИ, ПАНЕЛЬ ИНСТРУМЕНТОВ
// ============================================================================

@Composable
internal fun LanguagesPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val enabled = Languages.enabled(settings)
    val dictVersion = Dictionaries.version
    val status = Dictionaries.status
    var query by remember { mutableStateOf("") }
    var layoutFor by remember { mutableStateOf<KeyboardLanguage?>(null) }

    fun save(list: List<KeyboardLanguage>) =
        KeyboardPrefs.edit(context) { putString("languages", list.joinToString(",") { it.id }) }

    fun download(lang: KeyboardLanguage) {
        val code = lang.dict ?: return
        scope.launch {
            Dictionaries.download(context, code)
                .onSuccess { Toast.makeText(context, tr("Словарь «{0}»: {1} слов", lang.displayTitle(), it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, tr("Не удалось скачать словарь: {0}", it.message), Toast.LENGTH_LONG).show() }
        }
    }

    InfoCard(
        Icons.Rounded.Language,
        tr(
            "Язык меняется кнопкой 🌐 (удержание — список языков) или свайпом по пробелу. Всего языков: {0}. " +
                "Подсказки работают по частотному словарю: русский, английский и китайский встроены, остальные скачиваются " +
                "(~0,3–0,8 МБ). Китайский набирается пиньинем (nihao → 你好), японский — ромадзи (konnichiha → こんにちは), " +
                "корейский собирает слоги сам, вьетнамский — по Telex.", Languages.all.size
        )
    )
    SettingsSection("Включённые языки") {
        enabled.forEachIndexed { i, lang ->
            key(dictVersion, lang.id) {
                LanguageRow(
                    lang,
                    dictState(context, lang, status),
                    trailing = {
                        IconButton(onClick = { layoutFor = lang }) {
                            Icon(Icons.Rounded.Keyboard, tr("Раскладка"), tint = if (lang.customLayout) cs.primary else cs.onSurfaceVariant)
                        }
                        IconButton(onClick = { if (i > 0) save(enabled.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "Выше")
                        }
                        IconButton(onClick = { if (i < enabled.lastIndex) save(enabled.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }, enabled = i < enabled.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Ниже")
                        }
                        IconButton(onClick = { save(enabled - lang) }, enabled = enabled.size > 1) {
                            Icon(Icons.Rounded.RemoveCircleOutline, "Убрать", tint = if (enabled.size > 1) cs.error else cs.outline)
                        }
                    },
                    onDict = { download(lang) }
                )
            }
        }
    }
    SettingsSection("Добавить язык") {
        GroupItem {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text(tr("Поиск: немецкий, Deutsch, de…")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true
            )
        }
        val q = query.trim().lowercase()
        Languages.all.filter { it !in enabled }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.displayTitle().lowercase().contains(q) || it.spaceLabel.lowercase().contains(q) || it.id.startsWith(q) }
            .forEach { lang ->
                key(dictVersion, lang.id) {
                    LanguageRow(lang, dictState(context, lang, status, enabled = false), trailing = {
                        FilledTonalIconButton(onClick = {
                            save(enabled + lang)
                            if (settings.dictAutoDownload && lang.dict != null && !Dictionaries.isInstalled(context, lang.dict)) download(lang)
                        }) { Icon(Icons.Rounded.Add, "Добавить") }
                    }, onDict = null)
                }
            }
    }
    SettingsSection("Словари") {
        ToggleItem(Icons.Rounded.CloudDownload, "Скачивать словарь автоматически", "При добавлении языка и при первом наборе на нём", settings.dictAutoDownload) {
            KeyboardPrefs.edit(context) { putBoolean("dict_auto_download", it) }
        }
    }
    layoutFor?.let { l -> LangLayoutDialog(l, settings) { layoutFor = null } }
}

private fun dictState(context: Context, lang: KeyboardLanguage, status: Map<String, String>, enabled: Boolean = true): String {
    if (lang.composer == Composer.PINYIN) return tr("Встроенный словарь пиньиня — 40 000 слов")
    if (lang.composer == Composer.KANA) return tr("Хирагана и катакана из латиницы")
    val code = lang.dict ?: return tr("Без словаря — подсказки из ваших слов")
    status[code]?.let { return if (it == "загрузка…") tr("Словарь загружается…") else tr("Ошибка: {0}", it) }
    return when {
        Dictionaries.bundled(context, code) -> tr("Словарь встроен")
        Dictionaries.isInstalled(context, code) -> tr("Словарь скачан")
        enabled -> tr("Словарь не скачан — нажмите, чтобы скачать")
        else -> tr("Есть словарь подсказок")
    }
}

@Composable
private fun LanguageRow(lang: KeyboardLanguage, state: String, trailing: @Composable RowScope.() -> Unit, onDict: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val error = state.startsWith(tr("Ошибка: {0}", ""))
    val canDownload = onDict != null && (state == tr("Словарь не скачан — нажмите, чтобы скачать") || error)
    GroupItem(onClick = if (canDownload) onDict else null) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Text(lang.label, color = cs.onSecondaryContainer, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f)) {
                val title = lang.displayTitle()
                Text(if (title == lang.spaceLabel) title else "$title · ${lang.spaceLabel}", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state, style = MaterialTheme.typography.bodySmall,
                    color = if (error) cs.error else if (canDownload) cs.primary else cs.onSurfaceVariant
                )
                if (lang.customLayout) {
                    Text(
                        tr("Своя раскладка: {0}", lang.rows.firstOrNull()?.take(6)?.joinToString("").orEmpty() + "…"),
                        style = MaterialTheme.typography.bodySmall, color = cs.primary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

// ----------------------------------------------------------------------- иконки

@Composable
internal fun DefaultSlotIcon(slot: String, tint: Color, size: Dp) {
    val v: ImageVector? = when (slot) {
        "back" -> Icons.AutoMirrored.Rounded.ArrowBack
        "mic" -> Icons.Outlined.Mic
        "plugins" -> Icons.Rounded.Extension
        "translate" -> Icons.Rounded.Translate
        "gif" -> Icons.Rounded.GifBox
        "clipboard" -> Icons.Rounded.ContentPaste
        "emoji" -> Icons.Rounded.EmojiEmotions
        "one_handed" -> Icons.Rounded.SwapHoriz
        "incognito" -> Icons.Rounded.VisibilityOff
        "settings" -> Icons.Rounded.Settings
        "hide" -> Icons.Rounded.KeyboardHide
        "send" -> Icons.AutoMirrored.Rounded.Send
        "search" -> Icons.Rounded.Search
        "done" -> Icons.Rounded.Check
        else -> null
    }
    when {
        v != null -> Icon(v, null, tint = tint, modifier = Modifier.size(size))
        slot == "menu" -> GlyphIcon(Glyph.GRID, tint, size)
        slot == "globe" -> GlyphIcon(Glyph.GLOBE, tint, size)
        slot == "emoji_key" -> GlyphIcon(Glyph.EMOJI, tint, size)
        slot == "shift" -> GlyphIcon(Glyph.SHIFT, tint, size)
        slot == "caps" -> GlyphIcon(Glyph.CAPS, tint, size)
        slot == "backspace" -> GlyphIcon(Glyph.BACKSPACE, tint, size)
        slot == "enter" -> GlyphIcon(Glyph.RETURN, tint, size)
        slot == "go" -> GlyphIcon(Glyph.ARROW, tint, size)
        slot == "layout_swap" -> LayoutSwapIcon(tint, size)
        slot == "emoji_recent" -> Text("🕒", fontSize = 20.sp)
        slot.startsWith("emoji_cat_") -> Text(
            EmojiCategory.ICONS.values.toList().getOrElse(slot.removePrefix("emoji_cat_").toIntOrNull() ?: 0) { "😀" },
            fontSize = 20.sp
        )
    }
}

@Composable
internal fun IconsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IconSlot?>(null) }
    var textFor by remember { mutableStateOf<IconSlot?>(null) }
    var menuFor by remember { mutableStateOf<IconSlot?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // список из трёх десятков иконок нужен редко — раскрывается по нажатию
    var showIcons by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val slot = target
        if (uri != null && slot != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { IconStore.setImage(context, slot.id, uri) }
            if (!ok) Toast.makeText(context, tr("Не удалось открыть картинку"), Toast.LENGTH_LONG).show()
        }
        target = null
    }
    val emojiFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "emoji_check.ttf")
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("open")
                    requireNotNull(AndroidTypeface.Builder(temp).build())
                    temp.copyTo(File(context.filesDir, KeyboardPrefs.EMOJI_FONT_FILENAME), overwrite = true)
                    temp.delete()
                }.isSuccess
            }
            if (ok) KeyboardPrefs.setEmojiFont(context)
            else Toast.makeText(context, tr("Нужен файл шрифта .ttf с цветными эмодзи"), Toast.LENGTH_LONG).show()
        }
    }

    SettingsSection("Стиль эмодзи") {
        GroupItem { EmojiStylePicker(settings) { emojiFontLauncher.launch("*/*") } }
        if (settings.emojiFontVersion != 0L) {
            ActionItem(
                Icons.Rounded.DeleteOutline, "Удалить свой шрифт эмодзи", null,
                onClick = { KeyboardPrefs.resetEmojiFont(context) }
            )
        }
    }
    SettingsSection("Свои иконки") {
        ActionItem(
            if (showIcons) Icons.Rounded.ExpandLess else Icons.Rounded.Brush,
            if (showIcons) "Скрыть список иконок" else "Заменить иконки кнопок",
            "Своя картинка или эмодзи вместо любой иконки: меню, микрофон, ⌫, Enter, вкладки эмодзи",
            onClick = { showIcons = !showIcons }
        )
    }
    if (showIcons) IconStore.slots.groupBy { it.group }.forEach { (group, slots) ->
        SettingsSection(group) {
            for (slot in slots) {
                val custom = IconStore.userIcon(slot.id)
                GroupItem(onClick = { menuFor = slot }) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            SlotIcon(slot.id, cs.onPrimaryContainer, 24.dp) { DefaultSlotIcon(slot.id, cs.onPrimaryContainer, 24.dp) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(tr(slot.title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                            Text(
                                when (custom) {
                                    null -> tr(if (IconStore.get(slot.id) != null) "Из плагина" else "Стандартная")
                                    is CustomIcon.Picture -> tr(if (custom.tint) "Своя картинка · в цвет темы" else "Своя картинка")
                                    is CustomIcon.Label -> tr("Свой текст: {0}", custom.text)
                                },
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                            )
                        }
                        if (custom != null) {
                            IconButton(onClick = { IconStore.reset(context, slot.id) }) { Icon(Icons.Rounded.RestartAlt, "Вернуть стандартную") }
                        }
                    }
                }
            }
        }
    }
    if (showIcons) SettingsSection(null) {
        ActionItem(Icons.Rounded.RestartAlt, "Вернуть все иконки", null, onClick = { confirmReset = true })
    }

    menuFor?.let { slot ->
        val custom = IconStore.userIcon(slot.id)
        AlertDialog(
            onDismissRequest = { menuFor = null },
            icon = { SlotIcon(slot.id, cs.primary, 32.dp) { DefaultSlotIcon(slot.id, cs.primary, 32.dp) } },
            title = { Text(tr(slot.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { target = slot; menuFor = null; imageLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Image, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Картинка из галереи"))
                    }
                    FilledTonalButton(onClick = { textFor = slot; menuFor = null }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.EmojiEmotions, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Эмодзи или текст"))
                    }
                    if (custom is CustomIcon.Picture) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Перекрашивать в цвет темы"), Modifier.weight(1f))
                            Switch(checked = custom.tint, onCheckedChange = { IconStore.setTint(context, slot.id, it) })
                        }
                    }
                    if (custom != null) {
                        OutlinedButton(onClick = { IconStore.reset(context, slot.id); menuFor = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Вернуть стандартную"))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text(tr("Готово")) } }
        )
    }
    textFor?.let { slot ->
        TextInputDialog(slot.title, (IconStore.userIcon(slot.id) as? CustomIcon.Label)?.text.orEmpty(), "Эмодзи или до 6 символов", onDismiss = { textFor = null }) { v ->
            if (v.isNotBlank()) IconStore.setText(context, slot.id, v.trim()) else IconStore.reset(context, slot.id)
            textFor = null
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("Вернуть все иконки?")) },
            text = { Text(tr("Свои картинки и тексты будут удалены.")) },
            confirmButton = { Button(onClick = { IconStore.resetAll(context); confirmReset = false }) { Text(tr("Вернуть")) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Отмена")) } }
        )
    }
}

// ----------------------------------------------------------------------- панель инструментов

@Composable
internal fun ToolbarPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val catalog = Toolbar.catalog()
    val byId = catalog.associateBy { it.id }
    val hidden = settings.toolbarHiddenSet
    val order = remember(catalog, settings.toolbarOrder) {
        Toolbar.arrange(catalog.map { it.id }, settings.toolbarOrderList, hidden, includeHidden = true)
    }
    fun saveOrder(list: List<String>) = KeyboardPrefs.edit(context) { putString("toolbar_order", list.joinToString(",")) }

    InfoCard(
        Icons.Rounded.DragIndicator,
        "Порядок кнопок на панели инструментов (кнопка с сеткой слева вверху). На самой клавиатуре порядок тоже " +
            "меняется: удерживайте кнопку и перетащите её влево или вправо."
    )
    SettingsSection("Кнопка панели") {
        ToggleItem(
            Icons.Rounded.Apps, "Кнопка панели функций",
            if (!settings.showHeader) "Верхняя панель выключена"
            else if (settings.toolsButton) "Кнопка с сеткой слева от подсказок"
            else if (settings.showSuggestions) "Скрыта: панель функций появляется, когда поле пустое"
            else "Скрыта: подсказки выключены, поэтому панель функций видна всегда",
            settings.toolsButton
        ) { KeyboardPrefs.edit(context) { putBoolean("tools_button", it) } }
    }
    SettingsSection("Кнопки") {
        order.forEachIndexed { i, id ->
            val def = byId[id] ?: return@forEachIndexed
            val visible = id !in hidden
            GroupItem {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (visible) cs.secondaryContainer else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        val tint = if (visible) cs.onSecondaryContainer else cs.outline
                        when {
                            def.slot != null && def.icon != null -> SlotIcon(def.slot, tint, 20.dp) { Icon(def.icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
                            def.spec != null -> SpecIcon(def.spec, tint, 20.dp)
                        }
                    }
                    Text(
                        tr(def.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        color = if (visible) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { saveOrder(order.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }, enabled = i > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Раньше")
                    }
                    IconButton(onClick = { saveOrder(order.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }, enabled = i < order.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Позже")
                    }
                    Switch(
                        checked = visible,
                        onCheckedChange = { on -> Toolbar.setHidden(context, if (on) hidden - id else hidden + id) }
                    )
                }
            }
        }
    }
    SettingsSection(null) {
        ActionItem(Icons.Rounded.RestartAlt, "Порядок по умолчанию", "Все кнопки снова видны", onClick = {
            KeyboardPrefs.edit(context) { remove("toolbar_order"); remove("toolbar_hidden") }
        })
    }
}

/** Стили эмодзи: системный, встроенные наборы картинок и свой шрифт. В каждой карточке — пример. */
@Composable
internal fun EmojiStylePicker(settings: KeyboardSettings, onCustom: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val font = remember(settings.emojiFontVersion) { FontRepository.emoji(context, settings.emojiFontVersion) }
    val options = listOf(EmojiStyles.SYSTEM to tr("Как в системе")) + EmojiStyles.builtIn.map { it.first to it.second } +
        (EmojiStyles.CUSTOM to tr("Свой шрифт…"))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (id, title) ->
            val sel = settings.emojiStyle == id
            Column(
                Modifier.width(132.dp).clip(RoundedCornerShape(if (sel) 26.dp else 16.dp))
                    .background(if (sel) cs.primaryContainer else cs.surfaceContainerHigh)
                    .clickable {
                        if (id == EmojiStyles.CUSTOM && settings.emojiFontVersion == 0L) onCustom()
                        else KeyboardPrefs.edit(context) { putString("emoji_style", id) }
                    }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("😀", "😍", "🐱", "🍕").forEach { EmojiGlyph(it, 26.dp, id, font) }
                }
                Text(
                    title, style = MaterialTheme.typography.labelLarge, color = if (sel) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 2, minLines = 2
                )
            }
        }
    }
}

// ----------------------------------------------------------------------- своя раскладка языка

/** Сохранить ряды языка (null — вернуть раскладку языка). */
internal fun saveLangLayout(context: Context, settings: KeyboardSettings, langId: String, rows: List<String>?) {
    val o = runCatching { JSONObject(settings.langLayouts.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    if (rows == null) o.remove(langId) else o.put(langId, JSONArray(rows))
    KeyboardPrefs.edit(context) { putString("lang_layouts", if (o.length() == 0) "" else o.toString()) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LangLayoutDialog(lang: KeyboardLanguage, settings: KeyboardSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val original = remember(lang.id) { Languages.original(lang.id) ?: lang }
    val current = Languages.userRows[lang.id] ?: original.rows
    var text by remember { mutableStateOf(current.joinToString("\n") { Languages.rowToText(it) }) }
    var otherOpen by remember { mutableStateOf(false) }
    val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val parsed = rows.map { Languages.parseRowText(it) }
    val error = when {
        parsed.isEmpty() -> tr("Нужен хотя бы один ряд")
        parsed.size > 5 -> tr("Не больше 5 рядов")
        parsed.any { it.size > 14 } -> tr("В ряду не больше 14 клавиш")
        else -> null
    }
    fun fill(r: List<List<String>>) { text = r.joinToString("\n") { Languages.rowToText(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Keyboard, null) },
        title = { Text(tr("Раскладка: {0}", lang.displayTitle())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Словарь, подсказки и голосовой ввод остаются от языка — меняются только клавиши. Последний ряд получает Shift и ⌫."),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { fill(original.rows) }, label = { Text(tr("Как у языка")) })
                    Languages.presets.forEach { (name, r) ->
                        AssistChip(onClick = { fill(r.map { Languages.parseRowText(it) }) }, label = { Text(name) })
                    }
                    AssistChip(onClick = { otherOpen = !otherOpen }, label = { Text(tr("Другого языка…")) })
                }
                if (otherOpen) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Languages.all.filter { it.id != lang.id && it.composer == Composer.NONE }.forEach { l ->
                            Text(
                                "${l.displayTitle()} · " + l.rows.firstOrNull()?.take(6)?.joinToString("").orEmpty(),
                                Modifier.fillMaxWidth().clickable { fill(l.rows); otherOpen = false }.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(tr("Ряды — по одному в строке")) },
                    supportingText = { Text(error ?: tr("Буквы подряд: йцукен. Через пробел — если на клавише несколько знаков: sh ch ŋ")) },
                    isError = error != null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), minLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val same = parsed == original.rows
                saveLangLayout(context, settings, lang.id, if (same) null else rows)
                onDismiss()
            }, enabled = error == null) { Text(tr("Сохранить")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}
