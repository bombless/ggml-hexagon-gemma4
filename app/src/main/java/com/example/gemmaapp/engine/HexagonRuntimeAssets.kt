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
        val dspDir = File(root, "dsp")
        check(dspDir.exists() || dspDir.mkdirs()) { "Cannot create DSP runtime directory: $dspDir" }
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

        val skeleton = File(dspDir, SKELETON)
        if (!skeleton.isFile || skeleton.length() == 0L) {
            context.assets.open("hexagon/dsp/$SKELETON").use { input ->
                val temporary = File(dspDir, "$SKELETON.tmp")
                temporary.outputStream().use { output -> input.copyTo(output) }
                check(temporary.length() > 0L) { "Packaged HTP v75 skeleton is empty" }
                check(temporary.renameTo(skeleton)) { "Cannot atomically install HTP v75 skeleton" }
            }
        }
        check(skeleton.isFile && skeleton.length() > 0L) { "HTP v75 skeleton is missing: $skeleton" }

        val config = File(root, "ap/ggml-hexagon.cfg")
        if (compiled == "fastrpc-mempool") {
            check(config.parentFile!!.exists() || config.parentFile!!.mkdirs()) { "Cannot create FastRPC config directory" }
            if (!config.isFile || config.length() == 0L) {
                context.assets.open("hexagon/ap/ggml-hexagon.cfg").use { input ->
                    config.outputStream().use { output -> input.copyTo(output) }
                }
            }
            check(config.isFile && config.length() > 0L) { "FastRPC APK is missing its matching ggml-hexagon.cfg" }
        } else {
            check("ggml-hexagon.cfg" !in context.assets.list("hexagon/ap").orEmpty()) {
                "dspqueue APK contains a stale FastRPC config asset; rebuild after cleaning generated assets."
            }
        }

        // Upstream hard-codes /data/local/tmp for Android cfg/runtime files. An APK asset
        // extracted to filesDir is NOT visible to the FastRPC/dspqueue skeleton loader.
        val loaderDir = File("/data/local/tmp")
        val deployedSkeleton = File(loaderDir, SKELETON)
        check(deployedSkeleton.isFile && deployedSkeleton.length() == skeleton.length()) {
            "Matching HTP v75 skeleton is not deployed at ${deployedSkeleton.absolutePath}; run scripts/deploy-hexagon-runtime.ps1 with this APK first. APK asset extraction alone cannot satisfy file:/// loader paths."
        }
        if (compiled == "fastrpc-mempool") {
            val deployedConfig = File(loaderDir, "ggml-hexagon.cfg")
            check(deployedConfig.isFile && deployedConfig.length() > 0L) {
                "FastRPC config is not deployed at ${deployedConfig.absolutePath}; run scripts/deploy-hexagon-runtime.ps1."
            }
            check(sha256(config) == sha256(deployedConfig)) {
                "Deployed FastRPC config differs from this APK; redeploy the matching AP/DSP bundle."
            }
        }
        val adspPath = (listOf(loaderDir.absolutePath, "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp",
            "/system/lib/rfsa/adsp", "/vendor/dsp/dsp", "/vendor/dsp/images", "/dsp")).joinToString(";")
        val ldPath = (listOf(loaderDir.absolutePath, "/vendor/dsp/cdsp", "/vendor/lib64",
            "/vendor/dsp/dsp", "/vendor/dsp/images")).joinToString(":")
        Os.setenv("ADSP_LIBRARY_PATH", adspPath, true)
        Os.setenv("LD_LIBRARY_PATH", ldPath, true)
        check(sha256(skeleton) == sha256(deployedSkeleton)) {
            "Deployed HTP v75 skeleton hash differs from this APK; redeploy the matching AP/DSP bundle."
        }
        Log.i(TAG, "Prepared matched backend=$compiled skeleton=${deployedSkeleton.absolutePath} bytes=${deployedSkeleton.length()} ADSP_LIBRARY_PATH=$adspPath")

        // Android's linker resolves the AP ELF dependency closure from nativeLibraryDir.
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val requiredAp = mutableListOf("libgemma4_jni.so", "libllama.so", "libggml.so", "libggml-base.so")
        if (compiled != "cpu") requiredAp += "libggml-hexagon.so"
        val missing = requiredAp.filterNot { File(nativeDir, it).isFile }
        check(missing.isEmpty()) { "Incomplete arm64 AP runtime for $compiled; missing ${missing.joinToString()} in $nativeDir" }
        return RuntimeBundle(compiled, root, skeleton)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}