package com.example.calldelegate.feature.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.calldelegate.feature.main.screen.ActiveCallScreen
import com.example.calldelegate.feature.main.screen.HistoryScreen
import com.example.calldelegate.feature.main.screen.HomeScreen
import com.example.calldelegate.feature.main.screen.IncomingCallScreen
import com.example.calldelegate.feature.main.screen.ResultScreen
import com.example.calldelegate.feature.main.screen.SettingsScreen
import com.example.calldelegate.feature.main.ui.CallDelegateTheme
import com.example.calldelegate.feature.main.viewmodel.RootViewModel

private object Route {
    const val HOME = "home"
    const val INCOMING = "incoming"
    const val ACTIVE = "active"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val RESULT = "result/{recordId}"
    fun result(id: String) = "result/$id"
}

typealias AutomatedCallStarter = (onStarted: () -> Unit) -> Unit

@Composable
fun CallDelegateRoot(
    onStartAutomatedCall: AutomatedCallStarter? = null,
    openAutomatedCall: Boolean = false,
    onAutomatedCallOpened: () -> Unit = {},
    viewModel: RootViewModel = hiltViewModel(),
) {
    val scale by viewModel.fontScale.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale)) {
        CallDelegateTheme {
            val navController = rememberNavController()
            LaunchedEffect(openAutomatedCall) {
                if (openAutomatedCall) {
                    navController.navigate(Route.ACTIVE) { launchSingleTop = true }
                    onAutomatedCallOpened()
                }
            }
            // Without fillMaxSize, ActiveCallScreen only wraps its content height, so
            // Column weight(1f) collapses and the text field sits at the top above the keyboard.
            NavHost(
                navController = navController,
                startDestination = Route.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Route.HOME) {
                    HomeScreen(
                        onStartCall = { navController.navigate(Route.INCOMING) },
                        onHistory = { navController.navigate(Route.HISTORY) },
                        onSettings = { navController.navigate(Route.SETTINGS) },
                        onStartAutomatedCall = onStartAutomatedCall?.let { start ->
                            {
                                start {
                                    navController.navigate(Route.ACTIVE) { launchSingleTop = true }
                                }
                            }
                        },
                    )
                }
                composable(Route.INCOMING) {
                    IncomingCallScreen(
                        onBack = { navController.popBackStack() },
                        onAiAccepted = { navController.navigate(Route.ACTIVE) },
                    )
                }
                composable(Route.ACTIVE) {
                    ActiveCallScreen(
                        onBack = { navController.popBackStack(Route.HOME, false) },
                        onResult = { id ->
                            // Back to home, not back to the ringing screen, because a call can
                            // reach here without one: the automated route goes home -> active
                            // directly, so popping to INCOMING found nothing to pop and left the
                            // finished call on the stack. Going back from the result then landed on
                            // ActiveCallScreen, whose LaunchedEffect sees the same completed record
                            // and sends you straight back -- a back arrow that looked dead.
                            navController.navigate(Route.result(id)) {
                                popUpTo(Route.HOME) { inclusive = false }
                            }
                        },
                    )
                }
                composable(Route.HISTORY) {
                    HistoryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRecord = { navController.navigate(Route.result(it)) },
                    )
                }
                composable(Route.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
                composable(
                    route = Route.RESULT,
                    arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
                ) {
                    ResultScreen(
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack(Route.HOME, false) },
                    )
                }
            }
        }
    }
}
