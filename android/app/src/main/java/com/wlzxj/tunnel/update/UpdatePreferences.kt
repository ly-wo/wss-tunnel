package com.wlzxj.tunnel.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_update")

class UpdatePreferences(private val context: Context) {
    private val lastStartupCheckKey = longPreferencesKey("last_startup_check_ms")

    suspend fun lastStartupCheckEpochMs(): Long =
        context.updateDataStore.data.map { it[lastStartupCheckKey] ?: 0L }.first()

    suspend fun markStartupCheckAttempt(nowMs: Long) {
        context.updateDataStore.edit { prefs ->
            prefs[lastStartupCheckKey] = nowMs
        }
    }
}
