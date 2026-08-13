package com.example.calldelegate.core.ai.rules

import android.util.Log
import com.example.calldelegate.domain.model.RuleClassificationResult

fun interface RuleLogger {
    fun classificationCompleted(durationMicros: Long, result: RuleClassificationResult)
}

object NoOpRuleLogger : RuleLogger {
    override fun classificationCompleted(durationMicros: Long, result: RuleClassificationResult) = Unit
}

class AndroidRuleLogger : RuleLogger {
    override fun classificationCompleted(durationMicros: Long, result: RuleClassificationResult) {
        val evidenceKinds = result.matchedEvidence.mapNotNull { evidence ->
            evidence.split(':').getOrNull(2)
        }.distinct().joinToString("|")
        Log.d(
            TAG,
            "duration_us=$durationMicros scene=${result.scene ?: "none"} intent=${result.intent ?: "none"} " +
                "confidence=${"%.3f".format(result.confidence)} margin=${"%.3f".format(result.sceneMargin)} " +
                "risk=${result.riskLevel} clarify=${result.shouldClarify} evidence=$evidenceKinds",
        )
    }

    private companion object {
        const val TAG = "CallDelegateRule"
    }
}
