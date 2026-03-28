package com.openclaw.musicworker.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val ok: Boolean,
    val payload: T,
)

@Serializable
data class ErrorPayload(
    val message: String? = null,
)

@Serializable
data class LocalServiceInfo(
    val name: String,
    val host: String,
    val port: Int,
)

@Serializable
data class CookieSnapshot(
    val file: String? = null,
    val exists: Boolean = false,
    val size: Long = 0,
    val hasYoutube: Boolean = false,
    val enabled: Boolean = false,
)

@Serializable
data class RuntimeProxySnapshot(
    val ytdlp: String? = null,
    val ws: String? = null,
)

@Serializable
data class YtDlpSnapshot(
    val version: String? = null,
    val path: String? = null,
    val jsRuntime: String? = null,
    val remoteComponents: String? = null,
    val playerClients: List<String> = emptyList(),
    val fetchPot: String? = null,
    val potTrace: Boolean = false,
    val pluginDir: String? = null,
    val potHttp: String? = null,
    val potCli: String? = null,
    val potScript: String? = null,
)

@Serializable
data class RuntimeSnapshot(
    val cwd: String? = null,
    val baseDir: String? = null,
    val cookies: CookieSnapshot? = null,
    val proxy: RuntimeProxySnapshot? = null,
    val ytDlp: YtDlpSnapshot? = null,
    val ffmpeg: String? = null,
)

@Serializable
data class TaskStats(
    val total: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val finished: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class ProxyInfo(
    val selector: String? = null,
    val name: String? = null,
    val alive: Boolean? = null,
    val options: List<String> = emptyList(),
)

@Serializable
data class HealthPayload(
    val service: LocalServiceInfo,
    val runtime: RuntimeSnapshot,
    val tasks: TaskStats,
    val proxy: ProxyInfo,
)

@Serializable
data class SearchItem(
    val id: String,
    val title: String,
    val channel: String? = null,
    val duration: Double? = null,
    val cover: String? = null,
    val downloaded: Boolean = false,
    val downloadedFilePath: String? = null,
    val downloadedFileSize: Long? = null,
    val downloadedAt: String? = null,
)

@Serializable
data class SearchPayload(
    val keyword: String,
    val results: List<SearchItem>,
)

@Serializable
data class SearchRequest(
    val keyword: String,
    val limit: Int = 20,
)

@Serializable
data class DownloadRequest(
    val musicId: String,
)

@Serializable
data class ProxySelectRequest(
    val name: String,
    val password: String? = null,
    val client: String? = null,
)

@Serializable
data class DownloadTask(
    val taskId: String,
    val type: String = "download",
    val musicId: String,
    val status: String,
    val stage: String,
    val progress: Int,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val filename: String? = null,
    val filePath: String? = null,
    val fileSize: Long? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val speedBps: Double? = null,
    val etaSec: Int? = null,
    val strategy: String? = null,
    val errorMessage: String? = null,
    val errorClass: String? = null,
)

@Serializable
data class TaskListPayload(
    val tasks: List<DownloadTask>,
)

@Serializable
data class LogLinesPayload(
    val lines: List<String>,
)

@Serializable
data class AppUpdateInfo(
    val versionCode: Long? = null,
    val versionName: String? = null,
    val fileName: String,
    val fileSize: Long,
    val sha256: String? = null,
    val updatedAt: String? = null,
    val downloadPath: String,
)
