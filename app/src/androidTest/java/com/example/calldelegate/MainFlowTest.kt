package com.example.calldelegate

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.di.DebugTestEntryPoint
import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.StructuredResult
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var modules: AiModuleRegistry
    private lateinit var calls: CallRepository
    private lateinit var controller: CallSessionController
    private lateinit var settings: SettingsRepository
    private lateinit var originalSettings: AppSettings
    private val testRecordIds = linkedSetOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = EntryPointAccessors.fromApplication(context, DebugTestEntryPoint::class.java)
        modules = dependencies.aiModuleRegistry()
        calls = dependencies.callRepository()
        controller = dependencies.callSessionController()
        settings = dependencies.settingsRepository()

        runBlocking {
            controller.reset()
            originalSettings = settings.current()
            requireSuccess(
                settings.update { current ->
                    current.copy(
                        enabledScenes = current.enabledScenes + SceneType.DELIVERY,
                        defaultInputMode = InputMode.TEXT,
                        mockMode = true,
                        recordingPrompt = "",
                    )
                },
                "建立测试设置",
            )
            modules.initializeAll(mockMode = true)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            controller.reset()
            testRecordIds.forEach { id -> requireSuccess(calls.delete(id), "清理测试记录 $id") }
            requireSuccess(settings.update { originalSettings }, "恢复原设置")
            modules.initializeAll(originalSettings.mockMode)
        }
    }

    @Test
    fun textInputCompletesCallAndShowsResult() {
        startAiCall()

        composeRule.onNodeWithTag("caller_text_input").performTextInput("您好，我是快递员，快递放驿站可以吗？")
        composeRule.onNodeWithTag("submit_caller_text").performClick()
        composeRule.waitUntil(8_000) { composeRule.onAllNodes(hasText("请问是否需要回电？", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitForEnabledTextInput()
        composeRule.onNodeWithTag("caller_text_input").performTextInput("不用回电")
        composeRule.onNodeWithTag("submit_caller_text").performClick()

        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag("result_card")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("通话结果").assertIsDisplayed()
        composeRule.onNodeWithText("完整转写").assertIsDisplayed()
        composeRule.onNodeWithText("配送状态").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("文字").performScrollTo().assertIsDisplayed()
        assertThat(
            composeRule.onAllNodes(hasText("deliveryIntent", substring = true))
                .fetchSemanticsNodes(),
        ).isEmpty()
        assertThat(composeRule.onAllNodes(hasText("TEXT")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun explicitCourierStatementDoesNotAskForSceneClarification() {
        startAiCall()

        composeRule.onNodeWithTag("caller_text_input")
            .performTextInput("您好，我是顺丰快递员，快递到了，放在驿站可以吗？")
        composeRule.onNodeWithTag("submit_caller_text").performClick()

        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(hasText("配送事项已经记录", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertThat(
            composeRule.onAllNodes(hasText("我听到两类事项", substring = true))
                .fetchSemanticsNodes(),
        ).isEmpty()
        composeRule.onNodeWithText("快递 / 外卖员").assertIsDisplayed()
    }

    @Test
    fun homeNavigatesToHistoryAndSettings() {
        val historySummary = createHistoryRecord()

        composeRule.onNodeWithText("历史记录").performClick()
        composeRule.onNodeWithText("搜索号码、摘要或转写").assertIsDisplayed()
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(hasText(historySummary)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(historySummary).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("数据保留").assertIsDisplayed()
        composeRule.onNodeWithText("14 天").performClick()
        composeRule.onNodeWithTag("import_model").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun presetAudioRunsThroughRecognitionAndCreatesResult() {
        startAiCall()

        composeRule.onNodeWithText("预设").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("快递到达")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("快递到达").performClick()
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(hasText(PRESET_TRANSCRIPT)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(PRESET_TRANSCRIPT).performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(hasText("请问是否需要回电？", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        waitForAwaitingInput()
        composeRule.onNodeWithText("文字").performClick()
        waitForEnabledTextInput()
        composeRule.onNodeWithTag("caller_text_input").performTextInput("不用回电")
        composeRule.onNodeWithTag("submit_caller_text").performClick()

        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag("result_card")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("通话结果").assertIsDisplayed()
        composeRule.onNodeWithText("配送").assertIsDisplayed()
    }

    private fun startAiCall() {
        composeRule.onNodeWithTag("start_simulated_call").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("ai_answer")).fetchSemanticsNodes().isNotEmpty()
        }
        controller.state.value.sessionId?.let(testRecordIds::add)
        composeRule.onNodeWithTag("ai_answer").performClick()
        waitForEnabledTextInput()
    }

    private fun waitForEnabledTextInput() {
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(hasTestTag("caller_text_input").and(isEnabled()))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForAwaitingInput() {
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(hasText("等待来电方发言")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun createHistoryRecord(): String {
        val id = "main-flow-history-${System.nanoTime()}"
        val summary = "MainFlow 独立历史记录 $id"
        val now = System.currentTimeMillis()
        val record = CallRecord(
            id = id,
            callerName = "自动化测试",
            callerNumber = "10000",
            scene = SceneType.WORK,
            summary = summary,
            structuredResult = StructuredResult(purpose = summary),
            transcript = emptyList(),
            audioPath = null,
            startedAtMillis = now - 1_000,
            endedAtMillis = now,
            status = CallStatus.COMPLETED,
            inputMode = InputMode.TEXT,
            recognitionFailed = false,
            takeoverRequested = false,
            recordingIntegrity = RecordingIntegrity.FAILED,
        )
        runBlocking { requireSuccess(calls.save(record), "建立历史记录测试数据") }
        testRecordIds += id
        return summary
    }

    private fun requireSuccess(result: AppResult<Unit>, action: String) {
        if (result is AppResult.Failure) {
            throw AssertionError("$action 失败：${result.error.userMessage}")
        }
    }

    private companion object {
        const val PRESET_TRANSCRIPT = "您好，我是顺丰快递员，快递到了，放在驿站可以吗？"
    }
}
