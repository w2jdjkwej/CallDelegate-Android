package com.example.calldelegate.telecom.recording

enum class ShizukuOnboardingStep {
    ALLOW_MANAGER_INSTALL,
    INSTALL_MANAGER,
    START_SHIZUKU,
    GRANT_SHIZUKU_PERMISSION,
    REQUEST_DEFAULT_DIALER,
    COMPLETE,
}

data class ShizukuOnboardingEnvironment(
    val managerInstalled: Boolean,
    val canInstallPackages: Boolean,
    val shizukuState: ShizukuSetupState,
    val isDefaultDialer: Boolean,
)

object ShizukuOnboardingStateResolver {
    fun resolve(environment: ShizukuOnboardingEnvironment): ShizukuOnboardingStep {
        if (!environment.managerInstalled) {
            return if (environment.canInstallPackages) {
                ShizukuOnboardingStep.INSTALL_MANAGER
            } else {
                ShizukuOnboardingStep.ALLOW_MANAGER_INSTALL
            }
        }

        return when (environment.shizukuState) {
            ShizukuSetupState.NOT_RUNNING -> ShizukuOnboardingStep.START_SHIZUKU
            ShizukuSetupState.PERMISSION_REQUIRED ->
                ShizukuOnboardingStep.GRANT_SHIZUKU_PERMISSION
            ShizukuSetupState.READY -> {
                if (environment.isDefaultDialer) {
                    ShizukuOnboardingStep.COMPLETE
                } else {
                    ShizukuOnboardingStep.REQUEST_DEFAULT_DIALER
                }
            }
        }
    }
}
