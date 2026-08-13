package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SlotExtractionRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegexEntityExtractorTest {
    private val extractor = RegexEntityExtractor()

    @Test fun extractsContactTimeLocationAndFlags() = runTest {
        val values = extractor.extract(
            "我是张工，明天下午3点送到北门前台，电话13812345678，事情不紧急，不用回电。",
            setOf("callerIdentity", "time", "location", "contact", "urgent", "callbackNeeded"),
        )
        assertThat(values["callerIdentity"]).isEqualTo("张工")
        assertThat(values["contact"]).isEqualTo("13812345678")
        assertThat(values["urgent"]).isEqualTo("false")
        assertThat(values["callbackNeeded"]).isEqualTo("false")
        assertThat(values["location"]).contains("北门前台")
    }

    @Test fun arrivalWithoutExplicitDestinationDoesNotCreateLocation() = runTest {
        val values = extractor.extract(
            "快递 到 了",
            setOf("purpose", "location"),
        )

        assertThat(values).doesNotContainKey("location")
    }

    @Test fun invalidAsrLocationCandidateIsIgnored() = runTest {
        val values = extractor.extract(
            "快递到把放在以上",
            setOf("purpose", "location"),
        )

        assertThat(values).doesNotContainKey("location")
    }

    @Test fun insuranceMedicalNarrativeDoesNotBecomeLocation() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "您之前咨询的医疗保险报销范围已经核实当前方案包含住院医疗但不包含普通门诊",
                expectedSlots = setOf("location"),
                scene = SceneType.INSURANCE_FINANCE,
            ),
        )

        assertThat(result.slots).doesNotContainKey("location")
    }

    @Test fun removesQuestionSuffixFromLocation() = runTest {
        val cases = mapOf(
            "快递放在驿站可以吗？" to "驿站",
            "外卖送到北门保安处行吗" to "北门保安处",
            "文件留在一楼前台方便吗？" to "一楼前台",
            "地址是南门好不好？" to "南门",
            "包裹放在东门快递柜是否可以呢？" to "东门快递柜",
        )

        cases.forEach { (text, expectedLocation) ->
            val values = extractor.extract(text, setOf("purpose", "location"))
            assertThat(values["location"]).isEqualTo(expectedLocation)
        }
    }

    @Test fun questionSuffixWithoutLocationIsIgnored() = runTest {
        val values = extractor.extract(
            "快递放在可以吗？",
            setOf("purpose", "location"),
        )

        assertThat(values).doesNotContainKey("location")
    }

    @Test fun callbackAnswerDoesNotImplyUrgency() = runTest {
        val values = extractor.extract(
            "需要请今天下午三点回电",
            setOf("callbackNeeded", "urgent"),
        )

        assertThat(values["callbackNeeded"]).isEqualTo("true")
        assertThat(values).doesNotContainKey("urgent")
    }

    @Test fun correctedLocationOverwritesOldValueAndRejectsNegatedCandidate() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "不要放在门口，送到前台",
                expectedSlots = setOf("location"),
                existingSlots = mapOf("location" to "门口"),
                scene = SceneType.DELIVERY,
            ),
        )

        assertThat(result.slots["location"]).isEqualTo("前台")
        assertThat(result.overwrittenSlots).contains("location")
        assertThat(result.rejectedEvidence).contains("slot:location:negated")
    }

    @Test fun correctedTimeKeepsAffirmativeReplacement() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "不是明天，是后天",
                expectedSlots = setOf("time"),
                existingSlots = mapOf("time" to "明天"),
            ),
        )

        assertThat(result.slots["time"]).isEqualTo("后天")
        assertThat(result.overwrittenSlots).contains("time")
    }

    @Test fun sceneSpecificAliasesShareTheSameExtractorContract() = runTest {
        val ride = extractor.extract(
            SlotExtractionRequest(
                text = "我是司机小王，车牌京A12345",
                expectedSlots = setOf("driverName", "licensePlate"),
                scene = SceneType.RIDE_HAILING,
            ),
        )
        assertThat(ride.slots["driverName"]).isEqualTo("小王")
        assertThat(ride.slots["licensePlate"]).isEqualTo("京A12345")

        val viewing = extractor.extract(
            SlotExtractionRequest(
                text = "看房改到后天下午3点",
                expectedSlots = setOf("time", "viewingTime"),
                scene = SceneType.REAL_ESTATE,
            ),
        )
        assertThat(viewing.slots["time"]).isEqualTo("后天下午3点")
        assertThat(viewing.slots["viewingTime"]).isEqualTo("后天下午3点")
    }

    @Test fun rideLocationsHandleArrivalBoundaryAndPickupArea() = runTest {
        val arrived = extractor.extract(
            SlotExtractionRequest(
                text = "我已经到达您设置的上车点请问您现在方便上车吗",
                expectedSlots = setOf("location"),
                scene = SceneType.RIDE_HAILING,
            ),
        )
        assertThat(arrived.slots["location"]).isEqualTo("您设置的上车点")

        val pickupArea = extractor.extract(
            SlotExtractionRequest(
                text = "我已经到达小区南门的临时上客区正在这里等您",
                expectedSlots = setOf("location"),
                scene = SceneType.RIDE_HAILING,
            ),
        )
        assertThat(pickupArea.slots["location"]).isEqualTo("小区南门的临时上客区")
        assertThat(pickupArea.slots["pickupLocation"]).isEqualTo("小区南门的临时上客区")
    }

    @Test fun rideEntityExtractionRejectsVehicleRoleAndNonEtaDurations() = runTest {
        val vehicleRole = extractor.extract(
            SlotExtractionRequest(
                text = "我已经到达商场一号门停在出租车上客区旁边",
                expectedSlots = setOf("vehicleModel"),
                scene = SceneType.RIDE_HAILING,
            ),
        )
        assertThat(vehicleRole.slots).doesNotContainKey("vehicleModel")

        val elapsed = extractor.extract(
            SlotExtractionRequest(
                text = "我已经在上车点等了几分钟但没有看到您",
                expectedSlots = setOf("estimatedTime"),
                scene = SceneType.RIDE_HAILING,
            ),
        )
        assertThat(elapsed.slots).doesNotContainKey("estimatedTime")

        val eta = extractor.extract(
            SlotExtractionRequest(
                text = "我预计三分钟后到达",
                expectedSlots = setOf("estimatedTime"),
                scene = SceneType.RIDE_HAILING,
            ),
        )
        assertThat(eta.slots["estimatedTime"]).isEqualTo("三分钟")
    }

    @Test fun rideDestinationStopsAtConfirmationClause() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "您的订单目的地是人民广场请确认目的地是否正确",
                expectedSlots = setOf("destination"),
                scene = SceneType.RIDE_HAILING,
            ),
        )

        assertThat(result.slots["destination"]).isEqualTo("人民广场")
    }

    @Test fun rideNumericTailsAreNormalizedOnlyWhenRequested() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "我是接您订单的司机车牌尾号是六八二一手机号尾号三七五九",
                expectedSlots = setOf("licensePlate", "phoneTail"),
                scene = SceneType.RIDE_HAILING,
            ),
        )

        assertThat(result.slots["licensePlate"]).isEqualTo("6821")
        assertThat(result.slots["phoneTail"]).isEqualTo("3759")
    }

    @Test fun deliveryUsesCanonicalFieldsWithoutPurposeOrDeliveryLocation() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "您好尾号六八九一的订单大概还要十分钟送到公司前台",
                expectedSlots = setOf("location", "issueType", "orderNumber", "estimatedTime"),
                scene = SceneType.DELIVERY,
            ),
        )

        assertThat(result.slots["location"]).isEqualTo("公司前台")
        assertThat(result.slots["orderNumber"]).isEqualTo("6891")
        assertThat(result.slots["estimatedTime"]).isEqualTo("十分钟")
        assertThat(result.slots).doesNotContainKey("purpose")
        assertThat(result.slots).doesNotContainKey("deliveryLocation")
    }

    @Test fun normalizesDeliveryLocationsAndRejectsConflicts() = runTest {
        val cases = mapOf(
            "您的餐已经送到去南门了" to "南门",
            "我把外卖放在公司前台可以吗" to "公司前台",
            "餐轻轻放在门口的置物架上" to "门口的置物架",
            "外卖先放在保安室了" to "保安室",
            "我联系不到你外卖先放在保安室" to "保安室",
        )
        cases.forEach { (text, expected) ->
            val result = extractor.extract(
                SlotExtractionRequest(text, setOf("location"), scene = SceneType.DELIVERY),
            )
            assertThat(result.slots["location"]).isEqualTo(expected)
        }

        val conflict = extractor.extract(
            SlotExtractionRequest(
                text = "我到北门了，但是订单定位显示在东门",
                expectedSlots = setOf("location"),
                scene = SceneType.DELIVERY,
            ),
        )
        assertThat(conflict.slots).doesNotContainKey("location")
        assertThat(conflict.rejectedEvidence).contains("slot:location:conflict")
    }

    @Test fun canonicalizesDeliveryIssueTypesWithoutTreatingNegatedSpillAsDamage() = runTest {
        val cases = mapOf(
            "商家有一份商品卖完了" to "缺货",
            "袋子有一点破损，里面的餐没有洒" to "破损",
            "路上堵车可能会晚十分钟" to "延迟",
            "商家忘记放餐具了" to "餐具缺失",
        )
        cases.forEach { (text, expected) ->
            val result = extractor.extract(
                SlotExtractionRequest(text, setOf("issueType"), scene = SceneType.DELIVERY),
            )
            assertThat(result.slots["issueType"]).isEqualTo(expected)
        }
    }

    @Test fun extractsLongHierarchicalDeliveryLocations() = runTest {
        val cases = mapOf(
            "我现在在云杉广场B座地下二层卸货区入口旁边，您从货梯那边下来就能看见我。" to
                "云杉广场B座地下二层卸货区入口旁边",
            "东西放在梧桐公寓七层消防门旁边了。" to "梧桐公寓七层消防门旁边",
            "东西在启航中心C座十二层北区会议室门外，靠饮水机后面的灰色桌子上。" to
                "启航中心C座十二层北区会议室门外靠饮水机后面的灰色桌子上",
            "保安不让配送员上楼，我现在在金融港D区二号闸机后面的等候区，您带门禁卡下来。" to
                "金融港D区二号闸机后面的等候区",
        )

        cases.forEach { (text, expected) ->
            val result = extractor.extract(
                SlotExtractionRequest(text, setOf("location"), scene = SceneType.DELIVERY),
            )
            assertThat(result.slots["location"]).isEqualTo(expected)
        }
    }

    @Test fun correctionAndRelativeQuestionPreferTheFinalLocation() = runTest {
        val cases = mapOf(
            "我在文化馆西门，啊不对，是靠河边的北侧门。" to "文化馆靠河边的北侧门",
            "我先放西侧货架，啊不对，放到地下一层电梯厅最里面那排柜子上了。" to
                "地下一层电梯厅最里面那排柜子上",
            "我到博雅中学体育馆这边了，您是在西看台入口吗？" to
                "博雅中学体育馆西看台入口",
            "导航停在东侧车道，您实际是在北侧入口吗？" to "北侧入口",
            "导航停在东侧车道你实际是在北侧入口吗" to "北侧入口",
        )

        cases.forEach { (text, expected) ->
            val result = extractor.extract(
                SlotExtractionRequest(text, setOf("location"), scene = SceneType.DELIVERY),
            )
            assertThat(result.slots["location"]).isEqualTo(expected)
        }
    }

    @Test fun genericLocationQuestionDoesNotBecomeCallerLocation() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "您这是哪个单元来着",
                expectedSlots = setOf("location"),
                scene = SceneType.DELIVERY,
            ),
        )

        assertThat(result.slots).doesNotContainKey("location")
        assertThat(result.rejectedEvidence).contains("slot:location:question_target")
    }

    @Test fun preservesGenericSiteSideAndRemovesActionPrefix() = runTest {
        val cases = mapOf(
            "学校南侧施工封路我只能停在校车通道外面" to "学校南侧校车通道外面",
            "保安不让配送员上楼我现在在金融港D区二号闸机后面的等候区明带门禁卡下来" to
                "金融港D区二号闸机后面的等候区",
        )

        cases.forEach { (text, expected) ->
            val result = extractor.extract(
                SlotExtractionRequest(text, setOf("location"), scene = SceneType.DELIVERY),
            )
            assertThat(result.slots["location"]).isEqualTo(expected)
        }
    }

    @Test fun correctedEstimatedTimeUsesTheFinalValue() = runTest {
        val cases = mapOf(
            "原本说五分钟啊不对前面临时管制差不多还有一刻钟" to "一刻钟",
            "五分钟不对改成十二分钟左右" to "十二分钟左右",
        )

        cases.forEach { (text, expected) ->
            val result = extractor.extract(
                SlotExtractionRequest(
                    text = text,
                    expectedSlots = setOf("estimatedTime"),
                    scene = SceneType.DELIVERY,
                ),
            )
            assertThat(result.slots["estimatedTime"]).isEqualTo(expected)
            assertThat(result.rejectedEvidence).contains("slot:estimatedTime:corrected")
        }
    }

    @Test fun deliverySuppressesCrossSceneEntityFields() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "东西放在梧桐公寓七层消防门旁边了",
                expectedSlots = emptySet(),
                scene = SceneType.DELIVERY,
            ),
        )

        assertThat(result.slots).doesNotContainKey("community")
        assertThat(result.slots).doesNotContainKey("propertyType")
        assertThat(result.slots).doesNotContainKey("serviceType")
    }

    @Test fun extractsRelativeDeliveryTimesAndCanonicalIssues() = runTest {
        val cases = mapOf(
            "还得七八分钟才能到" to ("七八分钟" to true),
            "这边堵得有点厉害，估计还得十来分钟" to ("十来分钟" to true),
            "前面临时管制，差不多还要一刻钟" to ("一刻钟" to true),
            "十二分钟左右到" to ("十二分钟左右" to false),
        )
        cases.forEach { (text, expectation) ->
            val result = extractor.extract(
                SlotExtractionRequest(
                    text = text,
                    expectedSlots = setOf("estimatedTime", "issueType"),
                    scene = SceneType.DELIVERY,
                ),
            )
            assertThat(result.slots["estimatedTime"]).isEqualTo(expectation.first)
            if (expectation.second) {
                assertThat(result.slots["issueType"]).isEqualTo("延迟")
            }
        }
    }
}
