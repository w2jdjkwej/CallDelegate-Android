package com.example.calldelegate.telecom.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ScrcpyOpusPacketReaderTest {
    @Test
    fun readsCodecConfigurationAndAudioPacket() {
        val stream = packetStream(
            Packet(ptsAndFlags = CONFIG_PACKET_FLAG, data = byteArrayOf(1, 2, 3)),
            Packet(ptsAndFlags = 12_345L, data = byteArrayOf(4, 5)),
        )
        val packets = mutableListOf<ScrcpyOpusPacket>()

        ScrcpyOpusPacketReader().read(
            input = ByteArrayInputStream(stream),
            onPacket = packets::add,
        )

        assertThat(packets).hasSize(2)
        assertThat(packets[0].isConfig).isTrue()
        assertThat(packets[0].data).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(packets[1].isConfig).isFalse()
        assertThat(packets[1].presentationTimeUs).isEqualTo(12_345L)
        assertThat(packets[1].data).isEqualTo(byteArrayOf(4, 5))
    }

    @Test
    fun rejectsStreamWhoseFirstPacketIsNotConfiguration() {
        val stream = packetStream(
            Packet(ptsAndFlags = 100L, data = byteArrayOf(7)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyOpusPacketReader().read(
                input = ByteArrayInputStream(stream),
                onPacket = {},
            )
        }
    }

    @Test
    fun rejectsImplausiblyLargePacketBeforeAllocating() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(ScrcpyServerSpec.OPUS_FOUR_CC)
            stream.writeLong(CONFIG_PACKET_FLAG)
            stream.writeInt(1025)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ScrcpyOpusPacketReader(maxPacketBytes = 1024).read(
                input = ByteArrayInputStream(output.toByteArray()),
                onPacket = {},
            )
        }
    }

    private fun packetStream(vararg packets: Packet): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(ScrcpyServerSpec.OPUS_FOUR_CC)
            for (packet in packets) {
                stream.writeLong(packet.ptsAndFlags)
                stream.writeInt(packet.data.size)
                stream.write(packet.data)
            }
        }
        return output.toByteArray()
    }

    private data class Packet(
        val ptsAndFlags: Long,
        val data: ByteArray,
    )

    private companion object {
        const val CONFIG_PACKET_FLAG = 1L shl 62
    }
}
