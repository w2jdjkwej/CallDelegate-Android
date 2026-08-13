package com.example.calldelegate.telecom.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ScrcpyServerSpecTest {
    @Test
    fun buildServerCommandUsesAudioOnlyOpusCapture() {
        val command = ScrcpyServerSpec.buildServerCommand(
            socketId = "1234abcd",
            audioSource = "voice-call",
            audioBitRate = 32_000,
        )

        assertThat(command.take(4)).containsExactly(
            "app_process",
            "/",
            ScrcpyServerSpec.MAIN_CLASS,
            ScrcpyServerSpec.VERSION,
        ).inOrder()
        assertThat(command).contains("video=false")
        assertThat(command).contains("audio=true")
        assertThat(command).contains("control=false")
        assertThat(command).contains("audio_source=voice-call")
        assertThat(command).contains("audio_codec=opus")
        assertThat(command).contains("scid=1234abcd")
    }

    @Test
    fun buildServerCommandRejectsUnsafeArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyServerSpec.buildServerCommand(
                socketId = "bad;id",
                audioSource = "voice-call",
                audioBitRate = 32_000,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyServerSpec.buildServerCommand(
                socketId = "1234abcd",
                audioSource = "anything",
                audioBitRate = 32_000,
            )
        }
    }
}
