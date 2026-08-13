package com.example.calldelegate.data.local

import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.StructuredResult
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class CallEntityMapperTest {
    private val mapper = CallEntityMapper(Json { ignoreUnknownKeys = true; encodeDefaults = true })

    @Test
    fun roundTripPreservesRecordingIntegrityAndSeparateFailures() {
        RecordingIntegrity.entries.forEach { integrity ->
            val expected = CallRecord(
                id = "record-${integrity.name}",
                callerName = "caller",
                callerNumber = "10086",
                scene = SceneType.WORK,
                summary = "summary",
                structuredResult = StructuredResult(purpose = "purpose"),
                transcript = emptyList(),
                audioPath = "/recordings/test.wav",
                startedAtMillis = 1L,
                endedAtMillis = 2L,
                status = CallStatus.COMPLETED,
                inputMode = InputMode.MICROPHONE,
                recognitionFailed = false,
                takeoverRequested = false,
                recordingIntegrity = integrity,
                recordingFailure = AudioFailure("REC_${integrity.name}", "recording message"),
                playbackFailure = AudioFailure("PLAY_${integrity.name}", "playback message"),
            )

            val actual = mapper.fromEntity(mapper.toEntity(expected))

            assertThat(actual.recordingIntegrity).isEqualTo(integrity)
            assertThat(actual.recordingFailure).isEqualTo(expected.recordingFailure)
            assertThat(actual.playbackFailure).isEqualTo(expected.playbackFailure)
        }
    }

    @Test
    fun readsLegacyStructuredJsonWithoutNewDeliveryFields() {
        val expected = CallRecord(
            id = "legacy-structured-result",
            callerName = null,
            callerNumber = "10086",
            scene = SceneType.DELIVERY,
            summary = "legacy",
            structuredResult = StructuredResult(),
            transcript = emptyList(),
            audioPath = null,
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            status = CallStatus.COMPLETED,
            inputMode = InputMode.TEXT,
            recognitionFailed = false,
            takeoverRequested = false,
        )
        val entity = mapper.toEntity(expected).copy(
            structuredResultJson = """{
                "purpose":"旧配送事项",
                "time":"十分钟",
                "extras":{"deliveryLocation":"北门","orderId":"A12345"}
            }""".trimIndent(),
        )

        val actual = mapper.fromEntity(entity).structuredResult

        assertThat(actual.purpose).isEqualTo("旧配送事项")
        assertThat(actual.asEntityMap(SceneType.DELIVERY)).containsExactly(
            "location", "北门",
            "orderNumber", "A12345",
            "estimatedTime", "十分钟",
        )
        assertThat(actual.issueType).isNull()
        assertThat(actual.orderNumber).isNull()
        assertThat(actual.estimatedTime).isNull()
    }

    @Test
    fun deliveryProjectionAcceptsLegacyAliasesWithoutOutputtingThem() {
        val result = StructuredResult().merge(
            SceneType.DELIVERY,
            mapOf(
                "purpose" to "整句原文",
                "deliveryLocation" to "公司前台",
                "orderId" to "A12345",
                "time" to "十分钟",
            ),
        )

        assertThat(result.asEntityMap(SceneType.DELIVERY)).containsExactly(
            "location", "公司前台",
            "orderNumber", "A12345",
            "estimatedTime", "十分钟",
        )
        assertThat(result.purpose).isNull()
        assertThat(result.extras).isEmpty()
    }

    @Test
    fun nonDeliveryProjectionKeepsItsFormalTimeField() {
        val result = StructuredResult(time = "明天下午三点", purpose = "会议通知")

        assertThat(result.asEntityMap(SceneType.WORK)).containsExactly("time", "明天下午三点")
    }

    @Test
    fun riskExtrasSurviveCallRecordPersistenceRoundTrip() {
        val structuredResult = StructuredResult().merge(
            mapOf(
                "riskLevel" to "HIGH",
                "riskReason" to "request_sms_code,request_transfer",
                "sensitiveInfoType" to "sms_code,funds",
            ),
        )
        val record = CallRecord(
            id = "risk-round-trip",
            callerName = null,
            callerNumber = "10086",
            scene = SceneType.SPAM_RISK,
            summary = "疑似诈骗来电",
            structuredResult = structuredResult,
            transcript = emptyList(),
            audioPath = null,
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            status = CallStatus.COMPLETED,
            inputMode = InputMode.TEXT,
            recognitionFailed = false,
            takeoverRequested = false,
        )

        val restored = mapper.fromEntity(mapper.toEntity(record))

        assertThat(restored.structuredResult.extras).containsExactly(
            "riskLevel", "HIGH",
            "riskReason", "request_sms_code,request_transfer",
            "sensitiveInfoType", "sms_code,funds",
        )
    }
}
