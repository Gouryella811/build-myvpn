package com.buildmyvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.buildmyvpn.R
import com.buildmyvpn.data.BannerSlot
import com.buildmyvpn.data.DonationConfig
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

private data class LangItem(val tag: String, val name: String, val rec: Boolean = false)

private val LANGUAGES = listOf(
    LangItem("", "System Default"),
    LangItem("en", "English", rec = true),
    LangItem("zh-CN", "简体中文", rec = true),
    LangItem("zh-TW", "繁體中文"),
    LangItem("ru", "Русский"),
    LangItem("fa", "فارسی"),
    LangItem("ar", "العربية"),
    LangItem("es", "Español"),
    LangItem("fr", "Français"),
    LangItem("de", "Deutsch"),
    LangItem("ja", "日本語"),
    LangItem("ko", "한국어"),
    LangItem("pt", "Português"),
    LangItem("tr", "Türkçe"),
    LangItem("vi", "Tiếng Việt"),
    LangItem("id", "Bahasa Indonesia"),
    LangItem("th", "ไทย"),
    LangItem("hi", "हिन्दी")
)

private fun openUrl(ctx: Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun copyText(ctx: Context, label: String, text: String, toast: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

@Composable
fun BuildMyVpnApp(
    viewModel: MainViewModel,
    onRequestConnect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ctx = LocalContext.current

    var themeMode by remember { mutableStateOf(ThemeMode.load(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerProfile?>(null) }

    LaunchedEffect(tab) {
        if (tab == 1) viewModel.refreshServersStatusAndRegions()
    }

    BuildMyVpnTheme(themeMode = themeMode) {
        val c = LocalBmvColors.current
        Scaffold(
            containerColor = c.bg,
            topBar = {
                UnifiedTopBar(
                    vpnState = state.vpnState,
                    themeMode = themeMode,
                    onCycleTheme = {
                        val next = themeMode.next()
                        themeMode = next
                        ThemeMode.save(ctx, next)
                    }
                )
            },
            bottomBar = {
                Column(modifier = Modifier.background(c.chrome)) {
                    SponsorBar(
                        banner = state.monetization.connectBanner,
                        onClick = { openUrl(ctx, state.monetization.connectBanner.url) }
                    )
                    HorizontalDivider(color = c.border, thickness = 1.dp)
                    NavigationBar(containerColor = c.chrome, tonalElevation = 0.dp) {
                        val navColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.accent,
                            selectedTextColor = c.accent,
                            indicatorColor = c.accentSoft,
                            unselectedIconColor = c.muted,
                            unselectedTextColor = c.muted
                        )
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(20.dp)) },
                            label = { Text(stringResource(R.string.tab_connect), fontSize = 11.5.sp) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Dns, null, Modifier.size(20.dp)) },
                            label = { Text(stringResource(R.string.tab_servers), fontSize = 11.5.sp) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) },
                            label = { Text(stringResource(R.string.settings), fontSize = 11.5.sp) },
                            colors = navColors
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> ConnectView(
                        state = state,
                        onToggleConnect = {
                            if (state.vpnState == VpnState.DISCONNECTED) {
                                if (state.selectedServer == null) {
                                    tab = 1
                                    showAddDialog = true
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
                        onOpenServers = {
                            tab = 1
                            if (state.selectedServer == null) showAddDialog = true
                        }
                    )
                    1 -> ServersView(
                        servers = state.persisted.servers,
                        selectedId = state.selectedServer?.id,
                        onlineMap = state.serverOnlineMap,
                        onSelect = { viewModel.selectServer(it) },
                        onEdit = { editingServer = it },
                        onRefresh = { viewModel.refreshServersStatusAndRegions() },
                        onAdd = { showAddDialog = true }
                    )
                    2 -> SettingsView(
                        state = state,
                        themeMode = themeMode,
                        onCycleTheme = {
                            val next = themeMode.next()
                            themeMode = next
                            ThemeMode.save(ctx, next)
                        },
                        onOpenLanguage = { showLangDialog = true },
                        onUpdateRules = { viewModel.updateRulesNow() },
                        onOpenDonate = { showDonateDialog = true }
                    )
                }
            }
        }

        if (showRuleDialog) {
            RuleDialog(
                current = state.persisted.routingRule,
                onSelect = { viewModel.setRoutingRule(it); showRuleDialog = false },
                onDismiss = { showRuleDialog = false }
            )
        }

        if (showAddDialog) {
            val toast = stringResource(R.string.deploy_success_toast)
            AddVpsDialog(
                deployState = state.deployState,
                onDeploy = { nick, host, port, user, pass, key, hy2 ->
                    viewModel.deployNewServer(nick, host, port, user, pass, key, hy2) {
                        showAddDialog = false
                        tab = 0
                        Toast.makeText(ctx, toast, Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = {
                    if (!state.deployState.inProgress) {
                        viewModel.clearDeployError()
                        showAddDialog = false
                    }
                }
            )
        }

        state.pendingHostKey?.let { prompt ->
            AlertDialog(
                onDismissRequest = { viewModel.respondHostKeyPrompt(false) },
                title = { Text(stringResource(R.string.ssh_fingerprint_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ssh_fingerprint_body, prompt.host), fontSize = 13.sp, color = c.muted)
                        Surface(
                            color = c.surface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, c.border)
                        ) {
                            Text(
                                prompt.fingerprintSha256,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
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

        editingServer?.let { srv ->
            val fresh = state.persisted.servers.firstOrNull { it.id == srv.id } ?: srv
            ServerDetailDialog(
                server = fresh,
                onRename = { viewModel.renameServer(fresh.id, it); editingServer = null },
                onSwitchTransport = {
                    viewModel.switchTransport(fresh, it)
                    editingServer = fresh.copy(activeTransport = it)
                },
                onResetTraffic = { viewModel.resetServerTraffic(fresh.id) },
                onDelete = { viewModel.deleteServer(fresh.id); editingServer = null },
                onDismiss = { editingServer = null }
            )
        }

        if (showLangDialog) LanguageDialog(onDismiss = { showLangDialog = false })
        if (showDonateDialog) DonateDialog(state.monetization.donations) { showDonateDialog = false }
    }
}

@Composable
private fun UnifiedTopBar(
    vpnState: VpnState,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit
) {
    val c = LocalBmvColors.current
    Column(modifier = Modifier.background(c.chrome).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BmvBrandLogo(30.dp)
                Row {
                    Text("Build ", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text)
                    Text("MyVpn", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.accent)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dotColor = when (vpnState) {
                    VpnState.CONNECTED -> c.green
                    VpnState.CONNECTING -> c.amber
                    VpnState.DISCONNECTED -> c.muted
                }
                val label = when (vpnState) {
                    VpnState.CONNECTED -> stringResource(R.string.status_connected)
                    VpnState.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
                }
                Surface(
                    shape = CircleShape,
                    color = c.surface,
                    border = BorderStroke(1.dp, c.border)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
                    }
                }
                Surface(
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onCycleTheme),
                    shape = RoundedCornerShape(8.dp),
                    color = c.elevated,
                    border = BorderStroke(1.dp, c.border)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(themeMode.iconGlyph(), fontSize = 14.sp, color = c.text)
                    }
                }
            }
        }
        HorizontalDivider(color = c.border, thickness = 1.dp)
    }
}

@Composable
private fun SponsorBar(banner: BannerSlot, onClick: () -> Unit) {
    if (!banner.enabled || banner.title.isBlank()) return
    val c = LocalBmvColors.current
    Column(Modifier.fillMaxWidth().background(c.chrome)) {
        HorizontalDivider(color = c.border, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BmvBadge(banner.badge.ifBlank { "AD" }, c.surface, c.muted, c.border)
            Column(Modifier.weight(1f)) {
                Text(banner.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (banner.subtitle.isNotBlank()) {
                    Text(banner.subtitle, fontSize = 11.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("→", color = c.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ConnectView(
    state: MainUiState,
    onToggleConnect: () -> Unit,
    onRefreshDelay: () -> Unit,
    onSelectProxyMode: (ProxyMode) -> Unit,
    onOpenRulePicker: () -> Unit,
    onOpenServers: () -> Unit
) {
    val c = LocalBmvColors.current
    val srv = state.selectedServer

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active Node Bar (identical to macOS / Windows top bar)
        BmvCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenServers) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(srv?.flagEmoji?.ifBlank { "🌐" } ?: "🌐", fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = srv?.name ?: stringResource(R.string.no_server_yet),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = if (srv != null) {
                            listOfNotNull(srv.regionLabel?.takeIf { it.isNotBlank() }, "${srv.host}:${srv.port}").joinToString(" · ")
                        } else {
                            stringResource(R.string.add_vps)
                        }
                        Text(sub, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val proto = when {
                    srv == null -> "VPS"
                    srv.activeTransport == ActiveTransport.HYSTERIA2 -> "Hysteria2"
                    else -> "REALITY"
                }
                BmvBadge(proto, c.accentSoft, c.accent)
            }
        }

        // Center Gradient Power Stage (identical to macOS / Windows)
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val gradient = when (state.vpnState) {
                VpnState.CONNECTED -> Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF15803D)))
                VpnState.CONNECTING -> Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706)))
                VpnState.DISCONNECTED -> Brush.linearGradient(listOf(Color(0xFF1874FA), Color(0xFF1536A8)))
            }
            val glowColor = when (state.vpnState) {
                VpnState.CONNECTED -> Color(0xFF22C55E)
                VpnState.CONNECTING -> Color(0xFFF59E0B)
                VpnState.DISCONNECTED -> Color(0xFF1874FA)
            }
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .shadow(18.dp, CircleShape, ambientColor = glowColor, spotColor = glowColor)
                    .clip(CircleShape)
                    .background(gradient)
                    .clickable(onClick = onToggleConnect),
                contentAlignment = Alignment.Center
            ) {
                if (state.vpnState == VpnState.CONNECTING) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
                } else {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = when (state.vpnState) {
                    VpnState.CONNECTED -> stringResource(R.string.status_connected)
                    VpnState.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = c.text
            )
            state.vpnError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Surface(color = c.redSoft, shape = RoundedCornerShape(10.dp)) {
                    Text(err, color = c.red, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }

        // 2x2 Metrics Grid (identical 4 metrics to macOS / Windows)
        val delayVal = when (val d = state.delayResult) {
            DelayResult.Idle -> "-- ms"
            DelayResult.Testing -> "..."
            is DelayResult.Success -> "${d.millis} ms"
            DelayResult.Timeout -> stringResource(R.string.delay_timeout)
            is DelayResult.Failed -> stringResource(R.string.delay_failed)
        }
        val downStr = if (state.vpnState == VpnState.CONNECTED) FormatUtils.formatSpeed(state.trafficSpeed.downBytesPerSec) else "0 B/s"
        val upStr = if (state.vpnState == VpnState.CONNECTED) FormatUtils.formatSpeed(state.trafficSpeed.upBytesPerSec) else "0 B/s"
        val usedStr = FormatUtils.formatBytes(srv?.totalUsedBytes ?: 0L)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("↓ Download", downStr, Modifier.weight(1f))
            MetricTile("↑ Upload", upStr, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "${stringResource(R.string.refresh_delay)} ↻",
                value = delayVal,
                modifier = Modifier.weight(1f),
                valueColor = if (state.delayResult is DelayResult.Success) c.green else c.text,
                onClick = if (state.vpnState == VpnState.CONNECTED) onRefreshDelay else null
            )
            MetricTile(stringResource(R.string.used_traffic_fmt, "").trim(), usedStr, Modifier.weight(1f))
        }

        // Quick Mode Controls (clean unified pill selector + routing card)
        BmvCard(modifier = Modifier.fillMaxWidth(), radius = 12.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModePill(
                    label = stringResource(R.string.mode_smart),
                    selected = state.persisted.proxyMode == ProxyMode.SMART,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectProxyMode(ProxyMode.SMART) }
                )
                ModePill(
                    label = stringResource(R.string.mode_global),
                    selected = state.persisted.proxyMode == ProxyMode.GLOBAL,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectProxyMode(ProxyMode.GLOBAL) }
                )
            }
        }

        if (state.persisted.proxyMode == ProxyMode.SMART) {
            BmvCard(modifier = Modifier.fillMaxWidth(), radius = 12.dp, onClick = onOpenRulePicker) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when (state.persisted.routingRule) {
                            RoutingRule.GENERAL -> stringResource(R.string.rule_general)
                            RoutingRule.CN -> stringResource(R.string.rule_cn)
                            RoutingRule.IR -> stringResource(R.string.rule_ir)
                            RoutingRule.RU -> stringResource(R.string.rule_ru)
                        },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.text
                    )
                    Text("›", color = c.muted, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalBmvColors.current.text,
    onClick: (() -> Unit)? = null
) {
    val c = LocalBmvColors.current
    BmvCard(modifier = modifier, radius = 12.dp, onClick = onClick) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, fontSize = 11.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBmvColors.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) c.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else c.textSecondary
        )
    }
}

@Composable
private fun ServersView(
    servers: List<ServerProfile>,
    selectedId: String?,
    onlineMap: Map<String, OnlineStatus>,
    onSelect: (String) -> Unit,
    onEdit: (ServerProfile) -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit
) {
    val c = LocalBmvColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.tab_servers), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onRefresh),
                    shape = RoundedCornerShape(9.dp),
                    color = c.surface,
                    border = BorderStroke(1.dp, c.border)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh_status), tint = c.text, modifier = Modifier.size(18.dp))
                    }
                }
                Button(
                    onClick = onAdd,
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.add_vps), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (servers.isEmpty()) {
            BmvCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.no_server_yet), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Button(
                        onClick = onAdd,
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.add_vps))
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(servers, key = { it.id }) { srv ->
                    val selected = srv.id == selectedId
                    val online = onlineMap[srv.id] ?: OnlineStatus.UNKNOWN
                    val dotColor = when (online) {
                        OnlineStatus.ONLINE -> c.green
                        OnlineStatus.OFFLINE -> c.red
                        else -> c.muted
                    }
                    val statusText = when (online) {
                        OnlineStatus.ONLINE -> stringResource(R.string.vps_online)
                        OnlineStatus.OFFLINE -> stringResource(R.string.vps_offline)
                        else -> stringResource(R.string.vps_checking)
                    }
                    BmvCard(modifier = Modifier.fillMaxWidth(), selected = selected, onClick = { onSelect(srv.id) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                            Text(srv.flagEmoji.ifBlank { "🌐" }, fontSize = 20.sp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(srv.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    BmvBadge(if (srv.activeTransport == ActiveTransport.HYSTERIA2) "Hysteria2" else "REALITY", c.accentSoft, c.accent)
                                    if (srv.bbrStatus == "enabled" || srv.bbrStatus == "already_enabled") {
                                        BmvBadge("BBR ✓", c.greenSoft, c.green)
                                    }
                                }
                                val reg = srv.regionLabel?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
                                Text(
                                    "$reg${srv.host}:${srv.port} · $statusText · ${stringResource(R.string.used_traffic_fmt, FormatUtils.formatBytes(srv.totalUsedBytes))}",
                                    fontSize = 12.sp,
                                    color = c.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onEdit(srv) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.MoreVert, null, tint = c.muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsView(
    state: MainUiState,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onOpenLanguage: () -> Unit,
    onUpdateRules: () -> Unit,
    onOpenDonate: () -> Unit
) {
    val c = LocalBmvColors.current
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied_toast)
    var showLogs by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.settings), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text)

        BmvCard(modifier = Modifier.fillMaxWidth()) {
            SettingRow(stringResource(R.string.app_language), "17 Languages · RTL", "›", onOpenLanguage)
            HorizontalDivider(color = c.border)
            val themeLabel = when (themeMode) {
                ThemeMode.AUTO -> "◐ Auto (06:00–18:59)"
                ThemeMode.LIGHT -> "☀ Light"
                ThemeMode.DARK -> "☾ Dark"
            }
            SettingRow("Appearance", themeLabel, "↻", onCycleTheme)
            HorizontalDivider(color = c.border)
            SettingRow(
                title = stringResource(R.string.rules_update_title),
                subtitle = state.ruleUpdateMessage ?: state.persisted.rulesUpdatedAt ?: stringResource(R.string.rules_builtin),
                trailing = if (state.ruleUpdating) "..." else "↓",
                onClick = if (state.ruleUpdating) null else onUpdateRules
            )
            HorizontalDivider(color = c.border)
            SettingRow(stringResource(R.string.support_developer), stringResource(R.string.donate_voluntary_note), "♡", onOpenDonate)
        }

        // Core Logs (matching Desktop dark monospace console)
        BmvCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.view_logs), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { showLogs = !showLogs }) {
                            Text(if (showLogs) stringResource(R.string.hide_logs) else stringResource(R.string.view_logs), fontSize = 12.sp)
                        }
                        if (showLogs) {
                            TextButton(onClick = {
                                copyText(ctx, "Logs", state.recentLogs.takeLast(30).joinToString("\n"), copied)
                            }) {
                                Text(stringResource(R.string.copy_logs), fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (showLogs) {
                    val text = state.recentLogs.takeLast(25).joinToString("\n").ifBlank { stringResource(R.string.no_logs) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0C0C0F))
                            .border(1.dp, c.border, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFD4D4D8))
                    }
                }
            }
        }

        // About & Links
        BmvCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Build MyVpn v1.1.1", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
                Text(stringResource(R.string.settings_privacy_note), fontSize = 12.sp, color = c.muted)
                Text(stringResource(R.string.settings_license_note), fontSize = 12.sp, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openUrl(ctx, state.monetization.websiteUrl) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.website_link), fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { openUrl(ctx, "https://github.com/Gouryella811/build-myvpn") }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("GitHub", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { openUrl(ctx, state.monetization.privacyUrl) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.privacy_policy), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: String, onClick: (() -> Unit)?) {
    val c = LocalBmvColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.text)
            Text(subtitle, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(trailing, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.accent, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun AddVpsDialog(
    deployState: DeployUiState,
    onDeploy: (String, String, Int, String, CharArray?, String?, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalBmvColors.current
    var nick by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var adv by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("root") }
    var port by remember { mutableStateOf("22") }
    var hy2 by remember { mutableStateOf(false) }
    var ackHy2 by remember { mutableStateOf(false) }
    var hostErr by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_vps), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(nick, { nick = it }, label = { Text(stringResource(R.string.server_nickname_label)) }, singleLine = true, enabled = !deployState.inProgress)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; hostErr = it.isNotBlank() && !HostValidator.isValidHost(it) },
                    label = { Text(stringResource(R.string.server_host_label)) },
                    isError = hostErr,
                    supportingText = { if (hostErr) Text(stringResource(R.string.invalid_host_error)) },
                    singleLine = true,
                    enabled = !deployState.inProgress
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!useKey, { useKey = false }, enabled = !deployState.inProgress)
                    Text(stringResource(R.string.ssh_auth_password), fontSize = 13.sp)
                    Spacer(Modifier.size(8.dp))
                    RadioButton(useKey, { useKey = true }, enabled = !deployState.inProgress)
                    Text(stringResource(R.string.ssh_auth_key), fontSize = 13.sp)
                }
                if (!useKey) {
                    OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.ssh_password_label)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !deployState.inProgress)
                } else {
                    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.ssh_key_label)) }, maxLines = 4, enabled = !deployState.inProgress)
                }
                TextButton(onClick = { adv = !adv }, enabled = !deployState.inProgress) {
                    Text(stringResource(if (adv) R.string.hide_advanced_ssh else R.string.show_advanced_ssh), fontSize = 12.5.sp)
                }
                if (adv) {
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.ssh_username_label)) }, singleLine = true, enabled = !deployState.inProgress)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.ssh_port_label)) }, singleLine = true, enabled = !deployState.inProgress)
                }
                HorizontalDivider(color = c.border)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!hy2, { hy2 = false; ackHy2 = false }, enabled = !deployState.inProgress)
                    Text(stringResource(R.string.protocol_reality_label), fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(hy2, { hy2 = true }, enabled = !deployState.inProgress)
                    Text(stringResource(R.string.protocol_hy2_label), fontSize = 13.sp)
                }
                if (hy2) {
                    Surface(color = c.amberSoft, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Warning, null, tint = c.amber, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.hy2_block_risk_warning), fontSize = 12.sp, color = c.text)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(ackHy2, { ackHy2 = it }, enabled = !deployState.inProgress)
                                Text(stringResource(R.string.hy2_ack_checkbox), fontSize = 12.sp, color = c.text)
                            }
                        }
                    }
                }
                if (deployState.inProgress) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                    Text(deployState.statusMessage.ifBlank { stringResource(R.string.deploying_progress) }, fontSize = 12.sp, color = c.muted)
                }
                deployState.errorMessage?.let { Text(it, fontSize = 12.sp, color = c.red) }
            }
        },
        confirmButton = {
            Button(
                enabled = !deployState.inProgress && (!hy2 || ackHy2),
                onClick = {
                    if (!HostValidator.isValidHost(host)) { hostErr = true; return@Button }
                    val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                    val pwd = if (!useKey) pass.toCharArray() else null
                    val pem = if (useKey) key else null
                    pass = ""
                    onDeploy(nick, host, p, user, pwd, pem, hy2)
                }
            ) { Text(stringResource(R.string.deploy_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deployState.inProgress) { Text(stringResource(R.string.cancel)) }
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
    val c = LocalBmvColors.current
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied_toast)
    var name by remember { mutableStateOf(server.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${server.flagEmoji.ifBlank { "🌐" }} ${server.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.server_nickname_label)) }, singleLine = true)
                Text("${server.host}:${server.port} · BBR: ${server.bbrStatus ?: "-"}", fontSize = 12.sp, color = c.muted)
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(stringResource(R.string.traffic_detail_fmt, FormatUtils.formatBytes(server.usedRxBytes), FormatUtils.formatBytes(server.usedTxBytes)), fontSize = 12.sp)
                    TextButton(onClick = onResetTraffic) { Text(stringResource(R.string.reset_traffic), fontSize = 12.sp) }
                }
                if (server.deployedMode == DeployedProtocolMode.DUAL && server.reality != null && server.hysteria2 != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSwitchTransport(ActiveTransport.REALITY) }) {
                            Text(if (server.activeTransport == ActiveTransport.REALITY) "✓ REALITY" else "REALITY", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { onSwitchTransport(ActiveTransport.HYSTERIA2) }) {
                            Text(if (server.activeTransport == ActiveTransport.HYSTERIA2) "✓ Hysteria2" else "Hysteria2", fontSize = 12.sp)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { server.toShareUri()?.let { copyText(ctx, "Build MyVpn Node", it, copied) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.copy_node_uri), fontSize = 13.sp)
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = c.red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.delete_server), color = c.red)
                }
            }
        },
        confirmButton = { Button(onClick = { onRename(name) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun RuleDialog(current: RoutingRule, onSelect: (RoutingRule) -> Unit, onDismiss: () -> Unit) {
    val items = listOf(
        RoutingRule.GENERAL to stringResource(R.string.rule_general),
        RoutingRule.CN to stringResource(R.string.rule_cn),
        RoutingRule.IR to stringResource(R.string.rule_ir),
        RoutingRule.RU to stringResource(R.string.rule_ru)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_smart), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                items.forEach { (rule, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(rule) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(current == rule, { onSelect(rule) })
                        Text(label, fontSize = 13.5.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val recLabel = stringResource(R.string.recommended_badge)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_language), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LANGUAGES.forEach { item ->
                    val selected = if (item.tag.isEmpty()) currentTags.isEmpty()
                    else currentTags.equals(item.tag, true) || currentTags.startsWith("${item.tag}-", true)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            val list = if (item.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(item.tag)
                            AppCompatDelegate.setApplicationLocales(list)
                            onDismiss()
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected, null)
                        val label = when {
                            item.tag.isEmpty() -> stringResource(R.string.language_follow_system)
                            item.rec -> "${item.name} ★ ($recLabel)"
                            else -> item.name
                        }
                        Text(label, fontSize = 13.5.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun DonateDialog(donations: DonationConfig, onDismiss: () -> Unit) {
    val c = LocalBmvColors.current
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied_toast)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Favorite, null, tint = c.accent)
                Text(stringResource(R.string.support_developer), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.donate_voluntary_note), fontSize = 12.5.sp, color = c.muted)
                listOf("PayPal.Me" to donations.paypalUrl, "Ko-fi" to donations.kofiUrl, "Afdian" to donations.afdianUrl)
                    .filter { it.second.isNotBlank() }
                    .forEach { (name, url) ->
                        OutlinedButton(onClick = { openUrl(ctx, url) }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                    }
                donations.crypto.filter { it.address.isNotBlank() }.forEach { item ->
                    BmvCard(modifier = Modifier.fillMaxWidth(), radius = 10.dp, onClick = { copyText(ctx, item.network, item.address, copied) }) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.network, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(item.address, fontSize = 11.5.sp, color = c.muted)
                            }
                            Icon(Icons.Default.ContentCopy, null, tint = c.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } }
    )
}
