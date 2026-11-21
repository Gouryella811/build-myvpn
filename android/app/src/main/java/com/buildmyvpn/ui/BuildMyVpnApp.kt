package com.buildmyvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.buildmyvpn.R
import com.buildmyvpn.data.BannerSlot
import com.buildmyvpn.data.DonationConfig
import com.buildmyvpn.data.MonetizationConfig
import com.buildmyvpn.data.RecommendedVpsItem
import com.buildmyvpn.data.model.ActiveTransport
import com.buildmyvpn.data.model.DeployedProtocolMode
import com.buildmyvpn.data.model.ProxyMode
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.data.model.ServerProfile
import com.buildmyvpn.deploy.HostValidator
import com.buildmyvpn.vpn.DelayResult
import com.buildmyvpn.vpn.FormatUtils
import com.buildmyvpn.vpn.OnlineStatus
import com.buildmyvpn.vpn.VpnState

private data class LanguageOption(
    val tag: String,       // "" = Follow System
    val nativeName: String,
    val recommended: Boolean = false
)

// English & Simplified Chinese pinned first as recommended languages, followed by 15 major languages
private val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("", "System Default"),
    LanguageOption("en", "English", recommended = true),
    LanguageOption("zh-CN", "简体中文", recommended = true),
    LanguageOption("zh-TW", "繁體中文"),
    LanguageOption("ja", "日本語"),
    LanguageOption("ko", "한국어"),
    LanguageOption("th", "ไทย"),
    LanguageOption("vi", "Tiếng Việt"),
    LanguageOption("id", "Bahasa Indonesia"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("ru", "Русский"),
    LanguageOption("fa", "فارسی"),
    LanguageOption("ar", "العربية"),
    LanguageOption("tr", "Türkçe"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("es", "Español"),
    LanguageOption("pt", "Português")
)

private fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, label: String, text: String, toastMsg: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildMyVpnApp(
    viewModel: MainViewModel,
    onRequestConnect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var showVpsRecommendDialog by remember { mutableStateOf(false) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerProfile?>(null) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            viewModel.refreshServersStatusAndRegions()
        }
    }

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
                    onOpenServers = { selectedTab = 1 },
                    onClickConnectBanner = {
                        if (state.monetization.recommendedVps.isNotEmpty()) {
                            showVpsRecommendDialog = true
                        } else {
                            openExternalUrl(context, state.monetization.connectBanner.url)
                        }
                    }
                )
            } else {
                ServersScreen(
                    modifier = Modifier.padding(innerPadding),
                    servers = state.persisted.servers,
                    selectedId = state.selectedServer?.id,
                    onlineMap = state.serverOnlineMap,
                    addVpsBanner = state.monetization.addVpsBanner,
                    onSelect = { viewModel.selectServer(it) },
                    onEdit = { editingServer = it },
                    onRefreshStatus = { viewModel.refreshServersStatusAndRegions() },
                    onClickVpsBanner = { showVpsRecommendDialog = true },
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
            val deploySuccessToast = stringResource(R.string.deploy_success_toast)
            AddVpsDialog(
                deployState = state.deployState,
                addVpsBanner = state.monetization.addVpsBanner,
                onOpenVpsGuide = { showVpsRecommendDialog = true },
                onDeploy = { nick, host, port, user, pass, key, enableHy2 ->
                    viewModel.deployNewServer(
                        nickname = nick,
                        host = host,
                        sshPort = port,
                        sshUsername = user,
                        password = pass,
                        privateKeyPem = key,
                        enableHysteria2Dual = enableHy2,
                        onSuccess = {
                            showAddServerDialog = false
                            selectedTab = 0
                            Toast.makeText(context, deploySuccessToast, Toast.LENGTH_LONG).show()
                        }
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
                title = { Text(stringResource(R.string.ssh_fingerprint_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ssh_fingerprint_body, prompt.host))
                        Text(prompt.fingerprintSha256, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.respondHostKeyPrompt(true) }) {
                        Text(stringResource(R.string.trust_and_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.respondHostKeyPrompt(false) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        editingServer?.let { server ->
            val freshServer = state.persisted.servers.firstOrNull { it.id == server.id } ?: server
            ServerDetailDialog(
                server = freshServer,
                onRename = { newName ->
                    viewModel.renameServer(freshServer.id, newName)
                    editingServer = null
                },
                onSwitchTransport = { transport ->
                    viewModel.switchTransport(freshServer, transport)
                    editingServer = freshServer.copy(activeTransport = transport)
                },
                onResetTraffic = {
                    viewModel.resetServerTraffic(freshServer.id)
                },
                onDelete = {
                    viewModel.deleteServer(freshServer.id)
                    editingServer = null
                },
                onDismiss = { editingServer = null }
            )
        }

        if (showVpsRecommendDialog) {
            RecommendedVpsDialog(
                items = state.monetization.recommendedVps,
                guideUrl = state.monetization.vpsGuideUrl,
                onDismiss = { showVpsRecommendDialog = false }
            )
        }

        if (showSettings) {
            SettingsDialog(
                monetization = state.monetization,
                rulesUpdatedAt = state.persisted.rulesUpdatedAt,
                ruleUpdating = state.ruleUpdating,
                ruleUpdateMessage = state.ruleUpdateMessage,
                recentLogs = state.recentLogs,
                onOpenLanguage = {
                    showSettings = false
                    showLanguageDialog = true
                },
                onUpdateRules = { viewModel.updateRulesNow() },
                onOpenDonate = {
                    showSettings = false
                    showDonateDialog = true
                },
                onOpenVpsGuide = {
                    showSettings = false
                    showVpsRecommendDialog = true
                },
                onDismiss = { showSettings = false }
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(onDismiss = { showLanguageDialog = false })
        }

        if (showDonateDialog) {
            SupportDeveloperDialog(
                donations = state.monetization.donations,
                onDismiss = { showDonateDialog = false }
            )
        }
    }
}

@Composable
private fun SponsorBannerCard(
    banner: BannerSlot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!banner.enabled || banner.title.isBlank()) return
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = banner.badge.ifBlank { stringResource(R.string.recommended_badge) },
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        text = banner.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (banner.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = banner.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
    onOpenServers: () -> Unit,
    onClickConnectBanner: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(4.dp))

        // Core Connect Area: Power Button -> Status -> Compact Latency + Live Speed Row
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(
                onClick = onToggleConnect,
                shape = CircleShape,
                modifier = Modifier.size(132.dp)
            ) {
                if (state.vpnState == VpnState.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = stringResource(R.string.tab_connect),
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = when (state.vpnState) {
                    VpnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
                    VpnState.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnState.CONNECTED -> stringResource(R.string.status_connected)
                },
                style = MaterialTheme.typography.titleLarge
            )
            // Compact real latency row + live up/down speed when connected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val delayText = when (val d = state.delayResult) {
                    DelayResult.Idle -> "-- ms"
                    DelayResult.Testing -> "..."
                    is DelayResult.Success -> "${d.millis} ms"
                    DelayResult.Timeout -> stringResource(R.string.delay_timeout)
                    is DelayResult.Failed -> stringResource(R.string.delay_failed)
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
            if (state.vpnState == VpnState.CONNECTED) {
                Text(
                    text = "↓ ${FormatUtils.formatSpeed(state.trafficSpeed.downBytesPerSec)}   ↑ ${FormatUtils.formatSpeed(state.trafficSpeed.upBytesPerSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.vpnError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Server Selector + Proxy Mode + Routing Rule + Fixed Bottom Sponsor Banner
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedCard(
                onClick = onOpenServers,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.current_server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    val server = state.selectedServer
                    Text(
                        text = if (server != null) {
                            val prefix = if (server.flagEmoji.isNotBlank()) "${server.flagEmoji} " else ""
                            "$prefix${server.name} · ${server.host}"
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
                        modifier = Modifier.padding(13.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            SponsorBannerCard(
                banner = state.monetization.connectBanner,
                onClick = onClickConnectBanner
            )
        }
    }
}

@Composable
private fun ServersScreen(
    modifier: Modifier,
    servers: List<ServerProfile>,
    selectedId: String?,
    onlineMap: Map<String, OnlineStatus>,
    addVpsBanner: BannerSlot,
    onSelect: (String) -> Unit,
    onEdit: (ServerProfile) -> Unit,
    onRefreshStatus: () -> Unit,
    onClickVpsBanner: () -> Unit,
    onAddClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.no_server_yet),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tab_servers),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onRefreshStatus, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh_status),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    val isSelected = server.id == selectedId
                    val online = onlineMap[server.id] ?: OnlineStatus.UNKNOWN
                    val dotColor = when (online) {
                        OnlineStatus.ONLINE -> Color(0xFF22C55E)
                        OnlineStatus.OFFLINE -> Color(0xFFEF4444)
                        OnlineStatus.CHECKING, OnlineStatus.UNKNOWN -> Color(0xFF9CA3AF)
                    }
                    val statusLabel = when (online) {
                        OnlineStatus.ONLINE -> stringResource(R.string.vps_online)
                        OnlineStatus.OFFLINE -> stringResource(R.string.vps_offline)
                        OnlineStatus.CHECKING, OnlineStatus.UNKNOWN -> stringResource(R.string.vps_checking)
                    }

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
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    val prefix = if (server.flagEmoji.isNotBlank()) "${server.flagEmoji} " else ""
                                    Text(
                                        text = "$prefix${server.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                val regionSuffix = server.regionLabel?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                Text(
                                    text = "${server.host}$regionSuffix",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val proto = if (server.activeTransport == ActiveTransport.HYSTERIA2) "Hysteria2" else "VLESS + REALITY"
                                Text(
                                    text = "$statusLabel · $proto · ${stringResource(R.string.used_traffic_fmt, FormatUtils.formatBytes(server.totalUsedBytes))}",
                                    style = MaterialTheme.typography.labelSmall,
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
        }

        SponsorBannerCard(
            banner = addVpsBanner,
            onClick = onClickVpsBanner
        )

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
    addVpsBanner: BannerSlot,
    onOpenVpsGuide: () -> Unit,
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
                if (!deployState.inProgress) {
                    SponsorBannerCard(
                        banner = addVpsBanner,
                        onClick = onOpenVpsGuide
                    )
                }

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.server_nickname_label)) },
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
                    Text(stringResource(R.string.ssh_auth_password))
                    Spacer(Modifier.size(12.dp))
                    RadioButton(selected = usePrivateKey, onClick = { usePrivateKey = true }, enabled = !deployState.inProgress)
                    Text(stringResource(R.string.ssh_auth_key))
                }

                if (!usePrivateKey) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.ssh_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !deployState.inProgress
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text(stringResource(R.string.ssh_key_label)) },
                        maxLines = 4,
                        enabled = !deployState.inProgress
                    )
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }, enabled = !deployState.inProgress) {
                    Text(
                        if (showAdvanced) stringResource(R.string.hide_advanced_ssh)
                        else stringResource(R.string.show_advanced_ssh)
                    )
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text(stringResource(R.string.ssh_username_label)) },
                        singleLine = true,
                        enabled = !deployState.inProgress
                    )
                    OutlinedTextField(
                        value = sshPortText,
                        onValueChange = { sshPortText = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.ssh_port_label)) },
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
                                    text = stringResource(R.string.hy2_ack_checkbox),
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
                        text = deployState.statusMessage.ifBlank { stringResource(R.string.deploying_progress) },
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
                    password = ""
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
private fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val recLabel = stringResource(R.string.recommended_badge)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_language)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SUPPORTED_LANGUAGES.forEach { option ->
                    val selected = if (option.tag.isEmpty()) {
                        currentTags.isEmpty()
                    } else {
                        currentTags.equals(option.tag, ignoreCase = true) ||
                            currentTags.startsWith("${option.tag}-", ignoreCase = true)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                val locales = if (option.tag.isEmpty()) {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags(option.tag)
                                }
                                AppCompatDelegate.setApplicationLocales(locales)
                                onDismiss()
                            }
                        )
                        val display = if (option.tag.isEmpty()) {
                            stringResource(R.string.language_follow_system)
                        } else if (option.recommended) {
                            "${option.nativeName} · $recLabel"
                        } else {
                            option.nativeName
                        }
                        Text(display, style = MaterialTheme.typography.bodyMedium)
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
private fun RecommendedVpsDialog(
    items: List<RecommendedVpsItem>,
    guideUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vps_guide_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.vps_guide_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                items.forEach { vps ->
                    OutlinedCard(
                        onClick = { openExternalUrl(context, vps.url) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(vps.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    vps.price,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                vps.highlights,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { openExternalUrl(context, guideUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.open_full_guide))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

@Composable
private fun SupportDeveloperDialog(
    donations: DonationConfig,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.copied_toast)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.support_developer))
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.donate_voluntary_note), style = MaterialTheme.typography.bodySmall)

                if (donations.paypalUrl.isNotBlank()) {
                    Button(
                        onClick = { openExternalUrl(context, donations.paypalUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("PayPal.Me") }
                }
                if (donations.kofiUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = { openExternalUrl(context, donations.kofiUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ko-fi") }
                }
                if (donations.afdianUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = { openExternalUrl(context, donations.afdianUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Afdian (WeChat / Alipay)") }
                }

                val validCrypto = donations.crypto.filter { it.address.isNotBlank() }
                if (validCrypto.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.donate_crypto_title), style = MaterialTheme.typography.titleSmall)
                    validCrypto.forEach { item ->
                        OutlinedCard(
                            onClick = {
                                copyToClipboard(context, item.network, item.address, copiedToast)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.network, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        item.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

@Composable
private fun ServerDetailDialog(
    server: ServerProfile,
    onRename: (String) -> Unit,
    onSwitchTransport: (ActiveTransport) -> Unit,
    onResetTraffic: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.copied_toast)
    var nameInput by remember { mutableStateOf(server.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val prefix = if (server.flagEmoji.isNotBlank()) "${server.flagEmoji} " else ""
            Text("$prefix${server.name}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.server_nickname_label)) },
                    singleLine = true
                )
                Text(
                    text = "${server.host}:${server.port} · BBR: ${server.bbrStatus ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                server.regionLabel?.takeIf { it.isNotBlank() }?.let { region ->
                    Text(
                        text = "${server.flagEmoji} $region",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Per-VPS traffic statistics & reset
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.traffic_detail_fmt,
                            FormatUtils.formatBytes(server.usedRxBytes),
                            FormatUtils.formatBytes(server.usedTxBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onResetTraffic) {
                        Text(stringResource(R.string.reset_traffic))
                    }
                }

                if (server.deployedMode == DeployedProtocolMode.DUAL &&
                    server.reality != null && server.hysteria2 != null
                ) {
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
                            copyToClipboard(context, "Build MyVpn Node", uri, copiedToast)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.copy_node_uri))
                }

                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.delete_server), color = MaterialTheme.colorScheme.error)
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
    monetization: MonetizationConfig,
    rulesUpdatedAt: String?,
    ruleUpdating: Boolean,
    ruleUpdateMessage: String?,
    recentLogs: List<String>,
    onOpenLanguage: () -> Unit,
    onUpdateRules: () -> Unit,
    onOpenDonate: () -> Unit,
    onOpenVpsGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.copied_toast)
    var showLogs by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenLanguage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.app_language))
                }

                Button(
                    onClick = onOpenDonate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.support_developer))
                }

                OutlinedButton(
                    onClick = onOpenVpsGuide,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.vps_guide_title))
                }

                HorizontalDivider()

                Text(stringResource(R.string.rules_update_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = rulesUpdatedAt ?: stringResource(R.string.rules_builtin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onUpdateRules,
                    enabled = !ruleUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (ruleUpdating) stringResource(R.string.rules_updating)
                        else stringResource(R.string.rules_update_now)
                    )
                }
                ruleUpdateMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openExternalUrl(context, monetization.websiteUrl) }) {
                        Text(stringResource(R.string.website_link))
                    }
                    TextButton(onClick = { openExternalUrl(context, monetization.privacyUrl) }) {
                        Text(stringResource(R.string.privacy_policy))
                    }
                    TextButton(onClick = { openExternalUrl(context, "https://github.com/Gouryella811/build-myvpn") }) {
                        Text("GitHub")
                    }
                }

                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(
                        if (showLogs) stringResource(R.string.hide_logs)
                        else stringResource(R.string.view_logs)
                    )
                }
                if (showLogs) {
                    val logText = recentLogs.takeLast(20).joinToString("\n")
                        .ifBlank { stringResource(R.string.no_logs) }
                    Text(logText, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { copyToClipboard(context, "Build MyVpn Logs", logText, copiedToast) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.copy_logs))
                    }
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
