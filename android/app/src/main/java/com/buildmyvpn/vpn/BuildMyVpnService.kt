package com.buildmyvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.buildmyvpn.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Streams live up/down speed via Clash API `/traffic` and persists per-VPS used bytes.
 */
class BuildMyVpnService : VpnService() {

    companion object {
        private const val TAG = "BuildMyVpnService"
        const val ACTION_START = "com.buildmyvpn.action.START"
        const val ACTION_STOP = "com.buildmyvpn.action.STOP"
        const val ACTION_REFRESH_DELAY = "com.buildmyvpn.action.REFRESH_DELAY"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_SERVER_LABEL = "extra_server_label"
        const val EXTRA_CLASH_SECRET = "extra_clash_secret"

        private const val CHANNEL_ID = "build_myvpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOG_LINES = 40
        private const val TRAFFIC_FLUSH_INTERVAL_TICKS = 10

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _delayResult = MutableStateFlow<DelayResult>(DelayResult.Idle)
        val delayResult: StateFlow<DelayResult> = _delayResult.asStateFlow()

        private val _trafficSpeed = MutableStateFlow(TrafficSpeed())
        val trafficSpeed: StateFlow<TrafficSpeed> = _trafficSpeed.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
        val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

        private fun appendLog(line: String) {
            val updated = (_recentLogs.value + line).takeLast(MAX_LOG_LINES)
            _recentLogs.value = updated
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var libboxBridge: LibboxBridge
    private val delayTester = DelayTester()
    private val trafficMonitor = TrafficMonitor()
    private var trafficJob: Job? = null
    private var activeServerId: String = ""
    private var activeClashSecret: String = ""
    private var pendingRxBytes: Long = 0L
    private var pendingTxBytes: Long = 0L

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
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
                val serverLabel = intent.getStringExtra(EXTRA_SERVER_LABEL).orEmpty()
                val clashSecret = intent.getStringExtra(EXTRA_CLASH_SECRET).orEmpty()
                if (!configJson.isNullOrBlank()) {
                    startVpn(configJson, serverId, serverLabel, clashSecret)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(configJson: String, serverId: String, serverLabel: String, clashSecret: String) {
        flushTrafficSync()
        trafficJob?.cancel()
        _lastError.value = null
        _recentLogs.value = emptyList()
        _state.value = VpnState.CONNECTING
        _delayResult.value = DelayResult.Idle
        _trafficSpeed.value = TrafficSpeed()
        activeServerId = serverId
        activeClashSecret = clashSecret

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(serverLabel))

        serviceScope.launch {
            runCatching {
                libboxBridge.stop()
                if (!libboxBridge.isAvailable()) {
                    error("libbox.aar missing")
                }
                appendLog("Starting Build MyVpn core for $serverLabel")
                libboxBridge.start(configJson)
                _state.value = VpnState.CONNECTED

                // Start real-time traffic monitor + per-VPS usage accumulation
                startTrafficStreaming(serverId, clashSecret)

                // Wait briefly for Clash API & TUN to settle, then run real proxy delay probe
                delay(800)
                runDelayProbeInternal()
            }.onFailure { ex ->
                val causeMsg = ex.cause?.message ?: ex.message ?: "VPN start failed"
                Log.e(TAG, "Failed to start VPN: $causeMsg", ex)
                appendLog("ERROR: $causeMsg")
                _lastError.value = causeMsg
                stopVpn()
            }
        }
    }

    private fun startTrafficStreaming(serverId: String, clashSecret: String) {
        trafficJob?.cancel()
        trafficJob = serviceScope.launch {
            val repo = ServerRepository.getInstance(applicationContext)
            var tickCount = 0
            trafficMonitor.streamTraffic(clashSecret) { speed ->
                _trafficSpeed.value = speed
                if (serverId.isNotBlank()) {
                    pendingRxBytes += speed.downBytesPerSec
                    pendingTxBytes += speed.upBytesPerSec
                    tickCount++
                    if (tickCount >= TRAFFIC_FLUSH_INTERVAL_TICKS && (pendingRxBytes > 0L || pendingTxBytes > 0L)) {
                        val rx = pendingRxBytes
                        val tx = pendingTxBytes
                        pendingRxBytes = 0L
                        pendingTxBytes = 0L
                        tickCount = 0
                        runCatching { repo.addTrafficBytes(serverId, rx, tx) }
                    }
                }
            }
        }
    }

    private fun flushTrafficSync() {
        val srvId = activeServerId
        val rx = pendingRxBytes
        val tx = pendingTxBytes
        pendingRxBytes = 0L
        pendingTxBytes = 0L
        if (srvId.isNotBlank() && (rx > 0L || tx > 0L)) {
            serviceScope.launch {
                runCatching { ServerRepository.getInstance(applicationContext).addTrafficBytes(srvId, rx, tx) }
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
        trafficJob?.cancel()
        trafficJob = null
        flushTrafficSync()
        libboxBridge.stop()
        activeServerId = ""
        activeClashSecret = ""
        _state.value = VpnState.DISCONNECTED
        _delayResult.value = DelayResult.Idle
        _trafficSpeed.value = TrafficSpeed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        trafficJob?.cancel()
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
            .setSmallIcon(com.buildmyvpn.R.drawable.ic_stat_brand)
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
