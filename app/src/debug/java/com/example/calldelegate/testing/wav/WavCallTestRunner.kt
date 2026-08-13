package com.example.calldelegate.testing.wav

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.example.calldelegate.BuildConfig
import com.example.calldelegate.core.ai.adaptation.WavTestBatchRuntimeController
import com.example.calldelegate.core.ai.speech.VoskAlternativesExperimentController
import com.example.calldelegate.core.audio.capture.StreamingTurnAudioInputSource
import com.example.calldelegate.core.audio.capture.TurnCaptureObservation
import com.example.calldelegate.core.audio.capture.WavCallAudioSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.DialogueContextPresetController
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.ModelManager
import com.example.calldelegate.domain.api.PlaybackMetricsSource
import com.example.calldelegate.domain.api.RecognitionComputeMetrics
import com.example.calldelegate.domain.api.RecognitionMetricsSource
import com.example.calldelegate.domain.api.RuleClassificationMetricsSource
import com.example.calldelegate.domain.api.NBestRerankMetricsSource
import com.example.calldelegate.domain.api.SecondaryRecognitionMetricsSource
import com.example.calldelegate.domain.api.SecondaryRecognitionExperimentController
import com.example.calldelegate.domain.api.SceneConfidenceMetricsSource
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.TurnPipelineMetricsSource
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.api.VoiceActivityDetectorConfigurationSource
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.ModelType
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.SecondaryRecognitionExperimentMode
import com.example.calldelegate.domain.session.CallSessionSnapshot
import com.example.calldelegate.domain.session.SessionPhase
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.di.DebugTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only batch runner that drives the existing controller entry point with a WAV-backed
 * [StreamingTurnAudioInputSource]. It never selects the microphone source or call coordinator.
 */
class WavCallTestRunner private constructor(
    private val context: Context,
    private val controller: com.example.calldelegate.domain.api.CallSessionController,
    private val runtime: SpeechRuntimeManager,
    private val modelManager: ModelManager,
    private val deviceProfileProvider: DeviceProfileProvider,
    private val recognizer: SpeechRecognizer,
    private val audioOutput: AudioOutputSink,
    private val vad: VoiceActivityDetector,
    private val exporter: WavCallResultExporter = WavCallResultExporter(),
) {
    private val runMutex = Mutex()
    private val runGate = WavCallRunGate()
    private val utteranceCompleteness =
        com.example.calldelegate.core.ai.speech.UtteranceCompleteness()
    private var activeRun: ActiveRun? = null

    suspend fun run(request: WavCallTestRequest): AppResult<WavCallRunReport> {
        val run = ActiveRun()
        if (!runGate.tryAcquire()) {
            return AppResult.Failure(AppError("TEST_ALREADY_RUNNING", "已有 WAV 测试任务正在运行"))
        }
        runMutex.withLock { activeRun = run }
        val experimentController = controller as? SecondaryRecognitionExperimentController
        val alternativesController = recognizer as? VoskAlternativesExperimentController
        var profileOverrideApplied = false
        try {
            deviceProfileProvider.overrideNominalRamGb(request.nominalRamOverrideGb)
            profileOverrideApplied = true
            if (
                experimentController == null &&
                request.secondaryRecognitionMode != SecondaryRecognitionExperimentMode.CURRENT_POLICY
            ) {
                return AppResult.Failure(
                    AppError("SECONDARY_EXPERIMENT_UNAVAILABLE", "当前控制器不支持二次 ASR 三臂测试"),
                )
            }
            experimentController?.setSecondaryRecognitionExperimentMode(request.secondaryRecognitionMode)
            if (request.maxAlternativesOverride != null && alternativesController == null) {
                return AppResult.Failure(
                    AppError("NBEST_EXPERIMENT_UNAVAILABLE", "当前识别器不支持 N-best A/B 测试"),
                )
            }
            alternativesController?.setMaxAlternativesOverride(request.maxAlternativesOverride)
            return runBatch(request, run)
        } catch (cancelled: CancellationException) {
            run.cancelRequested.set(true)
            throw cancelled
        } catch (error: Throwable) {
            return AppResult.Failure(AppError("WAV_RUNNER", "WAV 测试运行器异常终止", error.message))
        } finally {
            if (profileOverrideApplied) {
                deviceProfileProvider.overrideNominalRamGb(null)
            }
            experimentController?.setSecondaryRecognitionExperimentMode(
                SecondaryRecognitionExperimentMode.DISABLED,
            )
            alternativesController?.setMaxAlternativesOverride(null)
            run.finished.complete(Unit)
            runMutex.withLock {
                if (activeRun === run) activeRun = null
            }
            runGate.release()
        }
    }

    /** Requests idempotent cancellation and waits until the active runner has released its resources. */
    suspend fun stop() {
        val run = runMutex.withLock { activeRun } ?: return
        run.cancelRequested.set(true)
        run.currentBridge?.cancel()
        val source = run.currentSource
        val callId = run.currentCallId
        if (source != null && callId != null) source.stop(callId)
        audioOutput.stop()
        controller.end("wav_test_cancelled")
        run.finished.await()
    }

    private suspend fun runBatch(
        request: WavCallTestRequest,
        run: ActiveRun,
    ): AppResult<WavCallRunReport> {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val outputRoot = request.outputRoot ?: context.getExternalFilesDir("wav-call-test-results")
            ?: File(context.filesDir, "wav-call-test-results")
        val (runId, resultDirectory) = try {
            exporter.createRunDirectory(outputRoot)
        } catch (error: Throwable) {
            return AppResult.Failure(AppError("RESULT_DIRECTORY", "无法创建 WAV 测试结果目录", error.message))
        }

        val manifest = try {
            WavCallManifestReader.read(request.manifestFile)
        } catch (error: WavCallManifestException) {
            return writeBatchFailure(
                runId = runId,
                directory = resultDirectory,
                request = request,
                startedAt = startedAt,
                manifestVersion = null,
                errorCode = error.code,
                errorMessage = error.message,
                asrModel = null,
                ttsModel = null,
            )
        }

        val models = try {
            modelManager.refresh()
            val asr = modelManager.activeModel(ModelType.ASR)
            val tts = modelManager.activeModel(ModelType.TTS)
            asr to tts
        } catch (error: Throwable) {
            return writeBatchFailure(
                runId = runId,
                directory = resultDirectory,
                request = request,
                startedAt = startedAt,
                manifestVersion = manifest.manifestVersion,
                errorCode = "MODEL_REFRESH",
                errorMessage = error.message ?: "无法刷新模型信息",
                asrModel = null,
                ttsModel = null,
            )
        }
        val asrModel = models.first
        val ttsModel = models.second
        val modelFailure = validateModels(asrModel, ttsModel)
        if (modelFailure != null) {
            return writeBatchFailure(
                runId = runId,
                directory = resultDirectory,
                request = request,
                startedAt = startedAt,
                manifestVersion = manifest.manifestVersion,
                errorCode = modelFailure.first,
                errorMessage = modelFailure.second,
                asrModel = asrModel,
                ttsModel = ttsModel,
            )
        }

        runtime.configure(mockMode = false)
        if (runtime.isMock) {
            return writeBatchFailure(
                runId = runId,
                directory = resultDirectory,
                request = request,
                startedAt = startedAt,
                manifestVersion = manifest.manifestVersion,
                errorCode = "WAV_BATCH_MOCK",
                errorMessage = "语音运行时未切换到真实模式",
                asrModel = asrModel,
                ttsModel = ttsModel,
            )
        }

        val batchRuntime = WavTestBatchRuntimeController(runtime)
        when (val batchStarted = batchRuntime.begin(request.disableMaxTurnDuration)) {
            is AppResult.Failure -> {
                return writeBatchFailure(
                    runId = runId,
                    directory = resultDirectory,
                    request = request,
                    startedAt = startedAt,
                    manifestVersion = manifest.manifestVersion,
                    errorCode = batchStarted.error.code,
                    errorMessage = batchStarted.error.userMessage,
                    asrModel = asrModel,
                    ttsModel = ttsModel,
                )
            }
            is AppResult.Success -> Unit
        }

        val samples = mutableListOf<WavCallSampleResult>()
        try {
            for (case in manifest.cases) {
                if (run.cancelRequested.get()) break
                samples += runCase(request, run, case)
            }
        } finally {
            run.currentBridge?.cancel()
            run.currentSource?.let { source ->
                run.currentCallId?.let { callId -> source.stop(callId) }
            }
            audioOutput.stop()
            controller.reset()
            batchRuntime.end()
        }

        if (run.cancelRequested.get() && samples.none { it.status == WavCallCaseStatus.CANCELLED }) {
            val nextCase = manifest.cases.drop(samples.size).firstOrNull()
            if (nextCase != null) {
                samples += cancelledSample(nextCase.input, request.injectionMode.name, request.measurementMode)
            }
        }
        val completedAt = SystemClock.elapsedRealtimeNanos()
        val status = if (run.cancelRequested.get()) WavCallRunStatus.CANCELLED else WavCallRunStatus.COMPLETED
        val environment = createEnvironment(request, asrModel, ttsModel)
        val summary = WavCallRunSummary(
            runId = runId,
            status = status,
            startedAtElapsedRealtimeNanos = startedAt,
            completedAtElapsedRealtimeNanos = completedAt,
            manifestVersion = manifest.manifestVersion,
            injectionMode = request.injectionMode.name,
            measurementMode = request.measurementMode.name,
            acceleratedInput = request.injectionMode.name == "AS_FAST_AS_POSSIBLE",
            environment = environment,
            modeSummaries = WavCallMetrics.summarize(samples),
            completedCaseCount = samples.size,
            failureCaseCount = samples.count { it.status != WavCallCaseStatus.PASSED },
            cancellationRequested = run.cancelRequested.get(),
        )
        return try {
            exporter.write(resultDirectory, summary, samples)
            AppResult.Success(WavCallRunReport(runId, resultDirectory, summary, samples))
        } catch (error: Throwable) {
            AppResult.Failure(AppError("RESULT_EXPORT", "无法导出 WAV 测试结果", error.message))
        }
    }

    private suspend fun runCase(
        request: WavCallTestRequest,
        run: ActiveRun,
        case: ParsedWavCallCase,
    ): WavCallSampleResult {
        val input = case.input
        if (!input.wavFile.isFile) {
            return failedSample(
                input,
                request.injectionMode.name,
                request.measurementMode,
                "WAV_MISSING",
                "WAV 文件不存在",
            )
        }

        val boundaryTracker = InjectionBoundaryTracker(
            speechStartMs = case.evaluation.speechStartMs,
            speechEndMs = case.evaluation.speechEndMs,
        )
        val callId = "wav-${input.caseId}"
        val source = WavCallAudioSource(
            wavFile = input.wavFile,
            injectionMode = request.injectionMode,
            tailSilenceMs = request.tailSilenceMs,
            monotonicNanos = { SystemClock.elapsedRealtimeNanos() },
            onFrameInjected = boundaryTracker::onFrameInjected,
        )
        var observation: TurnCaptureObservation? = null
        var sessionSnapshot: CallSessionSnapshot? = null
        var responsePlayback: com.example.calldelegate.domain.api.AudioPlaybackMetrics? = null
        var executionFailure: Pair<String, String>? = null
        val peakProcessPssKb = AtomicInteger(currentProcessPssKb() ?: 0)
        val pssSamplingJob = CoroutineScope(currentCoroutineContext()).launch {
            while (isActive) {
                currentProcessPssKb()?.let { currentPss ->
                    peakProcessPssKb.accumulateAndGet(currentPss) { previous, current -> maxOf(previous, current) }
                }
                delay(PSS_SAMPLE_INTERVAL_MS)
            }
        }
        run.currentSource = source
        run.currentCallId = callId

        try {
            when (val started = source.start(callId)) {
                is AppResult.Failure -> {
                    executionFailure = started.error.code to started.error.userMessage
                }
                is AppResult.Success -> {
                    val sourceMetrics = source.latestInjectionMetrics()
                    val boundaryFailure = validateSpeechBoundaries(case.evaluation, sourceMetrics?.originalAudioDurationMillis)
                    if (boundaryFailure != null) {
                        executionFailure = boundaryFailure
                    } else {
                        controller.reset()
                        val bridge = StreamingTurnAudioInputSource(
                            source = source,
                            vad = vad,
                            mode = InputMode.PRESET_AUDIO,
                            onTurnCaptured = { observation = it },
                            inspectRemainingFramesForAdditionalTurns = request.measurementMode.inspectsRemainingFrames,
                            nowElapsedRealtimeNanos = { SystemClock.elapsedRealtimeNanos() },
                            endpointGraceMs = request.endpointGraceMs,
                            recognitionChunkDurationMs = request.voskChunkDurationMs,
                            earlyEndpointGraceMs = request.earlyEndpointGraceMs,
                            utteranceLooksComplete = { snapshot ->
                                utteranceCompleteness.snapshotLooksComplete(snapshot)
                            },
                        )
                        run.currentBridge = bridge
                        controller.simulateIncoming(callerName = "WAV 测试", callerNumber = callId)
                        val presetController = controller as? DialogueContextPresetController
                        if (input.initialScene != null && presetController == null) {
                            executionFailure = "INITIAL_SCENE_UNSUPPORTED" to "当前控制器不支持预置测试场景"
                        } else {
                            input.initialScene?.let { scene ->
                                checkNotNull(presetController).presetNextDialogueContext(scene)
                            }
                            controller.acceptExternalWithAi(bridge)
                            val openingPlayback = playbackMetricsSource()?.value
                            val openingTranscriptSize = controller.state.value.transcript.size
                            val response = awaitResponse(run, openingPlayback, openingTranscriptSize)
                            sessionSnapshot = response.snapshot
                            responsePlayback = response.playback
                            executionFailure = response.failure
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!run.cancelRequested.get()) throw cancelled
        } catch (error: Throwable) {
            executionFailure = "CASE_EXCEPTION" to (error.message ?: "WAV 用例执行异常")
        } finally {
            run.currentBridge?.cancel()
            if (controller.state.value.phase !in setOf(SessionPhase.IDLE, SessionPhase.COMPLETED)) {
                controller.end(if (run.cancelRequested.get()) "wav_test_cancelled" else "wav_test_case_complete")
            }
            source.stop(callId)
            currentProcessPssKb()?.let { currentPss ->
                peakProcessPssKb.accumulateAndGet(currentPss) { previous, current -> maxOf(previous, current) }
            }
            pssSamplingJob.cancelAndJoin()
            run.currentBridge = null
            run.currentSource = null
            run.currentCallId = null
        }

        return buildCaseResult(
            request = request,
            input = input,
            evaluation = case.evaluation,
            source = source,
            observation = observation,
            boundaryTracker = boundaryTracker,
            sessionSnapshot = sessionSnapshot,
            playbackMetrics = responsePlayback,
            executionFailure = executionFailure,
            cancelled = run.cancelRequested.get(),
            peakProcessPssKb = peakProcessPssKb.get().takeIf { it > 0 },
        )
    }

    private suspend fun awaitResponse(
        run: ActiveRun,
        openingPlayback: com.example.calldelegate.domain.api.AudioPlaybackMetrics?,
        openingTranscriptSize: Int,
    ): ResponseWaitResult {
        val result = withTimeoutOrNull<ResponseWaitResult>(CASE_RESPONSE_TIMEOUT_MS) {
            while (!run.cancelRequested.get()) {
                val snapshot = controller.state.value
                if (snapshot.phase == SessionPhase.ERROR) {
                    return@withTimeoutOrNull ResponseWaitResult(
                        snapshot,
                        playbackMetricsSource()?.value,
                        "SESSION_ERROR" to (snapshot.lastError ?: "会话进入错误状态"),
                    )
                }
                val hasAssistantResponse = hasAssistantResponseAfterOpening(
                    snapshot.transcript,
                    openingTranscriptSize,
                )
                val playback = playbackMetricsSource()?.value
                val responsePlaybackStarted = playback != null &&
                    (openingPlayback == null || playback.requestedAtElapsedRealtimeMs != openingPlayback.requestedAtElapsedRealtimeMs)
                if (hasAssistantResponse && (
                    responsePlaybackStarted && playback?.playbackCompletedAtElapsedRealtimeMs != null ||
                        snapshot.playbackFailure != null ||
                        snapshot.phase == SessionPhase.COMPLETED
                    )
                ) {
                    return@withTimeoutOrNull ResponseWaitResult(snapshot, playback, null)
                }
                delay(POLL_INTERVAL_MS)
            }
            ResponseWaitResult(controller.state.value, null, "CANCELLED" to "测试已取消")
        }
        return result ?: ResponseWaitResult(
            controller.state.value,
            playbackMetricsSource()?.value,
            "CASE_TIMEOUT" to "等待首个自动化回复超时",
        )
    }

    private fun buildCaseResult(
        request: WavCallTestRequest,
        input: WavCallInputCase,
        evaluation: WavCallEvaluationReference,
        source: WavCallAudioSource,
        observation: TurnCaptureObservation?,
        boundaryTracker: InjectionBoundaryTracker,
        sessionSnapshot: CallSessionSnapshot?,
        playbackMetrics: com.example.calldelegate.domain.api.AudioPlaybackMetrics?,
        executionFailure: Pair<String, String>?,
        cancelled: Boolean,
        peakProcessPssKb: Int?,
    ): WavCallSampleResult {
        val sourceMetrics = source.latestInjectionMetrics()
        val recognitionAttempts = (recognizer as? com.example.calldelegate.domain.api.RecognitionAttemptsMetricsSource)
            ?.latestRecognitionAttempts?.value.orEmpty()
        val latestRecognitionMetrics = (recognizer as? RecognitionMetricsSource)?.latestRecognitionMetrics?.value
        val secondaryMetrics = (controller as? SecondaryRecognitionMetricsSource)
            ?.latestSecondaryRecognitionMetrics?.value
        val rerankObservation = (controller as? NBestRerankMetricsSource)?.latestNBestRerank?.value
        val finalClassification = (controller as? RuleClassificationMetricsSource)
            ?.latestRuleClassification?.value
        val sceneConfidenceState = (controller as? SceneConfidenceMetricsSource)
            ?.latestSceneConfidenceState?.value
        val pipelineMetrics = (controller as? TurnPipelineMetricsSource)
            ?.latestTurnPipelineMetrics?.value
        val totalRecognitionDuration = recognitionAttempts.takeIf { it.isNotEmpty() }
            ?.sumOf { it.computeDurationMillis }
            ?: latestRecognitionMetrics?.computeDurationMillis
        val recognizedRaw = sessionSnapshot?.transcript
            ?.lastOrNull { it.speaker == Speaker.CALLER }
            ?.text
            ?: latestRecognitionMetrics?.recognizedTextRaw
        val recognitionMetrics = latestRecognitionMetrics?.copy(
            computeDurationMillis = totalRecognitionDuration ?: latestRecognitionMetrics.computeDurationMillis,
            recognizedTextRaw = recognizedRaw,
        )
        val firstPassRaw = recognitionAttempts.firstOrNull()?.recognizedTextRaw ?: recognizedRaw
        val cer = WavCallMetrics.evaluateCer(recognizedRaw, evaluation.referenceText)
        val firstPassCer = WavCallMetrics.evaluateCer(firstPassRaw, evaluation.referenceText)
        val secondaryPassRaw = recognitionAttempts.getOrNull(1)?.recognizedTextRaw
        val secondaryPassCer = secondaryPassRaw?.let { text ->
            WavCallMetrics.evaluateCer(text, evaluation.referenceText)
        }
        val actualScene = sessionSnapshot?.scene?.id
        val generatedReply = sessionSnapshot?.latestReply?.takeIf { it.isNotBlank() }
        val replyCharCount = generatedReply?.length
        val replyTemplateId = sessionSnapshot?.latestReplyTemplateId
        val replyVariables = sessionSnapshot?.latestReplyVariables.orEmpty()
        val isFallbackTemplate = sessionSnapshot?.latestReplyIsFallbackTemplate
        val fallbackReason = sessionSnapshot?.latestReplyFallbackReason
        val replySafe = sessionSnapshot?.latestReplySafe
        val complianceFlags = sessionSnapshot?.latestReplyComplianceFlags.orEmpty()
        val actualEntities = sessionSnapshot?.let { snapshot ->
            snapshot.structuredResult.asEntityMap(snapshot.scene)
        }.orEmpty()
        val resultExtras = sessionSnapshot?.structuredResult?.extras.orEmpty()
        val actualDeliveryIntent = resultExtras["deliveryIntent"]
        val deliveryIntentMatched = evaluation.expectedDeliveryIntent?.let { expected ->
            expected == actualDeliveryIntent
        }
        val expectedIntent = evaluation.expectedIntent?.trim()?.takeIf(String::isNotEmpty)
        val intentMatched = expectedIntent?.let { expected -> expected == finalClassification?.intent }
        val expectedCallNature = evaluation.expectedCallNature
        val callNatureMatched = expectedCallNature?.let { expected ->
            expected == finalClassification?.callNature?.name
        }
        val expectedRiskLevel = evaluation.expectedRiskLevel
        val riskLevelMatched = expectedRiskLevel?.let { expected ->
            expected == finalClassification?.riskLevel?.name
        }
        val entities = if (evaluation.evaluateEntities) {
            WavCallMetrics.evaluateEntities(evaluation.expectedEntities, actualEntities)
        } else {
            EntityEvaluation.notEvaluated()
        }
        val recognizedHotwordEvidence = buildString {
            append(recognizedRaw.orEmpty())
            secondaryMetrics?.matchedHotwordsByScene?.values?.flatten()?.forEach { phrase ->
                append(' ')
                append(phrase)
            }
        }
        val hotwords = WavCallMetrics.evaluateHotwords(evaluation.expectedHotwords, recognizedHotwordEvidence)
        val expectedLocation = evaluation.expectedEntities["location"]
            ?.takeIf { evaluation.evaluateEntities }
        val location = WavCallMetrics.evaluateLocation(expectedLocation, actualEntities["location"])
        val selectedAttempt = recognitionAttempts.firstOrNull { attempt ->
            WavCallMetrics.normalizeCerText(attempt.recognizedTextRaw.orEmpty()) ==
                WavCallMetrics.normalizeCerText(recognizedRaw.orEmpty())
        }
        val selectedAttemptIndex = selectedAttempt?.attemptIndex
        val attemptReports = recognitionAttempts.map { attempt ->
            WavRecognitionAttempt(
                attemptIndex = attempt.attemptIndex,
                mode = attempt.recognitionMode.name,
                sceneHints = attempt.sceneHints,
                recognitionFocuses = attempt.recognitionFocuses,
                recognizedTextRaw = attempt.recognizedTextRaw,
                computeDurationMs = attempt.computeDurationMillis,
                errorCode = attempt.errorCode,
                adopted = attempt.attemptIndex == selectedAttemptIndex,
                unknownTokenCount = attempt.unknownTokenCount,
                alternativeCount = attempt.alternativeCount,
                meanWordConfidence = attempt.meanWordConfidence,
                minimumWordConfidence = attempt.minimumWordConfidence,
                recognizerCreateMs = attempt.recognizerCreateDurationMillis,
                voskAcceptComputeMs = attempt.voskAcceptComputeDurationMillis,
                voskQueueDepth = attempt.voskQueueMaxDepth,
                voskQueueWaitMs = attempt.voskQueueWaitDurationMillis,
                voskQueueWaitMaxMs = attempt.voskQueueWaitMaxMillis,
                voskDrainMs = attempt.voskDrainDurationMillis,
                voskFinalResultMs = attempt.voskFinalResultDurationMillis,
            )
        }
        val primaryRecognitionMetrics = recognitionAttempts.firstOrNull() ?: latestRecognitionMetrics
        val expectedScene = evaluation.expectedScene?.trim()?.takeIf(String::isNotEmpty)
        val expectedSceneId = WavCallMetrics.canonicalSceneId(expectedScene)
        val sceneReferenceValid = expectedScene == null || expectedSceneId != null
        val sceneMatched = if (expectedSceneId == null || actualScene == null) {
            null
        } else {
            expectedSceneId == actualScene
        }
        val endpointGroundTruthAvailable = evaluation.speechStartMs != null && evaluation.speechEndMs != null
        val vadStartMs = observation?.speechStartSample?.let { sample -> sample * 1_000L / observation.sampleRateHz }
        val vadEndMs = observation?.speechEndSample?.let { sample -> sample * 1_000L / observation.sampleRateHz }
        val vadLastSpeechMs = observation?.lastSpeechSample?.let { sample ->
            sample * 1_000L / observation.sampleRateHz
        }
        val endpointCommittedAtMs = observation?.endpointCommittedAtMs
        val speechEndToCommitAudioMs = if (
            vadLastSpeechMs != null && endpointCommittedAtMs != null
        ) {
            endpointCommittedAtMs - vadLastSpeechMs
        } else {
            null
        }
        val asrTrailingSilenceSkippedMs = observation?.let { capture ->
            capture.recognitionTrailingSilenceSkippedSamples * 1_000L / capture.sampleRateHz
        }
        val vadStartOffset = if (endpointGroundTruthAvailable && vadStartMs != null) {
            vadStartMs - checkNotNull(evaluation.speechStartMs)
        } else {
            null
        }
        val vadEndOffset = if (endpointGroundTruthAvailable && vadEndMs != null) {
            vadEndMs - checkNotNull(evaluation.speechEndMs)
        } else {
            null
        }
        val realTime = request.injectionMode.name == "REAL_TIME"
        val speechStartInjectedAt = if (realTime) boundaryTracker.speechStartInjectedAtNanos else null
        val speechEndInjectedAt = if (realTime) boundaryTracker.speechEndInjectedAtNanos else null
        val speechEndReferenceAt = when {
            !realTime -> null
            speechEndInjectedAt != null -> speechEndInjectedAt
            request.measurementMode == WavCallMeasurementMode.PRODUCTION_LATENCY -> {
                boundaryTracker.speechEndReferenceAtNanos
            }
            else -> null
        }
        val responseLatencyReference = when {
            speechEndInjectedAt != null -> "SPEECH_END_INJECTED"
            speechEndReferenceAt != null -> "SPEECH_END_REAL_TIME_SCHEDULE"
            else -> null
        }
        val vadOutputAt = observation?.vadOutputAtElapsedRealtimeNanos
        val vadDecisionLatency = if (
            realTime && speechEndInjectedAt != null && vadOutputAt != null
        ) {
            ((vadOutputAt - speechEndInjectedAt) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
        } else {
            null
        }
        val inputDurationMs = recognitionMetrics?.let { metrics ->
            if (metrics.inputSampleRateHz > 0) {
                metrics.inputSamples * 1_000L / metrics.inputSampleRateHz
            } else {
                null
            }
        }
        val sourceRtf = recognitionMetrics?.computeDurationMillis?.let { compute ->
            sourceMetrics?.originalAudioSamples?.let { samples ->
                rtf(compute, samples, 16_000)
            }
        }
        val asrRtf = recognitionMetrics?.computeDurationMillis?.let { compute ->
            recognitionMetrics.let { metrics -> rtf(compute, metrics.inputSamples.toLong(), metrics.inputSampleRateHz) }
        }
        val asrStageWallDuration = durationNanosBetween(
            pipelineMetrics?.asrCompletedAtElapsedRealtimeNanos,
            pipelineMetrics?.asrStartedAtElapsedRealtimeNanos,
        )
        val asrPostEndpointLatency = if (realTime && speechEndReferenceAt != null) {
            durationNanosBetween(
                pipelineMetrics?.asrCompletedAtElapsedRealtimeNanos,
                speechEndReferenceAt,
            )
        } else {
            null
        }
        val nluDuration = durationNanosBetween(
            pipelineMetrics?.nluCompletedAtElapsedRealtimeNanos,
            pipelineMetrics?.nluStartedAtElapsedRealtimeNanos,
        )
        val asrToNluGap = durationNanosBetween(
            pipelineMetrics?.nluStartedAtElapsedRealtimeNanos,
            pipelineMetrics?.asrCompletedAtElapsedRealtimeNanos,
        )
        val ttsSynthesisDuration = durationNanosBetween(
            pipelineMetrics?.ttsCompletedAtElapsedRealtimeNanos,
            pipelineMetrics?.ttsStartedAtElapsedRealtimeNanos,
        )
        val nluToTtsGap = durationNanosBetween(
            pipelineMetrics?.ttsStartedAtElapsedRealtimeNanos,
            pipelineMetrics?.nluCompletedAtElapsedRealtimeNanos,
        )
        val ttsToFirstAudioWriteLatency = pipelineMetrics?.ttsCompletedAtElapsedRealtimeNanos?.let { completedAt ->
            playbackMetrics?.firstAudioWriteAtElapsedRealtimeMs?.let { writeAt ->
                (writeAt - completedAt / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
            }
        }
        val endpointToTtsCompletedLatency = if (realTime && speechEndReferenceAt != null) {
            durationNanosBetween(
                pipelineMetrics?.ttsCompletedAtElapsedRealtimeNanos,
                speechEndReferenceAt,
            )
        } else {
            null
        }
        val ttsFirstWriteLatency = playbackMetrics?.let { metrics ->
            durationBetween(metrics.firstAudioWriteAtElapsedRealtimeMs, metrics.requestedAtElapsedRealtimeMs)
        }
        val playbackStartLatency = playbackMetrics?.let { metrics ->
            durationBetween(metrics.playbackStartedAtElapsedRealtimeMs, metrics.requestedAtElapsedRealtimeMs)
        }
        val playbackDuration = playbackMetrics?.let { metrics ->
            durationBetween(metrics.playbackCompletedAtElapsedRealtimeMs, metrics.playbackStartedAtElapsedRealtimeMs)
        }
        val responseLatency = if (realTime && speechEndReferenceAt != null) {
            playbackMetrics?.firstAudioWriteAtElapsedRealtimeMs?.let { writeAt ->
                writeAt - speechEndReferenceAt / NANOS_PER_MILLISECOND
            }
        } else {
            null
        }
        val turnTotalDuration = if (realTime && speechStartInjectedAt != null) {
            playbackMetrics?.playbackCompletedAtElapsedRealtimeMs?.let { completedAt ->
                completedAt - speechStartInjectedAt / NANOS_PER_MILLISECOND
            }
        } else {
            null
        }
        val unavailableReasons = linkedMapOf<String, String>()
        if (!endpointGroundTruthAvailable) {
            unavailableReasons["endpointGroundTruth"] = "清单未同时提供 speechStartMs 和 speechEndMs"
        }
        if (observation?.speechEndSample == null) {
            unavailableReasons["vadEndpoint"] = "现有 VAD 未在此输入中产生端点"
        }
        if (!realTime) {
            unavailableReasons["realTimeLatency"] = "AS_FAST_AS_POSSIBLE 不报告用户感知响应延迟"
        }
        if (recognitionMetrics == null) {
            unavailableReasons["asrMetrics"] = "本用例未发生可观测的 Vosk 识别调用"
        }
        if (pipelineMetrics == null) {
            unavailableReasons["pipelineMetrics"] = "控制器未提供 ASR、NLU、TTS 分段时间戳"
        }
        if (playbackMetrics == null) {
            unavailableReasons["playbackMetrics"] = "没有可用的真实输出播放指标"
        }
        val committedPartialText = observation?.committedCandidatePartialTextRaw
        val partialFinalExactMatched = if (committedPartialText != null && recognizedRaw != null) {
            committedPartialText.trim() == recognizedRaw.trim()
        } else {
            null
        }
        val partialFinalNormalizedMatched = if (committedPartialText != null && recognizedRaw != null) {
            WavCallMetrics.normalizeCerText(committedPartialText) == WavCallMetrics.normalizeCerText(recognizedRaw)
        } else {
            null
        }
        val partialFinalCharacterDifference = if (committedPartialText != null && recognizedRaw != null) {
            WavCallMetrics.characterDifference(
                WavCallMetrics.normalizeCerText(committedPartialText),
                WavCallMetrics.normalizeCerText(recognizedRaw),
            )
        } else {
            null
        }
        val rollbackRecognizerStable = observation?.takeIf { it.candidateEndpointRollbackCount > 0 }?.let { observed ->
            val ids = observed.candidateRecognizerIds.filterNotNull()
            ids.size == observed.candidateRecognizerIds.size && ids.distinct().size == 1
        }

        val statusDecision = determineCaseStatus(
            cancelled = cancelled,
            observation = observation,
            executionFailure = executionFailure,
            recognitionMetrics = recognitionMetrics,
            sceneReferenceValid = sceneReferenceValid,
            sceneMatched = sceneMatched,
            deliveryIntentMatched = deliveryIntentMatched,
            intentMatched = intentMatched,
            callNatureMatched = callNatureMatched,
            riskLevelMatched = riskLevelMatched,
            entities = entities,
            sessionSnapshot = sessionSnapshot,
        )
        return WavCallSampleResult(
            caseId = input.caseId,
            wavFile = input.relativeWavPath,
            injectionMode = request.injectionMode.name,
            measurementMode = request.measurementMode.name,
            acceleratedInput = !realTime,
            status = statusDecision.status,
            failureCode = statusDecision.code,
            failureMessage = statusDecision.message,
            originalAudioSamples = sourceMetrics?.originalAudioSamples,
            originalAudioDurationMs = sourceMetrics?.originalAudioDurationMillis,
            tailSilenceMs = sourceMetrics?.tailSilenceMs,
            injectedTailSamples = sourceMetrics?.injectedTailSamples,
            framePaddingSamples = sourceMetrics?.framePaddingSamples,
            framePaddingDurationMs = sourceMetrics?.framePaddingDurationMillis,
            emittedFrames = sourceMetrics?.emittedFrames,
            endpointGroundTruthAvailable = endpointGroundTruthAvailable,
            speechStartInjectedAtElapsedRealtimeNanos = speechStartInjectedAt,
            speechEndInjectedAtElapsedRealtimeNanos = speechEndInjectedAt,
            speechEndReferenceAtElapsedRealtimeNanos = speechEndReferenceAt,
            responseLatencyReference = responseLatencyReference,
            boundaryTimingResolutionMs = if (realTime) FRAME_DURATION_MS else null,
            vadSpeechStartMs = vadStartMs,
            vadSpeechEndMs = vadEndMs,
            vadLastSpeechMs = vadLastSpeechMs,
            speechEndToCommitAudioMs = speechEndToCommitAudioMs,
            speechEndToCommitWallClockMs = observation?.speechEndToCommitWallClockMs,
            asrTrailingSilenceSkippedMs = asrTrailingSilenceSkippedMs,
            vadOutputAtElapsedRealtimeNanos = observation?.vadOutputAtElapsedRealtimeNanos,
            vadStartOffsetMs = vadStartOffset,
            vadEndOffsetMs = vadEndOffset,
            vadPositiveEndpointDelayMs = vadEndOffset?.coerceAtLeast(0L),
            vadEarlyCutoffMs = vadEndOffset?.let { offset -> (-offset).coerceAtLeast(0L) },
            vadDecisionLatencyMs = vadDecisionLatency,
            vadFrameProcessingLagMs = observation?.vadFrameProcessingLagMs,
            endpointDetectionQuantizationMs = observation?.endpointDetectionQuantizationMs,
            candidateEndpointAtMs = observation?.candidateEndpointAtMs.orEmpty(),
            speechResumedAtMs = observation?.speechResumedAtMs.orEmpty(),
            speechResumedAfterCandidateMs = observation?.speechResumedAfterCandidateMs.orEmpty(),
            candidateCancelledAtMs = observation?.candidateCancelledAtMs.orEmpty(),
            endpointCommittedAtMs = observation?.endpointCommittedAtMs,
            candidateEndpointRollbackCount = observation?.candidateEndpointRollbackCount ?: 0,
            candidateRecognizerIds = observation?.candidateRecognizerIds.orEmpty(),
            candidatePartialTextsRaw = observation?.candidatePartialTextsRaw.orEmpty(),
            committedCandidateRecognizerId = observation?.committedCandidateRecognizerId,
            committedCandidatePartialTextRaw = committedPartialText,
            committedOnEarlyEndpointEvidence = observation?.committedOnEarlyEndpointEvidence ?: false,
            committedEndpointGraceMs = observation?.committedEndpointGraceMs,
            rollbackRecognizerStable = rollbackRecognizerStable,
            partialFinalExactMatched = partialFinalExactMatched,
            partialFinalNormalizedMatched = partialFinalNormalizedMatched,
            partialFinalCharacterDifference = partialFinalCharacterDifference,
            vadEndReason = observation?.endReason?.name,
            voskComputeDurationMs = totalRecognitionDuration,
            voskInputAudioDurationMs = inputDurationMs,
            asrStartedAtElapsedRealtimeNanos = pipelineMetrics?.asrStartedAtElapsedRealtimeNanos,
            asrCompletedAtElapsedRealtimeNanos = pipelineMetrics?.asrCompletedAtElapsedRealtimeNanos,
            asrStageWallDurationMs = asrStageWallDuration,
            asrPostEndpointLatencyMs = asrPostEndpointLatency,
            referenceEndToAsrCompleteMs = asrPostEndpointLatency,
            recognizerCreateMs = primaryRecognitionMetrics?.recognizerCreateDurationMillis,
            voskAcceptComputeMs = primaryRecognitionMetrics?.voskAcceptComputeDurationMillis,
            voskQueueDepth = primaryRecognitionMetrics?.voskQueueMaxDepth,
            voskQueueWaitMs = primaryRecognitionMetrics?.voskQueueWaitDurationMillis,
            voskQueueWaitMaxMs = primaryRecognitionMetrics?.voskQueueWaitMaxMillis,
            voskDrainMs = primaryRecognitionMetrics?.voskDrainDurationMillis,
            voskFinalResultMs = primaryRecognitionMetrics?.voskFinalResultDurationMillis,
            nluStartedAtElapsedRealtimeNanos = pipelineMetrics?.nluStartedAtElapsedRealtimeNanos,
            nluCompletedAtElapsedRealtimeNanos = pipelineMetrics?.nluCompletedAtElapsedRealtimeNanos,
            nluDurationMs = nluDuration,
            asrToNluGapMs = asrToNluGap,
            ttsStartedAtElapsedRealtimeNanos = pipelineMetrics?.ttsStartedAtElapsedRealtimeNanos,
            ttsCompletedAtElapsedRealtimeNanos = pipelineMetrics?.ttsCompletedAtElapsedRealtimeNanos,
            ttsSynthesisDurationMs = ttsSynthesisDuration,
            nluToTtsGapMs = nluToTtsGap,
            ttsToFirstAudioWriteLatencyMs = ttsToFirstAudioWriteLatency,
            endpointToTtsCompletedLatencyMs = endpointToTtsCompletedLatency,
            sourceRtf = sourceRtf,
            asrRtf = asrRtf,
            recognizedTextRaw = recognizedRaw,
            recognizedTextNormalized = cer.recognizedNormalized,
            referenceTextRaw = evaluation.referenceText,
            referenceTextNormalized = cer.referenceNormalized,
            cerEditDistance = cer.editDistance,
            cerReferenceLength = cer.referenceLength,
            cer = cer.cer,
            firstPassRecognizedTextRaw = firstPassRaw,
            firstPassRecognizedTextNormalized = firstPassCer.recognizedNormalized,
            firstPassCerEditDistance = firstPassCer.editDistance,
            firstPassCerReferenceLength = firstPassCer.referenceLength,
            firstPassCer = firstPassCer.cer,
            recognitionAttemptCount = recognitionAttempts.size.coerceAtLeast(if (recognitionMetrics == null) 0 else 1),
            recognitionAttempts = attemptReports,
            selectedRecognitionAttemptIndex = selectedAttemptIndex,
            secondaryRecognitionTriggered = recognitionAttempts.size > 1,
            secondaryRecognitionComputeDurationMs = recognitionAttempts.drop(1).sumOf { it.computeDurationMillis },
            selectedRecognitionMode = selectedAttempt?.recognitionMode?.name,
            secondaryPassRecognizedTextRaw = secondaryPassRaw,
            secondaryPassCer = secondaryPassCer?.cer,
            secondaryTextDifferenceRate = secondaryMetrics?.textDifferenceRate,
            secondaryTriggerReasons = secondaryMetrics?.triggerReasons.orEmpty(),
            asrAlternativeCount = primaryRecognitionMetrics?.alternativeCount,
            asrMeanWordConfidence = primaryRecognitionMetrics?.meanWordConfidence,
            asrMinimumWordConfidence = primaryRecognitionMetrics?.minimumWordConfidence,
            nBestAlternatives = rerankObservation?.alternatives.orEmpty(),
            rerankExaminedHypotheses = rerankObservation?.examinedHypotheses,
            rerankTriggered = rerankObservation?.changedHypothesis == true,
            rerankChosenRank = rerankObservation?.chosenRank,
            rerankUnderstoodText = rerankObservation?.understoodText,
            rerankReasons = rerankObservation?.reasons.orEmpty(),
            secondaryMatchedHotwordsByScene = secondaryMetrics?.matchedHotwordsByScene.orEmpty(),
            primaryNluScene = secondaryMetrics?.primaryClassification?.scene,
            primaryNluConfidence = secondaryMetrics?.primaryClassification?.confidence,
            primaryNluSceneMargin = secondaryMetrics?.primaryClassification?.sceneMargin,
            primaryNluEntities = secondaryMetrics?.primaryClassification?.entities.orEmpty(),
            secondaryNluScene = secondaryMetrics?.secondaryClassification?.scene,
            secondaryNluConfidence = secondaryMetrics?.secondaryClassification?.confidence,
            secondaryNluSceneMargin = secondaryMetrics?.secondaryClassification?.sceneMargin,
            secondaryNluEntities = secondaryMetrics?.secondaryClassification?.entities.orEmpty(),
            secondaryFusionReasons = secondaryMetrics?.decisionReasons.orEmpty(),
            secondaryEvidenceUsed = secondaryMetrics?.evidenceUsed == true,
            secondaryScenePromoted = secondaryMetrics?.decisionReasons.orEmpty()
                .any { it.startsWith("secondary:scene:accepted:") },
            secondaryAcceptedEntityKeys = secondaryMetrics?.decisionReasons.orEmpty()
                .filter { it.startsWith("secondary:entity:accepted:") }
                .map { it.substringAfterLast(':') }
                .distinct(),
            secondaryTextReplaced = selectedAttemptIndex == 2,
            generatedReply = generatedReply,
            replyTemplateId = replyTemplateId,
            replyVariables = replyVariables,
            isFallbackTemplate = isFallbackTemplate,
            fallbackReason = fallbackReason,
            replySafe = replySafe,
            replyCharCount = replyCharCount,
            replyAudioDurationMs = playbackDuration,
            complianceFlags = complianceFlags,
            finalNluScene = finalClassification?.scene,
            finalNluIntent = finalClassification?.intent,
            topicScene = finalClassification?.topicScene,
            finalNluCallNature = finalClassification?.callNature?.name,
            finalNluRiskLevel = finalClassification?.riskLevel?.name,
            finalNluConfidence = finalClassification?.confidence,
            finalNluSceneMargin = finalClassification?.sceneMargin,
            shouldClarify = finalClassification?.shouldClarify,
            sceneCandidates = finalClassification?.sceneCandidates.orEmpty(),
            riskReasons = finalClassification?.riskReasons.orEmpty(),
            nluMatchedEvidence = finalClassification?.matchedEvidence.orEmpty(),
            nluRejectedEvidence = finalClassification?.rejectedEvidence.orEmpty(),
            ruleDebugTrace = finalClassification?.debugTrace,
            initialScene = input.initialScene?.id,
            expectedScene = expectedScene,
            actualScene = actualScene,
            sceneConfidenceState = sceneConfidenceState?.name,
            sceneMatched = sceneMatched,
            expectedIntent = expectedIntent,
            intentMatched = intentMatched,
            expectedCallNature = expectedCallNature,
            callNatureMatched = callNatureMatched,
            expectedRiskLevel = expectedRiskLevel,
            riskLevelMatched = riskLevelMatched,
            expectedDigitSpans = evaluation.expectedDigitSpans,
            expectedDeliveryIntent = evaluation.expectedDeliveryIntent,
            actualDeliveryIntent = actualDeliveryIntent,
            deliveryIntentMatched = deliveryIntentMatched,
            deliveryIntentScore = resultExtras["deliveryIntentScore"]?.toFloatOrNull(),
            deliveryIntentDecisionRule = resultExtras["deliveryIntentDecisionRule"],
            deliveryIntentMatchedEvidence = resultExtras["deliveryIntentMatchedEvidence"]
                ?.split('|')
                ?.filter(String::isNotBlank)
                .orEmpty(),
            deliveryIntentRejectedCandidates = resultExtras["deliveryIntentRejectedCandidates"]
                ?.split(',')
                ?.filter(String::isNotBlank)
                .orEmpty(),
            expectedEntities = evaluation.expectedEntities,
            entityEvaluationEnabled = evaluation.evaluateEntities,
            expectedHotwords = evaluation.expectedHotwords,
            matchedHotwordCount = hotwords.matchedCount,
            hotwordAccuracy = hotwords.accuracy,
            actualEntities = actualEntities,
            entityTp = entities.truePositive.takeIf { evaluation.evaluateEntities },
            entityFp = entities.falsePositive.takeIf { evaluation.evaluateEntities },
            entityFn = entities.falseNegative.takeIf { evaluation.evaluateEntities },
            entityPrecision = entities.precision.takeIf { evaluation.evaluateEntities },
            entityRecall = entities.recall.takeIf { evaluation.evaluateEntities },
            entityF1 = entities.f1.takeIf { evaluation.evaluateEntities },
            strictEntitiesMatched = entities.strictMatched.takeIf { evaluation.evaluateEntities },
            requiredEntitiesIncluded = entities.requiredIncluded.takeIf { evaluation.evaluateEntities },
            locationMatched = location.normalizedMatched,
            locationExactMatched = location.exactMatched,
            locationCoreIncluded = location.coreIncluded,
            locationHierarchyMatched = location.hierarchyMatched,
            playbackRequestedAtElapsedRealtimeMs = playbackMetrics?.requestedAtElapsedRealtimeMs,
            firstAudioWriteAtElapsedRealtimeMs = playbackMetrics?.firstAudioWriteAtElapsedRealtimeMs,
            playbackStartedAtElapsedRealtimeMs = playbackMetrics?.playbackStartedAtElapsedRealtimeMs,
            playbackCompletedAtElapsedRealtimeMs = playbackMetrics?.playbackCompletedAtElapsedRealtimeMs,
            outputMode = playbackMetrics?.outputMode,
            playbackErrorCode = playbackMetrics?.errorCode,
            ttsFirstAudioWriteLatencyMs = ttsFirstWriteLatency,
            playbackStartLatencyMs = playbackStartLatency,
            playbackDurationMs = playbackDuration,
            responseLatencyMs = responseLatency,
            turnTotalDurationMs = turnTotalDuration,
            peakProcessPssKb = peakProcessPssKb,
            unavailableReasons = unavailableReasons,
        )
    }

    private fun determineCaseStatus(
        cancelled: Boolean,
        observation: TurnCaptureObservation?,
        executionFailure: Pair<String, String>?,
        recognitionMetrics: RecognitionComputeMetrics?,
        sceneReferenceValid: Boolean,
        sceneMatched: Boolean?,
        deliveryIntentMatched: Boolean?,
        intentMatched: Boolean?,
        callNatureMatched: Boolean?,
        riskLevelMatched: Boolean?,
        entities: EntityEvaluation,
        sessionSnapshot: CallSessionSnapshot?,
    ): CaseStatusDecision {
        if (cancelled) return CaseStatusDecision(WavCallCaseStatus.CANCELLED, "CANCELLED", "测试已取消")
        if (observation?.additionalUtteranceDetected == true) {
            return CaseStatusDecision(WavCallCaseStatus.MULTIPLE_UTTERANCES, "MULTIPLE_UTTERANCES", "VAD 检出多个用户话轮")
        }
        if (observation != null && observation.speechStartSample == null) {
            return CaseStatusDecision(WavCallCaseStatus.NO_SPEECH, "NO_SPEECH", "VAD 未检出语音")
        }
        if (executionFailure != null) return CaseStatusDecision(WavCallCaseStatus.FAILED, executionFailure.first, executionFailure.second)
        if (recognitionMetrics == null || recognitionMetrics.recognizedTextRaw.isNullOrBlank()) {
            return CaseStatusDecision(WavCallCaseStatus.FAILED, "ASR_EMPTY_RESULT", "真实 Vosk 未输出有效文本")
        }
        val playbackFailure = sessionSnapshot?.playbackFailure
        if (playbackFailure != null) {
            return CaseStatusDecision(
                WavCallCaseStatus.FAILED,
                playbackFailure.code,
                playbackFailure.message,
            )
        }
        if (!sceneReferenceValid) {
            return CaseStatusDecision(WavCallCaseStatus.FAILED, "EXPECTED_SCENE_INVALID", "清单 expectedScene 不是项目稳定场景 ID")
        }
        if (sceneMatched == false) return CaseStatusDecision(WavCallCaseStatus.FAILED, "SCENE_MISMATCH", "场景识别结果与清单不一致")
        if (deliveryIntentMatched == false) {
            return CaseStatusDecision(WavCallCaseStatus.FAILED, "DELIVERY_INTENT_MISMATCH", "配送场景内意图与清单不一致")
        }
        if (intentMatched == false) return CaseStatusDecision(WavCallCaseStatus.FAILED, "INTENT_MISMATCH", "通话意图与清单不一致")
        if (callNatureMatched == false) return CaseStatusDecision(WavCallCaseStatus.FAILED, "CALL_NATURE_MISMATCH", "通话性质与清单不一致")
        if (riskLevelMatched == false) return CaseStatusDecision(WavCallCaseStatus.FAILED, "RISK_LEVEL_MISMATCH", "风险等级与清单不一致")
        if (entities.evaluated && (entities.falsePositive > 0 || entities.falseNegative > 0)) {
            return CaseStatusDecision(WavCallCaseStatus.FAILED, "ENTITY_MISMATCH", "实体识别结果与清单不一致")
        }
        return CaseStatusDecision(WavCallCaseStatus.PASSED, null, null)
    }

    private suspend fun writeBatchFailure(
        runId: String,
        directory: File,
        request: WavCallTestRequest,
        startedAt: Long,
        manifestVersion: String?,
        errorCode: String,
        errorMessage: String,
        asrModel: com.example.calldelegate.domain.model.ActiveModel?,
        ttsModel: com.example.calldelegate.domain.model.ActiveModel?,
    ): AppResult<WavCallRunReport> {
        val completedAt = SystemClock.elapsedRealtimeNanos()
        val summary = WavCallRunSummary(
            runId = runId,
            status = WavCallRunStatus.BATCH_FAILED,
            startedAtElapsedRealtimeNanos = startedAt,
            completedAtElapsedRealtimeNanos = completedAt,
            manifestVersion = manifestVersion,
            injectionMode = request.injectionMode.name,
            measurementMode = request.measurementMode.name,
            acceleratedInput = request.injectionMode.name == "AS_FAST_AS_POSSIBLE",
            environment = createEnvironment(request, asrModel, ttsModel),
            modeSummaries = emptyList(),
            completedCaseCount = 0,
            failureCaseCount = 0,
            cancellationRequested = false,
            batchFailureCode = errorCode,
            batchFailureMessage = errorMessage,
        )
        return try {
            exporter.write(directory, summary, emptyList())
            AppResult.Success(WavCallRunReport(runId, directory, summary, emptyList()))
        } catch (error: Throwable) {
            AppResult.Failure(AppError("RESULT_EXPORT", "无法导出批次失败结果", error.message))
        }
    }

    private fun createEnvironment(
        request: WavCallTestRequest,
        asrModel: com.example.calldelegate.domain.model.ActiveModel?,
        ttsModel: com.example.calldelegate.domain.model.ActiveModel?,
    ): WavCallRunEnvironment {
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        val vadConfiguration = (vad as? VoiceActivityDetectorConfigurationSource)?.voiceActivityDetectorConfiguration
        val deviceProfile = deviceProfileProvider.profile.value
        return WavCallRunEnvironment(
            applicationVersion = packageInfo?.versionName,
            buildType = BuildConfig.BUILD_TYPE,
            gitCommit = request.gitCommit,
            baselineReference = request.baselineReference,
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter(String::isNotBlank).joinToString(" ").ifBlank { null },
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = memoryInfo.totalMem.takeIf { it > 0L },
            asrModelName = asrModel?.displayName,
            asrModelVersion = asrModel?.version,
            asrModelPath = asrModel?.directoryPath,
            asrRuntime = asrModel?.runtime,
            ttsModelName = ttsModel?.displayName,
            ttsModelVersion = ttsModel?.version,
            ttsModelPath = ttsModel?.directoryPath,
            ttsRuntime = ttsModel?.runtime,
            ttsVoice = "speaker-108",
            ttsLocale = "zh-CN",
            inferenceThreadCount = runtime.currentPolicy().ttsThreadCount,
            wavTurnDurationLimitMillis = if (request.disableMaxTurnDuration) {
                null
            } else {
                runtime.currentPolicy().maxTurnDurationMillis.takeIf { it > 0L }
            },
            wavTurnDurationLimitDisabled = request.disableMaxTurnDuration,
            vadImplementation = vadConfiguration?.implementationName,
            vadRmsThreshold = vadConfiguration?.rmsThreshold,
            vadEndSilenceFrames = vadConfiguration?.endSilenceFrames,
            vadInitialSilenceFrames = vadConfiguration?.initialSilenceFrames,
            vadFrameSamples = FRAME_SAMPLES,
            tailSilenceMs = request.tailSilenceMs,
            queueMetricReason = "WAV 源队列不可观测；Vosk worker 队列指标按样本单独导出",
            vadEndSilenceMs = vadConfiguration?.endSilenceMs,
            vadInitialSilenceMs = vadConfiguration?.initialSilenceMs,
            vadSubframeDurationMs = vadConfiguration?.subframeDurationMs,
            endpointGraceMs = request.endpointGraceMs,
            earlyEndpointGraceMs = request.earlyEndpointGraceMs,
            maxCandidateRollbackCount = StreamingTurnAudioInputSource.DEFAULT_MAX_CANDIDATE_ROLLBACK_COUNT,
            secondaryRecognitionMode = request.secondaryRecognitionMode.name,
            voskChunkDurationMs = request.voskChunkDurationMs,
            nominalRamOverrideGb = request.nominalRamOverrideGb,
            effectiveDeviceTier = deviceProfile.tier.name,
            baseDeviceTier = deviceProfile.baseTier.name,
            devicePolicy = deviceProfile.policy.toTelemetryString(),
            deviceProfileReasons = deviceProfile.reasons,
            maxAlternativesOverride = request.maxAlternativesOverride,
        )
    }

    private fun currentProcessPssKb(): Int? {
        val processMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return processMemory.totalPss.takeIf { it > 0 }
    }

    private fun validateModels(
        asrModel: com.example.calldelegate.domain.model.ActiveModel?,
        ttsModel: com.example.calldelegate.domain.model.ActiveModel?,
    ): Pair<String, String>? {
        if (asrModel == null) return "ASR_MODEL_MISSING" to "没有活动 ASR 模型"
        if (
            asrModel.version != REQUIRED_VOSK_MODEL_VERSION ||
            asrModel.runtime != REQUIRED_VOSK_RUNTIME ||
            asrModel.sampleRateHz != 16_000 ||
            !asrModel.files.containsKey("MODEL")
        ) {
            return "ASR_MODEL_MISMATCH" to "活动 ASR 模型不是项目要求的 Vosk small-cn 0.22"
        }
        if (ttsModel == null || ttsModel.runtime.substringBefore(':') != "sherpa-onnx") {
            return "TTS_MODEL_MISSING" to "没有可用的真实 sherpa-onnx TTS 模型"
        }
        return null
    }

    private fun validateSpeechBoundaries(
        evaluation: WavCallEvaluationReference,
        originalDurationMs: Long?,
    ): Pair<String, String>? {
        val start = evaluation.speechStartMs
        val end = evaluation.speechEndMs
        if ((start == null) != (end == null)) {
            return "MANIFEST_SPEECH_BOUNDARY" to "speechStartMs 和 speechEndMs 必须同时提供或同时为空"
        }
        if (start == null || end == null) return null
        if (originalDurationMs == null || start < 0L || start >= end || end > originalDurationMs) {
            return "MANIFEST_SPEECH_BOUNDARY" to "人工语音边界超出原始 WAV 时长"
        }
        return null
    }

    private fun playbackMetricsSource(): StateFlow<com.example.calldelegate.domain.api.AudioPlaybackMetrics?>? =
        (audioOutput as? PlaybackMetricsSource)?.latestPlaybackMetrics

    private fun failedSample(
        input: WavCallInputCase,
        mode: String,
        measurementMode: WavCallMeasurementMode,
        code: String,
        message: String,
    ) = WavCallSampleResult(
        caseId = input.caseId,
        wavFile = input.relativeWavPath,
        injectionMode = mode,
        measurementMode = measurementMode.name,
        acceleratedInput = mode == "AS_FAST_AS_POSSIBLE",
        status = WavCallCaseStatus.FAILED,
        failureCode = code,
        failureMessage = message,
        initialScene = input.initialScene?.id,
    )

    private fun cancelledSample(
        input: WavCallInputCase,
        mode: String,
        measurementMode: WavCallMeasurementMode,
    ) = WavCallSampleResult(
        caseId = input.caseId,
        wavFile = input.relativeWavPath,
        injectionMode = mode,
        measurementMode = measurementMode.name,
        acceleratedInput = mode == "AS_FAST_AS_POSSIBLE",
        status = WavCallCaseStatus.CANCELLED,
        failureCode = "CANCELLED",
        failureMessage = "测试在此用例开始前已取消",
        initialScene = input.initialScene?.id,
    )

    private fun durationBetween(end: Long?, start: Long?): Long? =
        if (end == null || start == null) null else (end - start).coerceAtLeast(0L)

    private fun durationNanosBetween(end: Long?, start: Long?): Long? =
        if (end == null || start == null) {
            null
        } else {
            ((end - start) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
        }

    private fun rtf(computeDurationMs: Long, samples: Long, sampleRateHz: Int): Double? {
        if (samples <= 0L || sampleRateHz <= 0) return null
        val audioDurationMs = samples.toDouble() * 1_000.0 / sampleRateHz
        if (audioDurationMs <= 0.0) return null
        return (computeDurationMs.toDouble() / audioDurationMs).takeIf { it.isFinite() }
    }

    private data class ActiveRun(
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var currentSource: WavCallAudioSource? = null,
        @Volatile var currentBridge: StreamingTurnAudioInputSource? = null,
        @Volatile var currentCallId: String? = null,
    )

    private data class ResponseWaitResult(
        val snapshot: CallSessionSnapshot,
        val playback: com.example.calldelegate.domain.api.AudioPlaybackMetrics?,
        val failure: Pair<String, String>?,
    )

    private data class CaseStatusDecision(
        val status: WavCallCaseStatus,
        val code: String?,
        val message: String?,
    )

    private class InjectionBoundaryTracker(
        speechStartMs: Long?,
        speechEndMs: Long?,
    ) {
        private val speechStartSample = speechStartMs?.times(SAMPLES_PER_MILLISECOND)
        private val speechEndSample = speechEndMs?.times(SAMPLES_PER_MILLISECOND)
        private val speechEndReferenceMs = speechEndMs
        var speechStartInjectedAtNanos: Long? = null
            private set
        var speechEndInjectedAtNanos: Long? = null
            private set
        var speechEndReferenceAtNanos: Long? = null
            private set

        fun onFrameInjected(frame: com.example.calldelegate.domain.api.PcmAudioFrame, injectedAtNanos: Long) {
            val frameStart = frame.timestampMs * SAMPLES_PER_MILLISECOND
            val frameSamples = frame.data.size / BYTES_PER_SAMPLE
            val frameEnd = frameStart + frameSamples
            if (speechEndReferenceAtNanos == null && speechEndReferenceMs != null) {
                speechEndReferenceAtNanos = estimateRealTimeBoundaryNanos(
                    injectedAtNanos = injectedAtNanos,
                    frameTimestampMs = frame.timestampMs,
                    boundaryMs = speechEndReferenceMs,
                )
            }
            if (speechStartInjectedAtNanos == null && speechStartSample in frameStart until frameEnd) {
                speechStartInjectedAtNanos = injectedAtNanos
            }
            if (speechEndInjectedAtNanos == null && speechEndSample in frameStart until frameEnd) {
                speechEndInjectedAtNanos = injectedAtNanos
            }
        }
    }

    companion object {
        const val REQUIRED_VOSK_MODEL_VERSION = "0.22.0"
        const val REQUIRED_VOSK_RUNTIME = "vosk:0.3.75"
        const val CASE_RESPONSE_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 20L
        const val PSS_SAMPLE_INTERVAL_MS = 100L
        const val FRAME_SAMPLES = 320
        const val FRAME_DURATION_MS = 20L
        const val SAMPLES_PER_MILLISECOND = 16L
        const val BYTES_PER_SAMPLE = 2
        const val NANOS_PER_MILLISECOND = 1_000_000L
        // `from()` stores only applicationContext, never an Activity or service context.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var sharedRunner: WavCallTestRunner? = null

        fun from(context: Context): WavCallTestRunner {
            val applicationContext = context.applicationContext
            return sharedRunner ?: synchronized(this) {
                sharedRunner ?: run {
                    val entryPoint = EntryPointAccessors.fromApplication(applicationContext, DebugTestEntryPoint::class.java)
                    WavCallTestRunner(
                        context = applicationContext,
                        controller = entryPoint.callSessionController(),
                        runtime = entryPoint.speechRuntimeManager(),
                        modelManager = entryPoint.modelManager(),
                        deviceProfileProvider = entryPoint.deviceProfileProvider(),
                        recognizer = entryPoint.speechRecognizer(),
                        audioOutput = entryPoint.audioOutputSink(),
                        vad = entryPoint.voiceActivityDetector(),
                    ).also { sharedRunner = it }
                }
            }
        }
    }
}

internal fun estimateRealTimeBoundaryNanos(
    injectedAtNanos: Long,
    frameTimestampMs: Long,
    boundaryMs: Long,
): Long = injectedAtNanos + (boundaryMs - frameTimestampMs) * 1_000_000L

internal fun hasAssistantResponseAfterOpening(
    transcript: List<com.example.calldelegate.domain.model.TranscriptTurn>,
    openingTranscriptSize: Int,
): Boolean = transcript
    .drop(openingTranscriptSize.coerceAtLeast(0))
    .any { it.speaker == Speaker.ASSISTANT }

private fun InferencePolicy.toTelemetryString(): String = listOf(
    "backend=$backend",
    "ttsThreadCount=$ttsThreadCount",
    "preloadAsrOnIncoming=$preloadAsrOnIncoming",
    "preloadTtsOnIncoming=$preloadTtsOnIncoming",
    "releaseAsrBeforeTts=$releaseAsrBeforeTts",
    "releaseTtsAfterSynthesis=$releaseTtsAfterSynthesis",
    "allowConcurrentSpeechModels=$allowConcurrentSpeechModels",
    "maxResidentLanguageModels=$maxResidentLanguageModels",
    "maxTurnDurationMillis=$maxTurnDurationMillis",
    "hardwareAccelerationEligible=$hardwareAccelerationEligible",
).joinToString(";")
