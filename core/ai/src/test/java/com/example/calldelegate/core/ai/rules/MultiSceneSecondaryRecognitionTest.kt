package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MultiSceneSecondaryRecognitionTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())

    @Test
    fun rideSecondPassFusesPickupLocationAndLicensePlate() = runTest {
        val location = classifyWithSecondPass(
            scene = SceneType.RIDE_HAILING,
            primaryText = "App定位显示南门上车点，但具体位置没听清",
            expectedSlots = setOf("location", "pickupLocation"),
            secondaryText = "上车点在南门",
            matchedHotword = "上车点 南门",
            triggerReason = "missing_location",
        )
        assertThat(location.extractedSlots["pickupLocation"]).isEqualTo("南门")
        assertThat(location.matchedEvidence).contains("secondary:entity:accepted:pickupLocation")

        val licensePlate = classifyWithSecondPass(
            scene = SceneType.RIDE_HAILING,
            primaryText = "司机说车牌号码没听清",
            expectedSlots = setOf("licensePlate"),
            secondaryText = "车牌号码京A12345",
            matchedHotword = "车牌 号码",
            triggerReason = "missing_order",
        )
        assertThat(licensePlate.extractedSlots["licensePlate"]).isEqualTo("京A12345")
        assertThat(licensePlate.matchedEvidence).contains("secondary:entity:accepted:licensePlate")
    }

    @Test
    fun customerSecondPassFusesOrderAndOrganizationOnlyWithSupportingCues() = runTest {
        val order = classifyWithSecondPass(
            scene = SceneType.CUSTOMER_SERVICE,
            primaryText = "平台客服核实订单尾号，但是号码没听清",
            expectedSlots = setOf("orderId"),
            secondaryText = "订单尾号123456",
            matchedHotword = "订单 尾号",
            triggerReason = "missing_order",
        )
        assertThat(order.extractedSlots["orderId"]).isEqualTo("123456")

        val organization = classifyWithSecondPass(
            scene = SceneType.CUSTOMER_SERVICE,
            primaryText = "平台客服的公司名称没听清",
            expectedSlots = setOf("organization", "platform"),
            secondaryText = "京东客服",
            matchedHotword = "京东 客服",
            triggerReason = "missing_organization",
        )
        assertThat(organization.extractedSlots["organization"]).isEqualTo("京东")
        assertThat(organization.extractedSlots["platform"]).isEqualTo("京东")
    }

    @Test
    fun realEstateAndInsuranceSecondPassFuseSceneCriticalEntities() = runTest {
        val community = classifyWithSecondPass(
            scene = SceneType.REAL_ESTATE,
            primaryText = "小区没听清",
            expectedSlots = setOf("community"),
            secondaryText = "阳光花园小区配套",
            matchedHotword = "小区 配套",
            triggerReason = "missing_community",
        )
        assertThat(community.extractedSlots["community"]).contains("阳光花园")

        val viewingTime = classifyWithSecondPass(
            scene = SceneType.REAL_ESTATE,
            primaryText = "中介说看房时间没听清",
            expectedSlots = setOf("viewingTime"),
            secondaryText = "明天下午三点看房",
            matchedHotword = "明天 下午 三点",
            triggerReason = "missing_time",
        )
        assertThat(viewingTime.extractedSlots["viewingTime"]).isEqualTo("明天下午三点")

        val expiryTime = classifyWithSecondPass(
            scene = SceneType.INSURANCE_FINANCE,
            primaryText = "保险客服说保单到期时间没听清",
            expectedSlots = setOf("expiryTime"),
            secondaryText = "下周保单到期",
            matchedHotword = "保单 到期",
            triggerReason = "missing_time",
        )
        assertThat(expiryTime.extractedSlots["expiryTime"]).isEqualTo("下周")

        val insuranceType = classifyWithSecondPass(
            scene = SceneType.INSURANCE_FINANCE,
            primaryText = "保险客服说险种没听清",
            expectedSlots = setOf("insuranceType"),
            secondaryText = "车险保单",
            matchedHotword = "车险 保单",
            triggerReason = "missing_insuranceType",
        )
        assertThat(insuranceType.extractedSlots["insuranceType"]).isEqualTo("车险")
    }

    private suspend fun classifyWithSecondPass(
        scene: SceneType,
        primaryText: String,
        expectedSlots: Set<String>,
        secondaryText: String,
        matchedHotword: String,
        triggerReason: String,
    ) = checkNotNull(
        classifier.classifyDetailed(
            primaryText,
            setOf(scene),
            RuleClassificationContext(
                lockedScene = scene,
                expectedSlots = expectedSlots,
                secondaryRecognition = SecondaryRecognitionEvidence(
                    text = secondaryText,
                    sceneHints = setOf(scene),
                    matchedHotwordsByScene = mapOf(scene.id to listOf(matchedHotword)),
                    textDifferenceRate = 0.50,
                    triggerReasons = listOf(triggerReason),
                ),
            ),
        ),
    )
}
