# RMX3800 device setup (verification pending)

Target: realme RMX3800, Snapdragon 8 Gen 3 (SM8650), HTP v75. No successful device deployment or DSP execution is claimed yet.

## Recorded app smoke test (2026-11-01)

- ADB identified RMX3800, SoC SM8650, Android 16, ABI arm64-v8a.
- Installed the migrated debug APK in place; the existing app data was retained.
- Provisioned tokenizer files from the untouched source checkout and verified both SHA-256 values against docs/model-setup.md.
- The app process was observed alive after startup and logged `Engine ready backend=QNN HTP (Snapdragon 8 Gen 3 NPU)`. It was no longer present in a later process check; no app crash was found in the filtered system/runtime logs.
- QNN decoder optrace session opened. The embedding graph had no usable QNN context and used CPU, as designed by the current app.
- No prompt was submitted and no inference throughput was measured. This validates only the existing ONNX Runtime/QNN app startup, not ggml-hexagon, FastRPC, dspqueue, or a standalone HTP operator smoke test.

## Baseline capture

Connect the phone with USB debugging enabled and capture:

    adb devices -l
    adb shell getprop ro.product.model
    adb shell getprop ro.soc.model
    adb shell getprop ro.build.fingerprint
    adb shell getprop ro.build.version.release
    adb shell getprop ro.product.cpu.abi
    adb shell df -h /data
    adb shell cat /proc/meminfo
    adb shell dumpsys thermalservice

Check that the device is the intended SM8650 target before recording results. Record output and timestamp in a private run directory; do not commit logs containing personal/device identifiers.

## App smoke test (current QNN app)

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb logcat -c
    adb shell monkey -p com.example.gemmaapp 1
    adb logcat -d -s GemmaEngine GemmaQnnOptrace

A successful launch is not proof that inference used HTP. Inspect QNN provider initialization and profiling diagnostics; record any CPU fallback.

## ggml Hexagon deployment (blocked on JNI/runtime packaging)

Do not deploy until the JNI/native runtime contract and a complete artifact manifest exist. For each backend separately:

1. Select exactly one artifact directory (fastrpc or dspqueue).
2. Verify AP shared-library dependencies and matching HTP v75 skeleton names/hashes.
3. Deploy to documented runtime locations with correct permissions and SELinux constraints.
4. Clear stale files before switching variants.
5. Capture linker, FastRPC/dspqueue, DSP loader, logcat, and tombstone diagnostics.
6. Run a deterministic HTP operation and prove DSP execution from supported runtime diagnostics, not merely successful library loading.

Record cleanup/recovery commands only after validating them on the target device.
