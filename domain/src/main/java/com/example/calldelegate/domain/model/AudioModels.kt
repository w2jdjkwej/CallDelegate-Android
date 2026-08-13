package com.example.calldelegate.domain.model

const val SESSION_RECORDING_SAMPLE_RATE_HZ = 16_000

data class NormalizedRecordingAudio(
    val samples: ShortArray,
    val sampleRateHz: Int,
)

data class CaptureRequest(
    val sessionId: String,
    val maxDurationMillis: Long = 30_000L,
    val presetId: String? = null,
)

data class CapturedAudio(
    val pcm16: ShortArray,
    val sampleRateHz: Int,
    val durationMillis: Long,
    val recordingPath: String?,
    val transcriptHint: String? = null,
    val speechDetected: Boolean = true,
)

/**
 * One hypothesis from the recognizer's N-best list, in rank order.
 *
 * [score] is the recognizer's own likelihood for this hypothesis. It is not a probability and is
 * only meaningful against the other alternatives of the same utterance, so it must never be shown
 * as a confidence or compared across turns.
 */
data class RecognitionAlternative(
    val text: String,
    val score: Float?,
)

/** One recognized word with the recognizer's 0..1 confidence and its position in the audio. */
data class RecognizedWord(
    val word: String,
    val confidence: Float,
    val startSeconds: Double,
    val endSeconds: Double,
)

data class RecognitionResult(
    val text: String,
    /** Mean word confidence in 0..1 when the recognizer reported words, otherwise null. */
    val confidence: Float?,
    val isMock: Boolean,
    /**
     * The N-best list including [text] at index 0, or empty when the recognizer was not asked for
     * alternatives. Every entry carries the same normalization [text] received, so they can be
     * compared as text.
     */
    val alternatives: List<RecognitionAlternative> = emptyList(),
    /** Words backing [text], or empty when the recognizer was not asked for them. */
    val words: List<RecognizedWord> = emptyList(),
)

data class SynthesizedSpeech(
    val text: String,
    val audioPath: String?,
    val durationMillis: Long,
    val isMock: Boolean,
    val pcm16: ShortArray = shortArrayOf(),
    val sampleRateHz: Int = 16_000,
)

data class PresetSample(
    val id: String,
    val title: String,
    val transcript: String,
    val expectedScene: SceneType?,
    val kind: Kind = Kind.SPEECH,
) {
    enum class Kind { SPEECH, SILENCE, UNRECOGNIZABLE }
}
