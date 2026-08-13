package com.example.calldelegate.testing.wav

import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.SceneConfidenceState
import com.example.calldelegate.domain.model.TranscriptTurn
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WavCallMetricsTest {
    @Test
    fun assistantFailureReplyCompletesTheWaitEvenWithoutCallerTranscript() {
        val transcript = listOf(
            TranscriptTurn(Speaker.ASSISTANT, "opening", 1L),
            TranscriptTurn(Speaker.ASSISTANT, "没有听清", 2L),
        )

        assertThat(hasAssistantResponseAfterOpening(transcript, openingTranscriptSize = 1)).isTrue()
    }

    @Test
    fun canonicalSceneIdAcceptsStableIdAndEnumName() {
        assertThat(WavCallMetrics.canonicalSceneId("delivery")).isEqualTo("delivery")
        assertThat(WavCallMetrics.canonicalSceneId("DELIVERY")).isEqualTo("delivery")
        assertThat(WavCallMetrics.canonicalSceneId("not_a_scene")).isNull()
    }

    @Test
    fun cerUsesNfkcWhitespacePunctuationAndCodePoints() {
        val equivalent = WavCallMetrics.evaluateCer("\uFF21\uFF22\u3000C!", "ab c")
        val emoji = WavCallMetrics.evaluateCer("\uD83D\uDE03", "\uD83D\uDE04")

        assertThat(equivalent.recognizedNormalized).isEqualTo("abc")
        assertThat(equivalent.referenceNormalized).isEqualTo("abc")
        assertThat(equivalent.editDistance).isEqualTo(0)
        assertThat(emoji.editDistance).isEqualTo(1)
        assertThat(emoji.referenceLength).isEqualTo(1)
    }

    @Test
    fun cerNormalizesHonorificPronoun() {
        val evaluation = WavCallMetrics.evaluateCer("您好，请确认您的位置", "你好请确认你的位置")

        assertThat(evaluation.editDistance).isEqualTo(0)
        assertThat(evaluation.recognizedNormalized).isEqualTo("你好请确认你的位置")
    }

    @Test
    fun entityEvaluationCountsFalsePositivesWhenExpectationIsEmpty() {
        val evaluation = WavCallMetrics.evaluateEntities(
            expectedEntities = emptyMap(),
            actualEntities = mapOf("location" to " North Gate "),
        )

        assertThat(evaluation.truePositive).isEqualTo(0)
        assertThat(evaluation.falsePositive).isEqualTo(1)
        assertThat(evaluation.falseNegative).isEqualTo(0)
        assertThat(evaluation.precision).isEqualTo(0.0)
        assertThat(evaluation.recall).isNull()
        assertThat(evaluation.f1).isEqualTo(0.0)
        assertThat(evaluation.strictMatched).isFalse()
        assertThat(evaluation.requiredIncluded).isNull()
    }

    @Test
    fun entityEvaluationSeparatesStrictAndRequiredEntityResults() {
        val evaluation = WavCallMetrics.evaluateEntities(
            expectedEntities = mapOf("location" to "公司前台"),
            actualEntities = mapOf("location" to "公司前台", "issueType" to "延迟"),
        )

        assertThat(evaluation.strictMatched).isFalse()
        assertThat(evaluation.requiredIncluded).isTrue()
        assertThat(evaluation.truePositive).isEqualTo(1)
        assertThat(evaluation.falsePositive).isEqualTo(1)
    }

    @Test
    fun hotwordEvaluationNormalizesTextAndDeduplicatesExpectations() {
        val evaluation = WavCallMetrics.evaluateHotwords(
            expectedHotwords = listOf("门口置物架", "门口置物架", "外卖骑手"),
            recognizedText = "外 卖 骑 手把餐放在门口置物架。",
        )

        assertThat(evaluation.expectedCount).isEqualTo(2)
        assertThat(evaluation.matchedCount).isEqualTo(2)
        assertThat(evaluation.accuracy).isEqualTo(1.0)
    }

    @Test
    fun locationEvaluationReportsExactNormalizedAndCoreResultsSeparately() {
        val exact = WavCallMetrics.evaluateLocation("云杉广场卸货区入口旁边", "云杉广场卸货区入口旁边")
        val core = WavCallMetrics.evaluateLocation("云杉广场地下二层卸货区入口旁边", "卸货区入口旁边")
        val hierarchy = WavCallMetrics.evaluateLocation(
            "研发楼二层茶水间外面的矮柜上",
            "研发楼二层茶水间外面",
        )

        assertThat(exact.exactMatched).isTrue()
        assertThat(exact.normalizedMatched).isTrue()
        assertThat(exact.coreIncluded).isTrue()
        assertThat(exact.hierarchyMatched).isTrue()
        assertThat(core.exactMatched).isFalse()
        assertThat(core.normalizedMatched).isFalse()
        assertThat(core.coreIncluded).isTrue()
        assertThat(core.hierarchyMatched).isFalse()
        assertThat(hierarchy.hierarchyMatched).isTrue()
    }

    @Test
    fun summaryUsesTotalsAndNeverProducesNanForZeroDenominators() {
        val samples = listOf(
            sample(computeMs = 10L, originalMs = 20L, inputMs = 20L, editDistance = 1, referenceLength = 2),
            sample(computeMs = 30L, originalMs = 60L, inputMs = 60L, editDistance = 2, referenceLength = 4),
        )

        val summary = WavCallMetrics.summarize(samples).single()

        assertThat(summary.globalCer).isEqualTo(0.5)
        assertThat(summary.globalSourceRtf).isEqualTo(0.5)
        assertThat(summary.globalAsrRtf).isEqualTo(0.5)
        assertThat(WavCallMetrics.safeRatio(0L, 0L)).isNull()
    }

    @Test
    fun summaryKeepsSegmentationAuditAndProductionLatencySeparate() {
        val audit = sample(10L, 20L, 20L, 0, 2).copy(
            measurementMode = WavCallMeasurementMode.SEGMENTATION_AUDIT.name,
        )
        val production = audit.copy(
            caseId = "production",
            measurementMode = WavCallMeasurementMode.PRODUCTION_LATENCY.name,
        )

        val summaries = WavCallMetrics.summarize(listOf(audit, production))

        assertThat(summaries).hasSize(2)
        assertThat(summaries.map { it.measurementMode }).containsExactly(
            WavCallMeasurementMode.SEGMENTATION_AUDIT.name,
            WavCallMeasurementMode.PRODUCTION_LATENCY.name,
        )
    }

    @Test
    fun summaryIncludesMissedClassesInMacroF1AndBuildsConfusionMatrix() {
        val samples = listOf(
            sample(10L, 20L, 20L, 0, 2).copy(
                expectedScene = "DELIVERY",
                actualScene = "delivery",
                sceneMatched = true,
            ),
            sample(20L, 20L, 20L, 0, 2).copy(
                caseId = "ride-missed",
                expectedScene = "ride_hailing",
                actualScene = "delivery",
                sceneMatched = false,
            ),
        )

        val summary = WavCallMetrics.summarize(samples).single()
        val delivery = summary.perSceneMetrics.single { it.scene == "delivery" }
        val ride = summary.perSceneMetrics.single { it.scene == "ride_hailing" }

        assertThat(delivery.f1).isWithin(0.0001).of(2.0 / 3.0)
        assertThat(ride.f1).isEqualTo(0.0)
        assertThat(summary.macroF1).isWithin(0.0001).of(1.0 / 3.0)
        assertThat(summary.confusionMatrix["ride_hailing"]?.get("delivery")).isEqualTo(1)
    }

    @Test
    fun summarySeparatesCandidateAndConfirmedSceneAccuracy() {
        val provisional = sample(10L, 20L, 20L, 0, 2).copy(
            expectedScene = "DELIVERY",
            actualScene = "delivery",
            sceneConfidenceState = SceneConfidenceState.PROVISIONAL.name,
            sceneMatched = true,
        )
        val confirmed = provisional.copy(
            caseId = "confirmed",
            sceneConfidenceState = SceneConfidenceState.CONFIRMED.name,
        )

        val summary = WavCallMetrics.summarize(listOf(provisional, confirmed)).single()

        assertThat(summary.sceneCandidateAccuracy).isEqualTo(1.0)
        assertThat(summary.sceneCandidateMatchedCases).isEqualTo(2)
        assertThat(summary.sceneConfirmedAccuracy).isEqualTo(0.5)
        assertThat(summary.sceneConfirmedMatchedCases).isEqualTo(1)
        assertThat(summary.sceneConfirmedCases).isEqualTo(1)
    }

    @Test
    fun summarySeparatesSinglePassAndSecondaryRecognitionPerformance() {
        val singlePass = sample(100L, 200L, 200L, 0, 2).copy(
            responseLatencyMs = 300L,
            asrPostEndpointLatencyMs = 120L,
            nluDurationMs = 10L,
            ttsSynthesisDurationMs = 90L,
            ttsToFirstAudioWriteLatencyMs = 5L,
            peakProcessPssKb = 400_000,
            recognitionAttemptCount = 1,
            selectedRecognitionAttemptIndex = 1,
        )
        val secondary = sample(180L, 200L, 400L, 0, 2).copy(
            caseId = "secondary",
            responseLatencyMs = 420L,
            asrPostEndpointLatencyMs = 240L,
            nluDurationMs = 20L,
            ttsSynthesisDurationMs = 140L,
            ttsToFirstAudioWriteLatencyMs = 8L,
            peakProcessPssKb = 430_000,
            recognitionAttemptCount = 2,
            selectedRecognitionAttemptIndex = 1,
            secondaryRecognitionTriggered = true,
            secondaryRecognitionComputeDurationMs = 80L,
            secondaryEvidenceUsed = true,
            secondaryScenePromoted = true,
            secondaryAcceptedEntityKeys = listOf("location"),
            secondaryTextReplaced = false,
        )

        val summary = WavCallMetrics.summarize(listOf(singlePass, secondary)).single()

        assertThat(summary.singlePassCases).isEqualTo(1)
        assertThat(summary.singlePassAsrLatencyP50Ms).isEqualTo(100L)
        assertThat(summary.singlePassResponseLatencyP95Ms).isEqualTo(300L)
        assertThat(summary.singlePassPeakPssKb).isEqualTo(400_000)
        assertThat(summary.secondaryRecognitionAdoptedCases).isEqualTo(0)
        assertThat(summary.secondaryEvidenceUsedCases).isEqualTo(1)
        assertThat(summary.secondaryScenePromotedCases).isEqualTo(1)
        assertThat(summary.secondaryEntitySupplementedCases).isEqualTo(1)
        assertThat(summary.secondaryTextReplacementCases).isEqualTo(0)
        assertThat(summary.secondaryPathAsrLatencyP50Ms).isEqualTo(180L)
        assertThat(summary.secondaryPathIncrementLatencyP95Ms).isEqualTo(80L)
        assertThat(summary.secondaryPathResponseLatencyP50Ms).isEqualTo(420L)
        assertThat(summary.secondaryPathPeakPssKb).isEqualTo(430_000)
        assertThat(summary.asrComputeDurationP50Ms).isEqualTo(100L)
        assertThat(summary.asrComputeDurationP90Ms).isEqualTo(180L)
        assertThat(summary.asrPostEndpointLatencyP50Ms).isEqualTo(120L)
        assertThat(summary.asrPostEndpointLatencyP90Ms).isEqualTo(240L)
        assertThat(summary.nluDurationP90Ms).isEqualTo(20L)
        assertThat(summary.ttsSynthesisDurationP90Ms).isEqualTo(140L)
        assertThat(summary.ttsToFirstAudioWriteLatencyP90Ms).isEqualTo(8L)
        assertThat(summary.responseLatencyP50Ms).isEqualTo(300L)
        assertThat(summary.responseLatencyP90Ms).isEqualTo(420L)
        assertThat(summary.validResponseLatencySampleCount).isEqualTo(2)
        assertThat(summary.responseLatencyMaxMs).isEqualTo(420L)
    }

    @Test
    fun realTimeReferenceBoundaryUsesTheFrameSchedule() {
        val referenceAt = estimateRealTimeBoundaryNanos(
            injectedAtNanos = 10_000_000_000L,
            frameTimestampMs = 200L,
            boundaryMs = 5_000L,
        )

        assertThat(referenceAt).isEqualTo(14_800_000_000L)
    }

    @Test
    fun summarySeparatesFirstTurnContinuationAndDeliveryIntentMetrics() {
        val firstTurn = sample(10L, 20L, 20L, 0, 2).copy(
            expectedScene = "DELIVERY",
            actualScene = "unclassified",
            sceneMatched = false,
        )
        val continuation = sample(10L, 20L, 20L, 0, 2).copy(
            caseId = "continuation",
            initialScene = "delivery",
            expectedScene = "DELIVERY",
            actualScene = "delivery",
            sceneMatched = true,
            expectedDeliveryIntent = "arrived",
            actualDeliveryIntent = "arrived",
            deliveryIntentMatched = true,
        )

        val summary = WavCallMetrics.summarize(listOf(firstTurn, continuation)).single()

        assertThat(summary.firstTurnSceneAccuracy).isEqualTo(0.0)
        assertThat(summary.continuationSceneRetentionRate).isEqualTo(1.0)
        assertThat(summary.deliveryIntentAccuracy).isEqualTo(1.0)
    }

    @Test
    fun summaryReportsCandidateRollbackAndPartialFinalDecisionMetrics() {
        val first = sample(10L, 20L, 20L, 0, 2).copy(
            candidateEndpointRollbackCount = 1,
            speechResumedAfterCandidateMs = listOf(20L),
            partialFinalExactMatched = false,
            partialFinalNormalizedMatched = true,
            partialFinalCharacterDifference = 0,
        )
        val second = sample(10L, 20L, 20L, 0, 2).copy(
            caseId = "second",
            candidateEndpointRollbackCount = 2,
            speechResumedAfterCandidateMs = listOf(80L, 400L),
            partialFinalExactMatched = false,
            partialFinalNormalizedMatched = false,
            partialFinalCharacterDifference = 3,
        )

        val summary = WavCallMetrics.summarize(listOf(first, second)).single()

        assertThat(summary.candidateEndpointRollbackCount).isEqualTo(3)
        assertThat(summary.speechResumedAfterCandidateP50Ms).isEqualTo(80L)
        assertThat(summary.speechResumedAfterCandidateP95Ms).isEqualTo(400L)
        assertThat(summary.speechResumedAfterCandidateMaxMs).isEqualTo(400L)
        assertThat(summary.partialFinalEvaluatedCases).isEqualTo(2)
        assertThat(summary.partialFinalExactMatchRate).isEqualTo(0.0)
        assertThat(summary.partialFinalNormalizedMatchRate).isEqualTo(0.5)
        assertThat(summary.partialFinalCharacterDifference).isEqualTo(3L)
    }

    private fun sample(
        computeMs: Long,
        originalMs: Long,
        inputMs: Long,
        editDistance: Int,
        referenceLength: Int,
    ) = WavCallSampleResult(
        caseId = "case-$computeMs",
        wavFile = "case-$computeMs.wav",
        injectionMode = "AS_FAST_AS_POSSIBLE",
        acceleratedInput = true,
        status = WavCallCaseStatus.PASSED,
        originalAudioDurationMs = originalMs,
        voskComputeDurationMs = computeMs,
        voskInputAudioDurationMs = inputMs,
        cerEditDistance = editDistance,
        cerReferenceLength = referenceLength,
        entityTp = 0,
        entityFp = 0,
        entityFn = 0,
    )
}
