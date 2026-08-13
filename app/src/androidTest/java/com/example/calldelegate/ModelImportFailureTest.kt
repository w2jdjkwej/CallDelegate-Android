package com.example.calldelegate

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.core.ai.model.AndroidModelManager
import com.example.calldelegate.core.ai.model.ModelPackageManifest
import com.example.calldelegate.core.ai.model.ModelFileManifest
import com.example.calldelegate.domain.model.ModelType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ModelImportFailureTest {
    @Test
    fun checksumFailureKeepsBuiltInModelActive() {
        runBlocking {
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            val testRoot = File(appContext.cacheDir, "model-import-test-${System.nanoTime()}")
            val context = object : ContextWrapper(appContext) {
                override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
                override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
            }
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val archive = File(context.cacheDir, "bad-model.zip")

            try {
                val manifest = ModelPackageManifest(
                    schemaVersion = 1,
                    type = "VAD",
                    displayName = "Corrupt VAD",
                    version = "1.0.0",
                    cpuArchitecture = "arm64-v8a",
                    estimatedMemoryMb = 20,
                    files = listOf(ModelFileManifest("model.onnx", "0".repeat(64), role = "MODEL")),
                    runtime = "onnxruntime",
                    license = "MIT",
                )
                ZipOutputStream(FileOutputStream(archive)).use { zip ->
                    zip.putNextEntry(ZipEntry("model_manifest.json"))
                    zip.write(json.encodeToString(ModelPackageManifest.serializer(), manifest).toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("model.onnx"))
                    zip.write(byteArrayOf(1, 2, 3, 4))
                    zip.closeEntry()
                }

                val manager = AndroidModelManager(context, json)
                assertThat(manager.restoreBuiltIn(ModelType.VAD.name).success).isTrue()

                val result = manager.importFromUri(Uri.fromFile(archive).toString())

                assertThat(result.success).isFalse()
                assertThat(result.errorCode).isEqualTo("CHECKSUM")
                assertThat(manager.installedModels.value.first { it.type == ModelType.VAD }.isBuiltIn).isTrue()
            } finally {
                testRoot.deleteRecursively()
            }
        }
    }
}
