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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

    suspend fun getChartSources(): List<ChartSourceInfo> = get<ChartSourcesPayload>("/api/charts/sources").sources

    suspend fun getCharts(
        source: String = DEFAULT_CHART_SOURCE,
        type: String = DEFAULT_CHART_TYPE,
        period: String = DEFAULT_CHART_PERIOD,
        region: String = DEFAULT_CHART_REGION,
        limit: Int = DEFAULT_CHART_LIMIT,
        forceRefresh: Boolean = false,
    ): ChartPayload {
        val query = buildList {
            add("source=${encodeQueryValue(source)}")
            add("type=${encodeQueryValue(type)}")
            add("period=${encodeQueryValue(period)}")
            add("region=${encodeQueryValue(region)}")
            add("limit=${limit.coerceIn(1, MAX_CHART_LIMIT)}")
            if (forceRefresh) {
                add("force_refresh=1")
            }
        }.joinToString("&")
        return get("/api/charts?$query")
    }

    suspend fun search(keyword: String, limit: Int): SearchPayload {
        return post("/api/search", SearchRequest(keyword = keyword, limit = limit))
    }

    suspend fun startDownload(musicId: String): DownloadTask {
        return post("/api/download", DownloadRequest(musicId = musicId))
    }

    suspend fun startLyricsGeneration(musicId: String): DownloadTask {
        return post("/api/lyrics/generate", LyricsGenerateRequest(musicId = musicId))
    }

    suspend fun getDownloadedSongs(
        page: Int = 1,
        pageSize: Int = DEFAULT_DOWNLOADS_PAGE_SIZE,
    ): DownloadedSongsPayload {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_DOWNLOADS_PAGE_SIZE)
        return get("/api/downloads?page=$safePage&page_size=$safePageSize")
    }

    suspend fun getDownloadedSongLyrics(musicId: String): DownloadedLyricsPayload? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${settingsStore.currentConfig().baseUrl}/api/downloads/${encodePathSegment(musicId)}/lyrics")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (response.code == 404) {
                return@withContext null
            }
            if (!response.isSuccessful) {
                throw IOException(decodeErrorMessage(rawBody).ifBlank { "HTTP ${response.code}" })
            }
            decodePayload(rawBody)
        }
    }

    suspend fun getTask(taskId: String): DownloadTask = get("/api/tasks/$taskId")

    suspend fun getTasks(): List<DownloadTask> = get<TaskListPayload>("/api/tasks").tasks

    suspend fun getLogs(lines: Int): List<String> = get<LogLinesPayload>("/api/logs?lines=$lines").lines

    suspend fun getAppUpdate(): AppUpdateInfo = get("/api/app/update")

    fun taskFileUrl(taskId: String): String = "${settingsStore.currentConfig().baseUrl}/api/files/$taskId"

    fun downloadedSongFileUrl(musicId: String): String {
        return "${settingsStore.currentConfig().baseUrl}/api/downloads/${encodePathSegment(musicId)}/file"
    }

    suspend fun downloadFile(taskId: String, outputStream: OutputStream) = withContext(Dispatchers.IO) {
        downloadBinary(
            url = taskFileUrl(taskId),
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
            url = taskFileUrl(taskId),
            outputStream = outputStream,
            expectedContentTypes = listOf("audio/", "application/octet-stream"),
            onProgress = onProgress,
        )
    }

    suspend fun downloadDownloadedSong(
        musicId: String,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        downloadBinary(
            url = downloadedSongFileUrl(musicId),
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
                var lastReportedBytes = 0L
                var lastReportedAtMs = System.currentTimeMillis()
                onProgress?.invoke(downloadedBytes, totalBytes)
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    outputStream.write(buffer, 0, readCount)
                    downloadedBytes += readCount
                    val nowMs = System.currentTimeMillis()
                    val shouldReportProgress =
                        downloadedBytes == totalBytes ||
                        (downloadedBytes - lastReportedBytes) >= PROGRESS_CALLBACK_BYTE_STEP ||
                        (nowMs - lastReportedAtMs) >= PROGRESS_CALLBACK_INTERVAL_MS
                    if (shouldReportProgress) {
                        onProgress?.invoke(downloadedBytes, totalBytes)
                        lastReportedBytes = downloadedBytes
                        lastReportedAtMs = nowMs
                    }
                }
                if (downloadedBytes != lastReportedBytes) {
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

    private fun encodeQueryValue(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    private companion object {
        const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_CHART_SOURCE = "apple_music"
        const val DEFAULT_CHART_TYPE = "songs"
        const val DEFAULT_CHART_PERIOD = "daily"
        const val DEFAULT_CHART_REGION = "us"
        const val DEFAULT_CHART_LIMIT = 50
        const val MAX_CHART_LIMIT = 100
        const val DEFAULT_DOWNLOADS_PAGE_SIZE = 10
        const val MAX_DOWNLOADS_PAGE_SIZE = 100
        const val PROGRESS_CALLBACK_INTERVAL_MS = 250L
        const val PROGRESS_CALLBACK_BYTE_STEP = 256 * 1024L
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
