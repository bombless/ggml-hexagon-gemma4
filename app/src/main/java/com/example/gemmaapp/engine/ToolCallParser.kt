package com.example.gemmaapp.engine

data class JavaScriptToolCall(val code: String)

/** Parser for the structured JavaScript tool call and Gemma reasoning channels. */
object ToolCallParser {
    private const val OPEN_TAG = "<tool_call>"
    private const val CLOSE_TAG = "</tool_call>"
    private const val GEMMA_OPEN = "<|tool_call>"
    private const val GEMMA_CLOSE = "<tool_call|>"
    private const val STRING_DELIMITER = "<|\"|>"

    fun parse(text: String): JavaScriptToolCall? {
        val nativeStart = text.lastIndexOf(GEMMA_OPEN)
        if (nativeStart >= 0) {
            val bodyStart = nativeStart + GEMMA_OPEN.length
            val nativeEnd = text.indexOf(GEMMA_CLOSE, bodyStart)
            if (nativeEnd >= 0) {
                val body = text.substring(bodyStart, nativeEnd).trim()
                val nameMatch = Regex("^call:([A-Za-z_][A-Za-z0-9_]*)\\s*\\{").find(body)
                if (nameMatch != null && nameMatch.groupValues[1] == "javascript") {
                    val codeKey = Regex("(?:^|[,\\s{])code\\s*:\\s*").find(body)
                    if (codeKey != null) {
                        val valueStart = codeKey.range.last + 1
                        val code = if (body.startsWith(STRING_DELIMITER, valueStart)) {
                            val from = valueStart + STRING_DELIMITER.length
                            val to = body.indexOf(STRING_DELIMITER, from)
                            if (to >= 0) body.substring(from, to) else ""
                        } else body.substring(valueStart).trim().trimEnd('}', ' ', '\n')
                        if (code.isNotBlank()) return JavaScriptToolCall(code)
                    }
                }
            }
        }
        val start = text.indexOf(OPEN_TAG)
        if (start < 0) return null
        val jsonStart = start + OPEN_TAG.length
        val end = text.indexOf(CLOSE_TAG, jsonStart)
        if (end < 0) return null
        val json = text.substring(jsonStart, end)
        val name = readJsonString(json, "name") ?: return null
        if (name != "javascript") return null
        val code = readJsonString(json, "code") ?: return null
        return if (code.isBlank()) null else JavaScriptToolCall(code)
    }

    private fun readJsonString(json: String, key: String): String? {
        val keyToken = "\"$key\""
        val keyStart = json.indexOf(keyToken)
        if (keyStart < 0) return null
        var i = keyStart + keyToken.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i++] != ':') return null
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i++] != '"') return null
        val encoded = StringBuilder()
        var escaped = false
        while (i < json.length) {
            val ch = json[i++]
            if (escaped) {
                encoded.append('\\').append(ch)
                escaped = false
            } else when (ch) {
                '\\' -> escaped = true
                '"' -> return decodeJsonString(encoded.toString())
                else -> encoded.append(ch)
            }
        }
        return null
    }

    private fun decodeJsonString(value: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i++]
            if (ch != '\\') { out.append(ch); continue }
            if (i >= value.length) return null
            when (val escaped = value[i++]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 > value.length) return null
                    val codeUnit = value.substring(i, i + 4).toIntOrNull(16) ?: return null
                    out.append(codeUnit.toChar()); i += 4
                }
                else -> return null
            }
        }
        return out.toString()
    }

    fun validateParser() {
        check(parse("<tool_call>{\"name\":\"javascript\",\"arguments\":{\"code\":\"console.log('ok');\"}}</tool_call>")?.code == "console.log('ok');") {
            "Tool-call parser self-test failed"
        }
        check(parse("<|tool_call>call:javascript{code:<|\"|>console.log('native');<|\"|>}<tool_call|>")?.code == "console.log('native');") {
            "Gemma 4 native tool-call parser self-test failed"
        }
        val gemmaThoughtSample = "<|channel>thought\nreasoning here<channel|>Final answer"
        check(thinkingText(gemmaThoughtSample) == "reasoning here") {
            "Gemma 4 thought-channel extraction self-test failed"
        }
        check(visibleText(gemmaThoughtSample) == "Final answer") {
            "Gemma 4 final-answer extraction self-test failed"
        }
        check(visibleText("<|channel>thought\nreasoning in progress").isEmpty()) {
            "Unfinished Gemma 4 thought channel should remain hidden from final answer"
        }
    }

    fun thinkingText(text: String): String {
        // Gemma 4's documented reasoning channel is "thought", not "analysis".
        for (channelName in listOf("thought", "analysis")) {
            val marker = "<|channel>$channelName"
            val channelStart = text.indexOf(marker)
            if (channelStart < 0) continue
            val newline = text.indexOf('\n', channelStart + marker.length)
            val from = if (newline >= 0) newline + 1 else channelStart + marker.length
            val close = text.indexOf("<channel|>", from)
            val toolStart = text.indexOf(OPEN_TAG, from)
            val end = listOf(close, toolStart).filter { it >= 0 }.minOrNull()
                ?: text.length
            return text.substring(from, end).trim()
        }
        // Backwards compatibility with older/custom exports using an explicit think tag.
        val thinkStart = text.indexOf("<|think|>")
        if (thinkStart >= 0) {
            val from = thinkStart + "<|think|>".length
            val finalMarker = text.indexOf("<|channel>final", from)
            val end = if (finalMarker >= 0) finalMarker else text.length
            return text.substring(from, end).trim()
        }
        return ""
    }

    fun toolCallPreview(text: String): String? {
        val nativeStart = text.lastIndexOf(GEMMA_OPEN)
        if (nativeStart >= 0) {
            val from = nativeStart + GEMMA_OPEN.length
            val end = text.indexOf(GEMMA_CLOSE, from)
            if (end < 0) return null
            return text.substring(from, end).trim().takeIf { it.isNotEmpty() }
        }
        val start = text.indexOf(OPEN_TAG)
        if (start < 0) return null
        val from = start + OPEN_TAG.length
        val end = text.indexOf(CLOSE_TAG, from)
        if (end < 0) return null
        return text.substring(from, end).trim().takeIf { it.isNotEmpty() }
    }

    /** Extract JavaScript incrementally once the opening string delimiter arrives. */
    fun toolCallCodePreview(text: String): String? {
        val start = text.lastIndexOf(GEMMA_OPEN)
        if (start < 0) return null
        val body = text.substring(start + GEMMA_OPEN.length)
        if (!body.startsWith("call:javascript")) return null
        val key = Regex("(?:^|[,\\s{])code\\s*:\\s*").find(body) ?: return null
        val valueStart = key.range.last + 1
        if (!body.startsWith(STRING_DELIMITER, valueStart)) return null
        val codeStart = valueStart + STRING_DELIMITER.length
        val codeEnd = body.indexOf(STRING_DELIMITER, codeStart)
        return body.substring(codeStart, if (codeEnd >= 0) codeEnd else body.length)
            .takeIf { it.isNotEmpty() }
    }

    fun visibleText(text: String): String {
        var result = text
        val explicitThink = result.indexOf("<|think|>")
        if (explicitThink >= 0) {
            val finalMarker = result.indexOf("<|channel>final", explicitThink)
            result = if (finalMarker >= 0) {
                result.substring(finalMarker + "<|channel>final".length)
            } else {
                result.substring(0, explicitThink)
            }
        }
        for (channelName in listOf("thought", "analysis")) {
            val marker = "<|channel>$channelName"
            val channelStart = result.indexOf(marker)
            if (channelStart >= 0) {
                val newline = result.indexOf('\n', channelStart + marker.length)
                val bodyStart = if (newline >= 0) newline + 1 else channelStart + marker.length
                val close = result.indexOf("<channel|>", bodyStart)
                // While streaming, hide the unfinished thought block; once closed,
                // remove only the thought section and keep the final answer after it.
                result = if (close >= 0) {
                    result.removeRange(channelStart, close + "<channel|>".length)
                } else {
                    result.substring(0, channelStart)
                }
            }
        }
        val start = result.indexOf(OPEN_TAG)
        if (start >= 0) {
            val end = result.indexOf(CLOSE_TAG, start + OPEN_TAG.length)
            result = if (end < 0) result.substring(0, start) else result.removeRange(start, end + CLOSE_TAG.length)
        }
        val nativeCall = result.indexOf(GEMMA_OPEN)
        if (nativeCall >= 0) result = result.substring(0, nativeCall)
        result = result.replace(GEMMA_CLOSE, "").replace("<|tool_response>", "").replace("<tool_response|>", "")
        return result.replace("<|channel>final", "").replace("<channel|>", "").trim()
    }
}
