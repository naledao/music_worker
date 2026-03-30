package com.openclaw.musicworker.desktop

import com.openclaw.musicworker.shared.api.AppUpdateInfo
import com.openclaw.musicworker.shared.api.ChartItem
import com.openclaw.musicworker.shared.api.ChartSourceInfo
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.HealthPayload
import com.openclaw.musicworker.shared.api.ProxyInfo
import com.openclaw.musicworker.shared.api.SearchItem
import com.openclaw.musicworker.shared.config.ApiServerConfig
import java.nio.file.Path
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
import kotlin.math.roundToLong

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

private const val DEFAULT_CHART_SOURCE = "apple_music"
private const val DEFAULT_CHART_TYPE = "songs"
private const val DEFAULT_CHART_PERIOD = "daily"
private const val DEFAULT_CHART_REGION = "us"
private const val DEFAULT_CHART_LIMIT = 50

private fun approximateDurationMs(durationSec: Double?): Long? {
    return durationSec
        ?.takeIf { it > 0 }
        ?.times(1000.0)
        ?.roundToLong()
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

data class DesktopChartsUiState(
    val availableSources: List<ChartSourceInfo> = emptyList(),
    val selectedSource: String = DEFAULT_CHART_SOURCE,
    val selectedType: String = DEFAULT_CHART_TYPE,
    val selectedPeriod: String = DEFAULT_CHART_PERIOD,
    val selectedRegion: String = DEFAULT_CHART_REGION,
    val title: String = "",
    val updatedAt: String? = null,
    val fromCache: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val items: List<ChartItem> = emptyList(),
    val errorMessage: String? = null,
)

data class DesktopDownloadUiState(
    val currentTask: DownloadTask? = null,
    val selectedTitle: String? = null,
    val isStarting: Boolean = false,
    val isPolling: Boolean = false,
    val isExporting: Boolean = false,
    val exportDownloadedBytes: Long = 0L,
    val exportTotalBytes: Long? = null,
    val exportedTaskId: String? = null,
    val exportingTaskId: String? = null,
    val savedFilePath: String? = null,
    val exportMessage: String? = null,
    val exportErrorMessage: String? = null,
    val errorMessage: String? = null,
)

data class DesktopPlaybackUiState(
    val currentMusicId: String? = null,
    val currentTitle: String? = null,
    val currentChannel: String? = null,
    val currentDurationSec: Double? = null,
    val currentPositionMs: Long = 0L,
    val playbackDurationMs: Long? = null,
    val supportsSeeking: Boolean = false,
    val currentCoverUrl: String? = null,
    val currentTask: DownloadTask? = null,
    val playbackUrl: String? = null,
    val source: String? = null,
    val isPreparing: Boolean = false,
    val isBuffering: Boolean = false,
    val playRequestToken: Long = 0L,
    val isPlaying: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class DesktopOpsUiState(
    val isLoading: Boolean = false,
    val proxy: ProxyInfo? = null,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val pendingProxyName: String? = null,
    val proxyPasswordInput: String = "",
    val proxyPasswordError: String? = null,
)

data class DesktopUpdateUiState(
    val currentVersionName: String = "0.2.2",
    val currentVersionCode: Long = 13L,
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
    val downloadDirectoryPath: String = DesktopPaths.defaultDownloadsDir().toAbsolutePath().normalize().toString(),
    val hasCustomDownloadDirectory: Boolean = false,
    val health: DesktopHealthUiState = DesktopHealthUiState(),
    val search: DesktopSearchUiState = DesktopSearchUiState(),
    val charts: DesktopChartsUiState = DesktopChartsUiState(),
    val download: DesktopDownloadUiState = DesktopDownloadUiState(),
    val playback: DesktopPlaybackUiState = DesktopPlaybackUiState(),
    val ops: DesktopOpsUiState = DesktopOpsUiState(),
    val update: DesktopUpdateUiState = DesktopUpdateUiState(),
    val message: String? = null,
)

private data class DesktopChartSelection(
    val sourceId: String,
    val type: String,
    val period: String,
    val regionId: String,
)

class DesktopAppState(
    private val settingsStore: DesktopSettingsStore = DesktopSettingsStore(),
    private val apiClient: DesktopMusicApiClient = DesktopMusicApiClient(),
    private val updateManager: DesktopUpdateManager = DesktopUpdateManager(),
    private val taskFileExporter: DesktopTaskFileExporter = DesktopTaskFileExporter(),
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        DesktopFileLogger.error("unhandled coroutine exception in DesktopAppState", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)
    private var pollJob: Job? = null
    private var playbackJob: Job? = null
    private var pollingTaskId: String? = null
    private var playbackRequestToken = 0L
    private val initialCustomDownloadDirectoryPath = settingsStore.currentDownloadDirectoryPath()

    private val _uiState = MutableStateFlow(
        DesktopUiState(
            serverConfig = settingsStore.load(),
            downloadDirectoryPath = resolveDownloadDirectoryPath(initialCustomDownloadDirectoryPath),
            hasCustomDownloadDirectory = !initialCustomDownloadDirectoryPath.isNullOrBlank(),
            update = DesktopUpdateUiState(
                currentVersionName = updateManager.currentVersionName(),
                currentVersionCode = updateManager.currentVersionCode(),
            ),
        ),
    )
    val uiState: StateFlow<DesktopUiState> = _uiState.asStateFlow()

    fun initialize() {
        DesktopFileLogger.info(
            "DesktopAppState initialized baseUrl=${_uiState.value.serverConfig.baseUrl} downloadDir=${_uiState.value.downloadDirectoryPath}",
        )
        refreshHealth()
        refreshOperations()
        refreshLatestTask()
        refreshCharts()
    }

    fun switchPage(page: DesktopPage) {
        _uiState.update { state ->
            state.copy(currentPage = page)
        }
        if (page == DesktopPage.CHARTS) {
            val chartsState = _uiState.value.charts
            if (!chartsState.isLoading && !chartsState.isRefreshing && chartsState.items.isEmpty()) {
                refreshCharts()
            }
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

    fun preparePlayback(item: SearchItem) {
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    currentMusicId = item.id,
                    currentTitle = item.title,
                    currentChannel = item.channel,
                    currentDurationSec = item.duration,
                    currentPositionMs = 0L,
                    playbackDurationMs = approximateDurationMs(item.duration),
                    currentCoverUrl = item.cover,
                    currentTask = null,
                    playbackUrl = null,
                    source = "server",
                    isPreparing = true,
                    isBuffering = false,
                    playRequestToken = 0L,
                    isPlaying = false,
                    message = "正在通知服务端准备音频…",
                    errorMessage = null,
                ),
            )
        }
    }

    fun playInApp(item: SearchItem) {
        playbackJob?.cancel()
        scope.launch {
            preparePlayback(item)

            runCatching { apiClient.startDownload(_uiState.value.serverConfig, item.id) }
                .onSuccess { task ->
                    updatePlaybackTask(item, task)
                    when (task.status) {
                        "finished" -> {
                            markPlaybackReady(item, task)
                            refreshHealth()
                        }
                        "failed" -> {
                            _uiState.update { state ->
                                state.copy(
                                    playback = state.playback.copy(
                                        currentMusicId = item.id,
                                        currentTitle = item.title,
                                        currentChannel = item.channel,
                                        currentDurationSec = item.duration,
                                        currentPositionMs = 0L,
                                        playbackDurationMs = approximateDurationMs(item.duration),
                                        currentCoverUrl = item.cover,
                                        currentTask = task,
                                        playbackUrl = null,
                                        isPreparing = false,
                                        isBuffering = false,
                                        playRequestToken = 0L,
                                        isPlaying = false,
                                        message = null,
                                        errorMessage = task.errorMessage ?: "准备播放失败",
                                    ),
                                )
                            }
                            refreshHealth()
                        }
                        else -> beginPlaybackPolling(item, task.taskId)
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            playback = state.playback.copy(
                                currentMusicId = item.id,
                                currentTitle = item.title,
                                currentChannel = item.channel,
                                currentDurationSec = item.duration,
                                currentPositionMs = 0L,
                                playbackDurationMs = approximateDurationMs(item.duration),
                                currentCoverUrl = item.cover,
                                currentTask = null,
                                playbackUrl = null,
                                isPreparing = false,
                                isBuffering = false,
                                playRequestToken = 0L,
                                isPlaying = false,
                                message = null,
                                errorMessage = error.message ?: "准备播放失败",
                            ),
                        )
                    }
                }
        }
    }

    fun updatePlaybackTask(item: SearchItem, task: DownloadTask) {
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    currentMusicId = item.id,
                    currentTitle = item.title,
                    currentChannel = item.channel,
                    currentDurationSec = item.duration,
                    currentPositionMs = 0L,
                    playbackDurationMs = approximateDurationMs(item.duration),
                    currentCoverUrl = item.cover,
                    currentTask = task,
                    playbackUrl = null,
                    source = "server",
                    isPreparing = task.status != "finished",
                    isBuffering = false,
                    playRequestToken = 0L,
                    isPlaying = false,
                    message = if (task.status == "finished") "服务端音频已准备完成" else "服务端正在准备音频…",
                    errorMessage = null,
                ),
            )
        }
    }

    fun markPlaybackReady(item: SearchItem, task: DownloadTask) {
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    currentMusicId = item.id,
                    currentTitle = item.title,
                    currentChannel = item.channel,
                    currentDurationSec = item.duration,
                    currentPositionMs = 0L,
                    playbackDurationMs = approximateDurationMs(item.duration),
                    currentCoverUrl = item.cover,
                    currentTask = task,
                    playbackUrl = apiClient.taskFileUrl(state.serverConfig, task.taskId),
                    source = "server",
                    isPreparing = false,
                    isBuffering = false,
                    playRequestToken = nextPlaybackRequestToken(),
                    isPlaying = false,
                    message = "服务端音频已准备完成，等待桌面端开始播放",
                    errorMessage = null,
                ),
            )
        }
    }

    fun onPlaybackStateChanged(requestToken: Long, isPlaying: Boolean) {
        updatePlaybackIfTokenMatches(requestToken) { playback ->
            playback.copy(
                isPlaying = isPlaying,
                errorMessage = if (isPlaying) null else playback.errorMessage,
                message = when {
                    playback.playbackUrl.isNullOrBlank() -> playback.message
                    playback.isBuffering -> "正在缓冲音频…"
                    isPlaying -> "正在桌面端播放"
                    else -> "已暂停，可直接继续播放"
                },
            )
        }
    }

    fun onPlaybackBufferingChanged(requestToken: Long, isBuffering: Boolean) {
        updatePlaybackIfTokenMatches(requestToken) { playback ->
            playback.copy(
                isBuffering = isBuffering,
                errorMessage = if (isBuffering) null else playback.errorMessage,
                message = when {
                    playback.playbackUrl.isNullOrBlank() -> playback.message
                    isBuffering -> "正在缓冲音频…"
                    playback.isPlaying -> "正在桌面端播放"
                    else -> playback.message
                },
            )
        }
    }

    fun onPlaybackProgressChanged(requestToken: Long, positionMs: Long, durationMs: Long?) {
        updatePlaybackIfTokenMatches(requestToken) { playback ->
            val resolvedDurationMs = durationMs
                ?.takeIf { it > 0L }
                ?: playback.playbackDurationMs
                ?: approximateDurationMs(playback.currentDurationSec)
            val safePositionMs = if (resolvedDurationMs != null) {
                positionMs.coerceIn(0L, resolvedDurationMs)
            } else {
                positionMs.coerceAtLeast(0L)
            }
            playback.copy(
                currentPositionMs = safePositionMs,
                playbackDurationMs = resolvedDurationMs,
                currentDurationSec = resolvedDurationMs?.let { it / 1000.0 } ?: playback.currentDurationSec,
                errorMessage = null,
            )
        }
    }

    fun onPlaybackCompleted(requestToken: Long) {
        updatePlaybackIfTokenMatches(requestToken) { playback ->
            playback.copy(
                currentPositionMs = playback.playbackDurationMs ?: playback.currentPositionMs,
                isBuffering = false,
                isPlaying = false,
                errorMessage = null,
                message = "播放结束，可重新播放",
            )
        }
    }

    fun onPlaybackError(requestToken: Long, message: String) {
        updatePlaybackIfTokenMatches(requestToken) { playback ->
            playback.copy(
                isPreparing = false,
                isBuffering = false,
                isPlaying = false,
                errorMessage = message,
            )
        }
    }

    fun clearPlayback() {
        _uiState.update { state ->
            state.copy(playback = DesktopPlaybackUiState())
        }
    }

    fun refreshCharts(forceRefresh: Boolean = false) {
        scope.launch {
            val currentCharts = _uiState.value.charts
            val hasLoadedContent = currentCharts.items.isNotEmpty() || currentCharts.title.isNotBlank()

            _uiState.update { state ->
                state.copy(
                    charts = state.charts.copy(
                        isLoading = !hasLoadedContent,
                        isRefreshing = hasLoadedContent,
                        errorMessage = null,
                    ),
                )
            }

            val availableSources = if (currentCharts.availableSources.isNotEmpty()) {
                currentCharts.availableSources
            } else {
                runCatching { apiClient.getChartSources(_uiState.value.serverConfig) }
                    .getOrElse { error ->
                        logFailure("load chart sources failed", error, "baseUrl=${_uiState.value.serverConfig.baseUrl}")
                        _uiState.update { state ->
                            state.copy(
                                charts = state.charts.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    errorMessage = error.message ?: "加载榜单来源失败",
                                ),
                            )
                        }
                        return@launch
                    }
            }

            val selection = resolveChartSelection(
                currentState = _uiState.value.charts,
                availableSources = availableSources,
            )

            _uiState.update { state ->
                state.copy(
                    charts = state.charts.copy(
                        availableSources = availableSources,
                        selectedSource = selection.sourceId,
                        selectedType = selection.type,
                        selectedPeriod = selection.period,
                        selectedRegion = selection.regionId,
                    ),
                )
            }

            runCatching {
                apiClient.getCharts(
                    config = _uiState.value.serverConfig,
                    source = selection.sourceId,
                    type = selection.type,
                    period = selection.period,
                    region = selection.regionId,
                    limit = DEFAULT_CHART_LIMIT,
                    forceRefresh = forceRefresh,
                )
            }
                .onSuccess { payload ->
                    DesktopFileLogger.info(
                        "desktop charts loaded source=${payload.source} region=${payload.region} count=${payload.items.size} fromCache=${payload.fromCache}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            charts = state.charts.copy(
                                availableSources = availableSources,
                                selectedSource = payload.source.ifBlank { selection.sourceId },
                                selectedType = payload.type.ifBlank { selection.type },
                                selectedPeriod = payload.period.ifBlank { selection.period },
                                selectedRegion = payload.region.ifBlank { selection.regionId },
                                title = payload.title,
                                updatedAt = payload.updatedAt,
                                fromCache = payload.fromCache,
                                isLoading = false,
                                isRefreshing = false,
                                items = payload.items,
                                errorMessage = null,
                            ),
                            message = "排行榜已更新：${payload.title}",
                        )
                    }
                }
                .onFailure { error ->
                    logFailure(
                        "load charts failed",
                        error,
                        "region=${selection.regionId} baseUrl=${_uiState.value.serverConfig.baseUrl}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            charts = state.charts.copy(
                                availableSources = availableSources,
                                selectedSource = selection.sourceId,
                                selectedType = selection.type,
                                selectedPeriod = selection.period,
                                selectedRegion = selection.regionId,
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = error.message ?: "加载排行榜失败",
                            ),
                        )
                    }
                }
        }
    }

    fun selectChartRegion(regionId: String) {
        val currentCharts = _uiState.value.charts
        val sourceInfo = currentCharts.availableSources.firstOrNull { it.id == currentCharts.selectedSource }
        val targetRegion = sourceInfo
            ?.regions
            ?.firstOrNull { it.id == regionId }
            ?.id
            ?: return

        _uiState.update { state ->
            state.copy(
                charts = state.charts.copy(
                    selectedRegion = targetRegion,
                    errorMessage = null,
                ),
            )
        }
        refreshCharts()
    }

    fun searchChartItem(item: ChartItem) {
        val keyword = item.searchKeyword.ifBlank {
            listOf(item.title, item.artist)
                .joinToString(" ")
                .trim()
        }
        if (keyword.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    charts = state.charts.copy(
                        errorMessage = "榜单项缺少可搜索关键词",
                    ),
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(currentPage = DesktopPage.SEARCH)
        }
        runSearch(keyword)
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
                charts = DesktopChartsUiState(),
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
        refreshCharts()
    }

    fun setDownloadDirectory(path: String) {
        val normalizedPath = resolveDownloadDirectoryPath(path)
        settingsStore.saveDownloadDirectory(normalizedPath)
        _uiState.update { state ->
            state.copy(
                downloadDirectoryPath = normalizedPath,
                hasCustomDownloadDirectory = true,
                message = "已设置 Windows 保存目录：$normalizedPath",
            )
        }
        maybeExportFinishedTask(_uiState.value.download.currentTask)
    }

    fun resetDownloadDirectory() {
        settingsStore.clearDownloadDirectory()
        val defaultPath = resolveDownloadDirectoryPath(null)
        _uiState.update { state ->
            state.copy(
                downloadDirectoryPath = defaultPath,
                hasCustomDownloadDirectory = false,
                message = "已恢复默认 Windows 保存目录：$defaultPath",
            )
        }
        maybeExportFinishedTask(_uiState.value.download.currentTask)
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

        runSearch(keyword)
    }

    private fun runSearch(keyword: String) {
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    search = state.search.copy(
                        input = keyword,
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
        if (currentDownload.isStarting || currentDownload.isExporting || currentDownload.currentTask?.status in ACTIVE_TASK_STATUSES) {
            DesktopFileLogger.warn("download ignored because another task is active itemId=${item.id}")
            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        errorMessage = if (state.download.isExporting) {
                            "当前正在保存上一首到 Windows 目录，请等待完成后再发起新的下载"
                        } else {
                            "当前已有下载任务在执行，请等待完成后再发起新的下载"
                        },
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
                            search = if (task.status == "finished" && !task.filePath.isNullOrBlank()) {
                                state.search.markItemDownloaded(
                                    musicId = item.id,
                                    filePath = task.filePath,
                                    fileSize = task.fileSize,
                                    downloadedAt = task.updatedAt,
                                )
                            } else {
                                state.search
                            },
                            download = DesktopDownloadUiState(
                                currentTask = task,
                                selectedTitle = item.title,
                                isStarting = false,
                                isPolling = task.status in ACTIVE_TASK_STATUSES,
                                errorMessage = null,
                            ),
                            message = if (task.status == "finished" && item.downloaded) {
                                "歌曲已存在，跳过重新下载：${item.title}"
                            } else {
                                "已创建下载任务：${item.title}"
                            },
                        )
                    }

                    if (task.status in ACTIVE_TASK_STATUSES) {
                        beginPolling(task.taskId)
                    } else {
                        maybeExportFinishedTask(task)
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
                    val currentDownload = _uiState.value.download
                    val previousTaskId = currentDownload.currentTask?.taskId
                    val selectedTitle = if (taskToDisplay?.taskId == previousTaskId) {
                        currentDownload.selectedTitle
                    } else {
                        null
                    }
                    val nextDownload = if (taskToDisplay?.taskId == previousTaskId) {
                        currentDownload.copy(
                            currentTask = taskToDisplay,
                            selectedTitle = selectedTitle,
                            isStarting = false,
                            isPolling = taskToDisplay?.status in ACTIVE_TASK_STATUSES,
                            errorMessage = taskToDisplay?.errorMessage,
                        )
                    } else {
                        DesktopDownloadUiState(
                            currentTask = taskToDisplay,
                            selectedTitle = selectedTitle,
                            isStarting = false,
                            isPolling = taskToDisplay?.status in ACTIVE_TASK_STATUSES,
                            errorMessage = taskToDisplay?.errorMessage,
                        )
                    }

                    _uiState.update { state ->
                        state.copy(
                            download = nextDownload,
                            playback = if (state.playback.currentTask?.taskId == taskToDisplay?.taskId && taskToDisplay != null) {
                                state.playback.copy(currentTask = taskToDisplay)
                            } else {
                                state.playback
                            },
                        )
                    }

                    if (taskToDisplay?.status in ACTIVE_TASK_STATUSES) {
                        beginPolling(taskToDisplay!!.taskId)
                    } else {
                        stopPolling()
                    }
                    maybeExportFinishedTask(taskToDisplay)
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

    fun requestProxySelection(name: String) {
        val target = name.trim()
        if (target.isBlank()) {
            return
        }

        _uiState.update { state ->
            state.copy(
                ops = state.ops.copy(
                    pendingProxyName = target,
                    proxyPasswordInput = "",
                    proxyPasswordError = null,
                    errorMessage = null,
                ),
                message = null,
            )
        }
    }

    fun dismissProxySelection() {
        _uiState.update { state ->
            state.copy(
                ops = state.ops.copy(
                    pendingProxyName = null,
                    proxyPasswordInput = "",
                    proxyPasswordError = null,
                ),
            )
        }
    }

    fun updateProxyPasswordInput(value: String) {
        _uiState.update { state ->
            state.copy(
                ops = state.ops.copy(
                    proxyPasswordInput = value,
                    proxyPasswordError = null,
                ),
            )
        }
    }

    fun confirmProxySelection() {
        val target = _uiState.value.ops.pendingProxyName?.trim().orEmpty()
        val password = _uiState.value.ops.proxyPasswordInput

        if (target.isBlank()) {
            return
        }
        if (password.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    ops = state.ops.copy(
                        proxyPasswordError = "请输入节点切换密码",
                    ),
                )
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    ops = state.ops.copy(
                        isLoading = true,
                        errorMessage = null,
                        proxyPasswordError = null,
                    ),
                    message = null,
                )
            }

            runCatching { apiClient.selectProxy(_uiState.value.serverConfig, target, password) }
                .onSuccess { proxy ->
                    _uiState.update { state ->
                        state.copy(
                            ops = state.ops.copy(
                                isLoading = false,
                                proxy = proxy,
                                errorMessage = null,
                                pendingProxyName = null,
                                proxyPasswordInput = "",
                                proxyPasswordError = null,
                            ),
                            health = state.health.copy(
                                health = state.health.health?.copy(proxy = proxy),
                            ),
                            message = "节点已切换到：${proxy.name.orEmpty()}",
                        )
                    }
                }
                .onFailure { error ->
                    val errorMessage = error.message ?: "切换节点失败"
                    logFailure("select proxy failed", error, "target=$target baseUrl=${_uiState.value.serverConfig.baseUrl}")
                    _uiState.update { state ->
                        state.copy(
                            ops = state.ops.copy(
                                isLoading = false,
                                errorMessage = errorMessage,
                                proxyPasswordError = errorMessage,
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
                    val sameTask = state.download.currentTask?.taskId == task.taskId
                    state.copy(
                        search = if (task.status == "finished" && !task.filePath.isNullOrBlank()) {
                            state.search.markItemDownloaded(
                                musicId = task.musicId,
                                filePath = task.filePath,
                                fileSize = task.fileSize,
                                downloadedAt = task.updatedAt,
                            )
                        } else {
                            state.search
                        },
                        download = if (sameTask) {
                            state.download.copy(
                                currentTask = task,
                                isStarting = false,
                                isPolling = task.status in ACTIVE_TASK_STATUSES,
                                errorMessage = task.errorMessage,
                            )
                        } else {
                            DesktopDownloadUiState(
                                currentTask = task,
                                isStarting = false,
                                isPolling = task.status in ACTIVE_TASK_STATUSES,
                                errorMessage = task.errorMessage,
                            )
                        },
                        playback = if (state.playback.currentTask?.taskId == task.taskId) {
                            state.playback.copy(
                                currentTask = task,
                                isPreparing = state.playback.isPreparing && task.status in ACTIVE_TASK_STATUSES,
                            )
                        } else {
                            state.playback
                        },
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
                    maybeExportFinishedTask(task)
                    stopPolling()
                    refreshHealth()
                    break
                }

                delay(TASK_POLL_INTERVAL_MS)
            }
        }
    }

    private fun beginPlaybackPolling(item: SearchItem, taskId: String) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                val taskResult = runCatching { apiClient.getTask(_uiState.value.serverConfig, taskId) }
                if (taskResult.isFailure) {
                    val error = taskResult.exceptionOrNull()
                    _uiState.update { state ->
                        state.copy(
                            playback = state.playback.copy(
                                isPreparing = false,
                                isBuffering = false,
                                isPlaying = false,
                                errorMessage = error?.message ?: "轮询播放任务失败",
                            ),
                        )
                    }
                    break
                }

                val task = taskResult.getOrThrow()
                _uiState.update { state ->
                    state.copy(
                        playback = state.playback.copy(
                            currentMusicId = item.id,
                            currentTitle = item.title,
                            currentChannel = item.channel,
                            currentDurationSec = item.duration,
                            currentCoverUrl = item.cover,
                            currentTask = task,
                            isPreparing = task.status in ACTIVE_TASK_STATUSES,
                            message = if (task.status == "finished") "服务端音频已准备完成" else "服务端正在准备音频…",
                            errorMessage = task.errorMessage,
                        ),
                    )
                }

                if (task.status == "finished") {
                    markPlaybackReady(item, task)
                    refreshHealth()
                    break
                }

                if (task.status == "failed") {
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

    private fun maybeExportFinishedTask(task: DownloadTask?) {
        if (task == null || task.status != "finished" || task.filePath.isNullOrBlank()) {
            return
        }

        val downloadState = _uiState.value.download
        if (downloadState.exportedTaskId == task.taskId || downloadState.exportingTaskId == task.taskId) {
            return
        }

        scope.launch {
            exportFinishedTask(task)
        }
    }

    private suspend fun exportFinishedTask(task: DownloadTask) {
        val downloadDirectoryPath = _uiState.value.downloadDirectoryPath
        _uiState.update { state ->
            state.copy(
                download = state.download.copy(
                    isExporting = true,
                    exportDownloadedBytes = 0L,
                    exportTotalBytes = task.fileSize,
                    exportingTaskId = task.taskId,
                    savedFilePath = if (state.download.exportedTaskId == task.taskId) state.download.savedFilePath else null,
                    exportMessage = "正在保存到 Windows 目录…",
                    exportErrorMessage = null,
                    errorMessage = task.errorMessage,
                ),
            )
        }

        runCatching {
            taskFileExporter.exportTaskFile(
                apiClient = apiClient,
                config = _uiState.value.serverConfig,
                task = task,
                downloadDirectoryPath = downloadDirectoryPath,
            ) { downloadedBytes, totalBytes ->
                _uiState.update { state ->
                    if (state.download.exportingTaskId != task.taskId) {
                        state
                    } else {
                        state.copy(
                            download = state.download.copy(
                                exportDownloadedBytes = downloadedBytes,
                                exportTotalBytes = totalBytes ?: task.fileSize,
                            ),
                        )
                    }
                }
            }
        }
            .onSuccess { exportedFile ->
                DesktopFileLogger.info(
                    "desktop task exported taskId=${task.taskId} path=${exportedFile.filePath}",
                )
                _uiState.update { state ->
                    state.copy(
                        download = state.download.copy(
                            isExporting = false,
                            exportDownloadedBytes = state.download.exportTotalBytes ?: state.download.exportDownloadedBytes,
                            exportTotalBytes = state.download.exportTotalBytes ?: task.fileSize,
                            exportedTaskId = task.taskId,
                            exportingTaskId = null,
                            savedFilePath = exportedFile.filePath,
                            exportMessage = "已保存到 Windows：${exportedFile.fileName}",
                            exportErrorMessage = null,
                            errorMessage = task.errorMessage,
                        ),
                        message = "已保存到 Windows：${exportedFile.fileName}",
                    )
                }
            }
            .onFailure { error ->
                logFailure(
                    "export desktop task file failed",
                    error,
                    "taskId=${task.taskId} dir=$downloadDirectoryPath",
                )
                _uiState.update { state ->
                    state.copy(
                        download = state.download.copy(
                            isExporting = false,
                            exportingTaskId = null,
                            exportMessage = null,
                            exportErrorMessage = error.message ?: "保存到 Windows 目录失败",
                            errorMessage = task.errorMessage,
                        ),
                    )
                }
            }
    }

    fun close() {
        DesktopFileLogger.info("DesktopAppState closing")
        stopPolling()
        playbackJob?.cancel()
        scope.cancel()
    }

    private fun nextPlaybackRequestToken(): Long {
        playbackRequestToken += 1L
        return playbackRequestToken
    }

    private fun updatePlaybackIfTokenMatches(
        requestToken: Long,
        transform: (DesktopPlaybackUiState) -> DesktopPlaybackUiState,
    ) {
        if (requestToken <= 0L) {
            return
        }
        _uiState.update { state ->
            if (state.playback.playRequestToken != requestToken) {
                state
            } else {
                state.copy(playback = transform(state.playback))
            }
        }
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

    private fun resolveChartSelection(
        currentState: DesktopChartsUiState,
        availableSources: List<ChartSourceInfo>,
    ): DesktopChartSelection {
        if (availableSources.isEmpty()) {
            return DesktopChartSelection(
                sourceId = currentState.selectedSource.ifBlank { DEFAULT_CHART_SOURCE },
                type = currentState.selectedType.ifBlank { DEFAULT_CHART_TYPE },
                period = currentState.selectedPeriod.ifBlank { DEFAULT_CHART_PERIOD },
                regionId = currentState.selectedRegion.ifBlank { DEFAULT_CHART_REGION },
            )
        }

        val sourceInfo = availableSources.firstOrNull { it.id == currentState.selectedSource } ?: availableSources.first()
        return DesktopChartSelection(
            sourceId = sourceInfo.id,
            type = sourceInfo.types.firstOrNull { it == currentState.selectedType }
                ?: sourceInfo.types.firstOrNull()
                ?: currentState.selectedType.ifBlank { DEFAULT_CHART_TYPE },
            period = sourceInfo.periods.firstOrNull { it == currentState.selectedPeriod }
                ?: sourceInfo.periods.firstOrNull()
                ?: currentState.selectedPeriod.ifBlank { DEFAULT_CHART_PERIOD },
            regionId = sourceInfo.regions.firstOrNull { it.id == currentState.selectedRegion }
                ?.id
                ?: sourceInfo.regions.firstOrNull()?.id
                ?: currentState.selectedRegion.ifBlank { DEFAULT_CHART_REGION },
        )
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

private fun resolveDownloadDirectoryPath(customPath: String?): String {
    val customDirectory = customPath
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { normalizedPathOrNull(it) }
    return (customDirectory ?: DesktopPaths.defaultDownloadsDir().toAbsolutePath().normalize()).toString()
}

private fun normalizedPathOrNull(path: String): Path? {
    return runCatching {
        Path.of(path).toAbsolutePath().normalize()
    }.getOrNull()
}

private fun DesktopSearchUiState.markItemDownloaded(
    musicId: String,
    filePath: String?,
    fileSize: Long?,
    downloadedAt: String?,
): DesktopSearchUiState {
    if (musicId.isBlank()) {
        return this
    }

    val nextResults = results.map { item ->
        if (item.id != musicId) {
            item
        } else {
            item.copy(
                downloaded = true,
                downloadedFilePath = filePath ?: item.downloadedFilePath,
                downloadedFileSize = fileSize ?: item.downloadedFileSize,
                downloadedAt = downloadedAt ?: item.downloadedAt,
            )
        }
    }

    return rebuildVisibleResults(
        results = nextResults,
        sortMode = sortMode,
        filterMode = filterMode,
        preferredSelectedResultId = selectedResultId,
    )
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
