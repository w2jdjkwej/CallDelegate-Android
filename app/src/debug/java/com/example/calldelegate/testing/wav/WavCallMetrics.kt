package com.example.calldelegate.testing.wav

import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SceneConfidenceState
import java.text.Normalizer
import java.util.Locale

data class CerEvaluation(
    val recognizedNormalized: String?,
    val referenceNormalized: String?,
    val editDistance: Int?,
    val referenceLength: Int?,
    val cer: Double?,
)

data class EntityEvaluation(
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
    val precision: Double?,
    val recall: Double?,
    val f1: Double?,
    val strictMatched: Boolean,
    val requiredIncluded: Boolean?,
    val evaluated: Boolean = true,
) {
    companion object {
        fun notEvaluated(): EntityEvaluation = EntityEvaluation(
            truePositive = 0,
            falsePositive = 0,
            falseNegative = 0,
            precision = null,
            recall = null,
            f1 = null,
            strictMatched = false,
            requiredIncluded = null,
            evaluated = false,
        )
    }
}

data class HotwordEvaluation(
    val expectedCount: Int,
    val matchedCount: Int,
    val accuracy: Double?,
)

data class LocationEvaluation(
    val exactMatched: Boolean?,
    val normalizedMatched: Boolean?,
    val coreIncluded: Boolean?,
    val hierarchyMatched: Boolean?,
)

object WavCallMetrics {
    /**
     * Returns the project's stable scene ID for either a stable ID (for example, "delivery")
     * or an enum name used by the manifest example (for example, "DELIVERY").
     */
    fun canonicalSceneId(value: String?): String? {
        val suppliedValue = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return SceneType.entries.firstOrNull { scene ->
            scene.id == suppliedValue || scene.name == suppliedValue
        }?.id
    }

    fun evaluateCer(recognizedText: String?, referenceText: String?): CerEvaluation {
        val recognized = recognizedText?.let(::normalizeCerText)
        val reference = referenceText?.let(::normalizeCerText)
        if (reference.isNullOrEmpty()) {
            return CerEvaluation(recognized, reference, null, null, null)
        }
        val distance = levenshteinByCodePoint(recognized.orEmpty(), reference)
        val referenceLength = reference.codePointCount(0, reference.length)
        return CerEvaluation(
            recognizedNormalized = recognized,
            referenceNormalized = reference,
            editDistance = distance,
            referenceLength = referenceLength,
            cer = safeRatio(distance.toLong(), referenceLength.toLong()),
        )
    }

    fun evaluateEntities(
        expectedEntities: Map<String, String>,
        actualEntities: Map<String, String>,
    ): EntityEvaluation {
        val expected = expectedEntities.normalizedEntityPairs()
        val actual = actualEntities.normalizedEntityPairs()
        val truePositive = expected.intersect(actual).size
        val falsePositive = actual.minus(expected).size
        val falseNegative = expected.minus(actual).size
        val precision = safeRatio(truePositive.toLong(), (truePositive + falsePositive).toLong())
        val recall = safeRatio(truePositive.toLong(), (truePositive + falseNegative).toLong())
        val f1 = calculateF1(truePositive, falsePositive, falseNegative, precision, recall)
        return EntityEvaluation(
            truePositive,
            falsePositive,
            falseNegative,
            precision,
            recall,
            f1,
            strictMatched = expected == actual,
            requiredIncluded = if (expected.isEmpty()) null else actual.containsAll(expected),
        )
    }

    fun evaluateHotwords(expectedHotwords: List<String>, recognizedText: String?): HotwordEvaluation {
        if (expectedHotwords.isEmpty()) return HotwordEvaluation(0, 0, null)
        val normalizedText = normalizeCerText(recognizedText.orEmpty())
        val normalizedExpected = expectedHotwords.map(::normalizeCerText).filter(String::isNotEmpty).distinct()
        val matched = normalizedExpected.count(normalizedText::contains)
        return HotwordEvaluation(
            expectedCount = normalizedExpected.size,
            matchedCount = matched,
            accuracy = safeRatio(matched.toLong(), normalizedExpected.size.toLong()),
        )
    }

    fun evaluateLocation(expectedLocation: String?, actualLocation: String?): LocationEvaluation {
        if (expectedLocation == null) return LocationEvaluation(null, null, null, null)
        if (actualLocation == null) return LocationEvaluation(false, false, false, false)

        val exactMatched = expectedLocation.trim() == actualLocation.trim()
        val expectedNormalized = normalizeEntityValue(expectedLocation)
        val actualNormalized = normalizeEntityValue(actualLocation)
        val normalizedMatched = expectedNormalized == actualNormalized
        val expectedCanonical = normalizeCerText(expectedNormalized)
        val actualCanonical = normalizeCerText(actualNormalized)
        val shorterLength = minOf(
            expectedCanonical.codePointCount(0, expectedCanonical.length),
            actualCanonical.codePointCount(0, actualCanonical.length),
        )
        val hierarchyIncluded = shorterLength >= 3 &&
            (expectedCanonical.contains(actualCanonical) || actualCanonical.contains(expectedCanonical))
        val expectedFacilityTerms = LOCATION_CORE_TERMS.filter(expectedCanonical::contains)
        val facilityIncluded = expectedFacilityTerms.any(actualCanonical::contains)
        val expectedHierarchyTerms = LOCATION_HIERARCHY_PATTERNS
            .flatMap { pattern -> pattern.findAll(expectedCanonical).map(MatchResult::value).toList() }
            .distinct()
        val hierarchyMatched = expectedHierarchyTerms.isNotEmpty() &&
            expectedHierarchyTerms.all(actualCanonical::contains)
        return LocationEvaluation(
            exactMatched = exactMatched,
            normalizedMatched = normalizedMatched,
            coreIncluded = normalizedMatched || hierarchyIncluded || facilityIncluded,
            hierarchyMatched = hierarchyMatched,
        )
    }

    fun normalizeCerText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('您', '你')
        val output = StringBuilder(normalized.length)
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (!codePoint.isUnicodeWhitespace() && !codePoint.isUnicodePunctuation()) {
                output.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        return output.toString()
    }

    fun normalizeEntityValue(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val output = StringBuilder(normalized.length)
        var offset = 0
        var pendingSpace = false
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (codePoint.isUnicodeWhitespace()) {
                if (output.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) output.append(' ')
                output.appendCodePoint(codePoint)
                pendingSpace = false
            }
            offset += Character.charCount(codePoint)
        }
        return output.toString()
    }

    fun characterDifference(left: String, right: String): Int =
        levenshteinByCodePoint(left, right)

    fun summarize(samples: List<WavCallSampleResult>): List<WavCallModeSummary> =
        samples.groupBy { it.measurementMode to it.injectionMode }.map { (mode, groupedSamples) ->
            val cerSamples = groupedSamples.filter { it.cerEditDistance != null && it.cerReferenceLength != null }
            val totalEditDistance = cerSamples.sumOf { it.cerEditDistance!!.toLong() }
            val totalReferenceLength = cerSamples.sumOf { it.cerReferenceLength!!.toLong() }
            val firstPassCerSamples = groupedSamples.filter {
                it.firstPassCerEditDistance != null && it.firstPassCerReferenceLength != null
            }
            val firstPassEditDistance = firstPassCerSamples.sumOf { it.firstPassCerEditDistance!!.toLong() }
            val firstPassReferenceLength = firstPassCerSamples.sumOf { it.firstPassCerReferenceLength!!.toLong() }
            val rtfSamples = groupedSamples.filter {
                it.voskComputeDurationMs != null &&
                    it.originalAudioDurationMs != null && it.originalAudioDurationMs > 0L &&
                    it.voskInputAudioDurationMs != null && it.voskInputAudioDurationMs > 0L
            }
            val totalComputeDuration = rtfSamples.sumOf { it.voskComputeDurationMs!! }
            val totalOriginalDuration = rtfSamples.sumOf { it.originalAudioDurationMs!! }
            val totalVoskInputDuration = rtfSamples.sumOf { it.voskInputAudioDurationMs!! }
            val sceneSamples = groupedSamples.filter { it.sceneMatched != null }
            val confirmedSceneSamples = sceneSamples.filter {
                it.sceneConfidenceState == SceneConfidenceState.CONFIRMED.name
            }
            val firstTurnSceneSamples = sceneSamples.filter { it.initialScene == null }
            val continuationSceneSamples = sceneSamples.filter { it.initialScene != null }
            val deliveryIntentSamples = groupedSamples.filter { it.deliveryIntentMatched != null }
            val intentSamples = groupedSamples.filter { it.intentMatched != null }
            val callNatureSamples = groupedSamples.filter { it.callNatureMatched != null }
            val riskSamples = groupedSamples.filter { it.riskLevelMatched != null }
            val entitySamples = groupedSamples.filter {
                it.entityTp != null && it.entityFp != null && it.entityFn != null
            }
            val entityTp = entitySamples.sumOf { it.entityTp!!.toLong() }
            val entityFp = entitySamples.sumOf { it.entityFp!!.toLong() }
            val entityFn = entitySamples.sumOf { it.entityFn!!.toLong() }
            val entityPrecision = safeRatio(entityTp, entityTp + entityFp)
            val entityRecall = safeRatio(entityTp, entityTp + entityFn)
            val entityF1 = calculateF1(entityTp, entityFp, entityFn, entityPrecision, entityRecall)
            val requiredEntitySamples = groupedSamples.filter { it.requiredEntitiesIncluded != null }
            val locationSamples = groupedSamples.filter { it.locationMatched != null }
            val locationExactSamples = groupedSamples.filter { it.locationExactMatched != null }
            val locationCoreSamples = groupedSamples.filter { it.locationCoreIncluded != null }
            val locationHierarchySamples = groupedSamples.filter { it.locationHierarchyMatched != null }
            val hotwordExpectedCount = groupedSamples.sumOf { it.expectedHotwords.distinct().size.toLong() }
            val hotwordMatchedCount = groupedSamples.sumOf { it.matchedHotwordCount.toLong() }
            val secondarySamples = groupedSamples.filter { it.secondaryRecognitionTriggered }
            val singlePassSamples = groupedSamples.filterNot { it.secondaryRecognitionTriggered }
            val sceneMetrics = calculateSceneMetrics(groupedSamples)
            val responseLatencies = groupedSamples.mapNotNull { it.responseLatencyMs }.sorted()
            val asrComputeDurations = groupedSamples.mapNotNull { it.voskComputeDurationMs }.sorted()
            val asrPostEndpointLatencies = groupedSamples.mapNotNull { it.asrPostEndpointLatencyMs }.sorted()
            val vadFrameProcessingLags = groupedSamples.mapNotNull { it.vadFrameProcessingLagMs }.sorted()
            val recognizerCreateDurations = groupedSamples.mapNotNull { it.recognizerCreateMs }.sorted()
            val voskAcceptComputeDurations = groupedSamples.mapNotNull { it.voskAcceptComputeMs }.sorted()
            val voskQueueDepths = groupedSamples.mapNotNull { it.voskQueueDepth }
            val voskQueueWaitMaxDurations = groupedSamples.mapNotNull { it.voskQueueWaitMaxMs }.sorted()
            val voskDrainDurations = groupedSamples.mapNotNull { it.voskDrainMs }.sorted()
            val voskFinalResultDurations = groupedSamples.mapNotNull { it.voskFinalResultMs }.sorted()
            val referenceEndToAsrCompleteDurations = groupedSamples
                .mapNotNull { it.referenceEndToAsrCompleteMs }
                .sorted()
            val nluDurations = groupedSamples.mapNotNull { it.nluDurationMs }.sorted()
            val ttsSynthesisDurations = groupedSamples.mapNotNull { it.ttsSynthesisDurationMs }.sorted()
            val ttsToFirstAudioWriteLatencies = groupedSamples
                .mapNotNull { it.ttsToFirstAudioWriteLatencyMs }
                .sorted()
            val singlePassAsrLatencies = singlePassSamples.mapNotNull { it.voskComputeDurationMs }.sorted()
            val singlePassResponseLatencies = singlePassSamples.mapNotNull { it.responseLatencyMs }.sorted()
            val secondaryAsrLatencies = secondarySamples.mapNotNull { it.voskComputeDurationMs }.sorted()
            val secondaryIncrementLatencies = secondarySamples.map { it.secondaryRecognitionComputeDurationMs }.sorted()
            val secondaryResponseLatencies = secondarySamples.mapNotNull { it.responseLatencyMs }.sorted()
            val speechResumeDelays = groupedSamples.flatMap { it.speechResumedAfterCandidateMs }.sorted()
            val partialFinalSamples = groupedSamples.filter { it.partialFinalExactMatched != null }
            val normalizedPartialFinalSamples = groupedSamples.filter { it.partialFinalNormalizedMatched != null }
            WavCallModeSummary(
                injectionMode = mode.second,
                measurementMode = mode.first,
                acceleratedInput = groupedSamples.firstOrNull()?.acceleratedInput ?: false,
                totalCases = groupedSamples.size,
                passedCases = groupedSamples.count { it.status == WavCallCaseStatus.PASSED },
                failedCases = groupedSamples.count { it.status == WavCallCaseStatus.FAILED },
                noSpeechCases = groupedSamples.count { it.status == WavCallCaseStatus.NO_SPEECH },
                multipleUtteranceCases = groupedSamples.count { it.status == WavCallCaseStatus.MULTIPLE_UTTERANCES },
                cancelledCases = groupedSamples.count { it.status == WavCallCaseStatus.CANCELLED },
                globalCer = safeRatio(totalEditDistance, totalReferenceLength),
                cerTotalEditDistance = totalEditDistance,
                cerTotalReferenceLength = totalReferenceLength,
                globalSourceRtf = safeRatio(totalComputeDuration, totalOriginalDuration),
                globalAsrRtf = safeRatio(totalComputeDuration, totalVoskInputDuration),
                voskComputeDurationMs = totalComputeDuration,
                originalAudioDurationMs = totalOriginalDuration,
                voskInputAudioDurationMs = totalVoskInputDuration,
                sceneAccuracy = safeRatio(
                    sceneSamples.count { it.sceneMatched == true }.toLong(),
                    sceneSamples.size.toLong(),
                ),
                sceneEvaluatedCases = sceneSamples.size,
                sceneMatchedCases = sceneSamples.count { it.sceneMatched == true },
                sceneCandidateAccuracy = safeRatio(
                    sceneSamples.count { it.sceneMatched == true }.toLong(),
                    sceneSamples.size.toLong(),
                ),
                sceneCandidateMatchedCases = sceneSamples.count { it.sceneMatched == true },
                sceneConfirmedAccuracy = safeRatio(
                    confirmedSceneSamples.count { it.sceneMatched == true }.toLong(),
                    sceneSamples.size.toLong(),
                ),
                sceneConfirmedMatchedCases = confirmedSceneSamples.count { it.sceneMatched == true },
                sceneConfirmedCases = confirmedSceneSamples.size,
                firstTurnSceneEvaluatedCases = firstTurnSceneSamples.size,
                firstTurnSceneMatchedCases = firstTurnSceneSamples.count { it.sceneMatched == true },
                firstTurnSceneAccuracy = safeRatio(
                    firstTurnSceneSamples.count { it.sceneMatched == true }.toLong(),
                    firstTurnSceneSamples.size.toLong(),
                ),
                continuationSceneEvaluatedCases = continuationSceneSamples.size,
                continuationSceneRetainedCases = continuationSceneSamples.count { it.sceneMatched == true },
                continuationSceneRetentionRate = safeRatio(
                    continuationSceneSamples.count { it.sceneMatched == true }.toLong(),
                    continuationSceneSamples.size.toLong(),
                ),
                deliveryIntentEvaluatedCases = deliveryIntentSamples.size,
                deliveryIntentMatchedCases = deliveryIntentSamples.count { it.deliveryIntentMatched == true },
                deliveryIntentAccuracy = safeRatio(
                    deliveryIntentSamples.count { it.deliveryIntentMatched == true }.toLong(),
                    deliveryIntentSamples.size.toLong(),
                ),
                intentEvaluatedCases = intentSamples.size,
                intentMatchedCases = intentSamples.count { it.intentMatched == true },
                intentAccuracy = safeRatio(
                    intentSamples.count { it.intentMatched == true }.toLong(),
                    intentSamples.size.toLong(),
                ),
                callNatureEvaluatedCases = callNatureSamples.size,
                callNatureMatchedCases = callNatureSamples.count { it.callNatureMatched == true },
                callNatureAccuracy = safeRatio(
                    callNatureSamples.count { it.callNatureMatched == true }.toLong(),
                    callNatureSamples.size.toLong(),
                ),
                riskEvaluatedCases = riskSamples.size,
                riskMatchedCases = riskSamples.count { it.riskLevelMatched == true },
                riskAccuracy = safeRatio(
                    riskSamples.count { it.riskLevelMatched == true }.toLong(),
                    riskSamples.size.toLong(),
                ),
                entityTp = entityTp,
                entityFp = entityFp,
                entityFn = entityFn,
                entityPrecision = entityPrecision,
                entityRecall = entityRecall,
                entityF1 = entityF1,
                strictEntityMatchedCases = entitySamples.count { it.strictEntitiesMatched == true },
                requiredEntityEvaluatedCases = requiredEntitySamples.size,
                requiredEntityIncludedCases = requiredEntitySamples.count { it.requiredEntitiesIncluded == true },
                requiredEntityInclusionRate = safeRatio(
                    requiredEntitySamples.count { it.requiredEntitiesIncluded == true }.toLong(),
                    requiredEntitySamples.size.toLong(),
                ),
                locationEvaluatedCases = locationSamples.size,
                locationMatchedCases = locationSamples.count { it.locationMatched == true },
                locationAccuracy = safeRatio(
                    locationSamples.count { it.locationMatched == true }.toLong(),
                    locationSamples.size.toLong(),
                ),
                locationExactMatchedCases = locationExactSamples.count { it.locationExactMatched == true },
                locationExactAccuracy = safeRatio(
                    locationExactSamples.count { it.locationExactMatched == true }.toLong(),
                    locationExactSamples.size.toLong(),
                ),
                locationCoreIncludedCases = locationCoreSamples.count { it.locationCoreIncluded == true },
                locationCoreInclusionRate = safeRatio(
                    locationCoreSamples.count { it.locationCoreIncluded == true }.toLong(),
                    locationCoreSamples.size.toLong(),
                ),
                locationHierarchyMatchedCases = locationHierarchySamples.count {
                    it.locationHierarchyMatched == true
                },
                locationHierarchyMatchRate = safeRatio(
                    locationHierarchySamples.count { it.locationHierarchyMatched == true }.toLong(),
                    locationHierarchySamples.size.toLong(),
                ),
                hotwordExpectedCount = hotwordExpectedCount,
                hotwordMatchedCount = hotwordMatchedCount,
                hotwordAccuracy = safeRatio(hotwordMatchedCount, hotwordExpectedCount),
                firstPassGlobalCer = safeRatio(firstPassEditDistance, firstPassReferenceLength),
                firstPassCerTotalEditDistance = firstPassEditDistance,
                firstPassCerTotalReferenceLength = firstPassReferenceLength,
                secondaryRecognitionCases = secondarySamples.size,
                secondaryRecognitionRate = safeRatio(secondarySamples.size.toLong(), groupedSamples.size.toLong()),
                secondaryRecognitionComputeDurationMs = secondarySamples.sumOf { it.secondaryRecognitionComputeDurationMs },
                secondaryRecognitionAdoptedCases = secondarySamples.count { it.selectedRecognitionAttemptIndex == 2 },
                secondaryEvidenceUsedCases = secondarySamples.count { it.secondaryEvidenceUsed },
                secondaryScenePromotedCases = secondarySamples.count { it.secondaryScenePromoted },
                secondaryEntitySupplementedCases = secondarySamples.count {
                    it.secondaryAcceptedEntityKeys.isNotEmpty()
                },
                secondaryTextReplacementCases = secondarySamples.count { it.secondaryTextReplaced },
                singlePassCases = singlePassSamples.size,
                singlePassAsrLatencyP50Ms = percentile(singlePassAsrLatencies, 0.50),
                singlePassAsrLatencyP95Ms = percentile(singlePassAsrLatencies, 0.95),
                singlePassResponseLatencyP50Ms = percentile(singlePassResponseLatencies, 0.50),
                singlePassResponseLatencyP95Ms = percentile(singlePassResponseLatencies, 0.95),
                singlePassPeakPssKb = singlePassSamples.mapNotNull { it.peakProcessPssKb }.maxOrNull(),
                secondaryPathAsrLatencyP50Ms = percentile(secondaryAsrLatencies, 0.50),
                secondaryPathAsrLatencyP95Ms = percentile(secondaryAsrLatencies, 0.95),
                secondaryPathIncrementLatencyP50Ms = percentile(secondaryIncrementLatencies, 0.50),
                secondaryPathIncrementLatencyP95Ms = percentile(secondaryIncrementLatencies, 0.95),
                secondaryPathResponseLatencyP50Ms = percentile(secondaryResponseLatencies, 0.50),
                secondaryPathResponseLatencyP95Ms = percentile(secondaryResponseLatencies, 0.95),
                secondaryPathPeakPssKb = secondarySamples.mapNotNull { it.peakProcessPssKb }.maxOrNull(),
                asrComputeDurationP50Ms = percentile(asrComputeDurations, 0.50),
                asrComputeDurationP90Ms = percentile(asrComputeDurations, 0.90),
                asrComputeDurationP95Ms = percentile(asrComputeDurations, 0.95),
                vadFrameProcessingLagP50Ms = percentile(vadFrameProcessingLags, 0.50),
                vadFrameProcessingLagP95Ms = percentile(vadFrameProcessingLags, 0.95),
                vadFrameProcessingLagMaxMs = vadFrameProcessingLags.lastOrNull(),
                recognizerCreateP50Ms = percentile(recognizerCreateDurations, 0.50),
                recognizerCreateP95Ms = percentile(recognizerCreateDurations, 0.95),
                voskAcceptComputeP50Ms = percentile(voskAcceptComputeDurations, 0.50),
                voskAcceptComputeP95Ms = percentile(voskAcceptComputeDurations, 0.95),
                voskQueueDepthMax = voskQueueDepths.maxOrNull(),
                voskQueueWaitP50Ms = percentile(voskQueueWaitMaxDurations, 0.50),
                voskQueueWaitP95Ms = percentile(voskQueueWaitMaxDurations, 0.95),
                voskDrainP50Ms = percentile(voskDrainDurations, 0.50),
                voskDrainP95Ms = percentile(voskDrainDurations, 0.95),
                voskFinalResultP50Ms = percentile(voskFinalResultDurations, 0.50),
                voskFinalResultP95Ms = percentile(voskFinalResultDurations, 0.95),
                referenceEndToAsrCompleteP50Ms = percentile(referenceEndToAsrCompleteDurations, 0.50),
                referenceEndToAsrCompleteP90Ms = percentile(referenceEndToAsrCompleteDurations, 0.90),
                referenceEndToAsrCompleteP95Ms = percentile(referenceEndToAsrCompleteDurations, 0.95),
                asrPostEndpointLatencyP50Ms = percentile(asrPostEndpointLatencies, 0.50),
                asrPostEndpointLatencyP90Ms = percentile(asrPostEndpointLatencies, 0.90),
                asrPostEndpointLatencyP95Ms = percentile(asrPostEndpointLatencies, 0.95),
                nluDurationP50Ms = percentile(nluDurations, 0.50),
                nluDurationP90Ms = percentile(nluDurations, 0.90),
                nluDurationP95Ms = percentile(nluDurations, 0.95),
                ttsSynthesisDurationP50Ms = percentile(ttsSynthesisDurations, 0.50),
                ttsSynthesisDurationP90Ms = percentile(ttsSynthesisDurations, 0.90),
                ttsSynthesisDurationP95Ms = percentile(ttsSynthesisDurations, 0.95),
                ttsToFirstAudioWriteLatencyP50Ms = percentile(ttsToFirstAudioWriteLatencies, 0.50),
                ttsToFirstAudioWriteLatencyP90Ms = percentile(ttsToFirstAudioWriteLatencies, 0.90),
                ttsToFirstAudioWriteLatencyP95Ms = percentile(ttsToFirstAudioWriteLatencies, 0.95),
                responseLatencyP50Ms = percentile(responseLatencies, 0.50),
                responseLatencyP90Ms = percentile(responseLatencies, 0.90),
                responseLatencyP95Ms = percentile(responseLatencies, 0.95),
                validResponseLatencySampleCount = responseLatencies.size,
                responseLatencyMaxMs = responseLatencies.lastOrNull(),
                candidateEndpointRollbackCount = groupedSamples.sumOf { it.candidateEndpointRollbackCount },
                speechResumedAfterCandidateP50Ms = percentile(speechResumeDelays, 0.50),
                speechResumedAfterCandidateP90Ms = percentile(speechResumeDelays, 0.90),
                speechResumedAfterCandidateP95Ms = percentile(speechResumeDelays, 0.95),
                speechResumedAfterCandidateMaxMs = speechResumeDelays.lastOrNull(),
                partialFinalEvaluatedCases = partialFinalSamples.size,
                partialFinalExactMatchedCases = partialFinalSamples.count { it.partialFinalExactMatched == true },
                partialFinalExactMatchRate = safeRatio(
                    partialFinalSamples.count { it.partialFinalExactMatched == true }.toLong(),
                    partialFinalSamples.size.toLong(),
                ),
                partialFinalNormalizedMatchedCases = normalizedPartialFinalSamples.count {
                    it.partialFinalNormalizedMatched == true
                },
                partialFinalNormalizedMatchRate = safeRatio(
                    normalizedPartialFinalSamples.count { it.partialFinalNormalizedMatched == true }.toLong(),
                    normalizedPartialFinalSamples.size.toLong(),
                ),
                partialFinalCharacterDifference = groupedSamples.sumOf {
                    it.partialFinalCharacterDifference?.toLong() ?: 0L
                },
                macroF1 = sceneMetrics.mapNotNull { it.f1 }.takeIf { it.isNotEmpty() }?.average(),
                perSceneMetrics = sceneMetrics,
                confusionMatrix = confusionMatrix(groupedSamples),
            )
        }

    private fun calculateSceneMetrics(samples: List<WavCallSampleResult>): List<WavSceneMetric> =
        EVALUATED_SCENES.map { scene ->
            val truePositive = samples.count { canonicalSceneId(it.expectedScene) == scene && it.actualScene == scene }
            val falsePositive = samples.count { canonicalSceneId(it.expectedScene) != scene && it.actualScene == scene }
            val falseNegative = samples.count { canonicalSceneId(it.expectedScene) == scene && it.actualScene != scene }
            val precision = safeRatio(truePositive.toLong(), (truePositive + falsePositive).toLong())
            val recall = safeRatio(truePositive.toLong(), (truePositive + falseNegative).toLong())
            val f1 = calculateF1(truePositive, falsePositive, falseNegative, precision, recall)
            WavSceneMetric(scene, truePositive, falsePositive, falseNegative, precision, recall, f1)
        }

    private fun confusionMatrix(samples: List<WavCallSampleResult>): Map<String, Map<String, Int>> =
        EVALUATED_SCENES.associateWith { expectedScene ->
            samples.asSequence()
                .filter { canonicalSceneId(it.expectedScene) == expectedScene }
                .groupingBy { it.actualScene ?: SceneType.UNCLASSIFIED.id }
                .eachCount()
                .toSortedMap()
        }

    private fun percentile(sortedValues: List<Long>, percentile: Double): Long? {
        if (sortedValues.isEmpty()) return null
        val index = kotlin.math.ceil(percentile * sortedValues.size).toInt().coerceIn(1, sortedValues.size) - 1
        return sortedValues[index]
    }

    fun safeRatio(numerator: Long, denominator: Long): Double? {
        if (denominator == 0L) return null
        return (numerator.toDouble() / denominator.toDouble()).finiteOrNull()
    }

    private fun calculateF1(
        truePositive: Int,
        falsePositive: Int,
        falseNegative: Int,
        precision: Double?,
        recall: Double?,
    ): Double? = calculateF1(
        truePositive.toLong(),
        falsePositive.toLong(),
        falseNegative.toLong(),
        precision,
        recall,
    )

    private fun calculateF1(
        truePositive: Long,
        falsePositive: Long,
        falseNegative: Long,
        precision: Double?,
        recall: Double?,
    ): Double? {
        if (truePositive + falsePositive + falseNegative == 0L) return null
        if (truePositive == 0L) return 0.0
        if (precision == null || recall == null) return null
        return (2.0 * precision * recall / (precision + recall)).finiteOrNull()
    }

    private fun Map<String, String>.normalizedEntityPairs(): Set<String> = entries.mapTo(linkedSetOf()) { entry ->
        "${entry.key}:${normalizeEntityValue(entry.value)}"
    }

    private fun levenshteinByCodePoint(left: String, right: String): Int {
        val leftPoints = left.toCodePoints()
        val rightPoints = right.toCodePoints()
        val longer: IntArray
        val shorter: IntArray
        if (leftPoints.size >= rightPoints.size) {
            longer = leftPoints
            shorter = rightPoints
        } else {
            longer = rightPoints
            shorter = leftPoints
        }
        var previous = IntArray(shorter.size + 1) { index -> index }
        var current = IntArray(shorter.size + 1)
        for (row in longer.indices) {
            current[0] = row + 1
            for (column in shorter.indices) {
                val substitutionCost = if (longer[row] == shorter[column]) 0 else 1
                current[column + 1] = minOf(
                    previous[column + 1] + 1,
                    current[column] + 1,
                    previous[column] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.size]
    }

    private fun String.toCodePoints(): IntArray {
        val points = IntArray(codePointCount(0, length))
        var sourceOffset = 0
        var targetOffset = 0
        while (sourceOffset < length) {
            val codePoint = codePointAt(sourceOffset)
            points[targetOffset] = codePoint
            sourceOffset += Character.charCount(codePoint)
            targetOffset += 1
        }
        return points
    }

    private fun Int.isUnicodeWhitespace(): Boolean = Character.isWhitespace(this) || Character.isSpaceChar(this)

    private fun Int.isUnicodePunctuation(): Boolean = when (Character.getType(this)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }

    private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

    private val EVALUATED_SCENES = listOf(
        SceneType.DELIVERY.id,
        SceneType.RIDE_HAILING.id,
        SceneType.CUSTOMER_SERVICE.id,
        SceneType.REAL_ESTATE.id,
        SceneType.INSURANCE_FINANCE.id,
        SceneType.SPAM_RISK.id,
    )

    private val LOCATION_CORE_TERMS = listOf(
        "访客通道", "校车通道", "地下连廊", "取餐柜", "取餐架", "保安亭", "值班室",
        "卸货区", "电梯厅", "消防门", "茶水间", "等候区", "停车带", "会议室",
        "访客口", "闸机", "连廊", "电梯口", "入口", "出口", "北侧门", "南侧门",
        "东侧门", "西侧门", "门口",
    )

    private val LOCATION_HIERARCHY_PATTERNS = listOf(
        Regex("(?:地下)?[一二三四五六七八九十两0-9]+层|首层|顶层|底层"),
        Regex("[A-Za-z][座区]|[一二三四五六七八九十两0-9]+(?:栋|号楼|单元)|(?:急诊|门诊|研发)楼"),
        Regex("[东南西北](?:侧|区|看台)|地下|对面"),
        Regex(
            "访客通道|校车通道|地下连廊|取餐柜|取餐架|保安亭|值班室|卸货区|电梯厅|" +
                "消防门|茶水间|等候区|停车带|会议室|访客口|闸机|连廊|电梯口|入口|出口|门口",
        ),
    )
}
