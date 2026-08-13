package com.example.calldelegate.domain.api

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.DialogueDecision
import com.example.calldelegate.domain.model.InstalledModel
import com.example.calldelegate.domain.model.IntentMatch
import com.example.calldelegate.domain.model.ModelImportResult
import com.example.calldelegate.domain.model.ModelType
import com.example.calldelegate.domain.model.ModuleStatusItem
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.example.calldelegate.domain.model.SecondaryRecognitionExperimentMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SceneConfidenceState
import com.example.calldelegate.domain.model.SlotExtractionRequest
import com.example.calldelegate.domain.model.SlotExtractionResult
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.model.TranscriptTurn
import kotlinx.coroutines.flow.StateFlow

data class VadDecision(val speechDetected: Boolean, val endOfSpeech: Boolean, val probability: Float)

enum class SpeechRecognitionMode { GENERAL, SCENE_VOCABULARY }

enum class SpeechRecognitionFocus { SCENE, LOCATION, ISSUE, ORDER, TIME }

data class SpeechRecognitionContext(
    val mode: SpeechRecognitionMode = SpeechRecognitionMode.GENERAL,
    val sceneHints: Set<SceneType> = emptySet(),
    val isSecondaryPass: Boolean = false,
    val focuses: Set<SpeechRecognitionFocus> = emptySet(),
    val languageTag: String = "zh-CN",
) {
    init {
        require(sceneHints.size <= 2) { "Speech recognition accepts at most two scene hints" }
    }
}

interface VoiceActivityDetector {
    fun reset()
    fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision
}

/** Optional configuration observation for recording the VAD actually used by a test run. */
data class VoiceActivityDetectorConfiguration(
    val implementationName: String,
    val rmsThreshold: Double?,
    val endSilenceFrames: Int?,
    val initialSilenceFrames: Int?,
    val endSilenceMs: Long? = null,
    val initialSilenceMs: Long? = null,
    val subframeDurationMs: Long? = null,
)

interface VoiceActivityDetectorConfigurationSource {
    val voiceActivityDetectorConfiguration: VoiceActivityDetectorConfiguration
}

interface SpeechRecognizer {
    suspend fun initialize(): AppResult<Unit>
    suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult>
    suspend fun recognize(
        audio: CapturedAudio,
        context: SpeechRecognitionContext,
    ): AppResult<RecognitionResult> = recognize(audio)
    suspend fun release()
}

/** A single incremental recognition session. Frames must be accepted in capture order. */
interface StreamingSpeechRecognitionSession {
    /** Stable identity of the one native recognizer used by this session, when observable. */
    val recognizerId: String?
        get() = null

    suspend fun accept(samples: ShortArray): AppResult<Unit>

    /** Read-only snapshot. It must not finish the recognizer or affect endpoint control flow. */
    suspend fun snapshot(): AppResult<StreamingRecognitionSnapshot> = AppResult.Success(
        StreamingRecognitionSnapshot(recognizerId = recognizerId, partialTextRaw = null),
    )

    suspend fun finish(speechDetected: Boolean): AppResult<RecognitionResult>
    suspend fun cancel()
}

data class StreamingRecognitionSnapshot(
    val recognizerId: String?,
    val partialTextRaw: String?,
    /**
     * True when the recognizer has closed a segment and is not part-way through a new one.
     *
     * This is the recognizer's own endpoint decision, reached from the acoustic model and the
     * language model rather than from the shape of the text it produced. It matters because the
     * text-shape rules cannot work on every model: a Mandarin model that emits no punctuation can
     * never satisfy a rule that looks for a full stop, whatever the caller actually said.
     */
    val recognizerClosedSegment: Boolean = false,
)

/** Optional recognizer capability for moving ASR compute onto the capture timeline. */
interface StreamingSpeechRecognizer {
    val supportsStreamingRecognition: Boolean

    suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession>
}

/** Facts emitted by a real recognizer for the one recognition call that just completed. */
data class RecognitionComputeMetrics(
    val startedAtElapsedRealtimeNanos: Long,
    val completedAtElapsedRealtimeNanos: Long,
    val computeDurationMillis: Long,
    val inputSamples: Int,
    val inputSampleRateHz: Int,
    val recognizedTextRaw: String?,
    val errorCode: String? = null,
    val attemptIndex: Int = 1,
    val recognitionMode: SpeechRecognitionMode = SpeechRecognitionMode.GENERAL,
    val sceneHints: List<String> = emptyList(),
    val recognitionFocuses: List<String> = emptyList(),
    val unknownTokenCount: Int = 0,
    /** Size of the N-best list the recognizer returned, or null when it was not asked for one. */
    val alternativeCount: Int? = null,
    /**
     * Mean and minimum word confidence in 0..1, or null when the recognizer reported no words.
     *
     * The minimum is kept separately because a turn whose words are uniformly mediocre and a turn
     * that is confident everywhere except the one word carrying the address are different problems,
     * and only the second is worth re-listening for.
     */
    val meanWordConfidence: Float? = null,
    val minimumWordConfidence: Float? = null,
    val recognizerCreateDurationMillis: Long? = null,
    val voskAcceptComputeDurationMillis: Long? = null,
    /** CPU time inside accept; far below [voskAcceptComputeDurationMillis] means a descheduled thread. */
    val voskAcceptCpuDurationMillis: Long? = null,
    val voskQueueMaxDepth: Int? = null,
    val voskQueueWaitDurationMillis: Long? = null,
    val voskQueueWaitMaxMillis: Long? = null,
    val voskDrainDurationMillis: Long? = null,
    val voskFinalResultDurationMillis: Long? = null,
    /** Number of audio chunks actually consumed by the native streaming recognizer. */
    val voskAcceptedFrameCount: Int? = null,
    /** Linux thread id and scheduling facts sampled once on the inference worker during the turn. */
    val voskWorkerThreadId: Int? = null,
    val voskWorkerThreadPriority: Int? = null,
    val voskWorkerCpusAllowedList: String? = null,
    val voskWorkerCpusetGroup: String? = null,
)

/**
 * What the recognizer's other hypotheses offered for one turn, and what was done with them.
 *
 * [transcript] is what the call record keeps -- the recognizer's own best hypothesis. A rerank
 * never rewrites it, so holding both texts here is what makes a substitution auditable.
 *
 * Recorded on every turn that had alternatives, not only on the turns that changed. A record of
 * changes alone cannot distinguish the two ways of being wrong: the better reading was absent from
 * the lattice, or it was there and the acceptance rules turned it down. Those call for opposite
 * fixes, so [alternatives] carries the whole list either way.
 */
data class NBestRerankObservation(
    val transcript: String,
    val understoodText: String,
    val chosenRank: Int,
    /** Empty when the recognizer's own best hypothesis was kept. */
    val reasons: List<String>,
    /** Every hypothesis the recognizer offered, in its ranking, including [transcript] at index 0. */
    val alternatives: List<String>,
    /** How many were actually classified. One means the alternatives were not worth examining. */
    val examinedHypotheses: Int,
) {
    val changedHypothesis: Boolean get() = chosenRank != 0
}

/** Optional observation capability; it does not add another recognition path. */
interface NBestRerankMetricsSource {
    val latestNBestRerank: StateFlow<NBestRerankObservation?>
}

/** Optional observation capability; it does not add another recognition path. */
interface RecognitionMetricsSource {
    val latestRecognitionMetrics: StateFlow<RecognitionComputeMetrics?>
}

interface RecognitionAttemptsMetricsSource {
    val latestRecognitionAttempts: StateFlow<List<RecognitionComputeMetrics>>
}

/** Monotonic timestamps for one caller turn from recognition through speech synthesis. */
data class TurnPipelineMetrics(
    val asrStartedAtElapsedRealtimeNanos: Long? = null,
    val asrCompletedAtElapsedRealtimeNanos: Long? = null,
    val asrComputeDurationMillis: Long? = null,
    val nluStartedAtElapsedRealtimeNanos: Long? = null,
    val nluCompletedAtElapsedRealtimeNanos: Long? = null,
    val ttsStartedAtElapsedRealtimeNanos: Long? = null,
    val ttsCompletedAtElapsedRealtimeNanos: Long? = null,
)

/** Optional observation capability for reporting one caller turn as ASR, NLU, and TTS stages. */
interface TurnPipelineMetricsSource {
    val latestTurnPipelineMetrics: StateFlow<TurnPipelineMetrics?>
}

data class RecognitionClassificationSnapshot(
    val scene: String?,
    val confidence: Float,
    val sceneMargin: Float,
    val entities: Map<String, String>,
)

data class SecondaryRecognitionFusionMetrics(
    val primaryText: String,
    val secondaryText: String,
    val textDifferenceRate: Double,
    val triggerReasons: List<String>,
    val sceneHints: List<String>,
    val matchedHotwordsByScene: Map<String, List<String>>,
    val primaryClassification: RecognitionClassificationSnapshot,
    val secondaryClassification: RecognitionClassificationSnapshot,
    val finalClassification: RecognitionClassificationSnapshot? = null,
    val decisionReasons: List<String> = emptyList(),
    val evidenceUsed: Boolean = false,
)

interface SecondaryRecognitionMetricsSource {
    val latestSecondaryRecognitionMetrics: StateFlow<SecondaryRecognitionFusionMetrics?>
}

/** Optional debug observation capability for exporting the final NLU decision of a turn. */
interface RuleClassificationMetricsSource {
    val latestRuleClassification: StateFlow<RuleClassificationResult?>
}

/** Optional debug control used to compare secondary-ASR policies without changing app settings. */
interface SecondaryRecognitionExperimentController {
    fun setSecondaryRecognitionExperimentMode(mode: SecondaryRecognitionExperimentMode)
}

/** Optional observation capability for distinguishing provisional and confirmed scenes. */
interface SceneConfidenceMetricsSource {
    val latestSceneConfidenceState: StateFlow<SceneConfidenceState>
}

interface IntentClassifier {
    suspend fun classify(text: String, enabledScenes: Set<SceneType>): IntentMatch?

    suspend fun classifyDetailed(
        text: String,
        enabledScenes: Set<SceneType>,
        context: RuleClassificationContext = RuleClassificationContext(),
    ): RuleClassificationResult? = classify(text, enabledScenes)?.let { match ->
        RuleClassificationResult(
            scene = match.scene.id,
            intent = match.intentId,
            confidence = match.confidence,
            sceneMargin = match.confidence,
            matchedEvidence = match.matchedEvidence.split(',').filter(String::isNotBlank),
        )
    }
}

interface EntityExtractor {
    suspend fun extract(text: String, expectedSlots: Set<String> = emptySet()): Map<String, String>

    suspend fun extract(request: SlotExtractionRequest): SlotExtractionResult {
        val extracted = extract(request.text, request.expectedSlots)
        return SlotExtractionResult(
            slots = extracted,
            overwrittenSlots = extracted.keys.filterTo(linkedSetOf()) { key ->
                request.existingSlots[key]?.let { it != extracted[key] } == true
            },
        )
    }
}

interface DialogueEngine {
    suspend fun opening(sessionId: String): DialogueDecision
    suspend fun opening(sessionId: String, initialScene: SceneType): DialogueDecision = opening(sessionId)
    suspend fun process(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
    ): DialogueDecision

    suspend fun processWithEvidence(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
        secondaryRecognition: SecondaryRecognitionEvidence?,
    ): DialogueDecision = process(context, callerText, recognitionFailed, enabledScenes)
}

interface SummaryGenerator {
    suspend fun generate(scene: SceneType, result: StructuredResult, transcript: List<TranscriptTurn>): String
}

interface SpeechSynthesizer {
    suspend fun initialize(): AppResult<Unit>
    suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech>
    suspend fun release()
}

interface AiModuleRegistry {
    val statuses: StateFlow<List<ModuleStatusItem>>
    suspend fun initializeAll(mockMode: Boolean)
    suspend fun releaseAll()
}

interface ModelManager {
    val installedModels: StateFlow<List<InstalledModel>>
    suspend fun importFromUri(uri: String): ModelImportResult
    suspend fun restoreBuiltIn(typeName: String): ModelImportResult
    suspend fun clearImportCache(): Long
    suspend fun refresh()
    suspend fun activeModel(type: ModelType): ActiveModel?
}
