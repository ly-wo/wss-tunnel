package com.wlzxj.tunnel.update

import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallCriteriaTest {
    private val installed = InstalledAppSnapshot(
        packageName = "com.wlzxj.tunnel.debug",
        versionCode = 4,
        versionName = "0.2.3",
        signingCertSha256 = setOf("aa"),
    )

    @Test
    fun rejectsPackageMismatchDowngradeAndSignature() {
        assertTrue(
            ApkInstallCriteria.evaluate(
                installed,
                installedApk(packageName = "com.other", versionCode = 5, versionName = "0.2.4", sig = setOf("aa")),
                "0.2.4",
            ) is InstallEligibility.Rejected,
        )
        assertTrue(
            ApkInstallCriteria.evaluate(
                installed,
                installedApk(versionCode = 4, versionName = "0.2.4", sig = setOf("aa")),
                "0.2.4",
            ) is InstallEligibility.Rejected,
        )
        assertTrue(
            ApkInstallCriteria.evaluate(
                installed,
                installedApk(versionCode = 5, versionName = "0.2.4", sig = setOf("bb")),
                "0.2.4",
            ) is InstallEligibility.Rejected,
        )
    }

    @Test
    fun acceptsHigherVersionWithMatchingSignatureAndName() {
        assertTrue(
            ApkInstallCriteria.evaluate(
                installed,
                installedApk(versionCode = 5, versionName = "0.2.4", sig = setOf("aa")),
                "0.2.4",
            ) is InstallEligibility.Ok,
        )
    }

    private fun installedApk(
        packageName: String = "com.wlzxj.tunnel.debug",
        versionCode: Long,
        versionName: String,
        sig: Set<String>,
    ) = ApkArchiveSnapshot(packageName, versionCode, versionName, sig)
}
