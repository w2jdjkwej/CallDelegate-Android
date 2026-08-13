package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.model.SceneType

class RuleConfigValidator {
    fun validate(rules: DialogueRuleFile) {
        require(rules.schemaVersion in SUPPORTED_SCHEMAS) {
            "Unsupported rule schema ${rules.schemaVersion}"
        }
        require(rules.openingPrompt.isNotBlank() || rules.openingPrompts.isNotEmpty()) {
            "At least one opening prompt is required"
        }
        require(LANGUAGE_TAG.matches(rules.lang)) { "Invalid default language tag ${rules.lang}" }
        validateClassification(rules.classification)
        validateLanguages(rules.languages)
        validateScenarios(rules)
        validateSafety(rules.safety)
    }

    private fun validateClassification(config: ClassificationRuleConfig) {
        val weights = config.weights
        require(weights.coreRegex > 0f && weights.coreKeyword > 0f && weights.auxiliaryKeyword >= 0f)
        require(
            weights.currentSceneContext >= 0f &&
                weights.correctionTarget >= 0f &&
                weights.semanticPolarity > 0f &&
                weights.preemptiveDenial >= 0f,
        )
        require(weights.explicitNegation <= 0f && weights.conflictingSceneCore <= 0f)
        val thresholds = config.thresholds
        require(thresholds.minimumSceneScore > 0f)
        require(thresholds.minimumIntentScore > 0f)
        require(thresholds.clarificationMargin >= 0f)
        require(thresholds.sceneSwitchScore >= thresholds.minimumSceneScore)
        require(
            thresholds.clarificationScore >= 0f &&
                thresholds.clarificationScore < thresholds.minimumIntentScore,
        )
        require(config.clarificationPromptTemplate.isNotBlank())
        val evidenceCombination = config.evidenceCombination
        require(evidenceCombination.comboBonus >= 0f)
        require(evidenceCombination.minimumEvidenceTypes >= 2)
        evidenceCombination.singleEvidenceCap?.let { cap ->
            require(cap >= 0f && cap < thresholds.minimumIntentScore)
        }
        require(evidenceCombination.entityEvidenceWeight >= 0f)
        val knownSceneIds = SceneType.entries.map { it.id }.toSet() - SceneType.UNCLASSIFIED.id
        require(evidenceCombination.enabledScenes.all { it in knownSceneIds }) {
            "Evidence combination contains an unknown scene"
        }
        require(evidenceCombination.domainAxes.keys.all { it in knownSceneIds }) {
            "Evidence axes contain an unknown scene"
        }
        evidenceCombination.domainAxes.forEach { (sceneId, axes) ->
            requireAxes(sceneId, "entity", axes.entity)
            requireAxes(sceneId, "action", axes.action)
            requireAxes(sceneId, "state", axes.state)
            requireAxes(sceneId, "clause", axes.clause)
        }
    }

    /**
     * An anchor claims that a term names one domain and no other, and it is trusted enough to pick
     * a scene on its own. That claim is checked here rather than left to the judgement of whoever
     * edited the file: if two scenes list the same anchor, neither one owns it and the rules refuse
     * to load. The same applies when an anchor merely contains another -- 快递 and 快递柜 cannot both
     * be anchors, because the shorter one already decides every case the longer one would.
     */
    private fun validateAnchorsAreExclusive(rules: DialogueRuleFile) {
        val byLanguage = mutableMapOf<String, MutableMap<String, String>>()
        rules.scenarios.forEach { scenario ->
            scenario.anchorKeywords.forEach { (languageTag, anchors) ->
                require(anchors.none(String::isBlank)) { "${scenario.sceneId}: blank anchor" }
                requireUnique(anchors, "${scenario.sceneId} anchor")
                val owners = byLanguage.getOrPut(languageTag) { mutableMapOf() }
                anchors.forEach { anchor ->
                    owners[anchor]?.let { other ->
                        error("Anchor '$anchor' is claimed by both $other and ${scenario.sceneId}")
                    }
                    owners.keys.firstOrNull { it.contains(anchor) || anchor.contains(it) }
                        ?.takeIf { owners[it] != scenario.sceneId }
                        ?.let { overlapping ->
                            error(
                                "Anchor '$anchor' (${scenario.sceneId}) overlaps '$overlapping' " +
                                    "(${owners[overlapping]}); one of them already decides the other's cases",
                            )
                        }
                    owners[anchor] = scenario.sceneId
                }
            }
        }
    }

    private fun validateLanguages(languages: Map<String, LanguageRuleConfig>) {
        languages.forEach { (languageTag, config) ->
            require(LANGUAGE_TAG.matches(languageTag)) { "Invalid language tag $languageTag" }
            require(config.replacements.keys.none(String::isBlank)) { "$languageTag has a blank normalization key" }
            config.correctionPatterns.forEach { compileRegex("$languageTag correction", it) }
        }
    }

    private fun validateScenarios(rules: DialogueRuleFile) {
        require(rules.scenarios.isNotEmpty()) { "At least one scenario is required" }
        requireUnique(rules.scenarios.map { it.sceneId }, "sceneId")
        validateAnchorsAreExclusive(rules)
        val knownSceneIds = SceneType.entries.map { it.id }.toSet() - SceneType.UNCLASSIFIED.id
        rules.scenarios.forEach { scenario ->
            require(scenario.sceneId in knownSceneIds) { "Unknown scene ${scenario.sceneId}" }
            require(scenario.displayName.isNotBlank()) { "${scenario.sceneId}: displayName is blank" }
            require(scenario.structureFields.distinct().size == scenario.structureFields.size) {
                "${scenario.sceneId}: structureFields contain duplicates"
            }
            require(scenario.intents.isNotEmpty()) { "${scenario.sceneId}: at least one intent is required" }
            requireUnique(scenario.intents.map { it.intentId }, "${scenario.sceneId} intentId")
            requireUnique(
                scenario.intents.flatMap { listOf(it.intentId) + it.legacyIntentIds },
                "${scenario.sceneId} canonical and legacy intentId",
            )
            require(scenario.states.isNotEmpty()) { "${scenario.sceneId}: at least one state is required" }
            requireUnique(scenario.states.map { it.stateId }, "${scenario.sceneId} stateId")
            val stateIds = scenario.states.map { it.stateId }.toSet()
            val intentIds = scenario.intents.map { it.intentId }.toSet()
            require(scenario.initialState in stateIds) { "${scenario.sceneId}: initialState does not exist" }

            scenario.intents.forEach { intent ->
                intent.regexPatterns.forEach { compileRegex("${scenario.sceneId}/${intent.intentId}", it) }
                intent.localeRules.forEach { (languageTag, localized) ->
                    require(LANGUAGE_TAG.matches(languageTag)) {
                        "${scenario.sceneId}/${intent.intentId}: invalid language tag $languageTag"
                    }
                    localized.coreRegexPatterns.forEach {
                        compileRegex("${scenario.sceneId}/${intent.intentId}/$languageTag", it)
                    }
                }
            }

            scenario.states.forEach { state ->
                require(state.expectedSlots.distinct().size == state.expectedSlots.size) {
                    "${scenario.sceneId}/${state.stateId}: expectedSlots contain duplicates"
                }
                require(state.requiredSlots.all { it in state.expectedSlots }) {
                    "${scenario.sceneId}/${state.stateId}: requiredSlots must also be expectedSlots"
                }
                state.missingSlotStates.forEach { (slot, nextState) ->
                    require(slot in state.requiredSlots) {
                        "${scenario.sceneId}/${state.stateId}: missingSlotStates contains non-required slot $slot"
                    }
                    require(nextState in stateIds) {
                        "${scenario.sceneId}/${state.stateId}: unknown missing-slot state $nextState"
                    }
                }
                state.transitions.forEach { transition ->
                    require(transition.intentId == "*" || transition.intentId in intentIds) {
                        "${scenario.sceneId}/${state.stateId}: unknown intent ${transition.intentId}"
                    }
                    require(transition.nextState in stateIds) {
                        "${scenario.sceneId}/${state.stateId}: unknown nextState ${transition.nextState}"
                    }
                }
            }
        }
    }

    private fun validateSafety(safety: SafetyRuleConfig) {
        require(safety.highRiskReply.isNotBlank()) { "highRiskReply is blank" }
        require(safety.mediumRiskEscalationCount >= 2) { "mediumRiskEscalationCount must be at least 2" }
        require(safety.protectedTopicScore in 0f..1f) { "protectedTopicScore must be in [0, 1]" }
        require(safety.softRiskIds.distinct().size == safety.softRiskIds.size) {
            "softRiskIds contain duplicates"
        }
        requireUnique(safety.rules.map { it.riskId }, "riskId")
        safety.rules.forEach { rule ->
            require(rule.riskId.isNotBlank()) { "riskId is blank" }
            require(rule.sensitiveInfoType.isNotBlank()) { "${rule.riskId}: sensitiveInfoType is blank" }
            require(rule.localeRules.isNotEmpty()) { "${rule.riskId}: localeRules are empty" }
            rule.localeRules.forEach { (languageTag, localized) ->
                require(LANGUAGE_TAG.matches(languageTag)) { "${rule.riskId}: invalid language tag $languageTag" }
                require(localized.requestPatterns.isNotEmpty()) { "${rule.riskId}/$languageTag: requestPatterns are empty" }
                (
                    localized.requestPatterns +
                        localized.safetyPatterns +
                        localized.contextExemptionPatterns
                    ).forEach {
                    compileRegex("${rule.riskId}/$languageTag", it)
                }
            }
        }
    }

    private fun compileRegex(owner: String, pattern: String) {
        require(pattern.isNotBlank()) { "$owner contains a blank regex" }
        try {
            Regex(pattern)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$owner contains an invalid regex", error)
        }
    }

    private fun requireUnique(values: List<String>, label: String) {
        require(values.none(String::isBlank)) { "$label must not be blank" }
        require(values.distinct().size == values.size) { "$label values must be unique" }
    }

    private fun requireAxes(sceneId: String, axis: String, values: List<String>) {
        require(values.none(String::isBlank)) { "$sceneId $axis evidence contains a blank term" }
        require(values.distinct().size == values.size) { "$sceneId $axis evidence contains duplicates" }
    }

    private companion object {
        val SUPPORTED_SCHEMAS = 1..2
        val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[A-Z]{2})?")
    }
}
