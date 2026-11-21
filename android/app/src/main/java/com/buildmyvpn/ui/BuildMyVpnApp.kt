package com.buildmyvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.buildmyvpn.R
import com.buildmyvpn.data.model.ActiveTransport
import com.buildmyvpn.data.model.DeployedProtocolMode
import com.buildmyvpn.data.model.ProxyMode
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.data.model.ServerProfile
import com.buildmyvpn.deploy.HostValidator
import com.buildmyvpn.vpn.DelayResult
import com.buildmyvpn.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildMyVpnApp(
    viewModel: MainViewModel,
    onRequestConnect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerProfile?>(null) }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        TextButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.settings))
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_connect)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_servers)) }
                    )
                }
            }
        ) { innerPadding ->
            if (selectedTab == 0) {
                ConnectScreen(
                    modifier = Modifier.padding(innerPadding),
                    state = state,
                    onToggleConnect = {
                        if (state.vpnState == VpnState.DISCONNECTED) {
                            if (state.selectedServer == null) {
                                selectedTab = 1
                                showAddServerDialog = true
                            } else {
                                onRequestConnect()
                            }
                        } else {
                            viewModel.stopVpn()
                        }
                    },
                    onRefreshDelay = { viewModel.refreshDelay() },
                    onSelectProxyMode = { viewModel.setProxyMode(it) },
                    onOpenRulePicker = { showRuleDialog = true },
                    onOpenServers = { selectedTab = 1 }
                )
            } else {
                ServersScreen(
                    modifier = Modifier.padding(innerPadding),
                    servers = state.persisted.servers,
                    selectedId = state.selectedServer?.id,
                    onSelect = { viewModel.selectServer(it) },
                    onEdit = { editingServer = it },
                    onAddClick = { showAddServerDialog = true }
                )
            }
        }

        if (showRuleDialog) {
            RulePickerDialog(
                current = state.persisted.routingRule,
                onSelect = {
                    viewModel.setRoutingRule(it)
                    showRuleDialog = false
                },
                onDismiss = { showRuleDialog = false }
            )
        }

        if (showAddServerDialog) {
            AddVpsDialog(
                deployState = state.deployState,
                onDeploy = { nick, host, port, user, pass, key, enableHy2 ->
                    viewModel.deployNewServer(
                        nickname = nick,
                        host = host,
                        sshPort = port,
                        sshUsername = user,
                        password = pass,
                        privateKeyPem = key,
                        enableHysteria2Dual = enableHy2,
                        onSuccess = { showAddServerDialog = false }
                    )
                },
                onDismiss = {
                    if (!state.deployState.inProgress) {
                        viewModel.clearDeployError()
                        showAddServerDialog = false
                    }
                }
            )
        }

        state.pendingHostKey?.let { prompt ->
            AlertDialog(
                onDismissRequest = { viewModel.respondHostKeyPrompt(false) },
                title = { Text("确认服务器 SSH 指纹") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("首次连接 ${prompt.host}，请核对主机公钥指纹：")
                        Text(prompt.fingerprintSha256, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.respondHostKeyPrompt(true) }) { Text("信任并继续") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.respondHostKeyPrompt(false) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        editingServer?.let { server ->
            ServerDetailDialog(
                server = server,
                onRename = { newName ->
                    viewModel.renameServer(server.id, newName)
                    editingServer = null
                },
                onSwitchTransport = { transport ->
                    viewModel.switchTransport(server, transport)
                    editingServer = server.copy(activeTransport = transport)
                },
                onDelete = {
                    viewModel.deleteServer(server.id)
                    editingServer = null
                },
                onDismiss = { editingServer = null }
            )
        }

        if (showSettings) {
            SettingsDialog(
                rulesUpdatedAt = state.persisted.rulesUpdatedAt,
                ruleUpdating = state.ruleUpdating,
                ruleUpdateMessage = state.ruleUpdateMessage,
                onUpdateRules = { viewModel.updateRulesNow() },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectScreen(
    modifier: Modifier,
    state: MainUiState,
    onToggleConnect: () -> Unit,
    onRefreshDelay: () -> Unit,
    onSelectProxyMode: (ProxyMode) -> Unit,
    onOpenRulePicker: () -> Unit,
    onOpenServers: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(8.dp))

        // Core Connect Area: Power Button -> Status -> Compact Single-Line Real Latency (Zero hint text)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(
                onClick = onToggleConnect,
                shape = CircleShape,
                modifier = Modifier.size(136.dp)
            ) {
                if (state.vpnState == VpnState.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = stringResource(R.string.tab_connect),
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (state.vpnState) {
                    VpnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
                    VpnState.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnState.CONNECTED -> stringResource(R.string.status_connected)
                },
                style = MaterialTheme.typography.titleLarge
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val delayText = when (val d = state.delayResult) {
                    DelayResult.Idle -> "-- ms"
                    DelayResult.Testing -> "..."
                    is DelayResult.Success -> "${d.millis} ms"
                    DelayResult.Timeout -> stringResource(R.string.delay_timeout)
                    DelayResult.Failed -> stringResource(R.string.delay_failed)
                }
                Text(
                    text = delayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onRefreshDelay,
                    enabled = state.vpnState == VpnState.CONNECTED
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh_delay),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            state.vpnError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Server Selector + Proxy Mode + Routing Rule
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedCard(
                onClick = onOpenServers,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.current_server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    val server = state.selectedServer
                    Text(
                        text = if (server != null) {
                            "${server.name} · ${server.host}"
                        } else {
                            stringResource(R.string.no_server_yet)
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.persisted.proxyMode == ProxyMode.SMART,
                    onClick = { onSelectProxyMode(ProxyMode.SMART) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.mode_smart))
                }
                SegmentedButton(
                    selected = state.persisted.proxyMode == ProxyMode.GLOBAL,
                    onClick = { onSelectProxyMode(ProxyMode.GLOBAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.mode_global))
                }
            }

            if (state.persisted.proxyMode == ProxyMode.SMART) {
                OutlinedCard(
                    onClick = onOpenRulePicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (state.persisted.routingRule) {
                            RoutingRule.GENERAL -> stringResource(R.string.rule_general)
                            RoutingRule.CN -> stringResource(R.string.rule_cn)
                            RoutingRule.IR -> stringResource(R.string.rule_ir)
                            RoutingRule.RU -> stringResource(R.string.rule_ru)
                        },
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ServersScreen(
    modifier: Modifier,
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onEdit: (ServerProfile) -> Unit,
    onAddClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (servers.isEmpty()) {
            Text(
                text = stringResource(R.string.no_server_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    val isSelected = server.id == selectedId
                    OutlinedCard(
                        onClick = { onSelect(server.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                                    if (isSelected) {
                                        Spacer(Modifier.size(6.dp))
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${server.host} · ${
                                        if (server.activeTransport == ActiveTransport.HYSTERIA2) "Hysteria2" else "VLESS + REALITY"
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onEdit(server) }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = onAddClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.add_vps))
        }
    }
}

@Composable
private fun AddVpsDialog(
    deployState: DeployUiState,
    onDeploy: (
        nickname: String,
        host: String,
        sshPort: Int,
        sshUser: String,
        password: CharArray?,
        privateKey: String?,
        enableHy2Dual: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var usePrivateKey by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var sshUser by remember { mutableStateOf("root") }
    var sshPortText by remember { mutableStateOf("22") }
    var enableHysteria2 by remember { mutableStateOf(false) }
    var acknowledgedHy2Risk by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_vps)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("服务器昵称（可选）") },
                    singleLine = true,
                    enabled = !deployState.inProgress
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        hostError = it.isNotBlank() && !HostValidator.isValidHost(it)
                    },
                    label = { Text(stringResource(R.string.server_host_label)) },
                    isError = hostError,
                    supportingText = {
                        if (hostError) Text(stringResource(R.string.invalid_host_error))
                    },
                    singleLine = true,
                    enabled = !deployState.inProgress
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !usePrivateKey, onClick = { usePrivateKey = false }, enabled = !deployState.inProgress)
                    Text("SSH 密码")
                    Spacer(Modifier.size(12.dp))
                    RadioButton(selected = usePrivateKey, onClick = { usePrivateKey = true }, enabled = !deployState.inProgress)
                    Text("SSH 私钥")
                }

                if (!usePrivateKey) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("SSH 密码（用后即焚，不保存）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !deployState.inProgress
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("粘贴 PEM 私钥内容") },
                        maxLines = 4,
                        enabled = !deployState.inProgress
                    )
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }, enabled = !deployState.inProgress) {
                    Text(if (showAdvanced) "收起高级 SSH 选项" else "高级选项（用户名 / 端口）")
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text("SSH 用户名") },
                        singleLine = true,
                        enabled = !deployState.inProgress
                    )
                    OutlinedTextField(
                        value = sshPortText,
                        onValueChange = { sshPortText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("SSH 端口") },
                        singleLine = true,
                        enabled = !deployState.inProgress
                    )
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !enableHysteria2,
                        onClick = {
                            enableHysteria2 = false
                            acknowledgedHy2Risk = false
                        },
                        enabled = !deployState.inProgress
                    )
                    Text(stringResource(R.string.protocol_reality_label), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = enableHysteria2,
                        onClick = { enableHysteria2 = true },
                        enabled = !deployState.inProgress
                    )
                    Text(stringResource(R.string.protocol_hy2_label), style = MaterialTheme.typography.bodyMedium)
                }

                if (enableHysteria2) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.hy2_block_risk_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = acknowledgedHy2Risk,
                                    onCheckedChange = { acknowledgedHy2Risk = it },
                                    enabled = !deployState.inProgress
                                )
                                Text(
                                    text = "我已知晓极速模式封锁风险（将同时保留 TCP 443 REALITY 兜底）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (deployState.inProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = deployState.statusMessage.ifBlank { "正在自动部署…" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                deployState.errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !deployState.inProgress && (!enableHysteria2 || acknowledgedHy2Risk),
                onClick = {
                    if (!HostValidator.isValidHost(host)) {
                        hostError = true
                        return@Button
                    }
                    val portNum = sshPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                    val passChars = if (!usePrivateKey) password.toCharArray() else null
                    val keyText = if (usePrivateKey) privateKey else null
                    password = "" // clear UI state immediately
                    onDeploy(nickname, host, portNum, sshUser, passChars, keyText, enableHysteria2)
                }
            ) {
                Text(stringResource(R.string.deploy_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deployState.inProgress) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ServerDetailDialog(
    server: ServerProfile,
    onRename: (String) -> Unit,
    onSwitchTransport: (ActiveTransport) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember { mutableStateOf(server.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("服务器昵称") },
                    singleLine = true
                )
                Text(
                    text = "地址：${server.host}:${server.port} · BBR：${server.bbrStatus ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (server.deployedMode == DeployedProtocolMode.DUAL &&
                    server.reality != null && server.hysteria2 != null
                ) {
                    Text("协议切换（同端口秒切）", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSwitchTransport(ActiveTransport.REALITY) }) {
                            Text(if (server.activeTransport == ActiveTransport.REALITY) "✓ REALITY" else "REALITY")
                        }
                        OutlinedButton(onClick = { onSwitchTransport(ActiveTransport.HYSTERIA2) }) {
                            Text(if (server.activeTransport == ActiveTransport.HYSTERIA2) "✓ Hysteria2" else "Hysteria2")
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val uri = server.toShareUri()
                        if (uri != null) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Build MyVpn Node", uri))
                            Toast.makeText(context, "已复制节点链接", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("复制节点链接（跨设备复用）")
                }

                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(6.dp))
                    Text("删除此服务器", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRename(nameInput) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun RulePickerDialog(
    current: RoutingRule,
    onSelect: (RoutingRule) -> Unit,
    onDismiss: () -> Unit
) {
    val items = listOf(
        RoutingRule.GENERAL to stringResource(R.string.rule_general),
        RoutingRule.CN to stringResource(R.string.rule_cn),
        RoutingRule.IR to stringResource(R.string.rule_ir),
        RoutingRule.RU to stringResource(R.string.rule_ru)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_smart)) },
        text = {
            Column {
                items.forEach { (rule, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = current == rule, onClick = { onSelect(rule) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun SettingsDialog(
    rulesUpdatedAt: String?,
    ruleUpdating: Boolean,
    ruleUpdateMessage: String?,
    onUpdateRules: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("分流规则更新", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "本地缓存版本：${rulesUpdatedAt ?: "内置基础规则"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onUpdateRules,
                    enabled = !ruleUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ruleUpdating) "正在检查更新…" else "立即更新分流规则")
                }
                ruleUpdateMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text(stringResource(R.string.settings_privacy_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_license_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}
