package com.example.calldelegate.telecom.recording

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ShizukuOnboardingDialog(
    step: ShizukuOnboardingStep,
    onContinue: () -> Unit,
    onLater: () -> Unit,
) {
    val content = contentFor(step)
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(content.title) },
        text = { Text(content.message) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(content.action)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("稍后设置")
            }
        },
    )
}

private data class OnboardingContent(
    val title: String,
    val message: String,
    val action: String,
)

private fun contentFor(step: ShizukuOnboardingStep): OnboardingContent {
    return when (step) {
        ShizukuOnboardingStep.ALLOW_MANAGER_INSTALL -> OnboardingContent(
            title = "允许安装录音组件",
            message = "CallDelegate 内置了官方签名的 Shizuku 13.6.0。" +
                "请允许本应用安装随包组件，系统仍会显示安装确认。",
            action = "打开系统设置",
        )
        ShizukuOnboardingStep.INSTALL_MANAGER -> OnboardingContent(
            title = "安装 Shizuku",
            message = "下一步将打开 Android 安装确认页面。" +
                "Shizuku 安装后会作为独立系统应用出现。",
            action = "开始安装",
        )
        ShizukuOnboardingStep.START_SHIZUKU -> OnboardingContent(
            title = "启动 Shizuku 服务",
            message = "打开 Shizuku 后选择“通过无线调试启动”，按页面提示完成配对和启动，" +
                "然后返回 CallDelegate。手机重启后通常需要再次启动该服务。",
            action = "打开 Shizuku",
        )
        ShizukuOnboardingStep.GRANT_SHIZUKU_PERMISSION -> OnboardingContent(
            title = "授予录音桥权限",
            message = "Shizuku 已运行。请允许 CallDelegate 使用 Shizuku 完成真实通话录音。",
            action = "授予权限",
        )
        ShizukuOnboardingStep.REQUEST_DEFAULT_DIALER -> OnboardingContent(
            title = "设置默认电话应用",
            message = "真实通话状态由 Android Telecom 提供。" +
                "请将 CallDelegate 设置为默认电话应用。",
            action = "设置默认应用",
        )
        ShizukuOnboardingStep.COMPLETE -> OnboardingContent(
            title = "录音环境已就绪",
            message = "所有必要条件均已满足。完成后将自动开启真实 SIM 通话录音。",
            action = "完成并开启录音",
        )
    }
}
