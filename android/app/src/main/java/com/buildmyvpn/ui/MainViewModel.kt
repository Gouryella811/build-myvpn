package com.buildmyvpn.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buildmyvpn.data.AppPersistedState
import com.buildmyvpn.data.MonetizationConfig
import com.buildmyvpn.data.MonetizationManager
import com.buildmyvpn.data.ServerRepository
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
import com.buildmyvpn.vpn.DelayResult
import com.buildmyvpn.vpn.BuildMyVpnService
import com.buildmyvpn.vpn.SingBoxConfigBuilder
import com.buildmyvpn.vpn.VpnState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

data class MainUiState(
    val persisted: AppPersistedState = AppPersistedState(),
    val monetization: MonetizationConfig = MonetizationConfig(),
    val vpnState: VpnState = VpnState.DISCONNECTED,
    val delayResult: DelayResult = DelayResult.Idle,
    val vpnError: String? = null,
    val recentLogs: List<String> = emptyList(),
    val deployState: DeployUiState = DeployUiState(),
    val pendingHostKey: PendingHostKeyPrompt? = null,
    val ruleUpdateMessage: String? = null,
    val ruleUpdating: Boolean = false
) {
    val selectedServer: ServerProfile?
        get() = persisted.servers.firstOrNull { it.id == persisted.selectedServerId }
            ?: persisted.servers.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServerRepository(application)
    private val monetizationManager = MonetizationManager(application)
    private val deployEngine = SshDeployEngine(application)
    private val ruleSetManager = RuleSetManager(application)

    private val _deployState = MutableStateFlow(DeployUiState())
    private val _pendingHostKey = MutableStateFlow<PendingHostKeyPrompt?>(null)
    private val _ruleUpdateMessage = MutableStateFlow<String?>(null)
    private val _ruleUpdating = MutableStateFlow(false)

    private val vpnFlow = combine(
        BuildMyVpnService.state,
        BuildMyVpnService.delayResult,
        BuildMyVpnService.lastError,
        BuildMyVpnService.recentLogs
    ) { vpnState, delayResult, vpnError, logs -> VpnTuple(vpnState, delayResult, vpnError, logs) }

    private val opsFlow = combine(
        _deployState,
        _pendingHostKey,
        _ruleUpdateMessage,
        _ruleUpdating
    ) { deploy, hostKey, ruleMsg, ruleBusy ->
        OpsTuple(deploy, hostKey, ruleMsg, ruleBusy)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repository.state,
        monetizationManager.config,
        vpnFlow,
        opsFlow
    ) { persisted, monetization, vpn, ops ->
        MainUiState(
            persisted = persisted,
            monetization = monetization,
            vpnState = vpn.vpnState,
            delayResult = vpn.delayResult,
            vpnError = vpn.vpnError,
            recentLogs = vpn.logs,
            deployState = ops.deploy,
            pendingHostKey = ops.hostKey,
            ruleUpdateMessage = ops.ruleMsg,
            ruleUpdating = ops.ruleBusy
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private data class VpnTuple(
        val vpnState: VpnState,
        val delayResult: DelayResult,
        val vpnError: String?,
        val logs: List<String>
    )

    private data class OpsTuple(
        val deploy: DeployUiState,
        val hostKey: PendingHostKeyPrompt?,
        val ruleMsg: String?,
        val ruleBusy: Boolean
    )

    init {
        viewModelScope.launch {
            repository.load()
            ruleSetManager.seedBundledRulesIfNeeded()
        }
        viewModelScope.launch {
            monetizationManager.loadAndRefresh()
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
                _deployState.value = DeployUiState(errorMessage = "请输入 SSH 密码或私钥")
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
            _deployState.value = DeployUiState(inProgress = true, currentStep = "ssh", statusMessage = "正在连接服务器…")
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
                onSuccess()
            }.onFailure { ex ->
                val msg = when (ex) {
                    is HostKeyMismatchException ->
                        "安全警告：服务器 SSH 主机指纹发生变化（可能存在中间人攻击），已中止连接。"
                    is HostKeyRejectedException ->
                        "已取消信任服务器指纹，部署中止。"
                    else -> ex.message ?: "部署失败，请检查服务器信息后重试。"
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
                    _ruleUpdateMessage.value = "规则已更新（${res.updatedAt}）"
                }
                is RuleUpdateResult.Failed -> {
                    _ruleUpdateMessage.value = "更新失败：${res.reason}（继续使用本地缓存）"
                }
            }
            _ruleUpdating.value = false
        }
    }
}
