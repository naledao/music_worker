package com.openclaw.musicworker.desktop

import com.openclaw.musicworker.shared.api.AppUpdateInfo
import com.openclaw.musicworker.shared.config.ApiServerConfig
import java.awt.Desktop
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

data class DownloadedDesktopUpdate(
    val fileName: String,
    val filePath: String,
)

class DesktopUpdateManager {
    fun currentVersionName(): String {
        return System.getProperty(PROP_VERSION_NAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_VERSION_NAME
    }

    fun currentVersionCode(): Long {
        return System.getProperty(PROP_VERSION_CODE)
            ?.trim()
            ?.toLongOrNull()
            ?: DEFAULT_VERSION_CODE
    }

    suspend fun downloadUpdate(
        apiClient: DesktopMusicApiClient,
        config: ApiServerConfig,
        updateInfo: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadedDesktopUpdate = withContext(Dispatchers.IO) {
        val updatesDir = DesktopPaths.updatesDir().createDirectories()
        val fileName = sanitizeInstallerFileName(updateInfo.fileName)
        val targetFile = updatesDir.resolve(fileName)
        val tempFile = updatesDir.resolve("$fileName.download")

        clearOldInstallers(updatesDir, keepFileName = fileName)
        Files.deleteIfExists(tempFile)
        Files.deleteIfExists(targetFile)

        try {
            tempFile.outputStream().use { output ->
                apiClient.downloadAppUpdate(config, updateInfo.downloadPath, output, onProgress)
            }

            updateInfo.sha256?.let { expected ->
                val actual = sha256File(tempFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("安装包校验失败")
                }
            }

            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(tempFile)
            Files.deleteIfExists(targetFile)
            throw error
        }

        DownloadedDesktopUpdate(
            fileName = fileName,
            filePath = targetFile.toString(),
        )
    }

    fun openInstaller(filePath: String) {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!path.exists()) {
            throw IOException("安装包不存在：$filePath")
        }

        val failures = mutableListOf<String>()
        if (isWindows()) {
            runCatching { openInstallerOnWindows(path) }
                .onSuccess { return }
                .onFailure { error ->
                    failures += error.message ?: error.javaClass.simpleName
                }
        }

        runCatching { openInstallerWithDesktop(path) }
            .onSuccess { return }
            .onFailure { error ->
                failures += error.message ?: error.javaClass.simpleName
            }

        throw IOException(
            buildString {
                append("打开安装包失败")
                if (failures.isNotEmpty()) {
                    append("：")
                    append(failures.joinToString(" | "))
                }
            },
        )
    }

    private fun clearOldInstallers(directory: Path, keepFileName: String) {
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { entry ->
                val name = entry.name.lowercase(Locale.US)
                if (entry != directory.resolve(keepFileName) && (name.endsWith(".msi") || name.endsWith(".exe") || name.endsWith(".download"))) {
                    Files.deleteIfExists(entry)
                }
            }
        }
    }

    private fun sanitizeInstallerFileName(fileName: String): String {
        val cleaned = fileName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.')
            .ifBlank { "音爪-update.exe" }
        return when {
            cleaned.lowercase(Locale.US).endsWith(".msi") -> cleaned
            cleaned.lowercase(Locale.US).endsWith(".exe") -> cleaned
            else -> "$cleaned.exe"
        }
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val readCount = input.read(buffer)
                if (readCount <= 0) {
                    break
                }
                digest.update(buffer, 0, readCount)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openInstallerWithDesktop(path: Path) {
        if (!Desktop.isDesktopSupported()) {
            throw IOException("当前环境不支持直接打开安装包")
        }

        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            throw IOException("当前环境不支持直接打开安装包")
        }
        desktop.open(path.toFile())
    }

    private fun openInstallerOnWindows(path: Path) {
        val installerPath = path.toString()
        val attempts = listOf(
            listOf("cmd", "/c", "start", "", installerPath),
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Process -LiteralPath '${escapePowerShellLiteral(installerPath)}'",
            ),
        )

        val failures = mutableListOf<String>()
        attempts.forEach { command ->
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                return
            }
            if (process.exitValue() == 0) {
                return
            }

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            failures += "${command.first()}: ${output.ifBlank { "exit ${process.exitValue()}" }}"
        }

        throw IOException(failures.joinToString(" | ").ifBlank { "Windows 打开命令执行失败" })
    }

    private fun escapePowerShellLiteral(value: String): String {
        return value.replace("'", "''")
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")
            ?.lowercase(Locale.US)
            ?.contains("win") == true
    }

    private companion object {
        const val PROP_VERSION_NAME = "musicworker.desktop.version"
        const val PROP_VERSION_CODE = "musicworker.desktop.versionCode"
        const val DEFAULT_VERSION_NAME = "0.1.7"
        const val DEFAULT_VERSION_CODE = 8L
    }
}
