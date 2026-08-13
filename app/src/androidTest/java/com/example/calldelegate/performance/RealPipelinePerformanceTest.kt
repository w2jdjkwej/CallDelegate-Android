package com.example.calldelegate.performance

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.PerformanceStatistics
import com.example.calldelegate.di.DebugTestEntryPoint
import com.example.calldelegate.domain.api.AudioPlaybackMetrics
import com.example.calldelegate.domain.api.PlaybackMetricsSource
import com.example.calldelegate.domain.model.ModelType
import dagger.hilt.android.EntryPointAccessors
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real, non-Mock Vosk + rule engine + sherpa-onnx pipeline on a controlled WAV fixture.
 *
 * The fixture is deliberately pushed by the test operator, rather than bundled as synthetic speech.
 * This prevents the report from being mistaken for a real-speech result when no validated reference
 * audio has been supplied.
 */
@RunWith(AndroidJUnit4::class)
class RealPipelinePerformanceTest {
    @Test
    fun collectsRealPipelineMetricsFromControlledFixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val fixturePath = arguments.getString(ARG_AUDIO_PATH)
        val referenceText = arguments.getString(ARG_REFERENCE_TEXT)
        assumeTrue("Set $ARG_AUDIO_PATH to a 16 kHz mono PCM WAV under /data/local/tmp", !fixturePath.isNullOrBlank())
        assumeTrue("Set $ARG_REFERENCE_TEXT to calculate CER", !referenceText.isNullOrBlank())

        val runCount = arguments.positiveInt(ARG_RUN_COUNT, DEFAULT_RUN_COUNT, MAX_RUN_COUNT)
        val warmupCount = arguments.nonNegativeInt(ARG_WARMUP_COUNT, DEFAULT_WARMUP_COUNT, MAX_WARMUP_COUNT)
        val audioId = arguments.getString(ARG_AUDIO_ID)?.ifBlank { null } ?: "controlled_fixture"
        val fixture = WavFixtureLoader.load(requireNotNull(fixturePath), audioId)
        val targetContext = instrumentation.targetContext
        val entryPoint = EntryPointAccessors.fromApplication(targetContext, DebugTestEntryPoint::class.java)
        val runtime = entryPoint.speechRuntimeManager()
        val recognizer = entryPoint.speechRecognizer()
        val synthesizer = entryPoint.speechSynthesizer()
        val dialogue = entryPoint.dialogueEngine()
        val settings = entryPoint.settingsRepository().current()
        val output = entryPoint.audioOutputSink()
        val playbackMetrics = output as? PlaybackMetricsSource
            ?: error("The configured AudioOutputSink does not expose playback metrics")

        entryPoint.modelManager().refresh()
        runtime.configure(mockMode = false)
        assertFalse("Real pipeline test must not run in Mock mode", runtime.isMock)

        val sampler = ProcessResourceSampler(targetContext)
        val resourceSamples = Collections.synchronizedList(mutableListOf<ProcessResourceSample>())
        val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val samplerJob = samplerScope.launch {
            while (isActive) {
                resourceSamples += sampler.snapshot()
                delay(RESOURCE_SAMPLE_INTERVAL_MS)
            }
        }

        val rounds = mutableListOf<PipelineRound>()
        val startedEnvironment = PerformanceReportWriter.deviceEnvironment(targetContext)
        val asrInitMs = measureMillis { requireSuccess("ASR initialize") { recognizer.initialize() } }
        val ttsInitMs = measureMillis { requireSuccess("TTS initialize") { synthesizer.initialize() } }
        try {
            repeat(warmupCount) { index ->
                runRound(
                    roundId = -(index + 1),
                    fixture = fixture,
                    referenceText = requireNotNull(referenceText),
                    dialogue = dialogue,
                    enabledScenes = settings.enabledScenes,
                    recognizer = recognizer,
                    synthesizer = synthesizer,
                    output = output,
                    playbackMetrics = playbackMetrics,
                )
            }

            repeat(runCount) { index ->
                val round = runRound(
                    roundId = index + 1,
                    fixture = fixture,
                    referenceText = requireNotNull(referenceText),
                    dialogue = dialogue,
                    enabledScenes = settings.enabledScenes,
                    recognizer = recognizer,
                    synthesizer = synthesizer,
                    output = output,
                    playbackMetrics = playbackMetrics,
                )
                val resource = sampler.snapshot()
                rounds += round.copy(
                    totalPssKb = resource.totalPssKb,
                    nativePssKb = resource.nativePssKb,
                    javaHeapBytes = resource.javaHeapBytes,
                    nativeHeapBytes = resource.nativeHeapBytes,
                    cpuPercent = resource.cpuPercent,
                    thermalStatus = resource.thermalStatus,
                    threadCount = resource.threadCount,
                    openFileDescriptorCount = resource.openFileDescriptorCount,
                )
            }
        } finally {
            samplerJob.cancelAndJoin()
            output.stop()
            recognizer.release()
            synthesizer.release()
            runtime.releaseAll()
        }

        val sampledResources = synchronized(resourceSamples) { resourceSamples.toList() }
        val summary = buildSummary(
            entryPoint = entryPoint,
            fixture = fixture,
            runCount = runCount,
            warmupCount = warmupCount,
            asrInitMs = asrInitMs,
            ttsInitMs = ttsInitMs,
            rounds = rounds,
            sampledResources = sampledResources,
            startedEnvironment = startedEnvironment,
            completedEnvironment = PerformanceReportWriter.deviceEnvironment(targetContext),
        )
        val outputDirectory = PerformanceReportWriter.writeReport(
            context = targetContext,
            reportName = "real_pipeline",
            summary = summary,
            samplesCsv = rounds.toCsv(),
        )
        instrumentation.sendStatus(
            STATUS_CODE,
            android.os.Bundle().apply {
                putString("performance_result_directory", outputDirectory.absolutePath)
                putInt("performance_round_count", rounds.size)
                putInt("performance_success_count", rounds.count { it.success })
            },
        )
    }

    private suspend fun runRound(
        roundId: Int,
        fixture: WavFixture,
        referenceText: String,
        dialogue: com.example.calldelegate.domain.api.DialogueEngine,
        enabledScenes: Set<com.example.calldelegate.domain.model.SceneType>,
        recognizer: com.example.calldelegate.domain.api.SpeechRecognizer,
        synthesizer: com.example.calldelegate.domain.api.SpeechSynthesizer,
        output: com.example.calldelegate.domain.api.AudioOutputSink,
        playbackMetrics: PlaybackMetricsSource,
    ): PipelineRound {
        val asrStartedAt = SystemClock.elapsedRealtime()
        val recognition = recognizer.recognize(fixture.audio)
        val asrFinishedAt = SystemClock.elapsedRealtime()
        if (recognition is AppResult.Failure) {
            return PipelineRound.failure(roundId, fixture.audioId, asrFinishedAt - asrStartedAt, recognition.error.code)
        }
        val recognizedText = (recognition as AppResult.Success).value.text
        val cer = characterErrorRate(referenceText, recognizedText)

        val nluStartedAt = SystemClock.elapsedRealtime()
        val opening = dialogue.opening("performance-$roundId")
        val decision = dialogue.process(opening.context, recognizedText, false, enabledScenes)
        val nluFinishedAt = SystemClock.elapsedRealtime()

        val ttsStartedAt = SystemClock.elapsedRealtime()
        val synthesis = synthesizer.synthesize(decision.reply, "performance-$roundId")
        val synthesisFinishedAt = SystemClock.elapsedRealtime()
        if (synthesis is AppResult.Failure) {
            return PipelineRound.failure(
                roundId = roundId,
                audioId = fixture.audioId,
                asrLatencyMs = asrFinishedAt - asrStartedAt,
                errorType = synthesis.error.code,
                nluLatencyMs = nluFinishedAt - nluStartedAt,
                cer = cer,
            )
        }
        val speech = (synthesis as AppResult.Success).value
        val played = output.play(speech)
        val playback = playbackMetrics.latestPlaybackMetrics.value
        val playbackStartedAt = playback?.playbackStartedAtElapsedRealtimeMs
        if (played is AppResult.Failure || playbackStartedAt == null) {
            return PipelineRound.failure(
                roundId = roundId,
                audioId = fixture.audioId,
                asrLatencyMs = asrFinishedAt - asrStartedAt,
                errorType = (played as? AppResult.Failure)?.error?.code ?: "PLAYBACK_TIMESTAMP_MISSING",
                nluLatencyMs = nluFinishedAt - nluStartedAt,
                ttsSynthesisMs = synthesisFinishedAt - ttsStartedAt,
                ttsAudioDurationMs = speech.durationMillis,
                cer = cer,
            )
        }

        return PipelineRound(
            roundId = roundId,
            audioId = fixture.audioId,
            asrLatencyMs = asrFinishedAt - asrStartedAt,
            audioDurationMs = fixture.audio.durationMillis,
            asrRtf = (asrFinishedAt - asrStartedAt).toDouble() / fixture.audio.durationMillis,
            cer = cer,
            nluLatencyMs = nluFinishedAt - nluStartedAt,
            ttsSynthesisMs = synthesisFinishedAt - ttsStartedAt,
            ttsAudioDurationMs = speech.durationMillis,
            ttsTimeToFirstPlaybackMs = playbackStartedAt - ttsStartedAt,
            audioReadyToFirstPlaybackMs = playbackStartedAt - asrStartedAt,
            success = true,
        )
    }

    private suspend fun requireSuccess(stage: String, block: suspend () -> AppResult<Unit>) {
        when (val result = block()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> error("$stage failed: ${result.error.code} ${result.error.userMessage}")
        }
    }

    private suspend fun measureMillis(block: suspend () -> Unit): Long {
        val startedAt = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - startedAt
    }

    private suspend fun buildSummary(
        entryPoint: DebugTestEntryPoint,
        fixture: WavFixture,
        runCount: Int,
        warmupCount: Int,
        asrInitMs: Long,
        ttsInitMs: Long,
        rounds: List<PipelineRound>,
        sampledResources: List<ProcessResourceSample>,
        startedEnvironment: JSONObject,
        completedEnvironment: JSONObject,
    ): JSONObject {
        val models = entryPoint.modelManager()
        val asrModel = models.activeModel(ModelType.ASR)
        val ttsModel = models.activeModel(ModelType.TTS)
        val successfulRounds = rounds.filter { it.success }
        return JSONObject()
            .put("testType", "REAL_PIPELINE_CONTROLLED_WAV")
            .put("evidence", "Real Vosk and sherpa-onnx on a supplied 16 kHz mono PCM WAV fixture; not microphone, streaming ASR, or physical speaker-audibility measurement.")
            .put("fixture", JSONObject().put("audioId", fixture.audioId).put("audioDurationMs", fixture.audio.durationMillis))
            .put("configuration", JSONObject().put("runCount", runCount).put("warmupCount", warmupCount))
            .put("models", JSONObject()
                .put("asr", modelJson(asrModel?.displayName, asrModel?.version, asrModel?.runtime))
                .put("tts", modelJson(ttsModel?.displayName, ttsModel?.version, ttsModel?.runtime)),
            )
            .put("initialization", JSONObject().put("asrMs", asrInitMs).put("ttsMs", ttsInitMs))
            .put("asrLatency", latencyJson(successfulRounds.map { it.asrLatencyMs }))
            .put("asrRtf", numericJson(successfulRounds.mapNotNull { it.asrRtf }))
            .put("cer", numericJson(successfulRounds.mapNotNull { it.cer }))
            .put("nluLatency", latencyJson(successfulRounds.mapNotNull { it.nluLatencyMs }))
            .put("ttsSynthesis", latencyJson(successfulRounds.mapNotNull { it.ttsSynthesisMs }))
            .put("ttsTimeToFirstPlayback", latencyJson(successfulRounds.mapNotNull { it.ttsTimeToFirstPlaybackMs }))
            .put("audioReadyToFirstPlayback", latencyJson(successfulRounds.mapNotNull { it.audioReadyToFirstPlaybackMs }))
            .put("sessionSuccessRate", if (rounds.isEmpty()) JSONObject.NULL else successfulRounds.size.toDouble() / rounds.size)
            .put("errors", JSONObject().apply {
                rounds.filterNot { it.success }.groupingBy { it.errorType ?: "UNKNOWN" }.eachCount().forEach { (code, count) -> put(code, count) }
            })
            .put("resources", resourceJson(rounds, sampledResources))
            .put("environmentAtStart", startedEnvironment)
            .put("environmentAtCompletion", completedEnvironment)
    }

    private fun modelJson(name: String?, version: String?, runtime: String?): JSONObject = JSONObject()
        .put("displayName", name)
        .put("version", version)
        .put("runtime", runtime)

    private fun latencyJson(values: List<Long>): JSONObject {
        val summary = PerformanceStatistics.summary(values)
        return JSONObject()
            .put("sampleCount", summary.sampleCount)
            .put("minMs", summary.minMillis)
            .put("medianMs", summary.medianMillis)
            .put("p90Ms", summary.p90Millis)
            .put("p95Ms", summary.p95Millis)
            .put("maxMs", summary.maxMillis)
            .put("percentileMethod", "nearest_rank")
    }

    private fun numericJson(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        return JSONObject()
            .put("sampleCount", values.size)
            .put("mean", values.takeIf { it.isNotEmpty() }?.average())
            .put("min", sorted.firstOrNull())
            .put("max", sorted.lastOrNull())
    }

    private fun resourceJson(rounds: List<PipelineRound>, samples: List<ProcessResourceSample>): JSONObject {
        val cpuValues = samples.mapNotNull { it.cpuPercent }
        val roundPss = rounds.mapNotNull { it.totalPssKb }
        val pssGrowth = if (roundPss.size < 2) null else (roundPss.last() - roundPss.first()).toDouble() / (roundPss.size - 1)
        return JSONObject()
            .put("continuousSampleCount", samples.size)
            .put("peakPssKb", samples.maxOfOrNull { it.totalPssKb })
            .put("peakNativePssKb", samples.maxOfOrNull { it.nativePssKb })
            .put("peakJavaHeapBytes", samples.maxOfOrNull { it.javaHeapBytes })
            .put("peakNativeHeapBytes", samples.maxOfOrNull { it.nativeHeapBytes })
            .put("peakThreadCount", samples.mapNotNull { it.threadCount }.maxOrNull())
            .put("peakOpenFileDescriptorCount", samples.mapNotNull { it.openFileDescriptorCount }.maxOrNull())
            .put("cpuPercent", numericJson(cpuValues))
            .put("pssGrowthPerRoundKb", pssGrowth)
    }

    private fun List<PipelineRound>.toCsv(): String = buildString {
        appendLine("round_id,audio_id,asr_latency_ms,audio_duration_ms,asr_rtf,cer,nlu_latency_ms,tts_synthesis_ms,tts_audio_duration_ms,tts_time_to_first_playback_ms,audio_ready_to_first_playback_ms,total_pss_kb,native_pss_kb,java_heap_bytes,native_heap_bytes,cpu_percent,thermal_status,thread_count,open_file_descriptors,success,error_type")
        this@toCsv.forEach { row ->
            appendLine(listOf(
                row.roundId, row.audioId, row.asrLatencyMs, row.audioDurationMs, row.asrRtf, row.cer,
                row.nluLatencyMs, row.ttsSynthesisMs, row.ttsAudioDurationMs, row.ttsTimeToFirstPlaybackMs,
                row.audioReadyToFirstPlaybackMs, row.totalPssKb, row.nativePssKb, row.javaHeapBytes,
                row.nativeHeapBytes, row.cpuPercent, row.thermalStatus, row.threadCount,
                row.openFileDescriptorCount, row.success, row.errorType,
            ).joinToString(",") { PerformanceReportWriter.csvCell(it) })
        }
    }

    private data class PipelineRound(
        val roundId: Int,
        val audioId: String,
        val asrLatencyMs: Long,
        val audioDurationMs: Long? = null,
        val asrRtf: Double? = null,
        val cer: Double? = null,
        val nluLatencyMs: Long? = null,
        val ttsSynthesisMs: Long? = null,
        val ttsAudioDurationMs: Long? = null,
        val ttsTimeToFirstPlaybackMs: Long? = null,
        val audioReadyToFirstPlaybackMs: Long? = null,
        val totalPssKb: Int? = null,
        val nativePssKb: Int? = null,
        val javaHeapBytes: Long? = null,
        val nativeHeapBytes: Long? = null,
        val cpuPercent: Double? = null,
        val thermalStatus: Int? = null,
        val threadCount: Int? = null,
        val openFileDescriptorCount: Int? = null,
        val success: Boolean,
        val errorType: String? = null,
    ) {
        companion object {
            fun failure(
                roundId: Int,
                audioId: String,
                asrLatencyMs: Long,
                errorType: String,
                nluLatencyMs: Long? = null,
                ttsSynthesisMs: Long? = null,
                ttsAudioDurationMs: Long? = null,
                cer: Double? = null,
            ) = PipelineRound(
                roundId = roundId,
                audioId = audioId,
                asrLatencyMs = asrLatencyMs,
                nluLatencyMs = nluLatencyMs,
                ttsSynthesisMs = ttsSynthesisMs,
                ttsAudioDurationMs = ttsAudioDurationMs,
                cer = cer,
                success = false,
                errorType = errorType,
            )
        }
    }

    private companion object {
        const val ARG_AUDIO_PATH = "performanceAudioPath"
        const val ARG_AUDIO_ID = "performanceAudioId"
        const val ARG_REFERENCE_TEXT = "performanceReferenceText"
        const val ARG_RUN_COUNT = "performanceRunCount"
        const val ARG_WARMUP_COUNT = "performanceWarmupCount"
        const val DEFAULT_RUN_COUNT = 20
        const val DEFAULT_WARMUP_COUNT = 1
        const val MAX_RUN_COUNT = 50
        const val MAX_WARMUP_COUNT = 5
        const val RESOURCE_SAMPLE_INTERVAL_MS = 500L
        const val STATUS_CODE = 2
    }
}

private fun android.os.Bundle.positiveInt(key: String, defaultValue: Int, maximum: Int): Int =
    getString(key)?.toIntOrNull()?.coerceIn(1, maximum) ?: defaultValue

private fun android.os.Bundle.nonNegativeInt(key: String, defaultValue: Int, maximum: Int): Int =
    getString(key)?.toIntOrNull()?.coerceIn(0, maximum) ?: defaultValue

private fun characterErrorRate(reference: String, hypothesis: String): Double? {
    val expected = normalizeForCer(reference)
    val actual = normalizeForCer(hypothesis)
    if (expected.isEmpty()) return null
    var previous = IntArray(actual.size + 1) { it }
    var current = IntArray(actual.size + 1)
    expected.forEachIndexed { row, expectedCharacter ->
        current[0] = row + 1
        actual.forEachIndexed { column, actualCharacter ->
            val substitution = previous[column] + if (expectedCharacter == actualCharacter) 0 else 1
            current[column + 1] = minOf(
                previous[column + 1] + 1,
                current[column] + 1,
                substitution,
            )
        }
        val temporary = previous
        previous = current
        current = temporary
    }
    return previous[actual.size].toDouble() / expected.size
}

private fun normalizeForCer(value: String): CharArray = value
    .asSequence()
    .filterNot { it.isWhitespace() || Character.getType(it).let { type -> type in PUNCTUATION_TYPES } }
    .map { it.lowercaseChar() }
    .toList()
    .toCharArray()

private val PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
)
