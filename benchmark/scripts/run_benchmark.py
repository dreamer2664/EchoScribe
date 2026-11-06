"""
Benchmark orchestrator: transcribe a manifest with N models, score against ground
truth, and write machine-readable results.

Usage
-----
    python3 benchmark/scripts/run_benchmark.py \
        --manifest benchmark/manifests/librispeech_test-clean.jsonl \
        --models whisper-tiny-int8 whisper-base-int8 whisper-small-int8 \
        --language en --mode final --limit 200

Outputs (in benchmark/results/):
    <run_id>.json   full detail, including per-utterance hypotheses
    <run_id>.csv    one row per (model, language, mode) for quick diffing
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import metrics  # noqa: E402
import runner  # noqa: E402
import download_models  # noqa: E402

RESULTS_DIR = HERE.parent / "results"


def load_manifest(path: Path, limit: int | None, stride: int = 1) -> list[dict]:
    entries = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                entries.append(json.loads(line))
    if stride > 1:
        entries = entries[::stride]
    if limit:
        entries = entries[:limit]
    return entries


def run_one(
    *,
    model_name: str,
    entries: list[dict],
    language: str,
    mode: str,
    num_threads: int,
    vad_model: Path,
    data_root: Path,
    stream_chunk: float,
    stream_overlap: float,
    verbose: bool,
) -> dict:
    paths = download_models.resolve_paths(model_name)
    spec = runner.WhisperSpec(
        name=model_name,
        encoder=paths["encoder"],
        decoder=paths["decoder"],
        tokens=paths["tokens"],
    )
    if not spec.available():
        raise FileNotFoundError(f"model files missing for {model_name}: {paths}")

    t_start = time.perf_counter()
    recognizer = runner.build_recognizer(spec, language=language, num_threads=num_threads)
    load_seconds = time.perf_counter() - t_start

    segmenter = None
    if mode == "final":
        segmenter = runner.VadSegmenter(str(vad_model), num_threads=num_threads)

    scores: list[metrics.UtteranceScore] = []
    integrity: list[dict] = []
    hypotheses: list[dict] = []

    for idx, entry in enumerate(entries):
        audio_path = data_root / entry["audio"]
        samples = runner.load_audio(audio_path)
        audio_seconds = len(samples) / runner.SAMPLE_RATE

        t0 = time.perf_counter()
        if mode == "final":
            text, decode_seconds, _seg = runner.recognize_vad(recognizer, segmenter, samples)
        else:
            text, decode_seconds = runner.recognize_chunked_stream(
                recognizer, samples, chunk_seconds=stream_chunk, overlap_seconds=stream_overlap
            )
        wall = time.perf_counter() - t0

        score = metrics.score_utterance(
            entry["id"],
            entry["text"],
            text,
            audio_seconds=audio_seconds,
            processing_seconds=wall,
            metadata={"speaker": entry.get("speaker"), "lang": entry.get("lang")},
        )
        scores.append(score)
        integrity.append(metrics.check_text_integrity(entry["id"], text).as_dict())
        hypotheses.append(
            {
                "id": entry["id"],
                "reference": entry["text"],
                "hypothesis": text,
                "wer": round(score.wer, 4),
                "cer": round(score.cer, 4),
                "audio_seconds": round(audio_seconds, 2),
                "decode_seconds": round(decode_seconds, 3),
            }
        )
        if verbose and (idx % 10 == 0 or idx == len(entries) - 1):
            print(
                f"      [{idx + 1}/{len(entries)}] {entry['id']} "
                f"wer={score.wer * 100:5.2f}% rtf={score.rtf:.3f}",
                flush=True,
            )

    corpus = metrics.aggregate(scores)

    # Per-speaker breakdown lets us report accent/speaker variance honestly.
    by_speaker: dict[str, list[metrics.UtteranceScore]] = {}
    for s in scores:
        by_speaker.setdefault(str(s.metadata.get("speaker") or "unknown"), []).append(s)
    speaker_wer = {k: round(metrics.aggregate(v, with_ci=False).wer * 100, 3) for k, v in by_speaker.items()}

    duplicate_ratios = [i["duplicate_ngram_ratio"] for i in integrity]
    empty = sum(1 for i in integrity if i["empty_hypothesis"])
    max_run = max((i["max_repeated_run"] for i in integrity), default=0)

    return {
        "model": model_name,
        "language": language,
        "mode": mode,
        "utterances": len(scores),
        "corpus": corpus.as_dict(),
        "model_load_seconds": round(load_seconds, 3),
        "peak_rss_mb": round(runner.peak_rss_mb(), 1),
        "num_threads": num_threads,
        "speaker_wer_percent": speaker_wer,
        "speaker_wer_spread": round(
            (statistics.pstdev(list(speaker_wer.values())) if len(speaker_wer) > 1 else 0.0), 3
        ),
        "integrity": {
            "empty_hypotheses": empty,
            "mean_duplicate_5gram_ratio": round(sum(duplicate_ratios) / len(duplicate_ratios), 4)
            if duplicate_ratios
            else 0.0,
            "max_repeated_token_run": max_run,
        },
        "hypotheses": hypotheses,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--models", nargs="+", required=True)
    ap.add_argument("--language", default="en")
    ap.add_argument("--mode", choices=["final", "stream", "both"], default="final")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--stride", type=int, default=1, help="take every Nth utterance (spreads speakers)")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--stream-chunk", type=float, default=8.0)
    ap.add_argument("--stream-overlap", type=float, default=1.0)
    ap.add_argument("--run-id", default=None)
    ap.add_argument("--data-root", default=str(HERE.parent / "data"))
    ap.add_argument("--vad", default=str(HERE.parent / "models" / "silero-vad" / "silero_vad.onnx"))
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    manifest = Path(args.manifest)
    if not manifest.exists():
        print(f"manifest not found: {manifest}  (run datasets.py first)", file=sys.stderr)
        return 2
    entries = load_manifest(manifest, args.limit, args.stride)
    if not entries:
        print("empty manifest", file=sys.stderr)
        return 2

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    run_id = args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    modes = ["final", "stream"] if args.mode == "both" else [args.mode]

    runs = []
    for model_name in args.models:
        for mode in modes:
            print(f"[bench] {model_name} | lang={args.language} | mode={mode} | n={len(entries)}", flush=True)
            try:
                result = run_one(
                    model_name=model_name,
                    entries=entries,
                    language=args.language,
                    mode=mode,
                    num_threads=args.threads,
                    vad_model=Path(args.vad),
                    data_root=Path(args.data_root),
                    stream_chunk=args.stream_chunk,
                    stream_overlap=args.stream_overlap,
                    verbose=args.verbose,
                )
            except Exception as exc:  # keep going: one broken model must not void the suite
                print(f"  !! failed: {exc}", file=sys.stderr, flush=True)
                runs.append({"model": model_name, "language": args.language, "mode": mode, "error": str(exc)})
                continue
            c = result["corpus"]
            print(
                f"  WER {c['wer_percent']:.2f}%  CER {c['cer_percent']:.2f}%  "
                f"RTF {c['rtf']:.3f}  peakRSS {result['peak_rss_mb']:.0f} MB",
                flush=True,
            )
            runs.append(result)

    payload = {
        "run_id": run_id,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "engine": {"name": "sherpa-onnx", "version": _sherpa_version()},
        "manifest": manifest.name,
        "manifest_path": str(manifest),
        "utterance_count": len(entries),
        "language": args.language,
        "host": _host_info(),
        "runs": runs,
    }
    json_path = RESULTS_DIR / f"{run_id}.json"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    csv_path = RESULTS_DIR / f"{run_id}.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(
            [
                "model", "language", "mode", "utterances", "wer_percent", "wer_ci95_low",
                "wer_ci95_high", "cer_percent", "rtf", "x_realtime", "audio_seconds",
                "processing_seconds", "peak_rss_mb", "substitutions", "deletions", "insertions",
            ]
        )
        for r in runs:
            if "error" in r:
                continue
            c = r["corpus"]
            w.writerow(
                [
                    r["model"], r["language"], r["mode"], r["utterances"], c["wer_percent"],
                    c["wer_ci95_percent"][0], c["wer_ci95_percent"][1], c["cer_percent"],
                    c["rtf"], c["x_realtime"], c["audio_seconds"], c["processing_seconds"],
                    r["peak_rss_mb"], c["substitutions"], c["deletions"], c["insertions"],
                ]
            )

    print(f"\n[bench] wrote {json_path.name} and {csv_path.name}")
    return 0


def _sherpa_version() -> str:
    try:
        import sherpa_onnx

        return getattr(sherpa_onnx, "__version__", "unknown")
    except Exception:
        return "unknown"


def _host_info() -> dict:
    import os
    import platform

    return {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "python": platform.python_version(),
        "numpy": np.__version__,
    }


if __name__ == "__main__":
    sys.exit(main())
