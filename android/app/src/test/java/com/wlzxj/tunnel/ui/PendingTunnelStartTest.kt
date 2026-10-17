package com.wlzxj.tunnel.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTunnelStartTest {
    @Test
    fun staleWhenGuardGenerationInvalidated() {
        val gen = TunnelStartGuard.currentGeneration()
        val snap = EpochSnapshot(server = 1, account = 2, guardGeneration = gen)
        val pending = PendingTunnelStart(
            snapshot = snap,
            request = TunnelStartRequest(
                server = "https://example.com",
                target = "http://127.0.0.1:1",
                targetLabel = "t",
                name = null,
                authKind = "token",
                authValue = "x".repeat(32),
                username = "",
                allowInsecure = false,
            ),
        )
        assertFalse(isEpochStale(pending.snapshot, serverEpoch = 1, accountEpoch = 2))
        TunnelStartGuard.invalidate()
        assertTrue(isEpochStale(pending.snapshot, serverEpoch = 1, accountEpoch = 2))
    }
}
