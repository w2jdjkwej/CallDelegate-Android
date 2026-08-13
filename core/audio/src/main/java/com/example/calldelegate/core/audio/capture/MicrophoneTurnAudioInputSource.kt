package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CandidateEndpointAudioInputSource
import com.example.calldelegate.domain.api.StreamingRecognitionSnapshot
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.InputMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Microphone turn input built on the same [StreamingTurnAudioInputSource] segmentation the call
 * transport uses, so a pause is a reversible endpoint candidate here too rather than an immediate
 * end of turn.
 *
 * This type deliberately does NOT implement
 * [com.example.calldelegate.domain.api.RemoteAudioInputSource]: its PCM really is local microphone
 * audio, and the session controller keys the downlink recorder off that marker.
 *
 * It adds only what the segmenter cannot express, both of which are microphone-specific failures
 * that must not be reported as a silent caller:
 *  - a permission check ahead of opening any recorder;
 *  - promotion of [MicrophoneCallAudioSource.latestFailure], since a recorder that never opened
 *    produces an empty frame stream, which the segmenter would otherwise report as a normal turn
 *    in which nobody spoke.
 */
class MicrophoneTurnAudioInputSource(
    private val hasRecordAudioPermission: () -> Boolean,
    readerFactory: () -> PcmReader,
    vad: VoiceActivityDetector,
    captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    frameProcessingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    endpointGraceMs: Long = StreamingTurnAudioInputSource.DEFAULT_ENDPOINT_GRACE_MS,
    earlyEndpointGraceMs: Long = endpointGraceMs,
    utteranceLooksComplete: (StreamingRecognitionSnapshot?) -> Boolean = { false },
    onTurnCaptured: ((TurnCaptureObservation) -> Unit)? = null,
) : CandidateEndpointAudioInputSource {

    private val microphone = MicrophoneCallAudioSource(
        readerFactory = readerFactory,
        captureDispatcher = captureDispatcher,
    )

    private val turns = StreamingTurnAudioInputSource(
        source = microphone,
        vad = vad,
        mode = InputMode.MICROPHONE,
        onTurnCaptured = onTurnCaptured,
        endpointGraceMs = endpointGraceMs,
        earlyEndpointGraceMs = earlyEndpointGraceMs,
        utteranceLooksComplete = utteranceLooksComplete,
        frameProcessingDispatcher = frameProcessingDispatcher,
    )

    override val mode: InputMode = InputMode.MICROPHONE
    override val state: StateFlow<AudioState> = turns.state

    override suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio> =
        guarded { turns.capture(request) }

    override suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
    ): AppResult<CapturedAudio> = guarded { turns.captureStreaming(request, onFrame) }

    override suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
        onEndpointCandidate: suspend () -> StreamingRecognitionSnapshot?,
    ): AppResult<CapturedAudio> = guarded {
        turns.captureStreaming(request, onFrame, onEndpointCandidate)
    }

    override suspend fun cancel() {
        microphone.cancel()
        turns.cancel()
    }

    override suspend fun release() {
        microphone.cancel()
        turns.release()
    }

    private suspend fun guarded(
        capture: suspend () -> AppResult<CapturedAudio>,
    ): AppResult<CapturedAudio> {
        if (!hasRecordAudioPermission()) {
            return AppResult.Failure(AppError("MIC_PERMISSION", "需要麦克风权限才能使用实时输入"))
        }
        microphone.clearFailure()
        val result = capture()
        // Cancellation is the caller's own decision and already reported accurately; a recorder
        // torn down by cancel() must not be relabelled as a microphone fault.
        if (result is AppResult.Failure && result.error.code == "AUDIO_CANCELLED") return result
        // A recorder fault only replaces the outcome when it actually cost us the turn. One that
        // arrives after a full turn was already captured is not worth discarding usable speech for.
        val turnIsEmpty = result !is AppResult.Success || result.value.pcm16.isEmpty()
        if (!turnIsEmpty) return result
        return microphone.latestFailure?.let { AppResult.Failure(it) } ?: result
    }
}
