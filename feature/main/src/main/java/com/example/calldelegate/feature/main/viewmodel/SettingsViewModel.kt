package com.example.calldelegate.feature.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.ModelManager
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DeviceProfile
import com.example.calldelegate.domain.model.InstalledModel
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.ModuleStatusItem
import com.example.calldelegate.domain.model.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val models: List<InstalledModel> = emptyList(),
    val modules: List<ModuleStatusItem> = emptyList(),
    val deviceProfile: DeviceProfile = DeviceProfile(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val models: ModelManager,
    private val modules: AiModuleRegistry,
    private val calls: CallRepository,
    private val clock: Clock,
    private val deviceProfiles: DeviceProfileProvider,
) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val uiState = combine(
        settings.settings,
        models.installedModels,
        modules.statuses,
        deviceProfiles.profile,
        ::SettingsUiState,
    )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { deviceProfiles.refresh() }
    }

    fun setAudioDays(days: Int) = update { it.copy(audioRetentionDays = days.coerceIn(1, it.transcriptRetentionDays)) }
    fun setTextDays(days: Int) = update { it.copy(transcriptRetentionDays = days.coerceAtLeast(it.audioRetentionDays).coerceAtMost(365)) }
    fun setScene(scene: SceneType, enabled: Boolean) = update {
        it.copy(enabledScenes = if (enabled) it.enabledScenes + scene else it.enabledScenes - scene)
    }
    fun setInputMode(mode: InputMode) = update { it.copy(defaultInputMode = mode) }
    fun setMockMode(enabled: Boolean) {
        viewModelScope.launch {
            when (val result = settings.update { it.copy(mockMode = enabled) }) {
                is com.example.calldelegate.core.common.AppResult.Failure -> message.value = result.error.userMessage
                is com.example.calldelegate.core.common.AppResult.Success -> modules.initializeAll(enabled)
            }
        }
    }
    fun setFontScale(scale: Float) = update { it.copy(fontScale = scale.coerceIn(0.85f, 1.4f)) }
    fun setRecordingPrompt(prompt: String) = update { it.copy(recordingPrompt = prompt) }
    fun setCarrierCallRecording(enabled: Boolean) = update {
        it.copy(carrierCallRecordingEnabled = enabled)
    }

    fun importModel(uri: String) {
        viewModelScope.launch {
            busy.value = true
            val result = models.importFromUri(uri)
            message.value = result.message
            if (result.success) modules.initializeAll(settings.current().mockMode)
            busy.value = false
        }
    }

    fun restore(type: String) {
        viewModelScope.launch {
            val result = models.restoreBuiltIn(type)
            message.value = result.message
            if (result.success) modules.initializeAll(settings.current().mockMode)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val bytes = models.clearImportCache()
            message.value = "已清理 ${bytes / 1024} KB 临时文件"
        }
    }

    fun cleanupNow() {
        viewModelScope.launch {
            val config = settings.current()
            val report = calls.cleanup(clock.nowEpochMillis(), config.audioRetentionDays, config.transcriptRetentionDays)
            message.value = "清理完成：${report.audioFilesDeleted} 个录音，${report.recordsDeleted} 条记录"
        }
    }

    fun clearMessage() { message.value = null }
    fun refreshDeviceProfile() { viewModelScope.launch { deviceProfiles.refresh() } }
    fun resetDeviceBenchmark() {
        viewModelScope.launch {
            deviceProfiles.invalidateBenchmark("用户手动重置")
            message.value = "设备校准数据已重置，将在真实语音链路中重新采集"
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val result = settings.update(transform)
            if (result is com.example.calldelegate.core.common.AppResult.Failure) message.value = result.error.userMessage
        }
    }
}
