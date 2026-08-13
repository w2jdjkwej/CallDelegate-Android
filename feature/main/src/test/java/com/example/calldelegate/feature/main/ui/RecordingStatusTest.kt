package com.example.calldelegate.feature.main.ui

import com.example.calldelegate.domain.model.RecordingIntegrity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingStatusTest {
    @Test
    fun mapsIntegrityToExplicitLabelsAndPlaybackPolicy() {
        assertThat(recordingStatus(RecordingIntegrity.COMPLETE, "/x.wav").label).isEqualTo("完整录音")
        assertThat(recordingStatus(RecordingIntegrity.COMPLETE, "/x.wav").canPlay).isTrue()

        assertThat(recordingStatus(RecordingIntegrity.PARTIAL, "/x.wav").label).isEqualTo("录音不完整")
        assertThat(recordingStatus(RecordingIntegrity.PARTIAL, "/x.wav").canPlay).isTrue()

        assertThat(recordingStatus(RecordingIntegrity.FAILED, null).label).isEqualTo("录音失败")
        assertThat(recordingStatus(RecordingIntegrity.FAILED, null).canPlay).isFalse()

        val legacy = recordingStatus(RecordingIntegrity.LEGACY_UNVERIFIED, "/x.wav")
        assertThat(legacy.label).isEqualTo("旧版录音，完整性未经验证")
        assertThat(legacy.canPlay).isTrue()
    }

    @Test
    fun failedRecordingCannotPlayEvenIfAPathIsPresent() {
        assertThat(recordingStatus(RecordingIntegrity.FAILED, "/stale.wav").canPlay).isFalse()
    }
}
