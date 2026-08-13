package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DialogueContext
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 抱歉，我没有听清 answered both of the assistant's ways of failing, and they call for opposite
 * things from the caller.
 *
 * Speech that never arrived should be said again. Speech that arrived and did not name a purpose
 * should be said *differently* -- and telling that caller the assistant did not hear them invites
 * them to repeat the same words to the same result. On the device on 2026-08-08 a caller said it
 * three times and was told 我没有听清 each time, which was untrue from the second turn onward: the
 * transcript was in the record, the purpose was not.
 */
class NotHeardIsNotNotUnderstoodTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
    private val engine = JsonDialogueEngine(provider, classifier, RegexEntityExtractor())
    private val scenes = AppSettings().enabledScenes

    @Test
    fun speechThatNeverArrivedIsAskedForAgain() = runTest {
        val decision = engine.process(
            DialogueContext(sessionId = "not-heard"),
            callerText = null,
            recognitionFailed = true,
            enabledScenes = scenes,
        )
        assertWithMessage("a lost turn should be asked for again")
            .that(decision.reply).contains("没有听清")
    }

    @Test
    fun speechThatArrivedWithoutAPurposeIsAskedToBeRestated() = runTest {
        // Words the recognizer had no trouble with, naming no business at all.
        val decision = engine.process(
            DialogueContext(sessionId = "not-understood"),
            callerText = "那个我想说一下这个事情你懂我意思吧",
            recognitionFailed = false,
            enabledScenes = scenes,
        )
        assertWithMessage("input was heard; claiming otherwise invites the same words back")
            .that(decision.reply).doesNotContain("没有听清")
        assertWithMessage("the caller should be asked what the call is about")
            .that(decision.reply).contains("来电事项")
    }

    /** The two prompts must actually differ, or the split is cosmetic. */
    @Test
    fun theTwoPromptsAreNotTheSameSentence() {
        val fallback = rules.fallback.localizedFor("zh-CN")
        assertWithMessage("purposePrompt defaults to retryPrompt when unset; production must set it")
            .that(fallback.purposeOrRetryPrompt).isNotEqualTo(fallback.retryPrompt)
    }
}
