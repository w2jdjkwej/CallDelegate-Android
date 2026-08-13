package com.example.calldelegate.testing.wav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calldelegate.core.audio.capture.StreamingTurnAudioInputSource
import com.example.calldelegate.core.audio.capture.WavInjectionMode
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.SecondaryRecognitionExperimentMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-only entry point. Supply a UTF-8 manifest under /data/local/tmp through instrumentation
 * arguments; the test stages it privately and intentionally bundles neither WAV data nor
 * evaluation references.
 */
@RunWith(AndroidJUnit4::class)
class WavCallEndToEndInstrumentationTest {

    @Test
    fun runsDebugWavPipelineWithExternalManifest() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val manifestPath = arguments.getString(ARG_MANIFEST)
        assumeTrue(
            "Pass -e $ARG_MANIFEST /data/local/tmp/<directory>/manifest.json to run the device WAV pipeline test.",
            !manifestPath.isNullOrBlank(),
        )
        val mode = arguments.getString(ARG_MODE)?.let { value ->
            runCatching { WavInjectionMode.valueOf(value) }.getOrElse {
                throw AssertionError("Unsupported $ARG_MODE: $value", it)
            }
        } ?: WavInjectionMode.AS_FAST_AS_POSSIBLE
        val measurementMode = arguments.getString(ARG_MEASUREMENT_MODE)?.let { value ->
            runCatching { WavCallMeasurementMode.valueOf(value) }.getOrElse {
                throw AssertionError("Unsupported $ARG_MEASUREMENT_MODE: $value", it)
            }
        } ?: WavCallMeasurementMode.SEGMENTATION_AUDIT
        val tailSilenceMs = arguments.getString(ARG_TAIL_SILENCE_MS)?.let { value ->
            value.toLongOrNull() ?: throw AssertionError("$ARG_TAIL_SILENCE_MS must be an integer: $value")
        } ?: DEFAULT_TAIL_SILENCE_MS
        val disableMaxTurnDuration = arguments.getString(ARG_DISABLE_MAX_TURN_DURATION)?.let { value ->
            when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw AssertionError("$ARG_DISABLE_MAX_TURN_DURATION must be true or false: $value")
            }
        } ?: false
        val secondaryRecognitionMode = arguments.getString(ARG_SECONDARY_RECOGNITION_MODE)?.let { value ->
            runCatching { SecondaryRecognitionExperimentMode.valueOf(value) }.getOrElse {
                throw AssertionError("Unsupported $ARG_SECONDARY_RECOGNITION_MODE: $value", it)
            }
        } ?: SecondaryRecognitionExperimentMode.DISABLED
        val voskChunkDurationMs = arguments.getString(ARG_VOSK_CHUNK_DURATION_MS)?.let { value ->
            val duration = value.toLongOrNull()
                ?: throw AssertionError("$ARG_VOSK_CHUNK_DURATION_MS must be an integer: $value")
            if (duration !in SUPPORTED_VOSK_CHUNK_DURATIONS_MS) {
                throw AssertionError(
                    "Unsupported $ARG_VOSK_CHUNK_DURATION_MS: $duration; " +
                        "expected one of $SUPPORTED_VOSK_CHUNK_DURATIONS_MS",
                )
            }
            duration
        } ?: StreamingTurnAudioInputSource.DEFAULT_RECOGNITION_CHUNK_DURATION_MS
        val endpointGraceMs = arguments.getString(ARG_ENDPOINT_GRACE_MS)?.let { value ->
            val grace = value.toLongOrNull()
                ?: throw AssertionError("$ARG_ENDPOINT_GRACE_MS must be an integer: $value")
            if (grace !in 0L..1_000L) {
                throw AssertionError("$ARG_ENDPOINT_GRACE_MS must be between 0 and 1000: $grace")
            }
            grace
        } ?: StreamingTurnAudioInputSource.DEFAULT_ENDPOINT_GRACE_MS
        val earlyEndpointGraceMs = arguments.getString(ARG_EARLY_ENDPOINT_GRACE_MS)?.let { value ->
            val grace = value.toLongOrNull()
                ?: throw AssertionError("$ARG_EARLY_ENDPOINT_GRACE_MS must be an integer: $value")
            // Must not exceed the long window: the early path may only ever shorten the wait.
            if (grace !in 0L..endpointGraceMs) {
                throw AssertionError(
                    "$ARG_EARLY_ENDPOINT_GRACE_MS must be between 0 and " +
                        "$ARG_ENDPOINT_GRACE_MS ($endpointGraceMs): $grace",
                )
            }
            grace
        } ?: endpointGraceMs
        val nominalRamOverrideGb = arguments.getString(ARG_RAM_OVERRIDE_GB)?.let { value ->
            val ram = value.toIntOrNull()
                ?: throw AssertionError("$ARG_RAM_OVERRIDE_GB must be an integer: $value")
            if (ram !in 4..16) {
                throw AssertionError("$ARG_RAM_OVERRIDE_GB must be between 4 and 16: $ram")
            }
            ram
        }
        val maxAlternativesOverride = arguments.getString(ARG_MAX_ALTERNATIVES)?.let { value ->
            val alternatives = value.toIntOrNull()
                ?: throw AssertionError("$ARG_MAX_ALTERNATIVES must be an integer: $value")
            if (alternatives !in 0..10) {
                throw AssertionError("$ARG_MAX_ALTERNATIVES must be between 0 and 10: $alternatives")
            }
            alternatives
        }
        val outputRoot = arguments.getString(ARG_OUTPUT_ROOT)?.let(::File)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val stagedManifest = DeviceWavManifestStager.stage(targetContext, checkNotNull(manifestPath))
        try {
            val request = WavCallTestRequest(
                manifestFile = stagedManifest.manifestFile,
                injectionMode = mode,
                measurementMode = measurementMode,
                tailSilenceMs = tailSilenceMs,
                disableMaxTurnDuration = disableMaxTurnDuration,
                outputRoot = outputRoot,
                gitCommit = arguments.getString(ARG_GIT_COMMIT),
                baselineReference = arguments.getString(ARG_BASELINE),
                secondaryRecognitionMode = secondaryRecognitionMode,
                voskChunkDurationMs = voskChunkDurationMs,
                endpointGraceMs = endpointGraceMs,
                earlyEndpointGraceMs = earlyEndpointGraceMs,
                nominalRamOverrideGb = nominalRamOverrideGb,
                maxAlternativesOverride = maxAlternativesOverride,
            )

            val result = WavCallTestRunner.from(targetContext).run(request)

            val failure = result as? AppResult.Failure
            assertTrue(
                "The runner must export a report for a runnable batch: ${failure?.error?.code} ${failure?.error?.userMessage}",
                result is AppResult.Success,
            )
            val report = (result as AppResult.Success).value
            assertNotEquals(
                "WAV batch failed: ${report.summary.batchFailureCode} ${report.summary.batchFailureMessage}",
                WavCallRunStatus.BATCH_FAILED,
                report.summary.status,
            )
            assertTrue("The manifest must contain at least one executed or cancelled case.", report.samples.isNotEmpty())
            assertTrue(File(report.resultDirectory, "summary.json").isFile)
            assertTrue(File(report.resultDirectory, "samples.json").isFile)
            assertTrue(File(report.resultDirectory, "samples.csv").isFile)
            assertTrue(File(report.resultDirectory, "failures.json").isFile)
        } finally {
            stagedManifest.delete()
        }
    }

    private companion object {
        const val ARG_MANIFEST = "wavManifest"
        const val ARG_MODE = "wavInjectionMode"
        const val ARG_MEASUREMENT_MODE = "wavMeasurementMode"
        const val ARG_TAIL_SILENCE_MS = "wavTailSilenceMs"
        const val ARG_DISABLE_MAX_TURN_DURATION = "wavDisableMaxTurnDuration"
        const val ARG_OUTPUT_ROOT = "wavOutputRoot"
        const val ARG_GIT_COMMIT = "wavGitCommit"
        const val ARG_BASELINE = "wavBaselineReference"
        const val ARG_SECONDARY_RECOGNITION_MODE = "wavSecondaryRecognitionMode"
        const val ARG_VOSK_CHUNK_DURATION_MS = "wavVoskChunkDurationMs"
        const val ARG_ENDPOINT_GRACE_MS = "wavEndpointGraceMs"
        const val ARG_EARLY_ENDPOINT_GRACE_MS = "wavEarlyEndpointGraceMs"
        const val ARG_RAM_OVERRIDE_GB = "wavRamOverrideGb"
        const val ARG_MAX_ALTERNATIVES = "wavMaxAlternatives"
        const val DEFAULT_TAIL_SILENCE_MS = 800L
        val SUPPORTED_VOSK_CHUNK_DURATIONS_MS = setOf(20L, 40L, 80L, 120L, 160L)
    }
}
