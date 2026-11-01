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

The llama/ggml backend registration and explicit HTP device selection are wired for Hexagon builds. The chat UI can switch between ONNX/QNN and the optional GGUF JNI session. JNI uses the GGUF embedded chat template, resets KV memory between turns, and streams token pieces back to Kotlin. GGUF history is currently flattened into one user message; the native path does not yet expose the ONNX engine's audio or JavaScript tool features.

## Android transport packaging

`native/CMakeLists.txt` binds each transport to its upstream HTP v75 ExternalProject target:

| APK identity | AP implementation / IDL | HTP skeleton target | Additional runtime asset |
| --- | --- | --- | --- |
| `fastrpc-mempool` | `ggml-hexagon-fastrpc.cpp` / `ggml_htp.idl` | `htp-mempool-v75` | `ggml-hexagon.cfg` |
| `dspqueue` | `ggml-hexagon.cpp` / `htp_iface.idl` | `htp-v75` | none currently identified by the pinned source |

The JNI target depends on the selected HTP v75 ExternalProject, then stages its DSP skeleton under `assets/hexagon/dsp/` and writes `assets/hexagon/backend.properties`. FastRPC also stages the exact pinned upstream `scripts/ggml-hexagon.cfg` under `assets/hexagon/ap/`. The DSP skeleton is deliberately not placed in `lib/arm64-v8a`: it is a DSP-side image, not an Android AP ELF. The Gradle asset-merge task is ordered after `externalNativeBuildDebug` so these generated assets enter the APK. `HexagonRuntimeAssets` checks the APK metadata, extracts private copies for comparison, validates the arm64 JNI/llama/ggml AP library set, and checks the deployed DSP/config SHA-256 before `NativeLlamaSession` causes `System.loadLibrary` and `llama_backend_init`.

Use `scripts/build-android-hexagon-apks.ps1` to build the two variants in sequence. It deletes only `app/.cxx` between runs to prevent CMake cache reuse across incompatible transport settings, and checks each APK's JNI/AP libraries, DSP skeleton, identity metadata, and FastRPC config before copying it to `artifacts/apk/`.

**Deployment remains a separate step.** The pinned FastRPC source hard-codes `/data/local/tmp` for its config and runtime search path and builds `ADSP_LIBRARY_PATH` using semicolons; its HTP URI is `file:///libggml-htp-v%u.so`. `scripts/deploy-hexagon-runtime.ps1` installs the selected APK and deploys the matching skeleton (and FastRPC config) into that loader directory. The app refuses to initialize Hexagon if the deployed files do not match the APK. This workflow has not yet been exercised on the RMX3800 firmware; SELinux/loader access and actual DSP execution still require device validation. A successful `dlopen`, registry lookup, model load, or APK deployment is not proof of HTP execution.

Qualcomm Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04 are licensed prerequisites. Do not copy SDK files into this repository.
