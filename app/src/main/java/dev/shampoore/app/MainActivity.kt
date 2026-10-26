package dev.shampoore.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.shampoore.app.ui.ConnectScreen
import dev.shampoore.app.ui.theme.ShampooreTheme
import dev.shampoore.app.viewmodel.MainViewModel
import dev.shampoore.app.vpn.ShampooreVpnService
import dev.shampoore.app.vpn.VpnBridge
import dev.shampoore.app.vpn.VpnState

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
            ShampooreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConnectScreen(
                        viewModel = viewModel,
                        onConnect = ::requestVpnPermissionAndStart,
                        onDisconnect = ::stopVpn
                    )
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