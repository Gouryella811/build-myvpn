package com.buildmyvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
 * Network switch recovery is delegated natively to LibboxBridge's DefaultInterfaceMonitor.
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
        private const val MAX_LOG_LINES = 40

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _delayResult = MutableStateFlow<DelayResult>(DelayResult.Idle)
        val delayResult: StateFlow<DelayResult> = _delayResult.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
        val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

        private fun appendLog(line: String) {
            val current = _recentLogs.value
            val updated = (current + line).takeLast(MAX_LOG_LINES)
            _recentLogs.value = updated
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var libboxBridge: LibboxBridge
    private val delayTester = DelayTester()
    private var activeClashSecret: String = ""

    override fun onCreate() {
        super.onCreate()
        libboxBridge = LibboxBridge(
            vpnService = this,
            appContext = applicationContext,
            onLog = { appendLog(it) }
        )
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
        _recentLogs.value = emptyList()
        _state.value = VpnState.CONNECTING
        _delayResult.value = DelayResult.Idle
        activeClashSecret = clashSecret

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(serverLabel))

        serviceScope.launch {
            runCatching {
                libboxBridge.stop()
                if (!libboxBridge.isAvailable()) {
                    error("未找到 libbox.aar，请使用 CI 编译的 APK")
                }
                appendLog("Starting Build MyVpn core for $serverLabel")
                libboxBridge.start(configJson)
                _state.value = VpnState.CONNECTED

                // Wait briefly for Clash API & tunnel interface to settle, then run real proxy delay probe
                delay(800)
                runDelayProbeInternal()
            }.onFailure { ex ->
                val causeMsg = ex.cause?.message ?: ex.message ?: "VPN 启动失败"
                Log.e(TAG, "Failed to start VPN: $causeMsg", ex)
                appendLog("ERROR: $causeMsg")
                _lastError.value = causeMsg
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
        val res = delayTester.measureProxyDelay(activeClashSecret)
        appendLog("Delay test result: $res")
        _delayResult.value = res
    }

    private fun stopVpn() {
        libboxBridge.stop()
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
