import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Embed the source revision in the APK so device logs and optrace CSVs can be
// traced back to the exact Git checkout used to build them.
fun gitOutput(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
        output
    } else {
        process.destroyForcibly()
        "unknown"
    }
}.getOrDefault("unknown")

val gitCommit = gitOutput("git", "rev-parse", "--short=12", "HEAD")
val buildTimestamp = ZonedDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))
// Include untracked migrated sources too; otherwise a fresh migration checkout
// could embed a misleading clean marker before its first commit.
val gitDirty = gitOutput("git", "status", "--porcelain")
    .let { it.isNotEmpty() && it != "unknown" }
// Keep the existing lightweight ONNX/QNN APK build unchanged unless a developer
// explicitly supplies the pinned llama.cpp checkout for the optional GGUF JNI target.
val ggmlHexagonSource = providers.gradleProperty("ggmlHexagonSource").orNull
val ggmlHexagonBackend = providers.gradleProperty("ggmlHexagonBackend").orElse("cpu").get()
val hexagonSdkRoot = providers.gradleProperty("hexagonSdkRoot").orNull ?: System.getenv("HEXAGON_SDK_ROOT")
val hexagonToolsRoot = providers.gradleProperty("hexagonToolsRoot").orNull ?: System.getenv("HEXAGON_TOOLS_ROOT")
val ggmlHexagonAssetDir = layout.buildDirectory.dir("generated/hexagonAssets")
require(ggmlHexagonBackend in setOf("cpu", "fastrpc-mempool", "dspqueue")) {
    "ggmlHexagonBackend must be cpu, fastrpc-mempool, or dspqueue"
}
require(ggmlHexagonBackend == "cpu" || ggmlHexagonSource != null) {
    "Set -PggmlHexagonSource to the pinned ggml-hexagon checkout when enabling Hexagon"
}
require(ggmlHexagonBackend == "cpu" || !hexagonSdkRoot.isNullOrBlank()) {
    "Set -PhexagonSdkRoot or HEXAGON_SDK_ROOT to the licensed Qualcomm Hexagon SDK 6.7.0.0"
}

android {
    namespace = "com.example.gemmaapp"
    compileSdk = 35

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        noCompress += listOf("")
    }

    defaultConfig {
        applicationId = "com.example.gemmaapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
        buildConfigField("String", "GGML_HEXAGON_BACKEND", "\"$ggmlHexagonBackend\"")
        buildConfigField("boolean", "GGML_JNI_ENABLED", (ggmlHexagonSource != null).toString())

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    if (ggmlHexagonSource != null) {
        externalNativeBuild {
            cmake {
                path = file("../native/CMakeLists.txt")
                version = "3.30.5"
            }
        }
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += "-DLLAMA_CPP_DIR=$ggmlHexagonSource"
                    arguments += "-DGEMMA4_HEXAGON_BACKEND=$ggmlHexagonBackend"
                    arguments += "-DGEMMA4_HEXAGON_ASSET_DIR=${ggmlHexagonAssetDir.get().asFile.absolutePath}"
                    arguments += "-DPREBUILT_LIB_DIR=android_aarch64"
                    if (!hexagonSdkRoot.isNullOrBlank()) arguments += "-DHEXAGON_SDK_ROOT=$hexagonSdkRoot"
                    if (!hexagonToolsRoot.isNullOrBlank()) arguments += "-DHEXAGON_TOOLS_ROOT=$hexagonToolsRoot"
                }
            }
        }
        sourceSets.getByName("main").assets.srcDir(ggmlHexagonAssetDir)
    }
}

// The DSP skeleton is built by an ExternalProject inside CMake and staged by
// gemma4_jni. Do not snapshot generated APK assets before that native build.
if (ggmlHexagonSource != null && ggmlHexagonBackend != "cpu") {
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
        dependsOn("externalNativeBuildDebug")
    }
}

dependencies {
    // Sandboxed, pure-JVM JavaScript runtime for model-generated code and tests.
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.qualcomm.qti:onnxruntime-android-qnn:2.6.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")

    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
