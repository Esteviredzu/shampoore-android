package dev.shampoore.app.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shampoore.app.data.AppRuleItem
import dev.shampoore.app.data.AppRuleStore
import dev.shampoore.app.data.CUSTOM_APP_RULE_NAME
import dev.shampoore.app.data.ConfigStore
import dev.shampoore.app.data.InstalledAppInfo
import dev.shampoore.app.data.RemoteUpdateResult
import dev.shampoore.app.data.RuleItem
import dev.shampoore.app.data.RuleStore
import dev.shampoore.app.data.SubscriptionRecord
import dev.shampoore.app.data.SubscriptionRepository
import dev.shampoore.app.data.VpnConfig
import dev.shampoore.app.data.ruleSourceHost
import dev.shampoore.app.data.toVpnConfigs
import dev.shampoore.app.stats.StatsData
import dev.shampoore.app.vpn.VpnBridge
import dev.shampoore.app.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)
    private val ruleStore = RuleStore(application)
    private val appRuleStore = AppRuleStore(application)
    private val subscriptionRepository = SubscriptionRepository(application)

    val configs: StateFlow<List<VpnConfig>> = configStore.configs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val subscriptions: StateFlow<List<SubscriptionRecord>> = subscriptionRepository.subscriptions
    val subscriptionRefreshing: StateFlow<Set<String>> = subscriptionRepository.refreshing

    private val _subscriptionBusy = MutableStateFlow(false)
    val subscriptionBusy: StateFlow<Boolean> = _subscriptionBusy

    private val _subscriptionMessage = MutableStateFlow<String?>(null)
    val subscriptionMessage: StateFlow<String?> = _subscriptionMessage

    val vpnState: StateFlow<VpnState> = VpnBridge.state

    private val _selectedConfig = MutableStateFlow<VpnConfig?>(null)
    val selectedConfig: StateFlow<VpnConfig?> = _selectedConfig

    private val _stats = MutableStateFlow<StatsData?>(null)
    val stats: StateFlow<StatsData?> = _stats

    private val _rules = MutableStateFlow<List<RuleItem>>(emptyList())
    val rules: StateFlow<List<RuleItem>> = _rules

    private val _rulesImporting = MutableStateFlow(false)
    val rulesImporting: StateFlow<Boolean> = _rulesImporting

    private val _rulesRefreshing = MutableStateFlow(false)
    val rulesRefreshing: StateFlow<Boolean> = _rulesRefreshing

    private val _rulesUpdating = MutableStateFlow<String?>(null)
    val rulesUpdating: StateFlow<String?> = _rulesUpdating

    private val _rulesMessage = MutableStateFlow<String?>(null)
    val rulesMessage: StateFlow<String?> = _rulesMessage

    private val _appRules = MutableStateFlow<List<AppRuleItem>>(emptyList())
    val appRules: StateFlow<List<AppRuleItem>> = _appRules

    private val _appRulesImporting = MutableStateFlow(false)
    val appRulesImporting: StateFlow<Boolean> = _appRulesImporting

    private val _appRulesRefreshing = MutableStateFlow(false)
    val appRulesRefreshing: StateFlow<Boolean> = _appRulesRefreshing

    private val _appRulesUpdating = MutableStateFlow<String?>(null)
    val appRulesUpdating: StateFlow<String?> = _appRulesUpdating

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps

    private val _installedAppsLoading = MutableStateFlow(false)
    val installedAppsLoading: StateFlow<Boolean> = _installedAppsLoading

    init {
        viewModelScope.launch {
            subscriptionRepository.reload()
            restoreSelectedConfig()
            
            subscriptionRepository.refreshStale()
        }
        viewModelScope.launch {
            configStore.configs.collect { validateSelection() }
        }
        viewModelScope.launch {
            subscriptionRepository.subscriptions.collect { validateSelection() }
        }
        loadRules()
        loadAppRules()
        startStatsPolling()
    }

    private suspend fun restoreSelectedConfig() {
        val last = configStore.getSelectedSource() ?: return
        val available = allConfigs()
        _selectedConfig.value = available.firstOrNull { it.source == last }
        if (_selectedConfig.value == null) configStore.setSelectedSource(null)
    }

    private suspend fun validateSelection() {
        val current = _selectedConfig.value ?: return
        val stillExists = allConfigs().any {
            it.source == current.source && it.subscriptionId == current.subscriptionId
        }
        if (!stillExists) {
            _selectedConfig.value = null
            configStore.setSelectedSource(null)
        }
    }

    private suspend fun allConfigs(): List<VpnConfig> =
        configStore.configs.first() +
            subscriptionRepository.subscriptions.value.flatMap { it.toVpnConfigs() }

    fun loadRules() {
        viewModelScope.launch {
            _rules.value = ruleStore.getRulesList()
        }
    }

    



    fun refreshRules() {
        if (_rulesRefreshing.value || _rulesImporting.value) return
        _rulesRefreshing.value = true
        viewModelScope.launch {
            try {
                val update = ruleStore.updateRemoteRules()
                val reloadError = ruleStore.reloadRulesFromDisk()
                _rules.value = ruleStore.getRulesList()
                _rulesMessage.value = update.error
                    ?: updateFailuresMessage(update)
                    ?: reloadError
                    ?: update.updated.takeIf { it.isNotEmpty() }
                        ?.let { "Updated ${it.size} linked rule(s)" }
            } finally {
                _rulesRefreshing.value = false
            }
        }
    }

    
    fun refreshRule(name: String) {
        if (_rulesRefreshing.value || _rulesImporting.value || _rulesUpdating.value != null) return
        _rulesUpdating.value = name
        viewModelScope.launch {
            try {
                val result = ruleStore.updateRemoteRules(name)
                _rules.value = ruleStore.getRulesList()
                _rulesMessage.value = result.error
                    ?: result.failures.firstOrNull()
                    ?: "Updated \"$name\""
            } finally {
                _rulesUpdating.value = null
            }
        }
    }

    private fun updateFailuresMessage(result: RemoteUpdateResult): String? {
        if (result.failures.isEmpty()) return null
        val total = result.updated.size + result.failures.size
        val shown = result.failures.take(2).joinToString("; ")
        val extra = if (result.failures.size > 2) " (+${result.failures.size - 2} more)" else ""
        return "Updated ${result.updated.size} of $total linked rule(s). $shown$extra"
    }

    fun setRuleEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            ruleStore.setRuleEnabled(name, enabled)?.let { _rulesMessage.value = it }
            loadRules()
        }
    }

    fun deleteRule(name: String) {
        viewModelScope.launch {
            ruleStore.deleteRule(name)?.let { _rulesMessage.value = it }
            loadRules()
        }
    }

    fun importRuleFile(uri: Uri, displayName: String?) {
        if (_rulesImporting.value) return
        _rulesImporting.value = true
        viewModelScope.launch {
            try {
                val result = ruleStore.importRuleFile(uri, displayName)
                loadRules()
                _rulesMessage.value = result.error
                    ?: result.fileName?.let { "Imported $it" }
                    ?: "Rule file imported"
            } finally {
                _rulesImporting.value = false
            }
        }
    }

    fun importRuleFromUrl(url: String, name: String?) {
        if (_rulesImporting.value || _rulesRefreshing.value) return
        _rulesImporting.value = true
        viewModelScope.launch {
            try {
                val result = ruleStore.importRuleFromUrl(url, name)
                loadRules()
                _rulesMessage.value = result.error
                    ?: result.fileName?.let { fileName ->
                        val host = result.sourceUrl?.let { ruleSourceHost(it) }
                        if (host != null) "Imported $fileName from $host" else "Imported $fileName"
                    }
                    ?: "Rule imported from link"
            } finally {
                _rulesImporting.value = false
            }
        }
    }

    fun loadAppRules() {
        viewModelScope.launch { loadAppRulesNow() }
    }

    private suspend fun loadAppRulesNow() {
        _appRules.value = appRuleStore.getAppRules()
    }

    




    fun loadInstalledApps(force: Boolean = false) {
        if (_installedAppsLoading.value) return
        if (!force && _installedApps.value.isNotEmpty()) return
        _installedAppsLoading.value = true
        viewModelScope.launch {
            try {
                _installedApps.value = withContext(Dispatchers.IO) { queryInstalledApps() }
            } finally {
                _installedAppsLoading.value = false
            }
        }
    }

    private fun queryInstalledApps(): List<InstalledAppInfo> {
        val app = getApplication<Application>()
        val packageManager = app.packageManager
        val self = app.packageName
        @Suppress("DEPRECATION")
        val infos = runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        return infos.asSequence()
            .filter { it.packageName != self }
            .map { info ->
                val label = runCatching {
                    packageManager.getApplicationLabel(info).toString()
                }.getOrNull()
                InstalledAppInfo(
                    packageName = info.packageName,
                    label = label?.takeIf { it.isNotBlank() } ?: info.packageName,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(
                compareBy<InstalledAppInfo> { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    fun importAppRuleFile(uri: Uri) {
        if (_appRulesImporting.value) return
        _appRulesImporting.value = true
        viewModelScope.launch {
            try {
                val result = appRuleStore.importFromFile(uri)
                loadAppRulesNow()
                _rulesMessage.value = result.error ?: appRuleSuccessMessage(
                    result.ruleName?.let {
                        "Imported \"$it\" (${appCount(result.packageCount)})"
                    } ?: "App rule imported"
                )
            } finally {
                _appRulesImporting.value = false
            }
        }
    }

    fun importAppRuleFromUrl(url: String, name: String?) {
        if (_appRulesImporting.value || _appRulesRefreshing.value) return
        _appRulesImporting.value = true
        viewModelScope.launch {
            try {
                val result = appRuleStore.importFromUrl(url, name)
                loadAppRulesNow()
                _rulesMessage.value = result.error ?: appRuleSuccessMessage(
                    result.ruleName?.let { ruleName ->
                        val host = result.sourceUrl?.let { ruleSourceHost(it) }
                        if (host != null) {
                            "Imported \"$ruleName\" from $host (${appCount(result.packageCount)})"
                        } else {
                            "Imported \"$ruleName\" (${appCount(result.packageCount)})"
                        }
                    } ?: "App rule imported from link"
                )
            } finally {
                _appRulesImporting.value = false
            }
        }
    }

    fun refreshAppRules(ruleName: String? = null) {
        if (_appRulesRefreshing.value || _appRulesImporting.value) return
        if (ruleName != null && _appRulesUpdating.value != null) return
        if (ruleName != null) {
            _appRulesUpdating.value = ruleName
        } else {
            _appRulesRefreshing.value = true
        }
        viewModelScope.launch {
            try {
                val result = appRuleStore.refreshRemoteRules(ruleName)
                loadAppRulesNow()
                val failed = result.error != null || result.failures.isNotEmpty()
                val base = result.error
                    ?: updateFailuresMessage(result)
                    ?: if (result.updated.isNotEmpty()) {
                        "Updated ${result.updated.size} app rule(s)"
                    } else {
                        "No linked app rules to update"
                    }
                _rulesMessage.value = if (failed) base else appRuleSuccessMessage(base)
            } finally {
                _appRulesUpdating.value = null
                _appRulesRefreshing.value = false
            }
        }
    }

    fun setAppRuleEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val error = appRuleStore.setRuleEnabled(name, enabled)
            loadAppRulesNow()
            _rulesMessage.value = error ?: appRuleSuccessMessage(
                if (enabled) "\"$name\" enabled" else "\"$name\" disabled"
            )
        }
    }

    fun deleteAppRule(name: String) {
        viewModelScope.launch {
            val error = appRuleStore.deleteRule(name)
            loadAppRulesNow()
            _rulesMessage.value = error ?: appRuleSuccessMessage("Deleted \"$name\"")
        }
    }

    fun saveCustomSelectedApps(packages: Set<String>) {
        viewModelScope.launch {
            val result = appRuleStore.saveCustomSelectedPackages(packages, enabled = null)
            loadAppRulesNow()
            _rulesMessage.value = result.error ?: appRuleSuccessMessage(
                if (packages.isEmpty()) {
                    "No apps selected for bypass"
                } else {
                    "Selected ${appCount(packages.size)} for bypass"
                }
            )
        }
    }

    fun setCustomSelectedAppsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val error = appRuleStore.setRuleEnabled(CUSTOM_APP_RULE_NAME, enabled)
            loadAppRulesNow()
            _rulesMessage.value = error ?: appRuleSuccessMessage(
                if (enabled) "Selected apps enabled" else "Selected apps disabled"
            )
        }
    }

    private fun appRuleSuccessMessage(message: String): String =
        if (vpnState.value is VpnState.Connected) {
            "$message. Reconnect the VPN to apply the change"
        } else {
            message
        }

    private fun appCount(count: Int): String =
        if (count == 1) "1 app" else "$count apps"

    fun clearRulesMessage() {
        _rulesMessage.value = null
    }

    private fun startStatsPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val connected = VpnBridge.state.value is VpnState.Connected
                if (connected) {
                    try {
                        val raw = Mobile.getStats()
                        _stats.value = StatsData.fromJson(raw)
                    } catch (_: Throwable) {
                        _stats.value = null
                    }
                } else {
                    _stats.value = null
                }
                delay(1000L)
            }
        }
    }

    fun selectConfig(config: VpnConfig) {
        _selectedConfig.value = config
        viewModelScope.launch { configStore.setSelectedSource(config.source) }
    }

    fun addConfig(source: String) {
        viewModelScope.launch {
            val cfg = configStore.addConfigFromSource(source)
            _selectedConfig.value = cfg
            _subscriptionMessage.value = "Added \"${cfg.name}\""
        }
    }

    fun deleteConfig(config: VpnConfig) {
        viewModelScope.launch {
            configStore.deleteConfig(config.source)
            if (_selectedConfig.value?.source == config.source) {
                _selectedConfig.value = null
            }
        }
    }

    fun updateConfig(oldConfig: VpnConfig, newSource: String) {
        viewModelScope.launch {
            val cfg = configStore.updateConfig(oldConfig.source, newSource)
            if (_selectedConfig.value?.source == oldConfig.source) {
                _selectedConfig.value = cfg
            }
        }
    }

    fun addSubscription(url: String) {
        if (_subscriptionBusy.value) return
        _subscriptionBusy.value = true
        viewModelScope.launch {
            try {
                val result = subscriptionRepository.importSubscription(url)
                _subscriptionMessage.value = result.error
                    ?: "Subscription added: ${result.subscription?.name ?: "subscription"} " +
                    "(${routeCount(result.updated)})"
            } finally {
                _subscriptionBusy.value = false
            }
        }
    }

    
    fun importOfflineBundle(raw: String) {
        if (_subscriptionBusy.value) return
        _subscriptionBusy.value = true
        viewModelScope.launch {
            try {
                val result = subscriptionRepository.importOfflineBundle(raw)
                _subscriptionMessage.value = result.error
                    ?: "Imported \"${result.subscription?.name ?: "group"}\" " +
                    "(${routeCount(result.updated)})"
            } finally {
                _subscriptionBusy.value = false
            }
        }
    }

    fun refreshSubscription(id: String) {
        if (id in subscriptionRefreshing.value) return
        viewModelScope.launch {
            val result = subscriptionRepository.refreshSubscription(id)
            val name = result.subscription?.name
            _subscriptionMessage.value = if (result.error != null) {
                if (name != null) "\"$name\": ${result.error}" else result.error
            } else {
                "Updated ${name ?: "subscription"} (${routeCount(result.updated)})"
            }
        }
    }

    fun refreshAllSubscriptions() {
        viewModelScope.launch {
            val results = subscriptionRepository.refreshAll()
            if (results.isEmpty()) {
                _subscriptionMessage.value = "No network subscriptions to refresh"
                return@launch
            }
            val failed = results.count { it.error != null }
            _subscriptionMessage.value = if (failed == 0) {
                "Updated ${results.size} subscription(s)"
            } else {
                val firstError = results.firstOrNull { it.error != null }?.error
                "Updated ${results.size - failed} of ${results.size} subscription(s)" +
                    (firstError?.let { ": $it" } ?: "")
            }
        }
    }

    
    fun refreshStaleSubscriptions() {
        viewModelScope.launch {
            subscriptionRepository.refreshStale()
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            val removed = subscriptionRepository.deleteSubscription(id)
            if (removed) {
                if (_selectedConfig.value?.subscriptionId == id) {
                    _selectedConfig.value = null
                    configStore.setSelectedSource(null)
                }
                _subscriptionMessage.value = "Subscription deleted"
            } else {
                _subscriptionMessage.value = "Subscription not found"
            }
        }
    }

    fun clearSubscriptionMessage() {
        _subscriptionMessage.value = null
    }

    private fun routeCount(count: Int): String =
        if (count == 1) "1 route" else "$count routes"
}
