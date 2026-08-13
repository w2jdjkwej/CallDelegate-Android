package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechRecognitionFocus
import com.example.calldelegate.domain.api.SpeechRecognitionMode
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence

data class RecognitionPreview(
    val text: String,
    val classification: RuleClassificationResult,
    val unknownTokenCount: Int = 0,
)

data class SceneRecognitionRetry(
    val context: SpeechRecognitionContext,
    val reasons: List<String>,
)

class SceneRecognitionPolicy(
    private val hotwords: SceneHotwordProvider,
) {
    fun retryContext(
        first: RecognitionPreview,
        firstContext: SpeechRecognitionContext,
    ): SpeechRecognitionContext? = retryDecision(first, firstContext)?.context

    /**
     * Context-only part of [retryDecision], safe to evaluate before the preview classification.
     * A false result means [retryDecision] would reject the turn regardless of the classification,
     * so the caller can skip the preview instead of computing and discarding it.
     * [retryDecision] keeps the same guard, so hoisting this never changes the decision.
     */
    fun supportsRetry(firstContext: SpeechRecognitionContext): Boolean =
        firstContext.mode == SpeechRecognitionMode.GENERAL

    fun retryDecision(
        first: RecognitionPreview,
        firstContext: SpeechRecognitionContext,
    ): SceneRecognitionRetry? {
        val classification = first.classification
        if (classification.riskLevel == RiskLevel.HIGH) return null
        if (
            classification.scene == SceneType.SPAM_RISK.id ||
            SceneType.SPAM_RISK.id in classification.sceneCandidates
        ) return null
        if (firstContext.mode != SpeechRecognitionMode.GENERAL) return null

        val presetHints = firstContext.sceneHints
            .filter { it != SceneType.UNCLASSIFIED && hotwords.supports(it) }
            .take(MAX_SCENE_HINTS)
            .toCollection(linkedSetOf())
        val hints = presetHints.ifEmpty { candidateScenes(classification) }
        if (hints.isEmpty()) return null
        val entityWeakness = criticalEntityWeakness(first.text, classification)
        val knownSceneContinuation = presetHints.size == 1
        val knownDeliveryContinuation = presetHints == setOf(SceneType.DELIVERY)
        if (knownDeliveryContinuation && entityWeakness.focuses.isEmpty()) return null
        val hasOtherWeakness = classification.scene == null ||
            classification.shouldClarify ||
            classification.sceneMargin < hotwords.policy().minimumSceneMargin ||
            first.unknownTokenCount > 0 ||
            first.text.contains(UNKNOWN_TOKEN, ignoreCase = true) ||
            entityWeakness.focuses.isNotEmpty()
        val reasons = buildList {
            if (classification.scene == null) add(REASON_UNCLASSIFIED)
            if (classification.shouldClarify) add(REASON_CLARIFICATION)
            if (classification.confidence < hotwords.policy().retryBelowConfidence && hasOtherWeakness) {
                add(REASON_LOW_CONFIDENCE)
            }
            if (classification.sceneMargin < hotwords.policy().minimumSceneMargin) add(REASON_LOW_MARGIN)
            if (first.unknownTokenCount > 0 || first.text.contains(UNKNOWN_TOKEN, ignoreCase = true)) {
                add(REASON_UNKNOWN_TOKEN)
            }
            addAll(entityWeakness.reasons)
        }.distinct()
        if (reasons.isEmpty()) return null

        val focuses = linkedSetOf<SpeechRecognitionFocus>()
        if (!knownSceneContinuation && reasons.any { it in SCENE_REASONS }) {
            focuses += SpeechRecognitionFocus.SCENE
        }
        focuses += entityWeakness.focuses
        if (hotwords.phrasesFor(hints, focuses).isEmpty()) return null
        return SceneRecognitionRetry(
            context = SpeechRecognitionContext(
                mode = SpeechRecognitionMode.SCENE_VOCABULARY,
                sceneHints = hints,
                isSecondaryPass = true,
                focuses = focuses,
                languageTag = firstContext.languageTag,
            ),
            reasons = reasons,
        )
    }

    fun secondaryEvidence(
        first: RecognitionPreview,
        second: RecognitionPreview,
        retry: SceneRecognitionRetry,
    ): SecondaryRecognitionEvidence = SecondaryRecognitionEvidence(
        text = second.text,
        sceneHints = retry.context.sceneHints,
        matchedHotwordsByScene = hotwords.matchedPhrasesByScene(second.text, retry.context.sceneHints),
        textDifferenceRate = textDifferenceRate(first.text, second.text),
        unknownTokenCount = second.unknownTokenCount,
        triggerReasons = retry.reasons,
        classifiedScene = second.classification.scene?.let(SceneType::fromId),
        classificationConfidence = second.classification.confidence,
        classificationSceneMargin = second.classification.sceneMargin,
        classificationShouldClarify = second.classification.shouldClarify,
    )

    fun textDifferenceRate(first: String, second: String): Double = normalizedTextDifference(first, second)

    fun isStable(classification: RuleClassificationResult): Boolean {
        val policy = hotwords.policy()
        val scene = classification.scene?.let(SceneType::fromId) ?: return false
        return scene != SceneType.UNCLASSIFIED &&
            hotwords.supports(scene) &&
            !classification.shouldClarify &&
            classification.confidence >= policy.retryBelowConfidence &&
            classification.sceneMargin >= policy.minimumSceneMargin
    }

    private fun candidateScenes(classification: RuleClassificationResult): Set<SceneType> {
        val policy = hotwords.policy()
        if (classification.confidence < policy.minimumCandidateConfidence) return emptySet()
        val candidateIds = buildList {
            classification.scene?.let(::add)
            addAll(classification.sceneCandidates)
        }
        val candidateLimit = if (
            classification.shouldClarify ||
            classification.sceneMargin < policy.minimumSceneMargin
        ) {
            MAX_SCENE_HINTS
        } else {
            1
        }
        return candidateIds.asSequence()
            .map(SceneType::fromId)
            .filter { scene -> scene != SceneType.UNCLASSIFIED && hotwords.supports(scene) }
            .distinct()
            .take(candidateLimit)
            .toCollection(linkedSetOf())
    }

    private fun criticalEntityWeakness(
        text: String,
        classification: RuleClassificationResult,
    ): EntityWeakness {
        val focuses = linkedSetOf<SpeechRecognitionFocus>()
        val reasons = linkedSetOf<String>()
        val slots = classification.extractedSlots
        val candidateScenes = buildList {
            classification.scene?.let(::add)
            addAll(classification.sceneCandidates)
        }.asSequence()
            .map(SceneType::fromId)
            .filter { it != SceneType.UNCLASSIFIED }
            .distinct()
            .take(MAX_SCENE_HINTS)
            .toList()

        for (scene in candidateScenes) {
            for (rule in hotwords.criticalEntitiesFor(scene)) {
                val value = when (rule.slot) {
                    "pickupLocation" -> slots["pickupLocation"] ?: slots["location"]
                    "viewingTime" -> slots["viewingTime"] ?: slots["time"]
                    "expiryTime" -> slots["expiryTime"] ?: slots["time"]
                    else -> slots[rule.slot]
                }
                if (!value.isNullOrBlank()) continue
                val cuePresent = rule.cues.any(text::contains)
                val requiredByIntent = classification.scene == scene.id &&
                    classification.intent in rule.requiredIntents
                if (!cuePresent && !requiredByIntent) continue

                focuses += SpeechRecognitionFocus.valueOf(rule.focus)
                reasons += missingEntityReason(rule.slot)
                if (requiredByIntent && rule.slot in LOCATION_SLOTS) {
                    reasons += REASON_INTENT_MISSING_LOCATION
                }
            }
        }

        val deliveryIsCandidate = SceneType.DELIVERY in candidateScenes
        if (!deliveryIsCandidate) return EntityWeakness(focuses, reasons.toList())
        val locationConflict = classification.rejectedEvidence.any { it.startsWith("slot:location:conflict") }
        val location = slots["location"]
        val suspectedLocationError = SUSPECTED_LOCATION_ERRORS.any(text::contains)
        if (!locationConflict && location.isNullOrBlank() && suspectedLocationError) {
            focuses += SpeechRecognitionFocus.LOCATION
            reasons += REASON_MISSING_LOCATION
        }
        if (!location.isNullOrBlank() && isLowQualityLocation(location)) {
            focuses += SpeechRecognitionFocus.LOCATION
            reasons += REASON_INVALID_LOCATION
        }
        if (suspectedLocationError) {
            focuses += SpeechRecognitionFocus.LOCATION
            reasons += REASON_SUSPECTED_LOCATION_ERROR
        }
        return EntityWeakness(focuses, reasons.toList())
    }

    private fun missingEntityReason(slot: String): String = when (slot) {
        "location", "pickupLocation" -> REASON_MISSING_LOCATION
        "orderNumber", "orderId", "licensePlate" -> REASON_MISSING_ORDER
        "estimatedTime", "viewingTime", "expiryTime", "time" -> REASON_MISSING_TIME
        "issueType" -> REASON_MISSING_ISSUE
        else -> "missing_$slot"
    }

    private fun isLowQualityLocation(location: String): Boolean =
        location.length > MAXIMUM_LOCATION_LENGTH ||
            location in LOW_INFORMATION_LOCATIONS ||
            INVALID_LOCATION_CONTENT.any(location::contains)

    private companion object {
        const val MAX_SCENE_HINTS = 2
        const val UNKNOWN_TOKEN = "[unk]"
        const val REASON_UNCLASSIFIED = "unclassified"
        const val REASON_CLARIFICATION = "clarification"
        const val REASON_LOW_CONFIDENCE = "low_confidence"
        const val REASON_LOW_MARGIN = "low_margin"
        const val REASON_UNKNOWN_TOKEN = "unknown_token"
        const val REASON_MISSING_LOCATION = "missing_location"
        const val REASON_INVALID_LOCATION = "invalid_location"
        const val REASON_SUSPECTED_LOCATION_ERROR = "suspected_location_error"
        const val REASON_INTENT_MISSING_LOCATION = "intent_missing_location"
        const val REASON_MISSING_ORDER = "missing_order"
        const val REASON_MISSING_TIME = "missing_time"
        const val REASON_MISSING_ISSUE = "missing_issue"
        const val MAXIMUM_LOCATION_LENGTH = 56
        val LOCATION_SLOTS = setOf("location", "pickupLocation")
        val SCENE_REASONS = setOf(
            REASON_UNCLASSIFIED,
            REASON_CLARIFICATION,
            REASON_LOW_CONFIDENCE,
            REASON_LOW_MARGIN,
            REASON_UNKNOWN_TOKEN,
        )
        val LOW_INFORMATION_LOCATIONS = setOf("门口", "入口", "楼下", "这里", "那里", "这边", "那边")
        val INVALID_LOCATION_CONTENT = listOf(
            "您这是", "你这是", "您在哪", "你在哪", "哪个单元", "哪个门", "什么位置",
            "保安不让", "配送员上楼", "麻烦您", "我想问一下",
        )
        val SUSPECTED_LOCATION_ERRORS = listOf(
            "保安挺", "娶惭愧", "爱柜", "卸货去", "电梯听", "澳地区", "停车大",
        )
    }

    private data class EntityWeakness(
        val focuses: Set<SpeechRecognitionFocus> = emptySet(),
        val reasons: List<String> = emptyList(),
    )
}

class SceneVocabularyTracker(
    private val policy: SceneRecognitionPolicy,
    private val configuration: SceneHotwordProvider,
) {
    private var stableCandidate: SceneType? = null
    private var stableCount = 0
    private var weakCount = 0
    private var activeScene: SceneType? = null

    fun recognitionContext(): SpeechRecognitionContext = activeScene?.let { scene ->
        SpeechRecognitionContext(SpeechRecognitionMode.SCENE_VOCABULARY, setOf(scene))
    } ?: SpeechRecognitionContext()

    fun observe(classification: RuleClassificationResult?) {
        if (classification == null) {
            observeWeak()
            return
        }
        val scene = classification.scene?.let(SceneType::fromId)?.takeIf { it != SceneType.UNCLASSIFIED }
        val active = activeScene
        if (active != null) {
            if (scene != null && scene != active && policy.isStable(classification)) {
                reset()
                observe(classification)
                return
            }
            if (scene == active && policy.isStable(classification)) {
                weakCount = 0
            } else {
                observeWeak()
            }
            return
        }

        if (scene == null || !policy.isStable(classification)) {
            stableCandidate = null
            stableCount = 0
            return
        }
        if (stableCandidate == scene) {
            stableCount += 1
        } else {
            stableCandidate = scene
            stableCount = 1
        }
        if (stableCount >= configuration.policy().stableTurns) {
            activeScene = scene
            weakCount = 0
        }
    }

    fun reset() {
        stableCandidate = null
        stableCount = 0
        weakCount = 0
        activeScene = null
    }

    private fun observeWeak() {
        if (activeScene == null) return
        weakCount += 1
        if (weakCount >= configuration.policy().weakTurnsBeforeGeneral) reset()
    }
}
