package com.example.gemmaapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingOutputStateMachineTest {
    @Test
    fun withholdsThinkPrefixUntilMarkerIsResolved() {
        val parser = StreamingOutputStateMachine()
        assertEquals("hello", parser.append("hello<").visibleText)
        assertEquals("hello", parser.append("|thi").visibleText)
        val resolved = parser.append("nk|>private reasoning")
        assertEquals("hello", resolved.visibleText)
        assertEquals("private reasoning", resolved.thinkingText)
    }

    @Test
    fun releasesTextWhenItCanNoLongerBeAThinkMarker() {
        val parser = StreamingOutputStateMachine()
        assertEquals("", parser.append("<|thi").visibleText)
        assertEquals("<|thix", parser.append("x").visibleText)
    }

    @Test
    fun streamsJavascriptOnlyAfterNativeToolHeaderAndCodeDelimiter() {
        val parser = StreamingOutputStateMachine()
        val first = parser.append("before<|tool_call>call:javascript{code:<|\"|>const value =")
        assertEquals("before", first.visibleText)
        assertEquals("const value =", first.toolPreview)
        val next = parser.append(" 42; console.log(value);")
        assertEquals("const value = 42; console.log(value);", next.toolPreview)
        assertEquals("before", next.visibleText)
    }

    @Test
    fun buffersPartialMarkerAndFlushesOrdinaryTextAtEnd() {
        val parser = StreamingOutputStateMachine()
        assertEquals("text", parser.append("text<").visibleText)
        assertEquals("text<", parser.finish().visibleText)
    }
}