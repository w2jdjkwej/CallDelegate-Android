package com.example.calldelegate.core.audio.capture

import java.io.File
import java.io.RandomAccessFile

/**
 * Incremental 16-bit PCM WAV writer. Writes a placeholder header on [open], streams raw PCM via
 * [write], then patches the RIFF/data sizes on [close]. Kept pure-JVM (java.io only) so the capture
 * engine stays unit-testable against a temp directory.
 *
 * Not thread-safe: the capture engine confines all calls to its single capture thread.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int = 16,
) {
    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0

    fun open() {
        file.parentFile?.mkdirs()
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        writeHeader(out, dataSize = 0)
        raf = out
        dataBytes = 0
    }

    fun write(data: ByteArray, length: Int) {
        val out = raf ?: return
        val usable = length.coerceAtMost(data.size)
        if (usable <= 0) return
        out.write(data, 0, usable)
        dataBytes += usable
    }

    /** Patches header sizes, syncs and closes. Returns the file path, or null if nothing was open. */
    fun close(): String? {
        val out = raf ?: return null
        return try {
            out.seek(0)
            writeHeader(out, dataBytes)
            out.fd.sync()
            file.absolutePath
        } finally {
            runCatching { out.close() }
            raf = null
        }
    }

    val bytesWritten: Long get() = dataBytes

    private fun writeHeader(out: RandomAccessFile, dataSize: Long) {
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        out.writeBytes("RIFF")
        out.writeIntLE((36L + dataSize).toInt())
        out.writeBytes("WAVEfmt ")
        out.writeIntLE(16)
        out.writeShortLE(1)
        out.writeShortLE(channelCount)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(blockAlign)
        out.writeShortLE(bitsPerSample)
        out.writeBytes("data")
        out.writeIntLE(dataSize.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }
}
