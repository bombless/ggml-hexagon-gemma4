# TODO — Gemma 4 Android + Hexagon

> Handoff checklist for the next development context.
>
> Project goal: build a complete, reproducible Android application that runs Gemma 4 E2B on
> realme RMX3800 / Snapdragon 8 Gen 3 (SM8650, HTP v75), with selectable FastRPC+mempool
> and dspqueue backends, and produces comparable prompt-processing (PP) / token-generation
> (TG) benchmarks.

## 0. Ground rules and workspace map

- [ ] Read this file and the current README before making changes.
- [ ] Keep the upstream checkout `D:\mcp-agent-workspace\ggml-hexagon` untouched. Its pinned baseline is
  `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d` (branch `self-build-jz`).
- [ ] Keep the original Android app checkout `D:\mcp-agent-workspace\gemma-android-app` untouched.
  It contains uncommitted/local data and must be treated as a read-only source for migration.
- [ ] Work only in `D:\mcp-agent-workspace\ggml-hexagon-gemma4` unless explicitly agreed otherwise.
- [ ] Do not use the old `ggml-hexagon-bench` checkout.
- [ ] Do not commit model weights, tokenizer/model binaries, API tokens, credentials, logs containing
  private data, build outputs, or Qualcomm proprietary SDK/toolchain files.
- [ ] Inspect source files and git status before copying. Preserve app functionality; do not blindly
  copy the entire original app workspace.
- [ ] Document every upstream file copied or adapted, its source revision, and any local patches.

## 1. Repository inventory and architecture

- [ ] Inventory the original Android app source: Gradle files, manifest, Java/Kotlin sources,
  resources, assets, native code, tests, and required runtime files.
- [ ] Inventory upstream Hexagon integration: top-level CMake, ggml backend, HTP skeleton build,
  Snapdragon scripts, ADB helpers, runtime dependencies, and supported SoC variants.
- [ ] Decide and document the source strategy: pinned upstream submodule, reproducible clone/fetch
  at a fixed commit, or a minimal maintained fork. Avoid duplicating large upstream trees without
  a clear maintenance reason.
- [ ] Add an architecture document showing Android UI -> JNI -> llama/ggml -> Hexagon backend ->
  HTP v75 DSP, including which libraries run on AP and DSP.
- [ ] Define a clean directory layout for `app/`, `native/`, `third_party/` (if needed),
  `scripts/`, `docs/`, and CI workflows.
- [ ] Add license and third-party notices for all imported/adapted code.

## 2. Android application migration

- [ ] Migrate the actual Android app module into this repository (not just a placeholder README).
- [ ] Include Gradle wrapper, settings/build scripts, manifest, source, resources, and tests that are
  required to build and install the app.
- [ ] Audit app assets and separate small required assets from large model/tokenizer files.
- [ ] Provide a documented model-import/download path; never require committing model weights.
- [ ] Add model file validation (expected files, size sanity checks, SHA-256) and useful UI errors
  for missing/incompatible files.
- [ ] Confirm package/application ID, minimum/target SDK, ABI filters, permissions, and storage
  behavior are intentional.
- [ ] Build a debug APK in CI and upload it as an artifact separately from native backend artifacts.
- [ ] Install and launch the APK on the target phone; capture logcat for startup and inference.

## 3. Native / JNI integration

- [ ] Add native CMake project and JNI bridge used by the Android app.
- [ ] Integrate the correct llama.cpp / ggml source revision and record the exact commit.
- [ ] Ensure the Gemma 4 architecture/model support is present in the selected upstream revision.
- [ ] Link the Android-side shared libraries for the selected backend and package them in the APK
  or deploy them through a clearly documented development workflow.
- [ ] Define runtime backend selection (FastRPC+mempool vs dspqueue) without mixing artifacts.
- [ ] Implement native initialization, model loading, prompt submission, token streaming, cancel,
  shutdown, and error propagation.
- [ ] Add JNI thread/lifecycle safety and avoid blocking the Android UI thread.
- [ ] Add a CPU fallback or a clear diagnostic if Hexagon initialization is unavailable.
- [ ] Verify ABI dependencies with `readelf`/Android tooling and inspect unresolved symbols.

## 4. Hexagon SDK and DSP build

- [ ] Keep the upstream pin explicit and reproducible.
- [ ] Document the licensed Qualcomm Hexagon SDK 6.7.0.0 / Hexagon Tools 21.0.04 prerequisites
  and setup steps. Never commit or redistribute SDK files unless the license explicitly permits it.
- [ ] Preserve the CI fix that supplies `PREBUILT_LIB_DIR` to CMake; validate the directory before
  configuration.
- [ ] Build both configurations:
  - [ ] FastRPC + mempool (`GGML_HEXAGON_USE_MEMPOOL=ON`)
  - [ ] dspqueue (`GGML_HEXAGON_USE_MEMPOOL=OFF`)
- [ ] Make target scope explicit: Android arm64 AP libraries and HTP v75 DSP skeleton for SM8650.
- [ ] Avoid spending CI time building unrelated HTP variants unless they are deliberately retained.
- [ ] Verify the artifact contains every required AP-side `.so`, DSP skeleton, dependency, and
  metadata file for each backend.
- [ ] Add an artifact manifest with relative paths, sizes, SHA-256 hashes, upstream commit, SDK/tool
  versions, CMake options, and backend name.
- [ ] Add a CI validation step that fails if expected files are absent or empty.
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
- [ ] Add Android APK build and unit tests.
- [ ] Add native host-side tests where practical.
- [ ] Add a versioned build manifest and release notes.
- [ ] Ensure workflow permissions are least-privilege and secrets are never printed.
- [ ] Test workflow on a clean runner and document any licensed self-hosted runner requirement.
- [ ] Distinguish clearly between compile success, APK success, device smoke-test success, and
  benchmark completion.

## 9. Documentation and handoff

- [ ] Rewrite README to describe the actual working project, prerequisites, build, install, model
  provisioning, backend selection, device run, and benchmark commands.
- [ ] Add `docs/architecture.md`, `docs/build-android.md`, `docs/device-setup-rmx3800.md`,
  `docs/model-setup.md`, and `docs/benchmark.md`.
- [ ] Include exact tested commands and expected output snippets.
- [ ] Maintain a known-issues section with reproducible symptoms and workarounds.
- [ ] Update this TODO as tasks are completed; link important commits and test runs.

## Current known state (2026-11-01)

- [x] GitHub Actions can build the two Android arm64 Hexagon backend configurations using the
  pinned upstream source.
- [x] CMake configure issue caused by missing `PREBUILT_LIB_DIR` was fixed in CI script.
- [x] Latest successful run: [Actions run 41278349559](https://github.com/bombless/ggml-hexagon-gemma4/actions/runs/41278349559).
- [x] That run uploaded artifact `gemma4-hexagon-android-arm64-46f015b963f9dc1720b8bec4c1daeca6c8f305ed`.
- [ ] Android app source has not yet been migrated into this repository.
- [ ] JNI/native application integration has not yet been implemented or verified.
- [ ] Device deployment and HTP v75 execution have not yet been verified.
- [ ] No RMX3800 PP/TG benchmark has been run.
- [ ] Current CI success is a native backend cross-build result, not proof of a complete runnable app.

## Definition of done

The project is not considered complete until a clean checkout can build an installable APK and
both backend artifact sets, install and launch on the RMX3800, load the documented Gemma 4 E2B
model, demonstrate actual HTP v75 execution for each backend, and produce repeatable PP/TG
benchmark results with raw logs and metadata.
