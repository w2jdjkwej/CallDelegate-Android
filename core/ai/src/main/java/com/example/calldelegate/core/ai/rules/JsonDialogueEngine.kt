package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.PerformanceTrace
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.EntityExtractor
import com.example.calldelegate.domain.api.IntentClassifier
import com.example.calldelegate.domain.model.CallNature
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.DialogueDecision
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SceneConfidenceState
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.example.calldelegate.domain.model.SlotExtractionRequest

class JsonDialogueEngine(
    private val provider: RuleProvider,
    private val classifier: IntentClassifier,
    private val extractor: EntityExtractor,
) : DialogueEngine {
    override suspend fun opening(sessionId: String): DialogueDecision =
        openingDecision(sessionId, null)

    override suspend fun opening(sessionId: String, initialScene: SceneType): DialogueDecision =
        openingDecision(sessionId, initialScene)

    private suspend fun openingDecision(
        sessionId: String,
        initialScene: SceneType?,
    ): DialogueDecision {
        val rules = loadRules()
        val initialScenario = rules?.scenarios?.firstOrNull {
            it.sceneId == initialScene?.id
        }
        val context = DialogueContext(
            sessionId = sessionId,
            scene = initialScenario?.sceneId?.let(SceneType::fromId) ?: SceneType.UNCLASSIFIED,
            stateId = initialScenario?.initialState ?: "route",
        )
        return DialogueDecision(
            context = context,
            reply = rules?.openingFor(context.languageTag) ?: DEFAULT_OPENING,
            matchedIntent = null,
            shouldEnd = false,
        ).withNlg(
            scene = initialScenario?.sceneId?.let(SceneType::fromId),
            templateId = "opening",
        )
    }

    override suspend fun process(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
    ): DialogueDecision = processInternal(context, callerText, recognitionFailed, enabledScenes, null)

    override suspend fun processWithEvidence(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
        secondaryRecognition: SecondaryRecognitionEvidence?,
    ): DialogueDecision = processInternal(
        context,
        callerText,
        recognitionFailed,
        enabledScenes,
        secondaryRecognition,
    )

    private suspend fun processInternal(
        context: DialogueContext,
        callerText: String?,
        recognitionFailed: Boolean,
        enabledScenes: Set<SceneType>,
        secondaryRecognition: SecondaryRecognitionEvidence?,
    ): DialogueDecision = PerformanceTrace.suspendSection("nlu_process") {
        val rules = loadRules() ?: return fatalFallback(context)
        if (context.fallbackStage > 0) return processFallback(rules, context, callerText.orEmpty())
        if (recognitionFailed || callerText.isNullOrBlank()) return retryOrFallback(rules, context)

        val resolvingClarification = context.pendingClarificationScenes.isNotEmpty()
        val lockedScenario = rules.scenarios.firstOrNull { it.sceneId == context.scene.id }
        // Dropping the locked state while a clarification is outstanding is right when the scene
        // itself is in doubt -- the state belongs to a scene we may be about to leave. It is wrong
        // once the scene is settled and only a turn went unrecognised: after 那这套房现在有租客吗
        // clarified, the state went with it, and 不需要回电 stopped being an answer to anything.
        // ...and only while there is another scene it might turn out to be. A clarification whose
        // candidates are just the scene we are already in is about the purpose of one turn, not
        // about which call this is, and dropping the state for it cost the next answer: F3's
        // 就问一下最近两年有没有住过院 clarified inside insurance_finance, and 需要回电 after it was
        // no longer an answer to anything, so the call could not be ended.
        val anotherSceneInPlay = context.pendingClarificationScenes.any { it != context.scene.id }
        val sceneStillInDoubt = resolvingClarification &&
            anotherSceneInPlay &&
            context.sceneConfidenceState != SceneConfidenceState.CONFIRMED
        val lockedState = lockedScenario?.states?.firstOrNull { it.stateId == context.stateId }
            ?.takeUnless { sceneStillInDoubt }
        val sceneScope = clarificationScope(context, enabledScenes)
        val classification = classifier.classifyDetailed(
            text = callerText,
            enabledScenes = sceneScope,
            context = RuleClassificationContext(
                // Released for the same reason the state is, and only for the same reason. An
                // outstanding clarification that names no scene but the current one is about a
                // turn, not about which call this is, and letting go of the scene there left
                // 需要回电 with nothing to be an answer to: the state survived, the scene did not,
                // and a short reply cannot be read without one. F3 could not be ended by answering
                // its question.
                lockedScene = context.scene.takeIf { it != SceneType.UNCLASSIFIED && !sceneStillInDoubt },
                stateId = lockedState?.stateId,
                // Slots the capture state cares about have to stay collectable while a
                // follow-up state is answering, or a caller who changes their mind is not heard:
                // ask_callback wants only callbackNeeded, so 那我先放驿站吧 extracted no location
                // and the reply went on quoting the one captured two turns earlier.
                expectedSlots = buildSet {
                    lockedState?.expectedSlots?.let(::addAll)
                    if (lockedState?.resumesCapture == true) {
                        lockedScenario?.states
                            ?.firstOrNull { it.stateId == lockedScenario.initialState }
                            ?.expectedSlots?.let(::addAll)
                    }
                },
                // Slots are narrowed to the cross-scene ones when the scene may be about to change
                // under them; a clarification within one scene is not that, and dropping what the
                // caller has already given would make them say it twice.
                existingSlots = if (sceneStillInDoubt) {
                    context.slots.filterKeys { it in CROSS_SCENE_SLOTS }
                } else {
                    context.slots
                },
                // A state that hands business turns back to its capture state has to let the
                // classifier see the capture state's intents, or the handover never happens: while
                // ask_callback allowed only callback_yes and callback_no, 那这套房现在有租客吗 was
                // understood as neither and fell to a clarification -- which set
                // pendingClarificationScenes, dropped the locked state, and left the next turn,
                // 不需要回电, unrecognisable too. The call could not be ended by answering it.
                allowedIntentIds = buildSet {
                    lockedState?.transitions.orEmpty()
                        .mapNotNullTo(this) { it.intentId.takeUnless { id -> id == "*" } }
                    if (lockedState?.resumesCapture == true) {
                        lockedScenario?.states
                            ?.firstOrNull { it.stateId == lockedScenario.initialState }
                            ?.transitions.orEmpty()
                            .mapNotNullTo(this) { it.intentId.takeUnless { id -> id == "*" } }
                    }
                },
                languageTag = context.languageTag,
                secondaryRecognition = secondaryRecognition,
            ),
        ) ?: return fatalFallback(context)

        val proposedScene = classification.scene?.let(SceneType::fromId)?.takeIf { it != SceneType.UNCLASSIFIED }
        val switchedScene = context.scene != SceneType.UNCLASSIFIED && proposedScene != null && proposedScene != context.scene
        val retainedSlots = if (switchedScene) context.slots.filterKeys { it in CROSS_SCENE_SLOTS } else context.slots
        val intentSlot = deliveryIntentSlots(
            scene = proposedScene ?: context.scene,
            classification = classification,
        )
        val mergedSlots = retainedSlots + classification.extractedSlots + intentSlot
        val classifiedContext = context.copy(
            slots = mergedSlots,
            topicScene = classification.topicScene?.let(SceneType::fromId) ?: context.topicScene,
            callNature = classification.callNature.takeUnless { it == CallNature.UNKNOWN } ?: context.callNature,
            riskLevel = maxOf(context.riskLevel, classification.riskLevel),
            lastConfidence = classification.confidence,
            lastSceneMargin = classification.sceneMargin,
            sceneConfidenceState = when {
                proposedScene == null -> SceneConfidenceState.UNKNOWN
                classification.shouldClarify -> SceneConfidenceState.PROVISIONAL
                else -> SceneConfidenceState.CONFIRMED
            },
        )

        val protectedSoftRiskTopic = classification.riskLevel == RiskLevel.HIGH &&
            classification.scene != SceneType.SPAM_RISK.id &&
            classification.scene != null &&
            !classification.shouldClarify &&
            classification.confidence >= rules.safety.protectedTopicScore &&
            classification.riskReasons.isNotEmpty() &&
            classification.riskReasons.all { it in rules.safety.softRiskIds }

        if (classification.riskLevel == RiskLevel.HIGH && !protectedSoftRiskTopic) {
            val safeScene = when {
                classification.scene == SceneType.SPAM_RISK.id -> SceneType.SPAM_RISK
                context.scene != SceneType.UNCLASSIFIED -> context.scene
                proposedScene != null -> proposedScene
                SceneType.SPAM_RISK in enabledScenes -> SceneType.SPAM_RISK
                else -> SceneType.UNCLASSIFIED
            }
            return DialogueDecision(
                context = classifiedContext.copy(
                    scene = safeScene,
                    stateId = RISK_END_STATE,
                    pendingClarificationScenes = emptyList(),
                ),
                reply = selectRiskReply(rules, classification, context.languageTag),
                matchedIntent = classification.intent,
                shouldEnd = true,
                classification = classification.withFinalScene(
                    safeScene,
                    classification.debugTrace?.finalSceneSource
                        ?.takeIf { it == "RISK_OVERRIDE" || it == "RISK_FALLBACK" }
                        ?: "RISK_SAFE_END",
                ),
            ).withNlg(
                scene = safeScene,
                templateId = "risk:${classification.riskReasons.joinToString("+").ifBlank { "high" }}",
            )
        }

        val entityScene = proposedScene ?: entityDrivenScene(classification, enabledScenes)
        if (
            (classification.shouldClarify || classification.scene == null) &&
            classification.confidence < rules.classification.thresholds.minimumIntentScore &&
            entityScene != null &&
            hasInsuranceEntity(classification)
        ) {
            return DialogueDecision(
                context = classifiedContext.copy(
                    scene = entityScene,
                    pendingClarificationScenes = listOf(entityScene.id),
                ),
                reply = entityDrivenReply(classification),
                matchedIntent = null,
                shouldEnd = false,
                classification = classification.withFinalScene(entityScene, "ENTITY_DRIVEN_CLARIFICATION"),
            ).withNlg(
                scene = entityScene,
                templateId = "entity_driven_clarification",
                variables = classification.extractedSlots.filterKeys { it in INSURANCE_ENTITY_KEYS },
            )
        }

        if (classification.shouldClarify) {
            // Asking again costs a retry like any other failure to understand. Without this the
            // branch returned the same prompt forever: 嗯嗯嗯 answered a real_estate callback
            // question with 抱歉，我没有听清 nine turns running, retryCount still 0, and nothing in
            // the engine would ever have ended that call.
            val clarifyRetry = classifiedContext.retryCount + 1
            val clarifyBudget = rules.scenarios.firstOrNull { it.sceneId == context.scene.id }
                ?.states?.firstOrNull { it.stateId == context.stateId }
                ?.retryStrategy?.maxRetries ?: rules.fallback.maxRetries
            if (clarifyRetry > clarifyBudget) {
                return retryOrFallback(rules, classifiedContext, heard = true).copy(
                    classification = classification.withFinalScene(context.scene, "CLARIFICATION_EXHAUSTED"),
                )
            }
            return DialogueDecision(
                context = classifiedContext.copy(
                    scene = proposedScene ?: context.scene,
                    retryCount = clarifyRetry,
                    pendingClarificationScenes = classification.sceneCandidates,
                ),
                reply = classification.clarificationPrompt ?: rules.fallback.localizedFor(context.languageTag).retryPrompt,
                matchedIntent = null,
                shouldEnd = false,
                classification = classification.withFinalScene(proposedScene ?: context.scene, "CLARIFICATION"),
            ).withNlg(
                scene = proposedScene ?: context.scene,
                templateId = "clarification",
            )
        }

        if (context.scene == SceneType.UNCLASSIFIED && proposedScene == null) {
            return retryOrFallback(rules, classifiedContext, heard = true).copy(
                classification = classification.withFinalScene(context.scene, "RETRY_FALLBACK"),
            )
        }

        val scene = proposedScene ?: context.scene
        val scenario = rules.scenarios.firstOrNull { it.sceneId == scene.id }
            ?: return retryOrFallback(rules, classifiedContext, heard = true).copy(
                classification = classification.withFinalScene(context.scene, "RETRY_FALLBACK"),
            )
        val stateId = if (context.scene == SceneType.UNCLASSIFIED || switchedScene) scenario.initialState else context.stateId
        val state = scenario.states.firstOrNull { it.stateId == stateId } ?: return fatalFallback(classifiedContext)
        val missingSlots = state.requiredSlots.filter { mergedSlots[it].isNullOrBlank() }
        val answersWithoutSlots = state.transitions
            .firstOrNull { it.intentId == classification.intent }
            ?.skipsRequiredSlots == true
        if (missingSlots.isNotEmpty() && !answersWithoutSlots &&
            (classification.intent != null || classification.extractedSlots.isNotEmpty())
        ) {
            val missingSlot = missingSlots.first()
            val missingSlotState = state.missingSlotStates[missingSlot] ?: stateId
            return DialogueDecision(
                context = classifiedContext.copy(
                    scene = scene,
                    stateId = missingSlotState,
                    retryCount = 0,
                    pendingClarificationScenes = emptyList(),
                ),
                reply = state.transitions
                    .firstOrNull { it.intentId == classification.intent }
                    ?.slotAcknowledgementFor(context.languageTag)
                    .orEmpty()
                    .plus(
                        state.missingSlotPrompt(missingSlot, context.languageTag)
                            ?: state.fallbackReplyFor(context.languageTag),
                    ),
                matchedIntent = classification.intent,
                shouldEnd = false,
                classification = classification.withFinalScene(scene, "DIALOGUE_COMMIT"),
            ).withNlg(
                scene = scene,
                templateId = "missing_slot:${scene.id}:$stateId:$missingSlot",
                variables = mapOf(missingSlot to "missing"),
            )
        }

        // A caller who keeps talking about the business after being asked about a callback is not
        // failing to answer; they are still on the first subject. Hand the turn back to the capture
        // state when it has a reply for it by name, so the assistant answers and asks again rather
        // than ending the call on them.
        val resumedTransition = if (state.resumesCapture && classification.intent != null) {
            scenario.states
                .firstOrNull { it.stateId == scenario.initialState }
                ?.transitions
                ?.firstOrNull { it.intentId == classification.intent }
        } else {
            null
        }
        val transition = state.transitions.firstOrNull { it.intentId == classification.intent }
            ?: resumedTransition
            ?: state.transitions.firstOrNull {
                it.intentId == "*" && (classification.intent != null || classification.extractedSlots.isNotEmpty())
            }
        if (transition == null) {
            return retryOrFallback(
                rules,
                classifiedContext.copy(scene = scene, stateId = stateId),
                heard = true,
            ).copy(classification = classification.withFinalScene(scene, "RETRY_FALLBACK"))
        }

        val nextState = scenario.states.firstOrNull { it.stateId == transition.nextState }
        val transitionedSlots = mergedSlots + transition.slotUpdates
        val reply = fillTemplate(
            transition.replyFor(context.languageTag).ifBlank {
                nextState?.systemQuestionFor(context.languageTag) ?: state.fallbackReplyFor(context.languageTag)
            },
            transitionedSlots,
        )
        return DialogueDecision(
            context = classifiedContext.copy(
                scene = scene,
                stateId = transition.nextState,
                slots = transitionedSlots,
                retryCount = 0,
                pendingClarificationScenes = emptyList(),
            ),
            reply = reply,
            matchedIntent = classification.intent,
            shouldEnd = transition.end || nextState?.endCondition == "always",
            classification = classification.withFinalScene(scene, "DIALOGUE_COMMIT"),
        ).withNlg(
            scene = scene,
            templateId = "transition:${scene.id}:$stateId:${transition.intentId}",
            variables = transitionedSlots,
        )
    }

    private fun RuleClassificationResult.withFinalScene(
        scene: SceneType,
        source: String,
    ): RuleClassificationResult = copy(
        debugTrace = debugTrace?.copy(
            finalScene = scene.id,
            finalSceneSource = source,
        ),
    )

    private suspend fun processFallback(
        rules: DialogueRuleFile,
        context: DialogueContext,
        text: String,
    ): DialogueDecision {
        val fallback = rules.fallback.localizedFor(context.languageTag)
        return when (context.fallbackStage) {
            1 -> {
                val extracted = extractor.extract(
                    SlotExtractionRequest(
                        text = text,
                        expectedSlots = setOf("urgent"),
                        existingSlots = context.slots,
                        scene = context.scene,
                        stateId = context.stateId,
                        languageTag = context.languageTag,
                    ),
                )
                DialogueDecision(
                    context.copy(stateId = "__fallback_callback", slots = context.slots + extracted.slots, fallbackStage = 2),
                    fallback.callbackQuestion,
                    null,
                    false,
                ).withNlg(
                    scene = context.scene,
                    templateId = "fallback.callback",
                    fallbackReason = "fallback_stage_1",
                )
            }
            else -> {
                val extracted = extractor.extract(
                    SlotExtractionRequest(
                        text = text,
                        expectedSlots = setOf("callbackNeeded"),
                        existingSlots = context.slots,
                        scene = context.scene,
                        stateId = context.stateId,
                        languageTag = context.languageTag,
                    ),
                )
                DialogueDecision(
                    context.copy(stateId = "__fallback_end", slots = context.slots + extracted.slots, fallbackStage = 3),
                    fallback.closingReply,
                    null,
                    true,
                ).withNlg(
                    scene = context.scene,
                    templateId = "fallback.closing",
                    fallbackReason = "fallback_stage_2",
                )
            }
        }
    }

    /**
     * @param heard whether words came back from the recogniser. False means ask for them again;
     *   true means they arrived and their purpose did not, which is a different request to make.
     */
    private fun retryOrFallback(
        rules: DialogueRuleFile,
        context: DialogueContext,
        heard: Boolean = false,
    ): DialogueDecision {
        val fallback = rules.fallback.localizedFor(context.languageTag)
        val maxRetries = if (context.scene == SceneType.UNCLASSIFIED) {
            rules.fallback.maxRetries
        } else {
            rules.scenarios.firstOrNull { it.sceneId == context.scene.id }
                ?.states?.firstOrNull { it.stateId == context.stateId }
                ?.retryStrategy?.maxRetries ?: rules.fallback.maxRetries
        }
        val nextRetry = context.retryCount + 1
        return if (nextRetry <= maxRetries) {
            val statePrompt = rules.scenarios.firstOrNull { it.sceneId == context.scene.id }
                ?.states?.firstOrNull { it.stateId == context.stateId }
                ?.retryStrategy?.promptFor(context.languageTag)
            DialogueDecision(
                context = context.copy(retryCount = nextRetry),
                reply = statePrompt ?: if (heard) fallback.purposeOrRetryPrompt else fallback.retryPrompt,
                matchedIntent = null,
                shouldEnd = false,
                recognitionFailure = true,
            ).withNlg(
                scene = context.scene,
                templateId = "fallback.retry",
                fallbackReason = "retry_$nextRetry",
            )
        } else {
            DialogueDecision(
                context = context.copy(stateId = "__fallback_emergency", retryCount = 0, fallbackStage = 1),
                reply = fallback.emergencyQuestion,
                matchedIntent = null,
                shouldEnd = false,
                recognitionFailure = true,
            ).withNlg(
                scene = context.scene,
                templateId = "fallback.emergency",
                fallbackReason = "retry_exhausted",
            )
        }
    }

    private fun clarificationScope(context: DialogueContext, enabledScenes: Set<SceneType>): Set<SceneType> {
        if (context.sceneConfidenceState == SceneConfidenceState.PROVISIONAL) return enabledScenes
        if (context.pendingClarificationScenes.isEmpty()) return enabledScenes
        val pending = context.pendingClarificationScenes.map(SceneType::fromId).toSet() - SceneType.UNCLASSIFIED
        return enabledScenes.intersect(pending).ifEmpty { enabledScenes }
    }

    private suspend fun loadRules(): DialogueRuleFile? = when (val loaded = provider.load()) {
        is AppResult.Success -> loaded.value
        is AppResult.Failure -> null
    }

    private fun fillTemplate(template: String, slots: Map<String, String>): String =
        Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}").replace(template) { match ->
            slots[match.groupValues[1]] ?: "相关信息"
        }

    private fun entityDrivenScene(
        classification: RuleClassificationResult,
        enabledScenes: Set<SceneType>,
    ): SceneType? = SceneType.INSURANCE_FINANCE.takeIf {
        it in enabledScenes && hasInsuranceEntity(classification)
    }

    private fun hasInsuranceEntity(classification: RuleClassificationResult): Boolean =
        classification.extractedSlots.keys.any { it in INSURANCE_ENTITY_KEYS }

    private fun entityDrivenReply(classification: RuleClassificationResult): String {
        val insuranceType = classification.extractedSlots["insuranceType"]
        val serviceType = classification.extractedSlots["serviceType"]
        return when {
            !insuranceType.isNullOrBlank() ->
                "我已记录与${insuranceType}相关的事项。请说明是理赔、续保、保单服务还是产品介绍。"
            !serviceType.isNullOrBlank() ->
                "我已记录${serviceType}相关事项。请补充说明具体需要机主处理什么。"
            else ->
                "我已识别到保险金融事项。请说明是理赔、续保、保单服务还是产品介绍。"
        }
    }

    private fun selectRiskReply(
        rules: DialogueRuleFile,
        classification: RuleClassificationResult,
        languageTag: String,
    ): String {
        val riskIds = classification.riskReasons.toSet()
        val tier = when {
            riskIds.any { it in L1_RISK_IDS } -> "L1"
            riskIds.any { it in L2_RISK_IDS } -> "L2"
            riskIds.any { it in L3_RISK_IDS } -> "L3"
            else -> null
        }
        if (tier != null) {
            rules.safety.localizedRiskReplies[languageTag]?.get(tier)?.let { return it }
            rules.safety.riskReplies[tier]?.let { return it }
        }
        return rules.safety.localizedHighRiskReplies[languageTag] ?: rules.safety.highRiskReply
    }

    private fun deliveryIntentSlots(
        scene: SceneType,
        classification: RuleClassificationResult,
    ): Map<String, String> {
        val intentId = classification.intent
        if (scene != SceneType.DELIVERY || intentId == null) return emptyMap()
        val value = DELIVERY_INTENT_VALUES[intentId] ?: return emptyMap()
        return buildMap {
            put("deliveryIntent", value)
            put("deliveryIntentScore", classification.confidence.toString())
            val decisionRule = classification.matchedEvidence
                .firstOrNull { it.startsWith(DELIVERY_INTENT_PRIORITY_PREFIX) }
                ?.substringAfterLast(':')
                ?: DELIVERY_WEIGHTED_RULE
            put("deliveryIntentDecisionRule", decisionRule)
            val matchedEvidence = classification.matchedEvidence
                .filter { evidence ->
                    evidence.startsWith(DELIVERY_INTENT_PRIORITY_PREFIX) ||
                        evidence.contains(":$intentId:")
                }
                .joinToString("|")
            put("deliveryIntentMatchedEvidence", matchedEvidence)
            val rejectedCandidates = classification.rejectedEvidence
                .filter { it.startsWith(DELIVERY_INTENT_REJECTED_PREFIX) }
                .map { it.substringAfterLast(':') }
                .distinct()
                .joinToString(",")
            put("deliveryIntentRejectedCandidates", rejectedCandidates)
        }
    }

    private fun fatalFallback(context: DialogueContext) = DialogueDecision(
        context = context.copy(stateId = "error"),
        reply = "抱歉，代接服务暂时不可用。我会记录本次来电，请稍后再联系机主。",
        matchedIntent = null,
        shouldEnd = true,
        recognitionFailure = true,
    ).withNlg(
        scene = context.scene,
        templateId = "system.fatal",
        fallbackReason = "rule_load_or_engine_failure",
    )

    private fun DialogueDecision.withNlg(
        scene: SceneType?,
        templateId: String?,
        variables: Map<String, String> = emptyMap(),
        fallbackReason: String? = null,
    ): DialogueDecision {
        val compliance = ReplyCompliance.evaluate(scene, reply)
        val safeVariables = variables.filter { (key, _) ->
            key !in SENSITIVE_REPLY_VARIABLE_KEYS && reply.contains("{$key}")
        }
        return copy(
            replyTemplateId = templateId,
            replyVariables = safeVariables,
            isFallbackTemplate = fallbackReason != null,
            fallbackReason = fallbackReason,
            replySafe = compliance.safe,
            complianceFlags = compliance.flags,
        )
    }

    private companion object {
        /**
         * Ends on 事 rather than 事情, because the caller has to hear the last syllable.
         *
         * Callers reported the greeting stopping part way through 事. The audio was not the
         * problem: its envelope carries all ten syllables of 您好，请问您有什么事情, and its final
         * syllable peaks higher than that of a reply confirmed audible by ear. But that syllable is
         * the neutral-tone 情 of 事情 -- short, weak and high-frequency, which is what an 8 kHz
         * telephone channel takes first. Replies that survive the channel end on full-tone
         * syllables: 回电, 结束. The greeting is the one turn with no context to reconstruct a lost
         * ending from, so it ends on a stressed syllable too.
         */
        const val DEFAULT_OPENING = "您好，请问您有什么事？"
        const val RISK_END_STATE = "risk_end"
        val INSURANCE_ENTITY_KEYS = setOf(
            "insuranceType",
            "serviceType",
            "expiryTime",
            "contactPurpose",
            "organization",
        )
        val L1_RISK_IDS = setOf(
            "request_sms_code",
            "request_password",
            "request_partial_identity",
            "request_screen_share",
            "impersonate_authority",
        )
        val L2_RISK_IDS = setOf(
            "request_transfer",
            "request_investment_action",
            "guided_banking_operation",
            "auto_billing_threat",
        )
        val L3_RISK_IDS = setOf(
            "request_unknown_channel",
            "request_unknown_app",
        )
        const val DELIVERY_INTENT_PRIORITY_PREFIX = "delivery:intent_priority:"
        const val DELIVERY_INTENT_REJECTED_PREFIX = "delivery:intent_priority:rejected:"
        const val DELIVERY_WEIGHTED_RULE = "weighted_rule"
        val SENSITIVE_REPLY_VARIABLE_KEYS = setOf(
            "contact",
            "amount",
            "rate",
            "premium",
            "coverage",
            "sensitiveInfoType",
        )
        val CROSS_SCENE_SLOTS = setOf("contact", "urgent", "callbackNeeded")
        val DELIVERY_INTENT_VALUES = mapOf(
            "delivery_arrived" to "arrived",
            "delivery_placed" to "placed",
            "delivery_location_query" to "location_query",
            "delivery_access_blocked" to "access_blocked",
            "delivery_unreachable" to "unreachable",
            "delivery_delayed" to "delayed",
            "delivery_item_issue" to "item_issue",
        )
    }
}
