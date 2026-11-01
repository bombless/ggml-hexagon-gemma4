# Gemma 4 Android + Hexagon

Android application and reproducible Snapdragon Hexagon build/benchmark workspace for Gemma 4 E2B.

## Current target

- Device: realme RMX3800 / Snapdragon 8 Gen 3 (SM8650)
- HTP: v75
- Model: Gemma 4 E2B-it, GGUF Q4_0
- Backends under comparison: FastRPC + mempool and dspqueue

Do not compare measurements from another SoC as if they were measurements from this device.

## Repository layout

- `app/` — Android application (to be added)
- `native/` — JNI/CMake integration (to be added)
- `scripts/` — build and device-side benchmark helpers
- `.github/workflows/` — GitHub Actions cross-build and artifact publishing
- `docs/` — setup and benchmark notes

## Build artifacts with GitHub Actions

Run **Hexagon Android artifacts** from the Actions tab (or push this repository). The workflow checks out the pinned `ggml-hexagon` revision and builds both variants for Android arm64. It uploads the AP-side shared libraries and DSP skeletons; it does not require or attempt to access a phone.

The Qualcomm Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04 are subject to Qualcomm's license terms (PKLA/ECA). The workflow uses a preinstalled SDK if the Snapdragon toolchain image provides one. Otherwise provide the SDK in your own compliant build environment and set `HEXAGON_SDK_ROOT` / `HEXAGON_TOOLS_ROOT`; do not commit the SDK or redistribute it as a public artifact unless your license permits that.

## Device benchmark

The actual PP/TG comparison must run on the RMX3800. Download the workflow artifact, deploy the two matching library sets with ADB, then run the same model, prompt, context, batch, thread, and warm-up settings for each backend. Keep the raw logs and record Android build, thermal state, power mode, model SHA-256, and backend revision with every result.

The workflow artifact is a build output, not a benchmark result. Device-side execution and result collection will be added in a later step.

## Upstream source pin

- Repository: https://github.com/ggml-hexagon/ggml-hexagon
- Branch: `self-build-jz`
- Commit: `a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d`
