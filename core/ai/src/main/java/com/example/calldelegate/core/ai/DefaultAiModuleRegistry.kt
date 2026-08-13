package com.example.calldelegate.core.ai

import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.model.ModuleKind
import com.example.calldelegate.domain.model.ModuleStatus
import com.example.calldelegate.domain.model.ModuleStatusItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultAiModuleRegistry(
    private val runtime: SpeechRuntimeManager,
) : AiModuleRegistry {
    private val mutableStatuses = MutableStateFlow(defaultStatuses(ModuleStatus.MockReady))
    override val statuses: StateFlow<List<ModuleStatusItem>> = mutableStatuses.asStateFlow()

    override suspend fun initializeAll(mockMode: Boolean) {
        mutableStatuses.value = ModuleKind.entries.map { ModuleStatusItem(it, ModuleStatus.Initializing) }
        val initialized = runtime.configure(mockMode)
        mutableStatuses.value = ModuleKind.entries.map { kind ->
            val status = when (kind) {
                ModuleKind.ASR -> initialized.asrStatus
                ModuleKind.TTS -> initialized.ttsStatus
                else -> if (mockMode) ModuleStatus.MockReady else ModuleStatus.RealReady("rules-v2")
            }
            ModuleStatusItem(kind, status)
        }
    }

    override suspend fun releaseAll() {
        runtime.releaseAll()
    }

    private companion object {
        fun defaultStatuses(status: ModuleStatus) = ModuleKind.entries.map { ModuleStatusItem(it, status) }
    }
}
