"""
Downloads the open-source ASR models used by both EchoScribe and the benchmark suite.

This file is the single source of truth for model metadata: the Android app's
`ModelCatalog.kt` mirrors these entries (name, files, size, languages, license), so a
model benchmarked here is exactly a model the app can install.

All models are redistributable:
  * Whisper ONNX exports — MIT (OpenAI Whisper) + export scripts from sherpa-onnx (Apache-2.0)
  * Silero VAD — MIT
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

MODELS_ROOT = Path(__file__).resolve().parent.parent / "models"
HF = "https://huggingface.co/csukuangfj"

# name -> (repo, [(remote_file, local_file)], approx_bytes, languages, label)
WHISPER_MODELS: dict[str, dict] = {
    "whisper-tiny-int8": {
        "repo": "sherpa-onnx-whisper-tiny",
        "files": [
            ("tiny-encoder.int8.onnx", "tiny-encoder.int8.onnx"),
            ("tiny-decoder.int8.onnx", "tiny-decoder.int8.onnx"),
            ("tiny-tokens.txt", "tiny-tokens.txt"),
        ],
        "bytes": 60_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Tiny (int8)",
    },
    "whisper-base-int8": {
        "repo": "sherpa-onnx-whisper-base",
        "files": [
            ("base-encoder.int8.onnx", "base-encoder.int8.onnx"),
            ("base-decoder.int8.onnx", "base-decoder.int8.onnx"),
            ("base-tokens.txt", "base-tokens.txt"),
        ],
        "bytes": 120_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Base (int8)",
    },
    "whisper-base-fp32": {
        "repo": "sherpa-onnx-whisper-base",
        "files": [
            ("base-encoder.onnx", "base-encoder.onnx"),
            ("base-decoder.onnx", "base-decoder.onnx"),
            ("base-tokens.txt", "base-tokens.txt"),
        ],
        "bytes": 200_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Base (fp32)",
    },
    "whisper-base-en-int8": {
        "repo": "sherpa-onnx-whisper-base.en",
        "files": [
            ("base.en-encoder.int8.onnx", "base.en-encoder.int8.onnx"),
            ("base.en-decoder.int8.onnx", "base.en-decoder.int8.onnx"),
            ("base.en-tokens.txt", "base.en-tokens.txt"),
        ],
        "bytes": 120_000_000,
        "languages": ["en"],
        "label": "Whisper Base.en (int8)",
    },
    "whisper-small-int8": {
        "repo": "sherpa-onnx-whisper-small",
        "files": [
            ("small-encoder.int8.onnx", "small-encoder.int8.onnx"),
            ("small-decoder.int8.onnx", "small-decoder.int8.onnx"),
            ("small-tokens.txt", "small-tokens.txt"),
        ],
        "bytes": 400_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Small (int8)",
    },
    "whisper-small-fp32": {
        "repo": "sherpa-onnx-whisper-small",
        "files": [
            ("small-encoder.onnx", "small-encoder.onnx"),
            ("small-decoder.onnx", "small-decoder.onnx"),
            ("small-tokens.txt", "small-tokens.txt"),
        ],
        "bytes": 700_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Small (fp32)",
    },
    "whisper-medium-int8": {
        "repo": "sherpa-onnx-whisper-medium",
        "files": [
            ("medium-encoder.int8.onnx", "medium-encoder.int8.onnx"),
            ("medium-decoder.int8.onnx", "medium-decoder.int8.onnx"),
            ("medium-tokens.txt", "medium-tokens.txt"),
        ],
        "bytes": 900_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Medium (int8)",
    },
    "whisper-turbo-int8": {
        "repo": "sherpa-onnx-whisper-turbo",
        "files": [
            ("turbo-encoder.int8.onnx", "turbo-encoder.int8.onnx"),
            ("turbo-decoder.int8.onnx", "turbo-decoder.int8.onnx"),
            ("turbo-tokens.txt", "turbo-tokens.txt"),
        ],
        "bytes": 900_000_000,
        "languages": ["multilingual"],
        "label": "Whisper Large-v3 Turbo (int8)",
    },
}

VAD_MODELS = {
    "silero-vad": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        "files": [("silero_vad.onnx", "silero_vad.onnx")],
        "bytes": 640_000,
        "label": "Silero VAD v4",
    }
}


def download_file(url: str, dest: Path) -> None:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"    cached {dest.name}", flush=True)
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    print(f"    GET {url}", flush=True)
    with urllib.request.urlopen(url) as resp, tmp.open("wb") as out:
        while True:
            block = resp.read(1 << 20)
            if not block:
                break
            out.write(block)
    tmp.rename(dest)
    print(f"    -> {dest.name} ({dest.stat().st_size / 1e6:.1f} MB)", flush=True)


def fetch(name: str, *, root: Path = MODELS_ROOT) -> Path:
    if name in WHISPER_MODELS:
        spec = WHISPER_MODELS[name]
        dest_dir = root / name
        for remote, local in spec["files"]:
            download_file(f"{HF}/{spec['repo']}/resolve/main/{remote}", dest_dir / local)
        return dest_dir
    if name in VAD_MODELS:
        spec = VAD_MODELS[name]
        dest_dir = root / name
        for remote, local in spec["files"]:
            download_file(spec["url"], dest_dir / local)
        return dest_dir
    raise KeyError(f"unknown model {name}")


def resolve_paths(name: str) -> dict:
    """Return encoder/decoder/tokens paths for a whisper model directory."""
    d = MODELS_ROOT / name
    files = sorted(p.name for p in d.glob("*.onnx")) if d.exists() else []
    encoder = next((d / f for f in files if "encoder" in f and ".int8." in f), None)
    if encoder is None:
        encoder = next((d / f for f in files if "encoder" in f), None)
    decoder = next((d / f for f in files if "decoder" in f and ".int8." in f), None)
    if decoder is None:
        decoder = next((d / f for f in files if "decoder" in f), None)
    tokens = next((p for p in d.glob("*-tokens.txt")), None)
    if tokens is None:
        tokens = next((p for p in d.glob("*tokens*.txt")), None)
    return {
        "encoder": str(encoder) if encoder else "",
        "decoder": str(decoder) if decoder else "",
        "tokens": str(tokens) if tokens else "",
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", default=None, help="model names (default: all small ones)")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    if args.list:
        for name, spec in {**WHISPER_MODELS, **VAD_MODELS}.items():
            print(f"{name:26s} {spec['bytes'] / 1e6:7.0f} MB  {spec.get('label', '')}")
        return 0

    names = args.models or [
        "silero-vad",
        "whisper-tiny-int8",
        "whisper-base-int8",
        "whisper-base-fp32",
        "whisper-base-en-int8",
        "whisper-small-int8",
    ]
    for name in names:
        print(f"[models] {name}", flush=True)
        fetch(name)
    print("[models] done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
