package com.expressive.keyboard

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardCapslock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

// ============================================================================
// СВОИ МЕСТА КЛАВИШ
// Для каждой раскладки (язык, «символы», «символы 2») и ряда цифр хранится порядок всех клавиш — букв, цифр,
// знаков и служебных (пробел, «,», эмодзи, смена языка, Shift, ⌫, Enter). Клавиша переезжает целиком — с вариантами
// при удержании; буква на месте буквы занимает ширину места, остальные — свою. Вместо буквы или знака можно
// поставить свой символ. Старый формат (только символьные клавиши, без нижнего ряда) по-прежнему читается.
// ============================================================================

object UserLayouts {
    private const val KEY = "user_layouts"
    /** Ряд цифр над буквами — отдельная раскладка: его клавиши меняются местами между собой. */
    const val NUMBERS = "numbers"
    /** Граница рядов в сохранённом порядке. */
    private const val ROW = "§/"
    private const val SERVICE = "§"

    fun parse(raw: String): Map<String, List<String>> = runCatching {
        if (raw.isBlank()) return emptyMap()
        val o = JSONObject(raw)
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).map { a.getString(it) }
        }
    }.getOrDefault(emptyMap())

    /** Чем клавиша записывается в порядке: символьная — подписью, служебная — своим типом. */
    fun token(k: KeyModel): String = if (k.type == KeyType.CHARACTER) k.label else SERVICE + k.type.name

    /** Порядок всех клавиш рядов (промежутки не считаются) — для сохранения. */
    fun encode(rows: List<List<KeyModel>>): List<String> =
        rows.flatMapIndexed { i, r -> (if (i > 0) listOf(ROW) else emptyList()) + r.map(::token) }

    /** Символьные клавиши рядов по порядку — старый формат. */
    fun editable(rows: List<KeyRow>): List<KeyModel> = rows.flatMap { r -> r.keys.filter { it.type == KeyType.CHARACTER } }

    /**
     * Расставить клавиши в сохранённом порядке. tail — ряд, который в новом формате тоже переставляется
     * (нижний: пробел, Enter…), а в старом остаётся как был.
     */
    fun apply(rows: List<KeyRow>, order: List<String>?, tail: KeyRow? = null): List<KeyRow> {
        val all = if (tail != null) rows + tail else rows
        if (order.isNullOrEmpty()) return all
        if (ROW !in order && order.none { it.startsWith(SERVICE) }) return applyOld(rows, order) + listOfNotNull(tail)
        return applyFull(all, order)
    }

    private fun applyOld(rows: List<KeyRow>, order: List<String>): List<KeyRow> {
        val original = editable(rows)
        if (original.size != order.size) return rows
        val pool = original.toMutableList()
        val placed = order.map { label ->
            val i = pool.indexOfFirst { it.label == label }
            if (i >= 0) pool.removeAt(i) else null
        }
        var k = 0
        return rows.map { r ->
            KeyRow(r.keys.map { key ->
                if (key.type != KeyType.CHARACTER) key
                else {
                    val idx = k++
                    // ширина — у места, клавиша — та, что сюда переставлена (или новая со своим символом)
                    (placed[idx] ?: KeyModel(order[idx])).copy(weight = key.weight)
                }
            }, r.heightWeight)
        }
    }

    /**
     * Новый формат: клавиши берутся из раскладки по записи; места, для которых записи нет (раскладка с тех пор
     * изменилась — например, включили кнопку эмодзи), занимают оставшиеся клавиши. Если служебная клавиша
     * осталась без места — порядок не подходит, раскладка остаётся как есть.
     */
    private fun applyFull(rows: List<KeyRow>, order: List<String>): List<KeyRow> {
        val want = ArrayList<MutableList<String>>().apply { add(ArrayList()) }
        for (t in order) if (t == ROW) want += ArrayList<String>() else want.last() += t
        val pool = rows.flatMap { r -> r.keys.filter { it.type != KeyType.SPACER } }.toMutableList()
        val placed = rows.mapIndexed { ri, r ->
            val places = r.keys.filter { it.type != KeyType.SPACER }
            places.mapIndexed { j, place ->
                val t = want.getOrNull(ri)?.getOrNull(j) ?: return@mapIndexed null
                val i = pool.indexOfFirst { token(it) == t }
                when {
                    i >= 0 -> pool.removeAt(i)
                    t.startsWith(SERVICE) || place.type != KeyType.CHARACTER -> null
                    else -> KeyModel(t)
                }
            }.toMutableList()
        }
        // свободные места: сначала новые клавиши (их нет в записи), потом остальные
        val wanted = order.toSet()
        pool.sortBy { if (token(it) in wanted) 1 else 0 }
        for (row in placed) for (j in row.indices) if (row[j] == null && pool.isNotEmpty()) row[j] = pool.removeAt(0)
        if (pool.any { it.type != KeyType.CHARACTER } || placed.any { r -> r.any { it == null } }) return rows
        return rows.mapIndexed { ri, r ->
            var j = 0
            KeyRow(r.keys.map { place ->
                if (place.type == KeyType.SPACER) place
                else {
                    val k = placed[ri][j++]!!
                    // буква на месте буквы — ширины места (ряды остаются ровными), остальные — своей ширины
                    if (k.type == KeyType.CHARACTER && place.type == KeyType.CHARACTER) k.copy(weight = place.weight) else k
                }
            }, r.heightWeight)
        }
    }

    fun save(context: Context, current: Map<String, List<String>>, id: String, order: List<String>?) {
        val next = current.toMutableMap()
        if (order == null) next.remove(id) else next[id] = order
        val o = JSONObject()
        next.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        KeyboardPrefs.edit(context) { putString(KEY, if (next.isEmpty()) "" else o.toString()) }
    }
}

/** Клавиша редактора: раздел (раскладка или ряд цифр), ряд, место в ряду и сама клавиша. */
private class EditorSlot(val section: String, val row: Int, val index: Int, val key: KeyModel, val id: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LayoutEditorPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val languages = remember(settings.languages) { Languages.enabled(settings) }
    var langId by remember { mutableStateOf(languages.first().id) }
    var page by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EditorSlot?>(null) }
    val lang = languages.firstOrNull { it.id == langId } ?: languages.first()
    val id = KeyboardLayouts.layoutId(lang, page)
    val user = settings.userLayoutMap
    val langLabels = remember(languages) { languages.map { it.label } }
    // те же ряды, что на клавиатуре: с нижним рядом и рядом цифр
    val rows = remember(lang, page, user, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys, settings.spaceLeftKey, settings.spaceRightKey, settings.showNumberRow, langLabels) {
        KeyboardLayouts.rows(
            lang, page, settings.showNumberRow && page == 0, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
            langLabels, settings.spaceLeftKey, settings.spaceRightKey, user
        )
    }
    val hasNumbers = settings.showNumberRow && page == 0
    // клавиши по разделам; у каждой — постоянное имя (подпись + номер повтора), по нему клавиша «летит» на новое место
    val slots = remember(rows, id) {
        val seen = HashMap<String, Int>()
        rows.mapIndexed { ri, r ->
            val section = if (hasNumbers && ri == 0) UserLayouts.NUMBERS else id
            val rowInSection = if (hasNumbers) ri - (if (ri == 0) 0 else 1) else ri
            r.keys.filter { it.type != KeyType.SPACER }.mapIndexed { j, k ->
                val t = section + ":" + UserLayouts.token(k)
                val n = seen.getOrDefault(t, 0)
                seen[t] = n + 1
                EditorSlot(section, rowInSection, j, k, "$t#$n")
            }
        }
    }
    val positions = remember(id) { HashMap<String, Offset>() }
    val bounds = remember(id) { HashMap<String, Rect>() }
    val root = remember { CoordsHolder() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<String?>(null) }

    fun sectionRows(section: String): List<List<EditorSlot>> = slots.filter { r -> r.firstOrNull()?.section == section }

    fun saveSection(section: String, rowsOf: List<List<KeyModel>>?) {
        UserLayouts.save(context, user, section, rowsOf?.let { UserLayouts.encode(it) })
        selected = null
    }

    fun swap(a: String, b: String) {
        val sa = slots.flatten().firstOrNull { it.id == a } ?: return
        val sb = slots.flatten().firstOrNull { it.id == b } ?: return
        if (a == b) return
        if (sa.section != sb.section) {
            // ряд цифр и остальные клавиши — разные раскладки
            android.widget.Toast.makeText(context, tr("Ряд цифр меняется только внутри себя"), android.widget.Toast.LENGTH_SHORT).show()
            selected = null
            return
        }
        val grid = sectionRows(sa.section).map { r -> r.map { it.key }.toMutableList() }
        grid[sa.row][sa.index] = sb.key
        grid[sb.row][sb.index] = sa.key
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        saveSection(sa.section, grid)
    }

    InfoCard(
        Icons.Rounded.SwapHoriz,
        "Перетащите клавишу на другую или нажмите на две по очереди — они поменяются местами. Меняются любые клавиши: " +
            "буквы, цифры, знаки, пробел, запятая, эмодзи, смена языка, Shift, ⌫ и Enter. Чтобы заменить букву или знак " +
            "другим символом, нажмите на неё и выберите «Заменить символ» (или удерживайте её)."
    )
    SettingsSection("Раскладка") {
        if (languages.size > 1) {
            ChoiceItem(
                Icons.Rounded.SwapHoriz, "Язык", null,
                languages.map { it.id to it.label }, langId
            ) { langId = it; selected = null }
        }
        ChoiceItem(
            Icons.Rounded.SwapHoriz, "Страница", null,
            listOf("0" to "Буквы", "1" to "Символы", "2" to "Символы 2"), page.toString()
        ) { page = it.toInt(); selected = null }
    }
    SettingsSection(lang.displayTitle()) {
        GroupItem {
            Box(Modifier.fillMaxWidth().padding(10.dp).onGloballyPositioned { root.coords = it }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEachIndexed { ri, r ->
                        if (hasNumbers && ri == 1) Spacer(Modifier.height(4.dp))
                        val rowSlots = slots[ri]
                        Row(Modifier.fillMaxWidth().height(if (hasNumbers && ri == 0) 38.dp else 46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            var j = 0
                            for (k in r.keys) {
                                if (k.type == KeyType.SPACER) {
                                    Spacer(Modifier.weight(k.weight))
                                    continue
                                }
                                val slot = rowSlots[j++]
                                key(slot.id) {
                                    EditorKey(
                                        slot = slot,
                                        lang = lang,
                                        selected = selected == slot.id,
                                        dragged = dragging == slot.id,
                                        dragDelta = if (dragging == slot.id) dragDelta else Offset.Zero,
                                        isTarget = target == slot.id,
                                        positions = positions,
                                        bounds = bounds,
                                        root = root,
                                        modifier = Modifier.weight(k.weight).fillMaxHeight(),
                                        onTap = {
                                            val s = selected
                                            when {
                                                s == null -> {
                                                    selected = slot.id
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                s == slot.id -> selected = null
                                                else -> swap(s, slot.id)
                                            }
                                        },
                                        onLongPress = {
                                            if (slot.key.type == KeyType.CHARACTER) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                editing = slot
                                            }
                                        },
                                        onDragStart = {
                                            dragging = slot.id
                                            dragDelta = Offset.Zero
                                            selected = null
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { d ->
                                            dragDelta += d
                                            val b = bounds[slot.id]
                                            val p = b?.center?.plus(dragDelta)
                                            val over = p?.let { pt -> bounds.entries.firstOrNull { it.key != slot.id && it.value.contains(pt) }?.key }
                                            if (over != target) {
                                                target = over
                                                if (over != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = {
                                            val over = target
                                            dragging = null
                                            dragDelta = Offset.Zero
                                            target = null
                                            if (over != null) swap(slot.id, over)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // выбранную клавишу можно не менять местами, а заменить другим символом — без долгого нажатия
        val pickedSlot = selected?.let { sel -> slots.flatten().firstOrNull { it.id == sel } }
        if (pickedSlot != null && pickedSlot.key.type == KeyType.CHARACTER) {
            ActionItem(
                Icons.Rounded.Edit, tr("Заменить символ на клавише «{0}»", pickedSlot.key.label),
                tr("Поставить на это место другую букву, цифру, знак или смайл"), onClick = { editing = pickedSlot }
            )
        }
        if (user.containsKey(id) || (hasNumbers && user.containsKey(UserLayouts.NUMBERS))) {
            ActionItem(Icons.Rounded.RestartAlt, "Вернуть как было", "Только для этой раскладки", onClick = {
                var next = user - id
                if (hasNumbers) next = next - UserLayouts.NUMBERS
                UserLayouts.save(context, next, id, null)
                selected = null
            })
        }
    }

    val e = editing
    if (e != null) {
        // старый символ выделен — набранный сразу его заменит
        var text by remember(e) { mutableStateOf(TextFieldValue(e.key.label, TextRange(0, e.key.label.length))) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("Символ на клавише")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { if (it.text.length <= 8) text = it }, singleLine = true,
                    label = { Text(tr("Буква, цифра, знак или смайл")) }
                )
            },
            confirmButton = {
                TextButton(enabled = text.text.isNotBlank(), onClick = {
                    val grid = sectionRows(e.section).map { r -> r.map { it.key }.toMutableList() }
                    grid[e.row][e.index] = KeyModel(text.text.trim())
                    saveSection(e.section, grid)
                    editing = null
                }) { Text(tr("Готово")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Отмена")) } }
        )
    }
}

/**
 * Клавиша редактора. Переставленная клавиша не прыгает, а перелетает со старого места на новое с пружинкой
 * (запоминаем, где она стояла, и разыгрываем разницу); выбранная покачивается, перетаскиваемая — приподнята.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorKey(
    slot: EditorSlot,
    lang: KeyboardLanguage,
    selected: Boolean,
    dragged: Boolean,
    dragDelta: Offset,
    isTarget: Boolean,
    positions: HashMap<String, Offset>,
    bounds: HashMap<String, Rect>,
    root: CoordsHolder,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val fly = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val lift by animateFloatAsState(
        when {
            dragged -> 1.18f
            isTarget -> 0.88f
            selected -> 1.08f
            else -> 1f
        },
        spring(dampingRatio = 0.5f, stiffness = 500f), label = "editLift"
    )
    val wobble = if (selected && !dragged) {
        val t = rememberInfiniteTransition(label = "editWobble")
        t.animateFloat(-3f, 3f, infiniteRepeatable(tween(130), RepeatMode.Reverse), label = "editWobbleAngle").value
    } else 0f
    val service = slot.key.type != KeyType.CHARACTER
    val bg by animateColorAsState(
        when {
            selected || dragged -> cs.primary
            isTarget -> cs.tertiaryContainer
            service -> cs.secondaryContainer
            else -> cs.surfaceContainerHighest
        },
        label = "editKey"
    )
    val fg = when {
        selected || dragged -> cs.onPrimary
        isTarget -> cs.onTertiaryContainer
        service -> cs.onSecondaryContainer
        else -> cs.onSurface
    }
    val cTap by rememberUpdatedState(onTap)
    val cLong by rememberUpdatedState(onLongPress)
    val cStart by rememberUpdatedState(onDragStart)
    val cDrag by rememberUpdatedState(onDrag)
    val cEnd by rememberUpdatedState(onDragEnd)
    val cDelta by rememberUpdatedState(dragDelta)
    Box(
        modifier
            .zIndex(if (dragged) 2f else if (fly.isRunning) 1f else 0f)
            .onPlaced { c ->
                val r = root.coords ?: return@onPlaced
                val p = runCatching { r.localPositionOf(c, Offset.Zero) }.getOrNull() ?: return@onPlaced
                bounds[slot.id] = Rect(p, androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
                val old = positions[slot.id]
                positions[slot.id] = p
                if (old != null && (abs(old.x - p.x) > 1f || abs(old.y - p.y) > 1f)) {
                    scope.launch {
                        fly.snapTo(fly.value + old - p)
                        fly.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = 320f))
                    }
                }
            }
            .graphicsLayer {
                val move = if (dragged) dragDelta else fly.value
                translationX = move.x
                translationY = move.y
                // в полёте клавиша чуть увеличивается и наклоняется по ходу движения
                val flight = min(1f, fly.value.getDistance() / (size.width.coerceAtLeast(1f) * 2f))
                scaleX = lift * (1f + 0.12f * flight)
                scaleY = lift * (1f + 0.12f * flight)
                rotationZ = wobble + (-fly.value.x / size.width.coerceAtLeast(1f)).coerceIn(-1f, 1f) * 8f
            }
            .then(if (dragged) Modifier.shadow(10.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(if (isTarget) Modifier.border(2.dp, cs.tertiary, RoundedCornerShape(10.dp)) else Modifier)
            .pointerInput(slot.id) {
                // клавиша летит дальше оттуда, где её отпустили: на новое место или обратно на своё
                fun endDrag() {
                    val from = cDelta
                    scope.launch {
                        fly.snapTo(from)
                        fly.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = 320f))
                    }
                    cEnd()
                }
                detectDragGestures(
                    onDragStart = { cStart() },
                    onDrag = { ch, d ->
                        ch.consume()
                        cDrag(d)
                    },
                    onDragEnd = { endDrag() },
                    onDragCancel = { endDrag() }
                )
            }
            .combinedClickable(onLongClick = { cLong() }) { cTap() },
        contentAlignment = Alignment.Center
    ) {
        val icon = when (slot.key.type) {
            KeyType.SHIFT -> Icons.Rounded.KeyboardCapslock
            KeyType.BACKSPACE -> Icons.AutoMirrored.Rounded.Backspace
            KeyType.GLOBE -> Icons.Rounded.Language
            KeyType.STICKERS -> Icons.Rounded.EmojiEmotions
            KeyType.ENTER -> Icons.AutoMirrored.Rounded.KeyboardReturn
            else -> null
        }
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        else Text(
            if (slot.key.type == KeyType.SPACE) lang.spaceLabel.ifBlank { tr("Пробел") } else slot.key.label,
            color = fg, fontSize = if (slot.key.label.length > 2 || slot.key.type == KeyType.SPACE) 13.sp else 17.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}
