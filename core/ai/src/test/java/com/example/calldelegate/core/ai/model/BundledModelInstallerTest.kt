package com.example.calldelegate.core.ai.model

import com.example.calldelegate.domain.model.ModelType
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class BundledModelInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun install_copiesAssetsAndCreatesResolvablePointer() {
        val model = "model".toByteArray()
        val words = "words".toByteArray()
        val manifest = """
            {"schemaVersion":1,"type":"ASR","displayName":"Bundled","version":"1.0.0",
             "cpuArchitecture":"arm64-v8a","estimatedMemoryMb":300,"runtime":"vosk",
             "license":"Apache-2.0","sampleRateHz":16000,"files":[
             {"path":"am/final.mdl","sha256":"${sha256(model)}","role":"MODEL"},
             {"path":"graph/words.txt","sha256":"${sha256(words)}","role":"VOCAB"}]}
        """.trimIndent().toByteArray()
        val source = FakeModelAssetSource(mapOf(
            "bundle/model_manifest.json" to manifest,
            "bundle/am/final.mdl" to model,
            "bundle/graph/words.txt" to words,
        ))
        val root = temporaryFolder.newFolder("models")

        val installed = BundledModelInstaller(root, Json { ignoreUnknownKeys = true }, source)
            .install(ModelType.ASR, "bundle")

        assertThat(installed).isTrue()
        assertThat(ActiveModelResolver(root, Json).resolve(ModelType.ASR)?.displayName).isEqualTo("Bundled")
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

private class FakeModelAssetSource(private val files: Map<String, ByteArray>) : ModelAssetSource {
    override fun list(path: String): List<String> {
        val prefix = "$path/"
        return files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
    }

    override fun open(path: String) = ByteArrayInputStream(checkNotNull(files[path]))
}
