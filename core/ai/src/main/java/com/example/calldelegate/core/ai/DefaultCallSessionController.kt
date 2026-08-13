package com.example.calldelegate.core.ai

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.PerformanceMonitor
import com.example.calldelegate.domain.api.AudioFrameStreamingInputSource
import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CandidateEndpointAudioInputSource
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.DialogueContextPresetController
import com.example.calldelegate.domain.api.ExternalCallResponseRoute
import com.example.calldelegate.domain.api.HumanTakeoverController
import com.example.calldelegate.domain.api.IntentClassifier
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.api.RemoteAudioInputSource
import com.example.calldelegate.domain.api.RecognitionMetricsSource
import com.example.calldelegate.domain.api.RecognitionAttemptsMetricsSource
import com.example.calldelegate.domain.api.RecognitionClassificationSnapshot
import com.example.calldelegate.domain.api.RecognitionComputeMetrics
import com.example.calldelegate.domain.api.RuleClassificationMetricsSource
import com.example.calldelegate.domain.api.NBestRerankMetricsSource
import com.example.calldelegate.domain.api.NBestRerankObservation
import com.example.calldelegate.domain.api.SecondaryRecognitionFusionMetrics
import com.example.calldelegate.domain.api.SecondaryRecognitionExperimentController
import com.example.calldelegate.domain.api.SecondaryRecognitionMetricsSource
import com.example.calldelegate.domain.api.SceneConfidenceMetricsSource
import com.example.calldelegate.domain.api.SessionRecordingStore
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.StreamingSpeechRuntimeManager
import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.api.TurnPipelineMetrics
import com.example.calldelegate.domain.api.TurnPipelineMetricsSource
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SceneConfidenceState
import com.example.calldelegate.domain.model.RecognitionAlternative
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.example.calldelegate.domain.model.SecondaryRecognitionExperimentMode
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.model.TranscriptTurn
import com.example.calldelegate.domain.session.CallSessionSnapshot
import com.example.calldelegate.domain.session.SessionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.calldelegate.core.ai.speech.DirectSpeechRuntimeManager
import com.example.calldelegate.core.ai.speech.NBestRecognitionReranker
import com.example.calldelegate.core.ai.speech.RecognitionPreview
import com.example.calldelegate.core.ai.speech.RerankCandidate
import com.example.calldelegate.core.ai.speech.SceneHotwordProvider
import com.example.calldelegate.core.ai.speech.SceneRecognitionPolicy
import com.example.calldelegate.core.ai.speech.SceneVocabularyTracker
import com.example.calldelegate.core.ai.speech.SlotReplyPrefetcher
import com.example.calldelegate.core.audio.capture.DownlinkCallRecorder

private const val PRIMARY_ASR_SAMPLE_RATE_HZ = 16_000

/**
 * How many capture windows may close on silence before the assistant speaks anyway.
 *
 * Waiting is the right answer to a caller who has not started talking, but not forever: a
 * call where nobody ever speaks still has to reach the fallback ladder and end.
 */
private const val MAX_SILENT_TURNS_BEFORE_PROMPT = 2

/**
 * Below this, a capture with no words in it is treated as a fragment rather than a turn.
 *
 * Real turns on the two device calls of 2026-08-09 captured 2.1 to 6.0 seconds. The capture that
 * interrupted the caller was 980 ms and recognized to nothing. 1200 ms sits between the two and
 * still leaves room for the short answers this dialogue asks for, which is why the length is only
 * ever consulted once recognition has already come back empty.
 */
private const val MINIMUM_UTTERANCE_DURATION_MILLIS = 1_200L

/** Turns a sample count into the milliseconds of speech it represents, for the turn breakdown. */
private const val MILLIS_PER_SECOND = 1_000L
private val STREAMING_PRIMARY_FALLBACK_ERRORS = setOf(
    "ASR_RECOGNIZE",
    "ASR_UNRECOGNIZABLE",
    "ASR_SESSION_CLOSED",
)

class DefaultCallSessionController(
    private val dialogueEngine: DialogueEngine,
    private val recognizer: SpeechRecognizer,
    private val synthesizer: SpeechSynthesizer,
    private val summaryGenerator: SummaryGenerator,
    private val audioInputs: AudioInputRegistry,
    private val audioOutput: AudioOutputSink,
    private val recordingStore: SessionRecordingStore,
    private val recordingAudioNormalizer: RecordingAudioNormalizer,
    private val calls: CallRepository,
    private val settings: SettingsRepository,
    private val takeover: HumanTakeoverController,
    private val clock: Clock,
    private val performanceMonitor: PerformanceMonitor,
    private val speechRuntime: SpeechRuntimeManager = DirectSpeechRuntimeManager(recognizer, synthesizer),
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val downlinkRecorder: DownlinkCallRecorder? = null,
    private val intentClassifier: IntentClassifier? = null,
    private val sceneHotwords: SceneHotwordProvider? = null,
    /**
     * Null disables consulting the recognizer's other hypotheses, leaving its best one to be
     * understood as-is. It has no effect when the recognizer was not asked for alternatives.
     */
    private val nBestReranker: NBestRecognitionReranker? = null,
    /**
     * Null disables synthesising the next turn's slot-filled replies ahead of time. It changes
     * nothing about which reply is chosen or spoken -- only whether speaking it has to wait for the
     * engine.
     */
    private val slotReplyPrefetcher: SlotReplyPrefetcher? = null,
    private val elapsedRealtimeNanos: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
) : CallSessionController, DialogueContextPresetController, SecondaryRecognitionMetricsSource,
    SecondaryRecognitionExperimentController,
    SceneConfidenceMetricsSource,
    RuleClassificationMetricsSource,
    NBestRerankMetricsSource,
    TurnPipelineMetricsSource {
    private val mutableState = MutableStateFlow(CallSessionSnapshot())
    override val state: StateFlow<CallSessionSnapshot> = mutableState.asStateFlow()
    private val mutableSecondaryRecognitionMetrics = MutableStateFlow<SecondaryRecognitionFusionMetrics?>(null)
    override val latestSecondaryRecognitionMetrics: StateFlow<SecondaryRecognitionFusionMetrics?> =
        mutableSecondaryRecognitionMetrics.asStateFlow()
    private val mutableLatestNBestRerank = MutableStateFlow<NBestRerankObservation?>(null)
    override val latestNBestRerank: StateFlow<NBestRerankObservation?> = mutableLatestNBestRerank.asStateFlow()
    private val mutableLatestRuleClassification = MutableStateFlow<RuleClassificationResult?>(null)
    override val latestRuleClassification: StateFlow<RuleClassificationResult?> =
        mutableLatestRuleClassification.asStateFlow()
    private val mutableLatestSceneConfidenceState = MutableStateFlow(SceneConfidenceState.UNKNOWN)
    override val latestSceneConfidenceState: StateFlow<SceneConfidenceState> =
        mutableLatestSceneConfidenceState.asStateFlow()
    private val mutableLatestTurnPipelineMetrics = MutableStateFlow<TurnPipelineMetrics?>(null)
    override val latestTurnPipelineMetrics: StateFlow<TurnPipelineMetrics?> =
        mutableLatestTurnPipelineMetrics.asStateFlow()
    private val operationMutex = Mutex()
    private val stopRequested = AtomicBoolean(false)
    /** Capture windows that closed without speech since the caller was last heard. */
    private val consecutiveSilentTurns = AtomicInteger(0)
    private val formalDeliveryEntityKeys = setOf("location", "issueType", "orderNumber", "estimatedTime")
    private var dialogueContext: DialogueContext? = null
    private var pendingInitialScene: SceneType? = null
    private var startedAtMillis: Long = 0L
    private var successfulRecordingFragments = 0
    private val pendingRecordingWrites = ArrayList<Deferred<AppResult<Boolean>>>()
    private val sceneRecognitionPolicy = sceneHotwords?.let(::SceneRecognitionPolicy)
    private val sceneVocabularyTracker = sceneRecognitionPolicy?.let { policy ->
        SceneVocabularyTracker(policy, checkNotNull(sceneHotwords))
    }
    @Volatile private var secondaryRecognitionExperimentMode =
        SecondaryRecognitionExperimentMode.DISABLED

    // Automated (no-UI) multi-turn loop for external calls.
    private var autoLoopJob: Job? = null
    /** Speculative synthesis of the next turn's replies. Always cancelled before a reply is due. */
    private var replyPrefetchJob: Job? = null
    @Volatile private var activeTurnAudio: AudioInputSource? = null
    @Volatile private var activeResponseRoute: ExternalCallResponseRoute? = null

    private data class RefinedRecognition(
        val primary: RecognitionResult,
        val secondaryEvidence: SecondaryRecognitionEvidence? = null,
        /**
         * A later-ranked hypothesis of the same decode, chosen for understanding only. Null keeps
         * the recognizer's best hypothesis, which is also always what the transcript records.
         */
        val understoodText: String? = null,
    )

    private data class CapturedTurn(
        val audio: CapturedAudio,
        val primaryRecognition: AppResult<RecognitionResult>? = null,
    )

    override fun setSecondaryRecognitionExperimentMode(mode: SecondaryRecognitionExperimentMode) {
        secondaryRecognitionExperimentMode = mode
    }

    override suspend fun simulateIncoming(callerName: String?, callerNumber: String) {
        var releasePreviousRuntime = false
        operationMutex.withLock {
            stopRequested.set(false)
            consecutiveSilentTurns.set(0)
            val oldId = mutableState.value.sessionId
            if (oldId != null && mutableState.value.phase !in setOf(SessionPhase.IDLE, SessionPhase.COMPLETED, SessionPhase.ERROR)) {
                cancelRecordingWritesLocked()
                recordingStore.discardSession(oldId)
                releasePreviousRuntime = true
            }
            takeover.clear()
            val sessionId = UUID.randomUUID().toString()
            startedAtMillis = clock.nowEpochMillis()
            successfulRecordingFragments = 0
            sceneVocabularyTracker?.reset()
            mutableSecondaryRecognitionMetrics.value = null
            mutableLatestRuleClassification.value = null
            mutableLatestSceneConfidenceState.value = SceneConfidenceState.UNKNOWN
            mutableLatestTurnPipelineMetrics.value = null
            dialogueContext = DialogueContext(sessionId)
            pendingInitialScene = null
            mutableState.value = CallSessionSnapshot(
                sessionId = sessionId,
                callerName = callerName,
                callerNumber = callerNumber,
                callStatus = CallStatus.RINGING,
                phase = SessionPhase.RINGING,
                inputMode = settings.current().defaultInputMode,
            )
        }
        if (releasePreviousRuntime) speechRuntime.onSessionEnded()
        speechRuntime.onIncoming()
    }

    override suspend fun decline() = operationMutex.withLock {
        val current = mutableState.value
        if (current.phase != SessionPhase.RINGING) return@withLock
        mutableState.value = current.copy(callStatus = CallStatus.DECLINED, phase = SessionPhase.COMPLETED)
        speechRuntime.onSessionEnded()
    }

    override suspend fun acceptNormally() = operationMutex.withLock {
        val current = mutableState.value
        if (current.phase != SessionPhase.RINGING) return@withLock
        mutableState.value = current.copy(callStatus = CallStatus.HUMAN_TAKEOVER, phase = SessionPhase.COMPLETED)
        speechRuntime.onSessionEnded()
    }

    override suspend fun acceptWithAi(inputMode: InputMode) = operationMutex.withLock {
        val current = mutableState.value
        val sessionId = current.sessionId ?: return@withLock
        if (current.phase != SessionPhase.RINGING) return@withLock
        stopRequested.set(false)
            consecutiveSilentTurns.set(0)
        mutableState.value = current.copy(
            callStatus = CallStatus.ACTIVE_AI,
            phase = SessionPhase.OPENING,
            inputMode = inputMode,
            lastError = null,
        )
        startDownlinkRecordingForMicrophoneInput(sessionId, inputMode)
        val opening = openingWithPreset(sessionId)
        dialogueContext = opening.context
        applyReplyMetadata(opening)
        appendAssistantAndSpeakLocked(opening.reply)
        val prompt = settings.current().recordingPrompt.trim()
        if (prompt.isNotEmpty()) appendAssistantAndSpeakLocked(prompt)
        mutableState.value = mutableState.value.copy(phase = SessionPhase.AWAITING_INPUT, dialogueStateId = opening.context.stateId)
    }

    /**
     * External-call entry: accept a ringing session with AI and then drive the conversation
     * automatically, turn after turn, with NO UI button presses. [turnAudio] supplies one
     * VAD-segmented buffer per turn (mic for SIMULATED, a call-stream wrapper for VoIP).
     *
     * The loop plays the opening under the mutex, then repeatedly captures → recognizes → responds
     * until the dialogue decides to end, capture is cancelled (hang up), or the session leaves the
     * active-AI state. Capture and TTS are strictly sequential per turn (never overlapping), so the
     * assistant never records its own speech.
     */
    override suspend fun acceptExternalWithAi(
        turnAudio: AudioInputSource,
        responseRoute: ExternalCallResponseRoute?,
    ) {
        operationMutex.withLock {
            val current = mutableState.value
            val sessionId = current.sessionId ?: return@withLock
            if (current.phase != SessionPhase.RINGING) {
                // Refusing here is correct -- a session already past ringing must not be re-opened --
                // but refusing quietly is what made a real call impossible to diagnose. The caller
                // heard nothing, the assistant played to the handset, and every artifact looked
                // healthy because the takeover that never happened left no trace of not happening.
                mutableState.value = current.copy(
                    lastError = "通话已在进行中（${current.phase}），无法改用通话音频接管",
                )
                return@withLock
            }
            stopRequested.set(false)
            consecutiveSilentTurns.set(0)
            activeTurnAudio = turnAudio
            activeResponseRoute = responseRoute
            mutableState.value = current.copy(
                callStatus = CallStatus.ACTIVE_AI,
                phase = SessionPhase.OPENING,
                inputMode = turnAudio.mode,
                lastError = null,
            )
            if (turnAudio !is RemoteAudioInputSource) {
                startDownlinkRecordingForMicrophoneInput(sessionId, turnAudio.mode)
            }
            val opening = openingWithPreset(sessionId)
            dialogueContext = opening.context
            applyReplyMetadata(opening)
            appendAssistantAndSpeakLocked(opening.reply)
            val prompt = settings.current().recordingPrompt.trim()
            if (prompt.isNotEmpty()) appendAssistantAndSpeakLocked(prompt)
            mutableState.value = mutableState.value.copy(
                phase = SessionPhase.AWAITING_INPUT,
                dialogueStateId = opening.context.stateId,
            )
        }
        if (mutableState.value.callStatus != CallStatus.ACTIVE_AI) return
        autoLoopJob?.cancel()
        autoLoopJob = sessionScope.launch {
            while (currentCoroutineContext().isActive && !stopRequested.get()) {
                if (!runAutomatedTurn(turnAudio)) break
            }
        }
    }

    /** Runs one automated turn under the session mutex; returns whether to continue looping. */
    private suspend fun runAutomatedTurn(turnAudio: AudioInputSource): Boolean = operationMutex.withLock {
        if (!canAcceptTurn()) return@withLock false
        val sessionId = mutableState.value.sessionId ?: return@withLock false
        mutableState.value = mutableState.value.copy(phase = SessionPhase.RECORDING, inputMode = turnAudio.mode)
        when (
            val captured = captureTurn(
                source = turnAudio,
                request = CaptureRequest(
                    sessionId,
                    maxDurationMillis = speechRuntime.currentPolicy().maxTurnDurationMillis,
                ),
            )
        ) {
            is AppResult.Failure -> {
                if (captured.error.code == "AUDIO_CANCELLED") return@withLock false
                processCallerTurnLocked(null, true, captured.error.userMessage)
            }
            is AppResult.Success -> {
                // A turn that captured no speech is not a turn the caller took. The window closes
                // on maxTurnDurationMillis whether or not anybody spoke, and feeding that silence
                // to the recogniser produced an empty transcript, which the engine answered as a
                // failure to understand -- so a caller still gathering their thoughts was prompted
                // again, and again. The device transcript from 2026-08-08 has three assistant turns
                // in a row with no caller turn between them:
                //
                //   请问需要机主回电吗？ / 请回答是否需要回电。 / 请回答是否需要回电。
                //
                // Silence is answered by waiting. The count is bounded so a call where nobody ever
                // speaks still reaches the fallback ladder rather than listening forever.
                if (!captured.value.audio.speechDetected &&
                    consecutiveSilentTurns.incrementAndGet() <= MAX_SILENT_TURNS_BEFORE_PROMPT
                ) {
                    return@withLock true
                }
                recognizeAndProcessLocked(
                    audio = captured.value.audio,
                    primaryRecognition = captured.value.primaryRecognition,
                )
            }
        }
        turnAudio.mode != InputMode.PRESET_AUDIO &&
            !stopRequested.get() &&
            mutableState.value.callStatus == CallStatus.ACTIVE_AI &&
            mutableState.value.phase == SessionPhase.AWAITING_INPUT
    }

    private suspend fun startDownlinkRecordingForMicrophoneInput(sessionId: String, inputMode: InputMode) {
        // WAV tests provide PRESET_AUDIO directly. Starting AudioRecord here would add a second,
        // unrelated capture path and violate the file-backed test contract.
        if (inputMode != InputMode.MICROPHONE) return
        downlinkRecorder?.let {
            when (val result = it.start(sessionId)) {
                is AppResult.Failure -> markRecordingFailure(result.error.code, result.error.userMessage)
                is AppResult.Success -> Unit
            }
        }
    }

    override suspend fun setInputMode(mode: InputMode) = operationMutex.withLock {
        val current = mutableState.value
        if (current.callStatus == CallStatus.ACTIVE_AI && current.phase == SessionPhase.AWAITING_INPUT) {
            mutableState.value = current.copy(inputMode = mode)
        }
    }

    override suspend fun presetNextDialogueContext(initialScene: SceneType) = operationMutex.withLock {
        require(initialScene != SceneType.UNCLASSIFIED) {
            "The preset scene must be a classified business scene"
        }
        check(mutableState.value.phase == SessionPhase.RINGING) {
            "Dialogue context can only be preset before the AI accepts the call"
        }
        pendingInitialScene = initialScene
    }

    override suspend fun submitText(text: String) = operationMutex.withLock {
        if (!canAcceptTurn()) return@withLock
        processCallerTurnLocked(text.trim().takeIf { it.isNotEmpty() }, text.isBlank(), null)
    }

    override suspend fun submitPreset(presetId: String) = operationMutex.withLock {
        if (!canAcceptTurn()) return@withLock
        val sessionId = mutableState.value.sessionId ?: return@withLock
        val source = audioInputs.sourceFor(InputMode.PRESET_AUDIO)
        if (source == null) {
            setErrorLocked("预设音频输入模块不可用")
            return@withLock
        }
        mutableState.value = mutableState.value.copy(phase = SessionPhase.RECORDING, inputMode = InputMode.PRESET_AUDIO)
        when (
            val captured = source.capture(
                CaptureRequest(
                    sessionId = sessionId,
                    maxDurationMillis = speechRuntime.currentPolicy().maxTurnDurationMillis,
                    presetId = presetId,
                ),
            )
        ) {
            is AppResult.Failure -> {
                if (captured.error.code == "AUDIO_CANCELLED") return@withLock
                processCallerTurnLocked(null, true, captured.error.userMessage)
            }
            is AppResult.Success -> recognizeAndProcessLocked(captured.value)
        }
    }

    override suspend fun captureMicrophoneTurn() = operationMutex.withLock {
        if (!canAcceptTurn()) return@withLock
        val sessionId = mutableState.value.sessionId ?: return@withLock
        val source = audioInputs.sourceFor(InputMode.MICROPHONE)
        if (source == null) {
            setErrorLocked("麦克风输入模块不可用")
            return@withLock
        }
        mutableState.value = mutableState.value.copy(phase = SessionPhase.RECORDING, inputMode = InputMode.MICROPHONE)
        when (
            val captured = captureTurn(
                source = source,
                request = CaptureRequest(
                    sessionId,
                    maxDurationMillis = speechRuntime.currentPolicy().maxTurnDurationMillis,
                ),
            )
        ) {
            is AppResult.Failure -> {
                if (captured.error.code == "AUDIO_CANCELLED") return@withLock
                processCallerTurnLocked(null, true, captured.error.userMessage)
            }
            is AppResult.Success -> recognizeAndProcessLocked(
                audio = captured.value.audio,
                primaryRecognition = captured.value.primaryRecognition,
            )
        }
    }

    override suspend fun requestHumanTakeover() = operationMutex.withLock {
        val current = mutableState.value
        val sessionId = current.sessionId ?: return@withLock
        if (current.callStatus != CallStatus.ACTIVE_AI) return@withLock
        takeover.request(sessionId)
        val event = TranscriptTurn(Speaker.SYSTEM, "请求机主接管", clock.nowEpochMillis())
        mutableState.value = current.copy(
            callStatus = CallStatus.REQUESTING_TAKEOVER,
            phase = SessionPhase.REQUESTING_TAKEOVER,
            takeoverRequested = true,
            transcript = current.transcript + event,
        )
    }

    override suspend fun end(reason: String) {
        // Cancel capture before taking the session mutex so backgrounding can unblock AudioRecord promptly.
        stopRequested.set(true)
        autoLoopJob?.cancel()
        autoLoopJob = null
        replyPrefetchJob?.cancel()
        replyPrefetchJob = null
        audioInputs.sourceFor(InputMode.MICROPHONE)?.cancel()
        audioInputs.sourceFor(InputMode.PRESET_AUDIO)?.cancel()
        activeTurnAudio?.cancel()
        activeResponseRoute = null
        audioOutput.stop()
        // Stop downlink recording early to unblock the capture thread.
        downlinkRecorder?.stop()
        operationMutex.withLock {
            val current = mutableState.value
            if (current.sessionId == null || current.phase in setOf(SessionPhase.IDLE, SessionPhase.COMPLETED)) return@withLock
            val withEvent = current.copy(
                transcript = current.transcript + TranscriptTurn(Speaker.SYSTEM, "代接结束：$reason", clock.nowEpochMillis()),
                phase = SessionPhase.ENDING,
            )
            mutableState.value = withEvent
            finalizeLocked(if (current.takeoverRequested) CallStatus.INTERRUPTED else CallStatus.COMPLETED)
        }
    }

    override suspend fun reset() {
        stopRequested.set(true)
        autoLoopJob?.cancel()
        autoLoopJob = null
        replyPrefetchJob?.cancel()
        replyPrefetchJob = null
        activeTurnAudio?.cancel()
        activeTurnAudio = null
        activeResponseRoute = null
        // Stop and release downlink recorder
        downlinkRecorder?.release()
        operationMutex.withLock {
            val sessionId = mutableState.value.sessionId
            cancelRecordingWritesLocked()
            if (sessionId != null && mutableState.value.completedRecordId == null) recordingStore.discardSession(sessionId)
            audioOutput.stop()
            takeover.clear()
            dialogueContext = null
            pendingInitialScene = null
            successfulRecordingFragments = 0
            sceneVocabularyTracker?.reset()
            mutableSecondaryRecognitionMetrics.value = null
            mutableLatestSceneConfidenceState.value = SceneConfidenceState.UNKNOWN
            mutableLatestTurnPipelineMetrics.value = null
            mutableState.value = CallSessionSnapshot()
            speechRuntime.onSessionEnded()
        }
    }

    private suspend fun captureTurn(
        source: AudioInputSource,
        request: CaptureRequest,
    ): AppResult<CapturedTurn> {
        mutableLatestTurnPipelineMetrics.value = null
        val streamingSource = source as? AudioFrameStreamingInputSource
        val streamingRuntime = speechRuntime as? StreamingSpeechRuntimeManager
        if (streamingSource == null || streamingRuntime?.supportsStreamingRecognition != true) {
            return when (val captured = source.capture(request)) {
                is AppResult.Failure -> captured
                is AppResult.Success -> AppResult.Success(CapturedTurn(captured.value))
            }
        }

        val opened = streamingRuntime.openStreamingRecognition(
            sampleRateHz = PRIMARY_ASR_SAMPLE_RATE_HZ,
            context = primaryRecognitionContext(),
        )
        if (opened is AppResult.Failure) {
            return when (val captured = source.capture(request)) {
                is AppResult.Failure -> captured
                is AppResult.Success -> AppResult.Success(CapturedTurn(captured.value))
            }
        }

        val session = (opened as AppResult.Success).value
        var finished = false
        try {
            val consumeFrame: suspend (ShortArray, Int) -> AppResult<Unit> = { samples, sampleRateHz ->
                if (sampleRateHz != PRIMARY_ASR_SAMPLE_RATE_HZ) {
                    AppResult.Failure(
                        AppError("ASR_SAMPLE_RATE", "流式语音识别只接受 16kHz 单声道 PCM"),
                    )
                } else {
                    session.accept(samples)
                }
            }
            val captured = if (streamingSource is CandidateEndpointAudioInputSource) {
                streamingSource.captureStreaming(request, consumeFrame) {
                    when (val snapshot = session.snapshot()) {
                        is AppResult.Failure -> null
                        is AppResult.Success -> snapshot.value
                    }
                }
            } else {
                streamingSource.captureStreaming(request, consumeFrame)
            }
            if (captured is AppResult.Failure) return captured
            val audio = (captured as AppResult.Success).value
            val primaryRecognition = session.finish(audio.speechDetected)
            finished = true
            return AppResult.Success(CapturedTurn(audio, primaryRecognition))
        } finally {
            if (!finished) session.cancel()
        }
    }

    private fun primaryRecognitionContext(): SpeechRecognitionContext {
        val currentScene = dialogueContext?.scene?.takeIf { it != SceneType.UNCLASSIFIED }
        return SpeechRecognitionContext(
            sceneHints = currentScene?.let(::setOf).orEmpty(),
            languageTag = dialogueContext?.languageTag ?: "zh-CN",
        )
    }

    private suspend fun recognizeAndProcessLocked(
        audio: CapturedAudio,
        primaryRecognition: AppResult<RecognitionResult>? = null,
    ) {
        val sessionId = mutableState.value.sessionId ?: return
        enqueueRecordingPcm(sessionId, audio.pcm16, audio.sampleRateHz)
        mutableState.value = mutableState.value.copy(phase = SessionPhase.RECOGNIZING)
        performanceMonitor.start("single_turn_asr")
        val recognized = recognizeWithRefinement(audio, primaryRecognition)
        performanceMonitor.stop("single_turn_asr")
        recordAsrPipelineMetrics()
        if (stopRequested.get()) return
        when (recognized) {
            is AppResult.Failure -> {
                if (fragmentIsNotATurn(audio)) return
                processCallerTurnLocked(null, true, recognized.error.userMessage)
            }
            is AppResult.Success -> {
                val recognizedText = recognized.value.primary.text.trim().takeIf { it.isNotEmpty() }
                if (recognizedText == null && fragmentIsNotATurn(audio)) return
                consecutiveSilentTurns.set(0)
                processCallerTurnLocked(
                    text = recognizedText,
                    recognitionFailed = recognizedText == null,
                    error = if (recognizedText == null) "未识别到有效语音" else null,
                    confidence = recognized.value.primary.confidence,
                    secondaryRecognition = recognized.value.secondaryEvidence,
                    understoodText = recognized.value.understoodText?.takeIf { recognizedText != null },
                )
            }
        }
    }

    /**
     * True when a capture that recognized to nothing was too brief to have been a turn at all.
     *
     * The voice detector opens a window on a breath, a half syllable or a door closing, and 500 ms
     * of quiet after it is enough to commit. On 2026-08-09 that produced a 980 ms capture with no
     * words in it, and the caller -- who had merely paused -- was answered with 请回答需要或不需要回电.
     * From their side the assistant interrupted them.
     *
     * The length test applies only after recognition came back empty, never before it. A capture
     * this short is often exactly the answer the state is waiting for -- 不需要, 没有了, 好的 -- and
     * discarding those unheard would be the worse fault. What is discarded here is only audio that
     * is both too short to be a sentence and carried no words the recognizer could find.
     *
     * Bounded by the same counter as silence, so a line that emits nothing but fragments still
     * reaches the fallback ladder instead of listening forever.
     */
    private fun fragmentIsNotATurn(audio: CapturedAudio): Boolean {
        if (audio.durationMillis >= MINIMUM_UTTERANCE_DURATION_MILLIS) return false
        if (consecutiveSilentTurns.incrementAndGet() > MAX_SILENT_TURNS_BEFORE_PROMPT) return false
        // Recognition already moved the session to RECOGNIZING, and the capture loop only continues
        // from AWAITING_INPUT. Without putting the phase back, discarding the fragment would also
        // stop the call listening -- silently, and to the caller indistinguishably from a hang.
        mutableState.value = mutableState.value.copy(phase = SessionPhase.AWAITING_INPUT)
        return true
    }

    private suspend fun recognizeWithRefinement(
        audio: CapturedAudio,
        primaryRecognition: AppResult<RecognitionResult>? = null,
    ): AppResult<RefinedRecognition> {
        mutableSecondaryRecognitionMetrics.value = null
        mutableLatestNBestRerank.value = null
        // The general recognizer always owns the transcript. Scene vocabulary is a side channel.
        val firstContext = primaryRecognitionContext()
        val firstResult = when (primaryRecognition) {
            null -> speechRuntime.recognize(audio, firstContext)
            is AppResult.Success -> primaryRecognition
            is AppResult.Failure -> {
                if (primaryRecognition.error.code in STREAMING_PRIMARY_FALLBACK_ERRORS) {
                    speechRuntime.recognize(audio, firstContext)
                } else {
                    primaryRecognition
                }
            }
        }
        val firstRecognition = when (firstResult) {
            is AppResult.Failure -> return firstResult
            is AppResult.Success -> firstResult.value
        }

        val unrefined = AppResult.Success(RefinedRecognition(firstRecognition))
        if (firstRecognition.isMock) return unrefined

        // Classifying the best hypothesis is the most expensive step on this path, and both
        // refinements need it. Decide whether either can still happen before paying for it, and
        // then share the one result -- the previous code computed it for the retry alone.
        val experimentMode = secondaryRecognitionExperimentMode
        val retryPolicy = sceneRecognitionPolicy
            ?.takeIf { experimentMode != SecondaryRecognitionExperimentMode.DISABLED }
            ?.takeIf { it.supportsRetry(firstContext) }
        val reranker = nBestReranker?.takeIf { firstRecognition.alternatives.size > 1 }
        if (retryPolicy == null && reranker == null) return unrefined

        val firstMetrics = (recognizer as? RecognitionMetricsSource)?.latestRecognitionMetrics?.value
        val firstUnknownCount = firstMetrics?.unknownTokenCount ?: 0
        val firstPreview = previewRecognition(firstRecognition.text, firstUnknownCount) ?: return unrefined
        val primaryResult = AppResult.Success(
            RefinedRecognition(
                primary = firstRecognition,
                understoodText = reranker?.let { rerankAlternatives(it, firstRecognition, firstPreview) },
            ),
        )

        val policy = retryPolicy ?: return primaryResult
        val retry = policy.retryDecision(firstPreview, firstContext) ?: return primaryResult
        val secondResult = speechRuntime.recognize(audio, retry.context)
        if (secondResult !is AppResult.Success) return primaryResult
        val secondUnknownCount = (recognizer as? RecognitionMetricsSource)
            ?.latestRecognitionMetrics?.value?.unknownTokenCount ?: 0
        val secondPreview = previewRecognition(secondResult.value.text, secondUnknownCount) ?: return primaryResult
        val secondaryEvidence = policy.secondaryEvidence(firstPreview, secondPreview, retry).copy(
            allowClassifiedSceneWithoutHotword =
                experimentMode == SecondaryRecognitionExperimentMode.REVISED_POLICY,
        )
        mutableSecondaryRecognitionMetrics.value = SecondaryRecognitionFusionMetrics(
            primaryText = firstPreview.text,
            secondaryText = secondPreview.text,
            textDifferenceRate = secondaryEvidence.textDifferenceRate,
            triggerReasons = secondaryEvidence.triggerReasons,
            sceneHints = secondaryEvidence.sceneHints.map { it.id },
            matchedHotwordsByScene = secondaryEvidence.matchedHotwordsByScene,
            primaryClassification = firstPreview.classification.toMetricsSnapshot(),
            secondaryClassification = secondPreview.classification.toMetricsSnapshot(),
        )
        return AppResult.Success(
            RefinedRecognition(
                primary = firstRecognition,
                secondaryEvidence = secondaryEvidence,
            ),
        )
    }

    /**
     * Consults the recognizer's other hypotheses for the same audio and returns the one
     * understanding should use, or null to keep the recognizer's own best.
     *
     * The alternatives come from the same unconstrained decode as the transcript, so unlike the
     * scene-vocabulary retry this costs no second pass over the audio -- only a classification per
     * hypothesis examined, and only for turns [NBestRecognitionReranker.shouldRerank] admits.
     *
     * Those classifications run at once rather than one after another. They are independent -- each
     * reads the same immutable compiled rule set and shares nothing but a log line -- and the caller
     * is waiting on all of them, so serialising them only adds up their latencies. On the
     * 2026-08-09 21:20 call the one turn that reranked spent 328 ms walking six hypotheses at
     * 53-105 ms each, on the turn whose transcript was least certain and therefore least able to
     * afford it.
     */
    private suspend fun rerankAlternatives(
        reranker: NBestRecognitionReranker,
        recognition: RecognitionResult,
        preview: RecognitionPreview,
    ): String? {
        val alternativeTexts = recognition.alternatives.map(RecognitionAlternative::text)
        val top = RerankCandidate(rank = 0, text = preview.text, classification = preview.classification)
        val candidates = if (reranker.shouldRerank(preview.classification, alternativeTexts)) {
            val examined = withContext(Dispatchers.Default) {
                recognition.alternatives
                    .mapIndexed { index, alternative ->
                        async {
                            if (index == 0) {
                                null
                            } else {
                                previewRecognition(alternative.text)?.classification?.let { classification ->
                                    RerankCandidate(index, alternative.text, classification)
                                }
                            }
                        }
                    }
                    .awaitAll()
            }
            // Rank order is what the reranker breaks ties by, so it survives the parallel fan-out.
            listOf(top) + examined.filterNotNull()
        } else {
            listOf(top)
        }
        val decision = reranker.rerank(candidates)
        mutableLatestNBestRerank.value = NBestRerankObservation(
            transcript = preview.text,
            understoodText = decision.text,
            chosenRank = decision.chosenRank,
            reasons = decision.reasons,
            alternatives = alternativeTexts,
            examinedHypotheses = candidates.size,
        )
        return decision.text.takeIf { decision.changedHypothesis }
    }

    private suspend fun previewRecognition(text: String, unknownTokenCount: Int = 0): RecognitionPreview? {
        val classifier = intentClassifier ?: return null
        val context = dialogueContext ?: return null
        val classification = classifier.classifyDetailed(
            text = text,
            enabledScenes = settings.current().enabledScenes,
            context = RuleClassificationContext(
                lockedScene = context.scene.takeIf { it != SceneType.UNCLASSIFIED },
                stateId = context.stateId,
                existingSlots = context.slots,
                languageTag = context.languageTag,
            ),
        ) ?: return null
        return RecognitionPreview(text, classification, unknownTokenCount)
    }

    private fun RuleClassificationResult.toMetricsSnapshot() = RecognitionClassificationSnapshot(
        scene = scene,
        confidence = confidence,
        sceneMargin = sceneMargin,
        entities = extractedSlots.filterKeys(formalDeliveryEntityKeys::contains),
    )

    /**
     * @param text what the caller said, as the transcript will record it.
     * @param understoodText the wording the dialogue engine reads, when it differs from [text].
     *   It differs only when a later-ranked recognition hypothesis was chosen for understanding,
     *   and it never reaches the transcript: the record has to show what the recognizer reported as
     *   its own best reading, so that a substitution stays visible instead of being written over.
     */
    private suspend fun processCallerTurnLocked(
        text: String?,
        recognitionFailed: Boolean,
        error: String?,
        confidence: Float? = null,
        secondaryRecognition: SecondaryRecognitionEvidence? = null,
        understoodText: String? = null,
    ) {
        val context = dialogueContext ?: return
        mutableLatestRuleClassification.value = null
        val current = mutableState.value
        val callerTurn = text?.let { TranscriptTurn(Speaker.CALLER, it, clock.nowEpochMillis(), confidence) }
        mutableState.value = current.copy(
            phase = SessionPhase.THINKING,
            transcript = if (callerTurn == null) current.transcript else current.transcript + callerTurn,
            recognitionFailed = current.recognitionFailed || recognitionFailed,
            lastError = error,
        )
        if (stopRequested.get()) return
        performanceMonitor.start("single_turn_response_start")
        performanceMonitor.start("single_turn_total")
        val nluStartedAt = elapsedRealtimeNanos()
        updateTurnPipelineMetrics { metrics ->
            metrics.copy(nluStartedAtElapsedRealtimeNanos = nluStartedAt)
        }
        val decision = try {
            dialogueEngine.processWithEvidence(
                context = context,
                callerText = understoodText ?: text,
                recognitionFailed = recognitionFailed,
                enabledScenes = settings.current().enabledScenes,
                secondaryRecognition = secondaryRecognition,
            )
        } finally {
            val nluCompletedAt = elapsedRealtimeNanos()
            updateTurnPipelineMetrics { metrics ->
                metrics.copy(nluCompletedAtElapsedRealtimeNanos = nluCompletedAt)
            }
        }
        mutableLatestRuleClassification.value = decision.classification
        if (secondaryRecognition != null) {
            val classification = decision.classification
            val reasons = classification?.let { result ->
                (result.matchedEvidence + result.rejectedEvidence).filter { it.startsWith("secondary:") }
            }.orEmpty()
            mutableSecondaryRecognitionMetrics.value = mutableSecondaryRecognitionMetrics.value?.copy(
                finalClassification = classification?.toMetricsSnapshot(),
                decisionReasons = reasons,
                evidenceUsed = reasons.any { reason ->
                    reason.startsWith("secondary:scene:accepted:") ||
                        reason.startsWith("secondary:entity:accepted:") ||
                        reason.startsWith("secondary:intent:accepted:")
                },
            )
        }
        dialogueContext = decision.context
        mutableLatestSceneConfidenceState.value = decision.context.sceneConfidenceState
        sceneVocabularyTracker?.observe(decision.classification)
        applyReplyMetadata(decision)
        if (stopRequested.get()) {
            performanceMonitor.stop("single_turn_response_start")
            performanceMonitor.stop("single_turn_total")
            return
        }
        mutableState.value = mutableState.value.copy(
            scene = decision.context.scene,
            dialogueStateId = decision.context.stateId,
            structuredResult = StructuredResult().merge(decision.context.scene, decision.context.slots),
            recognitionFailed = mutableState.value.recognitionFailed || decision.recognitionFailure,
        )
        appendAssistantAndSpeakLocked(decision.reply)
        performanceMonitor.stop("single_turn_total")
        if (decision.shouldEnd) finalizeLocked(CallStatus.COMPLETED)
        else mutableState.value = mutableState.value.copy(phase = SessionPhase.AWAITING_INPUT)
    }

    private fun applyReplyMetadata(decision: com.example.calldelegate.domain.model.DialogueDecision) {
        mutableState.value = mutableState.value.copy(
            latestReplyTemplateId = decision.replyTemplateId,
            latestReplyVariables = decision.replyVariables,
            latestReplyIsFallbackTemplate = decision.isFallbackTemplate,
            latestReplyFallbackReason = decision.fallbackReason,
            latestReplySafe = decision.replySafe,
            latestReplyComplianceFlags = decision.complianceFlags,
        )
    }

    /**
     * Starts synthesising the replies the next caller turn could need, and stops doing so before
     * this turn's own reply is synthesised.
     *
     * Both halves matter. The prefetch is only free while the engine is idle, and the engine takes
     * one lock for everything, so a prefetch still running when a reply is due would be charged to
     * the caller instead of saving them -- exactly the cost it exists to remove. Cancelling here
     * leaves the whole of the caller's turn, from the moment the assistant stops speaking, as the
     * window it runs in.
     */
    private suspend fun cancelReplyPrefetch() {
        replyPrefetchJob?.cancelAndJoin()
        replyPrefetchJob = null
    }

    private fun startReplyPrefetch() {
        val prefetcher = slotReplyPrefetcher ?: return
        val context = dialogueContext ?: return
        replyPrefetchJob = sessionScope.launch(workDispatcher) {
            val startedAt = elapsedRealtimeNanos()
            // A prefetch that fails has simply not happened; the reply path synthesises as before.
            val outcome = runCatching {
                prefetcher.prefetch(
                    sceneId = context.scene.id,
                    stateId = context.stateId,
                    slots = context.slots,
                    languageTag = context.languageTag,
                )
            }
            val result = outcome.getOrNull()
            android.util.Log.i(
                "TtsPrefetch",
                "reply prefetch: scene=${context.scene.id} state=${context.stateId} " +
                    "slots=${context.slots.keys.sorted()} " +
                    "candidates=${result?.candidates ?: -1} generated=${result?.generated ?: -1} " +
                    "alreadyStored=${result?.alreadyStored ?: -1} failed=${result?.failed ?: -1} " +
                    "took=${(elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms " +
                    "error=${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
            )
        }
    }

    private suspend fun appendAssistantAndSpeakLocked(text: String) {
        val sessionId = mutableState.value.sessionId ?: return
        cancelReplyPrefetch()
        mutableState.value = mutableState.value.copy(
            phase = SessionPhase.SPEAKING,
            latestReply = text,
            transcript = mutableState.value.transcript + TranscriptTurn(Speaker.ASSISTANT, text, clock.nowEpochMillis()),
        )
        val ttsStartedAt = elapsedRealtimeNanos()
        updateTurnPipelineMetrics { metrics ->
            metrics.copy(ttsStartedAtElapsedRealtimeNanos = ttsStartedAt)
        }
        val speech = try {
            speechRuntime.synthesize(text, sessionId)
        } finally {
            val ttsCompletedAt = elapsedRealtimeNanos()
            updateTurnPipelineMetrics { metrics ->
                metrics.copy(ttsCompletedAtElapsedRealtimeNanos = ttsCompletedAt)
            }
            // Every stage of the turn has a timestamp by now and none of them was ever visible on a
            // device -- the metrics live in a StateFlow the call UI does not surface, so "waiting a
            // long time after I stop speaking" could not be attributed to anything. One line makes
            // the split readable in logcat.
            logTurnPipelineBreakdown(text)
        }
        when (speech) {
            is AppResult.Failure -> {
                performanceMonitor.stop("single_turn_response_start")
                mutableState.value = mutableState.value.copy(lastError = speech.error.userMessage)
            }
            is AppResult.Success -> {
                if (stopRequested.get()) {
                    performanceMonitor.stop("single_turn_response_start")
                    return
                }
                enqueueRecordingPcm(sessionId, speech.value.pcm16, speech.value.sampleRateHz)
                // Stops immediately before the output sink writes/starts playback.
                performanceMonitor.stop("single_turn_response_start")
                playAssistantSpeech(speech.value)
                // The dialogue has already moved to the state this reply leads to, so the slots it
                // knows are the ones the next reply will be built from.
                startReplyPrefetch()
            }
        }
    }

    /**
     * Prints one turn as its stages, so a slow reply can be attributed rather than guessed at.
     *
     * asr is recognition after the caller stopped speaking, nlu is classification and reply
     * selection, tts is synthesis. Playback is not here -- it is timed by the uplink sink, which
     * logs writtenFrames and playedFrames of its own.
     */
    private fun logTurnPipelineBreakdown(replyText: String) {
        val metrics = mutableLatestTurnPipelineMetrics.value ?: return
        fun span(from: Long?, to: Long?): String =
            if (from == null || to == null) "-" else "${(to - from) / 1_000_000}ms"
        android.util.Log.i(
            "TurnPipeline",
            "turn stages: asr=${span(metrics.asrStartedAtElapsedRealtimeNanos, metrics.asrCompletedAtElapsedRealtimeNanos)} " +
                "nlu=${span(metrics.nluStartedAtElapsedRealtimeNanos, metrics.nluCompletedAtElapsedRealtimeNanos)} " +
                "tts=${span(metrics.ttsStartedAtElapsedRealtimeNanos, metrics.ttsCompletedAtElapsedRealtimeNanos)} " +
                "asrCompute=${metrics.asrComputeDurationMillis ?: -1}ms " +
                "replyChars=${replyText.length}",
        )
        logRecognitionBreakdown()
    }

    /**
     * Prints what the recognition seconds were spent on, when the recognizer reports it.
     *
     * The first device reading put recognition at two to seven seconds a turn against tens of
     * milliseconds for classification, so "which stage" is answered and the question becomes which
     * part of that stage. attempts matters most: the compute figure above is a sum, so a turn that
     * was decoded twice looks twice as slow as the decoder is. audio says how much speech was fed
     * in, which turns compute into a real-time factor rather than a bare number.
     */
    private fun logRecognitionBreakdown() {
        val attempts = (recognizer as? RecognitionAttemptsMetricsSource)
            ?.latestRecognitionAttempts?.value.orEmpty()
            .ifEmpty { listOfNotNull((recognizer as? RecognitionMetricsSource)?.latestRecognitionMetrics?.value) }
        if (attempts.isEmpty()) return
        fun sum(select: (RecognitionComputeMetrics) -> Long?): String =
            attempts.sumOf { select(it) ?: 0L }.toString() + "ms"
        val audioMillis = attempts.sumOf { attempt ->
            attempt.inputSamples.toLong() * MILLIS_PER_SECOND / attempt.inputSampleRateHz
        }
        val computeMillis = attempts.sumOf { it.computeDurationMillis }
        val workerThreadIds = attempts.mapNotNull { it.voskWorkerThreadId }.distinct().joinToString(",").ifBlank { "-" }
        val workerPriorities = attempts.mapNotNull { it.voskWorkerThreadPriority }.distinct().joinToString(",").ifBlank { "-" }
        val allowedCpus = attempts.mapNotNull { it.voskWorkerCpusAllowedList }.distinct().joinToString(",").ifBlank { "-" }
        val cpusetGroups = attempts.mapNotNull { it.voskWorkerCpusetGroup }.distinct().joinToString(",").ifBlank { "-" }
        android.util.Log.i(
            "TurnPipeline",
            "  recognition: attempts=${attempts.size} audio=${audioMillis}ms compute=${computeMillis}ms " +
                "rtf=${if (audioMillis > 0) "%.2f".format(computeMillis.toDouble() / audioMillis) else "-"} " +
                "create=${sum { it.recognizerCreateDurationMillis }} accept=${sum { it.voskAcceptComputeDurationMillis }} " +
                "acceptCpu=${sum { it.voskAcceptCpuDurationMillis }} " +
                "queueWaitTotal=${sum { it.voskQueueWaitDurationMillis }} " +
                "queueWaitMax=${attempts.maxOfOrNull { it.voskQueueWaitMaxMillis ?: 0L } ?: 0}ms " +
                "queueDepthMax=${attempts.maxOfOrNull { it.voskQueueMaxDepth ?: 0 } ?: 0} " +
                "frames=${attempts.sumOf { it.voskAcceptedFrameCount ?: 0 }} " +
                "drain=${sum { it.voskDrainDurationMillis }} final=${sum { it.voskFinalResultDurationMillis }} " +
                "tid=$workerThreadIds priority=$workerPriorities cpus=$allowedCpus cpuset=$cpusetGroups",
        )
    }

    private fun recordAsrPipelineMetrics() {
        val attempts = (recognizer as? RecognitionAttemptsMetricsSource)
            ?.latestRecognitionAttempts?.value.orEmpty()
        val latest = (recognizer as? RecognitionMetricsSource)?.latestRecognitionMetrics?.value
        val observedAttempts = if (attempts.isNotEmpty()) attempts else listOfNotNull(latest)
        if (observedAttempts.isEmpty()) return
        updateTurnPipelineMetrics { metrics ->
            metrics.copy(
                asrStartedAtElapsedRealtimeNanos = observedAttempts.minOf {
                    it.startedAtElapsedRealtimeNanos
                },
                asrCompletedAtElapsedRealtimeNanos = observedAttempts.maxOf {
                    it.completedAtElapsedRealtimeNanos
                },
                asrComputeDurationMillis = observedAttempts.sumOf { it.computeDurationMillis },
            )
        }
    }

    private fun updateTurnPipelineMetrics(
        update: (TurnPipelineMetrics) -> TurnPipelineMetrics,
    ) {
        mutableLatestTurnPipelineMetrics.value = update(
            mutableLatestTurnPipelineMetrics.value ?: TurnPipelineMetrics(),
        )
    }

    private suspend fun playAssistantSpeech(speech: SynthesizedSpeech) {
        val route = activeResponseRoute
        if (route == null) {
            playLocally(speech)
            return
        }
        val (responseResult, localResult) = if (route.monitorLocally) {
            coroutineScope {
                val localPlayback = async { audioOutput.play(speech) }
                val uplinkResult = route.sink.playToCall(route.callId, speech)
                uplinkResult to localPlayback.await()
            }
        } else {
            route.sink.playToCall(route.callId, speech) to null
        }
        if (localResult is AppResult.Failure) {
            markPlaybackFailure(localResult.error.code, localResult.error.userMessage)
        }
        when (val result = responseResult) {
            CallResponseResult.PlayedToCallUplink -> Unit
            CallResponseResult.LocalPlaybackOnly -> if (localResult == null) playLocally(speech)
            is CallResponseResult.Unsupported -> {
                markPlaybackFailure("CALL_UPLINK_UNSUPPORTED", result.reason)
            }
            is CallResponseResult.Failed -> {
                markPlaybackFailure(result.code, result.message)
            }
        }
    }

    private suspend fun playLocally(speech: SynthesizedSpeech) {
        when (val played = audioOutput.play(speech)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> markPlaybackFailure(played.error.code, played.error.userMessage)
        }
    }

    private suspend fun finalizeLocked(status: CallStatus) {
        val initial = mutableState.value
        val sessionId = initial.sessionId ?: return
        mutableState.value = initial.copy(phase = SessionPhase.ENDING)

        // Stop continuous downlink recording before finalizing the WAV.
        // Failure is logged but non-fatal — the session recording still finalizes.
        downlinkRecorder?.let {
            when (val stopResult = it.stop()) {
                is AppResult.Failure -> markRecordingFailure(stopResult.error.code, stopResult.error.userMessage)
                is AppResult.Success -> Unit
            }
        }

        drainRecordingWritesLocked()
        val current = mutableState.value
        var recordingFailure = current.recordingFailure
        val audioPath = when (val finalized = recordingStore.finalizeSession(sessionId)) {
            is AppResult.Success -> {
                if (finalized.value == null && recordingFailure == null) {
                    recordingFailure = AudioFailure("AUDIO_RECORDING_EMPTY", "未生成可播放的会话录音")
                }
                finalized.value
            }
            is AppResult.Failure -> {
                if (recordingFailure == null) {
                    recordingFailure = AudioFailure(finalized.error.code, finalized.error.userMessage)
                }
                null
            }
        }
        val recordingIntegrity = when {
            audioPath == null -> RecordingIntegrity.FAILED
            recordingFailure != null && successfulRecordingFragments > 0 -> RecordingIntegrity.PARTIAL
            recordingFailure != null -> RecordingIntegrity.FAILED
            else -> RecordingIntegrity.COMPLETE
        }
        val summary = summaryGenerator.generate(current.scene, current.structuredResult, current.transcript)
        val record = CallRecord(
            id = sessionId,
            callerName = current.callerName,
            callerNumber = current.callerNumber,
            scene = current.scene,
            summary = summary,
            structuredResult = current.structuredResult,
            transcript = current.transcript,
            audioPath = audioPath,
            startedAtMillis = startedAtMillis,
            endedAtMillis = clock.nowEpochMillis(),
            status = status,
            inputMode = current.inputMode,
            recognitionFailed = current.recognitionFailed,
            takeoverRequested = current.takeoverRequested,
            recordingIntegrity = recordingIntegrity,
            recordingFailure = recordingFailure,
            playbackFailure = current.playbackFailure,
        )
        when (val saved = calls.save(record)) {
            is AppResult.Success -> mutableState.value = mutableState.value.copy(
                callStatus = status,
                phase = SessionPhase.COMPLETED,
                completedRecordId = sessionId,
                activeRecordingPath = audioPath,
                recordingIntegrity = recordingIntegrity,
                recordingFailure = recordingFailure,
                playbackFailure = current.playbackFailure,
                lastError = recordingFailure?.message ?: mutableState.value.lastError,
            )
            is AppResult.Failure -> mutableState.value = mutableState.value.copy(
                callStatus = CallStatus.FAILED,
                phase = SessionPhase.ERROR,
                lastError = saved.error.userMessage,
                activeRecordingPath = audioPath,
                recordingIntegrity = recordingIntegrity,
                recordingFailure = recordingFailure,
                playbackFailure = current.playbackFailure,
            )
        }
        activeTurnAudio = null
        activeResponseRoute?.let { route -> runCatching { route.sink.releaseCall(route.callId) } }
        activeResponseRoute = null
        speechRuntime.onSessionEnded()
    }

    private fun canAcceptTurn(): Boolean {
        val current = mutableState.value
        return !stopRequested.get() && current.callStatus == CallStatus.ACTIVE_AI && current.phase == SessionPhase.AWAITING_INPUT
    }

    private suspend fun openingWithPreset(sessionId: String): com.example.calldelegate.domain.model.DialogueDecision {
        val initialScene = pendingInitialScene
        pendingInitialScene = null
        return if (initialScene == null) {
            dialogueEngine.opening(sessionId)
        } else {
            dialogueEngine.opening(sessionId, initialScene)
        }
    }

    private fun setErrorLocked(message: String) {
        mutableState.value = mutableState.value.copy(phase = SessionPhase.ERROR, lastError = message)
    }

    private fun enqueueRecordingPcm(
        sessionId: String,
        samples: ShortArray,
        sourceSampleRateHz: Int,
    ) {
        if (samples.isEmpty()) return
        val previousWrite = pendingRecordingWrites.lastOrNull()
        val pendingWrite = sessionScope.async(workDispatcher) {
            try {
                previousWrite?.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The previous failure is reported while draining; later fragments can still be saved.
            }
            when (val normalized = recordingAudioNormalizer.normalize(samples, sourceSampleRateHz)) {
                is AppResult.Failure -> normalized
                is AppResult.Success -> if (normalized.value.samples.isEmpty()) {
                    AppResult.Success(false)
                } else {
                    when (val saved = recordingStore.appendPcm(
                        sessionId = sessionId,
                        samples = normalized.value.samples,
                        sampleRateHz = normalized.value.sampleRateHz,
                    )) {
                        is AppResult.Failure -> saved
                        is AppResult.Success -> AppResult.Success(true)
                    }
                }
            }
        }
        pendingRecordingWrites += pendingWrite
    }

    private suspend fun drainRecordingWritesLocked() {
        val writes = pendingRecordingWrites.toList()
        pendingRecordingWrites.clear()
        for (write in writes) {
            val result = try {
                write.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppResult.Failure(
                    AppError("AUDIO_RECORDING_WRITE", "会话录音保存失败", error.message),
                )
            }
            when (result) {
                is AppResult.Failure -> markRecordingFailure(result.error.code, result.error.userMessage)
                is AppResult.Success -> if (result.value) successfulRecordingFragments += 1
            }
        }
    }

    private suspend fun cancelRecordingWritesLocked() {
        val writes = pendingRecordingWrites.toList()
        pendingRecordingWrites.clear()
        for (write in writes) write.cancel()
        for (write in writes) write.cancelAndJoin()
    }

    private fun markRecordingFailure(code: String, message: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            recordingIntegrity = RecordingIntegrity.PARTIAL,
            recordingFailure = current.recordingFailure ?: AudioFailure(code, message),
            lastError = message,
        )
    }

    private fun markPlaybackFailure(code: String, message: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            playbackFailure = current.playbackFailure ?: AudioFailure(code, message),
            lastError = message,
        )
    }
}
