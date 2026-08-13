package com.example.calldelegate.core.ai.evaluation

import kotlinx.serialization.Serializable

@Serializable
enum class CorpusKind { REGRESSION, CHALLENGE }

@Serializable
enum class EvidenceLevel { SYNTHETIC_REGRESSION, SYNTHETIC_CHALLENGE }

@Serializable
enum class SupportExpectation { SUPPORTED, FUTURE }

@Serializable
enum class DialogueOutcome { COMPLETE, RECOVERED, SAFE_END }

@Serializable
data class EvaluationCorpus(
    val schemaVersion: Int,
    val corpusId: String,
    val language: String,
    val sourceType: String,
    val evidenceLevel: EvidenceLevel,
    val kind: CorpusKind,
    val expectedTurnCaseCount: Int,
    val expectedDialogueCaseCount: Int,
    val turnCases: List<TurnEvaluationCase> = emptyList(),
    val dialogueCases: List<DialogueEvaluationCase> = emptyList(),
) {
    val totalCaseCount: Int
        get() = turnCases.size + dialogueCases.size
}

@Serializable
data class TurnEvaluationCase(
    val id: String,
    val text: String,
    val enabledScenes: List<String> = emptyList(),
    val expectedScene: String? = null,
    val expectedIntent: String? = null,
    val expectedSlots: Map<String, String> = emptyMap(),
    val expectedSupport: SupportExpectation = SupportExpectation.SUPPORTED,
    val tags: List<String> = emptyList(),
)

@Serializable
data class DialogueEvaluationCase(
    val id: String,
    val enabledScenes: List<String> = emptyList(),
    val turns: List<EvaluationTurn>,
    val expectedFinalScene: String,
    val expectedFinalState: String,
    val expectedShouldEnd: Boolean,
    val expectedFinalSlots: Map<String, String> = emptyMap(),
    val outcome: DialogueOutcome = DialogueOutcome.COMPLETE,
    val requiredReplyFragments: List<String> = emptyList(),
    val forbiddenReplyFragments: List<String> = emptyList(),
    val expectedSupport: SupportExpectation = SupportExpectation.SUPPORTED,
    val tags: List<String> = emptyList(),
)

@Serializable
data class EvaluationTurn(
    val text: String? = null,
    val recognitionFailed: Boolean = false,
)

data class EvaluationCaseResult(
    val corpusId: String,
    val id: String,
    val caseType: String,
    val supportExpectation: SupportExpectation,
    val enabledScenes: List<String>,
    val tags: List<String>,
    val inputText: String,
    val expectedScene: String?,
    val actualScene: String?,
    val expectedIntent: String?,
    val actualIntent: String?,
    val expectedSlots: Map<String, String>,
    val actualSlots: Map<String, String>,
    val outcome: DialogueOutcome?,
    val passed: Boolean?,
    val failures: List<String>,
    val sceneMatched: Boolean?,
    val intentMatched: Boolean?,
    val slotCounts: SlotCounts,
    val dialogueCompleted: Boolean?,
)

data class CorpusEvaluation(
    val summary: CorpusSummary,
    val cases: List<EvaluationCaseResult>,
)

@Serializable
data class ReportRate(
    val correct: Int,
    val total: Int,
    val value: Double?,
)

@Serializable
data class ReportSlotMetrics(
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
    val precision: Double?,
    val recall: Double?,
    val f1: Double?,
)

@Serializable
data class CorpusSummary(
    val corpusId: String,
    val totalCases: Int,
    val evaluatedCases: Int,
    val futureSceneCases: Int,
    val passedCases: Int,
    val failedCases: Int,
    val casePassRate: ReportRate,
    val sceneAccuracy: ReportRate,
    val intentAccuracy: ReportRate,
    val slotMetrics: ReportSlotMetrics,
    val dialogueCompletionRate: ReportRate,
    val recoveryRate: ReportRate,
    val safeTerminationRate: ReportRate,
)

@Serializable
data class EvaluationSummaryReport(
    val schemaVersion: Int = 1,
    val evidenceStatement: String =
        "Synthetic text evaluation only; not real ASR, device, or production accuracy.",
    val asrCerStatus: String = "NOT_MEASURED",
    val regression: CorpusSummary,
    val challenge: CorpusSummary,
)
