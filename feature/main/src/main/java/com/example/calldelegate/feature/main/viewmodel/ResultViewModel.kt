package com.example.calldelegate.feature.main.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val calls: CallRepository,
    private val audio: AudioOutputSink,
) : ViewModel() {
    private val recordId: String = checkNotNull(savedStateHandle["recordId"])
    val record = calls.observeById(recordId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val deleted = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun play() {
        val path = record.value?.audioPath ?: run { message.value = "该记录没有可播放的录音"; return }
        viewModelScope.launch {
            message.value = when (val result = audio.playFile(path)) {
                is com.example.calldelegate.core.common.AppResult.Success -> null
                is com.example.calldelegate.core.common.AppResult.Failure -> result.error.userMessage
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            when (val result = calls.delete(recordId)) {
                is com.example.calldelegate.core.common.AppResult.Success -> deleted.value = true
                is com.example.calldelegate.core.common.AppResult.Failure -> message.value = result.error.userMessage
            }
        }
    }
}
