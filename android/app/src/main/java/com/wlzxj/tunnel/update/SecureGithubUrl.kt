package com.wlzxj.tunnel.update

import java.net.URI

fun interface DownloadHostPolicy {
    fun isAllowed(host: String): Boolean
}

object GithubDownloadHostPolicy : DownloadHostPolicy {
    override fun isAllowed(host: String): Boolean = AppUpdateConstants.isAllowedDownloadHost(host)
}

object SecureGithubUrl {
    fun parseHttps(url: String, hostPolicy: DownloadHostPolicy = GithubDownloadHostPolicy): URI {
        val uri = try {
            URI(url.trim())
        } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("下载链接无效", e)
        }
        require(uri.scheme?.equals("https", ignoreCase = true) == true) { "仅允许 HTTPS 下载链接" }
        val host = uri.host ?: throw IllegalArgumentException("下载链接无效")
        require(hostPolicy.isAllowed(host)) { "下载链接主机不在允许列表" }
        require(uri.userInfo == null) { "下载链接不能包含凭据" }
        return uri
    }

    fun validateFixedLatestEndpoint(
        url: String,
        hostPolicy: DownloadHostPolicy = GithubDownloadHostPolicy,
    ) {
        require(url == AppUpdateConstants.RELEASES_LATEST_URL) { "更新检查地址无效" }
        parseHttps(url, hostPolicy)
    }
}
