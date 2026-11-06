package com.echoscribe.app.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpeechEngine"

/** One decoded utterance with its time span inside the supplied audio buffer. */
data class DecodeResult(
    val text: String,
    val startSample: Int,
    val endSample: Int,
    val confidence: Float? = null,
)

/**
 * Thin, testable boundary around the native ASR runtime.
 *
 * Everything the app knows about sherpa-onnx lives behind this interface, so the
 * recognition pipeline can be unit-tested with a fake engine and the native library
 * can be swapped without touching business logic.
 */
interface SpeechEngine {
    /** Decodes one contiguous buffer of 16 kHz mono float samples. */
    fun decode(samples: FloatArray, language: String): String
    fun close()
}

/**
 * Whisper (via sherpa-onnx) implementation.
 *
 * Design notes that matter for a recording app:
 *
 * * A single [OfflineRecognizer] instance owns several hundred megabytes of arena
 *   memory. It is created once per recording session and reused for every segment —
 *   constructing one per utterance would thrash memory and take seconds each time.
 * * Whisper expects ~30 s windows. Feeding it a whole 2-hour file is impossible on a
 *   phone, and feeding it 1-second fragments destroys accuracy, so callers must pass
 *   VAD-delimited speech segments (see [VadEngine]).
 * * The recognizer is not thread-safe; [closed] guards against use after release when
 *   a service is being torn down mid-decode.
 */
class SherpaWhisperEngine(
    private val assets: AssetManager,
    private val model: SpeechModel,
    modelDir: File,
    private val numThreads: Int = defaultThreadCount(),
) : SpeechEngine {

    private val closed = AtomicBoolean(false)

    private val recognizer: OfflineRecognizer = run {
        require(modelDir.exists()) { "Model directory missing: ${modelDir.absolutePath}" }
        val encoder = File(modelDir, model.encoderFile)
        val decoder = File(modelDir, model.decoderFile)
        val tokens = File(modelDir, model.tokensFile)
        require(encoder.exists() && decoder.exists() && tokens.exists()) {
            "Incomplete model files in ${modelDir.absolutePath}"
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    // Empty language = ask Whisper to detect; the pipeline sets this
                    // explicitly per segment so auto-detection can be reused.
                    language = "",
                    task = "transcribe",
                    tailPaddings = -1,
                    enableTokenTimestamps = false,
                    enableSegmentTimestamps = false,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
            maxActivePaths = 1,
        )
        Log.i(TAG, "Loading ${model.displayName} (${model.id}) with $numThreads threads")
        OfflineRecognizer(assetManager = assets, config = config)
    }

    override fun decode(samples: FloatArray, language: String): String {
        if (closed.get()) return ""
        if (samples.isEmpty()) return ""
        return try {
            val stream: OfflineStream = recognizer.createStream()
            try {
                if (language.isNotBlank() && language != "auto") {
                    // Per-utterance language selection; cheap in sherpa-onnx (it only
                    // seeds the decoder prompt).
                    stream.setOption("language", language)
                    stream.setOption("task", "transcribe")
                }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                result.text.trim()
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            // A native failure must not kill a 2-hour recording: log, return empty and
            // let the pipeline keep the surrounding segments.
            Log.e(TAG, "decode failed for ${samples.size} samples", t)
            ""
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { recognizer.release() }
                .onFailure { Log.w(TAG, "recognizer.release() threw", it) }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * Leave one core for the audio thread and the UI: on a 2-core phone using both
         * cores for inference causes dropped microphone callbacks, which is far worse
         * for transcription than decoding slightly slower.
         */
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}
