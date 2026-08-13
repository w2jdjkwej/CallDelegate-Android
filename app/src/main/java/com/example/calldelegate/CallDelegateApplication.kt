package com.example.calldelegate

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.ai.speech.FixedReplyPhrases
import com.example.calldelegate.core.ai.speech.SherpaSpeechSynthesizer
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.data.local.CleanupWorker
import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.session.SessionPhase
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.ModelManager
import com.example.calldelegate.domain.api.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class CallDelegateApplication : Application() {
    @Inject lateinit var calls: CallRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var modules: AiModuleRegistry
    @Inject lateinit var models: ModelManager
    @Inject lateinit var deviceProfiles: DeviceProfileProvider
    @Inject lateinit var ruleProvider: RuleProvider
    @Inject lateinit var synthesizer: SherpaSpeechSynthesizer
    @Inject lateinit var callSession: CallSessionController
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scheduleCleanup()
        appScope.launch {
            calls.seedExamplesIfEmpty()
            deviceProfiles.refresh()
            models.refresh()
            modules.initializeAll(settings.settings.first().mockMode)
            prewarmFixedReplies()
        }
    }

    /**
     * Runs after the bundled models are installed and the engines are up, so the fixed replies are
     * already resident before the first call instead of being synthesized during it.
     *
     * Cancelled the moment a call leaves IDLE. Prewarming takes the synthesizer lock one phrase at a
     * time, so an incoming call could otherwise wait behind the phrase in flight -- the opposite of
     * what this is for. Any failure is swallowed: this is an optimization, and losing it must never
     * be visible to the user or affect a call.
     */
    private fun CoroutineScope.prewarmFixedReplies() {
        val prewarm = launch {
            runCatching {
                val rules = ruleProvider.load()
                if (rules is AppResult.Success) {
                    synthesizer.prewarm(FixedReplyPhrases.extract(rules.value))
                }
            }
        }
        val callWatcher = launch {
            callSession.state.first { it.phase != SessionPhase.IDLE }
            prewarm.cancel()
        }
        launch {
            // Stop watching once prewarming is done, so the collector does not outlive its purpose.
            prewarm.join()
            callWatcher.cancel()
        }
    }

    private fun scheduleCleanup() {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "expired-call-data-cleanup",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
