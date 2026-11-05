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
// НАСТРОЙКИ МОДА: ПЕРЕКЛЮЧАТЕЛИ, ПОЛЗУНКИ, ВЫБОР
// ============================================================================

/** Условие showIf: "id", "!id" или "id=значение". */
internal fun modOptionVisible(rule: String, values: Map<String, Any?>): Boolean {
    if (rule.isEmpty()) return true
    if ('=' in rule) return values[rule.substringBefore('=').trim()]?.toString() == rule.substringAfter('=').trim()
    val negate = rule.startsWith("!")
    val v = values[rule.removePrefix("!").trim()]
    val on = v == true || (v is Number && v.toDouble() != 0.0) || (v is String && v.isNotEmpty())
    return on != negate
}

/** Настройки мода из его блока "config": вкладки, разделы, переключатели, выбор, ползунки, текст и пресеты. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModConfigEditor(p: KeyboardPlugin) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // значения могли поменять пресет и сам скрипт (kb.setConfig): их надо показывать сразу
    val rev = ModConfig.revision
    val values = remember(rev, p) { ModConfig.all(context, p) }
    val tabs = remember(p) { p.config.map { it.tab }.distinct() }
    var tab by remember(p.id) { mutableStateOf(tabs.firstOrNull().orEmpty()) }
    var toggled by remember(p.id) { mutableStateOf(emptySet<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tabs.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in tabs) FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(tr(t).ifEmpty { tr("Основное") }) })
            }
        }
        var current: String? = null
        for (o in p.config) {
            if (o.tab != tab) continue
            if (o.section != current) {
                current = o.section
                if (o.section.isNotEmpty()) {
                    val name = o.section
                    val open = o.sectionCollapsed == (name in toggled)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { toggled = if (name in toggled) toggled - name else toggled + name }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr(name), style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.weight(1f))
                        Text(if (open) "▾" else "▸", color = cs.primary)
                    }
                }
            }
            if (o.section.isNotEmpty() && o.sectionCollapsed != (o.section in toggled)) continue
            if (!modOptionVisible(o.showIf, values)) continue
            key(o.id) { ModOptionRow(p, o, values[o.id], rev) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModOptionRow(p: KeyboardPlugin, o: ModOption, value: Any?, rev: Int) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    fun set(v: Any?) = ModConfig.set(context, p, o.id, v)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (o.type) {
            "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(o.title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = value == true, onCheckedChange = { set(it) })
            }
            "choice" -> {
                Text(tr(o.title), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((cid, ctitle) in o.choices) {
                        FilterChip(selected = value?.toString() == cid, onClick = { set(cid) }, label = { Text(tr(ctitle)) })
                    }
                }
            }
            "slider" -> {
                var v by remember(rev, value) { mutableFloatStateOf((value as? Number)?.toFloat() ?: o.min) }
                Text("${tr(o.title)}: " + if (o.step >= 1f) v.roundToInt().toString() else "%.2f".format(v), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = v.coerceIn(o.min, o.max),
                    onValueChange = { x -> v = if (o.step > 0f) (kotlin.math.round((x - o.min) / o.step) * o.step + o.min).coerceIn(o.min, o.max) else x },
                    onValueChangeFinished = { set(v) },
                    valueRange = o.min..maxOf(o.max, o.min + 0.001f)
                )
            }
            "preset" -> FilledTonalButton(
                onClick = {
                    val n = ModConfig.setAll(context, p, o.values)
                    ModConfig.press(p, o.id)
                    Toast.makeText(context, tr("Пресет «{0}»: изменено настроек — {1}", tr(o.title), n), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr(o.title)) }
            "button" -> OutlinedButton(onClick = { ModConfig.press(p, o.id) }, modifier = Modifier.fillMaxWidth()) { Text(tr(o.title)) }
            "info" -> Text(tr(o.title), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            else -> {
                val multi = o.type == "textarea"
                var t by remember(rev, value) { mutableStateOf(value?.toString().orEmpty()) }
                val swatch = if (o.type == "color") PluginParser.parseColor(t) else null
                OutlinedTextField(
                    value = t, onValueChange = { t = it }, label = { Text(tr(o.title)) }, singleLine = !multi,
                    minLines = if (multi) 3 else 1, maxLines = if (multi) 10 else 1,
                    placeholder = if (o.hint.isNotEmpty()) ({ Text(tr(o.hint)) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = if (swatch != null) ({
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color(swatch)))
                    }) else null,
                    trailingIcon = {
                        if (t != value?.toString().orEmpty()) IconButton(onClick = { set(t) }) { Icon(Icons.Rounded.Check, null) }
                    }
                )
            }
        }
        if (o.description.isNotEmpty()) Text(tr(o.description), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}
