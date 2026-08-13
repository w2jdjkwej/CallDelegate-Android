package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CallNature
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SlotExtractionRequest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RealEstateTargetIntentTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    @Test
    fun recognizesRealEstateBusinessIntents() = runTest {
        val cases = mapOf(
            "明天下午三点看房可以吗" to "viewing_request",
            "原定周六看房，想改到周日下午" to "viewing_reschedule",
            "想确认一下这套房源的面积户型" to "property_information",
            "请介绍一下阳光花园小区的配套和停车情况" to "community_information",
            "房东来电通知下月调整房租" to "rent_notice",
            "物业来电安排房屋漏水维修" to "property_maintenance",
            "租客想续签租房合同" to "lease_renewal",
            "中介想给您推荐一个新楼盘" to "property_marketing",
        )

        cases.forEach { (text, expectedIntent) ->
            val result = classifier.classifyDetailed(text, setOf(SceneType.REAL_ESTATE))
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(SceneType.REAL_ESTATE.id)
            assertWithMessage("input: %s", text).that(result?.intent).isEqualTo(expectedIntent)
        }
    }

    @Test
    fun extractsRealEstateEntities() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "我是链家公司，明天下午三点带看阳光花园小区的二手房，我在阳光花园小区南门，电话13800138000",
                expectedSlots = setOf(
                    "organization",
                    "community",
                    "propertyType",
                    "viewingTime",
                    "time",
                    "location",
                    "contact",
                ),
                scene = SceneType.REAL_ESTATE,
            ),
        ).slots

        assertThat(result["organization"]).isEqualTo("链家公司")
        assertThat(result["community"]).contains("阳光花园")
        assertThat(result["propertyType"]).isEqualTo("二手房")
        assertThat(result["viewingTime"]).isEqualTo("明天下午三点")
        assertThat(result["time"]).isEqualTo("明天下午三点")
        assertThat(result["location"]).contains("南门")
        assertThat(result["contact"]).isEqualTo("13800138000")
    }

    @Test
    fun viewingAndLeaseRepliesRecordWithoutCommittingForOwner() = runTest {
        val viewing = engine.process(
            DialogueContext("property-viewing"),
            "明天下午三点看房可以吗",
            false,
            setOf(SceneType.REAL_ESTATE),
        )
        assertThat(viewing.reply).contains("需机主确认")
        assertThat(viewing.reply).doesNotContain("已经帮您预约")

        val renewal = engine.process(
            DialogueContext("property-renewal"),
            "租客想续签租房合同",
            false,
            setOf(SceneType.REAL_ESTATE),
        )
        assertThat(renewal.reply).contains("是否续约需机主确认")
        assertThat(renewal.reply).doesNotContain("同意续约")
    }

    @Test
    fun propertyMarketingKeepsRealEstateSceneAndMarketingNature() = runTest {
        val result = classifier.classifyDetailed(
            "房产中介给您推荐一个新楼盘优惠",
            setOf(SceneType.REAL_ESTATE, SceneType.SPAM_RISK),
        )

        assertThat(result?.scene).isEqualTo(SceneType.REAL_ESTATE.id)
        assertThat(result?.intent).isEqualTo("property_marketing")
        assertThat(result?.callNature).isEqualTo(CallNature.MARKETING)
    }

    @Test
    fun realEstateReferenceCorpusDoesNotFallBackToUnknown() = runTest {
        val references = listOf(
            "您之前在我们门店登记过购房需求我想确认一下您近期是否还在考虑买房",
            "您之前委托我们出租房屋我想确认一下目前是否仍需要继续寻找租客",
            "您之前咨询的那套房子还在出售",
            "根据您之前登记的两居室购房需求我们刚匹配到一套新的房源",
            "您之前预约查看的那套房子周末可以看请问您哪段时间方便",
            "房东今天晚上可以配合看房",
            "您关注的小区刚挂出一套新房源",
            "根据您的购房需求我们筛选的这套房子已经完成精装修目前可以直接入住",
            "房东愿意在挂牌价基础上再谈",
            "为了继续筛选您之前咨询的房源想确认一下您购房主要用于自住还是投资",
            "根据您之前提交的购房需求我想再确认一下您对面积楼层和朝向的要求",
            "您的房子有客户想看今晚七点方便吗",
            "您之前说预算在一百五十万元左右我筛了三套比较接近的房源",
            "您之前咨询的那套房源距离地铁口大约五百米附近还有商场和学校",
            "您上次觉得楼层太低我又找到了一套同户型的高楼层房源",
            "房东比较着急出售如果付款周期短价格还有协商空间",
            "您咨询的两套房子一套面积大一些另一套总价更低",
            "这套房子目前带租约出售租客合同到明年三月份到期",
            "您登记的出租房源有人感兴趣对方想确认能不能养宠物",
            "这套房子的产权证已经办理目前可以正常预约看房",
            "您昨天看过的房子房东想了解您是否还有购买意向",
            "您之前咨询的新楼盘样板间明天下午开放需要的话我可以按照原预约信息帮您登记",
            "您要求必须带电梯我想再确认一下对房龄有没有限制",
            "房东接受贷款购买但希望首付款不要低于总房款的百分之四十",
            "这套房子挂牌价包含一个车位如果不要车位价格需要重新和业主协商",
            "您准备出售的房子目前还有贷款需要先核对剩余本金和解押时间",
            "这套房源平台上显示满五唯一但最终税费还要根据产权和家庭情况核算",
            "您关注的房子有人交了意向金不过双方还没有正式签订买卖合同",
            "房东同意降价五万元但希望家具家电不包含在成交价格里",
            "您要求的学区条件需要向有关部门核实距离学校近不等于一定能够入学",
            "这套房子实际使用面积比较大但部分空间没有计入产权登记面积",
            "房屋目前由夫妻共同持有签约时需要两位产权人共同确认",
            "这套房子可以马上入住不过地下室曾经出现过渗水已经维修过一次",
            "您的预算能够覆盖房款但加上税费中介费和装修费用后整体支出可能会超出",
        )
        val enabledScenes = SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }.toSet()

        references.forEachIndexed { index, text ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("case %02d: %s".format(index + 1, text))
                .that(result?.scene)
                .isEqualTo(SceneType.REAL_ESTATE.id)
        }
    }

    @Test
    fun realEstateLoanAndRideArrivalKeepTheirSceneBoundaries() = runTest {
        val enabledScenes = SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }.toSet()

        assertThat(
            classifier.classifyDetailed(
                "房东接受贷款购买但希望首付款不要低于总房款的百分之四十",
                enabledScenes,
            )?.scene,
        ).isEqualTo(SceneType.REAL_ESTATE.id)
        assertThat(
            classifier.classifyDetailed("司机已经到南门上车点", enabledScenes)?.scene,
        ).isEqualTo(SceneType.RIDE_HAILING.id)
        assertThat(
            classifier.classifyDetailed("银行来电提醒贷款下周还款", enabledScenes)?.scene,
        ).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(
            classifier.classifyDetailed("我找到了一套同户型的高楼层房源", enabledScenes)?.scene,
        ).isEqualTo(SceneType.REAL_ESTATE.id)
    }
}
