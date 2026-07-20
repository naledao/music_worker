package com.openclaw.musicworker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AndroidSettingsModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "AndroidSettings"

    private var pendingDirectoryPromise: Promise? = null

    private val activityEventListener: ActivityEventListener = object : BaseActivityEventListener() {
        override fun onActivityResult(
            activity: Activity?,
            requestCode: Int,
            resultCode: Int,
            data: Intent?,
        ) {
            if (requestCode != REQUEST_PICK_DOWNLOAD_DIRECTORY) {
                return
            }

            val promise = pendingDirectoryPromise ?: return
            pendingDirectoryPromise = null

            if (resultCode != Activity.RESULT_OK || data?.data == null) {
                promise.reject("DOWNLOAD_DIRECTORY_CANCELLED", "未选择下载目录")
                return
            }

            try {
                val uri = data.data ?: throw IllegalStateException("目录地址为空")
                persistTreeUriPermission(uri, data.flags)
                preferences
                    .edit()
                    .putString(KEY_DOWNLOAD_TREE_URI, uri.toString())
                    .apply()
                promise.resolve(downloadDirectoryMap(uri.toString()))
            } catch (error: Throwable) {
                promise.reject("DOWNLOAD_DIRECTORY_SAVE_FAILED", error.message, error)
            }
        }
    }

    init {
        reactContext.addActivityEventListener(activityEventListener)
    }

    private val preferences by lazy {
        reactContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @ReactMethod
    fun getApiConfig(promise: Promise) {
        try {
            promise.resolve(apiConfigMap(readHost(), readPort()))
        } catch (error: Throwable) {
            promise.reject("API_CONFIG_READ_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun saveApiConfig(host: String, port: String, promise: Promise) {
        try {
            val normalizedHost = normalizeHost(host)
            val normalizedPort = normalizePort(port)
            preferences
                .edit()
                .putString(KEY_API_HOST, normalizedHost)
                .putString(KEY_API_PORT, normalizedPort)
                .apply()
            promise.resolve(apiConfigMap(normalizedHost, normalizedPort))
        } catch (error: Throwable) {
            promise.reject("API_CONFIG_SAVE_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getDownloadDirectory(promise: Promise) {
        try {
            promise.resolve(downloadDirectoryMap(readDownloadDirectoryUri()))
        } catch (error: Throwable) {
            promise.reject("DOWNLOAD_DIRECTORY_READ_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun pickDownloadDirectory(promise: Promise) {
        if (pendingDirectoryPromise != null) {
            promise.reject("DOWNLOAD_DIRECTORY_PICKER_BUSY", "目录选择器已打开")
            return
        }

        val activity = currentActivity
        if (activity == null) {
            promise.reject("DOWNLOAD_DIRECTORY_NO_ACTIVITY", "当前没有可用的 Activity")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                val currentUri = readDownloadDirectoryUri()
                if (!currentUri.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(currentUri))
                }
            }

            pendingDirectoryPromise = promise
            activity.startActivityForResult(intent, REQUEST_PICK_DOWNLOAD_DIRECTORY)
        } catch (error: Throwable) {
            pendingDirectoryPromise = null
            promise.reject("DOWNLOAD_DIRECTORY_PICK_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun clearDownloadDirectory(promise: Promise) {
        try {
            readDownloadDirectoryUri()?.let(::releasePersistedTreePermission)
            preferences
                .edit()
                .remove(KEY_DOWNLOAD_TREE_URI)
                .apply()
            promise.resolve(downloadDirectoryMap(null))
        } catch (error: Throwable) {
            promise.reject("DOWNLOAD_DIRECTORY_CLEAR_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun saveUrlToDownloadDirectory(url: String, fileName: String?, musicId: String?, promise: Promise) {
        Thread {
            try {
                val result = downloadUrlToSelectedDirectory(url, fileName, musicId)
                promise.resolve(result)
            } catch (error: Throwable) {
                promise.reject("DOWNLOAD_FILE_SAVE_FAILED", error.message, error)
            }
        }.start()
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required by NativeEventEmitter.
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required by NativeEventEmitter.
    }

    private fun readHost(): String {
        return normalizeHost(preferences.getString(KEY_API_HOST, DEFAULT_API_HOST).orEmpty())
    }

    private fun readPort(): String {
        return normalizePort(preferences.getString(KEY_API_PORT, DEFAULT_API_PORT).orEmpty())
    }

    private fun apiConfigMap(host: String, port: String) =
        Arguments.createMap().apply {
            putString("host", host)
            putString("port", port)
        }

    private fun readDownloadDirectoryUri(): String? {
        return preferences.getString(KEY_DOWNLOAD_TREE_URI, null)
            ?.trim()
            ?.takeUnless { it.isBlank() }
    }

    private fun downloadDirectoryMap(uriString: String?) =
        Arguments.createMap().apply {
            if (uriString.isNullOrBlank()) {
                putNull("uri")
                putNull("label")
            } else {
                putString("uri", uriString)
                putString("label", buildDownloadDirectoryLabel(uriString))
            }
        }

    private fun buildDownloadDirectoryLabel(uriString: String): String {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString

        val displayName = runCatching {
            reactContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (displayNameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(displayNameIndex)?.trim()
                } else {
                    null
                }
            }
        }.getOrNull()
        if (!displayName.isNullOrBlank()) {
            return displayName
        }

        return runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull()
            ?.substringAfterLast(':')
            ?.ifBlank { null }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
            ?: uriString
    }

    private fun persistTreeUriPermission(uri: Uri, intentFlags: Int) {
        val requestedFlags = intentFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val flags = if (requestedFlags != 0) {
            requestedFlags
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        runCatching {
            reactContext.contentResolver.takePersistableUriPermission(uri, flags)
        }.recoverCatching {
            reactContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.getOrThrow()
    }

    private fun releasePersistedTreePermission(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        runCatching {
            reactContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.recoverCatching {
            reactContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun downloadUrlToSelectedDirectory(url: String, rawFileName: String?, rawMusicId: String?) =
        Arguments.createMap().apply {
            val sourceUrl = url.trim()
            if (sourceUrl.isBlank()) {
                throw IOException("下载地址为空")
            }
            val musicId = rawMusicId?.trim()?.takeUnless { it.isBlank() }

            val treeUriString = readDownloadDirectoryUri()
                ?: throw IOException("请先在设置页选择下载目录")
            val treeUri = runCatching { Uri.parse(treeUriString) }
                .getOrElse { throw IOException("下载目录配置无效，请重新选择") }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )

            val outputFileName = resolveAvailableFileName(
                treeUri,
                sanitizeFileName(rawFileName.orEmpty()),
            )
            val mimeType = guessMimeType(outputFileName)
            val targetUri = DocumentsContract.createDocument(
                reactContext.contentResolver,
                parentUri,
                mimeType,
                outputFileName,
            ) ?: throw IOException("无法在所选目录创建文件")

            var downloadedBytes = 0L
            try {
                val outputStream = reactContext.contentResolver.openOutputStream(targetUri, "w")
                    ?: throw IOException("无法写入所选目录")
                outputStream.use { output ->
                    downloadedBytes = downloadUrlToStream(sourceUrl, output, musicId)
                }
            } catch (error: Throwable) {
                runCatching {
                    DocumentsContract.deleteDocument(reactContext.contentResolver, targetUri)
                }
                throw error
            }

            putString("fileName", outputFileName)
            putString("fileUri", targetUri.toString())
            putDouble("fileSize", downloadedBytes.toDouble())
        }

    private fun downloadUrlToStream(sourceUrl: String, output: java.io.OutputStream, musicId: String?): Long {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "audio/*,application/octet-stream,*/*;q=0.8")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException(errorText.ifBlank { "下载失败：HTTP $responseCode" })
            }

            val contentType = (connection.contentType ?: "").substringBefore(';').trim().lowercase(Locale.US)
            if (
                contentType.isNotBlank() &&
                    !contentType.startsWith("audio/") &&
                    contentType != "application/octet-stream"
            ) {
                throw IOException("接口返回了非音频文件类型：$contentType")
            }

            connection.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                var totalBytes = 0L
                val expectedBytes = connection.contentLengthLong.takeIf { it > 0 }
                var lastReportedBytes = 0L
                var lastReportedAtMs = System.currentTimeMillis()
                emitSaveProgress(musicId, totalBytes, expectedBytes)
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    output.write(buffer, 0, readCount)
                    totalBytes += readCount.toLong()
                    val nowMs = System.currentTimeMillis()
                    val shouldReport =
                        totalBytes == expectedBytes ||
                            totalBytes - lastReportedBytes >= 256 * 1024 ||
                            nowMs - lastReportedAtMs >= 500
                    if (shouldReport) {
                        emitSaveProgress(musicId, totalBytes, expectedBytes)
                        lastReportedBytes = totalBytes
                        lastReportedAtMs = nowMs
                    }
                }
                output.flush()
                if (totalBytes != lastReportedBytes) {
                    emitSaveProgress(musicId, totalBytes, expectedBytes)
                }
                return totalBytes
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun emitSaveProgress(musicId: String?, downloadedBytes: Long, totalBytes: Long?) {
        val progress = if (totalBytes != null && totalBytes > 0) {
            downloadedBytes.toDouble() / totalBytes.toDouble()
        } else {
            0.0
        }
        val payload = Arguments.createMap().apply {
            putString("musicId", musicId)
            putDouble("downloadedBytes", downloadedBytes.toDouble())
            if (totalBytes != null && totalBytes > 0) {
                putDouble("totalBytes", totalBytes.toDouble())
            } else {
                putNull("totalBytes")
            }
            putDouble("progress", progress.coerceIn(0.0, 1.0))
        }
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("AndroidSettingsFileSaveProgress", payload)
    }

    private fun resolveAvailableFileName(treeUri: Uri, desiredFileName: String): String {
        val existingNames = listChildDisplayNames(treeUri)
        if (desiredFileName !in existingNames) {
            return desiredFileName
        }

        val dotIndex = desiredFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) desiredFileName.substring(0, dotIndex) else desiredFileName
        val extension = if (dotIndex > 0) desiredFileName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = "$baseName ($counter)$extension"
            if (candidate !in existingNames) {
                return candidate
            }
            counter += 1
        }
    }

    private fun listChildDisplayNames(treeUri: Uri): Set<String> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return reactContext.contentResolver.query(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val names = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (nameIndex >= 0 && cursor.moveToNext()) {
                cursor.getString(nameIndex)?.trim()?.takeUnless { it.isBlank() }?.let(names::add)
            }
            names
        } ?: emptySet()
    }

    private fun sanitizeFileName(rawFileName: String): String {
        val cleaned = rawFileName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.')
        val fallback = cleaned.ifBlank { "music_worker.mp3" }
        return if (fallback.substringAfterLast('.', "").isBlank()) {
            "$fallback.mp3"
        } else {
            fallback
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .ifBlank { return "audio/mpeg" }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "audio/mpeg"
    }

    private fun normalizeHost(value: String): String {
        return value.trim().ifBlank { DEFAULT_API_HOST }
    }

    private fun normalizePort(value: String): String {
        val port = value.trim().toIntOrNull()
        return if (port != null && port in 1..65535) {
            port.toString()
        } else {
            DEFAULT_API_PORT
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "music_worker_settings"
        private const val KEY_API_HOST = "api_host"
        private const val KEY_API_PORT = "api_port"
        private const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri"
        private const val DEFAULT_API_HOST = "127.0.0.1"
        private const val DEFAULT_API_PORT = "18081"
        private const val REQUEST_PICK_DOWNLOAD_DIRECTORY = 42031
    }
}
