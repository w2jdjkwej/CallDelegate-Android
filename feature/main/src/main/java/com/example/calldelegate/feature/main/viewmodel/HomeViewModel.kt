package com.example.calldelegate.feature.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.ModuleStatusItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val modules: List<ModuleStatusItem> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    settings: SettingsRepository,
    modules: AiModuleRegistry,
) : ViewModel() {
    val uiState = combine(settings.settings, modules.statuses, ::HomeUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
