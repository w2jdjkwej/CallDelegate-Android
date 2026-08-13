package com.example.calldelegate.domain.model

data class AppSettings(
    val audioRetentionDays: Int = 7,
    val transcriptRetentionDays: Int = 30,
    /**
     * The scenes a call may be classified into.
     *
     * [SceneType.WORK] is deliberately absent. It is not one of the six the system is assessed on,
     * and leaving it enabled meant it could take turns without ever being credited for one: on the
     * blind material 我把会议材料发到你邮箱了 and 我们下午三点在会议室讨论方案 -- both of which should
     * select no scene at all -- were answered as work calls, and 您之前反馈的充电异常已经转交技术部门
     * was taken from customer service at 0.60. The scene stays in [SceneType] so that records
     * already written as work keep reading back, the same way sales does.
     */
    val enabledScenes: Set<SceneType> = setOf(
        SceneType.DELIVERY,
        SceneType.RIDE_HAILING,
        SceneType.CUSTOMER_SERVICE,
        SceneType.REAL_ESTATE,
        SceneType.INSURANCE_FINANCE,
        SceneType.SPAM_RISK,
        SceneType.UNKNOWN_IDENTITY,
    ),
    val defaultInputMode: InputMode = InputMode.TEXT,
    /**
     * Off by default: this build answers real calls with the real models, and a first launch in
     * mock mode answers them with placeholder speech until someone finds the switch.
     */
    val mockMode: Boolean = false,
    val fontScale: Float = 1f,
    val recordingPrompt: String = "",
    val carrierCallRecordingEnabled: Boolean = false,
    /**
     * Whether an incoming call is answered by the assistant without anyone touching the phone.
     *
     * On by default: answering unattended is what this application is for, and a build that waits
     * for a tap cannot do the one thing it exists to do. Turning it off leaves the manual AI-answer
     * button as the only way in, which is exactly how the app behaved before.
     */
    val autoAnswerEnabled: Boolean = true,
    /**
     * How long a call rings before the assistant picks it up.
     *
     * Not zero. The delay is what leaves room for the person holding the phone to take their own
     * call, and it lets a wrong number or a one-ring hangup end on its own rather than being
     * answered and recorded. Two seconds is roughly one ring.
     */
    val autoAnswerDelayMillis: Long = 2_000L,
)

enum class ModuleKind { VAD, ASR, INTENT, ENTITY, DIALOGUE, SUMMARY, TTS }

sealed interface ModuleStatus {
    data object MockReady : ModuleStatus
    data object Initializing : ModuleStatus
    data class RealReady(val version: String) : ModuleStatus
    data class Deferred(val reason: String) : ModuleStatus
    data class Missing(val reason: String) : ModuleStatus
    data class Error(val reason: String) : ModuleStatus
}

data class ModuleStatusItem(
    val kind: ModuleKind,
    val status: ModuleStatus,
)

enum class ModelType { VAD, ASR, INTENT, ENTITY, TTS }

data class InstalledModel(
    val type: ModelType,
    val version: String,
    val displayName: String,
    val isBuiltIn: Boolean,
    val sizeBytes: Long,
    val estimatedMemoryMb: Int,
    val active: Boolean,
)

data class ActiveModel(
    val type: ModelType,
    val version: String,
    val displayName: String,
    val runtime: String,
    val directoryPath: String,
    val sampleRateHz: Int,
    val files: Map<String, String>,
)

data class ModelImportResult(
    val installed: InstalledModel? = null,
    val errorCode: String? = null,
    val message: String,
) {
    val success: Boolean get() = installed != null
}
