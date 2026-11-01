package com.example.gemmaapp.ui

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val thinking: String = "",
    val toolCalls: List<ToolCallInfo> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val generationDurationMs: Long? = null,
    val generatedTokens: Int? = null,
    val averageTokensPerSecond: Float? = null
) {
    enum class Sender {
        USER,
        MODEL,
        SYSTEM
    }
}

data class ToolCallInfo(
    val name: String,
    val arguments: String,
    val code: String = "",
    val status: String = "运行中",
    val output: String = ""
)
