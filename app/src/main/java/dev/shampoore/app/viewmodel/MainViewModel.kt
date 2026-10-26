package dev.shampoore.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shampoore.app.data.ConfigStore
import dev.shampoore.app.data.VpnConfig
import dev.shampoore.app.vpn.VpnBridge
import dev.shampoore.app.vpn.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)

    val configs: StateFlow<List<VpnConfig>> = configStore.configs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val vpnState: StateFlow<VpnState> = VpnBridge.state

    private val _selectedConfig = MutableStateFlow<VpnConfig?>(null)
    val selectedConfig: StateFlow<VpnConfig?> = _selectedConfig

    init {
        viewModelScope.launch {
            val last = configStore.getSelectedSource() ?: return@launch
            val list = configStore.configs.first()
            _selectedConfig.value = list.find { it.source == last }
        }
        viewModelScope.launch {
            configStore.configs.collect { list ->
                val cur = _selectedConfig.value ?: return@collect
                if (list.none { it.source == cur.source }) {
                    _selectedConfig.value = null
                    configStore.setSelectedSource(null)
                }
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
}