package com.wlzxj.tunnel.jni

interface TunnelEventCallback {
    fun onEvent(json: String)
}

object TunnelNative {
    init {
        System.loadLibrary("tunnel_client_jni")
    }

    external fun nativeStart(
        server: String,
        target: String,
        name: String?,
        authKind: String,
        authValue: String,
        username: String,
        allowInsecure: Boolean,
        callback: TunnelEventCallback,
    ): Long

    external fun nativeStop(generation: Long)

    external fun nativeIsRunning(): Boolean
}
