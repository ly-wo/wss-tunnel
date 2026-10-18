package com.wlzxj.tunnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wlzxj.tunnel.update.AndroidApkArchiveInspector
import com.wlzxj.tunnel.update.ApkInstallCriteria
import com.wlzxj.tunnel.update.InstallEligibility
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateWrongSignatureInstrumentedTest {
    @Test
    fun wrongSignatureFixtureIsRejectedByProductCriteria() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UpdateFixtureSupport.requireFixture(
            context,
            UpdateFixtureSupport.WRONG_SIGNATURE_FIXTURE,
        )

        val inspector = AndroidApkArchiveInspector(context.packageManager)
        val installed = inspector.readInstalled(context.packageName)
            ?: throw AssertionError("无法读取已安装应用")
        val archive = inspector.readArchive(fixture)
            ?: throw AssertionError("无法读取错误签名 fixture")

        val result = ApkInstallCriteria.evaluate(
            installed = installed,
            apk = archive,
            targetVersionName = archive.versionName ?: "0.2.5",
        )
        assertTrue("错误签名应拒绝：$result", result is InstallEligibility.Rejected)
    }
}
