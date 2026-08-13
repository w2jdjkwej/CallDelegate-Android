package com.example.calldelegate.feature.main.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.TranscriptTurn
import com.example.calldelegate.domain.session.CallSessionSnapshot
import com.example.calldelegate.domain.session.SessionPhase
import com.example.calldelegate.feature.main.viewmodel.CallViewModel
import kotlinx.coroutines.delay

private val CallScreenBackground = Color(0xFFF3F5F8)
private val HangUpRed = Color(0xFFFF4D3A)
private val TakeoverGreen = Color(0xFF00AF3F)

@Composable
fun ActiveCallScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var textInput by rememberSaveable { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }
    var showInputPanel by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.captureMicrophone()
    }

    LaunchedEffect(state.completedRecordId) {
        state.completedRecordId?.let(onResult)
    }

    val handleBack = {
        viewModel.end()
        onBack()
    }
    BackHandler(onBack = handleBack)

    val canInput = state.callStatus == CallStatus.ACTIVE_AI &&
        state.phase == SessionPhase.AWAITING_INPUT
    val busy = state.phase in setOf(
        SessionPhase.OPENING,
        SessionPhase.SPEAKING,
        SessionPhase.RECORDING,
        SessionPhase.RECOGNIZING,
        SessionPhase.THINKING,
        SessionPhase.ENDING,
    )
    val canEnd = state.sessionId != null &&
        state.phase !in setOf(SessionPhase.COMPLETED, SessionPhase.ENDING)

    val screenOpenedAt = rememberSaveable(state.sessionId) { System.currentTimeMillis() }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.sessionId, canEnd) {
        while (canEnd) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val callStartedAt = state.transcript.firstOrNull()?.timestampMillis ?: screenOpenedAt
    val elapsedSeconds = ((nowMillis - callStartedAt).coerceAtLeast(0L)) / 1_000

    // Keep the header in Scaffold's fixed top slot. The bottom input panel handles
    // navigation-bar and IME insets itself, so focusing the text field only reduces
    // the conversation area instead of moving the whole call screen.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CallScreenBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CallHeader(
                state = state,
                elapsedSeconds = elapsedSeconds,
                canEnd = canEnd,
                inputPanelVisible = showInputPanel,
                onEnd = viewModel::end,
                onToggleInput = { showInputPanel = !showInputPanel },
                onTakeover = viewModel::requestTakeover,
            )
        },
        bottomBar = {
            if (showInputPanel) {
                CallInputPanel(
                    inputMode = state.inputMode,
                    textInput = textInput,
                    canInput = canInput,
                    permissionDenied = permissionDenied,
                    presetSamples = viewModel.presetSamples.map { it.id to it.title },
                    onInputModeChange = viewModel::setInputMode,
                    onTextChange = { textInput = it },
                    onSubmitText = {
                        viewModel.submitText(textInput)
                        textInput = ""
                    },
                    onSubmitPreset = viewModel::submitPreset,
                    onCaptureMicrophone = {
                        permissionDenied = false
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.captureMicrophone()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        },
    ) { outerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding),
        ) {
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            ConversationArea(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun CallHeader(
    state: CallSessionSnapshot,
    elapsedSeconds: Long,
    canEnd: Boolean,
    inputPanelVisible: Boolean,
    onEnd: () -> Unit,
    onToggleInput: () -> Unit,
    onTakeover: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 14.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.callerDisplayName(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = "通话中 ${formatDuration(elapsedSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.phase.chineseLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                CallAction(
                    icon = Icons.Default.CallEnd,
                    label = "挂断",
                    backgroundColor = HangUpRed,
                    enabled = canEnd,
                    onClick = onEnd,
                )
                CallAction(
                    icon = state.inputMode.icon(),
                    label = if (inputPanelVisible) "收起" else "输入",
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = true,
                    onClick = onToggleInput,
                )
                CallAction(
                    icon = if (state.takeoverRequested) Icons.Default.Person else Icons.Default.Call,
                    label = if (state.takeoverRequested) "已请求" else "接管",
                    backgroundColor = TakeoverGreen,
                    enabled = state.callStatus == CallStatus.ACTIVE_AI,
                    onClick = onTakeover,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CallAction(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    contentColor: Color = Color.White,
) {
    Column(
        modifier = Modifier.width(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(50.dp),
            shape = CircleShape,
            color = if (enabled) backgroundColor else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onClick,
            enabled = enabled,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConversationArea(
    state: CallSessionSnapshot,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val noticeCount = listOfNotNull(
        state.lastError,
        if (state.takeoverRequested) "已请求机主接管" else null,
    ).size
    val itemCount = state.transcript.size + noticeCount

    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    LazyColumn(
        modifier = modifier.background(CallScreenBackground),
        state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.transcript.isEmpty() && state.lastError == null) {
            item {
                Text(
                    text = "小布通话助理正在接听，请稍候…",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        items(
            count = state.transcript.size,
            key = { index -> "${state.transcript[index].timestampMillis}-$index" },
        ) { index ->
            ConversationBubble(state.transcript[index])
        }

        state.lastError?.let { error ->
            item(key = "last_error") {
                SystemNotice(text = error, isError = true)
            }
        }

        if (state.takeoverRequested) {
            item(key = "takeover_requested") {
                SystemNotice(text = "已请求机主接管，请稍候…", isError = false)
            }
        }
    }
}

@Composable
private fun ConversationBubble(turn: TranscriptTurn) {
    if (turn.speaker == Speaker.SYSTEM) {
        SystemNotice(text = turn.text, isError = false)
        return
    }

    val isAssistant = turn.speaker == Speaker.ASSISTANT
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .align(if (isAssistant) Alignment.CenterEnd else Alignment.CenterStart),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isAssistant) 20.dp else 6.dp,
                bottomEnd = if (isAssistant) 6.dp else 20.dp,
            ),
            color = if (isAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (isAssistant) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shadowElevation = if (isAssistant) 0.dp else 1.dp,
        ) {
            Text(
                text = turn.text,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun SystemNotice(
    text: String,
    isError: Boolean,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CallInputPanel(
    inputMode: InputMode,
    textInput: String,
    canInput: Boolean,
    permissionDenied: Boolean,
    presetSamples: List<Pair<String, String>>,
    onInputModeChange: (InputMode) -> Unit,
    onTextChange: (String) -> Unit,
    onSubmitText: () -> Unit,
    onSubmitPreset: (String) -> Unit,
    onCaptureMicrophone: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // CALL_AUDIO is not offered: it is what a telephony call supplies, not something
                // the user can switch to while no call is up.
                InputMode.entries.filterNot { it == InputMode.CALL_AUDIO }.forEach { mode ->
                    FilterChip(
                        selected = inputMode == mode,
                        onClick = { onInputModeChange(mode) },
                        enabled = canInput,
                        label = { Text(mode.chineseLabel()) },
                        leadingIcon = {
                            Icon(
                                imageVector = mode.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            when (inputMode) {
                // The call supplies the audio and the turn loop drives itself; there is no manual
                // input control to offer while one is in progress.
                InputMode.CALL_AUDIO -> Unit
                InputMode.TEXT -> {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("caller_text_input"),
                        enabled = canInput,
                        placeholder = { Text("输入来电方发言") },
                        minLines = 1,
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = onSubmitText,
                                enabled = canInput && textInput.isNotBlank(),
                                modifier = Modifier.testTag("submit_caller_text"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                            }
                        },
                    )
                }

                InputMode.PRESET_AUDIO -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        presetSamples.forEach { (id, title) ->
                            AssistChip(
                                onClick = { onSubmitPreset(id) },
                                enabled = canInput,
                                label = { Text(title) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }

                InputMode.MICROPHONE -> {
                    OutlinedButton(
                        onClick = onCaptureMicrophone,
                        enabled = canInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Text("录制一轮（自动 VAD 截止）", Modifier.padding(start = 8.dp))
                    }
                    if (permissionDenied) {
                        Text(
                            text = "麦克风权限被拒绝，可切换到预设音频或文字输入。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun CallSessionSnapshot.callerDisplayName(): String {
    return structuredResult.callerIdentity?.takeIf { it.isNotBlank() }
        ?: callerName?.takeIf { it.isNotBlank() }
        ?: when (scene) {
            SceneType.DELIVERY -> "快递 / 外卖员"
            SceneType.RIDE_HAILING -> "网约车司机"
            SceneType.CUSTOMER_SERVICE -> "客服人员"
            SceneType.REAL_ESTATE -> "房产联系人"
            SceneType.INSURANCE_FINANCE -> "金融服务来电"
            SceneType.SPAM_RISK -> "风险来电"
            SceneType.WORK -> "工作联系人"
            SceneType.UNKNOWN_IDENTITY,
            SceneType.SALES,
            SceneType.UNCLASSIFIED -> "陌生来电"
        }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun InputMode.icon(): ImageVector = when (this) {
    InputMode.MICROPHONE -> Icons.Default.Mic
    InputMode.CALL_AUDIO -> Icons.Default.Call
    InputMode.PRESET_AUDIO -> Icons.Default.PlayCircle
    InputMode.TEXT -> Icons.Default.Keyboard
}

private fun InputMode.chineseLabel() = when (this) {
    InputMode.MICROPHONE -> "麦克风"
    InputMode.CALL_AUDIO -> "通话音频"
    InputMode.PRESET_AUDIO -> "预设"
    InputMode.TEXT -> "文字"
}

private fun SessionPhase.chineseLabel() = when (this) {
    SessionPhase.IDLE -> "空闲"
    SessionPhase.RINGING -> "响铃"
    SessionPhase.OPENING -> "准备开场白"
    SessionPhase.SPEAKING -> "AI 正在说话"
    SessionPhase.LISTENING -> "正在聆听"
    SessionPhase.RECORDING -> "正在录音"
    SessionPhase.RECOGNIZING -> "正在识别"
    SessionPhase.THINKING -> "规则决策中"
    SessionPhase.AWAITING_INPUT -> "等待来电方发言"
    SessionPhase.REQUESTING_TAKEOVER -> "请求机主接管"
    SessionPhase.ENDING -> "正在结束"
    SessionPhase.COMPLETED -> "已完成"
    SessionPhase.ERROR -> "异常"
}
