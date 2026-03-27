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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.data.api.SearchItem
import com.openclaw.musicworker.ui.DownloadUiState
import com.openclaw.musicworker.ui.SearchUiState
import kotlin.math.roundToInt

@Composable
fun ResultsScreen(
    state: SearchUiState,
    downloadState: DownloadUiState,
    onRetrySearch: () -> Unit,
    onDownload: (SearchItem) -> Unit,
    onRetryExport: () -> Unit,
) {
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "搜索结果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (state.activeKeyword.isNotBlank()) {
                        Text(text = "当前关键词", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = state.activeKeyword,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.isSearching) {
                        CircularProgressIndicator()
                    }
                    state.errorMessage?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetrySearch) {
                            Text(text = "重试搜索")
                        }
                    }
                    if (!state.isSearching && state.results.isEmpty() && state.errorMessage == null) {
                        Text(text = "还没有结果。请先去搜索页发起请求。")
                    }
                }
            }
        }

        if (downloadState.currentTask != null || downloadState.errorMessage != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "当前下载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(text = downloadState.selectedTitle ?: downloadState.currentTask?.musicId.orEmpty())
                        downloadState.currentTask?.let { task ->
                            Text(text = "状态：${task.status} / ${task.stage}")
                            LinearProgressIndicator(
                                progress = { task.progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(text = "进度：${task.progress}%")
                            task.speedBps?.let {
                                Text(text = "速度：${formatSpeed(it)}")
                            }
                            task.filename?.let { Text(text = "文件：$it") }
                        }
                        if (downloadState.isExporting) {
                            val exportProgress = downloadState.exportTotalBytes
                                ?.takeIf { it > 0L }
                                ?.let { totalBytes ->
                                    (downloadState.exportDownloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                }
                            if (exportProgress != null) {
                                LinearProgressIndicator(
                                    progress = { exportProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "保存进度：${(exportProgress * 100).toInt()}%  " +
                                        "${formatBytes(downloadState.exportDownloadedBytes)} / " +
                                        "${formatBytes(downloadState.exportTotalBytes ?: 0L)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                        downloadState.exportMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.primary)
                        }
                        downloadState.savedFileUri?.let {
                            Text(text = "保存位置：$it", style = MaterialTheme.typography.bodySmall)
                        }
                        downloadState.exportErrorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                        if (downloadState.currentTask?.status == "finished" && !downloadState.isExporting) {
                            Button(
                                onClick = onRetryExport,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (downloadState.savedFileName == null) {
                                        "保存到所选目录"
                                    } else {
                                        "再次保存到所选目录"
                                    },
                                )
                            }
                        }
                        downloadState.errorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        items(state.results, key = { it.id }) { item ->
            SearchResultCard(item = item, onDownload = { onDownload(item) })
        }
    }
}

@Composable
private fun SearchResultCard(
    item: SearchItem,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            item.channel?.let { Text(text = it) }
            item.duration?.let {
                Text(text = "时长：${formatDuration(it)}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDownload) {
                    Text(text = "下载 MP3")
                }
            }
        }
    }
}

private fun formatSpeed(speedBps: Double): String {
    val kbps = speedBps / 1024.0
    val mbps = kbps / 1024.0
    return if (mbps >= 1) {
        String.format("%.2f MB/s", mbps)
    } else {
        String.format("%.0f KB/s", kbps)
    }
}

private fun formatDuration(durationSec: Double): String {
    val totalSeconds = durationSec.roundToInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes} 分 ${seconds} 秒"
    } else {
        "${seconds} 秒"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) {
        return "0 B"
    }

    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }

    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format("%.1f %s", value, units[unitIndex])
    }
}
