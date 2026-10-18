package com.wlzxj.tunnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wlzxj.tunnel.update.AndroidApkArchiveInspector
import com.wlzxj.tunnel.update.ApkInstallCriteria
import com.wlzxj.tunnel.update.InstallEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateInstallEligibilityInstrumentedTest {
    @Test
    fun fixtureApkPassesProductInstallCriteriaAgainstInstalledApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UpdateFixtureSupport.requireFixture(context, UpdateFixtureSupport.GOOD_FIXTURE)

        val inspector = AndroidApkArchiveInspector(context.packageManager)
        val installed = inspector.readInstalled(context.packageName)
            ?: throw AssertionError("无法读取已安装应用")
        val archive = inspector.readArchive(fixture)
            ?: throw AssertionError("无法读取 fixture APK")

        assertEquals(
            "同签名 fixture 环境要求",
            installed.signingCertSha256,
            archive.signingCertSha256,
        )
        assertTrue(archive.signingCertSha256.isNotEmpty())

        val result = ApkInstallCriteria.evaluate(
            installed = installed,
            apk = archive,
            targetVersionName = archive.versionName ?: "0.2.5",
        )
        assertTrue("同签名应允许升级：$result", result is InstallEligibility.Ok)
    }
}
