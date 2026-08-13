package com.example.calldelegate.data.local.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "call_records")
data class CallEntity(
    @PrimaryKey val id: String,
    val callerName: String?,
    val callerNumber: String,
    val sceneId: String,
    val summary: String,
    val structuredResultJson: String,
    val transcriptJson: String,
    val audioPath: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val status: String,
    val inputMode: String,
    val recognitionFailed: Boolean,
    val takeoverRequested: Boolean,
    @ColumnInfo(defaultValue = "'LEGACY_UNVERIFIED'") val recordingIntegrity: String = "COMPLETE",
    val recordingErrorCode: String? = null,
    val recordingErrorMessage: String? = null,
    val playbackErrorCode: String? = null,
    val playbackErrorMessage: String? = null,
)
