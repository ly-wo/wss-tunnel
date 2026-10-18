package com.wlzxj.tunnel.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wlzxj.tunnel.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class UpdateCheckPhase {
    Idle,
    Checking,
    UpdateAvailable,
    UpToDate,
    Error,
}

data class UpdateUiState(
    val currentVersionLabel: String = BuildConfig.VERSION_NAME,
    val phase: UpdateCheckPhase = UpdateCheckPhase.Idle,
    val statusMessage: String? = null,
    val releaseNotes: String? = null,
    val availableVersion: String? = null,
    val downloading: Boolean = false,
    val downloadProgressText: String? = null,
    val downloadedApkReady: Boolean = false,
    val installBlockedMessage: String? = null,
)

class UpdateViewModel(
    application: Application,
    private val repository: AppUpdateRepository,
    private val updatePrefs: UpdatePreferences,
    private val apkInspector: ApkArchiveInspector,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val checkMutex = Mutex()
    private var pendingRelease: ParsedRelease? = null
    private var verifiedDownload: VerifiedDownload? = null
    private var downloadJob: Job? = null
    private var checkJob: Job? = null
    private var startupCheckJob: Job? = null
    @Volatile
    private var installEvaluating = false
    private var installIntentLaunched = false

    val packageName: String = application.packageName

    fun onStartupMaybeCheck() {
        if (isOperationBusy()) return
        if (startupCheckJob?.isActive == true || checkJob?.isActive == true) return
        startupCheckJob = viewModelScope.launch {
            val last = updatePrefs.lastStartupCheckEpochMs()
            val now = clock()
            if (now - last < AppUpdateConstants.STARTUP_CHECK_INTERVAL_MS) return@launch
            updatePrefs.markStartupCheckAttempt(now)
            runCheck(startup = true)
        }
    }

    fun checkForUpdate(manual: Boolean) {
        if (isOperationBusy()) return
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            runCheck(startup = false)
        }
    }

    private suspend fun runCheck(startup: Boolean) {
        if (_uiState.value.downloading || installEvaluating) return
        checkMutex.withLock {
            if (_uiState.value.phase == UpdateCheckPhase.Checking) return
            _uiState.value = _uiState.value.copy(
                phase = UpdateCheckPhase.Checking,
                statusMessage = "检查中…",
                installBlockedMessage = null,
            )
            val result = try {
                repository.checkForUpdate()
            } catch (_: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    phase = UpdateCheckPhase.Idle,
                    statusMessage = null,
                )
                return
            }
            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    pendingRelease = result.release
                    invalidateDownloadIfStale(result.release)
                    val ready = isVerifiedForReleaseOnDisk(result.release)
                    val hint = if (startup) {
                        "发现新版本 v${result.release.version.display()}，可手动下载安装"
                    } else {
                        "发现新版本 v${result.release.version.display()}"
                    }
                    _uiState.value = _uiState.value.copy(
                        phase = UpdateCheckPhase.UpdateAvailable,
                        statusMessage = hint,
                        releaseNotes = result.release.body,
                        availableVersion = result.release.version.display(),
                        downloadedApkReady = ready,
                    )
                }
                UpdateCheckResult.UpToDate -> {
                    pendingRelease = null
                    clearVerifiedDownload()
                    _uiState.value = _uiState.value.copy(
                        phase = UpdateCheckPhase.UpToDate,
                        statusMessage = "已是最新版",
                        releaseNotes = null,
                        availableVersion = null,
                        downloadedApkReady = false,
                    )
                }
                is UpdateCheckResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        phase = UpdateCheckPhase.Error,
                        statusMessage = result.message,
                        downloadedApkReady = isVerifiedForReleaseOnDisk(pendingRelease),
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val release = pendingRelease ?: return
        if (isOperationBusy()) return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                downloading = true,
                downloadProgressText = "准备下载…",
                statusMessage = null,
                installBlockedMessage = null,
            )
            try {
                when (val result = repository.downloadRelease(release) { progress ->
                    _uiState.value = _uiState.value.copy(
                        downloadProgressText = repository.formatProgress(progress),
                    )
                }) {
                    is DownloadResult.Success -> {
                        verifiedDownload = result.verified
                        _uiState.value = _uiState.value.copy(
                            downloading = false,
                            downloadedApkReady = true,
                            downloadProgressText = null,
                            statusMessage = "下载完成，可安装",
                        )
                    }
                    is DownloadResult.Failed -> {
                        clearVerifiedDownload()
                        _uiState.value = _uiState.value.copy(
                            downloading = false,
                            phase = UpdateCheckPhase.Error,
                            statusMessage = result.message,
                            downloadProgressText = null,
                            downloadedApkReady = false,
                        )
                    }
                    DownloadResult.Cancelled -> {
                        clearVerifiedDownload()
                        _uiState.value = _uiState.value.copy(
                            downloading = false,
                            downloadProgressText = null,
                            statusMessage = "已取消下载",
                            downloadedApkReady = false,
                        )
                    }
                }
            } catch (_: CancellationException) {
                clearVerifiedDownload()
                _uiState.value = _uiState.value.copy(
                    downloading = false,
                    downloadProgressText = null,
                    statusMessage = "已取消下载",
                    downloadedApkReady = false,
                )
            } finally {
                if (_uiState.value.downloading) {
                    _uiState.value = _uiState.value.copy(downloading = false)
                }
            }
        }
    }

    fun cancelDownload() {
        repository.requestCancelDownload()
        downloadJob?.cancel()
    }

    fun onUserRequestInstall() {
        installIntentLaunched = false
    }

    suspend fun prepareInstallIntent(context: Context): Intent? {
        if (isOperationBusy()) return null
        if (installEvaluating) return null
        installEvaluating = true
        return try {
            when (val eligibility = withContext(Dispatchers.IO) { evaluateInstallEligibilityInternal() }) {
                is InstallEligibility.Rejected -> {
                    invalidateVerifiedDownloadOnDiskFailure(eligibility.message)
                    return null
                }
                InstallEligibility.Ok -> Unit
            }
            if (!canRequestPackageInstalls(context)) {
                _uiState.value = _uiState.value.copy(
                    installBlockedMessage = "需要允许「安装未知应用」权限后才能继续",
                )
                return null
            }
            val apk = verifiedDownload?.apkFile ?: run {
                invalidateVerifiedDownloadOnDiskFailure("安装包已不存在，请重新下载")
                return null
            }
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk,
                )
            } catch (_: IllegalArgumentException) {
                invalidateVerifiedDownloadOnDiskFailure("无法访问安装包，请重新下载")
                return null
            }
            _uiState.value = _uiState.value.copy(installBlockedMessage = null)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: IOException) {
            invalidateVerifiedDownloadOnDiskFailure("无法读取安装包，请重新下载")
            null
        } finally {
            installEvaluating = false
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            reportInstallLaunchFailure("无法打开安装权限设置")
        } catch (_: SecurityException) {
            reportInstallLaunchFailure("无法打开安装权限设置")
        }
    }

    fun onInstallIntentLaunched() {
        installIntentLaunched = true
    }

    fun reportInstallLaunchFailure(message: String) {
        _uiState.value = _uiState.value.copy(installBlockedMessage = message)
    }

    fun onResume(context: Context) {
        if (installIntentLaunched) return
        val granted = canRequestPackageInstalls(context)
        val blocked = _uiState.value.installBlockedMessage
        if (blocked != null && blocked.contains("安装未知应用")) {
            _uiState.value = _uiState.value.copy(
                installBlockedMessage = if (granted) null else blocked,
            )
        }
    }

    fun tunnelInterruptWarning(): String =
        "安装更新会重启本应用，进行中的隧道连接将中断。"

    override fun onCleared() {
        repository.cancelAllInFlight()
        downloadJob?.cancel()
        checkJob?.cancel()
        startupCheckJob?.cancel()
        super.onCleared()
    }

    private fun isOperationBusy(): Boolean =
        _uiState.value.phase == UpdateCheckPhase.Checking ||
            _uiState.value.downloading ||
            installEvaluating

    @Throws(IOException::class)
    private fun evaluateInstallEligibilityInternal(): InstallEligibility {
        val verified = verifiedDownload
            ?: return InstallEligibility.Rejected("请先下载安装包")
        val release = pendingRelease
            ?: return InstallEligibility.Rejected("缺少发布版本信息，请重新检查更新")
        if (verified.release.version != release.version) {
            return InstallEligibility.Rejected("安装包与当前发布版本不一致，请重新下载")
        }
        val apk = verified.apkFile
        if (!apk.isFile) {
            return InstallEligibility.Rejected("安装包已不存在，请重新下载")
        }
        if (apk.length() != release.apk.size) {
            return InstallEligibility.Rejected("安装包大小与发布信息不一致，请重新下载")
        }
        val hash = sha256File(apk)
        if (!hash.equals(verified.expectedSha256, ignoreCase = true)) {
            return InstallEligibility.Rejected("安装包校验失败，请重新下载")
        }
        val archive = apkInspector.readArchive(apk)
            ?: return InstallEligibility.Rejected("无法读取安装包信息")
        val installed = apkInspector.readInstalled(packageName)
            ?: return InstallEligibility.Rejected("无法读取当前应用信息")
        return ApkInstallCriteria.evaluate(
            installed = installed,
            apk = archive,
            targetVersionName = release.version.display(),
        )
    }

    private suspend fun isVerifiedForReleaseOnDisk(release: ParsedRelease?): Boolean =
        withContext(Dispatchers.IO) {
            if (release == null) return@withContext false
            val verified = verifiedDownload ?: return@withContext false
            if (verified.release.version != release.version) return@withContext false
            val apk = verified.apkFile
            if (!apk.isFile || apk.length() != release.apk.size) return@withContext false
            try {
                sha256File(apk).equals(verified.expectedSha256, ignoreCase = true)
            } catch (_: IOException) {
                false
            }
        }

    private fun invalidateVerifiedDownloadOnDiskFailure(message: String) {
        verifiedDownload?.apkFile?.delete()
        verifiedDownload = null
        _uiState.value = _uiState.value.copy(
            downloadedApkReady = false,
            installBlockedMessage = message,
        )
    }

    private fun invalidateDownloadIfStale(release: ParsedRelease?) {
        val verified = verifiedDownload
        if (verified == null) {
            _uiState.value = _uiState.value.copy(downloadedApkReady = false)
            return
        }
        if (release == null || verified.release.version != release.version) {
            clearVerifiedDownload()
        }
    }

    private fun clearVerifiedDownload() {
        verifiedDownload = null
        _uiState.value = _uiState.value.copy(downloadedApkReady = false)
    }

    @Throws(IOException::class)
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

    companion object {
        fun create(application: Application, okhttpClient: OkHttpClient? = null): UpdateViewModel {
            val updatesDir = File(application.cacheDir, "updates")
            val current = SemVer.parseTag("v${BuildConfig.VERSION_NAME}")
                ?: SemVer(0, 0, 0)
            val repo = AppUpdateRepository(
                updatesDir = updatesDir,
                currentVersion = current,
                okhttpClient = okhttpClient,
            )
            return UpdateViewModel(
                application = application,
                repository = repo,
                updatePrefs = UpdatePreferences(application),
                apkInspector = AndroidApkArchiveInspector(application.packageManager),
            )
        }
    }
}

class UpdateViewModelFactory(
    private val application: Application,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UpdateViewModel::class.java)) {
            return UpdateViewModel.create(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel $modelClass")
    }
}
