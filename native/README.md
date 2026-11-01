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

The llama/ggml backend registration and explicit HTP device selection are now wired for Hexagon builds. Transport-specific AP/DSP deployment, ELF dependency closure, actual Gemma 4 E2B-it model validation, HTP operation proof, and target-device inference remain pending. No CPU result is reported as HTP execution.

## Android transport packaging

`native/CMakeLists.txt` binds each transport to its upstream HTP v75 ExternalProject target:

| APK identity | AP implementation / IDL | HTP skeleton target | Additional runtime asset |
| --- | --- | --- | --- |
| `fastrpc-mempool` | `ggml-hexagon-fastrpc.cpp` / `ggml_htp.idl` | `htp-mempool-v75` | `ggml-hexagon.cfg` |
| `dspqueue` | `ggml-hexagon.cpp` / `htp_iface.idl` | `htp-v75` | none currently identified by the pinned source |

The JNI target depends on the selected HTP v75 ExternalProject, then stages its DSP skeleton under `assets/hexagon/dsp/` and writes `assets/hexagon/backend.properties`. FastRPC also stages the exact pinned upstream `scripts/ggml-hexagon.cfg` under `assets/hexagon/ap/`. The DSP skeleton is deliberately not placed in `lib/arm64-v8a`: it is a DSP-side image, not an Android AP ELF. The Gradle asset-merge task is ordered after `externalNativeBuildDebug` so these generated assets enter the APK.

Use `scripts/build-android-hexagon-apks.ps1` to build the two variants in sequence. It deletes only `app/.cxx` between runs to prevent CMake cache reuse across incompatible transport settings, and checks each APK's JNI/AP libraries, DSP skeleton, identity metadata, and FastRPC config before copying it to `artifacts/apk/`.

**Deployment remains a separate step.** The upstream runtime requests `file:///libggml-htp-v%u.so`; putting the file in APK assets does not make it visible to the DSP loader. Do not guess a writable system path or assume an unrooted app can install it. Resolve the supported HTP v75 skeleton deployment location and permissions for the exact RMX3800 firmware, then add/test the device-side deployment workflow. Until that is done, the two builds are compile/package variants, not runnable HTP deployments.

Qualcomm Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04 are licensed prerequisites. Do not copy SDK files into this repository.
