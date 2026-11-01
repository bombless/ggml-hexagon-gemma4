package com.example.gemmaapp.engine

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.atomic.AtomicInteger

/** Executes model-authored JavaScript without exposing Android or Java classes. */
class JavaScriptToolRuntime(
    private val timeoutMs: Long = 5_000L,
    private val maxOutputChars: Int = 20_000
) {
    data class Result(val output: String, val success: Boolean)

    fun execute(source: String): Result {
        if (source.isBlank()) return Result("JavaScript 代码为空。", false)
        if (source.length > 32_000) return Result("代码超过 32 KB 限制。", false)

        val output = StringBuilder()
        val testsPassed = AtomicInteger(0)
        val testsFailed = AtomicInteger(0)
        val startedAt = System.nanoTime()

        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1 // Rhino interpreter: works on Android, no generated JVM bytecode.
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 10_000
                setClassShutter(ClassShutter { false })
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if ((System.nanoTime() - startedAt) / 1_000_000L > timeoutMs) {
                    throw IllegalStateException("JavaScript 执行超时（${timeoutMs}ms）")
                }
            }
        }

        fun appendLine(value: String) {
            if (output.length >= maxOutputChars) return
            val remaining = maxOutputChars - output.length
            output.append(value.take(remaining)).append('\n')
        }

        return try {
            factory.call { cx ->
                val scope = cx.initSafeStandardObjects(null, true)

                val console = cx.newObject(scope)
                val logFunction = object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                        appendLine(args.joinToString(" ") { Context.toString(it) })
                        return Undefined.instance
                    }
                }
                ScriptableObject.putProperty(console, "log", logFunction)
                ScriptableObject.putProperty(console, "error", logFunction)
                ScriptableObject.putProperty(scope, "console", console)

                val assertFunction = object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                        val condition = args.isNotEmpty() && Context.toBoolean(args[0])
                        if (!condition) throw IllegalStateException(
                            if (args.size > 1) Context.toString(args[1]) else "Assertion failed"
                        )
                        return Undefined.instance
                    }
                }
                ScriptableObject.putProperty(scope, "assert", assertFunction)

                val testFunction = object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                        val name = if (args.isNotEmpty()) Context.toString(args[0]) else "unnamed test"
                        val fn = args.getOrNull(1) as? Function
                        if (fn == null) {
                            testsFailed.incrementAndGet()
                            appendLine("FAIL $name: test callback must be a function")
                            return Undefined.instance
                        }
                        try {
                            fn.call(cx, scope, scope, emptyArray())
                            testsPassed.incrementAndGet()
                            appendLine("PASS $name")
                        } catch (e: Exception) {
                            testsFailed.incrementAndGet()
                            appendLine("FAIL $name: ${e.message ?: e.javaClass.simpleName}")
                        }
                        return Undefined.instance
                    }
                }
                ScriptableObject.putProperty(scope, "test", testFunction)

                val tools = cx.newObject(scope)
                ScriptableObject.putProperty(tools, "test", testFunction)
                ScriptableObject.putProperty(tools, "assert", assertFunction)
                ScriptableObject.putProperty(tools, "randomInt", object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                        val min = if (args.isNotEmpty()) Context.toNumber(args[0]).toInt() else 0
                        val max = if (args.size > 1) Context.toNumber(args[1]).toInt() else 100
                        require(max >= min) { "randomInt: max must be >= min" }
                        return min + (Math.random() * (max - min + 1)).toInt()
                    }
                })
                ScriptableObject.putProperty(tools, "now", object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = System.currentTimeMillis().toDouble()
                })
                ScriptableObject.putProperty(scope, "tools", tools)

                val value = cx.evaluateString(scope, source, "<gemma-tool>", 1, null)
                if (value !== Undefined.instance && value != org.mozilla.javascript.Undefined.instance) {
                    appendLine("返回值: ${Context.toString(value)}")
                }
            }

            if (testsPassed.get() + testsFailed.get() > 0) {
                appendLine("测试汇总: ${testsPassed.get()} 通过，${testsFailed.get()} 失败")
            }
            Result(output.toString().ifBlank { "JavaScript 执行成功（无输出）。" }, testsFailed.get() == 0)
        } catch (e: Exception) {
            appendLine("执行错误: ${e.message ?: e.javaClass.simpleName}")
            Result(output.toString().ifBlank { "执行错误: ${e.message ?: e.javaClass.simpleName}" }, false)
        }
    }
}

