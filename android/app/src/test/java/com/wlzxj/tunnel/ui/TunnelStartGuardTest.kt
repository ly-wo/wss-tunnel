package com.wlzxj.tunnel.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStartGuardTest {
    @Test
    fun invalidateMonotonicAndNeverResetsToZeroPair() {
        val before = TunnelStartGuard.currentGeneration()
        val snap = EpochSnapshot(server = 1, account = 2, guardGeneration = before)
        assertTrue(TunnelStartGuard.matches(snap))
        val next = TunnelStartGuard.invalidate()
        assertNotEquals(before, next)
        assertFalse(TunnelStartGuard.matches(snap))
        assertTrue(TunnelStartGuard.matches(EpochSnapshot(1, 2, next)))
    }

    @Test
    fun connectionIdsAreUnique() {
        val a = TunnelStartGuard.nextConnectionId()
        val b = TunnelStartGuard.nextConnectionId()
        assertTrue(b > a)
    }
}
