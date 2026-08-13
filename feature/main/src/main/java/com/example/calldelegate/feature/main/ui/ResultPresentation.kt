package com.example.calldelegate.feature.main.ui

import com.example.calldelegate.domain.model.InputMode

internal data class ResultDisplayField(
    val label: String,
    val value: String,
)

internal fun InputMode.resultDisplayName(): String = when (this) {
    InputMode.MICROPHONE -> "麦克风"
    InputMode.CALL_AUDIO -> "通话音频"
    InputMode.PRESET_AUDIO -> "预设音频"
    InputMode.TEXT -> "文字"
}

/**
 * 将规则引擎元数据转换为面向用户的中文字段。
 *
 * 置信分、决策规则和匹配证据等诊断字段仍保存在记录中，但不在结果页展示。
 */
internal fun resultExtraFields(extras: Map<String, String>): List<ResultDisplayField> =
    EXTRA_LABELS.mapNotNull { (key, label) ->
        val rawValue = extras[key]?.trim().orEmpty()
        if (rawValue.isBlank()) {
            null
        } else {
            ResultDisplayField(label, localizeExtraValue(key, rawValue))
        }
    }

private fun localizeExtraValue(key: String, value: String): String = when (key) {
    "deliveryIntent" -> DELIVERY_INTENT_NAMES[value] ?: "配送事项"
    "sensitiveInfoType" -> localizeCodes(value, SENSITIVE_INFO_NAMES, "敏感信息")
    "riskReason" -> localizeCodes(value, RISK_REASON_NAMES, "存在风险")
    "riskLevel" -> RISK_LEVEL_NAMES[value] ?: "存在风险"
    "vehicleModel" -> value.replace("SUV", "运动型多用途车", ignoreCase = true)
    else -> value
}

private fun localizeCodes(
    value: String,
    names: Map<String, String>,
    fallback: String,
): String = value
    .split(',', '|')
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { names[it] ?: fallback }
    .distinct()
    .joinToString("、")
    .ifBlank { fallback }

private val EXTRA_LABELS = linkedMapOf(
    "deliveryIntent" to "配送状态",
    "deliveryLocation" to "配送地点",
    "platform" to "平台",
    "driverName" to "司机称呼",
    "licensePlate" to "车牌号",
    "vehicleColor" to "车辆颜色",
    "vehicleModel" to "车型",
    "pickupLocation" to "上车地点",
    "destination" to "目的地",
    "orderId" to "订单编号",
    "serviceType" to "服务类型",
    "community" to "小区",
    "viewingTime" to "看房时间",
    "propertyType" to "房产类型",
    "insuranceType" to "保险类型",
    "expiryTime" to "到期时间",
    "contactPurpose" to "联系目的",
    "sensitiveInfoType" to "涉及敏感信息",
    "riskReason" to "风险原因",
    "riskLevel" to "风险等级",
)

private val DELIVERY_INTENT_NAMES = mapOf(
    "arrived" to "已送达",
    "placed" to "已放置",
    "location_query" to "询问放置位置",
    "access_blocked" to "无法进入配送地点",
    "unreachable" to "无法联系收件人",
    "delayed" to "配送延迟",
    "item_issue" to "物品异常",
)

private val SENSITIVE_INFO_NAMES = mapOf(
    "sms_code" to "短信验证码",
    "password" to "密码",
    "funds" to "转账或资金",
    "bank_card" to "银行卡信息",
    "identity_number" to "身份证信息",
    "identity_claim" to "可疑身份声明",
    "screen_share" to "屏幕共享",
    "unknown_app" to "陌生应用",
    "unknown_link" to "陌生链接",
    "harassment" to "反复骚扰",
    "coercion" to "胁迫性要求",
)

private val RISK_REASON_NAMES = mapOf(
    "request_sms_code" to "索要短信验证码",
    "request_password" to "索要密码",
    "request_transfer" to "要求转账",
    "request_bank_card" to "索要银行卡信息",
    "request_identity_number" to "索要身份证信息",
    "suspicious_financial_identity" to "可疑金融身份",
    "request_screen_share" to "要求共享屏幕",
    "request_unknown_app" to "要求安装陌生应用",
    "request_unknown_link" to "要求打开陌生链接",
    "repeated_harassment" to "反复骚扰",
    "coercive_call" to "胁迫性来电",
)

private val RISK_LEVEL_NAMES = mapOf(
    "LOW" to "低风险",
    "MEDIUM" to "中风险",
    "HIGH" to "高风险",
)
