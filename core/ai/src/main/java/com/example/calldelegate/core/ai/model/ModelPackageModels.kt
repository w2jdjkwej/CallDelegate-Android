package com.example.calldelegate.core.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelPackageManifest(
    val schemaVersion: Int,
    val type: String,
    val displayName: String,
    val version: String,
    val cpuArchitecture: String,
    val estimatedMemoryMb: Int,
    val files: List<ModelFileManifest>,
    val runtime: String = "unspecified",
    val license: String = "UNKNOWN",
    val sampleRateHz: Int = 16_000,
)

@Serializable
data class ModelFileManifest(
    val path: String,
    val sha256: String,
    val required: Boolean = true,
    val role: String = "MODEL",
)

@Serializable
data class ActiveModelPointer(
    val manifest: ModelPackageManifest,
    val directoryPath: String,
    val packageSizeBytes: Long,
)

data class ValidationIssue(val code: String, val message: String)

class ModelPackageValidator(
    private val maxPackageBytes: Long = 450L * 1024L * 1024L,
    private val maxEstimatedMemoryMb: Int = 1_200,
) {
    fun validate(
        manifest: ModelPackageManifest,
        packageSizeBytes: Long,
        archiveEntries: Map<String, Long>,
        otherActiveModelMemoryMb: Int = 0,
        deviceCombinedMemoryBudgetMb: Int = maxEstimatedMemoryMb,
    ): List<ValidationIssue> = buildList {
        if (manifest.schemaVersion != 1) add(issue("MANIFEST_VERSION", "仅支持模型清单版本 1"))
        if (manifest.type !in SUPPORTED_TYPES) add(issue("MODEL_TYPE", "不支持的模型类型：${manifest.type}"))
        if (!VERSION.matches(manifest.version)) add(issue("MODEL_VERSION", "模型版本必须使用语义化版本，例如 1.0.0"))
        if (manifest.cpuArchitecture != "arm64-v8a") add(issue("CPU_ARCH", "模型包必须声明 arm64-v8a"))
        if (packageSizeBytes <= 0 || packageSizeBytes > maxPackageBytes) add(issue("PACKAGE_SIZE", "模型包必须小于等于 450MB"))
        if (manifest.estimatedMemoryMb !in 1..maxEstimatedMemoryMb) add(issue("MEMORY_BUDGET", "预计内存必须在 1–1200MB 之间"))
        if (manifest.estimatedMemoryMb + otherActiveModelMemoryMb > deviceCombinedMemoryBudgetMb) {
            add(issue("TOTAL_MEMORY_BUDGET", "按当前设备档位计算，活动模型合计预计内存超过 ${deviceCombinedMemoryBudgetMb}MB"))
        }
        if (manifest.license.isBlank() || manifest.license.equals("UNKNOWN", true)) add(issue("LICENSE", "模型清单必须声明明确许可证"))
        val runtimeName = manifest.runtime.substringBefore(':').lowercase()
        if (runtimeName !in SUPPORTED_RUNTIMES) add(issue("RUNTIME", "不兼容的推理运行时：${manifest.runtime}"))
        if (manifest.sampleRateHz !in setOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)) add(issue("SAMPLE_RATE", "不支持的采样率：${manifest.sampleRateHz}"))
        if (manifest.files.isEmpty()) add(issue("FILES_EMPTY", "模型清单没有声明文件"))
        if (manifest.files.size > 2_048) add(issue("FILES_COUNT", "模型清单声明的文件超过 2048 个"))
        if (manifest.files.map { it.path }.distinct().size != manifest.files.size) add(issue("FILES_DUPLICATE", "模型清单包含重复文件"))

        manifest.files.forEach { file ->
            if (!isSafeRelativePath(file.path)) add(issue("UNSAFE_PATH", "文件路径不安全：${file.path}"))
            if (file.required && file.path !in archiveEntries) add(issue("REQUIRED_FILE", "缺少必需文件：${file.path}"))
            if (!SHA256.matches(file.sha256.lowercase())) add(issue("SHA256_FORMAT", "SHA-256 格式错误：${file.path}"))
            val fileName = file.path.substringAfterLast('/').lowercase()
            if (fileName !in EXTENSIONLESS_METADATA && file.path.substringAfterLast('.', "").lowercase() !in ALLOWED_EXTENSIONS) {
                add(issue("FILE_EXTENSION", "不允许的模型资源扩展名：${file.path}"))
            }
        }

        if (manifest.files.none { it.role.uppercase() == "MODEL" }) add(issue("MODEL_FILE", "至少需要一个 role=MODEL 的文件"))
        if (manifest.type == "ASR" && manifest.files.none { it.role.uppercase() in setOf("TOKENS", "VOCAB") }) {
            add(issue("ASR_VOCAB", "ASR 模型需要 TOKENS 或 VOCAB 资源"))
        }
        if (manifest.type == "TTS" && manifest.files.none { it.role.uppercase() in setOf("TOKENS", "LEXICON", "VOCAB") }) {
            add(issue("TTS_VOCAB", "TTS 模型需要 TOKENS、LEXICON 或 VOCAB 资源"))
        }
    }

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 240 || path.startsWith('/') || path.startsWith('\\')) return false
        val normalized = path.replace('\\', '/')
        return normalized.split('/').none { it == ".." || it.isBlank() }
    }

    private fun issue(code: String, message: String) = ValidationIssue(code, message)

    private companion object {
        val SUPPORTED_TYPES = setOf("VAD", "ASR", "INTENT", "ENTITY", "TTS")
        val SUPPORTED_RUNTIMES = setOf("vosk", "sherpa-onnx", "onnxruntime", "mock")
        val ALLOWED_EXTENSIONS = setOf(
            "onnx", "ort", "bin", "txt", "json", "tokens", "fst", "dat", "wav",
            "mdl", "conf", "arpa", "mat", "vec", "int", "dubm", "ie", "stats", "md",
        )
        val EXTENSIONLESS_METADATA = setOf("license", "notice", "readme")
        val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
        val SHA256 = Regex("^[a-fA-F0-9]{64}$")
    }
}
