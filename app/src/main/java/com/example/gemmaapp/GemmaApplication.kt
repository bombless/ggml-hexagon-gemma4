package com.example.gemmaapp

import android.app.Application
import android.system.Os
import android.util.Log
import com.example.gemmaapp.engine.ToolCallParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GemmaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep the app data root owner-only; this also repairs accidental
        // permission changes made while staging large model sidecars over ADB.
        runCatching { Os.chmod(applicationInfo.dataDir, 448) }
            .onFailure { Log.w(TAG, "Could not restore private app-data permissions", it) }
        importStagedTiledSidecars()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val stack = Log.getStackTraceString(error)
            Log.e(TAG, "UNCAUGHT thread=${thread.name}", error)
            try {
                val dir = File(filesDir, "crash-logs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
                File(dir, "crash-$stamp.txt").writeText(
                    "time=${Date()}\nthread=${thread.name}\nprocess=${applicationInfo.processName}\n\n$stack"
                )
            } catch (writeError: Throwable) {
                Log.e(TAG, "Could not persist crash log", writeError)
            }
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "Crash logging initialized; filesDir=${filesDir.absolutePath}/crash-logs")
        try {
            ToolCallParser.validateParser()
            Log.i(TAG, "Tool-call parser self-test passed")
        } catch (error: Throwable) {
            Log.e(TAG, "Tool-call parser regex validation failed", error)
        }
    }

    private fun importStagedTiledSidecars() {
        val externalModels = getExternalFilesDir("models") ?: return
        val staged = externalModels.listFiles { file -> file.isFile && file.name.endsWith(".htpbin") } ?: return
        if (staged.isEmpty()) return
        Thread({
            val privateModels = File(filesDir, "models").apply { mkdirs() }
            for (source in staged) {
                val target = File(privateModels, source.name)
                val temporary = File(privateModels, source.name + ".importing")
                try {
                    FileInputStream(source).buffered(4 * 1024 * 1024).use { input ->
                        FileOutputStream(temporary).buffered(4 * 1024 * 1024).use { output ->
                            input.copyTo(output, 4 * 1024 * 1024)
                        }
                    }
                    if (temporary.length() != source.length()) {
                        temporary.delete()
                        Log.e(TAG, "[TILED_CACHE_IMPORT] size mismatch for ${source.name}")
                        continue
                    }
                    if (!temporary.renameTo(target)) {
                        target.delete()
                        if (!temporary.renameTo(target)) {
                            temporary.delete()
                            Log.e(TAG, "[TILED_CACHE_IMPORT] could not publish ${source.name}")
                            continue
                        }
                    }
                    source.delete()
                    Log.i(TAG, "[TILED_CACHE_IMPORT] imported ${source.name} bytes=${target.length()}")
                } catch (error: Throwable) {
                    temporary.delete()
                    Log.e(TAG, "[TILED_CACHE_IMPORT] failed for ${source.name}", error)
                }
            }
        }, "HtpSidecarImport").start()
    }

    companion object {
        const val TAG = "GemmaApp"
    }
}