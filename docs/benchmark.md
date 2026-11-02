# PP / TG benchmark protocol

Benchmarking is blocked until the ggml JNI inference path runs on the target RMX3800. Do not use ONNX/QNN measurements as ggml-hexagon results.

## Fixed run manifest

For every run record:
- device model/SoC, Android fingerprint, HTP/runtime version;
- model variant, source/revision, file size, SHA-256;
- backend (FastRPC+mempool or dspqueue), upstream commit, AP/DSP artifact hashes;
- prompt text and SHA-256, tokenizer/chat template, context and batch sizes;
- thread count, generation limit, sampler settings, warm-up count, repetition count;
- run timestamp, battery, power mode, thermal status/temperature;
- raw stdout/stderr, exit code, peak memory, and energy/power data if reliably measured.

Do not store secrets or personally identifying device logs in Git.

## Build configuration

Use the Android **Release** variant for both transports; Debug builds are not valid for performance comparison because AP-side repacking and JNI code may be compiled without production optimization.

```powershell
# Run from the ggml-hexagon-gemma4 project root. Resolve the sibling upstream checkout to an absolute path.
$Upstream = (Resolve-Path ..\ggml-hexagon).Path
.\gradlew.bat :app:assembleRelease "-PggmlHexagonSource=$Upstream" -PggmlHexagonBackend=fastrpc-mempool -PhexagonSdkRoot=D:/Hexagon_SDK_WinNT/6.7.0.0
.\gradlew.bat :app:assembleRelease "-PggmlHexagonSource=$Upstream" -PggmlHexagonBackend=dspqueue -PhexagonSdkRoot=D:/Hexagon_SDK_WinNT/6.7.0.0
```

On this Android Gradle setup, `assembleRelease` configures native CMake as `RelWithDebInfo` (`-O2 -DNDEBUG`), which is optimized and is the same configuration used for both transport APKs. Keep the two APKs separate and verify their embedded backend identity before deployment.

## Model-load instrumentation

FastRPC emits one `[LOAD-STATS]` summary per GGML tensor type immediately before the first graph execution (with a shutdown fallback). Capture these fields from logcat alongside wall-clock model-load duration:

- `calls`: number of `set_tensor` calls for this quantized type;
- `bytes`: total source bytes uploaded;
- `partial_calls`: calls where offset/size did not cover the complete tensor;
- `total_us`, `avg_us`, `max_us`: time spent in the upload callback, including repacking;
- compare per-type totals between runs, not just overall load time.

The current GGUF loader path in `src/llama-model-loader.cpp` calls `ggml_backend_tensor_set(cur, data, 0, n_size)` for a tensor upload, so the expected path is one whole-tensor write rather than many tiny chunks; the counter verifies that assumption on-device and catches alternate upload paths. The FastRPC Q4_0/Q4_1/Q8_0 repackers now use the shared range-aware implementation (including ARM NEON on arm64); the Q4_K/Q5_K/Q6_K conversion paths share the NEON Q4_0 tile transpose after their existing dequantize/requantize stage.

## Measurement procedure

1. Confirm model and prompt hashes match across backends.
2. Use Release APKs, the same NDK/SDK, source revision, and DSP runtime artifacts.
3. Warm up each backend using the same settings.
4. Alternate order (A/B, then B/A) to reduce thermal/order bias.
5. Record model-load wall time and per-type `[LOAD-STATS]` before measuring inference.
6. Measure prompt processing (PP) and token generation (TG) separately.
7. Record time-to-first-token, end-to-end latency, peak memory, and failures.
8. Run multiple repetitions; report median and spread, including failed runs.
9. Compare only equivalent configurations and note thermal throttling.

PP = prompt tokens / prompt-processing seconds. TG = generated tokens / decode seconds. Never infer performance from build time or a different SoC. No new device benchmark result is claimed until both Release APKs are deployed and tested on the RMX3800.
