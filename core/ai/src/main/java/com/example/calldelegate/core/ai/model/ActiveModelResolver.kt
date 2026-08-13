package com.example.calldelegate.core.ai.model

import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.ModelType
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class ActiveModelResolver(
    private val root: File,
    private val json: Json,
    private val validator: ModelPackageValidator = ModelPackageValidator(),
) {
    fun resolve(type: ModelType): ActiveModel? = runCatching {
        val pointerFile = File(root, "active_${type.name.lowercase()}.json")
        if (!pointerFile.isFile) return null

        val pointer = json.decodeFromString(ActiveModelPointer.serializer(), pointerFile.readText(Charsets.UTF_8))
        if (pointer.manifest.type != type.name) return null

        val canonicalRoot = root.canonicalFile
        val directory = File(pointer.directoryPath).canonicalFile
        if (!directory.isDirectory || !directory.path.startsWith(canonicalRoot.path + File.separator)) return null

        val entries = pointer.manifest.files.associate { declared ->
            declared.path to File(directory, declared.path).length()
        }
        if (validator.validate(pointer.manifest, pointer.packageSizeBytes, entries).isNotEmpty()) return null

        val rolePaths = linkedMapOf<String, String>()
        for (declared in pointer.manifest.files) {
            val file = File(directory, declared.path).canonicalFile
            if (!file.path.startsWith(directory.path + File.separator)) return null
            if (declared.required && !file.isFile) return null
            if (file.isFile && sha256(file) != declared.sha256.lowercase()) return null
            if (file.isFile) rolePaths.putIfAbsent(declared.role.uppercase(), file.path)
        }

        ActiveModel(
            type = type,
            version = pointer.manifest.version,
            displayName = pointer.manifest.displayName,
            runtime = pointer.manifest.runtime,
            directoryPath = directory.path,
            sampleRateHz = pointer.manifest.sampleRateHz,
            files = rolePaths,
        )
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
