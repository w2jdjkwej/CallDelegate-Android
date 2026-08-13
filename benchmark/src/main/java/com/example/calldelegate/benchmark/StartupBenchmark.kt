package com.example.calldelegate.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startTargetActivityAndWait()
    }

    @Test
    fun openSimulatedCall() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 8,
        setupBlock = {
            returnToHomeScreenIfNeeded()
            pressHome()
        },
    ) {
        startTargetActivityAndWait()
        requireNotNull(device.wait(Until.findObject(By.text(SIMULATED_CALL_BUTTON_TEXT)), WAIT_TIMEOUT_MILLIS)) {
            "Simulated call button was not found"
        }.click()
        check(device.wait(Until.hasObject(By.text(AI_CALL_SCREEN_TEXT)), WAIT_TIMEOUT_MILLIS)) {
            "Simulated call screen was not shown"
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.returnToHomeScreenIfNeeded() {
        val targetIsVisible = device.hasObject(By.pkg(PACKAGE_NAME))
        val homeScreenIsVisible = device.hasObject(By.text(SIMULATED_CALL_BUTTON_TEXT))
        if (targetIsVisible && !homeScreenIsVisible) {
            device.pressBack()
            check(device.wait(Until.hasObject(By.text(SIMULATED_CALL_BUTTON_TEXT)), WAIT_TIMEOUT_MILLIS)) {
                "Home screen was not restored before warm startup"
            }
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.startTargetActivityAndWait() {
        device.executeShellCommand("am start -W -n $MAIN_ACTIVITY")
        check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), WAIT_TIMEOUT_MILLIS)) {
            "Target activity was not shown"
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.calldelegate"
        const val MAIN_ACTIVITY = "$PACKAGE_NAME/com.example.calldelegate.MainActivity"
        const val SIMULATED_CALL_BUTTON_TEXT = "启动模拟来电"
        const val AI_CALL_SCREEN_TEXT = "AI 代接"
        const val WAIT_TIMEOUT_MILLIS = 5_000L
    }
}
