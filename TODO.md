# TODO — Gemma 4 Android + Hexagon

> Handoff checklist for the next development context.
>
> Project goal: build a complete, reproducible Android application that runs Gemma 4 E2B on
> realme RMX3800 / Snapdragon 8 Gen 3 (SM8650, HTP v75), with selectable FastRPC+mempool
> and dspqueue backends, and produces comparable prompt-processing (PP) / token-generation
> (TG) benchmarks.

## 0. Ground rules and workspace map

- [x] Read this file and the current README before making changes.
- [x] Keep the upstream checkout `D:\mcp-agent-workspace\ggml-hexagon` untouched. Its pinned baseline is
  `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d` (branch `self-build-jz`).
- [x] Keep the original Android app checkout `D:\mcp-agent-workspace\gemma-android-app` untouched.
  It contains uncommitted/local data and must be treated as a read-only source for migration.
- [ ] Work only in `D:\mcp-agent-workspace\ggml-hexagon-gemma4` unless explicitly agreed otherwise.
- [ ] Do not use the old `ggml-hexagon-bench` checkout.
- [ ] Do not commit model weights, tokenizer/model binaries, API tokens, credentials, logs containing
  private data, build outputs, or Qualcomm proprietary SDK/toolchain files.
- [x] Inspect source files and git status before copying. Preserve app functionality; do not blindly
  copy the entire original app workspace.
- [x] Document every upstream file copied or adapted, its source revision, and any local patches.

## 1. Repository inventory and architecture

- [x] Inventory the original Android app source: Gradle files, manifest, Java/Kotlin sources,
  resources, assets, native code, tests, and required runtime files.
- [x] Inventory upstream Hexagon integration: top-level CMake, ggml backend, HTP skeleton build,
  Snapdragon scripts/ADB helper directory, runtime library outputs, and declared DSP variants (see native/integration-design.md).
- [x] Decide and document the source strategy: pinned upstream submodule, reproducible clone/fetch
  at a fixed commit, or a minimal maintained fork. Avoid duplicating large upstream trees without
  a clear maintenance reason.
- [x] Add an architecture document showing Android UI -> JNI -> llama/ggml -> Hexagon backend ->
  HTP v75 DSP, including which libraries run on AP and DSP.
- [x] Define a clean directory layout for `app/`, `native/`, `third_party/` (if needed),
  `scripts/`, `docs/`, and CI workflows.
- [ ] Add license and third-party notices for all imported/adapted code.

## 2. Android application migration

- [x] Migrate the actual Android app module into this repository (not just a placeholder README).
- [x] Include Gradle wrapper, settings/build scripts, manifest, source, resources, and tests that are
  required to build and install the app.
- [x] Audit app assets and separate small required assets from large model/tokenizer files.
- [x] Provide a documented model-import/download path; never require committing model weights.
- [x] Add model file validation (expected files, size sanity checks, SHA-256) and useful UI errors
  for missing/incompatible files.
- [ ] Confirm package/application ID, minimum/target SDK, ABI filters, permissions, and storage
  behavior are intentional.
- [ ] Build a debug APK in CI and upload it as an artifact separately from native backend artifacts.
- [x] Install and launch the APK on the target phone; capture startup logcat (QNN engine initialized; see docs/device-setup-rmx3800.md).
- [ ] Submit a deterministic prompt and capture inference logcat/output on the target phone.

## 3. Native / JNI integration

- [x] Add an optional native CMake project and JNI llama session used by the Android app (CPU vertical slice; native build validation pending).
- [ ] Integrate the correct llama.cpp / ggml source revision and record the exact commit.
- [x] Ensure the Gemma 4 architecture/model support is present in the selected upstream revision (verified Gemma 4 model implementation, conversion, tokenizer, and chat-template code at the pinned commit; target-device model validation remains open).
- [ ] Link the Android-side shared libraries for the selected backend and package them in the APK
  or deploy them through a clearly documented development workflow.
- [x] Define compile-time backend identity and runtime HTP registry/device selection (FastRPC+mempool vs dspqueue); JNI rejects an APK/request identity mismatch.
- [ ] Implement native initialization, model loading, prompt submission, token streaming, cancel,
  shutdown, and error propagation.
- [ ] Add JNI thread/lifecycle safety and avoid blocking the Android UI thread.
- [ ] Add a CPU fallback or a clear diagnostic if Hexagon initialization is unavailable.
- [ ] Verify ABI dependencies with `readelf`/Android tooling and inspect unresolved symbols.

## 4. Hexagon SDK and DSP build

- [x] Keep the upstream pin explicit and reproducible (build script verifies the checked-out HEAD equals the pinned commit).
- [ ] Document the licensed Qualcomm Hexagon SDK 6.7.0.0 / Hexagon Tools 21.0.04 prerequisites
  and setup steps. Never commit or redistribute SDK files unless the license explicitly permits it.
- [ ] Preserve the CI fix that supplies `PREBUILT_LIB_DIR` to CMake; validate the directory before
  configuration.
- [ ] Build both configurations:
  - [ ] FastRPC + mempool (`GGML_HEXAGON_USE_MEMPOOL=ON`)
  - [ ] dspqueue (`GGML_HEXAGON_USE_MEMPOOL=OFF`)
- [x] Wire each Android JNI build to its matching upstream HTP v75 ExternalProject and stage the DSP skeleton as an APK asset, not an arm64 JNI library.
- [x] Stage the pinned FastRPC `ggml-hexagon.cfg` separately from dspqueue and embed backend/SoC/ABI metadata.
- [x] Add a Windows dual-APK build script with isolated CMake caches and APK-entry validation; licensed SDK execution remains pending.
- [ ] Make target scope explicit: Android arm64 AP libraries and HTP v75 DSP skeleton for SM8650.
- [ ] Avoid spending CI time building unrelated HTP variants unless they are deliberately retained.
- [ ] Verify the artifact contains every required AP-side `.so`, DSP skeleton, dependency, and
  metadata file for each backend (initial AP `.so` collection and HTP v75 checks are implemented; complete transitive dependency closure still needs device/ELF validation).
- [x] Add an artifact manifest with relative paths, sizes, SHA-256 hashes, upstream commit, SDK/tool
  versions, CMake options, and backend name (`scripts/validate-hexagon-artifacts.py`).
- [x] Add a CI validation step that fails if expected files are absent or empty.
- [ ] Upload artifacts with a clear retention policy and stable naming.
- [ ] Test that the two backend output directories are self-contained and cannot accidentally be
  mixed during deployment.

## 5. RMX3800 / HTP v75 device deployment

- [ ] Add scripts to detect ADB device state, ABI, SoC, Android build fingerprint, and available
  storage/RAM.
- [ ] Verify the device is SM8650 and confirm the expected HTP v75 runtime path/version using
  supported diagnostics; do not infer successful DSP use merely from successful library loading.
- [ ] Add separate deployment commands for FastRPC and dspqueue.
- [ ] Deploy the correct AP libraries and matching DSP skeleton to their required locations.
- [ ] Check file permissions, SELinux constraints, linker search paths, and DSP loader diagnostics.
- [ ] Add a smoke test that proves an HTP operation actually executes on DSP.
- [ ] Capture logcat, DSP/FastRPC diagnostics, exit codes, and relevant crash/tombstone information.
- [ ] Document recovery/cleanup between backend switches and ensure stale libraries are not reused.

## 6. Gemma 4 E2B inference correctness

- [ ] Confirm exact model variant and format (target currently Gemma 4 E2B-it, GGUF Q4_0);
  record model provenance and SHA-256.
- [ ] Verify tokenizer, chat template, special tokens, and multimodal/text-only assumptions.
- [ ] Add a deterministic smoke prompt and expected behavioral/output checks.
- [ ] Compare CPU and Hexagon outputs for basic correctness, allowing for expected quantization/
  floating-point variation.
- [ ] Test context handling, prompt truncation, generation limits, stop sequences, and cancellation.
- [ ] Test repeated load/unload and app background/foreground lifecycle.
- [ ] Record failures and unsupported operators rather than silently falling back.

## 7. PP / TG benchmark and A/B comparison

- [ ] Add a reproducible device benchmark command for both backends.
- [ ] Fix and record model hash, prompt text/hash, context size, batch size, generation length,
  thread count, sampling settings, warm-up count, and repetition count.
- [ ] Measure PP (prompt tokens/s) and TG (generated tokens/s) separately.
- [ ] Record time-to-first-token, total latency, peak memory, and energy/power data if reliably
  available.
- [ ] Alternate backend run order (A/B then B/A) to reduce thermal/order bias.
- [ ] Record device temperature/thermal status, power mode, battery state, Android fingerprint,
  backend commit, SDK/tool versions, and run timestamp.
- [ ] Save raw stdout/stderr and machine-readable JSON/CSV results.
- [ ] Add a comparison script that reports median, spread, and failed runs; do not compare unlike
  prompts/configurations.
- [ ] Run multiple repetitions after warm-up and report PP/TG independently.
- [ ] Do not claim a backend is faster until measurements are collected on this exact RMX3800.

## 8. CI / release quality

- [ ] CI checks formatting, shell syntax, CMake configure/build, and expected artifact contents.
- [x] Add Android APK build and unit tests (workflow added; local test/build passed, CI run pending).
- [ ] Add native host-side tests where practical.
- [ ] Add a versioned build manifest and release notes.
- [ ] Ensure workflow permissions are least-privilege and secrets are never printed.
- [ ] Test workflow on a clean runner and document any licensed self-hosted runner requirement.
- [ ] Distinguish clearly between compile success, APK success, device smoke-test success, and
  benchmark completion.

## 9. Documentation and handoff

- [x] Rewrite README to describe the actual working project, prerequisites, build, install, model
  provisioning, backend selection, device run, and benchmark commands.
- [x] Add `docs/architecture.md`, `docs/build-android.md`, `docs/device-setup-rmx3800.md`,
  `docs/model-setup.md`, and `docs/benchmark.md`.
- [ ] Include exact tested commands and expected output snippets.
- [ ] Maintain a known-issues section with reproducible symptoms and workarounds.
- [ ] Update this TODO as tasks are completed; link important commits and test runs.

## Next-context handoff — start here (2026-11-01)

**Immediate objective:** build the FastRPC+mempool and dspqueue JNI APK variants with the licensed SDK, audit/package matching AP/DSP dependencies, then validate a deterministic Gemma 4 GGUF prompt on the RMX3800. Do not redo the Android migration or QNN startup test.

### First actions in the next context

1. Read `README.md`, `docs/architecture.md`, `docs/build-android.md`, `docs/model-setup.md`, `docs/device-setup-rmx3800.md`, `docs/source-migration.md`, and this handoff.
2. Inspect target-repository `git status` and current `native/`, `scripts/`, and CI files. Work only in this repository.
3. Inspect the pinned ggml-hexagon checkout read-only at commit `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d`; record its actual exported API, CMake targets, AP libraries, DSP skeleton names, and deployment assumptions. Do not assume a guessed JNI ABI or library list.
4. Confirm the selected llama.cpp/ggml revision really loads Gemma 4 E2B-it GGUF Q4_0. The pinned Hexagon backend source's Gemma 4 architecture/conversion/tokenizer/chat-template code is evidence of source support only, not proof of the target model running on HTP.
5. Review `native/integration-design.md` and the artifact manifests; keep FastRPC+mempool and dspqueue as mutually matched build/runtime variants.
6. Build both transport variants with the pinned SDK, then complete AP dependency closure and deployment scripts. The JNI model/session and registry selection slice is already present; target-device model inference remains to be tested.
7. Build and test on host/CI, install on the connected RMX3800 only when ready, and prove DSP execution from runtime diagnostics. Keep QNN/ONNX and ggml-hexagon results clearly separate.

### Environment and verified facts

- Target repo: `D:\mcp-agent-workspace\ggml-hexagon-gemma4`.
- Read-only source app: `D:\mcp-agent-workspace\gemma-android-app`, source HEAD `364619abc9b7235e1f12d3944f619934dee81996`; its original working tree had two modified tracked files which were preserved during migration.
- Read-only upstream: `D:\mcp-agent-workspace\ggml-hexagon`, branch `self-build-jz`, HEAD `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d`.
- Android module builds locally with JDK 17 / Gradle 8.9. `:app:testDebugUnitTest`: 15 tests passed; `:app:assembleDebug`: succeeded. APK path: `app/build/outputs/apk/debug/app-debug.apk`.
- Device seen over ADB: realme RMX3800, SM8650, Android 16, arm64-v8a. ADB serial at the time was `adb-db6fad9-xYzn4j._adb-tls-connect._tcp`; re-run `adb devices -l` rather than assuming it persists.
- The debug APK was installed on the device and the migrated ONNX Runtime app logged QNN HTP decoder session initialization. Its embedding graph fell back to CPU. Tokenizer files were pushed from the original app checkout and SHA-256 verified. No deterministic prompt was submitted; this is not a ggml-hexagon smoke test.
- Current default app engine remains ONNX Runtime + QNN. An optional JNI llama/GGUF path and compile-time ggml Hexagon registry/device selection now exist; licensed transport packaging and ggml DSP deployment remain pending.
- Android workflow: `.github/workflows/android-app.yml`. Hexagon artifacts are built separately by the existing native workflow/script. Android workflow has been added but a GitHub Actions run for it is still pending.
- Do not assume the connected ADB device remains available in the next context. Do not uninstall the app or wipe app data; use `adb install -r` unless explicitly instructed otherwise.

### Important boundaries

- Do not modify either source checkout or the pinned upstream checkout.
- Do not commit model/tokenizer payloads, credentials, personal device logs, build outputs, or Qualcomm SDK/toolchain files.
- Qualcomm Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04 are licensed prerequisites; verify availability/licensing rather than copying them into the repo.
- Do not mark HTP execution complete based only on a successful cross-build, APK install, QNN initialization, library load, or app launch.
- Do not report PP/TG until the ggml path has actually run the same model/prompt/configuration on this RMX3800 with raw logs and metadata.
- Review the broad storage permissions before treating the migrated app as release-ready; the manifest audit is intentionally still open.

---

## Current known state (2026-11-01)

- [x] GitHub Actions can build the two Android arm64 Hexagon backend configurations using the
  pinned upstream source.
- [x] CMake configure issue caused by missing `PREBUILT_LIB_DIR` was fixed in CI script.
- [x] Latest successful run: [Actions run 41278349559](https://github.com/bombless/ggml-hexagon-gemma4/actions/runs/41278349559).
- [x] That run uploaded artifact `gemma4-hexagon-android-arm64-46f015b963f9dc1720b8bec4c1daeca6c8f305ed`.
- [x] Android app source has been migrated into this repository; the current migrated engine is ONNX Runtime/QNN and is not yet wired to ggml-hexagon.
- [ ] JNI/native application integration is partially implemented: llama/ggml Hexagon registration and HTP device selection are wired; transport build, deployment, inference, and device verification remain open.
- [ ] ggml-hexagon deployment and HTP v75 DSP execution have not yet been verified. (The separate ONNX/QNN app startup test on RMX3800 is documented; it is not ggml validation.)
- [ ] No RMX3800 PP/TG benchmark has been run.
- [ ] Current CI success is a native backend cross-build result, not proof of a complete runnable app.

## Progress log (2026-11-01)

- Migrated the Android Gradle project, wrapper, manifest, Kotlin/Compose UI, engine sources, and three unit-test suites from the original app checkout at `364619abc9b7235e1f12d3944f619934dee81996`, preserving its two modified tracked files.
- Kept the original app and pinned upstream checkouts unchanged. Upstream HEAD verified as `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d`.
- Excluded ONNX/model/tokenizer binaries, logs, tokens, screenshots, and generated build outputs. Tokenizer loading now uses app-specific `models/` storage and validates the recorded SHA-256 hashes.
- Added Android APK/unit-test CI workflow, architecture/build/model/device/benchmark/provenance documentation, and native integration handoff notes.
- Local validation: `:app:testDebugUnitTest` passed (15 tests, 0 failures/errors); `:app:assembleDebug` succeeded. Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (300,769,244 bytes; SHA-256 `037b5a91aabb7d21d74f5a091bc23f289f993ac0d8f2867d1e23d881ff511f34`). The invoking tool timed out while the Gradle process continued; outputs and XML reports were subsequently inspected after process exit.
- Device validation: ADB confirmed RMX3800 / SM8650 / Android 16 / arm64-v8a. Installed the APK and verified startup. The existing QNN app opened a QNN HTP decoder optrace session and fell back to CPU for the embedding graph; tokenizer files were provisioned and their hashes verified. No prompt inference was run, and this does not validate ggml-hexagon execution.
- Still open: exact E2B-it GGUF Q4_0 load validation, ggml HTP deployment/smoke test on RMX3800, complete artifact manifest validation, deterministic inference test, and PP/TG measurements. The migrated app's default engine remains ONNX Runtime/QNN; the optional JNI path now supports CPU and compile-time-selected Hexagon builds.

## Progress log (2026-11-01, native integration preparation)

- Read the target README and architecture/build/model/device/provenance documentation before editing.
- Verified the target worktree had pre-existing modified files and untracked Android migration content; preserved these changes. Verified the pinned upstream checkout was clean at `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d` and left it untouched.
- Inspected upstream root CMake, `ggml/src/CMakeLists.txt`, Hexagon backend CMake/source, public `ggml-hexagon.h`, HTP skeleton CMake, runtime config, and Snapdragon helper directories. Confirmed `GGML_HEXAGON_USE_MEMPOOL=ON` selects FastRPC+mempool and OFF selects dspqueue; confirmed exported backend entry points and HTP skeleton naming. Full transitive Android ELF dependency closure and runtime deployment paths remain to be verified.
- Added `native/integration-design.md` and replaced `native/README.md` with verified API/build facts and a JNI/session design. No JNI or Android CMake bridge was added because model/session and backend runtime behavior still require a tested vertical-slice implementation against the pinned llama API.
- Added `scripts/validate-hexagon-artifacts.py`; it requires a non-empty AP backend library and exactly `libggml-htp-v75.so`, rejects backend/mempool mismatches, and records relative paths, sizes, SHA-256, upstream pin, SDK/tool versions, CMake options, ABI, SoC, DSP target, and backend identity.
- Updated `scripts/ci-build-hexagon.sh` to verify the actual upstream checkout pin, isolate AP and DSP outputs, collect the HTP v75 skeleton, and invoke the validator. Updated the Hexagon workflow to trigger on validator/design changes and validate both manifest JSON files.
- Validation: Python validator passed a synthetic valid artifact fixture and rejected a fixture containing an additional HTP v73 skeleton; `python -m py_compile scripts/validate-hexagon-artifacts.py` passed; `git diff --check` passed. Bash syntax check could not run because WSL is not installed on this Windows agent. No licensed Qualcomm SDK build or RMX3800 ggml inference was attempted.

## Progress log (2026-11-01, JNI implementation)

- Added `native/gemma4_jni.cpp` with JNI-owned llama model/context/sampler lifecycle, prompt tokenization, greedy token generation, token callbacks, cooperative cancellation, and serialized session destruction. API calls match the pinned llama C header.
- Added `native/CMakeLists.txt` and an opt-in Gradle `ggmlHexagonSource` property so the normal ONNX/QNN APK build remains unchanged. Added `NativeLlamaSession.kt`, which exposes the native session and dispatches generation on `Dispatchers.IO`.
- The initial JNI vertical slice is explicitly CPU-only (`n_gpu_layers=0`) and rejects FastRPC/dspqueue requests. This prevents false HTP claims while transport-specific registration, matched AP/DSP packaging, and HTP diagnostics remain unimplemented.
- Updated native integration docs and this checklist. The existing ONNX/QNN engine was not replaced.
- Validation: the default Gradle build completed in the background after its 60-second tool timeout; XML reports show 15 JVM tests passed (3 + 8 + 4; zero failures/errors). Then built the optional native target with `:app:externalNativeBuildDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon`; CMake/Ninja compiled `gemma4_jni.cpp` and linked the arm64-v8a libraries. `:app:assembleDebug` with the same property succeeded, and APK inspection confirmed `libgemma4_jni.so`, `libllama.so`, `libggml.so`, `libggml-base.so`, and `libggml-cpu.so` are packaged. JNI APK size: 328,761,873 bytes; final SHA-256 after UTF-8 hardening `DF8A2D53F8B84C79E3AA59D480B8BD7826A3F931B2D4017D78C28178288CB157`. `git diff --check` passed. This is a CPU-only llama build; GGUF inference on device and FastRPC/dspqueue HTP integration/execution remain pending.

## Progress log (2026-11-01, ggml Hexagon runtime registration)

- Enabled transport selection in `native/CMakeLists.txt`: `cpu` remains the default; `fastrpc-mempool` enables `GGML_HEXAGON=ON` plus `GGML_HEXAGON_USE_MEMPOOL=ON`; `dspqueue` enables Hexagon with mempool OFF. Hexagon builds require an explicit licensed SDK root and optionally accept the tools root.
- Exposed the same mutually exclusive build identity through Gradle properties `ggmlHexagonBackend`, `hexagonSdkRoot`, and `hexagonToolsRoot`. The APK embeds its selected backend identity; JNI refuses to open a session if the caller requests a different variant.
- Integrated the pinned llama/ggml registry: after `llama_backend_init()`, JNI looks up the registered `HTP` backend, checks that a device exists, selects its first device via `llama_model_params.devices`, and requests all model layers for offload. The CPU build retains `n_gpu_layers=0`. Unsupported operations may still fall back to CPU; this is not DSP execution proof.
- Updated README/native integration documentation. The pinned upstream checkout remains read-only and unchanged.
- Validation: `:app:externalNativeBuildDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon` succeeded on Windows with the CPU variant (CMake configure + Ninja native build). A licensed SDK build of either Hexagon transport, APK ELF dependency audit, and RMX3800 HTP inference were not run.

## Progress log (2026-11-01, transport-specific Android packaging)

- Bound `fastrpc-mempool` to upstream target `htp-mempool-v75` and `dspqueue` to `htp-v75`; the JNI native target now depends on the selected DSP skeleton build.
- Stage the DSP image under `assets/hexagon/dsp/libggml-htp-v75.so`, not `lib/arm64-v8a`, and add `assets/hexagon/backend.properties` with transport identity, upstream pin, HTP generation, and ABI. FastRPC additionally stages the pinned upstream `scripts/ggml-hexagon.cfg` under `assets/hexagon/ap/`.
- Added the generated asset directory to the Android main asset source set and made `mergeDebugAssets` depend on `externalNativeBuildDebug` for Hexagon variants.
- Added `scripts/build-android-hexagon-apks.ps1` to verify the upstream commit, build both APKs with `.cxx` cache isolation, inspect required APK entries, and save separate APKs plus SHA-256 hashes under `artifacts/apk/`.
- Updated README/native docs to describe the transport mapping and explicitly state that APK asset packaging does not deploy the DSP skeleton to the loader's `file:///libggml-htp-v75.so` search location.
- Validation: CPU JNI `:app:externalNativeBuildDebug -PggmlHexagonSource=D:/mcp-agent-workspace/ggml-hexagon` passed; CPU JNI `:app:assembleDebug` passed. The first native build invocation exceeded the 60-second tool window, but an incremental rerun completed successfully in 13 seconds. Qualcomm SDK environment variables are absent on this machine, so neither licensed transport build nor transport APK package validation has been run. Device deployment/HTP execution remains unverified.

## Definition of done

The project is not considered complete until a clean checkout can build an installable APK and
both backend artifact sets, install and launch on the RMX3800, load the documented Gemma 4 E2B
model, demonstrate actual HTP v75 execution for each backend, and produce repeatable PP/TG
benchmark results with raw logs and metadata.
