package com.expressive.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.toArgb
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import java.net.HttpURLConnection
import java.net.URL
import org.mozilla.javascript.Context as Rhino

// ============================================================================
// 14. МОДЫ: JAVASCRIPT-СКРИПТЫ ПЛАГИНОВ
// Плагин с полем "script" (или файл .js / архив .zip) получает свою песочницу JavaScript
// (ES6, без доступа к Java-классам и файлам) и объект kb: события клавиатуры, ввод и
// чтение текста, свои кнопки, панели, клавиши-команды, хранилище, сеть, таймеры, иконки.
// Каждый вызов ограничен по времени — зависший скрипт не подвесит клавиатуру.
// Полное описание API — PLUGINS.md.
// ============================================================================

/** То, что скрипты могут попросить у сервиса клавиатуры. Реализует ComposeKeyboardService. */
interface ScriptBridge {
    fun scriptCommit(text: String)
    fun scriptDelete(before: Int, after: Int)
    fun scriptTextBefore(n: Int): String
    fun scriptTextAfter(n: Int): String
    fun scriptSelected(): String
    fun scriptSetSelection(start: Int, end: Int)
    fun scriptKey(name: String)
    fun scriptLanguage(): String
    fun scriptSetLanguage(id: String): Boolean
    fun scriptShift(): String
    fun scriptSetShift(state: String)
    fun scriptField(): Map<String, Any?>
    fun scriptOpenPanel(id: String)
    fun scriptStatus(text: String)
    fun scriptVibrate(ms: Int)
    fun scriptClipboard(): List<String>
    fun scriptCopy(text: String)
    /** pick — свой обработчик выбора подсказки (слово, номер); null — подсказка просто вставляется. */
    fun scriptSuggestions(words: List<String>, pick: ((String, Int) -> Unit)? = null)
    fun scriptHide()
    fun scriptSetComposing(text: String) {}
    fun scriptFinishComposing() {}
    /** [начало, конец] выделения в поле; пусто — поле не сказало. */
    fun scriptCursor(): List<Int> = emptyList()
    fun scriptAllText(): String = ""
    fun scriptReplaceSelection(text: String) {}
    fun scriptReplaceAll(text: String) {}
    fun scriptSendKey(code: Int, meta: Int) {}
    fun scriptEditorAction(name: String) {}
    fun scriptCommitImage(bytes: ByteArray, mime: String): Boolean = false
    /** Открыть (true) или закрыть поле ввода мода. */
    fun scriptPrompt(open: Boolean) {}
}

@Immutable
data class ScriptButton(val pluginId: String, val id: String, val title: String, val icon: String, val toolbar: Boolean)

@Immutable
data class ScriptPanel(val pluginId: String, val id: String, val title: String, val icon: String)

/** Ссылка на функцию скрипта (обработчик нажатия в панели). */
@Stable
class ScriptCall internal constructor(
    val pluginId: String,
    internal val fn: PluginScriptFunction,
    internal val runtime: PluginScriptRuntime
)

/** Элементы панели, которую рисует скрипт: render() возвращает массив таких объектов. */
@Immutable
sealed interface UiNode {
    data class Text(
        val text: String, val size: Float, val bold: Boolean, val color: Int?, val center: Boolean,
        val align: String = "start", val maxLines: Int = Int.MAX_VALUE, val mono: Boolean = false, val italic: Boolean = false
    ) : UiNode
    data class Button(
        val text: String, val icon: String, val accent: Boolean, val wide: Boolean, val action: ScriptCall?, val value: Any?,
        val color: Int? = null, val textColor: Int? = null, val longAction: ScriptCall? = null, val disabled: Boolean = false
    ) : UiNode
    /** Поле ввода: по нажатию клавиатура печатает в него (onChange — на каждую букву, onSubmit — по ✓/Enter). */
    data class Input(
        val title: String, val hint: String, val value: String, val onSubmit: ScriptCall?, val onChange: ScriptCall?
    ) : UiNode
    data class Toggle(val text: String, val checked: Boolean, val action: ScriptCall?) : UiNode
    data class Slider(val text: String, val value: Float, val min: Float, val max: Float, val step: Float, val action: ScriptCall?) : UiNode
    /** value null — бесконечная полоса «работаю». */
    data class Progress(val value: Float?) : UiNode
    object Divider : UiNode
    data class Box(
        val children: List<UiNode>, val color: Int?, val radius: Float, val padding: Float, val border: Int?,
        val action: ScriptCall?, val value: Any?
    ) : UiNode
    data class Tabs(val items: List<GridItem>, val selected: Any?, val action: ScriptCall?) : UiNode
    /** Холст: элементы как у kb.overlay (координаты от угла холста); onTap(x, y) — касание. */
    data class Canvas(val height: Float, val items: List<OverlayItem>, val action: ScriptCall?) : UiNode
    data class Scroll(val children: List<UiNode>, val horizontal: Boolean, val height: Float) : UiNode
    data class Row(val children: List<UiNode>) : UiNode
    data class Column(val children: List<UiNode>) : UiNode
    /** columns > 0 — ровно столько колонок одинаковой ширины (калькулятор не «прыгает» на другом dpi). */
    data class Grid(
        val items: List<GridItem>, val minWidth: Float, val height: Float, val action: ScriptCall?,
        val columns: Int = 0
    ) : UiNode
    data class Spacer(val size: Float) : UiNode
    data class Image(val src: String, val size: Float, val action: ScriptCall?, val value: Any?) : UiNode
    data class Error(val message: String) : UiNode
}

@Immutable
data class GridItem(val text: String, val icon: String, val value: Any?)

private class Mod(val plugin: KeyboardPlugin) {
    lateinit var runtime: PluginScriptRuntime
    val handlers = HashMap<String, MutableList<PluginScriptFunction>>()
    val commands = HashMap<String, PluginScriptFunction>()
    val buttons = LinkedHashMap<String, Pair<ScriptButton, PluginScriptFunction?>>()
    val panels = LinkedHashMap<String, Pair<ScriptPanel, PluginScriptFunction?>>()
    val timers = HashMap<Int, Runnable>()
    /** Таймеры, которые сработали, пока клавиатура скрыта: ждут показа, а не крутят процесс в фоне. */
    val parked = ArrayList<Runnable>()
    var nextTimer = 1
    /** Что мод рисует поверх клавиатуры (kb.overlay). */
    var overlay: List<OverlayItem> = emptyList()
    /** Что мод рисует под клавишами, поверх фона клавиатуры (kb.background). */
    var background: List<OverlayItem> = emptyList()
    /** Раскладки, заданные на лету (kb.setLayout). */
    val layouts = HashMap<String, List<KeyRow>>()
    /** Сколько раз мод не уложился во время: после трёх раз его обработчики отключаются до перезагрузки. */
    var timeouts = 0
    /** Время последних запросов kb.http — не больше 20 в минуту. */
    val httpTimes = ArrayDeque<Long>()
    /** Оформление, заданное на лету (kb.setSkin). */
    var skin: KeyboardSkin? = null
    /** Время последних запросов kb.ai — не больше 10 в минуту. */
    val aiTimes = ArrayDeque<Long>()
    /** Звуки, которые мод сейчас играет (kb.playAudio). */
    val players = HashMap<Int, android.media.MediaPlayer>()
    var nextPlayer = 1
    /** Мод хочет касания по клавиатуре (kb.captureTouches): пока так, печатать нельзя. */
    var touches = false
    /** Эффекты клавиш (kb.keyEffects). */
    var keyFx: Map<String, KeyFx> = emptyMap()
    /** Мод слушает микрофон (kb.audio.start). */
    var audio = false
    fun has(p: String) = p in plugin.permissions
}

object ScriptHost {
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)
    private var mods: Map<String, Mod> = emptyMap()
    private var appContext: Context? = null

    @Volatile var bridge: ScriptBridge? = null

    var buttons by mutableStateOf<List<ScriptButton>>(emptyList())
        private set
    var panels by mutableStateOf<List<ScriptPanel>>(emptyList())
        private set
    /** Увеличивается по kb.refresh() — панели скриптов перерисовываются. */
    var renderTick by mutableIntStateOf(0)
        private set
    /** Журнал скриптов: console.log, ошибки и время работы. */
    val logs = mutableStateMapOf<String, List<String>>()
    /** Всё, что моды рисуют поверх клавиатуры. */
    var overlayItems by mutableStateOf<List<OverlayItem>>(emptyList())
        private set
    /** Всё, что моды рисуют под клавишами. */
    var backgroundItems by mutableStateOf<List<OverlayItem>>(emptyList())
        private set
    /** Есть подписчики на "frame" — клавиатура шлёт кадры анимации. */
    var wantsFrames by mutableStateOf(false)
        private set
    /** Раскладки из kb.setLayout: важнее раскладок из манифестов плагинов. */
    var layoutOverrides by mutableStateOf<Map<String, List<KeyRow>>>(emptyMap())
        private set
    /** Оформление из kb.setSkin: важнее выбранного в настройках. */
    var skinOverride by mutableStateOf<KeyboardSkin?>(null)
        private set
    /**
     * Поле пароля или режим инкогнито: события с набираемым текстом и буфером (key, word, suggest, press,
     * start, clipboard) модам не приходят — даже с разрешением read_text.
     */
    @Volatile var privateMode = false
        set(value) {
            if (field == value) return
            field = value
            main.post { syncDevices() }
        }

    /** Открыт голосовой ввод: микрофон нужен ему, моды его не занимают. */
    var voiceBusy = false
        set(value) {
            if (field == value) return
            field = value
            syncDevices()
        }

    // --- поле ввода мода (kb.prompt, элемент input)
    private class Prompt(val m: Mod, val onSubmit: PluginScriptFunction?, val onChange: PluginScriptFunction?, val call: ScriptCall?, val change: ScriptCall?)
    private var prompt: Prompt? = null
    var promptTitle by mutableStateOf("")
        private set
    var promptHint by mutableStateOf("")
        private set
    var promptText by mutableStateOf("")
        private set

    /** Мод перехватывает касания клавиатуры (kb.captureTouches) — слой мода ловит их вместо клавиш. */
    var touchCapture by mutableStateOf(false)
        private set

    /** Обёртки на промисах поверх функций с обратным вызовом: kb.fetch(url).then(...). */
    private val PRELUDE = """
        (function () {
          var storage = kb.storage;
          var read = storage.get, write = storage.set;
          var parse = JSON.parse, stringify = JSON.stringify;
          storage.get = function (key, fallback) {
            var raw = read(key);
            return raw === null ? fallback : parse(raw);
          };
          storage.set = function (key, value) { write(key, stringify(value)); };
          function later(fn) { return new Promise(fn); }
          function done(res, rej) { return function (e, r) { if (e) rej(new Error(e)); else res(r); }; }
          kb.fetch = function (url, o) {
            var q = {}; o = o || {};
            for (var k in o) q[k] = o[k];
            q.url = url;
            return later(function (res, rej) { kb.http(q, done(res, rej)); });
          };
          kb.ask = function (prompt, text) { return later(function (res, rej) { kb.ai({ prompt: prompt, text: text || "" }, done(res, rej)); }); };
          kb.translateAsync = function (text, to) { return later(function (res, rej) { kb.translate(text, to, done(res, rej)); }); };
          kb.sleep = function (ms) { return later(function (res) { setTimeout(res, ms || 0); }); };
          kb.input = function (o) { return later(function (res) { kb.prompt(o || {}, res); }); };
        })();
    """.trimIndent()

    private const val CALL_BUDGET_MS = 300L
    private const val LOAD_BUDGET_MS = 2000L
    private const val MAX_TIMERS = 64
    /** События, которые показывают набираемый текст или буфер, приходят только плагинам с разрешением. */
    private val EVENT_PERMS = mapOf(
        "key" to "read_text", "word" to "read_text", "suggest" to "read_text", "start" to "read_text", "press" to "read_text",
        "selection" to "read_text", "pick" to "read_text", "voice" to "read_text", "autocorrect" to "read_text", "glide" to "read_text",
        // координаты пальца на клавиатуре — это и есть набираемый текст
        "pointer" to "read_text",
        "clipboard" to "clipboard",
        "tilt" to "sensors", "gyro" to "sensors", "audio" to "microphone", "app" to "app_info"
    )

    fun log(pluginId: String, line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logs[pluginId] = ((logs[pluginId] ?: emptyList()) + "$t  $line").takeLast(80)
    }

    private fun describe(t: Throwable): String = when (t) {
        is RhinoException -> "${t.details()} (строка ${t.lineNumber()})"
        is ScriptTimeout -> t.message.orEmpty()
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun <T> exec(m: Mod, budgetMs: Long, block: () -> T): Result<T> {
        return try {
            Result.success(m.runtime.execute(budgetMs, block))
        } catch (t: Throwable) {
            log(m.plugin.id, "✕ " + describe(t))
            // мод, который трижды подвесил клавиатуру, больше не получает событий и таймеров
            if (t is ScriptTimeout && ++m.timeouts >= 3 && m.handlers.isNotEmpty()) {
                m.handlers.clear()
                m.timers.values.forEach(main::removeCallbacks)
                m.timers.clear()
                log(m.plugin.id, "⏸ " + tr("мод тормозил клавиатуру и отключён до перезапуска"))
            }
            Result.failure(t)
        }
    }

    // ------------------------------------------------------------------ загрузка

    /** Первая загрузка модов ждёт, пока клавиатура покажется: запуск JavaScript не задерживает первый кадр. */
    private var firstLoad = true
    /** Первый запуск скриптов отложен: сюда — самый свежий список модов к этому моменту. */
    private var deferred: Pair<Context, List<KeyboardPlugin>>? = null

    fun reload(context: Context, plugins: List<KeyboardPlugin>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { reload(context, plugins) }
            return
        }
        if (deferred != null) {
            deferred = context to plugins
            return
        }
        if (firstLoad && plugins.any { it.script != null }) {
            firstLoad = false
            deferred = context to plugins
            // скрипты модов запускаются, когда главный поток свободен, — не в момент первых нажатий
            main.postDelayed({
                Looper.myQueue().addIdleHandler {
                    val d = deferred
                    deferred = null
                    if (d != null) reload(d.first, d.second)
                    false
                }
            }, 1200L)
            return
        }
        firstLoad = false
        appContext = context.applicationContext
        mods.values.forEach(::stop)
        mods = emptyMap()
        if (prompt != null) {
            prompt = null
            bridge?.scriptPrompt(false)
        }
        touchCapture = false
        ModFx.reset()
        val next = LinkedHashMap<String, Mod>()
        for (p in plugins) {
            val src = p.script ?: continue
            val m = Mod(p)
            val started = SystemClock.uptimeMillis()
            val runtime = runCatching {
                when (p.scriptEngine) {
                    PluginScriptEngine.RHINO -> RhinoPluginRuntime()
                    PluginScriptEngine.QUICKJS -> QuickJsPluginRuntime(context.assets)
                }
            }.onFailure { log(p.id, "✕ " + describe(it)) }.getOrNull() ?: continue
            m.runtime = runtime
            exec(m, LOAD_BUDGET_MS) {
                runtime.load(bindings(m), PRELUDE, src, p.id + ".js")
            }.onSuccess {
                next[p.id] = m
                log(p.id, "✓ " + tr("загружен за {0} мс", SystemClock.uptimeMillis() - started))
            }.onFailure { stop(m) }
        }
        mods = next
        lastApp = ""
        publish()
        renderTick++
    }

    private fun stop(m: Mod) {
        m.timers.values.forEach(main::removeCallbacks)
        m.timers.clear()
        m.parked.clear()
        m.players.values.forEach { runCatching { it.release() } }
        m.players.clear()
        m.runtime.close()
    }

    private fun publish() {
        buttons = mods.values.flatMap { m -> m.buttons.values.map { it.first } }
        panels = mods.values.flatMap { m -> m.panels.values.map { it.first } }
        publishOverlay()
        wantsFrames = mods.values.any { it.handlers["frame"]?.isNotEmpty() == true }
        val lay = HashMap<String, List<KeyRow>>()
        for (m in mods.values) for ((k, v) in m.layouts) lay.putIfAbsent(k, v)
        if (lay != layoutOverrides) layoutOverrides = lay
        val skin = mods.values.firstNotNullOfOrNull { it.skin }
        if (skin !== skinOverride) skinOverride = skin
        publishKeyFx()
        syncDevices()
    }

    private fun publishKeyFx() {
        val fx = HashMap<String, KeyFx>()
        for (m in mods.values) fx.putAll(m.keyFx)
        ModFx.updateKeyFx(fx)
    }

    private fun listens(event: String): Boolean {
        val perm = EVENT_PERMS[event]
        return mods.values.any { m -> m.handlers[event]?.isNotEmpty() == true && (perm == null || m.has(perm)) }
    }

    /** Датчики, микрофон и слежение за пальцем: только пока клавиатура на экране и они кому-то нужны. */
    private fun syncDevices() {
        val ctx = appContext ?: return
        if (!shown) ModFx.clearPointers()
        val usable = shown && !privateMode
        val nativeTilt = ModFx.usesTilt() || ModFx.shaderItems || ModFx.post != null
        ModSensors.sync(ctx, shown && nativeTilt || usable && listens("tilt"), usable && listens("gyro"))
        ModAudio.sync(ctx, usable && !voiceBusy && mods.values.any { it.audio && it.has("microphone") })
        ModFx.usesPointer(!privateMode && listens("pointer"))
    }

    private fun parseItems(m: Mod, raw: Any?): List<OverlayItem> {
        val list = raw as? List<*> ?: listOfNotNull(raw)
        return list.take(4000).mapNotNull { v ->
            OverlayItem.parse(v) ?: null.also {
                val o = v as? Map<*, *>
                if (o?.get("type") == "shader") {
                    val src = o["src"]?.toString().orEmpty()
                    log(m.plugin.id, "✕ shader: " + (ModShaders.error(src) ?: tr("нужен Android 13 или новее")))
                }
            }
        }
    }

    private var lastPointerEvent = 0L

    /** Палец на клавиатуре: движения — не чаще 60 раз в секунду, нажатие и отпускание — всегда. */
    fun pointerEvent(type: String, x: Float, y: Float, id: Int) {
        if (!has("pointer")) return
        val now = SystemClock.uptimeMillis()
        if (type == "move" && now - lastPointerEvent < 16L) return
        lastPointerEvent = now
        emit("pointer", mapOf("type" to type, "x" to x, "y" to y, "pointer" to id))
    }

    private var lastApp = ""

    /** Открылось поле другого приложения: событие "app" с его названием и цветом значка. */
    fun appChanged(pkg: String) {
        if (pkg == lastApp || pkg.isEmpty()) return
        lastApp = pkg
        if (!has("app") || privateMode) return
        val ctx = appContext ?: return
        io.launch {
            val info = appInfoOf(ctx, pkg)
            withContext(Dispatchers.Main) { if (lastApp == pkg) emit("app", info) }
        }
    }

    private fun appInfoOf(ctx: Context, pkg: String): Map<String, Any?> =
        mapOf("package" to pkg, "name" to ModApps.label(ctx, pkg), "color" to ModApps.color(ctx, pkg))

    private fun publishOverlay() {
        val items = mods.values.flatMap { it.overlay }
        if (items.isNotEmpty() || overlayItems.isNotEmpty()) overlayItems = items
        val back = mods.values.flatMap { it.background }
        if (back.isNotEmpty() || backgroundItems.isNotEmpty()) backgroundItems = back
        val shaders = items.any { it is OverlayItem.Shader } || back.any { it is OverlayItem.Shader }
        if (shaders != ModFx.shaderItems) {
            ModFx.shaderItems = shaders
            syncDevices()
        }
    }

    /** Клавиатура на экране. Пока скрыта, таймеры модов стоят: иначе мод с setInterval будит процесс каждый тик. */
    @Volatile var visible = true
        private set

    /** Окно клавиатуры правда показано. Моды загружаются и из настроек: там микрофон и датчики включаться не должны. */
    @Volatile private var shown = false

    fun setVisible(v: Boolean) {
        if (shown != v) {
            shown = v
            syncDevices()
        }
        if (visible == v) return
        visible = v
        if (!v) return
        for (m in mods.values) {
            val waiting = m.parked.toList()
            m.parked.clear()
            for (r in waiting) if (m.timers.containsValue(r)) main.post(r)
        }
    }

    /** Кадр анимации для подписчиков "frame": dt — миллисекунды с прошлого кадра. */
    fun frame(dt: Long) {
        emit("frame", dt.toDouble())
    }

    // ------------------------------------------------------------------ события

    fun has(event: String): Boolean = mods.values.any { it.handlers[event]?.isNotEmpty() == true }

    /** Вызывает обработчики события во всех модах. Результаты (уже в Kotlin-виде) — по порядку. */
    /** События, обработчики которых сейчас выполняются: kb.enter() внутри "enter" не вызовет "enter" снова. */
    private val running = HashSet<String>()

    fun emit(event: String, vararg args: Any?): List<Any?> {
        if (!has(event) || !running.add(event)) return emptyList()
        val out = ArrayList<Any?>()
        try {
            for (m in mods.values) {
                val hs = m.handlers[event] ?: continue
                val perm = EVENT_PERMS[event]
                if (perm != null && (privateMode || !m.has(perm))) continue
                for (h in hs.toList()) {
                    exec(m, CALL_BUDGET_MS) {
                        fromJs(m.runtime.call(h, args, readResult = true))
                    }.onSuccess { out += it }
                }
            }
        } finally {
            running.remove(event)
        }
        return out
    }

    /** "key": обработчик может вернуть строку (ввести её вместо буквы) или false (отменить нажатие). */
    fun filterKey(text: String): String? {
        val results = emit("key", mapOf("text" to text))
        var cur: String? = text
        for (r in results) {
            if (r == false) return null
            if (r is String) cur = r
        }
        return cur
    }

    /** true — какой-то обработчик вернул false (отменить стандартное действие). */
    fun cancelled(event: String, vararg args: Any?): Boolean = emit(event, *args).any { it == false }

    fun suggestions(word: String, previous: String): List<String> =
        emit("suggest", mapOf("word" to word, "previous" to previous))
            .flatMap { r -> (r as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) } ?: emptyList() }
            .distinct()

    fun runButton(pluginId: String, id: String) {
        val m = mods[pluginId] ?: return
        val fn = m.buttons[id]?.second ?: return
        exec(m, CALL_BUDGET_MS) { m.runtime.call(fn, emptyArray()) }
    }

    /** Клавиша раскладки с "action": "pluginId/команда". */
    fun runCommand(spec: String): Boolean {
        val m = mods[spec.substringBefore('/')] ?: return false
        val fn = m.commands[spec.substringAfter('/')] ?: return false
        exec(m, CALL_BUDGET_MS) { m.runtime.call(fn, emptyArray()) }
        return true
    }

    fun invoke(call: ScriptCall, vararg args: Any?) {
        val m = mods[call.pluginId]?.takeIf { it.runtime === call.runtime } ?: return
        exec(m, CALL_BUDGET_MS) {
            m.runtime.call(call.fn, args)
        }
    }

    fun render(panel: ScriptPanel): List<UiNode> {
        val m = mods[panel.pluginId] ?: return listOf(UiNode.Error("Плагин выключен"))
        val fn = m.panels[panel.id]?.second ?: return emptyList()
        return exec(m, CALL_BUDGET_MS) { parseNodes(m, m.runtime.call(fn, emptyArray(), readResult = true)) }
            .getOrElse { listOf(UiNode.Error(describe(it))) }
    }

    // ------------------------------------------------------------------ поле ввода мода

    private fun openPrompt(p: Prompt, title: String, hint: String, value: String) {
        prompt = p
        promptTitle = title
        promptHint = hint
        promptText = value
        bridge?.scriptPrompt(true)
    }

    /** Поле ввода из элемента input панели мода. */
    fun openInput(node: UiNode.Input) {
        val m = mods[node.onSubmit?.pluginId ?: node.onChange?.pluginId ?: return] ?: return
        openPrompt(Prompt(m, null, null, node.onSubmit, node.onChange), node.title, node.hint, node.value)
    }

    /** Клавиатура изменила текст в поле мода. */
    fun promptChanged(text: String) {
        promptText = text
        val p = prompt ?: return
        p.change?.let { invoke(it, text) }
        p.onChange?.let { fn -> exec(p.m, CALL_BUDGET_MS) { p.m.runtime.call(fn, arrayOf(text)) } }
    }

    /** ✓ или Enter. true — обработчик сразу открыл следующее поле. */
    fun promptSubmit(): Boolean {
        val p = prompt ?: return false
        prompt = null
        val text = promptText
        p.call?.let { invoke(it, text) }
        p.onSubmit?.let { fn -> exec(p.m, CALL_BUDGET_MS) { p.m.runtime.call(fn, arrayOf(text)) } }
        renderTick++
        return prompt != null
    }

    /** Поле закрыли без отправки: kb.prompt получает null. */
    fun promptClosed(): Boolean {
        val p = prompt ?: return false
        prompt = null
        p.onSubmit?.let { fn -> exec(p.m, CALL_BUDGET_MS) { p.m.runtime.call(fn, arrayOf(null)) } }
        return prompt != null
    }

    /** Пользователь поменял настройку мода в его карточке: событие "option" этому моду. */
    fun optionChanged(pluginId: String, id: String, value: Any?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { optionChanged(pluginId, id, value) }
            return
        }
        val m = mods[pluginId] ?: return
        val hs = m.handlers["option"] ?: return
        for (h in hs.toList()) exec(m, CALL_BUDGET_MS) {
            m.runtime.call(h, arrayOf(mapOf("id" to id, "value" to value)))
        }
        renderTick++
    }

    // ------------------------------------------------------------------ касания, устройство, сообщения

    /** Касание слоя мода (dp от угла клавиатуры); type — down, move, up. */
    fun touch(type: String, x: Float, y: Float, pointer: Int) {
        for (m in mods.values) {
            if (!m.touches) continue
            val hs = m.handlers["touch"] ?: continue
            for (h in hs.toList()) exec(m, CALL_BUDGET_MS) {
                m.runtime.call(h, arrayOf(mapOf("type" to type, "x" to x, "y" to y, "pointer" to pointer)))
            }
        }
    }

    private fun publishTouches() {
        touchCapture = mods.values.any { it.touches }
    }

    /** Кнопка ✕ поверх клавиатуры: снять перехват касаний у всех модов (им приходит touch с type "cancel"). */
    fun releaseTouches() {
        val held = mods.values.filter { it.touches }
        held.forEach { it.touches = false }
        publishTouches()
        for (m in held) {
            val hs = m.handlers["touch"] ?: continue
            for (h in hs.toList()) exec(m, CALL_BUDGET_MS) {
                m.runtime.call(h, arrayOf(mapOf("type" to "cancel", "x" to 0, "y" to 0, "pointer" to 0)))
            }
        }
    }

    /** Что мод может узнать об устройстве и экране — без личных данных. */
    fun device(): Map<String, Any?> {
        val ctx = appContext ?: return emptyMap()
        val cfg = ctx.resources.configuration
        val bat = runCatching { ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager }.getOrNull()
        return mapOf(
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "android" to android.os.Build.VERSION.RELEASE,
            "manufacturer" to android.os.Build.MANUFACTURER,
            "model" to android.os.Build.MODEL,
            "dark" to ((cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES),
            "landscape" to (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE),
            "screenWidth" to cfg.screenWidthDp,
            "screenHeight" to cfg.screenHeightDp,
            "locale" to cfg.locales[0].toLanguageTag(),
            "battery" to bat?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "charging" to (bat?.isCharging ?: false),
            "powerSave" to PowerState.saver,
            "appVersion" to runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull(),
            "time" to System.currentTimeMillis(),
            "timezone" to java.util.TimeZone.getDefault().id
        )
    }

    /** kb.broadcast: событие "message" во всех модах, кроме отправителя. */
    private fun broadcast(from: Mod, name: String, data: Any?) {
        main.post {
            for (m in mods.values) {
                if (m === from) continue
                val hs = m.handlers["message"] ?: continue
                for (h in hs.toList()) exec(m, CALL_BUDGET_MS) {
                    m.runtime.call(h, arrayOf(mapOf("from" to from.plugin.id, "name" to name, "data" to data)))
                }
            }
        }
    }

    // ------------------------------------------------------------------ звук и голос

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayList<Triple<String, String, Pair<Float, Float>>>()
    private var activeTones = 0

    private fun speak(ctx: Context, text: String, lang: String, rate: Float, pitch: Float) {
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(ctx) { status ->
                main.post {
                    ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                    if (ttsReady) pendingSpeech.forEach { (tx, l, rp) -> speakNow(tx, l, rp.first, rp.second) }
                    pendingSpeech.clear()
                }
            }
        }
        if (!ttsReady) {
            if (pendingSpeech.size < 20) pendingSpeech += Triple(text, lang, rate to pitch)
            return
        }
        speakNow(text, lang, rate, pitch)
    }

    private fun speakNow(text: String, lang: String, rate: Float, pitch: Float) {
        val t = tts ?: return
        if (lang.isNotEmpty()) runCatching { t.language = java.util.Locale.forLanguageTag(lang) }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        t.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "mod-${SystemClock.uptimeMillis()}")
    }

    /** Синтезированный звук: частота (Гц), длительность, громкость 0..1, форма волны. */
    private fun tone(freq: Double, ms: Int, volume: Float, wave: String) {
        if (activeTones >= 8) return
        activeTones++
        io.launch {
            try {
                val rate = 22050
                val n = (rate.toLong() * ms / 1000).toInt().coerceIn(1, rate * 5)
                val buf = ShortArray(n)
                val fadeIn = rate * 0.004
                val fadeOut = rate * 0.012
                for (i in 0 until n) {
                    val ph = (i * freq / rate) % 1.0
                    val v = when (wave) {
                        "square" -> if (ph < 0.5) 1.0 else -1.0
                        "saw" -> 2 * ph - 1
                        "triangle" -> 4 * kotlin.math.abs(ph - 0.5) - 1
                        "noise" -> Math.random() * 2 - 1
                        else -> kotlin.math.sin(2 * Math.PI * ph)
                    }
                    val env = minOf(1.0, i / fadeIn, (n - i) / fadeOut)
                    buf[i] = (v * env * volume * 0.6 * Short.MAX_VALUE).toInt().toShort()
                }
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                try {
                    track.write(buf, 0, n)
                    track.play()
                    kotlinx.coroutines.delay(ms + 80L)
                } finally {
                    track.release()
                }
            } finally {
                withContext(Dispatchers.Main) { activeTones-- }
            }
        }
    }

    /** Звук из data: URI или файла архива мода — во временный файл и в MediaPlayer. */
    private fun playAudio(ctx: Context, m: Mod, src: String, volume: Float, loop: Boolean): Int {
        require(m.players.size < 8) { "playAudio: не больше 8 звуков сразу" }
        val b64 = when {
            src.startsWith("data:") -> src.substringAfter(',')
            else -> m.plugin.files[src] ?: throw IllegalArgumentException("playAudio: нет файла «$src»")
        }
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        require(bytes.size < 8 * 1024 * 1024) { "playAudio: файл больше 8 МБ" }
        val dir = java.io.File(ctx.cacheDir, "mod_audio").apply { mkdirs() }
        val f = java.io.File(dir, "${m.plugin.id.hashCode()}_${src.hashCode()}.bin")
        if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        val pid = m.nextPlayer++
        val mp = android.media.MediaPlayer()
        mp.setAudioAttributes(
            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        mp.setDataSource(f.absolutePath)
        mp.isLooping = loop
        mp.setVolume(volume, volume)
        mp.setOnCompletionListener {
            if (!loop) {
                m.players.remove(pid)
                it.release()
            }
        }
        mp.setOnPreparedListener { it.start() }
        mp.prepareAsync()
        m.players[pid] = mp
        return pid
    }

    private fun haptic(ctx: Context, kind: String, pattern: LongArray?) {
        runCatching {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            val effect = when {
                pattern != null -> android.os.VibrationEffect.createWaveform(pattern, -1)
                android.os.Build.VERSION.SDK_INT >= 29 -> android.os.VibrationEffect.createPredefined(
                    when (kind) {
                        "click" -> android.os.VibrationEffect.EFFECT_CLICK
                        "heavy" -> android.os.VibrationEffect.EFFECT_HEAVY_CLICK
                        "double" -> android.os.VibrationEffect.EFFECT_DOUBLE_CLICK
                        else -> android.os.VibrationEffect.EFFECT_TICK
                    }
                )
                else -> android.os.VibrationEffect.createOneShot(if (kind == "heavy") 40L else 15L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vib.vibrate(effect)
        }
    }

    /** Файлы мода: своя папка, только простые имена, всего не больше 32 МБ. */
    private fun modDir(ctx: Context, m: Mod): java.io.File =
        java.io.File(java.io.File(ctx.filesDir, "plugin_files"), m.plugin.id.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }

    private fun modFile(ctx: Context, m: Mod, name: String): java.io.File {
        require(Regex("^[A-Za-z0-9._-]{1,100}$").matches(name) && name != "." && name != "..") {
            "files: имя файла — латиница, цифры, . _ - (до 100 символов)"
        }
        return java.io.File(modDir(ctx, m), name)
    }

    private fun checkQuota(ctx: Context, m: Mod, adding: Int, replacing: java.io.File) {
        val used = modDir(ctx, m).listFiles()?.filter { it != replacing }?.sumOf { it.length() } ?: 0L
        require(used + adding <= 32L * 1024 * 1024) { "files: у мода не больше 32 МБ файлов" }
    }

    private fun digest(algo: String, text: String): String =
        java.security.MessageDigest.getInstance(algo).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Клавиша по имени для kb.sendKey: "a", "f5", "tab", "page_up" или число — код Android. */
    private fun keyCodeOf(v: Any?): Int {
        if (v is Number) return v.toInt()
        val name = v?.toString()?.trim()?.lowercase().orEmpty()
        val alias = when (name) {
            "esc" -> "escape"
            "backspace" -> "del"
            "delete" -> "forward_del"
            "home" -> "move_home"
            "end" -> "move_end"
            "pageup" -> "page_up"
            "pagedown" -> "page_down"
            "return" -> "enter"
            " " -> "space"
            else -> name
        }
        val code = android.view.KeyEvent.keyCodeFromString("KEYCODE_" + alias.uppercase())
        require(code != android.view.KeyEvent.KEYCODE_UNKNOWN) { "sendKey: неизвестная клавиша «$name»" }
        return code
    }

    // ------------------------------------------------------------------ преобразования JS ⇄ Kotlin

    internal fun fromJs(v: Any?, depth: Int = 0): Any? = when {
        v == null || v is Undefined -> null
        v is CharSequence -> v.toString()
        v is Boolean -> v
        v is Number -> v.toDouble()
        v is org.mozilla.javascript.Function -> RhinoPluginFunction(v)
        v is PluginScriptFunction || v is Map<*, *> || v is List<*> -> v
        // глубже 32 уровней — почти наверняка объект ссылается сам на себя
        depth > 32 -> null
        v is NativeArray -> (0 until minOf(v.length, 100_000L).toInt()).map { fromJs(v.get(it, v), depth + 1) }
        v is Scriptable -> v.ids.associate { id ->
            id.toString() to fromJs(if (id is Number) v.get(id.toInt(), v) else v.get(id.toString(), v), depth + 1)
        }
        else -> v.toString()
    }

    private fun str(a: Array<Any?>, i: Int, def: String = ""): String =
        a.getOrNull(i)?.takeIf { it !is Undefined && it != null }?.let { Rhino.toString(it) } ?: def

    private fun num(a: Array<Any?>, i: Int, def: Double): Double =
        a.getOrNull(i)?.takeIf { it !is Undefined && it != null }?.let { Rhino.toNumber(it) }?.takeIf { !it.isNaN() } ?: def

    private fun opts(a: Array<Any?>, i: Int): Map<String, Any?> = (fromJs(a.getOrNull(i)) as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    private fun need(m: Mod, perm: String) {
        if (!m.has(perm)) throw IllegalStateException("нужно разрешение \"$perm\" в поле permissions")
    }

    // ------------------------------------------------------------------ API kb.*

    private fun bindings(m: Mod): Map<String, Any?> {
        val globals = linkedMapOf<String, Any?>()
        val ctx = appContext!!
        val id = m.plugin.id
        fun def(obj: MutableMap<String, Any?>, name: String, body: (Array<Any?>) -> Any?) {
            obj[name] = ScriptBinding(name, body)
        }
        fun b() = bridge

        val kb = linkedMapOf<String, Any?>("pluginId" to id, "apiVersion" to 4)
        // --- регистрация
        def(kb, "on") { a ->
            val ev = str(a, 0)
            val fn = a.getOrNull(1).scriptFunction() ?: throw IllegalArgumentException("kb.on(событие, функция)")
            m.handlers.getOrPut(ev) { ArrayList() } += fn
            main.post { publish() }
            Unit
        }
        def(kb, "off") { a -> m.handlers.remove(str(a, 0)); main.post { publish() }; Unit }
        def(kb, "addButton") { a ->
            val o = opts(a, 0)
            val title = o["title"]?.toString() ?: throw IllegalArgumentException("addButton: нужно title")
            val bid = o["id"]?.toString() ?: title
            m.buttons[bid] = ScriptButton(id, bid, title, o["icon"]?.toString() ?: "🔘", o["toolbar"] == true) to (o["onClick"].scriptFunction())
            main.post { publish() }
            bid
        }
        def(kb, "removeButton") { a -> m.buttons.remove(str(a, 0)); main.post { publish() }; Unit }
        def(kb, "addPanel") { a ->
            val o = opts(a, 0)
            val title = o["title"]?.toString() ?: throw IllegalArgumentException("addPanel: нужно title")
            val pid = o["id"]?.toString() ?: title
            m.panels[pid] = ScriptPanel(id, pid, title, o["icon"]?.toString() ?: m.plugin.icon) to (o["render"].scriptFunction())
            main.post { publish() }
            pid
        }
        def(kb, "addCommand") { a ->
            val fn = a.getOrNull(1).scriptFunction() ?: throw IllegalArgumentException("kb.addCommand(имя, функция)")
            m.commands[str(a, 0)] = fn
            Unit
        }
        def(kb, "refresh") { _ -> main.post { renderTick++ }; Unit }

        // --- текст
        def(kb, "commit") { a -> b()?.scriptCommit(str(a, 0)); Unit }
        def(kb, "insert") { a -> b()?.scriptCommit(str(a, 0)); Unit }
        def(kb, "deleteBefore") { a -> b()?.scriptDelete(num(a, 0, 1.0).toInt().coerceIn(0, 5_000), 0); Unit }
        def(kb, "deleteAfter") { a -> b()?.scriptDelete(0, num(a, 0, 1.0).toInt().coerceIn(0, 100_000)); Unit }
        def(kb, "replaceBefore") { a ->
            b()?.scriptDelete(num(a, 0, 0.0).toInt().coerceIn(0, 100_000), 0)
            b()?.scriptCommit(str(a, 1))
            Unit
        }
        def(kb, "textBefore") { a -> need(m, "read_text"); if (privateMode) "" else b()?.scriptTextBefore(num(a, 0, 200.0).toInt().coerceIn(0, 5_000)).orEmpty() }
        def(kb, "textAfter") { a -> need(m, "read_text"); if (privateMode) "" else b()?.scriptTextAfter(num(a, 0, 200.0).toInt().coerceIn(0, 5_000)).orEmpty() }
        def(kb, "selectedText") { _ -> need(m, "read_text"); b()?.scriptSelected().orEmpty() }
        def(kb, "currentWord") { _ ->
            need(m, "read_text")
            Regex("[\\p{L}\\p{M}\\p{N}'_-]+$").find(b()?.scriptTextBefore(64).orEmpty())?.value.orEmpty()
        }
        def(kb, "setSelection") { a -> b()?.scriptSetSelection(num(a, 0, 0.0).toInt(), num(a, 1, num(a, 0, 0.0)).toInt()); Unit }
        def(kb, "key") { a -> b()?.scriptKey(str(a, 0).lowercase()); Unit }
        def(kb, "enter") { _ -> b()?.scriptKey("enter"); Unit }

        // --- состояние клавиатуры
        def(kb, "language") { _ -> b()?.scriptLanguage() ?: "" }
        def(kb, "setLanguage") { a -> b()?.scriptSetLanguage(str(a, 0)) ?: false }
        def(kb, "languages") { _ -> Languages.enabled(KeyboardPrefs.current(ctx)).map { it.id } }
        def(kb, "shift") { _ -> b()?.scriptShift() ?: "off" }
        def(kb, "setShift") { a -> b()?.scriptSetShift(str(a, 0)); Unit }
        // приложение и подсказка поля выдают, где человек печатает (банк, мессенджер) — только с read_text
        def(kb, "field") { _ ->
            val f = b()?.scriptField() ?: emptyMap<String, Any?>()
            if (m.has("read_text") && !privateMode) f else f - setOf("package", "hint")
        }
        def(kb, "openPanel") { a -> b()?.scriptOpenPanel(str(a, 0, "keyboard")); Unit }
        def(kb, "closePanel") { _ -> b()?.scriptOpenPanel("keyboard"); Unit }
        def(kb, "hide") { _ -> b()?.scriptHide(); Unit }
        def(kb, "status") { a -> b()?.scriptStatus(str(a, 0)); Unit }
        def(kb, "toast") { a -> Toast.makeText(ctx, str(a, 0), Toast.LENGTH_SHORT).show(); Unit }
        def(kb, "vibrate") { a -> b()?.scriptVibrate(num(a, 0, 20.0).toInt().coerceIn(1, 400)); Unit }

        // --- слой поверх клавиатуры, клавиши и анимация
        def(kb, "overlay") { a ->
            m.overlay = parseItems(m, fromJs(a.getOrNull(0)))
            publishOverlay()
            Unit
        }
        def(kb, "clearOverlay") { _ -> m.overlay = emptyList(); publishOverlay(); Unit }
        // то же, но под клавишами: фон, волны, частицы — клавиши остаются сверху
        def(kb, "background") { a ->
            m.background = parseItems(m, fromJs(a.getOrNull(0)))
            publishOverlay()
            Unit
        }
        def(kb, "clearBackground") { _ -> m.background = emptyList(); publishOverlay(); Unit }
        // --- наклон и эффекты клавиш, шейдер на всю клавиатуру
        def(kb, "keyEffects") { a ->
            val v = fromJs(a.getOrNull(0)) as? Map<*, *>
            m.keyFx = v?.entries?.take(200)?.mapNotNull { (k, x) -> (x as? Map<*, *>)?.let { k.toString() to KeyFx.parse(it) } }?.toMap() ?: emptyMap()
            publishKeyFx()
            Unit
        }
        def(kb, "keyboardEffect") { a ->
            ModFx.keyboardFx = (fromJs(a.getOrNull(0)) as? Map<*, *>)?.let { KeyFx.parse(it) }
            Unit
        }
        def(kb, "postEffect") { a ->
            val o = fromJs(a.getOrNull(0)) as? Map<*, *>
            if (o == null) {
                ModFx.post = null
                syncDevices()
                return@def true
            }
            val src = o["src"]?.toString().orEmpty()
            val prog = ModShaders.program(src)
            if (prog == null || !prog.hasContent) {
                log(id, "✕ postEffect: " + (ModShaders.error(src) ?: if (prog == null) tr("нужен Android 13 или новее") else tr("в шейдере нужен uniform shader content")))
                ModFx.post = null
                syncDevices()
                return@def false
            }
            ModFx.post = PostEffect(prog, (o["uniforms"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())
            syncDevices()
            true
        }
        def(kb, "shaderError") { a -> ModShaders.error(str(a, 0)) }
        def(kb, "glslError") { a -> PluginGl.error(str(a, 0)) }
        def(kb, "supports") { a ->
            when (str(a, 0)) {
                "shader", "postEffect" -> ModShaders.supported
                "glsl", "mesh3d" -> true
                "labelBlur" -> android.os.Build.VERSION.SDK_INT >= 31
                "particles", "keyEffects", "pointer" -> true
                "sensors" -> ModSensors.available(ctx)
                "microphone" -> m.has("microphone") && ModAudio.hasPermission(ctx)
                else -> false
            }
        }

        // --- частицы: считаются клавиатурой, а не скриптом
        val particles = linkedMapOf<String, Any?>()
        fun sysKey(name: String) = "$id/$name"
        def(particles, "set") { a ->
            val name = str(a, 0, "default")
            require(ModFx.system(sysKey(name)) != null || ModFx.systemsOf("$id/") < 8) { "particles: не больше 8 систем у мода" }
            ModFx.setSystem(sysKey(name), ParticleConfig.parse(opts(a, 1)))
            syncDevices()
            Unit
        }
        def(particles, "emit") { a ->
            val sys = ModFx.system(sysKey(str(a, 0, "default"))) ?: throw IllegalArgumentException("particles.emit: сначала particles.set")
            val o = opts(a, 1)
            fun f(k: String, def: Float) = (o[k] as? Number)?.toFloat() ?: def
            sys.emit(f("count", 10f).toInt(), f("x", 0f), f("y", 0f), f("w", 0f), f("h", 0f), f("speed", 0f), f("angle", 0f), f("spread", 360f))
            Unit
        }
        def(particles, "clear") { a -> ModFx.system(sysKey(str(a, 0, "default")))?.clear(); Unit }
        def(particles, "remove") { a -> ModFx.removeSystem(sysKey(str(a, 0, "default"))); syncDevices(); Unit }
        def(particles, "count") { a -> ModFx.system(sysKey(str(a, 0, "default")))?.count ?: 0 }
        kb["particles"] = particles

        // --- микрофон: только громкость и полосы частот, не сам звук
        val audio = linkedMapOf<String, Any?>()
        def(audio, "start") { _ ->
            need(m, "microphone")
            if (!ModAudio.hasPermission(ctx)) return@def false
            m.audio = true
            syncDevices()
            true
        }
        def(audio, "stop") { _ -> m.audio = false; syncDevices(); Unit }
        def(audio, "level") { _ -> if (m.audio && m.has("microphone") && !privateMode) ModAudio.level else 0f }
        kb["audio"] = audio

        def(kb, "tilt") { _ ->
            need(m, "sensors")
            if (privateMode) mapOf("x" to 0f, "y" to 0f, "z" to 0f)
            else mapOf("x" to ModSensors.tiltX, "y" to ModSensors.tiltY, "z" to ModSensors.tiltZ)
        }
        def(kb, "appInfo") { _ ->
            need(m, "app_info")
            val pkg = lastApp
            if (privateMode || pkg.isEmpty()) emptyMap<String, Any?>() else appInfoOf(ctx, pkg)
        }

        def(kb, "keyboardSize") { _ ->
            val sz = KeyboardGeometry.size()
            mapOf("width" to (sz?.width ?: 0f), "height" to (sz?.height ?: 0f))
        }
        def(kb, "keys") { _ ->
            KeyboardGeometry.visible().mapNotNull { (h, r) ->
                val name = KeyboardGeometry.nameOf(h)
                if (name.isEmpty()) null
                else mapOf(
                    "label" to name, "type" to h.key.type.name.lowercase(), "x" to r.left, "y" to r.top,
                    "w" to r.width, "h" to r.height, "cx" to r.center.x, "cy" to r.center.y
                )
            }
        }
        def(kb, "keyRect") { a ->
            val h = KeyboardGeometry.find(str(a, 0)) ?: return@def null
            val r = KeyboardGeometry.rectOf(h) ?: return@def null
            mapOf("x" to r.left, "y" to r.top, "w" to r.width, "h" to r.height, "cx" to r.center.x, "cy" to r.center.y)
        }
        def(kb, "pressKey") { a ->
            val h = KeyboardGeometry.find(str(a, 0)) ?: return@def false
            h.pulse()
            if (opts(a, 1)["type"] != false) {
                val k = h.key
                val text = when (k.type) {
                    KeyType.CHARACTER -> k.text(b()?.scriptShift().let { it != null && it != "off" })
                    KeyType.SPACE -> " "
                    else -> ""
                }
                KeyboardGeometry.actions?.commitKey(k, text)
            }
            true
        }
        def(kb, "colors") { _ ->
            val p = KeyboardGeometry.palette ?: return@def emptyMap<String, Any?>()
            fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%08X", c.toArgb())
            mapOf(
                "background" to hex(p.background), "key" to hex(p.letter), "keyText" to hex(p.onLetter),
                "func" to hex(p.func), "funcText" to hex(p.onFunc), "accent" to hex(p.accent), "accentText" to hex(p.onAccent)
            )
        }
        def(kb, "setLayout") { a ->
            val lid = str(a, 0)
            require(lid in PluginParser.LAYOUT_IDS || Regex("^[a-z]{2,3}([-_][a-z0-9]{2,8})?$").matches(lid)) { "setLayout: код языка, symbols или symbols2" }
            val rows = fromJs(a.getOrNull(1)) as? List<*>
            if (rows == null) m.layouts.remove(lid)
            else m.layouts[lid] = rows.take(8).mapNotNull { r ->
                (r as? List<*>)?.let { keys ->
                    KeyboardLayouts.parseRow(JSONArray(keys.map { x -> if (x is Map<*, *>) JSONObject(x) else x }), lid !in PluginParser.LAYOUT_IDS, id)
                }
            }.filter { it.keys.isNotEmpty() }
            publish()
            Unit
        }
        def(kb, "setSkin") { a ->
            val v = fromJs(a.getOrNull(0)) as? Map<*, *>
            m.skin = v?.let { SkinParser.parse(JSONObject(it)) }
            publish()
            Unit
        }

        // --- буфер обмена
        def(kb, "clipboard") { _ -> need(m, "clipboard"); b()?.scriptClipboard() ?: emptyList<String>() }
        // запись в буфер обмена — тоже только с разрешением: иначе мод мог бы незаметно подменить скопированное
        def(kb, "copy") { a -> need(m, "clipboard"); b()?.scriptCopy(str(a, 0).take(100_000)); Unit }

        // --- иконки
        def(kb, "setIcon") { a -> IconStore.setPluginIcon(id, str(a, 0), str(a, 1)); Unit }
        def(kb, "resetIcon") { a -> IconStore.setPluginIcon(id, str(a, 0), ""); Unit }

        // --- файлы из архива плагина
        def(kb, "asset") { a ->
            val path = str(a, 0)
            val data = m.plugin.files[path] ?: return@def null
            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".webp") || path.endsWith(".gif")) {
                "data:image/${path.substringAfterLast('.')};base64,$data"
            } else String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
        }

        // --- хранилище плагина (переживает перезапуск)
        val prefs = ctx.getSharedPreferences("plugin_storage_$id", Context.MODE_PRIVATE)
        val storage = linkedMapOf<String, Any?>()
        def(storage, "get") { a -> prefs.getString(str(a, 0), null) }
        def(storage, "set") { a ->
            val raw = str(a, 1, "undefined")
            require(raw.length < 256 * 1024) { "storage: значение больше 256 КБ" }
            prefs.edit().putString(str(a, 0), raw).apply()
            Unit
        }
        def(storage, "remove") { a -> prefs.edit().remove(str(a, 0)).apply(); Unit }
        def(storage, "keys") { _ -> prefs.all.keys.toList() }
        def(storage, "clear") { _ -> prefs.edit().clear().apply(); Unit }
        kb["storage"] = storage

        // --- настройки клавиатуры
        val settings = linkedMapOf<String, Any?>()
        def(settings, "get") { a -> KeyboardPrefs.prefs(ctx).all[str(a, 0)] }
        def(settings, "set") { a ->
            val v = fromJs(a.getOrNull(1)) ?: return@def false
            KeyboardPrefs.applyPluginSettings(ctx, mapOf(str(a, 0) to v)) > 0
        }
        kb["settings"] = settings

        // --- сеть
        def(kb, "http") { a ->
            need(m, "network")
            val o = opts(a, 0)
            val cb = a.getOrNull(1).scriptFunction()
            val url = o["url"]?.toString() ?: throw IllegalArgumentException("http: нужно url")
            require(url.startsWith("https://")) { "http: разрешены только https-адреса" }
            require(!isPrivateHost(url)) { "http: адреса домашней сети и самого телефона недоступны" }
            val now = SystemClock.uptimeMillis()
            while (m.httpTimes.isNotEmpty() && now - m.httpTimes.first() > 60_000L) m.httpTimes.removeFirst()
            require(m.httpTimes.size < 60) { "http: не больше 60 запросов в минуту" }
            m.httpTimes.addLast(now)
            io.launch {
                val r = runCatching { httpCall(url, o) }
                withContext(Dispatchers.Main) {
                    if (cb == null || mods[id] !== m) return@withContext
                    exec(m, CALL_BUDGET_MS) {
                        r.fold(
                            onSuccess = { (code, body) ->
                                val b64 = o["responseType"] == "base64"
                                val res = linkedMapOf<String, Any?>(
                                    "status" to code, (if (b64) "base64" else "text") to body, "ok" to (code in 200..299)
                                )
                                if (!b64) res["json"] = ScriptJson(body)
                                m.runtime.call(cb, arrayOf(null, res))
                            },
                            onFailure = { e -> m.runtime.call(cb, arrayOf(e.message ?: "ошибка сети", null)) }
                        )
                    }
                }
            }
            Unit
        }
        def(kb, "translate") { a ->
            need(m, "network")
            val text = str(a, 0)
            val to = str(a, 1, "en")
            val cb = a.getOrNull(2).scriptFunction()
            io.launch {
                val r = Translator.translate(text, "auto", to)
                withContext(Dispatchers.Main) {
                    if (cb == null || mods[id] !== m) return@withContext
                    exec(m, CALL_BUDGET_MS) { m.runtime.call(cb, arrayOf(r.exceptionOrNull()?.message, r.getOrNull())) }
                }
            }
            Unit
        }

        // --- журнал
        val logFn: (Array<Any?>) -> Any? = { a -> log(id, a.joinToString(" ") { v -> if (v is Undefined) "undefined" else Rhino.toString(v) }); Unit }
        def(kb, "log", logFn)
        val console = linkedMapOf<String, Any?>()
        def(console, "log", logFn)
        def(console, "warn", logFn)
        def(console, "error", logFn)
        globals["console"] = console

        // --- таймеры
        fun timer(a: Array<Any?>, repeat: Boolean): Int {
            val fn = a.getOrNull(0).scriptFunction() ?: throw IllegalArgumentException("нужна функция")
            require(m.timers.size < MAX_TIMERS) { "слишком много таймеров" }
            require(mods.values.sumOf { it.timers.size } < MAX_TIMERS * 2) { "слишком много таймеров у всех модов вместе" }
            val delay = num(a, 1, 0.0).toLong().coerceIn(if (repeat) 50L else 0L, 24 * 3600_000L)
            val tid = m.nextTimer++
            val r = object : Runnable {
                override fun run() {
                    if (mods[id] !== m) return
                    if (!visible) {
                        // клавиатура скрыта: не выполняем мод и не перезапускаем таймер — продолжит при показе
                        if (m.timers.containsKey(tid)) m.parked += this
                        return
                    }
                    if (!repeat) m.timers.remove(tid)
                    exec(m, CALL_BUDGET_MS) { m.runtime.call(fn, emptyArray()) }
                    if (repeat && m.timers.containsKey(tid)) main.postDelayed(this, delay)
                }
            }
            m.timers[tid] = r
            main.postDelayed(r, delay)
            return tid
        }
        val clear: (Array<Any?>) -> Any? = { a -> m.timers.remove(num(a, 0, -1.0).toInt())?.let(main::removeCallbacks); Unit }
        def(globals, "setTimeout") { a -> timer(a, false) }
        def(globals, "setInterval") { a -> timer(a, true) }
        def(globals, "clearTimeout", clear)
        def(globals, "clearInterval", clear)

        // ================================================================ API 3: всё остальное

        // --- свой набор: «подчёркнутое» слово, курсор, весь текст, настоящие клавиши
        def(kb, "setComposing") { a -> b()?.scriptSetComposing(str(a, 0).take(5_000)); Unit }
        def(kb, "finishComposing") { _ -> b()?.scriptFinishComposing(); Unit }
        def(kb, "cursor") { _ ->
            need(m, "read_text")
            val c = b()?.scriptCursor().orEmpty()
            if (c.size < 2 || privateMode) null else mapOf("start" to c[0], "end" to c[1])
        }
        def(kb, "allText") { _ -> need(m, "read_text"); if (privateMode) "" else b()?.scriptAllText().orEmpty() }
        def(kb, "replaceSelection") { a -> b()?.scriptReplaceSelection(str(a, 0).take(1_000_000)); Unit }
        def(kb, "replaceAll") { a -> b()?.scriptReplaceAll(str(a, 0).take(1_000_000)); Unit }
        def(kb, "sendKey") { a ->
            val o = opts(a, 1)
            var meta = 0
            if (o["ctrl"] == true) meta = meta or android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
            if (o["alt"] == true) meta = meta or android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON
            if (o["shift"] == true) meta = meta or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
            if (o["meta"] == true) meta = meta or android.view.KeyEvent.META_META_ON or android.view.KeyEvent.META_META_LEFT_ON
            b()?.scriptSendKey(keyCodeOf(fromJs(a.getOrNull(0))), meta)
            Unit
        }
        def(kb, "editorAction") { a -> b()?.scriptEditorAction(str(a, 0, "done")); Unit }
        def(kb, "commitImage") { a ->
            val src = str(a, 0)
            val data = if (src.startsWith("data:")) src else m.plugin.files[src]?.let { "data:image/${src.substringAfterLast('.')};base64,$it" }
                ?: throw IllegalArgumentException("commitImage: data:image/… или файл из архива мода")
            val mime = data.substringAfter("data:").substringBefore(';').ifEmpty { "image/png" }
            val bytes = android.util.Base64.decode(data.substringAfter(','), android.util.Base64.DEFAULT)
            require(bytes.size < 16 * 1024 * 1024) { "commitImage: картинка больше 16 МБ" }
            b()?.scriptCommitImage(bytes, if (mime == "image/jpg") "image/jpeg" else mime) ?: false
        }
        // подсказки со своим обработчиком: так моды делают свои способы ввода, меню и автодополнение
        def(kb, "setSuggestions") { a ->
            val words = (fromJs(a.getOrNull(0)) as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val fn = a.getOrNull(1).scriptFunction()
            val pick: ((String, Int) -> Unit)? = fn?.let { f ->
                { w: String, i: Int ->
                    if (mods[id] === m) exec(m, CALL_BUDGET_MS) { m.runtime.call(f, arrayOf(w, i.toDouble())) }
                }
            }
            b()?.scriptSuggestions(words, pick)
            Unit
        }

        // --- поле ввода мода: клавиатура печатает в него вместо приложения
        def(kb, "prompt") { a ->
            val o = opts(a, 0)
            val fn = a.getOrNull(1).scriptFunction() ?: (o["onSubmit"].scriptFunction())
            openPrompt(
                Prompt(m, fn, o["onChange"].scriptFunction(), null, null),
                o["title"]?.toString().orEmpty(), o["hint"]?.toString().orEmpty(), o["value"]?.toString().orEmpty()
            )
            Unit
        }
        def(kb, "closePrompt") { _ ->
            if (prompt?.m === m) {
                promptClosed()
                bridge?.scriptPrompt(false)
            }
            Unit
        }

        // --- касания клавиатуры: игры и рисование поверх клавиш (пока включено, клавиши не нажимаются)
        def(kb, "captureTouches") { a ->
            m.touches = a.getOrNull(0).let { it == null || it is Undefined || Rhino.toBoolean(it) }
            publishTouches()
            Unit
        }

        // --- устройство и моды
        def(kb, "device") { _ -> device() }
        // свои настройки мода из блока "config": kb.config("id") или kb.config() — все сразу
        def(kb, "config") { a ->
            val key = str(a, 0)
            if (key.isEmpty()) ModConfig.all(ctx, m.plugin)
            else m.plugin.config.firstOrNull { it.id == key }?.let { ModConfig.get(ctx, m.plugin, it) }
        }
        def(kb, "setConfig") { a ->
            val key = str(a, 0)
            require(m.plugin.config.any { it.id == key }) { "setConfig: нет настройки «$key» в блоке config" }
            ModConfig.set(ctx, m.plugin, key, fromJs(a.getOrNull(1)))
            Unit
        }
        // несколько настроек разом (свой пресет): карточка мода сразу покажет новые значения
        def(kb, "setConfigs") { a ->
            val v = fromJs(a.getOrNull(0)) as? Map<*, *> ?: throw IllegalArgumentException("setConfigs: нужен объект")
            ModConfig.setAll(ctx, m.plugin, v.entries.associate { it.key.toString() to it.value })
        }
        def(kb, "mods") { _ -> mods.values.map { mapOf("id" to it.plugin.id, "name" to it.plugin.name, "version" to it.plugin.version) } }
        def(kb, "broadcast") { a -> broadcast(m, str(a, 0), fromJs(a.getOrNull(1))); Unit }

        // --- звук, вибрация, голос
        def(kb, "tone") { a ->
            val o = opts(a, 2)
            tone(num(a, 0, 440.0).coerceIn(20.0, 20_000.0), num(a, 1, 120.0).toInt().coerceIn(5, 5000),
                (o["volume"] as? Double)?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f, o["wave"]?.toString() ?: "sine")
            Unit
        }
        def(kb, "playAudio") { a ->
            val o = opts(a, 1)
            playAudio(ctx, m, str(a, 0), (o["volume"] as? Double)?.toFloat()?.coerceIn(0f, 1f) ?: 1f, o["loop"] == true)
        }
        def(kb, "stopAudio") { a ->
            val pid = num(a, 0, -1.0).toInt()
            val list = if (pid < 0) m.players.keys.toList() else listOf(pid)
            list.forEach { k -> m.players.remove(k)?.let { runCatching { it.stop(); it.release() } } }
            Unit
        }
        def(kb, "sound") { a ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val fx = when (str(a, 0)) {
                "space" -> android.media.AudioManager.FX_KEYPRESS_SPACEBAR
                "delete" -> android.media.AudioManager.FX_KEYPRESS_DELETE
                "enter", "return" -> android.media.AudioManager.FX_KEYPRESS_RETURN
                "click" -> android.media.AudioManager.FX_KEY_CLICK
                else -> android.media.AudioManager.FX_KEYPRESS_STANDARD
            }
            am.playSoundEffect(fx, num(a, 1, 0.6).toFloat().coerceIn(0f, 1f))
            Unit
        }
        def(kb, "haptic") { a ->
            val v = fromJs(a.getOrNull(0))
            val pattern = (v as? List<*>)?.mapNotNull { (it as? Double)?.toLong()?.coerceIn(0L, 2000L) }?.take(40)?.toLongArray()
            if (pattern != null) require(pattern.sum() <= 6000L) { "haptic: узор не длиннее 6 секунд" }
            haptic(ctx, v?.toString() ?: "tick", pattern)
            Unit
        }
        def(kb, "speak") { a ->
            val o = opts(a, 1)
            speak(ctx, str(a, 0).take(4000), o["lang"]?.toString() ?: (b()?.scriptLanguage() ?: ""),
                (o["rate"] as? Double)?.toFloat()?.coerceIn(0.1f, 4f) ?: 1f, (o["pitch"] as? Double)?.toFloat()?.coerceIn(0.1f, 4f) ?: 1f)
            Unit
        }
        def(kb, "stopSpeaking") { _ -> tts?.stop(); Unit }

        // --- ссылки, приложения, «Поделиться»
        fun start(i: android.content.Intent): Boolean = runCatching {
            ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        def(kb, "openUrl") { a ->
            need(m, "launch")
            val url = str(a, 0)
            val scheme = url.substringBefore(':').lowercase()
            require(scheme in setOf("https", "http", "mailto", "tel", "sms", "geo", "market", "tg", "whatsapp")) { "openUrl: такая ссылка не открывается" }
            start(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
        def(kb, "share") { a ->
            need(m, "launch")
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, str(a, 0).take(100_000))
            start(android.content.Intent.createChooser(send, null))
        }
        def(kb, "launchApp") { a ->
            need(m, "launch")
            val i = ctx.packageManager.getLaunchIntentForPackage(str(a, 0)) ?: return@def false
            start(i)
        }

        // --- ИИ по ключу пользователя (Gemini, Claude, OpenAI, Groq — что настроено)
        def(kb, "ai") { a ->
            need(m, "ai")
            val o = opts(a, 0)
            val cb = a.getOrNull(1).scriptFunction()
            val promptText = o["prompt"]?.toString() ?: throw IllegalArgumentException("ai: нужно prompt")
            val now = SystemClock.uptimeMillis()
            while (m.aiTimes.isNotEmpty() && now - m.aiTimes.first() > 60_000L) m.aiTimes.removeFirst()
            require(m.aiTimes.size < 10) { "ai: не больше 10 запросов в минуту" }
            m.aiTimes.addLast(now)
            val st = KeyboardPrefs.current(ctx)
            val keys = AiKeys.of(st)
            val lang = (b()?.scriptLanguage() ?: "en").substringBefore('-')
            io.launch {
                val r = AiFix.run(ctx, o["text"]?.toString().orEmpty().take(8000), keys, lang, AiTask.CUSTOM, promptText.take(4000))
                withContext(Dispatchers.Main) {
                    if (cb == null || mods[id] !== m) return@withContext
                    exec(m, CALL_BUDGET_MS) { m.runtime.call(cb, arrayOf(r.exceptionOrNull()?.message, r.getOrNull()?.first)) }
                }
            }
            Unit
        }

        // --- словарь и подсказки клавиатуры
        val dict = linkedMapOf<String, Any?>()
        fun langOf(a: Array<Any?>, i: Int): KeyboardLanguage {
            val lid = str(a, i).ifEmpty { b()?.scriptLanguage().orEmpty() }
            return Languages.withUser(Languages.byId(lid) ?: Languages.byId(b()?.scriptLanguage().orEmpty()) ?: KeyboardLanguage.RU)
        }
        def(dict, "predict") { a ->
            val lang = langOf(a, 2)
            WordPredictorEngine.predict(str(a, 0), str(a, 1), null, lang, false)
                ?.chips?.filter { !it.isEmpty }?.map { it.commitWord }.orEmpty()
        }
        def(dict, "correct") { a -> WordPredictorEngine.autoCorrectionFor(str(a, 0), langOf(a, 1), "SAFE") }
        def(dict, "learn") { a -> if (!privateMode) WordPredictorEngine.learn(langOf(a, 1), "", str(a, 0).take(64)); Unit }
        def(dict, "forget") { a -> WordPredictorEngine.forget(langOf(a, 1), str(a, 0)); Unit }
        def(dict, "nextLetters") { a ->
            WordPredictorEngine.nextLetters(langOf(a, 1), str(a, 0)).entries.sortedByDescending { it.value }.take(30)
                .associate { it.key.toString() to it.value.toDouble() }
        }
        kb["dictionary"] = dict

        // --- эмодзи
        val emoji = linkedMapOf<String, Any?>()
        def(emoji, "search") { a ->
            EmojiCatalog.search(str(a, 0), num(a, 1, 40.0).toInt().coerceIn(1, 500)).map { mapOf("emoji" to it.emoji, "name" to it.nameEn) }
        }
        def(emoji, "recent") { _ -> EmojiRecents.items }
        kb["emoji"] = emoji

        // --- файлы мода (своя папка, до 32 МБ)
        val files = linkedMapOf<String, Any?>()
        def(files, "write") { a ->
            val f = modFile(ctx, m, str(a, 0))
            val bytes = str(a, 1).toByteArray()
            checkQuota(ctx, m, bytes.size, f)
            f.writeBytes(bytes)
            Unit
        }
        def(files, "append") { a ->
            val f = modFile(ctx, m, str(a, 0))
            val bytes = str(a, 1).toByteArray()
            checkQuota(ctx, m, (f.length() + bytes.size).toInt(), f)
            f.appendBytes(bytes)
            Unit
        }
        def(files, "read") { a -> modFile(ctx, m, str(a, 0)).takeIf { it.exists() }?.readText() }
        def(files, "writeBase64") { a ->
            val f = modFile(ctx, m, str(a, 0))
            val bytes = android.util.Base64.decode(str(a, 1).substringAfter("base64,"), android.util.Base64.DEFAULT)
            checkQuota(ctx, m, bytes.size, f)
            f.writeBytes(bytes)
            Unit
        }
        def(files, "readBase64") { a ->
            modFile(ctx, m, str(a, 0)).takeIf { it.exists() }?.let { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }
        }
        def(files, "exists") { a -> modFile(ctx, m, str(a, 0)).exists() }
        def(files, "delete") { a -> modFile(ctx, m, str(a, 0)).delete() }
        def(files, "list") { _ ->
            modDir(ctx, m).listFiles()?.map { mapOf("name" to it.name, "size" to it.length(), "modified" to it.lastModified()) }.orEmpty()
        }
        kb["files"] = files

        // --- полезное: хэши, base64, uuid, даты
        val util = linkedMapOf<String, Any?>()
        def(util, "sha256") { a -> digest("SHA-256", str(a, 0)) }
        def(util, "sha1") { a -> digest("SHA-1", str(a, 0)) }
        def(util, "md5") { a -> digest("MD5", str(a, 0)) }
        def(util, "base64Encode") { a -> android.util.Base64.encodeToString(str(a, 0).toByteArray(), android.util.Base64.NO_WRAP) }
        def(util, "base64Decode") { a -> String(android.util.Base64.decode(str(a, 0), android.util.Base64.DEFAULT)) }
        def(util, "urlEncode") { a -> java.net.URLEncoder.encode(str(a, 0), "UTF-8") }
        def(util, "urlDecode") { a -> java.net.URLDecoder.decode(str(a, 0), "UTF-8") }
        def(util, "uuid") { _ -> java.util.UUID.randomUUID().toString() }
        def(util, "now") { _ -> System.currentTimeMillis().toDouble() }
        def(util, "formatDate") { a ->
            java.text.SimpleDateFormat(str(a, 1, "yyyy-MM-dd HH:mm"), java.util.Locale.getDefault()).format(java.util.Date(num(a, 0, System.currentTimeMillis().toDouble()).toLong()))
        }
        // все преобразования действий transform: upper, translit, layout_swap, calc, bold, …
        def(util, "transform") { a ->
            val o = opts(a, 2)
            val name = str(a, 0)
            require(name in PluginParser.TRANSFORMS) { "transform: неизвестное преобразование «$name»" }
            PluginTransforms.apply(
                PluginAction("", "", PluginActionType.TRANSFORM, transform = name, prefix = o["prefix"]?.toString().orEmpty(),
                    suffix = o["suffix"]?.toString().orEmpty(), pattern = o["pattern"]?.toString().orEmpty(),
                    replacement = o["replacement"]?.toString().orEmpty()),
                str(a, 1)
            )
        }
        kb["util"] = util
        def(globals, "btoa") { a -> android.util.Base64.encodeToString(str(a, 0).toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP) }
        def(globals, "atob") { a -> String(android.util.Base64.decode(str(a, 0), android.util.Base64.DEFAULT), Charsets.ISO_8859_1) }

        globals["kb"] = kb
        return globals
    }

    /** Роутер, сам телефон, локальная сеть: моды не должны сканировать домашнюю сеть. */
    internal fun isPrivateHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return true
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".lan")) return true
        val isIpLiteral = host.all { it.isDigit() || it == '.' } || (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' })
        if (!isIpLiteral) return false
        val addr = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return true
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress ||
            addr.isMulticastAddress || (addr is java.net.Inet6Address && (addr.address[0].toInt() and 0xFE) == 0xFC)
    }

    private fun httpCall(url: String, o: Map<String, Any?>): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // перенаправление могло бы увести запрос в домашнюю сеть в обход проверки адреса
            conn.instanceFollowRedirects = false
            val method = o["method"]?.toString()?.uppercase() ?: "GET"
            conn.requestMethod = if (method in setOf("GET", "POST", "PUT", "DELETE", "PATCH")) method else "GET"
            conn.setRequestProperty("User-Agent", "ExpressiveKeyboard-Plugin")
            (o["headers"] as? Map<*, *>)?.forEach { (k, v) -> conn.setRequestProperty(k.toString(), v.toString()) }
            val body = o["body"]?.let { if (it is Map<*, *> || it is List<*>) JSONObject.wrap(it).toString() else it.toString() }
            if (body != null && conn.requestMethod != "GET") {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            // картинки и прочие файлы — base64 (до 10 МБ)
            if (o["responseType"] == "base64") {
                val bytes = stream?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < 10 * 1024 * 1024) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                return code to android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
            val text = stream?.bufferedReader()?.use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < 1_000_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ панели скриптов

    private fun parseNodes(m: Mod, raw: Any?): List<UiNode> {
        val list = when (val v = fromJs(raw)) {
            is List<*> -> v
            null -> emptyList<Any?>()
            else -> listOf(v)
        }
        return list.mapNotNull { node(m, it) }
    }

    private fun call(m: Mod, v: Any?) = (v.scriptFunction())?.let { ScriptCall(m.plugin.id, it, m.runtime) }

    private fun node(m: Mod, v: Any?): UiNode? {
        if (v is String) return UiNode.Text(v, 14f, false, null, false)
        val o = v as? Map<*, *> ?: return null
        fun s(k: String, def: String = "") = o[k]?.toString() ?: def
        fun f(k: String, def: Float) = (o[k] as? Double)?.toFloat() ?: def
        fun kids() = (o["children"] as? List<*>)?.mapNotNull { node(m, it) } ?: emptyList()
        fun color(k: String) = PluginParser.parseColor(o[k]?.toString())
        fun items(k: String) = (o[k] as? List<*>)?.take(2000)?.map { it2 ->
            when (it2) {
                is Map<*, *> -> GridItem(it2["text"]?.toString().orEmpty(), it2["icon"]?.toString().orEmpty(), it2["value"] ?: it2["text"])
                else -> GridItem(it2?.toString().orEmpty(), "", it2)
            }
        } ?: emptyList()
        return when (s("type", "text")) {
            "text" -> UiNode.Text(
                s("text"), f("size", 14f).coerceIn(8f, 40f), o["bold"] == true, color("color"), o["center"] == true,
                s("align", if (o["center"] == true) "center" else "start"), f("maxLines", 1000f).toInt().coerceIn(1, 1000),
                o["mono"] == true, o["italic"] == true
            )
            "button" -> UiNode.Button(
                s("text"), s("icon"), o["accent"] == true, o["wide"] == true, call(m, o["onClick"]), o["value"],
                color("color"), color("textColor"), call(m, o["onLongClick"]), o["disabled"] == true
            )
            "input" -> UiNode.Input(s("title"), s("hint"), s("value"), call(m, o["onSubmit"]), call(m, o["onChange"]))
            "toggle", "switch" -> UiNode.Toggle(s("text"), o["checked"] == true || o["value"] == true, call(m, o["onChange"]))
            "slider" -> {
                val min = f("min", 0f)
                val max = f("max", 1f).coerceAtLeast(min + 0.0001f)
                UiNode.Slider(s("text"), f("value", min).coerceIn(min, max), min, max, f("step", 0f).coerceAtLeast(0f), call(m, o["onChange"]))
            }
            "progress" -> UiNode.Progress((o["value"] as? Double)?.toFloat()?.coerceIn(0f, 1f))
            "divider" -> UiNode.Divider
            "box", "card" -> UiNode.Box(
                kids(), color("color"), f("radius", 18f).coerceIn(0f, 100f), f("padding", 10f).coerceIn(0f, 64f), color("border"),
                call(m, o["onClick"]), o["value"]
            )
            "tabs", "chips" -> UiNode.Tabs(items("items"), o["selected"], call(m, o["onClick"]))
            "canvas" -> UiNode.Canvas(
                f("height", 120f).coerceIn(8f, 600f),
                (o["items"] as? List<*>)?.take(4000)?.mapNotNull { OverlayItem.parse(it) } ?: emptyList(),
                call(m, o["onTap"])
            )
            "scroll" -> UiNode.Scroll(kids(), o["horizontal"] == true, f("height", 0f).coerceIn(0f, 800f))
            "row" -> UiNode.Row(kids())
            "column" -> UiNode.Column(kids())
            "grid" -> UiNode.Grid(
                (o["items"] as? List<*>)?.take(2000)?.map { it2 ->
                    when (it2) {
                        is Map<*, *> -> GridItem(it2["text"]?.toString().orEmpty(), it2["icon"]?.toString().orEmpty(), it2["value"] ?: it2["text"])
                        else -> GridItem(it2?.toString().orEmpty(), "", it2)
                    }
                } ?: emptyList(),
                f("minWidth", 46f).coerceIn(24f, 400f),
                f("height", 46f).coerceIn(24f, 200f),
                call(m, o["onClick"]),
                f("columns", 0f).toInt().coerceIn(0, 20)
            )
            "spacer" -> UiNode.Spacer(f("size", 8f).coerceIn(0f, 200f))
            "image" -> UiNode.Image(s("src"), f("size", 48f).coerceIn(8f, 300f), call(m, o["onClick"]), o["value"])
            else -> UiNode.Text(s("text"), 14f, false, null, false)
        }
    }
}
