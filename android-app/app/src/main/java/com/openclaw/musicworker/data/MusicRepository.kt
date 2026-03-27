package com.openclaw.musicworker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.openclaw.musicworker.BuildConfig
import com.openclaw.musicworker.data.api.AppUpdateInfo
import com.openclaw.musicworker.data.api.DownloadTask
import com.openclaw.musicworker.data.api.HealthPayload
import com.openclaw.musicworker.data.api.MusicApiClient
import com.openclaw.musicworker.data.api.ProxyInfo
import com.openclaw.musicworker.data.api.SearchItem
import com.openclaw.musicworker.data.settings.ApiServerConfig
import com.openclaw.musicworker.data.settings.AppSettingsStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class ExportedFile(
    val fileName: String,
    val fileUri: String,
)

data class PrivateStorageSummary(
    val totalBytes: Long,
)

data class PrivateStorageCleanupResult(
    val freedBytes: Long,
    val remainingBytes: Long,
)

data class InstalledAppInfo(
    val versionName: String,
    val versionCode: Long,
)

data class DownloadedAppUpdate(
    val fileName: String,
    val contentUri: String,
)

class MusicRepository(
    context: Context,
    private val apiClient: MusicApiClient,
    private val settingsStore: AppSettingsStore,
) {
    private val appContext = context.applicationContext

    val serverConfig: StateFlow<ApiServerConfig> = settingsStore.config

    fun currentServerConfig(): ApiServerConfig = settingsStore.currentConfig()

    fun saveServerConfig(host: String, port: Int) {
        settingsStore.saveApiServer(host = host, port = port)
    }

    fun currentDownloadDirectoryUri(): String? = settingsStore.currentDownloadDirectoryUri()

    fun currentDownloadDirectoryLabel(): String? = settingsStore.currentDownloadDirectoryLabel()

    fun saveDownloadDirectory(uriString: String) {
        val previousUri = settingsStore.currentDownloadDirectoryUri()
        settingsStore.saveDownloadDirectory(uriString)
        if (previousUri != null && previousUri != uriString) {
            releasePersistedPermission(previousUri)
        }
    }

    fun clearDownloadDirectory() {
        settingsStore.currentDownloadDirectoryUri()?.let(::releasePersistedPermission)
        settingsStore.clearDownloadDirectory()
    }

    suspend fun getHealth(): HealthPayload = apiClient.getHealth()

    suspend fun getCurrentProxy(): ProxyInfo = apiClient.getCurrentProxy()

    suspend fun search(keyword: String, limit: Int = 20): List<SearchItem> {
        return apiClient.search(keyword = keyword, limit = limit).results
    }

    suspend fun startDownload(musicId: String): DownloadTask = apiClient.startDownload(musicId)

    suspend fun getTask(taskId: String): DownloadTask = apiClient.getTask(taskId)

    suspend fun getTasks(): List<DownloadTask> = apiClient.getTasks()

    suspend fun getLogs(lines: Int): List<String> = apiClient.getLogs(lines)

    suspend fun selectProxy(name: String): ProxyInfo = apiClient.selectProxy(name)

    fun getInstalledAppInfo(): InstalledAppInfo {
        val packageManager = appContext.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return InstalledAppInfo(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
        )
    }

    suspend fun getAppUpdate(): AppUpdateInfo = apiClient.getAppUpdate()

    suspend fun downloadAppUpdate(updateInfo: AppUpdateInfo): DownloadedAppUpdate = withContext(Dispatchers.IO) {
        downloadAppUpdate(updateInfo, onProgress = { _, _ -> })
    }

    suspend fun downloadAppUpdate(
        updateInfo: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadedAppUpdate = withContext(Dispatchers.IO) {
        val updatesDir = File(appContext.cacheDir, "app-updates").apply { mkdirs() }
        updatesDir.listFiles().orEmpty().forEach { existing ->
            if (existing.isFile && existing.extension.lowercase(Locale.US) == "apk") {
                runCatching { existing.delete() }
            }
        }

        val fileName = sanitizeApkFileName(updateInfo.fileName)
        val targetFile = File(updatesDir, fileName)
        val tempFile = File(updatesDir, "$fileName.download")
        runCatching { tempFile.delete() }
        runCatching { targetFile.delete() }

        try {
            tempFile.outputStream().use { outputStream ->
                apiClient.downloadAppUpdate(updateInfo.downloadPath, outputStream, onProgress)
            }
            updateInfo.sha256?.let { expected ->
                val actual = sha256File(tempFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("更新包校验失败")
                }
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (error: Throwable) {
            runCatching { tempFile.delete() }
            runCatching { targetFile.delete() }
            throw error
        }

        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            targetFile,
        )
        DownloadedAppUpdate(
            fileName = fileName,
            contentUri = contentUri.toString(),
        )
    }

    suspend fun getPrivateStorageSummary(): PrivateStorageSummary = withContext(Dispatchers.IO) {
        PrivateStorageSummary(
            totalBytes = privateStorageDirectories().sumOf(::directorySize),
        )
    }

    suspend fun clearPrivateStorage(): PrivateStorageCleanupResult = withContext(Dispatchers.IO) {
        val beforeBytes = privateStorageDirectories().sumOf(::directorySize)
        privateStorageDirectories().forEach(::deleteDirectoryContents)
        val afterBytes = privateStorageDirectories().sumOf(::directorySize)
        PrivateStorageCleanupResult(
            freedBytes = (beforeBytes - afterBytes).coerceAtLeast(0),
            remainingBytes = afterBytes,
        )
    }

    suspend fun exportTaskFile(task: DownloadTask): ExportedFile = withContext(Dispatchers.IO) {
        exportTaskFile(task, onProgress = { _, _ -> })
    }

    suspend fun exportTaskFile(
        task: DownloadTask,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ExportedFile = withContext(Dispatchers.IO) {
        val uriString = settingsStore.currentDownloadDirectoryUri()
            ?: throw IOException("请先在设置页选择下载目录")
        val treeUri = runCatching { Uri.parse(uriString) }
            .getOrElse { throw IOException("下载目录配置无效，请重新选择") }
        val directory = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("下载目录不可用，请重新选择")
        if (!directory.exists() || !directory.canWrite()) {
            throw IOException("下载目录不可写，请重新选择")
        }

        val desiredFileName = sanitizeFileName(task.filename ?: "${task.musicId}.mp3")
        val outputFileName = resolveAvailableFileName(directory, desiredFileName)
        val target = directory.createFile(guessMimeType(outputFileName), outputFileName)
            ?: throw IOException("无法在所选目录创建文件")

        try {
            val outputStream = appContext.contentResolver.openOutputStream(target.uri, "w")
                ?: throw IOException("无法写入所选目录")
            outputStream.use { stream ->
                apiClient.downloadFile(task.taskId, stream, onProgress)
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }

        ExportedFile(
            fileName = outputFileName,
            fileUri = target.uri.toString(),
        )
    }

    private fun releasePersistedPermission(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(uri, flags)
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.')
        return cleaned.ifBlank { "music_worker.mp3" }
    }

    private fun resolveAvailableFileName(directory: DocumentFile, desiredFileName: String): String {
        if (directory.findFile(desiredFileName) == null) {
            return desiredFileName
        }

        val dotIndex = desiredFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) desiredFileName.substring(0, dotIndex) else desiredFileName
        val extension = if (dotIndex > 0) desiredFileName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = "$baseName ($counter)$extension"
            if (directory.findFile(candidate) == null) {
                return candidate
            }
            counter += 1
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .ifBlank { return "audio/mpeg" }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "audio/mpeg"
    }

    private fun sanitizeApkFileName(fileName: String): String {
        val sanitized = fileName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "music-worker-update.apk" }
        return if (sanitized.lowercase(Locale.US).endsWith(".apk")) {
            sanitized
        } else {
            "$sanitized.apk"
        }
    }

    private fun privateStorageDirectories(): List<File> {
        return listOfNotNull(
            appContext.filesDir,
            appContext.cacheDir,
            appContext.codeCacheDir,
            appContext.noBackupFilesDir,
            appContext.externalCacheDir,
        ).distinct()
    }

    private fun deleteDirectoryContents(directory: File) {
        val children = directory.listFiles().orEmpty()
        children.forEach(::deleteRecursively)
    }

    private fun deleteRecursively(target: File) {
        if (target.isDirectory) {
            target.listFiles().orEmpty().forEach(::deleteRecursively)
        }
        if (target.exists()) {
            runCatching { target.delete() }
        }
    }

    private fun directorySize(target: File): Long {
        if (!target.exists()) {
            return 0L
        }
        if (target.isFile) {
            return target.length()
        }
        return target.listFiles().orEmpty().sumOf(::directorySize)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
