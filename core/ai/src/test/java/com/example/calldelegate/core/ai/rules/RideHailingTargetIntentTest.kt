package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SlotExtractionRequest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RideHailingTargetIntentTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    @Test
    fun recognizesEightCanonicalRideIntents() = runTest {
        val cases = mapOf(
            "您好，我已经到南门上车点了" to "driver_arrived",
            "您现在在哪个门，已经出来了吗" to "ask_passenger_location",
            "我想核对一下App定位的上车点" to "confirm_pickup_location",
            "我已经到附近了，但是没看到您" to "cannot_find_passenger",
            "路上堵车，我还有五分钟到" to "driver_delay",
            "我在南门等您呢，您还要多久下来" to "urge_passenger",
            "麻烦确认一下打车订单和目的地" to "confirm_order_info",
            "道路封闭进不去，需要调整上车点" to "trip_exception",
        )

        cases.forEach { (text, expectedIntent) ->
            val result = classifier.classifyDetailed(text, setOf(SceneType.RIDE_HAILING))
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(SceneType.RIDE_HAILING.id)
            assertWithMessage("input: %s", text).that(result?.intent).isEqualTo(expectedIntent)
        }
    }

    @Test
    fun phaseTwoSyntheticDatasetContainsAndRecognizesSixtyFourCases() = runTest {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(DATASET_RESOURCE))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val dataset = Json.decodeFromString<RideTargetDataset>(resource)

        assertThat(dataset.datasetId).isEqualTo("ride_hailing_phase2_synthetic_v1")
        assertThat(dataset.provenance).contains("not historical")
        assertThat(dataset.cases).hasSize(64)
        assertThat(dataset.cases.map(RideTargetCase::id)).containsExactlyElementsIn(
            (1..64).map { index -> "ride_phase2_${index.toString().padStart(3, '0')}" },
        ).inOrder()
        assertThat(dataset.cases.groupingBy(RideTargetCase::expectedIntent).eachCount().values)
            .containsExactly(8, 8, 8, 8, 8, 8, 8, 8)

        dataset.cases.forEach { case ->
            val result = classifier.classifyDetailed(case.text, setOf(SceneType.RIDE_HAILING))
            assertWithMessage("case: %s; input: %s", case.id, case.text)
                .that(result?.intent)
                .isEqualTo(case.expectedIntent)
        }
    }

    @Test
    fun arrivalDelayMissingPassengerAndUrgingAreHardNegatives() = runTest {
        val cases = mapOf(
            "我到了" to "driver_arrived",
            "我还有五分钟到" to "driver_delay",
            "我到了但是没看到您" to "cannot_find_passenger",
            "我到了，您还要多久出来" to "urge_passenger",
        )

        cases.forEach { (text, expectedIntent) ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, setOf(SceneType.RIDE_HAILING))?.intent)
                .isEqualTo(expectedIntent)
        }
    }

    @Test
    fun legacyClassifierContractStillReturnsCoarseRideIds() = runTest {
        assertThat(classifier.classify("司机已经到南门了", setOf(SceneType.RIDE_HAILING))?.intentId)
            .isEqualTo("ride_arrival")
        assertThat(classifier.classify("已经到附近但是找不到乘客", setOf(SceneType.RIDE_HAILING))?.intentId)
            .isEqualTo("ride_location_issue")
        assertThat(classifier.classify("我在南门等您，您多久下来", setOf(SceneType.RIDE_HAILING))?.intentId)
            .isEqualTo("ride_waiting")
        assertThat(classifier.classify("车辆故障需要取消订单", setOf(SceneType.RIDE_HAILING))?.intentId)
            .isEqualTo("ride_cancellation")
    }

    @Test
    fun extractsRideEntitiesWithoutGuessingPassengerData() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "我是滴滴司机，车牌京A12345，白色轿车，还有五分钟到，目的地是机场",
                expectedSlots = setOf(
                    "platform",
                    "driverName",
                    "licensePlate",
                    "vehicleColor",
                    "vehicleModel",
                    "estimatedTime",
                    "destination",
                ),
                scene = SceneType.RIDE_HAILING,
            ),
        ).slots

        assertThat(result["platform"]).isEqualTo("滴滴")
        assertThat(result["licensePlate"]).isEqualTo("京A12345")
        assertThat(result["vehicleColor"]).isEqualTo("白色")
        assertThat(result["vehicleModel"]).isEqualTo("轿车")
        assertThat(result["estimatedTime"]).isEqualTo("五分钟")
        assertThat(result["destination"]).isEqualTo("机场")
    }

    @Test
    fun rideRepliesRecordButDoNotConfirmPrivateOrTransactionalInformation() = runTest {
        val locationRequest = engine.process(
            DialogueContext("ride-location"),
            "滴滴司机问您现在在哪个门",
            false,
            setOf(SceneType.RIDE_HAILING),
        )
        assertThat(locationRequest.reply).contains("再说一遍")
        assertThat(locationRequest.reply).doesNotContain("门")

        val exception = engine.process(
            DialogueContext("ride-exception"),
            "司机说道路封闭，需要调整上车点",
            false,
            setOf(SceneType.RIDE_HAILING),
        )
        assertThat(exception.reply).contains("需机主确认")
        assertThat(exception.reply).doesNotContain("已经取消")
    }

    private companion object {
        const val DATASET_RESOURCE = "evaluation/ride_hailing_phase2_synthetic_v1.json"
    }
}

@Serializable
private data class RideTargetDataset(
    val datasetId: String,
    val provenance: String,
    val cases: List<RideTargetCase>,
)

@Serializable
private data class RideTargetCase(
    val id: String,
    val text: String,
    val expectedIntent: String,
)
