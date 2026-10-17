package com.wlzxj.tunnel.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdentityTest {
    @Test
    fun normalizesToLowercase() {
        assertTrue(AccountIdentity.sameUser("Alice", "alice"))
        assertTrue(AccountIdentity.sameUser("  BOB ", "bob"))
        assertFalse(AccountIdentity.sameUser("alice", "bob"))
    }
}
