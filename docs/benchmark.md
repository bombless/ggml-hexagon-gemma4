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

## Measurement procedure

1. Confirm model and prompt hashes match across backends.
2. Warm up each backend using the same settings.
3. Alternate order (A/B, then B/A) to reduce thermal/order bias.
4. Measure prompt processing (PP) and token generation (TG) separately.
5. Record time-to-first-token, end-to-end latency, peak memory, and failures.
6. Run multiple repetitions; report median and spread, including failed runs.
7. Compare only equivalent configurations and note thermal throttling.

PP = prompt tokens / prompt-processing seconds. TG = generated tokens / decode seconds. Never infer performance from build time or a different SoC. No benchmark result is currently available.
