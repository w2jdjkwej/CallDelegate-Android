package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CallNature
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LayeredRuleClassifierTest {
    private val rules by lazy(::loadProductionRuleFile)
    private val provider by lazy { RuleProvider { AppResult.Success(rules) } }
    private val classifier by lazy { RuleBasedIntentClassifier(provider) }
    private val officialScenes = setOf(
        SceneType.DELIVERY,
        SceneType.RIDE_HAILING,
        SceneType.CUSTOMER_SERVICE,
        SceneType.REAL_ESTATE,
        SceneType.INSURANCE_FINANCE,
        SceneType.SPAM_RISK,
    )

    @Test fun recognizesSixOfficialScenesFromSmallSeedSet() = runTest {
        val cases = mapOf(
            "顺丰快递送到北门前台" to SceneType.DELIVERY,
            "滴滴司机已经到小区门口" to SceneType.RIDE_HAILING,
            "京东客服来电确认售后退款" to SceneType.CUSTOMER_SERVICE,
            "房产中介想约周末看房" to SceneType.REAL_ESTATE,
            "保险公司来电核实理赔材料" to SceneType.INSURANCE_FINANCE,
            "这是贷款优惠推广电话" to SceneType.SPAM_RISK,
        )
        cases.forEach { (text, scene) ->
            val result = classifier.classifyDetailed(text, officialScenes)
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(scene.id)
            assertWithMessage("input: %s", text).that(result?.shouldClarify).isFalse()
        }
    }

    @Test fun distinguishesIntentsInsideEachScene() = runTest {
        val cases = listOf(
            IntentCase("网约车找不到上车点", SceneType.RIDE_HAILING, "ride_location_issue"),
            IntentCase("售后说退款原路返回", SceneType.CUSTOMER_SERVICE, "refund_notice"),
            IntentCase("租客想续签租房合同", SceneType.REAL_ESTATE, "lease_renewal"),
            IntentCase("理赔专员需要补充住院证明", SceneType.INSURANCE_FINANCE, "claim_document_request"),
            IntentCase("课程可以免费体验", SceneType.SPAM_RISK, "marketing_pitch"),
        )
        cases.forEach { case ->
            assertWithMessage("input: %s", case.text)
                .that(classifier.classify(case.text, setOf(case.scene))?.intentId)
                .isEqualTo(case.intent)
        }
    }

    @Test fun crossSceneTieRequestsClarificationAndWeakAuxiliaryDoesNot() = runTest {
        val ambiguous = classifier.classifyDetailed("保险公司客服来电", officialScenes)
        assertThat(ambiguous?.shouldClarify).isTrue()
        assertThat(ambiguous?.sceneMargin).isLessThan(rules.classification.thresholds.clarificationMargin)
        assertThat(ambiguous?.sceneCandidates).hasSize(2)

        val weak = classifier.classifyDetailed("到了", officialScenes)
        assertThat(weak?.scene).isNull()
        assertThat(weak?.shouldClarify).isTrue()
    }

    @Test fun negationWinsOverPositiveSubstring() = runTest {
        val callback = classifier.classifyDetailed(
            "不需要回电话",
            setOf(SceneType.WORK),
            lockedContext("ask_callback", "callbackNeeded", "callback_yes", "callback_no"),
        )
        assertThat(callback?.intent).isEqualTo("callback_no")
        assertThat(callback?.extractedSlots?.get("callbackNeeded")).isEqualTo("false")

        val urgent = classifier.classifyDetailed(
            "不紧急",
            setOf(SceneType.WORK),
            lockedContext("ask_urgent", "urgent", "urgent_yes", "urgent_no"),
        )
        assertThat(urgent?.intent).isEqualTo("urgent_no")
        assertThat(urgent?.extractedSlots?.get("urgent")).isEqualTo("false")
    }

    @Test fun shortAnswerOnlyAppliesWhenWholeUtteranceAndExpectedSlotAgree() = runTest {
        val callbackContext = lockedContext("ask_callback", "callbackNeeded", "callback_yes", "callback_no")
        assertThat(classifier.classifyDetailed("对啊", setOf(SceneType.WORK), callbackContext)?.intent)
            .isEqualTo("callback_yes")
        assertThat(classifier.classifyDetailed("不用", setOf(SceneType.WORK), callbackContext)?.intent)
            .isEqualTo("callback_no")
        listOf("不是，我是技术部小王", "需要，我先说明一下项目情况", "对了，明天还有一个会议").forEach { text ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, setOf(SceneType.WORK), callbackContext)?.intent)
                .isNull()
        }
    }

    @Test fun riskDetectionIsIndependentAndUnderstandsActionDirection() = runTest {
        val riskyPhrases = listOf(
            "请把短信验证码告诉我",
            "请把支付密码告诉我",
            "请马上转账到这个账户",
            "请把银行卡号告诉我",
            "现在打开屏幕共享",
            "请安装这个应用",
            "请点击陌生链接",
        )
        riskyPhrases.forEach { text ->
            val result = classifier.classifyDetailed(text, officialScenes)
            assertWithMessage("input: %s", text).that(result?.riskLevel).isEqualTo(RiskLevel.HIGH)
            assertWithMessage("input: %s", text).that(result?.callNature).isEqualTo(CallNature.SUSPICIOUS)
            assertWithMessage("input: %s", text).that(result?.riskReasons).isNotEmpty()
        }

        val warning = classifier.classifyDetailed("不要把验证码告诉任何人", officialScenes)
        assertThat(warning?.riskLevel).isEqualTo(RiskLevel.LOW)
        assertThat(warning?.riskReasons).isEmpty()
        assertThat(warning?.rejectedEvidence).contains("risk:request_sms_code:safety_or_negation")

        val warningThenRequest = classifier.classifyDetailed(
            "不要把验证码告诉别人，请把短信验证码告诉我",
            officialScenes,
        )
        assertThat(warningThenRequest?.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(warningThenRequest?.riskReasons).contains("request_sms_code")
    }

    @Test fun failedOrInvalidRuleLoadReturnsNoClassification() = runTest {
        val failed = RuleBasedIntentClassifier(
            RuleProvider { AppResult.Failure(AppError("RULE_LOAD_FAILED", "规则加载失败")) },
        )
        assertThat(failed.classifyDetailed("快递到了", officialScenes)).isNull()

        val invalid = rules.copy(scenarios = rules.scenarios.mapIndexed { index, scenario ->
            if (index == 0) scenario.copy(initialState = "missing") else scenario
        })
        val invalidClassifier = RuleBasedIntentClassifier(RuleProvider { AppResult.Success(invalid) })
        assertThat(invalidClassifier.classifyDetailed("快递到了", officialScenes)).isNull()
    }

    @Test fun deliveryCombinationRulesCoverRemainingCasesWithoutAsrErrorAliases() = runTest {
        val deliveryCases = listOf(
            "我到北门了但是订单定位显示在东门",
            "您的地址只写了三号楼没有写具体单元和房间号",
            "尾号六八九一的订单已经送达",
            "我到公司楼下了麻烦告诉我交给哪位同事",
            "您点的饮料暂时缺货成同价位的可以吗",
        )
        deliveryCases.forEach { text ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, officialScenes)?.scene)
                .isEqualTo(SceneType.DELIVERY.id)
        }
    }

    @Test fun explicitDeliveryEvidenceDoesNotCompeteWithRideArrival() = runTest {
        val cases = listOf(
            "您好，我是顺丰快递员，快递到了，放在驿站可以吗？",
            "我是外卖骑手，餐放到外卖架上可以吗？",
            "包裹已经放进快递柜了",
        )

        cases.forEach { text ->
            val result = classifier.classifyDetailed(text, officialScenes)

            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(SceneType.DELIVERY.id)
            assertWithMessage("input: %s", text).that(result?.shouldClarify).isFalse()
        }
    }

    @Test fun newDeliveryRulesDoNotCaptureOtherScenes() = runTest {
        val cases = listOf(
            // 订单定位规则的五类负样本。
            "滴滴司机说网约车订单定位显示在东门上车点" to SceneType.RIDE_HAILING,
            "平台客服核实订单定位在东门显示异常" to SceneType.CUSTOMER_SERVICE,
            "房产中介发来看房定位在东门" to SceneType.REAL_ESTATE,
            "保险公司理赔专员核实保单开户地址在东门支行" to SceneType.INSURANCE_FINANCE,
            "贷款优惠推广链接定位在东门门店" to SceneType.SPAM_RISK,
            // 地址不完整规则的五类负样本。
            "滴滴司机说上车地址只写了三号楼没有单元房间号" to SceneType.RIDE_HAILING,
            "平台客服核实订单地址只写了三号楼和房间号" to SceneType.CUSTOMER_SERVICE,
            "房产中介登记三号楼一单元房间号" to SceneType.REAL_ESTATE,
            "保险理赔材料地址只写了三号楼和房间号" to SceneType.INSURANCE_FINANCE,
            "贷款优惠推广要求登记三号楼单元房间号" to SceneType.SPAM_RISK,
            // 已送达规则的五类负样本。
            "滴滴司机说打车订单已经到达目的地" to SceneType.RIDE_HAILING,
            "售后客服说退款订单已经处理完成" to SceneType.CUSTOMER_SERVICE,
            "房产中介说租房合同已经送达" to SceneType.REAL_ESTATE,
            "保险公司通知电子保单已经送达" to SceneType.INSURANCE_FINANCE,
            "推销礼品订单已经送达请领取贷款优惠" to SceneType.SPAM_RISK,
            // 公司楼下交接规则的五类负样本。
            "滴滴司机在公司楼下请把文件交给同事" to SceneType.RIDE_HAILING,
            "平台客服在公司楼下把退货凭证交给同事" to SceneType.CUSTOMER_SERVICE,
            "房产中介在公司楼下把合同交给同事" to SceneType.REAL_ESTATE,
            "保险公司理赔专员在公司楼下把理赔保单交给同事" to SceneType.INSURANCE_FINANCE,
            "贷款优惠推广业务员在公司楼下把传单交给同事" to SceneType.SPAM_RISK,
        )
        cases.forEach { (text, expectedScene) ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, officialScenes)?.scene)
                .isEqualTo(expectedScene.id)
        }
    }

    @Test fun deliveryEnRoutePhraseNeedsOrderCoreOrLockedDeliveryContext() = runTest {
        val weakOnly = classifier.classifyDetailed("三号楼正在往这边赶", officialScenes)
        assertThat(weakOnly?.scene).isNull()

        val withOrder = classifier.classifyDetailed("订单在三号楼正在往这边赶", officialScenes)
        assertThat(withOrder?.scene).isEqualTo(SceneType.DELIVERY.id)

        val locked = classifier.classifyDetailed(
            "三号楼正在往这边赶",
            officialScenes,
            RuleClassificationContext(
                lockedScene = SceneType.DELIVERY,
                stateId = "capture_delivery",
                expectedSlots = setOf("location"),
                allowedIntentIds = setOf("delivery_request"),
            ),
        )
        assertThat(locked?.scene).isEqualTo(SceneType.DELIVERY.id)
        assertThat(locked?.intent).isEqualTo("delivery_request")
    }

    @Test fun secondaryRecognitionCanPromoteAWeakSceneButDoesNotReplacePrimaryText() = runTest {
        val result = classifier.classifyDetailed(
            "不好意思我刚才走出楼栋了现在正在往这边赶",
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = secondaryEvidence(
                    text = "走错楼栋下来",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("走 错 楼栋", "下来")),
                ),
            ),
        )

        assertThat(result?.scene).isEqualTo(SceneType.DELIVERY.id)
        assertThat(result?.matchedEvidence).contains("secondary:scene:accepted:delivery")
    }

    @Test fun secondaryRecognitionDoesNotPromoteFromOneHotword() = runTest {
        val result = classifier.classifyDetailed(
            "不好意思我刚才走出楼栋了现在正在往这边赶",
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = secondaryEvidence(
                    text = "走错楼栋下来",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("走 错 楼栋")),
                ),
            ),
        )

        assertThat(result?.scene).isNotEqualTo(SceneType.DELIVERY.id)
        assertThat(result?.rejectedEvidence)
            .contains("secondary:scene:rejected:insufficient_hotwords:2")
    }

    @Test fun revisedSecondaryPolicyCanUseHighConfidenceClassificationWithoutAHotword() = runTest {
        val primaryText = "路上有点堵我大概还有十分钟才能送到"
        val current = classifier.classifyDetailed(
            primaryText,
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = classifiedSecondaryEvidence(allowWithoutHotword = false),
            ),
        )
        val revised = classifier.classifyDetailed(
            primaryText,
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = classifiedSecondaryEvidence(allowWithoutHotword = true),
            ),
        )

        assertThat(current?.rejectedEvidence).contains("secondary:rejected:no_supported_hotword")
        assertThat(revised?.scene).isEqualTo(SceneType.DELIVERY.id)
        assertThat(revised?.intent).isEqualTo("delivery_delayed")
        assertThat(revised?.shouldClarify).isFalse()
        assertThat(revised?.matchedEvidence).contains("secondary:scene:accepted:delivery")
    }

    @Test fun revisedSecondaryPolicyRejectsWeakClassificationWithoutAHotword() = runTest {
        val evidence = classifiedSecondaryEvidence(allowWithoutHotword = true).copy(
            classificationConfidence = 0.80f,
            classificationSceneMargin = 0.40f,
        )
        val result = classifier.classifyDetailed(
            "路上有点堵我大概还有十分钟才能送到",
            officialScenes,
            RuleClassificationContext(secondaryRecognition = evidence),
        )

        assertThat(result?.rejectedEvidence).contains("secondary:rejected:no_supported_scene_evidence")
        assertThat(result?.matchedEvidence).doesNotContain("secondary:scene:accepted:delivery")
    }

    @Test fun secondaryRecognitionRejectsUnsupportedIssueAndConflictingLocation() = runTest {
        val unsupportedIssue = classifier.classifyDetailed(
            "我这里有两份外卖麻烦确认一下哪一份是您的",
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = secondaryEvidence(
                    text = "出来拿一下缺货",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("出来 拿 一下")),
                ),
            ),
        )
        assertThat(unsupportedIssue?.extractedSlots).doesNotContainKey("issueType")
        assertThat(unsupportedIssue?.rejectedEvidence)
            .contains("secondary:entity:rejected:issueType:primary_cue_missing")

        val conflictingLocation = classifier.classifyDetailed(
            "我到北门了但是订单定位显示在东门",
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = secondaryEvidence(
                    text = "订单定位放在保安室",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("订单 定位", "放在 保安室")),
                ),
            ),
        )
        assertThat(conflictingLocation?.extractedSlots).doesNotContainKey("location")
        assertThat(conflictingLocation?.rejectedEvidence)
            .contains("secondary:entity:rejected:location:primary_conflict")
    }

    @Test fun secondaryRecognitionDoesNotIntroduceAnUnverifiedDirection() = runTest {
        val result = classifier.classifyDetailed(
            "为到了我就在小区行吗",
            officialScenes,
            RuleClassificationContext(
                secondaryRecognition = secondaryEvidence(
                    text = "位置不对小区西门您出来拿一下",
                    matches = mapOf(
                        SceneType.DELIVERY.id to listOf("小区 西门", "出来 拿 一下"),
                        SceneType.RIDE_HAILING.id to listOf("位置 不对"),
                    ),
                ),
            ),
        )

        assertThat(result?.scene).isEqualTo(SceneType.DELIVERY.id)
        assertThat(result?.extractedSlots).doesNotContainKey("location")
        assertThat(result?.extractedSlots).doesNotContainKey("pickupLocation")
        assertThat(result?.rejectedEvidence)
            .contains("secondary:entity:rejected:location:introduces_unverified_direction")
    }

    @Test fun entityQualityRetryCanSupplementLocationAndMissingIntent() = runTest {
        val result = classifier.classifyDetailed(
            "我哥再娶惭愧顶层了",
            officialScenes,
            RuleClassificationContext(
                lockedScene = SceneType.DELIVERY,
                stateId = "capture_delivery",
                expectedSlots = setOf("location"),
                secondaryRecognition = secondaryEvidence(
                    text = "我搁在取餐柜顶层了",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("取餐 柜")),
                    triggerReasons = listOf("suspected_location_error"),
                ),
            ),
        )

        assertThat(result?.scene).isEqualTo(SceneType.DELIVERY.id)
        assertThat(result?.intent).isEqualTo("delivery_placed")
        assertThat(result?.extractedSlots?.get("location")).isEqualTo("取餐柜顶层")
        assertThat(result?.matchedEvidence).contains("secondary:entity:accepted:location")
        assertThat(result?.matchedEvidence).contains("secondary:intent:accepted:delivery_placed")
    }

    @Test fun secondaryRecognitionLocallyCorrectsAHotwordWithoutLosingLocationHierarchy() = runTest {
        val result = classifier.classifyDetailed(
            "我现在在云杉广场B座地下二层卸货去入口旁边",
            officialScenes,
            RuleClassificationContext(
                lockedScene = SceneType.DELIVERY,
                stateId = "capture_delivery",
                expectedSlots = setOf("location"),
                secondaryRecognition = secondaryEvidence(
                    text = "卸货区",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("卸货区")),
                    triggerReasons = listOf("suspected_location_error"),
                ),
            ),
        )

        assertThat(result?.extractedSlots?.get("location"))
            .isEqualTo("云杉广场B座地下二层卸货区入口旁边")
        assertThat(result?.matchedEvidence).contains("secondary:text:local_correction:卸货区")
        assertThat(result?.matchedEvidence).contains("secondary:entity:accepted:location")
    }

    @Test fun secondaryRecognitionRejectsAShortLocationThatWouldLosePrimaryHierarchy() = runTest {
        val result = classifier.classifyDetailed(
            "给您放在研发楼二层茶水间外面的矮柜上了",
            officialScenes,
            RuleClassificationContext(
                lockedScene = SceneType.DELIVERY,
                stateId = "capture_delivery",
                expectedSlots = setOf("location"),
                secondaryRecognition = secondaryEvidence(
                    text = "放在茶水间",
                    matches = mapOf(SceneType.DELIVERY.id to listOf("茶水间")),
                    triggerReasons = listOf("suspected_location_error"),
                ),
            ),
        )

        assertThat(result?.extractedSlots?.get("location")).isEqualTo("研发楼二层茶水间外面的矮柜上")
        assertThat(result?.rejectedEvidence)
            .contains("secondary:entity:rejected:location:loses_primary_hierarchy")
        assertThat(result?.rejectedEvidence)
            .contains("secondary:entity:rejected:location:insufficient_alignment_confidence")
    }

    @Test fun accessEvidenceOutranksArrivalAndDelayedIntentCompletesIssueType() = runTest {
        val access = classifier.classifyDetailed(
            "这栋楼电梯得刷卡，我在首层电梯厅等您",
            officialScenes,
            RuleClassificationContext(lockedScene = SceneType.DELIVERY),
        )
        assertThat(access?.intent).isEqualTo("delivery_access_blocked")

        val delayed = classifier.classifyDetailed(
            "园区入口没找到我肉一下马上就到",
            officialScenes,
            RuleClassificationContext(lockedScene = SceneType.DELIVERY),
        )
        assertThat(delayed?.intent).isEqualTo("delivery_delayed")
        assertThat(delayed?.extractedSlots?.get("issueType")).isEqualTo("延迟")
        assertThat(delayed?.matchedEvidence)
            .contains("delivery:semantic_completion:issueType:延迟")
    }

    private fun lockedContext(stateId: String, slot: String, vararg intents: String) = RuleClassificationContext(
        lockedScene = SceneType.WORK,
        stateId = stateId,
        expectedSlots = setOf(slot),
        allowedIntentIds = intents.toSet(),
    )

    private fun secondaryEvidence(
        text: String,
        matches: Map<String, List<String>>,
        triggerReasons: List<String> = listOf("low_confidence"),
    ) = SecondaryRecognitionEvidence(
        text = text,
        sceneHints = matches.keys.map(SceneType::fromId).toSet(),
        matchedHotwordsByScene = matches,
        textDifferenceRate = 0.50,
        triggerReasons = triggerReasons,
    )

    private fun classifiedSecondaryEvidence(
        allowWithoutHotword: Boolean,
    ) = SecondaryRecognitionEvidence(
        text = "送餐送到",
        sceneHints = setOf(SceneType.DELIVERY, SceneType.RIDE_HAILING),
        matchedHotwordsByScene = emptyMap(),
        textDifferenceRate = 0.88,
        unknownTokenCount = 2,
        triggerReasons = listOf("clarification", "low_margin"),
        classifiedScene = SceneType.DELIVERY,
        classificationConfidence = 1.0f,
        classificationSceneMargin = 1.0f,
        classificationShouldClarify = false,
        allowClassifiedSceneWithoutHotword = allowWithoutHotword,
    )

    private data class IntentCase(val text: String, val scene: SceneType, val intent: String)
}
