package com.example.gemmaapp.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Gemma 4 audio frontend, matching Gemma4AudioFeatureExtractor:
 * 16 kHz mono, 320-sample periodic Hann windows, 160-sample hop,
 * 512-point RFFT, HTK 128-bin mel bank, magnitude spectrum and log(mel + 1e-3).
 */
data class Gemma4AudioFeatures(
    val values: FloatArray,
    val frameCount: Int,
    val softTokenCount: Int
)

object Gemma4AudioFeatureExtractor {
    const val SAMPLE_RATE = 16_000
    const val MAX_AUDIO_SECONDS = 30
    const val FEATURE_SIZE = 128
    private const val FRAME_LENGTH = 320
    private const val HOP_LENGTH = 160
    private const val FFT_LENGTH = 512
    private const val MEL_FLOOR = 1e-3f

    fun softTokenCountForSamples(sampleCount: Int): Int {
        val boundedSamples = min(sampleCount, SAMPLE_RATE * MAX_AUDIO_SECONDS)
        val frames = (boundedSamples + FRAME_LENGTH / 2 - (FRAME_LENGTH + 1)) / HOP_LENGTH + 1
        if (frames <= 0) return 0
        val afterFirst = (frames + 1) / 2
        return min(750, (afterFirst + 1) / 2)
    }

    fun extract(waveform: FloatArray): Gemma4AudioFeatures {
        require(waveform.isNotEmpty()) { "录音内容为空" }
        val samples = waveform.copyOf(min(waveform.size, SAMPLE_RATE * MAX_AUDIO_SECONDS))
        val padLeft = FRAME_LENGTH / 2
        val frameSizeForUnfold = FRAME_LENGTH + 1
        val frameCount = (samples.size + padLeft - frameSizeForUnfold) / HOP_LENGTH + 1
        require(frameCount > 0) { "录音太短，请至少录制约 30 毫秒" }

        val padded = FloatArray(samples.size + padLeft)
        System.arraycopy(samples, 0, padded, padLeft, samples.size)
        val window = FloatArray(FRAME_LENGTH) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / FRAME_LENGTH)).toFloat()
        }
        val melBank = createMelFilterBank()
        val features = FloatArray(frameCount * FEATURE_SIZE)
        val real = DoubleArray(FFT_LENGTH)
        val imag = DoubleArray(FFT_LENGTH)
        val magnitude = DoubleArray(FFT_LENGTH / 2 + 1)

        for (frame in 0 until frameCount) {
            val start = frame * HOP_LENGTH
            real.fill(0.0)
            imag.fill(0.0)
            for (i in 0 until FRAME_LENGTH) {
                real[i] = padded[start + i].toDouble() * window[i]
            }
            fft(real, imag)
            for (bin in magnitude.indices) magnitude[bin] = hypot(real[bin], imag[bin])

            val outOffset = frame * FEATURE_SIZE
            for (mel in 0 until FEATURE_SIZE) {
                var energy = 0.0
                val weights = melBank[mel]
                for (bin in magnitude.indices) energy += magnitude[bin] * weights[bin]
                features[outOffset + mel] = ln(max(energy, 0.0) + MEL_FLOOR).toFloat()
            }
        }

        // Two stride-2, kernel-3, padding-1 convolution blocks (ceil(T / 4)).
        val afterFirst = (frameCount + 1) / 2
        val softTokens = min(750, (afterFirst + 1) / 2)
        return Gemma4AudioFeatures(features, frameCount, softTokens)
    }

    private fun createMelFilterBank(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(8_000.0)
        val points = DoubleArray(FEATURE_SIZE + 2) { i ->
            melToHz(lowMel + (highMel - lowMel) * i / (FEATURE_SIZE + 1))
        }
        return Array(FEATURE_SIZE) { mel ->
            val left = points[mel]
            val center = points[mel + 1]
            val right = points[mel + 2]
            DoubleArray(FFT_LENGTH / 2 + 1) { bin ->
                val frequency = bin * SAMPLE_RATE.toDouble() / FFT_LENGTH
                max(0.0, min(
                    (frequency - left) / (center - left),
                    (right - frequency) / (right - center)
                ))
            }
        }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle)
            val wLenI = kotlin.math.sin(angle)
            var offset = 0
            while (offset < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val even = offset + k
                    val odd = even + length / 2
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    real[odd] = real[even] - vr
                    imag[odd] = imag[even] - vi
                    real[even] += vr
                    imag[even] += vi
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                offset += length
            }
            length = length shl 1
        }
    }

    private fun Double.pow10() = Math.pow(10.0, this)
}
