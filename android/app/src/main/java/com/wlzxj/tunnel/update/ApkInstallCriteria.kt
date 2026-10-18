package com.wlzxj.tunnel.update

data class InstalledAppSnapshot(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signingCertSha256: Set<String>,
)

data class ApkArchiveSnapshot(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signingCertSha256: Set<String>,
)

sealed class InstallEligibility {
    data object Ok : InstallEligibility()
    data class Rejected(val message: String) : InstallEligibility()
}

object ApkInstallCriteria {
    fun evaluate(
        installed: InstalledAppSnapshot,
        apk: ApkArchiveSnapshot,
        targetVersionName: String,
    ): InstallEligibility {
        if (apk.packageName != installed.packageName) {
            return InstallEligibility.Rejected(
                "安装包包名与当前应用不一致，无法覆盖升级（当前 ${installed.packageName}）",
            )
        }
        if (apk.versionCode <= installed.versionCode) {
            return InstallEligibility.Rejected("安装包版本号不高于当前版本，无法降级或重复安装")
        }
        val apkName = apk.versionName?.trim()
        val expectedName = targetVersionName.removePrefix("v").trim()
        if (apkName == null || apkName != expectedName) {
            return InstallEligibility.Rejected(
                "安装包版本名与发布版本不一致（期望 $expectedName）",
            )
        }
        if (installed.signingCertSha256.isEmpty() || apk.signingCertSha256.isEmpty()) {
            return InstallEligibility.Rejected("无法验证安装包签名")
        }
        if (installed.signingCertSha256 != apk.signingCertSha256) {
            return InstallEligibility.Rejected(
                "安装包签名与当前已安装应用不一致。若您曾安装过 GitHub Actions 随机调试签名的旧包，需先手动卸载旧包后再安装（会清除应用数据）；配置稳定签名后的新版本才支持应用内覆盖升级。",
            )
        }
        return InstallEligibility.Ok
    }
}
