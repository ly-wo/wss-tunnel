package com.wlzxj.tunnel.update

object AppUpdateConstants {
    const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/ly-wo/wss-tunnel/releases/latest"

    const val APK_ASSET_NAME = "wss-tunnel-android-arm64-debug.apk"
    const val SHA256_ASSET_NAME = "$APK_ASSET_NAME.sha256"

    const val GITHUB_USER_AGENT = "wss-tunnel-android"

    /** Maximum JSON body for release metadata. */
    const val MAX_METADATA_BYTES = 256 * 1024

    /** Maximum `.sha256` sidecar size. */
    const val MAX_SHA256_FILE_BYTES = 512

    /** APK download size cap (200 MiB). */
    const val MAX_APK_BYTES: Long = 200L * 1024 * 1024

    const val DOWNLOAD_READ_TIMEOUT_SEC = 120L
    const val DOWNLOAD_CONNECT_TIMEOUT_SEC = 30L
    const val DOWNLOAD_CALL_TIMEOUT_SEC = 180L
    const val METADATA_TIMEOUT_SEC = 30L

    const val STARTUP_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    val ALLOWED_DOWNLOAD_HOST_SUFFIXES = listOf(
        "github.com",
        "githubusercontent.com",
    )

    fun isAllowedDownloadHost(host: String): Boolean {
        val lower = host.lowercase()
        return ALLOWED_DOWNLOAD_HOST_SUFFIXES.any { suffix ->
            lower == suffix || lower.endsWith(".$suffix")
        }
    }
}
