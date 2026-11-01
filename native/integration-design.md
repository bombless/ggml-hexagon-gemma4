# ggml-hexagon Android integration design

Status: GGUF JNI/chat integration and compile-time Hexagon registration/device selection are implemented; both licensed FastRPC+mempool and dspqueue APK variants have passed host build/package checks; target-device acceptance pending.

## Pinned inputs and verified API

- Upstream: `ggml-hexagon/ggml-hexagon`, `self-build-jz`, commit `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d`.
- The root project is llama.cpp 0.8.0-dev at that commit. `ggml/src/CMakeLists.txt` defines `ggml-base`, `ggml`, and backend targets through `ggml_add_backend_library()`.
- `ggml/src/ggml-hexagon/CMakeLists.txt` defines `GGML_HEXAGON_USE_MEMPOOL`: ON selects `ggml-hexagon-fastrpc.cpp` and IDL `htp/ggml_htp.idl`; OFF selects `ggml-hexagon.cpp` and `htp/htp_iface.idl` (dspqueue). Both currently declare HTP skeleton builds for multiple DSP generations. The app release target is HTP v75 only.
- The public backend header is `ggml/include/ggml-hexagon.h`; its exported entry points are `ggml_backend_hexagon_init()`, `ggml_backend_is_hexagon()`, and `ggml_backend_hexagon_reg()`. These are ggml backend APIs, not a model-inference/JNI API.
- The upstream build produces AP-side ggml shared libraries and DSP skeleton `libggml-htp-v75.so`. `scripts/ggml-hexagon.cfg` is an upstream FastRPC runtime configuration file; do not assume it is valid or required for dspqueue.

## Runtime layering

```text
Compose UI / ChatViewModel
  -> Kotlin inference interface (new; keep ONNX/QNN engine as existing option)
  -> single-thread-owned JNI session (new)
  -> llama C API: model + context + tokenizer/chat template
  -> llama scheduler / ggml backend registry
  -> ggml-hexagon backend on Android AP
  -> exactly one transport variant
       FastRPC + mempool AP implementation + matching v75 DSP skeleton
       dspqueue AP implementation       + matching v75 DSP skeleton
  -> Snapdragon 8 Gen 3 HTP v75
```

The backend source is selected at native build time, not by mixing runtime files. Ship separate APK/native-runtime variants or separate, atomically installed native payload directories with an explicit backend identity. Never load AP libraries from one variant with the other variant's skeleton. Keep the current ONNX Runtime/QNN engine intact until the GGUF path passes device acceptance.

## JNI contract and ggml runtime registration

Use an opaque `long` handle to a native session. `native/gemma4_jni.cpp` implements model/context/sampler ownership, prompt tokenization, decode, token callbacks, cancellation flag, and destruction. `NativeLlamaSession.kt` dispatches generation on `Dispatchers.IO`. Generation currently runs synchronously on that worker thread, so its listener is a valid local JNI reference and is not retained across threads. Native operations are serialized per session.

`GGML_HEXAGON=ON` causes the pinned `ggml/src/CMakeLists.txt` to build the Hexagon backend and, when static backend loading is used, publish `GGML_USE_HEXAGON` to `ggml`. The pinned `ggml-backend-reg.cpp` then calls `ggml_backend_hexagon_reg()` during registry initialization. Both transport implementations register as `HTP`. JNI calls `llama_backend_init()`, resolves `ggml_backend_reg_by_name("HTP")`, verifies that at least one device was exposed, and passes the first device as a NULL-terminated `llama_model_params.devices` list with `n_gpu_layers=-1`. The CMake transport option is mapped from the single Gradle backend identity: FastRPC+mempool sets `GGML_HEXAGON_USE_MEMPOOL=ON`; dspqueue sets it OFF. JNI rejects a requested identity that differs from the APK's compiled identity.

Create/destroy are idempotent at the Kotlin owner. Cancellation is cooperative and checked between decode steps. Destruction waits for an active generation through the per-session mutex. C++ creation errors are translated to Java exceptions. Device registration and layer offload requests are not proof that operators ran on DSP: unsupported operations may execute on CPU, and device acceptance still requires runtime diagnostics showing HTP work.

## Model loading and inference acceptance

Use the pinned llama C API (`llama_model_load_from_file`, `llama_init_from_model`, tokenization and decode APIs from that exact revision); do not invent a custom GGUF parser or equate the ggml backend API with the llama model API. Verify the actual API signatures in the pinned `include/llama.h` while implementing. Gemma 4 architecture code in the source tree is not proof that the exact E2B-it Q4_0 GGUF loads or that all its operators are supported by HTP v75.

The first device vertical slice is: create backend -> load the exact model -> tokenize a fixed text-only prompt -> decode a bounded number of tokens -> capture output and backend diagnostics -> destroy cleanly. Record model provenance/hash, tokenizer/chat-template behavior, unsupported operators, CPU fallback, and DSP execution evidence. Add streaming, cancellation races, reload, and app foreground/background tests only after this slice works.

## Build/deployment invariants

- Android ABI is `arm64-v8a`; device target is SM8650 / HTP v75.
- Qualcomm Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04 are licensed external prerequisites; never copy them into this repository or publish their files.
- Build FastRPC+mempool (`GGML_HEXAGON_USE_MEMPOOL=ON`) and dspqueue (`OFF`) in separate clean build directories and artifact roots.
- Each artifact set must include all AP `.so` dependencies, exactly the HTP v75 skeleton, a backend identity, source/toolchain/CMake metadata, sizes, and SHA-256 hashes. Validation must fail on missing/empty libraries or unexpected DSP generations.
- Successful compilation or `dlopen` is not proof of DSP execution. Device acceptance requires runtime diagnostics that establish an HTP operation executed.

For Android JNI APK builds, `fastrpc-mempool` depends on the pinned `htp-mempool-v75` ExternalProject and stages its `libggml-htp-v75.so` plus the pinned `scripts/ggml-hexagon.cfg`; `dspqueue` depends on `htp-v75` and stages its matching skeleton without the FastRPC config. Both skeletons are packaged under `assets/hexagon/dsp/`, with `assets/hexagon/backend.properties` identifying the transport. They must not be placed in `lib/arm64-v8a`, because they are DSP images, not Android AP ELF libraries. The APK asset is only a transport-matched payload; the `file:///libggml-htp-v75.so` loader URI still requires a verified device-side deployment location and permissions.

## Current blockers

The optional JNI/CMake path and both licensed transport APK builds are implemented and have passed host package checks. The app's default engine remains ONNX Runtime/QNN. No target Gemma 4 E2B-it GGUF Q4_0 file/hash is provisioned in this workspace, and no ggml-hexagon inference, runtime loader acceptance, or HTP execution has been run on the RMX3800.