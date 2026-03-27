package com.openclaw.musicworker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.musicworker.BuildConfig
import com.openclaw.musicworker.data.ExportedFile
import com.openclaw.musicworker.data.InstalledAppInfo
import com.openclaw.musicworker.data.MusicRepository
import com.openclaw.musicworker.data.api.AppUpdateInfo
import com.openclaw.musicworker.data.api.DownloadTask
import com.openclaw.musicworker.data.api.HealthPayload
import com.openclaw.musicworker.data.api.ProxyInfo
import com.openclaw.musicworker.data.api.SearchItem
import com.openclaw.musicworker.data.settings.ApiServerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val health: HealthPayload? = null,
    val errorMessage: String? = null,
)

data class SearchUiState(
    val input: String = "",
    val activeKeyword: String = "",
    val isSearching: Boolean = false,
    val results: List<SearchItem> = emptyList(),
    val errorMessage: String? = null,
)

data class DownloadUiState(
    val currentTask: DownloadTask? = null,
    val selectedTitle: String? = null,
    val isPolling: Boolean = false,
    val isExporting: Boolean = false,
    val exportDownloadedBytes: Long = 0L,
    val exportTotalBytes: Long? = null,
    val savedFileName: String? = null,
    val savedFileUri: String? = null,
    val exportMessage: String? = null,
    val exportErrorMessage: String? = null,
    val errorMessage: String? = null,
)

data class SettingsUiState(
    val host: String = "",
    val port: String = "",
    val downloadDirectoryUri: String? = null,
    val downloadDirectoryLabel: String? = null,
    val privateStorageBytes: Long = 0L,
    val installedAppVersionName: String = "unknown",
    val installedAppVersionCode: Long = 0L,
    val availableAppUpdate: AppUpdateInfo? = null,
    val downloadedAppUpdateUri: String? = null,
    val downloadedAppUpdateName: String? = null,
    val pendingInstallAppUpdateUri: String? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long? = null,
    val isLoading: Boolean = false,
    val isCleaningPrivateStorage: Boolean = false,
    val isCheckingAppUpdate: Boolean = false,
    val isDownloadingAppUpdate: Boolean = false,
    val proxy: ProxyInfo? = null,
    val logs: List<String> = emptyList(),
    val message: String? = null,
    val errorMessage: String? = null,
)

data class AppUiState(
    val serverConfig: ApiServerConfig,
    val home: HomeUiState = HomeUiState(),
    val search: SearchUiState = SearchUiState(),
    val download: DownloadUiState = DownloadUiState(),
    val settings: SettingsUiState = SettingsUiState(),
)

class AppViewModel(
    private val repository: MusicRepository,
) : ViewModel() {
    private val initialInstalledAppInfo = runCatching { repository.getInstalledAppInfo() }
        .getOrElse { InstalledAppInfo(versionName = "unknown", versionCode = 0L) }

    private val _uiState = MutableStateFlow(
        AppUiState(
            serverConfig = repository.currentServerConfig(),
            settings = SettingsUiState(
                host = repository.currentServerConfig().host,
                port = repository.currentServerConfig().port.toString(),
                downloadDirectoryUri = repository.currentDownloadDirectoryUri(),
                downloadDirectoryLabel = repository.currentDownloadDirectoryLabel(),
                installedAppVersionName = initialInstalledAppInfo.versionName,
                installedAppVersionCode = initialInstalledAppInfo.versionCode,
            ),
        ),
    )

    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        refreshDashboard()
        refreshSettingsPanel()
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(home = state.home.copy(isLoading = true, errorMessage = null))
            }

            runCatching { repository.getHealth() }
                .onSuccess { health ->
                    _uiState.update { state ->
                        state.copy(home = HomeUiState(health = health))
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(home = HomeUiState(errorMessage = error.message ?: "健康检查失败"))
                    }
                }
        }
    }

    fun updateSearchInput(value: String) {
        _uiState.update { state ->
            state.copy(search = state.search.copy(input = value))
        }
    }

    fun search() {
        val keyword = _uiState.value.search.input.trim()
        if (keyword.isBlank()) {
            _uiState.update { state ->
                state.copy(search = state.search.copy(errorMessage = "请输入搜索关键词"))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    search = state.search.copy(
                        activeKeyword = keyword,
                        isSearching = true,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { repository.search(keyword = keyword) }
                .onSuccess { results ->
                    _uiState.update { state ->
                        state.copy(
                            search = state.search.copy(
                                activeKeyword = keyword,
                                isSearching = false,
                                results = results,
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            search = state.search.copy(
                                activeKeyword = keyword,
                                isSearching = false,
                                errorMessage = error.message ?: "搜索失败",
                            ),
                        )
                    }
                }
        }
    }

    fun startDownload(item: SearchItem) {
        if (repository.currentDownloadDirectoryUri().isNullOrBlank()) {
            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        selectedTitle = item.title,
                        errorMessage = "请先在设置页选择下载目录",
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        currentTask = null,
                        selectedTitle = item.title,
                        isPolling = false,
                        isExporting = false,
                        exportDownloadedBytes = 0L,
                        exportTotalBytes = null,
                        savedFileName = null,
                        savedFileUri = null,
                        exportMessage = null,
                        exportErrorMessage = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { repository.startDownload(item.id) }
                .onSuccess { task ->
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                currentTask = task,
                                selectedTitle = item.title,
                                isPolling = true,
                                isExporting = false,
                                savedFileName = null,
                                savedFileUri = null,
                                exportMessage = null,
                                exportErrorMessage = null,
                                errorMessage = null,
                            ),
                        )
                    }
                    beginPolling(task.taskId)
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                isPolling = false,
                                errorMessage = error.message ?: "创建下载任务失败",
                            ),
                        )
                    }
                }
        }
    }

    fun updateHost(value: String) {
        _uiState.update { state ->
            state.copy(settings = state.settings.copy(host = value, message = null, errorMessage = null))
        }
    }

    fun updatePort(value: String) {
        _uiState.update { state ->
            state.copy(settings = state.settings.copy(port = value, message = null, errorMessage = null))
        }
    }

    fun saveDownloadDirectory(uriString: String) {
        repository.saveDownloadDirectory(uriString)
        val label = repository.currentDownloadDirectoryLabel()
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    downloadDirectoryUri = repository.currentDownloadDirectoryUri(),
                    downloadDirectoryLabel = label,
                    message = "下载目录已设置为：${label ?: uriString}",
                    errorMessage = null,
                ),
            )
        }
    }

    fun clearDownloadDirectory() {
        repository.clearDownloadDirectory()
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    downloadDirectoryUri = null,
                    downloadDirectoryLabel = null,
                    message = "已清除下载目录",
                    errorMessage = null,
                ),
            )
        }
    }

    fun clearPrivateStorage() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    settings = state.settings.copy(
                        isCleaningPrivateStorage = true,
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { repository.clearPrivateStorage() }
                .onSuccess { result ->
                    _uiState.update { state ->
                        state.copy(
                            settings = state.settings.copy(
                                privateStorageBytes = result.remainingBytes,
                                isCleaningPrivateStorage = false,
                                downloadedAppUpdateUri = null,
                                downloadedAppUpdateName = null,
                                pendingInstallAppUpdateUri = null,
                                message = "已清理 ${formatBytes(result.freedBytes)}，剩余 ${formatBytes(result.remainingBytes)}",
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            settings = state.settings.copy(
                                isCleaningPrivateStorage = false,
                                errorMessage = error.message ?: "清理私有目录失败",
                            ),
                        )
                    }
                }
        }
    }

    fun checkAppUpdate() {
        viewModelScope.launch {
            val installedAppInfo = runCatching { repository.getInstalledAppInfo() }
                .getOrElse {
                    InstalledAppInfo(
                        versionName = _uiState.value.settings.installedAppVersionName,
                        versionCode = _uiState.value.settings.installedAppVersionCode,
                    )
                }

            _uiState.update { state ->
                state.copy(
                    settings = state.settings.copy(
                        installedAppVersionName = installedAppInfo.versionName,
                        installedAppVersionCode = installedAppInfo.versionCode,
                        isCheckingAppUpdate = true,
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { repository.getAppUpdate() }
                .onSuccess { updateInfo ->
                    _uiState.update { state ->
                        val shouldDropDownloadedApk = state.settings.downloadedAppUpdateName != null &&
                            state.settings.downloadedAppUpdateName != updateInfo.fileName
                        state.copy(
                            settings = state.settings.copy(
                                installedAppVersionName = installedAppInfo.versionName,
                                installedAppVersionCode = installedAppInfo.versionCode,
                                availableAppUpdate = updateInfo,
                                downloadedAppUpdateUri = if (shouldDropDownloadedApk) null else state.settings.downloadedAppUpdateUri,
                                downloadedAppUpdateName = if (shouldDropDownloadedApk) null else state.settings.downloadedAppUpdateName,
                                pendingInstallAppUpdateUri = if (shouldDropDownloadedApk) null else state.settings.pendingInstallAppUpdateUri,
                                appUpdateDownloadedBytes = if (shouldDropDownloadedApk) 0L else state.settings.appUpdateDownloadedBytes,
                                appUpdateTotalBytes = if (shouldDropDownloadedApk) updateInfo.fileSize else state.settings.appUpdateTotalBytes,
                                isCheckingAppUpdate = false,
                                message = buildUpdateCheckMessage(installedAppInfo, updateInfo),
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            settings = state.settings.copy(
                                installedAppVersionName = installedAppInfo.versionName,
                                installedAppVersionCode = installedAppInfo.versionCode,
                                isCheckingAppUpdate = false,
                                errorMessage = error.message ?: "检查更新失败",
                            ),
                        )
                    }
                }
        }
    }

    fun downloadAndInstallAppUpdate() {
        val state = _uiState.value.settings
        if (state.isDownloadingAppUpdate || state.isCheckingAppUpdate) {
            return
        }

        state.downloadedAppUpdateUri?.let { downloadedUri ->
            _uiState.update { current ->
                current.copy(
                    settings = current.settings.copy(
                        pendingInstallAppUpdateUri = downloadedUri,
                        message = "准备安装已下载更新包",
                        errorMessage = null,
                    ),
                )
            }
            return
        }

        val updateInfo = state.availableAppUpdate
        if (updateInfo == null) {
            _uiState.update { current ->
                current.copy(settings = current.settings.copy(errorMessage = "请先检查更新"))
            }
            return
        }

        if (updateInfo.versionCode != null && updateInfo.versionCode <= state.installedAppVersionCode) {
            _uiState.update { current ->
                current.copy(settings = current.settings.copy(errorMessage = "当前没有更高版本可安装"))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    settings = current.settings.copy(
                        isDownloadingAppUpdate = true,
                        appUpdateDownloadedBytes = 0L,
                        appUpdateTotalBytes = updateInfo.fileSize,
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching {
                repository.downloadAppUpdate(updateInfo) { downloadedBytes, totalBytes ->
                    _uiState.update { latest ->
                        latest.copy(
                            settings = latest.settings.copy(
                                appUpdateDownloadedBytes = downloadedBytes,
                                appUpdateTotalBytes = totalBytes ?: updateInfo.fileSize,
                            ),
                        )
                    }
                }
            }
                .onSuccess { downloadedUpdate ->
                    _uiState.update { current ->
                        current.copy(
                            settings = current.settings.copy(
                                isDownloadingAppUpdate = false,
                                downloadedAppUpdateUri = downloadedUpdate.contentUri,
                                downloadedAppUpdateName = downloadedUpdate.fileName,
                                pendingInstallAppUpdateUri = downloadedUpdate.contentUri,
                                appUpdateDownloadedBytes = updateInfo.fileSize,
                                appUpdateTotalBytes = updateInfo.fileSize,
                                message = "已下载更新包：${downloadedUpdate.fileName}",
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { current ->
                        current.copy(
                            settings = current.settings.copy(
                                isDownloadingAppUpdate = false,
                                errorMessage = error.message ?: "下载更新失败",
                            ),
                        )
                    }
                }
        }
    }

    fun onAppUpdateInstallHandled(message: String? = null, errorMessage: String? = null) {
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    pendingInstallAppUpdateUri = null,
                    message = message ?: state.settings.message,
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    fun saveServerConfig() {
        val host = _uiState.value.settings.host.trim()
        val port = _uiState.value.settings.port.trim().toIntOrNull()
        if (host.isBlank() || port == null) {
            _uiState.update { state ->
                state.copy(settings = state.settings.copy(errorMessage = "Host 或端口不合法"))
            }
            return
        }

        repository.saveServerConfig(host = host, port = port)
        val config = repository.currentServerConfig()
        _uiState.update { state ->
            state.copy(
                serverConfig = config,
                settings = state.settings.copy(
                    host = config.host,
                    port = config.port.toString(),
                    availableAppUpdate = null,
                    downloadedAppUpdateUri = null,
                    downloadedAppUpdateName = null,
                    pendingInstallAppUpdateUri = null,
                    appUpdateDownloadedBytes = 0L,
                    appUpdateTotalBytes = null,
                    message = "已保存 API 地址：${config.baseUrl}",
                    errorMessage = null,
                ),
            )
        }
        refreshDashboard()
        refreshSettingsPanel()
    }

    fun refreshSettingsPanel() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(settings = state.settings.copy(isLoading = true, errorMessage = null))
            }

            val installedAppInfo = runCatching { repository.getInstalledAppInfo() }
                .getOrElse {
                    InstalledAppInfo(
                        versionName = _uiState.value.settings.installedAppVersionName,
                        versionCode = _uiState.value.settings.installedAppVersionCode,
                    )
                }
            val proxyResult = runCatching { repository.getCurrentProxy() }
            val logsResult = runCatching { repository.getLogs(BuildConfig.DEFAULT_LOG_LINES) }
            val privateStorageResult = runCatching { repository.getPrivateStorageSummary() }

            _uiState.update { state ->
                state.copy(
                    settings = state.settings.copy(
                        host = state.serverConfig.host,
                        port = state.serverConfig.port.toString(),
                        downloadDirectoryUri = repository.currentDownloadDirectoryUri(),
                        downloadDirectoryLabel = repository.currentDownloadDirectoryLabel(),
                        privateStorageBytes = privateStorageResult.getOrNull()?.totalBytes ?: state.settings.privateStorageBytes,
                        installedAppVersionName = installedAppInfo.versionName,
                        installedAppVersionCode = installedAppInfo.versionCode,
                        isLoading = false,
                        proxy = proxyResult.getOrNull(),
                        logs = logsResult.getOrDefault(emptyList()),
                        errorMessage = proxyResult.exceptionOrNull()?.message
                            ?: logsResult.exceptionOrNull()?.message
                            ?: privateStorageResult.exceptionOrNull()?.message,
                    ),
                )
            }
        }
    }

    fun selectProxy(name: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(settings = state.settings.copy(isLoading = true, message = null, errorMessage = null))
            }

            runCatching { repository.selectProxy(name) }
                .onSuccess { proxy ->
                    _uiState.update { state ->
                        state.copy(
                            settings = state.settings.copy(
                                isLoading = false,
                                proxy = proxy,
                                message = "节点已切换到：${proxy.name.orEmpty()}",
                                errorMessage = null,
                            ),
                        )
                    }
                    refreshDashboard()
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            settings = state.settings.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "切换节点失败",
                            ),
                        )
                    }
                }
        }
    }

    fun retryExportCurrentTask() {
        val task = _uiState.value.download.currentTask
        if (task?.status != "finished" || _uiState.value.download.isExporting) {
            return
        }

        viewModelScope.launch {
            exportFinishedTask(task)
        }
    }

    private fun beginPolling(taskId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val taskResult = runCatching { repository.getTask(taskId) }
                if (taskResult.isFailure) {
                    val error = taskResult.exceptionOrNull()
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                isPolling = false,
                                errorMessage = error?.message ?: "轮询任务失败",
                            ),
                        )
                    }
                    break
                }

                val task = taskResult.getOrThrow()
                _uiState.update { state ->
                    state.copy(
                        download = state.download.copy(
                            currentTask = task,
                            isPolling = task.status in setOf("queued", "running"),
                            errorMessage = task.errorMessage,
                        ),
                    )
                }

                if (task.status == "finished") {
                    exportFinishedTask(task)
                    refreshDashboard()
                    break
                }

                if (task.status == "failed") {
                    refreshDashboard()
                    break
                }

                delay(2000)
            }
        }
    }

    private suspend fun exportFinishedTask(task: DownloadTask) {
        _uiState.update { state ->
            state.copy(
                download = state.download.copy(
                    currentTask = task,
                    isPolling = false,
                    isExporting = true,
                    exportDownloadedBytes = 0L,
                    exportTotalBytes = task.fileSize,
                    exportMessage = "正在保存到所选目录…",
                    exportErrorMessage = null,
                    errorMessage = task.errorMessage,
                ),
            )
        }

        runCatching {
            repository.exportTaskFile(task) { downloadedBytes, totalBytes ->
                _uiState.update { state ->
                    state.copy(
                        download = state.download.copy(
                            currentTask = task,
                            isPolling = false,
                            isExporting = true,
                            exportDownloadedBytes = downloadedBytes,
                            exportTotalBytes = totalBytes ?: task.fileSize,
                            exportMessage = "正在保存到所选目录…",
                            exportErrorMessage = null,
                            errorMessage = task.errorMessage,
                        ),
                    )
                }
            }
        }
            .onSuccess { exportedFile ->
                applyExportSuccess(task, exportedFile)
            }
            .onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        download = state.download.copy(
                            currentTask = task,
                            isPolling = false,
                            isExporting = false,
                            exportDownloadedBytes = 0L,
                            exportTotalBytes = null,
                            exportMessage = null,
                            exportErrorMessage = error.message ?: "保存文件失败",
                            errorMessage = task.errorMessage,
                        ),
                    )
                }
            }
    }

    private fun applyExportSuccess(task: DownloadTask, exportedFile: ExportedFile) {
        _uiState.update { state ->
            state.copy(
                download = state.download.copy(
                    currentTask = task,
                    isPolling = false,
                    isExporting = false,
                    exportDownloadedBytes = 0L,
                    exportTotalBytes = null,
                    savedFileName = exportedFile.fileName,
                    savedFileUri = exportedFile.fileUri,
                    exportMessage = "已直接保存到所选目录：${exportedFile.fileName}",
                    exportErrorMessage = null,
                    errorMessage = task.errorMessage,
                ),
            )
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private fun buildUpdateCheckMessage(installedAppInfo: InstalledAppInfo, updateInfo: AppUpdateInfo): String {
        return when {
            updateInfo.versionCode == null || updateInfo.versionName.isNullOrBlank() -> {
                "已获取可安装更新包：${updateInfo.fileName}"
            }
            updateInfo.versionCode > installedAppInfo.versionCode -> {
                "发现新版本：${updateInfo.versionName} (${updateInfo.versionCode})"
            }
            else -> {
                "当前已是最新版本：${installedAppInfo.versionName} (${installedAppInfo.versionCode})"
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) {
            return "0 B"
        }

        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index += 1
        }

        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format("%.1f %s", value, units[index])
        }
    }
}
