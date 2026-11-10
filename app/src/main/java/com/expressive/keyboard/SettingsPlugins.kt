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
// НАСТРОЙКИ: ПЛАГИНЫ И МОДЫ
// ============================================================================

@Composable
internal fun PluginsPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val plugins = PluginRepository.plugins
    val errors = PluginRepository.errors
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<KeyboardPlugin?>(null) }
    var pendingNetwork by remember { mutableStateOf<KeyboardPlugin?>(null) }

    fun onImported(r: Result<KeyboardPlugin>) {
        r.onSuccess { p ->
            Toast.makeText(context, tr("Плагин «{0}» установлен", tr(p.name)), Toast.LENGTH_SHORT).show()
            expanded = p.id
            if (p.usesNetwork || p.permissions.any { it in setOf("read_text", "clipboard", "launch", "ai", "sensors", "microphone", "app_info") }) pendingNetwork = p
        }.onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
    }
    val modExampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/javascript")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.assets.open("examples/my-mod.js").use { it.readBytes() }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Пример мода сохранён — отредактируйте и импортируйте") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { onImported(withContext(Dispatchers.IO) { PluginRepository.importFrom(context, uri) }) }
    }
    val exampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(PluginRepository.EXAMPLE.toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Пример сохранён — отредактируйте и импортируйте") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }

    InfoCard(
        Icons.Rounded.Extension,
        "Как установить: нажмите «Импорт из файла» и выберите плагин (.json, .js или .zip) или скопируйте его текст " +
            "и нажмите «Импорт из буфера обмена». Плагин появится в списке ниже — его можно включить, выключить или удалить.\n\n" +
            "Как пользоваться: на клавиатуре нажмите кнопку с сеткой слева вверху, затем кнопку «Плагины» — откроются вкладки плагинов " +
            "с их кнопками и панелями. Кнопки, отмеченные плагином, появляются прямо на панели инструментов.\n\n" +
            "Как сделать свой: сохраните пример кнопкой ниже, отредактируйте и импортируйте. Описание формата — в файле PLUGINS.md."
    )
    SettingsSection("Установка") {
        ActionItem(Icons.Rounded.FileOpen, "Импорт из файла", ".js (мод), .zip (мод с картинками) или .json", onClick = {
            importLauncher.launch(arrayOf("application/json", "application/javascript", "text/javascript", "application/zip", "text/plain", "application/octet-stream", "*/*"))
        })
        ActionItem(Icons.Rounded.Code, "Сохранить пример мода (.js)", "Своя панель, кнопка, реакция на слова и хранилище", onClick = {
            modExampleLauncher.launch("my-mod.js")
        })
        ActionItem(Icons.Rounded.ContentPaste, "Импорт из буфера обмена", "Скопируйте JSON плагина или код мода и нажмите", onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (text.isBlank()) Toast.makeText(context, tr("Буфер обмена пуст"), Toast.LENGTH_SHORT).show()
            else onImported(PluginRepository.importText(context, text))
        })
        ActionItem(Icons.Rounded.Download, "Сохранить пример плагина", "Шаблон со всеми возможностями формата", onClick = {
            exampleLauncher.launch("my-plugin.json")
        })
    }
    if (errors.isNotEmpty()) {
        SettingsSection("Не загрузились") {
            errors.forEach { e ->
                GroupItem {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = cs.error)
                        Text(e, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    }
                }
            }
        }
    }
    val user = plugins.filter { !it.builtIn }
    val builtIn = plugins.filter { it.builtIn }
    if (user.isNotEmpty()) {
        SettingsSection("Мои плагины") {
            user.forEach { p ->
                PluginCard(p, expanded == p.id, onExpand = { expanded = if (expanded == p.id) null else p.id }, onDelete = { confirmDelete = p })
            }
        }
    }
    SettingsSection("Встроенные") {
        builtIn.forEach { p ->
            PluginCard(p, expanded == p.id, onExpand = { expanded = if (expanded == p.id) null else p.id }, onDelete = null)
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(tr("Удалить «{0}»?", tr(p.name))) },
            text = { Text(tr("Файл плагина будет удалён. Его можно импортировать заново.")) },
            confirmButton = {
                Button(onClick = {
                    PluginRepository.delete(context, p.id)
                    confirmDelete = null
                }) { Text(tr("Удалить")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Отмена")) } }
        )
    }
    pendingNetwork?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingNetwork = null },
            icon = { Icon(Icons.Rounded.Security, null) },
            title = { Text(tr("Разрешения плагина")) },
            text = {
                Text(
                    tr("«{0}» просит:", tr(p.name)) + "\n\n" + buildList {
                        if ("read_text" in p.permissions) add("• " + tr("читать набираемый текст и текст в поле"))
                        if ("clipboard" in p.permissions) add("• " + tr("читать историю буфера обмена"))
                        if ("launch" in p.permissions) add("• " + tr("открывать ссылки, приложения и окно «Поделиться»"))
                        if ("ai" in p.permissions) add("• " + tr("отправлять запросы ИИ по вашему ключу (расходует лимит ключа)"))
                        if ("sensors" in p.permissions) add("• " + tr("видеть наклон и движение телефона, пока открыта клавиатура"))
                        if ("microphone" in p.permissions) add("• " + tr("слышать громкость и частоты с микрофона, пока открыта клавиатура (сам звук мод не получает)"))
                        if ("app_info" in p.permissions) add("• " + tr("знать, в каком приложении вы печатаете"))
                        if ("network" in p.permissions) add(
                            "• " + tr("выходить в интернет") + p.actions.filter { it.type == PluginActionType.HTTP }
                                .joinToString("") { "\n   ${tr(it.title)}: ${Uri.parse(it.url).host}" }
                        )
                    }.joinToString("\n") + "\n\n" + tr("Устанавливайте моды только из источников, которым доверяете. Оставить плагин включённым?")
                )
            },
            confirmButton = { Button(onClick = { pendingNetwork = null }) { Text(tr("Оставить")) } },
            dismissButton = {
                TextButton(onClick = {
                    PluginRepository.setEnabled(context, p.id, false)
                    pendingNetwork = null
                }) { Text(tr("Выключить")) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginCard(p: KeyboardPlugin, expanded: Boolean, onExpand: () -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val settings by KeyboardPrefs.observe()
    val enabled = PluginRepository.isEnabled(p.id) && p.id !in PluginRepository.disabled
    val spec = remember(p.id, settings.shapeAmount) {
        val presets = ExpressiveShapePreset.decorative
        val pr = presets[(p.id.hashCode() and 0x7fffffff) % presets.size]
        WavySpec.of(pr).decor(settings)
    }
    GroupItem(onClick = onExpand) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).wavyBackground(spec, if (enabled) cs.primaryContainer else cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { SpecIcon(p.icon, if (enabled) cs.onPrimaryContainer else cs.onSurfaceVariant, 24.dp) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr(p.name), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.usesNetwork) Icon(Icons.Rounded.Public, "Использует сеть", tint = cs.tertiary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        listOf("v${p.version}", p.author, p.summary).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { PluginRepository.setEnabled(context, p.id, it) },
                    thumbContent = if (enabled) {
                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.description.isNotBlank()) Text(tr(p.description), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    val perms = p.permissions.filter { it in PluginParser.PERMISSIONS }
                    if (perms.isNotEmpty()) {
                        Text(tr("Разрешения"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            perms.joinToString("  ·  ") {
                                when (it) {
                                    "read_text" -> tr("читает текст")
                                    "clipboard" -> tr("буфер обмена")
                                    "launch" -> tr("открывает приложения")
                                    "ai" -> tr("ИИ по вашему ключу")
                                    "sensors" -> tr("датчики движения")
                                    "microphone" -> tr("громкость микрофона")
                                    "app_info" -> tr("видит приложение")
                                    else -> tr("интернет")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    if (p.config.isNotEmpty()) {
                        Text(tr("Настройки мода"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        ModConfigEditor(p)
                    }
                    if (p.actions.isNotEmpty()) {
                        Text(tr("Действия"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.actions.joinToString("  ·  ") { if (it.icon.startsWith("@") || it.icon.startsWith("data:")) tr(it.title) else "${it.icon} ${tr(it.title)}" },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (p.script != null) {
                        val log = ScriptHost.logs[p.id].orEmpty()
                        Text(tr("Журнал скрипта"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            if (log.isEmpty()) tr(if (enabled) "Пусто" else "Мод выключен") else log.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(10.dp)
                        )
                    }
                    if (p.shortcuts.isNotEmpty()) {
                        Text(tr("Сокращения (по пробелу)"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.shortcuts.entries.take(12).joinToString("\n") { (k, v) -> "$k → ${v.replace("\n", "⏎").take(40)}" } +
                                if (p.shortcuts.size > 12) "\n… " + tr("ещё {0}", p.shortcuts.size - 12) else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (p.symbols.isNotEmpty()) {
                        Text(tr("Символы"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(p.symbols.joinToString("  ·  ") { "${it.icon} ${tr(it.title)} (${it.items.size})" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    if (p.layouts.isNotEmpty()) {
                        Text(tr("Раскладки"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.layouts.entries.joinToString("\n") { (id, rows) -> "$id: " + rows.joinToString(" / ") { r -> r.keys.joinToString("") { it.label.take(1) } } },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val skinOn = p.skin != null && settings.skinPlugin == p.id
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.hasLook && !skinOn) {
                            FilledTonalButton(onClick = {
                                if (!enabled) PluginRepository.setEnabled(context, p.id, true)
                                PluginRepository.applyLook(context, p)
                                Toast.makeText(context, tr("Оформление «{0}» применено", tr(p.name)), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Rounded.FormatPaint, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Применить оформление"))
                            }
                        }
                        if (skinOn) {
                            OutlinedButton(onClick = { PluginRepository.removeSkin(context) }) {
                                Icon(Icons.Rounded.FormatColorReset, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Снять оформление"))
                            }
                        }
                        if (onDelete != null) {
                            OutlinedButton(onClick = onDelete) {
                                Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = cs.error)
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Удалить"), color = cs.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- резервная копия
