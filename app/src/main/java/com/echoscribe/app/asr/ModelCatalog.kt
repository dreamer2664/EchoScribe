package com.echoscribe.app.asr

/**
 * The catalogue of installable speech models.
 *
 * Every entry is a genuinely open-source, redistributable model. This list mirrors
 * `benchmark/scripts/download_models.py` exactly, so anything measured in
 * `docs/benchmarks/` is something the app can actually run.
 *
 * Nothing here is bundled in the APK: model weights are hundreds of megabytes and the
 * APK must stay a reasonable download. The user picks a model, the app downloads it
 * once (or it can be sideloaded into the models folder), and from then on the app is
 * fully offline.
 */
data class SpeechModel(
    val id: String,
    val displayName: String,
    val description: String,
    /** Hugging Face repo containing the ONNX export. */
    val repo: String,
    val encoderFile: String,
    val decoderFile: String,
    val tokensFile: String,
    val approxBytes: Long,
    /** Languages this model can transcribe. "multilingual" = Whisper's 99 languages. */
    val languages: List<String>,
    val multilingual: Boolean,
    /** Relative speed/accuracy tier used to sort the picker. */
    val tier: Tier,
    val license: String,
    /** Recommended default for new installs. */
    val recommended: Boolean = false,
    /**
     * Whisper hallucinates on silence when the model is too small or the audio is
     * quiet; models flagged here get a stricter VAD threshold.
     */
    val needsStrictVad: Boolean = false,
) {
    enum class Tier { FASTEST, BALANCED, ACCURATE, MAXIMUM }

    val id8: Boolean get() = encoderFile.contains(".int8.")

    fun downloadUrl(file: String): String =
        "https://huggingface.co/$repo/resolve/main/$file"

    /** Files in download order (encoder last because it is the largest). */
    fun files(): List<Pair<String, String>> = listOf(
        tokensFile to tokensFile,
        decoderFile to decoderFile,
        encoderFile to encoderFile,
    )
}

object ModelCatalog {

    // Whisper ONNX conversions by k2-fsa, exported from OpenAI Whisper weights.
    // OpenAI Whisper weights: MIT. Sherpa-onnx runtime: Apache-2.0.
    private const val HF = "csukuangfj"

    val models: List<SpeechModel> = listOf(
        SpeechModel(
            id = "whisper-base",
            displayName = "Whisper Base",
            description = "The default. Good accuracy in 99 languages at ~4× real time on a mid-range phone.",
            repo = "$HF/sherpa-onnx-whisper-base",
            encoderFile = "base-encoder.int8.onnx",
            decoderFile = "base-decoder.int8.onnx",
            tokensFile = "base-tokens.txt",
            approxBytes = 145_000_000,
            languages = listOf("multilingual"),
            multilingual = true,
            tier = SpeechModel.Tier.BALANCED,
            license = "MIT (model) / Apache-2.0 (runtime)",
            recommended = true,
            needsStrictVad = true,
        ),
        SpeechModel(
            id = "whisper-base-en",
            displayName = "Whisper Base English",
            description = "English-only sibling of Base. Slightly more accurate than Base for English at the same size.",
            repo = "$HF/sherpa-onnx-whisper-base.en",
            encoderFile = "base.en-encoder.int8.onnx",
            decoderFile = "base.en-decoder.int8.onnx",
            tokensFile = "base.en-tokens.txt",
            approxBytes = 145_000_000,
            languages = listOf("en"),
            multilingual = false,
            tier = SpeechModel.Tier.BALANCED,
            license = "MIT (model) / Apache-2.0 (runtime)",
            needsStrictVad = true,
        ),
        SpeechModel(
            id = "whisper-tiny",
            displayName = "Whisper Tiny",
            description = "Smallest and fastest. Use on older phones or when battery matters more than accuracy.",
            repo = "$HF/sherpa-onnx-whisper-tiny",
            encoderFile = "tiny-encoder.int8.onnx",
            decoderFile = "tiny-decoder.int8.onnx",
            tokensFile = "tiny-tokens.txt",
            approxBytes = 80_000_000,
            languages = listOf("multilingual"),
            multilingual = true,
            tier = SpeechModel.Tier.FASTEST,
            license = "MIT (model) / Apache-2.0 (runtime)",
            needsStrictVad = true,
        ),
        SpeechModel(
            id = "whisper-small",
            displayName = "Whisper Small",
            description = "Highest accuracy that still runs faster than real time on most phones. Best default for dictation.",
            repo = "$HF/sherpa-onnx-whisper-small",
            encoderFile = "small-encoder.int8.onnx",
            decoderFile = "small-decoder.int8.onnx",
            tokensFile = "small-tokens.txt",
            approxBytes = 480_000_000,
            languages = listOf("multilingual"),
            multilingual = true,
            tier = SpeechModel.Tier.ACCURATE,
            license = "MIT (model) / Apache-2.0 (runtime)",
        ),
        SpeechModel(
            id = "whisper-medium",
            displayName = "Whisper Medium",
            description = "Best accuracy short of Large. Needs a recent flagship to keep up with long recordings.",
            repo = "$HF/sherpa-onnx-whisper-medium",
            encoderFile = "medium-encoder.int8.onnx",
            decoderFile = "medium-decoder.int8.onnx",
            tokensFile = "medium-tokens.txt",
            approxBytes = 1_000_000_000,
            languages = listOf("multilingual"),
            multilingual = true,
            tier = SpeechModel.Tier.MAXIMUM,
            license = "MIT (model) / Apache-2.0 (runtime)",
        ),
    )

    val default: SpeechModel get() = models.first { it.recommended }

    fun byId(id: String?): SpeechModel = models.firstOrNull { it.id == id } ?: default

    /** Models that can transcribe [language], best tier first. */
    fun forLanguage(language: String): List<SpeechModel> = models
        .filter { it.multilingual || it.languages.contains(language) }
        .sortedByDescending { it.tier.ordinal }

    val supportedLanguages: List<LanguageOption> = listOf(
        LanguageOption("auto", "Auto-detect", "Detect the spoken language automatically"),
        LanguageOption("en", "English", "English"),
        LanguageOption("it", "Italiano", "Italian"),
        LanguageOption("es", "Español", "Spanish"),
        LanguageOption("fr", "Français", "French"),
        LanguageOption("de", "Deutsch", "German"),
        LanguageOption("pt", "Português", "Portuguese"),
        LanguageOption("nl", "Nederlands", "Dutch"),
        LanguageOption("pl", "Polski", "Polish"),
        LanguageOption("ru", "Русский", "Russian"),
        LanguageOption("uk", "Українська", "Ukrainian"),
        LanguageOption("tr", "Türkçe", "Turkish"),
        LanguageOption("ar", "العربية", "Arabic"),
        LanguageOption("hi", "हिन्दी", "Hindi"),
        LanguageOption("zh", "中文", "Chinese"),
        LanguageOption("ja", "日本語", "Japanese"),
        LanguageOption("ko", "한국어", "Korean"),
    )
}

data class LanguageOption(
    val code: String,
    val label: String,
    val englishName: String,
) {
    val isAuto: Boolean get() = code == "auto"
}
