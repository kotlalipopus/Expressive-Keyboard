package com.expressive.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.max

// ============================================================================
// 15. СВОИ ИКОНКИ
// Любую иконку клавиатуры (микрофон, 🌐, ⌫, панель инструментов, вкладки эмодзи)
// можно заменить своей картинкой или эмодзи/текстом. Порядок: ваш выбор в настройках →
// иконка из плагина → встроенная.
// ============================================================================

@Immutable
data class IconSlot(val id: String, val title: String, val group: String)

@Immutable
sealed interface CustomIcon {
    data class Picture(val image: ImageBitmap, val tint: Boolean) : CustomIcon
    data class Label(val text: String) : CustomIcon
}

object IconStore {
    private const val PREFS = "expressive_keyboard_icons"
    private const val DIR = "icons"

    val EMOJI_CATEGORIES = listOf("Смайлы и эмоции", "Люди", "Животные и природа", "Еда и напитки", "Путешествия и места", "Занятия", "Предметы", "Символы", "Флаги")

    val slots: List<IconSlot> = buildList {
        val top = "Верхняя панель"
        add(IconSlot("menu", "Меню (сетка)", top))
        add(IconSlot("back", "Назад", top))
        add(IconSlot("mic", "Микрофон", top))
        add(IconSlot("plugins", "Плагины", top))
        add(IconSlot("translate", "Переводчик", top))
        add(IconSlot("gif", "GIF", top))
        add(IconSlot("clipboard", "Буфер обмена", top))
        add(IconSlot("emoji", "Эмодзи (панель)", top))
        add(IconSlot("one_handed", "Одна рука", top))
        add(IconSlot("incognito", "Инкогнито", top))
        add(IconSlot("settings", "Настройки", top))
        add(IconSlot("hide", "Скрыть клавиатуру", top))
        add(IconSlot("layout_swap", "Исправить раскладку (dfg → вап)", top))
        val keys = "Клавиши"
        add(IconSlot("globe", "Смена языка", keys))
        add(IconSlot("emoji_key", "Клавиша эмодзи", keys))
        add(IconSlot("shift", "Shift", keys))
        add(IconSlot("caps", "Caps Lock", keys))
        add(IconSlot("backspace", "Стереть", keys))
        add(IconSlot("enter", "Ввод (новая строка)", keys))
        add(IconSlot("go", "Ввод (перейти)", keys))
        add(IconSlot("send", "Ввод (отправить)", keys))
        add(IconSlot("search", "Ввод (поиск)", keys))
        add(IconSlot("done", "Ввод (готово)", keys))
        val emoji = "Вкладки эмодзи"
        add(IconSlot("emoji_recent", "Недавние", emoji))
        EMOJI_CATEGORIES.forEachIndexed { i, t -> add(IconSlot("emoji_cat_$i", t, emoji)) }
    }

    private var user by mutableStateOf<Map<String, CustomIcon>>(emptyMap())
    /** Иконки из манифестов плагинов: слот → описание. */
    private var manifest by mutableStateOf<Map<String, CustomIcon>>(emptyMap())
    /** kb.setIcon() из скриптов: плагин → слот → иконка. */
    private var scripted by mutableStateOf<Map<String, Map<String, CustomIcon>>>(emptyMap())
    @Volatile private var loaded = false

    fun get(slot: String): CustomIcon? =
        user[slot] ?: scripted.values.firstNotNullOfOrNull { it[slot] } ?: manifest[slot]

    fun userIcon(slot: String): CustomIcon? = user[slot]

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(context: Context, slot: String) = File(File(context.applicationContext.filesDir, DIR), "$slot.png")

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        reload(context)
    }

    private fun reload(context: Context) {
        val p = prefs(context)
        val map = HashMap<String, CustomIcon>()
        for (slot in slots) {
            when (val v = p.getString(slot.id, null)) {
                null -> Unit
                "img" -> runCatching {
                    BitmapFactory.decodeFile(file(context, slot.id).absolutePath)?.asImageBitmap()
                }.getOrNull()?.let { map[slot.id] = CustomIcon.Picture(it, p.getBoolean("${slot.id}.tint", false)) }
                else -> if (v.startsWith("txt:")) map[slot.id] = CustomIcon.Label(v.removePrefix("txt:"))
            }
        }
        user = map
    }

    /** Картинка уменьшается до 192 px и хранится как PNG (прозрачность сохраняется). */
    fun setImage(context: Context, slot: String, uri: Uri): Boolean = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val side = max(bounds.outWidth, bounds.outHeight)
        require(side > 0)
        var sample = 1
        while (side / (sample * 2) >= 192) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("decode")
        val scale = 192f / max(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        val f = file(context, slot)
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prefs(context).edit().putString(slot, "img").apply()
        reload(context)
        true
    }.getOrDefault(false)

    fun setText(context: Context, slot: String, text: String) {
        file(context, slot).delete()
        prefs(context).edit().putString(slot, "txt:${text.take(6)}").apply()
        reload(context)
    }

    fun setTint(context: Context, slot: String, tint: Boolean) {
        prefs(context).edit().putBoolean("$slot.tint", tint).apply()
        reload(context)
    }

    fun reset(context: Context, slot: String) {
        file(context, slot).delete()
        prefs(context).edit().remove(slot).remove("$slot.tint").apply()
        reload(context)
    }

    fun resetAll(context: Context) {
        File(context.applicationContext.filesDir, DIR).deleteRecursively()
        prefs(context).edit().clear().apply()
        reload(context)
    }

    /** Иконки из включённых плагинов (поле "icons"): первый плагин побеждает. */
    fun setManifestIcons(icons: Map<String, String>) {
        manifest = icons.mapNotNull { (k, v) -> decode(v)?.let { k to it } }.toMap()
    }

    fun setPluginIcon(pluginId: String, slot: String, spec: String) {
        val cur = scripted[pluginId].orEmpty().toMutableMap()
        val icon = if (spec.isBlank()) null else decode(spec)
        if (icon == null) cur.remove(slot) else cur[slot] = icon
        scripted = scripted + (pluginId to cur)
    }

    fun dropScripted(keep: Set<String>) {
        scripted = scripted.filterKeys { it in keep }
    }

    /** "data:image/png;base64,…" → картинка (перекрашивается, если в конце "#tint"); иначе эмодзи/текст. */
    fun decode(spec: String): CustomIcon? {
        if (spec.isBlank()) return null
        if (spec.startsWith("data:image")) {
            val tint = spec.endsWith("#tint")
            val bmp = decodeDataUri(spec.removeSuffix("#tint")) ?: return null
            return CustomIcon.Picture(bmp, tint)
        }
        return CustomIcon.Label(spec.take(6))
    }

    /** Кэш по объёму картинок (8 МБ), а не по их числу: несколько больших картинок не займут всю память. */
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    fun decodeDataUri(uri: String): ImageBitmap? {
        cache.get(uri)?.let { return it }
        return runCatching {
            val bytes = Base64.decode(uri.substringAfter(','), Base64.DEFAULT)
            decodeBounded(bytes, 512)?.asImageBitmap()
        }.getOrNull()?.also { cache.put(uri, it) }
    }

    /** Сначала только размеры, потом уменьшенная копия: «картинка-бомба» 20000×20000 не раздуется в памяти. */
    internal fun decodeBounded(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Иконка слота: своя (картинка/текст) или встроенная default. */
@Composable
fun SlotIcon(slot: String, tint: Color, size: Dp, default: @Composable () -> Unit) {
    when (val c = IconStore.get(slot)) {
        null -> default()
        is CustomIcon.Label -> IconLabel(c.text, tint, size)
        is CustomIcon.Picture -> Image(
            c.image, null, Modifier.size(size),
            colorFilter = if (c.tint) ColorFilter.tint(tint) else null
        )
    }
}

@Composable
internal fun IconLabel(text: String, tint: Color, size: Dp) {
    val sp = with(LocalDensity.current) { (size * if (text.length > 2) 0.5f else 0.85f).toSp() }
    Text(text, color = tint, fontSize = sp, maxLines = 1, fontWeight = FontWeight.Bold)
}

/** Значки для кнопок плагинов: "@имя" — встроенный значок. */
internal val NAMED_ICONS: Map<String, ImageVector> by lazy {
    mapOf(
        "translate" to Icons.Rounded.Translate, "search" to Icons.Rounded.Search, "star" to Icons.Rounded.Star,
        "heart" to Icons.Rounded.Favorite, "settings" to Icons.Rounded.Settings, "mic" to Icons.Outlined.Mic,
        "clipboard" to Icons.Rounded.ContentPaste, "copy" to Icons.Rounded.ContentCopy, "cut" to Icons.Rounded.ContentCut,
        "undo" to Icons.AutoMirrored.Rounded.Undo, "redo" to Icons.AutoMirrored.Rounded.Redo, "calc" to Icons.Rounded.Calculate,
        "text" to Icons.Rounded.TextFields, "code" to Icons.Rounded.Code, "link" to Icons.Rounded.Link,
        "time" to Icons.Rounded.Schedule, "date" to Icons.Rounded.Today, "music" to Icons.Rounded.MusicNote,
        "image" to Icons.Rounded.Image, "bolt" to Icons.Rounded.Bolt, "magic" to Icons.Rounded.AutoFixHigh,
        "note" to Icons.AutoMirrored.Rounded.StickyNote2, "list" to Icons.AutoMirrored.Rounded.List, "check" to Icons.Rounded.Check,
        "close" to Icons.Rounded.Close, "refresh" to Icons.Rounded.Refresh, "send" to Icons.AutoMirrored.Rounded.Send,
        "case" to Icons.Rounded.TextFormat, "select_all" to Icons.Rounded.SelectAll, "lock" to Icons.Rounded.Lock,
        "emoji" to Icons.Rounded.EmojiEmotions, "gif" to Icons.Rounded.GifBox, "games" to Icons.Rounded.SportsEsports,
        "dice" to Icons.Rounded.Casino, "palette" to Icons.Rounded.Palette, "extension" to Icons.Rounded.Extension,
        "keyboard" to Icons.Rounded.Keyboard, "language" to Icons.Rounded.Language, "add" to Icons.Rounded.Add,
        "delete" to Icons.Rounded.DeleteOutline, "edit" to Icons.Rounded.Edit, "share" to Icons.Rounded.Share,
        "home" to Icons.Rounded.Home, "person" to Icons.Rounded.Person, "mail" to Icons.Rounded.Email,
        "rocket" to Icons.Rounded.RocketLaunch
    )
}

/**
 * Значок по описанию из плагина: "@layout_swap" / "@globe" — встроенные рисунки, "@search" и т. п. — значки Material,
 * "data:image/…" — картинка, иначе эмодзи или короткий текст.
 */
@Composable
fun SpecIcon(spec: String, tint: Color, size: Dp) {
    when {
        spec == "@layout_swap" -> SlotIcon("layout_swap", tint, size) { LayoutSwapIcon(tint, size) }
        spec == "@globe" -> GlyphIcon(Glyph.GLOBE, tint, size)
        spec == "@backspace" -> GlyphIcon(Glyph.BACKSPACE, tint, size)
        spec == "@enter" -> GlyphIcon(Glyph.RETURN, tint, size)
        spec == "@shift" -> GlyphIcon(Glyph.SHIFT, tint, size)
        spec == "@grid" -> GlyphIcon(Glyph.GRID, tint, size)
        spec.startsWith("@") -> NAMED_ICONS[spec.drop(1)]?.let { Icon(it, null, tint = tint, modifier = Modifier.size(size)) }
            ?: IconLabel(spec.drop(1).take(2), tint, size)
        spec.startsWith("data:image") -> {
            val img = remember(spec) { IconStore.decodeDataUri(spec.removeSuffix("#tint")) }
            if (img != null) Image(img, null, Modifier.size(size), colorFilter = if (spec.endsWith("#tint")) ColorFilter.tint(tint) else null)
        }
        else -> IconLabel(spec, tint, size)
    }
}

/**
 * «Исправить раскладку»: две клавиши — латинская и кириллическая — со стрелками по кругу между ними
 * (dfg ⇄ вап).
 */
@Composable
fun LayoutSwapIcon(color: Color, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val u = this.size.minDimension / 24f
            fun o(x: Float, y: Float) = Offset(x * u, y * u)
            val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            // клавиша «A»
            drawRoundRect(color, o(1.5f, 1.5f), Size(11f * u, 11f * u), androidx.compose.ui.geometry.CornerRadius(3f * u), style = stroke)
            // A
            val a = Path().apply { moveTo(4.2f * u, 10f * u); lineTo(7f * u, 4f * u); lineTo(9.8f * u, 10f * u) }
            drawPath(a, color, style = stroke)
            drawLine(color, o(5.3f, 7.9f), o(8.7f, 7.9f), 1.7f * u, StrokeCap.Round)
            // клавиша «Я» (залитая)
            drawRoundRect(color, o(11.5f, 11.5f), Size(11f * u, 11f * u), androidx.compose.ui.geometry.CornerRadius(3f * u))
            val ya = Path().apply {
                moveTo(19.6f * u, 20.2f * u); lineTo(19.6f * u, 13.8f * u); lineTo(16.6f * u, 13.8f * u)
                cubicTo(14.4f * u, 13.8f * u, 14.4f * u, 17.4f * u, 16.6f * u, 17.4f * u); lineTo(19.6f * u, 17.4f * u)
            }
            drawPath(ya, Color.Black, style = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            drawLine(Color.Black, o(16.9f, 17.4f), o(14.6f, 20.2f), 1.7f * u, StrokeCap.Round, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            // стрелки по кругу
            val top = Path().apply { moveTo(15f * u, 2.8f * u); quadraticTo(21f * u, 3f * u, 21.2f * u, 8.6f * u) }
            drawPath(top, color, style = stroke)
            val topHead = Path().apply { moveTo(19f * u, 6.9f * u); lineTo(21.2f * u, 9f * u); lineTo(23f * u, 6.6f * u) }
            drawPath(topHead, color, style = stroke)
            val bottom = Path().apply { moveTo(9f * u, 21.2f * u); quadraticTo(3f * u, 21f * u, 2.8f * u, 15.4f * u) }
            drawPath(bottom, color, style = stroke)
            val bottomHead = Path().apply { moveTo(1f * u, 17.4f * u); lineTo(2.8f * u, 15f * u); lineTo(5f * u, 17.1f * u) }
            drawPath(bottomHead, color, style = stroke)
        }
    }
}
