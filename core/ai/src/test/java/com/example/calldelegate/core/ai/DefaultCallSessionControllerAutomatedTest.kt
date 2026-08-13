package com.example.calldelegate.core.ai

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.PerformanceMonitor
import com.example.calldelegate.core.ai.speech.DirectSpeechRuntimeManager
import com.example.calldelegate.domain.api.AudioFrameStreamingInputSource
import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.ExternalCallResponseRoute
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.api.RecognitionAttemptsMetricsSource
import com.example.calldelegate.domain.api.RecognitionComputeMetrics
import com.example.calldelegate.domain.api.RecognitionMetricsSource
import com.example.calldelegate.domain.api.SessionRecordingStore
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechRuntimeInitialization
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.StreamingSpeechRecognitionSession
import com.example.calldelegate.domain.api.StreamingSpeechRuntimeManager
import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.DialogueDecision
import com.example.calldelegate.domain.model.HistoryFilter
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.DeviceTier
import com.example.calldelegate.domain.model.ModuleStatus
import com.example.calldelegate.domain.model.NormalizedRecordingAudio
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.model.TranscriptTurn
import com.example.calldelegate.domain.session.SessionPhase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCallSessionControllerAutomatedTest {

    @Test
    fun automatedCallRunsMultipleTurnsThenFinalizes() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 2)

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        // Two caller turns were captured and recognized automatically (no UI calls).
        assertThat(fx.turnAudio.captureCount).isEqualTo(2)
        val saved = fx.repository.saved
        assertThat(saved).isNotNull()
        assertThat(saved!!.status).isEqualTo(CallStatus.COMPLETED)
        assertThat(saved.transcript.count { it.speaker == Speaker.CALLER }).isEqualTo(2)
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.COMPLETED)
        scope.cancel()
    }

    @Test
    fun hangUpDuringCaptureCancelsLoopAndFinalizes() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 99) // never ends on its own
        fx.turnAudio.blockCaptures = true

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()
        // The loop is parked inside capture() waiting for audio.
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.RECORDING)

        fx.controller.end("user_hangup")
        advanceUntilIdle()

        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.COMPLETED)
        assertThat(fx.repository.saved).isNotNull()
        scope.cancel()
    }

    @Test
    fun emptyAsrResultIsHandledAsRecognitionFailure() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1)
        fx.recognizer.text = "   "

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        val saved = fx.repository.saved!!
        assertThat(saved.recognitionFailed).isTrue()
        assertThat(saved.transcript.none { it.speaker == Speaker.CALLER }).isTrue()
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.COMPLETED)
        scope.cancel()
    }

    @Test
    fun aShortCaptureWithNoWordsInItIsNotAnsweredAsATurn() = runTest {
        // In a validation run, the caller paused, the detector opened on the pause and
        // committed 980 ms containing nothing the recognizer could read, and the assistant answered
        // 请回答需要或不需要回电. From the caller's side it interrupted them mid-thought.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1, captureDurationMillis = 980L)
        fx.recognizer.text = "   "

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        // Listening continued rather than the fallback ladder answering a fragment.
        assertThat(fx.turnAudio.captureCount).isAtLeast(2)
        scope.cancel()
    }

    @Test
    fun aShortCaptureThatDidCarryWordsIsStillATurn() = runTest {
        // 不需要 and 没有了 are the answers these states ask for, and both are under a second. The
        // length only ever disqualifies a capture that already came back empty.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1, captureDurationMillis = 800L)
        fx.recognizer.text = "不需要"

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        val saved = fx.repository.saved!!
        assertThat(saved.transcript.any { it.speaker == Speaker.CALLER && it.text == "不需要" }).isTrue()
        assertThat(saved.recognitionFailed).isFalse()
        scope.cancel()
    }

    @Test
    fun ttsFailureStillFinalizesTheSession() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1)
        fx.synthesizer.failure = AppError("TTS_TEST", "tts failed")

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        assertThat(fx.repository.saved).isNotNull()
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.COMPLETED)
        assertThat(fx.controller.state.value.lastError).isEqualTo("tts failed")
        scope.cancel()
    }

    @Test
    fun repositoryFailureMovesSessionToError() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1)
        fx.repository.saveResult = AppResult.Failure(AppError("ROOM_TEST", "save failed"))

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        assertThat(fx.repository.saveCount).isEqualTo(1)
        assertThat(fx.controller.state.value.callStatus).isEqualTo(CallStatus.FAILED)
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.ERROR)
        assertThat(fx.controller.state.value.lastError).isEqualTo("save failed")
        scope.cancel()
    }

    @Test
    fun repeatedEndFinalizesAndSavesOnlyOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 99)
        fx.turnAudio.blockCaptures = true

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        fx.controller.end("first_end")
        fx.controller.end("duplicate_end")
        advanceUntilIdle()

        assertThat(fx.repository.saveCount).isEqualTo(1)
        assertThat(fx.recordingStore.finalizeCount).isEqualTo(1)
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.COMPLETED)
        scope.cancel()
    }

    @Test
    fun externalResponseRouteReceivesTtsWithoutLocalSpeakerFallback() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1)
        val injectedTexts = mutableListOf<String>()
        val sink = object : CallResponseAudioSink {
            override suspend fun playToCall(
                callId: String,
                speech: SynthesizedSpeech,
            ): CallResponseResult {
                assertThat(callId).isEqualTo("call-1")
                injectedTexts += speech.text
                return CallResponseResult.PlayedToCallUplink
            }
        }

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(
            fx.turnAudio,
            ExternalCallResponseRoute("call-1", sink),
        )
        advanceUntilIdle()

        assertThat(injectedTexts).containsExactly("opening", "reply-1").inOrder()
        assertThat(fx.audioOutput.playCount).isEqualTo(0)
        scope.cancel()
    }

    @Test
    fun externalResponseRouteCanMonitorTtsLocallyWhileWritingTheUplink() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1)
        val injectedTexts = mutableListOf<String>()
        val sink = object : CallResponseAudioSink {
            override suspend fun playToCall(
                callId: String,
                speech: SynthesizedSpeech,
            ): CallResponseResult {
                injectedTexts += speech.text
                return CallResponseResult.PlayedToCallUplink
            }
        }

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(
            fx.turnAudio,
            ExternalCallResponseRoute("call-1", sink, monitorLocally = true),
        )
        advanceUntilIdle()

        assertThat(injectedTexts).containsExactly("opening", "reply-1").inOrder()
        assertThat(fx.audioOutput.playCount).isEqualTo(2)
        scope.cancel()
    }

    @Test
    fun automatedCallUsesStreamingPrimaryRecognitionWithoutBatchingTheSameAudio() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1, useStreamingRuntime = true)

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        assertThat(fx.turnAudio.streamingCaptureCount).isEqualTo(1)
        assertThat(fx.streamingRuntime.acceptedSamples).isEqualTo(3)
        assertThat(fx.streamingRuntime.batchRecognitionCount).isEqualTo(0)
        assertThat(fx.repository.saved?.transcript?.any { it.text == "streamed-caller" }).isTrue()
        scope.cancel()
    }

    @Test
    fun streamingRecognitionFailureFallsBackToTheCapturedAudio() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1, useStreamingRuntime = true)
        fx.streamingRuntime.streamingResult = AppResult.Failure(
            AppError("ASR_UNRECOGNIZABLE", "streaming result was empty"),
        )

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        assertThat(fx.streamingRuntime.batchRecognitionCount).isEqualTo(1)
        assertThat(fx.repository.saved?.transcript?.any { it.text == "batch-caller" }).isTrue()
        scope.cancel()
    }

    @Test
    fun reportsAsrNluAndTtsStagesForTheCallerTurn() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 1)

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        val metrics = checkNotNull(fx.controller.latestTurnPipelineMetrics.value)
        assertThat(metrics.asrStartedAtElapsedRealtimeNanos).isEqualTo(1_000_000L)
        assertThat(metrics.asrCompletedAtElapsedRealtimeNanos).isEqualTo(3_000_000L)
        assertThat(metrics.asrComputeDurationMillis).isEqualTo(2L)
        assertThat(
            metrics.nluCompletedAtElapsedRealtimeNanos!! - metrics.nluStartedAtElapsedRealtimeNanos!!,
        ).isEqualTo(1_000_000L)
        assertThat(
            metrics.ttsCompletedAtElapsedRealtimeNanos!! - metrics.ttsStartedAtElapsedRealtimeNanos!!,
        ).isEqualTo(1_000_000L)
        scope.cancel()
    }

    @Test
    fun presetAudioRunsOnlyOneAutomatedTurn() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fx = AutoFixture(scope, dispatcher, endAfterTurns = 2, inputMode = InputMode.PRESET_AUDIO)

        fx.controller.simulateIncoming("caller", "123")
        fx.controller.acceptExternalWithAi(fx.turnAudio)
        advanceUntilIdle()

        assertThat(fx.turnAudio.captureCount).isEqualTo(1)
        assertThat(fx.controller.state.value.phase).isEqualTo(SessionPhase.AWAITING_INPUT)
        scope.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class AutoFixture(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    endAfterTurns: Int,
    useStreamingRuntime: Boolean = false,
    inputMode: InputMode = InputMode.MICROPHONE,
    captureDurationMillis: Long = 3_000L,
) {
    private var elapsedNanos = 20_000_000L
    val repository = AutoRepo()
    val turnAudio = AutoTurnAudio(inputMode, captureDurationMillis)
    val recognizer = AutoRecognizer()
    val synthesizer = AutoSynth()
    val streamingRuntime = AutoStreamingRuntime(synthesizer)
    val recordingStore = AutoStore()
    val audioOutput = AutoOutput()

    val controller = DefaultCallSessionController(
        dialogueEngine = AutoDialogue(endAfterTurns),
        recognizer = recognizer,
        synthesizer = synthesizer,
        summaryGenerator = AutoSummary(),
        audioInputs = object : AudioInputRegistry {
            override fun sourceFor(mode: InputMode): AudioInputSource? = null
        },
        audioOutput = audioOutput,
        recordingStore = recordingStore,
        recordingAudioNormalizer = AutoNormalizer(),
        calls = repository,
        settings = AutoSettings(),
        takeover = DefaultHumanTakeoverController(),
        clock = Clock { 1_000L },
        performanceMonitor = PerformanceMonitor(),
        speechRuntime = if (useStreamingRuntime) {
            streamingRuntime
        } else {
            DirectSpeechRuntimeManager(recognizer, synthesizer)
        },
        sessionScope = scope,
        workDispatcher = dispatcher,
        elapsedRealtimeNanos = {
            val current = elapsedNanos
            elapsedNanos += 1_000_000L
            current
        },
    )
}

private class AutoTurnAudio(
    override val mode: InputMode = InputMode.MICROPHONE,
    // Long enough to be a turn somebody took. The controller discards a capture that both ran
    // short and recognized to nothing, so a fake reporting one millisecond would silently opt
    // every test into that path instead of the one under test.
    private val durationMillis: Long = 3_000L,
) : AudioFrameStreamingInputSource {
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    var captureCount = 0
    var streamingCaptureCount = 0
    var blockCaptures = false
    private var gate = CompletableDeferred<Unit>()

    override suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio> {
        captureCount++
        if (blockCaptures) {
            gate.await()
            return AppResult.Failure(AppError("AUDIO_CANCELLED", "cancelled"))
        }
        return AppResult.Success(CapturedAudio(shortArrayOf(1, 2, 3), 16_000, durationMillis, null))
    }

    override suspend fun captureStreaming(
        request: CaptureRequest,
        onFrame: suspend (samples: ShortArray, sampleRateHz: Int) -> AppResult<Unit>,
    ): AppResult<CapturedAudio> {
        streamingCaptureCount += 1
        captureCount += 1
        val audio = CapturedAudio(shortArrayOf(1, 2, 3), 16_000, durationMillis, null)
        val accepted = onFrame(audio.pcm16, audio.sampleRateHz)
        return if (accepted is AppResult.Failure) accepted else AppResult.Success(audio)
    }

    override suspend fun cancel() {
        if (!gate.isCompleted) gate.complete(Unit)
    }

    override suspend fun release() = Unit
}

private class AutoDialogue(private val endAfterTurns: Int) : DialogueEngine {
    private var turns = 0
    override suspend fun opening(sessionId: String) = DialogueDecision(
        context = DialogueContext(sessionId),
        reply = "opening",
        matchedIntent = null,
        shouldEnd = false,
    )

    override suspend fun process(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
    ): DialogueDecision {
        turns++
        return DialogueDecision(
            context = context.copy(scene = SceneType.WORK),
            reply = "reply-$turns",
            matchedIntent = "work",
            shouldEnd = turns >= endAfterTurns,
        )
    }
}

private class AutoRecognizer : SpeechRecognizer, RecognitionMetricsSource, RecognitionAttemptsMetricsSource {
    private var n = 0
    var text: String? = null
    private val mutableLatestMetrics = MutableStateFlow<RecognitionComputeMetrics?>(null)
    override val latestRecognitionMetrics = mutableLatestMetrics
    private val mutableLatestAttempts = MutableStateFlow<List<RecognitionComputeMetrics>>(emptyList())
    override val latestRecognitionAttempts = mutableLatestAttempts
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult> {
        val recognizedText = text ?: "caller-${n++}"
        val metrics = RecognitionComputeMetrics(
            startedAtElapsedRealtimeNanos = 1_000_000L,
            completedAtElapsedRealtimeNanos = 3_000_000L,
            computeDurationMillis = 2L,
            inputSamples = audio.pcm16.size,
            inputSampleRateHz = audio.sampleRateHz,
            recognizedTextRaw = recognizedText,
        )
        mutableLatestMetrics.value = metrics
        mutableLatestAttempts.value = listOf(metrics)
        return AppResult.Success(RecognitionResult(recognizedText, 0.9f, false))
    }
    override suspend fun release() = Unit
}

private class AutoStreamingRuntime(
    private val synthesizer: SpeechSynthesizer,
) : SpeechRuntimeManager, StreamingSpeechRuntimeManager {
    override val isMock: Boolean = false
    override val supportsStreamingRecognition: Boolean = true
    var acceptedSamples = 0
    var batchRecognitionCount = 0
    var streamingResult: AppResult<RecognitionResult> =
        AppResult.Success(RecognitionResult("streamed-caller", 1f, false))

    override suspend fun configure(mockMode: Boolean) = SpeechRuntimeInitialization(
        asrStatus = ModuleStatus.RealReady("test"),
        ttsStatus = ModuleStatus.RealReady("test"),
    )

    override suspend fun onIncoming() = Unit

    override suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult> {
        batchRecognitionCount += 1
        return AppResult.Success(RecognitionResult("batch-caller", 1f, false))
    }

    override suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession> = AppResult.Success(
        object : StreamingSpeechRecognitionSession {
            override suspend fun accept(samples: ShortArray): AppResult<Unit> {
                acceptedSamples += samples.size
                return AppResult.Success(Unit)
            }

            override suspend fun finish(speechDetected: Boolean): AppResult<RecognitionResult> =
                streamingResult

            override suspend fun cancel() = Unit
        },
    )

    override suspend fun synthesize(text: String, sessionId: String) = synthesizer.synthesize(text, sessionId)
    override suspend fun onSessionEnded() = Unit
    override suspend fun releaseAll() = Unit
    override fun currentPolicy(): InferencePolicy = InferencePolicy.forTier(DeviceTier.LOW)
}

private class AutoSynth : SpeechSynthesizer {
    var failure: AppError? = null
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech> {
        val error = failure
        return if (error == null) {
            AppResult.Success(SynthesizedSpeech(text, null, 10, false, shortArrayOf(1), 16_000))
        } else {
            AppResult.Failure(error)
        }
    }
    override suspend fun release() = Unit
}

private class AutoSummary : SummaryGenerator {
    override suspend fun generate(scene: SceneType, result: StructuredResult, transcript: List<TranscriptTurn>) = "summary"
}

private class AutoOutput : AudioOutputSink {
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    var playCount = 0
    override suspend fun play(speech: SynthesizedSpeech): AppResult<Unit> {
        playCount += 1
        return AppResult.Success(Unit)
    }
    override suspend fun playFile(path: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun stop() = Unit
    override suspend fun release() = Unit
}

private class AutoStore : SessionRecordingStore {
    var finalizeCount = 0
    override suspend fun appendPcm(sessionId: String, samples: ShortArray, sampleRateHz: Int) =
        AppResult.Success("/rec/s.wav")
    override suspend fun finalizeSession(sessionId: String): AppResult<String?> {
        finalizeCount++
        return AppResult.Success("/rec/s.wav")
    }
    override suspend fun discardSession(sessionId: String) = Unit
}

private class AutoNormalizer : RecordingAudioNormalizer {
    override fun normalize(samples: ShortArray, sourceSampleRateHz: Int) =
        AppResult.Success(NormalizedRecordingAudio(samples, 16_000))
}

private class AutoSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings(defaultInputMode = InputMode.MICROPHONE))
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppResult<Unit> {
        settings.value = transform(settings.value)
        return AppResult.Success(Unit)
    }
    override suspend fun current(): AppSettings = settings.value
}

private class AutoRepo : CallRepository {
    var saved: CallRecord? = null
    var saveCount = 0
    var saveResult: AppResult<Unit> = AppResult.Success(Unit)
    override fun observeHistory(filter: HistoryFilter): Flow<List<CallRecord>> = MutableStateFlow(emptyList())
    override fun observeById(id: String): Flow<CallRecord?> = MutableStateFlow(saved)
    override suspend fun getById(id: String): CallRecord? = saved
    override suspend fun save(record: CallRecord): AppResult<Unit> {
        saveCount++
        if (saveResult is AppResult.Success) {
            saved = record
        }
        return saveResult
    }
    override suspend fun delete(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun cleanup(nowMillis: Long, audioDays: Int, recordDays: Int) =
        com.example.calldelegate.domain.model.CleanupReport()
    override suspend fun seedExamplesIfEmpty(): AppResult<Unit> = AppResult.Success(Unit)
}
