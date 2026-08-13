package com.example.calldelegate.testing.wav

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.di.DebugTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Speaks a list of sentences with the bundled VITS voice and writes them as 16 kHz WAV.
 *
 * The caller half of every test so far has been either a recorded corpus or a person reading
 * aloud, and a person reading aloud brings an accent, a pace and a microphone with them. When a
 * real call transcribed 这套二手房的挂牌价下调了 as 说出发坐堂转速行的话牌子下下调了 there was no
 * way to tell how much of that was the speaker and how much was the 8 kHz channel. Synthesised
 * speech removes the speaker as a variable and repeats exactly, which is what makes an A/B
 * possible at all.
 *
 * Not a test -- an asset generator, skipped unless a script file is passed:
 *
 *   adb push scripts.txt /data/local/tmp/tts-scripts.txt
 *   adb shell am instrument -w -r \
 *     -e class com.example.calldelegate.testing.wav.ScriptCorpusSynthesisTest \
 *     -e scriptFile /data/local/tmp/tts-scripts.txt \
 *     -e outputDir /sdcard/Android/data/com.example.calldelegate.debug/files/tts-corpus \
 *     com.example.calldelegate.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Each line of the script file is `caseId<TAB>text`. Blank lines and `#` comments are skipped.
 */
class ScriptCorpusSynthesisTest {

    @Test
    fun synthesisesScriptFileIntoWavCorpus() {
        val arguments = InstrumentationRegistry.getArguments()
        val scriptPath = arguments.getString("scriptFile")
        assumeTrue("未提供 scriptFile，跳过语料合成。", scriptPath != null)

        val scriptFile = File(requireNotNull(scriptPath))
        assumeTrue("脚本文件不存在：$scriptPath", scriptFile.isFile)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = File(
            arguments.getString("outputDir") ?: File(context.getExternalFilesDir(null), "tts-corpus").path,
        )
        outputDir.mkdirs()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugTestEntryPoint::class.java,
        )
        // Go through the runtime manager rather than the synthesizer directly: the switching
        // synthesizer starts on the mock, whose synthesize() is a sine beep of 280 + 7*length ms.
        // Taking it as given produced 59 files whose duration tracked that formula and not the
        // speech -- 31 characters in 0.50 s -- and they would have recognised as nothing and been
        // read as channel damage.
        val runtime = entryPoint.speechRuntimeManager()
        runBlocking {
            runtime.configure(mockMode = false)
            check(!runtime.isMock) { "真实 TTS 未能载入，仍在 mock 模式" }
        }
        val synthesizer = runtime

        val lines = scriptFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        var written = 0
        runBlocking {
            lines.forEach { line ->
                val parts = line.split('\t', limit = 2)
                check(parts.size == 2) { "脚本行缺少制表符分隔的 caseId：$line" }
                val (caseId, text) = parts
                when (val spoken = synthesizer.synthesize(text, "corpus-$caseId")) {
                    is AppResult.Success -> {
                        val speech = spoken.value
                        check(!speech.isMock) { "$caseId 由 mock 合成，不是语音" }
                        // Speech runs somewhere near a tenth of a second per Chinese character.
                        // The mock's beep is 280 + 7*length ms, which for anything long is far
                        // under this floor; catching it here means a beep corpus can never be
                        // handed on as if it were speech.
                        val secondsPerCharacter =
                            speech.pcm16.size.toDouble() / speech.sampleRateHz / text.length
                        check(secondsPerCharacter > 0.05) {
                            "$caseId 每字仅 %.3f 秒，疑似哔声而非语音：%s".format(secondsPerCharacter, text)
                        }
                        val target = File(outputDir, "$caseId.wav")
                        writeWav(target, speech.pcm16, speech.sampleRateHz)
                        written++
                        println("SYNTH\t$caseId\t${speech.sampleRateHz}\t${speech.pcm16.size}\t$text")
                    }
                    is AppResult.Failure -> println("SYNTH_FAILED\t$caseId\t${spoken.error}\t$text")
                }
            }
        }
        println("SYNTH_DONE\t$written/${lines.size}\t${outputDir.path}\tdevice=${Build.MODEL}")
    }

    /** Minimal mono PCM16 WAV writer; the corpus tools all expect a plain RIFF header. */
    private fun writeWav(target: File, samples: ShortArray, sampleRateHz: Int) {
        val dataBytes = samples.size * 2
        target.outputStream().buffered().use { out ->
            fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
            fun int32(value: Int) = out.write(
                byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 24) and 0xFF).toByte(),
                ),
            )
            fun int16(value: Int) = out.write(
                byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()),
            )

            ascii("RIFF"); int32(36 + dataBytes); ascii("WAVE")
            ascii("fmt "); int32(16); int16(1); int16(1)
            int32(sampleRateHz); int32(sampleRateHz * 2); int16(2); int16(16)
            ascii("data"); int32(dataBytes)
            samples.forEach { int16(it.toInt()) }
        }
    }
}
