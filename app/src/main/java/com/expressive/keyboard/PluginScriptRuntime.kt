package com.expressive.keyboard

import android.os.SystemClock
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

internal interface PluginScriptRuntime : AutoCloseable {
    fun load(bindings: Map<String, Any?>, prelude: String, source: String, filename: String)
    fun call(function: PluginScriptFunction, args: Array<out Any?>, readResult: Boolean = false): Any?
    fun <T> execute(budgetMs: Long, block: () -> T): T
}

internal interface PluginScriptFunction
internal class RhinoPluginFunction(val function: Function) : PluginScriptFunction
internal class ScriptBinding(val name: String, val invoke: (Array<Any?>) -> Any?)
internal class ScriptJson(val source: String)

internal fun Any?.scriptFunction(): PluginScriptFunction? = when (this) {
    is PluginScriptFunction -> this
    is Function -> RhinoPluginFunction(this)
    else -> null
}

internal class ScriptTimeout : Error("скрипт работает слишком долго и остановлен")

internal class RhinoPluginRuntime : PluginScriptRuntime {
    private lateinit var scope: ScriptableObject

    override fun load(bindings: Map<String, Any?>, prelude: String, source: String, filename: String) {
        val cx = Context.getCurrentContext()
        scope = cx.initSafeStandardObjects()
        val json = ScriptableObject.getProperty(scope, "JSON") as Scriptable
        parseJson = ScriptableObject.getProperty(json, "parse") as Function
        bindings.forEach { (key, value) -> ScriptableObject.putProperty(scope, key, toJs(cx, value)) }
        cx.evaluateString(scope, prelude, "kb-prelude.js", 1, null)
        cx.evaluateString(scope, source, filename, 1, null)
    }

    private lateinit var parseJson: Function

    override fun call(function: PluginScriptFunction, args: Array<out Any?>, readResult: Boolean): Any? {
        val cx = Context.getCurrentContext()
        val result = (function as RhinoPluginFunction).function.call(
            cx, scope, scope, args.map { toJs(cx, it) }.toTypedArray()
        )
        return if (readResult) result else null
    }

    private fun toJs(cx: Context, value: Any?): Any? = when (value) {
        null -> null
        Unit -> Undefined.instance
        is String, is Boolean, is Scriptable, is Undefined -> value
        is Number -> value.toDouble()
        is RhinoPluginFunction -> value.function
        is ScriptJson -> runCatching { parseJson.call(cx, scope, scope, arrayOf(value.source)) }
            .getOrDefault(Undefined.instance)
        is List<*> -> cx.newArray(scope, value.map { toJs(cx, it) }.toTypedArray())
        is Map<*, *> -> cx.newObject(scope).also { obj ->
            value.forEach { (key, item) -> ScriptableObject.putProperty(obj, key.toString(), toJs(cx, item)) }
        }
        is ScriptBinding -> object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun getFunctionName(): String = value.name
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
                toJs(cx, value.invoke(args))
        }
        else -> value.toString()
    }

    override fun <T> execute(budgetMs: Long, block: () -> T): T {
        val cx = factory.enterContext()
        val previous = cx.getThreadLocal(deadlineKey) as? Long
        try {
            val deadline = SystemClock.uptimeMillis() + budgetMs
            cx.putThreadLocal(deadlineKey, minOf(previous ?: deadline, deadline))
            return block().also { cx.processMicrotasks() }
        } finally {
            if (previous == null) cx.removeThreadLocal(deadlineKey)
            else cx.putThreadLocal(deadlineKey, previous)
            Context.exit()
        }
    }

    override fun close() = Unit

    private companion object {
        val deadlineKey = Any()
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                @Suppress("DEPRECATION")
                optimizationLevel = -1
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 5_000
                maximumInterpreterStackDepth = 1_000
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val deadline = cx.getThreadLocal(deadlineKey) as? Long ?: return
                if (SystemClock.uptimeMillis() > deadline) throw ScriptTimeout()
            }
        }
    }
}
