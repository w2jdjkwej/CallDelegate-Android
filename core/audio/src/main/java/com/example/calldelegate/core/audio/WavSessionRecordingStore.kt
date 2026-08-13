package com.example.calldelegate.core.audio

import android.content.Context
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SessionRecordingStore
import com.example.calldelegate.domain.model.SESSION_RECORDING_SAMPLE_RATE_HZ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class WavSessionRecordingStore(private val directory: File) : SessionRecordingStore {
    constructor(context: Context) : this(File(context.filesDir, "recordings"))

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    @Volatile private var initialized = false

    override suspend fun appendPcm(
        sessionId: String,
        samples: ShortArray,
        sampleRateHz: Int,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        ensureDirectory()
        if (sampleRateHz != SESSION_RECORDING_SAMPLE_RATE_HZ) {
            return@withContext AppResult.Failure(
                AppError("AUDIO_RATE_MISMATCH", "会话录音必须为 16000Hz"),
            )
        }
        if (samples.isEmpty()) return@withContext AppResult.Success(finalFile(safeId(sessionId)).absolutePath)
        val safeId = safeId(sessionId)
        mutexes.getOrPut(safeId) { Mutex() }.withLock {
            runCatching {
                BufferedOutputStream(FileOutputStream(pcmFile(safeId), true)).use { output ->
                    val bytes = ByteArray(samples.size * 2)
                    samples.forEachIndexed { index, value ->
                        val intValue = value.toInt()
                        bytes[index * 2] = (intValue and 0xff).toByte()
                        bytes[index * 2 + 1] = ((intValue ushr 8) and 0xff).toByte()
                    }
                    output.write(bytes)
                }
                finalFile(safeId).absolutePath
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(AppError("AUDIO_SAVE", "录音写入失败", it.message)) },
            )
        }
    }

    override suspend fun finalizeSession(sessionId: String): AppResult<String?> = withContext(Dispatchers.IO) {
        ensureDirectory()
        val safeId = safeId(sessionId)
        mutexes.getOrPut(safeId) { Mutex() }.withLock {
            val pcm = pcmFile(safeId)
            if (!pcm.isFile || pcm.length() == 0L) return@withLock AppResult.Success(null)
            val wav = finalFile(safeId)
            val temp = File(directory, "$safeId.wav.tmp")
            runCatching {
                RandomAccessFile(temp, "rw").use { output ->
                    output.setLength(0L)
                    writeWavHeader(output, pcm.length(), SESSION_RECORDING_SAMPLE_RATE_HZ)
                    BufferedInputStream(FileInputStream(pcm)).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    output.fd.sync()
                }
                if (wav.exists() && !wav.delete()) error("Cannot replace existing recording")
                if (!temp.renameTo(wav)) error("Cannot commit WAV")
                pcm.delete()
                mutexes.remove(safeId)
                wav.absolutePath
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(AppError("AUDIO_FINALIZE", "录音文件生成失败", it.message)) },
            )
        }
    }

    override suspend fun discardSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            ensureDirectory()
            val safeId = safeId(sessionId)
            pcmFile(safeId).delete()
            finalFile(safeId).delete()
            File(directory, "$safeId.wav.tmp").delete()
            mutexes.remove(safeId)
        }
    }

    private fun pcmFile(id: String) = File(directory, "$id.pcm.tmp")
    private fun finalFile(id: String) = File(directory, "$id.wav")
    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "session" }

    @Synchronized
    private fun ensureDirectory() {
        if (initialized) return
        directory.mkdirs()
        // A process death cannot produce a valid WAV header. Remove only unfinished files; committed WAV files stay intact.
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".pcm.tmp") || it.name.endsWith(".wav.tmp") }
            .forEach(File::delete)
        initialized = true
    }

    private fun writeWavHeader(file: RandomAccessFile, dataSize: Long, sampleRate: Int) {
        val byteRate = sampleRate * 2
        file.writeBytes("RIFF")
        file.writeIntLE((36L + dataSize).toInt())
        file.writeBytes("WAVEfmt ")
        file.writeIntLE(16)
        file.writeShortLE(1)
        file.writeShortLE(1)
        file.writeIntLE(sampleRate)
        file.writeIntLE(byteRate)
        file.writeShortLE(2)
        file.writeShortLE(16)
        file.writeBytes("data")
        file.writeIntLE(dataSize.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff); write((value ushr 16) and 0xff); write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff)
    }
}
