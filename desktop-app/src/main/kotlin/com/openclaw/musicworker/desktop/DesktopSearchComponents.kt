package com.openclaw.musicworker.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.SearchItem
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.awt.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SearchCoverColumnWidth = 64.dp
private val SearchCoverSize = 48.dp
private val SearchPreviewMaxWidthFraction = 0.82f
private val SearchPreviewMaxHeightFraction = 0.8f
private val SearchDurationColumnWidth = 68.dp
private val SearchStatusColumnWidth = 92.dp
private val SearchActionColumnWidth = 196.dp
private val SearchCoverCache = ConcurrentHashMap<String, ImageBitmap>()
private val SearchCoverFailedUrls = ConcurrentHashMap.newKeySet<String>()
private val SearchCoverPointerIcon = PointerIcon(Cursor(Cursor.HAND_CURSOR))

internal data class SearchCoverPreview(
    val title: String,
    val coverUrl: String?,
)

@Composable
internal fun SearchSidebarSummary(
    apiBaseUrl: String,
    activeKeyword: String,
    totalCount: Int,
    visibleCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchSidebarFact(label = "当前 API", value = apiBaseUrl)
            SearchSidebarFact(label = "当前关键词", value = activeKeyword.ifBlank { "未发起搜索" })
            SearchSidebarFact(
                label = "结果状态",
                value = when {
                    totalCount == 0 -> "等待新的搜索结果"
                    visibleCount == totalCount -> "已返回 $totalCount 条结果"
                    else -> "显示 $visibleCount / $totalCount 条结果"
                },
            )
            Text(
                text = "下载任务会持续显示在右侧固定面板。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SearchSidebarFact(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SearchContextStrip(
    activeKeyword: String,
    selectedItem: SearchItem?,
    currentTask: DownloadTask?,
    isStarting: Boolean,
    playbackState: DesktopPlaybackUiState,
) {
    if (activeKeyword.isBlank() && selectedItem == null && !hasPlaybackContext(playbackState)) {
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchContextBlock(
                label = "当前搜索",
                value = activeKeyword.ifBlank { "未发起搜索" },
                modifier = Modifier.weight(if (selectedItem != null) 0.38f else 1f),
            )

            selectedItem?.let { item ->
                SearchContextBlock(
                    label = "当前选中",
                    value = item.title,
                    detail = item.channel.orEmpty().ifBlank { "-" },
                    modifier = Modifier.weight(0.62f),
                )
                SearchCompactBadge(value = item.duration?.let(::formatDurationCompact) ?: "-")
                SearchCompactBadge(value = buildSearchItemStatusText(item, currentTask, isStarting, playbackState))
            }

            if (hasPlaybackContext(playbackState)) {
                SearchContextBlock(
                    label = "当前播放",
                    value = playbackState.currentTitle ?: buildPlaybackStatusText(playbackState),
                    detail = buildPlaybackContextDetail(playbackState),
                    modifier = Modifier.weight(if (selectedItem != null) 0.72f else 1f),
                )
                SearchCompactBadge(value = buildPlaybackStatusText(playbackState))
            }
        }
    }
}

@Composable
private fun SearchContextBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SearchResultsList(
    items: List<SearchItem>,
    selectedResultId: String?,
    currentTask: DownloadTask?,
    isStarting: Boolean,
    playbackState: DesktopPlaybackUiState,
    hasActiveDownload: Boolean,
    onSelectResult: (String) -> Unit,
    onPlayInApp: (SearchItem) -> Unit,
    onStartDownload: (SearchItem) -> Unit,
    onPreviewCover: (SearchCoverPreview) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = items,
            key = { it.id },
        ) { item ->
            SearchResultRow(
                item = item,
                isSelected = selectedResultId == item.id,
                currentTask = currentTask,
                isStarting = isStarting,
                playbackState = playbackState,
                hasActiveDownload = hasActiveDownload,
                onSelect = { onSelectResult(item.id) },
                onPlayInApp = { onPlayInApp(item) },
                onStartDownload = { onStartDownload(item) },
                onPreviewCover = {
                    onSelectResult(item.id)
                    onPreviewCover(
                        SearchCoverPreview(
                            title = item.title,
                            coverUrl = item.cover,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    item: SearchItem,
    isSelected: Boolean,
    currentTask: DownloadTask?,
    isStarting: Boolean,
    playbackState: DesktopPlaybackUiState,
    hasActiveDownload: Boolean,
    onSelect: () -> Unit,
    onPlayInApp: () -> Unit,
    onStartDownload: () -> Unit,
    onPreviewCover: () -> Unit,
) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isHovered -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val border = if (isSelected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource = hoverInteractionSource)
            .clickable(onClick = onSelect),
        color = containerColor,
        border = border,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchResultCover(
                coverUrl = item.cover,
                title = item.title,
                modifier = Modifier.width(SearchCoverColumnWidth),
                onClick = onPreviewCover,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.channel.orEmpty().ifBlank { "-" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = item.duration?.let(::formatDurationCompact) ?: "-",
                modifier = Modifier.width(SearchDurationColumnWidth),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SearchCompactBadge(
                value = buildSearchItemStatusText(item, currentTask, isStarting, playbackState),
                modifier = Modifier.width(SearchStatusColumnWidth),
            )

            Row(
                modifier = Modifier.width(SearchActionColumnWidth),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchResultActionButton(
                    isEmphasized = playbackState.currentMusicId == item.id,
                    onClick = onPlayInApp,
                    enabled = playbackButtonEnabled(
                        item = item,
                        currentTask = currentTask,
                        playbackState = playbackState,
                        hasActiveDownload = hasActiveDownload,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = playbackButtonText(item, playbackState),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                SearchResultActionButton(
                    isEmphasized = isSelected || currentTask?.musicId == item.id,
                    onClick = onStartDownload,
                    enabled = !hasActiveDownload || currentTask?.musicId == item.id,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = downloadButtonText(
                            item = item,
                            currentTask = currentTask,
                            isStarting = isStarting,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchResultHeaderRow() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(SearchCoverColumnWidth),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "封面",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "歌曲 / 频道",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "时长",
                modifier = Modifier.width(SearchDurationColumnWidth),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier.width(SearchStatusColumnWidth),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "状态",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier.width(SearchActionColumnWidth),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "操作",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchResultCover(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val image = rememberSearchCoverImage(coverUrl)

    Box(
        modifier = modifier
            .pointerHoverIcon(SearchCoverPointerIcon)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(
                modifier = Modifier.size(SearchCoverSize),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "$title 封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = buildSearchCoverFallbackText(title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchCoverPreviewOverlay(
    preview: SearchCoverPreview,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    val image = rememberSearchCoverImage(preview.coverUrl)
    val blockDismissInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(SearchPreviewMaxWidthFraction)
                .fillMaxHeight(SearchPreviewMaxHeightFraction)
                .clickable(
                    interactionSource = blockDismissInteractionSource,
                    indication = null,
                    onClick = {},
                ),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = "${preview.title} 封面大图",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = buildSearchCoverFallbackText(preview.title),
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (preview.coverUrl.isNullOrBlank()) "当前结果没有可预览封面" else "封面加载中或暂时不可用",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "点击空白区域关闭预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun rememberSearchCoverImage(coverUrl: String?): ImageBitmap? {
    val normalizedUrl = remember(coverUrl) { coverUrl?.trim().orEmpty() }
    val cachedImage = if (normalizedUrl.isBlank()) {
        null
    } else {
        SearchCoverCache[normalizedUrl]
    }
    val image by produceState(
        initialValue = cachedImage,
        key1 = normalizedUrl,
    ) {
        if (normalizedUrl.isBlank()) {
            value = null
            return@produceState
        }
        if (value != null || SearchCoverCache.containsKey(normalizedUrl) || SearchCoverFailedUrls.contains(normalizedUrl)) {
            return@produceState
        }

        value = loadSearchCoverImage(normalizedUrl)
    }
    return image
}

private suspend fun loadSearchCoverImage(url: String): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        val image = runCatching {
            val connection = URL(url).openConnection().apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                )
            }
            connection.getInputStream().use { input ->
                input.readBytes().decodeToImageBitmap()
            }
        }.onFailure { error ->
            DesktopFileLogger.warn("search cover load failed url=$url err=${error.message.orEmpty()}")
        }.getOrNull()

        if (image != null) {
            SearchCoverFailedUrls.remove(url)
            SearchCoverCache[url] = image
        } else {
            SearchCoverFailedUrls.add(url)
        }
        image
    }
}

private fun buildSearchCoverFallbackText(title: String): String {
    val firstVisible = title.firstOrNull { !it.isWhitespace() } ?: return "♪"
    return firstVisible.uppercaseChar().toString()
}

@Composable
internal fun SearchMetaChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SearchCompactBadge(
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun <T> SearchToolbarMenu(
    label: String,
    value: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.clickable { expanded = true },
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (option == selectedOption) {
                                "当前 · ${optionLabel(option)}"
                            } else {
                                optionLabel(option)
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
internal fun SearchEmptyState(
    title: String,
    detail: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
private fun SearchResultActionButton(
    isEmphasized: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isEmphasized) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            content = { content() },
        )
    }
}

private fun formatDurationCompact(durationSec: Double): String {
    val totalSeconds = durationSec.toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
        else -> "%d:%02d".format(minutes, seconds)
    }
}

private fun downloadButtonText(item: SearchItem, currentTask: DownloadTask?, isStarting: Boolean): String {
    if (isStarting && currentTask == null) {
        return "创建中…"
    }

    return when {
        currentTask?.musicId == item.id && currentTask.status == "running" -> "下载中…"
        currentTask?.musicId == item.id && currentTask.status == "queued" -> "排队中…"
        item.downloaded -> "直接保存"
        currentTask?.musicId == item.id && currentTask.status == "finished" -> "重新下载"
        currentTask?.musicId == item.id && currentTask.status == "failed" -> "重试下载"
        else -> "开始下载"
    }
}

private fun playbackButtonText(item: SearchItem, playbackState: DesktopPlaybackUiState): String {
    if (playbackState.currentMusicId != item.id) {
        return "在线播放"
    }

    return when {
        !playbackState.errorMessage.isNullOrBlank() -> "重试播放"
        playbackState.isPreparing -> "准备中…"
        playbackState.isBuffering -> "缓冲中…"
        playbackState.isPlaying -> "播放中"
        !playbackState.playbackUrl.isNullOrBlank() -> "待播放"
        else -> "在线播放"
    }
}

private fun playbackButtonEnabled(
    item: SearchItem,
    currentTask: DownloadTask?,
    playbackState: DesktopPlaybackUiState,
    hasActiveDownload: Boolean,
): Boolean {
    if (playbackState.currentMusicId == item.id && playbackState.isPreparing) {
        return false
    }

    if (!hasActiveDownload) {
        return true
    }

    return currentTask?.musicId == item.id || playbackState.currentMusicId == item.id
}

private fun buildSearchItemStatusText(
    item: SearchItem,
    currentTask: DownloadTask?,
    isStarting: Boolean,
    playbackState: DesktopPlaybackUiState,
): String {
    if (playbackState.currentMusicId == item.id) {
        return buildPlaybackStatusText(playbackState)
    }

    if (isStarting && currentTask == null) {
        return "准备中"
    }

    if (currentTask?.musicId != item.id) {
        return if (item.downloaded) "已存在" else "待下载"
    }

    return when (currentTask.status) {
        "queued" -> "排队中"
        "running" -> "下载中"
        "finished" -> "已完成"
        "failed" -> "失败"
        else -> currentTask.status.ifBlank { "未知" }
    }
}

private fun hasPlaybackContext(playbackState: DesktopPlaybackUiState): Boolean {
    return playbackState.currentMusicId != null ||
        !playbackState.currentTitle.isNullOrBlank() ||
        playbackState.isPreparing ||
        playbackState.isBuffering ||
        playbackState.isPlaying ||
        !playbackState.errorMessage.isNullOrBlank()
}

private fun buildPlaybackStatusText(playbackState: DesktopPlaybackUiState): String {
    return when {
        !playbackState.errorMessage.isNullOrBlank() -> "播放失败"
        playbackState.isPreparing -> "准备播放"
        playbackState.isBuffering -> "缓冲中"
        playbackState.isPlaying -> "播放中"
        !playbackState.playbackUrl.isNullOrBlank() -> "待播放"
        else -> "未播放"
    }
}

private fun buildPlaybackContextDetail(playbackState: DesktopPlaybackUiState): String? {
    return playbackState.currentChannel
        ?.takeIf { it.isNotBlank() }
        ?: playbackState.message
        ?: playbackState.errorMessage
        ?: playbackState.currentTask?.let { "${it.status} / ${it.stage}" }
}
