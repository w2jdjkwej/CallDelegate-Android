package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.ModelType
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.core.ai.mock.MockSpeechSynthesizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.Executors

class SherpaSpeechSynthesizerTest {
    @Test
    fun switchingSynthesizer_usesRealBackendWhenMockModeIsOff() = runTest {
        val real = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, FakeSherpaFactory(floatArrayOf(0.2f), 22_050))
        val switching = SwitchingSpeechSynthesizer(MockSpeechSynthesizer(), real)

        switching.configure(mockMode = false)
        val result = switching.synthesize("您好", "session") as AppResult.Success

        assertThat(result.value.isMock).isFalse()
        assertThat(switching.isMock).isFalse()
    }

    @Test
    fun aStoredPhraseIsServedFromDiskInsteadOfBeingGeneratedAgain() = runTest {
        // Stands in for the next process: the memory LRU is empty but the recording is on disk.
        val store = RecordingSpeechStore()
        val warm = SherpaSpeechSynthesizer(
            ActiveModelSource { activeModel() },
            FakeSherpaFactory(floatArrayOf(0.5f), 22_050),
            persistentStore = store,
        )
        warm.synthesize("开场白", "first-process")

        val factory = FakeSherpaFactory(floatArrayOf(0.5f), 22_050)
        val restarted = SherpaSpeechSynthesizer(
            ActiveModelSource { activeModel() },
            factory,
            persistentStore = store,
        )
        val result = restarted.synthesize("开场白", "second-process") as AppResult.Success

        assertThat(factory.generateCount).isEqualTo(0)
        assertThat(restarted.latestSynthesisObservation?.cacheSource).isEqualTo(TtsCacheSource.DISK)
        assertThat(result.value.text).isEqualTo("开场白")
        assertThat(result.value.isMock).isFalse()
    }

    @Test
    fun aDiskHitIsPromotedIntoMemorySoTheSecondReadSkipsDiskToo() = runTest {
        val store = RecordingSpeechStore()
        SherpaSpeechSynthesizer(
            ActiveModelSource { activeModel() },
            FakeSherpaFactory(floatArrayOf(0.5f), 22_050),
            persistentStore = store,
        ).synthesize("再见", "first")

        val restarted = SherpaSpeechSynthesizer(
            ActiveModelSource { activeModel() },
            FakeSherpaFactory(floatArrayOf(0.5f), 22_050),
            persistentStore = store,
        )
        restarted.synthesize("再见", "s1")
        val loadsAfterFirst = store.loadCount
        restarted.synthesize("再见", "s2")

        assertThat(store.loadCount).isEqualTo(loadsAfterFirst)
    }

    @Test
    fun prewarmGeneratesOnlyThePhrasesNotAlreadyStored() = runTest {
        val store = RecordingSpeechStore()
        val factory = FakeSherpaFactory(floatArrayOf(0.5f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(
            ActiveModelSource { activeModel() },
            factory,
            persistentStore = store,
        )
        synthesizer.synthesize("已经有了", "session")
        val generatedBefore = factory.generateCount

        val generated = synthesizer.prewarm(listOf("已经有了", "还没有", "还没有", "   "))

        assertThat(generated).isEqualTo(1)
        assertThat(factory.generateCount).isEqualTo(generatedBefore + 1)
        assertThat(store.contains("还没有")).isTrue()
    }

    @Test
    fun prewarmedPhrasesStayInMemorySoTheCallPathNeverTouchesDisk() = runTest {
        val store = RecordingSpeechStore()
        val factory = FakeSherpaFactory(floatArrayOf(0.5f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(
            ActiveModelSource { activeModel() },
            factory,
            persistentStore = store,
        )
        synthesizer.prewarm(listOf("开场白"))
        // A burst of caller-specific replies far exceeding the LRU budget must not evict it.
        // Each of those does legitimately probe the store, so measure right before the phrase.
        repeat(20) { index -> synthesizer.synthesize("动态回复$index", "session") }
        val loadsBeforeReplay = store.loadCount
        val generatesBeforeReplay = factory.generateCount

        val result = synthesizer.synthesize("开场白", "session") as AppResult.Success

        assertThat(store.loadCount).isEqualTo(loadsBeforeReplay)
        assertThat(factory.generateCount).isEqualTo(generatesBeforeReplay)
        assertThat(result.value.text).isEqualTo("开场白")
    }

    @Test
    fun prewarmWithoutAPersistentStoreStillPinsIntoMemory() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(0.5f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        assertThat(synthesizer.prewarm(listOf("开场白"))).isEqualTo(1)
        repeat(20) { index -> synthesizer.synthesize("动态回复$index", "session") }
        val generatesBefore = factory.generateCount
        synthesizer.synthesize("开场白", "session")

        assertThat(factory.generateCount).isEqualTo(generatesBefore)
    }

    @Test
    fun prefetchingDuringACallLeavesTheTurnsOwnSynthesisObservationAlone() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(0.5f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        synthesizer.synthesize("这一轮的回复", "session")
        val turnObservation = synthesizer.latestSynthesisObservation

        // Prefetching now runs while a call is in progress, and it goes through synthesize(). The
        // turn breakdown in logcat reads this field, so a prefetch that overwrote it would report
        // the speculative synthesis as though the caller had waited for it.
        synthesizer.prefetch(listOf("下一轮可能要说的话"))

        assertThat(turnObservation).isNotNull()
        assertThat(synthesizer.latestSynthesisObservation).isEqualTo(turnObservation)
    }

    @Test
    fun prefetchedRepliesAreCachedWithoutTakingAPinnedSlot() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(0.5f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        val first = synthesizer.prefetch(listOf("好的，放在门口已记录。还有其他事项吗？"))
        assertThat(first.generated).isEqualTo(1)
        assertThat(first.alreadyStored).isEqualTo(0)
        assertThat(first.failed).isEqualTo(0)
        val generatesBefore = factory.generateCount
        synthesizer.synthesize("好的，放在门口已记录。还有其他事项吗？", "session")

        // Speaking it is a cache read, which is the whole point.
        assertThat(factory.generateCount).isEqualTo(generatesBefore)
    }

    @Test
    fun completeSpeechCache_pinnedEntriesSurviveEvictionPressure() {
        val cache = CompleteSpeechCache(maxEntries = 2, maxPcmSamples = 10)
        cache.pin(speech("opening"))

        cache.put(speech("first"))
        cache.put(speech("second"))
        cache.put(speech("third"))

        assertThat(cache["opening"]).isNotNull()
        assertThat(cache.pinnedCount()).isEqualTo(1)
    }

    @Test
    fun completeSpeechCache_refusesToPinBeyondItsBudget() {
        val cache = CompleteSpeechCache(maxEntries = 4, maxPcmSamples = 10, maxPinnedPcmSamples = 3)

        assertThat(cache.pin(speech("fits", sampleCount = 2))).isTrue()
        assertThat(cache.pin(speech("does not fit", sampleCount = 2))).isFalse()
        assertThat(cache.pinnedCount()).isEqualTo(1)
    }

    @Test
    fun synthesize_convertsFloatSamplesToRealPcm() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(-1f, 0f, 0.5f, 1f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        synthesizer.initialize()
        val result = synthesizer.synthesize("您好", "session") as AppResult.Success

        assertThat(result.value.isMock).isFalse()
        assertThat(result.value.sampleRateHz).isEqualTo(22_050)
        assertThat(result.value.pcm16.toList()).containsExactly(
            (-32767).toShort(), 0.toShort(), 16383.toShort(), 32767.toShort(),
        ).inOrder()
        assertThat(factory.lastSpeakerId).isEqualTo(108)
        // The assistant talks slightly faster than the model's natural rate: on a real call its own
        // speech was the larger half of the turn the caller sat through.
        assertThat(factory.lastSpeed).isEqualTo(1.1f)
    }

    @Test
    fun initialize_rejectsMissingModel() = runTest {
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { null }, FakeSherpaFactory(floatArrayOf(), 22_050))

        val result = synthesizer.initialize()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("TTS_MODEL_MISSING")
    }

    @Test
    fun synthesize_reusesCompleteSpeechForTheSameText() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(0.2f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        val first = synthesizer.synthesize("same complete reply", "session-1") as AppResult.Success
        val second = synthesizer.synthesize("same complete reply", "session-2") as AppResult.Success

        assertThat(factory.generateCount).isEqualTo(1)
        assertThat(synthesizer.latestSynthesisObservation?.cacheSource).isEqualTo(TtsCacheSource.MEMORY)
        assertThat(second.value.pcm16.toList()).containsExactlyElementsIn(first.value.pcm16.toList())
    }

    @Test
    fun initializeForThreadCount_reopensOnlyWhenTheThreadCountChanges() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(0.2f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        synthesizer.initializeForThreadCount(2)
        synthesizer.initializeForThreadCount(2)
        synthesizer.initializeForThreadCount(4)

        assertThat(factory.openedThreadCounts).containsExactly(2, 4).inOrder()
        assertThat(synthesizer.activeThreadCount).isEqualTo(4)
    }

    @Test
    fun synthesize_doesNotReuseTextFragments() = runTest {
        val factory = FakeSherpaFactory(floatArrayOf(0.2f), 22_050)
        val synthesizer = SherpaSpeechSynthesizer(ActiveModelSource { activeModel() }, factory)

        synthesizer.synthesize("wait in the lobby", "session-1")
        synthesizer.synthesize("wait at the north gate", "session-2")

        assertThat(factory.generateCount).isEqualTo(2)
    }

    @Test
    fun completeSpeechCache_evictsLeastRecentlyUsedEntry() {
        val cache = CompleteSpeechCache(maxEntries = 2, maxPcmSamples = 10)
        cache.put(speech("first"))
        cache.put(speech("second"))
        cache["first"]

        cache.put(speech("third"))

        assertThat(cache["first"]).isNotNull()
        assertThat(cache["second"]).isNull()
        assertThat(cache["third"]).isNotNull()
    }

    @Test
    fun completeSpeechCache_skipsAudioAboveMemoryLimit() {
        val cache = CompleteSpeechCache(maxEntries = 2, maxPcmSamples = 2)

        cache.put(speech("too long", sampleCount = 3))

        assertThat(cache["too long"]).isNull()
    }

    @Test
    fun synthesize_usesInjectedInferenceDispatcher() {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-tts-inference")
        }.asCoroutineDispatcher()
        dispatcher.use {
            val factory = FakeSherpaFactory(floatArrayOf(0.2f), 22_050)
            val synthesizer = SherpaSpeechSynthesizer(
                modelSource = ActiveModelSource { activeModel() },
                engineFactory = factory,
                inferenceDispatcher = dispatcher,
            )

            runBlocking { synthesizer.synthesize("complete reply", "session") }

            assertThat(factory.generateThreadName).startsWith("test-tts-inference")
        }
    }

    private fun speech(text: String, sampleCount: Int = 1) = SynthesizedSpeech(
        text = text,
        audioPath = null,
        durationMillis = sampleCount.toLong(),
        isMock = false,
        pcm16 = ShortArray(sampleCount),
        sampleRateHz = 1_000,
    )

    private fun activeModel() = ActiveModel(
        ModelType.TTS, "1.0.0", "Test TTS", "sherpa-onnx", "test-model", 22_050,
        mapOf("MODEL" to "model.onnx", "TOKENS" to "tokens.txt", "LEXICON" to "lexicon.txt"),
    )
}

private class RecordingSpeechStore : SynthesizedSpeechStore {
    private val stored = mutableMapOf<String, SynthesizedSpeech>()
    var loadCount = 0
        private set

    override fun load(text: String, voice: String): SynthesizedSpeech? {
        loadCount++
        return stored["$voice $text"]
    }

    override fun save(speech: SynthesizedSpeech, voice: String) {
        stored["$voice ${speech.text}"] = speech
    }

    fun contains(text: String) = stored.keys.any { it.endsWith(" " + text) }
}

private class FakeSherpaFactory(private val samples: FloatArray, private val sampleRate: Int) : SherpaTtsEngineFactory {
    var lastSpeakerId: Int? = null
        private set
    var lastSpeed: Float? = null
        private set
    var generateCount: Int = 0
        private set
    var generateThreadName: String? = null
        private set
    val openedThreadCounts = mutableListOf<Int>()

    override fun open(model: ActiveModel, threadCount: Int): SherpaTtsHandle = object : SherpaTtsHandle {
        init {
            openedThreadCounts += threadCount
        }

        override fun generate(text: String, speakerId: Int, speed: Float): GeneratedPcm {
            generateThreadName = Thread.currentThread().name
            lastSpeakerId = speakerId
            lastSpeed = speed
            generateCount += 1
            return GeneratedPcm(samples, sampleRate)
        }

        override fun close() = Unit
    }
}
