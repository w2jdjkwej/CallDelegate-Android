package com.example.calldelegate.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.callDelegateDataStore by preferencesDataStore("call_delegate_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    override val settings: Flow<AppSettings> = context.callDelegateDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw throwable
        }
        .map(::toSettings)

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppResult<Unit> = try {
        context.callDelegateDataStore.edit { preferences ->
            write(preferences, transform(toSettings(preferences)))
        }
        AppResult.Success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        AppResult.Failure(AppError("SETTINGS_SAVE", "设置保存失败", throwable.message))
    }

    override suspend fun current(): AppSettings = settings.first()

    private fun toSettings(preferences: Preferences): AppSettings {
        val audioDays = (preferences[Keys.AUDIO_DAYS] ?: 7).coerceIn(1, 365)
        val textDays = (preferences[Keys.TEXT_DAYS] ?: 30).coerceIn(audioDays, 365)
        return AppSettings(
            audioRetentionDays = audioDays,
            transcriptRetentionDays = textDays,
            enabledScenes = (preferences[Keys.SCENES] ?: DEFAULT_SCENES)
                .split(',')
                .mapNotNull(::resolveScene)
                .toSet()
                .ifEmpty { DEFAULT_SCENE_SET },
            defaultInputMode = runCatching { InputMode.valueOf(preferences[Keys.INPUT_MODE] ?: InputMode.TEXT.name) }.getOrDefault(InputMode.TEXT),
            mockMode = preferences[Keys.MOCK_MODE] ?: false,
            fontScale = (preferences[Keys.FONT_SCALE] ?: 1f).coerceIn(0.85f, 1.4f),
            recordingPrompt = preferences[Keys.RECORDING_PROMPT].orEmpty(),
            carrierCallRecordingEnabled = preferences[Keys.CARRIER_CALL_RECORDING] ?: false,
            autoAnswerEnabled = preferences[Keys.AUTO_ANSWER] ?: true,
            autoAnswerDelayMillis = preferences[Keys.AUTO_ANSWER_DELAY_MS] ?: DEFAULT_AUTO_ANSWER_DELAY_MS,
        )
    }

    private fun write(preferences: androidx.datastore.preferences.core.MutablePreferences, value: AppSettings) {
        val audioDays = value.audioRetentionDays.coerceIn(1, 365)
        preferences[Keys.AUDIO_DAYS] = audioDays
        preferences[Keys.TEXT_DAYS] = value.transcriptRetentionDays.coerceIn(audioDays, 365)
        preferences[Keys.SCENES] = value.enabledScenes.joinToString(",") { it.id }
        preferences[Keys.INPUT_MODE] = value.defaultInputMode.name
        preferences[Keys.MOCK_MODE] = value.mockMode
        preferences[Keys.FONT_SCALE] = value.fontScale.coerceIn(0.85f, 1.4f)
        preferences[Keys.RECORDING_PROMPT] = value.recordingPrompt.take(200)
        preferences[Keys.CARRIER_CALL_RECORDING] = value.carrierCallRecordingEnabled
        preferences[Keys.AUTO_ANSWER] = value.autoAnswerEnabled
        // Floored rather than free: a zero here would answer inside the first ring, before the
        // person holding the phone could reach it.
        preferences[Keys.AUTO_ANSWER_DELAY_MS] =
            value.autoAnswerDelayMillis.coerceIn(MIN_AUTO_ANSWER_DELAY_MS, MAX_AUTO_ANSWER_DELAY_MS)
    }

    private object Keys {
        val AUDIO_DAYS = intPreferencesKey("audio_retention_days")
        val TEXT_DAYS = intPreferencesKey("text_retention_days")
        val SCENES = stringPreferencesKey("enabled_scenes")
        val INPUT_MODE = stringPreferencesKey("default_input_mode")
        val MOCK_MODE = booleanPreferencesKey("mock_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val RECORDING_PROMPT = stringPreferencesKey("recording_prompt")
        val CARRIER_CALL_RECORDING = booleanPreferencesKey("carrier_call_recording")
        val AUTO_ANSWER = booleanPreferencesKey("auto_answer_enabled")
        val AUTO_ANSWER_DELAY_MS = longPreferencesKey("auto_answer_delay_ms")
    }

    private companion object {
        const val DEFAULT_AUTO_ANSWER_DELAY_MS = 2_000L
        const val MIN_AUTO_ANSWER_DELAY_MS = 1_000L
        const val MAX_AUTO_ANSWER_DELAY_MS = 30_000L
        val DEFAULT_SCENE_SET = setOf(
            SceneType.DELIVERY,
            SceneType.RIDE_HAILING,
            SceneType.CUSTOMER_SERVICE,
            SceneType.REAL_ESTATE,
            SceneType.INSURANCE_FINANCE,
            SceneType.SPAM_RISK,
            SceneType.WORK,
            SceneType.UNKNOWN_IDENTITY,
        )
        val DEFAULT_SCENES = DEFAULT_SCENE_SET.joinToString(",") { it.id }

        @Suppress("DEPRECATION")
        fun resolveScene(id: String): SceneType? = when (id) {
            SceneType.SALES.id -> SceneType.SPAM_RISK
            else -> SceneType.entries.firstOrNull { it.id == id && it != SceneType.UNCLASSIFIED }
        }
    }
}
