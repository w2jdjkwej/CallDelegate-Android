package com.example.calldelegate.telecom.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShizukuOnboardingStateResolverTest {
    @Test
    fun `requests install permission before installing manager`() {
        val step = resolve(
            managerInstalled = false,
            canInstallPackages = false,
        )

        assertThat(step).isEqualTo(ShizukuOnboardingStep.ALLOW_MANAGER_INSTALL)
    }

    @Test
    fun `installs manager after install permission is available`() {
        val step = resolve(
            managerInstalled = false,
            canInstallPackages = true,
        )

        assertThat(step).isEqualTo(ShizukuOnboardingStep.INSTALL_MANAGER)
    }

    @Test
    fun `opens manager when Shizuku is not running`() {
        val step = resolve(
            managerInstalled = true,
            shizukuState = ShizukuSetupState.NOT_RUNNING,
        )

        assertThat(step).isEqualTo(ShizukuOnboardingStep.START_SHIZUKU)
    }

    @Test
    fun `requests Shizuku permission after service starts`() {
        val step = resolve(
            managerInstalled = true,
            shizukuState = ShizukuSetupState.PERMISSION_REQUIRED,
        )

        assertThat(step).isEqualTo(ShizukuOnboardingStep.GRANT_SHIZUKU_PERMISSION)
    }

    @Test
    fun `requests default dialer after Shizuku is ready`() {
        val step = resolve(
            managerInstalled = true,
            shizukuState = ShizukuSetupState.READY,
            isDefaultDialer = false,
        )

        assertThat(step).isEqualTo(ShizukuOnboardingStep.REQUEST_DEFAULT_DIALER)
    }

    @Test
    fun `completes only after every requirement is ready`() {
        val step = resolve(
            managerInstalled = true,
            shizukuState = ShizukuSetupState.READY,
            isDefaultDialer = true,
        )

        assertThat(step).isEqualTo(ShizukuOnboardingStep.COMPLETE)
    }

    private fun resolve(
        managerInstalled: Boolean,
        canInstallPackages: Boolean = false,
        shizukuState: ShizukuSetupState = ShizukuSetupState.NOT_RUNNING,
        isDefaultDialer: Boolean = false,
    ): ShizukuOnboardingStep {
        return ShizukuOnboardingStateResolver.resolve(
            ShizukuOnboardingEnvironment(
                managerInstalled = managerInstalled,
                canInstallPackages = canInstallPackages,
                shizukuState = shizukuState,
                isDefaultDialer = isDefaultDialer,
            ),
        )
    }
}
