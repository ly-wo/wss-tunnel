package com.wlzxj.tunnel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tunnel_prefs")

data class ConnectionPrefs(
    val server: String = "https://connect.demo.wlzxj.com",
    val target: String = "http://127.0.0.1:3000",
    val subdomain: String = "",
    val saveConnectionToken: Boolean = false,
    val debugAllowLocalHttp: Boolean = false,
)

class AppPreferences(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")
    private val targetKey = stringPreferencesKey("target")
    private val subdomainKey = stringPreferencesKey("subdomain")
    private val saveTokenKey = booleanPreferencesKey("save_conn_token")
    private val debugLocalKey = booleanPreferencesKey("debug_local_http")

    val connectionPrefs: Flow<ConnectionPrefs> = context.dataStore.data.map { prefs ->
        ConnectionPrefs(
            server = prefs[serverKey] ?: ConnectionPrefs().server,
            target = prefs[targetKey] ?: ConnectionPrefs().target,
            subdomain = prefs[subdomainKey] ?: "",
            saveConnectionToken = prefs[saveTokenKey] ?: false,
            debugAllowLocalHttp = prefs[debugLocalKey] ?: false,
        )
    }

    suspend fun updateConnection(transform: (ConnectionPrefs) -> ConnectionPrefs) {
        context.dataStore.edit { prefs ->
            val current = ConnectionPrefs(
                server = prefs[serverKey] ?: ConnectionPrefs().server,
                target = prefs[targetKey] ?: ConnectionPrefs().target,
                subdomain = prefs[subdomainKey] ?: "",
                saveConnectionToken = prefs[saveTokenKey] ?: false,
                debugAllowLocalHttp = prefs[debugLocalKey] ?: false,
            )
            val next = transform(current)
            prefs[serverKey] = next.server
            prefs[targetKey] = next.target
            prefs[subdomainKey] = next.subdomain
            prefs[saveTokenKey] = next.saveConnectionToken
            prefs[debugLocalKey] = next.debugAllowLocalHttp
        }
    }

    suspend fun setSubdomainForReuse(name: String) {
        context.dataStore.edit { it[subdomainKey] = name }
    }
}
