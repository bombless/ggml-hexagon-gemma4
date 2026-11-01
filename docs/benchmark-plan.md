# Build and benchmark notes

## Qualcomm SDK prerequisite

The upstream HTP kernels require the full Qualcomm Hexagon SDK and Hexagon Tools. The upstream helper currently targets SDK `6.7.0.0` and Tools `21.0.04`. Qualcomm states that the full SDK is distributed under its developer account and PKLA/ECA terms. The public minimal SDK archive alone is not assumed to be sufficient.

The Actions job uses the Snapdragon Android toolchain container, then searches for a compatible SDK installation. If the image does not contain it, the job stops with an actionable error. Install the licensed SDK in a compliant private runner/image and expose `HEXAGON_SDK_ROOT` and (optionally) `HEXAGON_TOOLS_ROOT`; do not place proprietary SDK files in this repository or a public artifact.

## Device-side A/B protocol (planned)

1. Confirm `adb shell getprop ro.soc.model`, Android build fingerprint, and HTP version on the target RMX3800.
2. Download the same Gemma 4 E2B-it Q4_0 GGUF once; record SHA-256 and ensure sufficient free storage/RAM.
3. Install matching FastRPC or dspqueue AP-side libraries together with that variant's DSP skeletons. Never mix libraries from the two variants.
4. Run identical prompt, model, context, batch, generation length, thread count, and warm-up/repetition settings.
5. Alternate run order (A/B then B/A), monitor thermal throttling, and retain raw stdout/stderr and device metadata.
6. Report prompt processing (PP) and token generation (TG) separately; do not combine results from different SoCs.
