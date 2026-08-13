package com.example.calldelegate.telecom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.audio.telecom.TelecomCallRegistry
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.feature.main.ui.CallDelegateTheme
import com.example.calldelegate.telecom.recording.ShizukuSetupState
import com.example.calldelegate.telecom.recording.ShizukuStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * In-call screen for real carrier calls. Normal answer and AI answer are separate actions: only an
 * explicit AI-answer click starts the automated session, while all audio work stays in services.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var registry: TelecomCallRegistry
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CallDelegateTheme {
                val snapshot by registry.callState.collectAsState()
                var aiSessionStarted by remember { mutableStateOf(false) }
                var aiSessionStarting by remember { mutableStateOf(false) }
                val aiAudioReady = ShizukuStatus.current() == ShizukuSetupState.READY
                LaunchedEffect(snapshot) {
                    if (snapshot == null) finish()
                }
                CallScreen(
                    snapshot = snapshot,
                    aiAnswerEnabled = aiAudioReady && !aiSessionStarting,
                    aiSessionStarted = aiSessionStarted,
                    onAnswerWithAi = {
                        val activeSnapshot = snapshot ?: return@CallScreen
                        aiSessionStarting = true
                        lifecycleScope.launch {
                            val currentSettings = settings.current()
                            val settingsResult = if (currentSettings.carrierCallRecordingEnabled) {
                                AppResult.Success(Unit)
                            } else {
                                settings.update { it.copy(carrierCallRecordingEnabled = true) }
                            }
                            if (settingsResult is AppResult.Failure) {
                                aiSessionStarting = false
                                return@launch
                            }
                            runCatching {
                                CallSessionService.startAutomated(
                                    context = this@CallActivity,
                                    transport = CallTransport.TELECOM,
                                    callerNumber = activeSnapshot.callerNumber,
                                )
                            }.onSuccess {
                                aiSessionStarted = true
                            }.onFailure {
                                aiSessionStarting = false
                            }
                        }
                    },
                    onAnswer = { registry.answer() },
                    onReject = { registry.reject() },
                    onHangUp = { registry.hangUp() },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SHOW_DIALPAD = "show_dialpad"
    }
}
