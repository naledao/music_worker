package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.data.settings.ApiServerConfig
import com.openclaw.musicworker.ui.SettingsUiState

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    serverConfig: ApiServerConfig,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onRefresh: () -> Unit,
    onProxySelect: (String) -> Unit,
    onPickDownloadDirectory: () -> Unit,
    onClearDownloadDirectory: () -> Unit,
    onClearPrivateStorage: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onDownloadAndInstallAppUpdate: () -> Unit,
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "本地 API 配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(text = "当前地址：${serverConfig.baseUrl}")
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = onHostChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Host") },
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = onPortChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("端口") },
                    )
                    Button(onClick = onSaveConfig, modifier = Modifier.fillMaxWidth()) {
                        Text(text = "保存并刷新")
                    }
                    state.message?.let { Text(text = it, color = MaterialTheme.colorScheme.primary) }
                    state.errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "App 更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "当前版本：${state.installedAppVersionName} (${state.installedAppVersionCode})")
                    state.availableAppUpdate?.let { update ->
                        Text(
                            text = "可用版本：${update.versionName ?: "unknown"} (${update.versionCode ?: 0})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "安装包：${update.fileName} / ${formatBytes(update.fileSize)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        update.sha256?.let { sha256 ->
                            Text(
                                text = "sha256：$sha256",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } ?: Text(
                        text = "还没有检查更新。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onCheckAppUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isCheckingAppUpdate && !state.isDownloadingAppUpdate,
                    ) {
                        Text(text = if (state.isCheckingAppUpdate) "检查中…" else "检查更新")
                    }
                    Button(
                        onClick = onDownloadAndInstallAppUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canDownloadOrInstallUpdate(state),
                    ) {
                        Text(
                            text = when {
                                state.isDownloadingAppUpdate -> "下载中…"
                                state.downloadedAppUpdateUri != null -> "安装已下载更新"
                                else -> "下载并安装更新"
                            },
                        )
                    }
                    if (state.isDownloadingAppUpdate) {
                        val progressFraction = state.appUpdateTotalBytes
                            ?.takeIf { it > 0L }
                            ?.let { totalBytes ->
                                (state.appUpdateDownloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            }
                        if (progressFraction != null) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "下载进度：${(progressFraction * 100).toInt()}%  " +
                                    "${formatBytes(state.appUpdateDownloadedBytes)} / ${formatBytes(state.appUpdateTotalBytes ?: 0L)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "已下载：${formatBytes(state.appUpdateDownloadedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "应用私有目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "当前可清理空间：${formatBytes(state.privateStorageBytes)}")
                    Text(
                        text = "会清理应用自己的 files / cache / code_cache 等私有目录，不会删除设置项，也不会删除你选择的下载目录。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onClearPrivateStorage,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isCleaningPrivateStorage,
                    ) {
                        Text(text = if (state.isCleaningPrivateStorage) "清理中…" else "清理私有目录")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "下载目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "当前目录：${state.downloadDirectoryLabel ?: "未选择"}")
                    state.downloadDirectoryUri?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "下载完成后会直接写入这个目录，不再经过应用私有中转目录。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onPickDownloadDirectory, modifier = Modifier.fillMaxWidth()) {
                        Text(text = "选择下载目录")
                    }
                    if (state.downloadDirectoryUri != null) {
                        Button(onClick = onClearDownloadDirectory, modifier = Modifier.fillMaxWidth()) {
                            Text(text = "清除下载目录")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "代理节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "当前节点：${state.proxy?.name.orEmpty()}")
                    Text(text = "分组：${state.proxy?.selector.orEmpty()}")
                    Text(
                        text = "先展示部分候选节点，后续再换成完整搜索/选择器。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.proxy?.options.orEmpty().take(8).forEach { name ->
                        Button(
                            onClick = { onProxySelect(name) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = name)
                        }
                    }
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(text = "刷新代理与日志")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "最近日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (state.logs.isEmpty()) {
                        Text(text = "暂无日志。")
                    }
                }
            }
        }

        items(state.logs.takeLast(20).reversed()) { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun canDownloadOrInstallUpdate(state: SettingsUiState): Boolean {
    if (state.isCheckingAppUpdate || state.isDownloadingAppUpdate) {
        return false
    }
    if (state.downloadedAppUpdateUri != null) {
        return true
    }
    val updateInfo = state.availableAppUpdate ?: return false
    return updateInfo.versionCode == null || updateInfo.versionCode > state.installedAppVersionCode
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) {
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
        String.format("%.1f %s", value, units[index])
    }
}
