package com.example.calldelegate.core.ai

import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.StructuredResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RuleSummaryGeneratorTest {
    @Test fun summaryUsesStructuredFieldsWithoutLanguageModel() = runTest {
        val summary = RuleSummaryGenerator().generate(
            SceneType.WORK,
            StructuredResult(callerIdentity = "张工", purpose = "项目评审", urgent = true, callbackNeeded = true),
            emptyList(),
        )
        assertThat(summary).contains("张工")
        assertThat(summary).contains("项目评审")
        assertThat(summary).contains("标记为紧急")
        assertThat(summary).contains("需要回电")
    }
}
