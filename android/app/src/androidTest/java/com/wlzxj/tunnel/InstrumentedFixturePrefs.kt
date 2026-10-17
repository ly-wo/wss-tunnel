package com.wlzxj.tunnel

import android.content.Context
import com.wlzxj.tunnel.data.AppPreferences
import com.wlzxj.tunnel.data.ConnectionPrefs
import kotlinx.coroutines.runBlocking

object InstrumentedFixturePrefs {
    fun baseline(origin: String, target: String): ConnectionPrefs =
        ConnectionPrefs(
            server = origin,
            target = target,
            subdomain = "",
            saveConnectionToken = false,
            debugAllowLocalHttp = true,
        )

    fun restore(context: Context, baseline: ConnectionPrefs) {
        runBlocking {
            AppPreferences(context).updateConnection { baseline }
        }
    }
}
