package com.example.calldelegate.core.ai.model

import com.example.calldelegate.domain.model.ModelType
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

interface ModelAssetSource {
    fun list(path: String): List<String>
    fun open(path: String): InputStream
}

class BundledModelInstaller(
    private val root: File,
    private val json: Json,
    private val source: ModelAssetSource,
) {
    fun install(type: ModelType, assetRoot: String): Boolean = runCatching {
        val manifestText = source.open("$assetRoot/model_manifest.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val manifest = json.decodeFromString(ModelPackageManifest.serializer(), manifestText)
        if (manifest.type != type.name) return false

        val temporary = File(root, "install-${UUID.randomUUID()}")
        copyTree(assetRoot, temporary)
        val packageSize = manifest.files.sumOf { File(temporary, it.path).length() }
        val entries = manifest.files.associate { it.path to File(temporary, it.path).length() }
        if (ModelPackageValidator().validate(manifest, packageSize, entries).isNotEmpty()) {
            temporary.deleteRecursively()
            return false
        }

        val target = File(root, "builtin/${type.name.lowercase()}/${manifest.version}")
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()
        if (!temporary.renameTo(target)) {
            temporary.deleteRecursively()
            return false
        }

        val pointer = ActiveModelPointer(manifest, target.absolutePath, packageSize)
        val pointerFile = File(root, "active_${type.name.lowercase()}.json")
        val pointerTemporary = File(root, "${pointerFile.name}.tmp")
        pointerTemporary.writeText(json.encodeToString(ActiveModelPointer.serializer(), pointer), Charsets.UTF_8)
        if (pointerFile.exists()) pointerFile.delete()
        pointerTemporary.renameTo(pointerFile)
    }.getOrDefault(false)

    private fun copyTree(assetPath: String, target: File) {
        val children = source.list(assetPath)
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            source.open(assetPath).use { input -> target.outputStream().use(input::copyTo) }
            return
        }
        target.mkdirs()
        for (child in children) copyTree("$assetPath/$child", File(target, child))
    }
}
