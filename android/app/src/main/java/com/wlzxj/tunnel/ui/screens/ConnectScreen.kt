package com.wlzxj.tunnel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wlzxj.tunnel.BuildConfig
import com.wlzxj.tunnel.service.TunnelUiPhase
import com.wlzxj.tunnel.ui.AppViewModel
import com.wlzxj.tunnel.ui.ConnectAuthMode
import com.wlzxj.tunnel.ui.TunnelStartRequest

@Composable
fun ConnectScreen(
    viewModel: AppViewModel,
    onStartTunnel: (TunnelStartRequest) -> Unit,
    onStopTunnel: () -> Unit,
    onNavigateToAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs by viewModel.connectionPrefs.collectAsState()
    val session by viewModel.session.collectAsState()
    val tunnel by viewModel.tunnelState.collectAsState()
    val error by viewModel.connectError.collectAsState()
    val notice by viewModel.connectNotice.collectAsState()
    val credEpoch by viewModel.credentialsEpoch.collectAsState()
    val accountChecking by viewModel.accountSessionCheckInFlight.collectAsState()
    val busy = viewModel.isTunnelBusy()
    val connecting = tunnel.phase == TunnelUiPhase.Connecting || tunnel.phase == TunnelUiPhase.Stopping
    val loggedIn = session.session != null

    val authModeResetKey = "${credEpoch}_${session.session}_${session.username}_${loggedIn}"
    var authMode by remember(authModeResetKey) {
        mutableStateOf(viewModel.initialConnectAuthMode(loggedIn))
    }
    var token by remember(credEpoch) { mutableStateOf("") }
    var username by remember(credEpoch) { mutableStateOf("") }
    var password by remember(credEpoch) { mutableStateOf("") }
    val injected by viewModel.injectedConnectionToken.collectAsState()
    LaunchedEffect(injected) {
        if (injected.isNotBlank()) {
            token = injected
            authMode = ConnectAuthMode.ManualToken
            viewModel.clearInjectedConnectionToken()
        }
    }
    val context = LocalContext.current
    var targetDraft by remember(prefs.target) { mutableStateOf(prefs.target) }
    LaunchedEffect(prefs.target) {
        if (targetDraft != prefs.target) targetDraft = prefs.target
    }

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
                    modifier = Modifier.fillMaxWidth().testTag("connect_server"),
                )
                OutlinedTextField(
                    value = targetDraft,
                    onValueChange = {
                        targetDraft = it
                        if (!busy) viewModel.updateConnection { c -> c.copy(target = it) }
                    },
                    label = { Text("目标地址") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("connect_target"),
                )
                OutlinedTextField(
                    value = prefs.subdomain,
                    onValueChange = { if (!busy) viewModel.updateConnection { c -> c.copy(subdomain = it) } },
                    label = { Text("子域名（可选）") },
                    supportingText = { Text("留空将由服务器自动分配随机子域名") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!loggedIn) {
                    Text(
                        "未登录账号。可使用手动 Token 连接，或前往账号页登录后默认使用当前账号。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onNavigateToAccount, enabled = !busy) {
                        Text("去登录")
                    }
                }

                if (loggedIn) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("当前账号", style = MaterialTheme.typography.titleSmall)
                            Text("用户：${session.username ?: "—"}")
                            Text("服务器：${session.serverOrigin ?: prefs.server}")
                        }
                    }
                }

                Text("认证方式", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    if (loggedIn) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = authMode == ConnectAuthMode.CurrentAccount,
                                    onClick = { if (!busy) authMode = ConnectAuthMode.CurrentAccount },
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                )
                                .testTag("connect_mode_account"),
                        ) {
                            RadioButton(
                                selected = authMode == ConnectAuthMode.CurrentAccount,
                                onClick = null,
                                enabled = !busy,
                            )
                            Text("使用当前账号")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = authMode == ConnectAuthMode.ManualToken,
                                onClick = { if (!busy) authMode = ConnectAuthMode.ManualToken },
                                enabled = !busy,
                                role = Role.RadioButton,
                            )
                            .testTag("connect_mode_token"),
                    ) {
                        RadioButton(
                            selected = authMode == ConnectAuthMode.ManualToken,
                            onClick = null,
                            enabled = !busy,
                        )
                        Text("连接 Token")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = authMode == ConnectAuthMode.ManualPassword,
                                onClick = { if (!busy) authMode = ConnectAuthMode.ManualPassword },
                                enabled = !busy,
                                role = Role.RadioButton,
                            )
                            .testTag("connect_mode_manual_password"),
                    ) {
                        RadioButton(
                            selected = authMode == ConnectAuthMode.ManualPassword,
                            onClick = null,
                            enabled = !busy,
                        )
                        Text("手动账号密码")
                    }
                }

                when (authMode) {
                    ConnectAuthMode.CurrentAccount -> {
                        Text(
                            "将使用本次登录的账号密码建立隧道，无需填写 Token。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ConnectAuthMode.ManualToken -> {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("连接 Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("connect_token"),
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
                    }
                    ConnectAuthMode.ManualPassword -> {
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
                }
                if (BuildConfig.DEBUG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = prefs.debugAllowLocalHttp,
                            onCheckedChange = {
                                viewModel.updateConnection { c -> c.copy(debugAllowLocalHttp = it) }
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("connect_debug_http"),
                        )
                        Text("Debug：允许本地 HTTP 接入（127.0.0.1 / 10.0.2.2）")
                    }
                }
            }
        }

        if (accountChecking) {
            Text("正在校验账号会话…", style = MaterialTheme.typography.bodyMedium)
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        if (error != null) {
            Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("connect_error"),
            )
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
                    viewModel.beginConnection(
                        mode = authMode,
                        targetInput = targetDraft,
                        tokenInput = token,
                        manualUsername = username,
                        manualPassword = password,
                        onReady = onStartTunnel,
                    )
                },
                enabled = !busy && !connecting && !accountChecking,
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
