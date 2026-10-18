package com.wlzxj.tunnel.update

import org.json.JSONArray
import org.json.JSONObject

object GithubReleaseParser {
    fun parseReleaseJson(json: String): ReleaseParseResult {
        return try {
            val root = JSONObject(json)
            if (root.optBoolean("draft", true)) {
                return ReleaseParseResult.Rejected("该发布为草稿，不提供更新")
            }
            if (root.optBoolean("prerelease", false)) {
                return ReleaseParseResult.Rejected("该发布为预发布版本，不提供更新")
            }
            val tag = root.optString("tag_name", "").trim()
            val version = SemVer.parseTag(tag)
                ?: return ReleaseParseResult.Rejected("发布版本号无效")
            val body = root.optString("body", "").takeIf { it.isNotBlank() }
            val assets = root.optJSONArray("assets") ?: JSONArray()
            var apk: ReleaseAsset? = null
            var sha: ReleaseAsset? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (url.isBlank()) continue
                val size = asset.optLong("size", -1)
                if (size <= 0) {
                    return ReleaseParseResult.Rejected("发布资源大小无效")
                }
                when (name) {
                    AppUpdateConstants.APK_ASSET_NAME -> {
                        try {
                            GithubReleaseUrls.validateReleaseAssetUrl(url, tag, AppUpdateConstants.APK_ASSET_NAME)
                        } catch (_: IllegalArgumentException) {
                            return ReleaseParseResult.Rejected("发布资源链接无效")
                        }
                        apk = ReleaseAsset(name, url, size)
                    }
                    AppUpdateConstants.SHA256_ASSET_NAME -> {
                        try {
                            GithubReleaseUrls.validateReleaseAssetUrl(url, tag, AppUpdateConstants.SHA256_ASSET_NAME)
                        } catch (_: IllegalArgumentException) {
                            return ReleaseParseResult.Rejected("发布资源链接无效")
                        }
                        sha = ReleaseAsset(name, url, size)
                    }
                }
            }
            val apkAsset = apk ?: return ReleaseParseResult.Rejected("发布中缺少 APK")
            val shaAsset = sha ?: return ReleaseParseResult.Rejected("发布中缺少校验文件")
            if (apkAsset.size > AppUpdateConstants.MAX_APK_BYTES) {
                return ReleaseParseResult.Rejected("APK 体积超出允许上限")
            }
            if (shaAsset.size > AppUpdateConstants.MAX_SHA256_FILE_BYTES) {
                return ReleaseParseResult.Rejected("校验文件无效")
            }
            ReleaseParseResult.Ok(
                ParsedRelease(
                    version = version,
                    tagName = tag,
                    body = body,
                    apk = apkAsset,
                    sha256 = shaAsset,
                ),
            )
        } catch (_: Exception) {
            ReleaseParseResult.Rejected("无法解析发布信息")
        }
    }
}
