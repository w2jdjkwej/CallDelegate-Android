package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.ai.rules.DialogueRuleFile

/**
 * Synthesises the replies a caller's next turn is about to make possible, before it arrives.
 *
 * Implementations run while nothing is waiting on the engine and must never let a failure surface:
 * this is a prefetch, and a call that would have worked without it must still work.
 */
fun interface SlotReplyPrefetcher {
    /** Null when the attempt could not run at all, which is not the same as having nothing to do. */
    suspend fun prefetch(
        sceneId: String,
        stateId: String,
        slots: Map<String, String>,
        languageTag: String,
    ): SlotReplyPrefetchResult?
}

/**
 * What one prefetch attempt found and did.
 *
 * Reported because a prefetch that silently does nothing looks exactly like one that works: the
 * reply is synthesised either way, and only the stopwatch differs. The first device run of this
 * left a cache miss unexplained -- the prediction, the timing and the engine were all equally
 * plausible suspects, and nothing in the log could separate them.
 *
 * [alreadyStored] earns its place for the same reason at one remove: the second device run
 * predicted correctly and generated nothing, because the call before it had already put the reply
 * on disk. Without this the two readings of "generated=0" stay indistinguishable.
 */
data class SlotReplyPrefetchResult(
    val candidates: Int,
    val generated: Int,
    val alreadyStored: Int,
    val failed: Int,
)

/**
 * Works out which replies reachable from a dialogue state are already fully determined.
 *
 * [FixedReplyPhrases] pre-synthesises every reply whose text is the same on every call, and
 * deliberately skips the ones carrying a `{slot}`: their final wording depends on what the caller
 * said, so nothing could have been recorded for them before the call. That exclusion is correct and
 * it is also why those replies are the ones the caller waits for. On the 2026-08-09 21:20 call the
 * only turn that missed the cache was 好的，就放在{location}。还有其他事项吗？ with the location
 * filled in -- 754 ms of synthesis inside the pause, against 0 ms for the two turns either side of
 * it.
 *
 * The wording stops depending on the caller the moment the slot is filled, which happens a whole
 * turn before the reply that uses it is spoken. This finds the replies that have reached that point:
 * the transitions out of the state the dialogue just moved to, whose every placeholder is already
 * answered by a known slot. Speaking one of them is then a cache read.
 *
 * Being wrong is cheap by construction. The cache is keyed on finished text, so a prediction that
 * does not come true is idle work on an idle engine and nothing else -- it cannot put words in the
 * assistant's mouth, because a reply is still only ever spoken when the dialogue engine produces it.
 */
object SlotFilledReplyPrefetch {

    /**
     * @param slots the slots known now. A template naming anything absent is skipped rather than
     *   guessed: [com.example.calldelegate.core.ai.rules.JsonDialogueEngine] would substitute its
     *   own placeholder text, and synthesising that would cache a string the engine is unlikely to
     *   ask for while leaving the real one uncached.
     */
    fun candidates(
        rules: DialogueRuleFile,
        sceneId: String,
        stateId: String,
        slots: Map<String, String>,
        languageTag: String = rules.lang,
    ): List<String> {
        if (slots.isEmpty()) return emptyList()
        val state = rules.scenarios
            .firstOrNull { it.sceneId == sceneId }
            ?.states
            ?.firstOrNull { it.stateId == stateId }
            ?: return emptyList()
        return state.transitions
            .map { transition ->
                transition.localizedReplyTemplates[languageTag] ?: transition.replyTemplate
            }
            // Templates without a placeholder are already resident from FixedReplyPhrases, and
            // re-requesting them here would only take the engine lock to learn that.
            .filter { template -> SLOT_PLACEHOLDER.containsMatchIn(template) }
            .mapNotNull { template -> fill(template, slots) }
            .distinct()
    }

    private fun fill(template: String, slots: Map<String, String>): String? {
        var complete = true
        val filled = SLOT_PLACEHOLDER.replace(template) { match ->
            val value = slots[match.groupValues[1]]
            if (value.isNullOrBlank()) {
                complete = false
                ""
            } else {
                value
            }
        }
        return filled.takeIf { complete }
    }

    /** Mirrors the substitution pattern the dialogue engine applies when it fills a template. */
    private val SLOT_PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}")
}
