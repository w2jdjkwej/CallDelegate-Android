package com.example.calldelegate.core.ai.rules

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class JsonRuleParsingTest {
    @Test fun requiredRuleFieldsAreParsed() {
        val source = """
            {
              "schemaVersion": 1,
              "openingPrompt": "开场白",
              "fallback": {
                "maxRetries": 2,
                "retryPrompt": "重试",
                "emergencyQuestion": "紧急吗",
                "callbackQuestion": "回电吗",
                "closingReply": "再见"
              },
              "scenarios": [{
                "sceneId": "delivery",
                "displayName": "配送",
                "initialState": "start",
                "structureFields": ["location"],
                "intents": [{"intentId":"delivery_request","keywords":["快递"],"synonyms":["包裹"],"regexPatterns":["送到.*"]}],
                "states": [{
                  "stateId":"start",
                  "systemQuestion":"放哪里",
                  "expectedSlots":["location"],
                  "transitions":[{"intentId":"delivery_request","nextState":"end","replyTemplate":"收到","end":true}],
                  "retryStrategy":{"maxRetries":2,"prompt":"再说一次"},
                  "endCondition":null,
                  "fallbackReply":"没听清"
                }]
              }]
            }
        """.trimIndent()

        val parsed = Json.decodeFromString(DialogueRuleFile.serializer(), source)
        assertThat(parsed.scenarios.single().intents.single().synonyms).containsExactly("包裹")
        assertThat(parsed.scenarios.single().states.single().transitions.single().nextState).isEqualTo("end")
    }
}
