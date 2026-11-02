# Gemma 4 Android + Hexagon

This repository combines a migrated Android chat application with reproducible Android-arm64 ggml-hexagon backend cross-builds for the realme RMX3800 / Snapdragon 8 Gen 3 (SM8650, HTP v75) target.

**Important status distinction:** the migrated Android application currently uses ONNX Runtime with Qualcomm QNN HTP (and CPU fallback). An optional llama/ggml JNI path now compiles the pinned Hexagon backend into a transport-specific APK and selects the registered HTP device through llama model parameters. The Hexagon build requires the licensed Qualcomm SDK and matching AP/DSP runtime deployment; no ggml HTP execution or benchmark on the device is claimed yet.

## Repository layout

- app/ — migrated Kotlin/Compose Android application and unit tests (currently QNN/ONNX Runtime).
- native/ — JNI llama session source, optional Android CMake target, and integration design (`native/integration-design.md`).
- scripts/ci-build-hexagon.sh and scripts/validate-hexagon-artifacts.py — pinned upstream Android-arm64 builds, isolated AP/HTP v75 bundles, and per-backend SHA-256 manifests.
- .github/workflows/ — separate Hexagon artifact and Android APK CI workflows.
- docs/ — architecture, build, model provisioning, RMX3800 setup, benchmark protocol, and migration provenance.
- gradlew, gradlew.bat — Gradle 8.9 wrapper.

## Build the Android app

Requirements: JDK 17, Android SDK with platform 35 and an arm64-capable NDK, and network access to configured Maven repositories.

    ./gradlew.bat :app:testDebugUnitTest
    ./gradlew.bat :app:assembleDebug

APK: app/build/outputs/apk/debug/app-debug.apk.

The app's present ONNX runtime expects the six graph/data files and two tokenizer binaries documented in model setup. Binary weights and tokenizer files are intentionally excluded from Git. Build the optional GGUF JNI target against the pinned source checkout:

    ./gradlew.bat :app:assembleDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon

This defaults to CPU. To compile and register Hexagon, select exactly one transport and supply the licensed SDK (and optionally tools) path:

    ./gradlew.bat :app:assembleDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon -PggmlHexagonBackend=fastrpc-mempool -PhexagonSdkRoot=D:/Qualcomm/Hexagon_SDK/6.7.0.0
    ./gradlew.bat :app:assembleDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon -PggmlHexagonBackend=dspqueue -PhexagonSdkRoot=D:/Qualcomm/Hexagon_SDK/6.7.0.0

The APK's backend identity is fixed at build time; a JNI request for a different transport is rejected. See `native/README.md` for registry behavior and runtime requirements.

### Use GGUF in the existing chat UI

Build the optional JNI APK (CPU-only llama path):

    ./gradlew.bat :app:assembleDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon -PggmlHexagonBackend=cpu

For HTP, build one of the two transport-specific APKs with the licensed SDK using the commands above. Put `gemma-4-E2B-it-Q4_0.gguf` in the app's external files `models/` directory (or private `files/models/`); the app does not download or package GGUF weights. Select **GGUF / llama.cpp** from the chat toolbar. The GGUF path uses the model's embedded chat template and token streaming. Audio input and the JavaScript tool loop remain on the ONNX/QNN path; switch back to ONNX/QNN to use them.

For a Hexagon APK, deploy the matching DSP files before selecting GGUF:

    powershell -ExecutionPolicy Bypass -File scripts/deploy-hexagon-runtime.ps1 -Backend fastrpc-mempool -Apk artifacts/apk/gemma4-fastrpc-mempool-debug.apk
    # or
    powershell -ExecutionPolicy Bypass -File scripts/deploy-hexagon-runtime.ps1 -Backend dspqueue -Apk artifacts/apk/gemma4-dspqueue-debug.apk

The deployment script verifies APK backend/SoC-generation/ABI metadata and required AP `.so` entries, then installs the APK. On GGUF initialization, `HexagonRuntimeAssets` extracts the matching DSP skeleton (and FastRPC config, when applicable) into `Context.filesDir/hexagon-runtime/<backend>/` and sets the runtime search paths. This keeps the bundle app-owned and removes the need for root or shared-storage deployment. It does not prove that HTP executed an operator: collect DSP/FastRPC diagnostics and run a deterministic prompt on the RMX3800 before claiming HTP inference.

To build **both isolated JNI APKs** on Windows, set `HEXAGON_SDK_ROOT` and `HEXAGON_TOOLS_ROOT` (or let the SDK manifest resolve tools), then run:

    powershell -ExecutionPolicy Bypass -File scripts/build-android-hexagon-apks.ps1

The script verifies the pinned upstream HEAD, clears only the generated `.cxx` native cache between transport builds, checks that each APK contains the JNI/AP Hexagon libraries and its matching HTP v75 DSP skeleton, and writes SHA-256-hashed APKs under `artifacts/apk/`. FastRPC additionally carries the upstream `ggml-hexagon.cfg`. DSP skeletons are APK assets, not arm64 JNI libraries; the app extracts them to its private runtime directory before HTP initialization. The APK packaging check is not a device smoke test.

See [docs/htp-tiled-cache.md](docs/htp-tiled-cache.md) for the optional FastRPC Q4_0 preconversion sidecar workflow.

## Build the ggml-hexagon artifacts

Run **Hexagon Android artifacts** from GitHub Actions, or execute bash scripts/ci-build-hexagon.sh in a compliant Linux environment with Android NDK, Qualcomm Hexagon SDK 6.7.0.0, and Hexagon Tools 21.0.04 installed. The workflow checks out upstream commit a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d and builds:

- FastRPC + mempool: GGML_HEXAGON_USE_MEMPOOL=ON
- dspqueue: GGML_HEXAGON_USE_MEMPOOL=OFF

Qualcomm SDK/toolchain files are proprietary/licensed prerequisites. Do not commit or redistribute them unless the applicable license permits it. See Android build.

## Device and benchmark status

No ggml-hexagon HTP smoke test or PP/TG benchmark is claimed complete. A separate QNN app smoke test was run on the RMX3800: the app launched and initialized its ONNX Runtime QNN HTP decoder session, while the embedding graph fell back to CPU. This does not validate the ggml FastRPC/dspqueue artifacts. Follow device setup and benchmark protocol only after a licensed Hexagon APK and its matching AP/DSP artifacts have been built and deployed.

## Upstream source pin

- Repository: https://github.com/ggml-hexagon/ggml-hexagon
- Branch: self-build-jz
- Commit: a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d

See docs/source-migration.md for the Android app source revision and locally modified files.
