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
import kotlinx.coroutines.CoroutineStart

// ============================================================================
// ТЕМЫ: галерея с живым предпросмотром, настройки выбранной темы и редактор своих тем.
// Предпросмотр рисует настоящие клавиши оформления (drawSkinKey) — как на клавиатуре.
// ============================================================================

private data class MiniKey(val label: String, val type: KeyType, val w: Float)

private fun miniRows(english: Boolean): List<List<MiniKey>> {
    fun letters(s: String) = s.map { MiniKey(it.toString(), KeyType.CHARACTER, 1f) }
    val r1 = if (english) "qwertyuiop" else "йцукенгшщз"
    val r2 = if (english) "asdfghjkl" else "фывапролд"
    val r3 = if (english) "zxcvbnm" else "ячсмитьб"
    return listOf(
        letters(r1),
        listOf(MiniKey("", KeyType.SPACER, 0.5f)) + letters(r2) + MiniKey("", KeyType.SPACER, 0.5f),
        listOf(MiniKey("⇧", KeyType.SHIFT, 1.4f)) + letters(r3).take(if (english) 7 else 7) + MiniKey("⌫", KeyType.BACKSPACE, 1.6f),
        listOf(
            MiniKey("?123", KeyType.MODE_SYMBOLS, 1.5f), MiniKey("☺", KeyType.STICKERS, 1.1f),
            MiniKey(if (english) "English" else "Русский", KeyType.SPACE, 4.6f), MiniKey(".", KeyType.CHARACTER, 1f),
            MiniKey("↵", KeyType.ENTER, 1.6f)
        )
    )
}

/** Тёмная ли сейчас клавиатура: по режиму темы из настроек. */
@Composable
internal fun keyboardIsDark(settings: KeyboardSettings): Boolean {
    val system = isSystemInDarkTheme()
    return when (settings.themeMode) {
        "DARK", "AMOLED" -> true
        "LIGHT" -> false
        else -> system
    }
}

/** Мини-клавиатура в стиле оформления. interactive — клавиши можно нажимать, чтобы увидеть анимацию. */
@Composable
internal fun SkinMiniPreview(skin: KeyboardSkin?, settings: KeyboardSettings, modifier: Modifier, interactive: Boolean = false) {
    val shown = remember(skin, settings) { skin?.applyColors(settings) ?: settings }
    val palette = rememberKeyPalette(shown)
    val baseFont = LocalKeyFont.current
    val font = remember(skin, baseFont) { skin?.fontFamily(baseFont) ?: baseFont }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    SideEffect { PatternTiles.density = density.density }
    val rows = remember(I18n.isEnglish) { miniRows(I18n.isEnglish) }
    val caches = remember(skin) { HashMap<Int, SkinKeyCache>() }
    val rects = remember { ArrayList<Pair<Int, Rect>>() }
    var pressedIdx by remember { mutableIntStateOf(-1) }
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pressAnim = remember(skin, settings.animPress, settings.customAnims) { Anims.forKey(settings, skin?.styleFor(KeyType.CHARACTER, "a")) }
    Canvas(
        modifier.clip(RoundedCornerShape(18.dp))
            .then(
                if (!interactive) Modifier else Modifier.pointerInput(skin) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val hit = rects.firstOrNull { it.second.contains(down.position) } ?: return@awaitEachGesture
                        pressedIdx = hit.first
                        caches[hit.first]?.touch = down.position - hit.second.topLeft
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            press.snapTo(0.6f)
                            press.animateTo(1f, spring(dampingRatio = 1f, stiffness = 4000f))
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        scope.launch { press.animateTo(0f, spring(dampingRatio = pressAnim.damping, stiffness = pressAnim.stiffness)) }
                    }
                }
            )
    ) {
        drawRect(palette.background)
        skin?.let { if (it.hasBackground) drawSkinBackground(it, size) }
        val pad = 6.dp.toPx()
        val gap = 4.dp.toPx()
        val rowGap = 6.dp.toPx()
        val rowH = (size.height - pad * 2 - rowGap * (rows.size - 1)) / rows.size
        val corner = settings.cornerRadiusDp.dp.toPx()
        rects.clear()
        var idx = 0
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.w.toDouble() }.toFloat()
            val unit = (size.width - pad * 2 - gap * (row.size - 1)) / total
            var x = pad
            val y = pad + ri * (rowH + rowGap)
            for (k in row) {
                val w = unit * k.w
                if (k.type == KeyType.SPACER) {
                    x += w + gap
                    continue
                }
                val i = idx++
                val rect = Rect(x, y, x + w, y + rowH)
                rects += i to rect
                x += w + gap
                val p = if (i == pressedIdx) press.value.coerceIn(0f, 1f) else 0f
                val a = if (i == pressedIdx) press.value else 0f
                val st = skin?.styleFor(k.type, k.label)
                val colors = keyColors(k.type, ShiftState.OFF, palette)
                val cache = caches.getOrPut(i) { SkinKeyCache() }
                val center = rect.center
                withTransform({
                    // анимация нажатия — та же формула, что на клавиатуре
                    val k2 = a * settings.pressAnimation
                    scale(1f + (pressAnim.scaleX - 1f) * k2, 1f + (pressAnim.scaleY - 1f) * k2, center)
                    translate(0f, pressAnim.lift * density.density * k2)
                    rotate(pressAnim.rotate * k2, center)
                }) {
                    inset(rect.left, rect.top, size.width - rect.right, size.height - rect.bottom) {
                        val m = min(this.size.width, this.size.height)
                        val fr = shapeFractions(k.type)
                        var rest = fr.first * m
                        if (k.type == KeyType.CHARACTER) rest = min(rest, corner)
                        if (st != null) drawSkinKey(st, colors, p, rest, fr.second * m, cache, null) { null }
                        else drawRoundRect(lerp(colors.bg, colors.activeBg, p), cornerRadius = CornerRadius(rest, rest))
                        val fg = pressedLabelColor(st, colors, p > 0.5f)
                        val textSize = when (k.type) {
                            KeyType.SPACE, KeyType.MODE_SYMBOLS -> rowH * 0.26f
                            else -> rowH * 0.42f * (st?.fontScale ?: 1f)
                        }
                        val shadow = st?.textShadow?.takeIf { it.alpha > 0.01f }?.let { Shadow(it, Offset(1.2f * density.density, 1.2f * density.density), 0f) }
                            ?: st?.textGlow?.let { Shadow(it, Offset.Zero, 12f) }
                        val layout = measurer.measure(
                            AnnotatedString(st?.label?.takeIf { k.type != KeyType.CHARACTER } ?: k.label),
                            TextStyle(
                                color = if (k.type == KeyType.SPACE) fg.copy(alpha = 0.72f) else fg,
                                fontSize = with(density) { textSize.toSp() },
                                fontFamily = font,
                                fontWeight = FontWeight(st?.fontWeight ?: settings.fontWeight),
                                fontStyle = if (st?.italic == true) FontStyle.Italic else null,
                                shadow = shadow
                            )
                        )
                        val tl = Offset((this.size.width - layout.size.width) / 2f, (this.size.height - layout.size.height) / 2f)
                        if (st?.labelEffect == LabelEffect.DOTS) {
                            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, this.size), Paint()) }
                            drawText(layout, topLeft = tl)
                            drawRect(PatternTiles.dotMask(1.7.dp.toPx()), blendMode = BlendMode.DstIn)
                            drawIntoCanvas { it.restore() }
                        } else drawText(layout, topLeft = tl)
                    }
                }
            }
        }
    }
}

/** Карточка темы в галерее. */
@Composable
private fun ThemeCard(title: String, subtitle: String, selected: Boolean, skin: KeyboardSkin?, settings: KeyboardSettings, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 28.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "themeCorner")
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh, label = "themeBg")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(corner)).background(bg)
            .then(if (selected) Modifier.border(2.dp, cs.primary, RoundedCornerShape(corner)) else Modifier)
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SkinMiniPreview(skin, settings, Modifier.fillMaxWidth().height(96.dp))
        Text(tr(title), style = MaterialTheme.typography.titleSmall, color = if (selected) cs.onPrimaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(
                tr(subtitle), style = MaterialTheme.typography.bodySmall,
                color = if (selected) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant, maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Оформление темы для предпросмотра — с её настройками и акцентом, как на клавиатуре. */
private fun previewSkin(p: KeyboardPlugin?, settings: KeyboardSettings, dark: Boolean): KeyboardSkin? =
    if (p == null) null else runCatching { ThemeOptions.build(p, p.skin ?: return null, ThemeOptions.values(settings, p.id), settings, dark) }.getOrNull()

@Composable
internal fun ThemesPage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val dark = keyboardIsDark(settings)
    val plugins = PluginRepository.plugins
    var confirmDelete by remember { mutableStateOf<KeyboardPlugin?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { PluginRepository.importFrom(context, uri) }
            r.onSuccess { p ->
                if (p.skin != null) ThemeEditor.apply(context, p.id)
                Toast.makeText(context, tr("Тема «{0}» установлена", p.name), Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
        }
    }

    // галерея: готовые стили + темы из плагинов и свои
    val designSkins = DesignStyle.entries.map { it.skin }.toSet()
    val extra = plugins.filter { it.skin != null && it.id !in designSkins }
    SettingsSection(null) { ThemeModeItem(settings) }
    SettingsSection("Темы") {
        GroupItem {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val items = DesignStyle.entries.map { d -> Triple(d.title, d.subtitle, d.skin) } + extra.map { Triple(tr(it.name), if (it.builtIn) "" else tr("Своя тема"), it.id) }
                items.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (title, sub, skinId) ->
                            val plugin = plugins.firstOrNull { it.id == skinId }
                            val skin = remember(plugin, settings.skinOptions, settings.userAccent, settings.colorAccentText, settings.keyOpacity, dark) { previewSkin(plugin, settings, dark) }
                            Box(Modifier.weight(1f)) {
                                ThemeCard(title, sub, settings.skinPlugin == skinId, skin, settings) {
                                    val d = DesignStyle.entries.firstOrNull { it.skin == skinId }
                                    if (d != null) d.apply(context) else ThemeEditor.apply(context, skinId)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        ActionItem(Icons.Rounded.ColorLens, "Акцентный цвет и цвета клавиш", "Enter, Shift и подсветка в любой теме — в вашем цвете", onClick = {
            onNavigate(SettingsPage.COLORS)
        })
    }

    if (Build.VERSION.SDK_INT >= 33) SettingsSection("Жидкое стекло") {
        ToggleItem(
            Icons.Rounded.BlurOn, "Жидкое стекло на любой теме",
            "Клавиши — стеклянные линзы: гнут и размазывают фон, с объёмными бликами. Размытие приложения под клавиатурой включается само",
            settings.liquidGlass
        ) { KeyboardPrefs.edit(context) { putBoolean("liquid_glass", it) } }
    }

    // настройки выбранной темы
    val current = PluginRepository.skinPlugin(settings)
    if (current?.skin != null) ThemeOptionsSections(current, settings, dark)

    SettingsSection("Свои темы") {
        ActionItem(Icons.Rounded.Brush, "Создать тему на основе текущей", "Меняйте всё: фон, каждую группу клавиш, форму, тени, шрифт, подсказки", onClick = {
            ThemeEditor.start(ThemeEditor.draftFrom(context, current, settings))
            onNavigate(SettingsPage.THEME_EDITOR)
        })
        ActionItem(Icons.Rounded.AddCircleOutline, "Создать тему с нуля", "Простая светлая и тёмная основа", onClick = {
            ThemeEditor.start(ThemeEditor.blank())
            onNavigate(SettingsPage.THEME_EDITOR)
        })
        ActionItem(Icons.Rounded.FileOpen, "Импорт темы", "Файл .json, которым с вами поделились", onClick = {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        })
        plugins.filter { it.skin != null && !it.builtIn }.forEach { p ->
            ActionItem(Icons.Rounded.Palette, p.name, if (settings.skinPlugin == p.id) tr("Применена") else tr("Нажмите, чтобы применить"), onClick = {
                ThemeEditor.apply(context, p.id)
            }, trailing = {
                Row {
                    IconButton(onClick = {
                        ThemeEditor.start(ThemeEditor.load(context, p) ?: return@IconButton)
                        onNavigate(SettingsPage.THEME_EDITOR)
                    }) { Icon(Icons.Rounded.Edit, tr("Изменить")) }
                    IconButton(onClick = { ThemeEditor.share(context, p) }) { Icon(Icons.Rounded.Share, tr("Поделиться")) }
                    IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = cs.error) }
                }
            })
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text(tr("Удалить «{0}»?", p.name)) },
            confirmButton = {
                Button(onClick = {
                    if (settings.skinPlugin == p.id) PluginRepository.removeSkin(context)
                    PluginRepository.delete(context, p.id)
                    confirmDelete = null
                }) { Text(tr("Удалить")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Отмена")) } }
        )
    }
}

/** «Настройки темы»: свои у каждой темы (из её файла) и общие для всех. */
@Composable
private fun ThemeOptionsSections(plugin: KeyboardPlugin, settings: KeyboardSettings, dark: Boolean) {
    val context = LocalContext.current
    val values = ThemeOptions.values(settings, plugin.id)
    val declared = remember(plugin) { ThemeOptions.declared(plugin.skinJson) }
    val common = remember(plugin) { ThemeOptions.common(plugin.skin!!) }
    // ползунок двигают — предпросмотр меняется сразу, в настройки значение пишется при отпускании
    var dragging by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val live = dragging?.let { values + (it.first to it.second) } ?: values
    val skin = remember(plugin, live, settings.userAccent, settings.colorAccentText, settings.keyOpacity, dark) {
        runCatching { ThemeOptions.build(plugin, plugin.skin!!, live, settings, dark) }.getOrNull()
    }
    SettingsSection(tr("Настройки темы «{0}»", tr(plugin.name))) {
        GroupItem { SkinMiniPreview(skin, settings, Modifier.fillMaxWidth().height(180.dp).padding(12.dp), interactive = true) }
        declared.forEach { OptionItem(plugin.id, it, values, settings) { id, v -> dragging = id to v } }
        if (declared.isEmpty()) {
            GroupItem {
                Text(
                    tr("У этой темы нет своих настроек — ниже общие для всех тем."),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    SettingsSection("Общие настройки темы") {
        common.forEach { OptionItem(plugin.id, it, values, settings) { id, v -> dragging = id to v } }
        ActionItem(Icons.Rounded.RestartAlt, "Сбросить настройки темы", "Вернуть всё, как задумано в теме", onClick = { ThemeOptions.reset(context, settings, plugin.id) })
    }
    LaunchedEffect(values) { dragging = null }
}

@Composable
private fun OptionItem(skinId: String, opt: SkinOption, values: Map<String, Any>, settings: KeyboardSettings, onDrag: (String, Float) -> Unit) {
    val context = LocalContext.current
    val v = ThemeOptions.valueOf(opt, values)
    when (opt.type) {
        OptionType.SLIDER -> SliderItem(
            icon = Icons.Rounded.Tune, title = opt.title,
            value = (v as? Number)?.toFloat() ?: 1f, range = opt.min..opt.max,
            format = { ThemeOptions.format(opt, it) },
            onCommit = { ThemeOptions.set(context, settings, skinId, opt.id, it.toDouble()) },
            onDrag = { onDrag(opt.id, it) }
        )
        OptionType.TOGGLE -> ToggleItem(Icons.Rounded.ToggleOn, opt.title, null, v == true || v.toString() == "true") {
            ThemeOptions.set(context, settings, skinId, opt.id, it)
        }
        OptionType.CHOICE -> ChoiceItem(Icons.Rounded.Style, opt.title, null, opt.choices.map { it.id to it.title }, v.toString()) {
            ThemeOptions.set(context, settings, skinId, opt.id, it)
        }
    }
}

// ============================================================================
// РЕДАКТОР ТЕМЫ
// Черновик — JSON плагина с "skin". Каждое поле редактора меняет одно значение в нём; предпросмотр
// разбирает черновик заново. Тёмный вариант — правки в "skin" → "dark" поверх светлого.
// ============================================================================

object ThemeEditor {
    var json by mutableStateOf("")
        private set
    var editDark by mutableStateOf(false)
    /** Выбранная группа клавиш: "*", "letter", "func", "enter", "key:а"… */
    var group by mutableStateOf("*")

    fun start(o: JSONObject) {
        json = o.toString()
        editDark = false
        group = "*"
    }

    fun root(): JSONObject = runCatching { JSONObject(json) }.getOrDefault(JSONObject())

    fun update(block: (JSONObject) -> Unit) {
        val o = root()
        block(o)
        json = o.toString()
    }

    /** Объект для правки: path внутри "skin" (или внутри "skin.dark" в тёмном режиме). */
    fun target(o: JSONObject, path: List<String>, create: Boolean, dark: Boolean = editDark): JSONObject? {
        var cur = o.optJSONObject("skin") ?: if (create) JSONObject().also { o.put("skin", it) } else return null
        if (dark) cur = cur.optJSONObject("dark") ?: if (create) JSONObject().also { cur.put("dark", it) } else return null
        for (p in path) cur = cur.optJSONObject(p) ?: if (create) JSONObject().also { cur.put(p, it) } else return null
        return cur
    }

    fun get(path: List<String>, field: String): Any? = target(root(), path, false)?.opt(field)?.takeIf { it != JSONObject.NULL }

    /** Значение из светлого варианта, если в тёмном его нет: показываем, откуда оно. */
    fun inherited(path: List<String>, field: String): Any? =
        if (editDark) target(root(), path, false, dark = false)?.opt(field)?.takeIf { it != JSONObject.NULL } else null

    fun set(path: List<String>, field: String, value: Any?) = update { o ->
        val t = target(o, path, true)!!
        if (value == null) t.remove(field) else t.put(field, value)
    }

    fun skin(): KeyboardSkin? = runCatching { SkinParser.parse(root().getJSONObject("skin")) }.getOrNull()
    fun error(): String? = runCatching { SkinParser.parse(root().getJSONObject("skin")); null }.getOrElse { it.message ?: "ошибка" }

    fun blank(): JSONObject = JSONObject(
        """{"id":"user.theme.${System.currentTimeMillis()}","name":"${tr("Моя тема")}","version":"1.0","author":"","description":"","icon":"🎨",
        "skin":{"keyboard":{"background":"#ECEFF4","corner":24},
        "colors":{"background":"#ECEFF4","key":"#FFFFFF","keyText":"#1B1B1F","func":"#D5DAE3","funcText":"#1B1B1F","accent":"#4F6BFF","accentText":"#FFFFFF"},
        "keys":{"*":{"material":"solid","background":"#FFFFFF","text":"#1B1B1F","radius":12,"shadow":2,"shadowY":1,"shadowColor":"#26000000"},
          "func":{"background":"#D5DAE3"},"enter":{"background":"#4F6BFF","text":"#FFFFFF"}},
        "dark":{"keyboard":{"background":"#16181D"},
          "colors":{"background":"#16181D","key":"#2C3038","keyText":"#F1F3F8","func":"#22252C","funcText":"#E6E9F0","accent":"#7B8FFF","accentText":"#FFFFFF"},
          "keys":{"*":{"background":"#2C3038","text":"#F1F3F8","shadowColor":"#59000000"},"func":{"background":"#22252C"},"enter":{"background":"#7B8FFF"}}}}}"""
    )

    /** Копия темы, со всеми выбранными в её настройках вариантами. */
    fun draftFrom(context: Context, p: KeyboardPlugin?, settings: KeyboardSettings): JSONObject {
        if (p?.skinJson == null) return blank()
        var skin = JSONObject(p.skinJson)
        val values = ThemeOptions.values(settings, p.id)
        for (opt in ThemeOptions.declared(p.skinJson)) {
            val v = values[opt.id] ?: opt.default
            val patch = when (opt.type) {
                OptionType.CHOICE -> opt.choices.firstOrNull { it.id == v.toString() }?.patch
                OptionType.TOGGLE -> if (v == true || v.toString() == "true") opt.on else opt.off
                OptionType.SLIDER -> null
            } ?: continue
            skin = ThemeOptions.merge(skin, patch)
        }
        skin.remove("options")
        val own = !p.builtIn
        return JSONObject()
            .put("id", if (own) p.id else "user.theme.${System.currentTimeMillis()}")
            .put("name", if (own) p.name else tr("{0} — моя", p.name))
            .put("version", "1.0").put("author", "").put("description", p.description)
            .put("icon", p.icon.takeIf { !it.startsWith("data:") } ?: "🎨")
            .put("skin", skin)
    }

    fun load(context: Context, p: KeyboardPlugin): JSONObject? =
        runCatching { JSONObject(File(File(context.filesDir, "plugins"), "${p.id}.json").readText()) }.getOrNull()

    fun apply(context: Context, id: String) {
        if (!PluginRepository.isEnabled(id)) PluginRepository.setEnabled(context, id, true)
        KeyboardPrefs.edit(context) {
            putString("skin_plugin", id)
            putString("design_style", "CUSTOM")
        }
    }

    fun save(context: Context): Result<KeyboardPlugin> {
        val o = root()
        if (!o.optString("id").startsWith("user.")) o.put("id", "user.theme.${System.currentTimeMillis()}")
        if (o.optString("name").isBlank()) o.put("name", tr("Моя тема"))
        return PluginRepository.importText(context, o.toString(2)).onSuccess { apply(context, it.id) }
    }

    fun share(context: Context, p: KeyboardPlugin) {
        val text = load(context, p)?.toString(2) ?: return
        shareText(context, text, p.name)
    }

    fun shareText(context: Context, text: String, name: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, name)
            context.startActivity(Intent.createChooser(send, tr("Поделиться темой")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private val GROUPS = listOf(
    "*" to "Все клавиши", "letter" to "Буквы", "digit" to "Цифры", "func" to "Служебные", "shift" to "Shift",
    "backspace" to "Стереть", "mode" to "?123", "globe" to "Язык", "emoji" to "Эмодзи", "space" to "Пробел", "enter" to "Enter"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeEditorPage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val json = ThemeEditor.json
    if (json.isEmpty()) {
        Text(tr("Откройте редактор из раздела «Темы»"), color = cs.onSurfaceVariant)
        return
    }
    val root = remember(json) { ThemeEditor.root() }
    val skin = remember(json, ThemeEditor.editDark) { ThemeEditor.skin()?.forMode(ThemeEditor.editDark) }
    val error = remember(json) { ThemeEditor.error() }
    var keyInput by remember { mutableStateOf(false) }

    GroupItem {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkinMiniPreview(skin, settings, Modifier.fillMaxWidth().height(190.dp), interactive = true)
            if (error != null) Text(error, color = cs.error, style = MaterialTheme.typography.bodySmall)
            ButtonGroup(listOf("light" to "Светлая", "dark" to "Тёмная"), if (ThemeEditor.editDark) "dark" else "light") {
                ThemeEditor.editDark = it == "dark"
            }
            Text(
                tr(if (ThemeEditor.editDark) "Правки тёмной версии ложатся поверх светлой: что не задано, берётся из светлой." else "Светлая версия — основа темы."),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
        }
    }
    SettingsSection("Основное") {
        GroupItem {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = root.optString("name"), onValueChange = { v -> ThemeEditor.update { it.put("name", v.take(40)) } },
                    label = { Text(tr("Название")) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = root.optString("icon"), onValueChange = { v -> ThemeEditor.update { it.put("icon", v.take(4)) } },
                    label = { Text(tr("Значок (эмодзи)")) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
            }
        }
        EChoice(listOf("keyboard"), "font", "Шрифт", listOf("" to "Из настроек", "sans" to "Системный", "pixel" to "Пиксели", "mono" to "Моно", "serif" to "Serif", "cursive" to "Курсив"))
        ESlider(listOf("keyboard"), "corner", "Скругление углов клавиатуры", 0f..48f, "dp")
        EColor(listOf("keyboard"), "trail", "След свайп-набора")
        EColor(listOf("keyboard"), "edge", "Контур по верхнему краю")
    }
    SettingsSection("Фон") {
        EFill(listOf("keyboard"), "background", "Фон клавиатуры")
        EToggleNum(listOf("keyboard"), "windowBlur", "Размытие приложения под клавиатурой", 32.0)
        if (ThemeEditor.get(listOf("keyboard"), "windowBlur") != null) {
            ESlider(listOf("keyboard"), "windowBlur", "Сила размытия", 4f..100f, "dp")
            EColor(listOf("keyboard"), "blurTint", "Дымка поверх размытия")
        }
    }
    SettingsSection("Цвета темы") {
        listOf(
            "background" to "Фон (для панелей)", "key" to "Клавиши", "keyText" to "Текст клавиш", "func" to "Служебные клавиши",
            "funcText" to "Текст служебных", "accent" to "Акцент", "accentText" to "Текст на акценте"
        ).forEach { (k, t) -> EColor(listOf("colors"), k, t) }
    }
    SettingsSection("Клавиши") {
        GroupItem {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Что настраиваем: сначала «Все клавиши», потом отличия групп"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val own = (root.optJSONObject("skin")?.optJSONObject("keys")?.keys()?.asSequence()?.filter { it.startsWith("key:") }?.toList() ?: emptyList())
                    (GROUPS + own.map { it to it.removePrefix("key:") }).forEach { (id, title) ->
                        FilterChip(selected = ThemeEditor.group == id, onClick = { ThemeEditor.group = id }, label = { Text(tr(title)) })
                    }
                    AssistChip(onClick = { keyInput = true }, label = { Text(tr("Одна клавиша…")) }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
                }
            }
        }
        StyleEditor(listOf("keys", ThemeEditor.group), full = true)
    }
    SettingsSection("Подсказка над клавишей") { StyleEditor(listOf("popup"), full = false) }
    SettingsSection("Подсказки слов") { StyleEditor(listOf("chips"), full = false) }
    SettingsSection("Круглые кнопки сверху") { StyleEditor(listOf("buttons"), full = false) }
    SettingsSection("Панели (эмодзи, буфер, переводчик)") { StyleEditor(listOf("panel"), full = false) }
    SettingsSection(null) {
        GroupItem {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    ThemeEditor.save(context)
                        .onSuccess {
                            Toast.makeText(context, tr("Тема «{0}» сохранена и применена", it.name), Toast.LENGTH_SHORT).show()
                            onNavigate(SettingsPage.THEMES)
                        }
                        .onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
                }, enabled = error == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Сохранить и применить"))
                }
                OutlinedButton(onClick = { ThemeEditor.shareText(context, root.toString(2), root.optString("name")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Поделиться файлом темы"))
                }
                TextButton(onClick = { onNavigate(SettingsPage.THEMES) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Выйти без сохранения")) }
            }
        }
    }
    if (keyInput) {
        TextInputDialog("Своя клавиша", "", "Буква или символ на клавише, например «я»", onDismiss = { keyInput = false }) { t ->
            keyInput = false
            val k = t.trim().lowercase().take(4)
            if (k.isNotEmpty()) {
                ThemeEditor.update { o -> ThemeEditor.target(o, listOf("keys", "key:$k"), true, dark = false) }
                ThemeEditor.group = "key:$k"
            }
        }
    }
}

/** Все свойства стиля группы клавиш (или подсказки, кнопок, панели). */
@Composable
private fun StyleEditor(path: List<String>, full: Boolean) {
    val material = (ThemeEditor.get(path, "material") ?: ThemeEditor.inherited(path, "material"))?.toString()
    EChoice(
        path, "material", "Материал",
        listOf("" to "Как у всех", "solid" to "Сплошной", "glass" to "Стекло", "outline" to "Контур", "neumorph" to "Неоморфизм", "bevel" to "Пиксельный", "none" to "Без фона")
    )
    EFill(path, "background", "Заливка")
    if (path.first() != "panel") EFill(path, "pressedBackground", "Заливка при нажатии")
    EColor(path, "text", "Цвет текста")
    if (full || path.first() == "popup") EColor(path, "pressedText", "Текст при нажатии")
    EColor(path, "border", "Цвет контура")
    ESlider(path, "borderWidth", "Толщина контура", 0f..6f, "dp1")
    EChoice(path, "borderStyle", "Вид контура", listOf("" to "Как у всех", "solid" to "Сплошной", "dots" to "Точки", "dashed" to "Пунктир"))
    ESlider(path, "radius", "Скругление", 0f..40f, "dp")
    if (full) {
        ESlider(path, "pressedRadius", "Скругление при нажатии", 0f..40f, "dp")
        EChoice(path, "shape", "Форма", listOf("" to "Как у всех", "rounded" to "Скруглённая", "pill" to "Капсула", "cut" to "Срезанные углы", "squircle" to "Сквиркл", "wavy" to "Волна"))
        ESlider(path, "shadow", "Тень", 0f..16f, "dp")
        ESlider(path, "shadowY", "Сдвиг тени", -8f..8f, "dp")
        EColor(path, "shadowColor", "Цвет тени")
        EColor(path, "glow", "Свечение")
        EColor(path, "textGlow", "Свечение текста")
        EColor(path, "textShadow", "Тень букв (без размытия)")
        ESlider(path, "fontScale", "Размер букв", 0.5f..1.6f, "x")
        ESlider(path, "fontWeight", "Толщина букв", 100f..900f, "int", steps = 7)
        EToggle(path, "italic", "Курсив")
        EChoice(path, "labelEffect", "Буквы", listOf("" to "Как у всех", "none" to "Обычные", "dots" to "Из точек"))
        EText(path, "label", "Своя подпись (служебные клавиши)")
        EChoice(path, "press", "Анимация нажатия", listOf("" to "Как у всех", "scale" to "Сжатие", "jelly" to "Желе", "bounce" to "Пружина", "sink" to "Вдавливание", "none" to "Нет"))
        ESlider(path, "pressScale", "Масштаб при нажатии", 0.8f..1.2f, "x")
        ESlider(path, "inset", "Отступ внутри ячейки", 0f..8f, "dp1")
        if (material == "bevel") ESlider(path, "bevel", "Объём кромки", 0f..6f, "dp1")
        EToggle(path, "hideHint", "Скрыть символ-подсказку в углу")
    }
    if (material == "glass") {
        val g = path + "glass"
        ESlider(g, "frost", "Размытие (матовость)", 0f..1f, "pct")
        ESlider(g, "grain", "Зерно", 0f..1f, "pct")
        ESlider(g, "refraction", "Преломление", 0f..1f, "pct")
        ESlider(g, "highlight", "Блик", 0f..1f, "pct")
        ESlider(g, "rim", "Ободок", 0f..1f, "pct")
        ESlider(g, "dispersion", "Радужная кайма", 0f..1f, "pct")
        ESlider(g, "blur", "Размытие фона под стеклом", 0f..40f, "dp")
        EColor(g, "tint", "Оттенок стекла")
    }
    ActionItem(Icons.Rounded.RestartAlt, "Сбросить эту группу", null, onClick = {
        ThemeEditor.update { o ->
            val parent = ThemeEditor.target(o, path.dropLast(1), false)
            parent?.remove(path.last())
        }
    })
}

// ----------------------------------------------------------------------- поля редактора

private fun colorString(argb: Int): String = String.format("#%08X", argb)

private fun parseColorAny(v: Any?): Int? = (v as? String)?.let { PluginParser.parseColor(it) }

@Composable
private fun EColor(path: List<String>, field: String, title: String) {
    var open by remember { mutableStateOf(false) }
    val own = parseColorAny(ThemeEditor.get(path, field))
    val inh = parseColorAny(ThemeEditor.inherited(path, field))
    val cs = MaterialTheme.colorScheme
    GroupItem(onClick = { open = true }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ColorSwatch(own ?: inh)
            Column(Modifier.weight(1f)) {
                Text(tr(title), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Text(
                    when {
                        own != null -> colorString(own)
                        inh != null -> tr("Как в светлой")
                        else -> tr("Не задан")
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
            if (own != null) IconButton(onClick = { ThemeEditor.set(path, field, null) }) { Icon(Icons.Rounded.Close, tr("Сбросить")) }
        }
    }
    if (open) {
        ColorPickerDialog(title, own ?: inh ?: 0xFF4F6BFF.toInt(), allowAuto = true, onDismiss = { open = false }, withAlpha = true, autoLabel = "Не задавать") { c ->
            ThemeEditor.set(path, field, if (c == 0) null else colorString(c))
            open = false
        }
    }
}

@Composable
private fun ColorSwatch(argb: Int?) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFCCCCCC), Color.White, Color(0xFFCCCCCC))))
            .background(if (argb != null) Color(argb) else Color.Transparent)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) { if (argb == null) Icon(Icons.Rounded.FormatColorReset, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

/** Заливка: цвет, градиент или узор (пиксели, точки, доски). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EFill(path: List<String>, field: String, title: String) {
    val cs = MaterialTheme.colorScheme
    val v = ThemeEditor.get(path, field)
    val obj = v as? JSONObject
    val kind = when {
        v == null -> ""
        v is String -> "solid"
        obj?.has("pattern") == true -> "pattern"
        obj?.has("colors") == true -> "gradient"
        else -> "solid"
    }
    val colors: List<Int> = when {
        v is String -> listOfNotNull(PluginParser.parseColor(v))
        obj != null -> obj.optJSONArray("colors")?.let { a -> (0 until a.length()).mapNotNull { PluginParser.parseColor(a.optString(it)) } } ?: emptyList()
        else -> emptyList()
    }
    var picking by remember { mutableStateOf<Int?>(null) }
    fun write(k: String, cols: List<Int>, extra: (JSONObject) -> Unit = {}) {
        val value: Any? = when (k) {
            "" -> null
            "solid" -> colorString(cols.firstOrNull() ?: 0xFFFFFFFF.toInt())
            else -> JSONObject().apply {
                put("colors", JSONArray(cols.map(::colorString)))
                if (k == "gradient") put("angle", obj?.optDouble("angle", 90.0) ?: 90.0)
                if (k == "pattern") {
                    put("pattern", obj?.optString("pattern")?.ifEmpty { null } ?: "pixels")
                    put("cell", obj?.optDouble("cell", 3.0) ?: 3.0)
                }
                extra(this)
            }
        }
        ThemeEditor.set(path, field, value)
    }
    GroupItem {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(title), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            ButtonGroup(listOf("" to "Нет", "solid" to "Цвет", "gradient" to "Градиент", "pattern" to "Узор"), kind) { k ->
                val base = colors.ifEmpty { listOf(0xFFFFFFFF.toInt()) }
                write(k, if (k == "solid") base.take(1) else if (base.size < 2) base + base.first() else base)
            }
            if (kind.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEachIndexed { i, c ->
                        Box(Modifier.clickable { picking = i }) { ColorSwatch(c) }
                    }
                    if (kind != "solid" && colors.size < 6) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(cs.secondaryContainer).clickable {
                            write(kind, colors + (colors.lastOrNull() ?: 0xFFFFFFFF.toInt()))
                        }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = cs.onSecondaryContainer) }
                    }
                    if (kind != "solid" && colors.size > 2) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(cs.secondaryContainer).clickable {
                            write(kind, colors.dropLast(1))
                        }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, null, tint = cs.onSecondaryContainer) }
                    }
                }
            }
            if (kind == "gradient") {
                val angle = obj?.optDouble("angle", 90.0)?.toFloat() ?: 90f
                Text(tr("Направление") + " · ${angle.roundToInt()}°", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Slider(value = angle, onValueChange = { a -> write(kind, colors) { it.put("angle", a.roundToInt()) } }, valueRange = 0f..360f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = obj?.optString("type") == "radial", onCheckedChange = { r -> write(kind, colors) { it.put("type", if (r) "radial" else "linear") } })
                    Text(tr("Из центра"))
                }
            }
            if (kind == "pattern") {
                val pk = obj?.optString("pattern") ?: "pixels"
                ButtonGroup(listOf("pixels" to "Пиксели", "planks" to "Доски", "dots" to "Точки"), pk) { k -> write(kind, colors) { it.put("pattern", k) } }
                val cell = obj?.optDouble("cell", 3.0)?.toFloat() ?: 3f
                Text(tr("Размер клетки") + " · ${cell.roundToInt()} dp", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Slider(value = cell, onValueChange = { c -> write(kind, colors) { it.put("cell", c.roundToInt()) } }, valueRange = 1f..16f)
            }
        }
    }
    picking?.let { i ->
        ColorPickerDialog(title, colors.getOrElse(i) { 0xFFFFFFFF.toInt() }, allowAuto = false, onDismiss = { picking = null }, withAlpha = true) { c ->
            write(kind, colors.toMutableList().also { if (i < it.size) it[i] = c else it += c })
            picking = null
        }
    }
}

@Composable
private fun ESlider(path: List<String>, field: String, title: String, range: ClosedFloatingPointRange<Float>, unit: String, steps: Int = 0) {
    val own = (ThemeEditor.get(path, field) as? Number)?.toFloat()
    val inh = (ThemeEditor.inherited(path, field) as? Number)?.toFloat()
    val shown = own ?: inh
    val cs = MaterialTheme.colorScheme
    GroupItem {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Text(
                    if (shown == null) tr("Не задано") else when (unit) {
                        "dp" -> "${shown.roundToInt()} dp"
                        "dp1" -> String.format("%.1f dp", shown)
                        "x" -> String.format("×%.2f", shown)
                        "pct" -> "${(shown * 100).roundToInt()}%"
                        else -> "${shown.roundToInt()}"
                    },
                    style = MaterialTheme.typography.labelLarge, color = if (own != null) cs.primary else cs.onSurfaceVariant
                )
                if (own != null) IconButton(onClick = { ThemeEditor.set(path, field, null) }) { Icon(Icons.Rounded.Close, tr("Сбросить"), Modifier.size(18.dp)) }
                else Spacer(Modifier.width(12.dp))
            }
            var local by remember(shown) { mutableFloatStateOf(shown ?: range.start) }
            Slider(
                value = local.coerceIn(range.start, range.endInclusive),
                onValueChange = { local = it },
                onValueChangeFinished = {
                    val v: Any = if (unit == "int" || unit == "dp") local.roundToInt() else (local * 100).roundToInt() / 100.0
                    ThemeEditor.set(path, field, v)
                },
                valueRange = range, steps = steps
            )
        }
    }
}

@Composable
private fun EChoice(path: List<String>, field: String, title: String, options: List<Pair<String, String>>) {
    val v = (ThemeEditor.get(path, field) ?: "").toString().lowercase()
    ChoiceItem(Icons.Rounded.Style, title, null, options, v) { ThemeEditor.set(path, field, it.ifEmpty { null }) }
}

@Composable
private fun EToggle(path: List<String>, field: String, title: String) {
    val v = ThemeEditor.get(path, field) == true
    ToggleItem(Icons.Rounded.ToggleOn, title, null, v) { ThemeEditor.set(path, field, if (it) true else null) }
}

/** Переключатель, который включает числовое поле со значением по умолчанию. */
@Composable
private fun EToggleNum(path: List<String>, field: String, title: String, default: Double) {
    val v = ThemeEditor.get(path, field) != null
    ToggleItem(Icons.Rounded.BlurOn, title, "Android 12+", v) { ThemeEditor.set(path, field, if (it) default else null) }
}

@Composable
private fun EText(path: List<String>, field: String, title: String) {
    val v = ThemeEditor.get(path, field)?.toString().orEmpty()
    GroupItem {
        OutlinedTextField(
            value = v, onValueChange = { t -> ThemeEditor.set(path, field, t.take(12).ifEmpty { null }) },
            label = { Text(tr(title)) }, singleLine = true, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
    }
}
