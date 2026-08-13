package com.example.calldelegate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.RuleConfigValidator
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetRuleValidationTest {
    @Test fun productionRuleAssetContainsOfficialAndCompatibilityScenes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("dialogue_rules.json").bufferedReader().use { it.readText() }
        val rules = Json { ignoreUnknownKeys = false }.decodeFromString(DialogueRuleFile.serializer(), text)
        RuleConfigValidator().validate(rules)
        assertThat(rules.scenarios.map { it.sceneId }).containsExactly(
            "delivery",
            "ride_hailing",
            "customer_service",
            "real_estate",
            "insurance_finance",
            "spam_risk",
            "work",
            "unknown_identity",
        )
        assertThat(rules.schemaVersion).isEqualTo(2)
        assertThat(rules.fallback.maxRetries).isEqualTo(2)
    }
}
