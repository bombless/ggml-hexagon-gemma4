package com.example.gemmaapp.engine

import android.content.Context
import android.system.Os
import android.util.Log
import com.example.gemmaapp.BuildConfig
import java.io.File
import java.security.MessageDigest

/** Installs the transport-matched DSP image/config before the JNI class is loaded. */
object HexagonRuntimeAssets {
    private const val TAG = "GemmaHexagonRuntime"
    private const val SKELETON = "libggml-htp-v75.so"

    data class RuntimeBundle(val backend: String, val directory: File, val skeleton: File)

    fun prepare(context: Context, requestedBackend: String): RuntimeBundle? {
        val compiled = BuildConfig.GGML_HEXAGON_BACKEND
        check(requestedBackend == compiled) {
            "This APK contains '$compiled'; requested '$requestedBackend'. Install the matching AP + HTP v75 APK."
        }
        if (compiled == "cpu") return null
        check(compiled in setOf("fastrpc-mempool", "dspqueue")) { "Unsupported Hexagon artifact identity: $compiled" }

        val root = File(context.filesDir, "hexagon-runtime/$compiled")
        check(root.exists() || root.mkdirs()) { "Cannot create private Hexagon runtime directory: $root" }
        val metadata = context.assets.open("hexagon/backend.properties").bufferedReader().use { reader ->
            reader.readLines().mapNotNull { line ->
                val key = line.substringBefore('=', "").trim()
                if (key.isEmpty()) null else key to line.substringAfter('=', "").trim()
            }.toMap()
        }
        check(metadata["backend"] == compiled && metadata["dsp"] == "HTP-v75" && metadata["abi"] == "arm64-v8a") {
            "APK Hexagon metadata does not match backend=$compiled / HTP-v75 / arm64-v8a: $metadata"
        }
        check(metadata["upstream_commit"] == "a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d") {
            "Unexpected ggml-hexagon upstream revision in APK: ${metadata["upstream_commit"]}"
        }

        val skeleton = File(root, SKELETON)
        run {
            val temporary = File(root, "$SKELETON.tmp")
            context.assets.open("hexagon/dsp/$SKELETON").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 0L) { "Packaged HTP v75 skeleton is empty" }
            if (skeleton.exists()) check(skeleton.delete()) { "Cannot replace stale HTP v75 skeleton" }
            check(temporary.renameTo(skeleton)) { "Cannot atomically install HTP v75 skeleton" }
        }
        check(skeleton.isFile && skeleton.length() > 0L) { "HTP v75 skeleton is missing: $skeleton" }

        val config = File(root, "ggml-hexagon.cfg")
        if (compiled == "fastrpc-mempool") {
            check(config.parentFile!!.exists() || config.parentFile!!.mkdirs()) { "Cannot create FastRPC config directory" }
            val temporaryConfig = File(root, "ggml-hexagon.cfg.tmp")
            context.assets.open("hexagon/ap/ggml-hexagon.cfg").use { input ->
                temporaryConfig.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporaryConfig.length() > 0L) { "Packaged FastRPC config is empty" }
            if (config.exists()) check(config.delete()) { "Cannot replace stale FastRPC config" }
            check(temporaryConfig.renameTo(config)) { "Cannot atomically install FastRPC config" }
            check(config.isFile && config.length() > 0L) { "FastRPC APK is missing its matching ggml-hexagon.cfg" }
        } else {
            check("ggml-hexagon.cfg" !in context.assets.list("hexagon/ap").orEmpty()) {
                "dspqueue APK contains a stale FastRPC config asset; rebuild after cleaning generated assets."
            }
        }

        // Both the DSP skeleton and (for FastRPC) its config live in this app-private bundle.
        // Set this before loading the JNI/native backend; the native FastRPC loader reads it.
        val loaderDir = root
        val adspPath = (listOf(loaderDir.absolutePath, "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp",
            "/system/lib/rfsa/adsp", "/vendor/dsp/dsp", "/vendor/dsp/images", "/dsp")).joinToString(";")
        val ldPath = (listOf(loaderDir.absolutePath, "/vendor/dsp/cdsp", "/vendor/lib64",
            "/vendor/dsp/dsp", "/vendor/dsp/images")).joinToString(":")
        Os.setenv("GGML_HEXAGON_RUNTIME_DIR", loaderDir.absolutePath, true)
        Os.setenv("ADSP_LIBRARY_PATH", adspPath, true)
        Os.setenv("LD_LIBRARY_PATH", ldPath, true)
        Log.i(TAG, "Prepared app-private backend=$compiled skeleton=${skeleton.absolutePath} bytes=${skeleton.length()} GGML_HEXAGON_RUNTIME_DIR=${loaderDir.absolutePath} ADSP_LIBRARY_PATH=$adspPath")
        // Android's linker resolves the AP ELF dependency closure from nativeLibraryDir.
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val requiredAp = mutableListOf("libgemma4_jni.so", "libllama.so", "libggml.so", "libggml-base.so")
        if (compiled != "cpu") requiredAp += "libggml-hexagon.so"
        val missing = requiredAp.filterNot { File(nativeDir, it).isFile }
        check(missing.isEmpty()) { "Incomplete arm64 AP runtime for $compiled; missing ${missing.joinToString()} in $nativeDir" }
        return RuntimeBundle(compiled, root, skeleton)
    }

}
