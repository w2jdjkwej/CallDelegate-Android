package com.example.calldelegate.core.ai.model

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.example.calldelegate.domain.api.ModelManager
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.model.InstalledModel
import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.ModelImportResult
import com.example.calldelegate.domain.model.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

class AndroidModelManager(
    private val context: Context,
    private val json: Json,
    private val validator: ModelPackageValidator = ModelPackageValidator(),
    private val profiles: DeviceProfileProvider? = null,
) : ModelManager {
    private val root = File(context.filesDir, "models")
    private val mutableModels = MutableStateFlow<List<InstalledModel>>(emptyList())
    override val installedModels: StateFlow<List<InstalledModel>> = mutableModels.asStateFlow()

    override suspend fun importFromUri(uri: String): ModelImportResult = withContext(Dispatchers.IO) {
        root.mkdirs()
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext failure("URI_INVALID", "无法读取所选文件地址")
        val metadata = runCatching { queryMetadata(context.contentResolver, parsed) }
            .getOrDefault(FileMetadata(parsed.lastPathSegment ?: "model.zip", null))
        if (!metadata.name.lowercase().endsWith(".zip")) return@withContext failure("EXTENSION", "模型包必须是 .zip 文件")
        if (metadata.size != null && metadata.size > MAX_PACKAGE_BYTES) return@withContext failure("PACKAGE_SIZE", "模型包超过 450MB")

        val packageFile = File(context.cacheDir, "model-import-${UUID.randomUUID()}.zip")
        var stagingDirectory: File? = null
        try {
            val copied = copyUriWithLimit(context.contentResolver, parsed, packageFile, MAX_PACKAGE_BYTES)
                ?: return@withContext failure("READ_FAILED", "模型包读取失败或超过 450MB")
            ZipFile(packageFile).use { zip ->
                val archiveEntries = linkedMapOf<String, Long>()
                var uncompressedTotal = 0L
                var entryCount = 0
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    entryCount += 1
                    if (entryCount > MAX_ARCHIVE_ENTRIES) return@withContext failure("ENTRY_COUNT", "模型包文件数量超过 4096")
                    val validationName = entry.name.removeSuffix("/")
                    if (!validator.isSafeRelativePath(validationName)) return@withContext failure("UNSAFE_PATH", "模型包包含不安全路径：${entry.name}")
                    if (entry.name in archiveEntries) return@withContext failure("DUPLICATE_ENTRY", "模型包包含重复条目：${entry.name}")
                    archiveEntries[entry.name] = entry.size
                    if (entry.size > 0) {
                        if (entry.size > MAX_UNCOMPRESSED_BYTES - uncompressedTotal) {
                            return@withContext failure("ZIP_BOMB", "模型解压后超过 500MB")
                        }
                        uncompressedTotal += entry.size
                    }
                }
                val manifestEntry = zip.getEntry(MANIFEST_NAME)
                    ?: return@withContext failure("MANIFEST_MISSING", "模型包根目录缺少 model_manifest.json")
                if (manifestEntry.size > MAX_MANIFEST_BYTES) return@withContext failure("MANIFEST_SIZE", "模型清单超过 1MB")
                val manifestText = ByteArrayOutputStream().use { output ->
                    zip.getInputStream(manifestEntry).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_MANIFEST_BYTES) {
                                return@withContext failure("MANIFEST_SIZE", "模型清单超过 1MB")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val manifest = runCatching {
                    json.decodeFromString(ModelPackageManifest.serializer(), manifestText)
                }.getOrElse { return@withContext failure("MANIFEST_PARSE", "模型清单格式错误：${it.message}") }

                val type = runCatching { ModelType.valueOf(manifest.type) }.getOrNull()
                    ?: return@withContext failure("MODEL_TYPE", "不支持的模型类型")
                val policy = profiles?.profile?.value?.policy
                if (policy != null && manifest.estimatedMemoryMb > policy.maxSingleModelMemoryMb) {
                    return@withContext failure(
                        "DEVICE_MODEL_MEMORY",
                        "该模型预计占用 ${manifest.estimatedMemoryMb}MB，超过当前设备档位单模型 ${policy.maxSingleModelMemoryMb}MB 上限",
                    )
                }
                val concurrentTypes = ModelType.entries.filterNot { candidate ->
                    candidate == type ||
                        (policy?.allowConcurrentSpeechModels == false &&
                            type in SPEECH_MODEL_TYPES && candidate in SPEECH_MODEL_TYPES)
                }
                val otherMemoryMb = concurrentTypes.sumOf(::activeMemoryMb)
                val issues = validator.validate(
                    manifest = manifest,
                    packageSizeBytes = copied,
                    archiveEntries = archiveEntries,
                    otherActiveModelMemoryMb = otherMemoryMb,
                    deviceCombinedMemoryBudgetMb = policy?.maxConcurrentModelMemoryMb ?: 1_200,
                )
                if (issues.isNotEmpty()) return@withContext failure(issues.first().code, issues.joinToString("；") { it.message })
                val typeRoot = File(root, "custom/${type.name.lowercase()}").apply { mkdirs() }
                stagingDirectory = File(typeRoot, ".staging-${UUID.randomUUID()}").apply { mkdirs() }

                var extractedTotal = 0L
                manifest.files.forEach { declared ->
                    val entry = zip.getEntry(declared.path)
                    if (entry == null) {
                        if (declared.required) return@withContext failure("REQUIRED_FILE", "缺少必需文件：${declared.path}")
                        return@forEach
                    }
                    val output = File(checkNotNull(stagingDirectory), declared.path)
                    if (!output.canonicalPath.startsWith(stagingDirectory!!.canonicalPath + File.separator)) {
                        return@withContext failure("UNSAFE_PATH", "模型资源路径越界")
                    }
                    output.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                extractedTotal += count
                                if (extractedTotal > MAX_UNCOMPRESSED_BYTES) {
                                    return@withContext failure("ZIP_BOMB", "模型解压后超过 500MB")
                                }
                                digest.update(buffer, 0, count)
                                sink.write(buffer, 0, count)
                            }
                            sink.fd.sync()
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHash.equals(declared.sha256, ignoreCase = true)) {
                        return@withContext failure("CHECKSUM", "文件完整性校验失败：${declared.path}")
                    }
                }

                File(checkNotNull(stagingDirectory), MANIFEST_NAME).writeText(
                    json.encodeToString(ModelPackageManifest.serializer(), manifest),
                    Charsets.UTF_8,
                )
                val commit = File(typeRoot, "${manifest.version}-${System.currentTimeMillis()}")
                if (!stagingDirectory!!.renameTo(commit)) return@withContext failure("COMMIT", "模型安装提交失败，旧模型未受影响")
                stagingDirectory = null
                val pointer = ActiveModelPointer(manifest, commit.absolutePath, copied)
                val previous = readPointer(type)
                if (!writePointer(type, pointer)) {
                    commit.deleteRecursively()
                    return@withContext failure("POINTER", "模型激活失败，旧模型未受影响")
                }
                previous?.directoryPath?.takeIf { it != commit.absolutePath }?.let(::deleteCustomDirectory)
                refresh()
                invalidateBenchmarkSafely("${type.name} 模型已切换")
                val installed = InstalledModel(
                    type, manifest.version, manifest.displayName, false, copied, manifest.estimatedMemoryMb, true,
                )
                return@withContext ModelImportResult(
                    installed = installed,
                    message = "模型包校验并安装成功；设备基准已重置，将在真实推理中重新校准",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            failure("IMPORT_EXCEPTION", "模型导入失败：${throwable.message ?: "未知错误"}")
        } finally {
            packageFile.delete()
            stagingDirectory?.deleteRecursively()
        }
    }

    override suspend fun restoreBuiltIn(typeName: String): ModelImportResult = withContext(Dispatchers.IO) {
        root.mkdirs()
        val type = runCatching { ModelType.valueOf(typeName.uppercase()) }.getOrNull()
            ?: return@withContext failure("MODEL_TYPE", "未知模型类型：$typeName")
        val pointer = pointerFile(type)
        val previous = readPointer(type)
        if (pointer.exists() && !pointer.delete()) return@withContext failure("RESTORE", "无法切换回内置模型")
        previous?.directoryPath?.let(::deleteCustomDirectory)
        refresh()
        invalidateBenchmarkSafely("${type.name} 模型已恢复为内置版本")
        val installed = mutableModels.value.firstOrNull { it.type == type } ?: builtIn(type)
        ModelImportResult(
            installed = installed,
            message = if (installed.isBuiltIn && type in setOf(ModelType.ASR, ModelType.TTS)) {
                "已恢复该模块的内置离线模型"
            } else {
                "已恢复该模块的内置 Mock 实现"
            },
        )
    }

    override suspend fun clearImportCache(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("model-import-") || it.name.startsWith("call-delegate-") }
            .forEach { file ->
                val size = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (file.deleteRecursively()) freed += size
            }
        freed
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        root.mkdirs()
        ensureBuiltInManifest()
        ensureBundledModel(ModelType.ASR, "models/asr/vosk-model-small-cn-0.22")
        ensureBundledModel(ModelType.TTS, "models/tts/vits-icefall-zh-aishell3")
        mutableModels.value = ModelType.entries.map { type ->
            readPointer(type)?.takeIf(::isPointerUsable)?.let { pointer ->
                InstalledModel(
                    type = type,
                    version = pointer.manifest.version,
                    displayName = pointer.manifest.displayName,
                    isBuiltIn = File(pointer.directoryPath).canonicalPath
                        .startsWith(File(root, "builtin").canonicalPath + File.separator),
                    sizeBytes = pointer.packageSizeBytes,
                    estimatedMemoryMb = pointer.manifest.estimatedMemoryMb,
                    active = true,
                )
            } ?: builtIn(type)
        }
    }

    override suspend fun activeModel(type: ModelType): ActiveModel? = withContext(Dispatchers.IO) {
        ActiveModelResolver(root, json, validator).resolve(type)
    }

    private fun writePointer(type: ModelType, pointer: ActiveModelPointer): Boolean {
        val target = pointerFile(type)
        val temp = File(root, ".active_${type.name.lowercase()}.tmp")
        return runCatching {
            temp.writeText(json.encodeToString(ActiveModelPointer.serializer(), pointer), Charsets.UTF_8)
            FileOutputStream(temp, true).use { it.fd.sync() }
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse { temp.delete(); false }
    }

    private fun readPointer(type: ModelType): ActiveModelPointer? = runCatching {
        json.decodeFromString(ActiveModelPointer.serializer(), pointerFile(type).readText(Charsets.UTF_8))
    }.getOrNull()

    private fun isPointerUsable(pointer: ActiveModelPointer): Boolean = runCatching {
        val directory = File(pointer.directoryPath)
        if (!directory.isDirectory) return@runCatching false
        val entries = pointer.manifest.files.associate { declared ->
            declared.path to File(directory, declared.path).takeIf { it.isFile }?.length().orZero()
        }
        if (validator.validate(pointer.manifest, pointer.packageSizeBytes, entries).isNotEmpty()) {
            return@runCatching false
        }
        pointer.manifest.files.filter { it.required }.all { declared ->
            val file = File(directory, declared.path)
            file.isFile && sha256(file).equals(declared.sha256, ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun pointerFile(type: ModelType) = File(root, "active_${type.name.lowercase()}.json")

    private fun deleteCustomDirectory(path: String) {
        runCatching {
            val customRoot = File(root, "custom").canonicalFile
            val candidate = File(path).canonicalFile
            if (candidate.path.startsWith(customRoot.path + File.separator)) candidate.deleteRecursively()
        }
    }

    private fun ensureBuiltInManifest() {
        val directory = File(root, "builtin").apply { mkdirs() }
        val target = File(directory, MANIFEST_NAME)
        if (target.isFile && target.length() > 0L) return
        val temporary = File(directory, "$MANIFEST_NAME.tmp")
        runCatching {
            context.assets.open("models/default/$MANIFEST_NAME").use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
            }
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { temporary.delete() }
    }

    private fun ensureBundledModel(type: ModelType, assetPath: String) {
        if (ActiveModelResolver(root, json, validator).resolve(type) != null) return
        val source = object : ModelAssetSource {
            override fun list(path: String): List<String> = context.assets.list(path)?.toList().orEmpty()
            override fun open(path: String) = context.assets.open(path)
        }
        BundledModelInstaller(root, json, source).install(type, assetPath)
    }

    private fun builtIn(type: ModelType) = InstalledModel(
        type = type,
        version = "mock-1.0.0",
        displayName = "内置 Mock ${type.name}",
        isBuiltIn = true,
        sizeBytes = 0,
        estimatedMemoryMb = if (type in setOf(ModelType.ASR, ModelType.TTS)) 24 else 4,
        active = true,
    )

    private fun activeMemoryMb(type: ModelType): Int =
        readPointer(type)?.takeIf(::isPointerUsable)?.manifest?.estimatedMemoryMb
            ?: builtIn(type).estimatedMemoryMb

    private fun copyUriWithLimit(resolver: ContentResolver, uri: Uri, target: File, maxBytes: Long): Long? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) error("Package too large")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                total
            }
        } ?: error("Cannot open URI")
    }.getOrNull()

    private fun queryMetadata(resolver: ContentResolver, uri: Uri): FileMetadata {
        var name = uri.lastPathSegment ?: "model.zip"
        var size: Long? = null
        val cursor: Cursor? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return FileMetadata(name, size)
    }

    private fun failure(code: String, message: String) = ModelImportResult(errorCode = code, message = message)

    private suspend fun invalidateBenchmarkSafely(reason: String) {
        try {
            profiles?.invalidateBenchmark(reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A committed model switch must not be reported as failed because benchmark metadata could not reset.
        }
    }

    private data class FileMetadata(val name: String, val size: Long?)

    private companion object {
        const val MANIFEST_NAME = "model_manifest.json"
        const val MAX_PACKAGE_BYTES = 450L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 500L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 1024L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 4096
        val SPEECH_MODEL_TYPES = setOf(ModelType.ASR, ModelType.TTS)
    }
}
