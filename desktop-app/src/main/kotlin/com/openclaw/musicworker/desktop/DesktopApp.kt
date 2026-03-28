package com.openclaw.musicworker.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.HealthPayload

private enum class StatusChipTone {
    ACCENT,
    SUCCESS,
    NEUTRAL,
    ERROR,
}

@Composable
fun DesktopApp() {
    val appState = remember { DesktopAppState() }
    val uiState by appState.uiState.collectAsState()
    val hasActiveDownload = uiState.download.isStarting || uiState.download.currentTask?.status in setOf("queued", "running")

    LaunchedEffect(appState) {
        appState.initialize()
    }

    DisposableEffect(appState) {
        onDispose {
            appState.close()
        }
    }

    DesktopTheme {
        DesktopScaffold(
            currentPage = uiState.currentPage,
            serverBaseUrl = uiState.serverConfig.baseUrl,
            onPageSelected = appState::switchPage,
            topBar = {
                DesktopTopBar(
                    uiState = uiState,
                    onRefreshAll = {
                        appState.refreshHealth()
                        appState.refreshOperations()
                        appState.refreshLatestTask()
                    },
                    onCheckUpdate = appState::checkAppUpdate,
                )
            },
            taskPanel = {
                TaskSidebar(
                    uiState = uiState,
                    onRefreshTask = appState::refreshLatestTask,
                    onOpenUpdate = appState::downloadOrOpenAppUpdate,
                )
            },
        ) {
            when (uiState.currentPage) {
                DesktopPage.OVERVIEW -> OverviewPage(
                    uiState = uiState,
                    onRefreshAll = {
                        appState.refreshHealth()
                        appState.refreshOperations()
                        appState.refreshLatestTask()
                    },
                )
                DesktopPage.SEARCH -> SearchDownloadPage(
                    uiState = uiState,
                    hasActiveDownload = hasActiveDownload,
                    onSearchInputChanged = appState::updateSearchInput,
                    onSelectResult = appState::selectSearchResult,
                    onSortModeChanged = appState::updateSearchSortMode,
                    onFilterModeChanged = appState::updateSearchFilterMode,
                    onSearch = appState::search,
                    onStartDownload = appState::startDownload,
                )
                DesktopPage.OPERATIONS -> OperationsPage(
                    uiState = uiState,
                    onRefresh = appState::refreshOperations,
                    onSelectProxy = appState::selectProxy,
                )
                DesktopPage.SETTINGS -> SettingsPage(
                    uiState = uiState,
                    onUpdateHost = appState::updateHost,
                    onUpdatePort = appState::updatePort,
                    onSaveConfig = appState::saveConfig,
                    onRefreshHealth = appState::refreshHealth,
                    onCheckUpdate = appState::checkAppUpdate,
                    onDownloadOrOpenUpdate = appState::downloadOrOpenAppUpdate,
                )
            }
        }
    }
}

@Composable
private fun DesktopTopBar(
    uiState: DesktopUiState,
    onRefreshAll: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "音爪 Windows",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = uiState.currentPage.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = uiState.currentPage.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.message?.let {
                    StatusMessagePill(message = it)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    label = "API",
                    value = if (uiState.health.health != null && uiState.health.errorMessage == null) "已连接" else "未连接",
                    tone = if (uiState.health.health != null && uiState.health.errorMessage == null) {
                        StatusChipTone.SUCCESS
                    } else {
                        StatusChipTone.ERROR
                    },
                )
                StatusChip(
                    label = "节点",
                    value = uiState.ops.proxy?.name.orEmpty().ifBlank {
                        uiState.health.health?.proxy?.name.orEmpty().ifBlank { "未知" }
                    },
                    tone = StatusChipTone.NEUTRAL,
                )
                StatusChip(
                    label = "更新",
                    value = when {
                        hasNewVersion(uiState.update) -> "发现新版本"
                        uiState.update.downloadedInstallerPath != null -> "安装包已下载"
                        else -> "当前 ${uiState.update.currentVersionName}"
                    },
                    tone = if (hasNewVersion(uiState.update)) StatusChipTone.ACCENT else StatusChipTone.NEUTRAL,
                )

                OutlinedButton(onClick = onRefreshAll) {
                    Text(text = "刷新状态")
                }
                FilledTonalButton(
                    onClick = onCheckUpdate,
                    enabled = !uiState.update.isChecking && !uiState.update.isDownloading,
                ) {
                    Text(text = if (uiState.update.isChecking) "检查中…" else "检查更新")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    value: String,
    tone: StatusChipTone,
) {
    val (containerColor, contentColor, borderColor) = when (tone) {
        StatusChipTone.ACCENT -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        )
        StatusChipTone.SUCCESS -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
        )
        StatusChipTone.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        )
        StatusChipTone.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
        )
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StatusMessagePill(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TaskSidebar(
    uiState: DesktopUiState,
    onRefreshTask: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "任务与更新",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "右侧固定面板持续展示当前下载和安装包状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "当前下载任务",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (uiState.download.isStarting) {
                CircularProgressIndicator()
                Text(text = "正在创建下载任务…")
            }

            uiState.download.currentTask?.let { task ->
                Text(
                    text = uiState.download.selectedTitle ?: task.filename ?: task.musicId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "状态：${formatTaskStatus(task.status)} / ${formatTaskStage(task.stage)}")
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = "进度：${task.progress.coerceIn(0, 100)}%")
                formatTransferProgress(task)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
                task.speedBps?.let {
                    Text(text = "速度：${formatSpeed(it)}")
                }
                task.etaSec?.let {
                    Text(text = "剩余时间：${formatEta(it)}")
                }
                task.filename?.let {
                    Text(text = "文件：$it", style = MaterialTheme.typography.bodySmall)
                }
                task.filePath?.let {
                    Text(text = "服务端路径：$it", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!uiState.download.isStarting && uiState.download.currentTask == null && uiState.download.errorMessage == null) {
                Text(
                    text = "暂无下载任务。切到“搜索下载”页后可直接发起下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.download.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(
                onClick = onRefreshTask,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "刷新任务状态")
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "桌面端更新",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = "当前版本：${uiState.update.currentVersionName} (${uiState.update.currentVersionCode})")
            uiState.update.availableUpdate?.let {
                Text(
                    text = "安装包：${it.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "大小：${formatBytes(it.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.update.downloadedInstallerPath?.let {
                Text(
                    text = "安装包已就绪",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                FilledTonalButton(
                    onClick = onOpenUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.update.isDownloading,
                ) {
                    Text(text = "打开已下载安装包")
                }
            }
            uiState.update.message?.let {
                Text(text = it, color = MaterialTheme.colorScheme.primary)
            }
            uiState.update.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun OverviewPage(
    uiState: DesktopUiState,
    onRefreshAll: () -> Unit,
) {
    val currentTask = uiState.download.currentTask
    val recentLogs = uiState.ops.logs.takeLast(10)
    val healthAvailable = uiState.health.health != null && uiState.health.errorMessage == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DesktopSectionPanel(
            title = "运行概览",
            subtitle = "服务连接、代理状态、下载任务和更新状态在这里集中查看。",
            action = {
                OutlinedButton(onClick = onRefreshAll) {
                    Text(text = "刷新全部状态")
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    title = "服务状态",
                    value = if (healthAvailable) "已连接" else "未连接",
                    detail = uiState.serverConfig.baseUrl,
                    tone = if (healthAvailable) StatusChipTone.SUCCESS else StatusChipTone.ERROR,
                )
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    title = "当前节点",
                    value = uiState.ops.proxy?.name.orEmpty().ifBlank { "未知" },
                    detail = uiState.ops.proxy?.selector.orEmpty().ifBlank { "未读取分组" },
                    tone = StatusChipTone.NEUTRAL,
                )
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    title = "下载任务",
                    value = currentTask?.let { formatTaskStatus(it.status) } ?: "空闲",
                    detail = currentTask?.let { "${it.progress.coerceIn(0, 100)}% · ${formatTaskStage(it.stage)}" } ?: "暂无任务",
                    tone = when (currentTask?.status) {
                        "failed" -> StatusChipTone.ERROR
                        "queued", "running" -> StatusChipTone.ACCENT
                        "finished" -> StatusChipTone.SUCCESS
                        else -> StatusChipTone.NEUTRAL
                    },
                )
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    title = "桌面更新",
                    value = when {
                        hasNewVersion(uiState.update) -> "发现新版本"
                        uiState.update.downloadedInstallerPath != null -> "安装包已下载"
                        else -> "已是当前版本"
                    },
                    detail = "当前 ${uiState.update.currentVersionName}",
                    tone = when {
                        hasNewVersion(uiState.update) -> StatusChipTone.ACCENT
                        uiState.update.downloadedInstallerPath != null -> StatusChipTone.SUCCESS
                        else -> StatusChipTone.NEUTRAL
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DesktopSectionPanel(
                title = "服务与任务",
                subtitle = "快速确认本地 API、代理、Cookie 与当前任务状态。",
                modifier = Modifier.weight(1f),
            ) {
                DesktopHealthSummary(
                    health = uiState.health.health,
                    errorMessage = uiState.health.errorMessage,
                )

                currentTask?.let { task ->
                    DesktopInsetPanel(title = "当前任务快照") {
                        DesktopFactRow(
                            label = "任务",
                            value = uiState.download.selectedTitle ?: task.filename ?: task.musicId,
                        )
                        DesktopFactRow(
                            label = "状态",
                            value = "${formatTaskStatus(task.status)} / ${formatTaskStage(task.stage)}",
                        )
                        DesktopFactRow(
                            label = "进度",
                            value = "${task.progress.coerceIn(0, 100)}%",
                        )
                        formatTransferProgress(task)?.let {
                            DesktopFactRow(label = "传输", value = it)
                        }
                    }
                } ?: DesktopNoticeBanner(
                    message = "当前没有下载任务，可切到“搜索下载”页发起下载。",
                    tone = StatusChipTone.NEUTRAL,
                )
            }

            DesktopSectionPanel(
                title = "最近日志",
                subtitle = if (recentLogs.isEmpty()) {
                    "当前还没有读取到运行日志。"
                } else {
                    "展示最近 ${recentLogs.size} 行运行日志，便于快速排障。"
                },
                modifier = Modifier.weight(1f),
            ) {
                if (recentLogs.isEmpty()) {
                    DesktopNoticeBanner(
                        message = "暂无日志，可在“代理日志”页查看完整运行日志。",
                        tone = StatusChipTone.NEUTRAL,
                    )
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recentLogs.forEach { line ->
                                OverviewLogLine(line = line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    detail: String,
    tone: StatusChipTone,
    modifier: Modifier = Modifier,
) {
    val accentColor = when (tone) {
        StatusChipTone.ACCENT -> MaterialTheme.colorScheme.primary
        StatusChipTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
        StatusChipTone.NEUTRAL -> MaterialTheme.colorScheme.outline
        StatusChipTone.ERROR -> MaterialTheme.colorScheme.error
    }
    val titleContainerColor = when (tone) {
        StatusChipTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
        StatusChipTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
        StatusChipTone.NEUTRAL -> MaterialTheme.colorScheme.secondaryContainer
        StatusChipTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val titleContentColor = when (tone) {
        StatusChipTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusChipTone.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusChipTone.NEUTRAL -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusChipTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.18f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = titleContainerColor.copy(alpha = 0.72f),
                contentColor = titleContentColor,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = titleContentColor,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (tone == StatusChipTone.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DesktopHealthSummary(
    health: HealthPayload?,
    errorMessage: String?,
) {
    if (health == null) {
        DesktopNoticeBanner(
            message = errorMessage ?: "还没有读取到健康状态。",
            tone = if (errorMessage != null) StatusChipTone.ERROR else StatusChipTone.NEUTRAL,
        )
        return
    }

    DesktopNoticeBanner(
        message = "本地 API 已连接，可直接执行搜索、下载和代理切换。",
        tone = StatusChipTone.SUCCESS,
    )

    DesktopInsetPanel(title = "健康详情") {
        DesktopFactRow(label = "服务", value = health.service.name)
        DesktopFactRow(label = "监听", value = "${health.service.host}:${health.service.port}")
        DesktopFactRow(
            label = "节点",
            value = health.proxy.name.orEmpty().ifBlank { "未知" },
        )
        DesktopFactRow(
            label = "Cookie",
            value = if (health.runtime.cookies?.enabled == true) "已启用" else "未启用",
        )
        DesktopFactRow(
            label = "FFmpeg",
            value = health.runtime.ffmpeg.orEmpty().ifBlank { "未知" },
        )
    }

    errorMessage?.let {
        DesktopNoticeBanner(
            message = it,
            tone = StatusChipTone.ERROR,
        )
    }
}

@Composable
private fun SettingsPage(
    uiState: DesktopUiState,
    onUpdateHost: (String) -> Unit,
    onUpdatePort: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onRefreshHealth: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadOrOpenUpdate: () -> Unit,
) {
    val updateStatus = when {
        uiState.update.isChecking -> "检查中"
        uiState.update.isDownloading -> "下载中"
        hasNewVersion(uiState.update) -> "发现新版本"
        uiState.update.downloadedInstallerPath != null -> "安装包已就绪"
        else -> "已是当前版本"
    }
    val updateTone = when {
        uiState.update.isDownloading || hasNewVersion(uiState.update) -> StatusChipTone.ACCENT
        uiState.update.downloadedInstallerPath != null -> StatusChipTone.SUCCESS
        else -> StatusChipTone.NEUTRAL
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DesktopSectionPanel(
            title = "桌面端更新",
            subtitle = "检查新版本、下载桌面安装包并在本机打开。",
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    label = "状态",
                    value = updateStatus,
                    tone = updateTone,
                )
                StatusChip(
                    label = "当前版本",
                    value = uiState.update.currentVersionName,
                    tone = StatusChipTone.NEUTRAL,
                )
                uiState.update.availableUpdate?.versionName?.let {
                    StatusChip(
                        label = "可用版本",
                        value = it,
                        tone = StatusChipTone.ACCENT,
                    )
                }
            }

            uiState.update.availableUpdate?.let { update ->
                DesktopInsetPanel(title = "可用安装包") {
                    DesktopFactRow(
                        label = "版本",
                        value = update.versionName ?: "unknown",
                    )
                    DesktopFactRow(
                        label = "文件",
                        value = update.fileName,
                    )
                    DesktopFactRow(
                        label = "大小",
                        value = formatBytes(update.fileSize),
                    )
                    update.updatedAt?.let {
                        DesktopFactRow(label = "时间", value = it)
                    }
                    update.sha256?.let {
                        DesktopFactRow(label = "SHA256", value = it)
                    }
                }
            } ?: DesktopNoticeBanner(
                message = "还没有检查到可用安装包，可先执行一次更新检查。",
                tone = StatusChipTone.NEUTRAL,
            )

            if (uiState.update.isDownloading || uiState.update.downloadedInstallerPath != null) {
                DesktopInsetPanel(
                    title = if (uiState.update.isDownloading) "下载进度" else "安装包状态",
                ) {
                    if (uiState.update.isDownloading) {
                        val progressFraction = uiState.update.totalBytes
                            ?.takeIf { it > 0L }
                            ?.let { totalBytes ->
                                (uiState.update.downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            }
                        if (progressFraction != null) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DesktopFactRow(
                                label = "进度",
                                value = "${(progressFraction * 100).toInt()}%  " +
                                    "${formatBytes(uiState.update.downloadedBytes)} / ${formatBytes(uiState.update.totalBytes ?: 0L)}",
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            DesktopFactRow(
                                label = "已下载",
                                value = formatBytes(uiState.update.downloadedBytes),
                            )
                        }
                    }
                    uiState.update.downloadedInstallerPath?.let {
                        DesktopFactRow(label = "路径", value = it)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onCheckUpdate,
                    enabled = !uiState.update.isChecking && !uiState.update.isDownloading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = if (uiState.update.isChecking) "检查中…" else "检查更新")
                }
                OutlinedButton(
                    onClick = onDownloadOrOpenUpdate,
                    enabled = canDownloadOrOpenUpdate(uiState.update),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = when {
                            uiState.update.isDownloading -> "下载中…"
                            uiState.update.downloadedInstallerPath != null -> "打开安装包"
                            else -> "下载安装包"
                        },
                    )
                }
            }

            uiState.update.message?.let {
                DesktopNoticeBanner(
                    message = it,
                    tone = StatusChipTone.ACCENT,
                )
            }
            uiState.update.errorMessage?.let {
                DesktopNoticeBanner(
                    message = it,
                    tone = StatusChipTone.ERROR,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.9f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DesktopSectionPanel(
                title = "本地 API 配置",
                subtitle = "修改桌面端请求地址，并在保存后立即刷新状态。",
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = uiState.serverConfig.host,
                    onValueChange = onUpdateHost,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Host") },
                )
                OutlinedTextField(
                    value = uiState.serverConfig.port.toString(),
                    onValueChange = onUpdatePort,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("端口") },
                )
                DesktopInsetPanel(title = "当前地址") {
                    DesktopFactRow(
                        label = "Base URL",
                        value = uiState.serverConfig.baseUrl,
                    )
                }
                FilledTonalButton(
                    onClick = onSaveConfig,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "保存并刷新状态")
                }
            }

            DesktopSectionPanel(
                title = "健康检查",
                subtitle = "快速确认当前服务、代理和运行依赖是否正常。",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                action = {
                    OutlinedButton(onClick = onRefreshHealth) {
                        Text(text = "重新检查")
                    }
                },
            ) {
                if (uiState.health.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在重新读取健康状态…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DesktopHealthSummary(
                    health = uiState.health.health,
                    errorMessage = uiState.health.errorMessage,
                )
            }
        }
    }
}

@Composable
private fun DesktopSectionPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                action?.invoke()
            }

            content()
        }
    }
}

@Composable
private fun DesktopInsetPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun DesktopNoticeBanner(
    message: String,
    tone: StatusChipTone,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (tone) {
        StatusChipTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
        StatusChipTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
        StatusChipTone.NEUTRAL -> MaterialTheme.colorScheme.secondaryContainer
        StatusChipTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        StatusChipTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusChipTone.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusChipTone.NEUTRAL -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusChipTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    val borderColor = when (tone) {
        StatusChipTone.ACCENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        StatusChipTone.SUCCESS -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        StatusChipTone.NEUTRAL -> MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        StatusChipTone.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor.copy(alpha = 0.72f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DesktopFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OverviewLogLine(line: String) {
    val isErrorLine = isOverviewErrorLine(line)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isErrorLine) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = line,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isErrorLine) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun isOverviewErrorLine(line: String): Boolean {
    val normalized = line.lowercase()
    return OVERVIEW_ERROR_LOG_KEYWORDS.any { keyword -> normalized.contains(keyword) }
}

private val OVERVIEW_ERROR_LOG_KEYWORDS = listOf(
    "error",
    "failed",
    "exception",
    "traceback",
    "timeout",
    "429",
    "403",
)

private fun canDownloadOrOpenUpdate(updateState: DesktopUpdateUiState): Boolean {
    return !updateState.isChecking &&
        !updateState.isDownloading &&
        (updateState.downloadedInstallerPath != null || updateState.availableUpdate != null)
}

private fun formatTaskStatus(status: String): String {
    return when (status) {
        "queued" -> "排队中"
        "running" -> "下载中"
        "finished" -> "已完成"
        "failed" -> "失败"
        else -> status.ifBlank { "未知" }
    }
}

private fun formatTaskStage(stage: String): String {
    return when (stage) {
        "queued" -> "等待执行"
        "starting" -> "初始化"
        "attempt_start" -> "开始尝试"
        "downloading" -> "下载音频"
        "download_finished" -> "下载完成"
        "finished" -> "处理完成"
        "attempt_failed" -> "尝试失败"
        "failed" -> "任务失败"
        else -> stage.ifBlank { "未知阶段" }
    }
}

private fun formatTransferProgress(task: DownloadTask): String? {
    val downloaded = task.downloadedBytes.takeIf { it > 0L } ?: return null
    val total = task.totalBytes
    return if (total != null && total > 0L) {
        "已下载：${formatBytes(downloaded)} / ${formatBytes(total)}"
    } else {
        "已下载：${formatBytes(downloaded)}"
    }
}

private fun formatSpeed(speedBps: Double): String {
    if (speedBps <= 0.0) {
        return "0 B/s"
    }

    return "${formatBytes(speedBps.toLong())}/s"
}

private fun formatEta(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainSeconds = safeSeconds % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分 ${remainSeconds} 秒"
        minutes > 0 -> "${minutes} 分 ${remainSeconds} 秒"
        else -> "${remainSeconds} 秒"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) {
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

private fun hasNewVersion(updateState: DesktopUpdateUiState): Boolean {
    val available = updateState.availableUpdate ?: return false
    val availableVersionCode = available.versionCode
    return when {
        availableVersionCode != null -> availableVersionCode > updateState.currentVersionCode
        available.versionName != null -> available.versionName != updateState.currentVersionName
        else -> false
    }
}
