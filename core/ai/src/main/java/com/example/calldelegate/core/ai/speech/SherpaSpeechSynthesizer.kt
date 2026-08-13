package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.PerformanceTrace
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GeneratedPcm(val samples: FloatArray, val sampleRateHz: Int)

interface SherpaTtsEngineFactory {
    fun open(model: ActiveModel, threadCount: Int): SherpaTtsHandle
}

interface SherpaTtsHandle : AutoCloseable {
    fun generate(text: String, speakerId: Int, speed: Float): GeneratedPcm
}

internal enum class TtsCacheSource(val logValue: String) {
    MEMORY("memory"),
    DISK("disk"),
    MISS("miss"),
}

internal data class TtsSynthesisObservation(
    val cacheSource: TtsCacheSource,
    val cacheLookupDurationMillis: Long,
    val generationDurationMillis: Long,
    val persistenceDurationMillis: Long,
    val totalDurationMillis: Long,
    val engineInstanceId: Long,
    val threadCount: Int,
)

class SherpaSpeechSynthesizer(
    private val modelSource: ActiveModelSource,
    private val engineFactory: SherpaTtsEngineFactory,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Optional cross-process cache for replies whose text never varies. Null keeps the previous
     * memory-only behavior, so a caller that does not supply one is unaffected.
     */
    private val persistentStore: SynthesizedSpeechStore? = null,
) : SpeechSynthesizer {
    private val mutex = Mutex()
    private val speechCache = CompleteSpeechCache(
        maxEntries = MAX_CACHE_ENTRIES,
        maxPcmSamples = MAX_CACHE_PCM_SAMPLES,
    )
    private var engine: SherpaTtsHandle? = null
    private var loadedThreadCount: Int? = null
    private var engineInstanceId: Long? = null
    @Volatile internal var latestSynthesisObservation: TtsSynthesisObservation? = null
        private set

    internal val activeEngineInstanceId: Long?
        get() = engineInstanceId

    internal val activeThreadCount: Int?
        get() = loadedThreadCount

    override suspend fun initialize(): AppResult<Unit> = initializeForThreadCount(DEFAULT_THREAD_COUNT)

    internal suspend fun initializeForThreadCount(threadCount: Int): AppResult<Unit> = mutex.withLock {
        val requestedThreadCount = threadCount.coerceIn(MIN_THREAD_COUNT, MAX_THREAD_COUNT)
        if (engine != null && loadedThreadCount == requestedThreadCount) {
            return AppResult.Success(Unit)
        }
        if (engine != null) closeEngineLocked()
        val active = modelSource.activeModel()
            ?: return AppResult.Failure(AppError("TTS_MODEL_MISSING", "未安装可用的普通话语音合成模型"))
        if (active.runtime.substringBefore(':').lowercase() != "sherpa-onnx") {
            return AppResult.Failure(AppError("TTS_RUNTIME", "当前 TTS 模型不是 sherpa-onnx 格式"))
        }
        return try {
            engine = PerformanceTrace.suspendSection("tts_model_initialize") {
                withContext(Dispatchers.IO) { engineFactory.open(active, requestedThreadCount) }
            }
            loadedThreadCount = requestedThreadCount
            engineInstanceId = nextEngineInstanceId()
            AppResult.Success(Unit)
        } catch (error: Exception) {
            loadedThreadCount = null
            engineInstanceId = null
            AppResult.Failure(AppError("TTS_INITIALIZE", "普通话语音合成模型初始化失败", error.message))
        }
    }

    override suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech> {
        if (text.isBlank()) return AppResult.Failure(AppError("TTS_EMPTY_TEXT", "没有需要合成的文字"))
        // Only a reply the caller is waiting for describes the turn. Prefetching now runs during a
        // call rather than only before one, and it goes through this same method, so without this
        // it would overwrite -- or with the reset below, erase -- the observation the turn log reads.
        val observed = sessionId != PREWARM_SESSION_ID
        fun record(observation: TtsSynthesisObservation) {
            if (observed) latestSynthesisObservation = observation
        }
        if (observed) latestSynthesisObservation = null
        if (engine == null) {
            val initialized = initialize()
            if (initialized is AppResult.Failure) return initialized
        }
        return mutex.withLock {
            val activeEngine = engine
                ?: return@withLock AppResult.Failure(AppError("TTS_NOT_READY", "语音合成模块尚未就绪"))
            val instanceId = engineInstanceId ?: 0L
            val threads = loadedThreadCount ?: DEFAULT_THREAD_COUNT
            val totalStartedAt = System.nanoTime()
            val memoryLookupStartedAt = System.nanoTime()
            val memorySpeech = speechCache[text]
            var cacheLookupNanos = elapsedNanos(memoryLookupStartedAt)
            if (memorySpeech != null) {
                record(
                    TtsSynthesisObservation(
                        cacheSource = TtsCacheSource.MEMORY,
                        cacheLookupDurationMillis = cacheLookupNanos.toMillis(),
                        generationDurationMillis = 0L,
                        persistenceDurationMillis = 0L,
                        totalDurationMillis = elapsedNanos(totalStartedAt).toMillis(),
                        engineInstanceId = instanceId,
                        threadCount = threads,
                    ),
                )
                return@withLock AppResult.Success(memorySpeech)
            }
            val voice = currentVoiceTag()
            persistentStore?.let { store ->
                val diskLookupStartedAt = System.nanoTime()
                val stored = withContext(Dispatchers.IO) { store.load(text, voice) }
                cacheLookupNanos += elapsedNanos(diskLookupStartedAt)
                stored?.let {
                    // Promote into the memory LRU so a repeat within this session skips disk too.
                    speechCache.put(it)
                    record(
                        TtsSynthesisObservation(
                            cacheSource = TtsCacheSource.DISK,
                            cacheLookupDurationMillis = cacheLookupNanos.toMillis(),
                            generationDurationMillis = 0L,
                            persistenceDurationMillis = 0L,
                            totalDurationMillis = elapsedNanos(totalStartedAt).toMillis(),
                            engineInstanceId = instanceId,
                            threadCount = threads,
                        ),
                    )
                    return@withLock AppResult.Success(it)
                }
            }
            try {
                val generationStartedAt = System.nanoTime()
                val speech = PerformanceTrace.suspendSection("tts_synthesis") {
                    withContext(inferenceDispatcher) {
                        val generated = activeEngine.generate(text, SPEAKER_ID, SPEECH_SPEED)
                        if (generated.samples.isEmpty() || generated.sampleRateHz <= 0) {
                            return@withContext null
                        }
                        val pcm = ShortArray(generated.samples.size) { index ->
                            (generated.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE)
                                .toInt()
                                .toShort()
                        }
                        SynthesizedSpeech(
                            text = text,
                            audioPath = null,
                            durationMillis = pcm.size * 1_000L / generated.sampleRateHz,
                            isMock = false,
                            pcm16 = pcm,
                            sampleRateHz = generated.sampleRateHz,
                        )
                    }
                }
                val generationNanos = elapsedNanos(generationStartedAt)
                if (speech == null) {
                    return@withLock AppResult.Failure(AppError("TTS_EMPTY_AUDIO", "语音合成没有生成音频"))
                }
                speechCache.put(speech)
                val persistenceStartedAt = System.nanoTime()
                persistentStore?.let { store ->
                    withContext(Dispatchers.IO) { store.save(speech, voice) }
                }
                val persistenceNanos = elapsedNanos(persistenceStartedAt)
                record(
                    TtsSynthesisObservation(
                        cacheSource = TtsCacheSource.MISS,
                        cacheLookupDurationMillis = cacheLookupNanos.toMillis(),
                        generationDurationMillis = generationNanos.toMillis(),
                        persistenceDurationMillis = persistenceNanos.toMillis(),
                        totalDurationMillis = elapsedNanos(totalStartedAt).toMillis(),
                        engineInstanceId = instanceId,
                        threadCount = threads,
                    ),
                )
                AppResult.Success(speech)
            } catch (error: Exception) {
                AppResult.Failure(AppError("TTS_SYNTHESIZE", "普通话语音合成失败", error.message))
            }
        }
    }

    /**
     * Generates and persists any of [texts] that are not already stored, so a later call does not
     * pay for them. Returns how many were newly generated.
     *
     * Must only be run while idle: it takes the same lock as [synthesize], so calling it during a
     * call would stall the reply it is meant to speed up. A failure on one phrase is skipped rather
     * than aborting the rest -- prewarming is an optimization, and its failure must never surface
     * as a call failure.
     */
    suspend fun prewarm(texts: List<String>): Int = warm(texts, pin = true).generated

    /**
     * Synthesises caller-specific replies mid-call, while the engine is idle, so that speaking one
     * later is a cache read.
     *
     * Unpinned, unlike [prewarm]: these texts are true of one call only, and pinning them would
     * spend the memory cache that the phrases every call needs are held in.
     *
     * The lock warning on [prewarm] applies here with less room, because a call is in progress. The
     * caller must cancel this before the next reply is due; cancellation is checked between phrases,
     * so the worst case is one synthesis already handed to the engine.
     */
    suspend fun prefetch(texts: List<String>): WarmOutcome = warm(texts, pin = false)

    /**
     * What warming a set of phrases actually did.
     *
     * [alreadyStored] is separate from [generated] because a count of generations alone cannot say
     * whether a zero means there was nothing to do or that every attempt failed. The first device
     * run of the prefetch reported `generated=0` for a reply that was already on disk from the call
     * before it, and the log could not distinguish that from a prefetch that was never working.
     */
    data class WarmOutcome(
        val generated: Int,
        val alreadyStored: Int,
        val failed: Int,
    )

    private suspend fun warm(texts: List<String>, pin: Boolean): WarmOutcome {
        val voice = currentVoiceTag()
        var generated = 0
        var alreadyStored = 0
        var failed = 0
        for (text in texts.filter { it.isNotBlank() }.distinct()) {
            currentCoroutineContext().ensureActive()
            val stored = persistentStore?.let { store ->
                withContext(Dispatchers.IO) { store.load(text, voice) }
            }
            if (stored != null) alreadyStored++
            val speech = stored ?: run {
                val result = synthesize(text, sessionId = PREWARM_SESSION_ID)
                if (result !is AppResult.Success) {
                    failed++
                    return@run null
                }
                generated++
                result.value
            } ?: continue
            // Pinned rather than merely cached: a burst of caller-specific replies must not evict
            // the opening prompt and put disk I/O back on the next call's critical path. A prefetched
            // reply is itself one of those caller-specific replies, so it takes an ordinary slot.
            if (pin) mutex.withLock { speechCache.pin(speech) } else mutex.withLock { speechCache.put(speech) }
        }
        return WarmOutcome(generated = generated, alreadyStored = alreadyStored, failed = failed)
    }

    /**
     * Identifies the voice a recording was produced with. The app supports importing and switching
     * TTS models, so a cached phrase from a previous model must never be replayed against a new one:
     * the opening prompt would come out in the old voice while the rest of the call uses the new
     * one. Speaker id is included for the same reason.
     */
    private suspend fun currentVoiceTag(): String {
        val active = modelSource.activeModel() ?: return "unknown"
        return listOf(
            active.runtime,
            active.version,
            active.directoryPath,
            active.sampleRateHz.toString(),
            SPEAKER_ID.toString(),
            // Speed belongs in the key: the disk cache outlives the setting, and without this a
            // rate change would keep serving whatever rate was current when each entry was written.
            SPEECH_SPEED.toString(),
        ).joinToString("|")
    }

    override suspend fun release() = mutex.withLock {
        closeEngineLocked()
        Unit
    }

    private fun closeEngineLocked() {
        try {
            engine?.close()
        } finally {
            engine = null
            loadedThreadCount = null
            engineInstanceId = null
            latestSynthesisObservation = null
            speechCache.clear()
        }
    }

    private companion object {
        const val SPEAKER_ID = 108

        /**
         * How fast the assistant talks, as a multiple of the model's natural rate.
         *
         * Measured on a real call: the assistant speaks for 3.2 s of a 4.6 s turn, against 1.3 s of
         * waiting, so its own speech is now the larger half of what the caller sits through and the
         * cheapest thing left to shorten. 1.1 buys about a tenth of that back. Higher was not taken
         * without listening to it on a phone line -- an assistant that gabbles reads as evasive, and
         * the 8 kHz channel is already unkind to consonants.
         */
        const val SPEECH_SPEED = 1.1f
        const val PREWARM_SESSION_ID = "prewarm"
        const val MAX_CACHE_ENTRIES = 8
        const val MAX_CACHE_PCM_SAMPLES = 2_000_000
        const val DEFAULT_THREAD_COUNT = 2
        const val MIN_THREAD_COUNT = 1
        const val MAX_THREAD_COUNT = 4

        private val instanceCounter = java.util.concurrent.atomic.AtomicLong(0L)

        fun nextEngineInstanceId(): Long = instanceCounter.incrementAndGet()
    }
}

private fun elapsedNanos(startedAt: Long): Long = (System.nanoTime() - startedAt).coerceAtLeast(0L)

private fun Long.toMillis(): Long = (this / 1_000_000L).coerceAtLeast(0L)

internal class CompleteSpeechCache(
    private val maxEntries: Int,
    private val maxPcmSamples: Int,
    private val maxPinnedPcmSamples: Int = DEFAULT_MAX_PINNED_PCM_SAMPLES,
) {
    private val cachedSpeech = LinkedHashMap<String, SynthesizedSpeech>(maxEntries, 0.75f, true)
    private var cachedPcmSamples = 0

    /**
     * Replies whose text never varies, held outside the LRU. They are the ones spoken most often --
     * the opening prompt runs on every call -- so letting a burst of caller-specific replies evict
     * them would put a disk read, or a full synthesis, back on the critical path.
     */
    private val pinnedSpeech = LinkedHashMap<String, SynthesizedSpeech>()
    private var pinnedPcmSamples = 0

    init {
        require(maxEntries > 0)
        require(maxPcmSamples > 0)
        require(maxPinnedPcmSamples > 0)
    }

    operator fun get(text: String): SynthesizedSpeech? = pinnedSpeech[text] ?: cachedSpeech[text]

    /**
     * Keeps [speech] resident for the lifetime of the engine. Bounded so a pathological rule file
     * cannot pin unlimited audio; once the budget is reached further phrases stay on the LRU path.
     */
    fun pin(speech: SynthesizedSpeech): Boolean {
        if (pinnedSpeech.containsKey(speech.text)) return true
        if (pinnedPcmSamples + speech.pcm16.size > maxPinnedPcmSamples) return false
        cachedSpeech.remove(speech.text)?.let { cachedPcmSamples -= it.pcm16.size }
        pinnedSpeech[speech.text] = speech
        pinnedPcmSamples += speech.pcm16.size
        return true
    }

    fun pinnedCount(): Int = pinnedSpeech.size

    fun put(speech: SynthesizedSpeech) {
        if (speech.pcm16.size > maxPcmSamples) return
        if (pinnedSpeech.containsKey(speech.text)) return

        cachedSpeech.remove(speech.text)?.let { cachedPcmSamples -= it.pcm16.size }
        cachedSpeech[speech.text] = speech
        cachedPcmSamples += speech.pcm16.size

        while (cachedSpeech.size > maxEntries || cachedPcmSamples > maxPcmSamples) {
            val oldest = cachedSpeech.entries.iterator().next()
            cachedPcmSamples -= oldest.value.pcm16.size
            cachedSpeech.remove(oldest.key)
        }
    }

    fun clear() {
        cachedSpeech.clear()
        cachedPcmSamples = 0
        pinnedSpeech.clear()
        pinnedPcmSamples = 0
    }

    companion object {
        /**
         * ~16 MB of PCM16. The fixed replies are roughly ten phrases of a few seconds each, so this
         * holds them all with room to spare, and is negligible against the 448 MB peak PSS measured
         * on device against a 1400 MB budget.
         */
        const val DEFAULT_MAX_PINNED_PCM_SAMPLES = 8_000_000
    }
}
