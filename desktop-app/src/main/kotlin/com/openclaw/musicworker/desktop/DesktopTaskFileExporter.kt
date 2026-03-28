package com.openclaw.musicworker.desktop

import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.config.ApiServerConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.outputStream

data class ExportedDesktopTaskFile(
    val fileName: String,
    val filePath: String,
)

class DesktopTaskFileExporter {
    suspend fun exportTaskFile(
        apiClient: DesktopMusicApiClient,
        config: ApiServerConfig,
        task: DownloadTask,
        downloadDirectoryPath: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ExportedDesktopTaskFile = withContext(Dispatchers.IO) {
        val downloadDirectory = normalizeDirectoryPath(downloadDirectoryPath).createDirectories()
        if (!Files.isDirectory(downloadDirectory)) {
            throw IOException("保存目录不可用：$downloadDirectoryPath")
        }
        if (!Files.isWritable(downloadDirectory)) {
            throw IOException("保存目录不可写：$downloadDirectoryPath")
        }

        val fileName = sanitizeFileName(task.filename ?: "${task.musicId}.mp3")
        val targetFile = resolveAvailableFile(downloadDirectory, fileName)
        val tempFile = downloadDirectory.resolve("${targetFile.name}.download")

        Files.deleteIfExists(tempFile)
        try {
            tempFile.outputStream().use { output ->
                apiClient.downloadTaskFile(config, task.taskId, output, onProgress)
            }
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(tempFile)
            Files.deleteIfExists(targetFile)
            throw error
        }

        ExportedDesktopTaskFile(
            fileName = targetFile.name,
            filePath = targetFile.toString(),
        )
    }

    private fun normalizeDirectoryPath(path: String): Path {
        val normalized = path.trim()
        if (normalized.isEmpty()) {
            throw IOException("保存目录为空")
        }
        return runCatching {
            Path.of(normalized).toAbsolutePath().normalize()
        }.getOrElse {
            throw IOException("保存目录路径无效：$path")
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.', ' ')
            .ifBlank { "music_worker.mp3" }
        return if ('.' in cleaned) {
            cleaned
        } else {
            "$cleaned.mp3"
        }
    }

    private fun resolveAvailableFile(directory: Path, desiredFileName: String): Path {
        val desiredPath = directory.resolve(desiredFileName)
        if (!desiredPath.exists()) {
            return desiredPath
        }

        val dotIndex = desiredFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) desiredFileName.substring(0, dotIndex) else desiredFileName
        val extension = if (dotIndex > 0) desiredFileName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = directory.resolve("$baseName ($counter)$extension")
            if (!candidate.exists()) {
                return candidate
            }
            counter += 1
        }
    }
}
