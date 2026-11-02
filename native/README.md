# Native ggml-hexagon integration

The pinned upstream source/API inventory and integration design are recorded in [integration-design.md](integration-design.md). `gemma4_jni.cpp` now implements a JNI-owned llama model/context/sampler session; `CMakeLists.txt` builds it against the pinned upstream checkout, and `NativeLlamaSession.kt` is the coroutine-safe Kotlin entry point.

Verified upstream facts:
- Pin: `ggml-hexagon/ggml-hexagon` commit `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d`.
- Public backend API: `ggml_backend_hexagon_init()`, `ggml_backend_is_hexagon()`, and `ggml_backend_hexagon_reg()` in `ggml/include/ggml-hexagon.h`.
- `GGML_HEXAGON_USE_MEMPOOL=ON` selects the FastRPC/mempool AP implementation; OFF selects dspqueue.
- The optional JNI target defaults to CPU. `-PggmlHexagonBackend=fastrpc-mempool` enables `GGML_HEXAGON=ON` and `GGML_HEXAGON_USE_MEMPOOL=ON`; `dspqueue` enables Hexagon with mempool OFF. Both require the licensed SDK root. The upstream `ggml-backend-reg.cpp` statically registers `ggml_backend_hexagon_reg()` under `GGML_USE_HEXAGON`; JNI resolves the `HTP` registry, selects its first device in `llama_model_params.devices`, and requests all layers for offload.
- Backend identity is compile-time and enforced at JNI session creation. Build separate APKs for CPU, FastRPC+mempool, and dspqueue; do not mix AP libraries or DSP skeletons. A registered device and successful model load are not proof that an HTP operation executed.
- Both upstream configurations currently declare multiple DSP generations. This project's artifact validator intentionally accepts only the HTP v75 skeleton for SM8650.
- The backend API is not a llama model-loading API. JNI integration must use the llama C API from the same pinned source revision.

To opt into the JNI shared library build, run Gradle with `-PggmlHexagonSource=D:/path/to/ggml-hexagon` (the checkout must be the documented pinned revision). CPU is the default. For Hexagon, additionally set `-PggmlHexagonBackend=fastrpc-mempool` or `dspqueue` and `-PhexagonSdkRoot=...` (or `HEXAGON_SDK_ROOT`); `-PhexagonToolsRoot=...` / `HEXAGON_TOOLS_ROOT` is optional when the SDK manifest resolves it. After building, call `NativeLlamaSession.open(ggufFile, backend = "cpu"|"fastrpc-mempool"|"dspqueue")` matching the APK identity; run `generate` from a coroutine. Do not replace the existing ONNX Runtime/QNN engine until the GGUF path has passed device acceptance.

The llama/ggml backend registration and explicit HTP device selection are wired for Hexagon builds. The chat UI can switch between ONNX/QNN and the optional GGUF JNI session. JNI uses the GGUF embedded chat template, accepts a separate system message, resets KV memory between turns, and streams token pieces back to Kotlin. JNI renders structured system/user/assistant/tool messages through the GGUF's embedded Jinja template, including separate assistant tool-call and JavaScript tool-result turns. The app-level JavaScript tool loop is shared with GGUF and runs through the existing Rhino sandbox; it is not a JavaScript runtime embedded in llama.cpp. In GGUF mode, microphone PCM is encoded by the app's existing Gemma 4 ONNX audio encoder (CPU session); JNI replaces each `<|audio|>` placeholder with the corresponding 1536-wide soft-token embedding through `llama_batch.embd`. This reuses the same Gemma audio frontend as ONNX/QNN and does not require `libmtmd`; the pinned ggml-hexagon checkout does not include that optional multimodal library. Device-level audio correctness still needs validation.

## Android transport packaging

`native/CMakeLists.txt` binds each transport to its upstream HTP v75 ExternalProject target:

| APK identity | AP implementation / IDL | HTP skeleton target | Additional runtime asset |
| --- | --- | --- | --- |
| `fastrpc-mempool` | `ggml-hexagon-fastrpc.cpp` / `ggml_htp.idl` | `htp-mempool-v75` | `ggml-hexagon.cfg` |
| `dspqueue` | `ggml-hexagon.cpp` / `htp_iface.idl` | `htp-v75` | none currently identified by the pinned source |

The JNI target depends on the selected HTP v75 ExternalProject, then stages its DSP skeleton under `assets/hexagon/dsp/` and writes `assets/hexagon/backend.properties`. FastRPC also stages the exact pinned upstream `scripts/ggml-hexagon.cfg` under `assets/hexagon/ap/`. The DSP skeleton is deliberately not placed in `lib/arm64-v8a`: it is a DSP-side image, not an Android AP ELF. The Gradle asset-merge task is ordered after `externalNativeBuildDebug` so these generated assets enter the APK. `HexagonRuntimeAssets` checks the APK metadata, extracts private copies for comparison, validates the arm64 JNI/llama/ggml AP library set, and checks the deployed DSP/config SHA-256 before `NativeLlamaSession` causes `System.loadLibrary` and `llama_backend_init`.

Use `scripts/build-android-hexagon-apks.ps1` to build the two variants in sequence. It deletes only `app/.cxx` between runs to prevent CMake cache reuse across incompatible transport settings, and checks each APK's JNI/AP libraries, DSP skeleton, identity metadata, and FastRPC config before copying it to `artifacts/apk/`.

**Runtime preparation is app-managed.** `HexagonRuntimeAssets` extracts the matching skeleton (and FastRPC config, if applicable) into `Context.filesDir/hexagon-runtime/<backend>/`, sets `GGML_HEXAGON_RUNTIME_DIR`, `ADSP_LIBRARY_PATH`, and `LD_LIBRARY_PATH`, and validates the packaged AP library set before JNI initialization. The native FastRPC loader reads `GGML_HEXAGON_RUNTIME_DIR` for its config and skeleton search path. Whether the Qualcomm DSP loader can access this app-private path remains a device/firmware validation item; successful extraction is not proof of DSP execution. A successful `dlopen`, registry lookup, model load, or APK deployment is not proof of HTP execution.

Qualcomm Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04 are licensed prerequisites. Do not copy SDK files into this repository.
