package com.example.calldelegate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.feature.main.screen.HomeScreenContent
import com.example.calldelegate.feature.main.viewmodel.HomeUiState
import com.example.calldelegate.telecom.CallScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class Stage4M6UiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun realIncomingCallShowsThreeFunctionalChoicesWithoutSimulationChrome() {
        var aiAnswered = false
        var answered = false
        var rejected = false
        composeRule.setContent {
            CallScreen(
                snapshot = ExternalCallSnapshot(
                    callId = "call-1",
                    state = ExternalCallState.RINGING,
                    callerNumber = "10086",
                    isIncoming = true,
                ),
                aiAnswerEnabled = true,
                aiSessionStarted = false,
                onAnswerWithAi = { aiAnswered = true },
                onAnswer = { answered = true },
                onReject = { rejected = true },
                onHangUp = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("陌生号码").assertIsDisplayed()
        composeRule.onNodeWithText("10086").assertIsDisplayed()
        composeRule.onAllNodesWithText("模拟来电").assertCountEquals(0)
        composeRule.onAllNodesWithText("Shizuku", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("传输方式", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("AI 代接").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("接听").performClick()
        composeRule.onNodeWithText("拒接").performClick()

        assertThat(aiAnswered).isTrue()
        assertThat(answered).isTrue()
        assertThat(rejected).isTrue()
    }

    @Test
    fun activeAiCallShowsAiStateAndHangUp() {
        var hungUp = false
        composeRule.setContent {
            CallScreen(
                snapshot = ExternalCallSnapshot("call-1", ExternalCallState.ACTIVE),
                aiAnswerEnabled = true,
                aiSessionStarted = true,
                onAnswerWithAi = {},
                onAnswer = {},
                onReject = {},
                onHangUp = { hungUp = true },
                onClose = {},
            )
        }

        composeRule.onNodeWithText("AI 代接中").assertIsDisplayed()
        composeRule.onNodeWithText("挂断").performClick()

        assertThat(hungUp).isTrue()
    }

    @Test
    fun automatedEntryExistsAndInvokesCallback() {
        var starts = 0
        composeRule.setContent {
            HomeScreenContent(
                state = HomeUiState(),
                onStartCall = {},
                onHistory = {},
                onSettings = {},
                onStartAutomatedCall = { starts++ },
            )
        }

        composeRule.onNodeWithTag("start_automated_call").assertIsDisplayed().performClick()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun automatedEntryIsAbsentWithoutCallback() {
        composeRule.setContent {
            HomeScreenContent(
                state = HomeUiState(),
                onStartCall = {},
                onHistory = {},
                onSettings = {},
                onStartAutomatedCall = null,
            )
        }

        composeRule.onAllNodesWithTag("start_automated_call").assertCountEquals(0)
    }
}
