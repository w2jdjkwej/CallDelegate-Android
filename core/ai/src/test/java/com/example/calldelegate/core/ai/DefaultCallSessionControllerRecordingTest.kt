package com.example.calldelegate.core.ai

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.PerformanceMonitor
import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.api.SessionRecordingStore
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.DialogueDecision
import com.example.calldelegate.domain.model.HistoryFilter
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.NormalizedRecordingAudio
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.model.TranscriptTurn
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultCallSessionControllerRecordingTest {
    @Test
    fun mixedRateConversationWritesOnlyNormalizedPcmAndPlaysOriginalSpeech() = runTest {
        val fixture = Fixture()

        fixture.runConversation()

        assertThat(fixture.store.rates).containsExactly(16_000, 16_000, 16_000).inOrder()
        assertThat(fixture.normalizer.sourceRates).containsExactly(22_050, 16_000, 22_050).inOrder()
        assertThat(fixture.normalizer.inputs[1]).isSameInstanceAs(fixture.callerPcm)
        assertThat(fixture.store.samples[1]).isSameInstanceAs(fixture.callerPcm)
        assertThat(fixture.output.played[0]).isSameInstanceAs(fixture.synthesizer.openingSpeech)
        assertThat(fixture.output.played[1]).isSameInstanceAs(fixture.synthesizer.replySpeech)
        assertThat(fixture.repository.saved!!.recordingIntegrity).isEqualTo(RecordingIntegrity.COMPLETE)
        assertThat(fixture.repository.saved!!.recordingFailure).isNull()
    }

    @Test
    fun resamplingFailureWithAUsableWavProducesPartialRecord() = runTest {
        val fixture = Fixture()
        fixture.normalizer.failSourceRate = 22_050

        fixture.runConversation()

        val saved = fixture.repository.saved!!
        assertThat(saved.recordingIntegrity).isEqualTo(RecordingIntegrity.PARTIAL)
        assertThat(saved.recordingFailure?.code).isEqualTo("AUDIO_RESAMPLE")
        assertThat(saved.transcript.map(TranscriptTurn::text)).containsAtLeast("caller text", "reply")
        assertThat(saved.summary).isEqualTo("summary")
        assertThat(saved.structuredResult.purpose).isEqualTo("test purpose")
    }

    @Test
    fun appendFailureWithAUsableWavProducesPartialRecord() = runTest {
        val fixture = Fixture()
        fixture.store.failFirstAppend = true

        fixture.runConversation()

        assertThat(fixture.repository.saved!!.recordingIntegrity).isEqualTo(RecordingIntegrity.PARTIAL)
        assertThat(fixture.repository.saved!!.recordingFailure?.code).isEqualTo("AUDIO_SAVE_TEST")
    }

    @Test
    fun missingOrFailedFinalWavProducesFailedRecord() = runTest {
        val missing = Fixture().also { it.store.finalizeResult = AppResult.Success(null) }
        missing.runConversation()
        assertThat(missing.repository.saved!!.recordingIntegrity).isEqualTo(RecordingIntegrity.FAILED)
        assertThat(missing.repository.saved!!.recordingFailure?.code).isEqualTo("AUDIO_RECORDING_EMPTY")

        val failed = Fixture().also {
            it.store.finalizeResult = AppResult.Failure(AppError("AUDIO_FINALIZE_TEST", "finalize failed"))
        }
        failed.runConversation()
        assertThat(failed.repository.saved!!.recordingIntegrity).isEqualTo(RecordingIntegrity.FAILED)
        assertThat(failed.repository.saved!!.recordingFailure?.code).isEqualTo("AUDIO_FINALIZE_TEST")
    }

    @Test
    fun playbackFailureDoesNotChangeRecordingIntegrity() = runTest {
        val fixture = Fixture()
        fixture.output.playResult = AppResult.Failure(AppError("AUDIO_PLAY_TEST", "play failed"))

        fixture.runConversation()

        val saved = fixture.repository.saved!!
        assertThat(saved.recordingIntegrity).isEqualTo(RecordingIntegrity.COMPLETE)
        assertThat(saved.recordingFailure).isNull()
        assertThat(saved.playbackFailure?.code).isEqualTo("AUDIO_PLAY_TEST")
    }
}

private class Fixture {
    val callerPcm = shortArrayOf(20, 21, 22)
    val synthesizer = FakeSynthesizer()
    val normalizer = FakeNormalizer()
    val store = FakeRecordingStore()
    val output = FakeOutput()
    val repository = FakeCallRepository()
    private val input = FakeInput(callerPcm)
    private var now = 1_000L

    private val controller = DefaultCallSessionController(
        dialogueEngine = FakeDialogue(),
        recognizer = FakeRecognizer(),
        synthesizer = synthesizer,
        summaryGenerator = FakeSummary(),
        audioInputs = object : AudioInputRegistry {
            override fun sourceFor(mode: InputMode): AudioInputSource? =
                if (mode == InputMode.MICROPHONE) input else null
        },
        audioOutput = output,
        recordingStore = store,
        recordingAudioNormalizer = normalizer,
        calls = repository,
        settings = FakeSettings(),
        takeover = DefaultHumanTakeoverController(),
        clock = Clock { now++ },
        performanceMonitor = PerformanceMonitor(),
    )

    suspend fun runConversation() {
        controller.simulateIncoming("caller", "10086")
        controller.acceptWithAi(InputMode.MICROPHONE)
        controller.captureMicrophoneTurn()
    }
}

private class FakeNormalizer : RecordingAudioNormalizer {
    val sourceRates = mutableListOf<Int>()
    val inputs = mutableListOf<ShortArray>()
    var failSourceRate: Int? = null

    override fun normalize(samples: ShortArray, sourceSampleRateHz: Int): AppResult<NormalizedRecordingAudio> {
        sourceRates += sourceSampleRateHz
        inputs += samples
        if (sourceSampleRateHz == failSourceRate) {
            return AppResult.Failure(AppError("AUDIO_RESAMPLE", "resample failed"))
        }
        val normalized = if (sourceSampleRateHz == 16_000) samples else ShortArray(2) { sourceSampleRateHz.toShort() }
        return AppResult.Success(NormalizedRecordingAudio(normalized, 16_000))
    }
}

private class FakeRecordingStore : SessionRecordingStore {
    val rates = mutableListOf<Int>()
    val samples = mutableListOf<ShortArray>()
    var failFirstAppend = false
    var finalizeResult: AppResult<String?> = AppResult.Success("/recordings/session.wav")

    override suspend fun appendPcm(sessionId: String, samples: ShortArray, sampleRateHz: Int): AppResult<String> {
        if (failFirstAppend) {
            failFirstAppend = false
            return AppResult.Failure(AppError("AUDIO_SAVE_TEST", "append failed"))
        }
        this.samples += samples
        rates += sampleRateHz
        return AppResult.Success("/recordings/session.wav")
    }

    override suspend fun finalizeSession(sessionId: String): AppResult<String?> = finalizeResult
    override suspend fun discardSession(sessionId: String) = Unit
}

private class FakeSynthesizer : SpeechSynthesizer {
    val openingSpeech = SynthesizedSpeech("opening", null, 100, false, shortArrayOf(1, 2), 22_050)
    val replySpeech = SynthesizedSpeech("reply", null, 100, false, shortArrayOf(3, 4), 22_050)
    private var calls = 0

    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech> =
        AppResult.Success(if (calls++ == 0) openingSpeech else replySpeech)
    override suspend fun release() = Unit
}

private class FakeOutput : AudioOutputSink {
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    val played = mutableListOf<SynthesizedSpeech>()
    var playResult: AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun play(speech: SynthesizedSpeech): AppResult<Unit> {
        played += speech
        return playResult
    }
    override suspend fun playFile(path: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun stop() = Unit
    override suspend fun release() = Unit
}

private class FakeInput(private val pcm: ShortArray) : AudioInputSource {
    override val mode = InputMode.MICROPHONE
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override suspend fun capture(request: CaptureRequest) = AppResult.Success(
        CapturedAudio(pcm, 16_000, 1, null),
    )
    override suspend fun cancel() = Unit
    override suspend fun release() = Unit
}

private class FakeRecognizer : SpeechRecognizer {
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun recognize(audio: CapturedAudio) = AppResult.Success(
        RecognitionResult("caller text", 0.9f, false),
    )
    override suspend fun release() = Unit
}

private class FakeDialogue : DialogueEngine {
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
    ) = DialogueDecision(
        context = context.copy(scene = SceneType.WORK, slots = mapOf("purpose" to "test purpose")),
        reply = "reply",
        matchedIntent = "work",
        shouldEnd = true,
    )
}

private class FakeSummary : SummaryGenerator {
    override suspend fun generate(
        scene: SceneType,
        result: StructuredResult,
        transcript: List<TranscriptTurn>,
    ) = "summary"
}

private class FakeSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings(defaultInputMode = InputMode.MICROPHONE))
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppResult<Unit> {
        settings.value = transform(settings.value)
        return AppResult.Success(Unit)
    }
    override suspend fun current(): AppSettings = settings.value
}

private class FakeCallRepository : CallRepository {
    var saved: CallRecord? = null
    override fun observeHistory(filter: HistoryFilter): Flow<List<CallRecord>> = MutableStateFlow(emptyList())
    override fun observeById(id: String): Flow<CallRecord?> = MutableStateFlow(saved)
    override suspend fun getById(id: String): CallRecord? = saved
    override suspend fun save(record: CallRecord): AppResult<Unit> {
        saved = record
        return AppResult.Success(Unit)
    }
    override suspend fun delete(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun cleanup(nowMillis: Long, audioDays: Int, recordDays: Int) =
        com.example.calldelegate.domain.model.CleanupReport()
    override suspend fun seedExamplesIfEmpty(): AppResult<Unit> = AppResult.Success(Unit)
}
