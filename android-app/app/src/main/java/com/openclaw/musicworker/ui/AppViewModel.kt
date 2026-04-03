package com.openclaw.musicworker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.openclaw.musicworker.BuildConfig
import com.openclaw.musicworker.data.ExportedFile
import com.openclaw.musicworker.data.InstalledAppInfo
import com.openclaw.musicworker.data.MusicRepository
import com.openclaw.musicworker.data.api.AppUpdateInfo
import com.openclaw.musicworker.data.api.ChartItem
import com.openclaw.musicworker.data.api.ChartSourceInfo
import com.openclaw.musicworker.data.api.DownloadTask
import com.openclaw.musicworker.data.api.DownloadedSongItem
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

private const val DEFAULT_CHART_SOURCE = "apple_music"
private const val DEFAULT_CHART_TYPE = "songs"
private const val DEFAULT_CHART_PERIOD = "daily"
private const val DEFAULT_CHART_REGION = "us"
private const val DEFAULT_CHART_LIMIT = 50
private const val DEFAULT_SEARCH_LIMIT = 30
private const val DEFAULT_SEARCH_PAGE_SIZE = 10
private const val DEFAULT_LIBRARY_PAGE_SIZE = 10

data class HomeUiState(
    val isLoading: Boolean = false,
    val health: HealthPayload? = null,
    val errorMessage: String? = null,
)

data class SearchUiState(
    val input: String = "",
    val activeKeyword: String = "",
    val isSearching: Boolean = false,
    val allResults: List<SearchItem> = emptyList(),
    val results: List<SearchItem> = emptyList(),
    val currentPage: Int = 1,
    val pageSize: Int = DEFAULT_SEARCH_PAGE_SIZE,
    val totalResults: Int = 0,
    val totalPages: Int = 0,
    val errorMessage: String? = null,
)

data class ChartsUiState(
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

data class LibraryUiState(
    val isLoading: Boolean = false,
    val items: List<DownloadedSongItem> = emptyList(),
    val currentPage: Int = 1,
    val pageSize: Int = DEFAULT_LIBRARY_PAGE_SIZE,
    val totalItems: Int = 0,
    val totalPages: Int = 0,
    val activeLyricsTasks: Map<String, DownloadTask> = emptyMap(),
    val message: String? = null,
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

enum class TaskTab(val label: String) {
    SONG_DOWNLOAD("歌曲下载"),
}

data class TasksUiState(
    val isLoading: Boolean = false,
    val selectedTab: TaskTab = TaskTab.SONG_DOWNLOAD,
    val downloadTasks: List<DownloadTask> = emptyList(),
    val errorMessage: String? = null,
)

data class PlaybackUiState(
    val currentMusicId: String? = null,
    val currentTitle: String? = null,
    val currentChannel: String? = null,
    val currentDurationSec: Double? = null,
    val currentCoverUrl: String? = null,
    val currentDownloadedSong: DownloadedSongItem? = null,
    val currentTask: DownloadTask? = null,
    val playbackUri: String? = null,
    val source: String? = null,
    val isPreparing: Boolean = false,
    val isPlayerBuffering: Boolean = false,
    val playRequestToken: Long = 0L,
    val isPlayerPlaying: Boolean = false,
    val lyricsContent: String? = null,
    val lyricsUpdatedAt: String? = null,
    val isLyricsLoading: Boolean = false,
    val lyricsErrorMessage: String? = null,
    val message: String? = null,
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
    val charts: ChartsUiState = ChartsUiState(),
    val library: LibraryUiState = LibraryUiState(),
    val download: DownloadUiState = DownloadUiState(),
    val tasks: TasksUiState = TasksUiState(),
    val playback: PlaybackUiState = PlaybackUiState(),
    val settings: SettingsUiState = SettingsUiState(),
)

private data class ChartSelection(
    val sourceId: String,
    val type: String,
    val period: String,
    val regionId: String,
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
    private var playbackJob: Job? = null
    private var playbackLyricsJob: Job? = null
    private var lyricsPollJob: Job? = null
    private var playbackRequestToken = 0L

    init {
        refreshDashboard()
        refreshSettingsPanel()
        refreshCharts()
        refreshLibrary()
        refreshTasks()
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

    fun refreshCharts(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val currentCharts = _uiState.value.charts
            val hasLoadedContent = currentCharts.items.isNotEmpty() || currentCharts.title.isNotBlank()
            val shouldFetchSources = forceRefresh || currentCharts.availableSources.isEmpty()

            _uiState.update { state ->
                state.copy(
                    charts = state.charts.copy(
                        isLoading = !hasLoadedContent,
                        isRefreshing = hasLoadedContent,
                        errorMessage = null,
                    ),
                )
            }

            val availableSources = if (!shouldFetchSources) {
                currentCharts.availableSources
            } else {
                runCatching { repository.getChartSources() }
                    .getOrElse { error ->
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
                repository.getCharts(
                    source = selection.sourceId,
                    type = selection.type,
                    period = selection.period,
                    region = selection.regionId,
                    limit = DEFAULT_CHART_LIMIT,
                    forceRefresh = forceRefresh,
                )
            }
                .onSuccess { payload ->
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
                        )
                    }
                }
                .onFailure { error ->
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
                                errorMessage = error.message ?: "加载榜单失败",
                            ),
                        )
                    }
                }
        }
    }

    fun selectChartSource(sourceId: String) {
        val currentCharts = _uiState.value.charts
        val sourceInfo = currentCharts.availableSources.firstOrNull { it.id == sourceId } ?: return
        val nextSelection = resolveChartSelection(
            currentState = currentCharts.copy(selectedSource = sourceInfo.id),
            availableSources = currentCharts.availableSources,
        )
        _uiState.update { state ->
            state.copy(
                charts = state.charts.copy(
                    selectedSource = nextSelection.sourceId,
                    selectedType = nextSelection.type,
                    selectedPeriod = nextSelection.period,
                    selectedRegion = nextSelection.regionId,
                    errorMessage = null,
                ),
            )
        }
        refreshCharts()
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

    fun search() {
        val keyword = _uiState.value.search.input.trim()
        if (keyword.isBlank()) {
            _uiState.update { state ->
                state.copy(search = state.search.copy(errorMessage = "请输入搜索关键词"))
            }
            return
        }

        runSearch(keyword)
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

        runSearch(keyword)
    }

    fun goToPreviousSearchPage() {
        updateSearchPage(_uiState.value.search.currentPage - 1)
    }

    fun goToNextSearchPage() {
        updateSearchPage(_uiState.value.search.currentPage + 1)
    }

    private fun runSearch(keyword: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    search = state.search.copy(
                        input = keyword,
                        activeKeyword = keyword,
                        isSearching = true,
                        allResults = emptyList(),
                        results = emptyList(),
                        currentPage = 1,
                        totalResults = 0,
                        totalPages = 0,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { repository.search(keyword = keyword, limit = DEFAULT_SEARCH_LIMIT) }
                .onSuccess { results ->
                    _uiState.update { state ->
                        state.copy(
                            search = paginateSearchState(
                                state.search.copy(
                                    activeKeyword = keyword,
                                    isSearching = false,
                                    allResults = results,
                                    errorMessage = null,
                                ),
                                requestedPage = 1,
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
                                allResults = emptyList(),
                                results = emptyList(),
                                currentPage = 1,
                                totalResults = 0,
                                totalPages = 0,
                                errorMessage = error.message ?: "搜索失败",
                            ),
                        )
                    }
                }
        }
    }

    private fun updateSearchPage(requestedPage: Int) {
        _uiState.update { state ->
            val searchState = state.search
            if (searchState.totalPages <= 1) {
                state
            } else {
                state.copy(
                    search = paginateSearchState(
                        currentState = searchState,
                        requestedPage = requestedPage,
                    ),
                )
            }
        }
    }

    fun selectTaskTab(tab: TaskTab) {
        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.copy(
                    selectedTab = tab,
                ),
            )
        }
    }

    fun refreshTasks() {
        viewModelScope.launch {
            loadTasks(showLoading = _uiState.value.tasks.downloadTasks.isEmpty())
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            loadLibrary(showLoading = _uiState.value.library.items.isEmpty(), resetFeedback = true)
        }
    }

    fun goToPreviousLibraryPage() {
        val currentLibrary = _uiState.value.library
        if (currentLibrary.currentPage <= 1 || currentLibrary.isLoading) {
            return
        }
        loadLibraryPage(currentLibrary.currentPage - 1)
    }

    fun goToNextLibraryPage() {
        val currentLibrary = _uiState.value.library
        if (
            currentLibrary.totalPages <= 1 ||
            currentLibrary.currentPage >= currentLibrary.totalPages ||
            currentLibrary.isLoading
        ) {
            return
        }
        loadLibraryPage(currentLibrary.currentPage + 1)
    }

    fun generateLyrics(item: DownloadedSongItem) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    library = state.library.copy(
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching { repository.startLyricsGeneration(item.musicId) }
                .onSuccess { task ->
                    _uiState.update { state ->
                        val shouldTrackTask = task.status in setOf("queued", "running")
                        state.copy(
                            library = state.library.copy(
                                activeLyricsTasks = if (shouldTrackTask) {
                                    state.library.activeLyricsTasks + (item.musicId to task)
                                } else {
                                    state.library.activeLyricsTasks - item.musicId
                                },
                                message = when {
                                    task.status == "finished" -> "LRC 已就绪：${item.displayTitle ?: item.filename ?: item.musicId}"
                                    task.status == "queued" -> "已加入 LRC 生成队列"
                                    else -> "正在生成 LRC…"
                                },
                                errorMessage = null,
                            ),
                        )
                    }
                    loadLibrary(showLoading = false, resetFeedback = false)
                    startLyricsPollingIfNeeded()
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            library = state.library.copy(
                                errorMessage = error.message ?: "创建 LRC 任务失败",
                            ),
                        )
                    }
                }
        }
    }

    fun downloadDownloadedSong(item: DownloadedSongItem) {
        if (repository.currentDownloadDirectoryUri().isNullOrBlank()) {
            val title = item.displayTitle?.ifBlank { null } ?: item.filename?.ifBlank { null } ?: item.musicId
            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        selectedTitle = title,
                        errorMessage = "请先在设置页选择下载目录",
                    ),
                    library = state.library.copy(
                        errorMessage = "请先在设置页选择下载目录",
                    ),
                )
            }
            return
        }

        val title = item.displayTitle?.ifBlank { null } ?: item.filename?.ifBlank { null } ?: item.musicId
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        currentTask = null,
                        selectedTitle = title,
                        isPolling = false,
                        isExporting = true,
                        exportDownloadedBytes = 0L,
                        exportTotalBytes = item.fileSize,
                        savedFileName = null,
                        savedFileUri = null,
                        exportMessage = "正在保存到所选目录…",
                        exportErrorMessage = null,
                        errorMessage = null,
                    ),
                    library = state.library.copy(
                        message = null,
                        errorMessage = null,
                    ),
                )
            }

            runCatching {
                repository.exportDownloadedSong(item) { downloadedBytes, totalBytes ->
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                currentTask = null,
                                selectedTitle = title,
                                isPolling = false,
                                isExporting = true,
                                exportDownloadedBytes = downloadedBytes,
                                exportTotalBytes = totalBytes ?: item.fileSize,
                                exportMessage = "正在保存到所选目录…",
                                exportErrorMessage = null,
                                errorMessage = null,
                            ),
                        )
                    }
                }
            }
                .onSuccess { exportedFile ->
                    applyDownloadedSongExportSuccess(title, exportedFile)
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            download = state.download.copy(
                                currentTask = null,
                                selectedTitle = title,
                                isPolling = false,
                                isExporting = false,
                                exportDownloadedBytes = 0L,
                                exportTotalBytes = null,
                                exportMessage = null,
                                exportErrorMessage = error.message ?: "保存文件失败",
                                errorMessage = null,
                            ),
                            library = state.library.copy(
                                errorMessage = error.message ?: "保存文件失败",
                            ),
                        )
                    }
                }
        }
    }

    private fun loadLibraryPage(requestedPage: Int) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    library = state.library.copy(
                        currentPage = requestedPage.coerceAtLeast(1),
                    ),
                )
            }
            loadLibrary(showLoading = true, resetFeedback = false)
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
                    refreshTasks()
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

    fun downloadCurrentPlayback() {
        val playback = _uiState.value.playback
        val musicId = playback.currentMusicId?.trim().orEmpty()
        if (musicId.isBlank()) {
            return
        }

        playback.currentDownloadedSong?.let { downloadedItem ->
            downloadDownloadedSong(downloadedItem)
            return
        }

        val title = playback.currentTitle?.ifBlank { null } ?: musicId
        val activeTask = playback.currentTask
        if (activeTask != null) {
            if (repository.currentDownloadDirectoryUri().isNullOrBlank()) {
                _uiState.update { state ->
                    state.copy(
                        download = state.download.copy(
                            selectedTitle = title,
                            errorMessage = "请先在设置页选择下载目录",
                        ),
                    )
                }
                return
            }

            _uiState.update { state ->
                state.copy(
                    download = state.download.copy(
                        currentTask = activeTask,
                        selectedTitle = title,
                        isPolling = activeTask.status in setOf("queued", "running"),
                        isExporting = false,
                        exportDownloadedBytes = 0L,
                        exportTotalBytes = activeTask.fileSize,
                        savedFileName = null,
                        savedFileUri = null,
                        exportMessage = null,
                        exportErrorMessage = null,
                        errorMessage = activeTask.errorMessage,
                    ),
                )
            }

            refreshTasks()
            if (activeTask.status == "finished") {
                viewModelScope.launch {
                    exportFinishedTask(activeTask)
                }
            } else if (activeTask.status in setOf("queued", "running")) {
                beginPolling(activeTask.taskId)
            }
            return
        }

        startDownload(
            SearchItem(
                id = musicId,
                title = title,
                channel = playback.currentChannel,
                duration = playback.currentDurationSec,
                cover = playback.currentCoverUrl,
            ),
        )
    }

    fun playInApp(item: SearchItem) {
        playbackJob?.cancel()
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    playback = state.playback.copy(
                        currentMusicId = item.id,
                        currentTitle = item.title,
                        currentChannel = item.channel,
                        currentDurationSec = item.duration,
                        currentCoverUrl = item.cover,
                        currentDownloadedSong = null,
                        currentTask = null,
                        playbackUri = null,
                        source = "server",
                        isPreparing = true,
                        isPlayerBuffering = false,
                        isPlayerPlaying = false,
                        lyricsContent = null,
                        lyricsUpdatedAt = null,
                        isLyricsLoading = true,
                        lyricsErrorMessage = null,
                        message = "正在通知服务端准备音频…",
                        errorMessage = null,
                    ),
                )
            }
            loadPlaybackLyrics(item.id)

            runCatching { repository.startDownload(item.id) }
                .onSuccess { task ->
                    _uiState.update { state ->
                        state.copy(
                            playback = state.playback.copy(
                                currentMusicId = item.id,
                                currentTitle = item.title,
                                currentChannel = item.channel,
                                currentDurationSec = item.duration,
                                currentCoverUrl = item.cover,
                                currentTask = task,
                                playbackUri = null,
                                source = "server",
                                isPreparing = true,
                                isPlayerBuffering = false,
                                isPlayerPlaying = false,
                                message = if (task.status == "finished") "服务端音频已准备完成" else "服务端正在准备音频…",
                                errorMessage = null,
                            ),
                        )
                    }
                    refreshTasks()
                    if (task.status == "finished") {
                        applyStreamReady(item, task)
                        refreshDashboard()
                        refreshLibrary()
                    } else {
                        beginPlaybackPolling(item, task.taskId)
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
                                currentCoverUrl = item.cover,
                                currentTask = null,
                                isPreparing = false,
                                isPlayerBuffering = false,
                                message = null,
                                errorMessage = error.message ?: "准备播放失败",
                            ),
                        )
                    }
                }
        }
    }

    fun playDownloadedSong(item: DownloadedSongItem) {
        playbackJob?.cancel()
        val displayTitle = item.displayTitle?.ifBlank { null } ?: item.filename?.ifBlank { null } ?: item.musicId
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    currentMusicId = item.musicId,
                    currentTitle = displayTitle,
                    currentChannel = null,
                    currentDurationSec = item.durationSec,
                    currentCoverUrl = null,
                    currentDownloadedSong = item,
                    currentTask = null,
                    playbackUri = repository.buildDownloadedSongStreamUrl(item.musicId),
                    source = "downloaded",
                    isPreparing = false,
                    isPlayerBuffering = false,
                    playRequestToken = nextPlaybackRequestToken(),
                    isPlayerPlaying = false,
                    lyricsContent = null,
                    lyricsUpdatedAt = null,
                    isLyricsLoading = true,
                    lyricsErrorMessage = null,
                    message = "正在在线播放已下载音频",
                    errorMessage = null,
                ),
            )
        }
        loadPlaybackLyrics(item.musicId)
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    isPlayerPlaying = isPlaying,
                    message = when {
                        state.playback.playbackUri.isNullOrBlank() -> state.playback.message
                        state.playback.isPlayerBuffering -> "正在缓冲音频…"
                        isPlaying -> "正在应用内播放"
                        else -> "已暂停，可直接继续播放"
                    },
                ),
            )
        }
    }

    fun onPlaybackTransportStateChanged(playbackState: Int) {
        _uiState.update { state ->
            val isBuffering = playbackState == Player.STATE_BUFFERING
            state.copy(
                playback = state.playback.copy(
                    isPlayerBuffering = isBuffering,
                    message = when {
                        state.playback.playbackUri.isNullOrBlank() -> state.playback.message
                        playbackState == Player.STATE_BUFFERING -> "正在缓冲音频…"
                        playbackState == Player.STATE_ENDED -> "播放完成"
                        state.playback.isPlayerPlaying -> "正在应用内播放"
                        else -> state.playback.message
                    },
                ),
            )
        }
    }

    fun onPlaybackError(message: String) {
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    isPreparing = false,
                    isPlayerBuffering = false,
                    isPlayerPlaying = false,
                    errorMessage = message,
                ),
            )
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
        refreshCharts()
        refreshLibrary()
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

    private fun beginPlaybackPolling(item: SearchItem, taskId: String) {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive) {
                val taskResult = runCatching { repository.getTask(taskId) }
                if (taskResult.isFailure) {
                    val error = taskResult.exceptionOrNull()
                    _uiState.update { state ->
                        state.copy(
                            playback = state.playback.copy(
                                isPreparing = false,
                                isPlayerBuffering = false,
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
                            isPreparing = task.status in setOf("queued", "running"),
                            message = when {
                                task.status == "finished" -> "服务端音频已准备完成"
                                else -> "服务端正在准备音频…"
                            },
                            errorMessage = task.errorMessage,
                        ),
                    )
                }
                loadTasks(showLoading = false)

                if (task.status == "finished") {
                    applyStreamReady(item, task)
                    refreshDashboard()
                    refreshLibrary()
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
                loadTasks(showLoading = false)

                if (task.status == "finished") {
                    exportFinishedTask(task)
                    refreshDashboard()
                    refreshLibrary()
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

    private suspend fun loadLibrary(showLoading: Boolean, resetFeedback: Boolean) {
        if (showLoading) {
            _uiState.update { state ->
                state.copy(
                    library = state.library.copy(
                        isLoading = true,
                        message = if (resetFeedback) null else state.library.message,
                        errorMessage = if (resetFeedback) null else state.library.errorMessage,
                    ),
                )
            }
        } else if (resetFeedback) {
            _uiState.update { state ->
                state.copy(
                    library = state.library.copy(
                        message = null,
                        errorMessage = null,
                    ),
                )
            }
        }

        val currentLibrary = _uiState.value.library
        val downloadsResult = runCatching {
            repository.getDownloadedSongs(
                page = currentLibrary.currentPage,
                pageSize = currentLibrary.pageSize,
            )
        }
        val tasksResult = runCatching { repository.getTasks() }

        val downloadedSongsPayload = downloadsResult.getOrElse { error ->
            _uiState.update { state ->
                state.copy(
                    library = state.library.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "加载已下载歌曲失败",
                    ),
                )
            }
            return
        }

        val activeLyricsTasks = tasksResult.getOrDefault(emptyList())
            .filter { task -> task.type == "lyrics" && task.status in setOf("queued", "running") }
            .associateBy { task -> task.musicId }

        _uiState.update { state ->
            state.copy(
                library = state.library.copy(
                    isLoading = false,
                    items = downloadedSongsPayload.items,
                    currentPage = downloadedSongsPayload.currentPage,
                    pageSize = downloadedSongsPayload.pageSize,
                    totalItems = downloadedSongsPayload.total,
                    totalPages = downloadedSongsPayload.totalPages,
                    activeLyricsTasks = activeLyricsTasks,
                    message = if (resetFeedback) null else state.library.message,
                    errorMessage = tasksResult.exceptionOrNull()?.message,
                ),
            )
        }

        if (activeLyricsTasks.isEmpty()) {
            lyricsPollJob?.cancel()
            lyricsPollJob = null
        }
    }

    private suspend fun loadTasks(showLoading: Boolean) {
        if (showLoading) {
            _uiState.update { state ->
                state.copy(
                    tasks = state.tasks.copy(
                        isLoading = true,
                        errorMessage = null,
                    ),
                )
            }
        }

        runCatching { repository.getTasks().filter { task -> task.type == "download" } }
            .onSuccess { downloadTasks ->
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks.copy(
                            isLoading = false,
                            downloadTasks = downloadTasks,
                            errorMessage = null,
                        ),
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "加载任务失败",
                        ),
                    )
                }
            }
    }

    private fun startLyricsPollingIfNeeded() {
        if (lyricsPollJob?.isActive == true) {
            return
        }

        lyricsPollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                loadLibrary(showLoading = false, resetFeedback = false)
                if (_uiState.value.library.activeLyricsTasks.isEmpty()) {
                    break
                }
            }
            lyricsPollJob = null
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

    private fun applyStreamReady(item: SearchItem, task: DownloadTask) {
        _uiState.update { state ->
            state.copy(
                playback = state.playback.copy(
                    currentMusicId = item.id,
                    currentTitle = item.title,
                    currentChannel = item.channel,
                    currentDurationSec = item.duration,
                    currentCoverUrl = item.cover,
                    currentDownloadedSong = null,
                    currentTask = task,
                    playbackUri = repository.buildTaskStreamUrl(task.taskId),
                    source = "server",
                    isPreparing = false,
                    isPlayerBuffering = false,
                    playRequestToken = nextPlaybackRequestToken(),
                    isPlayerPlaying = false,
                    isLyricsLoading = true,
                    message = "服务端音频已准备完成，正在在线播放",
                    errorMessage = null,
                ),
            )
        }
        loadPlaybackLyrics(item.id)
    }

    private fun loadPlaybackLyrics(musicId: String?) {
        playbackLyricsJob?.cancel()
        val targetMusicId = musicId?.trim().orEmpty()
        if (targetMusicId.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    playback = state.playback.copy(
                        lyricsContent = null,
                        lyricsUpdatedAt = null,
                        isLyricsLoading = false,
                        lyricsErrorMessage = null,
                    ),
                )
            }
            return
        }

        playbackLyricsJob = viewModelScope.launch {
            runCatching { repository.getDownloadedSongLyrics(targetMusicId) }
                .onSuccess { lyricsPayload ->
                    _uiState.update { state ->
                        if (state.playback.currentMusicId != targetMusicId) {
                            state
                        } else {
                            state.copy(
                                playback = state.playback.copy(
                                    lyricsContent = lyricsPayload?.content,
                                    lyricsUpdatedAt = lyricsPayload?.updatedAt,
                                    isLyricsLoading = false,
                                    lyricsErrorMessage = null,
                                ),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        if (state.playback.currentMusicId != targetMusicId) {
                            state
                        } else {
                            state.copy(
                                playback = state.playback.copy(
                                    lyricsContent = null,
                                    lyricsUpdatedAt = null,
                                    isLyricsLoading = false,
                                    lyricsErrorMessage = error.message ?: "加载歌词失败",
                                ),
                            )
                        }
                    }
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

    private fun applyDownloadedSongExportSuccess(
        title: String,
        exportedFile: ExportedFile,
    ) {
        _uiState.update { state ->
            state.copy(
                download = state.download.copy(
                    currentTask = null,
                    selectedTitle = title,
                    isPolling = false,
                    isExporting = false,
                    exportDownloadedBytes = 0L,
                    exportTotalBytes = null,
                    savedFileName = exportedFile.fileName,
                    savedFileUri = exportedFile.fileUri,
                    exportMessage = "已直接保存到所选目录：${exportedFile.fileName}",
                    exportErrorMessage = null,
                    errorMessage = null,
                ),
            )
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        playbackJob?.cancel()
        playbackLyricsJob?.cancel()
        lyricsPollJob?.cancel()
        super.onCleared()
    }

    private fun paginateSearchState(
        currentState: SearchUiState,
        requestedPage: Int,
    ): SearchUiState {
        val totalResults = currentState.allResults.size
        val totalPages = if (totalResults <= 0) {
            0
        } else {
            ((totalResults - 1) / currentState.pageSize) + 1
        }
        val safePage = if (totalPages <= 0) 1 else requestedPage.coerceIn(1, totalPages)
        val startIndex = if (totalPages <= 0) 0 else (safePage - 1) * currentState.pageSize
        val pagedResults = if (totalPages <= 0) {
            emptyList()
        } else {
            currentState.allResults.drop(startIndex).take(currentState.pageSize)
        }
        return currentState.copy(
            results = pagedResults,
            currentPage = safePage,
            totalResults = totalResults,
            totalPages = totalPages,
        )
    }

    private fun nextPlaybackRequestToken(): Long {
        playbackRequestToken += 1L
        return playbackRequestToken
    }

    private fun resolveChartSelection(
        currentState: ChartsUiState,
        availableSources: List<ChartSourceInfo>,
    ): ChartSelection {
        if (availableSources.isEmpty()) {
            return ChartSelection(
                sourceId = currentState.selectedSource.ifBlank { DEFAULT_CHART_SOURCE },
                type = currentState.selectedType.ifBlank { DEFAULT_CHART_TYPE },
                period = currentState.selectedPeriod.ifBlank { DEFAULT_CHART_PERIOD },
                regionId = currentState.selectedRegion.ifBlank { DEFAULT_CHART_REGION },
            )
        }

        val sourceInfo = availableSources.firstOrNull { it.id == currentState.selectedSource } ?: availableSources.first()
        return ChartSelection(
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
