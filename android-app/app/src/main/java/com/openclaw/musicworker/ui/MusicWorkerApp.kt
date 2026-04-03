package com.openclaw.musicworker.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openclaw.musicworker.ui.screens.HomeScreen
import com.openclaw.musicworker.ui.screens.LibraryScreen
import com.openclaw.musicworker.ui.screens.PlayerScreen
import com.openclaw.musicworker.ui.screens.ResultsScreen
import com.openclaw.musicworker.ui.screens.TasksScreen
import com.openclaw.musicworker.ui.screens.ChartsScreen
import com.openclaw.musicworker.ui.screens.SearchScreen
import com.openclaw.musicworker.ui.screens.SettingsScreen
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

private data class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    AppDestination(route = "home", label = "首页", icon = Icons.Rounded.Home),
    AppDestination(route = "search", label = "搜索", icon = Icons.Rounded.Search),
    AppDestination(route = "charts", label = "排行榜", icon = Icons.Rounded.MusicNote),
    AppDestination(route = "library", label = "已下载", icon = Icons.Rounded.LibraryMusic),
    AppDestination(route = "settings", label = "设置", icon = Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicWorkerApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "home"
    val isMainDestination = destinations.any { it.route == currentRoute }
    var lastMainRoute by rememberSaveable { androidx.compose.runtime.mutableStateOf("home") }
    LaunchedEffect(currentRoute) {
        if (destinations.any { it.route == currentRoute }) {
            lastMainRoute = currentRoute
        }
    }
    val bottomBarSelectedRoute = when {
        isMainDestination -> currentRoute
        currentRoute == "player" || currentRoute == "tasks" -> lastMainRoute
        else -> null
    }
    val showBottomNavigation = isMainDestination || currentRoute == "player" || currentRoute == "tasks"
    val showTaskFab = currentRoute != "tasks"
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTaskCount = activeTaskCount(uiState.tasks, uiState.download)
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.onPlaybackStateChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                viewModel.onPlaybackTransportStateChanged(playbackState)
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.onPlaybackError(error.message ?: "音频播放失败")
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        viewModel.saveDownloadDirectory(uri.toString())
    }
    LaunchedEffect(uiState.settings.pendingInstallAppUpdateUri) {
        val pendingUri = uiState.settings.pendingInstallAppUpdateUri ?: return@LaunchedEffect
        val contentUri = runCatching { Uri.parse(pendingUri) }.getOrNull()
        if (contentUri == null) {
            viewModel.onAppUpdateInstallHandled(errorMessage = "更新包地址无效")
            return@LaunchedEffect
        }

        val canInstallPackages = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        val intent = if (canInstallPackages) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching { context.startActivity(intent) }
            .onSuccess {
                if (canInstallPackages) {
                    viewModel.onAppUpdateInstallHandled(message = "已拉起系统安装器")
                } else {
                    viewModel.onAppUpdateInstallHandled(message = "请先允许安装未知应用，然后再点“安装已下载更新”")
                }
            }
            .onFailure { error ->
                viewModel.onAppUpdateInstallHandled(errorMessage = error.message ?: "拉起安装器失败")
            }
    }
    LaunchedEffect(
        uiState.playback.playRequestToken,
        uiState.playback.playbackUri,
        uiState.playback.currentMusicId,
        uiState.playback.currentTitle,
    ) {
        val playbackUri = uiState.playback.playbackUri
        if (playbackUri.isNullOrBlank()) {
            if (uiState.playback.isPreparing || uiState.playback.currentMusicId == null) {
                exoPlayer.stop()
            }
            return@LaunchedEffect
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(playbackUri))
            .setMediaId(uiState.playback.currentMusicId ?: playbackUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(uiState.playback.currentTitle)
                    .build(),
            )
            .build()

        runCatching {
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }.onFailure { error ->
            viewModel.onPlaybackError(error.message ?: "启动应用内播放失败")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentRoute) {
                            "player" -> "播放"
                            "tasks" -> "任务"
                            "results" -> "结果"
                            else -> destinations.firstOrNull { it.route == currentRoute }?.label ?: "Music Worker"
                        },
                    )
                },
                navigationIcon = {
                    if (!isMainDestination && navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            if (showTaskFab) {
                TaskFab(
                    activeTaskCount = activeTaskCount,
                    onClick = {
                        viewModel.refreshTasks()
                        navController.navigate("tasks") {
                            launchSingleTop = true
                        }
                    },
                )
            }
        },
        bottomBar = {
            Column {
                if (currentRoute != "player" && uiState.playback.shouldShowMiniPlayer()) {
                    MiniPlayerBar(
                        state = uiState.playback,
                        player = exoPlayer,
                        onOpenPlayer = {
                            navController.navigate("player") {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                if (showBottomNavigation) {
                    NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = bottomBarSelectedRoute == destination.route,
                                onClick = {
                                    if (currentRoute != destination.route) {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id)
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                icon = { Icon(imageVector = destination.icon, contentDescription = destination.label) },
                                label = { Text(text = destination.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    state = uiState.home,
                    serverConfig = uiState.serverConfig,
                    onRefresh = viewModel::refreshDashboard,
                )
            }
            composable("search") {
                SearchScreen(
                    state = uiState.search,
                    onKeywordChange = viewModel::updateSearchInput,
                    onSearch = {
                        viewModel.search()
                        navController.navigate("results") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("charts") {
                ChartsScreen(
                    state = uiState.charts,
                    onRefresh = { viewModel.refreshCharts(forceRefresh = true) },
                    onSelectRegion = viewModel::selectChartRegion,
                    onSearchSong = { item ->
                        viewModel.searchChartItem(item)
                        navController.navigate("results") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("results") {
                ResultsScreen(
                    state = uiState.search,
                    playbackState = uiState.playback,
                    onRetrySearch = viewModel::search,
                    onPreviousPage = viewModel::goToPreviousSearchPage,
                    onNextPage = viewModel::goToNextSearchPage,
                    onDownload = viewModel::startDownload,
                    onPlayInApp = { item ->
                        viewModel.playInApp(item)
                        navController.navigate("player") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("tasks") {
                LaunchedEffect(Unit) {
                    viewModel.refreshTasks()
                }
                TasksScreen(
                    state = uiState.tasks,
                    downloadState = uiState.download,
                    onSelectTab = viewModel::selectTaskTab,
                    onRefresh = viewModel::refreshTasks,
                    onRetryExport = viewModel::retryExportCurrentTask,
                )
            }
            composable("library") {
                LibraryScreen(
                    state = uiState.library,
                    playbackState = uiState.playback,
                    onRefresh = viewModel::refreshLibrary,
                    onPreviousPage = viewModel::goToPreviousLibraryPage,
                    onNextPage = viewModel::goToNextLibraryPage,
                    onPlayInApp = { item ->
                        viewModel.playDownloadedSong(item)
                        navController.navigate("player") {
                            launchSingleTop = true
                        }
                    },
                    onDownloadSong = viewModel::downloadDownloadedSong,
                    onGenerateLyrics = viewModel::generateLyrics,
                )
            }
            composable("player") {
                PlayerScreen(
                    state = uiState.playback,
                    player = exoPlayer,
                    onDownloadSong = viewModel::downloadCurrentPlayback,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate("results") { launchSingleTop = true }
                        }
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = uiState.settings,
                    serverConfig = uiState.serverConfig,
                    onHostChange = viewModel::updateHost,
                    onPortChange = viewModel::updatePort,
                    onSaveConfig = viewModel::saveServerConfig,
                    onRefresh = viewModel::refreshSettingsPanel,
                    onProxySelect = viewModel::selectProxy,
                    onPickDownloadDirectory = {
                        val initialUri = uiState.settings.downloadDirectoryUri?.let(Uri::parse)
                        directoryPicker.launch(initialUri)
                    },
                    onClearDownloadDirectory = viewModel::clearDownloadDirectory,
                    onClearPrivateStorage = viewModel::clearPrivateStorage,
                    onCheckAppUpdate = viewModel::checkAppUpdate,
                    onDownloadAndInstallAppUpdate = viewModel::downloadAndInstallAppUpdate,
                )
            }
        }
    }
}

@Composable
private fun TaskFab(
    activeTaskCount: Int,
    onClick: () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 10.dp),
    ) {
        BadgedBox(
            badge = {
                if (activeTaskCount > 0) {
                    Badge {
                        Text(text = activeTaskCount.coerceAtMost(99).toString())
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.List,
                contentDescription = "任务中心",
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    state: PlaybackUiState,
    player: Player,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val canControl = !state.playbackUri.isNullOrBlank() && !state.isPreparing

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onOpenPlayer),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerArtwork(
                coverUrl = state.currentCoverUrl,
                title = state.currentTitle ?: "当前播放",
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.currentTitle ?: "当前播放",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = miniPlayerSubtitle(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalIconButton(
                onClick = { togglePlayerPlayback(player) },
                enabled = canControl,
            ) {
                when {
                    state.isPreparing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    state.isPlayerPlaying -> {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = "暂停",
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "播放",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerArtwork(
    coverUrl: String?,
    title: String,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = "封面",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            return
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = "$title 封面",
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            contentScale = ContentScale.Crop,
            loading = {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            },
            error = {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = "封面加载失败",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            },
        )
    }
}

private fun PlaybackUiState.shouldShowMiniPlayer(): Boolean {
    return !currentTitle.isNullOrBlank() ||
        !currentMusicId.isNullOrBlank() ||
        !playbackUri.isNullOrBlank() ||
        isPreparing ||
        currentTask != null
}

private fun miniPlayerSubtitle(state: PlaybackUiState): String {
    return when {
        !state.errorMessage.isNullOrBlank() -> state.errorMessage
        state.isPreparing -> "正在准备音频"
        state.isPlayerBuffering -> "正在缓冲"
        state.isPlayerPlaying && state.source == "downloaded" -> "正在播放已下载歌曲"
        state.isPlayerPlaying -> "正在在线播放"
        !state.playbackUri.isNullOrBlank() -> "已暂停，点此回到播放页"
        !state.currentChannel.isNullOrBlank() -> state.currentChannel
        !state.message.isNullOrBlank() -> state.message
        else -> "点此进入完整播放页"
    } ?: "点此进入完整播放页"
}

private fun togglePlayerPlayback(player: Player) {
    if (player.isPlaying) {
        player.pause()
        return
    }
    if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0L)
    }
    player.play()
}

private fun activeTaskCount(tasks: TasksUiState, download: DownloadUiState): Int {
    val activeTaskIds = buildSet {
        tasks.downloadTasks
            .filter { it.status in setOf("queued", "running") }
            .forEach { add(it.taskId) }
        download.currentTask
            ?.takeIf { download.isPolling || download.isExporting || it.status in setOf("queued", "running") }
            ?.taskId
            ?.let { add(it) }
        if (download.isExporting && download.currentTask == null) {
            add("exporting")
        }
    }
    return activeTaskIds.size
}
