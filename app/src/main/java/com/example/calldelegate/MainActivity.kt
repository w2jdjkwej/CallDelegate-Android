package com.example.calldelegate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.feature.main.CallDelegateRoot
import com.example.calldelegate.feature.main.ui.CallDelegateTheme
import android.widget.Toast
import com.example.calldelegate.core.ai.coordination.ExternalCallCoordinator
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.telecom.CallNotifier
import com.example.calldelegate.telecom.CallSessionService
import com.example.calldelegate.telecom.DialerRoleManager
import com.example.calldelegate.telecom.DialerSetupBanner
import com.example.calldelegate.telecom.FullScreenIntentBanner
import com.example.calldelegate.telecom.shouldReleaseOnBackground
import com.example.calldelegate.telecom.shouldClearStaleForegroundNotification
import com.example.calldelegate.telecom.recording.EmbeddedShizukuInstaller
import com.example.calldelegate.telecom.recording.ShizukuOnboardingDialog
import com.example.calldelegate.telecom.recording.ShizukuOnboardingEnvironment
import com.example.calldelegate.telecom.recording.ShizukuOnboardingStateResolver
import com.example.calldelegate.telecom.recording.ShizukuOnboardingStep
import com.example.calldelegate.telecom.recording.ShizukuSetupState
import com.example.calldelegate.telecom.recording.ShizukuStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessionController: CallSessionController
    @Inject lateinit var callCoordinator: ExternalCallCoordinator
    @Inject lateinit var settingsRepository: SettingsRepository

    private val isDefaultDialer = mutableStateOf(false)
    private val canUseFullScreenIntent = mutableStateOf(true)
    private val notificationPermissionDenied = mutableStateOf(false)
    private val recordAudioSettingsRequired = mutableStateOf(false)
    private val openAutomatedCall = mutableStateOf(false)
    private val carrierRecordingEnabled = mutableStateOf(false)
    private val shizukuSetupState = mutableStateOf(ShizukuSetupState.NOT_RUNNING)
    private val shizukuOnboardingVisible = mutableStateOf(false)
    private val shizukuOnboardingStep = mutableStateOf(ShizukuOnboardingStep.INSTALL_MANAGER)
    private val automatedCallStartGate = AutomatedCallStartGate()
    private lateinit var embeddedShizukuInstaller: EmbeddedShizukuInstaller
    private var pendingAutomatedCallStarted: (() -> Unit)? = null
    private var installManagerAfterPermission = false
    private var onboardingDeferredForSession = false
    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefaultDialer.value = DialerRoleManager.isDefaultDialer(this)
        refreshShizukuOnboarding()
    }

    private val managerInstallPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val shouldInstall = installManagerAfterPermission
        installManagerAfterPermission = false
        if (shouldInstall &&
            embeddedShizukuInstaller.canInstallPackages() &&
            !embeddedShizukuInstaller.isManagerInstalled()
        ) {
            launchEmbeddedShizukuInstaller()
        } else {
            refreshShizukuOnboarding()
        }
    }

    private val managerInstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshShizukuState()
        if (embeddedShizukuInstaller.isManagerInstalled()) {
            Toast.makeText(this, "Shizuku 已安装，请继续启动服务", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationPermissionDenied.value = !granted }

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = automatedCallStartGate.onPermissionResult(
            granted = granted,
            canAskAgain = granted || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
        )
        handleAutomatedCallStartAction(action)
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                runOnUiThread {
                    refreshShizukuState()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG && "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            setContent {
                CallDelegateTheme {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "此版本仅支持 ARM64-v8a 设备。",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            return
        }
        embeddedShizukuInstaller = EmbeddedShizukuInstaller(this)
        shizukuOnboardingVisible.value = !isShizukuOnboardingComplete()
        isDefaultDialer.value = DialerRoleManager.isDefaultDialer(this)
        canUseFullScreenIntent.value = CallNotifier.canUseFullScreenIntent(this)
        openAutomatedCall.value = intent?.getBooleanExtra(EXTRA_OPEN_AUTOMATED_CALL, false) == true
        CallNotifier.ensureChannel(this)
        if (shouldClearStaleForegroundNotification(
                serviceManaged = callCoordinator.state.value != null,
                status = sessionController.state.value.callStatus,
            )
        ) {
            CallNotifier.clearForeground(this)
        }
        maybeRequestNotificationPermission()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        refreshShizukuState()
        lifecycleScope.launch {
            sessionController.state.collect { snapshot ->
                automatedCallStartGate.onSessionStatus(snapshot.callStatus)
            }
        }
        lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                carrierRecordingEnabled.value = settings.carrierCallRecordingEnabled
            }
        }
        setContent {
            CallDelegateTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        val default by isDefaultDialer
                        val fsi by canUseFullScreenIntent
                        if (!default) {
                            DialerSetupBanner(onRequest = {
                                DialerRoleManager.createRequestIntent(this@MainActivity)
                                    ?.let { roleLauncher.launch(it) }
                            })
                        } else if (!fsi) {
                            FullScreenIntentBanner(onRequest = {
                                CallNotifier.fullScreenIntentSettings(this@MainActivity)
                                    ?.let { startActivity(it) }
                            })
                        }
                        val audioSettingsRequired by recordAudioSettingsRequired
                        if (audioSettingsRequired) {
                            PermissionSettingsBanner(
                                message = "麦克风权限已被永久拒绝，请在系统设置中允许后再启动自动接听。",
                                onOpenSettings = ::openAppSettings,
                            )
                        }
                        val notificationsDenied by notificationPermissionDenied
                        if (notificationsDenied) {
                            PermissionSettingsBanner(
                                message = "通知权限未授予。自动会话仍可启动，但常驻通知可能不会显示在通知栏。",
                                onOpenSettings = ::openNotificationSettings,
                            )
                        }
                        val recordingEnabled by carrierRecordingEnabled
                        val session by sessionController.state.collectAsState()
                        val activeCallVisible = session.callStatus in setOf(
                            CallStatus.ACTIVE_AI,
                            CallStatus.REQUESTING_TAKEOVER,
                        )
                        val setupState by shizukuSetupState
                        // READY is not shown. It reports that nothing needs doing, which is the one
                        // thing nobody needs told on every launch, and it pushed the actions down
                        // the screen to say it. The states that still carry a setup action stay.
                        if (recordingEnabled && !activeCallVisible && setupState != ShizukuSetupState.READY) {
                            ShizukuSetupBanner(
                                state = setupState,
                                onAction = ::handleShizukuSetup,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            val shouldOpenAutomatedCall by openAutomatedCall
                            CallDelegateRoot(
                                onStartAutomatedCall = ::startAutomatedSimulatedCall,
                                openAutomatedCall = shouldOpenAutomatedCall,
                                onAutomatedCallOpened = { openAutomatedCall.value = false },
                            )
                        }
                    }
                    val onboardingVisible by shizukuOnboardingVisible
                    if (onboardingVisible) {
                        val onboardingStep by shizukuOnboardingStep
                        ShizukuOnboardingDialog(
                            step = onboardingStep,
                            onContinue = ::handleShizukuOnboarding,
                            onLater = ::deferShizukuOnboarding,
                        )
                    }
                }
            }
        }
    }

    /** Auto-answer entry: start the foreground-service-owned automated SIMULATED AI call. */
    private fun startAutomatedSimulatedCall(onStarted: () -> Unit) {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val action = automatedCallStartGate.onStartRequested(granted)
        if (action == AutomatedCallStartAction.REQUEST_PERMISSION ||
            action == AutomatedCallStartAction.START_CALL
        ) {
            pendingAutomatedCallStarted = onStarted
        }
        handleAutomatedCallStartAction(action)
    }

    private fun handleAutomatedCallStartAction(action: AutomatedCallStartAction) {
        when (action) {
            AutomatedCallStartAction.NONE -> Unit
            AutomatedCallStartAction.REQUEST_PERMISSION -> {
                recordAudioSettingsRequired.value = false
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            AutomatedCallStartAction.START_CALL -> {
                recordAudioSettingsRequired.value = false
                launchAutomatedSimulatedCall()
                pendingAutomatedCallStarted?.invoke()
                pendingAutomatedCallStarted = null
            }
            AutomatedCallStartAction.SHOW_PERMISSION_DENIED -> {
                pendingAutomatedCallStarted = null
                Toast.makeText(this, "需要麦克风权限才能进行 AI 自动接听", Toast.LENGTH_SHORT).show()
            }
            AutomatedCallStartAction.SHOW_APP_SETTINGS -> {
                pendingAutomatedCallStarted = null
                recordAudioSettingsRequired.value = true
                Toast.makeText(this, "请在系统设置中允许麦克风权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchAutomatedSimulatedCall() {
        CallSessionService.startAutomated(this, CallTransport.SIMULATED, callerNumber = "138 •••• 9527")
        Toast.makeText(this, "已启动 AI 自动接听（模拟），可从通知栏返回", Toast.LENGTH_SHORT).show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        isDefaultDialer.value = DialerRoleManager.isDefaultDialer(this)
        canUseFullScreenIntent.value = CallNotifier.canUseFullScreenIntent(this)
        refreshShizukuState()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_AUTOMATED_CALL, false)) {
            openAutomatedCall.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        // A coordinator-managed call is owned by the foreground CallSessionService, which legitimately
        // keeps the mic/session alive in the background. Only the legacy in-UI simulated demo (no
        // coordinator, no foreground service) releases audio here to avoid recording off-screen.
        val serviceManaged = callCoordinator.state.value != null
        if (shouldReleaseOnBackground(
                isChangingConfigurations = isChangingConfigurations,
                serviceManaged = serviceManaged,
                status = sessionController.state.value.callStatus,
            )
        ) {
            lifecycleScope.launch { sessionController.end("应用进入后台，已安全释放音频资源") }
        }
    }

    companion object {
        const val EXTRA_OPEN_AUTOMATED_CALL = "open_automated_call"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4618
        private const val SHIZUKU_PERMISSION_DIALOG_DELAY_MS = 250L
        private const val ONBOARDING_PREFERENCES = "shizuku_onboarding"
        private const val ONBOARDING_COMPLETE = "complete"
    }

    private fun refreshShizukuState() {
        shizukuSetupState.value = ShizukuStatus.current()
        refreshShizukuOnboarding()
    }

    private fun refreshShizukuOnboarding() {
        if (!::embeddedShizukuInstaller.isInitialized) return
        shizukuOnboardingStep.value = ShizukuOnboardingStateResolver.resolve(
            ShizukuOnboardingEnvironment(
                managerInstalled = embeddedShizukuInstaller.isManagerInstalled(),
                canInstallPackages = embeddedShizukuInstaller.canInstallPackages(),
                shizukuState = shizukuSetupState.value,
                isDefaultDialer = isDefaultDialer.value,
            ),
        )
        if (!isShizukuOnboardingComplete() && !onboardingDeferredForSession) {
            shizukuOnboardingVisible.value = true
        }
    }

    private fun handleShizukuOnboarding() {
        when (shizukuOnboardingStep.value) {
            ShizukuOnboardingStep.ALLOW_MANAGER_INSTALL -> {
                installManagerAfterPermission = true
                managerInstallPermissionLauncher.launch(
                    embeddedShizukuInstaller.createInstallPermissionIntent(),
                )
            }
            ShizukuOnboardingStep.INSTALL_MANAGER -> launchEmbeddedShizukuInstaller()
            ShizukuOnboardingStep.START_SHIZUKU -> openShizukuManager()
            ShizukuOnboardingStep.GRANT_SHIZUKU_PERMISSION -> requestShizukuPermission()
            ShizukuOnboardingStep.REQUEST_DEFAULT_DIALER -> {
                DialerRoleManager.createRequestIntent(this)?.let(roleLauncher::launch)
                    ?: Toast.makeText(this, "无法打开默认电话应用设置", Toast.LENGTH_SHORT).show()
            }
            ShizukuOnboardingStep.COMPLETE -> completeShizukuOnboarding()
        }
    }

    private fun launchEmbeddedShizukuInstaller() {
        runCatching {
            embeddedShizukuInstaller.createManagerInstallIntent()
        }.onSuccess(managerInstallLauncher::launch)
            .onFailure { error ->
                Toast.makeText(
                    this,
                    error.message ?: "无法准备内置 Shizuku 安装包",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun openShizukuManager() {
        val intent = ShizukuStatus.managerLaunchIntent(this)
        if (intent != null) {
            startActivity(intent)
        } else {
            showShizukuOnboarding()
            Toast.makeText(this, "Shizuku 尚未安装", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizukuPermission() {
        val requestedFromOnboarding = shizukuOnboardingVisible.value
        if (requestedFromOnboarding) {
            // Shizuku uses a translucent Activity for its permission prompt. On MIUI, launching it
            // while our Compose dialog window is still visible can immediately hide the prompt.
            shizukuOnboardingVisible.value = false
        }

        lifecycleScope.launch {
            if (requestedFromOnboarding) {
                delay(SHIZUKU_PERMISSION_DIALOG_DELAY_MS)
            }
            if (!ShizukuStatus.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)) {
                if (requestedFromOnboarding) {
                    showShizukuOnboarding()
                }
                Toast.makeText(
                    this@MainActivity,
                    "无法发起 Shizuku 授权",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun completeShizukuOnboarding() {
        lifecycleScope.launch {
            when (val result = settingsRepository.update {
                it.copy(carrierCallRecordingEnabled = true)
            }) {
                is AppResult.Success -> {
                    getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putBoolean(ONBOARDING_COMPLETE, true)
                        .apply()
                    shizukuOnboardingVisible.value = false
                    Toast.makeText(
                        this@MainActivity,
                        "真实 SIM 通话录音已开启",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is AppResult.Failure -> {
                    Toast.makeText(
                        this@MainActivity,
                        result.error.userMessage,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun deferShizukuOnboarding() {
        onboardingDeferredForSession = true
        shizukuOnboardingVisible.value = false
    }

    private fun showShizukuOnboarding() {
        onboardingDeferredForSession = false
        shizukuOnboardingVisible.value = true
        refreshShizukuOnboarding()
    }

    private fun isShizukuOnboardingComplete(): Boolean {
        return getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
            .getBoolean(ONBOARDING_COMPLETE, false)
    }

    private fun handleShizukuSetup() {
        when (ShizukuStatus.current()) {
            ShizukuSetupState.NOT_RUNNING -> {
                if (embeddedShizukuInstaller.isManagerInstalled()) {
                    openShizukuManager()
                } else {
                    showShizukuOnboarding()
                }
            }
            ShizukuSetupState.PERMISSION_REQUIRED -> requestShizukuPermission()
            ShizukuSetupState.READY -> Unit
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionSettingsBanner(message: String, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("打开系统设置")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ShizukuSetupBanner(
    state: ShizukuSetupState,
    onAction: () -> Unit,
) {
    val ready = state == ShizukuSetupState.READY
    Surface(
        color = if (ready) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                when (state) {
                    ShizukuSetupState.NOT_RUNNING -> "真实通话录音已开启，但 Shizuku 尚未运行。"
                    ShizukuSetupState.PERMISSION_REQUIRED -> "真实通话录音需要授予 Shizuku 权限。"
                    ShizukuSetupState.READY -> "Shizuku 已就绪；真实通话接通后将自动录音。"
                },
                color = if (ready) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
            if (!ready) {
                OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        if (state == ShizukuSetupState.NOT_RUNNING) {
                            "配置 Shizuku"
                        } else {
                            "授予权限"
                        },
                    )
                }
            }
        }
    }
}
