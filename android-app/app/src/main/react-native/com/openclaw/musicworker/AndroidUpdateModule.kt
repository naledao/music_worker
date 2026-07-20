package com.openclaw.musicworker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

class AndroidUpdateModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "AndroidUpdate"

    @ReactMethod
    fun getInstalledAppInfo(promise: Promise) {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactContext.packageManager.getPackageInfo(
                    reactContext.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                reactContext.packageManager.getPackageInfo(reactContext.packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            promise.resolve(
                Arguments.createMap().apply {
                    putString("versionName", packageInfo.versionName ?: "unknown")
                    putDouble("versionCode", versionCode.toDouble())
                },
            )
        } catch (error: Throwable) {
            promise.reject("APP_VERSION_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun downloadAndInstall(
        downloadUrl: String,
        fileName: String,
        expectedSha256: String?,
        promise: Promise,
    ) {
        thread(name = "music-worker-app-update") {
            try {
                val apkFile = downloadApk(downloadUrl, fileName)
                if (!expectedSha256.isNullOrBlank()) {
                    val actualSha256 = sha256File(apkFile)
                    if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                        apkFile.delete()
                        throw IOException("更新包校验失败")
                    }
                }
                val result = installApk(apkFile)
                promise.resolve(result)
            } catch (error: Throwable) {
                promise.reject("APP_UPDATE_FAILED", error.message, error)
            }
        }
    }

    private fun downloadApk(downloadUrl: String, rawFileName: String): File {
        val updatesDir = File(reactContext.cacheDir, "app-updates").apply { mkdirs() }
        updatesDir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && (file.extension == "apk" || file.extension == "download")) {
                file.delete()
            }
        }

        val fileName = sanitizeApkFileName(rawFileName)
        val targetFile = File(updatesDir, fileName)
        val tempFile = File(updatesDir, "$fileName.download")
        targetFile.delete()
        tempFile.delete()

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("下载更新失败: HTTP ${connection.responseCode}")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloadedBytes = 0L
                    var lastReportedBytes = 0L
                    var lastReportedAt = System.currentTimeMillis()
                    emitProgress(downloadedBytes, totalBytes)
                    while (true) {
                        val readCount = input.read(buffer)
                        if (readCount <= 0) {
                            break
                        }
                        output.write(buffer, 0, readCount)
                        downloadedBytes += readCount
                        val now = System.currentTimeMillis()
                        if (
                            downloadedBytes == totalBytes ||
                            downloadedBytes - lastReportedBytes >= 256 * 1024 ||
                            now - lastReportedAt >= 250
                        ) {
                            emitProgress(downloadedBytes, totalBytes)
                            lastReportedBytes = downloadedBytes
                            lastReportedAt = now
                        }
                    }
                    output.flush()
                    emitProgress(downloadedBytes, totalBytes)
                }
            }
        } catch (error: Throwable) {
            tempFile.delete()
            targetFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        return targetFile
    }

    private fun installApk(apkFile: File): com.facebook.react.bridge.WritableMap {
        val canInstallPackages = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            reactContext.packageManager.canRequestPackageInstalls()

        if (!canInstallPackages) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${reactContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            reactContext.startActivity(settingsIntent)
            return Arguments.createMap().apply {
                putString("status", "permission_required")
                putString("message", "请先允许安装未知应用，然后重新下载更新")
            }
        }

        val contentUri = FileProvider.getUriForFile(
            reactContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(installIntent)
        return Arguments.createMap().apply {
            putString("status", "installer_started")
            putString("message", "已拉起系统安装器")
            putString("uri", contentUri.toString())
        }
    }

    private fun emitProgress(downloadedBytes: Long, totalBytes: Long?) {
        val progress = if (totalBytes != null && totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val payload = Arguments.createMap().apply {
            putDouble("downloadedBytes", downloadedBytes.toDouble())
            if (totalBytes != null) {
                putDouble("totalBytes", totalBytes.toDouble())
            }
            putDouble("progress", progress)
        }
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("AndroidUpdateProgress", payload)
    }

    private fun sanitizeApkFileName(rawFileName: String): String {
        val sanitized = rawFileName
            .trim()
            .replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .ifBlank { "music-worker-update.apk" }
        return if (sanitized.lowercase(Locale.US).endsWith(".apk")) {
            sanitized
        } else {
            "$sanitized.apk"
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val readCount = input.read(buffer)
                if (readCount <= 0) {
                    break
                }
                digest.update(buffer, 0, readCount)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
