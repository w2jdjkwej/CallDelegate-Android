package com.example.calldelegate.telecom.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Prepares the official Shizuku manager APK bundled with CallDelegate.
 *
 * Android's package installer still displays its normal confirmation screen. This helper does not
 * attempt a silent install or grant shell privileges.
 */
class EmbeddedShizukuInstaller(
    private val context: Context,
) {
    fun isManagerInstalled(): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun createInstallPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun createManagerInstallIntent(): Intent {
        val apkFile = prepareManagerApk()
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.embedded-apks",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    }

    private fun prepareManagerApk(): File {
        val outputDirectory = File(context.cacheDir, OUTPUT_DIRECTORY)
        check(outputDirectory.exists() || outputDirectory.mkdirs()) {
            "无法创建内置安装包缓存目录"
        }

        val outputFile = File(outputDirectory, OUTPUT_FILE_NAME)
        if (outputFile.isFile && sha256(outputFile.inputStream()) == EXPECTED_SHA256) {
            return outputFile
        }

        val temporaryFile = File(outputDirectory, "$OUTPUT_FILE_NAME.tmp")
        temporaryFile.delete()
        context.assets.open(ASSET_PATH).use { input ->
            temporaryFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        check(sha256(temporaryFile.inputStream()) == EXPECTED_SHA256) {
            temporaryFile.delete()
            "内置 Shizuku 安装包校验失败"
        }

        outputFile.delete()
        check(temporaryFile.renameTo(outputFile)) {
            temporaryFile.delete()
            "无法准备内置 Shizuku 安装包"
        }
        return outputFile
    }

    private fun sha256(input: InputStream): String {
        return input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

        private const val ASSET_PATH =
            "third_party/shizuku/shizuku-v13.6.0.r1086.2650830c-release.apk"
        private const val OUTPUT_DIRECTORY = "embedded-apks"
        private const val OUTPUT_FILE_NAME = "shizuku-manager-v13.6.0.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val EXPECTED_SHA256 =
            "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"
    }
}
