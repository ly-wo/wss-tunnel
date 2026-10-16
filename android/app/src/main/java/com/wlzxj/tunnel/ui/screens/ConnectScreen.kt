package com.wlzxj.tunnel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wlzxj.tunnel.BuildConfig
import com.wlzxj.tunnel.service.TunnelUiPhase
import com.wlzxj.tunnel.ui.AppViewModel
import com.wlzxj.tunnel.ui.TunnelStartRequest

@Composable
fun ConnectScreen(
    viewModel: AppViewModel,
    onStartTunnel: (TunnelStartRequest) -> Unit,
    onStopTunnel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs by viewModel.connectionPrefs.collectAsState()
    val tunnel by viewModel.tunnelState.collectAsState()
    val error by viewModel.connectError.collectAsState()
    val notice by viewModel.connectNotice.collectAsState()
    val credEpoch by viewModel.credentialsEpoch.collectAsState()
    val busy = viewModel.isTunnelBusy()
    val connected = tunnel.phase == TunnelUiPhase.Ready
    val connecting = tunnel.phase == TunnelUiPhase.Connecting || tunnel.phase == TunnelUiPhase.Stopping

    var usePassword by remember(credEpoch) { mutableStateOf(false) }
    var token by remember(credEpoch) { mutableStateOf("") }
    var username by remember(credEpoch) { mutableStateOf("") }
    var password by remember(credEpoch) { mutableStateOf("") }
    val injected by viewModel.injectedConnectionToken.collectAsState()
    LaunchedEffect(injected) {
        if (injected.isNotBlank()) {
            token = injected
            viewModel.clearInjectedConnectionToken()
        }
    }
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("连接", style = MaterialTheme.typography.headlineMedium)
        Text(
            "127.0.0.1 表示手机本机，不是电脑。建立隧道前请确认目标服务已在手机上监听。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prefs.server,
                    onValueChange = { if (!busy) viewModel.onServerChanged(it) },
                    label = { Text("接入服务器") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefs.target,
                    onValueChange = { if (!busy) viewModel.updateConnection { c -> c.copy(target = it) } },
                    label = { Text("目标地址") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefs.subdomain,
                    onValueChange = { if (!busy) viewModel.updateConnection { c -> c.copy(subdomain = it) } },
                    label = { Text("子域名（可选）") },
                    supportingText = { Text("留空将由服务器自动分配随机子域名") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !usePassword, onClick = { if (!busy) usePassword = false }, enabled = !busy)
                    Text("连接 Token")
                    Spacer(Modifier.weight(1f))
                    RadioButton(selected = usePassword, onClick = { if (!busy) usePassword = true }, enabled = !busy)
                    Text("账号密码")
                }
                if (!usePassword) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("连接 Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = prefs.saveConnectionToken,
                            onCheckedChange = { viewModel.onSaveTokenToggled(it) },
                            enabled = !busy,
                        )
                        Text(
                            if (viewModel.keystoreAvailable) {
                                "加密保存连接 Token 到本机"
                            } else {
                                "加密保存不可用，连接 Token 仅保留在内存中"
                            },
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (BuildConfig.DEBUG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = prefs.debugAllowLocalHttp,
                            onCheckedChange = {
                                viewModel.updateConnection { c -> c.copy(debugAllowLocalHttp = it) }
                            },
                            enabled = !busy,
                        )
                        Text("Debug：允许本地 HTTP 接入（127.0.0.1 / 10.0.2.2）")
                    }
                }
            }
        }

        notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        if (error != null) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
        }

        when (tunnel.phase) {
            TunnelUiPhase.Connecting -> Text("状态：连接中…")
            TunnelUiPhase.Ready -> {
                Text("状态：已连接")
                tunnel.publicUrl?.let { url ->
                    Text("公网地址：$url", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyUrl(context, url) }) { Text("复制") }
                        Button(onClick = { shareUrl(context, url) }) { Text("分享") }
                    }
                }
            }
            TunnelUiPhase.Failed -> Text("状态：失败 — ${tunnel.message ?: ""}")
            TunnelUiPhase.Stopping -> Text("状态：正在停止…")
            else -> Text("状态：未连接")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.clearConnectError()
                    val req = viewModel.prepareConnection(token, usePassword, username, password)
                    if (req != null) onStartTunnel(req)
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("开始连接") }
            Button(
                onClick = onStopTunnel,
                enabled = busy,
                modifier = Modifier.weight(1f),
            ) { Text("断开") }
        }
        Spacer(Modifier.height(48.dp))
    }
}

private fun copyUrl(context: Context, url: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("tunnel_url", url))
}

private fun shareUrl(context: Context, url: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            },
            "分享公网地址",
        ),
    )
}
