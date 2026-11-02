package com.example.gemmaapp.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class NativeChatMessage(
    val role: String,
    val content: String,
    val toolName: String = ""
)

/** JNI-backed GGUF session. Calls that may decode tokens always run on Dispatchers.IO. */
class NativeLlamaSession private constructor(handle: Long) : Closeable {
    private val nativeHandle = AtomicLong(handle)

    /** Exact time spent inside llama_model_load_from_file(), in milliseconds. */
    val modelLoadDurationMs: Long
        get() {
            val handle = nativeHandle.get()
            check(handle != 0L) { "Native GGUF session is closed" }
            return nativeModelLoadDurationMs(handle)
        }

    interface Listener {
        fun onToken(text: String)
        fun onComplete(status: String)
        fun onError(message: String)
    }

    suspend fun generate(
        messages: List<NativeChatMessage>,
        maxTokens: Int = 128,
        audioEmbeddings: Gemma4AudioEmbeddings? = null,
        listener: Listener
    ) =
        withContext(Dispatchers.IO) {
            val handle = nativeHandle.get()
            check(handle != 0L) { "Native GGUF session is closed" }
            nativeGenerate(
                handle,
                Array(messages.size) { messages[it].role },
                Array(messages.size) { messages[it].content },
                Array(messages.size) { messages[it].toolName },
                audioEmbeddings?.values,
                audioEmbeddings?.rows ?: 0,
                audioEmbeddings?.dimension ?: 0,
                maxTokens, listener
            )
        }

    fun cancel() {
        nativeHandle.get().takeIf { it != 0L }?.let(::nativeCancel)
    }

    override fun close() {
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) nativeDestroy(handle)
    }

    companion object {
        init { System.loadLibrary("gemma4_jni") }

        /** backend is an artifact identity ("fastrpc-mempool" or "dspqueue"), not a runtime switch. */
        fun open(model: File, contextSize: Int = 4096, threads: Int = 4,
                 backend: String = "cpu") : NativeLlamaSession {
            require(model.isFile && model.canRead()) { "GGUF model is not readable: ${model.absolutePath}" }
            require(backend in setOf("cpu", "fastrpc-mempool", "dspqueue")) { "Unknown backend identity: $backend" }
            return NativeLlamaSession(nativeCreate(model.absolutePath, contextSize, threads, backend))
        }

        @JvmStatic private external fun nativeCreate(modelPath: String, contextSize: Int, threads: Int, backend: String): Long
    }

    private external fun nativeModelLoadDurationMs(handle: Long): Long

    private external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<String>, toolNames: Array<String>,
        audioEmbeddings: FloatArray?, audioRows: Int, audioDimension: Int,
        maxTokens: Int, listener: Listener
    )
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)
}