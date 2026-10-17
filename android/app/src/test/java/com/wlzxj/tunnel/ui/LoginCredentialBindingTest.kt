package com.wlzxj.tunnel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCredentialBindingTest {
    @Test
    fun returnsPasswordWhenEpochAndIdentityMatch() {
        val binding = LoginCredentialBinding()
        binding.bind("https://example.com", "alice", "secret-pass", accountEpoch = 3)
        assertEquals("secret-pass", binding.tunnelPassword("https://example.com", "alice", 3))
    }

    @Test
    fun clearedAfterEpochBump() {
        val binding = LoginCredentialBinding()
        binding.bind("https://example.com", "alice", "secret-pass", accountEpoch = 3)
        binding.clear()
        assertNull(binding.tunnelPassword("https://example.com", "alice", 3))
    }

    @Test
    fun rejectsWrongServerOrUserOrEpoch() {
        val binding = LoginCredentialBinding()
        binding.bind("https://a.example", "alice", "p", accountEpoch = 1)
        assertNull(binding.tunnelPassword("https://b.example", "alice", 1))
        assertNull(binding.tunnelPassword("https://a.example", "bob", 1))
        assertNull(binding.tunnelPassword("https://a.example", "alice", 2))
    }

    @Test
    fun sessionExpiry() {
        val future = System.currentTimeMillis() / 1000 + 3600
        val past = System.currentTimeMillis() / 1000 - 10
        assertFalse(isSessionExpired(future))
        assertTrue(isSessionExpired(past))
        assertFalse(isSessionExpired(null))
    }
}
