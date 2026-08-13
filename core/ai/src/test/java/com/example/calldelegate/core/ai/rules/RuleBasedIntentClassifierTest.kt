package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RuleBasedIntentClassifierTest {
    private val rules = DialogueRuleFile(
        schemaVersion = 1,
        openingPrompt = "您好",
        fallback = FallbackRule(2, "重试", "紧急吗", "回电吗", "再见"),
        scenarios = listOf(
            ScenarioRule(
                sceneId = "delivery",
                displayName = "配送",
                initialState = "start",
                structureFields = emptyList(),
                intents = listOf(
                    IntentRule(
                        intentId = "delivery_request",
                        keywords = listOf("快递"),
                        synonyms = listOf("包裹"),
                        regexPatterns = listOf("送到.{0,8}前台"),
                    ),
                ),
                states = listOf(
                    StateRule(
                        stateId = "start",
                        systemQuestion = "请说明",
                        transitions = listOf(TransitionRule("delivery_request", "end", "收到", true)),
                        fallbackReply = "请重试",
                    ),
                    StateRule(stateId = "end", systemQuestion = "", endCondition = "always", fallbackReply = "再见"),
                ),
            ),
        ),
    )
    private val classifier = RuleBasedIntentClassifier(RuleProvider { AppResult.Success(rules) })

    @Test fun coreKeywordAndRegexMatchWhileAuxiliaryAloneStaysLowConfidence() = runTest {
        val enabled = setOf(SceneType.DELIVERY)
        listOf("快递到了", "请送到一楼前台").forEach { text ->
            assertWithMessage("input: %s", text)
                .that(classifier.classify(text, enabled)?.intentId)
                .isEqualTo("delivery_request")
        }
        assertThat(classifier.classify("有个包裹", enabled)).isNull()
        val lowConfidence = classifier.classifyDetailed("你好", enabled)
        assertThat(lowConfidence?.shouldClarify).isTrue()
        assertThat(lowConfidence?.clarificationPrompt).isEqualTo("重试")
    }

    @Test fun disabledSceneCannotBeMatched() = runTest {
        assertThat(classifier.classify("快递到了", setOf(SceneType.WORK))).isNull()
    }

    @Test fun insuranceRequiresComposedEvidenceAndClarifiesWeakEvidence() = runTest {
        val insuranceRules = rules.copy(
            classification = ClassificationRuleConfig(
                thresholds = RuleThresholds(
                    minimumSceneScore = 0.38f,
                    minimumIntentScore = 0.35f,
                    clarificationMargin = 0.18f,
                    sceneSwitchScore = 0.50f,
                    clarificationScore = 0.15f,
                ),
                evidenceCombination = EvidenceCombinationConfig(
                    enabledScenes = listOf(SceneType.INSURANCE_FINANCE.id),
                    comboBonus = 0.10f,
                    domainAxes = mapOf(
                        SceneType.INSURANCE_FINANCE.id to EvidenceAxisConfig(
                            entity = listOf("保单", "保险"),
                            action = listOf("到期", "续保"),
                        ),
                    ),
                ),
            ),
            scenarios = listOf(
                ScenarioRule(
                    sceneId = SceneType.INSURANCE_FINANCE.id,
                    displayName = "保险金融",
                    initialState = "start",
                    structureFields = emptyList(),
                    intents = listOf(
                        IntentRule(
                            intentId = "policy_expiry",
                            synonyms = listOf("保单"),
                            sceneDefining = true,
                        ),
                    ),
                    states = listOf(
                        StateRule(
                            stateId = "start",
                            systemQuestion = "请说明",
                            transitions = listOf(TransitionRule("policy_expiry", "end", "收到", true)),
                            fallbackReply = "请重试",
                        ),
                        StateRule(stateId = "end", systemQuestion = "", endCondition = "always", fallbackReply = "再见"),
                    ),
                ),
            ),
        )
        val insuranceClassifier = RuleBasedIntentClassifier(RuleProvider { AppResult.Success(insuranceRules) })
        val enabled = setOf(SceneType.INSURANCE_FINANCE)

        assertThat(insuranceClassifier.classify("保单到期", enabled)?.intentId)
            .isEqualTo("policy_expiry")
        assertThat(insuranceClassifier.classify("保单", enabled)).isNull()
        assertThat(insuranceClassifier.classifyDetailed("保单", enabled)?.shouldClarify).isTrue()
    }
}
