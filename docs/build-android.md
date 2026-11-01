# Android build and CI

## Local Android app build

Use JDK 17 and install Android SDK platform 35. The migrated Gradle configuration uses Android Gradle Plugin 8.7.3, Kotlin 2.1.21, and Gradle 8.9. The application ID/namespace is com.example.gemmaapp, compileSdk/targetSdk are 35, minSdk is 29, and the only packaged ABI is arm64-v8a. The manifest requests INTERNET and RECORD_AUDIO plus legacy storage permissions and MANAGE_EXTERNAL_STORAGE; review the broad storage permission and modern scoped-storage behavior before any public Play release.

    ./gradlew.bat --version
    ./gradlew.bat :app:testDebugUnitTest
    ./gradlew.bat :app:assembleDebug

The debug APK is written to app/build/outputs/apk/debug/app-debug.apk. The APK is an ONNX Runtime/QNN app build; it does not contain a ggml JNI integration or proprietary Hexagon SDK libraries.

## CI

The Android app workflow builds the debug APK and runs JVM unit tests on a clean Ubuntu runner. The Hexagon Android artifacts workflow separately builds FastRPC+mempool and dspqueue from the pinned upstream source. Artifacts are separate by design.

## Hexagon toolchain prerequisites

- Android NDK with build/cmake/android.toolchain.cmake.
- Qualcomm Hexagon SDK 6.7.0.0 with build/cmake/hexagon_arch.cmake.
- Qualcomm Hexagon Tools 21.0.04.
- Linux build host/container with CMake, Ninja, Git, Python 3, and a supported compiler.

Set ANDROID_NDK_ROOT, HEXAGON_SDK_ROOT, and optionally HEXAGON_TOOLS_ROOT. SDK discovery is a convenience, not a substitute for checking versions and license entitlement. The workflow fails if the required licensed SDK is absent.

## Artifact acceptance

For each backend, verify that expected AP shared libraries and the HTP v75 skeleton are non-empty, record SHA-256, and keep each backend in a separate directory. The current script collects files and checks for at least one output; strengthening the manifest to enumerate every required runtime dependency remains open work. Build success is not device execution proof.
