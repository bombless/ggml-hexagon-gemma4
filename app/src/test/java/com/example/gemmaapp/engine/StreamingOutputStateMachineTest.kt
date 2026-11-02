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

    @Test
    fun separatesGemma4ThoughtChannelFromFinalAnswer() {
        val parser = StreamingOutputStateMachine()
        val thought = parser.append("<|channel>thought\nLet me reason through this")
        assertEquals("", thought.visibleText)
        assertEquals("Let me reason through this", thought.thinkingText)

        val answer = parser.append("<channel|><|channel>final\nThe answer is 42.")
        assertEquals("The answer is 42.", answer.visibleText)
        assertEquals("Let me reason through this", answer.thinkingText)

        val finished = parser.finish()
        assertEquals("The answer is 42.", finished.visibleText)
        assertEquals("Let me reason through this", finished.thinkingText)
    }

    @Test
    fun doesNotLeakIncompleteGemmaThoughtChannelIntoVisibleAnswer() {
        val parser = StreamingOutputStateMachine()
        val snapshot = parser.append("<|channel>thought\nprivate chain of thought")
        assertEquals("", snapshot.visibleText)
        assertEquals("private chain of thought", snapshot.thinkingText)
    }
}
