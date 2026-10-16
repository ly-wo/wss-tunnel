package com.wlzxj.tunnel.data

import com.wlzxj.tunnel.BuildConfig
import java.net.URI

object ApiOrigin {
    fun resolve(
        raw: String,
        debugAllowLocalHttp: Boolean,
    ): String {
        val trimmed = raw.trim().removeSuffix("/")
        require(trimmed.isNotEmpty()) { "接入地址不能为空" }
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "接入地址须为 https:// 或 http://" }
        require(uri.host != null && uri.host.isNotBlank()) { "接入地址无效" }
        require(uri.userInfo == null) { "不要在地址中包含账号密码" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "请只填写服务器根地址（不含路径）" }
        require(uri.query == null && uri.fragment == null) { "地址不能包含查询或片段" }
        val port = uri.port
        require(port == -1 || port in 1..65535) { "端口无效" }
        if (scheme == "http") {
            val allow = BuildConfig.DEBUG && debugAllowLocalHttp && Validation.isLocalDebugHost(trimmed)
            require(allow) { "正式环境仅允许 HTTPS 接入；Debug 可在设置中开启本地 HTTP 调试" }
        }
        return buildString {
            append(scheme)
            append("://")
            append(uri.host)
            if (port != -1) append(':').append(port)
        }
    }
}
