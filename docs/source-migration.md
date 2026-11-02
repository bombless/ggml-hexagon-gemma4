# Source migration provenance

## Android app source

- Source checkout: D:\mcp-agent-workspace\gemma-android-app
- Source HEAD at migration: 364619abc9b7235e1f12d3944f619934dee81996 (feat/qnn-optrace)
- Source working tree was NOT clean. The following modified tracked files were copied from their current working-tree versions, preserving local changes:
  - app/build.gradle.kts: embeds Git commit/dirty metadata in BuildConfig.
  - app/src/main/java/com/example/gemmaapp/engine/GemmaEngine.kt: emits build metadata and uses revision-tagged QNN optrace CSV names.
- Untracked local files (s.jpg, think_trace.log, think_trace_err.log, token.json) were not copied.
- Large ONNX model weights/data, tokenizer binaries, screenshots, generated build output, and caches were not copied.
- Migrated: Gradle settings/build files, Gradle 8.9 wrapper, manifest, Kotlin application/UI/engine sources, and three JVM unit-test source files.
- Local adaptation after copy: GemmaTokenizer.kt now reads tokenizer binaries from app-specific models/ storage and validates their pinned SHA-256 values. Hashes and sizes are recorded in model-setup.md.
- The source checkout remains untouched.

## Upstream ggml-hexagon

- Repository: https://github.com/ggml-hexagon/ggml-hexagon
- Pinned branch: self-build-jz
- Verified HEAD: a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d
- Pinned upstream commit remains the immutable base. The Android FastRPC loader/config directory is locally patched from `/data/local/tmp` to `/data/local/ggml-hexagon`.
- `scripts/ci-build-hexagon.sh` clones the exact pinned base and applies the same guarded one-occurrence source replacement before building, so CI artifacts and local builds use the same runtime path. This does not change the upstream base commit recorded in APK metadata.
- The sibling upstream checkout contains the same loader-path change as a local commit on top of the pinned base.

## Licensing note

The original Android app checkout did not contain a root-level LICENSE file in the inspected workspace. Confirm the source author's redistribution terms and add the appropriate license/notice before publishing this migration. Third-party dependency licenses remain governed by their respective packages. Qualcomm SDK/toolchain files are not included.
