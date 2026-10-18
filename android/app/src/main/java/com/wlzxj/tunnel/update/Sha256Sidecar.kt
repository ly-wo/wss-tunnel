package com.wlzxj.tunnel.update

object Sha256Sidecar {
    private val HEX64 = Regex("^[a-fA-F0-9]{64}$")

    fun parseExpectedHash(content: String): String? {
        val line = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val token = line.split(Regex("\\s+")).firstOrNull() ?: return null
        if (!HEX64.matches(token)) return null
        return token.lowercase()
    }
}
