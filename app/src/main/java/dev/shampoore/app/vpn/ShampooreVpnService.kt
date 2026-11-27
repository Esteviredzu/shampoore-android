package dev.shampoore.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.shampoore.app.MainActivity
import dev.shampoore.app.R
import dev.shampoore.app.data.AppRuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import mobile.SocketProtector
import mobile.StateListener

class ShampooreVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fdLock = Any()
    private val networkLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var stopping = false
    @Volatile private var cancelling = false
    @Volatile private var currentFd: Int = -1
    @Volatile private var fdHandedToGo: Boolean = false

    
    
    @Volatile private var sessionRunning = false
    @Volatile private var serviceDestroyed = false

    @Volatile private var callbackRegistered = false
    @Volatile private var networkDebounceJob: Job? = null
    private var lastTransport: Int = -1
    private var lastNetwork: Network? = null

    private val connectivityManager: ConnectivityManager? by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    
    
    
    private val networkRequest: NetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val transport = transportOf(network)
            synchronized(networkLock) {
                if (transport != 0) {
                    lastTransport = transport
                    lastNetwork = network
                }
            }
            scheduleNetworkChange()
        }

        override fun onLost(network: Network) {
            synchronized(networkLock) {
                if (lastNetwork == network) {
                    lastNetwork = null
                    lastTransport = -1
                }
            }
            scheduleNetworkChange()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val transport = transportFor(caps)
            if (transport == 0) return
            val changed = synchronized(networkLock) {
                val different = transport != lastTransport || lastNetwork != network
                lastTransport = transport
                lastNetwork = network
                different
            }
            if (changed) scheduleNetworkChange()
        }
    }

    
    
    
    
    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkChange()
        override fun onLost(network: Network) = scheduleNetworkChange()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            scheduleNetworkChange()
    }

    
    
    private val socketProtector = object : SocketProtector {
        override fun protect(fd: Long): Boolean = try {
            this@ShampooreVpnService.protect(fd.toInt())
        } catch (t: Throwable) {
            Log.w(TAG, "protect(fd=$fd) failed", t)
            false
        }
    }

    
    
    private val coreStateListener = object : StateListener {
        override fun onStateChanged(state: String?, message: String?) {
            val st = state ?: return
            val msg = message.orEmpty()
            mainHandler.post { handleCoreState(st, msg) }
        }
    }

    private fun handleCoreState(state: String, message: String) {
        if (serviceDestroyed) return
        
        
        
        val current = runCatching { Mobile.getState() }.getOrNull()
        if (current != null && current != state) {
            Log.d(TAG, "ignoring stale core state=$state current=$current")
            return
        }
        Log.i(TAG, "core state=$state message=$message")
        when (state) {
            Mobile.StateStarting -> {
                if (stopping) return
                VpnBridge.set(VpnState.Connecting)
                updateNotification("Connecting...")
            }
            Mobile.StateConnecting -> {
                if (stopping) return
                VpnBridge.set(VpnState.Connecting)
                updateNotification("Verifying connection...")
            }
            Mobile.StateRunning -> {
                if (stopping) return
                sessionRunning = true
                VpnBridge.set(VpnState.Connected)
                updateNotification("Connected")
            }
            Mobile.StateStopping -> {
                if (stopping) return
                VpnBridge.set(VpnState.Disconnecting)
                updateNotification("Disconnecting...")
            }
            Mobile.StateError -> {
                if (stopping) return
                val msg = message.ifBlank { DEFAULT_CORE_ERROR }
                
                
                stopVpn(errorMessage = msg)
            }
            Mobile.StateIdle -> {
                if (!sessionRunning || stopping) return
                stopVpn(errorMessage = message.ifBlank { "VPN core stopped unexpectedly" })
            }
        }
    }

    private fun transportFor(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
        else -> 0
    }

    private fun transportOf(network: Network): Int {
        val cm = connectivityManager ?: return 0
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return 0
        return transportFor(caps)
    }

    private fun scheduleNetworkChange() {
        if (stopping || cancelling || serviceDestroyed) return
        synchronized(networkLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = scope.launch {
                delay(NETWORK_CHANGE_DEBOUNCE_MS)
                if (stopping || cancelling || serviceDestroyed) return@launch
                runCatching { Mobile.notifyNetworkChanged() }
                    .onFailure { Log.w(TAG, "notifyNetworkChanged failed", it) }
            }
        }
    }

    private fun registerCallbackSafe() {
        if (callbackRegistered) return
        val cm = connectivityManager ?: run {
            Log.w(TAG, "ConnectivityManager unavailable")
            return
        }
        var registered = false
        runCatching { cm.registerNetworkCallback(networkRequest, networkCallback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
        runCatching { cm.registerDefaultNetworkCallback(defaultNetworkCallback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
        callbackRegistered = registered
    }

    private fun postUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val src = intent.getStringExtra(EXTRA_CONFIG) ?: run {
                    Log.e(TAG, "ACTION_CONNECT without EXTRA_CONFIG")
                    return START_NOT_STICKY
                }
                if (stopping) {
                    Log.w(TAG, "previous session is still stopping, ignoring connect")
                    return START_NOT_STICKY
                }
                val alreadyRunning = runCatching { Mobile.isRunning() }.getOrDefault(false)
                if (alreadyRunning) {
                    Log.w(TAG, "core is already running, ignoring duplicate connect")
                    return START_NOT_STICKY
                }
                cancelling = false
                stopping = false
                serviceDestroyed = false
                sessionRunning = false
                createNotificationChannel()
                try {
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground failed", t)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(src)
            }
            ACTION_DISCONNECT -> stopVpn()
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun startVpn(source: String) {
        scope.launch {
            try {
                Log.i(TAG, "startVpn begin source=${source.take(120)}")

                
                val rulesDir = java.io.File(filesDir, "rules").apply {
                    if (!exists()) mkdirs()
                }
                runCatching { Mobile.initRules(rulesDir.absolutePath) }

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

                
                
                
                
                val bypassPackages = runCatching {
                    AppRuleStore(this@ShampooreVpnService).getActiveBypassPackages()
                }.getOrElse {
                    Log.w(TAG, "failed to load per-app bypass rules", it)
                    emptySet()
                }
                if (bypassPackages.isNotEmpty()) {
                    Log.i(TAG, "per-app bypass: ${bypassPackages.size} package(s)")
                }
                for (pkg in bypassPackages) {
                    if (pkg == packageName) continue
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure {
                            Log.w(TAG, "addDisallowedApplication($pkg) failed", it)
                        }
                }

                Log.d(TAG, "establishing VPN interface")
                val pfd = builder.establish()
                if (pfd == null) {
                    Log.e(TAG, "builder.establish() returned null")
                    stopVpn(errorMessage = "Failed to create the TUN interface")
                    return@launch
                }

                val rawFd = try {
                    pfd.detachFd()
                } finally {
                    runCatching { pfd.close() }
                }
                Log.i(TAG, "VPN interface established fd=$rawFd")

                synchronized(fdLock) {
                    currentFd = rawFd
                    fdHandedToGo = false
                }

                if (cancelling) {
                    Log.w(TAG, "cancelled before Go start")
                    closeFdIfNotHanded()
                    return@launch
                }

                registerCallbackSafe()

                Log.i(TAG, "calling Mobile.startClientVPN")
                runCatching { Mobile.setSocketProtector(socketProtector) }
                    .onFailure { Log.w(TAG, "setSocketProtector failed", it) }
                
                
                runCatching { Mobile.setStateListener(coreStateListener) }
                    .onFailure { Log.w(TAG, "setStateListener failed", it) }

                if (cancelling) {
                    runCatching { Mobile.setStateListener(null) }
                    closeFdIfNotHanded()
                    return@launch
                }

                val err = Mobile.startClientVPN(source, rawFd.toLong(), 1500L)

                if (err.isEmpty()) {
                    Log.i(TAG, "Mobile.startClientVPN success")
                    
                    
                    synchronized(fdLock) {
                        fdHandedToGo = true
                        currentFd = -1
                    }
                    if (cancelling) {
                        runCatching { Mobile.stopClient() }
                    }
                } else {
                    Log.e(TAG, "Mobile.startClientVPN failed: $err")
                    
                    stopVpn(errorMessage = err)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startVpn failed", t)
                stopVpn(errorMessage = t.message ?: "Failed to start VPN")
            }
        }
    }

    private fun unregisterCallbackSafe() {
        if (!callbackRegistered) return
        callbackRegistered = false
        val cm = connectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { cm.unregisterNetworkCallback(defaultNetworkCallback) }
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

    private fun stopVpn(silent: Boolean = false, errorMessage: String? = null) {
        if (stopping) return
        stopping = true
        cancelling = true
        sessionRunning = false
        synchronized(networkLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = null
        }
        unregisterCallbackSafe()

        if (errorMessage != null) {
            postUi {
                VpnBridge.set(VpnState.Error(errorMessage))
                updateNotification("Error: $errorMessage")
            }
        } else if (!silent) {
            postUi { VpnBridge.set(VpnState.Disconnecting) }
        }

        scope.launch {
            runCatching { Mobile.stopClient() }
            runCatching { Mobile.clearSocketProtector() }
            runCatching { Mobile.setStateListener(null) }
            closeFdIfNotHanded()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (errorMessage == null) {
                    VpnBridge.set(VpnState.Disconnected)
                }
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
        serviceDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopping = true
        cancelling = true
        sessionRunning = false
        synchronized(networkLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = null
        }
        unregisterCallbackSafe()

        
        
        runCatching { Mobile.setStateListener(null) }
        closeFdIfNotHanded()

        
        Thread({
            runCatching { Mobile.stopClient() }
            runCatching { Mobile.clearSocketProtector() }
            runCatching { Mobile.setStateListener(null) }
        }, "shampoore-vpn-destroy").start()

        if (VpnBridge.state.value !is VpnState.Error) {
            VpnBridge.set(VpnState.Disconnected)
        }
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
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 400L
        private const val DEFAULT_CORE_ERROR = "VPN core error"
    }
}