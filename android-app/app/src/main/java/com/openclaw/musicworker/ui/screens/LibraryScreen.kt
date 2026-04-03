package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.data.api.DownloadTask
import com.openclaw.musicworker.data.api.DownloadedSongItem
import com.openclaw.musicworker.ui.LibraryUiState
import com.openclaw.musicworker.ui.PlaybackUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LibraryPaginationAnchor {
    TOP,
    BOTTOM,
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    playbackState: PlaybackUiState,
    onRefresh: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPlayInApp: (DownloadedSongItem) -> Unit,
    onDownloadSong: (DownloadedSongItem) -> Unit,
    onGenerateLyrics: (DownloadedSongItem) -> Unit,
) {
    val listState = rememberLazyListState()
    var pendingPaginationAnchor by remember { mutableStateOf<LibraryPaginationAnchor?>(null) }

    LaunchedEffect(state.currentPage, state.isLoading, state.items.size, pendingPaginationAnchor) {
        val anchor = pendingPaginationAnchor ?: return@LaunchedEffect
        if (state.isLoading) {
            return@LaunchedEffect
        }

        when (anchor) {
            LibraryPaginationAnchor.TOP -> listState.scrollToItem(0)
            LibraryPaginationAnchor.BOTTOM -> {
                val lastIndex = if (state.totalPages > 1) state.items.size + 1 else state.items.size
                listState.scrollToItem(lastIndex.coerceAtLeast(0))
            }
        }
        pendingPaginationAnchor = null
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "已下载歌曲",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "这里展示数据库里已经下载完成的歌曲，可直接查看是否已有 LRC。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "共 ${state.totalItems} 首，当前第 ${state.currentPage} / ${state.totalPages.coerceAtLeast(1)} 页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onRefresh, enabled = !state.isLoading) {
                            Text(text = if (state.isLoading) "刷新中..." else "刷新列表")
                        }
                        if (state.isLoading) {
                            CircularProgressIndicator()
                        }
                    }
                    state.message?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.primary)
                    }
                    state.errorMessage?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.totalPages > 1) {
                        LibraryPaginationControls(
                            state = state,
                            onPreviousPage = {
                                pendingPaginationAnchor = LibraryPaginationAnchor.TOP
                                onPreviousPage()
                            },
                            onNextPage = {
                                pendingPaginationAnchor = LibraryPaginationAnchor.TOP
                                onNextPage()
                            },
                        )
                    }
                }
            }
        }

        if (!state.isLoading && state.items.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "还没有已下载歌曲",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "先去搜索页或排行榜页下载歌曲，列表就会出现在这里。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        items(state.items, key = { it.musicId }) { item ->
            DownloadedSongCard(
                item = item,
                playbackState = playbackState,
                activeLyricsTask = state.activeLyricsTasks[item.musicId],
                onPlayInApp = { onPlayInApp(item) },
                onDownloadSong = { onDownloadSong(item) },
                onGenerateLyrics = { onGenerateLyrics(item) },
            )
        }

        if (state.totalPages > 1) {
            item {
                LibraryPaginationControls(
                    state = state,
                    onPreviousPage = {
                        pendingPaginationAnchor = LibraryPaginationAnchor.BOTTOM
                        onPreviousPage()
                    },
                    onNextPage = {
                        pendingPaginationAnchor = LibraryPaginationAnchor.BOTTOM
                        onNextPage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LibraryPaginationControls(
    state: LibraryUiState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.totalItems <= 0) {
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onPreviousPage,
            enabled = state.currentPage > 1 && !state.isLoading,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "上一页")
        }
        Button(
            onClick = onNextPage,
            enabled = state.currentPage < state.totalPages && !state.isLoading,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "下一页")
        }
    }
}

@Composable
private fun DownloadedSongCard(
    item: DownloadedSongItem,
    playbackState: PlaybackUiState,
    activeLyricsTask: DownloadTask?,
    onPlayInApp: () -> Unit,
    onDownloadSong: () -> Unit,
    onGenerateLyrics: () -> Unit,
) {
    val title = item.displayTitle?.ifBlank { null } ?: item.filename?.ifBlank { null } ?: item.musicId
    val isGenerating = activeLyricsTask != null && activeLyricsTask.status in setOf("queued", "running")
    val isCurrentItem = playbackState.currentMusicId == item.musicId
    val isPlayingCurrentItem = isCurrentItem && playbackState.isPlayerPlaying
    val isPreparingCurrentItem = isCurrentItem && playbackState.isPreparing

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "文件：${item.filename ?: item.filePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildMetaLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item.lyricsExists) {
                Text(
                    text = "LRC：已生成${item.lyricsUpdatedAt?.let { " · ${formatTimestamp(it)}" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "LRC：未生成",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (isGenerating) {
                LinearProgressIndicator(
                    progress = { activeLyricsTask.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "任务：${formatLyricsStage(activeLyricsTask.stage)} · ${activeLyricsTask.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPlayInApp,
                    enabled = !isPreparingCurrentItem,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = when {
                            isPreparingCurrentItem -> "准备中"
                            isPlayingCurrentItem -> "播放中"
                            else -> "在线播放"
                        },
                    )
                }
                Button(
                    onClick = onDownloadSong,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "下载歌曲")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isGenerating -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = if (activeLyricsTask.status == "queued") "排队中" else "生成中")
                        }
                    }

                    item.lyricsExists -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "已生成 .lrc")
                        }
                    }

                    else -> {
                        Button(
                            onClick = onGenerateLyrics,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "生成 .lrc")
                        }
                    }
                }
            }
        }
    }
}

private fun buildMetaLine(item: DownloadedSongItem): String {
    val segments = buildList {
        item.durationSec?.let { add("时长 ${formatDuration(it)}") }
        item.fileSize?.takeIf { it > 0L }?.let { add(formatBytes(it)) }
        item.downloadedAt?.let { add("下载于 ${formatTimestamp(it)}") }
    }
    return segments.joinToString("  ·  ").ifBlank { item.musicId }
}

private fun formatLyricsStage(stage: String?): String {
    return when (stage) {
        "queued" -> "排队中"
        "preparing" -> "准备环境"
        "uploading_audio" -> "上传音频"
        "transcribing" -> "生成歌词"
        "pulling_lrc" -> "回传 LRC"
        "embedding_lyrics" -> "写入 MP3"
        "already_exists" -> "歌词已存在"
        "finished" -> "已完成"
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

private fun formatDuration(durationSec: Double): String {
    val totalSeconds = durationSec.toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes} 分 ${seconds} 秒"
    } else {
        "${seconds} 秒"
    }
}

private fun formatTimestamp(timestamp: String): String {
    return runCatching {
        val instant = Instant.parse(timestamp)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(timestamp)
}
