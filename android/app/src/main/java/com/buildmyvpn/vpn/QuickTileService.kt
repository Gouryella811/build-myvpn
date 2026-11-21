package com.buildmyvpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.buildmyvpn.MainActivity
import com.buildmyvpn.data.ServerRepository
import com.buildmyvpn.rules.RegionDetector
import com.buildmyvpn.rules.RuleSetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android Quick Settings Tile allowing users to toggle Build MyVpn directly from the status shade.
 */
class QuickTileService : TileService() {

    private var tileScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).apply {
            launch {
                BuildMyVpnService.state.collectLatest { updateTile(it) }
            }
        }
    }

    override fun onStopListening() {
        tileScope?.cancel()
        tileScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (BuildMyVpnService.state.value != VpnState.DISCONNECTED) {
            startService(Intent(this, BuildMyVpnService::class.java).apply {
                action = BuildMyVpnService.ACTION_STOP
            })
            return
        }

        // If Android VPN consent hasn't been granted yet, open MainActivity to request it
        if (VpnService.prepare(this) != null) {
            openMainActivity()
            return
        }

        tileScope?.launch(Dispatchers.IO) {
            val repo = ServerRepository(applicationContext).apply { load() }
            val st = repo.state.value
            val server = st.servers.firstOrNull { it.id == st.selectedServerId } ?: st.servers.firstOrNull()
            if (server == null) {
                openMainActivity()
                return@launch
            }
            val ruleMgr = RuleSetManager(applicationContext).apply { seedBundledRulesIfNeeded() }
            val effectiveRule = RegionDetector.detectEffectiveRule(applicationContext, st.routingRule)
            val clashSecret = UUID.randomUUID().toString().replace("-", "")
            val configJson = SingBoxConfigBuilder.build(
                profile = server,
                proxyMode = st.proxyMode,
                resolvedRule = effectiveRule,
                localRuleFiles = ruleMgr.getLocalRuleFiles(effectiveRule),
                clashApiSecret = clashSecret
            )
            val startIntent = Intent(applicationContext, BuildMyVpnService::class.java).apply {
                action = BuildMyVpnService.ACTION_START
                putExtra(BuildMyVpnService.EXTRA_CONFIG_JSON, configJson)
                putExtra(BuildMyVpnService.EXTRA_SERVER_LABEL, "${server.name} · ${server.host}")
                putExtra(BuildMyVpnService.EXTRA_CLASH_SECRET, clashSecret)
            }
            startForegroundService(startIntent)
        }
    }

    private fun openMainActivity() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: VpnState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            VpnState.CONNECTED -> Tile.STATE_ACTIVE
            VpnState.CONNECTING -> Tile.STATE_ACTIVE
            VpnState.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        tile.label = "Build MyVpn"
        tile.updateTile()
    }
}
