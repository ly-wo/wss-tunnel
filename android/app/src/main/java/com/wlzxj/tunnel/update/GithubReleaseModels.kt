package com.wlzxj.tunnel.update

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

data class ParsedRelease(
    val version: SemVer,
    val tagName: String,
    val body: String?,
    val apk: ReleaseAsset,
    val sha256: ReleaseAsset,
)

sealed class ReleaseParseResult {
    data class Ok(val release: ParsedRelease) : ReleaseParseResult()
    data class Rejected(val reason: String) : ReleaseParseResult()
}
