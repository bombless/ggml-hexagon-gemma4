package com.example.gemmaapp.ui

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemmaapp.engine.GemmaEngine
import com.example.gemmaapp.engine.NativeLlamaSession
import com.example.gemmaapp.engine.NativeChatMessage
import com.example.gemmaapp.engine.HexagonRuntimeAssets
import com.example.gemmaapp.BuildConfig
import java.io.File
import com.example.gemmaapp.engine.Gemma4AudioFeatureExtractor
import com.example.gemmaapp.engine.Gemma4AudioEncoder
import com.example.gemmaapp.engine.JavaScriptToolRuntime
import com.example.gemmaapp.engine.ToolCallParser
import com.example.gemmaapp.engine.StreamingOutputStateMachine
import com.example.gemmaapp.engine.ModelDownloadProgress
import com.example.gemmaapp.engine.ModelScopeModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = ChatMessage.Sender.SYSTEM,
                text = "构建时间：${BuildConfig.BUILD_TIMESTAMP}"
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generationSpeed = MutableStateFlow(0f)
    val generationSpeed: StateFlow<Float> = _generationSpeed.asStateFlow()

    private val _generatedTokens = MutableStateFlow(0)
    val generatedTokens: StateFlow<Int> = _generatedTokens.asStateFlow()

    private val _downloadProgress = MutableStateFlow<ModelDownloadProgress?>(null)
    val downloadProgress: StateFlow<ModelDownloadProgress?> = _downloadProgress.asStateFlow()

    private val _isDownloadingModels = MutableStateFlow(false)
    val isDownloadingModels: StateFlow<Boolean> = _isDownloadingModels.asStateFlow()

    private val _modelDownloadFailed = MutableStateFlow(false)
    val modelDownloadFailed: StateFlow<Boolean> = _modelDownloadFailed.asStateFlow()

    private val _isEngineReady = MutableStateFlow(false)
    val isEngineReady: StateFlow<Boolean> = _isEngineReady.asStateFlow()

    private val _isLlamaModelLoading = MutableStateFlow(false)
    val isLlamaModelLoading: StateFlow<Boolean> = _isLlamaModelLoading.asStateFlow()

    private var engine: GemmaEngine? = null
    private var ggufSession: NativeLlamaSession? = null
    private var ggufAudioEncoder: Gemma4AudioEncoder? = null
    private val _engineMode = MutableStateFlow("ONNX/QNN")
    val engineMode: StateFlow<String> = _engineMode.asStateFlow()
    private val javaScriptRuntime = JavaScriptToolRuntime()
    private var isLoadingEngine = false
    // ONNX/QNN and llama.cpp both own process-wide HTP/FastRPC resources.
    // Never initialize or destroy either runtime concurrently with the other.
    private val engineLifecycleMutex = Mutex()

    private val toolSystemPrompt = """
        你是运行在 Android 手机上的 Gemma 编程助手。你可以通过 javascript 工具执行 JavaScript，并根据真实执行结果修复代码。
        当用户要求编写算法、调试代码或验证正确性时，优先编写可执行代码并调用工具测试，不要只凭推理声称测试通过。
        你可以调用 Gemma 4 原生 javascript 工具。仅当需要执行代码、验证结果或计算时调用。工具声明：<|tool>declaration:javascript{description:<|"|>在隔离 JavaScript 沙箱中执行 ES6 代码并返回输出<|"|>,parameters:{properties:{code:{description:<|"|>要执行的 JavaScript 源代码<|"|>,type:<|"|>STRING<|"|>}},required:[<|"|>code<|"|>],type:<|"|>OBJECT<|"|>}}<tool|>
        调用格式：<|tool_call>call:javascript{code:<|"|>console.log('hello');<|"|>}<tool_call|>。所有字符串值都必须使用 <|"|> 定界。先在 thought 通道中分析，再决定是否调用工具；收到工具结果后继续推理。
        工具可用 API：
        - console.log(...): 输出日志
        - assert(condition, message): 条件不成立时抛出错误
        - test(name, () => { ... }): 执行一个测试并记录 PASS/FAIL；测试内使用 assert
        - tools.randomInt(min, max): 生成包含边界的随机整数
        - tools.now(): 当前 Unix 毫秒时间戳
        - tools.assert / tools.test: 上述函数的 tools 命名空间别名
        JavaScript 使用 ES6。运行环境是隔离沙箱，不提供文件、网络、Android、Java 类访问。
        可以在一次测试脚本中实现算法、构造随机数据、检查不变量并打印汇总。测试失败时应分析错误，再发起下一次工具调用。
        每次只发起一个 javascript 工具调用。拿到工具结果后再回答。最多进行 3 轮工具调用。
        如果无需工具，直接用中文回答。工具调用标签外不要输出解释文字。
    """.trimIndent()

    init {
        loadEngine()
    }

    private fun loadEngine() {
        if (isLoadingEngine || engine != null) return
        isLoadingEngine = true
        Log.i(TAG, "Engine initialization started")
        viewModelScope.launch(Dispatchers.IO) {
            engineLifecycleMutex.withLock {
                try {
                    if (_engineMode.value == "ONNX/QNN" && ggufSession == null) {
                        initializeOnnxEngineLocked()
                    } else {
                        Log.i(TAG, "Skipping stale ONNX initialization after engine-mode change")
                    }
                } finally {
                    isLoadingEngine = false
                }
            }
        }
    }

    /** Caller must hold engineLifecycleMutex. */
    private fun publishSystemStatus(status: String) {
        _messages.value = _messages.value + ChatMessage(
            sender = ChatMessage.Sender.SYSTEM,
            text = status
        )
    }

    private suspend fun initializeOnnxEngineLocked() {
        try {
            _modelDownloadFailed.value = false
            publishSystemStatus("检查本地模型文件...")
            var found = GemmaEngine.findModelFiles(getApplication())
            if (found == null) {
                _isDownloadingModels.value = true
                publishSystemStatus("正在从 ModelScope 下载 Gemma 4 模型...")
                ModelScopeModelDownloader.download(getApplication()) { progress ->
                    _downloadProgress.value = progress
                }
                found = GemmaEngine.findModelFiles(getApplication())
                if (found == null) throw IllegalStateException("模型下载完成，但未找到完整模型文件")
            }
            publishSystemStatus("正在初始化 QNN HTP / CPU 引擎...")
            val eng = GemmaEngine.initialize(getApplication(), useQnnHtp = true)
            engine = eng
            _isEngineReady.value = true
            publishSystemStatus("引擎已就绪，运行后端：${eng.backendName}")
            Log.i(TAG, "Engine ready backend=${eng.backendName}")
        } catch (e: Exception) {
            Log.e(TAG, "Engine initialization failed", e)
            _modelDownloadFailed.value = GemmaEngine.findModelFiles(getApplication()) == null
            publishSystemStatus(if (_modelDownloadFailed.value) {
                "模型下载失败: ${e.message ?: "未知错误"}"
            } else {
                "引擎初始化失败: ${e.message}"
            })
        } finally {
            _isDownloadingModels.value = false
        }
    }

    fun retryModelDownload() {
        if (engine == null && !_isDownloadingModels.value) loadEngine()
    }

    fun useOnnxEngine() {
        if (_isGenerating.value) return
        _engineMode.value = "ONNX/QNN"
        viewModelScope.launch(Dispatchers.IO) {
            engineLifecycleMutex.withLock {
                try {
                    ggufAudioEncoder?.close()
                    ggufAudioEncoder = null
                    ggufSession?.close()
                    ggufSession = null
                    _engineMode.value = "ONNX/QNN"
                    if (engine == null) {
                        isLoadingEngine = true
                        initializeOnnxEngineLocked()
                    } else {
                        publishSystemStatus("已切换到 ONNX/QNN，引擎后端：${engine?.backendName ?: "ONNX/QNN"}")
                    }
                } finally {
                    isLoadingEngine = false
                }
            }
        }
    }

    fun useGgufEngine() {
        if (_isGenerating.value) return
        _engineMode.value = "GGUF"
        viewModelScope.launch(Dispatchers.IO) {
            engineLifecycleMutex.withLock {
              try {
                val switchStartNanos = System.nanoTime()
                publishSystemStatus("检查 GGUF / ${BuildConfig.GGML_HEXAGON_BACKEND} 运行时...")
                val modelDirs = listOfNotNull(
                    getApplication<Application>().getExternalFilesDir(null)?.let { File(it, "models") },
                    File(getApplication<Application>().filesDir, "models")
                )
                val model = modelDirs.asSequence()
                    .flatMap { dir -> dir.listFiles()?.asSequence().orEmpty() }
                    .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) &&
                        it.length() > 1024L * 1024L && it.canRead() }
                    .sortedBy { it.name }
                    .firstOrNull()
                    ?: throw IllegalStateException("未找到 GGUF。请将 .gguf 模型文件放入 ${modelDirs.first().absolutePath}/")
                Log.i(TAG, "GGUF switch model=${model.name} bytes=${model.length()} path=${model.absolutePath}")
                if (ggufSession == null) {
                    // Tear down QNN before llama_backend_init() opens HTP and reserves
                    // the FastRPC mempool. Closing it after model load is too late.
                    publishSystemStatus("正在释放 ONNX/QNN 资源...")
                    val closeStart = System.nanoTime()
                    engine?.close()
                    Log.i(TAG, "GGUF switch stage=onnx_close elapsedMs=${(System.nanoTime() - closeStart) / 1_000_000}")
                    engine = null
                    _isEngineReady.value = false
                    // Must run before NativeLlamaSession initializes System.loadLibrary().
                    publishSystemStatus("正在准备 Hexagon / FastRPC 运行时...")
                    val assetsStart = System.nanoTime()
                    HexagonRuntimeAssets.prepare(getApplication(), BuildConfig.GGML_HEXAGON_BACKEND)
                    Log.i(TAG, "GGUF switch stage=runtime_assets elapsedMs=${(System.nanoTime() - assetsStart) / 1_000_000}")
                    val memoryInfo = ActivityManager.MemoryInfo()
                    val activityManager = getApplication<Application>()
                        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    activityManager.getMemoryInfo(memoryInfo)
                    val availableMiB = memoryInfo.availMem / (1024L * 1024L)
                    val totalMiB = memoryInfo.totalMem / (1024L * 1024L)
                    publishSystemStatus("即将调用 llama_model_load_from_file：系统当前可用空闲内存 ${availableMiB} MiB / ${totalMiB} MiB" +
                        if (memoryInfo.lowMemory) "（系统已处于低内存状态）" else "")
                    publishSystemStatus("正在加载 GGUF 权重（${model.length() / (1024 * 1024)} MiB），首次加载可能需要几分钟...")
                    val nativeStart = System.nanoTime()
                    _isLlamaModelLoading.value = true
                    val openedSession = try {
                        NativeLlamaSession.open(model, contextSize = 4096, threads = 4,
                            backend = BuildConfig.GGML_HEXAGON_BACKEND)
                    } finally {
                        _isLlamaModelLoading.value = false
                    }
                    val modelLoadDurationMs = openedSession.modelLoadDurationMs
                    Log.i(TAG, "GGUF switch stage=native_open elapsedMs=${(System.nanoTime() - nativeStart) / 1_000_000} modelLoadMs=$modelLoadDurationMs totalMs=${(System.nanoTime() - switchStartNanos) / 1_000_000}")
                    val loadDurationText = if (modelLoadDurationMs >= 1_000L) {
                        String.format(java.util.Locale.US, "%.1f 秒", modelLoadDurationMs / 1_000.0)
                    } else {
                        "$modelLoadDurationMs 毫秒"
                    }
                    publishSystemStatus("模型载入完成：llama_model_load_from_file() 耗时 $loadDurationText")
                    if (_engineMode.value != "GGUF") {
                        openedSession.close()
                        return@withLock
                    }
                    ggufSession = openedSession
                    val audioModelFile = GemmaEngine.findModelFiles(getApplication())?.audioModelFile
                    ggufAudioEncoder = audioModelFile?.let { audioFile ->
                        runCatching { Gemma4AudioEncoder(audioFile) }
                            .onFailure { Log.w(TAG, "GGUF audio encoder initialization failed", it) }
                            .getOrNull()
                    }
                }
                if (_engineMode.value != "GGUF") return@withLock
                _engineMode.value = "GGUF"
                _isEngineReady.value = true
                publishSystemStatus("GGUF 引擎已就绪：${model.name} · ${BuildConfig.GGML_HEXAGON_BACKEND}")
                publishSystemStatus(if (ggufAudioEncoder != null) {
                    "GGUF 语音输入已就绪：Gemma 4 音频编码器 → llama.cpp 音频嵌入解码"
                } else {
                    "GGUF 文字与 JavaScript 工具已就绪；未找到 ONNX 音频编码器，语音输入暂不可用。"
                })
                Log.i(TAG, "GGUF switch complete totalMs=${(System.nanoTime() - switchStartNanos) / 1_000_000}")
                _messages.value = _messages.value + ChatMessage(sender = ChatMessage.Sender.SYSTEM,
                    text = "已切换到 GGUF 引擎。JavaScript 工具调用已启用；语音输入直接使用 Gemma 4 音频编码器，并将音频嵌入送入 llama.cpp。")
            } catch (error: Throwable) {
                Log.e(TAG, "GGUF engine initialization failed", error)
                _engineMode.value = "ONNX/QNN"
                if (engine == null && ggufSession == null) {
                    isLoadingEngine = true
                    initializeOnnxEngineLocked()
                    isLoadingEngine = false
                }
                publishSystemStatus("GGUF 初始化失败: ${error.message}")
                _messages.value = _messages.value + ChatMessage(sender = ChatMessage.Sender.SYSTEM,
                    text = "GGUF 引擎未切换：${error.message ?: error.javaClass.simpleName}")
              }
            }
        }
    }

    fun sendMessage(promptText: String, audioSamples: FloatArray? = null) {
        if ((promptText.isBlank() && audioSamples == null) || _isGenerating.value) return
        if (_engineMode.value == "GGUF") {
            sendGgufMessage(promptText.trim(), audioSamples)
            return
        }

        val audioTokenCount = audioSamples?.let {
            Gemma4AudioFeatureExtractor.softTokenCountForSamples(it.size)
        } ?: 0
        if (audioSamples != null && audioTokenCount <= 0) {
            _messages.value = _messages.value + ChatMessage(
                sender = ChatMessage.Sender.SYSTEM,
                text = "录音太短，无法识别，请重新录制。"
            )
            return
        }
        val modelPrompt = if (audioSamples != null) {
            "Transcribe the following speech segment in Chinese into Chinese text.\n\n" +
                "Follow these specific instructions for formatting the answer:\n" +
                "* Only output the transcription, with no newlines.\n" +
                "* When transcribing numbers, write the digits.\n\n" +
                "<|audio>" + "<|audio|>".repeat(audioTokenCount) + "<audio|>"
        } else promptText.trim()
        val displayText = if (audioSamples != null) {
            "🎤 语音输入（${String.format(java.util.Locale.US, "%.1f", audioSamples.size / 16000f)} 秒）"
        } else promptText.trim()

        val userMsg = ChatMessage(sender = ChatMessage.Sender.USER, text = displayText)
        val initialModelMsg = ChatMessage(sender = ChatMessage.Sender.MODEL, text = "")

        _messages.value = _messages.value + userMsg + initialModelMsg
        _isGenerating.value = true
        _generationSpeed.value = 0f
        _generatedTokens.value = 0

        val currentEngine = engine
        if (currentEngine == null) {
            updateLastMessage("错误: Gemma 引擎尚未初始化成功，请等待模型下载完成或点击重试。")
            _isGenerating.value = false
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val responseBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            val toolCallItems = mutableListOf<ToolCallInfo>()
            val generationStartNanos = System.nanoTime()
            var totalTokens = 0
            var conversationPrompt = currentEngine.tokenizer.formatChatPrompt(modelPrompt, toolSystemPrompt)
            try {
                var toolRounds = 0
                while (true) {
                    val roundBuilder = StringBuilder()
                    val streamParser = StreamingOutputStateMachine()
                    var thoughtChannelEntered = false
                    var thoughtChannelExited = false
                    val promptTokenIds = currentEngine.tokenizer.encode(conversationPrompt)
                    val thinkTokenId = currentEngine.tokenizer.encode("<|think|>").firstOrNull()
                    val thinkTokenPresent = thinkTokenId != null && promptTokenIds.contains(thinkTokenId)
                    Log.i(TAG, "THINK_TRACE round_start round=${toolRounds + 1} promptTokens=${promptTokenIds.size} thinkTokenPresent=$thinkTokenPresent")
                    currentEngine.generateStream(conversationPrompt, maxNewTokens = 768, audioSamples = audioSamples).collect { chunk ->
                        roundBuilder.append(chunk.text)
                        totalTokens += 1
                        _generationSpeed.value = chunk.tokensPerSecond
                        _generatedTokens.value = totalTokens
                        val streamSnapshot = streamParser.append(chunk.text)
                        val visible = streamSnapshot.visibleText
                        val parsedThinking = streamSnapshot.thinkingText
                        val thinking = thinkingBuilder.toString() + parsedThinking
                        if (!thoughtChannelEntered && roundBuilder.contains("<|channel>thought")) {
                            thoughtChannelEntered = true
                            Log.i(TAG, "THINK_CHANNEL_ENTER round=${toolRounds + 1} token=$totalTokens")
                        }
                        if (thoughtChannelEntered && !thoughtChannelExited && roundBuilder.contains("<channel|>")) {
                            thoughtChannelExited = true
                            Log.i(TAG, "THINK_CHANNEL_EXIT round=${toolRounds + 1} token=$totalTokens thoughtChars=${parsedThinking.length}")
                        }
                        if (thoughtChannelEntered && !thoughtChannelExited && totalTokens % 10 == 0) {
                            Log.d(TAG, "THINK_CONTENT round=${toolRounds + 1} chars=${parsedThinking.length}")
                        }
                        if (thoughtChannelExited && visible.isNotEmpty() && totalTokens % 10 == 0) {
                            Log.d(TAG, "FINAL_CONTENT round=${toolRounds + 1} chars=${visible.length}")
                        }
                        // Bounded token trace: expose the actual model channel markers without flooding Logcat.
                        if (totalTokens <= 120 || totalTokens % 10 == 0 || chunk.text.contains("<|")) {
                            Log.d(TAG, "THINK_TRACE token=$totalTokens raw=${chunk.text.replace("\n", "\\n").take(160)} roundChars=${roundBuilder.length} thinkChars=${thinking.length} visibleChars=${visible.length} markers=${listOf("<|think|>", "<|channel>thought", "<|channel>analysis", "<channel|>").filter { roundBuilder.contains(it) }}")
                        }
                        val preview = streamSnapshot.toolPreview
                        val liveTools = toolCallItems.toMutableList()
                        if (preview != null && liveTools.lastOrNull()?.status == "运行中") {
                            liveTools[liveTools.lastIndex] = liveTools.last().copy(arguments = preview)
                        } else if (preview != null) {
                            liveTools.add(ToolCallInfo("javascript", preview))
                        }
                        updateLastMessage(responseBuilder.toString() + visible, thinking, liveTools)
                    }
                    // Release any suffix that was buffered only because it could still
                    // have become a control marker (for example a final lone '<').
                    val finalSnapshot = streamParser.finish()
                    val finalThinking = thinkingBuilder.toString() + finalSnapshot.thinkingText
                    val finalTools = toolCallItems.toMutableList()
                    finalSnapshot.toolPreview?.let { preview ->
                        if (finalTools.lastOrNull()?.status == "运行中") {
                            finalTools[finalTools.lastIndex] = finalTools.last().copy(arguments = preview)
                        } else if (finalTools.isEmpty() || finalTools.last().arguments != preview) {
                            finalTools.add(ToolCallInfo("javascript", preview))
                        }
                    }
                    updateLastMessage(responseBuilder.toString() + finalSnapshot.visibleText, finalThinking, finalTools)

                    val roundText = roundBuilder.toString()
                    Log.d(TAG, "Generation round completed chars=${roundText.length}")
                    Log.i(TAG, "THINK_TRACE round_end round=${toolRounds + 1} rawChars=${roundText.length} rawHead=${roundText.take(700).replace("\n", "\\n")} rawTail=${roundText.takeLast(350).replace("\n", "\\n")} thinkChars=${ToolCallParser.thinkingText(roundText).length} visibleChars=${ToolCallParser.visibleText(roundText).length}")
                    val toolCall = try {
                        ToolCallParser.parse(roundText)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Tool-call parser failed", t)
                        throw t
                    }
                    if (toolCall == null || toolRounds >= 3) {
                        val roundThinking = ToolCallParser.thinkingText(roundText)
                        if (roundThinking.isNotBlank()) thinkingBuilder.append(roundThinking).append("\n\n")
                        responseBuilder.append(ToolCallParser.visibleText(roundText))
                        break
                    }

                    toolRounds++
                    Log.i(TAG, "JavaScript tool call round=$toolRounds codeChars=${toolCall.code.length}")
                    val preamble = ToolCallParser.visibleText(roundText)
                    if (preamble.isNotBlank()) responseBuilder.append(preamble).append("\n\n")
                    val roundThinking = ToolCallParser.thinkingText(roundText)
                    if (roundThinking.isNotBlank()) thinkingBuilder.append(roundThinking).append("\n\n")
                    val toolIndex = toolCallItems.size
                    toolCallItems.add(ToolCallInfo("javascript", ToolCallParser.toolCallPreview(roundText).orEmpty(), toolCall.code, "运行中"))
                    updateLastMessage(responseBuilder.toString(), thinkingBuilder.toString(), toolCallItems.toList())
                    val toolResult = javaScriptRuntime.execute(toolCall.code)
                    Log.i(TAG, "JavaScript tool completed success=${toolResult.success} outputChars=${toolResult.output.length}")
                    val status = if (toolResult.success) "success" else "error"
                    toolCallItems[toolIndex] = toolCallItems[toolIndex].copy(
                        status = if (toolResult.success) "成功" else "失败", output = toolResult.output
                    )
                    updateLastMessage(responseBuilder.toString(), thinkingBuilder.toString(), toolCallItems.toList())

                    // Native Gemma 4 tool responses stay in the same model turn.
                    // Preserve thought context across the tool handoff.
                    val nativeResponse = "<|tool_response>response:javascript{" +
                        "success:${toolResult.success},output:<|\"|>" +
                        toolResult.output.take(12_000).replace("<|\"|>", " ") +
                        "<|\"|>}<tool_response|>"
                    conversationPrompt += roundText + nativeResponse
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Generation/tool loop crashed", t)
                responseBuilder.append("\n[生成异常]: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                // QNN optrace CSVs are finalized when both model sessions are closed.
                runCatching { currentEngine.flushOptrace() }
                    .onFailure { Log.e(TAG, "QNN optrace flush failed", it) }
                val durationMs = ((System.nanoTime() - generationStartNanos) / 1_000_000L).coerceAtLeast(1L)
                val averageSpeed = totalTokens * 1000f / durationMs
                Log.i(TAG, "THINK_TRACE generation_finish tokens=$totalTokens thinkingChars=${thinkingBuilder.length} answerChars=${responseBuilder.length} error=${responseBuilder.contains("[生成异常]")}")
                finishLastMessage(responseBuilder.toString(), durationMs, totalTokens, averageSpeed, thinkingBuilder.toString(), toolCallItems.toList())
                _isGenerating.value = false
            }
        }
    }

    private fun sendGgufMessage(text: String, audioSamples: FloatArray? = null) {
        val session = ggufSession ?: run {
            _messages.value = _messages.value + ChatMessage(sender = ChatMessage.Sender.SYSTEM,
                text = "GGUF 会话未初始化，请重新选择 GGUF 引擎。")
            return
        }
        val audioTokenCount = audioSamples?.let {
            Gemma4AudioFeatureExtractor.softTokenCountForSamples(it.size)
        } ?: 0
        if (audioSamples != null && audioTokenCount <= 0) {
            _messages.value = _messages.value + ChatMessage(
                sender = ChatMessage.Sender.SYSTEM, text = "录音太短，无法识别，请重新录制。"
            )
            return
        }
        if (audioSamples != null && ggufAudioEncoder == null) {
            _messages.value = _messages.value + ChatMessage(
                sender = ChatMessage.Sender.SYSTEM,
                text = "GGUF 语音输入需要本地 audio_encoder_q4f16.onnx 和对应数据文件；请先完成 ONNX 模型下载。"
            )
            return
        }
        val modelInput = if (audioSamples != null) {
            "Transcribe the following speech segment in Chinese into Chinese text.\n\n" +
                "Follow these specific instructions for formatting the answer:\n" +
                "* Only output the transcription, with no newlines.\n" +
                "* When transcribing numbers, write the digits.\n\n" +
                "<|audio>" + "<|audio|>".repeat(audioTokenCount) + "<audio|>"
        } else text
        val displayText = audioSamples?.let {
            "🎤 语音输入（${String.format(java.util.Locale.US, "%.1f", it.size / 16000f)} 秒）"
        } ?: text
        val conversationMessages = mutableListOf(NativeChatMessage("system", toolSystemPrompt))
        _messages.value.filter { it.sender == ChatMessage.Sender.USER || it.sender == ChatMessage.Sender.MODEL }
            .filter { it.text.isNotBlank() }
            .forEach { message ->
                conversationMessages.add(NativeChatMessage(
                    if (message.sender == ChatMessage.Sender.USER) "user" else "assistant",
                    message.text
                ))
            }
        conversationMessages.add(NativeChatMessage("user", modelInput))
        _messages.value = _messages.value + ChatMessage(sender = ChatMessage.Sender.USER, text = displayText) +
            ChatMessage(sender = ChatMessage.Sender.MODEL, text = "")
        _isGenerating.value = true
        _generationSpeed.value = 0f
        _generatedTokens.value = 0
        viewModelScope.launch(Dispatchers.IO) {
            val start = System.nanoTime()
            var tokenCount = 0
            var errorText: String? = null
            val responseBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            val toolCallItems = mutableListOf<ToolCallInfo>()
            var toolRounds = 0
            try {
                val audioEmbeddings = audioSamples?.let { samples ->
                    ggufAudioEncoder?.encode(samples)
                        ?: throw IllegalStateException("GGUF audio encoder is not initialized")
                }
                if (audioEmbeddings != null) {
                    Log.i(TAG, "GGUF audio encoded rows=${audioEmbeddings.rows} dim=${audioEmbeddings.dimension}")
                }
                while (true) {
                    // Keep Gemma control markers out of the visible answer while
                    // retaining tool-call text for the structured parser below.
                    val roundBuilder = StringBuilder()
                    val streamParser = StreamingOutputStateMachine()
                    var roundError: String? = null
                    session.generate(
                        conversationMessages,
                        maxTokens = 768,
                        audioEmbeddings = audioEmbeddings,
                        listener = object : NativeLlamaSession.Listener {
                            override fun onToken(text: String) {
                                roundBuilder.append(text)
                                tokenCount++
                                val elapsed = ((System.nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(0.001)
                                _generatedTokens.value = tokenCount
                                _generationSpeed.value = (tokenCount / elapsed).toFloat()
                                val snapshot = streamParser.append(text)
                                val liveTools = toolCallItems.toMutableList()
                                snapshot.toolPreview?.let { preview ->
                                    if (liveTools.lastOrNull()?.status == "运行中") {
                                        liveTools[liveTools.lastIndex] = liveTools.last().copy(arguments = preview)
                                    } else {
                                        liveTools.add(ToolCallInfo("javascript", preview, status = "运行中"))
                                    }
                                }
                                updateLastMessage(
                                    newText = responseBuilder.toString() + snapshot.visibleText,
                                    thinking = thinkingBuilder.toString() + snapshot.thinkingText,
                                    toolCalls = liveTools
                                )
                            }

                            override fun onComplete(status: String) {
                                Log.i(TAG, "GGUF generation round=${toolRounds + 1} complete: $status")
                            }

                            override fun onError(message: String) { roundError = message }
                        }
                    )

                    val roundText = roundBuilder.toString()
                    val finalSnapshot = streamParser.finish()
                    val toolCall = if (roundError == null) ToolCallParser.parse(roundText) else null
                    if (roundError != null) {
                        responseBuilder.append(finalSnapshot.visibleText)
                        if (finalSnapshot.thinkingText.isNotBlank()) thinkingBuilder.append(finalSnapshot.thinkingText)
                        errorText = roundError
                        break
                    }
                    if (toolCall == null) {
                        if (finalSnapshot.thinkingText.isNotBlank()) thinkingBuilder.append(finalSnapshot.thinkingText)
                        responseBuilder.append(finalSnapshot.visibleText)
                        break
                    }
                    if (toolRounds >= 3) {
                        responseBuilder.append("\n[已达到 JavaScript 工具调用次数上限，本次未执行额外调用。]")
                        break
                    }

                    toolRounds++
                    val preamble = finalSnapshot.visibleText
                    if (preamble.isNotBlank()) responseBuilder.append(preamble).append("\n\n")
                    val roundThinking = finalSnapshot.thinkingText
                    if (roundThinking.isNotBlank()) thinkingBuilder.append(roundThinking).append("\n\n")
                    val toolIndex = toolCallItems.size
                    toolCallItems.add(ToolCallInfo(
                        "javascript", ToolCallParser.toolCallPreview(roundText).orEmpty(),
                        toolCall.code, "运行中"
                    ))
                    updateLastMessage(responseBuilder.toString(), thinkingBuilder.toString(), toolCallItems.toList())
                    Log.i(TAG, "GGUF JavaScript tool call round=$toolRounds codeChars=${toolCall.code.length}")
                    val toolResult = javaScriptRuntime.execute(toolCall.code)
                    Log.i(TAG, "GGUF JavaScript tool completed success=${toolResult.success} outputChars=${toolResult.output.length}")
                    toolCallItems[toolIndex] = toolCallItems[toolIndex].copy(
                        status = if (toolResult.success) "成功" else "失败",
                        output = toolResult.output
                    )
                    updateLastMessage(responseBuilder.toString(), thinkingBuilder.toString(), toolCallItems.toList())
                    conversationMessages.add(NativeChatMessage("assistant", roundText))
                    conversationMessages.add(NativeChatMessage(
                        "tool",
                        "success: ${toolResult.success}\noutput:\n${toolResult.output.take(12_000)}",
                        toolName = "javascript"
                    ))
                }
            } catch (error: Throwable) {
                errorText = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "GGUF generation/tool loop failed", error)
            } finally {
                val duration = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                val finalText = responseBuilder.toString() +
                    (errorText?.let { "\n[GGUF 错误] $it" } ?: "")
                finishLastMessage(
                    finalText,
                    duration,
                    tokenCount,
                    tokenCount * 1000f / duration,
                    thinking = thinkingBuilder.toString(),
                    toolCalls = toolCallItems.toList()
                )
                _isGenerating.value = false
            }
        }
    }

    private fun updateLastMessage(newText: String, thinking: String? = null, toolCalls: List<ToolCallInfo>? = null) {
        val currentList = _messages.value.toMutableList()
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.sender == ChatMessage.Sender.MODEL) {
                currentList[currentList.size - 1] = last.copy(
                    text = newText, thinking = thinking ?: last.thinking, toolCalls = toolCalls ?: last.toolCalls
                )
                _messages.value = currentList
            }
        }
    }

    private fun finishLastMessage(
        finalText: String,
        durationMs: Long,
        tokenCount: Int,
        averageSpeed: Float,
        thinking: String,
        toolCalls: List<ToolCallInfo>
    ) {
        val currentList = _messages.value.toMutableList()
        val lastIndex = currentList.lastIndex
        if (lastIndex >= 0 && currentList[lastIndex].sender == ChatMessage.Sender.MODEL) {
            currentList[lastIndex] = currentList[lastIndex].copy(
                text = finalText.ifEmpty { "（本次没有生成文本）" },
                thinking = thinking,
                toolCalls = toolCalls,
                generationDurationMs = durationMs,
                generatedTokens = tokenCount,
                averageTokensPerSecond = averageSpeed
            )
            _messages.value = currentList
        }
    }

    fun clearHistory() {
        _messages.value = listOf(
            ChatMessage(
                sender = ChatMessage.Sender.SYSTEM,
                text = "对话历史已清空。"
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { ggufAudioEncoder?.close() }
        ggufAudioEncoder = null
        engine?.close()
        ggufSession?.let { session ->
            session.cancel()
            // Native destroy waits for any in-flight decode; never block the main thread.
            CoroutineScope(Dispatchers.IO).launch { session.close() }
        }
    }

    companion object {
        private const val TAG = "GemmaChat"
    }
}
