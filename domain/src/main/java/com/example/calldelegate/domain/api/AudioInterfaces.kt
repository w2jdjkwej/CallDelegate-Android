package com.example.calldelegate.domain.api

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.NormalizedRecordingAudio
import com.example.calldelegate.domain.model.PresetSample
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.flow.StateFlow

sealed interface AudioState {
    data object Idle : AudioState
    data object Recording : AudioState
    data object Playing : AudioState
    data class Error(val message: String) : AudioState
}

interface AudioInputSource {
    val mode: InputMode
    val state: StateFlow<AudioState>
    suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio>
    suspend fun cancel()
    suspend fun release()
}

/** Optional capability for consuming immutable audio frames while capture is still in progress. */
interface AudioFrameStreamingInputSource : AudioInputSource {
    suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
    ): AppResult<CapturedAudio>
}

/** Optional capability for observing an endpoint candidate without ending capture or ASR. */
interface CandidateEndpointAudioInputSource : AudioFrameStreamingInputSource {
    suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
        onEndpointCandidate: suspend () -> StreamingRecognitionSnapshot?,
    ): AppResult<CapturedAudio>
}

interface AudioInputRegistry {
    fun sourceFor(mode: InputMode): AudioInputSource?
}

interface AudioOutputSink {
    val state: StateFlow<AudioState>
    suspend fun play(speech: SynthesizedSpeech): AppResult<Unit>
    suspend fun playFile(path: String): AppResult<Unit>
    suspend fun stop()
    suspend fun release()
}

/**
 * Playback timestamps produced by an output implementation.
 *
 * They describe when the app handed audio to the platform, not when a speaker membrane became
 * physically audible. PCM output is streamed after one-shot TTS has produced the complete audio;
 * these timestamps must not be interpreted as streaming TTS inference metrics.
 */
data class AudioPlaybackMetrics(
    val requestedAtElapsedRealtimeMs: Long,
    val firstAudioWriteAtElapsedRealtimeMs: Long?,
    val playbackStartedAtElapsedRealtimeMs: Long?,
    val playbackCompletedAtElapsedRealtimeMs: Long?,
    val outputMode: String,
    val errorCode: String? = null,
)

/** Optional capability for output implementations that can expose local playback timestamps. */
interface PlaybackMetricsSource {
    val latestPlaybackMetrics: StateFlow<AudioPlaybackMetrics?>
}

interface PresetRepository {
    fun samples(): List<PresetSample>
    fun find(id: String): PresetSample?
}

interface SessionRecordingStore {
    suspend fun appendPcm(sessionId: String, samples: ShortArray, sampleRateHz: Int): AppResult<String>
    suspend fun finalizeSession(sessionId: String): AppResult<String?>
    suspend fun discardSession(sessionId: String)
}

interface RecordingAudioNormalizer {
    fun normalize(samples: ShortArray, sourceSampleRateHz: Int): AppResult<NormalizedRecordingAudio>
}
