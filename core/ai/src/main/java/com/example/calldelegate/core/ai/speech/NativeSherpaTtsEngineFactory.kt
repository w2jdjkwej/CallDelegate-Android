package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.model.ActiveModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

class NativeSherpaTtsEngineFactory(
) : SherpaTtsEngineFactory {
    override fun open(model: ActiveModel, threadCount: Int): SherpaTtsHandle {
        val root = File(model.directoryPath)
        val modelFile = checkFile(root, "model.onnx")
        val lexicon = checkFile(root, "lexicon.txt")
        val tokens = checkFile(root, "tokens.txt")
        val ruleFsts = listOf("phone.fst", "date.fst", "number.fst")
            .map { checkFile(root, it) }.joinToString(",")
        val vits = OfflineTtsVitsModelConfig(
            model = modelFile,
            lexicon = lexicon,
            tokens = tokens,
            dataDir = "",
            dictDir = "",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1f,
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = threadCount.coerceIn(1, 4),
            debug = false,
            provider = "cpu",
        )
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = ruleFsts,
            ruleFars = "",
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        return NativeSherpaTtsHandle(OfflineTts(assetManager = null, config = config))
    }

    private fun checkFile(root: File, name: String): String {
        val file = File(root, name)
        require(file.isFile) { "缺少 TTS 资源：$name" }
        return file.absolutePath
    }
}

private class NativeSherpaTtsHandle(private val tts: OfflineTts) : SherpaTtsHandle {
    override fun generate(text: String, speakerId: Int, speed: Float): GeneratedPcm {
        val audio = tts.generate(text, speakerId, speed)
        return GeneratedPcm(audio.samples, audio.sampleRate)
    }

    override fun close() = tts.release()
}
