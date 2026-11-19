package com.expressive.keyboard

import android.content.res.AssetManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Undefined
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

internal class QuickJsPluginRuntime(private val assets: AssetManager) : PluginScriptRuntime {
    private var handle = nativeCreate().also { check(it != 0L) { "Could not create QuickJS runtime" } }
    private var deadline = Long.MAX_VALUE
    private var closePending = false
    private val bindings = ArrayList<ScriptBinding>()
    private val collectedFunctions = ReferenceQueue<Callback>()
    private val callbacks = HashMap<Int, CallbackRef>()

    private inner class Callback(val id: Int) : PluginScriptFunction {
        fun belongsTo(runtime: QuickJsPluginRuntime) = this@QuickJsPluginRuntime === runtime
    }

    private class CallbackRef(val id: Int, callback: Callback, queue: ReferenceQueue<Callback>) :
        WeakReference<Callback>(callback, queue)

    override fun load(bindings: Map<String, Any?>, prelude: String, source: String, filename: String) {
        val bootstrap = assets.open("quickjs/plugin-bridge.js").bufferedReader().use { it.readText() }
        run(BOOTSTRAP, bootstrap, "quickjs-bridge.js")
        dispatch("install", encode(bindings))
        run(EVAL, prelude, "kb-prelude.js")
        run(EVAL, source, filename)
    }

    override fun call(function: PluginScriptFunction, args: Array<out Any?>, readResult: Boolean): Any? {
        val callback = function as Callback
        check(callback.belongsTo(this))
        val expired = JSONArray()
        while (true) {
            val ref = collectedFunctions.poll() as? CallbackRef ?: break
            if (callbacks[ref.id] === ref) {
                callbacks.remove(ref.id)
                expired.put(ref.id)
            }
        }
        return decode(dispatch("call", callback.id, encode(args.asList()), expired, readResult), topLevel = true)
    }

    override fun <T> execute(budgetMs: Long, block: () -> T): T {
        check(handle != 0L && !closePending) { "QuickJS runtime is closed" }
        val previous = deadline
        deadline = minOf(previous, SystemClock.uptimeMillis() + budgetMs)
        return try {
            block()
        } finally {
            deadline = previous
            if (previous == Long.MAX_VALUE && closePending) close()
        }
    }

    override fun close() {
        if (deadline != Long.MAX_VALUE) {
            closePending = true
            return
        }
        if (handle != 0L) nativeClose(handle)
        handle = 0
        bindings.clear()
        callbacks.clear()
    }

    private fun dispatch(vararg command: Any?): Any? {
        val response = run(DISPATCH, JSONArray(command.asList()).toString(), "quickjs-bridge.js")
        return JSONArray(response).get(0)
    }

    private fun run(mode: Int, source: String, filename: String): String {
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0) throw ScriptTimeout()
        val response = nativeRun(handle, mode, source.toByteArray(), filename, remaining) ?: throw ScriptTimeout()
        val result = response.decodeToString(startIndex = 1)
        if (response[0].toInt() != 0) throw IllegalStateException(result)
        return result
    }

    @Suppress("unused") // Called synchronously by the native runtime on the keyboard thread.
    private fun dispatchHost(id: Int, payload: ByteArray): ByteArray {
        val reply = try {
            val args = JSONArray(payload.decodeToString())
            val values = Array(args.length()) { decode(args.get(it), topLevel = true) }
            tag(true, encode(bindings[id].invoke(values)))
        } catch (timeout: ScriptTimeout) {
            throw timeout
        } catch (error: Exception) {
            tag(false, error.message ?: error.javaClass.simpleName)
        }
        return reply.toString().toByteArray()
    }

    private fun encode(value: Any?, depth: Int = 0): Any = when {
        value == null || depth > 32 -> JSONObject.NULL
        value is Unit || value is Undefined -> tag("u")
        value is String || value is Boolean -> value
        value is Number -> value.toDouble().let { if (it.isFinite()) it else tag("n", it.toString()) }
        value is ScriptBinding -> tag("b", bindings.size.also { bindings += value }, value.name)
        value is ScriptJson -> tag("j", value.source)
        value is Callback && value.belongsTo(this) -> tag("f", value.id)
        value is PluginScriptFunction -> JSONObject.NULL
        value is List<*> -> tag("a", JSONArray(value.map { encode(it, depth + 1) }))
        value is Map<*, *> -> tag("o", JSONArray(value.map { (key, item) ->
            tag(key.toString(), encode(item, depth + 1))
        }))
        else -> value.toString()
    }

    private fun decode(value: Any?, topLevel: Boolean = false): Any? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble()
        is JSONArray -> when (value.getString(0)) {
            "u" -> if (topLevel) Undefined.instance else null
            "n" -> value.getString(1).toDouble()
            "f" -> {
                val id = value.getInt(1)
                callbacks[id]?.get() ?: Callback(id).also { callbacks[id] = CallbackRef(id, it, collectedFunctions) }
            }
            "a" -> value.getJSONArray(1).let { array -> List(array.length()) { decode(array.get(it)) } }
            "o" -> value.getJSONArray(1).let { entries ->
                buildMap {
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONArray(i)
                        put(entry.getString(0), decode(entry.get(1)))
                    }
                }
            }
            else -> error("Unknown QuickJS value")
        }
        else -> value
    }

    private fun tag(vararg values: Any?): JSONArray = JSONArray(values.asList())

    private external fun nativeCreate(): Long
    private external fun nativeRun(handle: Long, mode: Int, code: ByteArray, filename: String, budgetMs: Long): ByteArray?
    private external fun nativeClose(handle: Long)

    private companion object {
        const val BOOTSTRAP = 0
        const val EVAL = 1
        const val DISPATCH = 2

        init {
            System.loadLibrary("materialboard_quickjs")
        }
    }
}
