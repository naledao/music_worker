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
import com.openclaw.musicworker.data.api.ChartPayload
import com.openclaw.musicworker.data.api.ChartSourceInfo
import com.openclaw.musicworker.data.api.DownloadTask
import com.openclaw.musicworker.data.api.DownloadedLyricsPayload
import com.openclaw.musicworker.data.api.DownloadedSongItem
import com.openclaw.musicworker.data.api.DownloadedSongsPayload
import com.openclaw.musicworker.data.api.HealthPayload
import com.openclaw.musicworker.data.api.MusicApiClient
import com.openclaw.musicworker.data.api.ProxyInfo
import com.openclaw.musicworker.data.api.SearchItem
import com.openclaw.musicworker.data.settings.ApiServerConfig
import com.openclaw.musicworker.data.settings.AppSettingsStore
import com.openclaw.musicworker.data.settings.LocalAudioEntry
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class ExportedFile(
    val fileName: String,
    val fileUri: String,
)

data class LocalPlayableFile(
    val musicId: String,
    val fileName: String? = null,
    val uri: String,
    val source: String,
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

    suspend fun getChartSources(): List<ChartSourceInfo> = apiClient.getChartSources()

    suspend fun getCharts(
        source: String = DEFAULT_CHART_SOURCE,
        type: String = DEFAULT_CHART_TYPE,
        period: String = DEFAULT_CHART_PERIOD,
        region: String = DEFAULT_CHART_REGION,
        limit: Int = DEFAULT_CHART_LIMIT,
        forceRefresh: Boolean = false,
    ): ChartPayload {
        return apiClient.getCharts(
            source = source,
            type = type,
            period = period,
            region = region,
            limit = limit,
            forceRefresh = forceRefresh,
        )
    }

    suspend fun search(keyword: String, limit: Int = 20): List<SearchItem> {
        return apiClient.search(keyword = keyword, limit = limit).results
    }

    suspend fun findPlayableFile(musicId: String): LocalPlayableFile? = withContext(Dispatchers.IO) {
        resolveLocalPlayableFile(musicId = musicId, entry = settingsStore.getLocalAudioEntry(musicId))
    }

    suspend fun startDownload(musicId: String): DownloadTask = apiClient.startDownload(musicId)

    suspend fun startLyricsGeneration(musicId: String): DownloadTask = apiClient.startLyricsGeneration(musicId)

    suspend fun getDownloadedSongs(
        page: Int = 1,
        pageSize: Int = 10,
    ): DownloadedSongsPayload = apiClient.getDownloadedSongs(page = page, pageSize = pageSize)

    suspend fun getDownloadedSongLyrics(musicId: String): DownloadedLyricsPayload? {
        return apiClient.getDownloadedSongLyrics(musicId)
    }

    fun buildTaskStreamUrl(taskId: String): String = apiClient.taskFileUrl(taskId)

    fun buildDownloadedSongStreamUrl(musicId: String): String = apiClient.downloadedSongFileUrl(musicId)

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
        settingsStore.clearPrivateAudioLocations()
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
    ): ExportedFile = exportAudioToSelectedDirectory(
        musicId = task.musicId,
        desiredFileName = sanitizeFileName(task.filename ?: "${task.musicId}.mp3"),
    ) { outputStream ->
        apiClient.downloadFile(task.taskId, outputStream, onProgress)
    }

    suspend fun exportDownloadedSong(
        item: DownloadedSongItem,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ExportedFile = exportAudioToSelectedDirectory(
        musicId = item.musicId,
        desiredFileName = sanitizeFileName(item.filename ?: "${item.musicId}.mp3"),
    ) { outputStream ->
        apiClient.downloadDownloadedSong(item.musicId, outputStream, onProgress)
    }

    private suspend fun exportAudioToSelectedDirectory(
        musicId: String,
        desiredFileName: String,
        onWrite: suspend (OutputStream) -> Unit,
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

        val outputFileName = resolveAvailableFileName(directory, desiredFileName)
        val target = directory.createFile(guessMimeType(outputFileName), outputFileName)
            ?: throw IOException("无法在所选目录创建文件")

        try {
            val outputStream = appContext.contentResolver.openOutputStream(target.uri, "w")
                ?: throw IOException("无法写入所选目录")
            outputStream.use { stream ->
                onWrite(stream)
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }

        ExportedFile(
            fileName = outputFileName,
            fileUri = target.uri.toString(),
        ).also { exportedFile ->
            settingsStore.updateLocalAudioEntry(musicId) { current ->
                val baseEntry = current ?: LocalAudioEntry(musicId = musicId)
                baseEntry.copy(
                    exportedUri = exportedFile.fileUri,
                    exportedDisplayName = exportedFile.fileName,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun cacheTaskFileForPlayback(
        task: DownloadTask,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): LocalPlayableFile = withContext(Dispatchers.IO) {
        val playbackDir = File(appContext.filesDir, "playback-cache").apply { mkdirs() }
        val displayFileName = sanitizeFileName(task.filename ?: "${task.musicId}.mp3")
        val extension = displayFileName.substringAfterLast('.', "")
            .ifBlank { "mp3" }
            .lowercase(Locale.US)
        val targetFile = File(playbackDir, "${sha256String(task.musicId).take(24)}.$extension")
        val tempFile = File(playbackDir, "${targetFile.name}.download")
        runCatching { tempFile.delete() }
        if (targetFile.exists()) {
            runCatching { targetFile.delete() }
        }

        try {
            tempFile.outputStream().use { outputStream ->
                apiClient.downloadFile(task.taskId, outputStream, onProgress)
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

        val playbackUri = Uri.fromFile(targetFile).toString()
        settingsStore.updateLocalAudioEntry(task.musicId) { current ->
            val baseEntry = current ?: LocalAudioEntry(musicId = task.musicId)
            baseEntry.copy(
                privateUri = playbackUri,
                privatePath = targetFile.absolutePath,
                privateDisplayName = displayFileName,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }

        LocalPlayableFile(
            musicId = task.musicId,
            fileName = displayFileName,
            uri = playbackUri,
            source = PLAYABLE_SOURCE_PRIVATE_CACHE,
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

    private fun sha256String(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(value.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveLocalPlayableFile(
        musicId: String,
        entry: LocalAudioEntry?,
    ): LocalPlayableFile? {
        if (entry == null) {
            return null
        }

        val exportedUri = entry.exportedUri?.takeIf(::isUriReadable)
        val privatePath = entry.privatePath?.takeIf { File(it).exists() }
        val privateUri = privatePath?.let { entry.privateUri ?: Uri.fromFile(File(it)).toString() }

        val normalizedEntry = entry.copy(
            exportedUri = exportedUri,
            exportedDisplayName = if (exportedUri != null) entry.exportedDisplayName else null,
            privateUri = privateUri,
            privatePath = privatePath,
            privateDisplayName = if (privatePath != null) entry.privateDisplayName else null,
        )
        if (normalizedEntry != entry) {
            settingsStore.updateLocalAudioEntry(musicId) { normalizedEntry }
        }

        return when {
            exportedUri != null -> LocalPlayableFile(
                musicId = musicId,
                fileName = normalizedEntry.exportedDisplayName,
                uri = exportedUri,
                source = PLAYABLE_SOURCE_EXPORTED_FILE,
            )
            privateUri != null -> LocalPlayableFile(
                musicId = musicId,
                fileName = normalizedEntry.privateDisplayName,
                uri = privateUri,
                source = PLAYABLE_SOURCE_PRIVATE_CACHE,
            )
            else -> null
        }
    }

    private fun isUriReadable(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase(Locale.US)) {
            "file" -> {
                val filePath = uri.path ?: return false
                File(filePath).exists()
            }
            else -> {
                runCatching {
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
            }
        }
    }

    private companion object {
        const val DEFAULT_CHART_SOURCE = "apple_music"
        const val DEFAULT_CHART_TYPE = "songs"
        const val DEFAULT_CHART_PERIOD = "daily"
        const val DEFAULT_CHART_REGION = "us"
        const val DEFAULT_CHART_LIMIT = 50
        const val PLAYABLE_SOURCE_EXPORTED_FILE = "exported"
        const val PLAYABLE_SOURCE_PRIVATE_CACHE = "private"
    }
}
