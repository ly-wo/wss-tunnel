package com.wlzxj.tunnel.service

import android.content.Intent

sealed class TunnelServiceOwnership {
    data object Manual : TunnelServiceOwnership()

    data class Account(
        val serverOrigin: String,
        val username: String,
        val connectionId: Long,
    ) : TunnelServiceOwnership()

    fun matchesStopRequest(serverOrigin: String?, username: String?, connectionId: Long): Boolean = when (this) {
        is Manual -> false
        is Account -> {
            serverOrigin != null && username != null && connectionId > 0L &&
                this.serverOrigin == serverOrigin &&
                this.connectionId == connectionId &&
                AccountIdentityMatches(this.username, username)
        }
    }

    companion object {
        const val EXTRA_OWNERSHIP_KIND = "ownership_kind"
        const val EXTRA_OWNERSHIP_SERVER = "ownership_server"
        const val EXTRA_OWNERSHIP_USER = "ownership_user"
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_START_SERVER_EPOCH = "start_server_epoch"
        const val EXTRA_START_ACCOUNT_EPOCH = "start_account_epoch"
        const val EXTRA_START_GUARD_GENERATION = "start_guard_generation"

        const val KIND_MANUAL = "manual"
        const val KIND_ACCOUNT = "account"

        fun writeToIntent(intent: Intent, ownership: TunnelServiceOwnership) {
            when (ownership) {
                is Manual -> intent.putExtra(EXTRA_OWNERSHIP_KIND, KIND_MANUAL)
                is Account -> {
                    intent.putExtra(EXTRA_OWNERSHIP_KIND, KIND_ACCOUNT)
                    intent.putExtra(EXTRA_OWNERSHIP_SERVER, ownership.serverOrigin)
                    intent.putExtra(EXTRA_OWNERSHIP_USER, ownership.username)
                    intent.putExtra(EXTRA_CONNECTION_ID, ownership.connectionId)
                }
            }
        }

        fun readFromIntent(intent: Intent): TunnelServiceOwnership? {
            return when (intent.getStringExtra(EXTRA_OWNERSHIP_KIND)) {
                KIND_ACCOUNT -> {
                    val server = intent.getStringExtra(EXTRA_OWNERSHIP_SERVER) ?: return null
                    val user = intent.getStringExtra(EXTRA_OWNERSHIP_USER) ?: return null
                    val connId = intent.getLongExtra(EXTRA_CONNECTION_ID, 0L)
                    Account(server, user, connId)
                }
                KIND_MANUAL -> Manual
                else -> null
            }
        }
    }
}

private fun AccountIdentityMatches(a: String, b: String): Boolean =
    com.wlzxj.tunnel.data.AccountIdentity.sameUser(a, b)
