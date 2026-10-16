package com.wlzxj.tunnel.ui

internal data class EpochSnapshot(
    val server: Int,
    val account: Int,
)

internal fun isEpochStale(snapshot: EpochSnapshot, serverEpoch: Int, accountEpoch: Int): Boolean =
    snapshot.server != serverEpoch || snapshot.account != accountEpoch
