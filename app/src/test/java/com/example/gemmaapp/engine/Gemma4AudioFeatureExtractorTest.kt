package com.example.gemmaapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma4AudioFeatureExtractorTest {
    @Test
    fun oneSecondWaveformProducesExpectedFeatureShapeAndSoftTokens() {
        val samples = FloatArray(16_000) { index ->
            (0.2 * kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 16_000.0)).toFloat()
        }

        val result = Gemma4AudioFeatureExtractor.extract(samples)

        assertEquals(99, result.frameCount)
        assertEquals(99 * 128, result.values.size)
        assertEquals(25, result.softTokenCount)
        assertTrue(result.values.all { it.isFinite() })
    }

    @Test
    fun thirtySecondWaveformIsCappedAtSevenHundredFiftyAudioTokens() {
        assertEquals(750, Gemma4AudioFeatureExtractor.softTokenCountForSamples(16_000 * 30))
        assertEquals(750, Gemma4AudioFeatureExtractor.extract(FloatArray(16_000 * 30)).softTokenCount)
    }

    @Test
    fun audioLongerThanThirtySecondsUsesTheSameThirtySecondTokenBudget() {
        assertEquals(
            Gemma4AudioFeatureExtractor.softTokenCountForSamples(16_000 * 30),
            Gemma4AudioFeatureExtractor.softTokenCountForSamples(16_000 * 45)
        )
    }
}
