package com.example.calldelegate.core.ai.rules

private val negativeCallbackPattern = Regex(
    "(?:不用|不需要|无需|不必|不要|别|无需再).{0,8}(?:回电|回电话|回个电话|联系)",
)
private val negativeCallbackPhrases = listOf("不用回", "不需要回", "无需回", "不必回", "不要回", "别回", "不用联系")
private val positiveCallbackPhrases =
    listOf("需要回", "请回", "麻烦回", "要回", "回电", "回电话", "回个电话", "联系我", "给我电话", "让他回")
private val positiveCallbackShortAnswers = setOf("可以", "可以的", "行", "行的", "好", "好的", "没问题")

private val negativeUrgentPattern = Regex("(?:并不算|不算|不|没|无需|无须).{0,5}(?:紧急|着急|急)")
private val negativeUrgentPhrases = listOf("不急", "不紧急", "不用着急", "没那么急", "并不算紧急")
private val positiveUrgentPhrases = listOf("紧急", "急事", "很急", "尽快", "马上", "立刻")

private val defaultPositiveShortAnswers = setOf("是", "是的", "对", "对啊", "对呀", "需要", "要")
private val defaultNegativeShortAnswers = setOf("否", "不是", "不用", "不需要", "不要", "不必", "无需", "没有")
private val positiveConfirmationAnswers = setOf("可以", "可以的", "行", "行的", "好", "好的", "没问题", "同意")
private val negativeConfirmationAnswers = setOf("不可以", "不行", "不方便", "不太方便", "不同意")
private val noSupplementAnswers = setOf("没有", "没有了", "没了", "不用了", "没有补充", "没什么了")
private val defaultCorrectionPatterns = listOf(
    Regex("(?:不是|并非)[^，。！？,.!?]{1,16}(?:[，,]|而是|是)"),
    Regex("(?:刚才|前面)?(?:说错了|讲错了|弄错了)"),
    Regex("(?:更正一下|纠正一下|其实是|应该是)"),
)
private val negatingPrefix = Regex("(?:不是|并非|并不算|不算|不要|不用|不需要|无需|不必|别|没有|没)[^，。！？,.!?]{0,6}$")
private val securityReminderPattern = Regex(
    "(?:不会|无需|无须|不需要|不要求)[^，。！？,.!?]{0,20}" +
        "(?:在电话中)?(?:索要|要求|获取|让您提供|让你提供|提供|告诉)[^，。！？,.!?]{0,12}" +
        "(?:密码|验证码|短信验证码|银行卡号|身份证|身份信息)",
)
private val preemptiveDenialPattern = Regex(
    "(?:我们|本人|这边|平台|公司)[^，。！？,.!?]{0,20}" +
        "(?:不是|不卖|不推荐|不需要|不收|不用|不会)[^，。！？,.!?]{0,12}" +
        "(?:推销|推广|广告|营销|保险|理财|贷款|密码|验证码|费用|钱|骗)",
)

internal class RuleTextNormalizer(private val config: LanguageRuleConfig?) {
    fun normalize(text: String): String {
        var value = text.trim().lowercase()
            .replace('，', ',')
            .replace('。', '.')
            .replace('！', '!')
            .replace('？', '?')
            .replace('：', ':')
            .replace('；', ';')
        config?.replacements?.forEach { (source, target) -> value = value.replace(source.lowercase(), target.lowercase()) }
        value = if (config?.stripWhitespace == true) value.replace(Regex("\\s+"), "") else value.replace(Regex("\\s+"), " ")
        return value
    }
}

internal fun detectCallbackNeeded(
    text: String,
    allowShortAnswer: Boolean = false,
    language: LanguageRuleConfig? = null,
): Boolean? {
    val normalized = text.trim().lowercase()
    val shortPositive = language?.positiveShortAnswers?.toSet().orEmpty().ifEmpty { defaultPositiveShortAnswers }
    val shortNegative = language?.negativeShortAnswers?.toSet().orEmpty().ifEmpty { defaultNegativeShortAnswers }
    return when {
        negativeCallbackPattern.containsMatchIn(normalized) -> false
        negativeCallbackPhrases.any(normalized::contains) -> false
        positiveCallbackPhrases.any(normalized::contains) -> true
        allowShortAnswer && normalized in shortNegative -> false
        allowShortAnswer && (normalized in shortPositive || normalized in positiveCallbackShortAnswers) -> true
        else -> null
    }
}

internal fun detectConfirmation(text: String, allowShortAnswer: Boolean = false): Boolean? {
    if (!allowShortAnswer) return null
    val normalized = text.trim().lowercase()
    return when (normalized) {
        in negativeConfirmationAnswers -> false
        in positiveConfirmationAnswers -> true
        else -> null
    }
}

internal fun detectSupplementProvided(text: String, allowAnswer: Boolean = false): Boolean? {
    if (!allowAnswer) return null
    val normalized = text.trim().lowercase()
    if (normalized.isBlank()) return null
    return normalized !in noSupplementAnswers
}

internal fun detectUrgent(
    text: String,
    allowShortAnswer: Boolean = false,
    language: LanguageRuleConfig? = null,
): Boolean? {
    val normalized = text.trim().lowercase()
    val shortPositive = language?.positiveShortAnswers?.toSet().orEmpty().ifEmpty { defaultPositiveShortAnswers }
    val shortNegative = language?.negativeShortAnswers?.toSet().orEmpty().ifEmpty { defaultNegativeShortAnswers }
    return when {
        negativeUrgentPattern.containsMatchIn(normalized) -> false
        negativeUrgentPhrases.any(normalized::contains) -> false
        positiveUrgentPhrases.any(normalized::contains) -> true
        allowShortAnswer && normalized in shortNegative -> false
        allowShortAnswer && normalized in shortPositive -> true
        else -> null
    }
}

internal fun isExplicitCorrection(text: String, language: LanguageRuleConfig?): Boolean {
    val configured = language?.correctionPatterns.orEmpty().mapNotNull { runCatching { Regex(it) }.getOrNull() }
    return (configured.ifEmpty { defaultCorrectionPatterns }).any { it.containsMatchIn(text) }
}

internal fun isNegatedEvidence(text: String, startIndex: Int): Boolean {
    if (startIndex <= 0) return false
    if (isSecurityReminderEvidence(text, startIndex)) return true
    if (isPreemptiveDenialEvidence(text, startIndex)) return false
    val clauseStart = maxOf(
        text.lastIndexOf(',', startIndex - 1),
        text.lastIndexOf('.', startIndex - 1),
        text.lastIndexOf(';', startIndex - 1),
        text.lastIndexOf('!', startIndex - 1),
        text.lastIndexOf('?', startIndex - 1),
    ) + 1
    val prefix = text.substring(clauseStart, startIndex).takeLast(12)
    return negatingPrefix.containsMatchIn(prefix)
}

/** A caller-side security disclaimer is neutral evidence, not a business negation or a risk request. */
private fun isSecurityReminderEvidence(text: String, startIndex: Int): Boolean =
    securityReminderPattern.findAll(text).any { startIndex in it.range }

/**
 * Caller-side denial such as "我们不是推销" is a risk signal, not a user refusal.
 * The evidence index must be inside the matched caller statement so unrelated later clauses are
 * still handled by the ordinary negation rule.
 */
internal fun isPreemptiveDenialEvidence(text: String, startIndex: Int): Boolean =
    !isSecurityReminderEvidence(text, startIndex) &&
        preemptiveDenialPattern.findAll(text).any { startIndex in it.range }

internal fun containsPreemptiveDenial(text: String): Boolean =
    preemptiveDenialPattern.findAll(text).any { !isSecurityReminderEvidence(text, it.range.first) }
