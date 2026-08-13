package com.example.calldelegate.core.ai.model

import com.google.common.truth.Truth.assertThat
import com.example.calldelegate.domain.model.ModelType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ActiveModelResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun resolve_returnsValidatedCanonicalDirectory() {
        val root = temporaryFolder.newFolder("models")
        val directory = File(root, "custom/asr/1.0.0").apply { mkdirs() }
        val model = File(directory, "model.mdl").apply { writeText("model") }
        val tokens = File(directory, "words.txt").apply { writeText("tokens") }
        writePointer(root, directory, model, tokens)

        val resolved = ActiveModelResolver(root, json).resolve(ModelType.ASR)

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.directoryPath).isEqualTo(directory.canonicalPath)
        assertThat(resolved.runtime).isEqualTo("vosk")
        assertThat(resolved.files["MODEL"]).isEqualTo(model.canonicalPath)
        assertThat(resolved.files["VOCAB"]).isEqualTo(tokens.canonicalPath)
    }

    @Test
    fun resolve_returnsNullWhenRequiredFileWasDeleted() {
        val root = temporaryFolder.newFolder("models")
        val directory = File(root, "custom/asr/1.0.0").apply { mkdirs() }
        val model = File(directory, "model.mdl").apply { writeText("model") }
        val tokens = File(directory, "words.txt").apply { writeText("tokens") }
        writePointer(root, directory, model, tokens)
        tokens.delete()

        assertThat(ActiveModelResolver(root, json).resolve(ModelType.ASR)).isNull()
    }

    private fun writePointer(root: File, directory: File, model: File, tokens: File) {
        val manifest = ModelPackageManifest(
            schemaVersion = 1,
            type = "ASR",
            displayName = "Test Vosk",
            version = "1.0.0",
            cpuArchitecture = "arm64-v8a",
            estimatedMemoryMb = 320,
            runtime = "vosk",
            license = "Apache-2.0",
            sampleRateHz = 16_000,
            files = listOf(
                ModelFileManifest("model.mdl", sha256(model), role = "MODEL"),
                ModelFileManifest("words.txt", sha256(tokens), role = "VOCAB"),
            ),
        )
        val size = model.length() + tokens.length()
        val pointer = ActiveModelPointer(manifest, directory.absolutePath, size)
        File(root, "active_asr.json").writeText(json.encodeToString(pointer))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
