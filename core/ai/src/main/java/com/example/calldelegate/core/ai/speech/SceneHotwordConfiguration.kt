package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.api.SpeechRecognitionFocus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun interface SceneHotwordConfigSource {
    fun readText(): String
}

@Serializable
data class SceneHotwordPolicyConfig(
    val retryBelowConfidence: Float = 0.60f,
    val minimumCandidateConfidence: Float = 0.15f,
    val minimumSceneMargin: Float = 0.18f,
    val replacementConfidenceGain: Float = 0.15f,
    val stableTurns: Int = 2,
    val weakTurnsBeforeGeneral: Int = 2,
)

@Serializable
data class CriticalEntityConfig(
    val slot: String,
    val focus: String,
    val cues: List<String> = emptyList(),
    val requiredIntents: List<String> = emptyList(),
)

@Serializable
data class SceneHotwordConfig(
    val schemaVersion: Int,
    val policy: SceneHotwordPolicyConfig = SceneHotwordPolicyConfig(),
    val globalPhrases: List<String> = emptyList(),
    val scenes: Map<String, List<String>>,
    val criticalEntities: Map<String, List<CriticalEntityConfig>> = emptyMap(),
)

class SceneHotwordProvider(
    private val source: SceneHotwordConfigSource,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    @Volatile private var loaded = false
    @Volatile private var cached: SceneHotwordConfig? = null

    fun configuration(): SceneHotwordConfig? {
        if (loaded) return cached
        return synchronized(this) {
            if (!loaded) {
                cached = runCatching { parseAndValidate(source.readText()) }.getOrNull()
                loaded = true
            }
            cached
        }
    }

    fun policy(): SceneHotwordPolicyConfig = configuration()?.policy ?: SceneHotwordPolicyConfig()

    fun supports(scene: SceneType): Boolean = configuration()?.scenes?.containsKey(scene.id) == true

    /** Small, low-risk vocabulary used by the first recognition pass. */
    fun globalPhrases(): List<String> {
        val phrases = configuration()?.globalPhrases.orEmpty()
        if (phrases.isEmpty()) return emptyList()
        return (phrases + UNKNOWN_TOKEN).distinct()
    }

    fun criticalEntitiesFor(scene: SceneType): List<CriticalEntityConfig> =
        configuration()?.criticalEntities?.get(scene.id).orEmpty()

    fun phrasesFor(
        sceneHints: Set<SceneType>,
        focuses: Set<SpeechRecognitionFocus> = emptySet(),
    ): List<String> {
        if (sceneHints.isEmpty() || sceneHints.size > MAX_SCENE_HINTS) return emptyList()
        val config = configuration() ?: return emptyList()
        val phrases = linkedSetOf<String>()
        sceneHints.forEach { scene ->
            config.scenes[scene.id].orEmpty().forEach { phrase ->
                if (phrase != UNKNOWN_TOKEN && shouldInclude(phrase, focuses)) phrases += phrase
            }
        }
        if (phrases.isEmpty()) return emptyList()
        phrases += UNKNOWN_TOKEN
        return phrases.toList()
    }

    /** Corrects a small set of known ASR homophones only inside their scene context. */
    fun correctRecognizedText(text: String, sceneHints: Set<SceneType>): String {
        var corrected = text
        if (SceneType.DELIVERY in sceneHints) {
            corrected = corrected.replace(DELIVERY_RIDER_HOMOPHONE, DELIVERY_RIDER_HOTWORD)
        }
        if (SceneType.REAL_ESTATE in sceneHints) {
            if (
                corrected.contains("挂牌假") &&
                corrected.contains("房东") &&
                (corrected.contains("再谈") || corrected.contains("基础") || corrected.contains("价格"))
            ) {
                corrected = corrected.replace("挂牌假", "挂牌价")
            }
            if (
                corrected.contains("挤压") &&
                (corrected.contains("剩余本金") || corrected.contains("出售") && corrected.contains("时间"))
            ) {
                corrected = corrected.replace("挤压", "解押")
            }
            if (
                corrected.contains("防凌") &&
                (corrected.contains("电梯") || corrected.contains("限制"))
            ) {
                corrected = corrected.replace("防凌", "房龄")
            }
            if (
                corrected.contains("房原") &&
                (corrected.contains("新") || corrected.contains("一套") || corrected.contains("筛选"))
            ) {
                corrected = corrected.replace("房原", "房源")
            }
        }
        return corrected
    }

    fun matchedPhrasesByScene(
        text: String,
        sceneHints: Set<SceneType>,
    ): Map<String, List<String>> {
        if (sceneHints.isEmpty() || sceneHints.size > MAX_SCENE_HINTS) return emptyMap()
        val config = configuration() ?: return emptyMap()
        val canonicalText = canonical(text)
        if (canonicalText.isBlank()) return emptyMap()
        val matches = linkedMapOf<String, List<String>>()
        sceneHints.forEach { scene ->
            val sceneMatches = config.scenes[scene.id].orEmpty().filter { phrase ->
                phrase != UNKNOWN_TOKEN && canonicalText.contains(canonical(phrase))
            }
            if (sceneMatches.isNotEmpty()) matches[scene.id] = sceneMatches
        }
        return matches
    }

    internal fun parseAndValidate(text: String): SceneHotwordConfig {
        val config = json.decodeFromString(SceneHotwordConfig.serializer(), text)
        require(config.schemaVersion == SCHEMA_VERSION) { "Unsupported hotword schema" }
        require(config.scenes.keys == SUPPORTED_SCENE_IDS) { "Hotword scenes must match the six supported scene IDs" }
        require(config.policy.retryBelowConfidence in 0f..1f)
        require(config.policy.minimumCandidateConfidence in 0f..1f)
        require(config.policy.minimumSceneMargin in 0f..1f)
        require(config.policy.replacementConfidenceGain in 0f..1f)
        require(config.policy.stableTurns >= 1)
        require(config.policy.weakTurnsBeforeGeneral >= 1)
        val globalPhrases = config.globalPhrases.filter { it != UNKNOWN_TOKEN }
        require(globalPhrases == globalPhrases.distinct()) { "Global hotword list contains duplicates" }
        require(globalPhrases.all { phrase -> phrase == phrase.trim() && phrase.isNotBlank() }) {
            "Global hotword list contains a blank phrase"
        }
        require(globalPhrases.all { phrase -> phrase.replace(WHITESPACE_REGEX, "").length >= MIN_PHRASE_LENGTH }) {
            "Global hotword list contains a single-character phrase"
        }
        require(config.criticalEntities.keys.all(SUPPORTED_SCENE_IDS::contains)) {
            "Critical entity scenes must use supported scene IDs"
        }
        config.scenes.forEach { (sceneId, phrases) ->
            require(phrases.isNotEmpty()) { "$sceneId hotword list is empty" }
            val canonicalPhrases = phrases.map { phrase -> phrase.replace(WHITESPACE_REGEX, "") }
            require(canonicalPhrases == canonicalPhrases.distinct()) { "$sceneId hotword list contains duplicates" }
            require(UNKNOWN_TOKEN in phrases) { "$sceneId hotword list must contain $UNKNOWN_TOKEN" }
            phrases.forEach { phrase ->
                require(phrase == phrase.trim() && phrase.isNotBlank()) { "$sceneId contains a blank hotword" }
                require(phrase == UNKNOWN_TOKEN || phrase.replace(WHITESPACE_REGEX, "").length >= MIN_PHRASE_LENGTH) {
                    "$sceneId contains a single-character hotword"
                }
            }
        }
        config.criticalEntities.forEach { (sceneId, rules) ->
            val slots = rules.map(CriticalEntityConfig::slot)
            require(slots == slots.distinct()) { "$sceneId critical entity slots contain duplicates" }
            rules.forEach { rule ->
                require(rule.slot.isNotBlank()) { "$sceneId contains a blank critical entity slot" }
                require(rule.focus in SUPPORTED_FOCUSES) { "$sceneId contains an unsupported focus" }
                require(rule.cues.isNotEmpty() || rule.requiredIntents.isNotEmpty()) {
                    "$sceneId critical entity ${rule.slot} has no trigger"
                }
                require(rule.cues.all(String::isNotBlank)) { "$sceneId critical entity ${rule.slot} has a blank cue" }
                require(rule.requiredIntents.all(String::isNotBlank)) {
                    "$sceneId critical entity ${rule.slot} has a blank intent"
                }
            }
        }
        return config
    }

    private fun shouldInclude(phrase: String, focuses: Set<SpeechRecognitionFocus>): Boolean {
        if (focuses.isEmpty()) return true
        return phraseFocuses(phrase).any(focuses::contains)
    }

    private fun phraseFocuses(phrase: String): Set<SpeechRecognitionFocus> {
        val canonicalPhrase = canonical(phrase)
        val result = linkedSetOf<SpeechRecognitionFocus>()
        if (LOCATION_MARKERS.any(canonicalPhrase::contains)) result += SpeechRecognitionFocus.LOCATION
        if (ISSUE_MARKERS.any(canonicalPhrase::contains)) result += SpeechRecognitionFocus.ISSUE
        if (ORDER_MARKERS.any(canonicalPhrase::contains)) result += SpeechRecognitionFocus.ORDER
        if (TIME_MARKERS.any(canonicalPhrase::contains)) result += SpeechRecognitionFocus.TIME
        if (SCENE_MARKERS.any(canonicalPhrase::contains) || result.isEmpty()) {
            result += SpeechRecognitionFocus.SCENE
        }
        return result
    }

    private fun canonical(text: String): String = text
        .replace(WHITESPACE_REGEX, "")
        .replace(PUNCTUATION_REGEX, "")
        .lowercase()

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_SCENE_HINTS = 2
        const val MIN_PHRASE_LENGTH = 2
        const val UNKNOWN_TOKEN = "[unk]"
        const val DELIVERY_RIDER_HOMOPHONE = "棋手"
        const val DELIVERY_RIDER_HOTWORD = "骑手"
        val WHITESPACE_REGEX = Regex("\\s+")
        val PUNCTUATION_REGEX = Regex("[，。！？、,.!?：:；;‘’“”\"']")
        val LOCATION_MARKERS = listOf(
            "小区", "门口", "前台", "保安室", "号楼", "楼栋", "上车点", "位置", "定位",
            "取餐柜", "取餐架", "保安亭", "值班室", "访客通道", "卸货区", "闸机", "连廊",
            "消防门", "茶水间", "电梯厅", "电梯口", "货梯", "等候区", "停车带",
            "访客口", "住院部", "门诊楼", "急诊楼", "校车通道",
        )
        val ISSUE_MARKERS = listOf("缺货", "破损", "延迟", "餐具", "洒", "退款", "异常", "投诉")
        val ORDER_MARKERS = listOf("订单", "单号", "尾号", "车牌")
        val TIME_MARKERS = listOf("分钟", "小时", "时间", "预计", "多久", "还没", "到期", "还款日")
        val SCENE_MARKERS = listOf(
            "外卖", "骑手", "送餐", "取餐", "送到", "送达", "出来拿", "走错",
            "司机", "网约车", "乘客", "取消", "客服", "售后", "中介", "看房", "物业",
            "保险", "理赔", "贷款", "还款", "办卡", "骚扰", "不要挂电话",
        )
        val SUPPORTED_SCENE_IDS = setOf(
            SceneType.DELIVERY.id,
            SceneType.RIDE_HAILING.id,
            SceneType.CUSTOMER_SERVICE.id,
            SceneType.REAL_ESTATE.id,
            SceneType.INSURANCE_FINANCE.id,
            SceneType.SPAM_RISK.id,
        )
        val SUPPORTED_FOCUSES = SpeechRecognitionFocus.entries.mapTo(linkedSetOf()) { it.name }
    }
}
