package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.EntityExtractor
import com.example.calldelegate.domain.api.IntentClassifier
import com.example.calldelegate.domain.model.CallNature
import com.example.calldelegate.domain.model.IntentMatch
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.RuleDebugTrace
import com.example.calldelegate.domain.model.RuleEvidenceTrace
import com.example.calldelegate.domain.model.RuleIntentScoreTrace
import com.example.calldelegate.domain.model.RuleRiskTrace
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.core.ai.rules.template.SentenceTemplate
import com.example.calldelegate.core.ai.rules.template.TemplateMatch
import com.example.calldelegate.core.ai.rules.template.TemplateMatcher
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.RuleSceneScoreTrace
import com.example.calldelegate.domain.model.RuleThresholdTrace
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.example.calldelegate.domain.model.SlotExtractionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The intent a spam primitive speaks for unless it names another. */
private const val DEFAULT_SPAM_INTENT = "marketing_pitch"

private data class CompiledSpamRiskPrimitive(
    val primitiveId: String,
    val weight: Float,
    val patterns: List<Regex>,
    val intentId: String,
)

private data class CompiledSpamRiskSemanticConfig(
    val enabled: Boolean,
    val natureThreshold: Float,
    val weakCandidateThreshold: Float,
    val comboBonus: Float,
    val openingDetectionEnabled: Boolean,
    val openingWeight: Float,
    val primitives: List<CompiledSpamRiskPrimitive>,
    val exemptionPatterns: List<Regex>,
    val openingPatterns: List<Regex>,
) {
    /** The intents any primitive can route to, so the others skip the regex work entirely. */
    val targetIntentIds: Set<String> = primitives.mapTo(mutableSetOf()) { it.intentId }
}

private data class SpamRiskSemanticMatch(
    val score: Float = 0f,
    val matchedPrimitiveIds: List<String> = emptyList(),
    val evidence: List<RuleEvidenceTrace> = emptyList(),
    /**
     * The intent the heaviest matched primitive speaks for. The score itself is still computed over
     * every match, so which primitives combine, and whether the total clears natureThreshold, are
     * unchanged by routing -- only who receives it changes.
     */
    val targetIntentId: String = DEFAULT_SPAM_INTENT,
)

private fun compileSpamRiskSemantic(config: SpamRiskSemanticConfig): CompiledSpamRiskSemanticConfig =
    CompiledSpamRiskSemanticConfig(
        enabled = config.enabled,
        natureThreshold = config.natureThreshold,
        weakCandidateThreshold = config.weakCandidateThreshold,
        comboBonus = config.comboBonus,
        openingDetectionEnabled = config.openingDetectionEnabled,
        openingWeight = config.openingWeight,
        primitives = config.primitives.map { primitive ->
            CompiledSpamRiskPrimitive(
                primitiveId = primitive.primitiveId,
                weight = primitive.weight,
                patterns = primitive.patterns.map { Regex(it.lowercase()) },
                intentId = primitive.intentId,
            )
        },
        exemptionPatterns = config.exemptionPatterns.map { Regex(it.lowercase()) },
        openingPatterns = config.openingPatterns.map { Regex(it.lowercase()) },
    )

class RuleBasedIntentClassifier(
    private val provider: RuleProvider,
    private val extractor: EntityExtractor = RegexEntityExtractor(),
    private val logger: RuleLogger = NoOpRuleLogger,
    private val validator: RuleConfigValidator = RuleConfigValidator(),
    private val debugTraceEnabled: Boolean = false,
) : IntentClassifier {
    private val loadMutex = Mutex()
    @Volatile private var cached: CompiledRuleSet? = null
    @Volatile private var loadFailed = false

    suspend fun rules(): DialogueRuleFile? = compiledRules()?.source

    override suspend fun classify(text: String, enabledScenes: Set<SceneType>): IntentMatch? {
        val result = classifyDetailed(text, enabledScenes) ?: return null
        if (result.shouldClarify) return null
        val sceneId = result.scene ?: return null
        val intentId = result.intent ?: return null
        val externalIntentId = compiledRules()
            ?.source
            ?.scenarios
            ?.firstOrNull { it.sceneId == sceneId }
            ?.intents
            ?.firstOrNull { it.intentId == intentId }
            ?.legacyIntentIds
            ?.firstOrNull()
            ?: intentId
        return IntentMatch(
            intentId = externalIntentId,
            scene = SceneType.fromId(sceneId),
            confidence = result.confidence,
            matchedEvidence = result.matchedEvidence.joinToString(","),
        )
    }

    override suspend fun classifyDetailed(
        text: String,
        enabledScenes: Set<SceneType>,
        context: RuleClassificationContext,
    ): RuleClassificationResult? {
        val compiled = compiledRules() ?: return null
        val language = compiled.languageFor(context.languageTag)
        val startedNanos = System.nanoTime()
        val primaryContext = context.copy(secondaryRecognition = null)
        val primary = classifySingle(text, enabledScenes, primaryContext, compiled, language)
        val evidence = context.secondaryRecognition
        val result = if (evidence == null) {
            primary
        } else {
            fuseSecondaryRecognition(
                primaryText = text,
                primary = primary,
                evidence = evidence,
                enabledScenes = enabledScenes,
                context = primaryContext,
                compiled = compiled,
                language = language,
            )
        }
        logger.classificationCompleted((System.nanoTime() - startedNanos) / 1_000L, result)
        return result
    }

    private suspend fun classifySingle(
        text: String,
        enabledScenes: Set<SceneType>,
        context: RuleClassificationContext,
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
        allowLowConfidenceClarification: Boolean = true,
    ): RuleClassificationResult {
        if (text.isBlank()) return RuleClassificationResult(scene = context.lockedScene?.id, intent = null)
        val normalized = language.normalizer.normalize(text)
        if (normalized.isBlank()) return RuleClassificationResult(scene = context.lockedScene?.id, intent = null)
        val contextualCorrection = correctRealEstateContext(normalized, enabledScenes)
        val classificationText = contextualCorrection.text

        val preliminarySlots = extractor.extract(
            SlotExtractionRequest(
                text = text,
                expectedSlots = emptySet(),
                existingSlots = context.existingSlots,
                scene = context.lockedScene,
                stateId = context.stateId,
                languageTag = context.languageTag,
            ),
        ).slots
        val classificationContext = context.copy(derivedEntitySlots = preliminarySlots)

        val classified = withContext(Dispatchers.Default) {
            classifyNormalized(compiled, language, classificationText, enabledScenes, classificationContext)
        }.let { result ->
            if (!allowLowConfidenceClarification && result.shouldClarify) {
                result.copy(shouldClarify = false, clarificationPrompt = null)
            } else {
                result
            }
        }
        val resolvedScene = classified.scene?.let(SceneType::fromId)?.takeIf { it != SceneType.UNCLASSIFIED }
            ?: context.lockedScene
        val enteredNewScene = resolvedScene != null && resolvedScene != context.lockedScene
        val switchedScene = context.lockedScene != null && enteredNewScene
        val targetInitialState = if (enteredNewScene) {
            compiled.source.scenarios.firstOrNull { it.sceneId == resolvedScene?.id }?.let { scenario ->
                scenario.states.firstOrNull { it.stateId == scenario.initialState }
            }
        } else {
            null
        }
        val slotResult = extractor.extract(
            SlotExtractionRequest(
                text = text,
                expectedSlots = targetInitialState?.expectedSlots?.toSet() ?: context.expectedSlots,
                existingSlots = if (switchedScene) context.existingSlots - "purpose" else context.existingSlots,
                scene = resolvedScene,
                stateId = targetInitialState?.stateId ?: context.stateId,
                languageTag = context.languageTag,
            ),
        )
        val withSlots = classified.copy(
            matchedEvidence = (
                classified.matchedEvidence + contextualCorrection.appliedTerms.map { term ->
                    "nlu:context_correction:$term"
                }
            ).distinct(),
            rejectedEvidence = (classified.rejectedEvidence + slotResult.rejectedEvidence).distinct(),
            extractedSlots = classified.extractedSlots + slotResult.slots,
            debugTrace = classified.debugTrace?.copy(inputText = text),
        )
        val priorityIntentIds = targetInitialState?.transitions.orEmpty()
            .map(TransitionRule::intentId)
            .filterNot { it == "*" }
            .toSet()
            .ifEmpty { context.allowedIntentIds }
        return applyDeliveryIntentPriority(
            result = applyCustomerServiceIntentPriority(
                result = applyRideHailingIntentPolicy(
                    result = withSlots,
                    sourceText = text,
                    normalizedText = classificationText,
                    enabledScenes = enabledScenes,
                    lockedScene = context.lockedScene,
                ),
                normalizedText = classificationText,
                allowedIntentIds = priorityIntentIds,
            ),
            normalizedText = classificationText,
            resolvedScene = resolvedScene,
            allowedIntentIds = priorityIntentIds,
        )
    }

    private fun applyRideHailingIntentPolicy(
        result: RuleClassificationResult,
        sourceText: String,
        normalizedText: String,
        enabledScenes: Set<SceneType>,
        lockedScene: SceneType?,
    ): RuleClassificationResult {
        val rideEnabled = SceneType.RIDE_HAILING in enabledScenes || lockedScene == SceneType.RIDE_HAILING
        if (!rideEnabled || result.riskLevel == RiskLevel.HIGH || result.scene == SceneType.SPAM_RISK.id) {
            return result
        }
        if (
            result.scene == SceneType.RIDE_HAILING.id &&
            RideHailingIntentPolicy.shouldRejectWeakRideScene(sourceText)
        ) {
            return result.copy(
                scene = null,
                intent = null,
                callNature = CallNature.UNKNOWN,
                confidence = 0f,
                sceneMargin = 0f,
                matchedEvidence = (result.matchedEvidence + "ride_gate:rejected:weak_or_foreign").distinct(),
                rejectedEvidence = (result.rejectedEvidence + "ride_gate:rejected:weak_or_foreign").distinct(),
                shouldClarify = true,
                clarificationPrompt = null,
                sceneCandidates = emptyList(),
            )
        }
        val decision = RideHailingIntentPolicy.decide(normalizedText)
            ?: RideHailingIntentPolicy.decide(sourceText)
            ?: return result
        val rejected = buildList {
            addAll(result.rejectedEvidence)
            if (result.scene != null && result.scene != SceneType.RIDE_HAILING.id) {
                add("ride_gate:replaced_scene:${result.scene}")
            }
            if (result.intent != null && result.intent != decision.intentId) {
                add("ride_priority:replaced_intent:${result.intent}")
            }
        }.distinct()
        return result.copy(
            scene = SceneType.RIDE_HAILING.id,
            intent = decision.intentId,
            callNature = when (decision.intentId) {
                "driver_delay", "urge_passenger", "driver_arrived", "trip_exception" -> CallNature.NOTIFICATION
                else -> CallNature.SERVICE
            },
            confidence = maxOf(result.confidence, RIDE_POLICY_CONFIDENCE),
            sceneMargin = maxOf(result.sceneMargin, RIDE_POLICY_CONFIDENCE),
            matchedEvidence = (result.matchedEvidence + decision.evidence).distinct(),
            rejectedEvidence = rejected,
            shouldClarify = false,
            clarificationPrompt = null,
            sceneCandidates = listOf(SceneType.RIDE_HAILING.id),
        )
    }

    private fun applyCustomerServiceIntentPriority(
        result: RuleClassificationResult,
        normalizedText: String,
        allowedIntentIds: Set<String>,
    ): RuleClassificationResult {
        if (result.scene != SceneType.CUSTOMER_SERVICE.id) return result
        if (REFUND_NOTICE !in allowedIntentIds && allowedIntentIds.isNotEmpty()) return result
        if (result.intent == REFUND_NOTICE) return result
        if (!REFUND_COMPLETION_REGEX.containsMatchIn(normalizedText)) return result
        if (REFUND_ACTIVE_PROGRESS_REGEX.containsMatchIn(normalizedText)) return result
        return result.copy(
            intent = REFUND_NOTICE,
            callNature = CallNature.NOTIFICATION,
            matchedEvidence = (result.matchedEvidence + "customer_service:intent_priority:refund_completion").distinct(),
            rejectedEvidence = (
                result.rejectedEvidence +
                    "customer_service:intent_priority:rejected:${result.intent}"
            ).distinct(),
        )
    }

    private fun correctRealEstateContext(
        normalized: String,
        enabledScenes: Set<SceneType>,
    ): ContextualTextCorrection {
        if (SceneType.REAL_ESTATE !in enabledScenes) {
            return ContextualTextCorrection(normalized)
        }

        var corrected = normalized
        val appliedTerms = mutableListOf<String>()
        fun replaceWhen(source: String, target: String, condition: Boolean) {
            if (condition && corrected.contains(source)) {
                corrected = corrected.replace(source, target)
                appliedTerms += "$source->$target"
            }
        }

        replaceWhen(
            source = "挂牌假",
            target = "挂牌价",
            condition = corrected.contains("房东") &&
                (corrected.contains("再谈") || corrected.contains("基础") || corrected.contains("价格")),
        )
        replaceWhen(
            source = "挤压",
            target = "解押",
            condition = corrected.contains("剩余本金") || corrected.contains("出售") && corrected.contains("时间"),
        )
        replaceWhen(
            source = "防凌",
            target = "房龄",
            condition = corrected.contains("电梯") || corrected.contains("限制"),
        )
        replaceWhen(
            source = "房原",
            target = "房源",
            condition = corrected.contains("新") || corrected.contains("一套") || corrected.contains("筛选"),
        )
        return ContextualTextCorrection(corrected, appliedTerms.distinct())
    }

    private fun applyDeliveryIntentPriority(
        result: RuleClassificationResult,
        normalizedText: String,
        resolvedScene: SceneType?,
        allowedIntentIds: Set<String>,
    ): RuleClassificationResult {
        if (resolvedScene != SceneType.DELIVERY) return result

        val issueType = result.extractedSlots["issueType"]
        val delayedEvidence = delayedEvidence(normalizedText, result)
        val decision = when {
            issueType in DELIVERY_ITEM_ISSUES -> DeliveryIntentDecision(
                DELIVERY_ITEM_ISSUE,
                "issue_entity",
                CallNature.SERVICE,
            )
            containsNonNegatedMatch(normalizedText, DELIVERY_ACCESS_BLOCKED_REGEX) -> DeliveryIntentDecision(
                DELIVERY_ACCESS_BLOCKED,
                "access_blocked",
                CallNature.SERVICE,
            )
            containsNonNegatedMatch(normalizedText, DELIVERY_EXPLICIT_DELAY_REGEX) -> DeliveryIntentDecision(
                DELIVERY_DELAYED,
                "delay_issue",
                CallNature.NOTIFICATION,
            )
            delayedEvidence.size >= MINIMUM_DELAYED_EVIDENCE_COUNT -> DeliveryIntentDecision(
                DELIVERY_DELAYED,
                "combined_${delayedEvidence.joinToString("_")}",
                CallNature.NOTIFICATION,
            )
            issueType == DELIVERY_DELAY_ISSUE -> DeliveryIntentDecision(
                DELIVERY_DELAYED,
                "delay_issue",
                CallNature.NOTIFICATION,
            )
            containsNonNegatedMatch(normalizedText, DELIVERY_PLACED_REGEX) -> DeliveryIntentDecision(
                DELIVERY_PLACED,
                "placed",
                CallNature.NOTIFICATION,
            )
            containsNonNegatedMatch(normalizedText, DELIVERY_LOCATION_QUERY_REGEX) -> DeliveryIntentDecision(
                DELIVERY_LOCATION_QUERY,
                "location_query",
                CallNature.SERVICE,
            )
            containsNonNegatedMatch(normalizedText, DELIVERY_ARRIVED_REGEX) -> DeliveryIntentDecision(
                DELIVERY_ARRIVED,
                "arrived",
                CallNature.NOTIFICATION,
            )
            else -> null
        }

        val prioritized = if (
            decision != null &&
            (allowedIntentIds.isEmpty() || decision.intentId in allowedIntentIds)
        ) {
            val priorityEvidence = "delivery:intent_priority:${decision.ruleId}"
            val rejected = if (result.intent != null && result.intent != decision.intentId) {
                result.rejectedEvidence + "delivery:intent_priority:rejected:${result.intent}"
            } else {
                result.rejectedEvidence
            }
            result.copy(
                intent = decision.intentId,
                callNature = decision.callNature,
                confidence = maxOf(result.confidence, DELIVERY_PRIORITY_CONFIDENCE),
                matchedEvidence = (result.matchedEvidence + priorityEvidence).distinct(),
                rejectedEvidence = rejected.distinct(),
            )
        } else {
            result
        }

        if (
            prioritized.intent != DELIVERY_DELAYED ||
            !prioritized.extractedSlots["issueType"].isNullOrBlank()
        ) {
            return prioritized
        }
        return prioritized.copy(
            extractedSlots = prioritized.extractedSlots + ("issueType" to DELIVERY_DELAY_ISSUE),
            matchedEvidence = (
                prioritized.matchedEvidence +
                    "delivery:semantic_completion:issueType:$DELIVERY_DELAY_ISSUE"
                ).distinct(),
        )
    }

    /**
     * The ordinary clause-level negation rule, plus the case a template runs into that keywords do
     * not: a template may begin *inside* the negation that cancels it.
     *
     * 我不需要贷款 places 不需要 at index 1 and the template's 需要 at index 2, so looking only at the
     * text before the match sees 我 and reports no negation. A negation particle sitting immediately
     * before the evidence is a negation of it whether or not the two were tokenized together.
     */
    private fun isTemplateNegated(text: String, startIndex: Int): Boolean =
        isNegatedEvidence(text, startIndex) ||
            (startIndex > 0 && text[startIndex - 1] in NEGATION_PARTICLES)

    private fun containsNonNegatedMatch(text: String, regex: Regex): Boolean =
        regex.findAll(text).any { match -> !isNegatedEvidence(text, match.range.first) }

    private fun delayedEvidence(
        text: String,
        result: RuleClassificationResult,
    ): List<String> = buildList {
        if (containsNonNegatedMatch(text, DELIVERY_DELAY_CAUSE_REGEX)) add("cause")
        if (DELIVERY_NOT_ARRIVED_REGEX.containsMatchIn(text)) add("not_arrived")
        if (!result.extractedSlots["estimatedTime"].isNullOrBlank()) add("eta")
    }

    private suspend fun fuseSecondaryRecognition(
        primaryText: String,
        primary: RuleClassificationResult,
        evidence: SecondaryRecognitionEvidence,
        enabledScenes: Set<SceneType>,
        context: RuleClassificationContext,
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
    ): RuleClassificationResult {
        val primaryAccepted = primary.scene != null && !primary.shouldClarify
        val primaryScene = primary.scene?.let(SceneType::fromId)
            ?.takeIf { it != SceneType.UNCLASSIFIED }
        val selectedScene = selectSecondaryScene(primary, evidence, enabledScenes, primaryAccepted)
        if (selectedScene == null) {
            val rejectionReason = if (evidence.allowClassifiedSceneWithoutHotword) {
                "secondary:rejected:no_supported_scene_evidence"
            } else {
                "secondary:rejected:no_supported_hotword"
            }
            return primary.copy(
                rejectedEvidence = (primary.rejectedEvidence + rejectionReason).distinct(),
            )
        }
        val matchedPhrases = evidence.matchedHotwordsByScene[selectedScene.id].orEmpty()
        val secondaryContext = context.copy(
            lockedScene = context.lockedScene?.takeIf { it == selectedScene },
            secondaryRecognition = null,
        )
        val secondary = classifySingle(
            text = evidence.text,
            enabledScenes = setOf(selectedScene),
            context = secondaryContext,
            compiled = compiled,
            language = language,
            allowLowConfidenceClarification = false,
        )
        val secondaryAccepted = secondary.scene == selectedScene.id && !secondary.shouldClarify
        val minimumHotwordMatches = if (
            primaryScene != null
        ) {
            MINIMUM_PRIMARY_CONFIRMATION_HOTWORD_MATCHES
        } else {
            MINIMUM_SCENE_HOTWORD_MATCHES
        }
        val sceneSupportedByHotwords = matchedPhrases.size >= minimumHotwordMatches
        val classifiedWithoutHotword = evidence.allowClassifiedSceneWithoutHotword &&
            matchedPhrases.isEmpty() &&
            secondaryAccepted
        val primaryHasSpamSemanticEvidence = primary.matchedEvidence.any {
            it.startsWith("${SceneType.SPAM_RISK.id}:$MARKETING_PITCH:semantic_")
        }
        // When the primary saw no scene at all, whether the secondary may establish one depends on
        // what being wrong would cost -- see SCENES_ESTABLISHABLE_BY_SECONDARY_PASS.
        val promoteScene = (primaryScene != null || selectedScene in SCENES_ESTABLISHABLE_BY_SECONDARY_PASS) &&
            !primaryHasSpamSemanticEvidence &&
            !primaryAccepted &&
            secondaryAccepted &&
            (sceneSupportedByHotwords || classifiedWithoutHotword)
        val localCorrection = correctPrimaryLocationText(primaryText, matchedPhrases)
        val locallyCorrected = if (localCorrection.appliedTerms.isEmpty()) {
            primary
        } else {
            classifySingle(
                text = localCorrection.text,
                enabledScenes = setOf(selectedScene),
                context = secondaryContext,
                compiled = compiled,
                language = language,
            )
        }

        val acceptedSlots = linkedMapOf<String, String>()
        val fusionEvidence = mutableListOf<String>()
        val rejectedFusionEvidence = mutableListOf<String>()
        matchedPhrases.forEach { phrase -> fusionEvidence += "secondary:hotword:${selectedScene.id}:${canonical(phrase)}" }
        localCorrection.appliedTerms.forEach { term ->
            fusionEvidence += "secondary:text:local_correction:${canonical(term)}"
        }
        if (promoteScene) {
            fusionEvidence += "secondary:scene:accepted:${selectedScene.id}"
        } else if (!primaryAccepted) {
            // Most specific reason first: "too few hotwords" tells the reader which gate to move,
            // while "primary unclassified" only restates the situation the secondary pass handles.
            if (primaryHasSpamSemanticEvidence) {
                rejectedFusionEvidence += "secondary:scene:rejected:spam_semantic_evidence"
            } else if (secondaryAccepted && matchedPhrases.isNotEmpty() && !sceneSupportedByHotwords) {
                rejectedFusionEvidence +=
                    "secondary:scene:rejected:insufficient_hotwords:$minimumHotwordMatches"
            } else if (primaryScene == null) {
                rejectedFusionEvidence += "secondary:scene:rejected:primary_unclassified"
            } else {
                rejectedFusionEvidence += "secondary:scene:rejected:not_classified"
            }
        }
        if (evidence.textDifferenceRate > MAXIMUM_TEXT_DIFFERENCE_RATE) {
            rejectedFusionEvidence += "secondary:text:high_difference"
        }

        val primaryLocation = primary.extractedSlots["location"] ?: context.existingSlots["location"]
        val correctedLocation = locallyCorrected.extractedSlots["location"]
        if (
            localCorrection.appliedTerms.isNotEmpty() &&
            !correctedLocation.isNullOrBlank() &&
            canonicalEntity(correctedLocation) != canonicalEntity(primaryLocation.orEmpty())
        ) {
            val rejectionReasons = locationRejectionReasons(
                primaryText = primaryText,
                primaryLocation = primaryLocation,
                candidateLocation = correctedLocation,
                requiresAlignment = false,
            )
            if (rejectionReasons.isEmpty()) {
                acceptedSlots["location"] = correctedLocation
                fusionEvidence += "secondary:entity:accepted:location"
            } else {
                rejectionReasons.forEach { reason ->
                    rejectedFusionEvidence += "secondary:entity:rejected:location:$reason"
                }
            }
        }

        secondary.extractedSlots.forEach { (key, value) ->
            if (key !in FORMAL_ENTITY_KEYS || value.isBlank()) return@forEach
            if (key == "location") {
                if ("location" in acceptedSlots) return@forEach
                val rejectionReasons = locationRejectionReasons(
                    primaryText = primaryText,
                    primaryLocation = primaryLocation,
                    candidateLocation = value,
                    requiresAlignment = !primaryLocation.isNullOrBlank(),
                )
                val generalReason = secondaryEntityRejectionReason(
                    key = key,
                    value = value,
                    primaryText = primaryText,
                    primary = primary,
                    existingSlots = context.existingSlots,
                    matchedPhrases = matchedPhrases,
                    secondaryText = evidence.text,
                    triggerReasons = evidence.triggerReasons,
                )
                val reasons = buildList {
                    addAll(rejectionReasons)
                    if (generalReason != null && primaryLocation.isNullOrBlank()) add(generalReason)
                }.distinct()
                if (reasons.isEmpty()) {
                    acceptedSlots[key] = value
                    fusionEvidence += "secondary:entity:accepted:$key"
                } else {
                    reasons.forEach { reason ->
                        rejectedFusionEvidence += "secondary:entity:rejected:$key:$reason"
                    }
                }
                return@forEach
            }
            val rejectionReason = secondaryEntityRejectionReason(
                key = key,
                value = value,
                primaryText = primaryText,
                primary = primary,
                existingSlots = context.existingSlots,
                matchedPhrases = matchedPhrases,
                secondaryText = evidence.text,
                triggerReasons = evidence.triggerReasons,
            )
            if (rejectionReason == null) {
                val primaryValue = primary.extractedSlots[key] ?: context.existingSlots[key]
                if (primaryValue.isNullOrBlank() || canonical(primaryValue) != canonical(value)) {
                    acceptedSlots[key] = value
                    fusionEvidence += "secondary:entity:accepted:$key"
                }
            } else {
                rejectedFusionEvidence += "secondary:entity:rejected:$key:$rejectionReason"
            }
        }

        val secondaryIntentAccepted = !promoteScene &&
            primary.intent == null &&
            secondaryAccepted &&
            secondary.intent != null &&
            acceptedSlots.isNotEmpty()
        val correctedIntentAccepted = !promoteScene &&
            primary.intent == null &&
            localCorrection.appliedTerms.isNotEmpty() &&
            locallyCorrected.intent != null &&
            "location" in acceptedSlots
        val acceptedIntent = when {
            correctedIntentAccepted -> locallyCorrected.intent
            secondaryIntentAccepted -> secondary.intent
            else -> null
        }
        if (acceptedIntent != null) {
            fusionEvidence += "secondary:intent:accepted:$acceptedIntent"
        }
        val promotedWithoutHotword = promoteScene &&
            evidence.allowClassifiedSceneWithoutHotword &&
            matchedPhrases.isEmpty()
        val promotedIntent = if (promotedWithoutHotword) {
            primary.intent ?: secondary.intent
        } else {
            secondary.intent
        }
        val promotedCallNature = if (promotedWithoutHotword && primary.intent != null) {
            primary.callNature
        } else {
            secondary.callNature
        }

        val base = if (promoteScene) {
            primary.copy(
                scene = selectedScene.id,
                intent = promotedIntent,
                callNature = promotedCallNature,
                confidence = maxOf(primary.confidence, secondary.confidence),
                sceneMargin = maxOf(primary.sceneMargin, secondary.sceneMargin),
                shouldClarify = false,
                clarificationPrompt = null,
                sceneCandidates = listOf(selectedScene.id),
            )
        } else if (acceptedIntent != null) {
            val intentSource = if (correctedIntentAccepted) locallyCorrected else secondary
            primary.copy(
                intent = acceptedIntent,
                callNature = intentSource.callNature,
                confidence = maxOf(primary.confidence, intentSource.confidence),
            )
        } else {
            primary
        }
        val fused = base.copy(
            matchedEvidence = (base.matchedEvidence + fusionEvidence).distinct(),
            rejectedEvidence = (base.rejectedEvidence + rejectedFusionEvidence).distinct(),
            extractedSlots = base.extractedSlots + acceptedSlots,
        )
        return if (
            fused.intent == DELIVERY_DELAYED &&
            fused.extractedSlots["issueType"].isNullOrBlank()
        ) {
            fused.copy(
                extractedSlots = fused.extractedSlots + ("issueType" to DELIVERY_DELAY_ISSUE),
                matchedEvidence = (
                    fused.matchedEvidence +
                        "delivery:semantic_completion:issueType:$DELIVERY_DELAY_ISSUE"
                    ).distinct(),
            )
        } else {
            fused
        }
    }

    private fun selectSecondaryScene(
        primary: RuleClassificationResult,
        evidence: SecondaryRecognitionEvidence,
        enabledScenes: Set<SceneType>,
        primaryAccepted: Boolean,
    ): SceneType? {
        val primaryScene = primary.scene?.let(SceneType::fromId)
            ?.takeIf { it != SceneType.UNCLASSIFIED }
        if (primaryScene != null) {
            if (evidence.matchedHotwordsByScene[primaryScene.id].orEmpty().isNotEmpty()) return primaryScene
            // No hotword backs the primary scene, but a confident secondary classification of the
            // same scene may still confirm it -- that is what allowClassifiedSceneWithoutHotword is
            // for, and returning null here would make it unreachable whenever the primary saw a
            // scene. Restricted to the primary's own scene, so other evidence can never switch it.
            return primaryScene.takeIf { scene ->
                !primaryAccepted &&
                    evidence.allowClassifiedSceneWithoutHotword &&
                    evidence.classifiedScene == scene &&
                    evidence.classifiedSceneQualifies(scene, enabledScenes)
            }
        }
        val preferredOrder = buildList {
            addAll(primary.sceneCandidates)
            addAll(evidence.sceneHints.map(SceneType::id))
        }.distinct()
        val hotwordScene = evidence.matchedHotwordsByScene.entries
            .asSequence()
            .map { entry -> SceneType.fromId(entry.key) to entry.value.size }
            .filter { (scene, count) -> scene in enabledScenes && scene != SceneType.UNCLASSIFIED && count > 0 }
            .sortedWith(
                compareByDescending<Pair<SceneType, Int>> { it.second }
                    .thenBy { (scene, _) -> preferredOrder.indexOf(scene.id).takeIf { it >= 0 } ?: Int.MAX_VALUE },
            )
            .firstOrNull()
            ?.first
        if (hotwordScene != null) return hotwordScene
        if (primaryAccepted || !evidence.allowClassifiedSceneWithoutHotword) return null

        val classifiedScene = evidence.classifiedScene ?: return null
        return classifiedScene.takeIf { scene -> evidence.classifiedSceneQualifies(scene, enabledScenes) }
    }

    /** Whether the secondary pass's own classification is strong enough to name a scene. */
    private fun SecondaryRecognitionEvidence.classifiedSceneQualifies(
        scene: SceneType,
        enabledScenes: Set<SceneType>,
    ): Boolean =
        scene != SceneType.UNCLASSIFIED &&
            scene in enabledScenes &&
            scene in sceneHints &&
            !classificationShouldClarify &&
            classificationConfidence >= MINIMUM_SECONDARY_SCENE_CONFIDENCE &&
            classificationSceneMargin >= MINIMUM_SECONDARY_SCENE_MARGIN

    private fun secondaryEntityRejectionReason(
        key: String,
        value: String,
        primaryText: String,
        primary: RuleClassificationResult,
        existingSlots: Map<String, String>,
        matchedPhrases: List<String>,
        secondaryText: String,
        triggerReasons: List<String>,
    ): String? {
        if (primary.rejectedEvidence.any { it.startsWith("slot:$key:conflict") }) return "primary_conflict"
        val entityQualityRetry = key == "location" && triggerReasons.any { it in LOCATION_QUALITY_RETRY_REASONS }
        if (!hasEntityCue(primaryText, key) && !entityQualityRetry) return "primary_cue_missing"
        if (!isEntitySupportedByHotword(key, value, matchedPhrases, secondaryText)) return "hotword_not_supporting_value"
        val primaryValue = primary.extractedSlots[key] ?: existingSlots[key]
        if (
            !primaryValue.isNullOrBlank() &&
            !areEntityValuesCompatible(key, primaryValue, value) &&
            !entityQualityRetry
        ) {
            return "conflicts_with_primary"
        }
        return null
    }

    private fun hasEntityCue(text: String, key: String): Boolean {
        val markers = ENTITY_CUES[key].orEmpty()
        return markers.any(text::contains)
    }

    private fun isEntitySupportedByHotword(
        key: String,
        value: String,
        matchedPhrases: List<String>,
        secondaryText: String,
    ): Boolean {
        val canonicalValue = canonicalEntity(value)
        if (canonicalValue.isBlank() || !canonicalEntity(secondaryText).contains(canonicalValue)) return false
        return matchedPhrases.any { phrase ->
            val canonicalPhrase = canonicalEntity(phrase)
            canonicalPhrase.contains(canonicalValue) ||
                canonicalValue.contains(canonicalPhrase) ||
                (key in CUE_SUPPORTED_ENTITY_KEYS && HOTWORD_ENTITY_CUES[key].orEmpty().any(canonicalPhrase::contains))
        }
    }

    private fun locationRejectionReasons(
        primaryText: String,
        primaryLocation: String?,
        candidateLocation: String,
        requiresAlignment: Boolean,
    ): List<String> = buildList {
        if (introducesUnverifiedDirection(primaryText, candidateLocation)) {
            add("introduces_unverified_direction")
        }
        if (!primaryLocation.isNullOrBlank() && losesPrimaryHierarchy(primaryLocation, candidateLocation)) {
            add("loses_primary_hierarchy")
        }
        if (requiresAlignment) add("insufficient_alignment_confidence")
    }.distinct()

    private fun introducesUnverifiedDirection(primaryText: String, candidateLocation: String): Boolean {
        val primaryDirections = DIRECTION_REGEX.findAll(canonical(primaryText)).map { it.value }.toSet()
        val candidateDirections = DIRECTION_REGEX.findAll(canonical(candidateLocation)).map { it.value }.toSet()
        return candidateDirections.any { it !in primaryDirections }
    }

    private fun losesPrimaryHierarchy(primaryLocation: String, candidateLocation: String): Boolean {
        val primaryValue = canonicalEntity(primaryLocation)
        val candidateValue = canonicalEntity(candidateLocation)
        if (primaryValue.isBlank() || candidateValue.isBlank()) return false
        return candidateValue.length < primaryValue.length
    }

    private fun correctPrimaryLocationText(
        primaryText: String,
        matchedPhrases: List<String>,
    ): LocalTextCorrection {
        var correctedText = primaryText
        val appliedTerms = mutableListOf<String>()
        val correctionTerms = matchedPhrases
            .asSequence()
            .map(::canonical)
            .flatMap { phrase ->
                LOCATION_CORRECTION_TERMS.asSequence().filter(phrase::contains)
            }
            .distinct()
            .sortedByDescending(String::length)
            .toList()
        correctionTerms.forEach { term ->
            val alignment = bestLocalAlignment(correctedText, term) ?: return@forEach
            if (alignment.editDistance == 0) return@forEach
            val confidence = 1.0 -
                alignment.editDistance.toDouble() / maxOf(term.length, alignment.canonicalLength).toDouble()
            if (confidence < MINIMUM_LOCAL_ALIGNMENT_CONFIDENCE) return@forEach
            if (introducesUnverifiedDirection(correctedText, term)) return@forEach
            correctedText = correctedText.replaceRange(alignment.sourceRange, term)
            appliedTerms += term
        }
        return LocalTextCorrection(correctedText, appliedTerms.distinct())
    }

    private fun bestLocalAlignment(text: String, target: String): LocalAlignment? {
        val source = canonicalWithSourceIndices(text)
        if (source.text.isBlank() || target.isBlank() || source.text.length < target.length) return null
        var best: LocalAlignment? = null
        for (start in 0..source.text.length - target.length) {
            var differenceCount = 0
            for (offset in target.indices) {
                if (source.text[start + offset] != target[offset]) {
                    differenceCount += 1
                }
            }
            val endExclusive = start + target.length
            val candidate = LocalAlignment(
                sourceRange = source.sourceIndices[start]..source.sourceIndices[endExclusive - 1],
                canonicalLength = target.length,
                editDistance = differenceCount,
            )
            if (best == null || candidate.editDistance < best.editDistance) {
                best = candidate
            }
        }
        return best
    }

    private fun canonicalWithSourceIndices(text: String): CanonicalSourceText {
        val normalized = StringBuilder(text.length)
        val sourceIndices = mutableListOf<Int>()
        text.forEachIndexed { index, character ->
            if (!character.isWhitespace() && character !in ALIGNMENT_IGNORED_CHARACTERS) {
                normalized.append(character.lowercaseChar())
                sourceIndices += index
            }
        }
        return CanonicalSourceText(normalized.toString(), sourceIndices)
    }

    private fun areEntityValuesCompatible(key: String, first: String, second: String): Boolean {
        val firstValue = canonicalEntity(first)
        val secondValue = canonicalEntity(second)
        if (firstValue == secondValue || firstValue.contains(secondValue) || secondValue.contains(firstValue)) return true
        if (key != "location") return false
        val firstGate = GATE_REGEX.find(firstValue)?.value
        val secondGate = GATE_REGEX.find(secondValue)?.value
        return firstGate != null && firstGate == secondGate
    }

    private fun canonical(text: String): String = text.replace(CANONICAL_IGNORED_REGEX, "").lowercase()

    private fun canonicalEntity(text: String): String = canonical(text).replace(ENTITY_PARTICLE_REGEX, "")

    private suspend fun compiledRules(): CompiledRuleSet? {
        cached?.let { return it }
        if (loadFailed) return null
        return loadMutex.withLock {
            cached?.let { return@withLock it }
            if (loadFailed) return@withLock null
            when (val loaded = provider.load()) {
                is AppResult.Success -> runCatching {
                    validator.validate(loaded.value)
                    CompiledRuleSet.compile(loaded.value)
                }.fold(
                    onSuccess = { it.also { value -> cached = value } },
                    onFailure = { loadFailed = true; null },
                )
                is AppResult.Failure -> {
                    loadFailed = true
                    null
                }
            }
        }
    }

    private fun classifyNormalized(
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
        normalized: String,
        enabledScenes: Set<SceneType>,
        context: RuleClassificationContext,
    ): RuleClassificationResult {
        val enabledIds = enabledScenes.asSequence()
            .filter { it != SceneType.UNCLASSIFIED }
            .map { it.id }
            .toSet()
        val lockedScene = context.lockedScene?.takeIf { it != SceneType.UNCLASSIFIED }
        val explicitCorrection = lockedScene != null && isExplicitCorrection(normalized, language.config)
        val rawRisk = compiled.riskDetector.detect(normalized, language.languageTag)

        val base = if (lockedScene != null && !explicitCorrection) {
            classifyInsideLockedScene(compiled, language, normalized, enabledIds, context, lockedScene)
        } else {
            classifyAcrossScenes(
                compiled = compiled,
                language = language,
                normalized = normalized,
                enabledIds = enabledIds,
                context = context,
                correctionFrom = lockedScene?.takeIf { explicitCorrection },
            )
        }
        val risk = effectiveRisk(rawRisk, base, compiled.source.safety)
        val result = applyRisk(base, risk, enabledIds, compiled.source.safety)
        if (!debugTraceEnabled) return result
        return result.copy(
            debugTrace = buildDebugTrace(
                compiled = compiled,
                language = language,
                normalized = normalized,
                enabledIds = enabledIds,
                context = context,
                base = base,
                rawRisk = rawRisk,
                risk = risk,
                result = result,
            ),
        )
    }

    private fun classifyInsideLockedScene(
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
        normalized: String,
        enabledIds: Set<String>,
        context: RuleClassificationContext,
        lockedScene: SceneType,
    ): RuleClassificationResult {
        val scenario = compiled.scenarios.firstOrNull { it.sceneId == lockedScene.id }
            ?: return RuleClassificationResult(scene = lockedScene.id, intent = null)
        val candidates = scoreScenario(
            scenario = scenario,
            language = language,
            normalized = normalized,
            context = context,
            allowedIntentIds = context.allowedIntentIds,
            useContextBoost = true,
            correctionTarget = false,
        )
        val foreignCoreScenes = compiled.scenarios
            .asSequence()
            .filter { it.sceneId != lockedScene.id && it.sceneId in enabledIds }
            .mapNotNull { foreign ->
                scoreScenario(
                    scenario = foreign,
                    language = language,
                    normalized = normalized,
                    context = context,
                    allowedIntentIds = emptySet(),
                    useContextBoost = false,
                    correctionTarget = false,
                ).filter { it.sceneDefining && it.coreEvidence }.maxByOrNull { it.score }?.let { foreign.sceneId }
            }
            .toList()

        val best = candidates.maxWithOrNull(SCORED_INTENT_ORDER)
        val adjustedScore = (best?.score ?: 0f) + if (foreignCoreScenes.isNotEmpty()) {
            compiled.source.classification.weights.conflictingSceneCore
        } else {
            0f
        }
        val accepted = best?.takeIf {
            it.hasPositiveEvidence && adjustedScore >= compiled.source.classification.thresholds.minimumIntentScore
        }
        return RuleClassificationResult(
            scene = lockedScene.id,
            intent = accepted?.intentId,
            callNature = accepted?.callNature ?: CallNature.UNKNOWN,
            confidence = adjustedScore.coerceIn(0f, 1f),
            sceneMargin = adjustedScore.coerceAtLeast(0f),
            matchedEvidence = accepted?.matchedEvidence.orEmpty(),
            rejectedEvidence = buildList {
                addAll(best?.rejectedEvidence.orEmpty())
                foreignCoreScenes.forEach { add("scene_lock:blocked_switch:$it") }
                if (best != null && accepted == null) add("intent:below_threshold")
            },
            shouldClarify = accepted == null,
            clarificationPrompt = if (accepted == null) {
                compiled.source.fallback.localizedFor(language.languageTag).purposeOrRetryPrompt
            } else {
                null
            },
            sceneCandidates = listOf(lockedScene.id),
        )
    }

    private fun classifyAcrossScenes(
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
        normalized: String,
        enabledIds: Set<String>,
        context: RuleClassificationContext,
        correctionFrom: SceneType?,
    ): RuleClassificationResult {
        val scenarios = compiled.scenarios.filter { it.sceneId in enabledIds }
        if (scenarios.isEmpty()) return RuleClassificationResult(scene = null, intent = null)

        if (scenarios.size == 1 && correctionFrom == null) {
            val scenario = scenarios.single()
            val best = scoreScenario(
                scenario,
                language,
                normalized,
                context,
                context.allowedIntentIds,
                useContextBoost = false,
                correctionTarget = false,
            ).maxWithOrNull(SCORED_INTENT_ORDER)
            // Anchors decide the domain here too. Restricting the enabled scenes to one -- which the
            // secondary pass does -- must not silently disable them, or the same utterance is read
            // differently depending on how many scenes the caller happened to have switched on.
            val singleSceneAnchor = scenario.anchorsFor(language.languageTag)
                .firstOrNull { anchorTerm ->
                    val index = normalized.indexOf(anchorTerm)
                    index >= 0 && !isNegatedEvidence(normalized, index)
                }
            val anchored = if (singleSceneAnchor == null || best == null) {
                best
            } else {
                best.copy(
                    score = maxOf(best.score, compiled.source.classification.weights.sceneAnchor),
                    hasPositiveEvidence = true,
                    matchedEvidence = best.matchedEvidence + "${scenario.sceneId}:scene_anchor:$singleSceneAnchor",
                )
            } ?: best
            val accepted = anchored?.takeIf {
                it.hasPositiveEvidence && it.score >= compiled.source.classification.thresholds.minimumIntentScore
            }
            if (accepted == null && best != null) {
                val clarificationThreshold = clarificationThreshold(compiled, scenario)
                val entityOnlyClarification = isEntityOnlyEvidence(best)
                val clarificationReady = best.hasPositiveEvidence &&
                    best.score + SCORE_COMPARISON_EPSILON >= clarificationThreshold
                val noEvidence = !best.hasPositiveEvidence
                return RuleClassificationResult(
                    scene = scenario.sceneId.takeIf { clarificationReady || entityOnlyClarification },
                    intent = best.intentId.takeIf { clarificationReady },
                    callNature = best.callNature.takeIf { clarificationReady } ?: CallNature.UNKNOWN,
                    confidence = best.score.coerceIn(0f, 1f),
                    sceneMargin = best.score.coerceAtLeast(0f),
                    matchedEvidence = best.matchedEvidence,
                    rejectedEvidence = best.rejectedEvidence + if (clarificationReady || entityOnlyClarification || noEvidence) {
                        listOf("intent:below_threshold:clarification")
                    } else {
                        listOf("intent:below_threshold:fallback")
                    },
                    shouldClarify = clarificationReady || entityOnlyClarification || noEvidence,
                    clarificationPrompt = if (clarificationReady || entityOnlyClarification) {
                        clarificationPrompt(
                            compiled,
                            language,
                            SceneScore(scenario, best, listOf(best)),
                            null,
                        )
                    } else if (noEvidence) {
                        compiled.source.fallback.localizedFor(language.languageTag).purposeOrRetryPrompt
                    } else {
                        null
                    },
                    sceneCandidates = listOf(scenario.sceneId),
                )
            }
            return RuleClassificationResult(
                scene = accepted?.let { scenario.sceneId },
                intent = accepted?.intentId,
                callNature = accepted?.callNature ?: CallNature.UNKNOWN,
                confidence = (best?.score ?: 0f).coerceIn(0f, 1f),
                sceneMargin = (best?.score ?: 0f).coerceAtLeast(0f),
                matchedEvidence = accepted?.matchedEvidence.orEmpty(),
                rejectedEvidence = best?.rejectedEvidence.orEmpty() + if (best != null && accepted == null) {
                    listOf("intent:below_threshold")
                } else {
                    emptyList()
                },
                sceneCandidates = listOf(scenario.sceneId),
            )
        }

        val anchorDomainPriority = compiled.source.classification.thresholds.anchorDomainPriority
        val sceneScores = scenarios.mapNotNull { scenario ->
            val candidates = scoreScenario(
                scenario = scenario,
                language = language,
                normalized = normalized,
                context = context,
                allowedIntentIds = emptySet(),
                useContextBoost = false,
                correctionTarget = correctionFrom != null && scenario.sceneId != correctionFrom.id,
            )
            val defining = candidates.filter { it.sceneDefining && it.hasPositiveEvidence }
                .maxWithOrNull(SCORED_INTENT_ORDER)
            // An anchor names the domain by itself, so it can carry a scene that no scene-defining
            // intent reached. The intent it travels with may be non-defining, or absent entirely --
            // "which domain" and "what is being asked" are separate questions, and answering only
            // the first is a usable turn: the engine still has a scene to ask its next question in.
            val anchor = scenario.anchorsFor(language.languageTag)
                .firstOrNull { anchorTerm ->
                    val index = normalized.indexOf(anchorTerm)
                    index >= 0 && !isNegatedEvidence(normalized, index)
                }
            when {
                anchor == null -> defining?.let { SceneScore(scenario, it, candidates, anchored = false) }
                else -> {
                    val carrier = defining
                        ?: candidates.filter { it.hasPositiveEvidence }.maxWithOrNull(SCORED_INTENT_ORDER)
                        ?: candidates.maxWithOrNull(SCORED_INTENT_ORDER)
                    val anchorWeight = compiled.source.classification.weights.sceneAnchor
                    carrier?.let { intent ->
                        SceneScore(
                            scenario,
                            intent.copy(
                                score = maxOf(intent.score, anchorWeight),
                                sceneDefining = true,
                                hasPositiveEvidence = true,
                                matchedEvidence = intent.matchedEvidence + "${scenario.sceneId}:scene_anchor:$anchor",
                            ),
                            candidates,
                            anchored = true,
                        )
                    }
                }
            }
            // Domain evidence outranks intent evidence, because an anchor is exclusive to its scene
            // by a constraint the loader enforces: a scene that produced one has answered "which
            // industry is this call from", and a scene that produced none has not, however high its
            // intent wording happened to score. Ranking on the raw score alone let any sufficiently
            // broad pattern outrank an anchor -- a ride-hailing arrival pattern was taking delivery
            // turns that contained 配送员, 0.60 against 0.45.
            //
            // It outranks by a large bonus rather than absolutely. As a tier it could not be
            // overturned at any score, and one anchor appearing inside another domain's narrative
            // was enough to decide the turn: 您投诉的问题涉及商家配送员…… went to delivery at 0.67
            // over customer_service at 1.51. See RuleThresholds.anchorDomainPriority for what bounds
            // the value.
        }.sortedWith(
            compareByDescending<SceneScore> { it.domainRankingScore(anchorDomainPriority) }
                .thenByDescending { it.anchored }
                .thenBy { it.scenario.order },
        )

        val semanticSpamPriority = sceneScores.firstOrNull { score ->
            score.scenario.sceneId == SceneType.SPAM_RISK.id &&
                score.definingIntent.score >= compiled.source.classification.spamRiskSemantics.natureThreshold &&
                score.definingIntent.matchedEvidence.any { evidence ->
                    // Any spam intent may now carry the priority, not marketing_pitch alone.
                    evidence.startsWith("spam_risk:") && evidence.endsWith(":semantic_priority")
                }
        }
        val rankedSceneScores = if (semanticSpamPriority == null) {
            sceneScores
        } else {
            listOf(semanticSpamPriority) + sceneScores.filter { it.scenario.sceneId != SceneType.SPAM_RISK.id }
        }
        val top = rankedSceneScores.firstOrNull()
        val second = rankedSceneScores.getOrNull(1)
        val semanticSpamSelected = semanticSpamPriority != null && top === semanticSpamPriority
        val topScore = top?.definingIntent?.score ?: 0f
        val secondScore = second?.definingIntent?.score ?: 0f
        val thresholds = compiled.source.classification.thresholds
        val margin = if (top != null && second != null && top.anchored && !second.anchored) {
            // The runner-up scored higher but produced no domain evidence, so the raw difference is
            // negative and would read as a tie worth clarifying. There is nothing to clarify: one
            // scene owns a term the other cannot use. Report a margin that says so.
            maxOf(topScore - secondScore, thresholds.clarificationMargin + SCORE_COMPARISON_EPSILON)
        } else {
            (topScore - secondScore).coerceAtLeast(0f)
        }
        if (top == null) {
            return RuleClassificationResult(
                scene = null,
                intent = null,
                confidence = topScore.coerceIn(0f, 1f),
                sceneMargin = margin,
                rejectedEvidence = listOf("scene:below_threshold", "scene:below_threshold:clarification"),
                shouldClarify = true,
                clarificationPrompt = compiled.source.fallback.localizedFor(language.languageTag).purposeOrRetryPrompt,
                sceneCandidates = rankedSceneScores.take(2).map { it.scenario.sceneId },
            )
        }

        // A dead-heat is resolved by asking -- unless the fraud layer has already decided, in which
        // case there is nothing to ask about. 我是购物平台理赔专员，您账户被错误开通了代理商服务，
        // 会持续扣款 scores 0.85 on impersonation plus loss-threat and ties with insurance_finance
        // on 理赔; this branch runs after the semantic promotion and did not consult it, so the
        // scam was answered with 请您再说明来电目的 instead of ending the call.
        if (
            !semanticSpamSelected &&
            second != null &&
            margin < SCORE_COMPARISON_EPSILON &&
            topScore + SCORE_COMPARISON_EPSILON >= clarificationThreshold(compiled, top.scenario)
        ) {
            val entityPreferred = listOf(top, second).firstOrNull { candidate ->
                candidate.definingIntent.evidenceTrace.any { it.type == "entity_derived" }
            } ?: top
            return RuleClassificationResult(
                scene = entityPreferred.scenario.sceneId,
                intent = entityPreferred.definingIntent.intentId,
                callNature = entityPreferred.definingIntent.callNature,
                confidence = entityPreferred.definingIntent.score.coerceIn(0f, 1f),
                sceneMargin = margin,
                matchedEvidence = entityPreferred.definingIntent.matchedEvidence,
                rejectedEvidence = entityPreferred.definingIntent.rejectedEvidence +
                    "scene:ambiguous_epsilon_tie",
                shouldClarify = true,
                clarificationPrompt = clarificationPrompt(compiled, language, SceneScore(
                    entityPreferred.scenario,
                    entityPreferred.definingIntent,
                    entityPreferred.allIntents,
                ), rankedSceneScores.getOrNull(1)),
                sceneCandidates = rankedSceneScores.take(2).map { it.scenario.sceneId },
            )
        }

        if (topScore < thresholds.minimumSceneScore) {
            val intentReady = topScore >= thresholds.minimumIntentScore && top.definingIntent.hasPositiveEvidence
            if (intentReady) {
                return RuleClassificationResult(
                    scene = top.scenario.sceneId,
                    intent = top.definingIntent.intentId,
                    callNature = top.definingIntent.callNature,
                    confidence = topScore.coerceIn(0f, 1f),
                    sceneMargin = margin,
                    matchedEvidence = top.definingIntent.matchedEvidence + "scene:derived_from_intent",
                    rejectedEvidence = top.definingIntent.rejectedEvidence + "scene:below_threshold_but_intent_ready",
                    sceneCandidates = rankedSceneScores.take(2).map { it.scenario.sceneId },
                )
            }

            val entityOnlyClarification = isEntityOnlyEvidence(top.definingIntent)
            val clarificationReady = top.definingIntent.hasPositiveEvidence &&
                topScore + SCORE_COMPARISON_EPSILON >= clarificationThreshold(compiled, top.scenario)
            return RuleClassificationResult(
                scene = top.scenario.sceneId.takeIf { clarificationReady || entityOnlyClarification },
                intent = top.definingIntent.intentId.takeIf { clarificationReady },
                callNature = top.definingIntent.callNature.takeIf { clarificationReady } ?: CallNature.UNKNOWN,
                confidence = topScore.coerceIn(0f, 1f),
                sceneMargin = margin,
                matchedEvidence = top.definingIntent.matchedEvidence,
                rejectedEvidence = top.definingIntent.rejectedEvidence +
                    if (clarificationReady || entityOnlyClarification) {
                        listOf("scene:below_threshold:clarification")
                    } else {
                        listOf("scene:below_threshold:fallback")
                    },
                // Below the scene threshold there is nothing to act on either way, so the turn
                // always ends in a question. When there is enough to name a candidate the question
                // is about that candidate; when there is not -- a lone auxiliary keyword such as
                // "到了" -- the scene stays null and the question is the generic one. Reporting
                // "no scene, and nothing to ask" would leave the caller with silence.
                shouldClarify = true,
                clarificationPrompt = if (clarificationReady || entityOnlyClarification) {
                    clarificationPrompt(compiled, language, top, second)
                } else {
                    compiled.source.fallback.localizedFor(language.languageTag).purposeOrRetryPrompt
                },
                sceneCandidates = rankedSceneScores.take(2).map { it.scenario.sceneId },
            )
        }

        val switchTooWeak = correctionFrom != null && top.scenario.sceneId != correctionFrom.id && topScore < thresholds.sceneSwitchScore
        val ambiguous = !semanticSpamSelected && second != null && margin < thresholds.clarificationMargin
        if (ambiguous || switchTooWeak) {
            val candidates = rankedSceneScores.take(2).map { it.scenario.sceneId }
            return RuleClassificationResult(
                scene = top.scenario.sceneId,
                intent = top.definingIntent.intentId,
                callNature = top.definingIntent.callNature,
                confidence = topScore.coerceIn(0f, 1f),
                sceneMargin = margin,
                matchedEvidence = top.definingIntent.matchedEvidence,
                rejectedEvidence = top.definingIntent.rejectedEvidence + if (switchTooWeak) {
                    listOf("scene_switch:below_threshold")
                } else {
                    listOf("scene:ambiguous_margin")
                },
                shouldClarify = true,
                clarificationPrompt = clarificationPrompt(compiled, language, top, second),
                sceneCandidates = candidates,
            )
        }

        return RuleClassificationResult(
            scene = top.scenario.sceneId,
            intent = top.definingIntent.intentId,
            callNature = top.definingIntent.callNature,
            confidence = topScore.coerceIn(0f, 1f),
            sceneMargin = margin,
            matchedEvidence = top.definingIntent.matchedEvidence,
            rejectedEvidence = top.definingIntent.rejectedEvidence,
            sceneCandidates = rankedSceneScores.take(2).map { it.scenario.sceneId },
        )
    }

    private fun clarificationThreshold(
        compiled: CompiledRuleSet,
        scenario: CompiledScenario,
    ): Float {
        val thresholds = compiled.source.classification.thresholds
        return if (scenario.sceneId in compiled.source.classification.evidenceCombination.enabledScenes) {
            thresholds.clarificationScore
        } else {
            thresholds.minimumIntentScore
        }
    }

    private fun isEntityOnlyEvidence(intent: ScoredIntent): Boolean =
        intent.hasPositiveEvidence &&
            intent.evidenceTrace.isNotEmpty() &&
            intent.evidenceTrace.all { it.type == "entity_derived" }

    private fun scoreScenario(
        scenario: CompiledScenario,
        language: CompiledLanguage,
        normalized: String,
        context: RuleClassificationContext,
        allowedIntentIds: Set<String>,
        useContextBoost: Boolean,
        correctionTarget: Boolean,
    ): List<ScoredIntent> = scenario.intents.mapNotNull { intent ->
        if (allowedIntentIds.isNotEmpty() && intent.intentId !in allowedIntentIds) return@mapNotNull null
        scoreIntent(
            scenario = scenario,
            intent = intent,
            language = language,
            normalized = normalized,
            expectedSlots = context.expectedSlots,
            derivedEntitySlots = context.derivedEntitySlots,
            useContextBoost = useContextBoost,
            correctionTarget = correctionTarget,
        )
    }

    private fun scoreIntent(
        scenario: CompiledScenario,
        intent: CompiledIntent,
        language: CompiledLanguage,
        normalized: String,
        expectedSlots: Set<String>,
        derivedEntitySlots: Map<String, String>,
        useContextBoost: Boolean,
        correctionTarget: Boolean,
    ): ScoredIntent {
        val locale = intent.localeFor(language.languageTag)
        val weights = scenario.config.classification.weights
        val matched = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val evidenceTrace = mutableListOf<RuleEvidenceTrace>()
        var score = 0f
        var coreEvidence = false
        var positiveEvidence = false
        var hasNegatedCore = false

        val entityEvidenceWeight = scenario.config.classification.evidenceCombination.entityEvidenceWeight
        if (intent.sceneDefining && entityEvidenceWeight > 0f) {
            derivedEntitySlots
                .filterKeys { it in DERIVED_ENTITY_KEYS }
                .filterValues(String::isNotBlank)
                .forEach { (key, value) ->
                    matched += "${scenario.sceneId}:${intent.intentId}:entity_derived:$key"
                    positiveEvidence = true
                    evidenceTrace += RuleEvidenceTrace(
                        type = "entity_derived",
                        text = value,
                        weight = entityEvidenceWeight,
                        accepted = true,
                        reason = key,
                    )
                }
        }

        val coreKeywordMatches = locale.coreKeywords.mapNotNull { keyword ->
            normalized.indexOf(keyword).takeIf { it >= 0 }?.let { index -> keyword to index }
        }.distinctBy { it.first }
        coreKeywordMatches.forEach { (keyword, index) ->
            if (isNegatedEvidence(normalized, index)) {
                rejected += "${scenario.sceneId}:${intent.intentId}:negated_core_keyword:$keyword"
                hasNegatedCore = true
                evidenceTrace += RuleEvidenceTrace(
                    type = "core_keyword",
                    text = keyword,
                    startIndex = index,
                    endExclusive = index + keyword.length,
                    weight = weights.explicitNegation,
                    accepted = false,
                    reason = "negated",
                )
            } else {
                matched += "${scenario.sceneId}:${intent.intentId}:core_keyword:$keyword"
                coreEvidence = true
                positiveEvidence = true
                evidenceTrace += RuleEvidenceTrace(
                    type = "core_keyword",
                    text = keyword,
                    startIndex = index,
                    endExclusive = index + keyword.length,
                    weight = weights.coreKeyword,
                    accepted = true,
                    reason = if (isPreemptiveDenialEvidence(normalized, index)) "preemptive_denial_context" else null,
                )
            }
        }
        if (coreEvidence) score += weights.coreKeyword

        if (scenario.sceneId == SceneType.CUSTOMER_SERVICE.id ||
            scenario.sceneId == SceneType.INSURANCE_FINANCE.id
        ) {
            locale.coreKeywords
                .asSequence()
                .filterNot(normalized::contains)
                .mapNotNull { keyword -> ChinesePhoneticMatcher.findUniqueMatch(normalized, keyword) }
                .toList()
                .singleOrNull()
                ?.let { phoneticMatch ->
                    score += weights.coreKeyword * PHONETIC_CORE_KEYWORD_SCALE
                    coreEvidence = true
                    positiveEvidence = true
                    matched +=
                        "${scenario.sceneId}:${intent.intentId}:phonetic_core_keyword:" +
                            "${phoneticMatch.keyword}:${phoneticMatch.sourceWindow}:${phoneticMatch.level}"
                    evidenceTrace += RuleEvidenceTrace(
                        type = "phonetic_core_keyword",
                        text = phoneticMatch.sourceWindow,
                        weight = weights.coreKeyword * PHONETIC_CORE_KEYWORD_SCALE,
                        accepted = true,
                        reason = phoneticMatch.level,
                    )
                }
        }

        val auxiliaryMatches = locale.auxiliaryKeywords.mapNotNull { keyword ->
            normalized.indexOf(keyword).takeIf { it >= 0 }?.let { index -> keyword to index }
        }.distinctBy { it.first }
        val positiveAuxiliary = auxiliaryMatches.filterNot { (_, index) -> isNegatedEvidence(normalized, index) }
        if (positiveAuxiliary.isNotEmpty()) {
            // Distinct auxiliary terms are independent signals of the same domain, not one signal
            // restated: 租金 alongside 物业费 alongside 押一付三 is three of them. Scoring the set
            // once left every such turn at exactly the single-term weight, under every threshold.
            // Accumulation is deliberately slow -- two terms still fall short, three just clear the
            // scene threshold -- so ordinary co-occurrence cannot carry a scene on its own.
            val extra = (positiveAuxiliary.size - 1).coerceAtMost(MAX_ACCUMULATED_AUXILIARY_TERMS)
            score += weights.auxiliaryKeyword + extra * weights.auxiliaryAccumulation
            positiveEvidence = true
            positiveAuxiliary.forEach { (keyword, index) ->
                matched += "${scenario.sceneId}:${intent.intentId}:auxiliary_keyword:$keyword"
                evidenceTrace += RuleEvidenceTrace(
                    type = "auxiliary_keyword",
                    text = keyword,
                    startIndex = index,
                    endExclusive = index + keyword.length,
                    weight = weights.auxiliaryKeyword,
                    accepted = true,
                )
            }
        }
        auxiliaryMatches.filter { (_, index) -> isNegatedEvidence(normalized, index) }.forEach { (keyword, index) ->
            evidenceTrace += RuleEvidenceTrace(
                type = "auxiliary_keyword",
                text = keyword,
                startIndex = index,
                endExclusive = index + keyword.length,
                weight = 0f,
                accepted = false,
                reason = "negated",
            )
        }

        var regexMatched = false
        locale.coreRegexes.forEachIndexed { index, regex ->
            regex.findAll(normalized).forEach { match ->
                if (isNegatedEvidence(normalized, match.range.first)) {
                    rejected += "${scenario.sceneId}:${intent.intentId}:negated_core_regex:$index"
                    hasNegatedCore = true
                    evidenceTrace += RuleEvidenceTrace(
                        type = "core_regex",
                        text = match.value,
                        startIndex = match.range.first,
                        endExclusive = match.range.last + 1,
                        weight = weights.explicitNegation,
                        accepted = false,
                        reason = "negated_pattern_$index",
                    )
                } else {
                    regexMatched = true
                    matched += "${scenario.sceneId}:${intent.intentId}:core_regex:$index"
                    evidenceTrace += RuleEvidenceTrace(
                        type = "core_regex",
                        text = match.value,
                        startIndex = match.range.first,
                        endExclusive = match.range.last + 1,
                        weight = weights.coreRegex,
                        accepted = true,
                        reason = "pattern_$index",
                    )
                }
            }
        }
        if (regexMatched) {
            score += weights.coreRegex
            coreEvidence = true
            positiveEvidence = true
        }

        // Templates are scored by shape rather than by occurrence, so only the best-fitting one
        // counts: several templates describing the same sentence are the same evidence written
        // more than once, and adding them up would reward an intent for having many phrasings on
        // file rather than for the turn matching any of them.
        locale.templates
            .mapNotNull { template -> TemplateMatcher.match(template, normalized) }
            // A template is evidence, and evidence inside a negation is not evidence: 我不需要贷款
            // is a caller refusing, and without this it reads as the sales pitch it is refusing.
            .filterNot { isTemplateNegated(normalized, it.startIndex) }
            .maxByOrNull(TemplateMatch::score)
            ?.let { best ->
                val contribution = weights.template * best.score.toFloat()
                score += contribution
                coreEvidence = true
                positiveEvidence = true
                matched += "${scenario.sceneId}:${intent.intentId}:template"
                evidenceTrace += RuleEvidenceTrace(
                    type = "template",
                    text = best.slots.entries.joinToString(",") { "${it.key}=${it.value}" },
                    weight = contribution,
                    accepted = true,
                    reason = "reference=%.2f input=%.2f".format(best.referenceCoverage, best.inputCoverage),
                )
            }

        val hasAffirmativeNegativeKeyword = locale.negativeKeywords.any { keyword ->
            containsNonNegatedKeyword(normalized, keyword)
        }
        if (hasAffirmativeNegativeKeyword || (hasNegatedCore && !coreEvidence)) {
            score += weights.explicitNegation
            evidenceTrace += RuleEvidenceTrace(
                type = "negative_evidence",
                weight = weights.explicitNegation,
                accepted = false,
                reason = if (hasAffirmativeNegativeKeyword) "negative_keyword" else "negated_core_only",
            )
        }

        val preemptiveDenial = scenario.sceneId == SceneType.SPAM_RISK.id &&
            intent.intentId == MARKETING_PITCH &&
            containsPreemptiveDenial(normalized)
        if (preemptiveDenial) {
            score += weights.preemptiveDenial
            positiveEvidence = true
            matched += "${scenario.sceneId}:${intent.intentId}:preemptive_denial"
            evidenceTrace += RuleEvidenceTrace(
                type = "preemptive_denial",
                weight = weights.preemptiveDenial,
                accepted = true,
            )
        }

        val semanticMatch = semanticMatch(intent.intentId, normalized, expectedSlots, language.config)
        if (semanticMatch == true) {
            score += weights.semanticPolarity
            positiveEvidence = true
            matched += "${scenario.sceneId}:${intent.intentId}:semantic_polarity"
            evidenceTrace += RuleEvidenceTrace(
                type = "semantic_polarity",
                weight = weights.semanticPolarity,
                accepted = true,
            )
        } else if (semanticMatch == false) {
            rejected += "${scenario.sceneId}:${intent.intentId}:opposite_polarity"
            evidenceTrace += RuleEvidenceTrace(
                type = "semantic_polarity",
                weight = 0f,
                accepted = false,
                reason = "opposite_polarity",
            )
        }

        val domainAxes = scenario.config.classification.evidenceCombination.domainAxes[scenario.sceneId]
        if (domainAxes != null && positiveEvidence) {
            val axisEvidence = matchDomainAxes(
                sceneId = scenario.sceneId,
                intentId = intent.intentId,
                normalized = normalized,
                axes = domainAxes,
                existingEvidence = evidenceTrace,
                coreWeight = weights.coreKeyword,
                auxiliaryWeight = weights.auxiliaryKeyword,
            )
            matched += axisEvidence.matchedEvidence
            evidenceTrace += axisEvidence.evidence
            if (axisEvidence.coreEvidence) coreEvidence = true
        }

        if (scenario.sceneId == SceneType.SPAM_RISK.id &&
            intent.intentId in scenario.spamRiskSemantic.targetIntentIds
        ) {
            val semantic = scoreSpamRiskSemantic(scenario, normalized)
            if (semantic.score > 0f && semantic.targetIntentId == intent.intentId) {
                score = maxOf(score, semantic.score)
                matched += semantic.matchedPrimitiveIds.map { primitiveId ->
                    "${scenario.sceneId}:${intent.intentId}:semantic_primitive:$primitiveId"
                }
                matched += "${scenario.sceneId}:${intent.intentId}:semantic_score:${semantic.score}"
                evidenceTrace += semantic.evidence
                if (semantic.score >= scenario.spamRiskSemantic.natureThreshold) {
                    coreEvidence = true
                    positiveEvidence = true
                    // Only a semantic score that clears the threshold on its own may outrank a
                    // business scene. The score above is a max(), so ordinary keyword evidence can
                    // carry the intent past the threshold while the semantic reading stays weak.
                    matched += "${scenario.sceneId}:${intent.intentId}:semantic_priority"
                }
            }
        }

        // Terms several scenes may utter, credited to each of them. Deliberately after the blocks
        // above and deliberately not setting coreEvidence: a shared term raises a scene into
        // contention and must never, on its own, be what selects it. positiveEvidence is set
        // because the turn did say something -- an arrival is not nothing -- and without it the
        // scene would be discarded before any other evidence in the sentence could be weighed.
        val sharedVocabulary = scenario.config.classification.sharedVocabulary
        if (sharedVocabulary.enabled && intent.sceneDefining && scenario.sharedVocabularyGroups.isNotEmpty()) {
            // The strongest matching group, not the sum of them. Several shared groups reading the
            // same turn are several descriptions of one call, and 退款 with 优惠券 added 0.60 to a
            // single intent and took it off its own scene's better answer.
            var best: Pair<SharedVocabularyGroup, String>? = null
            var bestWeight = 0f
            scenario.sharedVocabularyGroups.forEach { group ->
                val hit = group.terms.firstOrNull { term ->
                    // A word this intent already counted is not counted again. Without this the
                    // table rewards the scene that owns the term, which is backwards -- 上门 was
                    // customer_service's auxiliary keyword and its shared credit both, 0.45 against
                    // delivery's 0.30 for the same word, and 您要退货的包裹我现在上门取件 stopped
                    // being a courier's call. The point of the table is to bring the scene that has
                    // no word for it into the contest, not to widen a lead.
                    term !in locale.coreKeywords && term !in locale.auxiliaryKeywords &&
                        normalized.indexOf(term).let { index ->
                            index >= 0 && !isNegatedSemanticEvidence(normalized, index)
                        }
                } ?: return@forEach
                val groupWeight = group.weight ?: sharedVocabulary.weight
                if (groupWeight > bestWeight) {
                    best = group to hit
                    bestWeight = groupWeight
                }
            }
            best?.let { (group, hit) ->
                score += bestWeight
                positiveEvidence = true
                matched += "${scenario.sceneId}:${intent.intentId}:shared_vocabulary:${group.groupId}"
                evidenceTrace += RuleEvidenceTrace(
                    type = "shared_vocabulary",
                    text = hit,
                    startIndex = normalized.indexOf(hit),
                    endExclusive = normalized.indexOf(hit) + hit.length,
                    weight = bestWeight,
                    accepted = true,
                    reason = group.groupId,
                )
            }
        }

        if (useContextBoost) {
            score += weights.currentSceneContext
            evidenceTrace += RuleEvidenceTrace(
                type = "current_scene_context",
                weight = weights.currentSceneContext,
                accepted = true,
            )
        }
        if (correctionTarget && coreEvidence) {
            score += weights.correctionTarget
            evidenceTrace += RuleEvidenceTrace(
                type = "correction_target",
                weight = weights.correctionTarget,
                accepted = true,
            )
        }

        val evidenceCombination = scenario.config.classification.evidenceCombination
        if (scenario.sceneId in evidenceCombination.enabledScenes) {
            val positiveByType = evidenceTrace
                .asSequence()
                .filter { it.accepted && it.weight > 0f }
                .groupBy(RuleEvidenceTrace::type)
                .mapValues { (_, traces) -> traces.maxOf(RuleEvidenceTrace::weight) }
            val noisyOr = positiveByType.values.fold(1f) { remaining, weight ->
                remaining * (1f - weight.coerceIn(0f, 1f))
            }.let { remaining -> 1f - remaining }
            val comboBonus = evidenceCombination.comboBonus *
                (positiveByType.size - 1).coerceAtLeast(0)
            val negativePenalty = evidenceTrace
                .asSequence()
                .filter { !it.accepted && it.weight < 0f }
                .sumOf { it.weight.toDouble() }
                .toFloat()
            score = (noisyOr + comboBonus + negativePenalty).coerceIn(0f, 1f)
            if (positiveEvidence && positiveByType.size < evidenceCombination.minimumEvidenceTypes) {
                val cap = evidenceCombination.singleEvidenceCap
                    ?: (scenario.config.classification.thresholds.minimumIntentScore - SINGLE_EVIDENCE_EPSILON)
                score = minOf(score, cap.coerceAtLeast(0f))
            }
        }

        if (
            scenario.sceneId == SceneType.SPAM_RISK.id &&
            intent.intentId == MARKETING_PITCH &&
            preemptiveDenial
        ) {
            val hasIndependentEvidence = evidenceTrace.any {
                it.accepted && it.weight > 0f && it.type != "preemptive_denial"
            }
            if (!hasIndependentEvidence) {
                score = minOf(
                    score,
                    scenario.config.classification.thresholds.minimumIntentScore - SINGLE_EVIDENCE_EPSILON,
                )
            }
        }
        return ScoredIntent(
            intentId = intent.intentId,
            callNature = intent.callNature,
            sceneDefining = intent.sceneDefining,
            score = score,
            coreEvidence = coreEvidence,
            hasPositiveEvidence = positiveEvidence,
            matchedEvidence = matched.distinct(),
            rejectedEvidence = rejected.distinct(),
            evidenceTrace = evidenceTrace,
            order = intent.order,
        )
    }

    private fun matchDomainAxes(
        sceneId: String,
        intentId: String,
        normalized: String,
        axes: EvidenceAxisConfig,
        existingEvidence: List<RuleEvidenceTrace>,
        coreWeight: Float,
        auxiliaryWeight: Float,
    ): DomainAxisEvidence {
        val existingTerms = existingEvidence
            .asSequence()
            .filter { it.accepted }
            .mapNotNull { it.text }
            .toSet()
        val axisTerms = listOf(
            "entity" to axes.entity,
            "action" to axes.action,
            "state" to axes.state,
            "clause" to axes.clause,
        ).flatMap { (axis, terms) ->
            terms.asSequence()
                .filter { term -> term.isNotBlank() && normalized.contains(term) && term !in existingTerms }
                .distinct()
                .map { term -> axis to term }
        }
        if (axisTerms.isEmpty()) return DomainAxisEvidence()

        val entityCount = axisTerms.count { it.first == "entity" }
        val hasEntity = entityCount > 0
        val hasAction = axisTerms.any { it.first == "action" }
        val hasClause = axisTerms.any { it.first == "clause" }
        val coreEvidence = entityCount >= 2 || hasEntity && (hasAction || hasClause)
        val axisWeight = if (coreEvidence) coreWeight else auxiliaryWeight
        val evidence = axisTerms.map { (axis, term) ->
            RuleEvidenceTrace(
                type = "domain_axis_$axis",
                text = term,
                startIndex = normalized.indexOf(term).takeIf { it >= 0 },
                endExclusive = normalized.indexOf(term).takeIf { it >= 0 }?.plus(term.length),
                weight = axisWeight,
                accepted = true,
                reason = if (coreEvidence) "core_axis" else "auxiliary_axis",
            )
        }
        val matchedEvidence = axisTerms.map { (axis, term) ->
            "$sceneId:$intentId:domain_axis:$axis:$term"
        }
        return DomainAxisEvidence(
            evidence = evidence,
            matchedEvidence = matchedEvidence,
            coreEvidence = coreEvidence,
        )
    }

    private data class DomainAxisEvidence(
        val evidence: List<RuleEvidenceTrace> = emptyList(),
        val matchedEvidence: List<String> = emptyList(),
        val coreEvidence: Boolean = false,
    )

    private fun scoreSpamRiskSemantic(
        scenario: CompiledScenario,
        normalized: String,
    ): SpamRiskSemanticMatch {
        val config = scenario.spamRiskSemantic
        if (!config.enabled) return SpamRiskSemanticMatch()

        val matched = config.primitives.mapNotNull { primitive ->
            primitive.patterns.asSequence()
                .flatMap { pattern -> pattern.findAll(normalized).asSequence() }
                .firstOrNull { match -> !isNegatedSemanticEvidence(normalized, match.range.first) }
                ?.let { match -> primitive to match }
        }
        val evidence = matched.map { (primitive, match) ->
            RuleEvidenceTrace(
                type = "spam_semantic_primitive",
                text = match.value,
                startIndex = match.range.first,
                endExclusive = match.range.last + 1,
                weight = primitive.weight,
                accepted = true,
                reason = primitive.primitiveId,
            )
        }.toMutableList()
        var score = if (matched.isEmpty()) {
            0f
        } else {
            1f - matched.fold(1f) { remaining, (primitive, _) ->
                remaining * (1f - primitive.weight)
            } + config.comboBonus * (matched.size - 1)
        }

        if (config.openingDetectionEnabled && config.openingPatterns.any { it.containsMatchIn(normalized) }) {
            score = maxOf(score, config.openingWeight)
            evidence += RuleEvidenceTrace(
                type = "spam_semantic_opening",
                weight = config.openingWeight,
                accepted = true,
                reason = "opening_pattern",
            )
        }
        if (score <= 0f) return SpamRiskSemanticMatch()

        val authorityThreatCombination = matched.any { it.first.primitiveId == "R3_authority_impersonation" } &&
            matched.any {
                it.first.primitiveId == "R2_urgency" ||
                    it.first.primitiveId == "R4_loss_threat"
            }
        val exemption = config.exemptionPatterns.firstOrNull { it.containsMatchIn(normalized) }
            ?.takeUnless { authorityThreatCombination }
        if (exemption != null) {
            score *= 0.5f
            evidence += RuleEvidenceTrace(
                type = "spam_semantic_exemption",
                text = exemption.pattern,
                weight = -0.5f,
                accepted = true,
                reason = "context_exemption",
            )
        }
        return SpamRiskSemanticMatch(
            score = score.coerceIn(0f, 1f),
            matchedPrimitiveIds = matched.map { it.first.primitiveId },
            evidence = evidence,
            // The heaviest match decides the register. A turn that both dangles a prize and demands
            // a fee is a pitch or a threat according to which it leans on, and the weights already
            // say which that is.
            targetIntentId = matched.maxByOrNull { it.first.weight }?.first?.intentId
                ?: DEFAULT_SPAM_INTENT,
        )
    }

    private fun isNegatedSemanticEvidence(text: String, startIndex: Int): Boolean {
        if (startIndex <= 0) return false
        val prefix = text.substring(maxOf(0, startIndex - 10), startIndex)
        return Regex("(?:不是|并非|不要|不用|不需要|无需|无须|不必|不要求|不能|不会|没有|没)[^，。！？,.!?]{0,1}$")
            .containsMatchIn(prefix)
    }

    private fun semanticMatch(
        intentId: String,
        normalized: String,
        expectedSlots: Set<String>,
        language: LanguageRuleConfig?,
    ): Boolean? = when (intentId) {
        CALLBACK_YES -> detectCallbackNeeded(normalized, "callbackNeeded" in expectedSlots, language)
        CALLBACK_NO -> detectCallbackNeeded(normalized, "callbackNeeded" in expectedSlots, language)?.not()
        URGENT_YES -> detectUrgent(normalized, "urgent" in expectedSlots, language)
        URGENT_NO -> detectUrgent(normalized, "urgent" in expectedSlots, language)?.not()
        CONFIRMATION_YES -> detectConfirmation(normalized, "confirmation" in expectedSlots)
        CONFIRMATION_NO -> detectConfirmation(normalized, "confirmation" in expectedSlots)?.not()
        SUPPLEMENT_NONE -> detectSupplementProvided(normalized, "supplement" in expectedSlots)?.not()
        SUPPLEMENT_PROVIDED -> detectSupplementProvided(normalized, "supplement" in expectedSlots)
        else -> null
    }

    private fun containsNonNegatedKeyword(text: String, keyword: String): Boolean {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val index = text.indexOf(keyword, searchFrom)
            if (index < 0) return false
            if (!isNegatedEvidence(text, index)) return true
            searchFrom = index + keyword.length
        }
        return false
    }

    private fun effectiveRisk(
        risk: RiskDetection,
        base: RuleClassificationResult,
        safety: SafetyRuleConfig,
    ): RiskDetection {
        if (risk.level == RiskLevel.HIGH) return risk
        if (
            risk.level == RiskLevel.LOW &&
            risk.matchedRuleLevels.isEmpty() &&
            risk.contextExemptedRuleIds.isNotEmpty() &&
            risk.contextExemptedRuleIds.all { it == REQUEST_INVESTMENT_ACTION }
        ) {
            return risk
        }
        val mediumRiskCount = risk.matchedRuleLevels.values.count { it == RiskLevel.MEDIUM }
        return when {
            risk.level == RiskLevel.LOW &&
                risk.contextExemptedRuleLevels.values.any { it == RiskLevel.HIGH } -> risk.copy(
                level = RiskLevel.MEDIUM,
                reasons = (risk.reasons + "context_exemption").distinct(),
                escalationReason = "context_exemption:high_to_medium",
            )
            mediumRiskCount >= safety.mediumRiskEscalationCount -> risk.copy(
                level = RiskLevel.HIGH,
                escalationReason = "multiple_medium_rules:$mediumRiskCount",
            )
            mediumRiskCount >= 1 && base.callNature == CallNature.MARKETING -> risk.copy(
                level = RiskLevel.HIGH,
                escalationReason = "medium_with_marketing_nature",
            )
            else -> risk
        }
    }

    private fun applyRisk(
        base: RuleClassificationResult,
        risk: RiskDetection,
        enabledIds: Set<String>,
        safety: SafetyRuleConfig,
    ): RuleClassificationResult {
        if (risk.level == RiskLevel.LOW && risk.matchedEvidence.isEmpty() && risk.rejectedEvidence.isEmpty()) return base
        val spamRiskEnabled = SceneType.SPAM_RISK.id in enabledIds
        val protectBusinessTopic = isSoftRiskProtected(base, risk, safety)
        val overrideWithRiskScene = risk.level == RiskLevel.HIGH &&
            safety.riskSceneCommitPolicy == RiskSceneCommitPolicy.OVERRIDE &&
            spamRiskEnabled &&
            !protectBusinessTopic
        val fallbackToRiskScene = risk.level == RiskLevel.HIGH &&
            base.scene == null &&
            spamRiskEnabled &&
            !protectBusinessTopic
        val riskSlots = buildMap {
            if (risk.sensitiveInfoTypes.isNotEmpty()) put("sensitiveInfoType", risk.sensitiveInfoTypes.joinToString(","))
            if (risk.reasons.isNotEmpty()) put("riskReason", risk.reasons.joinToString(","))
            if (risk.reasons.isNotEmpty()) put("riskLevel", risk.level.name)
        }
        val commitToRiskScene = overrideWithRiskScene || fallbackToRiskScene
        return base.copy(
            scene = if (commitToRiskScene) SceneType.SPAM_RISK.id else base.scene,
            intent = if (commitToRiskScene && base.scene != SceneType.SPAM_RISK.id) "sensitive_info_request" else base.intent,
            topicScene = if (commitToRiskScene && base.scene != SceneType.SPAM_RISK.id) base.scene else base.topicScene,
            callNature = if (risk.level == RiskLevel.LOW) base.callNature else CallNature.SUSPICIOUS,
            riskLevel = risk.level,
            confidence = if (risk.level == RiskLevel.HIGH) maxOf(base.confidence, HIGH_RISK_CONFIDENCE) else base.confidence,
            matchedEvidence = (base.matchedEvidence + risk.matchedEvidence).distinct(),
            rejectedEvidence = (base.rejectedEvidence + risk.rejectedEvidence).distinct(),
            shouldClarify = if (risk.level == RiskLevel.HIGH) false else base.shouldClarify,
            clarificationPrompt = if (risk.level == RiskLevel.HIGH) null else base.clarificationPrompt,
            extractedSlots = base.extractedSlots + riskSlots,
            sceneCandidates = if (commitToRiskScene) {
                (listOf(SceneType.SPAM_RISK.id) + base.sceneCandidates + listOfNotNull(base.scene)).distinct()
            } else {
                base.sceneCandidates
            },
            riskReasons = risk.reasons,
        )
    }

    private fun isSoftRiskProtected(
        base: RuleClassificationResult,
        risk: RiskDetection,
        safety: SafetyRuleConfig,
    ): Boolean {
        val riskIds = (risk.reasons + risk.contextExemptedRuleIds).distinct()
        return base.scene != null &&
            base.scene != SceneType.SPAM_RISK.id &&
            !base.shouldClarify &&
            base.confidence + SCORE_COMPARISON_EPSILON >= safety.protectedTopicScore &&
            riskIds.isNotEmpty() &&
            riskIds.all { it in safety.softRiskIds }
    }

    private fun buildDebugTrace(
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
        normalized: String,
        enabledIds: Set<String>,
        context: RuleClassificationContext,
        base: RuleClassificationResult,
        rawRisk: RiskDetection,
        risk: RiskDetection,
        result: RuleClassificationResult,
    ): RuleDebugTrace {
        val thresholds = compiled.source.classification.thresholds
        val scoredScenarios = compiled.scenarios
            .filter { it.sceneId in enabledIds }
            .map { scenario ->
                val locked = context.lockedScene?.id == scenario.sceneId
                scenario to scoreScenario(
                    scenario = scenario,
                    language = language,
                    normalized = normalized,
                    context = context,
                    allowedIntentIds = if (locked) context.allowedIntentIds else emptySet(),
                    useContextBoost = locked,
                    correctionTarget = false,
                )
            }
        val sceneScores = scoredScenarios.map { (scenario, intents) ->
            val defining = intents
                .filter { it.sceneDefining }
                .maxWithOrNull(SCORED_INTENT_ORDER)
            RuleSceneScoreTrace(
                scene = scenario.sceneId,
                score = defining?.score ?: 0f,
                definingIntent = defining?.intentId,
                hasPositiveEvidence = defining?.hasPositiveEvidence == true,
                accepted = base.scene == scenario.sceneId && !base.shouldClarify,
            )
        }.sortedByDescending { it.score }
        val intentScores = scoredScenarios.flatMap { (scenario, intents) ->
            intents.map { intent ->
                RuleIntentScoreTrace(
                    scene = scenario.sceneId,
                    intent = intent.intentId,
                    callNature = intent.callNature,
                    score = intent.score,
                    sceneDefining = intent.sceneDefining,
                    hasPositiveEvidence = intent.hasPositiveEvidence,
                    accepted = base.scene == scenario.sceneId && base.intent == intent.intentId,
                    evidence = intent.evidenceTrace,
                )
            }
        }
        val overrideApplied = result.scene == SceneType.SPAM_RISK.id &&
            base.scene != SceneType.SPAM_RISK.id &&
            risk.level == RiskLevel.HIGH &&
            compiled.source.safety.riskSceneCommitPolicy == RiskSceneCommitPolicy.OVERRIDE
        val fallbackApplied = result.scene == SceneType.SPAM_RISK.id && base.scene == null && risk.level == RiskLevel.HIGH
        val topicProtected = isSoftRiskProtected(base, risk, compiled.source.safety)
        val fallbackSkipReason = when {
            risk.level != RiskLevel.HIGH -> "risk_below_high"
            SceneType.SPAM_RISK.id !in enabledIds -> "spam_risk_disabled"
            topicProtected -> "soft_risk_protected_business_topic"
            compiled.source.safety.riskSceneCommitPolicy == RiskSceneCommitPolicy.ANNOTATE && base.scene != null ->
                "policy_annotate_preserved_topic"
            else -> null
        }
        val lockedSceneId = context.lockedScene?.id
        val finalSceneSource = when {
            overrideApplied -> "RISK_OVERRIDE"
            fallbackApplied -> "RISK_FALLBACK"
            lockedSceneId != null && base.scene == lockedSceneId -> "LOCKED_SCENE"
            base.shouldClarify -> "CLARIFICATION_CANDIDATE"
            base.scene != null -> "SCENE_CLASSIFIER"
            else -> "BELOW_THRESHOLD"
        }
        val top = sceneScores.getOrNull(0)
        val second = sceneScores.getOrNull(1)
        return RuleDebugTrace(
            inputText = normalized,
            normalizedText = normalized,
            sceneScores = sceneScores,
            intentScores = intentScores,
            thresholds = RuleThresholdTrace(
                minimumSceneScore = thresholds.minimumSceneScore,
                minimumIntentScore = thresholds.minimumIntentScore,
                clarificationMargin = thresholds.clarificationMargin,
                sceneSwitchScore = thresholds.sceneSwitchScore,
                clarificationScore = thresholds.clarificationScore,
            ),
            sceneCompetition = if (top == null) null else buildString {
                append(top.scene).append('=').append(top.score)
                if (second != null) append(" vs ").append(second.scene).append('=').append(second.score)
            },
            risk = RuleRiskTrace(
                invoked = true,
                rawLevel = rawRisk.level,
                effectiveLevel = risk.level,
                matchedRiskIds = risk.reasons,
                rejectedRiskIds = risk.evidenceTrace.filterNot { it.accepted }.map { it.riskId }.distinct(),
                contextExemptedRiskIds = risk.contextExemptedRuleIds,
                matchedEvidence = risk.evidenceTrace.map { evidence ->
                    RuleEvidenceTrace(
                        type = "risk_pattern",
                        text = evidence.text,
                        startIndex = evidence.startIndex,
                        endExclusive = evidence.endExclusive,
                        weight = when {
                            !evidence.accepted -> 0f
                            risk.matchedRuleLevels[evidence.riskId] == RiskLevel.HIGH -> 1f
                            else -> 0.5f
                        },
                        accepted = evidence.accepted,
                        reason = evidence.reason ?: evidence.riskId,
                    )
                },
                escalationReason = risk.escalationReason,
                commitPolicy = compiled.source.safety.riskSceneCommitPolicy.name,
                overrideApplied = overrideApplied,
                fallbackApplied = fallbackApplied,
                topicProtected = topicProtected,
                fallbackSkipReason = fallbackSkipReason,
            ),
            finalScene = result.scene,
            finalSceneSource = finalSceneSource,
        )
    }

    private fun clarificationPrompt(
        compiled: CompiledRuleSet,
        language: CompiledLanguage,
        top: SceneScore,
        second: SceneScore?,
    ): String {
        val template = compiled.source.classification.localizedClarificationPromptTemplates[language.languageTag]
            ?: compiled.source.classification.clarificationPromptTemplate
        val firstName = top.scenario.source.displayNameFor(language.languageTag)
        val secondName = second?.scenario?.source?.displayNameFor(language.languageTag) ?: "其他事项"
        return template.replace("{firstScene}", firstName).replace("{secondScene}", secondName)
    }

    private data class SceneScore(
        val scenario: CompiledScenario,
        val definingIntent: ScoredIntent,
        val allIntents: List<ScoredIntent>,
        /** True when a term exclusive to this scene appeared. See [ScenarioRule.anchorKeywords]. */
        val anchored: Boolean = false,
    ) {
        /**
         * The score scenes are ranked on, which is the intent score plus what naming the domain is
         * worth. Only used for ordering: the reported confidence stays the intent score, since the
         * priority answers "which industry" and not "how sure are we of this intent".
         */
        fun domainRankingScore(anchorDomainPriority: Float): Float =
            definingIntent.score + if (anchored) anchorDomainPriority else 0f
    }

    private data class ScoredIntent(
        val intentId: String,
        val callNature: CallNature,
        val sceneDefining: Boolean,
        val score: Float,
        val coreEvidence: Boolean,
        val hasPositiveEvidence: Boolean,
        val matchedEvidence: List<String>,
        val rejectedEvidence: List<String>,
        val evidenceTrace: List<RuleEvidenceTrace>,
        val order: Int,
    )

    private data class DeliveryIntentDecision(
        val intentId: String,
        val ruleId: String,
        val callNature: CallNature,
    )

    private data class LocalTextCorrection(
        val text: String,
        val appliedTerms: List<String>,
    )

    private data class ContextualTextCorrection(
        val text: String,
        val appliedTerms: List<String> = emptyList(),
    )

    private data class CanonicalSourceText(
        val text: String,
        val sourceIndices: List<Int>,
    )

    private data class LocalAlignment(
        val sourceRange: IntRange,
        val canonicalLength: Int,
        val editDistance: Int,
    )

    private data class CompiledRuleSet(
        val source: DialogueRuleFile,
        val languages: Map<String, CompiledLanguage>,
        val scenarios: List<CompiledScenario>,
        val riskDetector: CompiledRiskDetector,
    ) {
        fun languageFor(languageTag: String): CompiledLanguage {
            val languageOnly = languageTag.substringBefore('-')
            return languages[languageTag]
                ?: languages.values.firstOrNull { it.languageTag.substringBefore('-') == languageOnly }
                ?: languages.getValue(DEFAULT_LANGUAGE)
        }

        companion object {
            fun compile(source: DialogueRuleFile): CompiledRuleSet {
                val languageTags = buildSet {
                    add(DEFAULT_LANGUAGE)
                    addAll(source.languages.keys)
                    source.scenarios.flatMapTo(this) { scenario -> scenario.intents.flatMap { it.localeRules.keys } }
                }
                val languages = languageTags.associateWith { tag ->
                    val languageOnly = tag.substringBefore('-')
                    val config = source.languages[tag]
                        ?: source.languages.entries.firstOrNull { it.key.substringBefore('-') == languageOnly }?.value
                    CompiledLanguage(tag, config, RuleTextNormalizer(config))
                }
                val scenarios = source.scenarios.mapIndexed { scenarioIndex, scenario ->
                    CompiledScenario(
                        source = scenario,
                        config = source,
                        sceneId = scenario.sceneId,
                        order = scenarioIndex,
                        spamRiskSemantic = if (scenario.sceneId == SceneType.SPAM_RISK.id) {
                            compileSpamRiskSemantic(source.classification.spamRiskSemantics)
                        } else {
                            compileSpamRiskSemantic(SpamRiskSemanticConfig())
                        },
                        anchors = scenario.anchorKeywords.mapValues { (tag, anchors) ->
                            val normalizer = languages[tag]?.normalizer
                                ?: languages.getValue(DEFAULT_LANGUAGE).normalizer
                            anchors.map(normalizer::normalize).filter(String::isNotBlank).distinct()
                        },
                        intents = scenario.intents.mapIndexed { intentIndex, intent ->
                            val locales = languageTags.associateWith { tag ->
                                val localized = intent.localizedFor(tag)
                                val normalizer = languages.getValue(tag).normalizer
                                CompiledIntentLocale(
                                    coreKeywords = localized.coreKeywords.map(normalizer::normalize).filter(String::isNotBlank).distinct(),
                                    auxiliaryKeywords = localized.auxiliaryKeywords.map(normalizer::normalize).filter(String::isNotBlank).distinct(),
                                    negativeKeywords = localized.negativeKeywords.map(normalizer::normalize).filter(String::isNotBlank).distinct(),
                                    coreRegexes = localized.coreRegexPatterns.map { Regex(it.lowercase()) },
                                    // Normalized like every other pattern, so a template author
                                    // writes what the caller says and not what normalization
                                    // happens to leave behind.
                                    templates = localized.templates.map { source ->
                                        SentenceTemplate.parse(normalizer.normalize(source))
                                    },
                                )
                            }
                            CompiledIntent(
                                intentId = intent.intentId,
                                callNature = intent.callNature,
                                sceneDefining = intent.sceneDefining ?: (intent.intentId !in CONTEXT_ONLY_INTENTS),
                                locales = locales,
                                order = intentIndex,
                            )
                        },
                    )
                }
                return CompiledRuleSet(source, languages, scenarios, CompiledRiskDetector.compile(source.safety))
            }
        }
    }

    private data class CompiledLanguage(
        val languageTag: String,
        val config: LanguageRuleConfig?,
        val normalizer: RuleTextNormalizer,
    )

    private data class CompiledScenario(
        val source: ScenarioRule,
        val config: DialogueRuleFile,
        val sceneId: String,
        val order: Int,
        val spamRiskSemantic: CompiledSpamRiskSemanticConfig,
        val intents: List<CompiledIntent>,
        val anchors: Map<String, List<String>> = emptyMap(),
    ) {
        fun anchorsFor(languageTag: String): List<String> = anchors[languageTag].orEmpty()

        /** The shared groups this scene is entitled to, so scoring does not walk the whole table. */
        val sharedVocabularyGroups: List<SharedVocabularyGroup> =
            config.classification.sharedVocabulary.groups.filter { sceneId in it.targets }
    }

    private data class CompiledIntent(
        val intentId: String,
        val callNature: CallNature,
        val sceneDefining: Boolean,
        val locales: Map<String, CompiledIntentLocale>,
        val order: Int,
    ) {
        fun localeFor(languageTag: String): CompiledIntentLocale {
            val languageOnly = languageTag.substringBefore('-')
            return locales[languageTag]
                ?: locales.entries.firstOrNull { it.key.substringBefore('-') == languageOnly }?.value
                ?: locales.getValue(DEFAULT_LANGUAGE)
        }
    }

    private data class CompiledIntentLocale(
        val coreKeywords: List<String>,
        val auxiliaryKeywords: List<String>,
        val negativeKeywords: List<String>,
        val coreRegexes: List<Regex>,
        val templates: List<SentenceTemplate>,
    )

    private companion object {
        const val DEFAULT_LANGUAGE = "zh-CN"
        const val CALLBACK_YES = "callback_yes"
        const val CALLBACK_NO = "callback_no"
        const val URGENT_YES = "urgent_yes"
        const val URGENT_NO = "urgent_no"
        const val CONFIRMATION_YES = "confirmation_yes"
        const val CONFIRMATION_NO = "confirmation_no"
        const val SUPPLEMENT_NONE = "supplement_none"
        const val SUPPLEMENT_PROVIDED = "supplement_provided"
        const val MARKETING_PITCH = "marketing_pitch"
        const val REQUEST_INVESTMENT_ACTION = "request_investment_action"
        const val HIGH_RISK_CONFIDENCE = 0.95f
        const val MAXIMUM_TEXT_DIFFERENCE_RATE = 0.25
        const val PHONETIC_CORE_KEYWORD_SCALE = 0.75f

        /** Particles that cancel whatever immediately follows them. */
        const val NEGATION_PARTICLES = "不没别无未莫甭"

        /**
         * Ceiling on how many auxiliary terms past the first may add to a score. Beyond a handful
         * the extra terms stop being independent evidence and start being a long sentence.
         */
        const val MAX_ACCUMULATED_AUXILIARY_TERMS = 3
        const val SINGLE_EVIDENCE_EPSILON = 0.01f
        const val SCORE_COMPARISON_EPSILON = 0.0001f
        /**
         * Scenes the second ASR pass may establish on its own when the primary produced no scene.
         *
         * The pass is a re-read of the same audio against a closed vocabulary, so it is more prone
         * to hearing what the vocabulary contains. Membership is therefore decided by what a wrong
         * lock costs: for these three it is an irrelevant logistics question, which the caller can
         * correct in the next turn. Scenes left out drive money movement, property transactions,
         * identity handling or the safety flow that ends the call, and there a wrong lock is not
         * something the caller can talk the assistant back out of. Those still require the primary
         * pass to have seen the scene first; the secondary may then confirm it.
         *
         * Membership is opt-in so a newly added scene defaults to the conservative behaviour.
         */
        val SCENES_ESTABLISHABLE_BY_SECONDARY_PASS = setOf(
            SceneType.DELIVERY,
            SceneType.RIDE_HAILING,
            SceneType.CUSTOMER_SERVICE,
        )

        const val MINIMUM_SCENE_HOTWORD_MATCHES = 2
        const val MINIMUM_CROSS_SCENE_HOTWORD_MATCHES = 3
        const val MINIMUM_PRIMARY_CONFIRMATION_HOTWORD_MATCHES = 3
        const val MINIMUM_SECONDARY_SCENE_CONFIDENCE = 0.90f
        const val MINIMUM_SECONDARY_SCENE_MARGIN = 0.50f
        const val DELIVERY_PRIORITY_CONFIDENCE = 0.75f
        const val RIDE_POLICY_CONFIDENCE = 0.85f
        const val MINIMUM_DELAYED_EVIDENCE_COUNT = 2
        const val MINIMUM_LOCAL_ALIGNMENT_CONFIDENCE = 0.66
        const val DELIVERY_ARRIVED = "delivery_arrived"
        const val DELIVERY_PLACED = "delivery_placed"
        const val DELIVERY_LOCATION_QUERY = "delivery_location_query"
        const val DELIVERY_ACCESS_BLOCKED = "delivery_access_blocked"
        const val DELIVERY_DELAYED = "delivery_delayed"
        const val DELIVERY_ITEM_ISSUE = "delivery_item_issue"
        const val DELIVERY_DELAY_ISSUE = "延迟"
        const val REFUND_PROGRESS = "refund_progress"
        const val REFUND_NOTICE = "refund_notice"
        val REFUND_COMPLETION_REGEX = Regex(
            "退款.{0,16}(?:已|已经)(?:审核通过|审核完成|完成|到账|原路退回|原路返回)",
        )
        val REFUND_ACTIVE_PROGRESS_REGEX = Regex(
            "退款.{0,16}(?:进度|处理中|还没到账|未到账|什么时候到账|仍在)",
        )
        val DELIVERY_ITEM_ISSUES = setOf("缺货", "破损", "餐具缺失")
        val DELIVERY_ACCESS_BLOCKED_REGEX = Regex(
            "(?:进不去|进不了|无法进入|刷卡|登记|门禁.{0,8}(?:打不开|刷卡|不让|拦)|" +
                "保安.{0,10}(?:不让|拦|登记)|上不了楼|不能上楼|封路|封了|" +
                "入口.{0,8}(?:关闭|封闭)|闸机.{0,8}(?:打不开|刷卡|登记))",
        )
        val DELIVERY_DELAY_CAUSE_REGEX = Regex(
            "(?:没找到|找不到|绕路|绕一下|堵车|堵住|堵得|商家.{0,6}(?:刚出餐|刚刚出餐)|" +
                "雨.{0,4}(?:大|太大)|路滑|施工车|管制|挡住|封路)",
        )
        val DELIVERY_NOT_ARRIVED_REGEX = Regex(
            "(?:马上(?:就)?到|一会儿(?:就)?到|还没到|尚未到|正在赶|才能到|后到|送到|再过去|过去)",
        )
        val DELIVERY_EXPLICIT_DELAY_REGEX = Regex("延迟")
        val DELIVERY_PLACED_REGEX = Regex(
            "(?:给[您你](?:已经)?放[在到的]|我(?:已经)?(?:把[^，。！？\\n]{0,12})?(?:放[在到]|搁[在到])|" +
                "我[^，。！？\\n]{0,6}(?:放[在到]|搁[在到])|已经(?:放[在到]|搁[在到])|东西在)",
        )
        val DELIVERY_LOCATION_QUERY_REGEX = Regex(
            "(?:哪个单元|哪个门|哪一栋|哪栋|哪一座|具体在哪|到底在哪|" +
                "(?:您|你).{0,16}(?:在哪|哪里|哪儿|哪个|哪一|是在|从哪))",
        )
        val DELIVERY_ARRIVED_REGEX = Regex(
            "(?:^|我|已经|现在)(?:已经)?(?:到了|已到|到达|在.{1,32}(?:等您|等你|门|入口|通道))",
        )
        val FORMAL_ENTITY_KEYS = setOf(
            "location",
            "pickupLocation",
            "issueType",
            "orderNumber",
            "orderId",
            "estimatedTime",
            "viewingTime",
            "expiryTime",
            "licensePlate",
            "organization",
            "platform",
            "community",
            "insuranceType",
        )
        val DERIVED_ENTITY_KEYS = setOf(
            "insuranceType",
            "serviceType",
            "expiryTime",
            "contactPurpose",
            "organization",
        )
        val LOCATION_QUALITY_RETRY_REASONS = setOf(
            "missing_location",
            "invalid_location",
            "suspected_location_error",
            "intent_missing_location",
        )
        val CUE_SUPPORTED_ENTITY_KEYS = setOf(
            "orderNumber",
            "orderId",
            "estimatedTime",
            "viewingTime",
            "expiryTime",
            "licensePlate",
            "community",
        )
        val ENTITY_CUES = mapOf(
            "location" to listOf(
                "小区", "门口", "楼下", "前台", "保安室", "楼", "门", "路口", "地址", "定位", "位置",
            ),
            "pickupLocation" to listOf("上车点", "上车地址", "门", "入口", "出口", "定位", "位置"),
            "issueType" to listOf("缺货", "破损", "坏", "碎", "延迟", "晚", "餐具", "漏放", "忘记", "洒"),
            "orderNumber" to listOf("订单", "单号", "尾号"),
            "orderId" to listOf("订单", "工单", "单号", "尾号"),
            "estimatedTime" to listOf("分钟", "小时", "预计", "多久", "马上", "稍后", "还要", "晚"),
            "viewingTime" to listOf("看房时间", "几点看房", "明天看房", "周末看房", "预约时间"),
            "expiryTime" to listOf("到期", "续保时间", "月底", "保单时间"),
            "licensePlate" to listOf("车牌", "车辆尾号"),
            "organization" to listOf("平台客服", "公司客服", "保险公司", "银行客服", "保险客服", "来自"),
            "platform" to listOf("平台客服", "电商平台", "App客服"),
            "community" to listOf("小区", "公寓", "花园", "家园"),
            "insuranceType" to listOf("险种", "保险类型", "车险", "寿险", "医疗险", "意外险", "重疾险", "财产险"),
        )
        val HOTWORD_ENTITY_CUES = mapOf(
            "orderNumber" to listOf("订单号", "单号", "尾号"),
            "orderId" to listOf("订单号", "工单号", "单号", "尾号"),
            "estimatedTime" to listOf("分钟", "小时", "预计", "还要"),
            "viewingTime" to listOf("看房时间", "明天", "后天", "周末", "上午", "下午", "晚上", "点"),
            "expiryTime" to listOf("到期", "月底", "下周", "本周"),
            "licensePlate" to listOf("车牌", "车辆尾号"),
            "community" to listOf("小区", "公寓", "花园", "家园"),
        )
        val CANONICAL_IGNORED_REGEX = Regex("[\\s，。！？、,.!?：:；;‘’“”\"']+")
        val ENTITY_PARTICLE_REGEX = Regex("[的在去往了]")
        val GATE_REGEX = Regex("[东南西北]门")
        val DIRECTION_REGEX = Regex("[东南西北](?:侧)?(?:门|入口|出口)")
        val LOCATION_CORRECTION_TERMS = listOf(
            "访客通道", "校车通道", "地下连廊", "取餐柜", "取餐架", "保安亭", "值班室",
            "卸货区", "电梯厅", "消防门", "茶水间", "等候区", "停车带", "会议室",
            "访客口", "闸机", "连廊", "电梯口", "急诊楼", "门诊楼", "住院部",
        )
        val ALIGNMENT_IGNORED_CHARACTERS = setOf(
            '，', '。', '！', '？', '、', ',', '.', '!', '?', '：', ':', '；', ';',
            '‘', '’', '“', '”', '"', '\'',
        )
        val CONTEXT_ONLY_INTENTS = setOf(CALLBACK_YES, CALLBACK_NO, URGENT_YES, URGENT_NO, "provide_details")
        val SCORED_INTENT_ORDER = compareBy<ScoredIntent> { it.score }.thenBy { -it.order }
    }
}
