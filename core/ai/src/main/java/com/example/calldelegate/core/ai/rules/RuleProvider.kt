package com.example.calldelegate.core.ai.rules

import android.content.Context
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

fun interface RuleProvider {
    suspend fun load(): AppResult<DialogueRuleFile>
}

class AssetRuleProvider(
    private val context: Context,
    private val json: Json,
    private val assetName: String = "dialogue_rules.json",
    private val validator: RuleConfigValidator = RuleConfigValidator(),
) : RuleProvider {
    private val mutex = Mutex()
    private val strictJson = Json(json) { ignoreUnknownKeys = false }
    @Volatile private var cached: DialogueRuleFile? = null

    override suspend fun load(): AppResult<DialogueRuleFile> {
        cached?.let { return AppResult.Success(it) }
        return mutex.withLock {
            cached?.let { return@withLock AppResult.Success(it) }
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { reader ->
                        strictJson.decodeFromString(DialogueRuleFile.serializer(), reader.readText())
                    }.also(validator::validate)
                }.fold(
                    onSuccess = { value -> cached = value; AppResult.Success(value) },
                    onFailure = { AppResult.Failure(AppError("RULE_LOAD_FAILED", "对话规则加载失败", it.message, false)) },
                )
            }
        }
    }
}
