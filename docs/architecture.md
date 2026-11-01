# Architecture and integration boundary

## Current application path

    Jetpack Compose UI
      -> ChatViewModel
      -> GemmaEngine Kotlin
      -> ONNX Runtime Android
      -> Qualcomm QNN Execution Provider
      -> Snapdragon HTP / NPU
      -> ONNX Runtime CPU fallback (when QNN setup fails)

The migrated app currently runs ONNX graphs through ONNX Runtime. QNN HTP is the NPU execution provider; CPU fallback is implemented in the app. This is not the ggml-hexagon FastRPC/dspqueue path.

## Optional ggml-hexagon path (JNI and registry wiring implemented; device validation pending)

    Compose UI -> ChatViewModel -> Kotlin inference interface
      -> NativeLlamaSession Kotlin wrapper
      -> JNI llama session
      -> llama.cpp / ggml backend registry (static GGML_USE_HEXAGON registration)
      -> selected HTP device (compile-time transport identity)
      -> Android arm64 AP shared libraries
      -> FastRPC + mempool OR dspqueue
      -> HTP v75 DSP skeleton

AP-side shared libraries execute on Android ARM64. The HTP skeleton executes on the DSP through the selected RPC transport. The two backend variants must be packaged and deployed as matched sets. The JNI session currently implements llama model/context/sampler ownership, prompt tokenization, decode, token callbacks, cancellation, and close. CMake/Gradle select `cpu`, `fastrpc-mempool`, or `dspqueue`; Hexagon builds enable `GGML_HEXAGON`, which registers the upstream `HTP` backend in ggml. JNI selects the first registered HTP device and requests layer offload. Licensed SDK builds, matched deployment, and proof of actual DSP execution remain pending.

## Remaining integration and acceptance work

1. Connect the optional native session to the app's user-facing inference selection without replacing the working ONNX/QNN engine prematurely.
2. Validate the exact Gemma 4 E2B-it GGUF Q4_0 model, tokenizer, and chat template on the pinned llama.cpp/ggml revision.
3. Build/package one backend variant at a time; do not allow FastRPC AP libraries to be paired with dspqueue skeletons.
4. Audit and deploy the complete transitive AP ELF dependency set and matching v75 DSP skeleton.
5. Expose actual backend/operator placement diagnostics; do not equate registered HTP device or requested layer offload with DSP execution.
6. Add host tests for JNI-facing state transitions and device smoke tests proving a real HTP operation ran.

## Current vs planned model formats

- Current Android app: Gemma 4 ONNX model suite plus the app-specific BPE tokenizer files.
- Project target for ggml integration: Gemma 4 E2B-it GGUF Q4_0. It is not currently loaded by the migrated Kotlin engine.
- The pinned upstream source contains Gemma 4 conversion/runtime implementation (model architecture, tokenizer pre-tokenization, and chat-template handling). This establishes source-level architecture support, but does not prove the exact E2B Q4_0 model loads successfully on SM8650 or that every required operator executes on HTP v75.
