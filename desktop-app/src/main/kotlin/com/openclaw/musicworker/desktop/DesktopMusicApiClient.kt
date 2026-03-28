package com.openclaw.musicworker.desktop

import com.openclaw.musicworker.shared.api.AppUpdateInfo
import com.openclaw.musicworker.shared.api.HealthPayload
import com.openclaw.musicworker.shared.api.DownloadRequest
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.LogLinesPayload
import com.openclaw.musicworker.shared.api.ProxyInfo
import com.openclaw.musicworker.shared.api.ProxySelectRequest
import com.openclaw.musicworker.shared.api.SearchItem
import com.openclaw.musicworker.shared.api.SearchPayload
import com.openclaw.musicworker.shared.api.SearchRequest
import com.openclaw.musicworker.shared.api.TaskListPayload
import com.openclaw.musicworker.shared.config.ApiServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopMusicApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val httpClient = HttpClient(CIO)

    suspend fun getHealth(config: ApiServerConfig): HealthPayload {
        val rawBody = httpClient.get("${config.baseUrl}/api/health").body<String>()
        return decodePayload(rawBody)
    }

    suspend fun getCurrentProxy(config: ApiServerConfig): ProxyInfo {
        val rawBody = httpClient.get("${config.baseUrl}/api/proxy/current").body<String>()
        return decodePayload(rawBody)
    }

    suspend fun getAppUpdate(config: ApiServerConfig, platform: String = "desktop"): AppUpdateInfo {
        val normalizedPlatform = platform.lowercase(Locale.ROOT)
        val packageQuery = if (normalizedPlatform == "desktop") "&kind=exe" else ""
        val rawBody = httpClient.get("${config.baseUrl}/api/app/update?platform=$platform$packageQuery").body<String>()
        return decodePayload(rawBody)
    }

    suspend fun downloadTaskFile(
        config: ApiServerConfig,
        taskId: String,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        downloadBinary(
            url = "${config.baseUrl}/api/files/$taskId",
            outputStream = outputStream,
            expectedContentTypes = listOf("audio/", "application/octet-stream"),
            onProgress = onProgress,
        )
    }

    suspend fun search(config: ApiServerConfig, keyword: String, limit: Int = 20): List<SearchItem> {
        val rawBody = postJson(
            url = "${config.baseUrl}/api/search",
            body = SearchRequest(keyword = keyword, limit = limit),
        )
        return decodePayload<SearchPayload>(rawBody).results
    }

    suspend fun startDownload(config: ApiServerConfig, musicId: String): DownloadTask {
        val rawBody = postJson(
            url = "${config.baseUrl}/api/download",
            body = DownloadRequest(musicId = musicId),
        )
        return decodePayload(rawBody)
    }

    suspend fun getTask(config: ApiServerConfig, taskId: String): DownloadTask {
        val rawBody = httpClient.get("${config.baseUrl}/api/tasks/$taskId").body<String>()
        return decodePayload(rawBody)
    }

    suspend fun getTasks(config: ApiServerConfig): List<DownloadTask> {
        val rawBody = httpClient.get("${config.baseUrl}/api/tasks").body<String>()
        return decodePayload<TaskListPayload>(rawBody).tasks
    }

    suspend fun getLogs(config: ApiServerConfig, lines: Int = 100): List<String> {
        val rawBody = httpClient.get("${config.baseUrl}/api/logs?lines=$lines").body<String>()
        return decodePayload<LogLinesPayload>(rawBody).lines
    }

    suspend fun selectProxy(config: ApiServerConfig, name: String): ProxyInfo {
        val rawBody = postJson(
            url = "${config.baseUrl}/api/proxy/select",
            body = ProxySelectRequest(name = name),
        )
        return decodePayload(rawBody)
    }

    suspend fun downloadAppUpdate(
        config: ApiServerConfig,
        downloadPath: String,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = if (downloadPath.startsWith("http://") || downloadPath.startsWith("https://")) {
            downloadPath
        } else {
            "${config.baseUrl}$downloadPath"
        }

        downloadBinary(
            url = url,
            outputStream = outputStream,
            expectedContentTypes = listOf(
                "application/octet-stream",
                "application/x-msi",
                "application/x-msdownload",
                "application/vnd.microsoft.portable-executable",
            ),
            onProgress = onProgress,
        )
    }

    private inline fun <reified T> decodePayload(rawBody: String): T {
        if (rawBody.isBlank()) {
            throw IOException("API returned empty body")
        }

        val root = json.parseToJsonElement(rawBody).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val payload = root["payload"] ?: buildJsonObject { }

        if (!ok) {
            throw IOException(decodeErrorPayload(payload.jsonObject))
        }

        return json.decodeFromJsonElement(payload)
    }

    private suspend inline fun <reified T> postJson(url: String, body: T): String {
        return httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }.body<String>()
    }

    private fun decodeErrorPayload(payload: JsonObject?): String {
        return payload
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.ifBlank { null }
            ?: "API request failed"
    }

    private suspend fun downloadBinary(
        url: String,
        outputStream: OutputStream,
        expectedContentTypes: List<String>,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
            readTimeout = DEFAULT_READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val rawBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw IOException(decodeErrorMessage(rawBody).ifBlank { "HTTP $statusCode" })
            }

            val contentType = connection.contentType.orEmpty()
            if (!matchesExpectedContentType(contentType, expectedContentTypes)) {
                throw IOException(
                    "接口返回了非预期文件类型: ${contentType.substringBefore(';').ifBlank { "unknown" }}",
                )
            }

            val inputStream = connection.inputStream ?: throw IOException("API returned empty file body")
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            val buffer = ByteArray(DEFAULT_DOWNLOAD_BUFFER_SIZE)
            var downloadedBytes = 0L
            onProgress(downloadedBytes, totalBytes)
            inputStream.use { input ->
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    outputStream.write(buffer, 0, readCount)
                    downloadedBytes += readCount
                    onProgress(downloadedBytes, totalBytes)
                }
            }
            outputStream.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun matchesExpectedContentType(contentType: String, expectedContentTypes: List<String>): Boolean {
        val normalized = contentType
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
        if (normalized.isBlank()) {
            return false
        }
        return expectedContentTypes.any { expected ->
            val candidate = expected.lowercase(Locale.US)
            normalized == candidate || normalized.startsWith(candidate)
        }
    }

    private fun decodeErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) {
            return ""
        }

        return runCatching {
            val root = json.parseToJsonElement(rawBody).jsonObject
            decodeErrorPayload(root["payload"]?.jsonObject)
        }.getOrDefault(rawBody)
    }

    private companion object {
        const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 300_000
    }
}
