package com.example.calldelegate.core.ai.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelPackageValidatorTest {
    private val validator = ModelPackageValidator()
    private val hash = "a".repeat(64)

    @Test fun validArm64AsrPackagePasses() {
        val manifest = ModelPackageManifest(
            1, "ASR", "Mandarin ASR", "1.0.0", "arm64-v8a", 320,
            listOf(
                ModelFileManifest("model.onnx", hash, role = "MODEL"),
                ModelFileManifest("tokens.txt", hash, role = "TOKENS"),
            ),
            runtime = "vosk",
            license = "Apache-2.0",
        )
        assertThat(validator.validate(manifest, 40_000_000, mapOf("model.onnx" to 30L, "tokens.txt" to 20L))).isEmpty()
    }

    @Test fun rejectsWrongAbiMissingVocabularyAndTraversal() {
        val manifest = ModelPackageManifest(
            1, "ASR", "Bad", "1.0.0", "armeabi-v7a", 1300,
            listOf(ModelFileManifest("../model.onnx", hash, role = "MODEL")),
            runtime = "vosk",
            license = "Apache-2.0",
        )
        val codes = validator.validate(manifest, 20, mapOf("../model.onnx" to 20L)).map { it.code }
        assertThat(codes).containsAtLeast("CPU_ARCH", "MEMORY_BUDGET", "UNSAFE_PATH", "ASR_VOCAB")
    }

    @Test fun rejectsCombinedActiveModelMemoryOverBudget() {
        val manifest = ModelPackageManifest(
            1, "VAD", "VAD", "1.0.0", "arm64-v8a", 300,
            listOf(ModelFileManifest("model.onnx", hash, role = "MODEL")),
            runtime = "onnxruntime",
            license = "MIT",
        )

        val codes = validator.validate(
            manifest,
            packageSizeBytes = 1_000,
            archiveEntries = mapOf("model.onnx" to 1_000L),
            otherActiveModelMemoryMb = 950,
        ).map { it.code }

        assertThat(codes).contains("TOTAL_MEMORY_BUDGET")
    }

    @Test fun appliesDeviceSpecificCombinedMemoryBudget() {
        val manifest = ModelPackageManifest(
            1, "TTS", "TTS", "1.0.0", "arm64-v8a", 650,
            listOf(
                ModelFileManifest("model.onnx", hash, role = "MODEL"),
                ModelFileManifest("tokens.txt", hash, role = "TOKENS"),
            ),
            runtime = "sherpa-onnx",
            license = "Apache-2.0",
        )

        val codes = validator.validate(
            manifest = manifest,
            packageSizeBytes = 1_000,
            archiveEntries = mapOf("model.onnx" to 900L, "tokens.txt" to 100L),
            otherActiveModelMemoryMb = 100,
            deviceCombinedMemoryBudgetMb = 700,
        ).map { it.code }

        assertThat(codes).contains("TOTAL_MEMORY_BUDGET")
    }

    @Test fun rejectsBadVersionRuntimeLicenseSampleRateAndExtension() {
        val manifest = ModelPackageManifest(
            1, "VAD", "Bad metadata", "version-one", "arm64-v8a", 20,
            listOf(ModelFileManifest("model.exe", hash, role = "MODEL")),
            runtime = "unknown-runtime",
            license = "UNKNOWN",
            sampleRateHz = 12_345,
        )

        val codes = validator.validate(manifest, 100, mapOf("model.exe" to 100L)).map { it.code }

        assertThat(codes).containsAtLeast(
            "MODEL_VERSION", "RUNTIME", "LICENSE", "SAMPLE_RATE", "FILE_EXTENSION",
        )
    }
}
