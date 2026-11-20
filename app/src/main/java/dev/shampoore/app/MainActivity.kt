package dev.shampoore.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shampoore.app.data.AddConfigInput
import dev.shampoore.app.data.AddConfigInputClassifier
import dev.shampoore.app.data.VpnConfig
import dev.shampoore.app.ui.AddConfigChooserScreen
import dev.shampoore.app.ui.ConnectScreen
import dev.shampoore.app.ui.ConfigEditorScreen
import dev.shampoore.app.ui.RulesScreen
import dev.shampoore.app.ui.StatsScreen
import dev.shampoore.app.ui.theme.ShampooreTheme
import dev.shampoore.app.viewmodel.MainViewModel
import dev.shampoore.app.vpn.ShampooreVpnService
import dev.shampoore.app.vpn.VpnBridge
import dev.shampoore.app.vpn.VpnState

enum class AppScreen {
    MAIN, STATS, RULES, ADD_CHOOSER, EDITOR_ADD, EDITOR_EDIT
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingConfigSource: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val src = pendingConfigSource
        pendingConfigSource = null
        if (result.resultCode == Activity.RESULT_OK && src != null) {
            actuallyStartVpn(src)
        } else {
            VpnBridge.set(VpnState.Disconnected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val stats by viewModel.stats.collectAsStateWithLifecycle()
            val configs by viewModel.configs.collectAsStateWithLifecycle()

            var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }
            var editingConfig by remember { mutableStateOf<VpnConfig?>(null) }
            var prefilledSource by remember { mutableStateOf<String?>(null) }

            BackHandler(enabled = currentScreen != AppScreen.MAIN) {
                when (currentScreen) {
                    AppScreen.STATS        -> currentScreen = AppScreen.MAIN
                    AppScreen.RULES        -> currentScreen = AppScreen.MAIN
                    AppScreen.ADD_CHOOSER  -> currentScreen = AppScreen.MAIN
                    AppScreen.EDITOR_ADD   -> {
                        prefilledSource = null
                        currentScreen = AppScreen.ADD_CHOOSER
                    }
                    AppScreen.EDITOR_EDIT  -> currentScreen = AppScreen.MAIN
                    AppScreen.MAIN         -> Unit
                }
            }

            ShampooreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            AppScreen.MAIN -> ConnectScreen(
                                viewModel = viewModel,
                                onConnect = ::requestVpnPermissionAndStart,
                                onDisconnect = ::stopVpn,
                                onOpenStats = { currentScreen = AppScreen.STATS },
                                onOpenRules = { currentScreen = AppScreen.RULES },
                                onOpenAdd = {
                                    prefilledSource = null
                                    currentScreen = AppScreen.ADD_CHOOSER
                                },
                                onOpenEdit = { cfg ->
                                    editingConfig = cfg
                                    currentScreen = AppScreen.EDITOR_EDIT
                                }
                            )

                            AppScreen.STATS -> StatsScreen(
                                stats = stats,
                                onBack = { currentScreen = AppScreen.MAIN }
                            )

                            AppScreen.RULES -> {
                                val rulesList by viewModel.rules.collectAsStateWithLifecycle()
                                val rulesImporting by viewModel.rulesImporting.collectAsStateWithLifecycle()
                                val rulesRefreshing by viewModel.rulesRefreshing.collectAsStateWithLifecycle()
                                val rulesUpdating by viewModel.rulesUpdating.collectAsStateWithLifecycle()
                                val rulesMessage by viewModel.rulesMessage.collectAsStateWithLifecycle()
                                val appRules by viewModel.appRules.collectAsStateWithLifecycle()
                                val appRulesImporting by viewModel.appRulesImporting.collectAsStateWithLifecycle()
                                val appRulesRefreshing by viewModel.appRulesRefreshing.collectAsStateWithLifecycle()
                                val appRulesUpdating by viewModel.appRulesUpdating.collectAsStateWithLifecycle()
                                val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
                                val installedAppsLoading by viewModel.installedAppsLoading.collectAsStateWithLifecycle()
                                RulesScreen(
                                    rules = rulesList,
                                    importing = rulesImporting,
                                    refreshing = rulesRefreshing,
                                    updatingRule = rulesUpdating,
                                    message = rulesMessage,
                                    appRules = appRules,
                                    appRulesImporting = appRulesImporting,
                                    appRulesRefreshing = appRulesRefreshing,
                                    appRulesUpdating = appRulesUpdating,
                                    installedApps = installedApps,
                                    installedAppsLoading = installedAppsLoading,
                                    onBack = { currentScreen = AppScreen.MAIN },
                                    onToggleRule = { name, enabled -> viewModel.setRuleEnabled(name, enabled) },
                                    onDeleteRule = { name -> viewModel.deleteRule(name) },
                                    onImportFile = { uri, name -> viewModel.importRuleFile(uri, name) },
                                    onImportUrl = { url, name -> viewModel.importRuleFromUrl(url, name) },
                                    onRefreshRule = { name -> viewModel.refreshRule(name) },
                                    onRefresh = { viewModel.refreshRules() },
                                    onToggleAppRule = { name, enabled -> viewModel.setAppRuleEnabled(name, enabled) },
                                    onDeleteAppRule = { name -> viewModel.deleteAppRule(name) },
                                    onImportAppFile = { uri -> viewModel.importAppRuleFile(uri) },
                                    onImportAppUrl = { url, name -> viewModel.importAppRuleFromUrl(url, name) },
                                    onRefreshAppRule = { name -> viewModel.refreshAppRules(name) },
                                    onRefreshAppRules = { viewModel.refreshAppRules() },
                                    onSaveSelectedApps = { packages -> viewModel.saveCustomSelectedApps(packages) },
                                    onSetSelectedAppsEnabled = { enabled -> viewModel.setCustomSelectedAppsEnabled(enabled) },
                                    onLoadInstalledApps = { viewModel.loadInstalledApps() },
                                    onMessageShown = { viewModel.clearRulesMessage() }
                                )
                            }

                            AppScreen.ADD_CHOOSER -> AddConfigChooserScreen(
                                onBack = { currentScreen = AppScreen.MAIN },
                                onImportInput = { raw ->
                                    when (val input = AddConfigInputClassifier.classify(raw)) {
                                        is AddConfigInput.SubscriptionUrl -> {
                                            viewModel.addSubscription(input.url)
                                            currentScreen = AppScreen.MAIN
                                        }
                                        is AddConfigInput.OfflineBundle -> {
                                            viewModel.importOfflineBundle(input.raw)
                                            currentScreen = AppScreen.MAIN
                                        }
                                        is AddConfigInput.SingleConfig -> {
                                            viewModel.addConfig(input.source)
                                            currentScreen = AppScreen.MAIN
                                        }
                                        is AddConfigInput.Invalid -> Unit
                                    }
                                },
                                onManual = {
                                    prefilledSource = null
                                    currentScreen = AppScreen.EDITOR_ADD
                                }
                            )

                            AppScreen.EDITOR_ADD -> ConfigEditorScreen(
                                isEdit = false,
                                initialSource = prefilledSource,
                                onBack = {
                                    prefilledSource = null
                                    currentScreen = AppScreen.ADD_CHOOSER
                                },
                                onSave = { source ->
                                    viewModel.addConfig(source)
                                    prefilledSource = null
                                    currentScreen = AppScreen.MAIN
                                }
                            )

                            AppScreen.EDITOR_EDIT -> {
                                val cfg = editingConfig
                                if (cfg != null) {
                                    ConfigEditorScreen(
                                        isEdit = true,
                                        initialSource = cfg.source,
                                        onBack = { currentScreen = AppScreen.MAIN },
                                        onSave = { source ->
                                            viewModel.updateConfig(cfg, source)
                                            currentScreen = AppScreen.MAIN
                                        },
                                        onDelete = {
                                            viewModel.deleteConfig(cfg)
                                            currentScreen = AppScreen.MAIN
                                        }
                                    )
                                } else {
                                    currentScreen = AppScreen.MAIN
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestVpnPermissionAndStart(source: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConfigSource = source
            VpnBridge.set(VpnState.Connecting)
            vpnPermissionLauncher.launch(intent)
        } else {
            actuallyStartVpn(source)
        }
    }

    private fun actuallyStartVpn(source: String) {
        VpnBridge.set(VpnState.Connecting)
        val intent = Intent(this, ShampooreVpnService::class.java).apply {
            action = ShampooreVpnService.ACTION_CONNECT
            putExtra(ShampooreVpnService.EXTRA_CONFIG, source)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        VpnBridge.set(VpnState.Disconnecting)
        val intent = Intent(this, ShampooreVpnService::class.java).apply {
            action = ShampooreVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}