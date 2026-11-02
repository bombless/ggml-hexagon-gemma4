# FastRPC Q4_0 prepacked tiled cache

The optional .htpbin sidecar stores eligible Q4_0 tensors in the HTP 32x32 tiled layout emitted by the pinned upstream htp_repack_tensor_tiled() implementation.

## Convert on Windows

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/convert-gguf-to-htp-tiled.ps1 -Model D:/models/gemma-4-E2B-it-Q4_0.gguf
```

This writes gemma-4-E2B-it-Q4_0.gguf.htpbin next to the source GGUF.

## Deploy alongside the model

The sidecar filename must be the GGUF filename plus .htpbin. For a model stored in the app-private files/models/ directory:

```powershell
adb push D:/models/gemma-4-E2B-it-Q4_0.gguf.htpbin /sdcard/Android/data/com.example.gemmaapp/files/models/
adb shell run-as com.example.gemmaapp cp /sdcard/Android/data/com.example.gemmaapp/files/models/gemma-4-E2B-it-Q4_0.gguf.htpbin files/models/
```

For a model in the app external files models/ directory, push the sidecar directly beside the GGUF there.

## Runtime behavior and limits

The FastRPC/mempool JNI path advertises the paired sidecar and a lightweight source fingerprint. Before using a cache entry, the backend checks the source fingerprint plus the tensor name, type, and dimensions. A missing, stale, or incompatible sidecar falls back to the original GGUF runtime repack path.

The first converter version caches eligible Q4_0 tensors only; other tensor types retain their existing load behavior. The sidecar is specific to this pinned upstream tiled-layout implementation and is not portable to dspqueue or other backend revisions. The source fingerprint samples the file size and its first/last 64 KiB; it is intended to catch accidental model/cache mismatches, not to provide cryptographic integrity.
