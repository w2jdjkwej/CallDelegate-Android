package com.example.calldelegate.feature.main.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.calldelegate.feature.main.viewmodel.HomeUiState
import com.example.calldelegate.feature.main.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onStartCall: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onStartAutomatedCall: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreenContent(
        state = state,
        onStartCall = onStartCall,
        onHistory = onHistory,
        onSettings = onSettings,
        onStartAutomatedCall = onStartAutomatedCall,
    )
}

/**
 * History and settings, and nothing else.
 *
 * The two simulated-call buttons are gone. This application answers real calls by itself now, so a
 * button that stages a pretend one is a demo of a thing the product no longer does that way; the
 * simulated routes stay reachable in the graph for instrumentation, they are just not offered here.
 *
 * The module roster, the mode card and the standing explanations went earlier for their own reason:
 * they were read once and then sat in front of the actions on every later launch. The same facts
 * are still on the settings screen for anyone who wants them.
 */
@Composable
fun HomeScreenContent(
    @Suppress("UNUSED_PARAMETER") state: HomeUiState,
    @Suppress("UNUSED_PARAMETER") onStartCall: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onStartAutomatedCall: (() -> Unit)? = null,
) {
    Scaffold { padding ->
        // The gap under the last row, asked for as a tenth of the phone. Read from the screen
        // rather than written as a number, so it stays a tenth on a tall phone and on a short one.
        val bottomGap = (LocalConfiguration.current.screenHeightDp / 10).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = bottomGap),
            // Anchored to the bottom instead of stacked from the top: the actions then sit where a
            // thumb reaches them, and the gap below is the one thing measured against the screen.
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Text("历史记录", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text("设置", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
