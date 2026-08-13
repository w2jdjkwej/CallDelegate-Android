package com.example.calldelegate.feature.main.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.RecordingIntegrity

data class RecordingStatusPresentation(
    val label: String,
    val canPlay: Boolean,
)

fun recordingStatus(
    integrity: RecordingIntegrity,
    audioPath: String?,
): RecordingStatusPresentation = RecordingStatusPresentation(
    label = when (integrity) {
        RecordingIntegrity.COMPLETE -> "完整录音"
        RecordingIntegrity.PARTIAL -> "录音不完整"
        RecordingIntegrity.FAILED -> "录音失败"
        RecordingIntegrity.LEGACY_UNVERIFIED -> "旧版录音，完整性未经验证"
    },
    canPlay = audioPath != null && integrity != RecordingIntegrity.FAILED,
)

@Composable
fun RecordingStatusContent(
    presentation: RecordingStatusPresentation,
    recordingFailure: AudioFailure?,
    playbackFailure: AudioFailure?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(presentation.label, modifier = Modifier.testTag("recording_status"))
        recordingFailure?.let {
            Text(
                text = "录音错误（${it.code}）：${it.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("recording_error"),
            )
        }
        playbackFailure?.let {
            Text(
                text = "播放失败（${it.code}）：${it.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("playback_error"),
            )
        }
    }
}
