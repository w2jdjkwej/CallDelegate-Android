package com.example.calldelegate.core.ai

import com.example.calldelegate.core.ai.speech.NBestRecognitionReranker
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.PerformanceMonitor
import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.IntentClassifier
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.api.SessionRecordingStore
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.CleanupReport
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.DialogueDecision
import com.example.calldelegate.domain.model.HistoryFilter
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.IntentMatch
import com.example.calldelegate.domain.model.NormalizedRecordingAudio
import com.example.calldelegate.domain.model.RecognitionAlternative
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.model.TranscriptTurn
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The rerank is the system overruling its own recognizer, so what it may and may not touch is the
 * behaviour worth pinning here rather than inside the reranker: understanding moves, the record
 * does not.
 */
class DefaultCallSessionControllerRerankTest {
    @Test
    fun understandingUsesTheBetterHypothesisWhileTheTranscriptKeepsWhatTheRecognizerHeard() = runTest {
        val fixture = RerankFixture(
            recognized = RecognitionResult(
                text = "剩余挤压还有多少",
                confidence = 0.55f,
                isMock = false,
                alternatives = listOf(
                    RecognitionAlternative("剩余挤压还有多少", 305.4f),
                    RecognitionAlternative("剩余解押还有多少", 301.2f),
                ),
            ),
            classifications = mapOf(
                "剩余挤压还有多少" to classification(scene = null),
                "剩余解押还有多少" to classification(scene = "real_estate"),
            ),
        )

        fixture.runConversation()

        assertThat(fixture.dialogue.callerTexts).containsExactly("剩余解押还有多少")
        assertThat(fixture.callerTranscript()).containsExactly("剩余挤压还有多少")
        val rerank = checkNotNull(fixture.controller.latestNBestRerank.value)
        assertThat(rerank.transcript).isEqualTo("剩余挤压还有多少")
        assertThat(rerank.understoodText).isEqualTo("剩余解押还有多少")
        assertThat(rerank.chosenRank).isEqualTo(1)
        assertThat(rerank.reasons).containsExactly("scene_recovered:real_estate")
    }

    @Test
    fun aTurnTheClassifierSettledDoesNotPayToExamineTheAlternatives() = runTest {
        val fixture = RerankFixture(
            recognized = RecognitionResult(
                text = "我是外卖骑手",
                confidence = 0.95f,
                isMock = false,
                alternatives = listOf(
                    RecognitionAlternative("我是外卖骑手", 305.4f),
                    RecognitionAlternative("我是外卖棋手", 301.2f),
                ),
            ),
            classifications = mapOf(
                "我是外卖骑手" to classification(scene = "delivery"),
                "我是外卖棋手" to classification(scene = "delivery"),
            ),
        )

        fixture.runConversation()

        assertThat(fixture.dialogue.callerTexts).containsExactly("我是外卖骑手")
        // Only the best hypothesis was classified. Examining the rest of the list on turns like
        // this one was measured over 172 recorded utterances and bought no correct substitution.
        assertThat(fixture.classifier.classifiedTexts).containsExactly("我是外卖骑手")
        // Still observed, so "the list was not worth looking at" stays distinguishable from
        // "the list held nothing better".
        val rerank = checkNotNull(fixture.controller.latestNBestRerank.value)
        assertThat(rerank.changedHypothesis).isFalse()
        assertThat(rerank.examinedHypotheses).isEqualTo(1)
        assertThat(rerank.alternatives).containsExactly("我是外卖骑手", "我是外卖棋手").inOrder()
    }

    @Test
    fun withoutARerankerTheRecognizersOwnBestHypothesisIsUnderstood() = runTest {
        val fixture = RerankFixture(
            reranker = null,
            recognized = RecognitionResult(
                text = "剩余挤压还有多少",
                confidence = 0.55f,
                isMock = false,
                alternatives = listOf(
                    RecognitionAlternative("剩余挤压还有多少", 305.4f),
                    RecognitionAlternative("剩余解押还有多少", 301.2f),
                ),
            ),
            classifications = mapOf(
                "剩余挤压还有多少" to classification(scene = null),
                "剩余解押还有多少" to classification(scene = "real_estate"),
            ),
        )

        fixture.runConversation()

        assertThat(fixture.dialogue.callerTexts).containsExactly("剩余挤压还有多少")
        // Nothing was classified ahead of the dialogue engine either: with no refinement possible
        // there is nothing on this path to decide.
        assertThat(fixture.classifier.classifiedTexts).isEmpty()
    }

    @Test
    fun aRecognitionWithNoAlternativesIsUnderstoodAsItIs() = runTest {
        val fixture = RerankFixture(
            recognized = RecognitionResult("剩余挤压还有多少", 0.55f, isMock = false),
            classifications = mapOf("剩余挤压还有多少" to classification(scene = null)),
        )

        fixture.runConversation()

        assertThat(fixture.dialogue.callerTexts).containsExactly("剩余挤压还有多少")
        assertThat(fixture.classifier.classifiedTexts).isEmpty()
    }

    private fun classification(scene: String?) = RuleClassificationResult(
        scene = scene,
        intent = scene?.let { "intent" },
        confidence = if (scene == null) 0.1f else 0.8f,
        sceneMargin = if (scene == null) 0f else 0.4f,
    )
}

private class RerankFixture(
    recognized: RecognitionResult,
    classifications: Map<String, RuleClassificationResult>,
    reranker: NBestRecognitionReranker? = NBestRecognitionReranker(),
) {
    val dialogue = RecordingDialogue()
    val classifier = RecordingClassifier(classifications)
    private var now = 1_000L

    val controller = DefaultCallSessionController(
        dialogueEngine = dialogue,
        recognizer = FixedRecognizer(recognized),
        synthesizer = SilentSynthesizer(),
        summaryGenerator = object : SummaryGenerator {
            override suspend fun generate(
                scene: SceneType,
                result: StructuredResult,
                transcript: List<TranscriptTurn>,
            ) = "summary"
        },
        audioInputs = object : AudioInputRegistry {
            override fun sourceFor(mode: InputMode): AudioInputSource? =
                if (mode == InputMode.MICROPHONE) SilentInput() else null
        },
        audioOutput = SilentOutput(),
        recordingStore = NoRecordingStore(),
        recordingAudioNormalizer = PassThroughNormalizer(),
        calls = ForgetfulCallRepository(),
        settings = MicrophoneSettings(),
        takeover = DefaultHumanTakeoverController(),
        clock = Clock { now++ },
        performanceMonitor = PerformanceMonitor(),
        intentClassifier = classifier,
        nBestReranker = reranker,
    )

    suspend fun runConversation() {
        controller.simulateIncoming("caller", "10086")
        controller.acceptWithAi(InputMode.MICROPHONE)
        controller.captureMicrophoneTurn()
    }

    fun callerTranscript(): List<String> = controller.state.value.transcript
        .filter { it.speaker == Speaker.CALLER }
        .map(TranscriptTurn::text)
}

private class RecordingClassifier(
    private val classifications: Map<String, RuleClassificationResult>,
) : IntentClassifier {
    val classifiedTexts = mutableListOf<String>()

    override suspend fun classify(text: String, enabledScenes: Set<SceneType>): IntentMatch? = null

    override suspend fun classifyDetailed(
        text: String,
        enabledScenes: Set<SceneType>,
        context: RuleClassificationContext,
    ): RuleClassificationResult? {
        classifiedTexts += text
        return classifications[text]
    }
}

private class RecordingDialogue : DialogueEngine {
    val callerTexts = mutableListOf<String>()

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
        callerText?.let(callerTexts::add)
        return DialogueDecision(
            context = context.copy(scene = SceneType.WORK),
            reply = "reply",
            matchedIntent = "work",
            shouldEnd = true,
        )
    }
}

private class FixedRecognizer(private val result: RecognitionResult) : SpeechRecognizer {
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun recognize(audio: CapturedAudio) = AppResult.Success(result)
    override suspend fun release() = Unit
}

private class SilentSynthesizer : SpeechSynthesizer {
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun synthesize(text: String, sessionId: String) =
        AppResult.Success(SynthesizedSpeech(text, null, 10, false, shortArrayOf(1), 16_000))
    override suspend fun release() = Unit
}

private class SilentOutput : AudioOutputSink {
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override suspend fun play(speech: SynthesizedSpeech) = AppResult.Success(Unit)
    override suspend fun playFile(path: String) = AppResult.Success(Unit)
    override suspend fun stop() = Unit
    override suspend fun release() = Unit
}

private class SilentInput : AudioInputSource {
    override val mode = InputMode.MICROPHONE
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override suspend fun capture(request: CaptureRequest) =
        AppResult.Success(CapturedAudio(shortArrayOf(1, 2, 3), 16_000, 1, null))
    override suspend fun cancel() = Unit
    override suspend fun release() = Unit
}

private class PassThroughNormalizer : RecordingAudioNormalizer {
    override fun normalize(samples: ShortArray, sourceSampleRateHz: Int) =
        AppResult.Success(NormalizedRecordingAudio(samples, 16_000))
}

private class NoRecordingStore : SessionRecordingStore {
    override suspend fun appendPcm(sessionId: String, samples: ShortArray, sampleRateHz: Int) =
        AppResult.Success("/recordings/session.wav")
    override suspend fun finalizeSession(sessionId: String): AppResult<String?> =
        AppResult.Success("/recordings/session.wav")
    override suspend fun discardSession(sessionId: String) = Unit
}

private class MicrophoneSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings(defaultInputMode = InputMode.MICROPHONE))
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppResult<Unit> {
        settings.value = transform(settings.value)
        return AppResult.Success(Unit)
    }
    override suspend fun current(): AppSettings = settings.value
}

private class ForgetfulCallRepository : CallRepository {
    override fun observeHistory(filter: HistoryFilter): Flow<List<CallRecord>> = MutableStateFlow(emptyList())
    override fun observeById(id: String): Flow<CallRecord?> = MutableStateFlow(null)
    override suspend fun getById(id: String): CallRecord? = null
    override suspend fun save(record: CallRecord): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun delete(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun cleanup(nowMillis: Long, audioDays: Int, recordDays: Int) = CleanupReport()
    override suspend fun seedExamplesIfEmpty(): AppResult<Unit> = AppResult.Success(Unit)
}
