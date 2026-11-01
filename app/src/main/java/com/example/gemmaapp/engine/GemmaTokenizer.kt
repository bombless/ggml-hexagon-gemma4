package com.example.gemmaapp.engine

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * High-performance binary tokenizer for Gemma 4 (256k vocab, BPE).
 * Loads in ~100ms from compact binary files.
 */
class GemmaTokenizer private constructor(
    val vocab: Map<String, Int>,
    private val tokensList: Array<ByteArray>,
    private val merges: Map<Long, Int>,
    private val specialTokens: Map<String, Int>
) {
    companion object {
        const val BOS_TOKEN_ID = 2
        const val EOS_TOKEN_ID = 1
        const val PAD_TOKEN_ID = 0
        const val UNK_TOKEN_ID = 3

        private const val TOKENS_VOCAB_SHA256 = "8a5224e20d6409c253866c0a8b007822a630cdbdc6fb584608c01695a6081d9d"
        private const val BPE_MODEL_SHA256 = "00cc235e24ee32e6df96e6ec76b41c6b9333a4b6a1094c0e0d2d9957d7292972"

        const val SOT_TOKEN = "<|turn>"
        const val EOT_TOKEN = "<turn|>"

        fun load(context: Context): GemmaTokenizer {
            val modelDirs = listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, "models") },
                File(context.filesDir, "models")
            )
            val modelDir = modelDirs.firstOrNull { dir ->
                File(dir, "tokens_vocab.bin").isFile && File(dir, "bpe_model.bin").isFile
            } ?: throw IllegalStateException(
                "Tokenizer files are missing. Place tokens_vocab.bin and bpe_model.bin in " +
                    "${modelDirs.first().absolutePath} (see docs/model-setup.md)."
            )
            val vocabFile = File(modelDir, "tokens_vocab.bin")
            val bpeFile = File(modelDir, "bpe_model.bin")
            requireSha256(vocabFile, TOKENS_VOCAB_SHA256)
            requireSha256(bpeFile, BPE_MODEL_SHA256)

            // 1. Load tokens_vocab.bin
            val vocabList = ArrayList<ByteArray>(262144)
            vocabFile.inputStream().use { inputStream ->
                val header = ByteArray(4)
                inputStream.read(header)
                val numTokens = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                val lenBuf = ByteArray(2)
                for (i in 0 until numTokens) {
                    inputStream.read(lenBuf)
                    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val tokenBytes = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val r = inputStream.read(tokenBytes, read, len - read)
                        if (r <= 0) break
                        read += r
                    }
                    vocabList.add(tokenBytes)
                }
            }

            // 2. Load bpe_model.bin for vocab strings and merges
            val vocabMap = HashMap<String, Int>(262144)
            val mergesMap = HashMap<Long, Int>(520000)

            bpeFile.inputStream().use { inputStream ->
                val buf4 = ByteArray(4)
                val buf2 = ByteArray(2)

                fun readExact(b: ByteArray) {
                    var r = 0
                    while (r < b.size) {
                        val n = inputStream.read(b, r, b.size - r)
                        if (n <= 0) break
                        r += n
                    }
                }

                readExact(buf4)
                val vocabLen = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int
                for (i in 0 until vocabLen) {
                    readExact(buf2)
                    val l = ByteBuffer.wrap(buf2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val sb = ByteArray(l)
                    readExact(sb)
                    val s = String(sb, Charsets.UTF_8)
                    vocabMap[s] = i
                }

                readExact(buf4)
                val mergesLen = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int
                for (i in 0 until mergesLen) {
                    readExact(buf2)
                    val l1 = ByteBuffer.wrap(buf2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val b1 = ByteArray(l1)
                    readExact(b1)
                    val s1 = String(b1, Charsets.UTF_8)

                    readExact(buf2)
                    val l2 = ByteBuffer.wrap(buf2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val b2 = ByteArray(l2)
                    readExact(b2)
                    val s2 = String(b2, Charsets.UTF_8)

                    val id1 = vocabMap[s1]
                    val id2 = vocabMap[s2]
                    if (id1 != null && id2 != null) {
                        val key = (id1.toLong() shl 32) or (id2.toLong() and 0xFFFFFFFFL)
                        mergesMap[key] = i
                    }
                }
            }

            val specials = mapOf(
                "<bos>" to 2,
                "<eos>" to 1,
                "<pad>" to 0,
                "<unk>" to 3,
                "<mask>" to 4,
                "<|turn>" to (vocabMap["<|turn>"] ?: 106),
                "<turn|>" to (vocabMap["<turn|>"] ?: 107),
                "<|think|>" to (vocabMap["<|think|>"] ?: 108),
                "<|channel>" to (vocabMap["<|channel>"] ?: 109),
                "<channel|>" to (vocabMap["<channel|>"] ?: 110),
                "<|tool>" to (vocabMap["<|tool>"] ?: 111),
                "<tool|>" to (vocabMap["<tool|"] ?: 112),
                "<|tool_call>" to (vocabMap["<|tool_call>"] ?: 113),
                "<tool_call|>" to (vocabMap["<tool_call|"] ?: 114),
                "<|tool_response>" to (vocabMap["<|tool_response>"] ?: 115),
                "<tool_response|>" to (vocabMap["<tool_response|"] ?: 116),
                "<|\"|>" to (vocabMap["<|\"|>"] ?: 117),
                "<|audio>" to (vocabMap["<|audio>"] ?: 256000),
                "<|audio|>" to (vocabMap["<|audio|>"] ?: 258881),
                "<audio|>" to (vocabMap["<audio|>"] ?: 258883)
            )

            return GemmaTokenizer(vocabMap, vocabList.toTypedArray(), mergesMap, specials)
        }

        private fun requireSha256(file: File, expected: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expected, ignoreCase = true)) {
                "${file.name} SHA-256 mismatch: expected $expected, got $actual. Replace the file."
            }
        }
    }

    private val specialKeysSorted = specialTokens.keys.sortedByDescending { it.length }

    /**
     * Formats chat messages into Gemma 4 prompt format:
     * <bos><|turn>user\n{prompt}<turn|>\n<|turn>model\n
     */
    fun formatChatPrompt(userPrompt: String, systemPrompt: String? = null): String {
        val sb = StringBuilder()
        sb.append("<bos>")
        if (!systemPrompt.isNullOrBlank()) {
            // Gemma 4 E2B/E4B enables reasoning with <|think|> at the start
            // of the system message, not with an assistant analysis header.
            sb.append("<|turn>system\n<|think|>\n")
                .append(systemPrompt.trim()).append("<turn|>\n")
        }
        sb.append("<|turn>user\n").append(userPrompt.trim()).append("<turn|>\n")
        sb.append("<|turn>model\n")
        return sb.toString()
    }

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()
        val result = ArrayList<Int>()

        // Split on special tokens
        var remaining = text
        while (remaining.isNotEmpty()) {
            var matchedSpecial: String? = null
            var matchedIndex = -1

            for (sp in specialKeysSorted) {
                val idx = remaining.indexOf(sp)
                if (idx != -1 && (matchedIndex == -1 || idx < matchedIndex)) {
                    matchedIndex = idx
                    matchedSpecial = sp
                }
            }

            if (matchedSpecial != null && matchedIndex >= 0) {
                if (matchedIndex > 0) {
                    encodeOrdinary(remaining.substring(0, matchedIndex), result)
                }
                result.add(specialTokens.getValue(matchedSpecial))
                remaining = remaining.substring(matchedIndex + matchedSpecial.length)
            } else {
                encodeOrdinary(remaining, result)
                break
            }
        }

        return result.toIntArray()
    }

    private fun encodeOrdinary(rawText: String, out: ArrayList<Int>) {
        if (rawText.isEmpty()) return
        val text = rawText.replace(' ', '\u2581')
        val symbols = ArrayList<String>(text.length)
        val symbolIds = ArrayList<Int>(text.length)

        for (i in 0 until text.length) {
            val ch = text[i].toString()
            val id = vocab[ch]
            if (id != null) {
                symbols.add(ch)
                symbolIds.add(id)
            } else {
                val utf8 = ch.toByteArray(Charsets.UTF_8)
                for (b in utf8) {
                    val hex = String.format("<0x%02X>", b.toInt() and 0xFF)
                    symbols.add(hex)
                    symbolIds.add(vocab[hex] ?: UNK_TOKEN_ID)
                }
            }
        }

        while (symbolIds.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (i in 0 until symbolIds.size - 1) {
                val key = (symbolIds[i].toLong() shl 32) or (symbolIds[i + 1].toLong() and 0xFFFFFFFFL)
                val rank = merges[key]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }

            if (bestIdx == -1) break

            val mergedSymbol = symbols[bestIdx] + symbols[bestIdx + 1]
            val mergedId = vocab[mergedSymbol] ?: break

            symbols[bestIdx] = mergedSymbol
            symbols.removeAt(bestIdx + 1)
            symbolIds[bestIdx] = mergedId
            symbolIds.removeAt(bestIdx + 1)
        }

        out.addAll(symbolIds)
    }

    fun decodeToken(tokenId: Int): String {
        if (tokenId < 0 || tokenId >= tokensList.size) return ""
        val bytes = tokensList[tokenId]
        return String(bytes, Charsets.UTF_8)
    }

    /** Returns raw token bytes so callers can decode UTF-8 across token boundaries. */
    fun decodeTokenBytes(tokenId: Int): ByteArray =
        if (tokenId in tokensList.indices) tokensList[tokenId] else ByteArray(0)

    fun decodeTokens(tokenIds: List<Int>): String {
        var totalLen = 0
        for (id in tokenIds) {
            if (id in 0 until tokensList.size) {
                totalLen += tokensList[id].size
            }
        }
        val combined = ByteArray(totalLen)
        var offset = 0
        for (id in tokenIds) {
            if (id in 0 until tokensList.size) {
                val b = tokensList[id]
                System.arraycopy(b, 0, combined, offset, b.size)
                offset += b.size
            }
        }
        return String(combined, Charsets.UTF_8)
    }
}
