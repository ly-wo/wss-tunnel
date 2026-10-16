package com.wlzxj.tunnel

import android.app.Application
import com.wlzxj.tunnel.data.AppPreferences
import com.wlzxj.tunnel.data.SecureTokenStore
import com.wlzxj.tunnel.data.TunnelApi

class TunnelApp : Application() {
    lateinit var preferences: AppPreferences
    lateinit var secureTokens: SecureTokenStore
    lateinit var api: TunnelApi

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        secureTokens = SecureTokenStore(this)
        api = TunnelApi()
    }
}
