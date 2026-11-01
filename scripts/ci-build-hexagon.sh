#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_URL="${GGML_HEXAGON_REPOSITORY:-https://github.com/ggml-hexagon/ggml-hexagon.git}"
UPSTREAM_REF="${GGML_HEXAGON_REF:-a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d}"
WORK_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ggml-hexagon-src"
ARTIFACT_ROOT="${GITHUB_WORKSPACE:-$(pwd)}/artifacts/hexagon-android-arm64"

fail() { echo "ERROR: $*" >&2; exit 1; }

# Discover SDK roots only when the caller/image has not supplied them.
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  for candidate in "${ANDROID_NDK:-}" "${ANDROID_HOME:-}/ndk"/* "${ANDROID_SDK_ROOT:-}/ndk"/* /opt/android-sdk/ndk/* /opt/android-sdk-linux/ndk/*; do
    [[ -n "$candidate" && -f "$candidate/build/cmake/android.toolchain.cmake" ]] || continue
    ANDROID_NDK_ROOT="$candidate"; break
  done
fi
[[ -n "${ANDROID_NDK_ROOT:-}" && -f "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" ]] || fail "Android NDK not found. Set ANDROID_NDK_ROOT to an installed NDK."

if [[ -z "${HEXAGON_SDK_ROOT:-}" ]]; then
  sdk_cmake="$(find /opt /usr/local /root -path '*/build/cmake/hexagon_arch.cmake' -print -quit 2>/dev/null || true)"
  [[ -n "$sdk_cmake" ]] && HEXAGON_SDK_ROOT="$(dirname "$(dirname "$(dirname "$sdk_cmake")")")"
fi
[[ -n "${HEXAGON_SDK_ROOT:-}" && -f "$HEXAGON_SDK_ROOT/build/cmake/hexagon_arch.cmake" ]] || fail "Qualcomm Hexagon SDK 6.7.0.0 not found. It must be installed under its license terms; set HEXAGON_SDK_ROOT."

if [[ -z "${HEXAGON_TOOLS_ROOT:-}" ]]; then
  if [[ -f "$HEXAGON_SDK_ROOT/hexagon_sdk.json" ]]; then
    tools_rel="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["root"]["tools"]["info"][0]["path"])' "$HEXAGON_SDK_ROOT/hexagon_sdk.json")"
    HEXAGON_TOOLS_ROOT="$HEXAGON_SDK_ROOT/$tools_rel"
  else
    HEXAGON_TOOLS_ROOT="$HEXAGON_SDK_ROOT/tools/HEXAGON_Tools/21.0.04"
  fi
fi
[[ -d "$HEXAGON_TOOLS_ROOT" ]] || fail "Hexagon Tools not found: $HEXAGON_TOOLS_ROOT"

PREBUILT_LIB_DIR="${PREBUILT_LIB_DIR:-$HEXAGON_TOOLS_ROOT/Tools/target/hexagon/lib}"
[[ -d "$PREBUILT_LIB_DIR" ]] || fail "Hexagon prebuilt library directory not found: $PREBUILT_LIB_DIR"

export ANDROID_NDK_ROOT HEXAGON_SDK_ROOT HEXAGON_TOOLS_ROOT PREBUILT_LIB_DIR
export ANDROID_NDK="$ANDROID_NDK_ROOT"
export OPENCL_SDK_ROOT="${OPENCL_SDK_ROOT:-/tmp/opencl-sdk-not-used}"

rm -rf "$WORK_ROOT" "$ARTIFACT_ROOT"
mkdir -p "$(dirname "$WORK_ROOT")" "$ARTIFACT_ROOT"
git clone --filter=blob:none "$UPSTREAM_URL" "$WORK_ROOT"
git -C "$WORK_ROOT" checkout --detach "$UPSTREAM_REF"
printf 'Upstream revision: '; git -C "$WORK_ROOT" rev-parse HEAD

build_variant() {
  local name="$1"
  local use_mempool="$2"
  local build_dir="$WORK_ROOT/build-$name"
  echo "=== Configure $name (GGML_HEXAGON_USE_MEMPOOL=$use_mempool) ==="
  cmake -S "$WORK_ROOT" -B "$build_dir" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 \
    -DGGML_OPENMP=OFF -DGGML_OPENCL=OFF -DGGML_CCACHE=OFF \
    -DGGML_HEXAGON=ON -DGGML_HEXAGON_USE_MEMPOOL="$use_mempool" \
    -DLLAMA_CURL=OFF -DGGML_LLAMAFILE=ON \
    -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" \
    -DHEXAGON_TOOLS_ROOT="$HEXAGON_TOOLS_ROOT" \
    -DPREBUILT_LIB_DIR="$PREBUILT_LIB_DIR"
  cmake --build "$build_dir" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
  local dest="$ARTIFACT_ROOT/$name"
  mkdir -p "$dest/bin" "$dest/dsp"
  cp -av "$build_dir/bin/"*.so "$dest/bin/" 2>/dev/null || true
  cp -av "$build_dir/bin/"llama-* "$dest/bin/" 2>/dev/null || true
  find "$build_dir" -type f -name 'libggml-htp-v*.so' -exec cp -av {} "$dest/dsp/" \;
  [[ -n "$(find "$dest" -type f -print -quit)" ]] || fail "No output files collected for $name"
  (cd "$dest" && find . -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
}

build_variant fastrpc ON
build_variant dspqueue OFF
cp -f "$WORK_ROOT/LICENSE" "$ARTIFACT_ROOT/" 2>/dev/null || true
{
  echo "upstream_url=$UPSTREAM_URL"
  echo "upstream_ref=$UPSTREAM_REF"
  git -C "$WORK_ROOT" rev-parse HEAD | sed 's/^/upstream_commit=/'
  echo "android_abi=arm64-v8a"
  echo "android_platform=android-34"
  echo "hexagon_sdk_root=$HEXAGON_SDK_ROOT"
  echo "hexagon_tools_root=$HEXAGON_TOOLS_ROOT"
  echo "prebuilt_lib_dir=$PREBUILT_LIB_DIR"
  echo "fastrpc=GGML_HEXAGON_USE_MEMPOOL=ON"
  echo "dspqueue=GGML_HEXAGON_USE_MEMPOOL=OFF"
} > "$ARTIFACT_ROOT/BUILD-INFO.txt"
echo "Build artifacts: $ARTIFACT_ROOT"
