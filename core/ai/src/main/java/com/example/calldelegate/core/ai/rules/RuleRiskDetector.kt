package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.model.RiskLevel

internal data class RiskDetection(
    val level: RiskLevel = RiskLevel.LOW,
    val reasons: List<String> = emptyList(),
    val sensitiveInfoTypes: List<String> = emptyList(),
    val matchedEvidence: List<String> = emptyList(),
    val rejectedEvidence: List<String> = emptyList(),
    val matchedRuleLevels: Map<String, RiskLevel> = emptyMap(),
    val evidenceTrace: List<RiskPatternEvidence> = emptyList(),
    val contextExemptedRuleIds: List<String> = emptyList(),
    val contextExemptedRuleLevels: Map<String, RiskLevel> = emptyMap(),
    val escalationReason: String? = null,
)

internal data class RiskPatternEvidence(
    val riskId: String,
    val text: String,
    val startIndex: Int,
    val endExclusive: Int,
    val accepted: Boolean,
    val reason: String? = null,
)

internal class CompiledRiskDetector(private val rules: List<CompiledRiskRule>) {
    fun detect(normalizedText: String, languageTag: String): RiskDetection {
        var level = RiskLevel.LOW
        val reasons = linkedSetOf<String>()
        val sensitiveTypes = linkedSetOf<String>()
        val matched = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val matchedLevels = linkedMapOf<String, RiskLevel>()
        val evidenceTrace = mutableListOf<RiskPatternEvidence>()
        val contextExemptedRuleIds = linkedSetOf<String>()
        val contextExemptedRuleLevels = linkedMapOf<String, RiskLevel>()

        rules.forEach { rule ->
            val locale = rule.localeFor(languageTag) ?: return@forEach
            val safetyMatches = locale.safetyPatterns.flatMap { it.findAll(normalizedText).toList() }
            val contextExemptionMatches = locale.contextExemptionPatterns
                .flatMap { it.findAll(normalizedText).toList() }
            val requestMatches = locale.requestPatterns.flatMap { it.findAll(normalizedText).toList() }
            requestMatches.forEach { match ->
                val protectedDirection = safetyMatches.any { safety ->
                    rangesOverlap(safety.range, match.range) || sameClause(normalizedText, safety.range.first, match.range.first)
                }
                val contextExempted = contextExemptionMatches.any { exemption ->
                    rangesOverlap(exemption.range, match.range) ||
                        sameClause(normalizedText, exemption.range.first, match.range.first)
                }
                if (contextExempted) {
                    contextExemptedRuleIds += rule.riskId
                    contextExemptedRuleLevels[rule.riskId] = rule.level
                    rejected += "risk:${rule.riskId}:context_exemption"
                    evidenceTrace += RiskPatternEvidence(
                        riskId = rule.riskId,
                        text = match.value,
                        startIndex = match.range.first,
                        endExclusive = match.range.last + 1,
                        accepted = false,
                        reason = "context_exemption",
                    )
                } else if (protectedDirection || isNegatedEvidence(normalizedText, match.range.first)) {
                    rejected += "risk:${rule.riskId}:safety_or_negation"
                    evidenceTrace += RiskPatternEvidence(
                        riskId = rule.riskId,
                        text = match.value,
                        startIndex = match.range.first,
                        endExclusive = match.range.last + 1,
                        accepted = false,
                        reason = if (protectedDirection) "safety_context" else "negated_request",
                    )
                } else {
                    if (rule.level.ordinal > level.ordinal) level = rule.level
                    reasons += rule.riskId
                    sensitiveTypes += rule.sensitiveInfoType
                    matched += "risk:${rule.riskId}:directed_request"
                    matchedLevels[rule.riskId] = rule.level
                    evidenceTrace += RiskPatternEvidence(
                        riskId = rule.riskId,
                        text = match.value,
                        startIndex = match.range.first,
                        endExclusive = match.range.last + 1,
                        accepted = true,
                    )
                }
            }
        }

        return RiskDetection(
            level = level,
            reasons = reasons.toList(),
            sensitiveInfoTypes = sensitiveTypes.toList(),
            matchedEvidence = matched.distinct(),
            rejectedEvidence = rejected.distinct(),
            matchedRuleLevels = matchedLevels,
            evidenceTrace = evidenceTrace.distinct(),
            contextExemptedRuleIds = contextExemptedRuleIds.toList(),
            contextExemptedRuleLevels = contextExemptedRuleLevels,
        )
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first <= second.last && second.first <= first.last

    private fun sameClause(text: String, firstIndex: Int, secondIndex: Int): Boolean {
        val start = minOf(firstIndex, secondIndex)
        val end = maxOf(firstIndex, secondIndex)
        return text.substring(start, end).none { it in CLAUSE_BOUNDARIES }
    }

    internal data class CompiledRiskRule(
        val riskId: String,
        val level: RiskLevel,
        val sensitiveInfoType: String,
        val locales: Map<String, CompiledRiskLocale>,
    ) {
        fun localeFor(languageTag: String): CompiledRiskLocale? {
            val languageOnly = languageTag.substringBefore('-')
            return locales[languageTag]
                ?: locales.entries.firstOrNull { it.key.substringBefore('-') == languageOnly }?.value
        }
    }

    internal data class CompiledRiskLocale(
        val requestPatterns: List<Regex>,
        val safetyPatterns: List<Regex>,
        val contextExemptionPatterns: List<Regex>,
    )

    companion object {
        private val CLAUSE_BOUNDARIES = setOf(',', '.', ';', '!', '?', '\n')

        fun compile(config: SafetyRuleConfig): CompiledRiskDetector = CompiledRiskDetector(
            config.rules.map { rule ->
                CompiledRiskRule(
                    riskId = rule.riskId,
                    level = rule.level,
                    sensitiveInfoType = rule.sensitiveInfoType,
                    locales = rule.localeRules.mapValues { (_, locale) ->
                        CompiledRiskLocale(
                            requestPatterns = locale.requestPatterns.map(::Regex),
                            safetyPatterns = locale.safetyPatterns.map(::Regex),
                            contextExemptionPatterns = locale.contextExemptionPatterns.map(::Regex),
                        )
                    },
                )
            },
        )
    }
}
