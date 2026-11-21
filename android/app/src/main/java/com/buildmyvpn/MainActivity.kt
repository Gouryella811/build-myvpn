package com.buildmyvpn

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.buildmyvpn.ui.MainViewModel
import com.buildmyvpn.ui.BuildMyVpnApp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BuildMyVpnApp(
                viewModel = viewModel,
                onRequestConnect = { requestVpnAndConnect() }
            )
        }
    }

    private fun requestVpnAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpn()
        }
    }
}
