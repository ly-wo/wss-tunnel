package com.wlzxj.tunnel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wlzxj.tunnel.data.DomainRow
import com.wlzxj.tunnel.data.Formatters
import com.wlzxj.tunnel.ui.AppViewModel

@Composable
fun DomainsScreen(
    viewModel: AppViewModel,
    onNavigateConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.domains.collectAsState()
    val session by viewModel.session.collectAsState()
    var confirmDelete by remember { mutableStateOf<DomainRow?>(null) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("域名", style = MaterialTheme.typography.headlineMedium)
        if (session.session == null) {
            Text(
                state.error ?: "登录账号后可查看已保留的子域名与名额。",
                style = MaterialTheme.typography.bodyMedium,
            )
            return
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "已用 ${state.rows.size} / 上限 ${state.limit ?: "…"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { viewModel.refreshDomains() }, enabled = !state.loading) {
                Text(if (state.loading) "刷新中…" else "刷新")
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { viewModel.refreshDomains() }) { Text("重试") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.rows) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.domain, style = MaterialTheme.typography.titleMedium)
                        Text("状态：${if (row.status == "online") "在线" else "离线"}")
                        row.lastConnectedAt?.let {
                            Text("最近连接：${Formatters.epochSeconds(it)}")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    viewModel.reuseDomainName(row.name)
                                    onNavigateConnect()
                                },
                                enabled = !viewModel.isTunnelBusy(),
                            ) { Text("复用名称") }
                            val online = row.status == "online"
                            TextButton(
                                onClick = { confirmDelete = row },
                                enabled = !online,
                            ) { Text(if (online) "在线不可删" else "删除") }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除离线域名？") },
            text = { Text("将删除 ${row.name}，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteOfflineDomain(row.name)
                    confirmDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}
