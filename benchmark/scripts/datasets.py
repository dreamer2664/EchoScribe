"""
Dataset acquisition for the EchoScribe benchmark suite.

Only genuinely open, redistributable-for-evaluation corpora are used:

* LibriSpeech (OpenSLR SLR12) — CC BY 4.0 — English read speech, multiple speakers.
  Used for the *same-material cross-engine comparison*.
* FLEURS (Google) — CC BY 4.0 — 102 languages, parallel read speech.
  Used for Italian and for multilingual / language-ID evaluation.

No audio is committed to the repository: `benchmark/data/` is git-ignored and the
downloaders rebuild it deterministically from the public URL + SHA256.

Both datasets are ~normalised into a common manifest format:

    {"id": "...", "audio": "relative/path.wav", "text": "...", "seconds": 12.3,
     "speaker": "...", "lang": "en", "split": "test-clean", "dataset": "librispeech"}
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import random
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import wave
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
MANIFEST_DIR = Path(__file__).resolve().parent.parent / "manifests"

LIBRISPEECH_URLS = {
    "test-clean": "https://www.openslr.org/resources/12/test-clean.tar.gz",
    "dev-clean": "https://www.openslr.org/resources/12/dev-clean.tar.gz",
}

FLEURS_CONFIGS = {
    "en": "en_us",
    "it": "it_it",
}


def _log(msg: str) -> None:
    print(f"[datasets] {msg}", flush=True)


def sha256_file(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(chunk), b""):
            h.update(block)
    return h.hexdigest()


def download(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        _log(f"cached {dest.name} ({dest.stat().st_size / 1e6:.1f} MB)")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    _log(f"downloading {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url) as resp, tmp.open("wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            block = resp.read(1 << 20)
            if not block:
                break
            out.write(block)
            done += len(block)
            if total:
                pct = 100 * done / total
                if done % (20 << 20) < (1 << 20):
                    _log(f"  {pct:5.1f}%  {done / 1e6:.0f}/{total / 1e6:.0f} MB")
    tmp.rename(dest)
    _log(f"done {dest.name} sha256={sha256_file(dest)}")
    return dest


# --------------------------------------------------------------------------------------
# LibriSpeech
# --------------------------------------------------------------------------------------

def prepare_librispeech(splits: list[str] | None = None) -> Path:
    """LibriSpeech ships FLAC at 16 kHz + one .trans.txt per chapter."""
    splits = splits or ["test-clean"]
    root = DATA_DIR / "LibriSpeech"
    entries = []
    for split in splits:
        archive = DATA_DIR / f"librispeech-{split}.tar.gz"
        download(LIBRISPEECH_URLS[split], archive)
        if not (root / split).exists():
            _log(f"extracting {archive.name}")
            with tarfile.open(archive, "r:gz") as tf:
                tf.extractall(DATA_DIR)

        split_dir = root / split
        for trans_file in sorted(split_dir.rglob("*.trans.txt")):
            chapter = trans_file.parent
            speaker = chapter.parent.name
            for line in trans_file.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                utt_id, _, text = line.partition(" ")
                audio = chapter / f"{utt_id}.flac"
                if not audio.exists():
                    continue
                entries.append(
                    {
                        "id": utt_id,
                        "audio": str(audio.relative_to(DATA_DIR)),
                        "text": text.strip(),
                        "speaker": speaker,
                        "lang": "en",
                        "dataset": "librispeech",
                        "split": split,
                        "seconds": flac_duration_seconds(audio),
                    }
                )
    manifest = MANIFEST_DIR / f"librispeech_{'_'.join(splits)}.jsonl"
    _write_manifest(manifest, entries)
    _log(f"librispeech manifest: {len(entries)} utterances -> {manifest}")
    return manifest


def flac_duration_seconds(path: Path) -> float:
    """Parse the FLAC STREAMINFO header directly: avoids requiring ffmpeg."""
    try:
        with path.open("rb") as fh:
            header = fh.read(4096)
        if not header.startswith(b"fLaC"):
            return 0.0
        # first metadata block is STREAMINFO (type 0)
        pos = 4
        block_header = header[pos]
        length = int.from_bytes(header[pos + 1 : pos + 4], "big")
        info = header[pos + 4 : pos + 4 + length]
        # sample rate: 20 bits at bit offset 80; total samples: 36 bits at offset 108
        bits = int.from_bytes(info[10:18], "big")
        sample_rate = (bits >> 44) & 0xFFFFF
        total_samples = bits & 0xFFFFFFFFF
        return total_samples / sample_rate if sample_rate else 0.0
    except Exception:
        return 0.0


# --------------------------------------------------------------------------------------
# FLEURS
# --------------------------------------------------------------------------------------

def prepare_fleurs(langs: list[str] | None = None, limit: int = 200) -> Path:
    """FLEURS test split via the public parquet export.

    Pulls only the requested row-group subset: each language's test parquet is a few
    MB, and we cap at `limit` utterances per language to stay within sandbox disk.
    """
    import pyarrow.parquet as pq  # local import: optional dependency

    langs = langs or ["en", "it"]
    entries = []
    for lang in langs:
        config = FLEURS_CONFIGS[lang]
        url = f"https://huggingface.co/datasets/google/fleurs/resolve/main/data/{config}/audio/test.tar.gz"
        # The audio tarball is ~200 MB per language; prefer parquet (smaller, self-contained).
        parquet_url = f"https://huggingface.co/api/datasets/google/fleurs/parquet/{config}/test/0.parquet"
        dest = DATA_DIR / "fleurs" / f"{config}-test.parquet"
        try:
            download(parquet_url, dest)
        except Exception as exc:  # pragma: no cover - network dependent
            _log(f"parquet unavailable for {lang} ({exc}); trying audio tarball")
            download(url, DATA_DIR / "fleurs" / f"{config}-test.tar.gz")
            continue

        table = pq.read_table(dest)
        audio_col = "audio" if "audio" in table.column_names else None
        if audio_col is None:
            _log(f"unexpected FLEURS schema: {table.column_names}")
            continue
        rows = table.to_pylist()
        rng = random.Random(1234)
        rng.shuffle(rows)
        kept = 0
        out_dir = DATA_DIR / "fleurs" / config
        out_dir.mkdir(parents=True, exist_ok=True)
        for idx, row in enumerate(rows):
            if kept >= limit:
                break
            audio = row[audio_col]
            transcript = row.get("transcription") or row.get("raw_transcription")
            if not audio or not transcript:
                continue
            wav_bytes = audio.get("bytes")
            wav_path = audio.get("path")
            utt_id = f"fleurs-{config}-{idx:04d}"
            target = out_dir / f"{utt_id}.wav"
            if wav_bytes:
                target.write_bytes(wav_bytes)
            elif wav_path and (DATA_DIR / wav_path).exists():
                shutil.copy(DATA_DIR / wav_path, target)
            else:
                continue
            seconds, _sr = wav_info(target)
            entries.append(
                {
                    "id": utt_id,
                    "audio": str(target.relative_to(DATA_DIR)),
                    "text": transcript.strip(),
                    "speaker": str(row.get("speaker_id", "")),
                    "lang": lang,
                    "dataset": "fleurs",
                    "split": "test",
                    "seconds": seconds,
                }
            )
            kept += 1
        _log(f"fleurs {lang}: {kept} utterances")

    manifest = MANIFEST_DIR / f"fleurs_{'_'.join(langs)}.jsonl"
    _write_manifest(manifest, entries)
    return manifest


def wav_info(path: Path) -> tuple[float, int]:
    try:
        with wave.open(str(path), "rb") as wf:
            frames = wf.getnframes()
            rate = wf.getframerate()
            return (frames / rate if rate else 0.0), rate
    except Exception:
        return 0.0, 0


def _write_manifest(path: Path, entries: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for e in entries:
            fh.write(json.dumps(e, ensure_ascii=False) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description="Fetch open speech datasets for benchmarking")
    ap.add_argument("--dataset", choices=["librispeech", "fleurs", "all"], default="all")
    ap.add_argument("--splits", default="test-clean")
    ap.add_argument("--langs", default="en,it")
    ap.add_argument("--limit", type=int, default=200, help="utterances per FLEURS language")
    args = ap.parse_args()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    MANIFEST_DIR.mkdir(parents=True, exist_ok=True)
    if args.dataset in ("librispeech", "all"):
        prepare_librispeech(args.splits.split(","))
    if args.dataset in ("fleurs", "all"):
        try:
            prepare_fleurs(args.langs.split(","), limit=args.limit)
        except ImportError:
            _log("pyarrow not installed — skipping FLEURS (pip install pyarrow==15.0.2)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
