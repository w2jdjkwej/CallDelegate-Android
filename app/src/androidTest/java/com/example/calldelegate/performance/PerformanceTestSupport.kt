package com.example.calldelegate.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calldelegate.domain.model.CapturedAudio
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class ProcessResourceSample(
    val elapsedRealtimeMs: Long,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val cpuTimeMs: Long,
    val cpuPercent: Double?,
    val threadCount: Int?,
    val openFileDescriptorCount: Int?,
    val thermalStatus: Int?,
)

/** Samples the app process only. CPU percent is normalized to one logical CPU and may exceed 100%. */
class ProcessResourceSampler(private val context: Context) {
    private var previousElapsedMs: Long? = null
    private var previousCpuMs: Long? = null

    fun snapshot(): ProcessResourceSample {
        val elapsedMs = SystemClock.elapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime().coerceAtLeast(0L)
        val cpuPercent = cpuPercent(elapsedMs, cpuMs)
        val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val powerManager = context.getSystemService(PowerManager::class.java)

        previousElapsedMs = elapsedMs
        previousCpuMs = cpuMs
        return ProcessResourceSample(
            elapsedRealtimeMs = elapsedMs,
            totalPssKb = memoryInfo.totalPss,
            dalvikPssKb = memoryInfo.dalvikPss,
            nativePssKb = memoryInfo.nativePss,
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            cpuTimeMs = cpuMs,
            cpuPercent = cpuPercent,
            threadCount = readStatusValue("Threads:"),
            openFileDescriptorCount = File("/proc/self/fd").list()?.size,
            thermalStatus = powerManager?.currentThermalStatus,
        )
    }

    private fun cpuPercent(elapsedMs: Long, cpuMs: Long): Double? {
        val previousElapsed = previousElapsedMs ?: return null
        val previousCpu = previousCpuMs ?: return null
        val elapsedDelta = elapsedMs - previousElapsed
        if (elapsedDelta <= 0L) return null
        return (cpuMs - previousCpu).coerceAtLeast(0L) * 100.0 / elapsedDelta
    }

    private fun readStatusValue(prefix: String): Int? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith(prefix) }
                ?.substringAfter(prefix)
                ?.trim()
                ?.substringBefore(' ')
                ?.toIntOrNull()
        }
    }.getOrNull()
}

data class WavFixture(
    val audioId: String,
    val audio: CapturedAudio,
)

/** Reads a controlled fixture from /data/local/tmp through instrumentation shell access. */
object WavFixtureLoader {
    fun load(path: String, audioId: String): WavFixture {
        require(path.startsWith(DEVICE_FIXTURE_DIRECTORY)) {
            "performanceAudioPath must be under $DEVICE_FIXTURE_DIRECTORY"
        }
        require(path.matches(SAFE_DEVICE_PATH)) { "performanceAudioPath contains unsupported characters" }
        require(path.split('/').none { it == ".." }) { "performanceAudioPath must not contain parent traversal" }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $path"),
        ).use { it.readBytes() }
        require(bytes.isNotEmpty()) { "Performance WAV fixture is empty: $path" }
        return WavFixture(audioId, parsePcm16MonoWave(bytes))
    }

    private fun parsePcm16MonoWave(bytes: ByteArray): CapturedAudio {
        require(bytes.size >= RIFF_HEADER_BYTES && bytes.asAscii(0, 4) == "RIFF" && bytes.asAscii(8, 4) == "WAVE") {
            "Performance fixture must be a RIFF/WAVE file"
        }
        var offset = 12
        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var pcmData: ByteArray? = null
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkId = bytes.asAscii(offset, 4)
            val chunkSize = bytes.readIntLe(offset + 4)
            val dataOffset = offset + CHUNK_HEADER_BYTES
            require(chunkSize >= 0 && dataOffset + chunkSize <= bytes.size) { "Invalid WAV chunk" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= PCM_FORMAT_BYTES) { "WAV fmt chunk is too short" }
                    require(bytes.readShortLe(dataOffset) == PCM_FORMAT) { "Only PCM WAV is supported" }
                    channelCount = bytes.readShortLe(dataOffset + 2)
                    sampleRate = bytes.readIntLe(dataOffset + 4)
                    bitsPerSample = bytes.readShortLe(dataOffset + 14)
                }
                "data" -> pcmData = bytes.copyOfRange(dataOffset, dataOffset + chunkSize)
            }
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }
        require(channelCount == 1) { "Performance fixture must be mono PCM" }
        require(sampleRate == REQUIRED_SAMPLE_RATE_HZ) { "Performance fixture must be 16 kHz" }
        require(bitsPerSample == REQUIRED_BITS_PER_SAMPLE) { "Performance fixture must be PCM16" }
        val data = requireNotNull(pcmData) { "WAV data chunk is missing" }
        require(data.size % 2 == 0) { "PCM16 data size must be even" }
        val samples = ShortArray(data.size / 2) { index -> data.readShortLe(index * 2).toShort() }
        require(samples.isNotEmpty()) { "Performance fixture has no PCM samples" }
        return CapturedAudio(
            pcm16 = samples,
            sampleRateHz = sampleRate,
            durationMillis = samples.size * 1_000L / sampleRate,
            recordingPath = null,
            transcriptHint = null,
            speechDetected = true,
        )
    }

    private fun ByteArray.readShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readIntLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.asAscii(offset: Int, length: Int): String =
        String(this, offset, length, StandardCharsets.US_ASCII)

    private const val DEVICE_FIXTURE_DIRECTORY = "/data/local/tmp/"
    private val SAFE_DEVICE_PATH = Regex("/data/local/tmp/[A-Za-z0-9._/-]+")
    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val PCM_FORMAT_BYTES = 16
    private const val PCM_FORMAT = 1
    private const val REQUIRED_SAMPLE_RATE_HZ = 16_000
    private const val REQUIRED_BITS_PER_SAMPLE = 16
}

object PerformanceReportWriter {
    fun writeReport(
        context: Context,
        reportName: String,
        summary: JSONObject,
        samplesCsv: String,
    ): File {
        val root = context.getExternalFilesDir("performance-results") ?: File(context.filesDir, "performance-results")
        val directory = File(root, "${safeName(reportName)}-${System.currentTimeMillis()}")
        check(directory.mkdirs()) { "Cannot create performance output directory: $directory" }
        File(directory, "summary.json").writeText(summary.toString(2), StandardCharsets.UTF_8)
        File(directory, "samples.csv").writeText(samplesCsv, StandardCharsets.UTF_8)
        return directory
    }

    fun deviceEnvironment(context: Context): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val powerManager = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("thermalStatus", powerManager?.currentThermalStatus)
            .put("batteryLevel", battery?.getIntExtra("level", -1))
            .put("batteryScale", battery?.getIntExtra("scale", -1))
            .put("batteryTemperatureTenthsC", battery?.getIntExtra("temperature", -1))
            .put("batteryVoltageMv", battery?.getIntExtra("voltage", -1))
            .put("batteryStatus", battery?.getIntExtra("status", -1))
            .put("batteryPlugged", battery?.getIntExtra("plugged", -1))
            .put("powerSaveMode", powerManager?.isPowerSaveMode)
    }

    fun csvCell(value: Any?): String {
        val text = value?.toString().orEmpty()
        return if (text.any { it == ',' || it == '"' || it == '\n' }) {
            '"' + text.replace("\"", "\"\"") + '"'
        } else {
            text
        }
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "performance" }
}
