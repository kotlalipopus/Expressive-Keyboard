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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PushPin
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
// 9. ПАНЕЛИ: ПЕРЕВОДЧИК, ЭМОДЗИ, GIF, БУФЕР, ГОЛОС
// ============================================================================

internal val PanelShape = RoundedCornerShape(24.dp)

@Composable
internal fun PillButton(text: String, bg: Color, fg: Color, font: FontFamily, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
internal fun RepeatingBackspace(palette: KeyPalette, onDelete: () -> Unit) {
    val cb by rememberUpdatedState(onDelete)
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 44.dp)
            .clip(CircleShape)
            .background(palette.func)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    cb()
                    var interval = 420L
                    while (true) {
                        val ended = withTimeoutOrNull(interval) { awaitPressEnd(down.id) }
                        if (ended != null) break
                        cb()
                        interval = 55L
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(Glyph.BACKSPACE, palette.onFunc, 20.dp)
    }
}

/** Язык в панели выбора перевода: код для настройки, название на самом языке и пояснение. */
private class TranslateLang(val code: String, val name: String, val sub: String)

/** Свои языки, потом разновидности английского, потом все остальные языки приложения по алфавиту. */
private fun translateLanguages(settings: KeyboardSettings): Triple<List<TranslateLang>, List<TranslateLang>, List<TranslateLang>> {
    fun of(l: KeyboardLanguage) = TranslateLang(l.id, l.spaceLabel.substringBefore(" · "), l.displayTitle().substringBefore(" ("))
    val english = EnglishVariants.LIST.map { (code, name, sub) -> TranslateLang(code, name, tr(sub)) }
    val mine = Languages.enabled(settings).filter { it.id != "en" }.map(::of)
    val mineIds = mine.map { it.code }.toSet()
    val rest = Languages.all.filter { it.id != "en" && it.id !in mineIds }.sortedBy { it.displayTitle() }.map(::of)
    return Triple(mine, english, rest)
}

@Composable
private fun TranslatePill(l: TranslateLang, target: String, palette: KeyPalette, font: FontFamily, onPick: (String) -> Unit) {
    val sel = l.code == target
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (sel) palette.accent else palette.func)
            .clickable { onPick(l.code) }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            l.name, color = if (sel) palette.onAccent else palette.onFunc, fontFamily = font, fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            l.sub, color = (if (sel) palette.onAccent else palette.onFunc).copy(alpha = 0.65f), fontFamily = font,
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TranslateHeader(title: String, palette: KeyPalette, font: FontFamily) {
    Text(title, color = palette.muted, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

/** Перевод с автоопределением языка; нажатие на чип языка открывает панель выбора. Текст виден целиком. */
@Composable
internal fun TranslatorBar(state: KeyboardState, actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val input = state.translatorInput
    val target = settings.translateTarget
    val groups = remember(settings.languages, Languages.extra) { translateLanguages(settings) }
    val targetName = remember(target, groups) {
        (groups.second + groups.first + groups.third).firstOrNull { it.code == target }?.let { l ->
            if (l.code.startsWith("en")) "EN" + l.code.removePrefix("en").uppercase() else l.name
        } ?: target.uppercase()
    }
    var pick by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(input, target) {
        failed = false
        if (input.isBlank()) {
            state.translatorResult = ""
            state.translatorLoading = false
            return@LaunchedEffect
        }
        state.translatorLoading = true
        // региональный английский переводит ИИ по ключу пользователя: запросов меньше — ждём, пока допечатают
        val region = EnglishVariants.regionName(target)
        val keys = AiKeys.of(settings)
        val viaAi = region != null && AiFix.hasLlm(keys)
        delay(if (viaAi) 900L else 350L)
        // нет сети в первый раз — ещё одна попытка, прежде чем сдаться
        // языки раскладок — подсказка, на каком языке набран текст (текущая — первой)
        val hints = (listOf(state.language.id) + Languages.enabled(settings).map { it.id }).map { it.substringBefore('-') }.distinct()
        var r: Result<String> = Result.failure(IllegalStateException())
        if (viaAi) {
            val ai = AiFix.run(
                context, input, keys, "en", AiTask.CUSTOM,
                "Translate the text into $region: use that region's spelling, vocabulary and idioms. " +
                    "Keep the meaning, tone and formatting. Output only the translation, nothing else."
            )
            r = ai.map { it.first.trim() }.let { if (it.getOrNull().isNullOrBlank()) Result.failure(IllegalStateException()) else it }
        }
        if (r.isFailure) r = Translator.translate(input, "auto", target, hints)
        if (r.isFailure) {
            delay(800L)
            r = Translator.translate(input, "auto", target, hints)
        }
        state.translatorResult = r.getOrElse { "" }
        failed = r.isFailure
        state.translatorLoading = false
    }
    if (pick) {
        Column(Modifier.fillMaxWidth().panelSurface(palette).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Язык перевода"), color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { pick = false }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = palette.muted, modifier = Modifier.size(20.dp))
                }
            }
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                state = gridState,
                modifier = Modifier.fillMaxWidth().heightIn(max = if (settings.landscape) 120.dp else 230.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (groups.first.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { TranslateHeader(tr("Ваши языки"), palette, font) }
                    gridItems(groups.first) { TranslatePill(it, target, palette, font) { code -> KeyboardPrefs.edit(context) { putString("translate_target", code) }; pick = false } }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { TranslateHeader(tr("Английский"), palette, font) }
                gridItems(groups.second) { TranslatePill(it, target, palette, font) { code -> KeyboardPrefs.edit(context) { putString("translate_target", code) }; pick = false } }
                item(span = { GridItemSpan(maxLineSpan) }) { TranslateHeader(tr("Все языки"), palette, font) }
                gridItems(groups.third) { TranslatePill(it, target, palette, font) { code -> KeyboardPrefs.edit(context) { putString("translate_target", code) }; pick = false } }
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().panelSurface(palette).padding(6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.clip(CircleShape).background(palette.func).clickable { pick = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(tr("Авто") + " → $targetName", color = palette.onFunc, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // набранное: показываем целиком, длинное прокручивается и всегда стоит на конце — там, где печатаете
            val inputScroll = rememberScrollState()
            LaunchedEffect(input) { inputScroll.scrollTo(inputScroll.maxValue) }
            Box(Modifier.fillMaxWidth().heightIn(max = 78.dp).verticalScroll(inputScroll)) {
                Text(
                    if (input.isEmpty()) tr("Печатайте — переведу") else input,
                    color = if (input.isEmpty()) palette.muted else palette.onLetter,
                    fontFamily = font, fontSize = 13.sp
                )
            }
            val result = when {
                state.translatorLoading -> tr("Перевожу…")
                failed -> tr("Не удалось перевести — проверьте интернет")
                else -> state.translatorResult
            }
            if (result.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    Text(result, color = palette.accent, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        if (input.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { state.translatorInput = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = palette.muted, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(palette.accent).clickable { actions.insertTranslation() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, null, tint = palette.onAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Вкладки внизу между ABC и ⌫, как в Gboard; удержание эмодзи с тонами кожи — выбор тона. */
@Composable
internal fun EmojiPanel(actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val cats = EmojiCatalog.categories
    val recents = EmojiRecents.items
    var tab by remember { mutableIntStateOf(if (recents.isEmpty()) 1 else 0) }
    var variantsOf by remember { mutableStateOf<EmojiItem?>(null) }
    val style = settings.emojiStyle
    val emojiFont = remember(settings.emojiFontVersion) { FontRepository.emoji(context, settings.emojiFontVersion) }
    val imageStyle = EmojiStyles.isImageStyle(style)
    val list: List<String> = remember(tab, cats, recents, style) {
        if (tab == 0) recents
        else cats.getOrNull(tab - 1)?.items.orEmpty()
            .filter { it.system || (imageStyle && EmojiStyles.has(context, style, it.index)) || style == EmojiStyles.CUSTOM }
            .map { it.emoji }
    }
    Column(Modifier.fillMaxSize().panelSurface(palette)) {
        val v = variantsOf
        if (v != null) {
            // выбор тона кожи: основной вариант и все тона
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                    .background(palette.accentSoft).horizontalScroll(rememberScrollState()).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                (listOf(v.emoji) + v.variants).forEach { e ->
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).clickable {
                            actions.commitEmoji(e)
                            variantsOf = null
                        },
                        contentAlignment = Alignment.Center
                    ) { EmojiGlyph(e, 32.dp, style, emojiFont) }
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { variantsOf = null }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, tr("Закрыть"), tint = palette.onAccentSoft, modifier = Modifier.size(20.dp))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (cats.isEmpty()) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    else Text(tr("Пока пусто"), color = palette.muted, fontFamily = font)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp)
                ) {
                    gridItems(list, key = { it }) { e ->
                        val item = remember(e) { EmojiCatalog.item(e) }
                        Box(
                            Modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(
                                onLongClick = { if (item != null && item.variants.isNotEmpty()) variantsOf = item }
                            ) { actions.commitEmoji(e) },
                            contentAlignment = Alignment.Center
                        ) {
                            EmojiGlyph(e, 32.dp, style, emojiFont)
                            if (item != null && item.variants.isNotEmpty() && item.emoji == e) {
                                // точка в углу: у эмодзи есть тона кожи
                                Box(
                                    Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).size(4.dp)
                                        .clip(CircleShape).background(palette.muted)
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton("ABC", palette.func, palette.onFunc, font) { actions.togglePanel(ActivePanel.KEYBOARD) }
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Box(
                        Modifier.size(width = 40.dp, height = 36.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { actions.togglePanel(ActivePanel.EMOJI_SEARCH) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Search, tr("Поиск эмодзи"), tint = palette.onLetter, modifier = Modifier.size(22.dp)) }
                }
                item { EmojiTab(tab == 0, palette, "emoji_recent", "🕒", style, emojiFont) { tab = 0; variantsOf = null } }
                itemsIndexed(cats) { i, c ->
                    EmojiTab(tab == i + 1, palette, "emoji_cat_$i", c.icon, style, emojiFont) { tab = i + 1; variantsOf = null }
                }
            }
            RepeatingBackspace(palette) { actions.deleteBackward() }
        }
    }
}

@Composable
private fun EmojiTab(sel: Boolean, palette: KeyPalette, slot: String, icon: String, style: String, font: FontFamily?, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 40.dp, height = 36.dp).clip(RoundedCornerShape(if (sel) 18.dp else 12.dp))
            .background(if (sel) palette.accentSoft else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlotIcon(slot, if (sel) palette.onAccentSoft else palette.onLetter, 22.dp) { EmojiGlyph(icon, 24.dp, style, font) }
    }
}

/** Поиск эмодзи: запрос печатается обычной клавиатурой, результаты — лентой над ней. */
@Composable
internal fun EmojiSearchBar(state: KeyboardState, actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val query = state.emojiQuery
    val cats = EmojiCatalog.categories
    val style = settings.emojiStyle
    val emojiFont = remember(settings.emojiFontVersion) { FontRepository.emoji(context, settings.emojiFontVersion) }
    val results = remember(query, cats) {
        if (query.isBlank()) EmojiRecents.items.mapNotNull { EmojiCatalog.item(it) }.distinct() else EmojiCatalog.search(query)
    }
    Column(
        Modifier.fillMaxWidth().panelSurface(palette).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Search, null, tint = palette.muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
            Text(
                query.ifEmpty { tr("Найти эмодзи: кот, сердце, smile…") }, Modifier.weight(1f),
                color = if (query.isEmpty()) palette.muted else palette.onLetter, fontFamily = font, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { actions.togglePanel(ActivePanel.EMOJI) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Close, tr("Закрыть"), tint = palette.muted, modifier = Modifier.size(20.dp)) }
        }
        LazyRow(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (results.isEmpty()) item {
                Box(Modifier.height(46.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text(tr("Ничего не найдено"), color = palette.muted, fontFamily = font, fontSize = 14.sp)
                }
            }
            items(results.size) { i ->
                val item = results[i]
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).clickable { actions.commitEmoji(item.emoji) },
                    contentAlignment = Alignment.Center
                ) { EmojiGlyph(item.emoji, 32.dp, style, emojiFont) }
            }
        }
    }
}

/** Строка поиска GIF над клавиатурой: печатаете запрос, Enter или 🔍 — результаты в панели GIF. */
@Composable
internal fun GifSearchBar(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val query = state.gifQuery
    Row(
        Modifier.fillMaxWidth().height(48.dp).panelSurface(palette).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = palette.muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
        Text(
            query.ifEmpty { tr("Найти GIF: привет, котик, ура…") }, Modifier.weight(1f),
            color = if (query.isEmpty()) palette.muted else palette.onLetter, fontFamily = font, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier.height(36.dp).clip(CircleShape).background(palette.accent)
                .clickable { actions.togglePanel(ActivePanel.GIF) }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) { Text(tr("Найти"), color = palette.onAccent, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}

@Composable
internal fun GifPanel(state: KeyboardState, actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val loader = remember { GifImageLoader.get(context) }
    // запрос из строки поиска важнее категории; пустой запрос — выбранная категория
    val search = state.gifQuery.trim()
    var cat by remember { mutableIntStateOf(0) }
    val online = GifCatalog.online(settings.klipyKey, settings.giphyKey)
    val gifs = remember { mutableStateListOf<TenorGifResult>() }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val (title, catQuery) = GifCatalog.categories[cat]
    val query = search.ifEmpty { catQuery }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        val page = GifCatalog.fetch(title, query, if (reset) null else next, settings.klipyKey, settings.giphyKey)
        if (reset) gifs.clear()
        gifs.addAll(page.items.filter { n -> gifs.none { it.id == n.id } })
        next = page.next
        error = page.error
        loading = false
    }
    LaunchedEffect(query, settings.klipyKey, settings.giphyKey) {
        next = null
        loading = false
        load(true)
        grid.scrollToItem(0)
    }
    // докручено почти до конца — следующая страница
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= gifs.size - 8 } == true } }
    LaunchedEffect(nearEnd, next) {
        if (nearEnd && next != null && !loading) load(false)
    }

    Column(
        modifier = Modifier.fillMaxSize().panelSurface(palette).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // поиск: открывает строку над клавиатурой
            Box(
                Modifier.clip(CircleShape).background(if (search.isNotEmpty()) palette.accent else palette.func)
                    .clickable { actions.togglePanel(ActivePanel.GIF_SEARCH) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Search, tr("Поиск"), tint = if (search.isNotEmpty()) palette.onAccent else palette.onFunc, modifier = Modifier.size(16.dp))
                    if (search.isNotEmpty()) {
                        Text(search.take(18), color = palette.onAccent, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            GifCatalog.categories.forEachIndexed { i, (label, _) ->
                val sel = i == cat && search.isEmpty()
                Box(
                    Modifier.clip(CircleShape).background(if (sel) palette.accent else palette.func)
                        .clickable {
                            cat = i
                            // категория сбрасывает поиск
                            if (state.gifQuery.isNotEmpty()) state.gifQuery = ""
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(tr(label), color = if (sel) palette.onAccent else palette.onFunc, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        if (!online) {
            Text(
                tr("Сейчас — встроенная подборка. Чтобы искать любые GIF, добавьте бесплатный ключ KLIPY в настройках → «Буфер обмена и GIF»."),
                color = palette.muted, fontFamily = font, fontSize = 12.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                gridItems(gifs, key = { it.id }) { gif ->
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp)).background(palette.func)
                            .clickable { actions.commitGif(gif) }
                    ) {
                        AsyncImage(
                            model = gif.previewUrl,
                            imageLoader = loader,
                            contentDescription = gif.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = palette.accent)
                    }
                }
            }
            if (!loading && gifs.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (error != null) tr("Не удалось загрузить GIF: {0}", error.orEmpty()) else tr("Ничего не найдено"),
                        color = palette.muted, fontFamily = font, fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        PillButton(tr("Повторить"), palette.func, palette.onFunc, font) { scope.launch { load(true) } }
                    }
                }
            }
        }
    }
}

/** Нажатие — вставить, ✕ — удалить запись, 📌 (или долгое нажатие) — закрепить: закреплённые не стираются «Очистить». */
@Composable
internal fun ClipboardPanel(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val pinned = state.pinnedClips.toList()
    val items = pinned + state.clipboard.filter { it !in pinned }
    Column(
        modifier = Modifier.fillMaxSize().panelSurface(palette).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // высота шапки постоянная — записи не прыгают, когда «Очистить» пропадает
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Буфер обмена"), Modifier.weight(1f), color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            if (items.size > pinned.size) PillButton(tr("Очистить"), palette.func, palette.onFunc, font) { actions.clearClipboard() }
        }
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr("Скопируйте текст или картинку — они появятся здесь"), color = palette.muted, fontFamily = font)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(items, key = { it }) { t ->
                    val isPinned = t in pinned
                    val image = ClipImages.isImage(t)
                    Box(
                        Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(16.dp))
                            .background(if (isPinned) palette.accentSoft else palette.onLetter.copy(alpha = 0.07f).compositeOver(palette.letter))
                            .then(if (isPinned) Modifier.border(1.5.dp, palette.accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp)) else Modifier)
                            .combinedClickable(onLongClick = { actions.togglePinClip(t) }) { actions.commitClip(t) }
                    ) {
                        if (image) {
                            // картинка — во всю плитку, как в Gboard
                            ClipThumb(t, Modifier.fillMaxWidth().height(112.dp), palette.func)
                        } else {
                            Text(
                                t, color = if (isPinned) palette.onAccentSoft else palette.onLetter, fontFamily = font, fontSize = 14.sp,
                                maxLines = 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 34.dp)
                            )
                        }
                        // на картинке значки — на тёмной подложке, чтобы читались на любом фоне
                        val badgeBg = if (image) Color.Black.copy(alpha = 0.45f) else Color.Transparent
                        val badgeFg = if (image) Color.White else palette.muted
                        Column(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(badgeBg).clickable { actions.removeClip(t) },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Close, tr("Удалить"), tint = badgeFg, modifier = Modifier.size(16.dp)) }
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(badgeBg).clickable { actions.togglePinClip(t) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, tr("Закрепить"),
                                    tint = if (isPinned) palette.accent else badgeFg, modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoicePanel(
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    palette: KeyPalette,
    font: FontFamily,
    spec: WavySpec
) {
    val listening = voice.listening
    // вращение крутится только во время записи — в покое панель не перерисовывается каждый кадр
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(listening) {
        if (listening) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(9000, easing = LinearEasing))
            }
        } else {
            rotation.snapTo(rotation.value % 360f)
            rotation.animateTo(if (rotation.value > 180f) 360f else 0f, spring(dampingRatio = 0.6f, stiffness = 200f))
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().panelSurface(palette).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text((if (state.rambler) "Rambler" else tr("Голосовой ввод")) + " · ${state.language.label}", color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Box(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    val s = if (voice.listening) 1f + (voice.rms.coerceIn(0f, 10f) / 10f) * 0.18f else 1f
                    scaleX = s
                    scaleY = s
                }
                .then(
                    LocalSkin.current?.buttons?.let { b -> Modifier.skinButtonSurface(b, palette, listening) }
                        ?: Modifier.wavyBackground(spec, if (listening) palette.accent else palette.func) { rotation.value % 360f }
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.toggleVoice() },
            contentAlignment = Alignment.Center
        ) {
            val idleTint = LocalSkin.current?.buttons?.text ?: palette.onFunc
            Icon(Icons.Outlined.Mic, null, tint = if (listening) palette.onAccent else idleTint, modifier = Modifier.size(44.dp))
        }
        Text(
            voice.partial.ifEmpty { voice.status },
            color = if (voice.partial.isNotEmpty()) palette.accent else palette.muted,
            fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        if (!voice.hasPermission()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(tr("Разрешить микрофон"), palette.accent, palette.onAccent, font) { actions.openSettings() }
                PillButton(tr("Другой голосовой ввод"), palette.func, palette.onFunc, font) { actions.openVoiceFallback() }
            }
        } else if (voice.needsFallback) {
            PillButton(tr("Другой голосовой ввод"), palette.accent, palette.onAccent, font) { actions.openVoiceFallback() }
        }
    }
}

@Immutable
internal data class PluginTab(
    val key: String,
    val icon: String,
    val title: String,
    val plugin: KeyboardPlugin,
    val pack: SymbolPack?,
    val panel: ScriptPanel? = null
)

/** Кнопка на вкладке действий: декларативное действие плагина или кнопка скрипта. */
@Immutable
internal data class PluginEntry(val icon: String, val title: String, val actionIndex: Int, val buttonId: String?)

@Composable
internal fun PluginsPanel(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val active = PluginRepository.active
    val scriptButtons = ScriptHost.buttons
    val scriptPanels = ScriptHost.panels
    val tabs = remember(active, scriptButtons, scriptPanels) {
        buildList {
            for (p in active) {
                if (p.actions.isNotEmpty() || scriptButtons.any { it.pluginId == p.id }) add(PluginTab("${p.id}#a", p.icon, p.name, p, null))
                scriptPanels.filter { it.pluginId == p.id }.forEach { sp -> add(PluginTab("${p.id}#p:${sp.id}", sp.icon, sp.title, p, null, sp)) }
                p.symbols.forEachIndexed { i, s -> add(PluginTab("${p.id}#s$i", s.icon, s.title, p, s)) }
            }
        }
    }
    val selected = tabs.indexOfFirst { it.key == state.pluginTab }.coerceAtLeast(0)
    val tab = tabs.getOrNull(selected)
    Column(Modifier.fillMaxSize().panelSurface(palette)) {
        if (tab == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Icon(Icons.Rounded.Extension, null, tint = palette.muted, modifier = Modifier.size(36.dp))
                Text(
                    "Нет плагинов с действиями или символами",
                    color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                PillButton(tr("Открыть плагины"), palette.accent, palette.onAccent, font) { actions.openSettings() }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(tabs, key = { _, t -> t.key }) { i, t ->
                    val sel = i == selected
                    val corner by animateDpAsState(if (sel) 12.dp else 20.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "ptab")
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(corner))
                            .background(if (sel) palette.accent else palette.func)
                            .clickable { state.pluginTab = t.key }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecIcon(t.icon, if (sel) palette.onAccent else palette.onFunc, 18.dp)
                        Text(
                            t.title, color = if (sel) palette.onAccent else palette.onFunc, fontFamily = font,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1
                        )
                    }
                }
            }
            val pack = tab.pack
            val scriptPanel = tab.panel
            if (scriptPanel != null) {
                ScriptPanelView(scriptPanel, palette, font, Modifier.weight(1f).fillMaxWidth())
            } else if (pack != null) {
                val wide = remember(pack) { pack.items.any { it.length > 3 } }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (wide) 96.dp else 46.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gridItems(pack.items) { s ->
                        Box(
                            Modifier.height(46.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (wide) palette.letter else Color.Transparent)
                                .clickable { actions.commitSymbol(s) }.padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s, color = palette.onLetter, fontFamily = font, fontSize = if (wide) 14.sp else 24.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                val plugin = tab.plugin
                val entries = remember(plugin, scriptButtons) {
                    plugin.actions.mapIndexed { i, a -> PluginEntry(a.icon, a.title, i, null) } +
                        scriptButtons.filter { it.pluginId == plugin.id }.map { PluginEntry(it.icon, it.title, -1, it.id) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(entries) { e ->
                        val interaction = remember { MutableInteractionSource() }
                        val down by interaction.collectIsPressedAsState()
                        val corner by animateDpAsState(if (down) 26.dp else 16.dp, spring(dampingRatio = 0.55f, stiffness = 700f), label = "pact")
                        Row(
                            Modifier.height(48.dp).clip(RoundedCornerShape(corner)).background(palette.letter)
                                .clickable(interactionSource = interaction, indication = null) {
                                    if (e.buttonId != null) actions.runScriptButton(plugin.id, e.buttonId) else actions.runPluginAction(plugin.id, e.actionIndex)
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SpecIcon(e.icon, palette.onLetter, 18.dp)
                            Text(
                                e.title, color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton("ABC", palette.func, palette.onFunc, font) { actions.togglePanel(ActivePanel.KEYBOARD) }
            Text(
                state.pluginStatus, Modifier.weight(1f), color = palette.muted, fontFamily = font, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            RepeatingBackspace(palette) { actions.deleteBackward() }
        }
    }
}
// ----------------------------------------------------------------------------- панели скриптов

/** Панель, которую рисует скрипт плагина: render() вызывается заново после kb.refresh(). */
@Composable
internal fun ScriptPanelView(panel: ScriptPanel, palette: KeyPalette, font: FontFamily, modifier: Modifier) {
    val tick = ScriptHost.renderTick
    var clicks by remember { mutableIntStateOf(0) }
    val nodes = remember(panel, tick, clicks) { ScriptHost.render(panel) }
    val onClick: (ScriptCall, Any?) -> Unit = { call, value ->
        ScriptHost.invoke(call, value)
        clicks++
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (n in nodes) ScriptNode(n, palette, font, onClick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScriptNode(n: UiNode, palette: KeyPalette, font: FontFamily, onClick: (ScriptCall, Any?) -> Unit) {
    when (n) {
        is UiNode.Text -> Text(
            n.text, color = n.color?.let { Color(it) } ?: palette.onLetter,
            fontFamily = if (n.mono) FontFamily.Monospace else font, fontSize = n.size.sp,
            fontWeight = if (n.bold) FontWeight.ExtraBold else FontWeight.Medium,
            fontStyle = if (n.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
            textAlign = when (n.align) {
                "center" -> TextAlign.Center
                "end", "right" -> TextAlign.End
                else -> TextAlign.Start
            },
            maxLines = n.maxLines, overflow = TextOverflow.Ellipsis,
            modifier = if (n.align != "start") Modifier.fillMaxWidth() else Modifier
        )
        is UiNode.Button -> {
            val bg = n.color?.let { Color(it) } ?: if (n.accent) palette.accent else palette.letter
            val fg = n.textColor?.let { Color(it) } ?: if (n.accent) palette.onAccent else palette.onLetter
            val enabled = !n.disabled && (n.action != null || n.longAction != null)
            Row(
                Modifier.then(if (n.wide) Modifier.fillMaxWidth() else Modifier).height(44.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (n.disabled) bg.copy(alpha = bg.alpha * 0.4f) else bg)
                    .combinedClickable(
                        enabled = enabled,
                        onLongClick = n.longAction?.let { la -> { onClick(la, n.value) } }
                    ) { n.action?.let { onClick(it, n.value) } }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                if (n.icon.isNotEmpty()) SpecIcon(n.icon, fg, 18.dp)
                if (n.text.isNotEmpty()) Text(n.text, color = fg, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            }
        }
        is UiNode.Input -> {
            val empty = n.value.isEmpty()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (n.title.isNotEmpty()) Text(n.title, color = palette.muted, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(16.dp)).background(palette.letter)
                        .border(1.5.dp, palette.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { ScriptHost.openInput(n) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("✎", color = palette.accent, fontSize = 14.sp)
                    Text(
                        if (empty) n.hint.ifEmpty { tr("Нажмите, чтобы ввести") } else n.value,
                        color = if (empty) palette.muted else palette.onLetter, fontFamily = font, fontSize = 14.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        is UiNode.Toggle -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.letter)
                .clickable(enabled = n.action != null) { n.action?.let { onClick(it, !n.checked) } }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(n.text, color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = n.checked, onCheckedChange = { v -> n.action?.let { onClick(it, v) } },
                colors = SwitchDefaults.colors(checkedTrackColor = palette.accent, checkedThumbColor = palette.onAccent)
            )
        }
        is UiNode.Slider -> {
            var v by remember(n.value) { mutableFloatStateOf(n.value) }
            Column(Modifier.fillMaxWidth()) {
                if (n.text.isNotEmpty()) {
                    val shown = if (n.step >= 1f || (n.max - n.min) >= 20f) v.roundToInt().toString() else "%.2f".format(v)
                    Text("${n.text}: $shown", color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = v,
                    onValueChange = { x -> v = if (n.step > 0f) (kotlin.math.round((x - n.min) / n.step) * n.step + n.min).coerceIn(n.min, n.max) else x },
                    onValueChangeFinished = { n.action?.let { onClick(it, v.toDouble()) } },
                    valueRange = n.min..n.max,
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
                )
            }
        }
        is UiNode.Progress -> {
            val p = n.value
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.accent, trackColor = palette.letter)
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = palette.accent, trackColor = palette.letter)
        }
        is UiNode.Divider -> HorizontalDivider(color = palette.muted.copy(alpha = 0.3f))
        is UiNode.Box -> {
            val shape = RoundedCornerShape(n.radius.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(n.color?.let { Color(it) } ?: palette.letter)
                    .then(if (n.border != null) Modifier.border(1.5.dp, Color(n.border), shape) else Modifier)
                    .clickable(enabled = n.action != null) { n.action?.let { onClick(it, n.value) } }
                    .padding(n.padding.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (c in n.children) ScriptNode(c, palette, font, onClick)
            }
        }
        is UiNode.Tabs -> Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (item in n.items) {
                val sel = item.value == n.selected || item.text == n.selected?.toString()
                Row(
                    Modifier.clip(CircleShape).background(if (sel) palette.accent else palette.func)
                        .clickable(enabled = n.action != null) { n.action?.let { onClick(it, item.value) } }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val fg = if (sel) palette.onAccent else palette.onFunc
                    if (item.icon.isNotEmpty()) SpecIcon(item.icon, fg, 16.dp)
                    if (item.text.isNotEmpty()) Text(item.text, color = fg, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        is UiNode.Canvas -> {
            val measurer = rememberTextMeasurer()
            val path = remember { Path() }
            val dens = LocalDensity.current.density
            Box(
                Modifier.fillMaxWidth().height(n.height.dp).clip(RoundedCornerShape(16.dp))
                    .then(
                        if (n.action != null) Modifier.pointerInput(n.action) {
                            detectTapGestures { p -> onClick(n.action, mapOf("x" to (p.x / dens).toDouble(), "y" to (p.y / dens).toDouble())) }
                        } else Modifier
                    )
                    .drawBehind { drawOverlayItems(n.items, measurer, path) }
            )
        }
        is UiNode.Scroll -> {
            val sized = if (n.height > 0f) Modifier.heightIn(max = n.height.dp) else Modifier
            if (n.horizontal) Row(
                Modifier.fillMaxWidth().then(sized).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (c in n.children) ScriptNode(c, palette, font, onClick)
            } else Column(
                Modifier.fillMaxWidth().then(sized).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (c in n.children) ScriptNode(c, palette, font, onClick)
            }
        }
        is UiNode.Row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in n.children) ScriptNode(c, palette, font, onClick)
        }
        is UiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in n.children) ScriptNode(c, palette, font, onClick)
        }
        is UiNode.Grid -> FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (item in n.items) {
                Row(
                    Modifier.widthIn(min = n.minWidth.dp).height(n.height.dp).clip(RoundedCornerShape(14.dp))
                        .background(palette.letter.copy(alpha = palette.letter.alpha * 0.7f))
                        .clickable(enabled = n.action != null) { n.action?.let { onClick(it, item.value) } }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    if (item.icon.isNotEmpty()) SpecIcon(item.icon, palette.onLetter, 20.dp)
                    if (item.text.isNotEmpty()) Text(
                        item.text, color = palette.onLetter, fontFamily = font,
                        fontSize = if (item.text.length <= 2) 22.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        is UiNode.Spacer -> Spacer(Modifier.height(n.size.dp))
        is UiNode.Image -> {
            val img = remember(n.src) { IconStore.decodeDataUri(n.src) }
            if (img != null) Image(
                img, null,
                Modifier.size(n.size.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = n.action != null) { n.action?.let { onClick(it, n.value) } }
            )
        }
        is UiNode.Error -> Text("⚠ ${n.message}", color = palette.accent, fontFamily = font, fontSize = 13.sp)
    }
}

/** Поле ввода мода над клавиатурой: заголовок, набранный текст, ← закрыть, ✓ отправить. */
@Composable
internal fun ScriptInputBar(actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val text = ScriptHost.promptText
    val title = ScriptHost.promptTitle
    Row(
        Modifier.fillMaxWidth().panelSurface(palette).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(palette.func).clickable { actions.scriptPromptDone(false) },
            contentAlignment = Alignment.Center
        ) { Text("←", color = palette.onFunc, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
        Column(Modifier.weight(1f)) {
            if (title.isNotEmpty()) Text(title, color = palette.accent, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
            Text(
                if (text.isEmpty()) ScriptHost.promptHint.ifEmpty { tr("Введите текст") } else text + "▏",
                color = if (text.isEmpty()) palette.muted else palette.onLetter,
                fontFamily = font, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(palette.accent).clickable { actions.scriptPromptDone(true) },
            contentAlignment = Alignment.Center
        ) { Text("✓", color = palette.onAccent, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
    }
}

/**
 * Панель ИИ над клавиатурой (как инструменты письма Gboard в PixelBoard): исправить, тон, короче, эмодзи, дописать
 * и своё указание. Работает с выделением или со всем текстом поля; последнюю правку можно отменить.
 */
@Composable
internal fun AiWriteBar(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    if (state.panel == ActivePanel.AI_PROMPT) {
        val prompt = state.aiPrompt
        Row(
            Modifier.fillMaxWidth().panelSurface(palette).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(palette.func).clickable { actions.togglePanel(ActivePanel.AI_WRITE) },
                contentAlignment = Alignment.Center
            ) { Text("←", color = palette.onFunc, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
            Text(
                if (prompt.isEmpty()) tr("Что сделать? Например: «ответь вежливо», «переведи на английский»") else prompt,
                color = if (prompt.isEmpty()) palette.muted else palette.onLetter,
                fontFamily = font, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val ready = prompt.isNotBlank()
            val bg by animateColorAsState(if (ready) palette.accent else palette.func, label = "aiSend")
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(enabled = ready) { actions.aiTool("send") },
                contentAlignment = Alignment.Center
            ) { Text("↑", color = if (ready) palette.onAccent else palette.muted, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().panelSurface(palette).horizontalScroll(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(state.aiCanUndo, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            AiChip("↶", tr("Отменить"), false, true, palette, font) { actions.aiTool("undo") }
        }
        for (task in AiTask.TOOLS) {
            AiChip(task.icon, tr(task.title), state.aiTask == task.id, task == AiTask.PROOFREAD, palette, font) { actions.aiTool(task.id) }
        }
        AiChip(AiTask.CUSTOM.icon, tr(AiTask.CUSTOM.title), false, false, palette, font) { actions.aiTool("prompt") }
    }
}

@Composable
private fun AiChip(icon: String, title: String, busy: Boolean, accent: Boolean, palette: KeyPalette, font: FontFamily, onClick: () -> Unit) {
    // кнопка, над которой думает ИИ, мягко «дышит»
    val pulse = if (busy) {
        rememberInfiniteTransition(label = "aiChip").animateFloat(0.55f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "aiChipA").value
    } else 1f
    val bg = if (accent || busy) palette.accent else palette.func
    val fg = if (accent || busy) palette.onAccent else palette.onFunc
    Row(
        Modifier.clip(CircleShape).background(bg.copy(alpha = bg.alpha * pulse)).clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(icon, fontSize = 14.sp, color = fg)
        Text(if (busy) tr("ИИ думает…") else title, color = fg, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1)
    }
}
