package com.example.gemmaapp.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.system.Os
import android.util.Log
import com.example.gemmaapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.exp

data class GenerationChunk(
    val text: String,
    val generatedTokens: Int,
    val tokensPerSecond: Float
)

/** Decodes BPE token bytes as one UTF-8 stream, retaining partial multibyte characters. */
private class Utf8TokenStreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun append(bytes: ByteArray): String = decode(bytes, endOfInput = false)

    fun finish(): String = decode(ByteArray(0), endOfInput = true)

    private fun decode(bytes: ByteArray, endOfInput: Boolean): String {
        val combined = ByteArray(pending.size + bytes.size)
        System.arraycopy(pending, 0, combined, 0, pending.size)
        System.arraycopy(bytes, 0, combined, pending.size, bytes.size)
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(maxOf(8, combined.size * 2 + 4))
        decoder.decode(input, output, endOfInput)
        pending = ByteArray(input.remaining())
        input.get(pending)
        if (endOfInput) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}

/**
 * OnnxRuntime execution engine for Gemma 4 ONNX on Snapdragon 8 Gen 3.
 * Supports QNN HTP (NPU) hardware acceleration or high-speed multi-threaded CPU fallback.
 * KV cache and logits are natively in Float16 (ShortBuffer).
 */
class GemmaEngine private constructor(
    private val env: OrtEnvironment,
    private var embedSession: OrtSession?,
    private var decoderSession: OrtSession?,
    private val audioModelFile: File,
    val tokenizer: GemmaTokenizer,
    val backendName: String,
    private val qnnSessionConfig: QnnSessionConfig? = null
) {
    companion object {
        private const val TAG = "GemmaEngine"
        /** Dedicated log tag for QNN optrace diagnostics. */
        private const val OPTRACE_TAG = "GemmaQnnOptrace"
        private const val NUM_KV_LAYERS = 15 // Gemma 4 E2B merged decoder KV layers (0..14)
        private const val MAX_SEQ_LEN = 2048

        // KV cache dimensions:
        // Layers with head_dim=512: 4, 9, 14
        // Other layers with head_dim=256: 0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13
        private val LARGE_HEAD_LAYERS = setOf(4, 9, 14)

        fun getHeadDim(layer: Int): Int = if (layer in LARGE_HEAD_LAYERS) 512 else 256

        private data class QnnSessionConfig(
            val device: OrtEpDevice,
            val providerOptions: Map<String, String>,
            val decoderContext: File,
            val embedContext: File?,
            val embedModelFile: File,
            val optraceDir: File,
            var csvFiles: List<File> = emptyList()
        )

        private fun ensureContext(
            env: OrtEnvironment, device: OrtEpDevice, providerOptions: Map<String, String>,
            source: File, data: File, root: File
        ): File {
            val key = listOf(
                source.length(), source.lastModified(), data.length(), data.lastModified(), "57", "75",
                providerOptions["htp_performance_mode"].orEmpty(),
                providerOptions["rpc_control_latency"].orEmpty(),
                providerOptions["vtcm_mb"].orEmpty()
            )
                .joinToString("_")
            val dir = File(root, source.nameWithoutExtension + "_" + key)
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create " + dir.absolutePath)
            val ctx = File(dir, source.nameWithoutExtension + "_ctx.onnx")
            fun ready(): Boolean {
                val bins = dir.listFiles()?.filter { it.isFile && it.extension.equals("bin", true) }.orEmpty()
                // ORT 1.27/QNN 2.50 writes the context ONNX and *_ctx_qnn.bin.
                // Some backend builds do not emit a separate schematic sidecar.
                return ctx.isFile && ctx.length() > 0L &&
                    bins.any { !it.name.contains("schematic", true) }
            }
            if (ready()) return ctx
            dir.listFiles()?.forEach { it.delete() }
            val compile = OrtSession.SessionOptions().use { opts ->
                opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.addConfigEntry("ep.context_enable", "1")
                opts.addConfigEntry("ep.context_embed_mode", "0")
                opts.addConfigEntry("ep.context_file_path", ctx.absolutePath)
                val compileOptions = LinkedHashMap(providerOptions).apply {
                    put("profiling_level", "optrace")
                    put("profiling_file_path", File(dir, "qnn_context_compile.csv").absolutePath)
                }
                opts.addExecutionProvider(listOf(device), compileOptions)
                env.createSession(source.absolutePath, opts)
            }
            compile.close()
            if (!ready()) {
                throw IllegalStateException("QNN context artifacts missing for " + source.name +
                    ": " + (dir.listFiles()?.joinToString { it.name + ":" + it.length() } ?: "empty"))
            }
            Log.i(OPTRACE_TAG, "QNN context ready: " + ctx.absolutePath)
            return ctx
        }

        private fun openProfiledSession(
            env: OrtEnvironment, config: QnnSessionConfig, model: File, csv: File
        ): OrtSession {
            csv.delete()
            val optsMap = LinkedHashMap(config.providerOptions).apply {
                put("profiling_level", "optrace")
                put("profiling_file_path", csv.absolutePath)
            }
            return OrtSession.SessionOptions().use { opts ->
                opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.addConfigEntry("ep.context_enable", "0")
                opts.addExecutionProvider(listOf(config.device), optsMap)
                env.createSession(model.absolutePath, opts)
            }
        }

        private fun openProfiledSessions(env: OrtEnvironment, config: QnnSessionConfig): Pair<OrtSession, OrtSession> {
            if (!config.optraceDir.exists() && !config.optraceDir.mkdirs()) {
                throw IllegalStateException("Cannot create optrace directory: " + config.optraceDir.absolutePath)
            }
            val stamp = System.currentTimeMillis()
            val revision = BuildConfig.GIT_COMMIT
            val dirtySuffix = if (BuildConfig.GIT_DIRTY) "_dirty" else ""
            val decoderCsv = File(config.optraceDir, "qnn_optrace_decoder_${revision}${dirtySuffix}_${stamp}.csv")
            val embedCsv = File(config.optraceDir, "qnn_optrace_embed_${revision}${dirtySuffix}_${stamp}.csv")
            val decoder = openProfiledSession(env, config, config.decoderContext, decoderCsv)
            try {
                val embed = if (config.embedContext != null) {
                    openProfiledSession(env, config, config.embedContext, embedCsv)
                } else {
                    // The token embedding graph is Gather-heavy and may have no HTP-partitionable nodes.
                    OrtSession.SessionOptions().use { opts ->
                        opts.setIntraOpNumThreads(4)
                        opts.setInterOpNumThreads(1)
                        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        env.createSession(config.embedModelFile.absolutePath, opts)
                    }
                }
                config.csvFiles = if (config.embedContext != null) {
                    listOf(decoderCsv, embedCsv)
                } else {
                    listOf(decoderCsv)
                }
                Log.e(OPTRACE_TAG, "QNN decoder optrace session opened: " + decoderCsv.absolutePath +
                    if (config.embedContext == null) " ; embed=CPU" else " ; embed=QNN " + embedCsv.absolutePath)
                return Pair(decoder, embed)
            } catch (t: Throwable) {
                decoder.close()
                throw t
            }
        }

        data class ModelPaths(
            val embedModelFile: File,
            val embedDataFile: File,
            val decoderModelFile: File,
            val decoderDataFile: File,
            val audioModelFile: File,
            val audioDataFile: File
        )

        fun findModelFiles(context: Context): ModelPaths? {
            val candidateDirs = listOfNotNull(
                File(context.getExternalFilesDir(null), "models"),
                context.getExternalFilesDir(null),
                File(context.filesDir, "models"),
                context.filesDir,
                File("/sdcard/Download/gemma-4-E2B-it-ONNX"),
                File("/sdcard/Download"),
                File("/data/local/tmp/models")
            )

            for (dir in candidateDirs) {
                val embedModel = File(dir, "embed_tokens_q4f16.onnx")
                val embedData = File(dir, "embed_tokens_q4f16.onnx_data")
                val decoderModel = File(dir, "decoder_model_merged_q4f16.onnx")
                val decoderData = File(dir, "decoder_model_merged_q4f16.onnx_data")
                val audioModel = File(dir, "audio_encoder_q4f16.onnx")
                val audioData = File(dir, "audio_encoder_q4f16.onnx_data")

                Log.i(TAG, "Scanning for models in ${dir.absolutePath}...")
                if (embedModel.exists() && embedData.exists() && decoderModel.exists() && decoderData.exists() &&
                    audioModel.exists() && audioData.exists()
                ) {
                    Log.i(TAG, "Found complete text + audio ONNX model suite in: ${dir.absolutePath}")
                    return ModelPaths(embedModel, embedData, decoderModel, decoderData, audioModel, audioData)
                }
            }
            return null
        }

        fun initialize(context: Context, useQnnHtp: Boolean = true): GemmaEngine {
            Log.e(OPTRACE_TAG, "BUILD_METADATA gitCommit=${BuildConfig.GIT_COMMIT} gitDirty=${BuildConfig.GIT_DIRTY} version=${BuildConfig.VERSION_NAME} initialize(useQnnHtp=$useQnnHtp)")
            val tokenizer = GemmaTokenizer.load(context)
            val modelPaths = findModelFiles(context)
                ?: throw IllegalStateException("Gemma ONNX model files not found. Please place embed and decoder ONNX files in /sdcard/Download/gemma-4-E2B-it-ONNX/")

            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "GemmaApp")

            var backend = "CPU"
            var decoderSession: OrtSession? = null
            var embedSession: OrtSession? = null
            var qnnConfig: QnnSessionConfig? = null

            if (useQnnHtp) {
                try {
                    // FastRPC searches vendor paths for HTP skels; add the APK-extracted
                    // nativeLibraryDir as well, before registering or creating any QNN session.
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val vendorDspPaths =
                        "/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp/;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"
                    Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir + ";" + vendorDspPaths, true)
                    Log.i(TAG, "ADSP_LIBRARY_PATH=" + nativeLibDir + ";" + vendorDspPaths)
                    Log.i(TAG, "Attempting to initialize QNN HTP Execution Provider for 8Gen3...")
                    QnnEpRegistration.ensureRegistered(env)
                    val device = env.epDevices.firstOrNull { it.epName == QnnEpRegistration.EP_NAME }
                    val backendLib = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so")

                    if (device != null && backendLib.isFile) {
                        // Let QNN auto-detect the SM8650 target; apply low-latency HTP tuning below.
                        val providerOptions = mutableMapOf(
                            "backend_path" to backendLib.absolutePath,
                            // Decode invokes HTP once per generated token. Burst mode is
                            // intended for low-latency, short-running inference calls.
                            "htp_performance_mode" to "burst",
                            // QNN EP specifies RPC control latency in microseconds.
                            "rpc_control_latency" to "100",
                            // Keep VTCM sizing explicit and bounded for this HTP context.
                            "vtcm_mb" to "8"
                        )
                        val contextRoot = File(context.filesDir, "qnn-context")
                        val decoderContext = ensureContext(
                            env, device, providerOptions, modelPaths.decoderModelFile, modelPaths.decoderDataFile, contextRoot
                        )
                        // Embed may contain only Gather/lookup nodes that QNN cannot partition.
                        // Keep that stage on CPU if no QNN context can be generated; decoder remains on HTP.
                        val embedContext = runCatching {
                            ensureContext(
                                env, device, providerOptions, modelPaths.embedModelFile,
                                modelPaths.embedDataFile, contextRoot
                            )
                        }.onFailure {
                            Log.w(OPTRACE_TAG, "Embed model has no usable QNN context; using CPU for embeddings", it)
                        }.getOrNull()
                        val downloadDir = File("/sdcard/Download").takeIf { it.isDirectory && it.canWrite() }
                        val optraceDir = downloadDir
                            ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "qnn-optrace")
                        qnnConfig = QnnSessionConfig(
                            device, providerOptions, decoderContext, embedContext,
                            modelPaths.embedModelFile, optraceDir
                        )
                        val sessions = openProfiledSessions(env, qnnConfig)
                        decoderSession = sessions.first
                        embedSession = sessions.second
                        backend = "QNN HTP (Snapdragon 8 Gen 3 NPU)"
                        Log.e(OPTRACE_TAG, "QNN HTP initialized with two-phase optrace gitCommit=${BuildConfig.GIT_COMMIT} gitDirty=${BuildConfig.GIT_DIRTY}")

                    }
                } catch (e: Exception) {
                    runCatching { decoderSession?.close() }
                    runCatching { embedSession?.close() }
                    decoderSession = null
                    embedSession = null
                    qnnConfig = null
                    Log.e(OPTRACE_TAG, "QNN optrace initialization failed; falling back to CPU", e)
                    Log.w(TAG, "QNN EP initialization failed, falling back to multi-threaded CPU: ${e.message}")
                }
            }

            if (decoderSession == null || embedSession == null) {
                Log.i(TAG, "Initializing CPU Execution Provider (8 cores)...")
                val cpuOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(8)
                    setInterOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                decoderSession = env.createSession(modelPaths.decoderModelFile.absolutePath, cpuOpts)
                embedSession = env.createSession(modelPaths.embedModelFile.absolutePath, cpuOpts)
                backend = "CPU (ARM64 FP16/NEON 8-Core)"
            }

            return GemmaEngine(env, embedSession, decoderSession, modelPaths.audioModelFile, tokenizer, backend, qnnConfig)
        }
    }

    private class LayerKvBuffer(val headDim: Int, val maxSeqLen: Int) {
        val keyBuf: ShortBuffer = ByteBuffer.allocateDirect(1 * 1 * maxSeqLen * headDim * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        val valBuf: ShortBuffer = ByteBuffer.allocateDirect(1 * 1 * maxSeqLen * headDim * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()

        fun reset() {
            keyBuf.clear()
            valBuf.clear()
        }
    }

    @Volatile private var audioSession: OrtSession? = null

    private fun getAudioSession(): OrtSession {
        audioSession?.let { return it }
        synchronized(this) {
            audioSession?.let { return it }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return env.createSession(audioModelFile.absolutePath, options).also { audioSession = it }
        }
    }

    private val kvBuffers = Array(NUM_KV_LAYERS) { l ->
        LayerKvBuffer(getHeadDim(l), MAX_SEQ_LEN)
    }
    private val kvKeyInputNames = Array(NUM_KV_LAYERS) { "past_key_values.$it.key" }
    private val kvValueInputNames = Array(NUM_KV_LAYERS) { "past_key_values.$it.value" }

    fun generateStream(
        prompt: String,
        maxNewTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        audioSamples: FloatArray? = null
    ): Flow<GenerationChunk> = flow {
        val inputTokens = tokenizer.encode(prompt)
        if (inputTokens.isEmpty()) return@flow
        val (activeEmbedSession, activeDecoderSession) = ensureSessionsAvailable()

        for (buf in kvBuffers) buf.reset()

        val promptLen = inputTokens.size
        var currentTotalSeqLen = promptLen

        // 1. Prefill stage: run embed_tokens on prompt input_ids
        val inputIdsLongArray = LongArray(promptLen) { i -> inputTokens[i].toLong() }
        val inputIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(inputIdsLongArray),
            longArrayOf(1, promptLen.toLong())
        )

        val embedInputs = mapOf("input_ids" to inputIdsTensor)
        val embedResults = activeEmbedSession.run(embedInputs)
        var inputsEmbeds = embedResults.get("inputs_embeds").get() as OnnxTensor
        val perLayerInputs = embedResults.get("per_layer_inputs").get() as OnnxTensor

        if (audioSamples != null) {
            val audio = Gemma4AudioFeatureExtractor.extract(audioSamples)
            val audioTokenId = tokenizer.vocab["<|audio|>"]
                ?: throw IllegalStateException("Tokenizer 中缺少 <|audio|> 音频占位符")
            val audioPositions = inputTokens.indices.filter { inputTokens[it] == audioTokenId }
            if (audioPositions.size != audio.softTokenCount) {
                throw IllegalStateException(
                    "音频占位符数量不匹配：prompt=${audioPositions.size}, encoder预计=${audio.softTokenCount}"
                )
            }

            val featureTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audio.values),
                longArrayOf(1, audio.frameCount.toLong(), Gemma4AudioFeatureExtractor.FEATURE_SIZE.toLong())
            )
            val maskBytes = ByteBuffer.allocateDirect(audio.frameCount).order(ByteOrder.nativeOrder())
            repeat(audio.frameCount) { maskBytes.put(1.toByte()) }
            maskBytes.flip()
            val featureMaskTensor = OnnxTensor.createTensor(
                env,
                maskBytes,
                longArrayOf(1, audio.frameCount.toLong()),
                OnnxJavaType.BOOL
            )
            val audioResults = getAudioSession().run(
                mapOf("input_features" to featureTensor, "input_features_mask" to featureMaskTensor)
            )
            try {
                val audioEmbeddingsTensor = audioResults.get("audio_features").get() as OnnxTensor
                val audioShape = audioEmbeddingsTensor.info.shape
                val audioRows = audioShape[0].toInt()
                if (audioRows != audioPositions.size || audioShape.getOrNull(1) != 1536L) {
                    throw IllegalStateException(
                        "音频编码器输出形状异常：${audioShape.contentToString()}，占位符=${audioPositions.size}"
                    )
                }

                val audioValues = FloatArray(audioRows * 1536)
                audioEmbeddingsTensor.floatBuffer.get(audioValues)
                val textEmbeddings = FloatArray(promptLen * 1536)
                inputsEmbeds.floatBuffer.get(textEmbeddings)
                audioPositions.forEachIndexed { index, tokenPosition ->
                    System.arraycopy(audioValues, index * 1536, textEmbeddings, tokenPosition * 1536, 1536)
                }
                inputsEmbeds.close()
                inputsEmbeds = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(textEmbeddings),
                    longArrayOf(1, promptLen.toLong(), 1536)
                )
                Log.i(TAG, "Audio encoded frames=${audio.frameCount} softTokens=${audioRows}")
            } finally {
                audioResults.close()
                featureTensor.close()
                featureMaskTensor.close()
            }
        }

        // Run decoder for prefill
        val posIdsArray = LongArray(promptLen) { i -> i.toLong() }
        val attnMaskArray = LongArray(promptLen) { 1L }

        val posIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(posIdsArray), longArrayOf(1, promptLen.toLong()))
        val attnMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attnMaskArray), longArrayOf(1, promptLen.toLong()))
        val numLogitsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1L)), longArrayOf())

        val decoderInputs = HashMap<String, OnnxTensor>()
        decoderInputs["inputs_embeds"] = inputsEmbeds
        decoderInputs["attention_mask"] = attnMaskTensor
        decoderInputs["position_ids"] = posIdsTensor
        decoderInputs["num_logits_to_keep"] = numLogitsTensor
        decoderInputs["per_layer_inputs"] = perLayerInputs

        // Initial empty past_key_values (past_sequence_length = 0) with FLOAT16 type
        val emptyPastTensors = ArrayList<OnnxTensor>(NUM_KV_LAYERS)
        for (l in 0 until NUM_KV_LAYERS) {
            val hDim = getHeadDim(l).toLong()
            val emptyTensor = OnnxTensor.createTensor(
                env,
                ShortBuffer.wrap(ShortArray(0)),
                longArrayOf(1, 1, 0, hDim),
                OnnxJavaType.FLOAT16
            )
            emptyPastTensors.add(emptyTensor)
            decoderInputs["past_key_values.$l.key"] = emptyTensor
            decoderInputs["past_key_values.$l.value"] = emptyTensor
        }

        var decResult: OrtSession.Result
        try {
            decResult = activeDecoderSession.run(decoderInputs)
        } finally {
            // One empty tensor is shared by K and V for each layer; close it once.
            emptyPastTensors.forEach { runCatching { it.close() } }
        }
        var lastLogitsTensor = decResult.get("logits").get() as OnnxTensor

        // Save present KV into our buffers
        for (l in 0 until NUM_KV_LAYERS) {
            val presKey = decResult.get("present.$l.key").get() as OnnxTensor
            val presVal = decResult.get("present.$l.value").get() as OnnxTensor
            val kSb = presKey.shortBuffer
            val vSb = presVal.shortBuffer
            val kb = kvBuffers[l].keyBuf
            val vb = kvBuffers[l].valBuf
            kb.clear(); kb.put(kSb); kb.flip()
            vb.clear(); vb.put(vSb); vb.flip()
        }

        // Measure decode throughput separately from prompt prefill latency.
        val decodeStartNanos = System.nanoTime()
        var generatedTokens = 0
        val utf8Decoder = Utf8TokenStreamDecoder()

        // Reuse the decoder input map across steps; the per-step KV OrtValues remain shape-bound.
        val stepDecInputs = HashMap<String, OnnxTensor>(5 + NUM_KV_LAYERS * 2)
        try {
            // Generate tokens sequentially
            for (step in 0 until maxNewTokens) {
            val nextToken = sampleNextTokenFp16(lastLogitsTensor, temperature, topP)
            // Gemma 4 emits <|tool_response> immediately after a tool call as a
            // generation stop marker. Return control to the host to execute it.
            if (nextToken == GemmaTokenizer.EOS_TOKEN_ID ||
                nextToken == (tokenizer.vocab["<turn|>"] ?: 107) ||
                nextToken == (tokenizer.vocab["<|tool_response>"] ?: 115)) {
                break
            }

            // BPE token bytes can split a UTF-8 character; decode the whole stream.
            val tokenText = utf8Decoder.append(tokenizer.decodeTokenBytes(nextToken))
            generatedTokens += 1
            val elapsedSeconds = ((System.nanoTime() - decodeStartNanos).coerceAtLeast(1L)) / 1_000_000_000.0
            emit(
                GenerationChunk(
                    text = tokenText,
                    generatedTokens = generatedTokens,
                    tokensPerSecond = (generatedTokens / elapsedSeconds).toFloat()
                )
            )

            currentTotalSeqLen += 1
            if (currentTotalSeqLen >= MAX_SEQ_LEN) break

            // Embed one token. Inputs are consumed synchronously, so close the ID tensor
            // immediately and retain the result only until the decoder run completes.
            val singleIdTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(nextToken.toLong())),
                longArrayOf(1, 1)
            )
            val stepEmbedRes = try {
                activeEmbedSession.run(mapOf("input_ids" to singleIdTensor))
            } finally {
                singleIdTensor.close()
            }
            val stepKvTensors = ArrayList<OnnxTensor>(NUM_KV_LAYERS * 2)
            var stepPosTensor: OnnxTensor? = null
            var stepMaskTensor: OnnxTensor? = null
            try {
                val stepInputsEmbeds = stepEmbedRes.get("inputs_embeds").get() as OnnxTensor
                val stepPerLayerInputs = stepEmbedRes.get("per_layer_inputs").get() as OnnxTensor

                stepPosTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf((currentTotalSeqLen - 1).toLong())),
                    longArrayOf(1, 1)
                )

                val stepMaskArray = LongArray(currentTotalSeqLen) { 1L }
                stepMaskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(stepMaskArray),
                    longArrayOf(1, currentTotalSeqLen.toLong())
                )

                stepDecInputs.clear()
                stepDecInputs["inputs_embeds"] = stepInputsEmbeds
                stepDecInputs["attention_mask"] = stepMaskTensor
                stepDecInputs["position_ids"] = stepPosTensor
                stepDecInputs["num_logits_to_keep"] = numLogitsTensor
                stepDecInputs["per_layer_inputs"] = stepPerLayerInputs

                val pastLen = (currentTotalSeqLen - 1).toLong()
                for (l in 0 until NUM_KV_LAYERS) {
                    val hDim = getHeadDim(l).toLong()
                    val kb = kvBuffers[l].keyBuf.duplicate()
                    val vb = kvBuffers[l].valBuf.duplicate()
                    kb.position(0); kb.limit((pastLen * hDim).toInt())
                    vb.position(0); vb.limit((pastLen * hDim).toInt())

                    val keyTensor = OnnxTensor.createTensor(
                        env, kb, longArrayOf(1, 1, pastLen, hDim), OnnxJavaType.FLOAT16
                    )
                    stepKvTensors.add(keyTensor)
                    val valueTensor = OnnxTensor.createTensor(
                        env, vb, longArrayOf(1, 1, pastLen, hDim), OnnxJavaType.FLOAT16
                    )
                    stepKvTensors.add(valueTensor)
                    stepDecInputs[kvKeyInputNames[l]] = keyTensor
                    stepDecInputs[kvValueInputNames[l]] = valueTensor
                }

                // The current logits have already been sampled and its KV outputs copied.
                // Release the previous native result before replacing it with the next one.
                decResult.close()
                decResult = activeDecoderSession.run(stepDecInputs)
                lastLogitsTensor = decResult.get("logits").get() as OnnxTensor

                for (l in 0 until NUM_KV_LAYERS) {
                    val presKey = decResult.get("present.$l.key").get() as OnnxTensor
                    val presVal = decResult.get("present.$l.value").get() as OnnxTensor
                    val kSb = presKey.shortBuffer
                    val vSb = presVal.shortBuffer
                    val kb = kvBuffers[l].keyBuf
                    val vb = kvBuffers[l].valBuf
                    kb.clear(); kb.put(kSb); kb.flip()
                    vb.clear(); vb.put(vSb); vb.flip()
                }
            } finally {
                stepKvTensors.forEach { runCatching { it.close() } }
                stepPosTensor?.let { runCatching { it.close() } }
                stepMaskTensor?.let { runCatching { it.close() } }
                runCatching { stepEmbedRes.close() }
                stepDecInputs.clear()
            }
            }
        } finally {
            // The final result owns the logits needed only until generation terminates.
            runCatching { decResult.close() }
            runCatching { numLogitsTensor.close() }
            stepDecInputs.clear()
        }
        val utf8Tail = utf8Decoder.finish()
        if (utf8Tail.isNotEmpty()) {
            val elapsedSeconds = ((System.nanoTime() - decodeStartNanos).coerceAtLeast(1L)) / 1_000_000_000.0
            emit(GenerationChunk(utf8Tail, generatedTokens, (generatedTokens / elapsedSeconds).toFloat()))
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Converts an IEEE 754 half-precision float (16-bit short) to standard 32-bit float.
     */
    private fun fp16ToFloat(hbits: Short): Float {
        var mant = (hbits.toInt() and 0x03FF)
        var exp = (hbits.toInt() and 0x7C00)
        if (exp == 0x7C00) {
            exp = 0x3FC00
        } else if (exp != 0) {
            exp += 0x1C000
            if (mant == 0 && exp > 0x1C400) {
                return Float.fromBits(((hbits.toInt() and 0x8000) shl 16) or (exp shl 13))
            }
        } else if (mant != 0) {
            exp = 0x1C400
            do {
                mant = mant shl 1
                exp -= 0x400
            } while ((mant and 0x400) == 0)
            mant = mant and 0x3FF
        }
        return Float.fromBits(((hbits.toInt() and 0x8000) shl 16) or ((exp or mant) shl 13))
    }

    private fun sampleNextTokenFp16(logitsTensor: OnnxTensor, temperature: Float, topP: Float): Int {
        val sb = logitsTensor.shortBuffer
        val vocabSize = sb.remaining()
        val logits = FloatArray(vocabSize)
        val shortArr = ShortArray(vocabSize)
        sb.get(shortArr)

        for (i in 0 until vocabSize) {
            logits[i] = fp16ToFloat(shortArr[i])
        }

        if (temperature <= 0.001f) {
            var maxIdx = 0
            var maxVal = logits[0]
            for (i in 1 until vocabSize) {
                if (logits[i] > maxVal) {
                    maxVal = logits[i]
                    maxIdx = i
                }
            }
            return maxIdx
        }

        var maxLogit = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            logits[i] /= temperature
            if (logits[i] > maxLogit) maxLogit = logits[i]
        }

        var sumExp = 0.0
        val probs = DoubleArray(vocabSize)
        for (i in 0 until vocabSize) {
            val p = exp((logits[i] - maxLogit).toDouble())
            probs[i] = p
            sumExp += p
        }

        val r = Math.random() * sumExp
        var accum = 0.0
        for (i in 0 until vocabSize) {
            accum += probs[i]
            if (accum >= r) return i
        }
        return vocabSize - 1
    }

    @Synchronized
    private fun ensureSessionsAvailable(): Pair<OrtSession, OrtSession> {
        if (embedSession == null || decoderSession == null) {
            val config = qnnSessionConfig
                ?: throw IllegalStateException("Inference sessions are closed and cannot be recreated")
            val sessions = openProfiledSessions(env, config)
            decoderSession = sessions.first
            embedSession = sessions.second
        }
        return Pair(checkNotNull(embedSession), checkNotNull(decoderSession))
    }

    @Synchronized
    fun flushOptrace() {
        val config = qnnSessionConfig ?: return
        // Closing an ORT session is required to finalize its optrace CSV, but doing so
        // after every chat turn forces QNN to reacquire HTP/VTCM on the next request.
        // Keep the decoder and embedding sessions warm; finalize the trace in close().
        config.csvFiles.forEach { file ->
            Log.i(OPTRACE_TAG, "QNN optrace still recording file=" + file.absolutePath +
                " exists=" + file.isFile + " size=" + file.length() +
                " (finalized when GemmaEngine.close() runs)")
        }
    }

    @Synchronized
    fun close() {
        audioSession?.close()
        runCatching { embedSession?.close() }
            .onFailure { Log.e(OPTRACE_TAG, "Embed session close failed", it) }
        runCatching { decoderSession?.close() }
            .onFailure { Log.e(OPTRACE_TAG, "Decoder session close failed", it) }
        embedSession = null
        decoderSession = null
        qnnSessionConfig?.csvFiles?.forEach { file ->
            Log.i(OPTRACE_TAG, "QNN optrace finalized file=" + file.absolutePath +
                " exists=" + file.isFile + " size=" + file.length())
        }
        env.close()
    }
}
