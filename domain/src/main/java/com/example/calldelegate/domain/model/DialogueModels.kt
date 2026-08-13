package com.example.calldelegate.domain.model

import kotlinx.serialization.Serializable

data class IntentMatch(
    val intentId: String,
    val scene: SceneType,
    val confidence: Float,
    val matchedEvidence: String,
)

@Serializable
enum class CallNature {
    SERVICE,
    APPOINTMENT,
    NOTIFICATION,
    MARKETING,
    SUSPICIOUS,
    UNKNOWN,
}

@Serializable
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class RuleClassificationContext(
    val lockedScene: SceneType? = null,
    val stateId: String? = null,
    val expectedSlots: Set<String> = emptySet(),
    val existingSlots: Map<String, String> = emptyMap(),
    /** Entities extracted before scene scoring so high-confidence slots can contribute evidence. */
    val derivedEntitySlots: Map<String, String> = emptyMap(),
    val allowedIntentIds: Set<String> = emptySet(),
    val languageTag: String = "zh-CN",
    val secondaryRecognition: SecondaryRecognitionEvidence? = null,
)

/**
 * Evidence from an optional scene-vocabulary recognition pass.
 *
 * The primary transcript remains the user-visible transcript. This evidence is only used to
 * strengthen scene classification or supplement a formally supported entity.
 */
data class SecondaryRecognitionEvidence(
    val text: String,
    val sceneHints: Set<SceneType>,
    val matchedHotwordsByScene: Map<String, List<String>>,
    val textDifferenceRate: Double,
    val unknownTokenCount: Int = 0,
    val triggerReasons: List<String> = emptyList(),
    val classifiedScene: SceneType? = null,
    val classificationConfidence: Float = 0f,
    val classificationSceneMargin: Float = 0f,
    val classificationShouldClarify: Boolean = false,
    val allowClassifiedSceneWithoutHotword: Boolean = false,
)

/** Debug evaluation modes for the optional second ASR pass. Production defaults to [DISABLED]. */
enum class SecondaryRecognitionExperimentMode {
    DISABLED,
    CURRENT_POLICY,
    REVISED_POLICY,
}

data class RuleClassificationResult(
    val scene: String?,
    val intent: String?,
    /** Original business scene retained when a risk policy overrides the final scene. */
    val topicScene: String? = null,
    val callNature: CallNature = CallNature.UNKNOWN,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val confidence: Float = 0f,
    val sceneMargin: Float = 0f,
    val matchedEvidence: List<String> = emptyList(),
    val rejectedEvidence: List<String> = emptyList(),
    val shouldClarify: Boolean = false,
    val clarificationPrompt: String? = null,
    val extractedSlots: Map<String, String> = emptyMap(),
    val sceneCandidates: List<String> = emptyList(),
    val riskReasons: List<String> = emptyList(),
    val debugTrace: RuleDebugTrace? = null,
)

@Serializable
data class RuleDebugTrace(
    val inputText: String,
    val normalizedText: String,
    val sceneScores: List<RuleSceneScoreTrace> = emptyList(),
    val intentScores: List<RuleIntentScoreTrace> = emptyList(),
    val thresholds: RuleThresholdTrace = RuleThresholdTrace(),
    val sceneCompetition: String? = null,
    val risk: RuleRiskTrace = RuleRiskTrace(),
    val finalScene: String? = null,
    val finalSceneSource: String? = null,
)

@Serializable
data class RuleSceneScoreTrace(
    val scene: String,
    val score: Float,
    val definingIntent: String? = null,
    val hasPositiveEvidence: Boolean = false,
    val accepted: Boolean = false,
)

@Serializable
data class RuleIntentScoreTrace(
    val scene: String,
    val intent: String,
    val callNature: CallNature,
    val score: Float,
    val sceneDefining: Boolean,
    val hasPositiveEvidence: Boolean,
    val accepted: Boolean,
    val evidence: List<RuleEvidenceTrace> = emptyList(),
)

@Serializable
data class RuleEvidenceTrace(
    val type: String,
    val text: String? = null,
    val startIndex: Int? = null,
    val endExclusive: Int? = null,
    val weight: Float,
    val accepted: Boolean,
    val reason: String? = null,
)

@Serializable
data class RuleThresholdTrace(
    val minimumSceneScore: Float = 0f,
    val minimumIntentScore: Float = 0f,
    val clarificationMargin: Float = 0f,
    val sceneSwitchScore: Float = 0f,
    val clarificationScore: Float = 0f,
)

@Serializable
data class RuleRiskTrace(
    val invoked: Boolean = false,
    val rawLevel: RiskLevel = RiskLevel.LOW,
    val effectiveLevel: RiskLevel = RiskLevel.LOW,
    val matchedRiskIds: List<String> = emptyList(),
    val rejectedRiskIds: List<String> = emptyList(),
    val contextExemptedRiskIds: List<String> = emptyList(),
    val matchedEvidence: List<RuleEvidenceTrace> = emptyList(),
    val escalationReason: String? = null,
    val commitPolicy: String? = null,
    val overrideApplied: Boolean = false,
    val fallbackApplied: Boolean = false,
    val topicProtected: Boolean = false,
    val fallbackSkipReason: String? = null,
)

/** How confidently the current scene can be used by downstream dialogue actions. */
enum class SceneConfidenceState {
    UNKNOWN,
    PROVISIONAL,
    CONFIRMED,
}

data class SlotExtractionRequest(
    val text: String,
    val expectedSlots: Set<String> = emptySet(),
    val existingSlots: Map<String, String> = emptyMap(),
    val scene: SceneType? = null,
    val stateId: String? = null,
    val languageTag: String = "zh-CN",
)

data class SlotExtractionResult(
    val slots: Map<String, String>,
    val overwrittenSlots: Set<String> = emptySet(),
    val rejectedEvidence: List<String> = emptyList(),
)

data class DialogueContext(
    val sessionId: String,
    val scene: SceneType = SceneType.UNCLASSIFIED,
    /** Business topic before a safety override, if one was identified. */
    val topicScene: SceneType? = null,
    val stateId: String = "route",
    val slots: Map<String, String> = emptyMap(),
    val retryCount: Int = 0,
    val fallbackStage: Int = 0,
    val languageTag: String = "zh-CN",
    val callNature: CallNature = CallNature.UNKNOWN,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val lastConfidence: Float = 0f,
    val lastSceneMargin: Float = 0f,
    val pendingClarificationScenes: List<String> = emptyList(),
    val sceneConfidenceState: SceneConfidenceState = SceneConfidenceState.UNKNOWN,
)

data class DialogueDecision(
    val context: DialogueContext,
    val reply: String,
    val matchedIntent: String?,
    val shouldEnd: Boolean,
    val recognitionFailure: Boolean = false,
    val classification: RuleClassificationResult? = null,
    val replyTemplateId: String? = null,
    val replyVariables: Map<String, String> = emptyMap(),
    val isFallbackTemplate: Boolean = false,
    val fallbackReason: String? = null,
    val replySafe: Boolean? = null,
    val complianceFlags: List<String> = emptyList(),
)
