package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.ai.rules.DialogueRuleFile

/**
 * The replies the engine speaks verbatim, extracted from the rule file rather than duplicated.
 *
 * These are the phrases worth synthesizing ahead of time: the opening prompt runs on every single
 * call, and the fallback, risk and scenario replies are identical every time they fire. Anything
 * carrying a `{slot}` placeholder is excluded -- its final text depends on the caller, so a
 * pre-generated recording would be wrong.
 *
 * The scenario replies matter more than the rest put together. They are what the assistant says on
 * almost every turn of a real call, and until they were listed here every one of them was a cache
 * miss: 456 to 639 ms of synthesis inside the pause the caller is sitting through, against 130 ms
 * to read one back from disk. Synthesising them costs nothing at call time because it happens
 * before any call.
 *
 * A missing required slot produces the acknowledgement followed by the question, with no separator,
 * so those pairs are pre-composed the same way the engine composes them. Getting that join wrong
 * costs a cache miss and nothing else.
 *
 * Reading them from [DialogueRuleFile] is deliberate. A hardcoded copy would drift the moment
 * dialogue_rules.json changes, and the system would then speak a cached recording that no longer
 * matches the configured reply.
 */
object FixedReplyPhrases {

    fun extract(rules: DialogueRuleFile, languageTag: String = rules.lang): List<String> {
        val phrases = buildList {
            add(rules.openingPrompts[languageTag] ?: rules.openingPrompt)

            val fallback = rules.fallback
            val localizedFallback = fallback.localized[languageTag]
            add(localizedFallback?.retryPrompt ?: fallback.retryPrompt)
            add(localizedFallback?.emergencyQuestion ?: fallback.emergencyQuestion)
            add(localizedFallback?.callbackQuestion ?: fallback.callbackQuestion)
            add(localizedFallback?.closingReply ?: fallback.closingReply)

            val safety = rules.safety
            add(safety.localizedHighRiskReplies[languageTag] ?: safety.highRiskReply)
            val localizedRiskReplies = safety.localizedRiskReplies[languageTag].orEmpty()
            // Every tier the config declares, preferring the localized text where one exists.
            for (tier in safety.riskReplies.keys + localizedRiskReplies.keys) {
                val reply = localizedRiskReplies[tier] ?: safety.riskReplies[tier]
                if (reply != null) add(reply)
            }

            for (scenario in rules.scenarios) {
                for (state in scenario.states) {
                    add(state.localized[languageTag]?.fallbackReply ?: state.fallbackReply)
                    add(state.retryStrategy.prompt)
                    val prompts = state.localized[languageTag]?.missingSlotPrompts.orEmpty()
                    addAll(prompts.values)
                    for (transition in state.transitions) {
                        val reply = transition.localizedReplyTemplates[languageTag]
                            ?: transition.replyTemplate
                        add(reply)
                        val acknowledgement = transition.localizedSlotAcknowledgements[languageTag]
                            ?: transition.slotAcknowledgement
                        if (acknowledgement.isNotBlank()) {
                            // What the caller hears when the turn is short of a required slot.
                            for (prompt in prompts.values) add(acknowledgement + prompt)
                        }
                    }
                }
            }
        }

        return phrases
            .filter { it.isNotBlank() && !SLOT_PLACEHOLDER.containsMatchIn(it) }
            .distinct()
    }

    /** Mirrors the substitution pattern the dialogue engine applies when it fills a template. */
    private val SLOT_PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}")
}
