package com.wlzxj.tunnel.ui

/**
 * Process-wide monotonic guard. Never reset to zero on new ViewModel instances.
 * Invalidated on account/server identity changes and ViewModel destruction.
 */
object TunnelStartGuard {
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var connectionSeq: Long = 0L

    fun currentGeneration(): Long = generation

    fun invalidate(): Long {
        generation++
        return generation
    }

    fun nextConnectionId(): Long = ++connectionSeq

    fun matches(snapshot: EpochSnapshot): Boolean =
        snapshot.guardGeneration == generation
}
