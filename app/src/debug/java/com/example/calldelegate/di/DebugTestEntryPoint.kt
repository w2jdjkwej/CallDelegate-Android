package com.example.calldelegate.di

import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.ModelManager
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.VoiceActivityDetector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugTestEntryPoint {
    fun aiModuleRegistry(): AiModuleRegistry
    fun callRepository(): CallRepository
    fun callSessionController(): CallSessionController
    fun deviceProfileProvider(): DeviceProfileProvider
    fun settingsRepository(): SettingsRepository
    fun modelManager(): ModelManager
    fun speechRuntimeManager(): SpeechRuntimeManager
    fun speechRecognizer(): SpeechRecognizer
    fun speechSynthesizer(): SpeechSynthesizer
    fun dialogueEngine(): DialogueEngine
    fun audioOutputSink(): AudioOutputSink
    fun voiceActivityDetector(): VoiceActivityDetector
}
