"""
EchoScribe ASR runner — drives the *same* sherpa-onnx engine the Android app links
against (identical C++ core, identical ONNX models), so benchmark numbers describe
what the shipped application actually does, not a different desktop pipeline.

Two modes are measured, mirroring the app's architecture:

* ``stream``    — fixed-length chunk-by-chunk decoding with a small left-context
                  overlap, i.e. what "live transcription" shows on screen.
* ``final``     — Silero-VAD-segmented decoding of the whole recording, i.e. what
                  the app writes to the saved transcript.

Running both on the same audio is how we quantify the "final pass improves accuracy"
claim with a real measurement instead of an assertion.
"""

from __future__ import annotations

import json
import math
import os
import resource
import struct
import sys
import time
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import sherpa_onnx

SAMPLE_RATE = 16000


# --------------------------------------------------------------------------------------
# Audio I/O
# --------------------------------------------------------------------------------------

def read_wav(path: str | Path) -> tuple[np.ndarray, int]:
    """Read a mono 16-bit PCM WAV into float32 [-1, 1]."""
    with wave.open(str(path), "rb") as wf:
        channels = wf.getnchannels()
        width = wf.getsampwidth()
        rate = wf.getframerate()
        frames = wf.readframes(wf.getnframes())
    if width != 2:
        raise ValueError(f"expected 16-bit PCM, got {width * 8}-bit: {path}")
    data = np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return data, rate


def resample_linear(samples: np.ndarray, src_rate: int, dst_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Linear resampler. Adequate for evaluation audio (FLEURS is 16 kHz already;
    LibriSpeech is 16 kHz); used only as a safety net for odd inputs."""
    if src_rate == dst_rate or samples.size == 0:
        return samples
    duration = samples.size / src_rate
    n_out = int(round(duration * dst_rate))
    x_old = np.linspace(0.0, duration, num=samples.size, endpoint=False)
    x_new = np.linspace(0.0, duration, num=n_out, endpoint=False)
    return np.interp(x_new, x_old, samples).astype(np.float32)


def flac_to_float32(path: Path) -> np.ndarray:
    """LibriSpeech is distributed as 16 kHz FLAC.

    `sherpa-onnx` does not decode FLAC, and ffmpeg is not guaranteed to exist, so we
    decode with the pure-python `soundfile`(libsndfile) if present, otherwise fall
    back to the `flac` CLI. Both preserve the original 16 kHz sample rate.
    """
    try:
        import soundfile as sf  # type: ignore

        data, rate = sf.read(str(path), dtype="float32", always_2d=False)
        if data.ndim > 1:
            data = data.mean(axis=1)
        return resample_linear(data.astype(np.float32), rate)
    except Exception:
        pass

    import subprocess
    import tempfile

    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        subprocess.run(
            ["flac", "-d", "-f", "--totally-silent", "-o", tmp_path, str(path)],
            check=True,
            capture_output=True,
        )
        data, rate = read_wav(tmp_path)
        return resample_linear(data, rate)
    finally:
        os.unlink(tmp_path)


def load_audio(path: Path) -> np.ndarray:
    suffix = path.suffix.lower()
    if suffix == ".wav":
        data, rate = read_wav(path)
        return resample_linear(data, rate)
    if suffix == ".flac":
        return flac_to_float32(path)
    raise ValueError(f"unsupported audio format: {path}")


# --------------------------------------------------------------------------------------
# Recognisers
# --------------------------------------------------------------------------------------

@dataclass
class WhisperSpec:
    name: str
    encoder: str
    decoder: str
    tokens: str
    quantized: bool = False

    def available(self) -> bool:
        return all(Path(p).exists() for p in (self.encoder, self.decoder, self.tokens))


def build_recognizer(spec: WhisperSpec, language: str, num_threads: int) -> sherpa_onnx.OfflineRecognizer:
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=spec.encoder,
        decoder=spec.decoder,
        tokens=spec.tokens,
        language=language,
        task="transcribe",
        num_threads=num_threads,
        decoding_method="greedy_search",
        debug=False,
        # Whisper needs 30 s of padded context per window; sherpa pads automatically
        # when tail_paddings == -1.
        tail_paddings=-1,
    )


def decode_full_window(
    recognizer: sherpa_onnx.OfflineRecognizer, samples: np.ndarray
) -> tuple[str, float]:
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples)
    t0 = time.perf_counter()
    recognizer.decode_stream(stream)
    elapsed = time.perf_counter() - t0
    return stream.result.text.strip(), elapsed


class VadSegmenter:
    """Silero VAD configured to segment long audio at natural speech boundaries."""

    def __init__(self, model_path: str, num_threads: int = 1, max_speech: float = 20.0):
        cfg = sherpa_onnx.VadModelConfig()
        cfg.silero_vad.model = model_path
        cfg.silero_vad.min_silence_duration = 0.45
        cfg.silero_vad.min_speech_duration = 0.20
        # Whisper degrades when a single window exceeds ~30 s; hard-cap segments.
        cfg.silero_vad.max_speech_duration = max_speech
        cfg.sample_rate = SAMPLE_RATE
        cfg.num_threads = num_threads
        self.vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=600)

    def segments(self, samples: np.ndarray, window: int = 512):
        """Yield speech segments from a long waveform in bounded memory."""
        self.vad.reset()
        for start in range(0, len(samples), window):
            self.vad.accept_waveform(samples[start : start + window])
            while not self.vad.empty():
                seg = self.vad.front
                yield seg.start / SAMPLE_RATE, np.array(seg.samples, dtype=np.float32)
                self.vad.pop()
        self.vad.flush()
        while not self.vad.empty():
            seg = self.vad.front
            yield seg.start / SAMPLE_RATE, np.array(seg.samples, dtype=np.float32)
            self.vad.pop()


def recognize_vad(
    recognizer: sherpa_onnx.OfflineRecognizer,
    segmenter: VadSegmenter,
    samples: np.ndarray,
    *,
    emit_streaming: bool = False,
) -> tuple[str, float, list[dict]]:
    """Decode audio by VAD segment. Returns joined text, decode seconds, segment info."""
    parts: list[str] = []
    seg_meta: list[dict] = []
    decode_seconds = 0.0
    for start, seg in segmenter.segments(samples):
        text, elapsed = decode_full_window(recognizer, seg)
        decode_seconds += elapsed
        if text:
            parts.append(text)
        seg_meta.append({"start": round(start, 2), "duration": round(len(seg) / SAMPLE_RATE, 2), "text": text})
        if emit_streaming:
            print(f"    [{start:7.2f}s] {text}", flush=True)
    # Whisper already emits sentence punctuation; join with a single space and let the
    # formatter downstream handle paragraph breaks. Never concatenate without a space.
    return " ".join(parts).strip(), decode_seconds, seg_meta


def recognize_chunked_stream(
    recognizer: sherpa_onnx.OfflineRecognizer,
    samples: np.ndarray,
    *,
    chunk_seconds: float = 8.0,
    overlap_seconds: float = 1.0,
) -> tuple[str, float]:
    """Simulates live transcription: fixed windows with small overlap, then merge
    by dropping the duplicated boundary words (longest-suffix/prefix overlap)."""
    chunk = int(chunk_seconds * SAMPLE_RATE)
    hop = int((chunk_seconds - overlap_seconds) * SAMPLE_RATE)
    parts: list[str] = []
    decode_seconds = 0.0
    for start in range(0, max(1, len(samples)), hop):
        window = samples[start : start + chunk]
        if len(window) < SAMPLE_RATE // 2:  # ignore <0.5 s tails
            break
        text, elapsed = decode_full_window(recognizer, window)
        decode_seconds += elapsed
        if text:
            parts.append(text)
        if start + chunk >= len(samples):
            break
    return merge_overlapping(parts), decode_seconds


def merge_overlapping(parts: list[str], max_overlap_words: int = 12) -> str:
    """Join streaming chunks without duplicating or dropping boundary words.

    Each new chunk starts `overlap_seconds` before the previous chunk ended, so its
    first few words repeat the previous chunk's tail. We find the longest suffix of
    the accumulated text that equals a prefix of the new chunk and skip it.
    """
    if not parts:
        return ""
    merged_words: list[str] = parts[0].split()
    for part in parts[1:]:
        new_words = part.split()
        if not new_words:
            continue
        best = 0
        max_k = min(max_overlap_words, len(merged_words), len(new_words))
        for k in range(max_k, 0, -1):
            if [w.lower() for w in merged_words[-k:]] == [w.lower() for w in new_words[:k]]:
                best = k
                break
        merged_words.extend(new_words[best:])
    return " ".join(merged_words)


def peak_rss_mb() -> float:
    usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # Linux reports kilobytes, macOS bytes.
    return usage / 1024.0 if sys.platform != "darwin" else usage / (1024.0 * 1024.0)
