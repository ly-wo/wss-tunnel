package com.wlzxj.tunnel.data

import com.wlzxj.tunnel.BuildConfig
import java.net.URI

object Validation {
    private val subdomainRegex = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun requireHttpsForRelease(server: String, debugAllowLocal: Boolean): String =
        ApiOrigin.resolve(server, debugAllowLocal)

    fun isLocalDebugHost(origin: String): Boolean {
        val host = URI(origin).host?.lowercase() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
    }

    /** Display-safe target label (host:port only, no userinfo/path/query). */
    fun normalizeTarget(input: String): String {
        val trimmed = input.trim()
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss") {
            "目标须为 http(s):// 或 ws(s)://"
        }
        require(uri.host != null && uri.host.isNotBlank()) { "目标地址无效" }
        require(uri.userInfo == null) { "目标地址不能包含账号密码" }
        require(uri.query == null && uri.fragment == null) { "目标请使用根地址，不要带查询或片段" }
        val path = uri.path ?: ""
        require(path.isEmpty() || path == "/") { "目标请使用服务根地址" }
        val port = uri.port
        require(port == -1 || port in 1..65535) { "端口无效" }
        return buildString {
            append(scheme)
            append("://")
            append(uri.host)
            if (port != -1) append(':').append(port)
            if (path == "/" && trimmed.endsWith("/")) append('/')
        }
    }

    fun targetDisplayLabel(target: String): String {
        return runCatching { normalizeTarget(target) }.getOrElse { "本地服务" }
    }

    fun optionalSubdomain(name: String?): String? {
        val value = name?.trim()?.takeIf { it.isNotEmpty() }
        if (value == null) return null
        require(subdomainRegex.matches(value)) { "子域名须为小写字母、数字或连字符，1-63 位" }
        return value
    }
}
