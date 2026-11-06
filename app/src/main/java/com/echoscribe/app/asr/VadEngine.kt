package com.echoscribe.app.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voice activity detection (Silero VAD v4, MIT licence).
 *
 * Why this is central rather than an optimisation:
 *
 * * Whisper **hallucinates on silence** — a quiet room produces phrases like
 *   "Thank you." or repeated words. Every decoder pass must therefore be gated on
 *   real speech.
 * * A 3-hour recording is mostly pauses; skipping them cuts decode time and battery
 *   roughly in half.
 * * Segments must be cut at pauses, never mid-sentence, or the model loses context
 *   and words at the boundary get dropped.
 */
class VadEngine(
    assets: AssetManager,
    vadModelFile: File,
    numThreads: Int = 1,
    minSilenceSeconds: Float = 0.45f,
    minSpeechSeconds: Float = 0.25f,
    maxSpeechSeconds: Float = 20f,
    threshold: Float = 0.5f,
) {
    private val closed = AtomicBoolean(false)

    private val vad: Vad = run {
        require(vadModelFile.exists()) { "VAD model missing: ${vadModelFile.absolutePath}" }
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModelFile.absolutePath,
                threshold = threshold,
                minSilenceDuration = minSilenceSeconds,
                minSpeechDuration = minSpeechSeconds,
                windowSize = WINDOW_SIZE,
                // Hard ceiling: Whisper's context is 30 s, and a segment longer than
                // maxSpeech keeps accuracy high while still bounding memory.
                maxSpeechDuration = maxSpeechSeconds,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = numThreads,
            provider = "cpu",
            debug = false,
        )
        Log.i("VadEngine", "Silero VAD ready (threshold=$threshold, maxSpeech=${maxSpeechSeconds}s)")
        Vad(assetManager = assets, config = config)
    }

    /** A detected speech span. */
    data class SpeechSpan(val startSample: Int, val samples: FloatArray) {
        val durationSeconds: Float get() = samples.size.toFloat() / SAMPLE_RATE
    }

    /**
     * Feeds [chunk] and returns every speech span that became complete.
     *
     * The caller streams audio in window-sized pieces (see [WINDOW_SIZE]), so peak
     * memory is bounded by `maxSpeechSeconds` regardless of recording length.
     */
    fun accept(chunk: FloatArray, chunkStartSample: Int): List<SpeechSpan> {
        if (closed.get()) return emptyList()
        val spans = mutableListOf<SpeechSpan>()
        vad.acceptWaveform(chunk)
        drain(spans, chunkStartSample)
        return spans
    }

    /** Flushes any speech still buffered when the recording stops. */
    fun flush(streamEndSample: Int = 0): List<SpeechSpan> {
        if (closed.get()) return emptyList()
        val spans = mutableListOf<SpeechSpan>()
        vad.flush()
        drain(spans, streamEndSample)
        return spans
    }

    fun isSpeechDetected(): Boolean = !closed.get() && vad.isSpeechDetected()

    private fun drain(into: MutableList<SpeechSpan>, chunkStartSample: Int) {
        while (!vad.empty()) {
            val segment = vad.front
            val startSample = (segment.start * SAMPLE_RATE / 1000).toInt()
            into += SpeechSpan(startSample, segment.samples)
            vad.pop()
        }
    }

    fun reset() {
        if (!closed.get()) vad.reset()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { vad.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Silero v4 requires 512-sample windows at 16 kHz. */
        const val WINDOW_SIZE = 512
    }
}
