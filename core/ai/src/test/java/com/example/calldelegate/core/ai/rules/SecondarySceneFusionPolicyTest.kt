package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.RuleClassificationContext
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SecondaryRecognitionEvidence
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SecondarySceneFusionPolicyTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
    private val scenes = SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }.toSet()

    @Test
    fun unclassifiedPrimaryCannotBeLockedToSecondaryDomainScene() = runTest {
        val evidence = SecondaryRecognitionEvidence(
            text = "贷款 还款",
            sceneHints = setOf(SceneType.INSURANCE_FINANCE),
            matchedHotwordsByScene = mapOf(SceneType.INSURANCE_FINANCE.id to listOf("贷款", "还款")),
            textDifferenceRate = 0.80,
            triggerReasons = listOf("unclassified", "low_confidence", "low_margin"),
        )
        val result = checkNotNull(
            classifier.classifyDetailed(
                text = "您好我想咨询一下",
                enabledScenes = scenes,
                context = RuleClassificationContext(secondaryRecognition = evidence),
            ),
        )

        assertThat(result.scene).isNull()
        assertThat(result.rejectedEvidence).contains("secondary:scene:rejected:primary_unclassified")
    }

    @Test
    fun secondaryCannotSwitchAnExistingPrimaryScene() = runTest {
        val evidence = SecondaryRecognitionEvidence(
            text = "优惠 套餐 客服",
            sceneHints = setOf(SceneType.CUSTOMER_SERVICE),
            matchedHotwordsByScene = mapOf(
                SceneType.CUSTOMER_SERVICE.id to listOf("优惠", "套餐", "客服"),
            ),
            textDifferenceRate = 0.60,
            triggerReasons = listOf("low_confidence", "low_margin"),
        )
        val result = checkNotNull(
            classifier.classifyDetailed(
                text = "保险公司正在查询理赔进度",
                enabledScenes = scenes,
                context = RuleClassificationContext(secondaryRecognition = evidence),
            ),
        )

        assertThat(result.scene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(result.scene).isNotEqualTo(SceneType.CUSTOMER_SERVICE.id)
    }
}
