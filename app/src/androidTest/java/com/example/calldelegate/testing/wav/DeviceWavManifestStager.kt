package com.example.calldelegate.testing.wav

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/**
 * Reads an operator-supplied manifest and its WAV files from /data/local/tmp through the
 * instrumentation shell identity, then materializes them in private app storage for the runner.
 */
class DeviceWavManifestStager private constructor(
    val manifestFile: File,
    private val stagingDirectory: File,
) {
    fun delete() {
        stagingDirectory.deleteRecursively()
    }

    companion object {
        fun stage(context: Context, deviceManifestPath: String): DeviceWavManifestStager {
            validateDevicePath(deviceManifestPath)
            val stagingDirectory = File(
                context.cacheDir,
                "wav-call-input-${UUID.randomUUID()}",
            )
            check(stagingDirectory.mkdirs()) { "Cannot create private WAV staging directory" }
            try {
                val manifestFile = File(stagingDirectory, "manifest.json")
                manifestFile.writeBytes(readDeviceFile(deviceManifestPath))
                val manifest = WavCallManifestReader.read(manifestFile)
                val deviceRoot = deviceManifestPath.substringBeforeLast('/')

                manifest.cases.forEach { case ->
                    val deviceWavPath = "$deviceRoot/${case.input.relativeWavPath}"
                    validateDevicePath(deviceWavPath)
                    val privateWavFile = case.input.wavFile
                    privateWavFile.parentFile?.mkdirs()
                    privateWavFile.writeBytes(readDeviceFile(deviceWavPath))
                }
                return DeviceWavManifestStager(manifestFile, stagingDirectory)
            } catch (error: Throwable) {
                stagingDirectory.deleteRecursively()
                throw error
            }
        }

        private fun readDeviceFile(path: String): ByteArray {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat $path")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                input.readBytes().also { bytes ->
                    require(bytes.isNotEmpty()) { "Device WAV test file is empty or unreadable: $path" }
                }
            }
        }

        private fun validateDevicePath(path: String) {
            require(path.startsWith(DEVICE_INPUT_DIRECTORY)) {
                "wavManifest and WAV files must be under $DEVICE_INPUT_DIRECTORY"
            }
            require(path.matches(SAFE_DEVICE_PATH)) {
                "WAV test device path contains unsupported characters: $path"
            }
            require(path.split('/').none { it == ".." }) {
                "WAV test device path must not contain parent traversal"
            }
        }

        private const val DEVICE_INPUT_DIRECTORY = "/data/local/tmp/"
        private val SAFE_DEVICE_PATH = Regex("/data/local/tmp/[A-Za-z0-9._/-]+")
    }
}
