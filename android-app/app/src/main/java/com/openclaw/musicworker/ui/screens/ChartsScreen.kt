package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.openclaw.musicworker.data.api.ChartItem
import com.openclaw.musicworker.data.api.ChartRegionInfo
import com.openclaw.musicworker.ui.ChartsUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChartsScreen(
    state: ChartsUiState,
    onRefresh: () -> Unit,
    onSelectRegion: (String) -> Unit,
    onSearchSong: (ChartItem) -> Unit,
) {
    val currentSource = state.availableSources.firstOrNull { it.id == state.selectedSource }
    val currentRegions = currentSource?.regions.orEmpty()
    val selectedRegionLabel = localizeRegionLabel(
        regionId = state.selectedRegion,
        label = currentRegions.firstOrNull { it.id == state.selectedRegion }?.label,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ChartsSummaryCard(
                state = state,
                selectedRegionLabel = selectedRegionLabel,
                sourceLabel = currentSource?.label ?: "Apple Music",
                onRefresh = onRefresh,
            )
        }

        if (currentRegions.isNotEmpty()) {
            item {
                RegionSelectorCard(
                    regions = currentRegions,
                    selectedRegion = state.selectedRegion,
                    onSelectRegion = onSelectRegion,
                )
            }
        }

        when {
            state.isLoading && state.items.isEmpty() -> {
                item {
                    LoadingCard()
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                item {
                    FullStateCard(
                        title = "排行榜加载失败",
                        body = state.errorMessage,
                        actionLabel = "重新加载",
                        onAction = onRefresh,
                    )
                }
            }

            state.items.isEmpty() -> {
                item {
                    FullStateCard(
                        title = "暂无榜单数据",
                        body = "当前还没有拿到可展示的排行榜结果。",
                        actionLabel = "刷新榜单",
                        onAction = onRefresh,
                    )
                }
            }
        }

        if (state.errorMessage != null && state.items.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.errorMessage,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        items(
            items = state.items,
            key = { item -> "${item.rank}:${item.sourceId ?: item.searchKeyword}" },
        ) { item ->
            ChartListItem(
                item = item,
                onSearchSong = { onSearchSong(item) },
            )
        }
    }
}

@Composable
private fun ChartsSummaryCard(
    state: ChartsUiState,
    selectedRegionLabel: String,
    sourceLabel: String,
    onRefresh: () -> Unit,
) {
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        ),
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .background(backgroundBrush)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "今日排行榜",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = localizeChartTitle(
                            title = state.title,
                            sourceLabel = sourceLabel,
                            selectedRegionLabel = selectedRegionLabel,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                CacheBadge(fromCache = state.fromCache)
            }

            Text(
                text = "地区：$selectedRegionLabel  ·  来源：$sourceLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "榜单歌曲会自动带入当前 YouTube 搜索链路，点一下就能直接搜索。",
                style = MaterialTheme.typography.bodyMedium,
            )

            state.updatedAt?.let { updatedAt ->
                Text(
                    text = "更新时间：${formatUpdatedAt(updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = !state.isLoading && !state.isRefreshing,
                ) {
                    Text(
                        text = when {
                            state.isRefreshing -> "刷新中..."
                            state.isLoading -> "加载中..."
                            else -> "刷新榜单"
                        },
                    )
                }

                if (state.isLoading || state.isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.4.dp)
                }
            }

            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CacheBadge(fromCache: Boolean) {
    val backgroundColor = if (fromCache) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
    }
    val contentColor = if (fromCache) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (fromCache) "缓存结果" else "实时结果",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RegionSelectorCard(
    regions: List<ChartRegionInfo>,
    selectedRegion: String,
    onSelectRegion: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "切换地区",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(regions, key = { it.id }) { region ->
                    FilterChip(
                        selected = region.id == selectedRegion,
                        onClick = { onSelectRegion(region.id) },
                        label = { Text(localizeRegionLabel(region.id, region.label)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在加载排行榜...",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FullStateCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ChartListItem(
    item: ChartItem,
    onSearchSong: () -> Unit,
) {
    val containerColor = when (item.rank) {
        1 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        2 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        3 -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RankBadge(rank = item.rank)

            ChartCover(
                coverUrl = item.cover,
                title = item.title,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.album?.takeIf { it.isNotBlank() }?.let { album ->
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.durationSec?.let { durationSec ->
                    Text(
                        text = "时长：${formatDuration(durationSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "搜索关键词：${item.searchKeyword}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onSearchSong,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("搜索这首歌")
                }
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int) {
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
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isNullOrBlank()) {
            CoverPlaceholder(
                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                contentDescription = "暂无封面",
            )
            return
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            loading = {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.2.dp)
            },
            error = {
                CoverPlaceholder(
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentDescription = "封面加载失败",
                    broken = true,
                )
            },
        )
    }
}

@Composable
private fun CoverPlaceholder(
    iconTint: Color,
    contentDescription: String,
    broken: Boolean = false,
) {
    androidx.compose.material3.Icon(
        imageVector = if (broken) Icons.Outlined.BrokenImage else Icons.Outlined.MusicNote,
        contentDescription = contentDescription,
        tint = iconTint,
        modifier = Modifier.size(28.dp),
    )
}

private fun formatUpdatedAt(updatedAt: String): String {
    return runCatching {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        formatter.format(Instant.parse(updatedAt))
    }.getOrDefault(updatedAt)
}

private fun formatDuration(durationSec: Double): String {
    val totalSeconds = durationSec.toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun localizeRegionLabel(regionId: String, label: String? = null): String {
    val normalizedId = regionId.trim().lowercase(Locale.US)
    val fallback = when (normalizedId) {
        "us" -> "美国"
        "jp" -> "日本"
        "gb" -> "英国"
        "kr" -> "韩国"
        "cn" -> "中国"
        else -> normalizedId.uppercase(Locale.US)
    }

    val normalizedLabel = label?.trim().orEmpty()
    if (normalizedLabel.isBlank()) {
        return fallback
    }

    return when (normalizedLabel.lowercase(Locale.US)) {
        "usa", "united states" -> "美国"
        "japan" -> "日本"
        "uk", "united kingdom", "great britain" -> "英国"
        "korea", "south korea" -> "韩国"
        "china" -> "中国"
        else -> normalizedLabel
    }
}

private fun localizeChartTitle(
    title: String,
    sourceLabel: String,
    selectedRegionLabel: String,
): String {
    val normalizedTitle = title.trim()
    if (normalizedTitle.isBlank()) {
        return "$sourceLabel 热门歌曲（$selectedRegionLabel）"
    }

    return normalizedTitle
        .replace("Top Songs", "热门歌曲")
        .replace("(USA)", "（美国）")
        .replace("(Japan)", "（日本）")
        .replace("(UK)", "（英国）")
        .replace("(Korea)", "（韩国）")
        .replace("(China)", "（中国）")
}
