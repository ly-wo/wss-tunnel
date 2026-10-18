package com.wlzxj.tunnel.update

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class AppUpdateRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun repository(server: MockWebServer, current: SemVer): AppUpdateRepository {
        val client = MockGithubTransport.okhttpClient(server)
        return AppUpdateRepository(
            updatesDir = temp.newFolder("updates-${System.nanoTime()}"),
            currentVersion = current,
            okhttpClient = client,
        )
    }

    private fun partFile(repoDir: File): File =
        File(repoDir, "${AppUpdateConstants.APK_ASSET_NAME}.part")

    @Test
    fun reportsUpdateWhenRemoteIsNewer() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(releaseResponse("v0.2.4", apkSize = 8))
            val repo = repository(server, SemVer(0, 2, 3))
            val result = repo.checkForUpdate()
            if (result is UpdateCheckResult.Failed) {
                throw AssertionError(result.message)
            }
            assertTrue(result is UpdateCheckResult.UpdateAvailable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun upToDateWhenEqualVersion() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(releaseResponse("v0.2.3", apkSize = 8))
            val repo = repository(server, SemVer(0, 2, 3))
            assertTrue(repo.checkForUpdate() is UpdateCheckResult.UpToDate)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun downloadVerifiesSha256() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val payload = "fake-apk".toByteArray()
            val hash = sha256Hex(payload)
            val tag = "v0.2.4"
            server.enqueue(releaseResponse(tag, apkSize = payload.size.toLong()))
            server.enqueue(
                MockResponse().setBody("$hash  ${AppUpdateConstants.APK_ASSET_NAME}\n"),
            )
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val repo = repository(server, SemVer(0, 2, 3))
            val check = repo.checkForUpdate()
            assertTrue(check is UpdateCheckResult.UpdateAvailable)
            val download = repo.downloadRelease((check as UpdateCheckResult.UpdateAvailable).release) {}
            assertTrue(download is DownloadResult.Success)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsCorruptSha256() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val payload = "fake-apk".toByteArray()
            val tag = "v0.2.4"
            server.enqueue(releaseResponse(tag, apkSize = payload.size.toLong()))
            server.enqueue(
                MockResponse().setBody("${"0".repeat(64)}  ${AppUpdateConstants.APK_ASSET_NAME}\n"),
            )
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val repo = repository(server, SemVer(0, 2, 3))
            val check = repo.checkForUpdate() as UpdateCheckResult.UpdateAvailable
            val download = repo.downloadRelease(check.release) {}
            assertTrue(download is DownloadResult.Failed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsTruncatedApk() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val payload = "12345678".toByteArray()
            val tag = "v0.2.4"
            val hash = sha256Hex(payload)
            server.enqueue(releaseResponse(tag, apkSize = payload.size.toLong()))
            server.enqueue(
                MockResponse().setBody("$hash  ${AppUpdateConstants.APK_ASSET_NAME}\n"),
            )
            server.enqueue(MockResponse().setBody(Buffer().write("short".toByteArray())))
            val repo = repository(server, SemVer(0, 2, 3))
            val check = repo.checkForUpdate() as UpdateCheckResult.UpdateAvailable
            val download = repo.downloadRelease(check.release) {}
            assertTrue(download is DownloadResult.Failed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsHttpRedirect() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://evil.example/apk"),
            )
            val repo = repository(server, SemVer(0, 2, 3))
            assertTrue(repo.checkForUpdate() is UpdateCheckResult.Failed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancelStopsDownloadAndClearsPartFile() = runBlocking {
        val server = MockWebServer()
        server.start()
        val updatesDir = temp.newFolder("cancel-updates")
        try {
            val tag = "v0.2.4"
            val bigSize = 512 * 1024L
            server.enqueue(releaseResponse(tag, apkSize = bigSize))
            server.enqueue(MockResponse().setBody("${"a".repeat(64)}  ${AppUpdateConstants.APK_ASSET_NAME}\n"))
            server.enqueue(
                MockResponse()
                    .setBody(Buffer().write(ByteArray(512 * 1024) { 1 }))
                    .throttleBody(2048, 100, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            val repo = AppUpdateRepository(
                updatesDir = updatesDir,
                currentVersion = SemVer(0, 2, 3),
                okhttpClient = MockGithubTransport.okhttpClient(server),
            )
            val check = repo.checkForUpdate() as UpdateCheckResult.UpdateAvailable
            val part = partFile(updatesDir)
            val downloadDeferred = async {
                repo.downloadRelease(check.release) {}
            }
            delay(300)
            repo.requestCancelDownload()
            val result = downloadDeferred.await()
            assertTrue(result is DownloadResult.Cancelled)
            assertFalse(part.exists())
        } finally {
            server.shutdown()
        }
    }

    private fun releaseResponse(tag: String, apkSize: Long): MockResponse {
        val apkUrl = GithubReleaseUrls.releaseAssetDownloadUrl(tag, AppUpdateConstants.APK_ASSET_NAME)
        val shaUrl = GithubReleaseUrls.releaseAssetDownloadUrl(tag, AppUpdateConstants.SHA256_ASSET_NAME)
        val body = """
            {
              "tag_name": "$tag",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name":"${AppUpdateConstants.APK_ASSET_NAME}","browser_download_url":"$apkUrl","size":$apkSize},
                {"name":"${AppUpdateConstants.SHA256_ASSET_NAME}","browser_download_url":"$shaUrl","size":80}
              ]
            }
        """.trimIndent()
        return MockResponse().setBody(body)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
