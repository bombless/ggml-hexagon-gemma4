#!/usr/bin/env python3
"""Validate and fingerprint one isolated Android ggml-hexagon backend artifact set."""
import argparse
import hashlib
import json
import os
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--backend", required=True, choices=("fastrpc", "dspqueue"))
    parser.add_argument("--upstream-commit", required=True)
    parser.add_argument("--sdk-version", required=True)
    parser.add_argument("--tools-version", required=True)
    parser.add_argument("--mempool", required=True, choices=("ON", "OFF"))
    args = parser.parse_args()

    root = args.root.resolve()
    ap_dir, dsp_dir = root / "ap", root / "dsp"
    if not ap_dir.is_dir() or not dsp_dir.is_dir():
        parser.error("artifact root must contain ap/ and dsp/ directories")
    if (args.backend == "fastrpc") != (args.mempool == "ON"):
        parser.error("backend name and GGML_HEXAGON_USE_MEMPOOL disagree")

    ap_files = sorted(p for p in ap_dir.rglob("*") if p.is_file() and ".so" in p.name)
    dsp_files = sorted(p for p in dsp_dir.rglob("*") if p.is_file() and ".so" in p.name)
    if not ap_files:
        raise SystemExit("ERROR: no AP shared libraries collected")
    if not any(p.name.startswith("libggml-hexagon") for p in ap_files):
        raise SystemExit("ERROR: AP ggml-hexagon backend library is missing")
    if len(dsp_files) != 1 or dsp_files[0].name != "libggml-htp-v75.so":
        raise SystemExit("ERROR: expected exactly dsp/libggml-htp-v75.so; refusing mixed or incomplete DSP artifacts")

    files = []
    runtime_files = sorted(p for base in (ap_dir, dsp_dir) for p in base.rglob("*") if p.is_file())
    for path in runtime_files:
        size = path.stat().st_size
        if size <= 0:
            raise SystemExit(f"ERROR: empty runtime library: {path}")
        files.append({"path": path.relative_to(root).as_posix(), "size": size, "sha256": sha256(path)})

    manifest = {
        "schema_version": 1,
        "backend": args.backend,
        "android_abi": "arm64-v8a",
        "android_platform": "android-34",
        "soc_target": "SM8650",
        "dsp_target": "HTP v75",
        "upstream_commit": args.upstream_commit,
        "hexagon_sdk_version": args.sdk_version,
        "hexagon_tools_version": args.tools_version,
        "cmake_options": {"GGML_HEXAGON": "ON", "GGML_HEXAGON_USE_MEMPOOL": args.mempool},
        "files": files,
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Validated {args.backend}: {len(ap_files)} AP libraries + HTP v75 skeleton")
    return 0


if __name__ == "__main__":
    sys.exit(main())