package com.echoscribe.app.core

import java.util.Locale

/**
 * A recognised piece of speech covering `[startMs, endMs)` of the recording.
 */
data class Segment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Which engine pass produced it: live (streaming) or final (VAD + full model). */
    val origin: Origin = Origin.FINAL,
    /** Mean model confidence 0..1 when the engine reports it; null when unavailable. */
    val confidence: Float? = null,
) {
    enum class Origin { LIVE, FINAL }

    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Merging rules that make multi-segment transcription safe.
 *
 * Two failure modes are unacceptable in a transcription app, and both are subtle:
 *
 *  1. **Duplicated text.** Overlapping windows (live mode decodes `chunk` with
 *     `overlap` of shared audio) produce the same words twice.
 *  2. **Silently dropped text.** Aggressive de-duplication eats words that genuinely
 *     repeat ("no no no"), and VAD gap-filling can discard quiet speech.
 *
 * The strategy here is deliberately conservative and *verifiable*: we only remove a
 * boundary duplication when a reasonably long suffix of the committed text matches a
 * prefix of the incoming text, word for word, case-insensitively. Everything else is
 * kept. There is a unit test pinning both behaviours.
 */
object SegmentMerger {

    /** Minimum words that must match before we treat a boundary as duplicated audio. */
    const val MIN_OVERLAP_WORDS: Int = 3

    /** Never remove more than this many words at a boundary (guards long repeats). */
    const val MAX_OVERLAP_WORDS: Int = 14

    /**
     * Appends [incoming] to [committed] text, removing audio-overlap duplication.
     *
     * @param committed text already produced for earlier audio
     * @param incoming text for the current window
     * @param minOverlapWords how many trailing/first words must agree (3 = safe default)
     */
    fun appendWithoutDuplication(
        committed: String,
        incoming: String,
        minOverlapWords: Int = MIN_OVERLAP_WORDS,
        maxOverlapWords: Int = MAX_OVERLAP_WORDS,
    ): String {
        val incomingTrimmed = incoming.trim()
        if (committed.isBlank()) return incomingTrimmed
        if (incomingTrimmed.isEmpty()) return committed.trim()

        val committedWords = committed.trim().split(WHITESPACE)
        val incomingWords = incomingTrimmed.split(WHITESPACE)

        val overlap = longestOverlap(committedWords, incomingWords, minOverlapWords, maxOverlapWords)
        if (overlap == 0) {
            return committed.trimEnd() + " " + incomingTrimmed
        }
        val remainder = incomingWords.drop(overlap)
        if (remainder.isEmpty()) return committed.trim()
        return committed.trimEnd() + " " + remainder.joinToString(" ")
    }

    /**
     * Longest k in [minOverlapWords, maxOverlapWords] such that the k trailing words of
     * [left] equal the k leading words of [right] (case- and punctuation-insensitive).
     */
    internal fun longestOverlap(
        left: List<String>,
        right: List<String>,
        minOverlapWords: Int = MIN_OVERLAP_WORDS,
        maxOverlapWords: Int = MAX_OVERLAP_WORDS,
    ): Int {
        val maxK = minOf(maxOverlapWords, left.size, right.size)
        if (maxK < minOverlapWords) return 0
        for (k in maxK downTo minOverlapWords) {
            var matches = true
            for (i in 0 until k) {
                if (compareWord(left[left.size - k + i]) != compareWord(right[i])) {
                    matches = false
                    break
                }
            }
            if (matches) return k
        }
        return 0
    }

    /**
     * Normalises a word for comparison only — never for display. Strips surrounding
     * punctuation and lowercases, so "Mondo." matches "mondo".
     */
    private fun compareWord(word: String): String =
        word.lowercase(Locale.ROOT).trim { it.isLetterOrDigit().not() }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Merges a full list of segments into a single timestamped transcript.
     *
     * Later segments from the same origin replace earlier ones covering the same time
     * range (a live segment being superseded by its final decode), then the remainder
     * is ordered and de-duplicated at boundaries.
     */
    fun mergeSegments(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

        val result = mutableListOf<Segment>()
        for (segment in sorted) {
            val last = result.lastOrNull()
            if (last == null) {
                result += segment
                continue
            }
            // A FINAL segment that starts where a LIVE segment started replaces it.
            if (segment.origin == Segment.Origin.FINAL && last.origin == Segment.Origin.LIVE) {
                val deduped = if (segment.startMs <= last.startMs) {
                    result.removeAt(result.lastIndex)
                    segment
                } else {
                    segment.copy(text = appendWithoutDuplication(last.text, segment.text))
                }
                result += deduped
                continue
            }
            val merged = appendWithoutDuplication(last.text, segment.text)
            result += segment.copy(
                // Keep the earlier start: the merged text begins at the earlier segment.
                startMs = last.startMs,
                text = merged,
            )
            result.removeAt(result.lastIndex - 1)
        }
        return result
    }

    /**
     * Joins segments into display text with timestamps preserved for the editor's
     * "show timestamps" mode. Insertion order is chronological; text within a segment
     * is never re-flowed, so the model's own punctuation survives.
     */
    fun joinText(segments: List<Segment>, separator: String = " "): String =
        segments.filter { it.text.isNotBlank() }
            .joinToString(separator) { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
}
