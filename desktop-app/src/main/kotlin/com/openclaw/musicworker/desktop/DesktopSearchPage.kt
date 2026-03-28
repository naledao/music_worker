package com.openclaw.musicworker.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.SearchItem

private val SearchPanelWidth = 272.dp

@Composable
internal fun SearchDownloadPage(
    uiState: DesktopUiState,
    hasActiveDownload: Boolean,
    onSearchInputChanged: (String) -> Unit,
    onSelectResult: (String) -> Unit,
    onSortModeChanged: (SearchSortMode) -> Unit,
    onFilterModeChanged: (SearchFilterMode) -> Unit,
    onSearch: () -> Unit,
    onStartDownload: (SearchItem) -> Unit,
) {
    val visibleResults = uiState.search.visibleResults
    val selectedItem = visibleResults.firstOrNull { it.id == uiState.search.selectedResultId }
    var previewCover by remember { mutableStateOf<SearchCoverPreview?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchSidebar(
                uiState = uiState,
                visibleResults = visibleResults,
                onSearchInputChanged = onSearchInputChanged,
                onSearch = onSearch,
            )

            SearchResultsPanel(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                uiState = uiState,
                visibleResults = visibleResults,
                selectedItem = selectedItem,
                hasActiveDownload = hasActiveDownload,
                onSelectResult = onSelectResult,
                onSortModeChanged = onSortModeChanged,
                onFilterModeChanged = onFilterModeChanged,
                onStartDownload = onStartDownload,
                onPreviewCover = { previewCover = it },
            )
        }

        previewCover?.let { preview ->
            SearchCoverPreviewOverlay(
                preview = preview,
                onDismiss = { previewCover = null },
            )
        }
    }
}

@Composable
private fun SearchSidebar(
    uiState: DesktopUiState,
    visibleResults: List<SearchItem>,
    onSearchInputChanged: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(SearchPanelWidth)
            .fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "搜索面板",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "左侧输入关键词，中间直接看结果并发起下载。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.search.input,
                onValueChange = onSearchInputChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                label = { Text("关键词") },
                placeholder = { Text("例如：King Gnu 飞行艇") },
            )
            FilledTonalButton(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.search.isSearching,
            ) {
                Text(text = if (uiState.search.isSearching) "搜索中…" else "开始搜索")
            }
            HorizontalDivider()
            SearchSidebarSummary(
                apiBaseUrl = uiState.serverConfig.baseUrl,
                activeKeyword = uiState.search.activeKeyword,
                totalCount = uiState.search.results.size,
                visibleCount = visibleResults.size,
            )
            uiState.search.errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsPanel(
    modifier: Modifier = Modifier,
    uiState: DesktopUiState,
    visibleResults: List<SearchItem>,
    selectedItem: SearchItem?,
    hasActiveDownload: Boolean,
    onSelectResult: (String) -> Unit,
    onSortModeChanged: (SearchSortMode) -> Unit,
    onFilterModeChanged: (SearchFilterMode) -> Unit,
    onStartDownload: (SearchItem) -> Unit,
    onPreviewCover: (SearchCoverPreview) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchResultsToolbar(
                totalCount = uiState.search.results.size,
                visibleCount = visibleResults.size,
                hasActiveDownload = hasActiveDownload,
                sortMode = uiState.search.sortMode,
                filterMode = uiState.search.filterMode,
                onSortModeChanged = onSortModeChanged,
                onFilterModeChanged = onFilterModeChanged,
            )

            SearchContextStrip(
                activeKeyword = uiState.search.activeKeyword,
                selectedItem = selectedItem,
                currentTask = uiState.download.currentTask,
                isStarting = uiState.download.isStarting,
            )

            when {
                uiState.search.results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SearchEmptyState(
                            title = "输入关键词后开始搜索",
                            detail = "结果区已经切成桌面列表布局，搜索后会直接显示在这里。",
                        )
                    }
                }

                visibleResults.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SearchEmptyState(
                            title = "当前过滤条件下没有结果",
                            detail = "已返回 ${uiState.search.results.size} 条结果，请调整排序或过滤条件后继续查看。",
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SearchResultHeaderRow()
                        SearchResultsList(
                            items = visibleResults,
                            selectedResultId = uiState.search.selectedResultId,
                            currentTask = uiState.download.currentTask,
                            isStarting = uiState.download.isStarting,
                            hasActiveDownload = hasActiveDownload,
                            onSelectResult = onSelectResult,
                            onStartDownload = onStartDownload,
                            onPreviewCover = onPreviewCover,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsToolbar(
    totalCount: Int,
    visibleCount: Int,
    hasActiveDownload: Boolean,
    sortMode: SearchSortMode,
    filterMode: SearchFilterMode,
    onSortModeChanged: (SearchSortMode) -> Unit,
    onFilterModeChanged: (SearchFilterMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "搜索结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    totalCount == 0 -> "还没有搜索结果。"
                    visibleCount == 0 -> "当前过滤条件下没有可显示结果。"
                    visibleCount == totalCount -> "共 $totalCount 条结果，右侧任务面板会持续显示下载进度。"
                    else -> "显示 $visibleCount / $totalCount 条结果，右侧任务面板会持续显示下载进度。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SearchMetaChip(
            label = "结果数",
            value = if (visibleCount == totalCount) totalCount.toString() else "$visibleCount/$totalCount",
        )
        SearchToolbarMenu(
            label = "排序",
            value = sortMode.label,
            options = SearchSortMode.values().toList(),
            selectedOption = sortMode,
            optionLabel = { it.label },
            onSelect = onSortModeChanged,
        )
        SearchToolbarMenu(
            label = "过滤",
            value = filterMode.label,
            options = SearchFilterMode.values().toList(),
            selectedOption = filterMode,
            optionLabel = { it.label },
            onSelect = onFilterModeChanged,
        )
        SearchMetaChip(
            label = "主流程",
            value = if (hasActiveDownload) "任务进行中" else "等待发起",
        )
    }
}
