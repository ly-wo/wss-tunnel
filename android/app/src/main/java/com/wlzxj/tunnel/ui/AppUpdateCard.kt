package com.wlzxj.tunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wlzxj.tunnel.update.UpdateCheckPhase
import com.wlzxj.tunnel.update.UpdateViewModel

@Composable
fun AppUpdateCard(
    viewModel: UpdateViewModel,
    onRequestInstall: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Card(modifier.fillMaxWidth().testTag("app_update_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本：${state.currentVersionLabel}", style = MaterialTheme.typography.bodyMedium)

            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app_update_status"))
            }
            state.installBlockedMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.releaseNotes?.takeIf { state.phase == UpdateCheckPhase.UpdateAvailable }?.let { notes ->
                Text("更新说明：", style = MaterialTheme.typography.labelMedium)
                Text(notes, style = MaterialTheme.typography.bodySmall, maxLines = 8)
            }

            if (state.downloading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.downloadProgressText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = { viewModel.cancelDownload() },
                    modifier = Modifier.fillMaxWidth().testTag("app_update_cancel_download"),
                ) { Text("取消") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.checkForUpdate(manual = true) },
                    enabled = !state.downloading && state.phase != UpdateCheckPhase.Checking,
                    modifier = Modifier.weight(1f).testTag("app_update_check"),
                ) {
                    Text(
                        when (state.phase) {
                            UpdateCheckPhase.Checking -> "检查中…"
                            else -> "检查更新"
                        },
                    )
                }
                if (state.phase == UpdateCheckPhase.UpdateAvailable && !state.downloadedApkReady) {
                    Button(
                        onClick = { viewModel.downloadUpdate() },
                        enabled = !state.downloading,
                        modifier = Modifier.weight(1f).testTag("app_update_download"),
                    ) { Text("下载") }
                }
            }

            if (state.downloadedApkReady) {
                Text(
                    viewModel.tunnelInterruptWarning(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRequestInstall,
                    modifier = Modifier.fillMaxWidth().testTag("app_update_install"),
                ) { Text("安装") }
            }

            state.installBlockedMessage?.let { msg ->
                if (msg.contains("安装未知应用") || msg.contains("权限")) {
                    TextButton(
                        onClick = onOpenInstallPermission,
                        modifier = Modifier.testTag("app_update_install_permission"),
                    ) { Text("打开安装权限设置") }
                }
            }
        }
    }
}
