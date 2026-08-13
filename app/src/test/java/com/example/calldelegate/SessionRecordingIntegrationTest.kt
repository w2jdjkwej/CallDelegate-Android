package com.example.calldelegate

import com.example.calldelegate.core.ai.DefaultCallSessionController
import com.example.calldelegate.core.ai.DefaultHumanTakeoverController
import com.example.calldelegate.core.audio.DefaultRecordingAudioNormalizer
import com.example.calldelegate.core.audio.WavSessionRecordingStore
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.PerformanceMonitor
import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CleanupReport
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.DialogueDecision
import com.example.calldelegate.domain.model.HistoryFilter
import com.example.calldelegate.domain.model.InputMode
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SessionRecordingIntegrationTest {
    @Test
    fun mixedRateConversationProducesOrderedSixteenKilohertzWav() = runTest {
        val directory = Files.createTempDirectory("session-recording-integration").toFile()
        try {
            val opening = tone(300.0, 22_050)
            val caller = tone(600.0, 16_000)
            val reply = tone(900.0, 22_050)
            val repository = IntegrationCallRepository()
            val controller = DefaultCallSessionController(
                dialogueEngine = IntegrationDialogue(),
                recognizer = IntegrationRecognizer(),
                synthesizer = IntegrationSynthesizer(opening, reply),
                summaryGenerator = IntegrationSummary(),
                audioInputs = IntegrationInputRegistry(caller),
                audioOutput = IntegrationOutput(),
                recordingStore = WavSessionRecordingStore(directory),
                recordingAudioNormalizer = DefaultRecordingAudioNormalizer(),
                calls = repository,
                settings = IntegrationSettings(),
                takeover = DefaultHumanTakeoverController(),
                clock = Clock { 1_000L },
                performanceMonitor = PerformanceMonitor(),
            )

            controller.simulateIncoming("caller", "10086")
            controller.acceptWithAi(InputMode.MICROPHONE)
            controller.captureMicrophoneTurn()

            val saved = repository.saved!!
            assertThat(saved.recordingIntegrity).isEqualTo(RecordingIntegrity.COMPLETE)
            assertThat(saved.recordingFailure).isNull()
            val bytes = java.io.File(saved.audioPath!!).readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertThat(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)).isEqualTo("RIFF")
            assertThat(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)).isEqualTo("WAVE")
            assertThat(buffer.getShort(20).toInt()).isEqualTo(1)
            assertThat(buffer.getShort(22).toInt()).isEqualTo(1)
            assertThat(buffer.getInt(24)).isEqualTo(16_000)
            assertThat(buffer.getShort(34).toInt()).isEqualTo(16)

            val pcm = ShortArray((bytes.size - 44) / 2) { index -> buffer.getShort(44 + index * 2) }
            assertThat(pcm.size).isWithin(2).of(48_000)
            assertDominantTone(pcm, 0, 300.0, 600.0, 900.0)
            assertDominantTone(pcm, 16_000, 600.0, 300.0, 900.0)
            assertDominantTone(pcm, 32_000, 900.0, 300.0, 600.0)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertDominantTone(
        pcm: ShortArray,
        windowStart: Int,
        expectedHz: Double,
        otherOneHz: Double,
        otherTwoHz: Double,
    ) {
        val start = windowStart + 1_000
        val end = (windowStart + 15_000).coerceAtMost(pcm.size)
        val expected = toneMagnitude(pcm, start, end, expectedHz)
        assertThat(expected).isGreaterThan(toneMagnitude(pcm, start, end, otherOneHz) * 5.0)
        assertThat(expected).isGreaterThan(toneMagnitude(pcm, start, end, otherTwoHz) * 5.0)
    }

    private fun toneMagnitude(pcm: ShortArray, start: Int, end: Int, frequencyHz: Double): Double {
        var sine = 0.0
        var cosine = 0.0
        for (index in start until end) {
            val phase = 2.0 * PI * frequencyHz * (index - start) / 16_000.0
            sine += pcm[index] * sin(phase)
            cosine += pcm[index] * cos(phase)
        }
        return sqrt(sine * sine + cosine * cosine) / (end - start)
    }

    private fun tone(frequencyHz: Double, sampleRateHz: Int): ShortArray =
        ShortArray(sampleRateHz) { index ->
            (8_000.0 * sin(2.0 * PI * frequencyHz * index / sampleRateHz)).toInt().toShort()
        }
}

private class IntegrationSynthesizer(
    private val opening: ShortArray,
    private val reply: ShortArray,
) : SpeechSynthesizer {
    private var count = 0
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech> {
        val pcm = if (count++ == 0) opening else reply
        return AppResult.Success(SynthesizedSpeech(text, null, 1_000, false, pcm, 22_050))
    }
    override suspend fun release() = Unit
}

private class IntegrationInputRegistry(caller: ShortArray) : AudioInputRegistry {
    private val source = object : AudioInputSource {
        override val mode = InputMode.MICROPHONE
        override val state = MutableStateFlow<AudioState>(AudioState.Idle)
        override suspend fun capture(request: CaptureRequest) = AppResult.Success(
            CapturedAudio(caller, 16_000, 1_000, null),
        )
        override suspend fun cancel() = Unit
        override suspend fun release() = Unit
    }
    override fun sourceFor(mode: InputMode): AudioInputSource? = if (mode == InputMode.MICROPHONE) source else null
}

private class IntegrationRecognizer : SpeechRecognizer {
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun recognize(audio: CapturedAudio) =
        AppResult.Success(RecognitionResult("caller", 1f, false))
    override suspend fun release() = Unit
}

private class IntegrationDialogue : DialogueEngine {
    override suspend fun opening(sessionId: String) = DialogueDecision(
        DialogueContext(sessionId), "opening", null, false,
    )
    override suspend fun process(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
    ) = DialogueDecision(
        context.copy(scene = SceneType.WORK, slots = mapOf("purpose" to "integration")),
        "reply",
        "work",
        true,
    )
}

private class IntegrationSummary : SummaryGenerator {
    override suspend fun generate(
        scene: SceneType,
        result: StructuredResult,
        transcript: List<TranscriptTurn>,
    ) = "summary"
}

private class IntegrationOutput : AudioOutputSink {
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override suspend fun play(speech: SynthesizedSpeech): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun playFile(path: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun stop() = Unit
    override suspend fun release() = Unit
}

private class IntegrationSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings(defaultInputMode = InputMode.MICROPHONE))
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppResult<Unit> {
        settings.value = transform(settings.value)
        return AppResult.Success(Unit)
    }
    override suspend fun current(): AppSettings = settings.value
}

private class IntegrationCallRepository : CallRepository {
    var saved: CallRecord? = null
    override fun observeHistory(filter: HistoryFilter): Flow<List<CallRecord>> = MutableStateFlow(emptyList())
    override fun observeById(id: String): Flow<CallRecord?> = MutableStateFlow(saved)
    override suspend fun getById(id: String): CallRecord? = saved
    override suspend fun save(record: CallRecord): AppResult<Unit> {
        saved = record
        return AppResult.Success(Unit)
    }
    override suspend fun delete(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun cleanup(nowMillis: Long, audioDays: Int, recordDays: Int) = CleanupReport()
    override suspend fun seedExamplesIfEmpty(): AppResult<Unit> = AppResult.Success(Unit)
}
