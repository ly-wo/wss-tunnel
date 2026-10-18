package com.wlzxj.tunnel.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.wlzxj.tunnel.data.Formatters
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wlzxj.tunnel.ui.AppUpdateCard
import com.wlzxj.tunnel.ui.AppViewModel
import com.wlzxj.tunnel.update.UpdateViewModel

@Composable
fun AccountScreen(
    viewModel: AppViewModel,
    updateViewModel: UpdateViewModel,
    onRequestInstall: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by viewModel.session.collectAsState()
    val account by viewModel.account.collectAsState()
    val prefs by viewModel.connectionPrefs.collectAsState()
    val credEpoch by viewModel.credentialsEpoch.collectAsState()
    val context = LocalContext.current

    var username by remember(credEpoch) { mutableStateOf("") }
    var password by remember(credEpoch) { mutableStateOf("") }
    var showRegister by remember(credEpoch) { mutableStateOf(false) }
    var confirmRotate by remember(credEpoch) { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshConfig() }
    LaunchedEffect(session.session) {
        if (session.session != null) {
            password = ""
            viewModel.loadAccount()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("账号", style = MaterialTheme.typography.headlineMedium)
        account.message?.let { Text(it) }

        AppUpdateCard(
            viewModel = updateViewModel,
            onRequestInstall = onRequestInstall,
            onOpenInstallPermission = onOpenInstallPermission,
        )

        if (session.session == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth().testTag("account_username"),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("account_password"),
                    )
                    Button(
                        onClick = { viewModel.login(username, password) },
                        enabled = !account.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (account.loading) "登录中…" else "登录")
                    }
                    if (account.registrationEnabled == true) {
                        TextButton(onClick = { showRegister = !showRegister }) {
                            Text(if (showRegister) "收起注册" else "注册新账号")
                        }
                        if (showRegister) {
                            Button(onClick = { viewModel.register(username, password) }, modifier = Modifier.fillMaxWidth()) {
                                Text("提交注册")
                            }
                        }
                    }
                }
            }
        } else {
            val profile = account.profile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("用户：${profile?.username ?: session.username}")
                    profile?.tokenPrefix?.let { Text("连接 Token 前缀：$it…") }
                    profile?.let { Text("域名名额：${it.domainLimit}") }
                    if (profile?.isAdmin == true) {
                        Button(onClick = {
                            val url = "${prefs.server.trim().removeSuffix("/")}/_tunnel/admin"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }) { Text("打开管理后台") }
                    }
                    Button(onClick = { confirmRotate = true }) { Text("创建/重置连接 Token") }
                    Button(onClick = { viewModel.logout() }) { Text("退出登录") }
                }
            }
            if (account.audit.isNotEmpty()) {
                Text("个人审计（保留 ${account.auditRetention ?: "—"} 天）", style = MaterialTheme.typography.titleMedium)
                account.audit.forEach { event ->
                    val line = buildString {
                        append(Formatters.epochSeconds(event.createdAt))
                        append(" · ")
                        append(Formatters.auditActionLabel(event.action))
                        append(" · ")
                        append(Formatters.auditOutcomeLabel(event.outcome))
                        event.domain?.let { append(" · $it") }
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("重置连接 Token？") },
            text = {
                Text(
                    "旧 Token 将立即失效。已用旧 Token 建立的隧道通常在约 20 秒内断开（心跳与阻塞发送时可能再延迟最多约 10 秒）。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    viewModel.rotateToken()
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("取消") } },
        )
    }

    account.oneTimeToken?.let { token ->
        val view = LocalView.current
        androidx.compose.runtime.DisposableEffect(token) {
            (view.parent as? DialogWindowProvider)?.window?.addFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
            )
            onDispose { }
        }
        AlertDialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("新连接 Token（仅显示一次）") },
            text = { Text(token) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("tunnel_token", token)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        cm.setPrimaryClip(clip)
                    }) { Text("复制") }
                    TextButton(onClick = {
                        viewModel.applyOneTimeTokenToConnection(token)
                    }) { Text("填入连接页") }
                    TextButton(onClick = { viewModel.dismissOneTimeToken() }) { Text("关闭") }
                }
            },
        )
    }
}
