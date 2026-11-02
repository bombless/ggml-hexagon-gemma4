package com.example.gemmaapp.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class Gemma4AudioEmbeddings(
    val values: FloatArray,
    val rows: Int,
    val dimension: Int
)

/** CPU ONNX audio frontend shared by the GGUF/JNI path. */
class Gemma4AudioEncoder(audioModelFile: File) : Closeable {
    private val session: OrtSession

    init {
        require(audioModelFile.isFile && audioModelFile.canRead()) {
            "Gemma 4 audio encoder is not readable: ${audioModelFile.absolutePath}"
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = try {
            OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "GemmaApp")
                .createSession(audioModelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    fun encode(samples: FloatArray): Gemma4AudioEmbeddings {
        val audio = Gemma4AudioFeatureExtractor.extract(samples)
        val featureTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(audio.values),
            longArrayOf(1, audio.frameCount.toLong(), Gemma4AudioFeatureExtractor.FEATURE_SIZE.toLong())
        )
        val maskBuffer = ByteBuffer.allocateDirect(audio.frameCount).order(ByteOrder.nativeOrder())
        repeat(audio.frameCount) { maskBuffer.put(1.toByte()) }
        maskBuffer.flip()
        val maskTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(), maskBuffer,
            longArrayOf(1, audio.frameCount.toLong()), OnnxJavaType.BOOL
        )
        try {
            session.run(mapOf("input_features" to featureTensor, "input_features_mask" to maskTensor)).use { result ->
                val output = result.get("audio_features").get() as OnnxTensor
                val shape = output.info.shape
                require(shape.size == 2 && shape[1] == 1536L) {
                    "Unexpected Gemma audio embedding shape: ${shape.contentToString()}"
                }
                val rows = shape[0].toInt()
                require(rows == audio.softTokenCount) {
                    "Audio encoder rows=$rows but frontend expected ${audio.softTokenCount} soft tokens"
                }
                val values = FloatArray(rows * shape[1].toInt())
                output.floatBuffer.get(values)
                return Gemma4AudioEmbeddings(values, rows, shape[1].toInt())
            }
        } finally {
            featureTensor.close()
            maskTensor.close()
        }
    }

    override fun close() = session.close()
}