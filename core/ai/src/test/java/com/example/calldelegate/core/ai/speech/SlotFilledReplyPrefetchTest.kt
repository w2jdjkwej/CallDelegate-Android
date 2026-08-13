package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.ai.rules.loadProductionRuleFile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlotFilledReplyPrefetchTest {

    @Test fun fillsTheReplyTheNextTurnWillAskFor() {
        // The turn measured on 2026-08-09 at 21:20:38: the caller had already given the location a
        // turn earlier, and agreeing to it cost 754 ms of synthesis because the finished sentence
        // had never existed before that moment.
        val candidates = SlotFilledReplyPrefetch.candidates(
            rules = loadProductionRuleFile(),
            sceneId = "delivery",
            stateId = "confirm_delivery_location",
            slots = mapOf("location" to "三号楼门口"),
        )

        assertThat(candidates).contains("好的，就放在三号楼门口。还有其他事项吗？")
    }

    @Test fun leavesOutTheTemplatesWhoseSlotIsStillUnknown() {
        val candidates = SlotFilledReplyPrefetch.candidates(
            rules = loadProductionRuleFile(),
            sceneId = "ride_hailing",
            stateId = "confirm_ride_pickup",
            // The state's replies name pickupLocation, and guessing it would cache a sentence the
            // engine is not going to ask for while leaving the real one to be synthesized live.
            slots = mapOf("location" to "三号楼门口"),
        )

        assertThat(candidates).isEmpty()
    }

    @Test fun neverRepeatsWhatIsAlreadyPrewarmed() {
        val rules = loadProductionRuleFile()
        val fixed = FixedReplyPhrases.extract(rules).toSet()

        val candidates = rules.scenarios.flatMap { scenario ->
            scenario.states.flatMap { state ->
                SlotFilledReplyPrefetch.candidates(
                    rules = rules,
                    sceneId = scenario.sceneId,
                    stateId = state.stateId,
                    slots = mapOf(
                        "location" to "三号楼门口",
                        "pickupLocation" to "南门",
                        "platform" to "某平台",
                        "community" to "某小区",
                        "insuranceType" to "车险",
                    ),
                )
            }
        }

        assertThat(candidates).isNotEmpty()
        // Every one of these is worth the engine's time precisely because nothing could have been
        // recorded for it before the call.
        assertThat(fixed.intersect(candidates.toSet())).isEmpty()
        candidates.forEach { assertThat(it).doesNotContain("{") }
    }

    @Test fun predictsNothingBeforeTheCallerHasFilledAnySlot() {
        val candidates = SlotFilledReplyPrefetch.candidates(
            rules = loadProductionRuleFile(),
            sceneId = "delivery",
            stateId = "confirm_delivery_location",
            slots = emptyMap(),
        )

        assertThat(candidates).isEmpty()
    }

    @Test fun predictsNothingForAStateTheRulesDoNotDeclare() {
        val candidates = SlotFilledReplyPrefetch.candidates(
            rules = loadProductionRuleFile(),
            sceneId = "delivery",
            stateId = "no_such_state",
            slots = mapOf("location" to "三号楼门口"),
        )

        assertThat(candidates).isEmpty()
    }
}
