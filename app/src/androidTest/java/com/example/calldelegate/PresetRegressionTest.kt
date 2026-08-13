package com.example.calldelegate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.core.ai.rules.AssetRuleProvider
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.audio.BuiltInPresetRepository
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetRegressionTest {
    @Test fun speechPresetsRouteToExpectedScene() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val classifier = RuleBasedIntentClassifier(AssetRuleProvider(context, Json { ignoreUnknownKeys = true }))
        val enabled = SceneType.entries.filterTo(linkedSetOf()) {
            it != SceneType.UNCLASSIFIED && it.id != "sales"
        }

        BuiltInPresetRepository().samples().filter { it.expectedScene != null }.forEach { sample ->
            assertWithMessage(sample.id)
                .that(classifier.classify(sample.transcript, enabled)?.scene)
                .isEqualTo(sample.expectedScene)
        }
    }
}
