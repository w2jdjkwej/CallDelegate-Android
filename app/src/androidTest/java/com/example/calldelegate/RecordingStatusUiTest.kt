package com.example.calldelegate

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.feature.main.ui.RecordingStatusContent
import com.example.calldelegate.feature.main.ui.recordingStatus
import org.junit.Rule
import org.junit.Test

class RecordingStatusUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsIncompleteFailedLegacyAndPlaybackFailureSeparately() {
        composeRule.setContent {
            Column {
                RecordingStatusContent(
                    recordingStatus(RecordingIntegrity.PARTIAL, "/partial.wav"),
                    AudioFailure("AUDIO_SAVE", "写入失败"),
                    null,
                )
                RecordingStatusContent(
                    recordingStatus(RecordingIntegrity.FAILED, null),
                    null,
                    AudioFailure("AUDIO_PLAY", "扬声器不可用"),
                )
                RecordingStatusContent(
                    recordingStatus(RecordingIntegrity.LEGACY_UNVERIFIED, "/legacy.wav"),
                    null,
                    null,
                )
            }
        }

        composeRule.onNodeWithText("录音不完整").assertIsDisplayed()
        composeRule.onNodeWithText("录音失败").assertIsDisplayed()
        composeRule.onNodeWithText("旧版录音，完整性未经验证").assertIsDisplayed()
        composeRule.onNodeWithText("录音错误（AUDIO_SAVE）：写入失败").assertIsDisplayed()
        composeRule.onNodeWithText("播放失败（AUDIO_PLAY）：扬声器不可用").assertIsDisplayed()
    }
}
