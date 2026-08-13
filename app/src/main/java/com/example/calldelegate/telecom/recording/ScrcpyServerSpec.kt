package com.example.calldelegate.telecom.recording

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

object ScrcpyServerSpec {
    const val VERSION = "4.0"
    const val ASSET_NAME = "scrcpy-server-v4.0"
    const val EXPECTED_SHA256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
    const val MAIN_CLASS = "com.genymobile.scrcpy.Server"
    const val SOCKET_PREFIX = "scrcpy_"
    const val OPUS_FOUR_CC = 0x6F707573
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val DEFAULT_AUDIO_SOURCE = "voice-call"
    const val DEFAULT_BIT_RATE = 32_000

    private val allowedAudioSources = setOf(
        "voice-call",
        "voice-call-uplink",
        "voice-call-downlink",
        "mic-voice-communication",
    )

    fun newSocketId(): String =
        SecureRandom().nextInt(Int.MAX_VALUE).toString(16).padStart(8, '0')

    fun isValidSocketId(value: String): Boolean =
        value.length == 8 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun buildServerCommand(
        socketId: String,
        audioSource: String,
        audioBitRate: Int,
    ): List<String> {
        require(isValidSocketId(socketId)) { "Invalid scrcpy socket id" }
        require(audioSource in allowedAudioSources) { "Unsupported scrcpy audio source" }
        require(audioBitRate in 8_000..128_000) { "Invalid audio bit rate" }

        return listOf(
            "app_process",
            "/",
            MAIN_CLASS,
            VERSION,
            "log_level=warn",
            "video=false",
            "audio=true",
            "control=false",
            "tunnel_forward=false",
            "send_dummy_byte=false",
            "scid=$socketId",
            "audio_source=$audioSource",
            "audio_codec=opus",
            "audio_bit_rate=$audioBitRate",
            "send_device_meta=false",
            "send_frame_meta=true",
            "send_stream_meta=true",
        )
    }

    fun verify(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            actual.equals(EXPECTED_SHA256, ignoreCase = true)
        }.getOrDefault(false)
    }
}
