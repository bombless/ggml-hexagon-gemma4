package com.example.gemmaapp.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaScriptToolRuntimeTest {
    private val runtime = JavaScriptToolRuntime()

    @Test
    fun executesJavaScriptAndReportsPassingTests() {
        val result = runtime.execute(
            """
            function sum(a, b) { return a + b; }
            test('sum works', () => assert(sum(20, 22) === 42));
            console.log('red-black-tree harness ready');
            """.trimIndent()
        )
        assertTrue(result.success)
        assertTrue(result.output.contains("PASS sum works"))
        assertTrue(result.output.contains("red-black-tree harness ready"))
    }

    @Test
    fun reportsFailingTests() {
        val result = runtime.execute("test('bad invariant', () => assert(false, 'black height mismatch'));")
        assertFalse(result.success)
        assertTrue(result.output.contains("FAIL bad invariant"))
        assertTrue(result.output.contains("black height mismatch"))
    }

    @Test
    fun parsesNestedToolCallJson() {
        val parsed = ToolCallParser.parse(
            "<tool_call>{\"name\":\"javascript\",\"arguments\":{\"code\":\"test('x', () => assert(true));\"}}</tool_call>"
        )
        assertTrue(parsed?.code?.contains("test('x'") == true)
    }

    @Test
    fun parsesEscapedNewlinesAndQuotes() {
        val parsed = ToolCallParser.parse(
            """<tool_call>{"name":"javascript","arguments":{"code":"console.log(\"hello\");\ntest('line', () => assert(true));"}}</tool_call>"""
        )
        assertTrue(parsed?.code?.contains("console.log(\"hello\");") == true)
        assertTrue(parsed?.code?.contains("\ntest('line'") == true)
    }

    @Test
    fun removesToolCallFromVisibleText() {
        val visible = ToolCallParser.visibleText(
            "thinking<tool_call>{\"name\":\"javascript\",\"arguments\":{\"code\":\"1+1\"}}</tool_call>done"
        )
        assertTrue(visible == "thinkingdone")
    }

    @Test
    fun extractsThinkMarkerAndKeepsFinalAnswerSeparate() {
        val raw = "<|think|>Need to inspect the input.<|channel>finalThe answer is 42."
        assertTrue(ToolCallParser.thinkingText(raw) == "Need to inspect the input.")
        assertTrue(ToolCallParser.visibleText(raw) == "The answer is 42.")
    }

    @Test
    fun extractsAnalysisChannelAndToolArguments() {
        val raw = """<|channel>analysis
Check the edge cases.<tool_call>{"name":"javascript","arguments":{"code":"1+1"}}</tool_call>"""
        assertTrue(ToolCallParser.thinkingText(raw) == "Check the edge cases.")
        assertTrue(ToolCallParser.toolCallPreview(raw)?.contains("\"code\":\"1+1\"") == true)
        assertTrue(ToolCallParser.visibleText(raw).isEmpty())
    }

    @Test
    fun hidesIncompleteToolCallPreviewUntilClosingMarkerArrives() {
        val jsonPrefix = "<tool_call>{\"name\":\"javascript\",\"arguments\":{\"code\":\"1+"
        assertTrue(ToolCallParser.toolCallPreview(jsonPrefix) == null)
        assertTrue(ToolCallParser.toolCallPreview(jsonPrefix + "1\"}}" + "</tool_call>") != null)

        val nativePrefix = "<|tool_call>call:javascript{code:<|\"|>console.log('partial');"
        assertTrue(ToolCallParser.toolCallPreview(nativePrefix) == null)
        assertTrue(ToolCallParser.toolCallPreview(nativePrefix + "<|\"|>}<tool_call|>") != null)
    }
}
