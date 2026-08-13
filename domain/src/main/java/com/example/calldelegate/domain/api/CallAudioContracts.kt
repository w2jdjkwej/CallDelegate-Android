package com.example.calldelegate.domain.api

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.flow.Flow

/** Transport dimension of a call. Kept ORTHOGONAL to [com.example.calldelegate.domain.model.InputMode]. */
enum class CallTransport { SIMULATED, TELECOM, VOIP }

/** Single active-call snapshot at the telephony layer (maps to SessionPhase at the AI layer). */
data class ExternalCallSnapshot(
    val callId: String,
    val state: ExternalCallState,
    val callerNumber: String? = null,
    val callerName: String? = null,
    val isIncoming: Boolean = true,
    val startedAtMillis: Long = 0L,
)

/**
 * One frame of raw call PCM. [data] is always a private copy owned by the consumer, so async
 * pipelines (Flow/Channel, or a future buffer pool) can never observe an overwritten buffer.
 * Canonical format: 16000 Hz, mono, 16-bit PCM little-endian.
 */
data class PcmAudioFrame(
    val callId: String,
    val data: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val timestampMs: Long,
    val emittedAtElapsedRealtimeNanos: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmAudioFrame) return false
        return callId == other.callId &&
            sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            timestampMs == other.timestampMs &&
            emittedAtElapsedRealtimeNanos == other.emittedAtElapsedRealtimeNanos &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = callId.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + (emittedAtElapsedRealtimeNanos?.hashCode() ?: 0)
        return result
    }
}

/** How trustworthy the captured audio is. Never claim REMOTE_CONFIRMED without device evidence. */
enum class CaptureProvenance { REMOTE_CONFIRMED, LOCAL_MIC, MIXED_UNKNOWN, SILENCED, UNKNOWN }

/** Dev-only capture diagnostics. Must NOT contain phone numbers or raw PCM content. */
data class CaptureDiagnostics(
    val audioSourceLabel: String,
    val initialized: Boolean,
    val bytesPerSecond: Long,
    val meanRms: Double,
    val maxAbsAmplitude: Int,
    val silenceRatio: Double,
    val longestSilenceMs: Long,
    /** Frames discarded by the bounded stream when a downstream consumer could not keep up. */
    val droppedFrames: Long = 0,
    /** Zero-length reads are diagnostic only; a single zero read is not automatically a failed turn. */
    val zeroByteReads: Long = 0,
    val readErrorCount: Long = 0,
    val error: String? = null,
)

data class AudioCaptureResult(
    val callId: String,
    val wavPath: String?,
    val durationMs: Long,
    val totalBytes: Long,
    val provenance: CaptureProvenance,
    val diagnostics: CaptureDiagnostics,
)

/**
 * Streaming call-audio abstraction (telephony/audio layer). Distinct from [AudioInputSource]:
 * this emits raw frames continuously; [AudioInputSource] returns one VAD-segmented turn buffer.
 */
interface CallAudioSource {
    val audioFrames: Flow<PcmAudioFrame>
    suspend fun start(callId: String): AppResult<Unit>
    suspend fun stop(callId: String): AppResult<AudioCaptureResult>
}

/**
 * Honest call-uplink boundary. Standard Android audio APIs generally CANNOT inject audio into a
 * carrier call uplink, so implementations must return [CallResponseResult.LocalPlaybackOnly] or
 * [CallResponseResult.Unsupported] rather than silently pretending success.
 */
interface CallResponseAudioSink {
    suspend fun playToCall(callId: String, speech: SynthesizedSpeech): CallResponseResult

    /**
     * Release anything held for the duration of [callId], such as an audio route.
     *
     * A sink that has to change the device's audio routing cannot do it per reply: acquiring and
     * restoring the route around every utterance means the track is bound while the switch is still
     * in flight, and the reply plays out of whatever the route used to be. It holds the route for
     * the call and gives it back here.
     */
    suspend fun releaseCall(callId: String) = Unit
}

/** Identifies the active external call and the transport-specific output used for its replies. */
data class ExternalCallResponseRoute(
    val callId: String,
    val sink: CallResponseAudioSink,
    /**
     * Also play TTS on the device while writing the transport uplink. This is useful as a local
     * monitor and provides an acoustic fallback on OEM carrier stacks that accept the injection
     * track but fail to make it audible to the remote party.
     */
    val monitorLocally: Boolean = false,
)

sealed interface CallResponseResult {
    /** The transport accepted and consumed the uplink PCM; remote audibility still needs a call test. */
    data object PlayedToCallUplink : CallResponseResult

    /** Played on the local speaker only; the remote party does NOT hear it. */
    data object LocalPlaybackOnly : CallResponseResult

    data class Unsupported(val reason: String) : CallResponseResult
    data class Failed(val code: String, val message: String) : CallResponseResult
}
