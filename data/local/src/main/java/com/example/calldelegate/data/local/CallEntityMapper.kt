package com.example.calldelegate.data.local

import com.example.calldelegate.data.local.db.CallEntity
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.TranscriptTurn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class CallEntityMapper(private val json: Json) {
    fun toEntity(record: CallRecord) = CallEntity(
        id = record.id,
        callerName = record.callerName,
        callerNumber = record.callerNumber,
        sceneId = record.scene.id,
        summary = record.summary,
        structuredResultJson = json.encodeToString(StructuredResult.serializer(), record.structuredResult),
        transcriptJson = json.encodeToString(ListSerializer(TranscriptTurn.serializer()), record.transcript),
        audioPath = record.audioPath,
        startedAtMillis = record.startedAtMillis,
        endedAtMillis = record.endedAtMillis,
        status = record.status.name,
        inputMode = record.inputMode.name,
        recognitionFailed = record.recognitionFailed,
        takeoverRequested = record.takeoverRequested,
        recordingIntegrity = record.recordingIntegrity.name,
        recordingErrorCode = record.recordingFailure?.code,
        recordingErrorMessage = record.recordingFailure?.message,
        playbackErrorCode = record.playbackFailure?.code,
        playbackErrorMessage = record.playbackFailure?.message,
    )

    fun fromEntity(entity: CallEntity): CallRecord = CallRecord(
        id = entity.id,
        callerName = entity.callerName,
        callerNumber = entity.callerNumber,
        scene = SceneType.fromId(entity.sceneId),
        summary = entity.summary,
        structuredResult = runCatching {
            json.decodeFromString(StructuredResult.serializer(), entity.structuredResultJson)
        }.getOrDefault(StructuredResult()),
        transcript = runCatching {
            json.decodeFromString(ListSerializer(TranscriptTurn.serializer()), entity.transcriptJson)
        }.getOrDefault(emptyList()),
        audioPath = entity.audioPath,
        startedAtMillis = entity.startedAtMillis,
        endedAtMillis = entity.endedAtMillis,
        status = runCatching { CallStatus.valueOf(entity.status) }.getOrDefault(CallStatus.FAILED),
        inputMode = runCatching { InputMode.valueOf(entity.inputMode) }.getOrDefault(InputMode.TEXT),
        recognitionFailed = entity.recognitionFailed,
        takeoverRequested = entity.takeoverRequested,
        recordingIntegrity = runCatching {
            RecordingIntegrity.valueOf(entity.recordingIntegrity)
        }.getOrDefault(RecordingIntegrity.LEGACY_UNVERIFIED),
        recordingFailure = audioFailure(entity.recordingErrorCode, entity.recordingErrorMessage),
        playbackFailure = audioFailure(entity.playbackErrorCode, entity.playbackErrorMessage),
    )

    private fun audioFailure(code: String?, message: String?): AudioFailure? =
        if (code != null && message != null) AudioFailure(code, message) else null
}
