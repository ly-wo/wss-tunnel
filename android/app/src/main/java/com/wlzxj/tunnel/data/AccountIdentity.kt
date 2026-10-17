package com.wlzxj.tunnel.data

object AccountIdentity {
    fun normalizeUsername(input: String): String = input.trim().lowercase()

    fun sameUser(canonical: String, other: String): Boolean =
        normalizeUsername(canonical) == normalizeUsername(other)
}
