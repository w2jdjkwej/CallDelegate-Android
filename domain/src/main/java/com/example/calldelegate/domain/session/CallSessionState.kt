package com.example.calldelegate.domain.session

import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.TranscriptTurn

sealed interface SessionPhase {
    data object IDLE : SessionPhase
    data object RINGING : SessionPhase
    data object OPENING : SessionPhase
    data object SPEAKING : SessionPhase
    data object LISTENING : SessionPhase
    data object RECORDING : SessionPhase
    data object RECOGNIZING : SessionPhase
    data object THINKING : SessionPhase
    data object AWAITING_INPUT : SessionPhase
    data object REQUESTING_TAKEOVER : SessionPhase
    data object ENDING : SessionPhase
    data object COMPLETED : SessionPhase
    data object ERROR : SessionPhase
}

data class CallSessionSnapshot(
    val sessionId: String? = null,
    val callerName: String? = null,
    val callerNumber: String = "",
    val callStatus: CallStatus? = null,
    val phase: SessionPhase = SessionPhase.IDLE,
    val scene: SceneType = SceneType.UNCLASSIFIED,
    val dialogueStateId: String = "idle",
    val inputMode: InputMode = InputMode.TEXT,
    val transcript: List<TranscriptTurn> = emptyList(),
    val structuredResult: StructuredResult = StructuredResult(),
    val latestReply: String = "",
    val latestReplyTemplateId: String? = null,
    val latestReplyVariables: Map<String, String> = emptyMap(),
    val latestReplyIsFallbackTemplate: Boolean? = null,
    val latestReplyFallbackReason: String? = null,
    val latestReplySafe: Boolean? = null,
    val latestReplyComplianceFlags: List<String> = emptyList(),
    val recognitionFailed: Boolean = false,
    val takeoverRequested: Boolean = false,
    val activeRecordingPath: String? = null,
    val lastError: String? = null,
    val completedRecordId: String? = null,
    val recordingIntegrity: RecordingIntegrity = RecordingIntegrity.COMPLETE,
    val recordingFailure: AudioFailure? = null,
    val playbackFailure: AudioFailure? = null,
)
