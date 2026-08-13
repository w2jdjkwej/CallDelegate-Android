package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.model.CallNature
import com.example.calldelegate.domain.model.RiskLevel
import kotlinx.serialization.Serializable

@Serializable
data class DialogueRuleFile(
    val schemaVersion: Int,
    val openingPrompt: String,
    val fallback: FallbackRule,
    val scenarios: List<ScenarioRule>,
    val lang: String = "zh-CN",
    val openingPrompts: Map<String, String> = emptyMap(),
    val classification: ClassificationRuleConfig = ClassificationRuleConfig(),
    val languages: Map<String, LanguageRuleConfig> = emptyMap(),
    val safety: SafetyRuleConfig = SafetyRuleConfig(),
)

@Serializable
data class ClassificationRuleConfig(
    val weights: RuleWeights = RuleWeights(),
    val thresholds: RuleThresholds = RuleThresholds(),
    val clarificationPromptTemplate: String = "请确认来电是{firstScene}还是{secondScene}？",
    val localizedClarificationPromptTemplates: Map<String, String> = emptyMap(),
    val spamRiskSemantics: SpamRiskSemanticConfig = SpamRiskSemanticConfig(),
    val evidenceCombination: EvidenceCombinationConfig = EvidenceCombinationConfig(),
    val sharedVocabulary: SharedVocabularyConfig = SharedVocabularyConfig(),
)

/**
 * Terms that belong to several scenes at once, scored for all of them instead of taken from all
 * of them.
 *
 * Until now a phrase two scenes could both utter had exactly two fates, and both threw information
 * away. Left in one scene's vocabulary it decided the call on its own: 我已经到了 was a
 * ride_hailing core keyword and nothing else, so 我已经到了写字楼前台，需要登记您的姓名才能把餐
 * 送进去 went to ride_hailing at confidence 1.00. Removed from every vocabulary -- which is what
 * happened to 南门, 定位, 门禁, 物业, 中介, 佣金 and 发票, one at a time, each time a test caught
 * them -- it scored nothing at all, and turns that said little else fell to null.
 *
 * Neither fate is what the phrase means. 我到了 does not name an industry and never did, but it is
 * not silent either: it says this is an arrival, which is a real narrowing to the two or three
 * scenes where somebody arrives. [weight] is what that narrowing is worth, and it is deliberately
 * set below RuleThresholds.minimumSceneScore so that a shared term can raise a scene into
 * contention and can never, alone, select it. What decides between the contenders is the rest of
 * the sentence; if the rest of the sentence decides nothing, the scores stay level, the margin
 * stays inside RuleThresholds.clarificationMargin, and the caller is asked -- which is the honest
 * answer to a turn that genuinely did not say.
 */
@Serializable
data class SharedVocabularyConfig(
    val enabled: Boolean = true,
    /**
     * Contribution of a group whose terms appear in the turn, counted once per group however many
     * of its terms matched -- three ways of saying "I have arrived" are one claim, not three.
     *
     * Must stay under RuleThresholds.minimumSceneScore. At the current 0.30 against 0.38: a shared
     * term alone leaves every scene short of selection; a shared term plus one auxiliary keyword
     * (0.15) selects at 0.45 while the scene that had only the shared term sits at 0.30, a margin
     * of 0.15 that is still inside clarificationMargin and so still asks; a shared term plus a core
     * keyword (0.40) selects at 0.70 against 0.30 and does not ask. Evidence decides, in the order
     * that evidence should.
     */
    val weight: Float = 0.30f,
    val groups: List<SharedVocabularyGroup> = emptyList(),
)

@Serializable
data class SharedVocabularyGroup(
    val groupId: String,
    val terms: List<String> = emptyList(),
    /**
     * The scenes entitled to this group, every one of which is credited equally.
     *
     * Equally, and across all of a scene's scene-defining intents, because a shared term says which
     * kind of call this is and nothing finer. Crediting a named intent instead let the table
     * reorder intents inside a scene that was never in doubt: 定位 pointed at
     * confirm_pickup_location, so App定位不一致，我没看到您 stopped being cannot_find_passenger,
     * and 退款 pointed at refund_progress, so 您的退款包含现金优惠券和积分三部分会分别退回 stopped
     * being refund_notice. Which intent it is was already decided, correctly, by the scene's own
     * vocabulary; the shared term has nothing to add to that question and must not answer it.
     *
     * A term a scene owns outright does not belong here for that scene. 退款 is customer_service's
     * own word, and listing insurance_finance beside it on the strength of 这笔退款正在等待银行入账
     * -- a turn that went astray on 银行入账, not on 退款 -- handed insurance_finance turns it had
     * no claim to.
     */
    val targets: List<String> = emptyList(),
    /** Overrides [SharedVocabularyConfig.weight] for groups that narrow more, or less, than most. */
    val weight: Float? = null,
)

/**
 * Controls evidence composition without changing the default behavior of other scenes.
 * Weights and the clarification threshold remain calibration parameters; this model only
 * provides the structure needed to evaluate them on develop/holdout/guard data.
 */
@Serializable
data class EvidenceCombinationConfig(
    val enabledScenes: List<String> = emptyList(),
    val comboBonus: Float = 0f,
    val minimumEvidenceTypes: Int = 2,
    val singleEvidenceCap: Float? = null,
    /** Provisional value; calibrate against develop/holdout/guard before release. */
    val entityEvidenceWeight: Float = 0f,
    val domainAxes: Map<String, EvidenceAxisConfig> = emptyMap(),
)

@Serializable
data class EvidenceAxisConfig(
    val entity: List<String> = emptyList(),
    val action: List<String> = emptyList(),
    val state: List<String> = emptyList(),
    val clause: List<String> = emptyList(),
)

@Serializable
data class SpamRiskSemanticConfig(
    val enabled: Boolean = false,
    val natureThreshold: Float = 0.55f,
    val weakCandidateThreshold: Float = 0.38f,
    val comboBonus: Float = 0.15f,
    val openingDetectionEnabled: Boolean = false,
    val openingWeight: Float = 0.60f,
    val primitives: List<SpamRiskPrimitiveRule> = emptyList(),
    val exemptionPatterns: List<String> = emptyList(),
    val openingPatterns: List<String> = emptyList(),
)

@Serializable
data class SpamRiskPrimitiveRule(
    val primitiveId: String,
    val weight: Float,
    val patterns: List<String> = emptyList(),
    /**
     * Which spam_risk intent this primitive is evidence for.
     *
     * The semantic layer used to hand its score to marketing_pitch whatever it had matched, so
     * 这里是公安机关，您的银行卡涉及案件，禁止挂断电话 was answered 谢谢，机主目前不考虑相关服务 --
     * the right scene and the wrong register entirely, since each spam intent replies differently
     * and only coercion refuses to act under threat. A primitive knows what it saw; it should say
     * so. Defaults to marketing_pitch, which is what every primitive meant before this existed.
     */
    val intentId: String = "marketing_pitch",
)

@Serializable
data class RuleWeights(
    val coreRegex: Float = 0.60f,
    val coreKeyword: Float = 0.40f,
    val auxiliaryKeyword: Float = 0.15f,
    val currentSceneContext: Float = 0.25f,
    val explicitNegation: Float = -0.80f,
    val conflictingSceneCore: Float = -0.30f,
    val correctionTarget: Float = 0.15f,
    val semanticPolarity: Float = 0.40f,
    val preemptiveDenial: Float = 0.35f,
    /** Weight of a scene anchor. Set above minimumSceneScore so one anchor names the domain. */
    val sceneAnchor: Float = 0.45f,
    /**
     * Added for each distinct auxiliary keyword beyond the first, up to
     * [RuleThresholds.maximumAuxiliaryAccumulation].
     *
     * A single auxiliary term is weak on its own, which is why it scores 0.15 and stays under every
     * threshold. Several distinct ones together are not the same claim repeated -- 租金 with 物业费
     * with 押一付三 is three independent signals of one domain -- yet the score was added once
     * regardless of how many matched, so those turns ended at exactly 0.15 and were dropped.
     */
    val auxiliaryAccumulation: Float = 0.12f,
    /**
     * Weight of a sentence template, multiplied by how well it matched (0..1), so a template that
     * is fully present and accounts for the whole turn contributes this and a partial one less.
     *
     * Above [coreRegex] because it is strictly more evidence: a regex says a wording occurred
     * somewhere in the turn, a template says the turn *has that shape* and how much of it is left
     * unexplained. Provisional -- calibrate on blind material, not on the tuned corpora.
     */
    val template: Float = 0.70f,
)

@Serializable
data class RuleThresholds(
    val minimumSceneScore: Float = 0.38f,
    val minimumIntentScore: Float = 0.35f,
    val clarificationMargin: Float = 0.18f,
    val sceneSwitchScore: Float = 0.50f,
    /** Provisional value; it must be calibrated with develop/holdout/guard data. */
    val clarificationScore: Float = 0f,
    /**
     * How much a scene anchor is worth when ranking scenes against each other, on top of the
     * [RuleWeights.sceneAnchor] the anchor already contributed to the score.
     *
     * An anchor used to be an absolute tier: any scene that produced one outranked every scene that
     * did not, at any score. That is too strong to be true of a word. 您投诉的问题涉及商家配送员和平台
     * 三方责任，我们会先核对录音…… is a service agent describing a complaint about a delivery, and the
     * single anchor 配送员 handed it to delivery at 0.67 over customer_service at 1.51 -- more than
     * twice the score, unable to win at any score.
     *
     * As a large bonus rather than a tier, an anchor still decides every ordinary contest and can
     * still be overturned by evidence that is not close. The value is bounded on both sides by cases
     * that must keep working: the ride-hailing arrival pattern taking delivery turns containing
     * 配送员 needs it above 0.15 (0.60 against 0.45), and the complaint turn above needs it below
     * 0.84. It is set equal to [RuleWeights.sceneAnchor], which is the middle of that range and says
     * something meaningful -- an anchor counts once as evidence and once as domain priority.
     */
    val anchorDomainPriority: Float = 0.45f,
)

@Serializable
data class LanguageRuleConfig(
    val stripWhitespace: Boolean = false,
    val replacements: Map<String, String> = emptyMap(),
    val correctionPatterns: List<String> = emptyList(),
    val positiveShortAnswers: List<String> = emptyList(),
    val negativeShortAnswers: List<String> = emptyList(),
)

@Serializable
data class FallbackRule(
    val maxRetries: Int = 2,
    /** Said when nothing usable came back from the recogniser. Asks for the words again. */
    val retryPrompt: String,
    val emergencyQuestion: String,
    val callbackQuestion: String,
    val closingReply: String,
    /**
     * Said when the words arrived and their purpose did not.
     *
     * These were one sentence, 抱歉，我没有听清。请您用一句话说明来电事项, and they are two
     * different problems with two different remedies. A caller whose speech was lost needs to say
     * it again; a caller whose speech was heard and not understood needs to say something *else*,
     * and telling them the assistant did not hear invites them to repeat the same words to the
     * same result. On the device on 2026-08-08 a caller said it three times and was told 我没有听清
     * every time, which was untrue by the second turn: the transcript was there, the purpose was
     * not.
     *
     * Defaults to [retryPrompt] so a rule file that has not been updated behaves exactly as before.
     * Declared after the required prompts so adding it did not renumber anyone's positional
     * construction.
     */
    val purposePrompt: String = "",
    val localized: Map<String, LocalizedFallbackRule> = emptyMap(),
)

@Serializable
data class LocalizedFallbackRule(
    val retryPrompt: String,
    val emergencyQuestion: String,
    val callbackQuestion: String,
    val closingReply: String,
    val purposePrompt: String = "",
) {
    /** The purpose prompt, or the retry prompt when a rule file has not defined one. */
    val purposeOrRetryPrompt: String get() = purposePrompt.ifBlank { retryPrompt }
}

@Serializable
data class ScenarioRule(
    val sceneId: String,
    val displayName: String,
    val initialState: String,
    val structureFields: List<String>,
    val intents: List<IntentRule>,
    val states: List<StateRule>,
    val displayNames: Map<String, String> = emptyMap(),
    /**
     * Terms that belong to this scene and to no other, keyed by language tag.
     *
     * Intent keywords answer "what is the caller asking for", and many of them are ordinary speech
     * -- a courier, a driver and a visitor all say 我到了. Those intents are marked
     * sceneDefining = false precisely because their wording cannot be trusted to pick a scene, which
     * leaves a scene reachable only through whichever intent happens to carry domain wording.
     *
     * An anchor answers the separate question "which domain is this call from" and is held to a
     * stricter standard: it must be unusable by any other scene. 外卖 and 取件码 qualify; 我到 and
     * 单元 do not. The loader enforces that no two scenes claim the same anchor, so the standard is
     * checked rather than trusted.
     */
    val anchorKeywords: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class IntentRule(
    val intentId: String,
    val keywords: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val regexPatterns: List<String> = emptyList(),
    val negativeKeywords: List<String> = emptyList(),
    val sceneDefining: Boolean? = null,
    val callNature: CallNature = CallNature.UNKNOWN,
    val localeRules: Map<String, LocalizedIntentRule> = emptyMap(),
    val legacyIntentIds: List<String> = emptyList(),
)

@Serializable
data class LocalizedIntentRule(
    val coreKeywords: List<String> = emptyList(),
    val auxiliaryKeywords: List<String> = emptyList(),
    val negativeKeywords: List<String> = emptyList(),
    val coreRegexPatterns: List<String> = emptyList(),
    /**
     * Sentence shapes this intent can be said in. See
     * [com.example.calldelegate.core.ai.rules.template.SentenceTemplate] for the syntax.
     *
     * Unlike the keyword lists, a template is scored by how much of the turn it accounts for as
     * well as by how much of itself was found, so it does not have to be extended once per wording.
     */
    val templates: List<String> = emptyList(),
)

@Serializable
data class StateRule(
    val stateId: String,
    val systemQuestion: String,
    val expectedSlots: List<String> = emptyList(),
    val transitions: List<TransitionRule> = emptyList(),
    val retryStrategy: RetryStrategy = RetryStrategy(),
    val endCondition: String? = null,
    val fallbackReply: String,
    val requiredSlots: List<String> = emptyList(),
    val localized: Map<String, LocalizedStateRule> = emptyMap(),
    /** Optional state entered after asking for a missing slot, keyed by that slot name. */
    val missingSlotStates: Map<String, String> = emptyMap(),
    /**
     * Whether a turn this state has no answer for should be handed back to the scene's capture
     * state instead of falling to the catch-all.
     *
     * Every scene ends its first reply with 请问需要机主回电吗, and ask_callback answered
     * callback_yes, callback_no, and everything else with 当前信息已经记录，我会转告机主。再见.
     * So a caller who said anything other than yes or no was hung up on. An estate agent told that
     * the price is the owner's to settle asks 那大概什么时候方便 next, which is a perfectly good
     * question, and the call ended.
     *
     * Only a turn the capture state answers *by name* is taken back -- its own catch-all is not
     * consulted, or nothing would ever reach this state's. Somebody who keeps talking about the
     * business gets answered and asked again; somebody who says something the scene has no answer
     * for still reaches the ending.
     */
    val resumesCapture: Boolean = false,
)

@Serializable
data class LocalizedStateRule(
    val systemQuestion: String,
    val fallbackReply: String,
    val missingSlotPrompts: Map<String, String> = emptyMap(),
)

@Serializable
data class TransitionRule(
    val intentId: String,
    val nextState: String,
    val replyTemplate: String,
    val end: Boolean = false,
    val localizedReplyTemplates: Map<String, String> = emptyMap(),
    /** Deterministic facts established by this transition, such as accepting a proposed location. */
    val slotUpdates: Map<String, String> = emptyMap(),
    /**
     * What to say about this turn when a required slot is missing and the slot question is asked
     * instead of [replyTemplate].
     *
     * A required slot is asked for before any transition runs, so the question replaced the answer:
     * every delivery turn without a location got 请您放在订单上指定的位置可以吗 and nothing else,
     * including 这个包裹是到付件，签收时需要支付运费. Nineteen of twenty-eight on the fourth blind
     * set. This is the acknowledgement that goes in front of the question, so the caller hears that
     * what they said was understood before being asked for what is still missing. It is the
     * transition's own reply with its trailing question removed -- one question per turn.
     *
     * Empty leaves the previous behaviour exactly as it was: the slot question alone.
     */
    val slotAcknowledgement: String = "",
    val localizedSlotAcknowledgements: Map<String, String> = emptyMap(),
    /**
     * Answer this turn instead of asking for the state's required slots.
     *
     * A required slot is asked for before any transition runs, which is right while the slot is the
     * thing the call is about. It is wrong when the turn has already established that the slot's
     * usual answer does not apply: the delivery scene asks 可以放在订单上指定的位置吗 because leaving
     * the parcel is normally the decision to get, and a courier who has just said the box is
     * cash-on-delivery, refrigerated, or signature-only has said that it cannot be left anywhere.
     * Asking anyway is not merely unhelpful; agreeing to it would be the wrong instruction.
     */
    val skipsRequiredSlots: Boolean = false,
) {
    fun slotAcknowledgementFor(languageTag: String): String =
        localizedSlotAcknowledgements[languageTag] ?: slotAcknowledgement
}

@Serializable
data class RetryStrategy(
    val maxRetries: Int = 2,
    val prompt: String = "抱歉，我没有听清，请您再说一遍。",
    val localizedPrompts: Map<String, String> = emptyMap(),
)

@Serializable
data class SafetyRuleConfig(
    val highRiskReply: String = "为保护隐私和资金安全，我不能提供验证码、密码或协助转账，本次通话将结束。",
    val localizedHighRiskReplies: Map<String, String> = emptyMap(),
    val riskReplies: Map<String, String> = emptyMap(),
    val localizedRiskReplies: Map<String, Map<String, String>> = emptyMap(),
    val riskSceneCommitPolicy: RiskSceneCommitPolicy = RiskSceneCommitPolicy.OVERRIDE,
    val mediumRiskEscalationCount: Int = 2,
    /** Soft risks may annotate a high-confidence business topic without replacing its scene. */
    val softRiskIds: List<String> = emptyList(),
    /** Provisional protection threshold; calibrate against holdout/guard data. */
    val protectedTopicScore: Float = 1f,
    val rules: List<RiskPatternRule> = emptyList(),
)

@Serializable
enum class RiskSceneCommitPolicy {
    /** A HIGH risk decision becomes the final spam_risk scene. */
    OVERRIDE,

    /** Keep the business scene and only attach risk information. */
    ANNOTATE,
}

@Serializable
data class RiskPatternRule(
    val riskId: String,
    val level: RiskLevel,
    val sensitiveInfoType: String,
    val localeRules: Map<String, RiskLocaleRule>,
)

@Serializable
data class RiskLocaleRule(
    val requestPatterns: List<String>,
    val safetyPatterns: List<String> = emptyList(),
    val contextExemptionPatterns: List<String> = emptyList(),
)

internal fun DialogueRuleFile.openingFor(languageTag: String): String =
    openingPrompts[languageTag] ?: openingPrompt

internal fun ScenarioRule.displayNameFor(languageTag: String): String =
    displayNames[languageTag] ?: displayName

internal fun IntentRule.localizedFor(languageTag: String): LocalizedIntentRule {
    val languageOnly = languageTag.substringBefore('-')
    return localeRules[languageTag]
        ?: localeRules.entries.firstOrNull { it.key.substringBefore('-') == languageOnly }?.value
        ?: LocalizedIntentRule(
            coreKeywords = keywords,
            auxiliaryKeywords = synonyms,
            negativeKeywords = negativeKeywords,
            coreRegexPatterns = regexPatterns,
        )
}

internal fun StateRule.systemQuestionFor(languageTag: String): String =
    localized[languageTag]?.systemQuestion ?: systemQuestion

internal fun StateRule.fallbackReplyFor(languageTag: String): String =
    localized[languageTag]?.fallbackReply ?: fallbackReply

internal fun StateRule.missingSlotPrompt(slot: String, languageTag: String): String? =
    localized[languageTag]?.missingSlotPrompts?.get(slot)

internal fun TransitionRule.replyFor(languageTag: String): String =
    localizedReplyTemplates[languageTag] ?: replyTemplate

internal fun RetryStrategy.promptFor(languageTag: String): String =
    localizedPrompts[languageTag] ?: prompt

internal fun FallbackRule.localizedFor(languageTag: String): LocalizedFallbackRule =
    localized[languageTag] ?: LocalizedFallbackRule(
        retryPrompt = retryPrompt,
        purposePrompt = purposePrompt,
        emergencyQuestion = emergencyQuestion,
        callbackQuestion = callbackQuestion,
        closingReply = closingReply,
    )
