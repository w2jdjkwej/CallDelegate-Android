package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JsonDialogueEngineTest {
    private val ruleFile = DialogueRuleFile(
        schemaVersion = 1,
        openingPrompt = "您好",
        fallback = FallbackRule(2, "再说一次", "是否紧急？", "是否回电？", "再见"),
        scenarios = listOf(
            ScenarioRule(
                sceneId = "delivery",
                displayName = "配送",
                initialState = "capture",
                structureFields = listOf("purpose", "location", "callbackNeeded"),
                intents = listOf(
                    IntentRule("delivery_request", listOf("快递"), listOf("包裹")),
                    IntentRule("callback_no", listOf("不用回"), regexPatterns = listOf("^不用$")),
                ),
                states = listOf(
                    StateRule(
                        "capture", "请说明", listOf("purpose", "location"),
                        listOf(TransitionRule("delivery_request", "callback", "需要回电吗？")),
                        RetryStrategy(2, "配送事项没听清"), null, "没听清",
                    ),
                    StateRule(
                        "callback", "是否回电", listOf("callbackNeeded"),
                        listOf(TransitionRule("callback_no", "end", "好的，再见", true)),
                        RetryStrategy(2, "请回答"), null, "没听清",
                    ),
                    StateRule("end", "", endCondition = "always", fallbackReply = "再见"),
                ),
            ),
        ),
    )
    private val provider = RuleProvider { AppResult.Success(ruleFile) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    @Test fun keywordRoutesSceneAndState() = runTest {
        val decision = engine.process(
            DialogueContext("session"),
            "顺丰快递到了，放在北门驿站可以吗？",
            false,
            setOf(SceneType.DELIVERY),
        )

        assertThat(decision.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(decision.context.stateId).isEqualTo("callback")
        assertThat(decision.context.slots["location"]).isEqualTo("北门驿站")
        assertThat(decision.shouldEnd).isFalse()
    }

    @Test fun twoRetriesThenEmergencyAndCallbackFallback() = runTest {
        var context = DialogueContext("session")
        repeat(2) {
            val retry = engine.process(context, null, true, setOf(SceneType.DELIVERY))
            assertThat(retry.context.fallbackStage).isEqualTo(0)
            context = retry.context
        }
        val emergency = engine.process(context, null, true, setOf(SceneType.DELIVERY))
        assertThat(emergency.context.fallbackStage).isEqualTo(1)
        assertThat(emergency.reply).isEqualTo("是否紧急？")

        val callback = engine.process(emergency.context, "不紧急", false, setOf(SceneType.DELIVERY))
        assertThat(callback.context.slots["urgent"]).isEqualTo("false")
        val end = engine.process(callback.context, "不用回", false, setOf(SceneType.DELIVERY))
        assertThat(end.context.slots["callbackNeeded"]).isEqualTo("false")
        assertThat(end.shouldEnd).isTrue()
    }
}
