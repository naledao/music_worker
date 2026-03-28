package com.openclaw.musicworker.desktop

import com.openclaw.musicworker.shared.api.AppUpdateInfo
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.HealthPayload
import com.openclaw.musicworker.shared.api.ProxyInfo
import com.openclaw.musicworker.shared.api.SearchItem
import com.openclaw.musicworker.shared.config.ApiServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SearchSortMode(val label: String) {
    DEFAULT("默认"),
    TITLE_ASC("标题 A-Z"),
    DURATION_ASC("时长短到长"),
    DURATION_DESC("时长长到短"),
}

enum class SearchFilterMode(val label: String) {
    ALL("全部"),
    UNDER_4_MIN("短于 4 分钟"),
    BETWEEN_4_AND_10_MIN("4-10 分钟"),
    OVER_10_MIN("长于 10 分钟"),
}

data class DesktopHealthUiState(
    val isLoading: Boolean = false,
    val health: HealthPayload? = null,
    val errorMessage: String? = null,
)

data class DesktopSearchUiState(
    val input: String = "",
    val activeKeyword: String = "",
    val isSearching: Boolean = false,
    val results: List<SearchItem> = emptyList(),
    val visibleResults: List<SearchItem> = emptyList(),
    val sortMode: SearchSortMode = SearchSortMode.DEFAULT,
    val filterMode: SearchFilterMode = SearchFilterMode.ALL,
    val selectedResultId: String? = null,
    val errorMessage: String? = null,
)

data class DesktopDownloadUiState(
    val currentTask: DownloadTask? = null,
    val selectedTitle: String? = null,
    val isStarting: Boolean = false,
    val isPolling: Boolean = false,
    val errorMessage: String? = null,
)

data class DesktopOpsUiState(
    val isLoading: Boolean = false,
    val proxy: ProxyInfo? = null,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class DesktopUpdateUiState(
    val currentVersionName: String = "0.1.1",
    val currentVersionCode: Long = 2L,
    val availableUpdate: AppUpdateInfo? = null,
    val downloadedInstallerPath: String? = null,
    val downloadedInstallerName: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class DesktopUiState(
    val currentPage: DesktopPage = DesktopPage.SEARCH,
    val serverConfig: ApiServerConfig = ApiServerConfig(),
    val health: DesktopHealthUiState = DesktopHealthUiState(),
    val search: DesktopSearchUiState = DesktopSearchUiState(),
    val download: DesktopDownloadUiState = DesktopDownloadUiState(),
    val ops: DesktopOpsUiState = DesktopOpsUiState(),
    val update: DesktopUpdateUiState = DesktopUpdateUiState(),
    val message: String? = null,
)

class DesktopAppState(
    private val settingsStore: DesktopSettingsStore = DesktopSettingsStore(),
    private val apiClient: DesktopMusicApiClient = DesktopMusicApiClient(),
    private val updateManager: DesktopUpdateManager = DesktopUpdateManager(),
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        DesktopFileLogger.error("unhandled coroutine exception in DesktopAppState", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)
    private var pollJob: Job? = null
    private var pollingTaskId: String? = null

    private val _uiState = MutableStateFlow(
        DesktopUiState(
            serverConfig = settingsStore.load(),
            update = DesktopUpdateUiState(
                currentVersionName = updateManager.currentVersionName(),
                currentVersionCode = updateManager.currentVersionCode(),
            ),
        ),
    )
    val uiState: StateFlow<DesktopUiState> = _uiState.asStateFlow()

    fun initialize() {
        DesktopFileLogger.info("DesktopAppState initialized baseUrl=${_uiState.value.serverConfig.baseUrl}")
        refreshHealth()
        refreshOperations()
        refreshLatestTask()
    }

    fun switchPage(page: DesktopPage) {
        _uiState.update { state ->
            state.copy(currentPage = page)
        }
    }

    fun updateHost(value: String) {
        _uiState.update { state ->
            state.copy(serverConfig = state.serverConfig.copy(host = value.trim()))
        }
    }

    fun updatePort(value: String) {
        val port = value.trim().toIntOrNull() ?: return
        _uiState.update { state ->
            state.copy(serverConfig = state.serverConfig.copy(port = port))
        }
    }

    fun updateSearchInput(value: String) {
        _uiState.update { state ->
            state.copy(search = state.search.copy(input = value))
        }
    }

    fun selectSearchResult(itemId: String) {
        _uiState.update { state ->
            state.copy(
                search = state.search.copy(selectedResultId = itemId),
            )
        }
    }

    fun updateSearchSortMode(mode: SearchSortMode) {
        _uiState.update { state ->
            state.copy(search = state.search.rebuildVisibleResults(sortMode = mode))
        }
    }

    fun updateSearchFilterMode(mode: SearchFilterMode) {
        _uiState.update { state ->
            state.copy(search = state.search.rebuildVisibleResults(filterMode = mode))
        }
    }

    fun saveConfig() {
        val config = _uiState.value.serverConfig
        settingsStore.save(config)
        DesktopFileLogger.info("desktop API config updated baseUrl=${config.baseUrl}")
        stopPolling()
        _uiState.update { state ->
            state.copy(
                download = DesktopDownloadUiState(),
                ops = DesktopOpsUiState(),
                update = state.update.copy(
                    availableUpdate = null,
                    downloadedInstallerPath = null,
                    downloadedInstallerName = null,
                    downloadedBytes = 0L,
                    totalBytes = null,
                    isChecking = false,
                    isDownloading = false,
                    message = null,
                    errorMessage = null,
                ),
                message = "已保存本地 API 地址：${config.baseUrl}",
            )
        }
        refreshHealth()
        refreshOperations()
        refreshLatestTask()
    }

    fun refreshHealth() {
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    health = state.health.copy(isLoading = true, errorMessage = null),
                    message = null,
                )
            }

            runCatching { apiClient.getHealth(_uiState.value.serverConfig) }
                .onSuccess { health ->
                    _uiState.update { state ->
                        state.copy(
                            health = DesktopHealthUiState(health = health),
                            message = "桌面端已连接到本地 API",
                        )
                    }
                }
                .onFailure { error ->
                    logFailure("health check failed", error, "baseUrl=${_uiState.value.serverConfig.baseUrl}")
                    _uiState.update { state ->
                        state.copy(
                            health = DesktopHealthUiState(errorMessage = error.message ?: "健康检查失败"),
                        )
                    }
                }
        }
    }

    fun checkAppUpdate() {
        val currentVersionName = updateManager.currentVersionName()
        val currentVersionCode = updateManager.currentVersionCode()
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    update = state.update.copy(
                        currentVersionName = currentVersionName,
                        currentVersionCode = currentVersionCode,
                        isChecking = true,
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { apiClient.getAppUpdate(_uiState.value.serverConfig, platform = UPDATE_PLATFORM_DESKTOP) }
                .onSuccess { updateInfo ->
                    _uiState.update { state ->
                        val shouldDropDownloadedInstaller = state.update.downloadedInstallerName != null &&
                            state.update.downloadedInstallerName != updateInfo.fileName
                        state.copy(
                            update = state.update.copy(
                                currentVersionName = currentVersionName,
                                currentVersionCode = currentVersionCode,
                                availableUpdate = updateInfo,
                                downloadedInstallerPath = if (shouldDropDownloadedInstaller) null else state.update.downloadedInstallerPath,
                                downloadedInstallerName = if (shouldDropDownloadedInstaller) null else state.update.downloadedInstallerName,
                                downloadedBytes = if (shouldDropDownloadedInstaller) 0L else state.update.downloadedBytes,
                                totalBytes = if (shouldDropDownloadedInstaller) updateInfo.fileSize else state.update.totalBytes,
                                isChecking = false,
                                isDownloading = false,
                                message = buildUpdateCheckMessage(currentVersionName, currentVersionCode, updateInfo),
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    logFailure("update check failed", error, "baseUrl=${_uiState.value.serverConfig.baseUrl}")
                    _uiState.update { state ->
                        state.copy(
                            update = state.update.copy(
                                currentVersionName = currentVersionName,
                                currentVersionCode = currentVersionCode,
                                isChecking = false,
                                errorMessage = error.message ?: "检查更新失败",
                            ),
                        )
                    }
                }
        }
    }

    fun downloadOrOpenAppUpdate() {
        val updateState = _uiState.value.update
        if (updateState.isChecking || updateState.isDownloading) {
            return
        }

        updateState.downloadedInstallerPath?.let { installerPath ->
            runCatching { updateManager.openInstaller(installerPath) }
                .onSuccess {
                    DesktopFileLogger.info("opened installer path=$installerPath")
                    _uiState.update { state ->
                        state.copy(
                            update = state.update.copy(
                                message = "已打开安装包：${state.update.downloadedInstallerName ?: installerPath}",
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    logFailure("open installer failed", error, "path=$installerPath")
                    _uiState.update { state ->
                        state.copy(
                            update = state.update.copy(
                                errorMessage = error.message ?: "打开安装包失败",
                            ),
                        )
                    }
                }
            return
        }

        val updateInfo = updateState.availableUpdate
        if (updateInfo == null) {
            DesktopFileLogger.warn("downloadOrOpenAppUpdate ignored because no update info is available")
            _uiState.update { state ->
                state.copy(
                    update = state.update.copy(
                        errorMessage = "请先检查更新",
                    ),
                )
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    update = state.update.copy(
                        isDownloading = true,
                        downloadedBytes = 0L,
                        totalBytes = updateInfo.fileSize,
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching {
                updateManager.downloadUpdate(
                    apiClient = apiClient,
                    config = _uiState.value.serverConfig,
                    updateInfo = updateInfo,
                ) { downloadedBytes, totalBytes ->
                    _uiState.update { state ->
                        state.copy(
                            update = state.update.copy(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes ?: updateInfo.fileSize,
                            ),
                        )
                    }
                }
            }
                .onSuccess { downloadedUpdate ->
                    DesktopFileLogger.info(
                        "desktop update downloaded file=${downloadedUpdate.fileName} path=${downloadedUpdate.filePath}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            update = state.update.copy(
                                isDownloading = false,
                                downloadedInstallerPath = downloadedUpdate.filePath,
                                downloadedInstallerName = downloadedUpdate.fileName,
                                downloadedBytes = updateInfo.fileSize,
                                totalBytes = updateInfo.fileSize,
                                message = "已下载安装包：${downloadedUpdate.fileName}",
                                errorMessage = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    logFailure(
                        "download desktop update failed",
                        error,
                        "file=${updateInfo.fileName} baseUrl=${_uiState.value.serverConfig.baseUrl}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            update = state.update.copy(
                                isDownloading = false,
                                errorMessage = error.message ?: "下载安装包失败",
                            ),
                        )
                    }
                }
        }
    }

    fun search() {
        val keyword = _uiState.value.search.input.trim()
        if (keyword.isBlank()) {
            DesktopFileLogger.warn("search ignored because keyword is blank")
            _uiState.update { state ->
                state.copy(
                    search = state.search.copy(errorMessage = "请输入搜索关键词"),
                )
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    search = state.search.copy(
                        activeKeyword = keyword,
                        isSearching = true,
                        errorMessage = null,
                    ),
                    message = null,
                )
            }

            runCatching { apiClient.search(_uiState.value.serverConfig, keyword) }
                .onSuccess { results ->
                    DesktopFileLogger.info("search completed keyword=$keyword resultCount=${results.size}")
                    _uiState.update { state ->
                        state.copy(
                            search = state.search.copy(
                                activeKeyword = keyword,
                                isSearching = false,
                                errorMessage = null,
                            ).rebuildVisibleResults(results = results),
                            message = "桌面端搜索完成，共 ${results.size} 条结果",
                        )
                    }
                }
                .onFailure { error ->
                    logFailure("search failed", error, "keyword=$keyword baseUrl=${_uiState.value.serverConfig.baseUrl}")
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
        val currentDownload = _uiState.value.download
        if (currentDownload.isStarting || currentDownload.currentTask?.status in ACTIVE_TASK_STATUSES) {
            DesktopFileLogger.warn("download ignored because another task is active itemId=${item.id}")
            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        errorMessage = "当前已有下载任务在执行，请等待完成后再发起新的下载",
                    ),
                )
            }
            return
        }

        stopPolling()
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    download = DesktopDownloadUiState(
                        selectedTitle = item.title,
                        isStarting = true,
                    ),
                    message = null,
                )
            }

            runCatching { apiClient.startDownload(_uiState.value.serverConfig, item.id) }
                .onSuccess { task ->
                    DesktopFileLogger.info(
                        "download task created taskId=${task.taskId} musicId=${item.id} title=${item.title}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            download = DesktopDownloadUiState(
                                currentTask = task,
                                selectedTitle = item.title,
                                isStarting = false,
                                isPolling = task.status in ACTIVE_TASK_STATUSES,
                                errorMessage = null,
                            ),
                            message = "已创建下载任务：${item.title}",
                        )
                    }

                    if (task.status in ACTIVE_TASK_STATUSES) {
                        beginPolling(task.taskId)
                    } else {
                        refreshHealth()
                    }
                }
                .onFailure { error ->
                    logFailure(
                        "create download task failed",
                        error,
                        "musicId=${item.id} title=${item.title} baseUrl=${_uiState.value.serverConfig.baseUrl}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            download = DesktopDownloadUiState(
                                selectedTitle = item.title,
                                isStarting = false,
                                errorMessage = error.message ?: "创建下载任务失败",
                            ),
                        )
                    }
                }
        }
    }

    fun refreshLatestTask() {
        scope.launch {
            runCatching { apiClient.getTasks(_uiState.value.serverConfig) }
                .onSuccess { tasks ->
                    val taskToDisplay = tasks.firstOrNull { it.status in ACTIVE_TASK_STATUSES } ?: tasks.firstOrNull()
                    val previousTaskId = _uiState.value.download.currentTask?.taskId
                    val selectedTitle = if (taskToDisplay?.taskId == previousTaskId) {
                        _uiState.value.download.selectedTitle
                    } else {
                        null
                    }

                    _uiState.update { state ->
                        state.copy(
                            download = DesktopDownloadUiState(
                                currentTask = taskToDisplay,
                                selectedTitle = selectedTitle,
                                isStarting = false,
                                isPolling = taskToDisplay?.status in ACTIVE_TASK_STATUSES,
                                errorMessage = taskToDisplay?.errorMessage,
                            ),
                        )
                    }

                    if (taskToDisplay?.status in ACTIVE_TASK_STATUSES) {
                        beginPolling(taskToDisplay!!.taskId)
                    } else {
                        stopPolling()
                    }
                }
                .onFailure { error ->
                    logFailure("refresh latest task failed", error, "baseUrl=${_uiState.value.serverConfig.baseUrl}")
                    stopPolling()
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                isStarting = false,
                                isPolling = false,
                                errorMessage = error.message ?: "读取任务失败",
                            ),
                        )
                    }
                }
        }
    }

    fun refreshOperations(showLoading: Boolean = true) {
        scope.launch {
            if (showLoading) {
                _uiState.update { state ->
                    state.copy(
                        ops = state.ops.copy(
                            isLoading = true,
                            errorMessage = null,
                        ),
                    )
                }
            }

            val proxyResult = runCatching { apiClient.getCurrentProxy(_uiState.value.serverConfig) }
            val logsResult = runCatching { apiClient.getLogs(_uiState.value.serverConfig, DEFAULT_LOG_LINES) }

            _uiState.update { state ->
                val proxy = proxyResult.getOrNull() ?: state.ops.proxy
                val logs = logsResult.getOrNull() ?: state.ops.logs
                val errorMessage = proxyResult.exceptionOrNull()?.message
                    ?: logsResult.exceptionOrNull()?.message

                state.copy(
                    ops = state.ops.copy(
                        isLoading = false,
                        proxy = proxy,
                        logs = logs,
                        errorMessage = errorMessage,
                    ),
                    health = state.health.copy(
                        health = state.health.health?.copy(proxy = proxy ?: state.health.health.proxy),
                    ),
                )
            }

            proxyResult.exceptionOrNull()?.let { error ->
                logFailure("load current proxy failed", error, "baseUrl=${_uiState.value.serverConfig.baseUrl}")
            }
            logsResult.exceptionOrNull()?.let { error ->
                logFailure("load backend logs failed", error, "baseUrl=${_uiState.value.serverConfig.baseUrl}")
            }
        }
    }

    fun selectProxy(name: String) {
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    ops = state.ops.copy(
                        isLoading = true,
                        errorMessage = null,
                    ),
                    message = null,
                )
            }

            runCatching { apiClient.selectProxy(_uiState.value.serverConfig, name) }
                .onSuccess { proxy ->
                    _uiState.update { state ->
                        state.copy(
                            ops = state.ops.copy(
                                isLoading = false,
                                proxy = proxy,
                                errorMessage = null,
                            ),
                            health = state.health.copy(
                                health = state.health.health?.copy(proxy = proxy),
                            ),
                            message = "节点已切换到：${proxy.name.orEmpty()}",
                        )
                    }
                }
                .onFailure { error ->
                    logFailure("select proxy failed", error, "target=$name baseUrl=${_uiState.value.serverConfig.baseUrl}")
                    _uiState.update { state ->
                        state.copy(
                            ops = state.ops.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "切换节点失败",
                            ),
                        )
                    }
                }
        }
    }

    private fun beginPolling(taskId: String) {
        if (pollingTaskId == taskId && pollJob?.isActive == true) {
            return
        }

        stopPolling()
        pollingTaskId = taskId
        pollJob = scope.launch {
            while (isActive) {
                val taskResult = runCatching { apiClient.getTask(_uiState.value.serverConfig, taskId) }
                if (taskResult.isFailure) {
                    val error = taskResult.exceptionOrNull()
                    error?.let {
                        logFailure("poll task failed", it, "taskId=$taskId baseUrl=${_uiState.value.serverConfig.baseUrl}")
                    }
                    stopPolling()
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                isStarting = false,
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
                            isStarting = false,
                            isPolling = task.status in ACTIVE_TASK_STATUSES,
                            errorMessage = task.errorMessage,
                        ),
                        message = if (task.status == "finished") {
                            "下载任务已完成：${task.filename ?: task.musicId}"
                        } else {
                            state.message
                        },
                    )
                }

                if (task.status !in ACTIVE_TASK_STATUSES) {
                    DesktopFileLogger.info(
                        "download task finished taskId=${task.taskId} status=${task.status} stage=${task.stage} file=${task.filename.orEmpty()}",
                    )
                    stopPolling()
                    refreshHealth()
                    break
                }

                delay(TASK_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pollingTaskId = null
    }

    fun close() {
        DesktopFileLogger.info("DesktopAppState closing")
        stopPolling()
        scope.cancel()
    }

    private fun logFailure(action: String, error: Throwable, context: String? = null) {
        val message = buildString {
            append(action)
            context?.takeIf { it.isNotBlank() }?.let {
                append(" | ")
                append(it)
            }
        }
        DesktopFileLogger.error(message, error)
    }

    private companion object {
        val ACTIVE_TASK_STATUSES = setOf("queued", "running")
        const val TASK_POLL_INTERVAL_MS = 2000L
        const val DEFAULT_LOG_LINES = 60
        const val UPDATE_PLATFORM_DESKTOP = "desktop"
    }

    private fun buildUpdateCheckMessage(
        currentVersionName: String,
        currentVersionCode: Long,
        updateInfo: AppUpdateInfo,
    ): String {
        val remoteVersionCode = updateInfo.versionCode
        return when {
            remoteVersionCode != null && remoteVersionCode > currentVersionCode -> {
                "发现新版本：${updateInfo.versionName ?: updateInfo.fileName} (${updateInfo.versionCode})"
            }
            updateInfo.versionName != null && updateInfo.versionName != currentVersionName -> {
                "发现可下载安装包：${updateInfo.versionName}"
            }
            else -> {
                "已获取安装包：${updateInfo.fileName}"
            }
        }
    }
}

private fun DesktopSearchUiState.rebuildVisibleResults(
    results: List<SearchItem> = this.results,
    sortMode: SearchSortMode = this.sortMode,
    filterMode: SearchFilterMode = this.filterMode,
    preferredSelectedResultId: String? = this.selectedResultId,
): DesktopSearchUiState {
    val visibleResults = results
        .asSequence()
        .filter { filterMode.matches(it) }
        .let { items ->
            when (sortMode) {
                SearchSortMode.DEFAULT -> items.toList()
                SearchSortMode.TITLE_ASC -> items.sortedBy { it.title.lowercase() }.toList()
                SearchSortMode.DURATION_ASC -> items.sortedWith(compareBy<SearchItem> { it.duration ?: Double.MAX_VALUE }.thenBy { it.title.lowercase() }).toList()
                SearchSortMode.DURATION_DESC -> items.sortedWith(compareByDescending<SearchItem> { it.duration ?: -1.0 }.thenBy { it.title.lowercase() }).toList()
            }
        }
    val selectedResultId = preferredSelectedResultId?.takeIf { selectedId ->
        visibleResults.any { it.id == selectedId }
    } ?: visibleResults.firstOrNull()?.id

    return copy(
        results = results,
        visibleResults = visibleResults,
        sortMode = sortMode,
        filterMode = filterMode,
        selectedResultId = selectedResultId,
    )
}

private fun SearchFilterMode.matches(item: SearchItem): Boolean {
    val duration = item.duration ?: return this == SearchFilterMode.ALL
    return when (this) {
        SearchFilterMode.ALL -> true
        SearchFilterMode.UNDER_4_MIN -> duration < 240.0
        SearchFilterMode.BETWEEN_4_AND_10_MIN -> duration in 240.0..600.0
        SearchFilterMode.OVER_10_MIN -> duration > 600.0
    }
}
