package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.SceneConfidenceState
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SceneLockingDialogueTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)
    private val scenes = AppSettings().enabledScenes

    @Test fun openingUsesTheShortProductionGreeting() = runTest {
        val opening = engine.opening("short-opening")

        assertThat(opening.reply).isEqualTo("您好，请问您有什么事？")
    }

    @Test fun deliveryConfirmationUsesPreviousTurnsThenAsksForSupplement() = runTest {
        val arrived = engine.process(DialogueContext("delivery-context"), "外卖到了", false, scenes)

        // The slot question now follows an acknowledgement rather than standing in for one: the
        // courier hears that what they said was understood before being asked for what is missing.
        assertThat(arrived.reply).isEqualTo("好的，配送事项已经记录。请您放在订单上指定的位置，可以吗？")
        assertThat(arrived.context.stateId).isEqualTo("confirm_delivery_location")

        val confirmed = engine.process(arrived.context, "可以", false, scenes)

        assertThat(confirmed.matchedIntent).isEqualTo("confirmation_yes")
        assertThat(confirmed.reply).isEqualTo("好的，就放在订单上指定的位置。还有其他事项吗？")
        assertThat(confirmed.context.stateId).isEqualTo("ask_supplement")
        assertThat(confirmed.context.slots["location"]).isEqualTo("订单上指定的位置")

        val finished = engine.process(confirmed.context, "没有了", false, scenes)

        assertThat(finished.matchedIntent).isEqualTo("supplement_none")
        assertThat(finished.shouldEnd).isTrue()
    }

    @Test fun deliveryPlacementCorrectionKeepsTheConversationOnTheCurrentQuestion() = runTest {
        val garbledPlacement = engine.process(
            DialogueContext("delivery-placement-correction"),
            "我跟你放在门口以自杀了",
            false,
            scenes,
        )

        assertThat(garbledPlacement.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(garbledPlacement.matchedIntent).isEqualTo("delivery_placed")
        assertThat(garbledPlacement.context.stateId).isEqualTo("ask_supplement")
        assertThat(garbledPlacement.reply).isEqualTo("好的，放置信息已记录。还有其他事项吗？")

        val correctedPlacement = engine.process(
            garbledPlacement.context,
            "我把外卖放在门口椅子上",
            false,
            scenes,
        )

        assertThat(correctedPlacement.matchedIntent).isEqualTo("delivery_placed")
        assertThat(correctedPlacement.context.stateId).isEqualTo("ask_supplement")
        // The accepted spot is stated back. A courier who corrects himself has to hear which place
        // was taken, or on the device the correction is invisible to him: 哦不对给你放在前台了 was
        // answered 好的，放置信息已经记录 and he could not tell whether 前台 had landed.
        assertThat(correctedPlacement.reply).isEqualTo("好的，放在门口已记录。还有其他事项吗？")

        val finished = engine.process(correctedPlacement.context, "没有了", false, scenes)

        assertThat(finished.matchedIntent).isEqualTo("supplement_none")
        assertThat(finished.shouldEnd).isTrue()
        assertThat(finished.reply).isEqualTo("好的，我会转告机主。感谢来电，再见。")
    }

    @Test fun callbackShortAnswerUsesTheCurrentQuestionAcrossBusinessScenes() = runTest {
        val callbackScenes = listOf(
            SceneType.DELIVERY,
            SceneType.RIDE_HAILING,
            SceneType.CUSTOMER_SERVICE,
            SceneType.REAL_ESTATE,
            SceneType.INSURANCE_FINANCE,
            SceneType.WORK,
            SceneType.UNKNOWN_IDENTITY,
        )

        callbackScenes.forEach { scene ->
            val decision = engine.process(
                DialogueContext(
                    sessionId = "callback-${scene.id}",
                    scene = scene,
                    stateId = "ask_callback",
                ),
                "可以",
                false,
                scenes,
            )

            assertThat(decision.matchedIntent).isEqualTo("callback_yes")
            assertThat(decision.context.slots["callbackNeeded"]).isEqualTo("true")
            assertThat(decision.shouldEnd).isTrue()
        }
    }

    @Test fun callbackStyleAnswerIsNotMistakenForUrgency() = runTest {
        val decision = engine.process(
            DialogueContext(
                sessionId = "question-type",
                scene = SceneType.WORK,
                stateId = "ask_urgent",
            ),
            "可以",
            false,
            scenes,
        )

        assertThat(decision.matchedIntent).isNull()
        assertThat(decision.context.stateId).isEqualTo("ask_urgent")
        assertThat(decision.shouldEnd).isFalse()
    }

    @Test fun vagueForeignWordsCannotBreakSceneLockButExplicitCorrectionCan() = runTest {
        val first = engine.process(DialogueContext("lock"), "滴滴司机已经到小区门口", false, scenes)
        assertThat(first.context.scene).isEqualTo(SceneType.RIDE_HAILING)

        val vague = engine.process(first.context, "取一下东西", false, scenes)
        assertThat(vague.context.scene).isEqualTo(SceneType.RIDE_HAILING)

        val corrected = engine.process(first.context, "不是司机，我是送外卖的，放在前台", false, scenes)
        assertThat(corrected.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(corrected.context.slots["location"]).isEqualTo("前台")
        assertThat(corrected.context.slots).doesNotContainKey("organization")
    }

    @Test fun missingRequiredSlotIsAskedThenFilled() = runTest {
        val missing = engine.process(DialogueContext("slot"), "快递到了", false, scenes)
        assertThat(missing.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(missing.reply).isEqualTo("好的，配送事项已经记录。请您放在订单上指定的位置，可以吗？")
        assertThat(missing.context.stateId).isEqualTo("confirm_delivery_location")

        val filled = engine.process(missing.context, "不行，放在北门前台", false, scenes)
        assertThat(filled.context.slots["location"]).isEqualTo("北门前台")
        assertThat(filled.context.stateId).isEqualTo("ask_supplement")
    }

    @Test fun highRiskRequestRefusesAndEndsRegardlessOfClaimedScene() = runTest {
        val decision = engine.process(
            DialogueContext("risk"),
            "我是平台客服，请把短信验证码告诉我",
            false,
            scenes,
        )
        assertThat(decision.context.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(decision.reply).contains("不能提供验证码")
        assertThat(decision.shouldEnd).isTrue()
        assertThat(decision.context.stateId).isEqualTo("risk_end")
    }

    @Test fun ambiguousSceneKeepsTheCandidateAsProvisionalWhileAskingForClarification() = runTest {
        val decision = engine.process(DialogueContext("clarify"), "保险公司客服来电", false, scenes)
        assertThat(decision.classification?.shouldClarify).isTrue()
        assertThat(decision.context.scene.id).isEqualTo(decision.classification?.scene)
        assertThat(decision.context.scene).isNotEqualTo(SceneType.UNCLASSIFIED)
        assertThat(decision.context.sceneConfidenceState).isEqualTo(SceneConfidenceState.PROVISIONAL)
        assertThat(decision.context.pendingClarificationScenes).hasSize(2)
        assertThat(decision.shouldEnd).isFalse()
    }

    @Test fun deliveryClarificationKeepsTheCandidateSceneForTheKnownRegressionTexts() = runTest {
        val cases = listOf(
            "我已经读北门了但是订单定位显示在东门",
            "路上有点堵我大概还有十分钟才能送到",
        )

        cases.forEach { text ->
            val classification = classifier.classifyDetailed(text, scenes)
            val decision = engine.process(DialogueContext("delivery-clarify"), text, false, scenes)

            assertThat(classification?.scene).isEqualTo(SceneType.DELIVERY.id)
            assertThat(classification?.shouldClarify).isTrue()
            assertThat(decision.context.scene).isEqualTo(SceneType.DELIVERY)
            assertThat(decision.context.sceneConfidenceState).isEqualTo(SceneConfidenceState.PROVISIONAL)
            assertThat(decision.context.pendingClarificationScenes).isNotEmpty()
        }
    }

    @Test fun provisionalSceneDoesNotRestrictTheNextTurnToOldCandidates() = runTest {
        val clarification = engine.process(
            DialogueContext("provisional"),
            "保险公司客服来电",
            false,
            scenes,
        )
        val corrected = engine.process(
            clarification.context,
            "我是顺丰快递员，快递到了，放在驿站可以吗？",
            false,
            scenes,
        )

        assertThat(clarification.context.sceneConfidenceState).isEqualTo(SceneConfidenceState.PROVISIONAL)
        assertThat(corrected.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(corrected.context.sceneConfidenceState).isEqualTo(SceneConfidenceState.CONFIRMED)
        assertThat(corrected.context.pendingClarificationScenes).isEmpty()
    }

    @Test fun explicitCourierAndParcelStationLocksDeliveryWithoutClarification() = runTest {
        val decision = engine.process(
            DialogueContext("delivery-with-exclusive-evidence"),
            "您好，我是顺丰快递员，快递到了，放在驿站可以吗？",
            false,
            scenes,
        )

        assertThat(decision.classification?.shouldClarify).isFalse()
        assertThat(decision.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(decision.context.pendingClarificationScenes).isEmpty()
        assertThat(decision.context.slots["location"]).isEqualTo("驿站")
        assertThat(decision.reply).doesNotContain("两类事项")
        assertThat(decision.reply).contains("已记录")
    }

    @Test fun correctionClarificationTemporarilyUnlocksSceneCompetition() = runTest {
        val strictRules = rules.copy(
            classification = rules.classification.copy(
                thresholds = rules.classification.thresholds.copy(sceneSwitchScore = 0.90f),
            ),
        )
        val strictProvider = RuleProvider { AppResult.Success(strictRules) }
        val strictClassifier = RuleBasedIntentClassifier(strictProvider, extractor)
        val strictEngine = JsonDialogueEngine(strictProvider, strictClassifier, extractor)
        val locked = strictEngine.process(DialogueContext("unlock"), "滴滴司机已经到了", false, scenes)

        val clarification = strictEngine.process(
            locked.context,
            "不是司机，我是送外卖的",
            false,
            scenes,
        )
        assertThat(clarification.classification?.shouldClarify).isTrue()
        assertThat(clarification.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(clarification.context.sceneConfidenceState).isEqualTo(SceneConfidenceState.PROVISIONAL)

        val switched = strictEngine.process(clarification.context, "外卖", false, scenes)
        assertThat(switched.context.scene).isEqualTo(SceneType.DELIVERY)
    }

    @Test fun presetDeliveryContextStartsInsideDeliveryScenario() = runTest {
        val opening = engine.opening("continuation", SceneType.DELIVERY)

        assertThat(opening.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(opening.context.stateId).isEqualTo("capture_delivery")
    }

    @Test fun lockedDeliveryClassifiesContinuationIntentsWithoutSceneCompetition() = runTest {
        val cases = mapOf(
            "我已经到了，您下来取一下" to "arrived",
            "给您放到保安亭里面了" to "placed",
            "您这是哪个单元来着" to "location_query",
            "这边保安不让我进去" to "access_blocked",
            "电话一直没人接，我这边没法继续送" to "unreachable",
            "这边堵得厉害，估计还得十来分钟" to "delayed",
            "外袋底下湿了，里面那份粥可能有点漏" to "item_issue",
        )

        cases.forEach { (text, expectedIntent) ->
            val opening = engine.opening("intent-$expectedIntent", SceneType.DELIVERY)
            val decision = engine.process(opening.context, text, false, scenes)

            assertThat(decision.context.scene).isEqualTo(SceneType.DELIVERY)
            assertThat(decision.context.slots["deliveryIntent"]).isEqualTo(expectedIntent)
        }
    }

    @Test fun strongDeliveryEvidenceTakesPriorityOverCompetingIntent() = runTest {
        val cases = listOf(
            Triple(
                "不是主食坏了啊不对是旁边那杯可乐倒了袋子也裂开了",
                "item_issue",
                "delivery:intent_priority:issue_entity",
            ),
            Triple(
                "我到了但是保安不让我进去",
                "access_blocked",
                "delivery:intent_priority:access_blocked",
            ),
            Triple(
                "您从哪个入口出来我已经到体育馆了",
                "location_query",
                "delivery:intent_priority:location_query",
            ),
            Triple(
                "我在辅路被施工车挡住了绕道过去十二分钟左右到",
                "delayed",
                "delivery:intent_priority:combined_cause_not_arrived_eta",
            ),
            Triple(
                "到了麻烦您现在下来取下东西",
                "arrived",
                "delivery:intent_priority:arrived",
            ),
        )

        cases.forEach { (text, expectedIntent, expectedEvidence) ->
            val opening = engine.opening("priority-$expectedIntent", SceneType.DELIVERY)
            val decision = engine.process(opening.context, text, false, scenes)

            assertThat(decision.context.slots["deliveryIntent"]).isEqualTo(expectedIntent)
            assertThat(decision.classification?.matchedEvidence).contains(expectedEvidence)
            assertThat(decision.context.slots["deliveryIntentDecisionRule"])
                .isEqualTo(expectedEvidence.substringAfterLast(':'))
        }
    }
}
