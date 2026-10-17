package com.wlzxj.tunnel.ui

data class EpochSnapshot(
    val server: Int,
    val account: Int,
    val guardGeneration: Long,
)

fun isEpochStale(snapshot: EpochSnapshot, serverEpoch: Int, accountEpoch: Int): Boolean =
    snapshot.server != serverEpoch ||
        snapshot.account != accountEpoch ||
        !TunnelStartGuard.matches(snapshot)
