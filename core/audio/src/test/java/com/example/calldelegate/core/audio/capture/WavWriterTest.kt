package com.example.calldelegate.core.audio.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun intLE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or
            ((b[offset + 1].toInt() and 0xff) shl 8) or
            ((b[offset + 2].toInt() and 0xff) shl 16) or
            ((b[offset + 3].toInt() and 0xff) shl 24)

    private fun ascii(b: ByteArray, offset: Int, len: Int): String =
        String(b, offset, len, Charsets.US_ASCII)

    @Test
    fun writesValidWavHeaderAndData() {
        val file = File(tempFolder.root, "out.wav")
        val writer = WavWriter(file, sampleRate = 16_000, channelCount = 1)
        writer.open()
        val chunk = ByteArray(4_000) { (it % 128).toByte() }
        writer.write(chunk, chunk.size)
        writer.write(chunk, chunk.size) // 8000 bytes total
        val path = writer.close()

        assertThat(path).isEqualTo(file.absolutePath)
        val bytes = file.readBytes()
        assertThat(bytes.size).isEqualTo(44 + 8_000)
        assertThat(ascii(bytes, 0, 4)).isEqualTo("RIFF")
        assertThat(ascii(bytes, 8, 4)).isEqualTo("WAVE")
        assertThat(intLE(bytes, 4)).isEqualTo(36 + 8_000) // RIFF chunk size
        assertThat(intLE(bytes, 24)).isEqualTo(16_000) // sample rate
        assertThat(intLE(bytes, 40)).isEqualTo(8_000) // data chunk size
        assertThat(writer.bytesWritten).isEqualTo(8_000)
    }

    @Test
    fun closeWithoutOpenReturnsNull() {
        val file = File(tempFolder.root, "unopened.wav")
        val writer = WavWriter(file, sampleRate = 16_000, channelCount = 1)
        assertThat(writer.close()).isNull()
    }

    @Test
    fun writeRespectsProvidedLength() {
        val file = File(tempFolder.root, "partial.wav")
        val writer = WavWriter(file, sampleRate = 16_000, channelCount = 1)
        writer.open()
        val buffer = ByteArray(1_000)
        writer.write(buffer, 400) // only 400 of 1000 bytes are valid
        writer.close()

        val bytes = file.readBytes()
        assertThat(intLE(bytes, 40)).isEqualTo(400)
        assertThat(bytes.size).isEqualTo(44 + 400)
    }
}
