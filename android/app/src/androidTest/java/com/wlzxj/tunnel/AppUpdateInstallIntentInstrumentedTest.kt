package com.wlzxj.tunnel

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wlzxj.tunnel.update.AppUpdateConstants
import com.wlzxj.tunnel.update.GithubReleaseUrls
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppUpdateInstallIntentInstrumentedTest {
    @Test
    fun prepareInstallIntentWhenInstallPermissionDenied() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UpdateFixtureSupport.requireFixture(context, UpdateFixtureSupport.GOOD_FIXTURE)
        val payload = fixture.readBytes()
        val hash = sha256Hex(payload)
        val tag = "v0.2.5"
        val server = MockWebServer()
        server.start()
        try {
            enqueueRelease(server, tag, payload.size.toLong(), hash, payload)
            UpdateFixtureSupport.setRequestInstallPackages(context, allow = false)
            val vm = UpdateVmTestSupport.downloadUpdateViaViewModel(context, server, payload, tag)
            assertTrue(vm.uiState.value.downloadedApkReady)
            val intent = vm.prepareInstallIntent(context)
            assertNull(intent)
            assertTrue(
                vm.uiState.value.installBlockedMessage?.contains("安装未知应用") == true,
            )
            assertTrue(vm.uiState.value.downloadedApkReady)
        } finally {
            UpdateFixtureSupport.setRequestInstallPackages(context, allow = true)
            server.shutdown()
        }
    }

    @Test
    fun prepareInstallIntentWhenInstallPermissionGranted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UpdateFixtureSupport.requireFixture(context, UpdateFixtureSupport.GOOD_FIXTURE)
        val payload = fixture.readBytes()
        val hash = sha256Hex(payload)
        val tag = "v0.2.5"
        val server = MockWebServer()
        server.start()
        try {
            enqueueRelease(server, tag, payload.size.toLong(), hash, payload)
            UpdateFixtureSupport.setRequestInstallPackages(context, allow = true)
            val vm = UpdateVmTestSupport.downloadUpdateViaViewModel(context, server, payload, tag)
            val intent = vm.prepareInstallIntent(context)
            assertNotNull(intent)
            assertEquals("application/vnd.android.package-archive", intent!!.type)
            assertTrue(intent.data.toString().startsWith("content://"))
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(intent.data!!)!!.use { stream ->
                assertTrue(stream.read() != -1)
            }
            UpdateFixtureSupport.launchInstallerIfRequested(intent)
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
