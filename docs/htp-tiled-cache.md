# FastRPC Q4_0 prepacked tiled cache

The optional .htpbin sidecar stores eligible Q4_0 and Q4_1 tensors in the HTP 32x32 tiled layout emitted by the pinned upstream repacker. Q4_K and Q5_K tensors are converted to the same Q4_0 tiled representation used by the current FastRPC backend (dequantize, requantize to Q4_0, then tile).

## Convert on Windows

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/convert-gguf-to-htp-tiled.ps1 -Model D:/models/gemma-4-E2B-it-Q4_0.gguf
```

This writes gemma-4-E2B-it-Q4_0.gguf.htpbin next to the source GGUF.

## Deploy alongside the model

The sidecar filename must be the GGUF filename plus .htpbin. For a model stored in the app-private files/models/ directory, stage it in the app's external files directory:

```powershell
adb push D:/models/gemma-4-E2B-it-Q4_0.gguf.htpbin /sdcard/Android/data/com.example.gemmaapp/files/models/
```

On the next app launch, GemmaApplication copies staged .htpbin files into the app-private files/models/ directory in the background, verifies the byte count, and removes the external staging copy after a successful import. Wait for the [TILED_CACHE_IMPORT] imported log before starting model loading. For a model that itself lives in the app external files models/ directory, push the sidecar directly beside the GGUF instead.

## Runtime behavior and limits

The FastRPC/mempool JNI path advertises the paired sidecar and a lightweight source fingerprint. Before using a cache entry, the backend checks the source fingerprint plus the tensor name, type, and dimensions. A missing, stale, or incompatible sidecar falls back to the original GGUF runtime repack path.

The converter caches eligible Q4_0, Q4_1, Q4_K, and Q5_K tensors. F32 and BF16 tensors retain their existing load behavior. For the current phone model, these four quantized types account for about 2.59 GB of prepacked payload; the original file also contains F32 and BF16 tensors. The sidecar is specific to this pinned upstream tiled-layout implementation and is not portable to dspqueue or other backend revisions. The source fingerprint samples the file size and its first/last 64 KiB; it is intended to catch accidental model/cache mismatches, not to provide cryptographic integrity.
