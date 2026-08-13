package com.example.calldelegate.core.audio

import com.example.calldelegate.core.common.AppResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavSessionRecordingStoreTest {
    @Test
    fun rejectsAnyPcmThatIsNotAtTheSessionRate() = runBlocking {
        val directory = Files.createTempDirectory("wav-rate-test").toFile()
        try {
            val result = WavSessionRecordingStore(directory).appendPcm(
                sessionId = "mixed-rate",
                samples = shortArrayOf(1, 2, 3),
                sampleRateHz = 22_050,
            )

            assertThat(result).isInstanceOf(AppResult.Failure::class.java)
            assertThat((result as AppResult.Failure).error.code).isEqualTo("AUDIO_RATE_MISMATCH")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun appendsMultipleSessionRateSegmentsToFixedFormatWav() = runBlocking {
        val directory = Files.createTempDirectory("wav-format-test").toFile()
        try {
            val store = WavSessionRecordingStore(directory)
            assertThat(store.appendPcm("session", shortArrayOf(100, 200), 16_000))
                .isInstanceOf(AppResult.Success::class.java)
            assertThat(store.appendPcm("session", shortArrayOf(300, 400, 500), 16_000))
                .isInstanceOf(AppResult.Success::class.java)

            val finalized = store.finalizeSession("session") as AppResult.Success
            val bytes = java.io.File(finalized.value!!).readBytes()
            val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            assertThat(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)).isEqualTo("RIFF")
            assertThat(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)).isEqualTo("WAVE")
            assertThat(littleEndian.getShort(22).toInt()).isEqualTo(1)
            assertThat(littleEndian.getInt(24)).isEqualTo(16_000)
            assertThat(littleEndian.getShort(34).toInt()).isEqualTo(16)
            assertThat(littleEndian.getInt(40)).isEqualTo(10)
            assertThat(bytes).hasLength(54)
        } finally {
            directory.deleteRecursively()
        }
    }
}
