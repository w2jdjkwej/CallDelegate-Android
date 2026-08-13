package com.example.calldelegate.domain.model

import kotlinx.serialization.Serializable

@Serializable
/**
 * Where a turn's audio came from.
 *
 * [CALL_AUDIO] is the downlink of a real telephony call, and it is separate from [MICROPHONE]
 * because the two lead to different answers about a failure. The telecom path used to report itself
 * as MICROPHONE -- the source it is built from defaults to that -- so a call record could not say
 * whether the assistant had heard the caller down the line or heard the room, and a session that had
 * silently fallen back to the microphone looked exactly like one that had not.
 */
enum class InputMode { MICROPHONE, CALL_AUDIO, PRESET_AUDIO, TEXT }

@Serializable
enum class SceneType(val id: String, val displayName: String) {
    DELIVERY("delivery", "快递或外卖"),
    RIDE_HAILING("ride_hailing", "打车出行"),
    CUSTOMER_SERVICE("customer_service", "客服售后"),
    REAL_ESTATE("real_estate", "房产相关"),
    INSURANCE_FINANCE("insurance_finance", "保险金融"),
    SPAM_RISK("spam_risk", "骚扰或风险来电"),
    WORK("work", "工作来电"),
    UNKNOWN_IDENTITY("unknown_identity", "陌生号码身份询问"),
    @Deprecated("Only retained to read records created by rule schema v1")
    SALES("sales", "旧版推销分类"),
    UNCLASSIFIED("unclassified", "待判断");

    companion object {
        fun fromId(id: String?): SceneType = entries.firstOrNull { it.id == id } ?: UNCLASSIFIED
    }
}

@Serializable
enum class CallStatus {
    RINGING,
    DECLINED,
    ACTIVE_AI,
    REQUESTING_TAKEOVER,
    HUMAN_TAKEOVER,
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

@Serializable
enum class RecordingIntegrity {
    COMPLETE,
    PARTIAL,
    FAILED,
    LEGACY_UNVERIFIED,
}

@Serializable
data class AudioFailure(
    val code: String,
    val message: String,
)

@Serializable
enum class Speaker { ASSISTANT, CALLER, SYSTEM }

@Serializable
data class TranscriptTurn(
    val speaker: Speaker,
    val text: String,
    val timestampMillis: Long,
    val confidence: Float? = null,
)

@Serializable
data class StructuredResult(
    val callerIdentity: String? = null,
    val organization: String? = null,
    val purpose: String? = null,
    val urgent: Boolean? = null,
    val callbackNeeded: Boolean? = null,
    val time: String? = null,
    val location: String? = null,
    val contact: String? = null,
    val issueType: String? = null,
    val orderNumber: String? = null,
    val estimatedTime: String? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    fun merge(slots: Map<String, String>): StructuredResult = copy(
        callerIdentity = slots["callerIdentity"] ?: callerIdentity,
        organization = slots["organization"] ?: organization,
        purpose = slots["purpose"] ?: purpose,
        urgent = slots["urgent"]?.toBooleanStrictOrNull() ?: urgent,
        callbackNeeded = slots["callbackNeeded"]?.toBooleanStrictOrNull() ?: callbackNeeded,
        time = slots["time"] ?: time,
        location = slots["location"] ?: location,
        contact = slots["contact"] ?: contact,
        issueType = slots["issueType"] ?: issueType,
        orderNumber = slots["orderNumber"] ?: orderNumber,
        estimatedTime = slots["estimatedTime"] ?: estimatedTime,
        extras = extras + slots.filterKeys { it !in KNOWN_KEYS },
    )

    /**
     * Merges slots using the public entity protocol for the selected scene.
     * Legacy delivery aliases are accepted as input, but are never retained in [extras].
     */
    fun merge(scene: SceneType, slots: Map<String, String>): StructuredResult {
        if (scene == SceneType.RIDE_HAILING) {
            val canonicalSlots = linkedMapOf<String, String>()
            slots.forEach { (key, value) ->
                if (key != "pickupLocation" && key != "deliveryLocation") canonicalSlots[key] = value
            }
            (slots["location"] ?: slots["pickupLocation"])
                ?.let { canonicalSlots["location"] = it }
            return merge(canonicalSlots)
        }
        if (scene != SceneType.DELIVERY) return merge(slots)

        val canonicalSlots = linkedMapOf<String, String>()
        slots.forEach { (key, value) ->
            if (key !in DELIVERY_LEGACY_KEYS && key != "purpose") canonicalSlots[key] = value
        }
        (slots["location"] ?: slots["deliveryLocation"])
            ?.let { canonicalSlots["location"] = it }
        (slots["orderNumber"] ?: slots["orderId"])
            ?.let { canonicalSlots["orderNumber"] = it }
        (slots["estimatedTime"] ?: slots["time"])
            ?.let { canonicalSlots["estimatedTime"] = it }
        return merge(canonicalSlots)
    }

    fun asSlotMap(): Map<String, String> = buildMap {
        callerIdentity?.let { put("callerIdentity", it) }
        organization?.let { put("organization", it) }
        purpose?.let { put("purpose", it) }
        urgent?.let { put("urgent", it.toString()) }
        callbackNeeded?.let { put("callbackNeeded", it.toString()) }
        time?.let { put("time", it) }
        location?.let { put("location", it) }
        contact?.let { put("contact", it) }
        issueType?.let { put("issueType", it) }
        orderNumber?.let { put("orderNumber", it) }
        estimatedTime?.let { put("estimatedTime", it) }
        putAll(extras)
    }

    /** Returns the formal externally evaluated entity set. Summary metadata is excluded. */
    fun asEntityMap(scene: SceneType): Map<String, String> {
        if (scene == SceneType.DELIVERY) {
            return buildMap {
                (location ?: extras["deliveryLocation"])?.let { put("location", it) }
                issueType?.let { put("issueType", it) }
                (orderNumber ?: extras["orderId"])?.let { put("orderNumber", it) }
                (estimatedTime ?: time)?.let { put("estimatedTime", it) }
            }
        }
        return asSlotMap().filterKeys { key -> key != "purpose" && key !in DELIVERY_EXTRA_ALIASES }
    }

    companion object {
        private val KNOWN_KEYS = setOf(
            "callerIdentity", "organization", "purpose", "urgent", "callbackNeeded", "time", "location", "contact",
            "issueType", "orderNumber", "estimatedTime",
        )
        private val DELIVERY_LEGACY_KEYS = setOf("deliveryLocation", "orderId", "time")
        private val DELIVERY_EXTRA_ALIASES = setOf("deliveryLocation", "orderId")
    }
}

@Serializable
data class CallRecord(
    val id: String,
    val callerName: String?,
    val callerNumber: String,
    val scene: SceneType,
    val summary: String,
    val structuredResult: StructuredResult,
    val transcript: List<TranscriptTurn>,
    val audioPath: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val status: CallStatus,
    val inputMode: InputMode,
    val recognitionFailed: Boolean,
    val takeoverRequested: Boolean,
    val recordingIntegrity: RecordingIntegrity = RecordingIntegrity.COMPLETE,
    val recordingFailure: AudioFailure? = null,
    val playbackFailure: AudioFailure? = null,
)

data class HistoryFilter(
    val scene: SceneType? = null,
    val keyword: String = "",
)

data class CleanupReport(
    val audioFilesDeleted: Int = 0,
    val recordsDeleted: Int = 0,
    val missingFiles: Int = 0,
    val errors: List<String> = emptyList(),
)
