package com.wlzxj.tunnel.ui

import com.wlzxj.tunnel.service.TunnelServiceOwnership

enum class ConnectAuthMode {
    CurrentAccount,
    ManualToken,
    ManualPassword,
}

sealed class TunnelOwnership {
    data object Manual : TunnelOwnership()

    data class Account(
        val serverOrigin: String,
        val username: String,
        val connectionId: Long,
    ) : TunnelOwnership()
}

data class PendingTunnelStart(
    val snapshot: EpochSnapshot,
    val request: TunnelStartRequest,
)

fun TunnelOwnership.toServiceOwnership(): TunnelServiceOwnership = when (this) {
    is TunnelOwnership.Manual -> TunnelServiceOwnership.Manual
    is TunnelOwnership.Account -> TunnelServiceOwnership.Account(serverOrigin, username, connectionId)
}
