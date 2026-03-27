package com.openclaw.musicworker.data.api

import com.openclaw.musicworker.data.settings.AppSettingsStore
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MusicApiClient(
    private val settingsStore: AppSettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    suspend fun getHealth(): HealthPayload = get("/api/health")

    suspend fun getCurrentProxy(): ProxyInfo = get("/api/proxy/current")

    suspend fun search(keyword: String, limit: Int): SearchPayload {
        return post("/api/search", SearchRequest(keyword = keyword, limit = limit))
    }

    suspend fun startDownload(musicId: String): DownloadTask {
        return post("/api/download", DownloadRequest(musicId = musicId))
    }

    suspend fun getTask(taskId: String): DownloadTask = get("/api/tasks/$taskId")

    suspend fun getTasks(): List<DownloadTask> = get<TaskListPayload>("/api/tasks").tasks

    suspend fun getLogs(lines: Int): List<String> = get<LogLinesPayload>("/api/logs?lines=$lines").lines

    suspend fun getAppUpdate(): AppUpdateInfo = get("/api/app/update")

    suspend fun downloadFile(taskId: String, outputStream: OutputStream) = withContext(Dispatchers.IO) {
        downloadBinary(
            url = "${settingsStore.currentConfig().baseUrl}/api/files/$taskId",
            outputStream = outputStream,
            expectedContentTypes = listOf("audio/", "application/octet-stream"),
        )
    }

    suspend fun downloadFile(
        taskId: String,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        downloadBinary(
            url = "${settingsStore.currentConfig().baseUrl}/api/files/$taskId",
            outputStream = outputStream,
            expectedContentTypes = listOf("audio/", "application/octet-stream"),
            onProgress = onProgress,
        )
    }

    suspend fun downloadAppUpdate(downloadPath: String, outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val url = if (downloadPath.startsWith("http://") || downloadPath.startsWith("https://")) {
            downloadPath
        } else {
            "${settingsStore.currentConfig().baseUrl}$downloadPath"
        }
        downloadBinary(
            url = url,
            outputStream = outputStream,
            expectedContentTypes = listOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
            ),
        )
    }

    suspend fun downloadAppUpdate(
        downloadPath: String,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = if (downloadPath.startsWith("http://") || downloadPath.startsWith("https://")) {
            downloadPath
        } else {
            "${settingsStore.currentConfig().baseUrl}$downloadPath"
        }
        downloadBinary(
            url = url,
            outputStream = outputStream,
            expectedContentTypes = listOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
            ),
            onProgress = onProgress,
        )
    }

    private fun downloadBinary(
        url: String,
        outputStream: OutputStream,
        expectedContentTypes: List<String>,
        onProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val rawBody = response.body?.string().orEmpty()
                throw IOException(decodeErrorMessage(rawBody).ifBlank { "HTTP ${response.code}" })
            }

            val contentType = response.header("Content-Type").orEmpty()
            if (!matchesExpectedContentType(contentType, expectedContentTypes)) {
                val rawBody = response.body?.string().orEmpty()
                throw IOException(
                    decodeErrorMessage(rawBody).ifBlank {
                        "接口返回了非预期文件类型: ${contentType.ifBlank { "unknown" }}"
                    },
                )
            }

            val responseBody = response.body ?: throw IOException("API returned empty file body")
            val totalBytes = responseBody.contentLength().takeIf { it > 0 }
            responseBody.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_DOWNLOAD_BUFFER_SIZE)
                var downloadedBytes = 0L
                onProgress?.invoke(downloadedBytes, totalBytes)
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    outputStream.write(buffer, 0, readCount)
                    downloadedBytes += readCount
                    onProgress?.invoke(downloadedBytes, totalBytes)
                }
                outputStream.flush()
            }
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

    private companion object {
        const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 64 * 1024
    }

    suspend fun selectProxy(name: String): ProxyInfo {
        return post("/api/proxy/select", ProxySelectRequest(name = name))
    }

    private suspend inline fun <reified T> get(path: String): T {
        return request(path = path, method = "GET", payloadJson = null)
    }

    private suspend inline fun <reified T, reified Req> post(path: String, payload: Req): T {
        return request(
            path = path,
            method = "POST",
            payloadJson = json.encodeToString<Req>(payload),
        )
    }

    private suspend inline fun <reified T> request(
        path: String,
        method: String,
        payloadJson: String? = null,
    ): T = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url("${settingsStore.currentConfig().baseUrl}$path")

        if (payloadJson == null) {
            requestBuilder.method(method, null)
        } else {
            val body = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            requestBuilder.method(method, body)
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(decodeErrorMessage(rawBody).ifBlank { "HTTP ${response.code}" })
            }

            decodePayload(rawBody)
        }
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

    private fun decodeErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) {
            return ""
        }

        return runCatching {
            val root = json.parseToJsonElement(rawBody).jsonObject
            decodeErrorPayload(root["payload"]?.jsonObject)
        }.getOrDefault(rawBody)
    }

    private fun decodeErrorPayload(payload: JsonObject?): String {
        return payload
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.ifBlank { null }
            ?: "API request failed"
    }
}
