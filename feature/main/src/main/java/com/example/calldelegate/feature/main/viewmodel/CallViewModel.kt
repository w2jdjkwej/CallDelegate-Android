package com.example.calldelegate.feature.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calldelegate.domain.api.AiAnswerRouter
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.PresetRepository
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.PresetSample
import com.example.calldelegate.domain.session.SessionPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val controller: CallSessionController,
    private val answerRouter: AiAnswerRouter,
    presets: PresetRepository,
) : ViewModel() {
    val state = controller.state
    val presetSamples: List<PresetSample> = presets.samples()

    fun ensureIncoming() {
        if (state.value.phase in setOf(SessionPhase.IDLE, SessionPhase.COMPLETED, SessionPhase.ERROR)) {
            viewModelScope.launch { controller.simulateIncoming(null, "138 •••• 9527") }
        }
    }

    fun decline() { viewModelScope.launch { controller.decline() } }
    fun acceptNormally() { viewModelScope.launch { controller.acceptNormally() } }
    /**
     * The input mode is a fallback, not a choice: when a telephony call is up, the assistant has to
     * answer over that call's own audio or the caller hears nothing. See [AiAnswerRouter].
     */
    fun acceptWithAi() { viewModelScope.launch { answerRouter.acceptWithAi(state.value.inputMode) } }
    fun setInputMode(mode: InputMode) { viewModelScope.launch { controller.setInputMode(mode) } }
    fun submitText(text: String) { viewModelScope.launch { controller.submitText(text) } }
    fun submitPreset(id: String) { viewModelScope.launch { controller.submitPreset(id) } }
    fun captureMicrophone() { viewModelScope.launch { controller.captureMicrophoneTurn() } }
    fun requestTakeover() { viewModelScope.launch { controller.requestHumanTakeover() } }
    fun end() { viewModelScope.launch { controller.end() } }
    fun reset() { viewModelScope.launch { controller.reset() } }
}
