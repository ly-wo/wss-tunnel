package com.wlzxj.tunnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wlzxj.tunnel.update.AndroidApkArchiveInspector
import com.wlzxj.tunnel.update.ApkInstallCriteria
import com.wlzxj.tunnel.update.AppUpdateConstants
import com.wlzxj.tunnel.update.AppUpdateRepository
import com.wlzxj.tunnel.update.DownloadResult
import com.wlzxj.tunnel.update.GithubReleaseUrls
import com.wlzxj.tunnel.update.InstallEligibility
import com.wlzxj.tunnel.update.MockGithubTransport
import com.wlzxj.tunnel.update.SemVer
import com.wlzxj.tunnel.update.UpdateCheckResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppUpdateDownloadInstrumentedTest {
    @Test
    fun repositoryDownloadsFixtureAndInspectorAcceptsHigherVersion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UpdateFixtureSupport.requireFixture(context, UpdateFixtureSupport.GOOD_FIXTURE)
        val payload = fixture.readBytes()
        val hash = sha256Hex(payload)
        val tag = "v0.2.5"
        val server = MockWebServer()
        server.start()
        try {
            enqueueRelease(server, tag, payload.size.toLong(), hash, payload)
            val updatesDir = File(context.cacheDir, "updates-test-${System.nanoTime()}")
            val current = SemVer.parseTag("v${BuildConfig.VERSION_NAME}")!!
            val repo = AppUpdateRepository(
                updatesDir = updatesDir,
                currentVersion = current,
                okhttpClient = MockGithubTransport.okhttpClient(server),
            )
            val check = repo.checkForUpdate()
            assertTrue(check is UpdateCheckResult.UpdateAvailable)
            val download = repo.downloadRelease((check as UpdateCheckResult.UpdateAvailable).release) {}
            assertTrue(download is DownloadResult.Success)
            val verified = (download as DownloadResult.Success).verified
            val inspector = AndroidApkArchiveInspector(context.packageManager)
            val installed = inspector.readInstalled(context.packageName)
                ?: throw AssertionError("无法读取已安装应用")
            val archive = inspector.readArchive(verified.apkFile)
                ?: throw AssertionError("无法读取下载的 APK")
            assertEquals(
                "fixture 与当前安装包须为同签名稳定环境",
                installed.signingCertSha256,
                archive.signingCertSha256,
            )
            assertTrue(archive.signingCertSha256.isNotEmpty())
            val eligibility = ApkInstallCriteria.evaluate(installed, archive, "0.2.5")
            assertTrue("同签名更高版本应允许安装：$eligibility", eligibility is InstallEligibility.Ok)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun corruptDownloadedApkClearsReadyAndAllowsRedownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UpdateFixtureSupport.requireFixture(context, UpdateFixtureSupport.GOOD_FIXTURE)
        val payload = fixture.readBytes()
        val hash = sha256Hex(payload)
        val tag = "v0.2.5"
        val server = MockWebServer()
        server.start()
        try {
            enqueueRelease(server, tag, payload.size.toLong(), hash, payload)
            val vm = UpdateVmTestSupport.downloadUpdateViaViewModel(context, server, payload, tag)
            assertTrue(vm.uiState.value.downloadedApkReady)
            val apkPath = File(context.cacheDir, "updates/${AppUpdateConstants.APK_ASSET_NAME}")
            assertTrue(apkPath.isFile)
            val bytes = apkPath.readBytes()
            bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
            apkPath.writeBytes(bytes)

            val blocked = vm.prepareInstallIntent(context)
            assertTrue(blocked == null)
            assertFalse(vm.uiState.value.downloadedApkReady)

            server.enqueue(MockResponse().setBody("$hash  ${AppUpdateConstants.APK_ASSET_NAME}\n"))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            vm.downloadUpdate()
            UpdateVmTestSupport.waitUntil("损坏后应能重新下载") { vm.uiState.value.downloadedApkReady }
        } finally {
            server.shutdown()
        }
    }

    private fun enqueueRelease(
        server: MockWebServer,
        tag: String,
        apkSize: Long,
        hash: String,
        payload: ByteArray,
    ) {
        val apkUrl = GithubReleaseUrls.releaseAssetDownloadUrl(tag, AppUpdateConstants.APK_ASSET_NAME)
        val shaUrl = GithubReleaseUrls.releaseAssetDownloadUrl(tag, AppUpdateConstants.SHA256_ASSET_NAME)
        server.enqueue(
            MockResponse().setBody(
                """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[
                  {"name":"${AppUpdateConstants.APK_ASSET_NAME}","browser_download_url":"$apkUrl","size":$apkSize},
                  {"name":"${AppUpdateConstants.SHA256_ASSET_NAME}","browser_download_url":"$shaUrl","size":80}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("$hash  ${AppUpdateConstants.APK_ASSET_NAME}\n"))
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
