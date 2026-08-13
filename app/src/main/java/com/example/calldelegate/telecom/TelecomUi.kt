package com.example.calldelegate.telecom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState

/** Shown on top of the home screen while the app is not the default dialer (real-telecom mode). */
@Composable
fun DialerSetupBanner(onRequest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "尚未设为默认拨号应用",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "真实通话（REAL_TELECOM）模式需要成为默认拨号应用；模拟来电模式无需此权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) { Text("设为默认拨号应用") }
        }
    }
}

/** Shown when we are the default dialer but the OS would demote our full-screen in-call UI. */
@Composable
fun FullScreenIntentBanner(onRequest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "通话界面可能无法自动弹出",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "系统未授予「全屏通知」权限，来电时通话界面只能以横幅提示，需手动点击。建议开启以便通话界面自动全屏弹出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) { Text("开启全屏通知权限") }
        }
    }
}

/** Real carrier-call UI. It deliberately contains no simulation title or setup banner. */
@Composable
fun CallScreen(
    snapshot: ExternalCallSnapshot?,
    aiAnswerEnabled: Boolean,
    aiSessionStarted: Boolean,
    onAnswerWithAi: () -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (snapshot == null) {
                Text("无进行中的通话", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onClose) { Text("关闭") }
                return@Column
            }

            Spacer(Modifier.height(88.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "☎",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 38.sp,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                snapshot.callerName ?: "陌生号码",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                maskCallerNumber(snapshot.callerNumber),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (aiSessionStarted) "AI 代接中" else stateLabel(snapshot.state),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))

            if (snapshot.state == ExternalCallState.RINGING && snapshot.isIncoming) {
                Button(
                    onClick = onAnswerWithAi,
                    enabled = aiAnswerEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .testTag("real_ai_answer"),
                ) {
                    Text("AI 代接", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Button(
                        onClick = onReject,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("拒接")
                    }
                    Button(
                        onClick = onAnswer,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ANSWER_GREEN),
                    ) {
                        Text("接听")
                    }
                }
            } else {
                if (!aiSessionStarted) {
                    Button(
                        onClick = onAnswerWithAi,
                        enabled = aiAnswerEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("real_ai_answer"),
                    ) {
                        Text("AI 代接", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Button(
                    onClick = onHangUp,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("挂断")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

internal fun maskCallerNumber(number: String?): String {
    val value = number?.trim().orEmpty()
    if (value.isEmpty()) return "未知号码"
    if (value.length < 8) return value
    return "${value.take(3)} •••• ${value.takeLast(4)}"
}

private fun stateLabel(state: ExternalCallState): String = when (state) {
    ExternalCallState.IDLE -> "空闲"
    ExternalCallState.RINGING -> "来电中"
    ExternalCallState.CONNECTING -> "接通中"
    ExternalCallState.ACTIVE -> "通话中"
    ExternalCallState.HOLDING -> "保持中"
    ExternalCallState.ENDED -> "已结束"
    ExternalCallState.ERROR -> "异常"
}

private val ANSWER_GREEN = Color(0xFF1C8C4A)
