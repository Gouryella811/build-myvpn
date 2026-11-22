package com.buildmyvpn.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buildmyvpn.data.AppPersistedState
import com.buildmyvpn.data.MonetizationConfig
import com.buildmyvpn.data.MonetizationManager
import com.buildmyvpn.data.ServerRepository
import com.buildmyvpn.data.UpdateInfo
import com.buildmyvpn.data.UpdateManager
import com.buildmyvpn.data.model.ActiveTransport
import com.buildmyvpn.data.model.DeployedProtocolMode
import com.buildmyvpn.data.model.ProxyMode
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.data.model.ServerProfile
import com.buildmyvpn.deploy.DeployRequest
import com.buildmyvpn.deploy.HostKeyMismatchException
import com.buildmyvpn.deploy.HostKeyRejectedException
import com.buildmyvpn.deploy.SshAuth
import com.buildmyvpn.deploy.SshDeployEngine
import com.buildmyvpn.rules.RegionDetector
import com.buildmyvpn.rules.RuleSetManager
import com.buildmyvpn.rules.RuleUpdateResult
import com.buildmyvpn.vpn.BuildMyVpnService
import com.buildmyvpn.vpn.DelayResult
import com.buildmyvpn.vpn.OnlineStatus
import com.buildmyvpn.vpn.SingBoxConfigBuilder
import com.buildmyvpn.vpn.TrafficSpeed
import com.buildmyvpn.vpn.VpnState
import com.buildmyvpn.vpn.VpsStatusChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingHostKeyPrompt(
    val host: String,
    val fingerprintSha256: String,
    val decision: CompletableDeferred<Boolean>
)

data class DeployUiState(
    val inProgress: Boolean = false,
    val currentStep: String = "",
    val statusMessage: String = "",
    val errorMessage: String? = null
)

data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadPct: Int = 0,
    val bannerDismissed: Boolean = false,
    val info: UpdateInfo? = null,
    val statusNote: String? = null
)

data class MainUiState(
    val persisted: AppPersistedState = AppPersistedState(),
    val monetization: MonetizationConfig = MonetizationConfig(),
    val vpnState: VpnState = VpnState.DISCONNECTED,
    val delayResult: DelayResult = DelayResult.Idle,
    val trafficSpeed: TrafficSpeed = TrafficSpeed(),
    val vpnError: String? = null,
    val recentLogs: List<String> = emptyList(),
    val serverOnlineMap: Map<String, OnlineStatus> = emptyMap(),
    val deployState: DeployUiState = DeployUiState(),
    val pendingHostKey: PendingHostKeyPrompt? = null,
    val ruleUpdateMessage: String? = null,
    val ruleUpdating: Boolean = false,
    val updateState: UpdateUiState = UpdateUiState()
) {
    val selectedServer: ServerProfile?
        get() = persisted.servers.firstOrNull { it.id == persisted.selectedServerId }
            ?: persisted.servers.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServerRepository.getInstance(application)
    private val monetizationManager = MonetizationManager(application)
    private val deployEngine = SshDeployEngine(application)
    private val ruleSetManager = RuleSetManager(application)

    private val _deployState = MutableStateFlow(DeployUiState())
    private val _pendingHostKey = MutableStateFlow<PendingHostKeyPrompt?>(null)
    private val _ruleUpdateMessage = MutableStateFlow<String?>(null)
    private val _ruleUpdating = MutableStateFlow(false)
    private val _serverOnlineMap = MutableStateFlow<Map<String, OnlineStatus>>(emptyMap())
    private val _updateState = MutableStateFlow(UpdateUiState())

    private val vpnFlow = combine(
        BuildMyVpnService.state,
        BuildMyVpnService.delayResult,
        BuildMyVpnService.trafficSpeed,
        BuildMyVpnService.lastError,
        BuildMyVpnService.recentLogs
    ) { vpnState, delayResult, speed, vpnError, logs ->
        VpnTuple(vpnState, delayResult, speed, vpnError, logs)
    }

    private val opsFlow = combine(
        _deployState,
        _pendingHostKey,
        _ruleUpdateMessage,
        _ruleUpdating,
        _serverOnlineMap
    ) { deploy, hostKey, ruleMsg, ruleBusy, onlineMap ->
        OpsTuple(deploy, hostKey, ruleMsg, ruleBusy, onlineMap)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repository.state,
        monetizationManager.config,
        vpnFlow,
        opsFlow,
        _updateState
    ) { persisted, monetization, vpn, ops, upd ->
        MainUiState(
            persisted = persisted,
            monetization = monetization,
            vpnState = vpn.vpnState,
            delayResult = vpn.delayResult,
            trafficSpeed = vpn.trafficSpeed,
            vpnError = vpn.vpnError,
            recentLogs = vpn.logs,
            serverOnlineMap = ops.onlineMap,
            deployState = ops.deploy,
            pendingHostKey = ops.hostKey,
            ruleUpdateMessage = ops.ruleMsg,
            ruleUpdating = ops.ruleBusy,
            updateState = upd
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private data class VpnTuple(
        val vpnState: VpnState,
        val delayResult: DelayResult,
        val trafficSpeed: TrafficSpeed,
        val vpnError: String?,
        val logs: List<String>
    )

    private data class OpsTuple(
        val deploy: DeployUiState,
        val hostKey: PendingHostKeyPrompt?,
        val ruleMsg: String?,
        val ruleBusy: Boolean,
        val onlineMap: Map<String, OnlineStatus>
    )

    init {
        viewModelScope.launch {
            repository.load()
            ruleSetManager.seedBundledRulesIfNeeded()
            refreshServersStatusAndRegions()
        }
        viewModelScope.launch {
            monetizationManager.loadAndRefresh()
        }
        viewModelScope.launch {
            checkForUpdates(manual = false)
        }
    }

    fun startVpn() {
        val state = uiState.value
        val server = state.selectedServer ?: return
        val ctx = getApplication<Application>()
        val effectiveRule = RegionDetector.detectEffectiveRule(ctx, state.persisted.routingRule)
        val localRules = ruleSetManager.getLocalRuleFiles(effectiveRule)
        val clashSecret = UUID.randomUUID().toString().replace("-", "")

        val configJson = SingBoxConfigBuilder.build(
            profile = server,
            proxyMode = state.persisted.proxyMode,
            resolvedRule = effectiveRule,
            localRuleFiles = localRules,
            clashApiSecret = clashSecret
        )

        val intent = Intent(ctx, BuildMyVpnService::class.java).apply {
            action = BuildMyVpnService.ACTION_START
            putExtra(BuildMyVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(BuildMyVpnService.EXTRA_SERVER_ID, server.id)
            putExtra(BuildMyVpnService.EXTRA_SERVER_LABEL, "${server.name} · ${server.host}")
            putExtra(BuildMyVpnService.EXTRA_CLASH_SECRET, clashSecret)
        }
        ctx.startForegroundService(intent)
    }

    fun stopVpn() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, BuildMyVpnService::class.java).apply {
            action = BuildMyVpnService.ACTION_STOP
        })
    }

    fun refreshDelay() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, BuildMyVpnService::class.java).apply {
            action = BuildMyVpnService.ACTION_REFRESH_DELAY
        })
    }

    fun refreshServersStatusAndRegions() {
        val servers = repository.state.value.servers
        for (srv in servers) {
            viewModelScope.launch {
                _serverOnlineMap.update { it + (srv.id to OnlineStatus.CHECKING) }
                val online = VpsStatusChecker.probeTcpOnline(srv.host, srv.port)
                _serverOnlineMap.update { it + (srv.id to online) }
            }
            if (srv.countryCode.isNullOrBlank()) {
                viewModelScope.launch {
                    val info = VpsStatusChecker.lookupRegion(srv.host)
                    if (info != null) {
                        repository.updateServerRegion(srv.id, info.countryCode, info.label)
                    }
                }
            }
        }
    }

    fun selectServer(serverId: String) {
        viewModelScope.launch {
            repository.selectServer(serverId)
            if (BuildMyVpnService.state.value == VpnState.CONNECTED) {
                startVpn()
            }
        }
    }

    fun setProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            repository.setProxyMode(mode)
            if (BuildMyVpnService.state.value == VpnState.CONNECTED) {
                startVpn()
            }
        }
    }

    fun setRoutingRule(rule: RoutingRule) {
        viewModelScope.launch {
            repository.setRoutingRule(rule)
            if (BuildMyVpnService.state.value == VpnState.CONNECTED) {
                startVpn()
            }
        }
    }

    fun switchTransport(server: ServerProfile, transport: ActiveTransport) {
        viewModelScope.launch {
            repository.upsertServer(server.copy(activeTransport = transport), selectImmediately = false)
            if (BuildMyVpnService.state.value == VpnState.CONNECTED &&
                uiState.value.selectedServer?.id == server.id
            ) {
                startVpn()
            }
        }
    }

    fun renameServer(serverId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { repository.renameServer(serverId, newName) }
    }

    fun resetServerTraffic(serverId: String) {
        viewModelScope.launch { repository.resetTraffic(serverId) }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            if (uiState.value.selectedServer?.id == serverId &&
                BuildMyVpnService.state.value != VpnState.DISCONNECTED
            ) {
                stopVpn()
            }
            repository.deleteServer(serverId)
        }
    }

    fun deployNewServer(
        nickname: String,
        host: String,
        sshPort: Int,
        sshUsername: String,
        password: CharArray?,
        privateKeyPem: String?,
        enableHysteria2Dual: Boolean,
        onSuccess: () -> Unit
    ) {
        val auth: SshAuth = when {
            !privateKeyPem.isNullOrBlank() -> SshAuth.PrivateKey(privateKeyPem)
            password != null && password.isNotEmpty() -> SshAuth.Password(password)
            else -> {
                _deployState.value = DeployUiState(errorMessage = "SSH credentials required")
                return
            }
        }

        val request = DeployRequest(
            nickname = nickname.ifBlank { host },
            host = host.trim(),
            sshPort = sshPort,
            sshUsername = sshUsername.trim().ifBlank { "root" },
            auth = auth,
            protocolMode = if (enableHysteria2Dual) {
                DeployedProtocolMode.DUAL
            } else {
                DeployedProtocolMode.REALITY
            }
        )

        viewModelScope.launch {
            _deployState.value = DeployUiState(inProgress = true, currentStep = "ssh", statusMessage = "Connecting via SSH…")
            runCatching {
                val profile = deployEngine.deploy(
                    request = request,
                    onConfirmNewHostKey = { fp ->
                        val deferred = CompletableDeferred<Boolean>()
                        _pendingHostKey.value = PendingHostKeyPrompt(request.host, fp, deferred)
                        try {
                            deferred.await()
                        } finally {
                            _pendingHostKey.value = null
                        }
                    },
                    onEvent = { ev ->
                        _deployState.value = _deployState.value.copy(
                            inProgress = true,
                            currentStep = ev.step,
                            statusMessage = ev.message
                        )
                    }
                )
                repository.upsertServer(profile, selectImmediately = true)
                _deployState.value = DeployUiState()
                refreshServersStatusAndRegions()
                onSuccess()
            }.onFailure { ex ->
                val msg = when (ex) {
                    is HostKeyMismatchException ->
                        "Security warning: SSH host key fingerprint changed (possible MITM)."
                    is HostKeyRejectedException ->
                        "SSH host key rejected; deployment canceled."
                    else -> ex.message ?: "Deployment failed."
                }
                _deployState.value = DeployUiState(inProgress = false, errorMessage = msg)
            }
        }
    }

    fun respondHostKeyPrompt(trust: Boolean) {
        _pendingHostKey.value?.decision?.complete(trust)
    }

    fun clearDeployError() {
        _deployState.value = _deployState.value.copy(errorMessage = null)
    }

    fun updateRulesNow() {
        if (_ruleUpdating.value) return
        viewModelScope.launch {
            _ruleUpdating.value = true
            _ruleUpdateMessage.value = null
            when (val res = ruleSetManager.updateAllRules()) {
                is RuleUpdateResult.Updated -> {
                    repository.setRulesUpdatedAt(res.updatedAt)
                    _ruleUpdateMessage.value = res.updatedAt
                }
                is RuleUpdateResult.Failed -> {
                    _ruleUpdateMessage.value = res.reason
                }
            }
            _ruleUpdating.value = false
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        val cur = _updateState.value
        if (cur.checking || cur.downloading) return
        viewModelScope.launch {
            val currentVer = UpdateManager.getCurrentVersion(getApplication())
            _updateState.update {
                it.copy(
                    checking = true,
                    statusNote = if (manual) "Checking..." else it.statusNote
                )
            }
            val info = UpdateManager.checkForUpdate(currentVer)
            _updateState.update {
                it.copy(
                    checking = false,
                    info = info,
                    bannerDismissed = if (manual && info.hasUpdate) false else it.bannerDismissed,
                    statusNote = when {
                        info.error != null -> if (manual) info.error else null
                        info.hasUpdate -> "v${info.latestVersion} available (current v$currentVer)"
                        manual -> "Up to date (v$currentVer)"
                        else -> null
                    }
                )
            }
        }
    }

    fun dismissUpdateBanner() {
        _updateState.update { it.copy(bannerDismissed = true) }
    }

    fun downloadAndInstallUpdate(onFallbackBrowser: (String) -> Unit) {
        val st = _updateState.value
        val info = st.info ?: return
        if (!info.hasUpdate || st.downloading) return
        if (info.downloadUrl.isBlank()) {
            onFallbackBrowser(info.releaseUrl)
            return
        }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _updateState.update { it.copy(downloading = true, downloadPct = 5, statusNote = "Downloading v${info.latestVersion}...") }
            runCatching {
                val apkFile = UpdateManager.downloadApk(ctx, info.downloadUrl) { pct ->
                    _updateState.update { it.copy(downloadPct = pct, statusNote = "Downloading $pct%...") }
                }
                _updateState.update { it.copy(downloading = false, downloadPct = 100, statusNote = "Opening installer...") }
                UpdateManager.launchApkInstaller(ctx, apkFile)
            }.onFailure { ex ->
                _updateState.update { it.copy(downloading = false, statusNote = ex.message ?: "Download failed") }
                onFallbackBrowser(info.downloadUrl)
            }
        }
    }
}
