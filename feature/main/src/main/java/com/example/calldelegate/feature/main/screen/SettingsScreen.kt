package com.example.calldelegate.feature.main.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.feature.main.ui.LabelValue
import com.example.calldelegate.feature.main.ui.PageScaffold
import com.example.calldelegate.feature.main.ui.SectionCard
import com.example.calldelegate.feature.main.ui.label
import com.example.calldelegate.feature.main.ui.shortName
import com.example.calldelegate.feature.main.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var recordingPrompt by remember { mutableStateOf(state.settings.recordingPrompt) }
    LaunchedEffect(state.settings.recordingPrompt) { recordingPrompt = state.settings.recordingPrompt }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importModel(it.toString()) }
    }

    PageScaffold(title = "设置", onBack = onBack) { outer ->
        Column(
            modifier = outer.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let {
                Text(it, color = if (it.contains("失败") || it.contains("错误")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            SectionCard("数据保留") {
                RetentionStepper("原始录音", state.settings.audioRetentionDays, listOf(1, 7, 14, 30), viewModel::setAudioDays)
                Spacer(Modifier.height(8.dp))
                RetentionStepper("转写与结构化记录", state.settings.transcriptRetentionDays, listOf(7, 30, 60, 90), viewModel::setTextDays)
                Text("录音和数据库仅位于 App 私有目录。录音默认 7 天、文本默认 30 天。", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("启用场景") {
                SceneType.entries.filter { it != SceneType.UNCLASSIFIED && it.id != "sales" }.forEach { scene ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(scene.displayName, Modifier.weight(1f))
                        Switch(
                            checked = scene in state.settings.enabledScenes,
                            onCheckedChange = { viewModel.setScene(scene, it) },
                        )
                    }
                }
            }

            SectionCard("默认输入方式") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A default cannot be CALL_AUDIO: that mode only exists while a call carries it.
                    InputMode.entries.filterNot { it == InputMode.CALL_AUDIO }.forEach { mode ->
                        FilterChip(
                            selected = state.settings.defaultInputMode == mode,
                            onClick = { viewModel.setInputMode(mode) },
                            label = { Text(mode.displayName()) },
                        )
                    }
                }
            }

            SectionCard("设备分级与推理适配") {
                val profile = state.deviceProfile
                val benchmark = profile.benchmark
                LabelValue("当前档位", "${profile.tier}（RAM 基线 ${profile.baseTier}）")
                LabelValue("内存", "标称 ${profile.nominalRamGb}GB · 可见 ${profile.totalRamMb}MB · PSS ${profile.currentPssMb}MB")
                LabelValue("SoC", "${profile.socFamily} · ${profile.socModel}")
                LabelValue("ABI / CPU", "${profile.primaryAbi} · ${profile.cpuCoreCount} 核")
                LabelValue("热状态", profile.thermalSeverity.name)
                LabelValue(
                    "实际推理",
                    "${profile.policy.backend} · TTS ${profile.policy.ttsThreadCount} 线程 · ASR/TTS ${if (profile.policy.allowConcurrentSpeechModels) "可并驻" else "互斥驻留"}",
                )
                LabelValue(
                    "首次校准",
                    "${benchmark.state} · ${benchmark.completedSamples} 个有效样本 · 峰值 PSS ${benchmark.peakPssMb?.let { "${it}MB" } ?: "待采集"}",
                )
                if (benchmark.asrInferenceP95Millis != null || benchmark.ttsGenerationP95Millis != null) {
                    LabelValue(
                        "推理 P95",
                        "ASR ${benchmark.asrInferenceP95Millis?.let { "${it}ms" } ?: "待采集"} · TTS ${benchmark.ttsGenerationP95Millis?.let { "${it}ms" } ?: "待采集"}",
                    )
                }
                profile.reasons.take(3).forEach { reason ->
                    Text("• $reason", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (profile.policy.hardwareAccelerationEligible) {
                        "基准允许后续试验硬件加速；当前 Vosk / sherpa 主链仍使用 CPU。"
                    } else {
                        "当前固定使用 CPU；不会因芯片标称带 NPU 就自动启用硬件后端。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = viewModel::refreshDeviceProfile, modifier = Modifier.fillMaxWidth()) {
                    Text("重新检测当前资源状态")
                }
                OutlinedButton(onClick = viewModel::resetDeviceBenchmark, modifier = Modifier.fillMaxWidth()) {
                    Text("重置首次校准数据")
                }
            }

            SectionCard("语音模块") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mock 模式")
                        Text("关闭后使用真实模型；初始化失败会明确报错，不会静默切回 Mock。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.settings.mockMode, onCheckedChange = viewModel::setMockMode)
                }
                state.modules.forEach { LabelValue(it.kind.name, it.status.label()) }
            }

            SectionCard("模型管理") {
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("import_model"),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Text(if (busy) "正在校验…" else "从本地导入模型 ZIP", Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                state.models.forEach { model ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("${model.type.name} · ${model.displayName}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "版本 ${model.version} · ${model.sizeBytes / 1024 / 1024}MB · 预计 ${model.estimatedMemoryMb}MB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!model.isBuiltIn) {
                            OutlinedButton(onClick = { viewModel.restore(model.type.name) }) {
                                Icon(Icons.Default.Restore, contentDescription = null)
                                Text("恢复内置模型")
                            }
                        }
                    }
                }
            }

            SectionCard("无障碍字号") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.9f to "小", 1f to "标准", 1.2f to "大", 1.4f to "特大").forEach { (scale, label) ->
                        FilterChip(
                            selected = kotlin.math.abs(state.settings.fontScale - scale) < 0.01f,
                            onClick = { viewModel.setFontScale(scale) },
                            label = { Text(label) },
                        )
                    }
                }
                Text("系统字体缩放仍然有效，本设置是在系统缩放基础上追加。", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("录音提示话术") {
                OutlinedTextField(
                    value = recordingPrompt,
                    onValueChange = { recordingPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("可选；默认不播放") },
                    minLines = 2,
                )
                OutlinedButton(onClick = { viewModel.setRecordingPrompt(recordingPrompt) }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存提示话术")
                }
                Text("不同国家和地区对通话录音及告知的要求不同，实际发布前需按适用法律调整。", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("真实 SIM 通话录音") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("接通后自动录音")
                        Text(
                            "需要默认拨号器、已运行并授权的 Shizuku。录音保存到系统“音乐/CallDelegate/Recordings”。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.settings.carrierCallRecordingEnabled,
                        onCheckedChange = viewModel::setCarrierCallRecording,
                        modifier = Modifier.testTag("carrier_call_recording"),
                    )
                }
                Text(
                    "该能力依赖 shell 权限、scrcpy 内部协议和厂商音频实现，不保证所有 Android/ROM 均可用；开启前请确认参与方同意及当地法律要求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionCard("维护") {
                OutlinedButton(onClick = viewModel::cleanupNow, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Text("手动清理过期数据", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = viewModel::clearCache, modifier = Modifier.fillMaxWidth()) {
                    Text("清理模型导入临时缓存")
                }
            }

            Text(
                "内置 ASR/TTS 可完全离线运行；导入兼容模型后会重新初始化相应语音模块。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RetentionStepper(label: String, value: Int, choices: List<Int>, onChange: (Int) -> Unit) {
    Text("$label：$value 天", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { days ->
            FilterChip(selected = value == days, onClick = { onChange(days) }, label = { Text("$days 天") })
        }
    }
}

private fun InputMode.displayName() = when (this) {
    InputMode.MICROPHONE -> "麦克风"
    InputMode.CALL_AUDIO -> "通话音频"
    InputMode.PRESET_AUDIO -> "预设"
    InputMode.TEXT -> "文字"
}
