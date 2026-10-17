package com.wlzxj.tunnel.ui

/**
 * In-memory tunnel password bound to a successful login.
 * Not persisted; invalidated when [boundAccountEpoch] no longer matches the ViewModel epoch.
 */
internal class LoginCredentialBinding {
    private var password: String? = null
    private var boundServerOrigin: String? = null
    private var boundUsername: String? = null
    private var boundAccountEpoch: Int = -1

    fun bind(serverOrigin: String, username: String, password: String, accountEpoch: Int) {
        boundServerOrigin = serverOrigin
        boundUsername = username
        this.password = password
        boundAccountEpoch = accountEpoch
    }

    fun clear() {
        password = null
        boundServerOrigin = null
        boundUsername = null
        boundAccountEpoch = -1
    }

    fun tunnelPassword(
        serverOrigin: String,
        username: String,
        accountEpoch: Int,
    ): String? {
        if (boundAccountEpoch != accountEpoch) return null
        if (boundServerOrigin != serverOrigin || boundUsername != username) return null
        return password
    }
}

internal fun isSessionExpired(expiresAtEpochSeconds: Long?): Boolean {
    if (expiresAtEpochSeconds == null) return false
    return System.currentTimeMillis() / 1000 >= expiresAtEpochSeconds
}
