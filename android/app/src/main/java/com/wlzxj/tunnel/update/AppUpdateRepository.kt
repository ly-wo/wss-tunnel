package com.wlzxj.tunnel.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.math.min

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ParsedRelease) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

data class DownloadProgress(val bytesRead: Long, val totalBytes: Long?)

data class VerifiedDownload(
    val apkFile: File,
    val expectedSha256: String,
    val release: ParsedRelease,
)

sealed class DownloadResult {
    data class Success(val verified: VerifiedDownload) : DownloadResult()
    data class Failed(val message: String) : DownloadResult()
    data object Cancelled : DownloadResult()
}

class AppUpdateRepository(
    private val updatesDir: File,
    private val currentVersion: SemVer,
    private val releasesUrl: String = AppUpdateConstants.RELEASES_LATEST_URL,
    okhttpClient: OkHttpClient? = null,
) {
    private val redirectClient = HttpsRedirectClient(client = okhttpClient)
    private val downloadMutex = Mutex()
    @Volatile
    private var cancelRequested = false

    fun requestCancelDownload() {
        cancelRequested = true
        redirectClient.cancelInFlight()
    }

    fun cancelAllInFlight() {
        requestCancelDownload()
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        cancelRequested = false
        try {
            SecureGithubUrl.validateFixedLatestEndpoint(releasesUrl)
            val response = redirectClient.executeWithRedirects(releasesUrl) { builder ->
                builder
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", AppUpdateConstants.GITHUB_USER_AGENT)
                    .get()
            }
            response.use { resp ->
                when (resp.code) {
                    200 -> Unit
                    403, 429 -> return@withContext UpdateCheckResult.Failed("GitHub 访问受限，请稍后重试")
                    else -> return@withContext UpdateCheckResult.Failed(
                        "检查更新失败（HTTP ${resp.code}）",
                    )
                }
                val body = resp.body
                    ?: return@withContext UpdateCheckResult.Failed("检查更新失败：空响应")
                val text = BoundedBodyReader.readUtf8(body, AppUpdateConstants.MAX_METADATA_BYTES)
                    ?: return@withContext UpdateCheckResult.Failed("发布信息过大")
                val parsed = GithubReleaseParser.parseReleaseJson(text)
                when (parsed) {
                    is ReleaseParseResult.Rejected ->
                        return@withContext UpdateCheckResult.Failed(parsed.reason)
                    is ReleaseParseResult.Ok -> {
                        val release = parsed.release
                        when {
                            release.version > currentVersion ->
                                UpdateCheckResult.UpdateAvailable(release)
                            else -> UpdateCheckResult.UpToDate
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            if (cancelRequested) throw CancellationException("检查更新已取消", e)
            UpdateCheckResult.Failed(timeoutMessage(e))
        } catch (e: IOException) {
            if (cancelRequested) throw CancellationException("检查更新已取消", e)
            UpdateCheckResult.Failed("网络错误：${e.message ?: "请检查网络后重试"}")
        } catch (e: IllegalArgumentException) {
            UpdateCheckResult.Failed(e.message ?: "更新检查失败")
        }
    }

    suspend fun downloadRelease(
        release: ParsedRelease,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = downloadMutex.withLock {
        cancelRequested = false
        withContext(Dispatchers.IO) {
            val partFile = File(updatesDir, "${AppUpdateConstants.APK_ASSET_NAME}.part")
            try {
                updatesDir.mkdirs()
                cleanupPartialFiles()
                val expectedHash = fetchSha256(release.sha256.downloadUrl)
                    ?: return@withContext DownloadResult.Failed("无法读取校验文件")
                val totalExpected = release.apk.size
                val streamed = streamToFile(
                    url = release.apk.downloadUrl,
                    dest = partFile,
                    maxBytes = AppUpdateConstants.MAX_APK_BYTES,
                    expectedLength = totalExpected,
                    onProgress = onProgress,
                )
                if (streamed == null) {
                    partFile.delete()
                    return@withContext if (cancelRequested) {
                        DownloadResult.Cancelled
                    } else {
                        DownloadResult.Failed("下载失败")
                    }
                }
                val actualHash = sha256File(partFile)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    partFile.delete()
                    return@withContext DownloadResult.Failed("安装包校验失败，文件可能已损坏")
                }
                val apkFile = File(updatesDir, AppUpdateConstants.APK_ASSET_NAME)
                if (apkFile.exists()) apkFile.delete()
                if (!partFile.renameTo(apkFile)) {
                    partFile.copyTo(apkFile, overwrite = true)
                    partFile.delete()
                }
                DownloadResult.Success(
                    VerifiedDownload(
                        apkFile = apkFile,
                        expectedSha256 = expectedHash,
                        release = release,
                    ),
                )
            } catch (e: CancellationException) {
                partFile.delete()
                throw e
            } catch (e: InterruptedIOException) {
                partFile.delete()
                if (cancelRequested) DownloadResult.Cancelled
                else DownloadResult.Failed(timeoutMessage(e))
            } catch (e: IOException) {
                partFile.delete()
                if (cancelRequested) DownloadResult.Cancelled
                else DownloadResult.Failed("下载失败：${e.message ?: "网络错误"}")
            } catch (e: IllegalArgumentException) {
                partFile.delete()
                DownloadResult.Failed(e.message ?: "下载失败")
            }
        }
    }

    private fun timeoutMessage(e: InterruptedIOException): String =
        if (e is SocketTimeoutException) "网络超时，请稍后重试" else "网络中断，请稍后重试"

    private fun cleanupPartialFiles() {
        File(updatesDir, "${AppUpdateConstants.APK_ASSET_NAME}.part").delete()
    }

    private fun fetchSha256(url: String): String? {
        redirectClient.executeWithRedirects(url) { it.get() }.use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val text = BoundedBodyReader.readUtf8(body, AppUpdateConstants.MAX_SHA256_FILE_BYTES)
                ?: return null
            return Sha256Sidecar.parseExpectedHash(text)
        }
    }

    private fun streamToFile(
        url: String,
        dest: File,
        maxBytes: Long,
        expectedLength: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean? {
        redirectClient.executeWithRedirects(url) { it.get() }.use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > maxBytes || expectedLength > maxBytes) return null
            if (contentLength > 0 && contentLength != expectedLength) return null
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelRequested) return null
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IllegalArgumentException("安装包体积超出上限")
                        if (expectedLength > 0 && total > expectedLength) {
                            throw IllegalArgumentException("安装包大小与发布信息不一致")
                        }
                        out.write(buffer, 0, read)
                        onProgress(
                            DownloadProgress(
                                bytesRead = total,
                                totalBytes = expectedLength.takeIf { it > 0 }
                                    ?: contentLength.takeIf { it > 0 },
                            ),
                        )
                    }
                    if (expectedLength > 0 && total != expectedLength) {
                        throw IllegalArgumentException("安装包下载不完整")
                    }
                }
            }
            return true
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun formatProgress(progress: DownloadProgress): String {
        val total = progress.totalBytes
        if (total == null || total <= 0) {
            return "已下载 ${progress.bytesRead / 1024} KB"
        }
        val pct = min(100, (progress.bytesRead * 100 / total).toInt())
        return "已下载 $pct%（${progress.bytesRead / 1024} / ${total / 1024} KB）"
    }
}
