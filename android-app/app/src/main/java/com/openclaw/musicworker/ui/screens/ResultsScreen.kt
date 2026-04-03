package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MusicNote
import com.openclaw.musicworker.data.api.SearchItem
import com.openclaw.musicworker.ui.PlaybackUiState
import com.openclaw.musicworker.ui.SearchUiState
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

@Composable
fun ResultsScreen(
    state: SearchUiState,
    playbackState: PlaybackUiState,
    onRetrySearch: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onDownload: (SearchItem) -> Unit,
    onPlayInApp: (SearchItem) -> Unit,
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
                    if (state.totalResults > 0) {
                        Text(
                            text = "共 ${state.totalResults} 条结果，当前第 ${state.currentPage} / ${state.totalPages.coerceAtLeast(1)} 页",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.totalPages > 1) {
                            SearchPaginationControls(
                                state = state,
                                onPreviousPage = onPreviousPage,
                                onNextPage = onNextPage,
                            )
                        }
                    }
                }
            }
        }

        items(state.results, key = { it.id }) { item ->
            SearchResultCard(
                item = item,
                playbackState = playbackState,
                onDownload = { onDownload(item) },
                onPlayInApp = { onPlayInApp(item) },
            )
        }

        if (state.totalPages > 1) {
            item {
                SearchPaginationControls(
                    state = state,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SearchPaginationControls(
    state: SearchUiState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.totalResults <= 0) {
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onPreviousPage,
            enabled = state.currentPage > 1,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "上一页")
        }
        Button(
            onClick = onNextPage,
            enabled = state.currentPage < state.totalPages,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "下一页")
        }
    }
}

@Composable
private fun SearchResultCard(
    item: SearchItem,
    playbackState: PlaybackUiState,
    onDownload: () -> Unit,
    onPlayInApp: () -> Unit,
) {
    val isCurrentItem = playbackState.currentMusicId == item.id
    val isPreparingCurrentItem = isCurrentItem && playbackState.isPreparing
    val isPlayingCurrentItem = isCurrentItem && playbackState.isPlayerPlaying

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SearchResultCover(
                coverUrl = item.cover,
                title = item.title,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                item.channel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.duration?.let {
                    Text(
                        text = "时长：${formatDuration(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onPlayInApp,
                        enabled = !isPreparingCurrentItem,
                    ) {
                        Text(
                            text = when {
                                isPreparingCurrentItem -> "准备中"
                                isPlayingCurrentItem -> "播放中"
                                else -> "在线播放"
                            },
                        )
                    }
                    Button(onClick = onDownload) {
                        Text(text = "下载 MP3")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCover(
    coverUrl: String?,
    title: String,
) {
    val shape = MaterialTheme.shapes.medium
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .size(108.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isNullOrBlank()) {
            CoverPlaceholder(
                icon = Icons.Outlined.MusicNote,
                contentDescription = "暂无封面",
            )
            return
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = "$title 封面",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            loading = {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            },
            error = {
                CoverPlaceholder(
                    icon = Icons.Outlined.BrokenImage,
                    contentDescription = "封面加载失败",
                )
            },
        )
    }
}

@Composable
private fun CoverPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(28.dp),
    )
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
