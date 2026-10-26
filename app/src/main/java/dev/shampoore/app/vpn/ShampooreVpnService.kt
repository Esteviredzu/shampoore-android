package dev.shampoore.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.shampoore.app.MainActivity
import dev.shampoore.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile

class ShampooreVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fdLock = Any()

    @Volatile private var stopping = false
    @Volatile private var cancelling = false
    @Volatile private var currentFd: Int = -1
    @Volatile private var fdHandedToGo: Boolean = false

    @Volatile private var awaitingValidation = false
    @Volatile private var validated = false
    private var validationJob: Job? = null

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            if (validated) return

            validated = true
            if (awaitingValidation) {
                awaitingValidation = false
                validationJob?.cancel()
                validationJob = null
                scope.launch {
                    withContext(Dispatchers.Main) {
                        if (!cancelling) {
                            VpnBridge.set(VpnState.Connected)
                            updateNotification("Connected")
                        }
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            validated = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val src = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                cancelling = false
                stopping = false
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                startVpn(src)
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(source: String) {
        scope.launch {
            try {
                val builder = Builder()
                    .setSession("Shampoore")
                    .setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setBlocking(false)

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication failed", e)
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    stopVpn(silent = true)
                    return@launch
                }

                val rawFd = try {
                    pfd.detachFd()
                } finally {
                    runCatching { pfd.close() }
                }

                synchronized(fdLock) {
                    currentFd = rawFd
                    fdHandedToGo = false
                }

                if (cancelling) {
                    closeFdIfNotHanded()
                    return@launch
                }

                try {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback)
                } catch (t: Throwable) {
                    Log.w(TAG, "registerDefaultNetworkCallback failed", t)
                }

                val err = Mobile.startClientVPN(source, rawFd.toLong(), 1500L)

                if (err.isEmpty()) {
                    synchronized(fdLock) {
                        fdHandedToGo = true
                        currentFd = -1
                    }
                    if (cancelling) {
                        runCatching { Mobile.stopClient() }
                        return@launch
                    }
                    awaitingValidation = true
                    if (validated) {
                        awaitingValidation = false
                        withContext(Dispatchers.Main) {
                            VpnBridge.set(VpnState.Connected)
                            updateNotification("Connected")
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        updateNotification("Verifying...")
                    }
                    startValidationTimeout()
                } else {
                    closeFdIfNotHanded()
                    unregisterCallbackSafe()
                    stopVpn(silent = true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startVpn failed", t)
                closeFdIfNotHanded()
                unregisterCallbackSafe()
                stopVpn(silent = true)
            }
        }
    }

    private fun startValidationTimeout() {
        validationJob?.cancel()
        validationJob = scope.launch {
            delay(VALIDATION_TIMEOUT_MS)
            if (!awaitingValidation) return@launch
            awaitingValidation = false
            if (cancelling) return@launch
            unregisterCallbackSafe()
            stopVpn(silent = true)
        }
    }

    private fun unregisterCallbackSafe() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
    }

    private fun closeFdIfNotHanded() {
        synchronized(fdLock) {
            val fd = currentFd
            val owned = fdHandedToGo
            currentFd = -1
            fdHandedToGo = false
            if (fd >= 0 && !owned) {
                try {
                    ParcelFileDescriptor.adoptFd(fd).close()
                } catch (t: Throwable) {
                    Log.w(TAG, "closeFd($fd) failed", t)
                }
            }
        }
    }

    private fun stopVpn(silent: Boolean = false) {
        if (stopping) return
        stopping = true
        cancelling = true
        awaitingValidation = false
        validated = false
        validationJob?.cancel()
        validationJob = null
        unregisterCallbackSafe()

        if (!silent) {
            VpnBridge.set(VpnState.Disconnecting)
        }

        scope.launch {
            runCatching { Mobile.stopClient() }
            closeFdIfNotHanded()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                VpnBridge.set(VpnState.Disconnected)
                stopping = false
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        awaitingValidation = false
        validationJob?.cancel()
        unregisterCallbackSafe()
        closeFdIfNotHanded()
        runCatching { Mobile.stopClient() }
        VpnBridge.set(VpnState.Disconnected)
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Shampoore VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shampoore VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_CONNECT = "dev.shampoore.app.CONNECT"
        const val ACTION_DISCONNECT = "dev.shampoore.app.DISCONNECT"
        const val EXTRA_CONFIG = "extra_config"
        private const val TAG = "ShampooreVpn"
        private const val CHANNEL_ID = "shampoore_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val VALIDATION_TIMEOUT_MS = 30_000L
    }
}