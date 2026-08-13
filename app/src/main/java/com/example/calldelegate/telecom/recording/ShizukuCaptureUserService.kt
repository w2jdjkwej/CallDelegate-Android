package com.example.calldelegate.telecom.recording

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's shell-identity user-service process.
 *
 * The service starts the bundled scrcpy server with ProcessBuilder, relays its abstract-socket
 * stream into a Binder-transferable pipe, and owns every privileged resource until stopCapture().
 */
@Keep
class ShizukuCaptureUserService : IShizukuCaptureService.Stub {
    private var serviceContext: Context? = null

    constructor() : super()

    /**
     * Shizuku v13+ supplies this context. The user-service is not a normal application process,
     * but AudioManager system-service access is sufficient for the call interception API.
     */
    constructor(context: Context) : super() {
        serviceContext = context
    }

    private val active = AtomicBoolean(false)
    private var process: Process? = null
    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var pipeWriteEnd: ParcelFileDescriptor? = null
    private var workers: ExecutorService? = null
    private var uplinkTrack: AudioTrack? = null
    private var uplinkSampleRateHz = 0
    private var uplinkWrittenFrames = 0L

    @Synchronized
    override fun startCapture(
        serverPath: String,
        socketId: String,
        audioSource: String,
        audioBitRate: Int,
    ): ParcelFileDescriptor? {
        if (!active.compareAndSet(false, true)) return null

        var pipeReadEnd: ParcelFileDescriptor? = null
        return try {
            val serverFile = File(serverPath)
            require(ScrcpyServerSpec.verify(serverFile)) {
                "scrcpy server is missing or failed SHA-256 verification"
            }
            val command = ScrcpyServerSpec.buildServerCommand(
                socketId = socketId,
                audioSource = audioSource,
                audioBitRate = audioBitRate,
            )

            val pipe = ParcelFileDescriptor.createPipe()
            pipeReadEnd = pipe[0]
            pipeWriteEnd = pipe[1]

            val fullSocketName = ScrcpyServerSpec.SOCKET_PREFIX + socketId
            serverSocket = LocalServerSocket(fullSocketName)
            workers = Executors.newFixedThreadPool(3) { runnable ->
                Thread(runnable, "call-capture-shell").apply { isDaemon = true }
            }
            startSocketRelay()

            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment()["CLASSPATH"] = serverFile.absolutePath }
                .start()
            startLogDrain(requireNotNull(process))
            startProcessMonitor(requireNotNull(process))
            pipeReadEnd
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unable to start privileged capture", throwable)
            runCatching { pipeReadEnd?.close() }
            cleanup(waitForProcess = false)
            null
        }
    }

    @Synchronized
    override fun stopCapture() {
        cleanup(waitForProcess = true)
    }

    /**
     * Opens Android's system call-uplink injection track. This is a hidden SystemApi guarded by
     * CALL_AUDIO_INTERCEPTION. Android 16 grants that permission to shell, but the vendor can still
     * report that PSTN interception is unsupported; the returned text preserves that distinction.
     */
    @Synchronized
    override fun startUplinkInjection(sampleRateHz: Int): String {
        stopUplinkInjection()
        if (sampleRateHz !in MIN_UPLINK_SAMPLE_RATE_HZ..MAX_UPLINK_SAMPLE_RATE_HZ) {
            return "UNSUPPORTED_SAMPLE_RATE:$sampleRateHz"
        }
        return try {
            val context = requireNotNull(serviceContext) {
                "Shizuku did not provide a user-service Context"
            }
            val shellContext = ShellAudioContext(context)
            Log.i(
                TAG,
                "Call-uplink context: package=${shellContext.attributionSource.packageName} " +
                    "uid=${shellContext.attributionSource.uid}",
            )
            val audioManager = requireNotNull(
                context.getSystemService(AudioManager::class.java),
            ) {
                "AudioManager is unavailable in the Shizuku user-service"
            }
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val track = createCallUplinkTrack(shellContext, audioManager, format)
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "Call-uplink AudioTrack failed to initialize"
            }
            track.play()
            uplinkTrack = track
            uplinkSampleRateHz = sampleRateHz
            uplinkWrittenFrames = 0L
            // No lead-in. It was added on the theory that the start of the uplink path swallowed
            // the first syllable of the greeting; the greeting turned out to be losing its last
            // syllable to the phone channel instead, a wording problem, and the padding never had
            // anything to do with it. What it did have was a cost: 200 ms added to every reply, on
            // the one number the caller actually feels. The tail padding stays -- it costs nothing,
            // because nobody is waiting on the end of a sentence.
            Log.i(
                TAG,
                "Call-uplink injection started: sampleRateHz=$sampleRateHz " +
                    "sessionId=${track.audioSessionId} playState=${track.playState} " +
                    "bufferFrames=${track.bufferSizeInFrames} " +
                    "preferredType=${track.preferredDevice?.type} " +
                    "routedType=${track.routedDevice?.type} (18=telephony)",
            )
            ""
        } catch (throwable: Throwable) {
            releaseUplinkTrack()
            val cause = (throwable as? InvocationTargetException)?.targetException ?: throwable
            Log.e(TAG, "Call-uplink injection start failed", cause)
            "${cause.javaClass.simpleName}:${cause.message.orEmpty()}"
        }
    }

    /**
     * Builds a track that plays into the call's uplink.
     *
     * Two routes exist and only one of them works here.
     *
     * The first is the Android 13 call-redirection API -- USAGE_CALL_ASSISTANT with
     * CALL_REDIRECT_PSTN. It is the documented one and this service used it. On the validation device it is
     * accepted and then ignored: the flag arrives set (0x10800 carries AUDIO_FLAG_CALL_REDIRECTION)
     * and the policy engine still hands the track to the earpiece, because it lists no registered
     * call assistant and a shell uid cannot become one.
     *
     * The second is what AOSP's own CallRecordingTonePlayer does: ask for the telephony output
     * device by preference. The device exposes the whole path for it -- the policy dump lists
     * `in_call_music` (AUDIO_OUTPUT_FLAG_INCALL_MUSIC) as a source of the `telephony_tx` sink, at
     * 8/16/32/48 kHz PCM16 -- and the permission that gates it, MODIFY_PHONE_STATE, is held by
     * shell, which is the uid this service runs as. The app itself holds neither, which is why this
     * has to happen here rather than in the app process.
     *
     * setPreferredDevice returning true means the request was recorded, not that it was honoured;
     * only routedDevice after play() says where the audio actually went. Type 18 is telephony.
     */
    private fun createCallUplinkTrack(
        shellContext: Context,
        audioManager: AudioManager,
        format: AudioFormat,
    ): AudioTrack {
        check(audioManager.mode == AudioManager.MODE_IN_CALL) {
            "Call-uplink injection requires MODE_IN_CALL, actual=${audioManager.mode}"
        }
        val telephony = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        checkNotNull(telephony) { "This device exposes no TYPE_TELEPHONY output" }

        val minimumBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MINIMUM_UPLINK_BUFFER_BYTES)

        val track = AudioTrack.Builder()
            .setContext(shellContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimumBuffer * UPLINK_BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val preferred = track.setPreferredDevice(telephony)
        Log.i(TAG, "Call-uplink preferred device set=$preferred type=${telephony.type}")
        return track
    }

    @Synchronized
    override fun writeUplinkInjection(pcm16LittleEndian: ByteArray?): Int {
        val track = uplinkTrack ?: return ERROR_UPLINK_NOT_STARTED
        val data = pcm16LittleEndian ?: return ERROR_UPLINK_INVALID_DATA
        if (data.isEmpty() || data.size % PCM16_BYTES_PER_SAMPLE != 0) {
            return ERROR_UPLINK_INVALID_DATA
        }
        val written = runCatching {
            track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
        }.getOrElse { error ->
            Log.e(TAG, "Call-uplink AudioTrack write failed", error)
            return ERROR_UPLINK_WRITE
        }
        if (written > 0) uplinkWrittenFrames += written / PCM16_BYTES_PER_SAMPLE
        return written
    }

    @Synchronized
    override fun stopUplinkInjection(): String {
        val track = uplinkTrack ?: return ""
        return try {
            writeUplinkSilence(track, uplinkSampleRateHz, UPLINK_TAIL_PAD_MILLIS)
            waitForUplinkTail(track)
            val playedFrames = track.playbackHeadPosition.toLong() and UINT32_MASK
            val routedType = track.routedDevice?.type
            val underrunCount = track.underrunCount
            val writtenFrames = uplinkWrittenFrames
            runCatching { track.stop() }
            Log.i(
                TAG,
                "Call-uplink injection stopped: writtenFrames=$writtenFrames " +
                    "playedFrames=$playedFrames underruns=$underrunCount routedType=$routedType",
            )
            ""
        } catch (throwable: Throwable) {
            "${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
        } finally {
            releaseUplinkTrack()
        }
    }

    override fun destroy() {
        stopUplinkInjection()
        stopCapture()
        exitProcess(0)
    }

    private fun startSocketRelay() {
        val executor = requireNotNull(workers)
        executor.execute {
            try {
                val connected = requireNotNull(serverSocket).accept()
                clientSocket = connected
                val destination = ParcelFileDescriptor.AutoCloseOutputStream(pipeWriteEnd)
                connected.inputStream.use { source ->
                    destination.use { output ->
                        val buffer = ByteArray(RELAY_BUFFER_BYTES)
                        while (active.get()) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                }
            } catch (throwable: Throwable) {
                if (active.get()) Log.e(TAG, "scrcpy socket relay failed", throwable)
            } finally {
                runCatching { pipeWriteEnd?.close() }
            }
        }
    }

    private fun startLogDrain(activeProcess: Process) {
        requireNotNull(workers).execute {
            runCatching {
                activeProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.i(TAG, "scrcpy: $line") }
                }
            }
        }
    }

    private fun startProcessMonitor(activeProcess: Process) {
        requireNotNull(workers).execute {
            val exitCode = runCatching { activeProcess.waitFor() }.getOrNull()
            if (active.get()) {
                Log.w(TAG, "scrcpy server exited while capture was active: $exitCode")
                active.set(false)
                runCatching { clientSocket?.close() }
                runCatching { serverSocket?.close() }
                runCatching { pipeWriteEnd?.close() }
            }
        }
    }

    private fun cleanup(waitForProcess: Boolean) {
        if (!active.getAndSet(false) && process == null && workers == null) return

        val activeProcess = process
        runCatching { activeProcess?.destroy() }
        if (waitForProcess) {
            val exited = runCatching {
                activeProcess?.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: true
            }.getOrDefault(true)
            if (!exited) runCatching { activeProcess?.destroyForcibly() }
        }

        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        runCatching { pipeWriteEnd?.close() }
        workers?.shutdownNow()

        process = null
        clientSocket = null
        serverSocket = null
        pipeWriteEnd = null
        workers = null
    }

    /**
     * Plays silence into the call before the first real sample of a reply.
     *
     * A track that has just started is not yet carrying audio to the far end. The route is chosen
     * at play() but the mixer, the HAL and the modem each take their own moment to begin, and
     * whatever is written into that moment is lost -- every injection in the 2026-08-09 calls
     * reported exactly one underrun, at the start. For most replies that costs an inaudible
     * fraction of the first syllable. For the greeting it is the whole point of the call: the
     * caller has just been answered, hears the first part of 您好，请问您有什么事情 missing, and has
     * no context to reconstruct it from.
     *
     * Silence here is discarded instead. It costs the caller a fifth of a second of quiet they
     * were already going to spend waiting for the answer to connect.
     */
    private fun writeUplinkSilence(track: AudioTrack, sampleRateHz: Int, millis: Long) {
        if (sampleRateHz <= 0) return
        val frames = (sampleRateHz * millis / MILLIS_PER_SECOND).toInt()
        if (frames <= 0) return
        val silence = ByteArray(frames * PCM16_BYTES_PER_SAMPLE)
        var offset = 0
        while (offset < silence.size) {
            val written = track.write(silence, offset, silence.size - offset)
            if (written <= 0) break
            offset += written
        }
        // Counted like any other audio so the tail wait measures the whole queue, not just speech.
        uplinkWrittenFrames += offset / PCM16_BYTES_PER_SAMPLE
    }

    private fun waitForUplinkTail(track: AudioTrack) {
        val sampleRate = uplinkSampleRateHz
        if (sampleRate <= 0 || uplinkWrittenFrames <= 0L) return
        // Wait for what is still queued, not for the whole utterance again. write() is blocking, so
        // by the time it returns most of the audio has already played and only the buffer's worth
        // remains; budgeting the full duration made every reply hold the call open for its own
        // length a second time, which left the line silent and unhangupable. The old flat two
        // seconds was the opposite error -- it cut 为保护您的隐私和资金安全…也不能协助共享屏幕, some
        // nine seconds of speech, off after two.
        val queuedFrames = (uplinkWrittenFrames - (track.playbackHeadPosition.toLong() and UINT32_MASK))
            .coerceAtLeast(0L)
        val queuedMillis = queuedFrames * MILLIS_PER_SECOND / sampleRate
        val budgetMillis = (queuedMillis + UPLINK_DRAIN_GRACE_MILLIS)
            .coerceAtMost(UPLINK_DRAIN_CEILING_MILLIS)
        val deadline = System.nanoTime() + budgetMillis * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            val playedFrames = track.playbackHeadPosition.toLong() and UINT32_MASK
            if (playedFrames >= uplinkWrittenFrames) {
                awaitUplinkFlush()
                return
            }
            Thread.sleep(UPLINK_DRAIN_POLL_MILLIS)
        }
        // The head position never caught up inside the budget. Whatever is still queued is going to
        // be cut either way, but the same flush applies: it is the downstream pipe, not the queue.
        awaitUplinkFlush()
    }

    /**
     * Holds the track open past the point the head position says everything played.
     *
     * playbackHeadPosition counts frames the track has handed downstream, not frames the far end
     * has heard, and it also counts silence the track inserted for itself during an underrun. Both
     * make it run ahead: across seven injections on 2026-08-09 the track was released 29 to 96 ms
     * before the audio it carried could have finished playing in real time, every one of them with
     * an underrun. writtenFrames equalled playedFrames each time, which is why the counters looked
     * healthy while the caller lost the end of every reply.
     *
     * There is no API for "the modem has finished with this"; a fixed margin covering the mixer,
     * the HAL and the encoder is the available answer.
     */
    private fun awaitUplinkFlush() {
        Thread.sleep(UPLINK_FLUSH_MARGIN_MILLIS)
    }

    private fun releaseUplinkTrack() {
        val track = uplinkTrack
        uplinkTrack = null
        uplinkSampleRateHz = 0
        uplinkWrittenFrames = 0L
        if (track != null) {
            runCatching { track.release() }
        }
    }

    private companion object {
        const val TAG = "CallCaptureShell"
        const val SHELL_PACKAGE_NAME = "com.android.shell"
        const val RELAY_BUFFER_BYTES = 64 * 1024
        const val PROCESS_STOP_TIMEOUT_SECONDS = 2L
        const val MINIMUM_UPLINK_BUFFER_BYTES = 4096
        const val UPLINK_BUFFER_MULTIPLIER = 4
        const val MIN_UPLINK_SAMPLE_RATE_HZ = 8_000
        const val MAX_UPLINK_SAMPLE_RATE_HZ = 48_000
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val ERROR_UPLINK_NOT_STARTED = -1
        const val ERROR_UPLINK_INVALID_DATA = -2
        const val ERROR_UPLINK_WRITE = -3
        /** Head-room over the audio's own duration, for device latency. */
        /**
         * Silence played after the last real sample, so a lost tail costs nothing.
         *
         * Holding the track open longer was not enough on its own: the caller still lost the
         * end of 您好，请问您有什么事情 with the track demonstrably open 216 ms past the point the
         * head position said everything had played. Waiting only helps if what the pipe drops
         * is the last stretch of *time*. If what it drops is the last stretch of the *stream* --
         * a partly filled buffer the encoder never sends, a reconfiguration part way through --
         * then no amount of waiting recovers it, and the only thing that helps is having
         * something expendable at the end. Silence is expendable.
         */
        const val UPLINK_TAIL_PAD_MILLIS = 400L

        /**
         * Held open after the head position says everything played, for what is still in flight.
         *
         * The measured shortfall was 29 to 96 ms. 250 ms covers it with margin for a busier device
         * and still ends the reply well inside the pause a caller leaves before answering.
         */
        const val UPLINK_FLUSH_MARGIN_MILLIS = 250L
        const val UPLINK_DRAIN_GRACE_MILLIS = 1_500L
        /** However far behind the head position claims to be, never hold the call this long. */
        const val UPLINK_DRAIN_CEILING_MILLIS = 4_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val UPLINK_DRAIN_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UINT32_MASK = 0xffff_ffffL
    }

    private class ShellAudioContext(base: Context) : ContextWrapper(base) {
        private val source = AttributionSource.Builder(AndroidProcess.myUid())
            .setPid(AndroidProcess.myPid())
            .setPackageName(SHELL_PACKAGE_NAME)
            .build()

        override fun getAttributionSource(): AttributionSource = source

        override fun getOpPackageName(): String = SHELL_PACKAGE_NAME

        override fun getPackageName(): String = SHELL_PACKAGE_NAME
    }
}
