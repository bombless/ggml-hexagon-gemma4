package com.example.gemmaapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val backendStatus by viewModel.backendStatus.collectAsState()
    val generationSpeed by viewModel.generationSpeed.collectAsState()
    val generatedTokens by viewModel.generatedTokens.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isDownloadingModels by viewModel.isDownloadingModels.collectAsState()
    val modelDownloadFailed by viewModel.modelDownloadFailed.collectAsState()
    val isEngineReady by viewModel.isEngineReady.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val recordingStopRequested = remember { AtomicBoolean(false) }

    fun beginVoiceRecording() {
        val sampleRate = 16_000
        val maxSamples = sampleRate * 30
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Toast.makeText(context, "当前设备不支持 16 kHz 麦克风录音", Toast.LENGTH_LONG).show()
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 8192)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Toast.makeText(context, "无法初始化麦克风", Toast.LENGTH_LONG).show()
            return
        }
        recordingStopRequested.set(false)
        try {
            recorder.startRecording()
        } catch (error: Exception) {
            recorder.release()
            Toast.makeText(context, "启动录音失败：${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        isRecording = true
        coroutineScope.launch(Dispatchers.IO) {
            val pcm = ArrayList<Short>(maxSamples)
            val buffer = ShortArray(4096)
            try {
                while (!recordingStopRequested.get() && pcm.size < maxSamples) {
                    val count = recorder.read(buffer, 0, minOf(buffer.size, maxSamples - pcm.size))
                    if (count > 0) {
                        for (i in 0 until count) pcm.add(buffer[i])
                    } else if (count < 0) {
                        throw IllegalStateException("麦克风读取失败：$count")
                    }
                }
            } catch (error: Exception) {
                Log.e("GemmaVoice", "Audio recording failed", error)
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                } catch (_: Exception) { }
                recorder.release()
            }
            val samples = FloatArray(pcm.size) { pcm[it].toFloat() / 32768f }
            withContext(Dispatchers.Main) {
                isRecording = false
                if (samples.size >= 4800) {
                    viewModel.sendMessage("", samples)
                } else {
                    Toast.makeText(context, "录音太短，请重新录制", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceRecording()
        else Toast.makeText(context, "需要麦克风权限才能识别语音", Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        onDispose { recordingStopRequested.set(true) }
    }

    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.text?.length,
        messages.lastOrNull()?.thinking?.length,
        messages.lastOrNull()?.toolCalls?.lastOrNull()?.arguments?.length,
        messages.lastOrNull()?.toolCalls?.lastOrNull()?.output?.length
    ) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Gemma 4 Android",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = backendStatus,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear History")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入你想问 Gemma 4 的内容...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            if (isRecording) {
                                recordingStopRequested.set(true)
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                beginVoiceRecording()
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !isGenerating && isEngineReady
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "停止录音并识别" else "开始语音输入",
                            tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !isGenerating) {
                                val text = inputText
                                inputText = ""
                                viewModel.sendMessage(text)
                                coroutineScope.launch {
                                    listState.animateScrollToItem(messages.size)
                                }
                            }
                        },
                        enabled = inputText.isNotBlank() && !isGenerating && !isRecording && isEngineReady
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isDownloadingModels || modelDownloadFailed) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        val progress = downloadProgress
                        Text(
                            text = if (modelDownloadFailed) "模型下载未完成" else "正在下载 ${progress?.fileName ?: "Gemma 4 模型"}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        if (progress != null) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "文件 ${progress.fileIndex}/${progress.totalFiles} · ${String.format(java.util.Locale.US, "%.1f", progress.downloadedBytes / 1_073_741_824.0)} / ${String.format(java.util.Locale.US, "%.2f", progress.totalBytes / 1_073_741_824.0)} GB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (modelDownloadFailed) {
                            Text(backendStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { viewModel.retryModelDownload() }) { Text("重试下载") }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    val isActiveReply = isGenerating && msg.id == messages.lastOrNull()?.id
                    MessageBubble(
                        message = msg,
                        isGenerating = isActiveReply,
                        generationSpeed = generationSpeed,
                        generatedTokens = generatedTokens
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    isGenerating: Boolean = false,
    generationSpeed: Float = 0f,
    generatedTokens: Int = 0
) {
    val isUser = message.sender == ChatMessage.Sender.USER
    val isSystem = message.sender == ChatMessage.Sender.SYSTEM

    val alignment = when {
        isUser -> Alignment.CenterEnd
        isSystem -> Alignment.Center
        else -> Alignment.CenterStart
    }

    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primary
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isSystem) 340.dp else 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!isUser) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isSystem) "系统提示" else "Gemma 4",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                    if (!isSystem && isGenerating) {
                        Text(
                            text = "${String.format(java.util.Locale.US, "%.1f", generationSpeed)} token/s · $generatedTokens tokens",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
            }
            if (isUser || isSystem) {
                Text(
                    text = message.text.ifEmpty { "思考中..." },
                    fontSize = 14.sp,
                    color = textColor,
                    lineHeight = 20.sp
                )
            } else {
                if (message.thinking.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("💭 思考过程", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer {
                                Text(message.thinking, fontSize = 12.sp, lineHeight = 18.sp, color = textColor.copy(alpha = 0.88f))
                            }
                        }
                    }
                }
                message.toolCalls.forEachIndexed { index, tool ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("🛠 工具调用 #${index + 1} · ${tool.name}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(tool.status, fontSize = 11.sp, color = if (tool.status == "失败") MaterialTheme.colorScheme.error else textColor)
                            }
                            val displayedCode = tool.code.ifBlank { tool.arguments }
                            Log.d("CodeHighlight", "Tool preview name=${tool.name}, codeChars=${displayedCode.length}, fromCode=${tool.code.isNotBlank()}")
                            if (displayedCode.isNotBlank()) {
                                Text("JavaScript 代码", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.7f))
                                SelectionContainer {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            highlightJavaScript(displayedCode),
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = textColor
                                        )
                                    }
                                }
                            }
                            if (tool.output.isNotBlank()) {
                                HorizontalDivider()
                                Text("执行结果", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.7f))
                                SelectionContainer {
                                    Text(tool.output, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = textColor)
                                }
                            }
                        }
                    }
                }
                MarkdownMessage(content = message.text.ifEmpty { "思考中..." }, textColor = textColor)
            }
            if (!isUser && !isSystem && message.generationDurationMs != null) {
                val durationSeconds = message.generationDurationMs / 1000f
                val durationLabel = if (durationSeconds >= 60f) {
                    String.format(java.util.Locale.US, "%.1f 分钟", durationSeconds / 60f)
                } else {
                    String.format(java.util.Locale.US, "%.2f 秒", durationSeconds)
                }
                Text(
                    text = "耗时 $durationLabel  ·  ${message.generatedTokens ?: 0} tokens  ·  平均 ${String.format(java.util.Locale.US, "%.1f", message.averageTokensPerSecond ?: 0f)} token/s",
                    fontSize = 10.sp,
                    color = textColor.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
