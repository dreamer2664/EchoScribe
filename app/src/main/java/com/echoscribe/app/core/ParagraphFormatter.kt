package com.echoscribe.app.core

import java.util.Locale

/**
 * Turns a flat stream of timestamped segments into readable paragraphs.
 *
 * Whisper already emits punctuation, but it returns one long line per 30-second
 * window. Reading hours of that is miserable, so we insert paragraph breaks using
 * speech-structure signals rather than a fixed character count:
 *
 *  - a long pause (>= [pauseBreakMs]) almost always means a new thought;
 *  - a sentence-final punctuation mark immediately before the break is a strong signal;
 *  - very long paragraphs are force-broken so the text stays scannable on a phone.
 *
 * Nothing here rewrites words: the formatter only inserts `\n\n`, capitalises the
 * first letter of a paragraph, and normalises spacing. The original model text is
 * preserved word-for-word, which is what "keep the original transcription separate
 * from optional AI cleanup" requires.
 */
object ParagraphFormatter {

    /** A pause at least this long starts a new paragraph. */
    const val pauseBreakMs: Long = 1_200

    /** A softer pause starts a new paragraph when a sentence just ended. */
    const val softPauseMs: Long = 700

    /** Hard cap so a breathless 4-minute monologue is still scannable. */
    const val maxParagraphChars: Int = 900

    /** Hard cap on sentences per paragraph. */
    const val maxParagraphSentences: Int = 6

    private val SENTENCE_END = Regex("[.!?…]['\"”’)]?$")
    private val MULTI_SPACE = Regex("[ \\t]+")
    private val MULTI_NEWLINE = Regex("\\n{3,}")

    data class Paragraph(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    /**
     * @param segments chronological segments with timestamps
     * @return paragraphs with their time ranges, suitable for rendering or export
     */
    fun toParagraphs(segments: List<Segment>): List<Paragraph> {
        val ordered = segments.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (ordered.isEmpty()) return emptyList()

        val paragraphs = mutableListOf<Paragraph>()
        val buffer = StringBuilder()
        var paraStart = ordered.first().startMs
        var paraEnd = ordered.first().startMs
        var sentences = 0
        var previous: Segment? = null

        fun flush(endMs: Long) {
            val text = tidy(buffer.toString())
            if (text.isNotEmpty()) {
                paragraphs += Paragraph(paraStart, endMs, capitaliseParagraph(text))
            }
            buffer.setLength(0)
            sentences = 0
        }

        for (segment in ordered) {
            val text = tidy(segment.text)
            if (text.isEmpty()) continue

            val gap = previous?.let { segment.startMs - it.endMs } ?: 0L
            val prevEndsSentence = previous?.text?.trim()?.let { SENTENCE_END.containsMatchIn(it) } ?: false

            val hardPause = gap >= pauseBreakMs
            val softPause = gap >= softPauseMs && (prevEndsSentence || sentences >= 2)
            val tooLong = buffer.length + text.length > maxParagraphChars
            val tooManySentences = sentences >= maxParagraphSentences

            if (buffer.isNotEmpty() && (hardPause || softPause || tooLong || tooManySentences)) {
                flush(previous?.endMs ?: paraEnd)
                paraStart = segment.startMs
            }

            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(text)
            paraEnd = segment.endMs
            sentences += countSentences(text)
            previous = segment
        }
        flush(paraEnd)
        return paragraphs
    }

    /** Convenience: the plain-text body used in the transcript editor and TXT export. */
    fun format(segments: List<Segment>): String =
        toParagraphs(segments).joinToString("\n\n") { it.text }

    private fun tidy(raw: String): String =
        raw.replace(MULTI_SPACE, " ")
            .replace(MULTI_NEWLINE, "\n")
            .trim()

    internal fun countSentences(text: String): Int =
        text.count { it == '.' || it == '!' || it == '?' || it == '…' }.coerceAtLeast(if (text.isBlank()) 0 else 1)

    /** Uppercases the first letter of the paragraph; never touches the rest of the word. */
    internal fun capitaliseParagraph(text: String): String {
        if (text.isEmpty()) return text
        val first = text.indexOfFirst { it.isLetter() }
        if (first < 0) return text
        val replacement = text[first].uppercaseChar()
        if (replacement == text[first]) return text
        return text.substring(0, first) + replacement + text.substring(first + 1)
    }

    /**
     * Numbers: Whisper writes digits for most numerals already. For Italian, values
     * below one hundred are routinely spelled out by the model, which reads badly in a
     * document. This helper is *off* by default (lossy) and offered as an explicit
     * export/cleanup option so the stored original is never silently altered.
     */
    fun normalizeNumbers(text: String, locale: Locale): String {
        val map = if (locale.language == "it") ITALIAN_UNITS else ENGLISH_UNITS
        var out = text
        for ((word, digit) in map) {
            out = Regex("(?<![\\p{L}])$word(?![\\p{L}])", RegexOption.IGNORE_CASE)
                .replace(out) { digit }
        }
        return out
    }

    private val ENGLISH_UNITS = linkedMapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12",
    )

    private val ITALIAN_UNITS = linkedMapOf(
        "zero" to "0", "uno" to "1", "due" to "2", "tre" to "3", "quattro" to "4",
        "cinque" to "5", "sei" to "6", "sette" to "7", "otto" to "8", "nove" to "9",
        "dieci" to "10", "undici" to "11", "dodici" to "12",
    )
}
