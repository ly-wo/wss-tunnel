package com.wlzxj.tunnel.update

import java.net.URI

object GithubReleaseUrls {
    private const val OWNER_REPO = "ly-wo/wss-tunnel"

    fun releaseAssetDownloadUrl(tagName: String, assetFileName: String): String =
        "https://github.com/$OWNER_REPO/releases/download/${encodeTagPath(tagName)}/$assetFileName"

    fun validateReleaseAssetUrl(url: String, tagName: String, assetFileName: String): URI {
        val expected = releaseAssetDownloadUrl(tagName, assetFileName)
        require(url == expected) { "发布资源链接无效" }
        val uri = SecureGithubUrl.parseHttps(url)
        require(uri.host.equals("github.com", ignoreCase = true)) { "发布资源链接无效" }
        val path = uri.path ?: throw IllegalArgumentException("发布资源链接无效")
        require(path == "/$OWNER_REPO/releases/download/${encodeTagPath(tagName)}/$assetFileName") {
            "发布资源链接无效"
        }
        val port = uri.port
        require(port == -1 || port == 443) { "发布资源链接无效" }
        return uri
    }

    private fun encodeTagPath(tagName: String): String {
        // GitHub release tags in download URLs use the tag as-is (e.g. v0.2.4).
        require(tagName.isNotBlank() && !tagName.contains("/")) { "发布版本号无效" }
        return tagName
    }
}
