# Model and tokenizer provisioning

## Current app model format

The migrated app currently uses Gemma 4 E2B ONNX graphs, not GGUF. The app's model discovery requires all six files in one model directory:

| File | Expected SHA-256 |
|---|---|
| decoder_model_merged_q4f16.onnx | 73c0f1fe04f9a3a048fb3319c0671b6cf0346bf33a3a8624c853bcffe01c24a4 |
| decoder_model_merged_q4f16.onnx_data | 3b27245a7396cb7039a4e4118bd2a8aa35106bae381522edf7c4867b5f22bb10 |
| embed_tokens_q4f16.onnx | d7ca53f6a169471b5699b2f57ee4c7aa2c73732b0152f3909e64b71384444825 |
| embed_tokens_q4f16.onnx_data | 024b199e6358ed42970f807686add5f9430d7e254ca7ce22fc9c83f015b9c517 |
| audio_encoder_q4f16.onnx | 5e0deb22791685c792d4b8e089deef9670fa4a4cecde434213d6a742e58fc3fa |
| audio_encoder_q4f16.onnx_data | df58e61a00bafa9449ee5fd52895ce952f158bbdd1fe38df8a68f48f36842e62 |

The in-app downloader pins these hashes and writes into the app-specific external files models/ directory. Confirm the source repository/revision and applicable model license before redistribution.

## Tokenizer files

The tokenizer currently requires these two files in the same models/ directory:

| File | Size | SHA-256 |
|---|---:|---|
| tokens_vocab.bin | 2,563,293 bytes | 8a5224e20d6409c253866c0a8b007822a630cdbdc6fb584608c01695a6081d9d |
| bpe_model.bin | 9,517,780 bytes | 00cc235e24ee32e6df96e6ec76b41c6b9333a4b6a1094c0e0d2d9957d7292972 |

These hashes were calculated from the original app checkout's existing files during migration. The binary files are intentionally not checked into this repository. Obtain them from the same trusted, license-compliant source/revision as the migrated app's tokenizer data.

## Device location

The app looks in its app-specific external files directory under Android/data/com.example.gemmaapp/files/models, then its internal files directory under models/. For a development device with ADB:

    adb shell mkdir -p /sdcard/Android/data/com.example.gemmaapp/files/models
    adb push tokens_vocab.bin /sdcard/Android/data/com.example.gemmaapp/files/models/
    adb push bpe_model.bin /sdcard/Android/data/com.example.gemmaapp/files/models/

Use the app's downloader for the six ONNX graph/data files. Verify tokenizer hashes on the host before pushing. Missing or mismatched tokenizer files now produce an explicit error rather than silently loading unverified data.

## Future ggml target

The intended ggml path uses Gemma 4 E2B-it GGUF Q4_0. That model is a separate format and must not be substituted into the current ONNX/QNN app. Record its exact source, revision, license, size, and SHA-256 when the ggml loader is implemented.
