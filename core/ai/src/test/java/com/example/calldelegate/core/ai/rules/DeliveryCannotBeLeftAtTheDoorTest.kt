package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The owner is not on the call, so the assistant cannot complete a courier's errand. It can decide
 * only what the owner has already authorised, and the one such decision this scene holds is that a
 * parcel may be left where the order says. That authorisation is why capture_delivery asks
 * 可以放在订单上指定的位置吗 before anything else.
 *
 * These are the turns it does not reach. A cash-on-delivery box needs money the assistant cannot
 * pay; refrigerated or fresh goods spoil where they are left; valuables, fragile items and
 * signature-required parcels need a person; a door code or a collection code is the owner's to give
 * out. Answering 可以放在订单上指定的位置吗 to any of them is not merely unhelpful -- agreeing would
 * be the wrong instruction, and it costs the owner the parcel or the money.
 *
 * Before this, nineteen of twenty-eight delivery turns on the fourth blind set got that question
 * and nothing else, these among them.
 */
class DeliveryCannotBeLeftAtTheDoorTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
    private val engine = JsonDialogueEngine(provider, classifier, RegexEntityExtractor())
    private val scenes = AppSettings().enabledScenes

    private suspend fun replyTo(text: String) =
        engine.process(DialogueContext(sessionId = "left-at-door"), text, false, scenes)

    @Test
    fun theFourKindsOfParcelThatCannotBeLeftAreNotOfferedTheDoor() = runTest {
        val cases = mapOf(
            "这个包裹是到付件，签收时需要支付运费" to "delivery_payment_due",
            "您的药品订单到了，因为有冷藏要求需要尽快取走" to "delivery_perishable",
            "这件贵重物品平台要求我必须当面交给收件人" to "delivery_in_person_required",
            "您购买的家具今天送货，需要家里有人配合收货" to "delivery_in_person_required",
            "您的蛋糕已经到楼下，为避免损坏需要您亲自下来领取" to "delivery_in_person_required",
            "这件包裹需要收件人提供取件码，我才能完成交付" to "delivery_credential_request",
        )

        cases.forEach { (text, expectedIntent) ->
            val classification = classifier.classifyDetailed(text, scenes)
            assertWithMessage("input: %s", text)
                .that(classification?.scene).isEqualTo(SceneType.DELIVERY.id)
            assertWithMessage("input: %s", text)
                .that(classification?.intent).isEqualTo(expectedIntent)
            assertWithMessage("input: %s must not be offered the door", text)
                .that(replyTo(text).reply).doesNotContain("放在订单上指定的位置")
        }
    }

    /** An ordinary parcel still gets the decision the owner did authorise. */
    @Test
    fun anOrdinaryParcelIsStillOfferedTheDoor() = runTest {
        assertWithMessage("a plain delivery must still be offered the agreed placement")
            .that(replyTo("我是给您送快递的，已经到您填写的收货地址附近了").reply)
            .contains("放在订单上指定的位置")
    }

    /** And a courier who already said where it goes is not asked again. */
    @Test
    fun aStatedPlacementIsNotAskedForTwice() = runTest {
        val decision = replyTo("顺丰快递到了，放在北门驿站")
        assertWithMessage("the location was given; asking for it again wastes the turn")
            .that(decision.reply).doesNotContain("放在订单上指定的位置")
        assertWithMessage("the location must be captured")
            .that(decision.context.slots["location"]).isEqualTo("北门驿站")
    }
}
