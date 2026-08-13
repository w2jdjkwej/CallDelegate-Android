package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpamRiskHardNegativeTextTest {
    private val rules = loadProductionRuleFile()
    private val classifier = RuleBasedIntentClassifier(
        provider = RuleProvider { AppResult.Success(rules) },
        extractor = RegexEntityExtractor(),
    )
    private val scenes = SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }.toSet()

    @Test
    fun textOnlyHardNegativeSetDoesNotCommitSpamRisk() = runTest {
        val manifest = loadManifest()
        assertThat(manifest.cases).hasSize(48)
        assertThat(manifest.cases.map { it.category }.toSet()).hasSize(8)

        var falsePositiveCount = 0
        manifest.cases.forEach { case ->
            val result = checkNotNull(classifier.classifyDetailed(case.text, scenes))
            val falsePositive = result.scene == SceneType.SPAM_RISK.id || result.riskLevel == RiskLevel.HIGH
            if (falsePositive) falsePositiveCount++
            assertWithMessage("case=%s category=%s text=%s result=%s", case.caseId, case.category, case.text, result)
                .that(falsePositive)
                .isFalse()
        }

        assertThat(falsePositiveCount).isEqualTo(0)
    }

    private fun loadManifest(): HardNegativeManifest = loadHardNegativeManifest()
}
