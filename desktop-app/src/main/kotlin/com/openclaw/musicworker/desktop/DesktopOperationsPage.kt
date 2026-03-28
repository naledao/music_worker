package com.openclaw.musicworker.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.ProxyInfo

private val OperationsSidebarWidth = 384.dp

private enum class OperationsLogFilter(val label: String) {
    ALL("全部日志"),
    ERRORS("仅错误"),
}

@Composable
internal fun OperationsPage(
    uiState: DesktopUiState,
    onRefresh: () -> Unit,
    onRequestProxySelection: (String) -> Unit,
    onDismissProxySelection: () -> Unit,
    onProxyPasswordChanged: (String) -> Unit,
    onConfirmProxySelection: () -> Unit,
) {
    var proxyQuery by remember { mutableStateOf("") }
    var logFilter by remember { mutableStateOf(OperationsLogFilter.ALL) }

    val allOptions = uiState.ops.proxy?.options.orEmpty()
    val currentProxyName = uiState.ops.proxy?.name
    val filteredOptions = allOptions
        .filter { option ->
            proxyQuery.isBlank() || option.contains(proxyQuery.trim(), ignoreCase = true)
        }
        .sortedBy { option ->
            if (option == currentProxyName) 0 else 1
        }
    val errorLineCount = uiState.ops.logs.count(::isErrorLogLine)
    val latestErrorLine = uiState.ops.logs.lastOrNull(::isErrorLogLine)
    val visibleLogLines = when (logFilter) {
        OperationsLogFilter.ALL -> uiState.ops.logs
        OperationsLogFilter.ERRORS -> uiState.ops.logs.filter(::isErrorLogLine)
    }

    uiState.ops.pendingProxyName?.let { pendingProxyName ->
        ProxySwitchPasswordDialog(
            proxyName = pendingProxyName,
            password = uiState.ops.proxyPasswordInput,
            errorMessage = uiState.ops.proxyPasswordError,
            isLoading = uiState.ops.isLoading,
            onPasswordChanged = onProxyPasswordChanged,
            onDismiss = onDismissProxySelection,
            onConfirm = onConfirmProxySelection,
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .width(OperationsSidebarWidth)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OperationsSummaryCard(
                proxy = uiState.ops.proxy,
                errorMessage = uiState.ops.errorMessage,
                isLoading = uiState.ops.isLoading,
                errorLineCount = errorLineCount,
                onRefresh = onRefresh,
            )

            OperationsProxyListCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                currentProxy = uiState.ops.proxy,
                proxyQuery = proxyQuery,
                filteredOptions = filteredOptions,
                totalOptions = allOptions.size,
                isLoading = uiState.ops.isLoading,
                onProxyQueryChanged = { proxyQuery = it },
                onSelectProxy = onRequestProxySelection,
            )
        }

        OperationsLogCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            logs = visibleLogLines,
            totalLogCount = uiState.ops.logs.size,
            errorLogCount = errorLineCount,
            latestErrorLine = latestErrorLine,
            logFilter = logFilter,
            onLogFilterChanged = { logFilter = it },
        )
    }
}

@Composable
private fun ProxySwitchPasswordDialog(
    proxyName: String,
    password: String,
    errorMessage: String?,
    isLoading: Boolean,
    onPasswordChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(
                text = "验证节点切换",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "切换到节点“$proxyName”前需要输入桌面端验证密码。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("切换密码") },
                    placeholder = { Text("输入服务端生成的密码") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = !errorMessage.isNullOrBlank(),
                    supportingText = {
                        if (!errorMessage.isNullOrBlank()) {
                            Text(text = errorMessage)
                        }
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text(text = "取消")
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                enabled = !isLoading,
            ) {
                Text(text = if (isLoading) "验证中…" else "验证并切换")
            }
        },
    )
}

@Composable
private fun OperationsSummaryCard(
    proxy: ProxyInfo?,
    errorMessage: String?,
    isLoading: Boolean,
    errorLineCount: Int,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "代理控制",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "这里保留节点切换和排障摘要，日志查看放到右侧独立面板。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OperationsCurrentProxyBanner(proxy = proxy)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OperationsMetaChip(
                    label = "当前节点",
                    value = proxy?.name.orEmpty().ifBlank { "未知" },
                    modifier = Modifier.weight(1f),
                )
                OperationsMetaChip(
                    label = "分组",
                    value = proxy?.selector.orEmpty().ifBlank { "未知" },
                    modifier = Modifier.weight(1f),
                )
                OperationsMetaChip(
                    label = "错误行",
                    value = errorLineCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OperationsMetaChip(
                    label = "可用性",
                    value = when (proxy?.alive) {
                        true -> "可用"
                        false -> "异常"
                        null -> "未知"
                    },
                    modifier = Modifier.weight(1f),
                )
                OperationsMetaChip(
                    label = "节点数",
                    value = proxy?.options.orEmpty().size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            FilledTonalButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = if (isLoading) "刷新中…" else "刷新代理与日志")
            }
        }
    }
}

@Composable
private fun OperationsProxyListCard(
    modifier: Modifier = Modifier,
    currentProxy: ProxyInfo?,
    proxyQuery: String,
    filteredOptions: List<String>,
    totalOptions: Int,
    isLoading: Boolean,
    onProxyQueryChanged: (String) -> Unit,
    onSelectProxy: (String) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "节点列表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (proxyQuery.isBlank()) {
                    "显示全部 $totalOptions 个节点。"
                } else {
                    "筛选后显示 ${filteredOptions.size} / $totalOptions 个节点。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = proxyQuery,
                onValueChange = onProxyQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("筛选节点") },
                placeholder = { Text("输入节点名称") },
            )

            if (filteredOptions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (totalOptions == 0) "暂无可切换节点。" else "没有匹配的节点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filteredOptions, key = { it }) { option ->
                        ProxyOptionRow(
                            name = option,
                            isCurrent = currentProxy?.name == option,
                            isLoading = isLoading,
                            selector = currentProxy?.selector,
                            onSelect = { onSelectProxy(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyOptionRow(
    name: String,
    isCurrent: Boolean,
    isLoading: Boolean,
    selector: String?,
    onSelect: () -> Unit,
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (isCurrent) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrent && !isLoading, onClick = onSelect),
        color = containerColor,
        border = border,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (isCurrent) {
                    OperationsInlineBadge(
                        text = "当前节点",
                        emphasized = true,
                    )
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isCurrent) {
                        "当前节点 · ${selector.orEmpty().ifBlank { "默认分组" }}"
                    } else {
                        "点击切换到该节点"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isCurrent) {
                OperationsInlineBadge(text = "当前", emphasized = true)
            } else {
                OutlinedButton(
                    onClick = onSelect,
                    enabled = !isLoading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(text = "切换")
                }
            }
        }
    }
}

@Composable
private fun OperationsLogCard(
    modifier: Modifier = Modifier,
    logs: List<String>,
    totalLogCount: Int,
    errorLogCount: Int,
    latestErrorLine: String?,
    logFilter: OperationsLogFilter,
    onLogFilterChanged: (OperationsLogFilter) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "运行日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when (logFilter) {
                            OperationsLogFilter.ALL -> "查看最近 $totalLogCount 行日志。"
                            OperationsLogFilter.ERRORS -> "仅查看错误相关日志，共 ${logs.size} 行。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OperationsMetaChip(label = "总行数", value = totalLogCount.toString())
                OperationsMetaChip(label = "错误行", value = errorLogCount.toString())
            }

            OperationsLogSummaryBanner(
                latestErrorLine = latestErrorLine,
                visibleLogCount = logs.size,
                logFilter = logFilter,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OperationsFilterButton(
                    text = "${OperationsLogFilter.ALL.label} $totalLogCount",
                    selected = logFilter == OperationsLogFilter.ALL,
                    onClick = { onLogFilterChanged(OperationsLogFilter.ALL) },
                )
                OperationsFilterButton(
                    text = "${OperationsLogFilter.ERRORS.label} $errorLogCount",
                    selected = logFilter == OperationsLogFilter.ERRORS,
                    onClick = { onLogFilterChanged(OperationsLogFilter.ERRORS) },
                )
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (totalLogCount == 0) "暂无日志。" else "当前过滤条件下没有日志。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(logs.indices.toList(), key = { it }) { index ->
                                LogLineRow(
                                    index = index + 1,
                                    line = logs[index],
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(
    index: Int,
    line: String,
) {
    val isError = isErrorLogLine(line)
    val textColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = index.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isError) {
                OperationsInlineBadge(
                    text = "ERR",
                    emphasized = true,
                )
            }
            Text(
                text = line,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = textColor,
            )
        }
    }
}

@Composable
private fun OperationsCurrentProxyBanner(proxy: ProxyInfo?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "当前节点",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = proxy?.name.orEmpty().ifBlank { "未知" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(proxy?.selector.orEmpty().ifBlank { "默认分组" })
                    append(" · ")
                    append(
                        when (proxy?.alive) {
                            true -> "可用"
                            false -> "异常"
                            null -> "状态未知"
                        },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OperationsLogSummaryBanner(
    latestErrorLine: String?,
    visibleLogCount: Int,
    logFilter: OperationsLogFilter,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = when (logFilter) {
                    OperationsLogFilter.ALL -> "当前显示 $visibleLogCount 行日志。"
                    OperationsLogFilter.ERRORS -> "当前过滤为错误日志，共 $visibleLogCount 行。"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (latestErrorLine != null) {
                Text(
                    text = "最近错误：${compactLogLine(latestErrorLine)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = "最近日志里没有发现错误关键字。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OperationsMetaChip(
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
private fun OperationsInlineBadge(
    text: String,
    emphasized: Boolean,
) {
    Surface(
        color = if (emphasized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OperationsFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(onClick = onClick) {
            Text(text = text)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(text = text)
        }
    }
}

private fun isErrorLogLine(line: String): Boolean {
    val normalized = line.lowercase()
    return ERROR_LOG_KEYWORDS.any { keyword -> normalized.contains(keyword) }
}

private fun compactLogLine(line: String): String {
    val compact = line.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= 140) compact else compact.take(137) + "..."
}

private val ERROR_LOG_KEYWORDS = listOf(
    "error",
    "failed",
    "exception",
    "traceback",
    "timeout",
    "429",
    "403",
)
