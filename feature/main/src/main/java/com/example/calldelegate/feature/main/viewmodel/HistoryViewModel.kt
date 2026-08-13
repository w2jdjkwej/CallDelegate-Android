package com.example.calldelegate.feature.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.RetentionPolicy
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.HistoryFilter
import com.example.calldelegate.domain.model.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val calls: CallRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val audio: AudioOutputSink,
) : ViewModel() {
    val query = MutableStateFlow("")
    val scene = MutableStateFlow<SceneType?>(null)
    val message = MutableStateFlow<String?>(null)
    val settingsState = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.example.calldelegate.domain.model.AppSettings())
    val records = combine(query, scene) { text, selected -> HistoryFilter(selected, text) }
        .flatMapLatest(calls::observeHistory)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { query.value = value }
    fun setScene(value: SceneType?) { scene.value = value }
    fun delete(record: CallRecord) {
        viewModelScope.launch {
            message.value = when (val result = calls.delete(record.id)) {
                is com.example.calldelegate.core.common.AppResult.Success -> "记录已删除"
                is com.example.calldelegate.core.common.AppResult.Failure -> result.error.userMessage
            }
        }
    }

    fun play(record: CallRecord) {
        val path = record.audioPath ?: run { message.value = "该记录没有可播放的录音"; return }
        viewModelScope.launch {
            if (audio.playFile(path) is com.example.calldelegate.core.common.AppResult.Failure) {
                message.value = "录音播放失败或文件已不存在"
            }
        }
    }

    fun cleanupExpired() {
        viewModelScope.launch {
            val config = settings.current()
            val report = calls.cleanup(clock.nowEpochMillis(), config.audioRetentionDays, config.transcriptRetentionDays)
            message.value = "已删除 ${report.audioFilesDeleted} 个录音、${report.recordsDeleted} 条记录" +
                if (report.errors.isEmpty()) "" else "；${report.errors.size} 项失败"
        }
    }

    fun remainingDays(record: CallRecord): Int = RetentionPolicy.remainingDays(
        record.endedAtMillis,
        clock.nowEpochMillis(),
        settingsState.value.transcriptRetentionDays,
    )

    fun clearMessage() { message.value = null }
}
