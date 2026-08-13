package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSynthesizedSpeechStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun aSavedPhraseSurvivesANewStoreInstanceWhichIsThePointOfTheDiskCache() {
        val directory = temporaryFolder.newFolder("speech")
        store(directory).saveV(speech("您好，这里是智能助理"))

        // A new instance stands in for the next process: the in-memory LRU would be empty here.
        val loaded = store(directory).loadV("您好，这里是智能助理")

        assertThat(loaded).isNotNull()
        assertThat(loaded?.text).isEqualTo("您好，这里是智能助理")
        assertThat(loaded?.pcm16?.toList()).isEqualTo(speech("您好，这里是智能助理").pcm16.toList())
        assertThat(loaded?.sampleRateHz).isEqualTo(16_000)
        assertThat(loaded?.isMock).isFalse()
    }

    @Test fun aChangedRuleFingerprintDiscardsEverySavedRecording() {
        // Otherwise the system would keep speaking a reply the operator has already edited.
        val directory = temporaryFolder.newFolder("speech")
        store(directory, fingerprint = "rules-v1").saveV(speech("旧的结束语"))

        val afterRulesEdit = store(directory, fingerprint = "rules-v2")

        assertThat(afterRulesEdit.loadV("旧的结束语")).isNull()
    }

    @Test fun theSameFingerprintKeepsRecordingsAcrossInstances() {
        val directory = temporaryFolder.newFolder("speech")
        store(directory, fingerprint = "rules-v1").saveV(speech("再见"))

        assertThat(store(directory, fingerprint = "rules-v1").loadV("再见")).isNotNull()
    }

    @Test fun aMissingPhraseReturnsNullInsteadOfFailing() {
        assertThat(store(temporaryFolder.newFolder("speech")).loadV("从未合成过")).isNull()
    }

    @Test fun aTruncatedEntryIsDroppedRatherThanPlayed() {
        val directory = temporaryFolder.newFolder("speech")
        val subject = store(directory)
        subject.saveV(speech("被截断的音频"))
        val entry = directory.listFiles()!!.first { it.name.endsWith(".pcm") }
        entry.writeBytes(entry.readBytes().copyOf(6))

        assertThat(subject.loadV("被截断的音频")).isNull()
        assertThat(entry.exists()).isFalse()
    }

    @Test fun anEntryWhoseStoredTextDiffersIsRejectedSoAHashCollisionCannotServeWrongAudio() {
        val directory = temporaryFolder.newFolder("speech")
        store(directory).saveV(speech("原始文本"))
        val entry = directory.listFiles()!!.first { it.name.endsWith(".pcm") }
        // Rename the recording of one phrase onto another phrase's key.
        val other = File(directory, store(directory).let { _ -> keyFileNameFor(directory, "另一段文本") })
        entry.copyTo(other, overwrite = true)

        assertThat(store(directory).loadV("另一段文本")).isNull()
    }

    @Test fun oversizedAudioIsNotStoredAndTheDirectoryStaysWithinBudget() {
        val directory = temporaryFolder.newFolder("speech")
        val subject = store(directory, maxBytes = 4_096)

        subject.saveV(speech("超大音频", samples = 8_000))

        assertThat(subject.loadV("超大音频")).isNull()
        assertThat(directory.listFiles()!!.sumOf { it.length() }).isAtMost(4_096L)
    }

    @Test fun evictionKeepsTheDirectoryUnderBudgetWhenManyPhrasesAreSaved() {
        val directory = temporaryFolder.newFolder("speech")
        val subject = store(directory, maxBytes = 3_000)

        repeat(10) { index ->
            subject.saveV(speech("话术$index", samples = 400))
            Thread.sleep(2) // distinct lastModified so eviction order is deterministic
        }

        val entryBytes = directory.listFiles()!!
            .filter { it.name.endsWith(".pcm") }
            .sumOf { it.length() }
        assertThat(entryBytes).isAtMost(3_000L)
        // The most recent phrase must still be present; eviction drops the oldest.
        assertThat(subject.loadV("话术9")).isNotNull()
    }

    @Test fun emptyAudioIsNeverStored() {
        val directory = temporaryFolder.newFolder("speech")
        val subject = store(directory)

        subject.saveV(speech("空音频", samples = 0))

        assertThat(subject.loadV("空音频")).isNull()
    }

    @Test fun aRecordingIsNeverReplayedAfterTheVoiceChanges() {
        // The app can import and switch TTS models. Without this the opening prompt would keep
        // playing in the old model's voice while the rest of the call used the new one.
        val directory = temporaryFolder.newFolder("speech")
        val subject = store(directory)
        subject.save(speech("您好"), voice = "aishell3|1.0|speaker-108")

        assertThat(subject.load("您好", voice = "aishell3|1.0|speaker-7")).isNull()
        assertThat(subject.load("您好", voice = "other-model|1.0|speaker-108")).isNull()
        assertThat(subject.load("您好", voice = "aishell3|1.0|speaker-108")).isNotNull()
    }

    private fun keyFileNameFor(directory: File, text: String): String {
        val probe = store(directory)
        probe.saveV(speech(text, samples = 1))
        val name = directory.listFiles()!!
            .first { it.name.endsWith(".pcm") && it.length() < 200 }
            .name
        File(directory, name).delete()
        return name
    }

    private fun store(
        directory: File,
        fingerprint: String = "rules-v1",
        maxBytes: Long = FileSynthesizedSpeechStore.DEFAULT_MAX_BYTES,
    ) = FileSynthesizedSpeechStore(directory, { fingerprint }, maxBytes)

    private fun speech(text: String, samples: Int = 160) = SynthesizedSpeech(
        text = text,
        audioPath = null,
        durationMillis = samples * 1_000L / 16_000,
        isMock = false,
        pcm16 = ShortArray(samples) { (it % 100).toShort() },
        sampleRateHz = 16_000,
    )

    /** Most cases are not about the voice, so they share one. */
    private fun SynthesizedSpeechStore.saveV(speech: SynthesizedSpeech) = save(speech, TEST_VOICE)

    private fun SynthesizedSpeechStore.loadV(text: String) = load(text, TEST_VOICE)

    private companion object {
        const val TEST_VOICE = "test-model|1.0|speaker-108"
    }
}
