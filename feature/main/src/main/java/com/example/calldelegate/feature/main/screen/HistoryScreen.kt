package com.example.calldelegate.feature.main.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.feature.main.ui.PageScaffold
import com.example.calldelegate.feature.main.ui.RecordingStatusContent
import com.example.calldelegate.feature.main.ui.formatTime
import com.example.calldelegate.feature.main.ui.shortName
import com.example.calldelegate.feature.main.ui.recordingStatus
import com.example.calldelegate.feature.main.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedScene by viewModel.scene.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    PageScaffold(
        title = "历史记录",
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::cleanupExpired) {
                Icon(Icons.Default.CleaningServices, contentDescription = "批量删除过期记录")
            }
        },
    ) { outer ->
        Column(outer.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("搜索号码、摘要或转写") },
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = selectedScene == null, onClick = { viewModel.setScene(null) }, label = { Text("全部") })
                SceneType.entries.filter { it != SceneType.UNCLASSIFIED && it.id != "sales" }.forEach { scene ->
                    FilterChip(selected = selectedScene == scene, onClick = { viewModel.setScene(scene) }, label = { Text(scene.shortName()) })
                }
            }
            message?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
            }
            if (records.isEmpty()) {
                Text("没有符合条件的记录", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(records, key = CallRecord::id) { record ->
                        HistoryCard(
                            record,
                            viewModel.remainingDays(record),
                            onOpen = { onOpenRecord(record.id) },
                            onPlay = { viewModel.play(record) },
                            onDelete = { viewModel.delete(record) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    record: CallRecord,
    remainingDays: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val recordingPresentation = recordingStatus(record.recordingIntegrity, record.audioPath)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(record.callerName ?: record.callerNumber, style = MaterialTheme.typography.titleMedium)
                Text("${record.scene.shortName()} · ${formatTime(record.endedAtMillis)}", style = MaterialTheme.typography.bodySmall)
                Text(record.summary, modifier = Modifier.padding(top = 7.dp), maxLines = 2)
                RecordingStatusContent(
                    presentation = recordingPresentation,
                    recordingFailure = record.recordingFailure,
                    playbackFailure = record.playbackFailure,
                )
                Text("记录剩余 $remainingDays 天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                IconButton(onClick = onPlay, enabled = recordingPresentation.canPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "播放这条录音")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除这条记录") }
            }
        }
    }
}
