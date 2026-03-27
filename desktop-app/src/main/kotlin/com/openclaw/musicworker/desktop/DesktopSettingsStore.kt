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
        if (!configFile.exists()) {
            return ApiServerConfig()
        }

        return runCatching {
            configFile.inputStream().use { input ->
                json.decodeFromString<DesktopSettingsData>(input.readBytes().decodeToString()).toApiServerConfig()
            }
        }.getOrElse { ApiServerConfig() }
    }

    fun save(config: ApiServerConfig) {
        configDir.createDirectories()
        val payload = json.encodeToString(DesktopSettingsData.from(config))
        configFile.outputStream().use { output ->
            output.write(payload.encodeToByteArray())
        }
    }
}

@Serializable
private data class DesktopSettingsData(
    val host: String = "127.0.0.1",
    val port: Int = 18081,
) {
    fun toApiServerConfig(): ApiServerConfig = ApiServerConfig(host = host, port = port)

    companion object {
        fun from(config: ApiServerConfig): DesktopSettingsData = DesktopSettingsData(
            host = config.host,
            port = config.port,
        )
    }
}
