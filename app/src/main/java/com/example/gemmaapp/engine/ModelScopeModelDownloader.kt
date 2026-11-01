package com.example.gemmaapp.engine

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelDownloadProgress(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileIndex: Int,
    val totalFiles: Int
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Downloads the ONNX graph and external tensor data from the public ModelScope repo. */
object ModelScopeModelDownloader {
    private const val BASE_URL = "https://modelscope.cn/models/rustykate/android-multi-modal-onnx-htp/resolve/master/"
    private const val AUDIO_BASE_URL = "https://hf-mirror.com/onnx-community/gemma-4-E2B-it-ONNX/resolve/main/onnx/"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val BUFFER_SIZE = 256 * 1024

    private data class RemoteFile(val name: String, val sha256: String, val baseUrl: String = BASE_URL)
    private data class RemoteMetadata(val size: Long)

    // SHA-256 values are the ModelScope LFS object identifiers verified for this model revision.
    private val files = listOf(
        RemoteFile("decoder_model_merged_q4f16.onnx", "73c0f1fe04f9a3a048fb3319c0671b6cf0346bf33a3a8624c853bcffe01c24a4"),
        RemoteFile("decoder_model_merged_q4f16.onnx_data", "3b27245a7396cb7039a4e4118bd2a8aa35106bae381522edf7c4867b5f22bb10"),
        RemoteFile("embed_tokens_q4f16.onnx", "d7ca53f6a169471b5699b2f57ee4c7aa2c73732b0152f3909e64b71384444825"),
        RemoteFile("embed_tokens_q4f16.onnx_data", "024b199e6358ed42970f807686add5f9430d7e254ca7ce22fc9c83f015b9c517"),
        RemoteFile("audio_encoder_q4f16.onnx", "5e0deb22791685c792d4b8e089deef9670fa4a4cecde434213d6a742e58fc3fa", AUDIO_BASE_URL),
        RemoteFile("audio_encoder_q4f16.onnx_data", "df58e61a00bafa9449ee5fd52895ce952f158bbdd1fe38df8a68f48f36842e62", AUDIO_BASE_URL)
    )

    suspend fun download(context: Context, onProgress: (ModelDownloadProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val modelDir = File(baseDir, "models")
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                throw IllegalStateException("无法创建模型目录：${modelDir.absolutePath}")
            }

            val metadata = files.map { file -> file to probe(file) }
            val verifiedFiles = mutableSetOf<String>()
            val requiredBytes = metadata.sumOf { (file, remote) ->
                val finalFile = File(modelDir, file.name)
                val partFile = File(modelDir, "${file.name}.part")
                val alreadyVerified = finalFile.isFile &&
                    finalFile.length() == remote.size &&
                    sha256(finalFile).equals(file.sha256, ignoreCase = true)
                if (alreadyVerified) verifiedFiles.add(file.name)
                if (alreadyVerified) 0L
                else (remote.size - (if (partFile.isFile) partFile.length() else 0L)).coerceAtLeast(0L)
            }
            val availableBytes = StatFs(modelDir.absolutePath).availableBytes
            val requiredWithReserve = requiredBytes + 256L * 1024L * 1024L
            if (availableBytes < requiredWithReserve) {
                throw IllegalStateException("存储空间不足：还需要约 ${formatBytes(requiredWithReserve)}，可用 ${formatBytes(availableBytes)}")
            }

            metadata.forEachIndexed { index, (file, remote) ->
                downloadOne(file, remote.size, modelDir, index + 1, file.name in verifiedFiles, onProgress)
            }
            modelDir
        }

    private fun probe(file: RemoteFile): RemoteMetadata {
        val connection = (URL(file.baseUrl + file.name).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "GemmaAndroid/1.0")
        }
        try {
            val code = connection.responseCode
            val linkedHash = connection.getHeaderField("X-Linked-Etag")?.trim()?.trim('"')
            // HF mirror exposes a Xet object hash as ETag rather than X-Linked-Etag;
            // the downloaded file is still verified against the pinned SHA-256 below.
            if (file.baseUrl == BASE_URL && !linkedHash.equals(file.sha256, ignoreCase = true)) {
                throw IllegalStateException("ModelScope 文件校验标识不匹配：${file.name}")
            }
            val total = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            if (code != HttpURLConnection.HTTP_PARTIAL || total == null || total <= 0L) {
                throw IllegalStateException("无法读取模型文件信息：${file.name}（HTTP $code）")
            }
            return RemoteMetadata(total)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadOne(
        file: RemoteFile,
        totalBytes: Long,
        directory: File,
        fileIndex: Int,
        alreadyVerified: Boolean,
        onProgress: (ModelDownloadProgress) -> Unit
    ) {
        val destination = File(directory, file.name)
        val partial = File(directory, "${file.name}.part")
        if (alreadyVerified || (destination.isFile && destination.length() == totalBytes && sha256(destination) == file.sha256)) {
            onProgress(ModelDownloadProgress(file.name, totalBytes, totalBytes, fileIndex, files.size))
            return
        }
        if (destination.exists()) destination.delete()
        if (partial.length() == totalBytes && sha256(partial) == file.sha256) {
            if (!partial.renameTo(destination)) throw IllegalStateException("无法保存已完成的下载文件：" + destination.absolutePath)
            onProgress(ModelDownloadProgress(file.name, totalBytes, totalBytes, fileIndex, files.size))
            return
        }
        if (partial.length() > totalBytes) partial.delete()

        var attempt = 0
        while (true) {
            attempt++
            var offset = if (partial.isFile) partial.length() else 0L
            val connection = (URL(file.baseUrl + file.name).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "GemmaAndroid/1.0")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK && offset > 0L) {
                    partial.delete()
                    offset = 0L
                } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("下载 ${file.name} 失败：HTTP $code")
                }
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val actualStart = Regex("bytes (\\d+)-").find(connection.getHeaderField("Content-Range") ?: "")
                        ?.groupValues?.get(1)?.toLongOrNull()
                    if (actualStart != offset) throw IllegalStateException("服务器返回了无效的断点位置：${file.name}")
                }

                connection.inputStream.use { input ->
                    RandomAccessFile(partial, "rw").use { output ->
                        if (offset == 0L) output.setLength(0L) else output.seek(offset)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = offset
                        var lastReport = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReport >= 1024L * 1024L || downloaded == totalBytes) {
                                onProgress(ModelDownloadProgress(file.name, downloaded, totalBytes, fileIndex, files.size))
                                lastReport = downloaded
                            }
                        }
                    }
                }

                if (partial.length() != totalBytes) {
                    throw IllegalStateException("${file.name} 下载不完整：${partial.length()} / $totalBytes bytes")
                }
                if (!sha256(partial).equals(file.sha256, ignoreCase = true)) {
                    partial.delete()
                    throw IllegalStateException("${file.name} SHA-256 校验失败，已清除损坏文件")
                }
                if (!partial.renameTo(destination)) throw IllegalStateException("无法保存下载文件：${destination.absolutePath}")
                onProgress(ModelDownloadProgress(file.name, totalBytes, totalBytes, fileIndex, files.size))
                return
            } catch (e: Exception) {
                if (attempt >= 3) throw e
                Thread.sleep(1000L * attempt)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatBytes(bytes: Long): String = String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}