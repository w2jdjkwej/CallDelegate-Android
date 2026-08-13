package com.example.calldelegate.testing.wav

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files

class WavCallResultExporterTest {

    @Test
    fun writesUtf8SummaryCsvAndFailuresWithoutNonFiniteValues() {
        val exporter = WavCallResultExporter()
        val (runId, directory) = exporter.createRunDirectory(Files.createTempDirectory("wav-results").toFile())
        val sample = WavCallSampleResult(
            caseId = "case_001",
            wavFile = "audio.wav",
            injectionMode = "AS_FAST_AS_POSSIBLE",
            acceleratedInput = true,
            status = WavCallCaseStatus.FAILED,
            failureCode = "ASR_EMPTY_RESULT",
            recognizedTextRaw = "a,\"b\"",
            vadLastSpeechMs = 900L,
            speechEndToCommitAudioMs = 500L,
            speechEndToCommitWallClockMs = 530L,
            asrTrailingSilenceSkippedMs = 150L,
            vadFrameProcessingLagMs = 4L,
            endpointDetectionQuantizationMs = 20L,
            candidateEndpointRollbackCount = 1,
            speechResumedAfterCandidateMs = listOf(80L),
            candidateRecognizerIds = listOf("recognizer-one"),
            partialFinalNormalizedMatched = true,
            referenceEndToAsrCompleteMs = 120L,
            recognizerCreateMs = 30L,
            voskAcceptComputeMs = 45L,
            voskQueueDepth = 2,
            voskQueueWaitMs = 8L,
            voskQueueWaitMaxMs = 5L,
            voskDrainMs = 10L,
            voskFinalResultMs = 6L,
            sourceRtf = null,
            asrRtf = null,
            generatedReply = "已记录",
            replyTemplateId = "test.reply",
            replySafe = true,
            replyCharCount = 3,
            replyAudioDurationMs = 120L,
        )
        val summary = WavCallRunSummary(
            runId = runId,
            status = WavCallRunStatus.COMPLETED,
            startedAtElapsedRealtimeNanos = 1L,
            completedAtElapsedRealtimeNanos = 2L,
            manifestVersion = "test",
            injectionMode = "AS_FAST_AS_POSSIBLE",
            acceleratedInput = true,
            environment = environment(),
            modeSummaries = WavCallMetrics.summarize(listOf(sample)),
            completedCaseCount = 1,
            failureCaseCount = 1,
            cancellationRequested = false,
        )

        exporter.write(directory, summary, listOf(sample))

        val csv = java.io.File(directory, "samples.csv").readText(Charsets.UTF_8)
        assertThat(java.io.File(directory, "summary.json").isFile).isTrue()
        assertThat(java.io.File(directory, "samples.json").isFile).isTrue()
        assertThat(java.io.File(directory, "failures.json").isFile).isTrue()
        assertThat(csv).contains("asrPostEndpointLatencyMs")
        assertThat(csv).contains("ttsSynthesisDurationMs")
        assertThat(csv).contains("measurementMode")
        assertThat(csv).contains("speechEndReferenceAtElapsedRealtimeNanos")
        assertThat(csv).contains("responseLatencyReference")
        assertThat(csv).contains("speechEndToCommitAudioMs")
        assertThat(csv).contains("speechEndToCommitWallClockMs")
        assertThat(csv).contains("asrTrailingSilenceSkippedMs")
        assertThat(csv).contains("endpointDetectionQuantizationMs")
        assertThat(csv).contains("candidateEndpointRollbackCount")
        assertThat(csv).contains("speechResumedAfterCandidateMs")
        assertThat(csv).contains("candidateRecognizerIds")
        assertThat(csv).contains("partialFinalNormalizedMatched")
        assertThat(csv).contains("vadFrameProcessingLagMs")
        assertThat(csv).contains("recognizerCreateMs")
        assertThat(csv).contains("voskAcceptComputeMs")
        assertThat(csv).contains("voskQueueDepth")
        assertThat(csv).contains("voskQueueWaitMs")
        assertThat(csv).contains("voskDrainMs")
        assertThat(csv).contains("voskFinalResultMs")
        assertThat(csv).contains("referenceEndToAsrCompleteMs")
        assertThat(csv).contains("generatedReply")
        assertThat(csv).contains("replyTemplateId")
        assertThat(csv).contains("replySafe")
        assertThat(csv).contains("replyAudioDurationMs")
        assertThat(csv).contains("a,\"\"b\"\"")
        assertThat(csv).doesNotContain("NaN")
        assertThat(csv).doesNotContain("Infinity")
    }

    @Test
    fun runGateRejectsASecondActiveTaskWithoutQueueing() = runTest {
        val gate = WavCallRunGate()

        assertThat(gate.tryAcquire()).isTrue()
        assertThat(gate.tryAcquire()).isFalse()
        gate.release()
        assertThat(gate.tryAcquire()).isTrue()
    }

    private fun environment() = WavCallRunEnvironment(
        applicationVersion = null,
        buildType = "debug",
        gitCommit = null,
        baselineReference = null,
        deviceModel = null,
        androidVersion = null,
        soc = null,
        supportedAbis = emptyList(),
        totalRamBytes = null,
        asrModelName = null,
        asrModelVersion = null,
        asrModelPath = null,
        asrRuntime = null,
        ttsModelName = null,
        ttsModelVersion = null,
        ttsModelPath = null,
        ttsRuntime = null,
        ttsVoice = null,
        ttsLocale = null,
        inferenceThreadCount = null,
        wavTurnDurationLimitMillis = 8_000L,
        wavTurnDurationLimitDisabled = false,
        vadImplementation = null,
        vadRmsThreshold = null,
        vadEndSilenceFrames = null,
        vadInitialSilenceFrames = null,
        vadFrameSamples = 320,
        tailSilenceMs = 800L,
        vadEndSilenceMs = 600L,
        vadInitialSilenceMs = 8_000L,
        vadSubframeDurationMs = 20L,
    )
}
