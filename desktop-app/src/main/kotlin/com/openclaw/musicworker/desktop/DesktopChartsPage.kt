package com.openclaw.musicworker.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.ChartItem
import com.openclaw.musicworker.shared.api.ChartRegionInfo
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ChartsSidebarWidth = 300.dp
private val ChartCoverCache = ConcurrentHashMap<String, ImageBitmap>()
private val ChartCoverFailedUrls = ConcurrentHashMap.newKeySet<String>()

@Composable
internal fun ChartsPage(
    uiState: DesktopUiState,
    onRefresh: () -> Unit,
    onSelectRegion: (String) -> Unit,
    onSearchSong: (ChartItem) -> Unit,
) {
    val currentSource = uiState.charts.availableSources.firstOrNull { it.id == uiState.charts.selectedSource }
    val currentRegions = currentSource?.regions.orEmpty()
    val selectedRegionLabel = currentRegions.firstOrNull { it.id == uiState.charts.selectedRegion }?.label
        ?: uiState.charts.selectedRegion.uppercase(Locale.US)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChartsSidebar(
            modifier = Modifier
                .width(ChartsSidebarWidth)
                .fillMaxHeight(),
            uiState = uiState,
            selectedRegionLabel = selectedRegionLabel,
            regions = currentRegions,
            onRefresh = onRefresh,
            onSelectRegion = onSelectRegion,
        )

        ChartsResultsPanel(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            uiState = uiState,
            sourceLabel = currentSource?.label ?: "Apple Music",
            selectedRegionLabel = selectedRegionLabel,
            onRefresh = onRefresh,
            onSearchSong = onSearchSong,
        )
    }
}

@Composable
private fun ChartsSidebar(
    modifier: Modifier,
    uiState: DesktopUiState,
    selectedRegionLabel: String,
    regions: List<ChartRegionInfo>,
    onRefresh: () -> Unit,
    onSelectRegion: (String) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "排行榜面板",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "桌面端现在也可以直接查看榜单，并一键带入现有搜索下载链路。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ChartsSummaryCard(
                title = uiState.charts.title.ifBlank { "Apple Music Top Songs" },
                selectedRegionLabel = selectedRegionLabel,
                fromCache = uiState.charts.fromCache,
                updatedAt = uiState.charts.updatedAt,
                itemCount = uiState.charts.items.size,
            )

            FilledTonalButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.charts.isLoading && !uiState.charts.isRefreshing,
            ) {
                Text(
                    text = when {
                        uiState.charts.isRefreshing -> "刷新中…"
                        uiState.charts.isLoading -> "加载中…"
                        else -> "刷新榜单"
                    },
                )
            }

            if (regions.isNotEmpty()) {
                ChartsInsetPanel(title = "地区切换") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        regions.forEach { region ->
                            val selected = region.id == uiState.charts.selectedRegion
                            val containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                            val borderColor = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = containerColor,
                                border = BorderStroke(1.dp, borderColor),
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = region.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = region.id.uppercase(Locale.US),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onSelectRegion(region.id) },
                                        enabled = !selected && !uiState.charts.isLoading && !uiState.charts.isRefreshing,
                                    ) {
                                        Text(text = if (selected) "当前地区" else "切换")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            uiState.charts.errorMessage?.let {
                ChartsNoticeBanner(
                    message = it,
                    tone = ChartsNoticeTone.ERROR,
                )
            }
        }
    }
}

@Composable
private fun ChartsResultsPanel(
    modifier: Modifier,
    uiState: DesktopUiState,
    sourceLabel: String,
    selectedRegionLabel: String,
    onRefresh: () -> Unit,
    onSearchSong: (ChartItem) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChartsHeader(
                sourceLabel = sourceLabel,
                selectedRegionLabel = selectedRegionLabel,
                itemCount = uiState.charts.items.size,
                onRefresh = onRefresh,
                isRefreshing = uiState.charts.isRefreshing || uiState.charts.isLoading,
            )

            when {
                uiState.charts.isLoading && uiState.charts.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChartsEmptyState(
                            title = "正在加载排行榜",
                            detail = "稍等一下，桌面端正在从本地 API 获取榜单数据。",
                            loading = true,
                        )
                    }
                }

                uiState.charts.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChartsEmptyState(
                            title = "暂无榜单结果",
                            detail = "当前还没有可展示的排行榜结果，可以尝试刷新一次。",
                            loading = false,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = uiState.charts.items,
                            key = { item -> "${item.rank}:${item.sourceId ?: item.searchKeyword}" },
                        ) { item ->
                            DesktopChartRow(
                                item = item,
                                onSearchSong = { onSearchSong(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartsHeader(
    sourceLabel: String,
    selectedRegionLabel: String,
    itemCount: Int,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "今日排行榜",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "来源：$sourceLabel  ·  地区：$selectedRegionLabel  ·  共 $itemCount 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
        ) {
            Text(text = if (isRefreshing) "刷新中…" else "刷新")
        }
    }
}

@Composable
private fun ChartsSummaryCard(
    title: String,
    selectedRegionLabel: String,
    fromCache: Boolean,
    updatedAt: String?,
    itemCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ChartsFact(label = "地区", value = selectedRegionLabel)
            ChartsFact(label = "结果", value = "$itemCount 首")
            ChartsFact(label = "缓存", value = if (fromCache) "是" else "否")
            updatedAt?.let {
                ChartsFact(label = "更新时间", value = formatChartUpdatedAt(it))
            }
        }
    }
}

@Composable
private fun ChartsFact(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ChartsInsetPanel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                content()
            },
        )
    }
}

private enum class ChartsNoticeTone {
    ERROR,
}

@Composable
private fun ChartsNoticeBanner(
    message: String,
    tone: ChartsNoticeTone,
) {
    val containerColor = when (tone) {
        ChartsNoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        ChartsNoticeTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ChartsEmptyState(
    title: String,
    detail: String,
    loading: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loading) {
            CircularProgressIndicator()
        }
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

@Composable
private fun DesktopChartRow(
    item: ChartItem,
    onSearchSong: () -> Unit,
) {
    val rowBrush = when (item.rank) {
        1 -> Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                MaterialTheme.colorScheme.surface,
            ),
        )
        2 -> Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f),
                MaterialTheme.colorScheme.surface,
            ),
        )
        3 -> Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.82f),
                MaterialTheme.colorScheme.surface,
            ),
        )
        else -> Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                MaterialTheme.colorScheme.surface,
            ),
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBrush)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartRankBadge(rank = item.rank)
            ChartCover(coverUrl = item.cover, title = item.title)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.album?.takeIf { it.isNotBlank() }?.let { album ->
                    Text(
                        text = album,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.searchKeyword,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onSearchSong,
            ) {
                Text(text = "搜索这首歌")
            }
        }
    }
}

@Composable
private fun ChartRankBadge(rank: Int) {
    val backgroundColor = when (rank) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (rank) {
        1 -> MaterialTheme.colorScheme.onPrimary
        2 -> MaterialTheme.colorScheme.onSecondary
        3 -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

@Composable
private fun ChartCover(
    coverUrl: String?,
    title: String,
) {
    val image = rememberChartCoverImage(coverUrl)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = title.take(1).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun rememberChartCoverImage(coverUrl: String?): ImageBitmap? {
    val normalizedUrl = remember(coverUrl) { coverUrl?.trim().orEmpty() }
    val cachedImage = if (normalizedUrl.isBlank()) null else ChartCoverCache[normalizedUrl]
    val image by produceState(
        initialValue = cachedImage,
        key1 = normalizedUrl,
    ) {
        if (normalizedUrl.isBlank()) {
            value = null
            return@produceState
        }
        if (value != null || ChartCoverCache.containsKey(normalizedUrl) || ChartCoverFailedUrls.contains(normalizedUrl)) {
            return@produceState
        }

        value = loadChartCoverImage(normalizedUrl)
    }
    return image
}

private suspend fun loadChartCoverImage(url: String): ImageBitmap? {
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
            DesktopFileLogger.warn("chart cover load failed url=$url err=${error.message.orEmpty()}")
        }.getOrNull()

        if (image != null) {
            ChartCoverFailedUrls.remove(url)
            ChartCoverCache[url] = image
        } else {
            ChartCoverFailedUrls.add(url)
        }
        image
    }
}

private fun formatChartUpdatedAt(updatedAt: String): String {
    return runCatching {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        formatter.format(Instant.parse(updatedAt))
    }.getOrDefault(updatedAt)
}
