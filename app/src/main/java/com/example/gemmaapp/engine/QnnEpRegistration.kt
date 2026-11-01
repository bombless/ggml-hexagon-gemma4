package com.example.gemmaapp.engine

import ai.onnxruntime.OrtEnvironment
import android.util.Log

/**
 * Gate to safely register Qualcomm QNN Execution Provider plugin with ONNX Runtime.
 */
object QnnEpRegistration {
    const val EP_NAME = "QNNExecutionProvider"
    private const val TAG = "QNN_EP"
    private const val NATIVE_LIBRARY = "onnxruntime_providers_qnn"
    const val PLUGIN_LIBRARY = "libonnxruntime_providers_qnn.so"

    @Synchronized
    fun ensureRegistered(env: OrtEnvironment): Boolean {
        if (isDeviceExposed(env)) {
            Log.i(TAG, "QNN EP already registered; reusing existing registration")
            return false
        }
        return try {
            System.loadLibrary(NATIVE_LIBRARY)
            env.registerExecutionProviderLibrary(EP_NAME, PLUGIN_LIBRARY)
            Log.i(TAG, "QNN EP successfully registered")
            true
        } catch (t: Throwable) {
            if (!isDeviceExposed(env)) {
                Log.w(TAG, "QNN EP registration note: ${t.message}")
                throw t
            }
            false
        }
    }

    fun isDeviceExposed(env: OrtEnvironment): Boolean =
        runCatching { env.epDevices.any { it.epName == EP_NAME } }.getOrDefault(false)
}
