package com.wlzxj.tunnel.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

interface ApkArchiveInspector {
    fun readArchive(apkFile: File): ApkArchiveSnapshot?
    fun readInstalled(packageName: String): InstalledAppSnapshot?
}

class AndroidApkArchiveInspector(
    private val packageManager: PackageManager,
) : ApkArchiveInspector {
    override fun readArchive(apkFile: File): ApkArchiveSnapshot? {
        val flags = signingFlags()
        val info = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        val pkg = info.packageName ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val certs = signingSha256Set(info)
        return ApkArchiveSnapshot(
            packageName = pkg,
            versionCode = versionCode,
            versionName = info.versionName,
            signingCertSha256 = certs,
        )
    }

    override fun readInstalled(packageName: String): InstalledAppSnapshot? {
        val flags = signingFlags()
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, flags)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return InstalledAppSnapshot(
            packageName = packageName,
            versionCode = versionCode,
            versionName = info.versionName,
            signingCertSha256 = signingSha256Set(info),
        )
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun signingSha256Set(info: PackageInfo): Set<String> {
        val digests = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signatures?.forEach { sig ->
                digests.add(sha256Hex(sig.toByteArray()))
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.forEach { sig ->
                digests.add(sha256Hex(sig.toByteArray()))
            }
        }
        return digests
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
