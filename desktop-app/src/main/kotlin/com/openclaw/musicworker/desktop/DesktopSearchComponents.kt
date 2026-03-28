package com.openclaw.musicworker.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.SearchItem

private val SearchDurationColumnWidth = 68.dp
private val SearchStatusColumnWidth = 92.dp
private val SearchActionColumnWidth = 100.dp

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
) {
    if (activeKeyword.isBlank() && selectedItem == null) {
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
                SearchCompactBadge(value = buildSearchItemStatusText(item, currentTask, isStarting))
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
    hasActiveDownload: Boolean,
    onSelectResult: (String) -> Unit,
    onStartDownload: (SearchItem) -> Unit,
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
                hasActiveDownload = hasActiveDownload,
                onSelect = { onSelectResult(item.id) },
                onStartDownload = { onStartDownload(item) },
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
    hasActiveDownload: Boolean,
    onSelect: () -> Unit,
    onStartDownload: () -> Unit,
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
                value = buildSearchItemStatusText(item, currentTask, isStarting),
                modifier = Modifier.width(SearchStatusColumnWidth),
            )

            SearchResultActionButton(
                isEmphasized = isSelected || currentTask?.musicId == item.id,
                onClick = onStartDownload,
                enabled = !hasActiveDownload || currentTask?.musicId == item.id,
                modifier = Modifier.width(SearchActionColumnWidth),
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
        currentTask?.musicId == item.id && currentTask.status == "finished" -> "重新下载"
        currentTask?.musicId == item.id && currentTask.status == "failed" -> "重试下载"
        else -> "开始下载"
    }
}

private fun buildSearchItemStatusText(
    item: SearchItem,
    currentTask: DownloadTask?,
    isStarting: Boolean,
): String {
    if (isStarting && currentTask == null) {
        return "准备中"
    }

    if (currentTask?.musicId != item.id) {
        return "待下载"
    }

    return when (currentTask.status) {
        "queued" -> "排队中"
        "running" -> "下载中"
        "finished" -> "已完成"
        "failed" -> "失败"
        else -> currentTask.status.ifBlank { "未知" }
    }
}
