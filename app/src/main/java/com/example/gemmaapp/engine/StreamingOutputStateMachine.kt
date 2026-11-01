package com.example.gemmaapp.engine

/** Incremental presentation parser that withholds ambiguous control-token prefixes. */
class StreamingOutputStateMachine {
    data class Snapshot(val visibleText: String, val thinkingText: String, val toolPreview: String?)
    private val raw = StringBuilder()

    fun append(chunk: String): Snapshot {
        raw.append(chunk)
        return snapshot(false)
    }

    fun finish(): Snapshot = snapshot(true)

    private fun snapshot(finished: Boolean): Snapshot {
        val all = raw.toString()
        val stableEnd = if (finished) all.length else all.length - ambiguousSuffixLength(all)
        val stable = all.substring(0, stableEnd)
        return Snapshot(
            ToolCallParser.visibleText(stable),
            ToolCallParser.thinkingText(stable),
            ToolCallParser.toolCallCodePreview(stable)
                ?: if (stable.contains("<|tool_call>")) null else ToolCallParser.toolCallPreview(stable)
        )
    }

    private fun ambiguousSuffixLength(value: String): Int {
        val markers = listOf(
            "<|think|>", "<|channel>thought", "<|channel>analysis", "<|channel>final", "<|tool_call>",
            "<tool_call>", "</tool_call>", "<tool_call|>", "<|tool_response>", "<tool_response|>",
            "<|tool_call>call:javascript"
        )
        var longest = 0
        for (marker in markers) {
            val limit = minOf(value.length, marker.length - 1)
            for (size in 1..limit) {
                if (size > longest && value.endsWith(marker.substring(0, size))) longest = size
            }
        }
        return longest
    }
}