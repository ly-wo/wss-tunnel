package com.wlzxj.tunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wlzxj.tunnel.service.TunnelForegroundService
import com.wlzxj.tunnel.service.TunnelServiceOwnership
import com.wlzxj.tunnel.ui.AppViewModel
import com.wlzxj.tunnel.ui.PendingTunnelStart
import com.wlzxj.tunnel.ui.TunnelOwnership
import com.wlzxj.tunnel.ui.TunnelStartRequest
import com.wlzxj.tunnel.ui.screens.AccountScreen
import com.wlzxj.tunnel.ui.screens.ConnectScreen
import com.wlzxj.tunnel.ui.screens.DomainsScreen
import com.wlzxj.tunnel.ui.theme.WssTunnelTheme
import com.wlzxj.tunnel.ui.toServiceOwnership
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    private var pendingTunnelStart: PendingTunnelStart? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            flushPendingTunnelStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val darkTheme = nightMode == Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = if (darkTheme) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            },
            navigationBarStyle = if (darkTheme) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            },
        )
        viewModel.syncActiveOwnershipFromService()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.session.collect { session ->
                    if (session.session == null) {
                        discardAccountPendingTunnelStart()
                    }
                }
            }
        }
        setContent {
            WssTunnelTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    viewModel.syncActiveOwnershipFromService()
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Default.Link, null) },
                                label = { Text("连接") },
                                modifier = Modifier.testTag("nav_connect"),
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.Dns, null) },
                                label = { Text("域名") },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.AccountCircle, null) },
                                label = { Text("账号") },
                                modifier = Modifier.testTag("nav_account"),
                            )
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> ConnectScreen(
                            viewModel = viewModel,
                            onStartTunnel = ::requestStartTunnel,
                            onStopTunnel = ::stopTunnel,
                            onNavigateToAccount = { tab = 2 },
                            modifier = Modifier.padding(padding),
                        )
                        1 -> DomainsScreen(
                            viewModel = viewModel,
                            onNavigateConnect = { tab = 0 },
                            modifier = Modifier.padding(padding),
                        )
                        2 -> AccountScreen(viewModel, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onUiVisible()
        viewModel.syncActiveOwnershipFromService()
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestStartTunnel(req: TunnelStartRequest) {
        if (needsNotificationPermission()) {
            pendingTunnelStart = PendingTunnelStart(viewModel.currentEpochSnapshot(), req)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchTunnel(req)
    }

    private fun flushPendingTunnelStart() {
        val pending = pendingTunnelStart ?: return
        pendingTunnelStart = null
        if (!viewModel.validatePendingTunnelStart(pending)) {
            viewModel.onPendingTunnelStartDiscarded()
            return
        }
        launchTunnel(pending.request)
    }

    private fun discardAccountPendingTunnelStart() {
        val pending = pendingTunnelStart ?: return
        if (pending.request.ownership is TunnelOwnership.Account) {
            pendingTunnelStart = null
            viewModel.onPendingTunnelStartDiscarded()
        }
    }

    private fun launchTunnel(req: TunnelStartRequest) {
        if (!viewModel.validatePendingTunnelStart(PendingTunnelStart(req.startSnapshot, req))) {
            return
        }
        viewModel.noteTunnelStarted(req.ownership)
        val intent = Intent(this, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_START
            putExtra(TunnelForegroundService.EXTRA_SERVER, req.server)
            putExtra(TunnelForegroundService.EXTRA_TARGET, req.target)
            putExtra(TunnelForegroundService.EXTRA_TARGET_LABEL, req.targetLabel)
            putExtra(TunnelForegroundService.EXTRA_NAME, req.name)
            putExtra(TunnelForegroundService.EXTRA_AUTH_KIND, req.authKind)
            putExtra(TunnelForegroundService.EXTRA_AUTH_VALUE, req.authValue)
            putExtra(TunnelForegroundService.EXTRA_USERNAME, req.username)
            putExtra(TunnelForegroundService.EXTRA_ALLOW_INSECURE, req.allowInsecure)
            putExtra(TunnelServiceOwnership.EXTRA_START_SERVER_EPOCH, req.startSnapshot.server)
            putExtra(TunnelServiceOwnership.EXTRA_START_ACCOUNT_EPOCH, req.startSnapshot.account)
            putExtra(TunnelServiceOwnership.EXTRA_START_GUARD_GENERATION, req.startSnapshot.guardGeneration)
            TunnelServiceOwnership.writeToIntent(this, req.ownership.toServiceOwnership())
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTunnel() {
        val intent = Intent(this, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}
