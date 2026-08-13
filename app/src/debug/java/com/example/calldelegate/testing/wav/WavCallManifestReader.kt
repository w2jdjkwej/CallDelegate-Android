package com.example.calldelegate.testing.wav

import com.example.calldelegate.domain.model.SceneType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

data class ParsedWavCallManifest(
    val schemaVersion: Int,
    val manifestVersion: String?,
    val cases: List<ParsedWavCallCase>,
)

data class ParsedWavCallCase(
    val input: WavCallInputCase,
    val evaluation: WavCallEvaluationReference,
)

data class WavCallInputCase(
    val caseId: String,
    val relativeWavPath: String,
    val wavFile: File,
    val initialScene: SceneType?,
)

/** Evaluation-only fields. They are never passed to WAV, VAD, ASR, NLU/NLG, or TTS. */
data class WavCallEvaluationReference(
    val referenceText: String?,
    val speechStartMs: Long?,
    val speechEndMs: Long?,
    val expectedScene: String?,
    val expectedIntent: String?,
    val expectedCallNature: String?,
    val expectedRiskLevel: String?,
    val expectedEntities: Map<String, String>,
    val evaluateEntities: Boolean,
    val expectedDigitSpans: List<List<Int>>,
    val turnId: String?,
    val turnIndex: Int?,
    val expectedHotwords: List<String>,
    val expectedDeliveryIntent: String?,
)

class WavCallManifestException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

@OptIn(ExperimentalSerializationApi::class)
object WavCallManifestReader {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    fun read(manifestFile: File): ParsedWavCallManifest {
        if (!manifestFile.isFile) {
            throw WavCallManifestException("MANIFEST_MISSING", "找不到 WAV 测试清单")
        }
        val rootDirectory = manifestFile.parentFile?.canonicalFile
            ?: throw WavCallManifestException("MANIFEST_ROOT", "WAV 测试清单没有可用的父目录")
        val raw = try {
            json.decodeFromString<RawManifest>(manifestFile.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw WavCallManifestException("MANIFEST_PARSE", "WAV 测试清单不是有效 UTF-8 JSON：${error.message}")
        }
        if (raw.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            throw WavCallManifestException("MANIFEST_SCHEMA", "不支持的 WAV 测试清单 schemaVersion")
        }
        if (raw.cases.isEmpty()) {
            throw WavCallManifestException("MANIFEST_CASES", "WAV 测试清单至少需要一个用例")
        }

        val knownCaseIds = linkedSetOf<String>()
        val cases = raw.cases.map { rawCase ->
            val caseId = rawCase.caseId.trim()
            if (caseId.isEmpty()) {
                throw WavCallManifestException("MANIFEST_CASE_ID", "WAV 用例 caseId 不能为空")
            }
            if (!knownCaseIds.add(caseId)) {
                throw WavCallManifestException("MANIFEST_CASE_ID", "WAV 用例 caseId 必须唯一：$caseId")
            }
            val relativePath = rawCase.wavFile.trim()
            if (relativePath.isEmpty()) {
                throw WavCallManifestException("MANIFEST_WAV_PATH", "WAV 用例路径不能为空：$caseId")
            }
            val suppliedPath = File(relativePath)
            if (suppliedPath.isAbsolute) {
                throw WavCallManifestException("MANIFEST_WAV_PATH", "WAV 路径必须相对于清单目录：$caseId")
            }
            val resolved = File(rootDirectory, relativePath).canonicalFile
            if (!resolved.path.startsWith(rootDirectory.path + File.separator)) {
                throw WavCallManifestException("MANIFEST_WAV_ESCAPE", "WAV 路径不能逃离清单目录：$caseId")
            }
            val initialScene = parseInitialScene(rawCase.initialScene ?: raw.defaultInitialScene, caseId)
            val expectedDeliveryIntent = rawCase.expectedDeliveryIntent?.trim()?.takeIf(String::isNotEmpty)
            if (expectedDeliveryIntent != null && expectedDeliveryIntent !in DELIVERY_INTENTS) {
                throw WavCallManifestException(
                    "MANIFEST_DELIVERY_INTENT",
                    "不支持的 expectedDeliveryIntent：$caseId/$expectedDeliveryIntent",
                )
            }
            val expectedCallNature = rawCase.expectedCallNature?.trim()?.takeIf(String::isNotEmpty)
            if (expectedCallNature != null && expectedCallNature !in CALL_NATURES) {
                throw WavCallManifestException(
                    "MANIFEST_CALL_NATURE",
                    "不支持的 expectedCallNature：$caseId/$expectedCallNature",
                )
            }
            val expectedRiskLevel = rawCase.expectedRiskLevel?.trim()?.takeIf(String::isNotEmpty)
            if (expectedRiskLevel != null && expectedRiskLevel !in RISK_LEVELS) {
                throw WavCallManifestException(
                    "MANIFEST_RISK_LEVEL",
                    "不支持的 expectedRiskLevel：$caseId/$expectedRiskLevel",
                )
            }
            rawCase.expectedDigitSpans.forEach { span ->
                if (span.size != 2 || span[0] < 0 || span[1] < span[0]) {
                    throw WavCallManifestException(
                        "MANIFEST_DIGIT_SPAN",
                        "expectedDigitSpans 必须是 [start,end] 且范围有效：$caseId",
                    )
                }
            }
            ParsedWavCallCase(
                input = WavCallInputCase(caseId, relativePath, resolved, initialScene),
                evaluation = WavCallEvaluationReference(
                    referenceText = rawCase.referenceText,
                    speechStartMs = rawCase.speechStartMs,
                    speechEndMs = rawCase.speechEndMs,
                    expectedScene = rawCase.expectedScene,
                    expectedIntent = rawCase.expectedIntent,
                    expectedCallNature = expectedCallNature,
                    expectedRiskLevel = expectedRiskLevel,
                    expectedEntities = rawCase.expectedEntities.orEmpty(),
                    // An empty object means entity ground truth is not annotated. It must not
                    // turn a sample into an evaluated strict-negative case unless the manifest
                    // explicitly sets evaluateEntities=true.
                    evaluateEntities = rawCase.evaluateEntities ?: !rawCase.expectedEntities.isNullOrEmpty(),
                    expectedDigitSpans = rawCase.expectedDigitSpans,
                    turnId = rawCase.turnId,
                    turnIndex = rawCase.turnIndex,
                    expectedHotwords = rawCase.expectedHotwords,
                    expectedDeliveryIntent = expectedDeliveryIntent,
                ),
            )
        }
        return ParsedWavCallManifest(raw.schemaVersion, raw.manifestVersion, cases)
    }

    @Serializable
    private data class RawManifest(
        val schemaVersion: Int,
        val manifestVersion: String? = null,
        val defaultInitialScene: String? = null,
        val cases: List<RawCase>,
    )

    @Serializable
    private data class RawCase(
        val caseId: String,
        val wavFile: String,
        val referenceText: String? = null,
        val speechStartMs: Long? = null,
        val speechEndMs: Long? = null,
        val expectedScene: String? = null,
        val expectedIntent: String? = null,
        val expectedCallNature: String? = null,
        val expectedRiskLevel: String? = null,
        val expectedEntities: Map<String, String>? = null,
        val evaluateEntities: Boolean? = null,
        val expectedDigitSpans: List<List<Int>> = emptyList(),
        val turnId: String? = null,
        val turnIndex: Int? = null,
        val expectedHotwords: List<String> = emptyList(),
        val initialScene: String? = null,
        val expectedDeliveryIntent: String? = null,
    )

    private fun parseInitialScene(value: String?, caseId: String): SceneType? {
        val supplied = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val canonicalId = WavCallMetrics.canonicalSceneId(supplied)
            ?: throw WavCallManifestException(
                "MANIFEST_INITIAL_SCENE",
                "不支持的 initialScene：$caseId/$supplied",
            )
        return SceneType.fromId(canonicalId).takeIf { it != SceneType.UNCLASSIFIED }
            ?: throw WavCallManifestException(
                "MANIFEST_INITIAL_SCENE",
                "initialScene 不能是 unclassified：$caseId",
            )
    }

    private val DELIVERY_INTENTS = setOf(
        "arrived",
        "placed",
        "location_query",
        "access_blocked",
        "unreachable",
        "delayed",
        "item_issue",
    )

    private val CALL_NATURES = setOf("SERVICE", "NOTIFICATION", "MARKETING", "SUSPICIOUS", "UNKNOWN")
    private val RISK_LEVELS = setOf("LOW", "MEDIUM", "HIGH")
    private val SUPPORTED_SCHEMA_VERSIONS = setOf(
        WAV_CALL_MANIFEST_LEGACY_SCHEMA_VERSION,
        WAV_CALL_MANIFEST_SCHEMA_VERSION,
    )
}
