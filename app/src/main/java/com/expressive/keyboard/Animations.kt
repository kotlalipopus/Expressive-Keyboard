package com.expressive.keyboard

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ============================================================================
// АНИМАЦИИ
// Нажатие клавиши описывается одной «формулой»: на сколько клавиша сжимается по ширине и высоте,
// сдвигается, поворачивается и тускнеет, и насколько упруго возвращается. Готовые анимации — такие же
// формулы, свои пользователь собирает ползунками. Отдельно — скорость всех анимаций, всплывающая
// подсказка над клавишей и переходы между панелями.
// ============================================================================

data class PressAnim(
    val id: String,
    val title: String,
    val scaleX: Float = 0.94f,
    val scaleY: Float = 0.94f,
    /** Сдвиг по вертикали, dp: плюс — вниз (вдавливание), минус — вверх (подпрыгивание). */
    val lift: Float = 0f,
    val rotate: Float = 0f,
    val alpha: Float = 1f,
    /** Упругость возврата: 0.2 — долго качается, 1 — без колебаний. */
    val damping: Float = 0.5f,
    /** Скорость возврата (жёсткость пружины). */
    val stiffness: Float = 600f,
    val custom: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("sx", scaleX.toDouble()).put("sy", scaleY.toDouble())
        .put("lift", lift.toDouble()).put("rot", rotate.toDouble()).put("alpha", alpha.toDouble())
        .put("damping", damping.toDouble()).put("stiffness", stiffness.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = PressAnim(
            id = o.optString("id"), title = o.optString("title", "Своя"),
            scaleX = o.optDouble("sx", 0.94).toFloat().coerceIn(0.5f, 1.5f),
            scaleY = o.optDouble("sy", 0.94).toFloat().coerceIn(0.5f, 1.5f),
            lift = o.optDouble("lift", 0.0).toFloat().coerceIn(-16f, 16f),
            rotate = o.optDouble("rot", 0.0).toFloat().coerceIn(-45f, 45f),
            alpha = o.optDouble("alpha", 1.0).toFloat().coerceIn(0.1f, 1f),
            damping = o.optDouble("damping", 0.5).toFloat().coerceIn(0.15f, 1f),
            stiffness = o.optDouble("stiffness", 600.0).toFloat().coerceIn(100f, 3000f),
            custom = true
        )
    }
}

object Anims {
    val presets = listOf(
        // возврат короткий: каждый кадр анимации — перерисовка всей клавиатуры, долгие покачивания грели телефон
        PressAnim("scale", "Сжатие", 0.94f, 0.94f, damping = 0.8f, stiffness = 1400f),
        PressAnim("jelly", "Желе", 1.07f, 0.93f, damping = 0.45f, stiffness = 1100f),
        PressAnim("bounce", "Пружина", 1.07f, 1.07f, damping = 0.55f, stiffness = 1200f),
        PressAnim("sink", "Вдавливание", 0.98f, 0.98f, lift = 2.5f, damping = 0.85f, stiffness = 1400f),
        PressAnim("pop", "Прыжок", 1.1f, 1.1f, lift = -5f, damping = 0.5f, stiffness = 1000f),
        PressAnim("squish", "Расплющивание", 1.12f, 0.82f, lift = 2f, damping = 0.5f, stiffness = 1000f),
        PressAnim("tilt", "Наклон", 0.95f, 0.95f, rotate = -6f, damping = 0.55f, stiffness = 1000f),
        PressAnim("fade", "Затухание", 0.97f, 0.97f, alpha = 0.5f, damping = 1f, stiffness = 1200f),
        PressAnim("twist", "Вихрь", 0.87f, 0.87f, lift = -3f, rotate = 16f, damping = 0.56f, stiffness = 1250f),
        PressAnim("stretch", "Растяжение", 0.83f, 1.19f, lift = -2f, damping = 0.48f, stiffness = 1150f),
        PressAnim("none", "Без анимации", 1f, 1f, damping = 1f, stiffness = 1500f)
    )

    /** Анимация из темы — та же формула, что у готовых. */
    fun fromEffect(e: PressEffect?, pressScale: Float?): PressAnim = when (e) {
        PressEffect.JELLY -> presets[1]
        PressEffect.BOUNCE -> presets[2].let { if (pressScale != null) it.copy(scaleX = pressScale, scaleY = pressScale) else it }
        PressEffect.SINK -> presets[3]
        PressEffect.NONE -> presets.last()
        else -> presets[0].let { if (pressScale != null) it.copy(scaleX = pressScale, scaleY = pressScale) else it }
    }

    @Volatile private var parsedRaw = ""
    @Volatile private var parsed: List<PressAnim> = emptyList()

    fun custom(settings: KeyboardSettings): List<PressAnim> {
        val raw = settings.customAnims
        if (raw != parsedRaw) {
            parsed = runCatching {
                val a = JSONArray(raw.ifBlank { "[]" })
                (0 until a.length()).map { PressAnim.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            parsedRaw = raw
        }
        return parsed
    }

    fun all(settings: KeyboardSettings): List<PressAnim> = cached(settings)

    /** Готовый список и карта id → анимация: forKey вызывается для каждой клавиши, а список собирался заново. */
    @Volatile private var allRaw = ""
    @Volatile private var allCached: List<PressAnim> = presets
    @Volatile private var byId: Map<String, PressAnim> = presets.associateBy { it.id }

    private fun cached(settings: KeyboardSettings): List<PressAnim> {
        val raw = settings.customAnims
        if (raw != allRaw) {
            val list = presets + custom(settings)
            val map = HashMap<String, PressAnim>(list.size * 2)
            // при совпадении id побеждает первый, как и раньше в firstOrNull
            list.forEach { map.putIfAbsent(it.id, it) }
            byId = map
            allCached = list
            allRaw = raw
        }
        return allCached
    }

    /** Анимация нажатия для клавиши: выбранная в настройках или из темы. */
    fun forKey(settings: KeyboardSettings, style: KeyStyle?): PressAnim {
        cached(settings)
        val id = settings.animPress
        if (id != "THEME") byId[id]?.let { return it }
        return fromEffect(style?.press, style?.pressScale)
    }

    fun saveCustom(context: android.content.Context, settings: KeyboardSettings, anim: PressAnim?, removeId: String? = null) {
        val list = custom(settings).filter { it.id != (anim?.id ?: removeId) }.toMutableList()
        if (anim != null) list += anim.copy(custom = true)
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        KeyboardPrefs.edit(context) {
            putString("custom_anims", a.toString())
            if (anim != null) putString("anim_press", anim.id)
            else if (settings.animPress == removeId) putString("anim_press", "THEME")
        }
    }

    fun exportCustom(settings: KeyboardSettings): String = JSONObject()
        .put("format", "expressive-keyboard-animations")
        .put("version", 1)
        .put("animations", JSONArray(custom(settings).map { it.toJson() }))
        .toString(2)

    fun importCustom(context: Context, json: String): Int {
        val text = json.removePrefix("\uFEFF").trim()
        val entries = if (text.startsWith("[")) JSONArray(text) else {
            val document = JSONObject(text)
            if (document.has("animations")) document.getJSONArray("animations") else JSONArray().put(document)
        }
        require(entries.length() in 1..100) { "Expected 1–100 animations" }
        val existing = custom(KeyboardPrefs.current(context)).associateByTo(linkedMapOf()) { it.id }
        val reserved = presets.mapTo(hashSetOf("THEME")) { it.id }
        val imported = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            require(listOf("sx", "sy", "lift", "rot", "alpha").any(entry::has)) { "Invalid animation" }
            val animation = PressAnim.fromJson(entry)
            require(listOf(animation.scaleX, animation.scaleY, animation.lift, animation.rotate,
                animation.alpha, animation.damping, animation.stiffness).all(Float::isFinite)) { "Invalid animation values" }
            val id = animation.id.takeIf { it.isNotBlank() && it.length <= 128 && it !in reserved }
                ?: "custom.${java.util.UUID.randomUUID()}"
            animation.copy(id = id, title = animation.title.take(24).ifBlank { tr("Моя анимация") })
        }
        imported.forEach { existing[it.id] = it }
        KeyboardPrefs.edit(context) { putString("custom_anims", JSONArray(existing.values.map { it.toJson() }).toString()) }
        return imported.size
    }

    /** a — прогресс нажатия (может «перелетать» за 1 и уходить ниже 0 у упругих анимаций). */
    fun apply(scope: androidx.compose.ui.graphics.GraphicsLayerScope, anim: PressAnim, a: Float, strength: Float) {
        val k = a * strength
        scope.scaleX = 1f + (anim.scaleX - 1f) * k
        scope.scaleY = 1f + (anim.scaleY - 1f) * k
        if (anim.lift != 0f) scope.translationY = anim.lift * scope.density * k
        if (anim.rotate != 0f) scope.rotationZ = anim.rotate * k
        if (anim.alpha < 1f) scope.alpha = (1f - (1f - anim.alpha) * k.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

// ----------------------------------------------------------------------- экран настроек

/** Клавиша-образец: нажмите — проиграется анимация. */
@Composable
internal fun AnimSample(anim: PressAnim, speed: Float, strength: Float, selected: Boolean, label: String, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val a = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(48.dp)
                .graphicsLayer { Anims.apply(this, anim, a.value, strength) }
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) cs.primary else cs.secondaryContainer)
                .pointerInput(anim, speed) {
                    awaitEachGesture {
                        awaitFirstDown()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            a.snapTo(0.6f)
                            a.animateTo(1f, spring(dampingRatio = 1f, stiffness = 3500f * speed * speed))
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        scope.launch { a.animateTo(0f, spring(dampingRatio = anim.damping, stiffness = anim.stiffness * speed * speed)) }
                        onClick?.invoke()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = if (selected) cs.onPrimary else cs.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(
            tr(label), style = MaterialTheme.typography.labelMedium, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
internal fun AnimationsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val fileScope = rememberCoroutineScope()
    val importAnimations = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fileScope.launch {
            val result = runCatching {
                val json = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        buildString {
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                require(length + count <= 1_048_576) { "Animation file is too large" }
                                appendRange(buffer, 0, count)
                            }
                        }
                    }
                }
                Anims.importCustom(context, json)
            }
            result.onSuccess { Toast.makeText(context, tr("Импортировано анимаций: {0}", it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    val exportAnimations = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) fileScope.launch {
            val json = Anims.exportCustom(KeyboardPrefs.current(context))
            val saved = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) } }.isSuccess
            }
            Toast.makeText(context, tr(if (saved) "Файл сохранён" else "Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }
    var editing by remember { mutableStateOf<PressAnim?>(null) }
    val speed = settings.pressAnimSpeed
    InfoCard(Icons.Rounded.Animation, "Нажмите на любую клавишу-образец, чтобы увидеть анимацию. «Как в теме» — у каждой темы своя анимация нажатия.")
    SettingsSection("Нажатие клавиши") {
        GroupItem {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val themeAnim = Anims.fromEffect(PluginRepository.activeSkin(settings)?.styleFor(KeyType.CHARACTER, "a")?.press, null)
                AnimSample(themeAnim, speed, settings.pressAnimation.coerceAtLeast(0.3f), settings.animPress == "THEME", "Как в теме") {
                    KeyboardPrefs.edit(context) { putString("anim_press", "THEME") }
                }
                Anims.all(settings).forEach { anim ->
                    AnimSample(anim, speed, settings.pressAnimation.coerceAtLeast(0.3f), settings.animPress == anim.id, anim.title) {
                        KeyboardPrefs.edit(context) { putString("anim_press", anim.id) }
                    }
                }
            }
        }
        SliderItem(
            icon = Icons.Rounded.Animation, title = "Сила анимации нажатия",
            value = settings.pressAnimation, range = 0f..2f,
            format = { if (it < 0.05f) tr("Выкл") else "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("press_animation", if (v < 0.05f) 0f else v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Speed, title = "Скорость нажатия клавиш",
            value = settings.pressAnimSpeed, range = 0.5f..2f,
            format = { String.format("×%.1f", it) },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("press_anim_speed", v) } }
        )
    }
    SettingsSection("Свои анимации") {
        ActionItem(Icons.Rounded.FileOpen, "Импорт анимаций", null,
            onClick = { importAnimations.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        if (Anims.custom(settings).isNotEmpty()) {
            ActionItem(Icons.Rounded.Download, "Экспорт анимаций", null,
                onClick = { exportAnimations.launch("expressive-animations.json") })
        }
        Anims.custom(settings).forEach { anim ->
            ActionItem(Icons.Rounded.AutoAwesome, anim.title, animSummary(anim), onClick = { editing = anim }, trailing = {
                IconButton(onClick = { Anims.saveCustom(context, settings, null, anim.id) }) {
                    Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = MaterialTheme.colorScheme.error)
                }
            })
        }
        ActionItem(Icons.Rounded.Add, "Создать анимацию", "Сжатие, сдвиг, поворот, прозрачность и упругость — ползунками", onClick = {
            editing = PressAnim("custom.${System.currentTimeMillis()}", tr("Моя анимация"), custom = true)
        })
    }
    SettingsSection("Подсказка над клавишей") {
        ChoiceItem(
            Icons.Rounded.ChatBubbleOutline, "Как появляется", null,
            listOf("POP" to "Пружина", "ZOOM" to "Из точки", "SLIDE" to "Выезжает", "FADE" to "Проявление", "NONE" to "Сразу"),
            settings.animPopup
        ) { v -> KeyboardPrefs.edit(context) { putString("anim_popup", v) } }
    }
    SettingsSection("Панели") {
        SliderItem(
            icon = Icons.Rounded.Speed, title = "Скорость переходов панелей",
            value = settings.panelAnimSpeed, range = 0.5f..2f,
            format = { String.format("×%.1f", it) },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("panel_anim_speed", v) } }
        )
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val hiddenByPowerSaving = settings.panelAnimations &&
            !settings.forPower(power?.isPowerSaveMode == true).panelAnimations
        ChoiceItem(
            Icons.Rounded.Layers, "Переход между буквами, символами и панелями", null,
            listOf(
                "CENTER" to "Из центра", "SPLIT" to "Разлёт", "FOLD" to "Переворот",
                "WAVE" to "Волна по клавишам", "WAVE_SCALE" to "Волна с увеличением",
                "CASCADE" to "Каскад рядов", "SWEEP" to "Ряды в сторону",
                "SCALE" to "Увеличение", "SLIDE" to "Сдвиг", "FADE" to "Проявление", "NONE" to "Без анимации"
            ),
            if (settings.panelAnimations) settings.animPanel else "NONE"
        ) { v ->
            KeyboardPrefs.edit(context) {
                putString("anim_panel", v)
                putBoolean("panel_animations", v != "NONE")
            }
        }
        if (hiddenByPowerSaving) {
            ActionItem(
                Icons.Rounded.Animation,
                "Включить переходы в энергосбережении",
                "Энергосбережение отключило анимацию панелей",
                onClick = { KeyboardPrefs.edit(context) { putBoolean("eco_panels", false) } }
            )
        }
    }
    editing?.let { a -> AnimEditorDialog(a, settings, onDismiss = { editing = null }) { Anims.saveCustom(context, settings, it); editing = null } }
}

private fun animSummary(a: PressAnim): String = buildList {
    if (a.scaleX != 1f || a.scaleY != 1f) add(tr("размер {0}×{1}", "%.2f".format(a.scaleX), "%.2f".format(a.scaleY)))
    if (a.lift != 0f) add(tr("сдвиг {0} dp", a.lift.roundToInt()))
    if (a.rotate != 0f) add(tr("поворот {0}°", a.rotate.roundToInt()))
    if (a.alpha < 1f) add(tr("прозрачность {0}%", ((1f - a.alpha) * 100).roundToInt()))
}.joinToString(" · ").ifEmpty { tr("без изменений") }

@Composable
private fun AnimEditorDialog(initial: PressAnim, settings: KeyboardSettings, onDismiss: () -> Unit, onSave: (PressAnim) -> Unit) {
    var a by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Своя анимация")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimSample(a, settings.pressAnimSpeed, 1f, true, "Нажмите", null)
                }
                OutlinedTextField(
                    value = a.title, onValueChange = { a = a.copy(title = it.take(24)) }, singleLine = true,
                    label = { Text(tr("Название")) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                AnimSlider("Ширина", a.scaleX, 0.6f..1.4f, { "%.2f".format(it) }) { a = a.copy(scaleX = it) }
                AnimSlider("Высота", a.scaleY, 0.6f..1.4f, { "%.2f".format(it) }) { a = a.copy(scaleY = it) }
                AnimSlider("Сдвиг вниз / вверх", a.lift, -12f..12f, { "${it.roundToInt()} dp" }) { a = a.copy(lift = it) }
                AnimSlider("Поворот", a.rotate, -30f..30f, { "${it.roundToInt()}°" }) { a = a.copy(rotate = it) }
                AnimSlider("Видимость", a.alpha, 0.2f..1f, { "${(it * 100).roundToInt()}%" }) { a = a.copy(alpha = it) }
                AnimSlider("Упругость (меньше — дольше качается)", a.damping, 0.15f..1f, { "%.2f".format(it) }) { a = a.copy(damping = it) }
                AnimSlider("Скорость возврата", a.stiffness, 150f..2000f, { "${it.roundToInt()}" }) { a = a.copy(stiffness = it) }
            }
        },
        confirmButton = { Button(onClick = { onSave(a.copy(title = a.title.ifBlank { tr("Моя анимация") })) }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun AnimSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
