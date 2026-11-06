"""
Word Error Rate / Character Error Rate with bootstrap confidence intervals.

Deliberately dependency-free (pure stdlib) so the numbers can be reproduced by
anyone with a Python interpreter, on any OS, without installing a scientific stack.

Definitions (standard ASR evaluation, cf. NIST sclite):

    WER = (S + D + I) / N        where N = number of reference words
    CER = edit_distance(ref_chars, hyp_chars) / len(ref_chars)

Aggregation is *corpus level*: errors and reference lengths are summed over all
utterances before dividing. This is the standard for comparing ASR systems and
avoids tiny utterances dominating the average.
"""

from __future__ import annotations

import random
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Iterable, Sequence


# --------------------------------------------------------------------------------------
# Normalisation
# --------------------------------------------------------------------------------------

# Symbols/formatting that should never count as recognition errors: whisper-family
# models emit punctuation from a distribution the reference transcripts (LibriSpeech,
# FLEURS) do not contain, so scoring raw text would penalise the *better* system.
_PUNCT_RE = re.compile(r"[^\w\s']", re.UNICODE)
_WS_RE = re.compile(r"\s+")


def normalize_text(text: str, *, lowercase: bool = True, strip_punct: bool = True) -> str:
    """Canonical text form used for scoring.

    Order matters: NFKC first (so full-width/half-width variants collapse), then
    punctuation removal, then whitespace squashing.
    """
    if text is None:
        return ""
    text = unicodedata.normalize("NFKC", text)
    if lowercase:
        text = text.lower()
    if strip_punct:
        # Keep intra-word apostrophes (Italian "l'italiano", English "don't").
        text = _PUNCT_RE.sub(" ", text)
    text = _WS_RE.sub(" ", text).strip()
    return text


def tokenize_words(text: str) -> list[str]:
    return normalize_text(text).split() if text else []


def tokenize_chars(text: str) -> list[str]:
    return list(normalize_text(text).replace(" ", ""))


# --------------------------------------------------------------------------------------
# Edit distance (single unified implementation for words and characters)
# --------------------------------------------------------------------------------------

def edit_distance(ref: Sequence[str], hyp: Sequence[str]) -> tuple[int, int, int, int]:
    """Levenshtein alignment with backtrace.

    Returns (distance, substitutions, deletions, insertions).

    Uses two rolling rows for cost (O(min(n,m)) memory) plus a compact backtrace of
    direction codes over the full matrix — matrices here are tiny (utterance level)
    so O(n*m) bytes is affordable and keeps the code simple and auditable.
    """
    n, m = len(ref), len(hyp)
    if n == 0:
        return m, 0, 0, m
    if m == 0:
        return n, 0, n, 0

    # direction codes: 0 = match/diag, 1 = substitution(diag), 2 = deletion(up), 3 = insertion(left)
    dirs = bytearray(n * m)
    prev = list(range(m + 1))
    cur = [0] * (m + 1)

    for i in range(1, n + 1):
        cur[0] = i
        ri = ref[i - 1]
        for j in range(1, m + 1):
            cost_diag = prev[j - 1] + (0 if ri == hyp[j - 1] else 1)
            cost_up = prev[j] + 1      # deletion from hypothesis (ref word missing)
            cost_left = cur[j - 1] + 1  # insertion (extra hypothesis word)
            best = cost_diag
            code = 0 if ri == hyp[j - 1] else 1
            if cost_up < best:
                best, code = cost_up, 2
            if cost_left < best:
                best, code = cost_left, 3
            cur[j] = best
            dirs[(i - 1) * m + (j - 1)] = code
        prev, cur = cur, prev

    # Backtrace (only counts; the alignment itself is not needed downstream)
    subs = dels = ins = 0
    i, j = n, m
    while i > 0 and j > 0:
        code = dirs[(i - 1) * m + (j - 1)]
        if code == 0:
            i, j = i - 1, j - 1
        elif code == 1:
            subs += 1
            i, j = i - 1, j - 1
        elif code == 2:
            dels += 1
            i -= 1
        else:
            ins += 1
            j -= 1
    del_count_total = dels + i
    ins_count_total = ins + j
    return subs + del_count_total + ins_count_total, subs, del_count_total, ins_count_total


# --------------------------------------------------------------------------------------
# Result containers
# --------------------------------------------------------------------------------------

@dataclass
class UtteranceScore:
    utterance_id: str
    reference: str
    hypothesis: str
    ref_words: int
    hyp_words: int
    word_distance: int = 0
    substitutions: int = 0
    deletions: int = 0
    insertions: int = 0
    ref_chars: int = 0
    char_distance: int = 0
    audio_seconds: float = 0.0
    processing_seconds: float = 0.0
    metadata: dict = field(default_factory=dict)

    @property
    def wer(self) -> float:
        return self.word_distance / self.ref_words if self.ref_words else 0.0

    @property
    def cer(self) -> float:
        return self.char_distance / self.ref_chars if self.ref_chars else 0.0

    @property
    def rtf(self) -> float:
        return self.processing_seconds / self.audio_seconds if self.audio_seconds else 0.0


@dataclass
class CorpusScore:
    utterances: int
    ref_words: int
    word_distance: int
    substitutions: int
    deletions: int
    insertions: int
    ref_chars: int
    char_distance: int
    audio_seconds: float
    processing_seconds: float
    wer: float
    cer: float
    rtf: float
    wer_ci95: tuple[float, float]

    def as_dict(self) -> dict:
        return {
            "utterances": self.utterances,
            "ref_words": self.ref_words,
            "word_errors": self.word_distance,
            "substitutions": self.substitutions,
            "deletions": self.deletions,
            "insertions": self.insertions,
            "wer": round(self.wer, 5),
            "wer_percent": round(self.wer * 100, 3),
            "wer_ci95_percent": [round(self.wer_ci95[0] * 100, 3), round(self.wer_ci95[1] * 100, 3)],
            "cer": round(self.cer, 5),
            "cer_percent": round(self.cer * 100, 3),
            "audio_seconds": round(self.audio_seconds, 2),
            "processing_seconds": round(self.processing_seconds, 3),
            "rtf": round(self.rtf, 4),
            "x_realtime": round(1 / self.rtf, 2) if self.rtf else None,
        }


# --------------------------------------------------------------------------------------
# Scoring
# --------------------------------------------------------------------------------------

def score_utterance(
    utterance_id: str,
    reference: str,
    hypothesis: str,
    *,
    audio_seconds: float = 0.0,
    processing_seconds: float = 0.0,
    metadata: dict | None = None,
) -> UtteranceScore:
    ref_w = tokenize_words(reference)
    hyp_w = tokenize_words(hypothesis)
    dist, subs, dels, ins = edit_distance(ref_w, hyp_w)

    ref_c = tokenize_chars(reference)
    hyp_c = tokenize_chars(hypothesis)
    char_dist, _, _, _ = edit_distance(ref_c, hyp_c)

    return UtteranceScore(
        utterance_id=utterance_id,
        reference=reference,
        hypothesis=hypothesis,
        ref_words=len(ref_w),
        hyp_words=len(hyp_w),
        word_distance=dist,
        substitutions=subs,
        deletions=dels,
        insertions=ins,
        ref_chars=len(ref_c),
        char_distance=char_dist,
        audio_seconds=audio_seconds,
        processing_seconds=processing_seconds,
        metadata=metadata or {},
    )


def bootstrap_ci(
    scores: Sequence[UtteranceScore],
    *,
    iterations: int = 1000,
    seed: int = 20260101,
    alpha: float = 0.05,
) -> tuple[float, float]:
    """Percentile bootstrap CI over utterances (resampling utterances, not words).

    Reported so that readers can tell whether a WER gap between two models is real
    or within sampling noise — a difference smaller than the overlapping CI is not
    evidence of superiority.
    """
    usable = [s for s in scores if s.ref_words > 0]
    if len(usable) < 2:
        return (0.0, 0.0)
    rng = random.Random(seed)
    values: list[float] = []
    n = len(usable)
    for _ in range(iterations):
        err = 0
        words = 0
        for _ in range(n):
            s = usable[rng.randrange(n)]
            err += s.word_distance
            words += s.ref_words
        values.append(err / words if words else 0.0)
    values.sort()
    lo = values[int((alpha / 2) * len(values))]
    hi = values[int((1 - alpha / 2) * len(values)) - 1]
    return (lo, hi)


def aggregate(scores: Iterable[UtteranceScore], *, with_ci: bool = True) -> CorpusScore:
    scores = list(scores)
    ref_words = sum(s.ref_words for s in scores)
    word_distance = sum(s.word_distance for s in scores)
    ref_chars = sum(s.ref_chars for s in scores)
    char_distance = sum(s.char_distance for s in scores)
    audio = sum(s.audio_seconds for s in scores)
    processing = sum(s.processing_seconds for s in scores)

    wer = word_distance / ref_words if ref_words else 0.0
    cer = char_distance / ref_chars if ref_chars else 0.0
    rtf = processing / audio if audio else 0.0
    ci = bootstrap_ci(scores) if with_ci else (0.0, 0.0)

    return CorpusScore(
        utterances=len(scores),
        ref_words=ref_words,
        word_distance=word_distance,
        substitutions=sum(s.substitutions for s in scores),
        deletions=sum(s.deletions for s in scores),
        insertions=sum(s.insertions for s in scores),
        ref_chars=ref_chars,
        char_distance=char_distance,
        audio_seconds=audio,
        processing_seconds=processing,
        wer=wer,
        cer=cer,
        rtf=rtf,
        wer_ci95=ci,
    )


# --------------------------------------------------------------------------------------
# Hallucination / omission diagnostics
# --------------------------------------------------------------------------------------

@dataclass
class TextIntegrityReport:
    """Guards against the two silent failure modes the project must not have:
    silently dropped speech (omissions) and repeated text (duplication)."""

    utterance_id: str
    duplicate_ngram_ratio: float
    max_repeated_run: int
    empty_hypothesis: bool

    def as_dict(self) -> dict:
        return {
            "utterance_id": self.utterance_id,
            "duplicate_ngram_ratio": round(self.duplicate_ngram_ratio, 4),
            "max_repeated_run": self.max_repeated_run,
            "empty_hypothesis": self.empty_hypothesis,
        }


def check_text_integrity(utterance_id: str, hypothesis: str, ngram: int = 5) -> TextIntegrityReport:
    words = tokenize_words(hypothesis)
    if not words:
        return TextIntegrityReport(utterance_id, 0.0, 0, True)

    seen: set[tuple[str, ...]] = set()
    duplicates = 0
    grams = [tuple(words[i : i + ngram]) for i in range(max(0, len(words) - ngram + 1))]
    for g in grams:
        if g in seen:
            duplicates += 1
        seen.add(g)
    ratio = duplicates / len(grams) if grams else 0.0

    # Longest run of the same word repeated (classic whisper loop artefact).
    best = cur = 1
    for a, b in zip(words, words[1:]):
        cur = cur + 1 if a == b else 1
        best = max(best, cur)

    return TextIntegrityReport(utterance_id, ratio, best, False)
