package com.example.gemmaapp

import android.app.Application
import android.util.Log
import com.example.gemmaapp.engine.ToolCallParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GemmaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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

    companion object {
        const val TAG = "GemmaApp"
    }
}