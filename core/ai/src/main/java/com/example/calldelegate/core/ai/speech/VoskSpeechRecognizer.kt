package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.PerformanceTrace
import com.example.calldelegate.domain.api.RecognitionComputeMetrics
import com.example.calldelegate.domain.api.RecognitionAttemptsMetricsSource
import com.example.calldelegate.domain.api.RecognitionMetricsSource
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechRecognitionMode
import com.example.calldelegate.domain.api.StreamingSpeechRecognitionSession
import com.example.calldelegate.domain.api.StreamingSpeechRecognizer
import com.example.calldelegate.domain.api.StreamingRecognitionSnapshot
import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.RecognitionAlternative
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.RecognizedWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

fun interface ActiveModelSource {
    suspend fun activeModel(): ActiveModel?
}

interface VoskEngineFactory {
    fun openModel(directoryPath: String): VoskModelHandle
}

/** Debug runner capability for a same-build N-best A/B. Null restores the production option. */
interface VoskAlternativesExperimentController {
    fun setMaxAlternativesOverride(maxAlternatives: Int?)
}

/**
 * How much detail to ask the recognizer for.
 *
 * [maxAlternatives] makes the decoder walk the lattice for an N-best list; [words] makes it emit
 * per-word confidence and timing. **Vosk produces one or the other, never both**, so asking for
 * both is rejected here rather than silently answered with less than was asked for.
 *
 * Measured on 2026-08-07, one build and 30 utterances of the customer-service corpus on an
 * Android validation device: with maxAlternatives=5 and words=true, 30/30 turns returned alternatives and 0/30
 * returned word confidence; the same build with maxAlternatives=0 and words=true returned 0/30 and
 * 30/30. The two live on separate result paths in the decoder, and only the requested one is
 * populated.
 *
 * The same pair of runs measured the cost, one run each rather than repeated, so read the
 * direction and not the digits: N-best was the *cheaper* request (mean ASR 1137 ms against
 * 1558 ms, p95 1366 ms against 2183 ms). Word confidence requires an alignment pass over the
 * lattice that extracting an N-best list does not.
 */
data class VoskRecognizerOptions(
    val maxAlternatives: Int = 0,
    val words: Boolean = false,
    /** Null leaves the library's own endpointer timing in place. */
    val endpointerDelays: VoskEndpointerDelays? = null,
) {
    init {
        require(maxAlternatives >= 0) { "maxAlternatives cannot be negative" }
        require(maxAlternatives == 0 || !words) {
            "Vosk returns either an N-best list or word confidence, not both; ask for one"
        }
    }

    val isPlainText: Boolean get() = maxAlternatives == 0 && !words
}

/**
 * How long the recognizer waits before declaring an utterance over.
 *
 * [endSeconds] is the one that matters here: trailing silence after speech before the decoder
 * closes a segment. The capture path asks the recognizer whether the caller finished, and that
 * question has no useful answer until this much silence has passed -- so this value sets the
 * earliest moment a turn could ever commit on the recognizer's say-so.
 *
 * Lowering it is not free. The recognizer closes a segment on any pause this long, mid-sentence
 * ones included, and a device corpus already contains a 400 ms pause inside one caller's sentence.
 * Committing a turn on evidence that arrives faster than that would cut them off, which is the
 * fault the whole endpoint design exists to avoid.
 */
data class VoskEndpointerDelays(
    val startMaxSeconds: Float,
    val endSeconds: Float,
    val maxSeconds: Float,
) {
    init {
        require(startMaxSeconds > 0f && endSeconds > 0f && maxSeconds > 0f) {
            "endpointer delays must be positive"
        }
    }
}

/** One hypothesis as the recognizer returned it, before any text normalization. */
data class VoskHypothesis(
    val text: String,
    val score: Float?,
)

/** One word as the recognizer returned it. [confidence] is in 0..1. */
data class VoskWord(
    val word: String,
    val confidence: Float,
    val startSeconds: Double,
    val endSeconds: Double,
)

/**
 * What one recognizer call returned.
 *
 * [alternatives] and [words] are empty unless the recognizer was configured to produce them;
 * [text] is always populated, and equals the best hypothesis when there are alternatives.
 */
data class VoskRecognition(
    val text: String,
    val alternatives: List<VoskHypothesis> = emptyList(),
    val words: List<VoskWord> = emptyList(),
)

interface VoskModelHandle : AutoCloseable {
    fun newRecognizer(sampleRateHz: Int): VoskRecognizerHandle
    fun newRecognizer(sampleRateHz: Int, phrases: List<String>): VoskRecognizerHandle = newRecognizer(sampleRateHz)
    fun newRecognizer(
        sampleRateHz: Int,
        phrases: List<String>,
        options: VoskRecognizerOptions,
    ): VoskRecognizerHandle =
        if (phrases.isEmpty()) newRecognizer(sampleRateHz) else newRecognizer(sampleRateHz, phrases)
}

/**
 * A recognizer handle.
 *
 * [result] and [finalResult] return the full detail rather than text plus a separate detail
 * accessor, because the underlying Vosk calls consume the decoder state: asking twice for the same
 * segment would flush it, so there must be exactly one way to read each result.
 */
interface VoskRecognizerHandle : AutoCloseable {
    fun accept(samples: ShortArray): Boolean
    fun result(): VoskRecognition
    fun partialText(): String
    fun finalResult(): VoskRecognition
}

class VoskSpeechRecognizer(
    private val modelSource: ActiveModelSource,
    private val engineFactory: VoskEngineFactory,
    private val hotwords: SceneHotwordProvider? = null,
    private val recognizerOptions: VoskRecognizerOptions = VoskRecognizerOptions(),
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedRealtimeNanos: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
    private val metricsClockNanos: () -> Long = System::nanoTime,
    /**
     * CPU time burnt by the calling thread, for telling slow work from interrupted work.
     *
     * The wall clock around a decode says how long the caller waited; it does not say whether the
     * decoder was computing or waiting for a core. A device reading put the decode at roughly one
     * times real time, which is several times slower than this model has any business being, and
     * the two explanations -- genuinely that much arithmetic, or a thread the scheduler keeps
     * putting down -- take opposite fixes.
     */
    private val threadCpuClockNanos: () -> Long = { android.os.Debug.threadCpuTimeNanos() },
) : SpeechRecognizer, StreamingSpeechRecognizer, RecognitionMetricsSource, RecognitionAttemptsMetricsSource,
    VoskAlternativesExperimentController {
    private val mutex = Mutex()
    private var model: VoskModelHandle? = null
    private var activeStreamingSession: VoskStreamingSession? = null
    private val mutableLatestRecognitionMetrics = MutableStateFlow<RecognitionComputeMetrics?>(null)
    override val latestRecognitionMetrics: StateFlow<RecognitionComputeMetrics?> =
        mutableLatestRecognitionMetrics.asStateFlow()
    private val mutableLatestRecognitionAttempts = MutableStateFlow<List<RecognitionComputeMetrics>>(emptyList())
    override val latestRecognitionAttempts: StateFlow<List<RecognitionComputeMetrics>> =
        mutableLatestRecognitionAttempts.asStateFlow()
    override val supportsStreamingRecognition: Boolean = true
    @Volatile private var maxAlternativesOverride: Int? = null

    override fun setMaxAlternativesOverride(maxAlternatives: Int?) {
        require(maxAlternatives == null || maxAlternatives in 0..MAX_EXPERIMENT_ALTERNATIVES) {
            "maxAlternatives override must be between 0 and $MAX_EXPERIMENT_ALTERNATIVES"
        }
        maxAlternativesOverride = maxAlternatives
    }

    override suspend fun initialize(): AppResult<Unit> = mutex.withLock {
        model?.let { return AppResult.Success(Unit) }
        val active = modelSource.activeModel()
            ?: return AppResult.Failure(AppError("ASR_MODEL_MISSING", "未安装可用的普通话识别模型"))
        if (active.runtime.substringBefore(':').lowercase() != "vosk") {
            return AppResult.Failure(AppError("ASR_RUNTIME", "当前 ASR 模型不是 Vosk 格式"))
        }
        if (active.sampleRateHz != SAMPLE_RATE_HZ) {
            return AppResult.Failure(AppError("ASR_SAMPLE_RATE", "Vosk 模型必须使用 16kHz 采样率"))
        }
        return try {
            model = PerformanceTrace.suspendSection("asr_model_initialize") {
                withContext(Dispatchers.IO) { engineFactory.openModel(active.directoryPath) }
            }
            AppResult.Success(Unit)
        } catch (error: Exception) {
            AppResult.Failure(AppError("ASR_INITIALIZE", "普通话识别模型初始化失败", error.message))
        }
    }

    override suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult> =
        recognize(audio, SpeechRecognitionContext())

    override suspend fun recognize(
        audio: CapturedAudio,
        context: SpeechRecognitionContext,
    ): AppResult<RecognitionResult> {
        if (!context.isSecondaryPass) mutableLatestRecognitionAttempts.value = emptyList()
        mutableLatestRecognitionMetrics.value = null
        if (!audio.speechDetected) {
            return AppResult.Failure(AppError("ASR_SILENCE", "没有检测到语音"))
        }
        if (audio.sampleRateHz != SAMPLE_RATE_HZ) {
            return AppResult.Failure(AppError("ASR_SAMPLE_RATE", "语音识别只接受 16kHz 单声道 PCM"))
        }
        if (audio.pcm16.isEmpty()) {
            return AppResult.Failure(AppError("ASR_EMPTY_AUDIO", "没有可识别的音频数据"))
        }
        if (model == null) {
            val initialized = initialize()
            if (initialized is AppResult.Failure) return initialized
        }

        return mutex.withLock {
            val activeModel = model
                ?: return@withLock AppResult.Failure(AppError("ASR_NOT_READY", "语音识别模块尚未就绪"))
            if (activeStreamingSession != null) {
                return@withLock AppResult.Failure(AppError("ASR_BUSY", "语音识别正在处理另一段音频"))
            }
            val phrases = recognitionPhrases(context)
            val options = optionsFor(context)
            val attemptIndex = mutableLatestRecognitionAttempts.value.size + 1
            try {
                val normalized = PerformanceTrace.suspendSection("asr_inference") {
                    withContext(inferenceDispatcher) {
                        val startedAt = elapsedRealtimeNanos()
                        val recognizerHandle = newRecognizer(activeModel, phrases, options)
                        recognizerHandle.use { recognizer ->
                            try {
                                recognizer.accept(audio.pcm16)
                                val normalized = normalize(recognizer.finalResult(), context)
                                recordComputeMetrics(
                                    startedAt = startedAt,
                                    completedAt = elapsedRealtimeNanos(),
                                    audio = audio,
                                    recognizedTextRaw = normalized.rawUsableText,
                                    errorCode = if (normalized.rawUsableText.isBlank()) "ASR_UNRECOGNIZABLE" else null,
                                    context = context,
                                    attemptIndex = attemptIndex,
                                    normalized = normalized,
                                )
                                normalized
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                recordComputeMetrics(
                                    startedAt = startedAt,
                                    completedAt = elapsedRealtimeNanos(),
                                    audio = audio,
                                    recognizedTextRaw = null,
                                    errorCode = "ASR_RECOGNIZE",
                                    context = context,
                                    attemptIndex = attemptIndex,
                                )
                                throw error
                            }
                        }
                    }
                }
                if (normalized.text.isEmpty()) {
                    AppResult.Failure(AppError("ASR_UNRECOGNIZABLE", "未能识别这段语音"))
                } else {
                    AppResult.Success(normalized.toRecognitionResult())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppResult.Failure(AppError("ASR_RECOGNIZE", "语音识别失败", error.message))
            }
        }
    }

    override suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession> {
        if (!context.isSecondaryPass) mutableLatestRecognitionAttempts.value = emptyList()
        mutableLatestRecognitionMetrics.value = null
        if (sampleRateHz != SAMPLE_RATE_HZ) {
            return AppResult.Failure(AppError("ASR_SAMPLE_RATE", "语音识别只接受 16kHz 单声道 PCM"))
        }
        if (model == null) {
            val initialized = initialize()
            if (initialized is AppResult.Failure) return initialized
        }
        return mutex.withLock {
            val activeModel = model
                ?: return@withLock AppResult.Failure(AppError("ASR_NOT_READY", "语音识别模块尚未就绪"))
            if (activeStreamingSession != null) {
                return@withLock AppResult.Failure(AppError("ASR_BUSY", "语音识别正在处理另一段音频"))
            }
            val phrases = recognitionPhrases(context)
            val options = optionsFor(context)
            try {
                val startedAt = elapsedRealtimeNanos()
                val recognizerCreateStartedAt = metricsClockNanos()
                val recognizerHandle = withContext(inferenceDispatcher) {
                    newRecognizer(activeModel, phrases, options)
                }
                val recognizerCreateDurationNanos =
                    (metricsClockNanos() - recognizerCreateStartedAt).coerceAtLeast(0L)
                val session = VoskStreamingSession(
                    recognizerHandle = recognizerHandle,
                    context = context,
                    attemptIndex = mutableLatestRecognitionAttempts.value.size + 1,
                    startedAt = startedAt,
                    recognizerCreateDurationNanos = recognizerCreateDurationNanos,
                )
                activeStreamingSession = session
                AppResult.Success(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppResult.Failure(AppError("ASR_RECOGNIZE", "无法创建流式识别会话", error.message))
            }
        }
    }

    private fun newRecognizer(
        activeModel: VoskModelHandle,
        phrases: List<String>,
        options: VoskRecognizerOptions,
    ): VoskRecognizerHandle =
        when {
            phrases.isEmpty() && options.isPlainText -> activeModel.newRecognizer(SAMPLE_RATE_HZ)
            options.isPlainText -> activeModel.newRecognizer(SAMPLE_RATE_HZ, phrases)
            else -> activeModel.newRecognizer(SAMPLE_RATE_HZ, phrases, options)
        }

    /**
     * A phrase-list grammar already confines the decoder to a handful of expected strings, so its
     * N-best list ranks that list rather than what was said, and reranking it would only measure
     * the phrase list against itself. Word confidence is kept: it still describes the audio.
     */
    private fun optionsFor(context: SpeechRecognitionContext): VoskRecognizerOptions =
        if (context.mode == SpeechRecognitionMode.SCENE_VOCABULARY) {
            recognizerOptions.copy(maxAlternatives = 0)
        } else {
            maxAlternativesOverride?.let { override ->
                recognizerOptions.copy(maxAlternatives = override, words = false)
            } ?: recognizerOptions
        }

    private fun recognitionPhrases(context: SpeechRecognitionContext): List<String> =
        if (context.mode == SpeechRecognitionMode.SCENE_VOCABULARY) {
            hotwords?.phrasesFor(context.sceneHints, context.focuses).orEmpty()
        } else {
            emptyList()
        }

    override suspend fun release() {
        val active = mutex.withLock {
            activeStreamingSession.also { activeStreamingSession = null }
        }
        active?.cancelFromOwner()
        mutex.withLock {
            model?.close()
            model = null
        }
    }

    private fun recordComputeMetrics(
        startedAt: Long,
        completedAt: Long,
        audio: CapturedAudio,
        recognizedTextRaw: String?,
        errorCode: String? = null,
        context: SpeechRecognitionContext,
        attemptIndex: Int,
        normalized: NormalizedRecognition? = null,
    ) {
        val metrics = RecognitionComputeMetrics(
            startedAtElapsedRealtimeNanos = startedAt,
            completedAtElapsedRealtimeNanos = completedAt,
            computeDurationMillis = ((completedAt - startedAt) / NANOS_PER_MILLISECOND).coerceAtLeast(0L),
            inputSamples = audio.pcm16.size,
            inputSampleRateHz = audio.sampleRateHz,
            recognizedTextRaw = recognizedTextRaw,
            errorCode = errorCode,
            attemptIndex = attemptIndex,
            recognitionMode = context.mode,
            sceneHints = context.sceneHints.map { it.id }.sorted(),
            recognitionFocuses = context.focuses.map { it.name.lowercase() }.sorted(),
            unknownTokenCount = normalized?.unknownTokenCount ?: 0,
            alternativeCount = normalized?.alternatives?.size?.takeIf { it > 0 },
            meanWordConfidence = normalized?.meanWordConfidence,
            minimumWordConfidence = normalized?.minimumWordConfidence,
        )
        mutableLatestRecognitionMetrics.value = metrics
        mutableLatestRecognitionAttempts.value = mutableLatestRecognitionAttempts.value + metrics
    }

    /**
     * Turns what the recognizer returned into what the rest of the app sees: unknown tokens
     * dropped, spacing normalized for the language, and in-scene homophones corrected.
     *
     * Every alternative goes through exactly the same steps as the primary text, because the point
     * of keeping them is to let a later stage compare them, and two strings that were cleaned
     * differently cannot be compared. Alternatives that collapse onto the same text after
     * normalization are folded into the best-ranked one rather than competing with themselves.
     */
    private fun normalize(
        recognition: VoskRecognition,
        context: SpeechRecognitionContext,
    ): NormalizedRecognition {
        val unknownTokenCount = countVoskUnknownTokens(recognition.text)
        val rawUsableText = removeVoskUnknownTokens(recognition.text)
        val words = recognition.words
            .filterNot { VOSK_UNKNOWN_TOKEN_REGEX.matches(it.word.trim()) }
            .map { RecognizedWord(it.word, it.confidence, it.startSeconds, it.endSeconds) }
        val confidences = words.map(RecognizedWord::confidence)
        return NormalizedRecognition(
            text = cleanText(rawUsableText, context),
            rawUsableText = rawUsableText,
            unknownTokenCount = unknownTokenCount,
            alternatives = recognition.alternatives
                .map { RecognitionAlternative(cleanText(removeVoskUnknownTokens(it.text), context), it.score) }
                .filter { it.text.isNotEmpty() }
                .distinctBy(RecognitionAlternative::text),
            words = words,
            meanWordConfidence = confidences.average().takeIf { confidences.isNotEmpty() }?.toFloat(),
            minimumWordConfidence = confidences.minOrNull(),
        )
    }

    private fun cleanText(usableText: String, context: SpeechRecognitionContext): String {
        val spaced = normalizeRecognitionSpacing(usableText, context.languageTag)
        return if (context.mode == SpeechRecognitionMode.SCENE_VOCABULARY) {
            hotwords?.correctRecognizedText(spaced, context.sceneHints) ?: spaced
        } else {
            spaced
        }
    }

    private data class NormalizedRecognition(
        val text: String,
        val rawUsableText: String,
        val unknownTokenCount: Int,
        val alternatives: List<RecognitionAlternative>,
        val words: List<RecognizedWord>,
        val meanWordConfidence: Float?,
        val minimumWordConfidence: Float?,
    ) {
        fun toRecognitionResult() = RecognitionResult(
            text = text,
            confidence = meanWordConfidence,
            isMock = false,
            alternatives = alternatives,
            words = words,
        )
    }

    private inner class VoskStreamingSession(
        private val recognizerHandle: VoskRecognizerHandle,
        private val context: SpeechRecognitionContext,
        private val attemptIndex: Int,
        private val startedAt: Long,
        private val recognizerCreateDurationNanos: Long,
    ) : StreamingSpeechRecognitionSession {
        override val recognizerId: String = UUID.randomUUID().toString()
        private val commands = Channel<StreamingCommand>(capacity = STREAM_BUFFER_FRAMES)
        private val scope = CoroutineScope(SupervisorJob() + inferenceDispatcher)
        @Volatile private var workerFailure: AppResult.Failure? = null
        @Volatile private var closed = false
        private val recognizerClosed = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Completes when whoever set [closed] has stopped touching the native recognizer.
         *
         * [closed] alone cannot carry that: it is set on *entry* to a teardown, so a second teardown
         * arriving mid-way saw the flag, concluded there was nothing to wait for, and freed the
         * recognizer under the first one. That is a use-after-free, and it was observed -- a
         * SIGSEGV at 0x10 inside Kaldi's ProcessNonemitting, on the worker thread, reached through
         * accept(), while finish() was draining the queue on 2026-08-08.
         *
         * The window is widest when finishing is slow, which asking for an N-best list makes it.
         */
        private val teardownComplete = CompletableDeferred<Unit>()
        private var inputSamples = 0
        private var computeDurationNanos = 0L
        private var acceptComputeDurationNanos = 0L
        private var acceptCpuDurationNanos = 0L
        private val completedSegments = ArrayList<VoskRecognition>()
        private val queuedAudioFrames = AtomicInteger(0)
        private val maxQueueDepth = AtomicInteger(0)
        private val totalQueueWaitNanos = AtomicLong(0L)
        private val maxQueueWaitNanos = AtomicLong(0L)
        private val acceptedFrameCount = AtomicInteger(0)
        private var drainDurationNanos = 0L
        private var finalResultDurationNanos = 0L
        @Volatile private var workerScheduling: ThreadSchedulingSnapshot? = null
        private val worker: Job = scope.launch {
            workerScheduling = readCurrentThreadScheduling()
            for (command in commands) {
                try {
                    val frameStartedAt = elapsedRealtimeNanos()
                    when (command) {
                        is StreamingCommand.AudioFrame -> {
                            val queueWaitNanos =
                                (metricsClockNanos() - command.enqueuedAtNanos).coerceAtLeast(0L)
                            totalQueueWaitNanos.addAndGet(queueWaitNanos)
                            maxQueueWaitNanos.updateMax(queueWaitNanos)
                            queuedAudioFrames.decrementAndGet()
                            val acceptStartedAt = metricsClockNanos()
                            val acceptCpuStartedAt = threadCpuClockNanos()
                            val completedSegment = PerformanceTrace.section("vosk_accept") {
                                recognizerHandle.accept(command.samples)
                            }
                            acceptedFrameCount.incrementAndGet()
                            acceptComputeDurationNanos +=
                                (metricsClockNanos() - acceptStartedAt).coerceAtLeast(0L)
                            acceptCpuDurationNanos +=
                                (threadCpuClockNanos() - acceptCpuStartedAt).coerceAtLeast(0L)
                            if (completedSegment) {
                                recognizerHandle.result()
                                    .takeIf { it.text.isNotBlank() }
                                    ?.let(completedSegments::add)
                            }
                        }
                        is StreamingCommand.Snapshot -> {
                            val partial = recognizerHandle.partialText()
                            command.response.complete(
                                AppResult.Success(
                                    StreamingRecognitionSnapshot(
                                        recognizerId = recognizerId,
                                        partialTextRaw = joinSegments(
                                            completedSegments.map(VoskRecognition::text),
                                            partial,
                                        ),
                                        // Vosk closed a segment and has started no new one, so its
                                        // own endpointer says the utterance ended here. Text that is
                                        // still accumulating means the opposite, whatever the
                                        // closed segments before it looked like.
                                        recognizerClosedSegment = completedSegments.isNotEmpty() &&
                                            partial.isBlank(),
                                    ),
                                ),
                            )
                        }
                    }
                    computeDurationNanos += (elapsedRealtimeNanos() - frameStartedAt).coerceAtLeast(0L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val failure = AppResult.Failure(
                        AppError("ASR_RECOGNIZE", "流式语音识别失败", error.message),
                    )
                    workerFailure = failure
                    if (command is StreamingCommand.Snapshot) command.response.complete(failure)
                    while (true) {
                        val pending = commands.tryReceive().getOrNull() ?: break
                        if (pending is StreamingCommand.Snapshot) pending.response.complete(failure)
                    }
                    commands.cancel()
                    break
                }
            }
        }

        override suspend fun accept(samples: ShortArray): AppResult<Unit> {
            if (closed) return AppResult.Failure(AppError("ASR_SESSION_CLOSED", "流式识别会话已经结束"))
            workerFailure?.let { return it }
            if (samples.isEmpty()) return AppResult.Success(Unit)
            val enqueuedAtNanos = metricsClockNanos()
            val depth = queuedAudioFrames.incrementAndGet()
            maxQueueDepth.updateMax(depth)
            var sent = false
            return try {
                commands.send(StreamingCommand.AudioFrame(samples, enqueuedAtNanos))
                sent = true
                inputSamples += samples.size
                workerFailure ?: AppResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                workerFailure ?: AppResult.Failure(
                    AppError("ASR_RECOGNIZE", "无法提交流式音频帧", error.message),
                )
            } finally {
                if (!sent) queuedAudioFrames.decrementAndGet()
            }
        }

        override suspend fun snapshot(): AppResult<StreamingRecognitionSnapshot> {
            if (closed) return AppResult.Failure(AppError("ASR_SESSION_CLOSED", "流式识别会话已经结束"))
            workerFailure?.let { return it }
            val response = CompletableDeferred<AppResult<StreamingRecognitionSnapshot>>()
            return try {
                commands.send(StreamingCommand.Snapshot(response))
                response.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                workerFailure ?: AppResult.Failure(
                    AppError("ASR_RECOGNIZE", "无法读取流式识别中间结果", error.message),
                )
            }
        }

        override suspend fun finish(speechDetected: Boolean): AppResult<RecognitionResult> {
            if (closed) return AppResult.Failure(AppError("ASR_SESSION_CLOSED", "流式识别会话已经结束"))
            closed = true
            // Every exit has to leave the recognizer closed and release a waiting canceller,
            // including the exit where join() throws because this coroutine was cancelled. Leaving
            // the recognizer open there would leak it, and leaving the canceller unreleased would
            // turn the use-after-free into a permanent wait inside NonCancellable. Both steps are
            // idempotent, so the paths below may still run them earlier.
            try {
                val drainStartedAt = metricsClockNanos()
                commands.close()
                worker.join()
                drainDurationNanos = (metricsClockNanos() - drainStartedAt).coerceAtLeast(0L)
                workerFailure?.let {
                    finishSession()
                    return it
                }
                if (!speechDetected || inputSamples == 0) {
                    finishSession()
                    val code = if (speechDetected) "ASR_EMPTY_AUDIO" else "ASR_SILENCE"
                    return AppResult.Failure(AppError(code, "没有可识别的语音"))
                }
                return finishRecognition()
            } finally {
                finishSession()
            }
        }

        override suspend fun cancel() = cancelInternal(detachFromOwner = true)

        suspend fun cancelFromOwner() = cancelInternal(detachFromOwner = false)

        // Cleanup runs from `finally` blocks that are often reached because the calling coroutine was
        // cancelled (turn timeout, session stop). Suspending normally there throws CancellationException
        // at the first suspension point, so the recognizer would stay attached to its owner and every
        // later turn would fail with ASR_BUSY for the rest of the process. NonCancellable guarantees
        // ownership is always released; it does not suppress or retry any recognition error.
        private suspend fun cancelInternal(detachFromOwner: Boolean) = withContext(NonCancellable) {
            if (closed) {
                // Someone else is already tearing this session down. Wait for them to finish rather
                // than freeing the recognizer they are still using, then only detach.
                teardownComplete.await()
                if (detachFromOwner) detachStreamingSession(this@VoskStreamingSession)
                return@withContext
            }
            closed = true
            try {
                commands.cancel()
                worker.cancelAndJoin()
                scope.cancel()
                closeRecognizer()
            } finally {
                teardownComplete.complete(Unit)
            }
            if (detachFromOwner) detachStreamingSession(this@VoskStreamingSession)
        }

        private suspend fun finishSession() = withContext(NonCancellable) {
            try {
                scope.cancel()
                closeRecognizer()
            } finally {
                teardownComplete.complete(Unit)
            }
            detachStreamingSession(this@VoskStreamingSession)
        }

        private fun closeRecognizer() {
            if (recognizerClosed.compareAndSet(false, true)) {
                runCatching { recognizerHandle.close() }
            }
        }

        private suspend fun finishRecognition(): AppResult<RecognitionResult> = try {
            val finalStartedAt = elapsedRealtimeNanos()
            val finalMetricStartedAt = metricsClockNanos()
            val finalSegment = withContext(inferenceDispatcher) { recognizerHandle.finalResult() }
            finalResultDurationNanos = (metricsClockNanos() - finalMetricStartedAt).coerceAtLeast(0L)
            val completedAt = elapsedRealtimeNanos()
            computeDurationNanos += (completedAt - finalStartedAt).coerceAtLeast(0L)
            val normalized = normalize(mergeSegments(completedSegments + finalSegment), context)
            recordStreamingComputeMetrics(
                startedAt = startedAt,
                completedAt = completedAt,
                computeDurationNanos = computeDurationNanos,
                inputSamples = inputSamples,
                recognizedTextRaw = normalized.rawUsableText,
                errorCode = if (normalized.rawUsableText.isBlank()) "ASR_UNRECOGNIZABLE" else null,
                context = context,
                attemptIndex = attemptIndex,
                normalized = normalized,
                recognizerCreateDurationNanos = recognizerCreateDurationNanos,
                voskAcceptComputeDurationNanos = acceptComputeDurationNanos,
                voskAcceptCpuDurationNanos = acceptCpuDurationNanos,
                voskQueueMaxDepth = maxQueueDepth.get(),
                voskQueueWaitDurationNanos = totalQueueWaitNanos.get(),
                voskQueueWaitMaxNanos = maxQueueWaitNanos.get(),
                voskDrainDurationNanos = drainDurationNanos,
                voskFinalResultDurationNanos = finalResultDurationNanos,
                voskAcceptedFrameCount = acceptedFrameCount.get(),
                workerScheduling = workerScheduling,
            )
            finishSession()
            if (normalized.text.isEmpty()) {
                AppResult.Failure(AppError("ASR_UNRECOGNIZABLE", "未能识别这段语音"))
            } else {
                AppResult.Success(normalized.toRecognitionResult())
            }
        } catch (cancelled: CancellationException) {
            finishSession()
            throw cancelled
        } catch (error: Exception) {
            finishSession()
            AppResult.Failure(AppError("ASR_RECOGNIZE", "语音识别失败", error.message))
        }
    }

    private sealed interface StreamingCommand {
        data class AudioFrame(
            val samples: ShortArray,
            val enqueuedAtNanos: Long,
        ) : StreamingCommand
        data class Snapshot(
            val response: CompletableDeferred<AppResult<StreamingRecognitionSnapshot>>,
        ) : StreamingCommand
    }

    private suspend fun detachStreamingSession(session: VoskStreamingSession) = mutex.withLock {
        if (activeStreamingSession === session) activeStreamingSession = null
    }

    private fun recordStreamingComputeMetrics(
        startedAt: Long,
        completedAt: Long,
        computeDurationNanos: Long,
        inputSamples: Int,
        recognizedTextRaw: String?,
        errorCode: String?,
        context: SpeechRecognitionContext,
        attemptIndex: Int,
        normalized: NormalizedRecognition,
        recognizerCreateDurationNanos: Long,
        voskAcceptComputeDurationNanos: Long,
        voskAcceptCpuDurationNanos: Long,
        voskQueueMaxDepth: Int,
        voskQueueWaitDurationNanos: Long,
        voskQueueWaitMaxNanos: Long,
        voskDrainDurationNanos: Long,
        voskFinalResultDurationNanos: Long,
        voskAcceptedFrameCount: Int,
        workerScheduling: ThreadSchedulingSnapshot?,
    ) {
        val metrics = RecognitionComputeMetrics(
            startedAtElapsedRealtimeNanos = startedAt,
            completedAtElapsedRealtimeNanos = completedAt,
            computeDurationMillis = (computeDurationNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L),
            inputSamples = inputSamples,
            inputSampleRateHz = SAMPLE_RATE_HZ,
            recognizedTextRaw = recognizedTextRaw,
            errorCode = errorCode,
            attemptIndex = attemptIndex,
            recognitionMode = context.mode,
            sceneHints = context.sceneHints.map { it.id }.sorted(),
            recognitionFocuses = context.focuses.map { it.name.lowercase() }.sorted(),
            unknownTokenCount = normalized.unknownTokenCount,
            alternativeCount = normalized.alternatives.size.takeIf { it > 0 },
            meanWordConfidence = normalized.meanWordConfidence,
            minimumWordConfidence = normalized.minimumWordConfidence,
            recognizerCreateDurationMillis = recognizerCreateDurationNanos.toMillis(),
            voskAcceptComputeDurationMillis = voskAcceptComputeDurationNanos.toMillis(),
            voskAcceptCpuDurationMillis = voskAcceptCpuDurationNanos.toMillis(),
            voskQueueMaxDepth = voskQueueMaxDepth,
            voskQueueWaitDurationMillis = voskQueueWaitDurationNanos.toMillis(),
            voskQueueWaitMaxMillis = voskQueueWaitMaxNanos.toMillis(),
            voskDrainDurationMillis = voskDrainDurationNanos.toMillis(),
            voskFinalResultDurationMillis = voskFinalResultDurationNanos.toMillis(),
            voskAcceptedFrameCount = voskAcceptedFrameCount,
            voskWorkerThreadId = workerScheduling?.threadId,
            voskWorkerThreadPriority = workerScheduling?.threadPriority,
            voskWorkerCpusAllowedList = workerScheduling?.cpusAllowedList,
            voskWorkerCpusetGroup = workerScheduling?.cpusetGroup,
        )
        mutableLatestRecognitionMetrics.value = metrics
        mutableLatestRecognitionAttempts.value = mutableLatestRecognitionAttempts.value + metrics
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val STREAM_BUFFER_FRAMES = 64
        const val MAX_EXPERIMENT_ALTERNATIVES = 10
    }
}

internal data class ThreadSchedulingSnapshot(
    val threadId: Int?,
    val threadPriority: Int?,
    val cpusAllowedList: String?,
    val cpusetGroup: String?,
)

internal fun parseCpusAllowedList(statusText: String): String? = statusText
    .lineSequence()
    .firstOrNull { it.startsWith("Cpus_allowed_list:") }
    ?.substringAfter(':')
    ?.trim()
    ?.takeIf(String::isNotEmpty)

internal fun parseCpusetGroup(cgroupText: String): String? {
    val lines = cgroupText.lineSequence().filter(String::isNotBlank).toList()
    val cpusetLine = lines.firstOrNull { line ->
        line.split(':', limit = 3).getOrNull(1)?.split(',')?.contains("cpuset") == true
    }
    val selected = cpusetLine ?: lines.firstOrNull { it.startsWith("0::") } ?: return null
    return selected.split(':', limit = 3).getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
}

private fun readCurrentThreadScheduling(): ThreadSchedulingSnapshot {
    val threadId = runCatching { android.os.Process.myTid() }.getOrNull()?.takeIf { it > 0 }
    val threadPriority = threadId?.let { tid ->
        runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
    }
    val taskRoot = threadId?.let { File("/proc/self/task/$it") }
    val statusText = taskRoot?.let { root -> runCatching { File(root, "status").readText() }.getOrNull() }
    val cgroupText = taskRoot?.let { root -> runCatching { File(root, "cgroup").readText() }.getOrNull() }
    return ThreadSchedulingSnapshot(
        threadId = threadId,
        threadPriority = threadPriority,
        cpusAllowedList = statusText?.let(::parseCpusAllowedList),
        cpusetGroup = cgroupText?.let(::parseCpusetGroup),
    )
}

private fun AtomicInteger.updateMax(candidate: Int) {
    var current = get()
    while (candidate > current && !compareAndSet(current, candidate)) current = get()
}

private fun AtomicLong.updateMax(candidate: Long) {
    var current = get()
    while (candidate > current && !compareAndSet(current, candidate)) current = get()
}

private fun Long.toMillis(): Long = (this / 1_000_000L).coerceAtLeast(0L)

private fun joinSegments(completedSegments: List<String>, lastSegment: String): String =
    (completedSegments + lastSegment)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")

/**
 * Combines the segments of one streaming turn into a single recognition.
 *
 * Vosk emits an independent N-best list per completed segment, so the true joint N-best over a
 * multi-segment turn is their cartesian product: exponential in the number of segments, and rarely
 * worth computing here because a turn is normally endpointed by our own VAD before Vosk splits it.
 * Taking rank i from every segment yields N whole-turn hypotheses at linear cost instead. A segment
 * that decoded unambiguously repeats its best hypothesis at every rank rather than truncating the
 * list, so one confident segment cannot hide the alternatives of the others.
 *
 * A combined score is only reported when every segment scored its pick, since a sum missing a term
 * is not comparable with one that has it. Vosk's score is a log likelihood, so summing is the right
 * combination for segments of one utterance.
 */
internal fun mergeSegments(segments: List<VoskRecognition>): VoskRecognition {
    val present = segments.filter { it.text.isNotBlank() }
    if (present.size <= 1) return present.firstOrNull() ?: VoskRecognition("")
    val depth = present.maxOf { it.alternatives.size }
    return VoskRecognition(
        text = joinSegments(present.map(VoskRecognition::text), ""),
        alternatives = (0 until depth)
            .map { rank ->
                val picked = present.map { segment ->
                    segment.alternatives.getOrNull(rank)
                        ?: segment.alternatives.firstOrNull()
                        ?: VoskHypothesis(segment.text, null)
                }
                val scores = picked.mapNotNull(VoskHypothesis::score)
                VoskHypothesis(
                    text = joinSegments(picked.map(VoskHypothesis::text), ""),
                    score = scores.sum().takeIf { scores.size == picked.size },
                )
            }
            .distinctBy(VoskHypothesis::text),
        words = present.flatMap(VoskRecognition::words),
    )
}

internal fun normalizeMandarinSpacing(text: String): String {
    val trimmed = text.trim()
    val normalized = StringBuilder(trimmed.length)
    var index = 0
    while (index < trimmed.length) {
        val current = trimmed[index]
        if (!current.isWhitespace()) {
            normalized.append(current)
            index += 1
            continue
        }

        var nextIndex = index + 1
        while (nextIndex < trimmed.length && trimmed[nextIndex].isWhitespace()) {
            nextIndex += 1
        }
        val previous = normalized.lastOrNull()
        val next = trimmed.getOrNull(nextIndex)
        if (previous != null && next != null && !previous.isHanCharacter() && !next.isHanCharacter()) {
            normalized.append(' ')
        }
        index = nextIndex
    }
    return normalized.toString()
}

internal fun normalizeRecognitionSpacing(text: String, languageTag: String): String =
    if (languageTag.startsWith("zh", ignoreCase = true)) {
        normalizeMandarinSpacing(text)
    } else {
        text.trim().replace(WHITESPACE_REGEX, " ")
    }

internal fun removeVoskUnknownTokens(text: String): String = text
    .replace(VOSK_UNKNOWN_TOKEN_REGEX, " ")
    .trim()

internal fun countVoskUnknownTokens(text: String): Int = VOSK_UNKNOWN_TOKEN_REGEX.findAll(text).count()

private val VOSK_UNKNOWN_TOKEN_REGEX = Regex("(?i)\\[unk]")
private val WHITESPACE_REGEX = Regex("\\s+")

private fun Char.isHanCharacter(): Boolean = code in 0x3400..0x9FFF
