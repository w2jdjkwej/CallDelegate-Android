package com.example.calldelegate.telecom.recording

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

data class ScrcpyOpusPacket(
    val presentationTimeUs: Long,
    val isConfig: Boolean,
    val data: ByteArray,
)

class ScrcpyOpusPacketReader(
    private val maxPacketBytes: Int = 1024 * 1024,
) {
    fun read(
        input: InputStream,
        shouldContinue: () -> Boolean = { true },
        onPacket: (ScrcpyOpusPacket) -> Unit,
    ) {
        DataInputStream(BufferedInputStream(input)).use { stream ->
            val codec = stream.readInt()
            require(codec == ScrcpyServerSpec.OPUS_FOUR_CC) {
                "Unexpected scrcpy audio codec: 0x${codec.toString(16)}"
            }

            var firstPacket = true
            while (shouldContinue()) {
                val ptsAndFlags = try {
                    stream.readLong()
                } catch (_: EOFException) {
                    break
                }
                val packetSize = stream.readInt()
                require(packetSize in 1..maxPacketBytes) {
                    "Invalid scrcpy packet size: $packetSize"
                }

                val isConfig = ptsAndFlags and CONFIG_PACKET_FLAG != 0L
                if (firstPacket) {
                    require(isConfig) { "First scrcpy audio packet is not codec configuration" }
                    firstPacket = false
                }

                val payload = ByteArray(packetSize)
                stream.readFully(payload)
                onPacket(
                    ScrcpyOpusPacket(
                        presentationTimeUs = ptsAndFlags and PTS_MASK,
                        isConfig = isConfig,
                        data = payload,
                    ),
                )
            }
        }
    }

    private companion object {
        const val CONFIG_PACKET_FLAG = 1L shl 62
        const val PTS_MASK = (1L shl 61) - 1L
    }
}
