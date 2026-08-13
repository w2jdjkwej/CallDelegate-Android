package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioCaptureResult
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CaptureDiagnostics
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.PcmAudioFrame
import com.example.calldelegate.domain.api.VadDecision
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.Executors

class StreamingTurnAudioInputSourceTest {
    @Test
    fun growingPcm16Buffer_decodesLittleEndianSamplesAcrossGrowth() {
        val buffer = GrowingPcm16Buffer(initialCapacity = 1)
        val bytes = byteArrayOf(
            0x34, 0x12,
            0x00, 0x80.toByte(),
            0xff.toByte(), 0x7f,
        )

        buffer.appendLittleEndian(bytes, sourceSampleOffset = 0, sampleCount = 2)
        buffer.appendLittleEndian(bytes, sourceSampleOffset = 2, sampleCount = 1)

        assertThat(buffer.toShortArray().toList()).containsExactly(
            0x1234.toShort(), Short.MIN_VALUE, Short.MAX_VALUE,
        ).inOrder()
    }

    @Test
    fun capture_usesInjectedFrameProcessingDispatcher() {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-vad-processing")
        }.asCoroutineDispatcher()
        dispatcher.use {
            var vadThreadName: String? = null
            var observation: TurnCaptureObservation? = null
            val vad = object : VoiceActivityDetector {
                override fun reset() = Unit
                override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
                    vadThreadName = Thread.currentThread().name
                    return VadDecision(speechDetected = true, endOfSpeech = false, probability = 1f)
                }
            }
            val source = FakeCallAudioSource(
                listOf(
                    pcmFrame(ShortArray(320)).copy(emittedAtElapsedRealtimeNanos = 1_000_000L),
                ),
            )
            val adapter = StreamingTurnAudioInputSource(
                source = source,
                vad = vad,
                frameProcessingDispatcher = dispatcher,
                nowElapsedRealtimeNanos = { 6_000_000L },
                onTurnCaptured = { observation = it },
            )

            runBlocking { adapter.capture(CaptureRequest("thread-test", maxDurationMillis = 0L)) }

            assertThat(vadThreadName).startsWith("test-vad-processing")
            assertThat(observation?.vadFrameProcessingLagMs).isEqualTo(5L)
        }
    }

    private class FakeVad(
        private val endOfSpeechAtAccept: Int = Int.MAX_VALUE,
        private val speech: Boolean = true,
    ) : VoiceActivityDetector {
        private var accepts = 0
        override fun reset() { accepts = 0 }
        override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
            accepts++
            return VadDecision(speechDetected = speech, endOfSpeech = accepts >= endOfSpeechAtAccept, probability = 1f)
        }
    }

    private class FakeCallAudioSource(
        private val frames: List<PcmAudioFrame>,
        private val delayBeforeFirstFrameMillis: Long = 0L,
    ) : CallAudioSource {
        override val audioFrames: Flow<PcmAudioFrame> = flow {
            if (delayBeforeFirstFrameMillis > 0L) delay(delayBeforeFirstFrameMillis)
            frames.forEach { emit(it) }
        }
        override suspend fun start(callId: String) = AppResult.Success(Unit)
        override suspend fun stop(callId: String) = AppResult.Success(
            AudioCaptureResult(
                callId = callId,
                wavPath = null,
                durationMs = 0,
                totalBytes = 0,
                provenance = CaptureProvenance.UNKNOWN,
                diagnostics = CaptureDiagnostics("FAKE", true, 0, 0.0, 0, 0.0, 0),
            ),
        )
    }

    private fun frame(vararg samples: Int, sampleRate: Int = 16_000): PcmAudioFrame {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = (s and 0xff).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return PcmAudioFrame("call", bytes, sampleRate, 1, i.toLong())
    }

    private var i = 0

    private fun toneFrame(samplesPerFrame: Int, value: Int = 1_000): PcmAudioFrame =
        frame(*IntArray(samplesPerFrame) { value })

    private fun pcmFrame(samples: ShortArray): PcmAudioFrame {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
        }
        return PcmAudioFrame("call", bytes, 16_000, 1, i.toLong())
    }

    @Test
    fun collectsUntilVadEndOfSpeech() = runBlocking {
        val frames = List(5) { toneFrame(320) }
        val source = FakeCallAudioSource(frames)
        val adapter = StreamingTurnAudioInputSource(
            source,
            FakeVad(endOfSpeechAtAccept = 3),
            endpointGraceMs = 0L,
        )

        val result = adapter.capture(CaptureRequest("s", maxDurationMillis = 30_000)) as AppResult.Success
        val audio = result.value
        assertThat(audio.pcm16.size).isEqualTo(3 * 320)
        assertThat(audio.speechDetected).isTrue()
        assertThat(audio.sampleRateHz).isEqualTo(16_000)
        assertThat(audio.durationMillis).isEqualTo(3 * 320 * 1_000L / 16_000)
    }

    @Test
    fun stopsAtMaxDurationCap() = runBlocking {
        val frames = List(10) { toneFrame(160) }
        val source = FakeCallAudioSource(frames)
        // 10 ms budget -> 160 samples cap -> stops after the first 160-sample frame.
        val adapter = StreamingTurnAudioInputSource(source, FakeVad(endOfSpeechAtAccept = Int.MAX_VALUE))

        val result = adapter.capture(CaptureRequest("s", maxDurationMillis = 10)) as AppResult.Success
        assertThat(result.value.pcm16.size).isEqualTo(160)
    }

    @Test
    fun zeroMaxDurationWaitsForTheStreamInsteadOfUsingTheGracePeriodAsATimeout() = runBlocking {
        val source = FakeCallAudioSource(
            frames = listOf(toneFrame(160)),
            delayBeforeFirstFrameMillis = 300L,
        )
        val adapter = StreamingTurnAudioInputSource(source, FakeVad(endOfSpeechAtAccept = 1))

        val result = adapter.capture(CaptureRequest("unlimited", maxDurationMillis = 0)) as AppResult.Success

        assertThat(result.value.pcm16).hasLength(160)
    }

    @Test
    fun returnsAllAudioWhenStreamEndsBeforeVadBoundary() = runBlocking {
        val frames = List(4) { toneFrame(160) }
        val source = FakeCallAudioSource(frames)
        val adapter = StreamingTurnAudioInputSource(source, FakeVad(endOfSpeechAtAccept = Int.MAX_VALUE))

        val result = adapter.capture(CaptureRequest("s", maxDurationMillis = 30_000)) as AppResult.Success
        assertThat(result.value.pcm16.size).isEqualTo(4 * 160)
    }

    @Test
    fun captureStreamingForwardsImmutableFramesInOrder() = runBlocking {
        val first = ShortArray(320) { index -> index.toShort() }
        val second = ShortArray(320) { index -> (index + 320).toShort() }
        val adapter = StreamingTurnAudioInputSource(
            FakeCallAudioSource(listOf(pcmFrame(first), pcmFrame(second))),
            FakeVad(endOfSpeechAtAccept = Int.MAX_VALUE),
        )
        val streamed = ArrayList<List<Short>>()

        val result = adapter.captureStreaming(CaptureRequest("stream", 30_000)) { samples, sampleRate ->
            assertThat(sampleRate).isEqualTo(16_000)
            streamed += samples.toList()
            AppResult.Success(Unit)
        }

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(streamed).containsExactly(first.toList(), second.toList()).inOrder()
    }

    @Test
    fun captureStreamingGroupsRecognitionFramesWithoutChangingVadFramesOrPcm() = runBlocking {
        val sourcePcm = ShortArray(10 * 320) { index -> index.toShort() }
        val expectedChunkLengths = mapOf(
            40L to listOf(640, 640, 640, 640, 640),
            80L to listOf(1_280, 1_280, 640),
            120L to listOf(1_920, 1_280),
            160L to listOf(2_560, 640),
        )

        for ((chunkDurationMs, chunkLengths) in expectedChunkLengths) {
            val vadFrameLengths = ArrayList<Int>()
            val vad = object : VoiceActivityDetector {
                override fun reset() = Unit
                override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
                    vadFrameLengths += samples.size
                    return VadDecision(speechDetected = true, endOfSpeech = false, probability = 1f)
                }
            }
            val frames = sourcePcm.toList().chunked(320).map { samples ->
                pcmFrame(samples.toShortArray())
            }
            val streamedFrames = ArrayList<ShortArray>()
            val adapter = StreamingTurnAudioInputSource(
                source = FakeCallAudioSource(frames),
                vad = vad,
                recognitionChunkDurationMs = chunkDurationMs,
            )

            val result = adapter.captureStreaming(CaptureRequest("chunk-$chunkDurationMs", 0L)) { samples, _ ->
                streamedFrames += samples
                AppResult.Success(Unit)
            } as AppResult.Success

            assertThat(vadFrameLengths).containsExactlyElementsIn(List(10) { 320 }).inOrder()
            assertThat(streamedFrames.map { it.size }).containsExactlyElementsIn(chunkLengths).inOrder()
            assertThat(streamedFrames.flatMap { it.toList() })
                .containsExactlyElementsIn(sourcePcm.toList())
                .inOrder()
            assertThat(result.value.pcm16.toList()).containsExactlyElementsIn(sourcePcm.toList()).inOrder()
        }
    }

    @Test
    fun captureStreamingFlushesPartialRecognitionChunkBeforeEndpointSnapshot() = runBlocking {
        val frames = List(4) { toneFrame(320) }
        val streamedFrames = ArrayList<ShortArray>()
        var samplesVisibleAtSnapshot = 0
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(frames),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                    VadDecision(false, true, 0f),
                ),
            ),
            endpointGraceMs = 40L,
            earlyEndpointGraceMs = 20L,
            recognitionChunkDurationMs = 160L,
        )

        val result = adapter.captureStreaming(
            request = CaptureRequest("snapshot-flush", 30_000L),
            onFrame = { samples, _ ->
                streamedFrames += samples
                AppResult.Success(Unit)
            },
            onEndpointCandidate = {
                samplesVisibleAtSnapshot = streamedFrames.sumOf { it.size }
                null
            },
        ) as AppResult.Success

        assertThat(samplesVisibleAtSnapshot).isEqualTo(3 * 320)
        assertThat(streamedFrames.map { it.size }).containsExactly(3 * 320)
        assertThat(result.value.pcm16).hasLength(4 * 320)
    }

    @Test
    fun endpointGraceChangesCommitTimingWithoutChangingPcmPrefix() = runBlocking {
        suspend fun capture(graceMs: Long): Pair<CapturedAudio, TurnCaptureObservation?> {
            var observation: TurnCaptureObservation? = null
            val frames = listOf(1, 2, 3, 4, 5).map { value ->
                pcmFrame(ShortArray(320) { value.toShort() })
            }
            val adapter = StreamingTurnAudioInputSource(
                source = FakeCallAudioSource(frames),
                vad = SequenceVad(
                    listOf(
                        VadDecision(true, false, 1f),
                        VadDecision(false, true, 0f),
                        VadDecision(false, true, 0f),
                        VadDecision(false, true, 0f),
                    ),
                ),
                endpointGraceMs = graceMs,
                onTurnCaptured = { observation = it },
            )
            val result = adapter.capture(CaptureRequest("grace-$graceMs", 30_000L))
            return@capture (result as AppResult.Success).value to observation
        }

        val immediate = capture(graceMs = 0L)
        val delayed = capture(graceMs = 40L)

        assertThat(immediate.second?.candidateEndpointAtMs).containsExactly(40L)
        assertThat(delayed.second?.candidateEndpointAtMs).containsExactly(40L)
        assertThat(immediate.second?.endpointCommittedAtMs).isEqualTo(40L)
        assertThat(delayed.second?.endpointCommittedAtMs).isEqualTo(80L)
        assertThat(delayed.first.pcm16.copyOf(immediate.first.pcm16.size).toList())
            .containsExactlyElementsIn(immediate.first.pcm16.toList())
            .inOrder()
    }

    @Test
    fun rejectsUnsupportedRecognitionChunkDuration() {
        val error = runCatching {
            StreamingTurnAudioInputSource(
                source = FakeCallAudioSource(emptyList()),
                vad = FakeVad(),
                recognitionChunkDurationMs = 60L,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun captureStreamingPropagatesFrameConsumerFailure() = runBlocking {
        val adapter = StreamingTurnAudioInputSource(
            FakeCallAudioSource(listOf(frame(1, 2), frame(3, 4))),
            FakeVad(endOfSpeechAtAccept = Int.MAX_VALUE),
        )

        val result = adapter.captureStreaming(CaptureRequest("stream", 30_000)) { _, _ ->
            AppResult.Failure(AppError("STREAM_FAILED", "stream failed"))
        }

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("STREAM_FAILED")
    }

    @Test
    fun convertsLittleEndianBytesToShorts() = runBlocking {
        // 0x2710 = 10000 ; 0x8000 = -32768
        val f = frame(10_000, -32768)
        val source = FakeCallAudioSource(listOf(f))
        val adapter = StreamingTurnAudioInputSource(source, FakeVad(endOfSpeechAtAccept = 1))

        val result = adapter.capture(CaptureRequest("s", maxDurationMillis = 30_000)) as AppResult.Success
        assertThat(result.value.pcm16.toList()).containsExactly(10_000.toShort(), (-32768).toShort()).inOrder()
    }

    @Test
    fun reportsExistingVadBoundariesWithoutChangingCapturedAudio() = runBlocking {
        val frames = List(4) { toneFrame(320) }
        var observation: TurnCaptureObservation? = null
        val source = FakeCallAudioSource(frames)
        val adapter = StreamingTurnAudioInputSource(
            source = source,
            vad = FakeVad(endOfSpeechAtAccept = 3),
            onTurnCaptured = { observation = it },
            nowElapsedRealtimeNanos = { 123L },
            endpointGraceMs = 0L,
        )

        val result = adapter.capture(CaptureRequest("s", maxDurationMillis = 30_000)) as AppResult.Success

        assertThat(result.value.pcm16.size).isEqualTo(3 * 320)
        assertThat(observation).isEqualTo(
            TurnCaptureObservation(
                sampleRateHz = 16_000,
                capturedSamples = 3 * 320,
                speechStartSample = 0,
                speechEndSample = 3 * 320,
                vadOutputAtElapsedRealtimeNanos = 123L,
                additionalUtteranceDetected = false,
                endReason = TurnCaptureEndReason.VAD_ENDPOINT,
                endpointDetectionQuantizationMs = 0L,
                candidateEndpointAtMs = listOf(60L),
                endpointCommittedAtMs = 60L,
                candidateRecognizerIds = emptyList(),
                candidatePartialTextsRaw = emptyList(),
                committedEndpointGraceMs = 0L,
                lastSpeechSample = 3 * 320,
            ),
        )
    }

    @Test
    fun reportsLastSpeechSeparatelyFromTheCommittedEndpoint() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val frames = listOf(10L, 30L, 50L, 70L).mapIndexed { index, emittedAtMs ->
            pcmFrame(ShortArray(320) { (index + 1).toShort() }).copy(
                emittedAtElapsedRealtimeNanos = emittedAtMs * 1_000_000L,
            )
        }
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(frames),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                    VadDecision(false, true, 0f),
                    VadDecision(false, true, 0f),
                ),
            ),
            onTurnCaptured = { observation = it },
            nowElapsedRealtimeNanos = { 100_000_000L },
            endpointGraceMs = 40L,
        )

        val result = adapter.capture(CaptureRequest("speech-end-telemetry", 30_000L)) as AppResult.Success

        assertThat(result.value.pcm16).hasLength(4 * 320)
        assertThat(observation?.lastSpeechSample).isEqualTo(320)
        // Keep the existing committed endpoint field stable for WAV evaluation consumers.
        assertThat(observation?.speechEndSample).isEqualTo(4 * 320)
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(80L)
        assertThat(observation?.speechEndToCommitWallClockMs).isEqualTo(90L)
        assertThat(observation?.recognitionTrailingSilenceSkippedSamples).isEqualTo(2 * 320)
    }

    @Test
    fun reportsNoSpeechWhenExistingVadFindsNoSpeech() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(List(2) { toneFrame(160) }),
            vad = FakeVad(speech = false),
            onTurnCaptured = { observation = it },
        )

        val result = adapter.capture(CaptureRequest("no-speech", maxDurationMillis = 30_000)) as AppResult.Success

        assertThat(result.value.speechDetected).isFalse()
        assertThat(observation?.speechStartSample).isNull()
        assertThat(observation?.endReason).isEqualTo(TurnCaptureEndReason.STREAM_ENDED)
    }

    @Test
    fun rollsBackCandidateAndKeepsTheSameTurnWhenSpeechResumes() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val forwarded = ArrayList<Short>()
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(List(6) { toneFrame(320) }),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                    VadDecision(false, true, 0f),
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                    VadDecision(false, true, 0f),
                ),
            ),
            inspectRemainingFramesForAdditionalTurns = true,
            onTurnCaptured = { observation = it },
            endpointGraceMs = 40L,
        )

        val result = adapter.captureStreaming(CaptureRequest("rollback", maxDurationMillis = 30_000), { samples, _ ->
            forwarded += samples.toList()
            AppResult.Success(Unit)
        }, {
            com.example.calldelegate.domain.api.StreamingRecognitionSnapshot("one-recognizer", "前半句")
        }) as AppResult.Success

        assertThat(result.value.pcm16).hasLength(6 * 320)
        // The first candidate's held frame is replayed on rollback. Only the final committed
        // candidate's one trailing-silence frame is omitted from recognition.
        assertThat(forwarded).hasSize(5 * 320)
        assertThat(forwarded)
            .containsExactlyElementsIn(result.value.pcm16.copyOf(5 * 320).toList())
            .inOrder()
        assertThat(observation?.candidateEndpointRollbackCount).isEqualTo(1)
        assertThat(observation?.recognitionTrailingSilenceSkippedSamples).isEqualTo(320)
        assertThat(observation?.speechResumedAfterCandidateMs).containsExactly(20L)
        // Neither candidate survived to the 40 ms snapshot point: the first rolled back and the
        // preset stream ended during the second. No recognizer call is needed for either one.
        assertThat(observation?.candidateRecognizerIds).isEmpty()
        assertThat(observation?.additionalUtteranceDetected).isFalse()
        assertThat(observation?.endReason).isEqualTo(TurnCaptureEndReason.VAD_ENDPOINT)
    }

    /** 20 ms/frame, so a candidate raised at frame 2 commits after `graceMs / 20` silent frames. */
    private fun earlyCommitAdapter(
        looksComplete: (com.example.calldelegate.domain.api.StreamingRecognitionSnapshot?) -> Boolean,
        onTurnCaptured: (TurnCaptureObservation) -> Unit,
        earlyGraceMs: Long = 40L,
    ) = StreamingTurnAudioInputSource(
        source = FakeCallAudioSource(List(8) { toneFrame(320) }),
        vad = SequenceVad(
            listOf(
                VadDecision(true, false, 1f),
                VadDecision(false, true, 0f),
            ),
        ),
        onTurnCaptured = onTurnCaptured,
        endpointGraceMs = 120L,
        earlyEndpointGraceMs = earlyGraceMs,
        utteranceLooksComplete = looksComplete,
    )

    @Test
    fun withoutCompletenessEvidenceTheLongGraceStillDecidesTheCommit() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val forwarded = mutableListOf<Short>()
        val adapter = earlyCommitAdapter({ false }, { observation = it })

        val result = adapter.captureStreaming(CaptureRequest("no-evidence", maxDurationMillis = 30_000), { samples, _ ->
            forwarded += samples.toList()
            AppResult.Success(Unit)
        }, {
            com.example.calldelegate.domain.api.StreamingRecognitionSnapshot("r", "我先把东西放在门口然后")
        }) as AppResult.Success

        assertThat(observation?.committedOnEarlyEndpointEvidence).isFalse()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(120L)
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(160L)
        assertThat(result.value.pcm16).hasLength(8 * 320)
        assertThat(forwarded).hasSize(2 * 320)
        assertThat(observation?.recognitionTrailingSilenceSkippedSamples).isEqualTo(6 * 320)
    }

    @Test
    fun completenessEvidenceCommitsOnTheShortGraceWithoutDroppingPcm() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val forwarded = mutableListOf<Short>()
        val adapter = earlyCommitAdapter({ true }, { observation = it })

        val result = adapter.captureStreaming(
            CaptureRequest("evidence", maxDurationMillis = 30_000),
            { samples, _ -> forwarded += samples.toList(); AppResult.Success(Unit) },
            { com.example.calldelegate.domain.api.StreamingRecognitionSnapshot("r", "你现在方便接电话吗") },
        ) as AppResult.Success

        assertThat(observation?.committedOnEarlyEndpointEvidence).isTrue()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(40L)
        // Commits 80 ms earlier than the long window. Captured PCM stays complete, while the two
        // confirmed trailing-silence frames after the candidate do not consume Vosk CPU.
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(80L)
        assertThat(result.value.pcm16).hasLength(4 * 320)
        assertThat(forwarded).hasSize(2 * 320)
        assertThat(observation?.recognitionTrailingSilenceSkippedSamples).isEqualTo(2 * 320)
    }

    @Test
    fun aSnapshotThatHasNotComeBackYetDoesNotHoldUpTheEndpoint() = runBlocking {
        // The recognizer running behind real time is the normal case, not the exception: on the
        // 2026-08-09 21:20 call it decoded at 1.13x and 1.84x, so an answer arrived only after
        // every queued frame had been decoded. Awaiting it here used to stop VAD as well, and the
        // grace window then elapsed 913 ms and 1,268 ms later in wall clock than in audio.
        val neverAnswers = CompletableDeferred<com.example.calldelegate.domain.api.StreamingRecognitionSnapshot?>()
        var observation: TurnCaptureObservation? = null
        val adapter = earlyCommitAdapter({ true }, { observation = it })

        // The timeout is the assertion: a blocking wait would sit here until the deferred that
        // nothing completes, which is what the capture loop used to do.
        val result = withTimeout(5_000L) {
            adapter.captureStreaming(
                CaptureRequest("slow-snapshot", maxDurationMillis = 30_000),
                { _, _ -> AppResult.Success(Unit) },
                { neverAnswers.await() },
            )
        } as AppResult.Success

        // No evidence arrived, so the long grace decided it -- the same commit the turn would have
        // reached had the recognizer never been asked at all.
        assertThat(observation?.committedOnEarlyEndpointEvidence).isFalse()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(120L)
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(160L)
        assertThat(observation?.committedCandidateRecheckAtMs).isNull()
        // VAD kept advancing through the whole window rather than stalling on the answer.
        assertThat(result.value.pcm16).hasLength(8 * 320)
    }

    @Test
    fun anAnswerArrivingLateIsStillUsedAndReportsWhenItLanded() = runBlocking {
        val answer = CompletableDeferred<com.example.calldelegate.domain.api.StreamingRecognitionSnapshot?>()
        var observation: TurnCaptureObservation? = null
        val adapter = earlyCommitAdapter({ true }, { observation = it })

        val result = withTimeout(5_000L) {
            adapter.captureStreaming(
                CaptureRequest("late-snapshot", maxDurationMillis = 30_000),
                { _, _ ->
                    // Released while the grace window is still open, standing in for a recognizer
                    // that catches up part way through it.
                    answer.complete(
                        com.example.calldelegate.domain.api.StreamingRecognitionSnapshot("r", "你现在方便接电话吗"),
                    )
                    AppResult.Success(Unit)
                },
                { answer.await() },
            )
        } as AppResult.Success

        assertThat(observation?.committedOnEarlyEndpointEvidence).isTrue()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(40L)
        // Reported against the grace window, so a batch can read straight off it how far behind the
        // caller the recognizer was when its evidence finally landed.
        assertThat(observation?.committedCandidateRecheckAtMs).isNotNull()
        assertThat(result.value.pcm16).isNotEmpty()
    }

    @Test
    fun anIncompleteEarlySnapshotCanBecomeCompleteAtTheSingleLateRecheck() = runBlocking {
        var observation: TurnCaptureObservation? = null
        var snapshotCount = 0
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(List(10) { toneFrame(320) }),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                ),
            ),
            onTurnCaptured = { observation = it },
            endpointGraceMs = 140L,
            earlyEndpointGraceMs = 40L,
            endpointRecheckMs = 80L,
            utteranceLooksComplete = { snapshot -> snapshot?.partialTextRaw == "已经说完" },
        )

        adapter.captureStreaming(
            CaptureRequest("late-evidence", maxDurationMillis = 30_000),
            { _, _ -> AppResult.Success(Unit) },
            {
                snapshotCount += 1
                val partial = if (snapshotCount == 1) "还没" else "已经说完"
                com.example.calldelegate.domain.api.StreamingRecognitionSnapshot("r", partial)
            },
        )

        assertThat(snapshotCount).isEqualTo(2)
        assertThat(observation?.committedOnEarlyEndpointEvidence).isTrue()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(40L)
        assertThat(observation?.committedCandidateRecheckAtMs).isEqualTo(80L)
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(120L)
    }

    @Test
    fun aThrowingCompletenessJudgeFallsBackToTheLongGrace() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val adapter = earlyCommitAdapter({ error("judge exploded") }, { observation = it })

        adapter.captureStreaming(CaptureRequest("throwing", maxDurationMillis = 30_000), { _, _ ->
            AppResult.Success(Unit)
        }, {
            com.example.calldelegate.domain.api.StreamingRecognitionSnapshot("r", "你现在方便接电话吗")
        })

        assertThat(observation?.committedOnEarlyEndpointEvidence).isFalse()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(120L)
    }

    @Test
    fun anUnconfiguredSourceKeepsTodaysSingleGraceWindow() = runBlocking {
        // Guards the rollout: constructing without the new parameters must not change any timing.
        var observation: TurnCaptureObservation? = null
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(List(8) { toneFrame(320) }),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                ),
            ),
            onTurnCaptured = { observation = it },
            endpointGraceMs = 120L,
        )

        adapter.capture(CaptureRequest("default", maxDurationMillis = 30_000))

        assertThat(observation?.committedOnEarlyEndpointEvidence).isFalse()
        assertThat(observation?.committedEndpointGraceMs).isEqualTo(120L)
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(160L)
    }

    @Test
    fun commitsNextCandidateAfterMaximumRollbackCount() = runBlocking {
        var observation: TurnCaptureObservation? = null
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(List(7) { toneFrame(320) }),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                    VadDecision(true, false, 1f),
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                ),
            ),
            onTurnCaptured = { observation = it },
            endpointGraceMs = 100L,
            maxCandidateRollbackCount = 1,
        )

        val result = adapter.capture(CaptureRequest("rollback-limit", 30_000)) as AppResult.Success

        assertThat(result.value.pcm16).hasLength(5 * 320)
        assertThat(observation?.candidateEndpointRollbackCount).isEqualTo(1)
        assertThat(observation?.candidateEndpointAtMs).containsExactly(40L, 100L).inOrder()
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(100L)
    }

    @Test
    fun maxTurnDurationStillStopsACandidateThatNeverCommits() = runBlocking {
        val adapter = StreamingTurnAudioInputSource(
            source = FakeCallAudioSource(List(10) { toneFrame(320) }),
            vad = SequenceVad(
                listOf(
                    VadDecision(true, false, 1f),
                    VadDecision(false, true, 0f),
                ),
                fallback = VadDecision(false, true, 0f),
            ),
            endpointGraceMs = 5_000L,
        )

        val result = adapter.capture(CaptureRequest("duration-limit", 60L)) as AppResult.Success

        assertThat(result.value.pcm16).hasLength(3 * 320)
    }

    @Test
    fun normalizesVariableInputBlocksToStableTwentyMillisecondVadAndAsrFrames() = runBlocking {
        val speechSamples = 2_880 // 180 ms
        val endpointSamples = speechSamples + 9_600 // 600 ms trailing silence
        val sourcePcm = ShortArray(endpointSamples + 3_200) { index ->
            if (index < speechSamples) 2_000 else 0
        }
        val chunkPatterns = listOf(
            intArrayOf(160),
            intArrayOf(320),
            intArrayOf(640),
            intArrayOf(1_280),
            intArrayOf(1_600),
            intArrayOf(137, 503, 211, 997, 89, 401),
        )

        var expectedVadSequence: List<VadDecision>? = null
        var expectedStreamedPcm: List<Short>? = null
        val quantizationValues = ArrayList<Long?>()
        for (pattern in chunkPatterns) {
            val vad = TwentyMillisecondTestVad()
            val frames = splitIntoFrames(sourcePcm, pattern)
            var observation: TurnCaptureObservation? = null
            val streamedFrames = ArrayList<List<Short>>()
            val adapter = StreamingTurnAudioInputSource(
                source = FakeCallAudioSource(frames),
                vad = vad,
                onTurnCaptured = { observation = it },
                endpointGraceMs = 0L,
            )

            val result = adapter.captureStreaming(CaptureRequest("variable", 30_000)) { samples, sampleRate ->
                assertThat(sampleRate).isEqualTo(16_000)
                assertThat(samples).hasLength(320)
                streamedFrames += samples.toList()
                AppResult.Success(Unit)
            } as AppResult.Success

            assertThat(result.value.pcm16).hasLength(endpointSamples)
            assertThat(observation?.speechStartSample).isEqualTo(0)
            assertThat(observation?.speechEndSample).isEqualTo(endpointSamples)
            assertThat(observation?.lastSpeechSample).isEqualTo(speechSamples)
            assertThat(observation?.recognitionTrailingSilenceSkippedSamples).isEqualTo(0)
            quantizationValues += observation?.endpointDetectionQuantizationMs
            val streamedPcm = streamedFrames.flatten()
            assertThat(streamedPcm).containsExactlyElementsIn(sourcePcm.copyOf(endpointSamples).toList()).inOrder()
            if (expectedVadSequence == null) {
                expectedVadSequence = vad.decisions.toList()
                expectedStreamedPcm = streamedPcm
            } else {
                assertThat(vad.decisions).containsExactlyElementsIn(expectedVadSequence).inOrder()
                assertThat(streamedPcm).containsExactlyElementsIn(expectedStreamedPcm).inOrder()
            }
        }
        assertThat(quantizationValues).containsExactly(0L, 0L, 20L, 20L, 20L, 3L).inOrder()
    }

    private fun splitIntoFrames(samples: ShortArray, chunkPattern: IntArray): List<PcmAudioFrame> {
        val frames = ArrayList<PcmAudioFrame>()
        var offset = 0
        var patternIndex = 0
        while (offset < samples.size) {
            val chunkSize = minOf(chunkPattern[patternIndex % chunkPattern.size], samples.size - offset)
            frames += pcmFrame(samples.copyOfRange(offset, offset + chunkSize))
            offset += chunkSize
            patternIndex += 1
        }
        return frames
    }

    private class TwentyMillisecondTestVad : VoiceActivityDetector {
        val decisions = ArrayList<VadDecision>()
        private var seenSpeech = false
        private var silentSubframes = 0

        override fun reset() {
            seenSpeech = false
            silentSubframes = 0
            decisions.clear()
        }

        override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
            check(sampleRateHz == 16_000)
            check(samples.size == 320)
            val speech = samples.any { it.toInt() != 0 }
            if (speech) {
                seenSpeech = true
                silentSubframes = 0
            } else {
                silentSubframes += 1
            }
            val decision = VadDecision(
                speechDetected = speech,
                endOfSpeech = seenSpeech && silentSubframes >= 30,
                probability = if (speech) 1f else 0f,
            )
            decisions += decision
            return decision
        }
    }

    private class SequenceVad(
        private val decisions: List<VadDecision>,
        private val fallback: VadDecision = decisions.last(),
    ) : VoiceActivityDetector {
        private var index = 0

        override fun reset() {
            index = 0
        }

        override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
            val decision = decisions.getOrNull(index) ?: fallback
            index += 1
            return decision
        }
    }
}
