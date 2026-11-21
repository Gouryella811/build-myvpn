package com.buildmyvpn.vpn

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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Android VpnService hosting `libbox` (sing-box 1.12+).
 * Handles foreground notification, network switch recovery, and automatic post-connect delay test.
 */
class BuildMyVpnService : VpnService() {

    companion object {
        private const val TAG = "BuildMyVpnService"
        const val ACTION_START = "com.buildmyvpn.action.START"
        const val ACTION_STOP = "com.buildmyvpn.action.STOP"
        const val ACTION_REFRESH_DELAY = "com.buildmyvpn.action.REFRESH_DELAY"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_SERVER_LABEL = "extra_server_label"
        const val EXTRA_CLASH_SECRET = "extra_clash_secret"

        private const val CHANNEL_ID = "build_myvpn_status"
        private const val NOTIFICATION_ID = 1001

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _delayResult = MutableStateFlow<DelayResult>(DelayResult.Idle)
        val delayResult: StateFlow<DelayResult> = _delayResult.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var libboxBridge: LibboxBridge
    private val delayTester = DelayTester()
    private var activeConfigJson: String? = null
    private var activeServerLabel: String = ""
    private var activeClashSecret: String = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        libboxBridge = LibboxBridge(this, applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_REFRESH_DELAY -> triggerDelayProbe()
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                val serverLabel = intent.getStringExtra(EXTRA_SERVER_LABEL).orEmpty()
                val clashSecret = intent.getStringExtra(EXTRA_CLASH_SECRET).orEmpty()
                if (!configJson.isNullOrBlank()) {
                    startVpn(configJson, serverLabel, clashSecret)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(configJson: String, serverLabel: String, clashSecret: String) {
        _lastError.value = null
        _state.value = VpnState.CONNECTING
        _delayResult.value = DelayResult.Idle
        activeConfigJson = configJson
        activeServerLabel = serverLabel
        activeClashSecret = clashSecret

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(serverLabel))

        serviceScope.launch {
            runCatching {
                libboxBridge.stop()
                if (!libboxBridge.isAvailable()) {
                    error("未找到 libbox.aar，请先编译并放入 android/app/libs/libbox.aar")
                }
                libboxBridge.start(configJson)
                registerNetworkRecovery()
                _state.value = VpnState.CONNECTED

                // Automatically run one real proxy egress latency probe after connection establishes
                delay(600)
                runDelayProbeInternal()
            }.onFailure { ex ->
                Log.e(TAG, "Failed to start VPN", ex)
                _lastError.value = ex.message ?: "VPN 启动失败"
                stopVpn()
            }
        }
    }

    private fun triggerDelayProbe() {
        if (_state.value != VpnState.CONNECTED || activeClashSecret.isBlank()) return
        serviceScope.launch { runDelayProbeInternal() }
    }

    private suspend fun runDelayProbeInternal() {
        _delayResult.value = DelayResult.Testing
        _delayResult.value = delayTester.measureProxyDelay(activeClashSecret)
    }

    private fun registerNetworkRecovery() {
        unregisterNetworkRecovery()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var seenInitial = false
            override fun onAvailable(network: Network) {
                if (!seenInitial) {
                    seenInitial = true
                    return
                }
                // Recover QUIC / TCP sockets cleanly on Wi-Fi <-> Cellular handoff
                val cfg = activeConfigJson ?: return
                if (_state.value == VpnState.CONNECTED) {
                    serviceScope.launch {
                        runCatching {
                            libboxBridge.stop()
                            libboxBridge.start(cfg)
                        }.onFailure { Log.w(TAG, "Network switch recovery failed", it) }
                    }
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun unregisterNetworkRecovery() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun stopVpn() {
        unregisterNetworkRecovery()
        libboxBridge.stop()
        activeConfigJson = null
        activeClashSecret = ""
        _state.value = VpnState.DISCONNECTED
        _delayResult.value = DelayResult.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterNetworkRecovery()
        libboxBridge.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Build MyVpn",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(serverLabel: String): Notification {
        val stopIntent = Intent(this, BuildMyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Build MyVpn")
            .setContentText(serverLabel.ifBlank { "Connected" })
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Disconnect",
                    stopPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}
