package com.example.calldelegate.core.ai.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleConfigValidatorTest {
    private val validator = RuleConfigValidator()

    @Test fun productionConfigurationIsComplete() {
        validator.validate(loadProductionRuleFile())
    }

    @Test fun rejectsMissingInitialState() {
        val rules = loadProductionRuleFile()
        val invalid = rules.copy(scenarios = rules.scenarios.mapIndexed { index, scenario ->
            if (index == 0) scenario.copy(initialState = "missing") else scenario
        })
        val failure = runCatching { validator.validate(invalid) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("initialState")
    }

    @Test fun rejectsInvalidRegex() {
        val rules = loadProductionRuleFile()
        val scenario = rules.scenarios.first()
        val intent = scenario.intents.first()
        val localized = checkNotNull(intent.localeRules["zh-CN"])
        val invalidIntent = intent.copy(localeRules = intent.localeRules + ("zh-CN" to localized.copy(coreRegexPatterns = listOf("["))))
        val invalidScenario = scenario.copy(intents = listOf(invalidIntent) + scenario.intents.drop(1))
        val invalid = rules.copy(scenarios = listOf(invalidScenario) + rules.scenarios.drop(1))
        val failure = runCatching { validator.validate(invalid) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("invalid regex")
    }
}
