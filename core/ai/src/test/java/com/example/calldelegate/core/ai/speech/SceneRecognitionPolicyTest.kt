package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechRecognitionFocus
import com.example.calldelegate.domain.api.SpeechRecognitionMode
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SceneRecognitionPolicyTest {
    private val provider = SceneHotwordProvider(SceneHotwordConfigSource { validConfiguration })
    private val policy = SceneRecognitionPolicy(provider)

    @Test fun validatesSixScenesAndMergesAtMostTwoListsWithOneUnknownToken() {
        assertThat(provider.configuration()).isNotNull()

        val phrases = provider.phrasesFor(setOf(SceneType.DELIVERY, SceneType.RIDE_HAILING))

        assertThat(phrases).containsAtLeast("外卖 骑手", "滴滴 司机", "[unk]")
        assertThat(phrases.count { it == "[unk]" }).isEqualTo(1)
        assertThat(provider.phrasesFor(setOf(SceneType.DELIVERY, SceneType.WORK))).containsExactly(
            "外卖 骑手", "小区 西门", "[unk]",
        ).inOrder()
    }

    @Test fun productionConfigurationDeclaresCriticalEntitiesForBusinessScenes() {
        val productionProvider = SceneHotwordProvider(
            SceneHotwordConfigSource {
                File(projectRoot(), "app/src/main/assets/scene_hotwords.json").readText(Charsets.UTF_8)
            },
        )

        assertThat(productionProvider.configuration()).isNotNull()
        assertThat(productionProvider.criticalEntitiesFor(SceneType.RIDE_HAILING)).isNotEmpty()
        assertThat(productionProvider.criticalEntitiesFor(SceneType.CUSTOMER_SERVICE)).isNotEmpty()
        assertThat(productionProvider.criticalEntitiesFor(SceneType.REAL_ESTATE)).isNotEmpty()
        assertThat(productionProvider.criticalEntitiesFor(SceneType.INSURANCE_FINANCE)).isNotEmpty()
        assertThat(productionProvider.criticalEntitiesFor(SceneType.SPAM_RISK)).isEmpty()
    }

    @Test fun rejectsAHotwordConfigurationWithoutUnknownToken() {
        val invalid = validConfiguration.replace(", \"[unk]\"", "")

        assertThat(SceneHotwordProvider(SceneHotwordConfigSource { invalid }).configuration()).isNull()
    }

    @Test fun retriesWeakCandidateAndKeepsGeneralWhenThereIsNoCandidate() {
        val weakDelivery = preview(
            text = "订单定位显示在东门",
            scene = null,
            confidence = 0.15f,
            margin = 0.15f,
            candidates = listOf(SceneType.DELIVERY.id),
        )
        val retry = policy.retryContext(
            weakDelivery,
            SpeechRecognitionContext(languageTag = "en-US"),
        )

        assertThat(retry?.mode).isEqualTo(SpeechRecognitionMode.SCENE_VOCABULARY)
        assertThat(retry?.sceneHints).containsExactly(SceneType.DELIVERY)
        assertThat(retry?.languageTag).isEqualTo("en-US")

        val noCandidate = preview("无法识别的文本", null, 0f, 0f, emptyList())
        assertThat(policy.retryContext(noCandidate, SpeechRecognitionContext())).isNull()
    }

    @Test fun contextOnlyGateRejectsExactlyWhatRetryDecisionRejectsForTheSameContext() {
        // The caller hoists supportsRetry ahead of the preview classification, so a false result
        // must never hide a turn that retryDecision would otherwise have accepted.
        val retryable = preview(
            text = "订单定位显示在东门",
            scene = null,
            confidence = 0.15f,
            margin = 0.15f,
            candidates = listOf(SceneType.DELIVERY.id),
        )

        val general = SpeechRecognitionContext(mode = SpeechRecognitionMode.GENERAL)
        assertThat(policy.supportsRetry(general)).isTrue()
        assertThat(policy.retryDecision(retryable, general)).isNotNull()

        val sceneVocabulary = SpeechRecognitionContext(mode = SpeechRecognitionMode.SCENE_VOCABULARY)
        assertThat(policy.supportsRetry(sceneVocabulary)).isFalse()
        assertThat(policy.retryDecision(retryable, sceneVocabulary)).isNull()
    }

    @Test fun lowMarginRetryCombinesOnlyTheTopTwoCandidateScenes() {
        val ambiguous = preview(
            text = "保险公司客服来电",
            scene = SceneType.CUSTOMER_SERVICE.id,
            confidence = 0.55f,
            margin = 0.05f,
            candidates = listOf(
                SceneType.CUSTOMER_SERVICE.id,
                SceneType.INSURANCE_FINANCE.id,
                SceneType.WORK.id,
            ),
        )

        val retry = policy.retryContext(ambiguous, SpeechRecognitionContext())

        assertThat(retry?.sceneHints).containsExactly(
            SceneType.CUSTOMER_SERVICE,
            SceneType.INSURANCE_FINANCE,
        ).inOrder()
    }

    @Test fun spamRiskSceneOrCandidateDoesNotTriggerSecondaryRecognition() {
        val committed = preview(
            text = "我们不是推销",
            scene = SceneType.SPAM_RISK.id,
            confidence = 0.40f,
            margin = 0.10f,
            candidates = listOf(SceneType.SPAM_RISK.id),
        )
        assertThat(policy.retryDecision(committed, SpeechRecognitionContext())).isNull()

        val candidate = preview(
            text = "会员权益",
            scene = null,
            confidence = 0.20f,
            margin = 0.05f,
            candidates = listOf(SceneType.SPAM_RISK.id),
        )
        assertThat(policy.retryDecision(candidate, SpeechRecognitionContext())).isNull()
    }

    @Test fun secondPassCreatesSideChannelEvidenceWithoutSelectingAReplacementTranscript() {
        val first = preview("订单定位", null, 0.15f, 0.15f, listOf(SceneType.DELIVERY.id))
        val retry = checkNotNull(policy.retryDecision(first, SpeechRecognitionContext()))
        val second = preview("外卖骑手", SceneType.DELIVERY.id, 0.60f, 0.60f)

        val evidence = policy.secondaryEvidence(first, second, retry)

        assertThat(evidence.text).isEqualTo("外卖骑手")
        assertThat(evidence.matchedHotwordsByScene[SceneType.DELIVERY.id]).containsExactly("外卖 骑手")
        assertThat(evidence.textDifferenceRate).isGreaterThan(0.25)
        assertThat(evidence.triggerReasons).containsAtLeast("unclassified", "low_confidence", "low_margin")
    }

    @Test fun retryVocabularyIsLimitedToTheMissingEntityAndSceneFocuses() {
        val phrases = provider.phrasesFor(
            setOf(SceneType.DELIVERY),
            setOf(SpeechRecognitionFocus.SCENE),
        )

        assertThat(phrases).containsExactly("外卖 骑手", "[unk]").inOrder()
    }

    @Test fun knownDeliveryContinuationRetriesForEntityQualityWithoutRequiringLowSceneConfidence() {
        val knownDelivery = SpeechRecognitionContext(sceneHints = setOf(SceneType.DELIVERY))
        val lowWithMissingLocation = preview(
            text = "我在小区门口",
            scene = SceneType.DELIVERY.id,
            confidence = 0.40f,
            margin = 0.40f,
        )
        val retry = policy.retryDecision(lowWithMissingLocation, knownDelivery)
        assertThat(retry?.context?.focuses).containsExactly(SpeechRecognitionFocus.LOCATION)

        val highWithMissingLocation = preview(
            text = "我在小区门口",
            scene = SceneType.DELIVERY.id,
            confidence = 0.75f,
            margin = 0.40f,
        )
        assertThat(policy.retryDecision(highWithMissingLocation, knownDelivery)?.context?.focuses)
            .containsExactly(SpeechRecognitionFocus.LOCATION)

        val lowWithCompleteLocation = preview(
            text = "我在小区门口",
            scene = SceneType.DELIVERY.id,
            confidence = 0.40f,
            margin = 0.40f,
            slots = mapOf("location" to "小区门口"),
        )
        assertThat(policy.retryDecision(lowWithCompleteLocation, knownDelivery)).isNull()
    }

    @Test fun knownDeliveryRetriesLowQualityAndSuspectedFacilityLocations() {
        val knownDelivery = SpeechRecognitionContext(sceneHints = setOf(SceneType.DELIVERY))
        val questionContaminated = preview(
            text = "您这是哪个单元来着",
            scene = SceneType.DELIVERY.id,
            confidence = 0.75f,
            margin = 0.40f,
            slots = mapOf("location" to "您这是哪个单元"),
        )
        val questionRetry = policy.retryDecision(questionContaminated, knownDelivery)
        assertThat(questionRetry?.context?.focuses).containsExactly(SpeechRecognitionFocus.LOCATION)
        assertThat(questionRetry?.reasons).contains("invalid_location")

        val suspectedFacility = preview(
            text = "我搁在娶惭愧顶层了",
            scene = SceneType.DELIVERY.id,
            confidence = 0.75f,
            margin = 0.40f,
        )
        val facilityRetry = policy.retryDecision(suspectedFacility, knownDelivery)
        assertThat(facilityRetry?.context?.focuses).containsExactly(SpeechRecognitionFocus.LOCATION)
        assertThat(facilityRetry?.reasons).contains("suspected_location_error")
    }

    @Test fun knownTargetScenesRetryOnlyForConfiguredCriticalEntities() {
        val cases = listOf(
            CriticalEntityCase(
                scene = SceneType.RIDE_HAILING,
                text = "司机说还有五分钟到",
                intent = "driver_delay",
                focus = SpeechRecognitionFocus.TIME,
            ),
            CriticalEntityCase(
                scene = SceneType.CUSTOMER_SERVICE,
                text = "客服说订单尾号没听清",
                intent = "order_inquiry",
                focus = SpeechRecognitionFocus.ORDER,
            ),
            CriticalEntityCase(
                scene = SceneType.REAL_ESTATE,
                text = "中介说看房时间没听清",
                intent = "viewing_request",
                focus = SpeechRecognitionFocus.TIME,
            ),
            CriticalEntityCase(
                scene = SceneType.INSURANCE_FINANCE,
                text = "保险客服说保单下周到期",
                intent = "policy_expiry",
                focus = SpeechRecognitionFocus.TIME,
            ),
        )

        cases.forEach { case ->
            val initialPreview = preview(
                text = case.text,
                scene = case.scene.id,
                confidence = 0.75f,
                margin = 0.40f,
            )
            val first = initialPreview.copy(
                classification = initialPreview.classification.copy(intent = case.intent),
            )
            val context = SpeechRecognitionContext(sceneHints = setOf(case.scene))
            val retry = policy.retryDecision(first, context)

            assertThat(retry?.context?.focuses).contains(case.focus)
        }

        val noCriticalCue = preview(
            text = "售后事项正在处理",
            scene = SceneType.CUSTOMER_SERVICE.id,
            confidence = 0.75f,
            margin = 0.40f,
        )
        assertThat(
            policy.retryDecision(
                noCriticalCue,
                SpeechRecognitionContext(sceneHints = setOf(SceneType.CUSTOMER_SERVICE)),
            ),
        ).isNull()
    }

    @Test fun vocabularyActivatesAfterTwoStableTurnsAndFallsBackAfterTwoWeakTurns() {
        val tracker = SceneVocabularyTracker(policy, provider)
        val stable = preview("外卖骑手", SceneType.DELIVERY.id, 0.75f, 0.60f).classification
        val weak = preview("到了", SceneType.DELIVERY.id, 0.30f, 0.10f).classification

        tracker.observe(stable)
        assertThat(tracker.recognitionContext().mode).isEqualTo(SpeechRecognitionMode.GENERAL)
        tracker.observe(stable)
        assertThat(tracker.recognitionContext().mode).isEqualTo(SpeechRecognitionMode.SCENE_VOCABULARY)
        tracker.observe(weak)
        assertThat(tracker.recognitionContext().mode).isEqualTo(SpeechRecognitionMode.SCENE_VOCABULARY)
        tracker.observe(weak)
        assertThat(tracker.recognitionContext().mode).isEqualTo(SpeechRecognitionMode.GENERAL)
    }

    private fun preview(
        text: String,
        scene: String?,
        confidence: Float,
        margin: Float,
        candidates: List<String> = listOfNotNull(scene),
        slots: Map<String, String> = emptyMap(),
    ) = RecognitionPreview(
        text,
        RuleClassificationResult(
            scene = scene,
            intent = scene?.let { "intent" },
            confidence = confidence,
            sceneMargin = margin,
            sceneCandidates = candidates,
            extractedSlots = slots,
        ),
    )

    private val validConfiguration = """
        {
          "schemaVersion": 1,
          "policy": {
            "retryBelowConfidence": 0.60,
            "minimumCandidateConfidence": 0.15,
            "minimumSceneMargin": 0.18,
            "replacementConfidenceGain": 0.15,
            "stableTurns": 2,
            "weakTurnsBeforeGeneral": 2
          },
          "scenes": {
            "delivery": ["外卖 骑手", "小区 西门", "[unk]"],
            "ride_hailing": ["滴滴 司机", "还有 五分钟 到", "[unk]"],
            "customer_service": ["客服 售后", "订单 尾号", "[unk]"],
            "real_estate": ["房产 中介", "看房 时间", "[unk]"],
            "insurance_finance": ["保险 理赔", "保单 到期", "[unk]"],
            "spam_risk": ["贷款 优惠", "[unk]"]
          },
          "criticalEntities": {
            "delivery": [
              {"slot": "location", "focus": "LOCATION", "cues": ["小区", "门口"], "requiredIntents": ["delivery_placed"]}
            ],
            "ride_hailing": [
              {"slot": "estimatedTime", "focus": "TIME", "cues": ["分钟"], "requiredIntents": ["driver_delay"]}
            ],
            "customer_service": [
              {"slot": "orderId", "focus": "ORDER", "cues": ["订单尾号"]}
            ],
            "real_estate": [
              {"slot": "viewingTime", "focus": "TIME", "cues": ["看房时间"], "requiredIntents": ["viewing_request"]}
            ],
            "insurance_finance": [
              {"slot": "expiryTime", "focus": "TIME", "cues": ["到期"], "requiredIntents": ["policy_expiry"]}
            ]
          }
        }
    """.trimIndent()

    private data class CriticalEntityCase(
        val scene: SceneType,
        val text: String,
        val intent: String,
        val focus: SpeechRecognitionFocus,
    )

    private fun projectRoot(): File {
        var current: File? = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("Unable to locate CallDelegate project root")
    }
}
