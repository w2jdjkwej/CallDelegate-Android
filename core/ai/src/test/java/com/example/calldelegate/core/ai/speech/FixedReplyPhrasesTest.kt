package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.FallbackRule
import com.example.calldelegate.core.ai.rules.LocalizedFallbackRule
import com.example.calldelegate.core.ai.rules.SafetyRuleConfig
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class FixedReplyPhrasesTest {

    @Test fun everyScenarioReplyWithoutAPlaceholderIsPrewarmed() {
        // These are what the assistant says on almost every turn of a real call. Until they were
        // listed, each one cost 456 to 639 ms of synthesis inside the pause the caller sits in.
        val production = com.example.calldelegate.core.ai.rules.loadProductionRuleFile()
        val phrases = FixedReplyPhrases.extract(production).toSet()

        val spoken = production.scenarios
            .flatMap { it.states }
            .flatMap { it.transitions }
            .map { it.replyTemplate }
            .filter { it.isNotBlank() && !it.contains('{') }
            .distinct()

        assertThat(spoken).isNotEmpty()
        assertThat(phrases).containsAtLeastElementsIn(spoken)
    }

    @Test fun aReplyStillCarryingASlotIsNeverPrewarmed() {
        // A cached recording of 好的，放在{location} would speak the braces at the caller.
        FixedReplyPhrases.extract(com.example.calldelegate.core.ai.rules.loadProductionRuleFile()).forEach { phrase ->
            assertThat(phrase).doesNotContain("{")
        }
    }

    @Test fun extractsTheRepliesTheEngineSpeaksVerbatim() {
        val phrases = FixedReplyPhrases.extract(rules())

        assertThat(phrases).containsExactly(
            "您好，这里是智能助理",
            "麻烦再说一次",
            "是否紧急？",
            "需要回电吗？",
            "再见",
            "涉及资金，本次通话结束",
            "L1 回复",
            "L2 回复",
        )
    }

    @Test fun excludesTemplatesThatDependOnCallerSuppliedSlots() {
        // A pre-generated recording of "请问{location}对吗" would speak the placeholder out loud.
        val phrases = FixedReplyPhrases.extract(
            rules(closingReply = "已记录{location}，再见"),
        )

        assertThat(phrases).doesNotContain("已记录{location}，再见")
        assertThat(phrases).contains("您好，这里是智能助理")
    }

    @Test fun prefersLocalizedTextWhenTheLanguageDeclaresIt() {
        val localized = rules().copy(
            openingPrompts = mapOf("zh-CN" to "您好，本地化开场"),
            fallback = rules().fallback.copy(
                localized = mapOf(
                    "zh-CN" to LocalizedFallbackRule(
                        retryPrompt = "本地化重试",
                        emergencyQuestion = "本地化紧急",
                        callbackQuestion = "本地化回电",
                        closingReply = "本地化结束",
                    ),
                ),
            ),
        )

        val phrases = FixedReplyPhrases.extract(localized, languageTag = "zh-CN")

        assertThat(phrases).contains("您好，本地化开场")
        assertThat(phrases).contains("本地化重试")
        assertThat(phrases).doesNotContain("您好，这里是智能助理")
        assertThat(phrases).doesNotContain("麻烦再说一次")
    }

    @Test fun fallsBackToTheDefaultTextForAnUndeclaredLanguage() {
        val phrases = FixedReplyPhrases.extract(rules(), languageTag = "en-US")

        assertThat(phrases).contains("您好，这里是智能助理")
        assertThat(phrases).contains("麻烦再说一次")
    }

    @Test fun dropsBlanksAndRepeatsSoEachPhraseIsSynthesizedOnce() {
        val duplicated = rules(closingReply = "再见").copy(
            safety = SafetyRuleConfig(
                highRiskReply = "再见",
                riskReplies = mapOf("L1" to "再见", "L2" to "   "),
            ),
        )

        val phrases = FixedReplyPhrases.extract(duplicated)

        assertThat(phrases.count { it == "再见" }).isEqualTo(1)
        assertThat(phrases.none(String::isBlank)).isTrue()
    }

    @Test fun theProductionRuleFileYieldsPhrasesThatAreAllSlotFree() {
        val json = File(projectRoot(), "app/src/main/assets/dialogue_rules.json")
            .readText(Charsets.UTF_8)
        val rules = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(DialogueRuleFile.serializer(), json)

        val phrases = FixedReplyPhrases.extract(rules)

        assertThat(phrases).isNotEmpty()
        assertThat(phrases.none { it.contains('{') }).isTrue()
        // The opening prompt runs on every call, so it must always be a prewarm candidate.
        assertThat(phrases).contains(rules.openingPrompts[rules.lang] ?: rules.openingPrompt)
    }

    private fun rules(closingReply: String = "再见") = DialogueRuleFile(
        schemaVersion = 1,
        openingPrompt = "您好，这里是智能助理",
        fallback = FallbackRule(2, "麻烦再说一次", "是否紧急？", "需要回电吗？", closingReply),
        scenarios = emptyList(),
        safety = SafetyRuleConfig(
            highRiskReply = "涉及资金，本次通话结束",
            riskReplies = mapOf("L1" to "L1 回复", "L2" to "L2 回复"),
        ),
    )

    private fun projectRoot(): File {
        var directory = File("").absoluteFile
        while (!File(directory, "settings.gradle.kts").exists()) {
            directory = directory.parentFile ?: error("settings.gradle.kts not found")
        }
        return directory
    }
}
