package com.example.calldelegate.core.ai.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.vosk.Model
import org.vosk.Recognizer

class NativeVoskEngineFactory : VoskEngineFactory {
    override fun openModel(directoryPath: String): VoskModelHandle = NativeVoskModelHandle(Model(directoryPath))
}

private class NativeVoskModelHandle(private val model: Model) : VoskModelHandle {
    private val modelLock = Any()
    private var modelClosed = false

    private inline fun <T> whileOpen(block: () -> T): T = synchronized(modelLock) {
        if (modelClosed) error("Vosk model already closed; refusing to build a recognizer")
        block()
    }

    override fun newRecognizer(sampleRateHz: Int): VoskRecognizerHandle =
        whileOpen { NativeVoskRecognizerHandle(Recognizer(model, sampleRateHz.toFloat())) }

    override fun newRecognizer(sampleRateHz: Int, phrases: List<String>): VoskRecognizerHandle =
        whileOpen {
            NativeVoskRecognizerHandle(Recognizer(model, sampleRateHz.toFloat(), Json.encodeToString(phrases)))
        }

    override fun newRecognizer(
        sampleRateHz: Int,
        phrases: List<String>,
        options: VoskRecognizerOptions,
    ): VoskRecognizerHandle = whileOpen {
        val recognizer = if (phrases.isEmpty()) {
            Recognizer(model, sampleRateHz.toFloat())
        } else {
            Recognizer(model, sampleRateHz.toFloat(), Json.encodeToString(phrases))
        }
        // Configured before any audio is accepted: Vosk applies both settings when it builds the
        // result for a segment, so changing them mid-stream would make earlier and later segments of
        // one turn report different shapes.
        if (options.maxAlternatives > 0) recognizer.setMaxAlternatives(options.maxAlternatives)
        if (options.words) recognizer.setWords(true)
        // Stated rather than inherited. The endpoint code now asks this recognizer whether the
        // utterance ended, and the answer depends entirely on how long it waits for trailing
        // silence -- a value that was, until here, whatever the library happened to default to.
        // A prior write-up recorded that the Java API exposes no way to set this; it does, on
        // vosk-android 0.3.75, as setEndpointerDelays(float, float, float).
        options.endpointerDelays?.let { delays ->
            recognizer.setEndpointerDelays(
                delays.startMaxSeconds,
                delays.endSeconds,
                delays.maxSeconds,
            )
        }
        NativeVoskRecognizerHandle(recognizer)
    }

    /**
     * Freeing the model out from under a live recognizer is the same crash one level up, so a
     * closed model refuses to build more of them and will not free itself twice.
     */
    override fun close() = synchronized(modelLock) {
        if (!modelClosed) {
            modelClosed = true
            model.close()
        }
    }
}

/**
 * Serialises every call into the native recognizer and refuses them once it has been freed.
 *
 * The wrapper used to forward straight through, which left the C++ object's lifetime guarded only
 * by the Kotlin bookkeeping in [VoskSpeechRecognizer]. That bookkeeping is careful about the
 * teardown paths it knows about, and on 2026-08-08 the app was moved to the background mid-session
 * and died on one it did not: SIGSEGV, SEGV_MAPERR, fault address 0x10, on the vosk-inference
 * thread, inside Kaldi's ProcessNonemitting, reached from acceptWaveForm. A null dereference that
 * far inside the decoder means the Recognizer had already been closed while a frame was still being
 * fed to it. The same day's log carries two `pthread_mutex_lock called on a destroyed mutex` aborts,
 * which is the same race arriving through a different door.
 *
 * No arrangement of flags one level up can make that safe, because the check and the native call
 * have to be atomic with respect to close(). Here they are: after [close] the object is never
 * touched again, and a late frame raises an exception the streaming worker already knows how to
 * turn into an ASR failure. An answered call that reports a recognition error is recoverable; a
 * SIGSEGV during one is not.
 */
private class NativeVoskRecognizerHandle(private val recognizer: Recognizer) : VoskRecognizerHandle {
    private val lock = Any()
    private var closed = false

    private inline fun <T> whileOpen(operation: String, block: () -> T): T = synchronized(lock) {
        if (closed) error("Vosk recognizer already closed; refusing $operation")
        block()
    }

    override fun accept(samples: ShortArray): Boolean =
        whileOpen("acceptWaveForm") { recognizer.acceptWaveForm(samples, samples.size) }

    override fun result(): VoskRecognition =
        whileOpen("result") { parseVoskRecognition(recognizer.result) }

    override fun partialText(): String =
        whileOpen("partialResult") { parseVoskPartialText(recognizer.partialResult) }

    override fun finalResult(): VoskRecognition =
        whileOpen("finalResult") { parseVoskRecognition(recognizer.finalResult) }

    /** Idempotent: a second teardown must not free the native object twice. */
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            recognizer.close()
        }
    }
}
