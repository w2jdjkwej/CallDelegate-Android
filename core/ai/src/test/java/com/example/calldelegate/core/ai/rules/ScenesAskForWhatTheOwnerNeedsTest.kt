package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Four scenes now ask the one thing the owner cannot act without, as delivery already did.
 *
 * Before this, only delivery had follow-up turns. The other scenes listed a dozen expectedSlots
 * and an empty requiredSlots, so a driver who said 我到了 without saying where, or an agent who
 * called about a viewing without naming the property, was thanked and hung up on -- leaving the
 * owner a message they could not act on.
 *
 * What is deliberately not pursued is the sales half of each scene. A caller pitching a policy is
 * recorded and declined, not interviewed.
 */
class ScenesAskForWhatTheOwnerNeedsTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)
    private val scenes = SceneType.entries.toSet()

    @Test fun theMissingFactIsAskedForWithoutLosingWhatWasAlreadySaid() = runTest {
        // The question follows an acknowledgement rather than replacing it: a caller who hears only
        // 请问是哪个小区 has no sign their request registered at all.
        val cases = listOf(
            Triple("我是滴滴司机，已经到了", "司机到达", "上车点"),
            Triple("上次维修的服务想做个回访", "服务回访", "平台或商家"),
            Triple("您的保单下个月到期了，提醒您续保", "保单到期", "保单或产品"),
            Triple("想约您明天下午看房", "看房时间", "小区或哪套房源"),
        )
        cases.forEach { (said, acknowledged, asked) ->
            val decision = engine.process(DialogueContext(sessionId = said), said, false, scenes)
            assertWithMessage("input: %s", said).that(decision.reply).contains(acknowledged)
            assertWithMessage("input: %s", said).that(decision.reply).contains(asked)
            assertWithMessage("input: %s", said).that(decision.shouldEnd).isFalse()
        }
    }

    @Test fun answeringTheQuestionMovesTheCallOnRatherThanAskingAgain() = runTest {
        val scripts = mapOf(
            "打车" to listOf("我是滴滴司机，已经到了", "我在小区南门"),
            "客服" to listOf("上次维修的服务想做个回访", "是美的的售后"),
            "保险" to listOf("您的保单下个月到期了，提醒您续保", "是车险"),
            // The answer arrives as a bare noun, because that is how people answer a question.
            "打车-裸地名" to listOf("我是滴滴司机，已经到了", "南门"),
        )
        scripts.forEach { (name, turns) ->
            var context = DialogueContext(sessionId = name)
            var reply = ""
            turns.forEach { said ->
                val decision = engine.process(context, said, false, scenes)
                context = decision.context
                reply = decision.reply
            }
            assertWithMessage("%s: the answer must be taken, not re-asked", name)
                .that(context.stateId).isEqualTo("ask_supplement")
            assertWithMessage("%s: the caller is asked whether anything else remains", name)
                .that(reply).contains("还有其他事项")
        }
    }

    @Test fun sayingThereIsNothingElseEndsTheCall() = runTest {
        listOf(
            listOf("我是滴滴司机，已经到了", "我在小区南门", "没有别的了"),
            listOf("上次维修的服务想做个回访", "是美的的售后", "没有别的了"),
            listOf("您的保单下个月到期了，提醒您续保", "是车险", "没有别的了"),
        ).forEach { turns ->
            var context = DialogueContext(sessionId = turns.first())
            var ended = false
            turns.forEach { said ->
                val decision = engine.process(context, said, false, scenes)
                context = decision.context
                ended = decision.shouldEnd
            }
            assertWithMessage("script starting %s must end", turns.first()).that(ended).isTrue()
        }
    }

    @Test fun aListingIsRecognisedByTheNamesAgentsActuallyUse() = runTest {
        // The pattern accepted 小区, 公寓, 花园 and 家园 and nothing else, so 万科城 and 天悦府 --
        // names any agent says a dozen times a day -- were read as no listing at all.
        listOf("万科城", "天悦府", "保利天悦湾", "阳光花园", "翠湖苑").forEach { listing ->
            val slots = extractor.extract(
                com.example.calldelegate.domain.model.SlotExtractionRequest(
                    text = "房子在$listing",
                    expectedSlots = setOf("community"),
                    scene = SceneType.REAL_ESTATE,
                ),
            ).slots
            assertWithMessage("listing: %s", listing).that(slots["community"]).isNotNull()
        }
    }

    @Test fun anOrderProgressQuestionIsCustomerServiceNotARide() = runTest {
        // 核对订单 was a core rule of the ride scene and of no other, so a customer-service caller
        // asking after their order was answered with 乘客和行程信息我不能代为确认.
        val decision = engine.process(
            DialogueContext(sessionId = "order"),
            "想跟您核对一下订单进度",
            false,
            scenes,
        )
        assertThat(decision.context.scene).isEqualTo(SceneType.CUSTOMER_SERVICE)
    }

    @Test fun aCallbackAnswerEndsTheCallWhereverItIsGiven() = runTest {
        // 需要回电 means the same thing whether it answers the callback question, the missing-fact
        // question or 还有其他事项吗. Before this it was swallowed as though it were the missing fact,
        // and the call would not hang up.
        listOf("需要回电", "不用回电了").forEach { answer ->
            val opening = engine.process(
                DialogueContext(sessionId = answer),
                "我是滴滴司机，已经到了",
                false,
                scenes,
            )
            assertThat(opening.context.stateId).isEqualTo("confirm_ride_pickup")
            val closing = engine.process(opening.context, answer, false, scenes)
            assertWithMessage("answering '%s' must end the call", answer)
                .that(closing.shouldEnd).isTrue()
        }
    }

    @Test fun aDecisionForTheOwnerIsDeclinedBeforeAnythingElseIsAsked() = runTest {
        // Whatever else the turn goes on to ask, the caller is told first that this is not the
        // assistant's to agree to. The follow-up states must not turn a refusal into a negotiation.
        val decisions = mapOf(
            "业主说挂牌价还可以再谈" to SceneType.REAL_ESTATE,
            "您的保单需要补充健康告知，保险公司才能继续承保审核" to SceneType.INSURANCE_FINANCE,
        )
        decisions.forEach { (said, scene) ->
            val decision = engine.process(DialogueContext(sessionId = said), said, false, scenes)
            assertWithMessage("input: %s", said).that(decision.context.scene).isEqualTo(scene)
            assertWithMessage("input: %s", said).that(decision.reply).contains("需机主")
            assertWithMessage("input: %s", said).that(decision.shouldEnd).isFalse()
        }
    }
}
