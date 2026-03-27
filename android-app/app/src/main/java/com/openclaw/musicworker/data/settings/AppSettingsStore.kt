package com.openclaw.musicworker.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.openclaw.musicworker.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ApiServerConfig(
    val host: String,
    val port: Int,
) {
    val baseUrl: String
        get() = "http://$host:$port"
}

class AppSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(loadConfig())

    val config: StateFlow<ApiServerConfig> = _config.asStateFlow()

    fun currentConfig(): ApiServerConfig = _config.value

    fun saveApiServer(host: String, port: Int) {
        val normalizedHost = host.trim().ifEmpty { BuildConfig.DEFAULT_API_HOST }
        val normalizedPort = port.coerceAtLeast(1)
        prefs.edit()
            .putString(KEY_API_HOST, normalizedHost)
            .putInt(KEY_API_PORT, normalizedPort)
            .apply()
        _config.value = ApiServerConfig(host = normalizedHost, port = normalizedPort)
    }

    fun currentDownloadDirectoryUri(): String? {
        return prefs.getString(KEY_DOWNLOAD_TREE_URI, null)?.trim().takeUnless { it.isNullOrEmpty() }
    }

    fun currentDownloadDirectoryLabel(): String? {
        return currentDownloadDirectoryUri()?.let(::buildDownloadDirectoryLabel)
    }

    fun saveDownloadDirectory(uriString: String) {
        prefs.edit()
            .putString(KEY_DOWNLOAD_TREE_URI, uriString)
            .apply()
    }

    fun clearDownloadDirectory() {
        prefs.edit()
            .remove(KEY_DOWNLOAD_TREE_URI)
            .apply()
    }

    private fun loadConfig(): ApiServerConfig {
        return ApiServerConfig(
            host = prefs.getString(KEY_API_HOST, BuildConfig.DEFAULT_API_HOST).orEmpty().ifBlank {
                BuildConfig.DEFAULT_API_HOST
            },
            port = prefs.getInt(KEY_API_PORT, BuildConfig.DEFAULT_API_PORT),
        )
    }

    private fun buildDownloadDirectoryLabel(uriString: String): String {
        val treeUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
        val documentName = DocumentFile.fromTreeUri(appContext, treeUri)
            ?.name
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
        if (documentName != null) {
            return documentName
        }

        val lastSegment = treeUri.lastPathSegment.orEmpty()
        return lastSegment.substringAfterLast(':').ifBlank { uriString }
    }

    companion object {
        private const val PREFS_NAME = "music_worker_settings"
        private const val KEY_API_HOST = "api_host"
        private const val KEY_API_PORT = "api_port"
        private const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri"
    }
}
