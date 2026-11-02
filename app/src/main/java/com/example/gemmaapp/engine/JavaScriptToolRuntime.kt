package com.example.gemmaapp.engine

import com.bombless.agenttools.JavaScriptToolRuntime as SharedJavaScriptToolRuntime

/** App-facing adapter; the sandbox implementation is shared across Android agent apps. */
class JavaScriptToolRuntime(
    timeoutMs: Long = 5_000L,
    maxOutputChars: Int = 20_000
) {
    data class Result(val output: String, val success: Boolean)

    private val delegate = SharedJavaScriptToolRuntime(
        timeoutMs = timeoutMs,
        maxOutputChars = maxOutputChars
    )

    fun execute(source: String): Result {
        val result = delegate.execute(source)
        return Result(result.output, result.success)
    }
}
