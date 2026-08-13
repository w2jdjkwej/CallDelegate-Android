package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CandidateEndpointAudioInputSource
import com.example.calldelegate.domain.api.RemoteAudioInputSource
import com.example.calldelegate.domain.api.StreamingRecognitionSnapshot
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.InputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** VAD and candidate-endpoint facts for device-test telemetry. They never alter captured PCM. */
data class TurnCaptureObservation(
    val sampleRateHz: Int,
    val capturedSamples: Int,
    val speechStartSample: Int?,
    val speechEndSample: Int?,
    val vadOutputAtElapsedRealtimeNanos: Long?,
    val additionalUtteranceDetected: Boolean,
    val endReason: TurnCaptureEndReason,
    val endpointDetectionQuantizationMs: Long? = null,
    val candidateEndpointAtMs: List<Long> = emptyList(),
    val speechResumedAtMs: List<Long> = emptyList(),
    val speechResumedAfterCandidateMs: List<Long> = emptyList(),
    val candidateCancelledAtMs: List<Long> = emptyList(),
    val endpointCommittedAtMs: Long? = null,
    val candidateEndpointRollbackCount: Int = 0,
    val candidateRecognizerIds: List<String?> = emptyList(),
    val candidatePartialTextsRaw: List<String?> = emptyList(),
    val committedCandidateRecognizerId: String? = null,
    val committedCandidatePartialTextRaw: String? = null,
    val vadFrameProcessingLagMs: Long? = null,
    /** True when the committed endpoint used the short grace because the partial looked complete. */
    val committedOnEarlyEndpointEvidence: Boolean = false,
    /** Grace window in effect at commit time, so a batch can attribute latency to the decision. */
    val committedEndpointGraceMs: Long? = null,
    /**
     * What the completeness judge was actually given at commit time.
     *
     * Null means no snapshot at all -- the recognizer was unavailable or not streaming, and no
     * evidence of any kind could have existed. False means a snapshot arrived with the segment
     * still open. Without this the log could only report that the short window did not open,
     * never which of those two reasons it was.
     */
    val committedCandidateSegmentClosed: Boolean? = null,
    /**
     * Grace elapsed when the recognizer was asked a second time, or null if it never was.
     *
     * Distinguishes "the recognizer still had not closed a segment after this long" from "nobody
     * asked it again", which the segment flag alone cannot.
     */
    val committedCandidateRecheckAtMs: Long? = null,
    /** Last VAD-positive sample, kept separate from the committed endpoint sample. */
    val lastSpeechSample: Int? = null,
    /** Wall-clock delay from the last emitted speech frame to endpoint commit. */
    val speechEndToCommitWallClockMs: Long? = null,
    /** Confirmed trailing silence omitted from ASR after a candidate endpoint commits. */
    val recognitionTrailingSilenceSkippedSamples: Int = 0,
)

enum class TurnCaptureEndReason {
    VAD_ENDPOINT,
    MAX_DURATION,
    STREAM_ENDED,
    TIMEOUT,
    CANCELLED,
    MULTIPLE_UTTERANCES,
}

enum class EndpointState {
    LISTENING,
    CANDIDATE,
    COMMITTED,
}

/**
 * Converts a continuous call stream into one turn while preserving fixed 20 ms VAD/ASR frames.
 * An endpoint is first reversible: capture and the same recognizer continue during the grace window.
 */
class StreamingTurnAudioInputSource(
    private val source: CallAudioSource,
    private val vad: VoiceActivityDetector,
    override val mode: InputMode = InputMode.MICROPHONE,
    private val onTurnCaptured: ((TurnCaptureObservation) -> Unit)? = null,
    @Suppress("unused")
    private val inspectRemainingFramesForAdditionalTurns: Boolean = false,
    private val nowElapsedRealtimeNanos: () -> Long = System::nanoTime,
    private val endpointGraceMs: Long = DEFAULT_ENDPOINT_GRACE_MS,
    /**
     * Shorter grace used only when [utteranceLooksComplete] finds positive evidence in the candidate
     * partial. Defaults to [endpointGraceMs], so an unconfigured source behaves exactly as before.
     */
    private val earlyEndpointGraceMs: Long = endpointGraceMs,
    /**
     * Fail-safe completeness judgement on the candidate snapshot. The default never claims a partial
     * is complete, so the long grace always applies unless a caller supplies real evidence. A null
     * snapshot (recognizer unavailable) must keep the long grace, so absence of evidence is never
     * treated as evidence of completion.
     */
    private val utteranceLooksComplete: (StreamingRecognitionSnapshot?) -> Boolean = { false },
    /**
     * When, into the grace window, the recognizer is asked a second time.
     *
     * The first snapshot is delayed until [earlyEndpointGraceMs], so it does not block VAD as soon
     * as a candidate appears. This later recheck remains useful for recognizers whose own endpoint
     * signal becomes available near the long window.
     */
    private val endpointRecheckMs: Long = DEFAULT_ENDPOINT_RECHECK_MS,
    private val maxCandidateRollbackCount: Int = DEFAULT_MAX_CANDIDATE_ROLLBACK_COUNT,
    private val frameProcessingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val recognitionChunkDurationMs: Long = DEFAULT_RECOGNITION_CHUNK_DURATION_MS,
) : RemoteAudioInputSource, CandidateEndpointAudioInputSource {

    init {
        require(endpointGraceMs >= 0L) { "endpointGraceMs must not be negative" }
        require(earlyEndpointGraceMs in 0L..endpointGraceMs) {
            "earlyEndpointGraceMs must be between 0 and endpointGraceMs"
        }
        require(maxCandidateRollbackCount >= 0) { "maxCandidateRollbackCount must not be negative" }
        require(recognitionChunkDurationMs in SUPPORTED_RECOGNITION_CHUNK_DURATIONS_MS) {
            "recognitionChunkDurationMs must be one of $SUPPORTED_RECOGNITION_CHUNK_DURATIONS_MS"
        }
    }

    private val _state = MutableStateFlow<AudioState>(AudioState.Idle)
    override val state: StateFlow<AudioState> = _state.asStateFlow()
    private val cancelled = AtomicBoolean(false)

    private class TurnComplete(val reason: TurnCaptureEndReason) : CancellationException()
    private class FrameConsumerFailed(val failure: AppResult.Failure) : CancellationException()
    private data class DeferredRecognitionSubframe(
        val samples: ShortArray,
        val sampleRateHz: Int,
    )

    override suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio> =
        captureStreaming(request, { _, _ -> AppResult.Success(Unit) }, { null })

    override suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
    ): AppResult<CapturedAudio> = captureStreaming(request, onFrame) { null }

    override suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
        onEndpointCandidate: suspend () -> StreamingRecognitionSnapshot?,
    ): AppResult<CapturedAudio> {
        cancelled.set(false)
        vad.reset()
        _state.value = AudioState.Recording
        val candidateEndpointAtMs = ArrayList<Long>()
        val speechResumedAtMs = ArrayList<Long>()
        val speechResumedAfterCandidateMs = ArrayList<Long>()
        val candidateCancelledAtMs = ArrayList<Long>()
        val candidateRecognizerIds = ArrayList<String?>()
        val candidatePartialTextsRaw = ArrayList<String?>()
        var totalSamples = 0
        var speechDetected = false
        var speechStartSample: Int? = null
        var speechEndSample: Int? = null
        var lastSpeechSample: Int? = null
        var lastSpeechFrameEmittedAtElapsedRealtimeNanos: Long? = null
        var speechEndToCommitWallClockMs: Long? = null
        var vadOutputAtElapsedRealtimeNanos: Long? = null
        var endpointDetectionQuantizationMs: Long? = null
        var endpointCommittedAtMs: Long? = null
        var maxVadFrameProcessingLagMs: Long? = null
        var endpointState = EndpointState.LISTENING
        var candidateSample: Int? = null
        var candidateSnapshot: StreamingRecognitionSnapshot? = null
        /** An outstanding request for what the recognizer has heard. Null when nothing is in flight. */
        var pendingSnapshot: Deferred<StreamingRecognitionSnapshot?>? = null
        var candidateEarlyCommitEligible = false
        var candidateRollbackCount = 0
        var committedCandidateRecognizerId: String? = null
        var committedCandidatePartialTextRaw: String? = null
        var committedOnEarlyEndpointEvidence = false
        var committedEndpointGraceMs: Long? = null
        var committedCandidateSegmentClosed: Boolean? = null
        var candidateEarlyCheckDone = false
        var candidateRecheckDone = false
        var candidateRecheckAtMs: Long? = null
        var committedCandidateRecheckAtMs: Long? = null
        var sampleRate = DEFAULT_SAMPLE_RATE
        var endReason = TurnCaptureEndReason.STREAM_ENDED
        var vadSubframe = ShortArray(0)
        var vadSubframeSampleCount = 0
        var vadSubframeSampleRate = 0
        var vadSubframeLastEmittedAtElapsedRealtimeNanos: Long? = null
        var recognitionChunk = ShortArray(0)
        var recognitionChunkSampleCount = 0
        var recognitionChunkSampleRate = 0
        val deferredRecognitionSubframes = ArrayList<DeferredRecognitionSubframe>()
        var deferredRecognitionSampleCount = 0
        var recognitionTrailingSilenceSkippedSamples = 0
        val maxSamples = if (request.maxDurationMillis > 0) {
            (DEFAULT_SAMPLE_RATE * request.maxDurationMillis / 1_000L).toInt().coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        val capturedPcm = GrowingPcm16Buffer(
            initialCapacity = minOf(maxSamples, DEFAULT_SAMPLE_RATE),
        )

        fun samplesToMillis(samples: Int, rate: Int): Long =
            if (rate > 0) samples * 1_000L / rate else 0L

        suspend fun sendRecognitionChunk(samples: ShortArray, rate: Int) {
            when (val streamed = onFrame(samples, rate)) {
                is AppResult.Failure -> throw FrameConsumerFailed(streamed)
                is AppResult.Success -> Unit
            }
        }

        suspend fun flushRecognitionChunk() {
            if (recognitionChunkSampleCount <= 0) return
            val samples = if (recognitionChunkSampleCount == recognitionChunk.size) {
                recognitionChunk
            } else {
                recognitionChunk.copyOf(recognitionChunkSampleCount)
            }
            sendRecognitionChunk(samples, recognitionChunkSampleRate)
            recognitionChunk = ShortArray(recognitionChunk.size)
            recognitionChunkSampleCount = 0
        }

        suspend fun appendRecognitionSubframe(samples: ShortArray, rate: Int) {
            if (recognitionChunkDurationMs == VAD_SUBFRAME_DURATION_MS) {
                sendRecognitionChunk(samples, rate)
                return
            }
            if (recognitionChunkSampleRate != rate) {
                flushRecognitionChunk()
                recognitionChunkSampleRate = rate
                recognitionChunk = ShortArray(recognitionChunkSamples(rate))
            }
            samples.copyInto(recognitionChunk, destinationOffset = recognitionChunkSampleCount)
            recognitionChunkSampleCount += samples.size
            if (recognitionChunkSampleCount == recognitionChunk.size) flushRecognitionChunk()
        }

        fun deferRecognitionSubframe(samples: ShortArray, rate: Int) {
            deferredRecognitionSubframes += DeferredRecognitionSubframe(samples, rate)
            deferredRecognitionSampleCount += samples.size
        }

        suspend fun flushDeferredRecognitionSubframes() {
            for (subframe in deferredRecognitionSubframes) {
                appendRecognitionSubframe(subframe.samples, subframe.sampleRateHz)
            }
            deferredRecognitionSubframes.clear()
            deferredRecognitionSampleCount = 0
        }

        fun discardDeferredRecognitionSubframes() {
            recognitionTrailingSilenceSkippedSamples += deferredRecognitionSampleCount
            deferredRecognitionSubframes.clear()
            deferredRecognitionSampleCount = 0
        }

        fun effectiveGraceMs(earlyEligible: Boolean): Long =
            if (earlyEligible) earlyEndpointGraceMs else endpointGraceMs

        fun commitCandidate(commitSample: Int) {
            endpointState = EndpointState.COMMITTED
            speechEndSample = commitSample
            endpointCommittedAtMs = samplesToMillis(commitSample, vadSubframeSampleRate)
            val committedAtNanos = nowElapsedRealtimeNanos()
            vadOutputAtElapsedRealtimeNanos = committedAtNanos
            speechEndToCommitWallClockMs = lastSpeechFrameEmittedAtElapsedRealtimeNanos?.let { speechEndAt ->
                ((committedAtNanos - speechEndAt) / 1_000_000L).coerceAtLeast(0L)
            }
            committedCandidateRecognizerId = candidateSnapshot?.recognizerId
            committedCandidatePartialTextRaw = candidateSnapshot?.partialTextRaw
            committedCandidateSegmentClosed = candidateSnapshot?.recognizerClosedSegment
            committedCandidateRecheckAtMs = candidateRecheckAtMs
            committedOnEarlyEndpointEvidence = candidateEarlyCommitEligible
            committedEndpointGraceMs = effectiveGraceMs(candidateEarlyCommitEligible)
            endReason = TurnCaptureEndReason.VAD_ENDPOINT
        }

        /**
         * Where snapshot requests wait, so that the capture loop does not.
         *
         * Supervised and independent: a request that fails must not take the turn down with it, and
         * the turn's own end must abandon a request still outstanding.
         */
        val snapshotScope = CoroutineScope(SupervisorJob() + frameProcessingDispatcher)

        try {
            /**
             * Asks the recognizer what it has heard, without waiting for the answer.
             *
             * A snapshot is served by the same worker that decodes audio, so its answer arrives only
             * after everything already queued. Awaiting it here stopped this loop, and stopping this
             * loop stops VAD: on the 2026-08-09 21:20 call the grace window then elapsed 913 ms and
             * 1,268 ms later in wall clock than in the audio it is measured in, and `vadLagMax`
             * reached 967 ms on the same turn. The window had passed; only nobody was counting.
             *
             * Started UNDISPATCHED so a recognizer that can answer without suspending -- an idle one,
             * or a fake -- still answers within this subframe, exactly as it did before.
             */
            fun requestCandidateSnapshot() {
                pendingSnapshot = snapshotScope.async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        onEndpointCandidate()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            fun discardPendingSnapshot() {
                pendingSnapshot?.cancel()
                pendingSnapshot = null
            }

            /**
             * Takes an answer that has arrived. Never blocks: an answer still in flight simply is
             * not evidence yet, and the long grace is what the absence of evidence already means.
             *
             * @param graceElapsedMs recorded as the moment the evidence landed, which is how far
             *   behind the caller the recognizer actually was.
             */
            fun collectCandidateSnapshotIfReady(graceElapsedMs: Long) {
                val pending = pendingSnapshot ?: return
                if (!pending.isCompleted) return
                pendingSnapshot = null
                val snapshot = runCatching { pending.getCompleted() }.getOrNull()
                candidateSnapshot = snapshot
                candidateRecheckAtMs = graceElapsedMs
                candidateEarlyCommitEligible = try {
                    utteranceLooksComplete(snapshot)
                } catch (_: Exception) {
                    false
                }
                candidateRecognizerIds += snapshot?.recognizerId
                candidatePartialTextsRaw += snapshot?.partialTextRaw
            }

            suspend fun processVadSubframe(
                actualSampleCount: Int,
                detectionQuantizationMs: Long,
                allowNewCandidate: Boolean,
            ) {
                if (actualSampleCount <= 0) return
                val samplesForVad = if (actualSampleCount == vadSubframe.size) {
                    vadSubframe
                } else {
                    // VAD always receives one fixed-size 20 ms frame. Recognition may group several
                    // subframes, while captured PCM keeps its original length.
                    vadSubframe.copyOf()
                }
                val stateBeforeDecision = endpointState
                val decision = vad.accept(samplesForVad, vadSubframeSampleRate)
                speechDetected = speechDetected || decision.speechDetected
                val subframeStartSample = totalSamples - actualSampleCount
                if (decision.speechDetected && speechStartSample == null) {
                    speechStartSample = subframeStartSample
                }
                if (decision.speechDetected) {
                    lastSpeechSample = totalSamples
                    lastSpeechFrameEmittedAtElapsedRealtimeNanos =
                        vadSubframeLastEmittedAtElapsedRealtimeNanos
                }

                // Once an endpoint is reversible, keep its following silence out of the recognizer.
                // VAD can then advance while Vosk drains prior speech. If speech resumes, replay the
                // held frames before the resumed frame so the recognizer still sees exact PCM order.
                if (stateBeforeDecision == EndpointState.CANDIDATE && !decision.speechDetected) {
                    deferRecognitionSubframe(samplesForVad, vadSubframeSampleRate)
                } else {
                    if (stateBeforeDecision == EndpointState.CANDIDATE) {
                        flushDeferredRecognitionSubframes()
                    }
                    appendRecognitionSubframe(samplesForVad, vadSubframeSampleRate)
                }

                if (endpointState == EndpointState.CANDIDATE) {
                    val currentCandidateSample = checkNotNull(candidateSample)
                    if (decision.speechDetected) {
                        val resumedAfterMs = samplesToMillis(
                            (subframeStartSample - currentCandidateSample).coerceAtLeast(0),
                            vadSubframeSampleRate,
                        )
                        speechResumedAtMs += samplesToMillis(subframeStartSample, vadSubframeSampleRate)
                        speechResumedAfterCandidateMs += resumedAfterMs
                        candidateCancelledAtMs += samplesToMillis(subframeStartSample, vadSubframeSampleRate)
                        candidateRollbackCount += 1
                        candidateSample = null
                        candidateSnapshot = null
                        // The pause this was asked about turned out not to end the turn.
                        discardPendingSnapshot()
                        candidateEarlyCommitEligible = false
                        candidateEarlyCheckDone = false
                        candidateRecheckDone = false
                        candidateRecheckAtMs = null
                        endpointState = EndpointState.LISTENING
                    } else {
                        val graceElapsedMs = samplesToMillis(
                            totalSamples - currentCandidateSample,
                            vadSubframeSampleRate,
                        )
                        collectCandidateSnapshotIfReady(graceElapsedMs)

                        // Still delayed to the short grace: that is the earliest a commit could
                        // happen, so nothing before it could use the answer. Asking early is now
                        // merely useless rather than harmful.
                        if (!candidateEarlyCheckDone && graceElapsedMs >= earlyEndpointGraceMs) {
                            candidateEarlyCheckDone = true
                            requestCandidateSnapshot()
                        }

                        // One later observation preserves support for recognizers whose own segment
                        // closure becomes visible near the long window. Skipped while an earlier
                        // request is still outstanding: replacing it would only restart the wait,
                        // and its answer is the more complete of the two anyway.
                        if (!candidateEarlyCommitEligible &&
                            candidateEarlyCheckDone &&
                            !candidateRecheckDone &&
                            pendingSnapshot == null &&
                            endpointRecheckMs > earlyEndpointGraceMs &&
                            graceElapsedMs >= endpointRecheckMs
                        ) {
                            candidateRecheckDone = true
                            requestCandidateSnapshot()
                        }
                        collectCandidateSnapshotIfReady(graceElapsedMs)
                        if (graceElapsedMs >= effectiveGraceMs(candidateEarlyCommitEligible)) {
                            flushRecognitionChunk()
                            discardDeferredRecognitionSubframes()
                            discardPendingSnapshot()
                            commitCandidate(totalSamples)
                            throw TurnComplete(TurnCaptureEndReason.VAD_ENDPOINT)
                        }
                    }
                } else if (
                    endpointState == EndpointState.LISTENING &&
                    allowNewCandidate &&
                    decision.endOfSpeech
                ) {
                    candidateSample = totalSamples
                    candidateEndpointAtMs += samplesToMillis(totalSamples, vadSubframeSampleRate)
                    endpointDetectionQuantizationMs = detectionQuantizationMs
                    endpointState = EndpointState.CANDIDATE
                    candidateSnapshot = null
                    discardPendingSnapshot()
                    candidateEarlyCommitEligible = false
                    candidateEarlyCheckDone = false
                    candidateRecheckDone = false
                    candidateRecheckAtMs = null
                    flushRecognitionChunk()
                    if (endpointGraceMs == 0L || candidateRollbackCount >= maxCandidateRollbackCount) {
                        commitCandidate(totalSamples)
                        throw TurnComplete(TurnCaptureEndReason.VAD_ENDPOINT)
                    }
                    if (earlyEndpointGraceMs == 0L) {
                        candidateEarlyCheckDone = true
                        requestCandidateSnapshot()
                        // A zero-length short grace leaves no later subframe to collect in, so this
                        // is the only chance to use an answer. A recognizer that cannot answer
                        // without suspending simply does not get to shorten this turn.
                        collectCandidateSnapshotIfReady(0L)
                        if (candidateEarlyCommitEligible) {
                            commitCandidate(totalSamples)
                            throw TurnComplete(TurnCaptureEndReason.VAD_ENDPOINT)
                        }
                    }
                }
                vadSubframe = ShortArray(vadSubframe.size)
                vadSubframeSampleCount = 0
                vadSubframeLastEmittedAtElapsedRealtimeNanos = null
            }

            val collectFrames: suspend () -> Boolean = {
                withContext(frameProcessingDispatcher) {
                    source.audioFrames.collect { frame ->
                        currentCoroutineContext().ensureActive()
                        if (cancelled.get()) throw TurnComplete(TurnCaptureEndReason.CANCELLED)
                        frame.emittedAtElapsedRealtimeNanos?.let { emittedAt ->
                            val lagMs = ((nowElapsedRealtimeNanos() - emittedAt) / 1_000_000L)
                                .coerceAtLeast(0L)
                            maxVadFrameProcessingLagMs = maxOf(maxVadFrameProcessingLagMs ?: 0L, lagMs)
                        }
                        sampleRate = frame.sampleRate
                        val frameSampleCount = frame.data.size / Short.SIZE_BYTES
                        if (frameSampleCount > 0) {
                            if (vadSubframeSampleRate != frame.sampleRate) {
                                if (vadSubframeSampleCount > 0) {
                                    processVadSubframe(
                                        actualSampleCount = vadSubframeSampleCount,
                                        detectionQuantizationMs = 0L,
                                        allowNewCandidate = false,
                                    )
                                }
                                flushRecognitionChunk()
                                vadSubframeSampleRate = frame.sampleRate
                                vadSubframe = ShortArray(subframeSamples(frame.sampleRate))
                                vadSubframeSampleCount = 0
                            }

                            var frameOffset = 0
                            while (frameOffset < frameSampleCount) {
                                if (totalSamples >= maxSamples) {
                                    flushDeferredRecognitionSubframes()
                                    flushRecognitionChunk()
                                    throw TurnComplete(TurnCaptureEndReason.MAX_DURATION)
                                }
                                val copyCount = minOf(
                                    vadSubframe.size - vadSubframeSampleCount,
                                    frameSampleCount - frameOffset,
                                    maxSamples - totalSamples,
                                )
                                capturedPcm.appendLittleEndian(
                                    source = frame.data,
                                    sourceSampleOffset = frameOffset,
                                    sampleCount = copyCount,
                                )
                                capturedPcm.copyInto(
                                    destination = vadSubframe,
                                    destinationOffset = vadSubframeSampleCount,
                                    sourceOffset = totalSamples,
                                    sampleCount = copyCount,
                                )
                                totalSamples += copyCount
                                vadSubframeSampleCount += copyCount
                                vadSubframeLastEmittedAtElapsedRealtimeNanos =
                                    frame.emittedAtElapsedRealtimeNanos
                                frameOffset += copyCount

                                if (vadSubframeSampleCount == vadSubframe.size) {
                                    val quantizationMs =
                                        (frameSampleCount - frameOffset) * 1_000L / frame.sampleRate
                                    processVadSubframe(
                                        actualSampleCount = vadSubframeSampleCount,
                                        detectionQuantizationMs = quantizationMs,
                                        allowNewCandidate = true,
                                    )
                                }
                                if (totalSamples >= maxSamples) {
                                    if (vadSubframeSampleCount > 0) {
                                        processVadSubframe(
                                            actualSampleCount = vadSubframeSampleCount,
                                            detectionQuantizationMs = 0L,
                                            allowNewCandidate = false,
                                        )
                                    }
                                    flushDeferredRecognitionSubframes()
                                    flushRecognitionChunk()
                                    throw TurnComplete(TurnCaptureEndReason.MAX_DURATION)
                                }
                            }
                        }
                    }
                    if (vadSubframeSampleCount > 0) {
                        processVadSubframe(
                            actualSampleCount = vadSubframeSampleCount,
                            detectionQuantizationMs = 0L,
                            allowNewCandidate = false,
                        )
                    }
                    if (endpointState == EndpointState.CANDIDATE) {
                        // A finite preset stream provides definitive end-of-input evidence. Commit the
                        // candidate immediately instead of waiting for wall-clock silence that cannot arrive.
                        discardDeferredRecognitionSubframes()
                        flushRecognitionChunk()
                        commitCandidate(totalSamples)
                    } else {
                        flushDeferredRecognitionSubframes()
                        flushRecognitionChunk()
                    }
                    true
                }
            }
            val completedBeforeTimeout = if (request.maxDurationMillis > 0L) {
                withTimeoutOrNull(request.maxDurationMillis + TIMEOUT_GRACE_MS) { collectFrames() }
            } else {
                collectFrames()
            }
            if (completedBeforeTimeout == null) {
                withContext(frameProcessingDispatcher) {
                    if (vadSubframeSampleCount > 0) {
                        processVadSubframe(
                            actualSampleCount = vadSubframeSampleCount,
                            detectionQuantizationMs = 0L,
                            allowNewCandidate = false,
                        )
                    }
                    flushDeferredRecognitionSubframes()
                    flushRecognitionChunk()
                }
                endReason = TurnCaptureEndReason.TIMEOUT
            }
        } catch (turn: TurnComplete) {
            endReason = turn.reason
        } catch (failed: FrameConsumerFailed) {
            _state.value = AudioState.Idle
            return failed.failure
        } catch (cancellation: CancellationException) {
            _state.value = AudioState.Idle
            throw cancellation
        } catch (throwable: Throwable) {
            _state.value = AudioState.Error("远端音频采集失败")
            return AppResult.Failure(AppError("REMOTE_AUDIO_CAPTURE", "远端音频采集失败", throwable.message))
        } finally {
            // The turn is over however it ended, so nothing is waiting on an answer any more.
            snapshotScope.cancel()
        }
        _state.value = AudioState.Idle
        val observation = TurnCaptureObservation(
            sampleRateHz = sampleRate,
            capturedSamples = totalSamples,
            speechStartSample = speechStartSample,
            speechEndSample = speechEndSample,
            vadOutputAtElapsedRealtimeNanos = vadOutputAtElapsedRealtimeNanos,
            additionalUtteranceDetected = false,
            endReason = endReason,
            endpointDetectionQuantizationMs = endpointDetectionQuantizationMs,
            candidateEndpointAtMs = candidateEndpointAtMs,
            speechResumedAtMs = speechResumedAtMs,
            speechResumedAfterCandidateMs = speechResumedAfterCandidateMs,
            candidateCancelledAtMs = candidateCancelledAtMs,
            endpointCommittedAtMs = endpointCommittedAtMs,
            candidateEndpointRollbackCount = candidateRollbackCount,
            candidateRecognizerIds = candidateRecognizerIds,
            candidatePartialTextsRaw = candidatePartialTextsRaw,
            committedCandidateRecognizerId = committedCandidateRecognizerId,
            committedCandidatePartialTextRaw = committedCandidatePartialTextRaw,
            vadFrameProcessingLagMs = maxVadFrameProcessingLagMs,
            committedOnEarlyEndpointEvidence = committedOnEarlyEndpointEvidence,
            committedEndpointGraceMs = committedEndpointGraceMs,
            committedCandidateSegmentClosed = committedCandidateSegmentClosed,
            committedCandidateRecheckAtMs = committedCandidateRecheckAtMs,
            lastSpeechSample = lastSpeechSample,
            speechEndToCommitWallClockMs = speechEndToCommitWallClockMs,
            recognitionTrailingSilenceSkippedSamples = recognitionTrailingSilenceSkippedSamples,
        )
        logTurnCapture(observation)
        onTurnCaptured?.invoke(observation)
        if (cancelled.get()) {
            return AppResult.Failure(AppError("AUDIO_CANCELLED", "采集已停止"))
        }
        val pcm = capturedPcm.toShortArray()
        return AppResult.Success(
            CapturedAudio(
                pcm16 = pcm,
                sampleRateHz = sampleRate,
                durationMillis = samplesToMillis(totalSamples, sampleRate),
                recordingPath = null,
                transcriptHint = null,
                speechDetected = speechDetected,
            ),
        )
    }

    override suspend fun cancel() {
        cancelled.set(true)
    }

    override suspend fun release() {
        cancelled.set(true)
        _state.value = AudioState.Idle
    }

    private fun subframeSamples(sampleRateHz: Int): Int =
        (sampleRateHz * VAD_SUBFRAME_DURATION_MS / 1_000L).toInt().coerceAtLeast(1)

    private fun recognitionChunkSamples(sampleRateHz: Int): Int =
        subframeSamples(sampleRateHz) *
            (recognitionChunkDurationMs / VAD_SUBFRAME_DURATION_MS).toInt()

    private fun logTurnCapture(observation: TurnCaptureObservation) {
        fun durationMillis(samples: Int): Long =
            samples.toLong() * 1_000L / observation.sampleRateHz.coerceAtLeast(1)

        val capturedMillis = durationMillis(observation.capturedSamples)
        val speechEndToCommitMillis = if (
            observation.lastSpeechSample != null && observation.endpointCommittedAtMs != null
        ) {
            observation.endpointCommittedAtMs -
                durationMillis(observation.lastSpeechSample)
        } else {
            null
        }
        val skippedTrailingSilenceMillis =
            durationMillis(observation.recognitionTrailingSilenceSkippedSamples)
        runCatching {
            android.util.Log.i(
                "TurnEndpoint",
                "endpoint: reason=${observation.endReason} captured=${capturedMillis}ms " +
                    "candidates=${observation.candidateEndpointAtMs.size} " +
                    "rollbacks=${observation.candidateEndpointRollbackCount} " +
                    "grace=${observation.committedEndpointGraceMs ?: -1}ms " +
                    "earlyEvidence=${observation.committedOnEarlyEndpointEvidence} " +
                    "speechEndToCommit=${speechEndToCommitMillis ?: -1}ms " +
                    "speechEndToCommitWall=${observation.speechEndToCommitWallClockMs ?: -1}ms " +
                    "asrTrailingSilenceSkipped=${skippedTrailingSilenceMillis}ms " +
                    "quantization=${observation.endpointDetectionQuantizationMs ?: -1}ms " +
                    "vadLagMax=${observation.vadFrameProcessingLagMs ?: -1}ms " +
                    "segmentClosed=${observation.committedCandidateSegmentClosed ?: "no-snapshot"} " +
                    "recheckAt=${observation.committedCandidateRecheckAtMs ?: -1}ms " +
                    "partialChars=${observation.committedCandidatePartialTextRaw?.length ?: -1}",
            )
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT_GRACE_MS = 500L

        /**
         * Short grace applied only on positive completeness evidence. 150 ms still clears every
         * intra-sentence pause observed on device so far (max 140 ms across 27 spam_risk rollbacks),
         * and the long window remains the fallback for everything without evidence -- including the
         * 400 ms delivery_008 pause, which no evidence rule is expected to shorten.
         */
        const val DEFAULT_EARLY_ENDPOINT_GRACE_MS = 150L

        /** Late enough that the recognizer's own delay can have elapsed, early enough to still save. */
        const val DEFAULT_ENDPOINT_RECHECK_MS = 480L
        const val DEFAULT_MAX_CANDIDATE_ROLLBACK_COUNT = 5
        const val DEFAULT_RECOGNITION_CHUNK_DURATION_MS = 20L

        private const val DEFAULT_SAMPLE_RATE = 16_000
        private const val TIMEOUT_GRACE_MS = 500L
        private const val VAD_SUBFRAME_DURATION_MS = 20L
        private val SUPPORTED_RECOGNITION_CHUNK_DURATIONS_MS = setOf(20L, 40L, 80L, 120L, 160L)
    }
}

internal class GrowingPcm16Buffer(initialCapacity: Int) {
    private var samples = ShortArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun appendLittleEndian(
        source: ByteArray,
        sourceSampleOffset: Int,
        sampleCount: Int,
    ) {
        require(sourceSampleOffset >= 0)
        require(sampleCount >= 0)
        val sourceEndSample = sourceSampleOffset.toLong() + sampleCount
        require(sourceEndSample * Short.SIZE_BYTES <= source.size.toLong())
        require(sampleCount <= Int.MAX_VALUE - size)
        ensureCapacity(size + sampleCount)

        var sourceIndex = sourceSampleOffset
        val sourceEndIndex = sourceSampleOffset + sampleCount
        while (sourceIndex < sourceEndIndex) {
            val byteIndex = sourceIndex * Short.SIZE_BYTES
            val low = source[byteIndex].toInt() and 0xff
            val high = source[byteIndex + 1].toInt()
            samples[size] = ((high shl 8) or low).toShort()
            size += 1
            sourceIndex += 1
        }
    }

    fun copyInto(
        destination: ShortArray,
        destinationOffset: Int,
        sourceOffset: Int,
        sampleCount: Int,
    ) {
        require(sourceOffset in 0..size)
        require(sampleCount >= 0 && sampleCount <= size - sourceOffset)
        samples.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = sourceOffset,
            endIndex = sourceOffset + sampleCount,
        )
    }

    fun toShortArray(): ShortArray = samples.copyOf(size)

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= samples.size) return
        val doubledCapacity = (samples.size.toLong() * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        samples = samples.copyOf(maxOf(requiredCapacity, doubledCapacity))
    }
}
