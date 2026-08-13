package com.example.calldelegate.feature.main.screen

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.feature.main.ui.LabelValue
import com.example.calldelegate.feature.main.ui.PageScaffold
import com.example.calldelegate.feature.main.ui.RecordingStatusContent
import com.example.calldelegate.feature.main.ui.SectionCard
import com.example.calldelegate.feature.main.ui.formatTime
import com.example.calldelegate.feature.main.ui.shortName
import com.example.calldelegate.feature.main.ui.recordingStatus
import com.example.calldelegate.feature.main.ui.resultDisplayName
import com.example.calldelegate.feature.main.ui.resultExtraFields
import com.example.calldelegate.feature.main.viewmodel.ResultViewModel

@Composable
fun ResultScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val record by viewModel.record.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }

    PageScaffold(title = "通话结果", onBack = onBack) { outer ->
        val value = record
        if (value == null) {
            Column(outer.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Text("正在读取记录…", Modifier.padding(top = 12.dp))
            }
        } else {
            val recordingPresentation = recordingStatus(value.recordingIntegrity, value.audioPath)
            Column(
                modifier = outer.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("result_card"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard("摘要") {
                    Text(value.summary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    LabelValue("来电显示", value.callerName ?: "陌生号码")
                    LabelValue("号码", value.callerNumber)
                    LabelValue("场景", value.scene.shortName())
                    LabelValue("时间", formatTime(value.startedAtMillis))
                    LabelValue("输入方式", value.inputMode.resultDisplayName())
                    if (value.recognitionFailed) LabelValue("识别异常", "发生过识别失败/沉默")
                    if (value.takeoverRequested) LabelValue("人工接管", "已请求")
                    RecordingStatusContent(
                        presentation = recordingPresentation,
                        recordingFailure = value.recordingFailure,
                        playbackFailure = value.playbackFailure,
                    )
                }
                SectionCard("结构化结果") {
                    val result = value.structuredResult
                    LabelValue("来电人/身份", result.callerIdentity)
                    LabelValue("单位", result.organization)
                    LabelValue("来电事项", result.purpose)
                    LabelValue("是否紧急", result.urgent?.let { if (it) "是" else "否" })
                    LabelValue("需要回电", result.callbackNeeded?.let { if (it) "是" else "否" })
                    LabelValue("时间信息", result.time)
                    LabelValue("地点", result.location)
                    LabelValue("异常类型", result.issueType)
                    LabelValue("订单编号", result.orderNumber)
                    LabelValue("预计送达时间", result.estimatedTime)
                    LabelValue("联系方式", result.contact)
                    resultExtraFields(result.extras).forEach { field ->
                        LabelValue(field.label, field.value)
                    }
                }
                SectionCard("完整转写") {
                    value.transcript.forEach { turn ->
                        val label = when (turn.speaker) {
                            Speaker.ASSISTANT -> "助手"
                            Speaker.CALLER -> "来电方"
                            Speaker.SYSTEM -> "系统"
                        }
                        Text("$label：${turn.text}", Modifier.padding(vertical = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = viewModel::play,
                        modifier = Modifier.weight(1f).testTag("result_play_recording"),
                        enabled = recordingPresentation.canPlay,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("播放录音")
                    }
                    Button(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("删除记录")
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除通话记录？") },
            text = { Text("数据库记录和对应私有录音文件会一并删除，此操作不可撤销。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
