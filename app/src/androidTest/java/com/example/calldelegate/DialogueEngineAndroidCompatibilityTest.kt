package com.example.calldelegate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.core.ai.rules.AssetRuleProvider
import com.example.calldelegate.core.ai.rules.JsonDialogueEngine
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogueEngineAndroidCompatibilityTest {
    @Test fun processingCallerTurnDoesNotCrashOnAndroidRegexEngine() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = AssetRuleProvider(context, Json { ignoreUnknownKeys = true })
        val engine = JsonDialogueEngine(
            provider = provider,
            classifier = RuleBasedIntentClassifier(provider),
            extractor = RegexEntityExtractor(),
        )

        val decision = engine.process(
            context = DialogueContext("android-regex-test"),
            callerText = "快递到了，请放在驿站",
            recognitionFailed = false,
            enabledScenes = setOf(SceneType.DELIVERY),
        )

        assertThat(decision.context.scene).isEqualTo(SceneType.DELIVERY)
        assertThat(decision.reply).isNotEmpty()
    }
}
