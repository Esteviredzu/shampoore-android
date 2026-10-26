package dev.shampoore.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data object Connected : VpnState
    data object Disconnecting : VpnState
}

object VpnBridge {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state

    fun set(s: VpnState) {
        _state.value = s
    }
}