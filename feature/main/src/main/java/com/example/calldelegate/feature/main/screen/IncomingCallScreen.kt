package com.example.calldelegate.feature.main.screen

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.calldelegate.domain.session.SessionPhase
import com.example.calldelegate.feature.main.ui.PageScaffold
import com.example.calldelegate.feature.main.viewmodel.CallViewModel

@Composable
fun IncomingCallScreen(
    onBack: () -> Unit,
    onAiAccepted: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.ensureIncoming() }
    val handleBack = { viewModel.reset(); onBack() }
    BackHandler(onBack = handleBack)

    PageScaffold(title = "模拟来电", onBack = handleBack) { outer ->
        Column(
            modifier = outer.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(22.dp))
            Text(state.callerName ?: "陌生号码", style = MaterialTheme.typography.headlineMedium)
            Text(state.callerNumber.ifBlank { "138 •••• 9527" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("模拟来电", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)

            Spacer(Modifier.weight(1f))
            if (state.phase == SessionPhase.COMPLETED) {
                Text("本次模拟来电已结束", textAlign = TextAlign.Center)
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("返回首页") }
            } else if (state.phase == SessionPhase.RINGING) {
                Button(
                    onClick = { viewModel.acceptWithAi(); onAiAccepted() },
                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("ai_answer"),
                ) {
                    Icon(Icons.Default.SmartToy, contentDescription = null)
                    Text("AI 代接", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = viewModel::decline,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null)
                        Text("拒接", Modifier.padding(start = 6.dp))
                    }
                    Button(
                        onClick = viewModel::acceptNormally,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C8C4A)),
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Text("接听", Modifier.padding(start = 6.dp))
                    }
                }
            } else {
                CircularProgressIndicator()
                Text("正在准备模拟来电…", modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
