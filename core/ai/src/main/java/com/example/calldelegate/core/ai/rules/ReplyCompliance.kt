package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.model.SceneType

internal data class ReplyComplianceResult(
    val safe: Boolean?,
    val flags: List<String> = emptyList(),
)

/**
 * Conservative, template-level guard for insurance replies.
 * It does not judge the caller's content and it never changes the reply; it only exports flags.
 */
internal object ReplyCompliance {
    fun evaluate(scene: SceneType?, reply: String): ReplyComplianceResult {
        if (scene != SceneType.INSURANCE_FINANCE) return ReplyComplianceResult(safe = null)
        val flags = buildList {
            if (advicePattern.containsMatchIn(reply)) add("INSURANCE_FINANCE_ADVICE")
            if (exactValuePattern.containsMatchIn(reply)) add("INSURANCE_FINANCE_EXACT_VALUE")
            if (ownerActionPattern.containsMatchIn(reply)) add("INSURANCE_FINANCE_OWNER_ACTION")
        }
        return ReplyComplianceResult(safe = flags.isEmpty(), flags = flags)
    }

    private val advicePattern = Regex(
        "(?:建议|推荐|适合|值得|保证|承诺).{0,10}(?:投保|保险|理财|投资|退保|赎回|购买|续保)",
    )
    private val exactValuePattern = Regex(
        "(?:\\d+(?:\\.\\d+)?|百分之[一二三四五六七八九十百千万]+)\\s*(?:元|万元|%|百分比|利率|收益率|保额)",
    )
    private val ownerActionPattern = Regex(
        "(?:我|我们|助手).{0,6}(?:替您|替机主|代机主|帮您|为您).{0,4}(?:投保|退保|赎回|购买|续保|签约|付款)",
    )
}
