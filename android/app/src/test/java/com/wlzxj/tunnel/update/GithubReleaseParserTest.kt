package com.wlzxj.tunnel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleaseParserTest {
    @Test
    fun acceptsStableReleaseWithCanonicalAssetUrls() {
        val tag = "v0.2.4"
        val apkUrl = GithubReleaseUrls.releaseAssetDownloadUrl(tag, AppUpdateConstants.APK_ASSET_NAME)
        val shaUrl = GithubReleaseUrls.releaseAssetDownloadUrl(tag, AppUpdateConstants.SHA256_ASSET_NAME)
        val json = """
            {
              "tag_name": "$tag",
              "draft": false,
              "prerelease": false,
              "body": "notes",
              "assets": [
                {"name":"${AppUpdateConstants.APK_ASSET_NAME}","browser_download_url":"$apkUrl","size":1024},
                {"name":"${AppUpdateConstants.SHA256_ASSET_NAME}","browser_download_url":"$shaUrl","size":80}
              ]
            }
        """.trimIndent()
        val result = GithubReleaseParser.parseReleaseJson(json)
        if (result is ReleaseParseResult.Rejected) {
            throw AssertionError(result.reason)
        }
        assertTrue(result is ReleaseParseResult.Ok)
        val ok = result as ReleaseParseResult.Ok
        assertEquals(SemVer(0, 2, 4), ok.release.version)
    }

    @Test
    fun rejectsDraftPrereleaseAndMissingAssets() {
        assertTrue(
            GithubReleaseParser.parseReleaseJson("""{"tag_name":"v1.0.0","draft":true,"prerelease":false,"assets":[]}""")
                is ReleaseParseResult.Rejected,
        )
        assertTrue(
            GithubReleaseParser.parseReleaseJson("""{"tag_name":"v1.0.0","draft":false,"prerelease":true,"assets":[]}""")
                is ReleaseParseResult.Rejected,
        )
        assertTrue(
            GithubReleaseParser.parseReleaseJson(
                """{"tag_name":"v1.0.0","draft":false,"prerelease":false,"assets":[{"name":"other","browser_download_url":"https://github.com/ly-wo/wss-tunnel/releases/download/v1.0.0/other","size":1}]}""",
            ) is ReleaseParseResult.Rejected,
        )
    }

    @Test
    fun rejectsCdnAssetUrlNotUnderReleaseDownload() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name":"${AppUpdateConstants.APK_ASSET_NAME}","browser_download_url":"https://objects.githubusercontent.com/x.apk","size":10},
                {"name":"${AppUpdateConstants.SHA256_ASSET_NAME}","browser_download_url":"https://github.com/ly-wo/wss-tunnel/releases/download/v1.0.0/${AppUpdateConstants.SHA256_ASSET_NAME}","size":10}
              ]
            }
        """.trimIndent()
        assertTrue(GithubReleaseParser.parseReleaseJson(json) is ReleaseParseResult.Rejected)
    }

    @Test
    fun rejectsNonHttpsAssetUrl() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name":"${AppUpdateConstants.APK_ASSET_NAME}","browser_download_url":"http://github.com/ly-wo/wss-tunnel/releases/download/v1.0.0/${AppUpdateConstants.APK_ASSET_NAME}","size":10},
                {"name":"${AppUpdateConstants.SHA256_ASSET_NAME}","browser_download_url":"https://github.com/ly-wo/wss-tunnel/releases/download/v1.0.0/${AppUpdateConstants.SHA256_ASSET_NAME}","size":10}
              ]
            }
        """.trimIndent()
        assertTrue(GithubReleaseParser.parseReleaseJson(json) is ReleaseParseResult.Rejected)
    }
}
