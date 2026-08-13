package com.example.calldelegate.core.ai

import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.TranscriptTurn

class RuleSummaryGenerator : SummaryGenerator {
    override suspend fun generate(
        scene: SceneType,
        result: StructuredResult,
        transcript: List<TranscriptTurn>,
    ): String {
        val identity = result.callerIdentity ?: result.organization ?: "来电方"
        val purpose = result.purpose ?: transcript.lastOrNull { it.speaker == Speaker.CALLER }?.text ?: "未说明具体事项"
        val flags = buildList {
            result.urgent?.let { add(if (it) "标记为紧急" else "不紧急") }
            result.callbackNeeded?.let { add(if (it) "需要回电" else "无需回电") }
            result.time?.let { add("时间：$it") }
            result.location?.let { add("地点：$it") }
        }
        return buildString {
            append(identity).append("来电，")
            append(when (scene) {
                SceneType.DELIVERY -> "涉及配送事项："
                SceneType.RIDE_HAILING -> "涉及打车出行："
                SceneType.CUSTOMER_SERVICE -> "涉及客服售后："
                SceneType.REAL_ESTATE -> "涉及房产事项："
                SceneType.INSURANCE_FINANCE -> "涉及保险金融："
                SceneType.SPAM_RISK -> "疑似骚扰或风险来电："
                SceneType.WORK -> "涉及工作事项："
                SceneType.UNKNOWN_IDENTITY -> "身份及来意："
                SceneType.SALES -> "疑似推销或骚扰："
                SceneType.UNCLASSIFIED -> "事项："
            })
            append(purpose.take(80))
            if (flags.isNotEmpty()) append("；").append(flags.joinToString("，"))
            append("。")
        }
    }
}
