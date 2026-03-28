package com.openclaw.musicworker.desktop

import com.openclaw.musicworker.shared.config.ApiServerConfig
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DesktopSettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val configDir: Path = DesktopPaths.configDir()
    private val configFile: Path = configDir.resolve("config.json")

    fun load(): ApiServerConfig {
        return readSettings().toApiServerConfig()
    }

    fun save(config: ApiServerConfig) {
        writeSettings(
            readSettings().copy(
                host = config.host,
                port = config.port,
            ),
        )
        DesktopFileLogger.info("saved desktop config path=$configFile baseUrl=${config.baseUrl}")
    }

    fun currentDownloadDirectoryPath(): String? {
        return readSettings().downloadDirectoryPath
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
    }

    fun saveDownloadDirectory(path: String) {
        val normalizedPath = path.trim()
        writeSettings(
            readSettings().copy(
                downloadDirectoryPath = normalizedPath.ifBlank { null },
            ),
        )
        DesktopFileLogger.info("saved desktop download directory path=$normalizedPath")
    }

    fun clearDownloadDirectory() {
        writeSettings(
            readSettings().copy(
                downloadDirectoryPath = null,
            ),
        )
        DesktopFileLogger.info("cleared custom desktop download directory")
    }

    private fun readSettings(): DesktopSettingsData {
        if (!configFile.exists()) {
            return DesktopSettingsData()
        }

        return runCatching {
            configFile.inputStream().use { input ->
                json.decodeFromString<DesktopSettingsData>(input.readBytes().decodeToString())
            }
        }.onFailure { error ->
            DesktopFileLogger.error("failed to load desktop config path=$configFile", error)
        }.getOrElse { DesktopSettingsData() }
    }

    private fun writeSettings(data: DesktopSettingsData) {
        configDir.createDirectories()
        val payload = json.encodeToString(data)
        configFile.outputStream().use { output ->
            output.write(payload.encodeToByteArray())
        }
    }
}

@Serializable
private data class DesktopSettingsData(
    val host: String = "127.0.0.1",
    val port: Int = 18081,
    val downloadDirectoryPath: String? = null,
) {
    fun toApiServerConfig(): ApiServerConfig = ApiServerConfig(host = host, port = port)
}
