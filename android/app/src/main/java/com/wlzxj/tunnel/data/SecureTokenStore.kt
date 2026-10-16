package com.wlzxj.tunnel.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureTokenStore(private val context: Context) {
    init {
        context.deleteSharedPreferences("tunnel_secrets_fallback")
    }

    private var securePrefs = openPrefs()

    val keystoreAvailable: Boolean
        get() = securePrefs != null

    private fun openPrefs() = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tunnel_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private fun resetCorruptedStore() {
        context.deleteSharedPreferences("tunnel_secrets")
        securePrefs = openPrefs()
    }

    fun loadToken(serverOrigin: String): String? {
        val prefs = securePrefs ?: return null
        return runCatching {
            prefs.getString(key(serverOrigin), null)?.takeIf { it.isNotBlank() }
        }.getOrElse {
            resetCorruptedStore()
            null
        }
    }

    fun saveToken(serverOrigin: String, token: String): Boolean {
        val prefs = securePrefs ?: return false
        return runCatching {
            prefs.edit().putString(key(serverOrigin), token).apply()
            true
        }.getOrElse {
            resetCorruptedStore()
            false
        }
    }

    fun clearToken(serverOrigin: String) {
        val prefs = securePrefs ?: return
        runCatching {
            prefs.edit().remove(key(serverOrigin)).apply()
        }.onFailure {
            resetCorruptedStore()
        }
    }

    fun clearAll() {
        val prefs = securePrefs ?: return
        runCatching {
            prefs.edit().clear().apply()
        }.onFailure {
            resetCorruptedStore()
        }
    }

    private fun key(serverOrigin: String): String =
        "conn_token:${serverOrigin.trim().removeSuffix("/")}"
}
