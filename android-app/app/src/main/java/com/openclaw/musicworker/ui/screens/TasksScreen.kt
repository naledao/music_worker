package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.data.api.DownloadTask
import com.openclaw.musicworker.ui.DownloadUiState
import com.openclaw.musicworker.ui.TaskTab
import com.openclaw.musicworker.ui.TasksUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TasksScreen(
    state: TasksUiState,
    downloadState: DownloadUiState,
    onSelectTab: (TaskTab) -> Unit,
    onRefresh: () -> Unit,
    onRetryExport: () -> Unit,
) {
    val tabs = TaskTab.values().toList()
    val selectedTabIndex = tabs.indexOf(state.selectedTab).coerceAtLeast(0)
    val currentTaskId = downloadState.currentTask?.taskId
    val historyTasks = state.downloadTasks.filterNot { task -> task.taskId == currentTaskId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "任务中心",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "这里集中展示下载任务。后续新增任务类型时，会继续在这里追加新的 tag。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onRefresh, enabled = !state.isLoading) {
                            Text(text = if (state.isLoading) "刷新中..." else "刷新任务")
                        }
                        if (state.isLoading) {
                            CircularProgressIndicator()
                        }
                    }
                    state.errorMessage?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                    TabRow(selectedTabIndex = selectedTabIndex) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == selectedTabIndex,
                                onClick = { onSelectTab(tab) },
                                text = { Text(text = tab.label) },
                            )
                        }
                    }
                }
            }
        }

        when (state.selectedTab) {
            TaskTab.SONG_DOWNLOAD -> {
                if (shouldShowCurrentSongDownload(downloadState)) {
                    item {
                        CurrentSongDownloadCard(
                            state = downloadState,
                            onRetryExport = onRetryExport,
                        )
                    }
                }

                if (state.downloadTasks.isEmpty()) {
                    item {
                        EmptyTaskCard(
                            title = "还没有歌曲下载任务",
                            body = "从搜索结果或已下载列表发起下载后，这里会显示任务进度和历史记录。",
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "服务端任务记录 ${state.downloadTasks.size} 条",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    items(historyTasks, key = { it.taskId }) { task ->
                        SongDownloadTaskHistoryCard(task = task)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentSongDownloadCard(
    state: DownloadUiState,
    onRetryExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "当前歌曲下载",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = state.selectedTitle ?: state.currentTask?.musicId.orEmpty())

            state.currentTask?.let { task ->
                Text(text = "状态：${formatTaskStatus(task.status)} / ${formatTaskStage(task.stage)}")
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = "进度：${task.progress}%")
                task.speedBps?.let {
                    Text(text = "速度：${formatSpeed(it)}")
                }
                task.filename?.let { Text(text = "文件：$it") }
                task.updatedAt?.let {
                    Text(text = "更新时间：${formatTimestamp(it)}")
                }
            }

            if (state.isExporting) {
                val exportProgress = state.exportTotalBytes
                    ?.takeIf { it > 0L }
                    ?.let { totalBytes ->
                        (state.exportDownloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    }
                if (exportProgress != null) {
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存进度：${(exportProgress * 100).toInt()}%  " +
                            "${formatBytes(state.exportDownloadedBytes)} / ${formatBytes(state.exportTotalBytes ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            state.exportMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.primary)
            }
            state.savedFileUri?.let {
                Text(text = "保存位置：$it", style = MaterialTheme.typography.bodySmall)
            }
            state.exportErrorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            state.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            if (state.currentTask?.status == "finished" && !state.isExporting) {
                Button(
                    onClick = onRetryExport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (state.savedFileName == null) {
                            "保存到所选目录"
                        } else {
                            "再次保存到所选目录"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SongDownloadTaskHistoryCard(task: DownloadTask) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = task.filename?.ifBlank { null } ?: task.musicId,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "状态：${formatTaskStatus(task.status)} / ${formatTaskStage(task.stage)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { task.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "进度：${task.progress}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            task.speedBps?.let {
                Text(
                    text = "速度：${formatSpeed(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.fileSize?.takeIf { it > 0L }?.let {
                Text(
                    text = "文件大小：${formatBytes(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.createdAt?.let {
                Text(
                    text = "创建于：${formatTimestamp(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.updatedAt?.let {
                Text(
                    text = "更新于：${formatTimestamp(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyTaskCard(
    title: String,
    body: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun shouldShowCurrentSongDownload(state: DownloadUiState): Boolean {
    return state.currentTask != null ||
        state.selectedTitle != null ||
        state.isExporting ||
        state.exportMessage != null ||
        state.exportErrorMessage != null ||
        state.errorMessage != null
}

private fun formatTaskStatus(status: String?): String {
    return when (status) {
        "queued" -> "排队中"
        "running" -> "进行中"
        "finished" -> "已完成"
        "failed" -> "失败"
        else -> status?.ifBlank { "未知" } ?: "未知"
    }
}

private fun formatTaskStage(stage: String?): String {
    return when (stage) {
        "queued" -> "排队中"
        "starting" -> "开始处理"
        "searching" -> "搜索资源"
        "downloading" -> "下载音频"
        "completed" -> "已完成"
        "failed" -> "失败"
        else -> stage?.ifBlank { "处理中" } ?: "处理中"
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
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

private fun formatSpeed(speedBps: Double): String {
    val kbps = speedBps / 1024.0
    val mbps = kbps / 1024.0
    return if (mbps >= 1) {
        String.format(Locale.US, "%.2f MB/s", mbps)
    } else {
        String.format(Locale.US, "%.0f KB/s", kbps)
    }
}

private fun formatTimestamp(timestamp: String): String {
    return runCatching {
        val instant = Instant.parse(timestamp)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(timestamp)
}
