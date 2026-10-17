package com.wlzxj.tunnel.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochSnapshotTest {
    @Test
    fun staleWhenAccountEpochAdvances() {
        val gen = TunnelStartGuard.currentGeneration()
        val snap = EpochSnapshot(server = 2, account = 5, guardGeneration = gen)
        assertFalse(isEpochStale(snap, serverEpoch = 2, accountEpoch = 5))
        assertTrue(isEpochStale(snap, serverEpoch = 2, accountEpoch = 6))
    }

    @Test
    fun staleWhenServerEpochAdvances() {
        val gen = TunnelStartGuard.currentGeneration()
        val snap = EpochSnapshot(server = 2, account = 5, guardGeneration = gen)
        assertTrue(isEpochStale(snap, serverEpoch = 3, accountEpoch = 5))
    }
}
