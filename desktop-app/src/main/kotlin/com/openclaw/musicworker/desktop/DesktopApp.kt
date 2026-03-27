package com.openclaw.musicworker.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.shared.api.DownloadTask
import com.openclaw.musicworker.shared.api.SearchItem

@Composable
fun DesktopApp() {
    val appState = remember { DesktopAppState() }
    val uiState by appState.uiState.collectAsState()
    val hasActiveDownload = uiState.download.isStarting || uiState.download.currentTask?.status in setOf("queued", "running")

    LaunchedEffect(appState) {
        appState.initialize()
    }

    DisposableEffect(appState) {
        onDispose {
            appState.close()
        }
    }

    MaterialTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(text = "音爪 Windows", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "当前桌面 MVP 已接通 health / search / download 任务轮询；桌面端当前只负责发起任务和查看服务端状态。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        uiState.message?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.primary)
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
                        Text(text = "桌面端更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(text = "当前版本：${uiState.update.currentVersionName} (${uiState.update.currentVersionCode})")
                        uiState.update.availableUpdate?.let { update ->
                            Text(
                                text = "可用版本：${update.versionName ?: "unknown"} (${update.versionCode ?: 0})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "安装包：${update.fileName} / ${formatBytes(update.fileSize)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            update.updatedAt?.let {
                                Text(text = "更新时间：$it", style = MaterialTheme.typography.bodySmall)
                            }
                            update.sha256?.let {
                                Text(text = "sha256：$it", style = MaterialTheme.typography.bodySmall)
                            }
                        } ?: Text(
                            text = "还没有检查桌面端更新。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = appState::checkAppUpdate,
                            enabled = !uiState.update.isChecking && !uiState.update.isDownloading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = if (uiState.update.isChecking) "检查中…" else "检查更新")
                        }
                        Button(
                            onClick = appState::downloadOrOpenAppUpdate,
                            enabled = canDownloadOrOpenUpdate(uiState.update),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = when {
                                    uiState.update.isDownloading -> "下载中…"
                                    uiState.update.downloadedInstallerPath != null -> "打开已下载安装包"
                                    else -> "下载安装包"
                                },
                            )
                        }
                        if (uiState.update.isDownloading) {
                            val progressFraction = uiState.update.totalBytes
                                ?.takeIf { it > 0L }
                                ?.let { totalBytes ->
                                    (uiState.update.downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                }
                            if (progressFraction != null) {
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "下载进度：${(progressFraction * 100).toInt()}%  " +
                                        "${formatBytes(uiState.update.downloadedBytes)} / ${formatBytes(uiState.update.totalBytes ?: 0L)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "已下载：${formatBytes(uiState.update.downloadedBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        uiState.update.downloadedInstallerPath?.let {
                            Text(text = "安装包路径：$it", style = MaterialTheme.typography.bodySmall)
                        }
                        uiState.update.message?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.primary)
                        }
                        uiState.update.errorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
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
                        Text(text = "代理与日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (uiState.ops.isLoading) {
                            CircularProgressIndicator()
                        }
                        Text(text = "当前节点：${uiState.ops.proxy?.name.orEmpty().ifBlank { "未知" }}")
                        Text(text = "分组：${uiState.ops.proxy?.selector.orEmpty().ifBlank { "未知" }}")
                        if (uiState.ops.proxy?.alive != null) {
                            Text(text = "可用性：${if (uiState.ops.proxy?.alive == true) "可用" else "不可用"}")
                        }
                        if (uiState.ops.proxy?.options.orEmpty().isEmpty()) {
                            Text(text = "暂无可切换节点。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(text = "候选节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            uiState.ops.proxy?.options.orEmpty().take(10).forEach { name ->
                                Button(
                                    onClick = { appState.selectProxy(name) },
                                    enabled = !uiState.ops.isLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = if (uiState.ops.proxy?.name == name) "$name (当前)" else name)
                                }
                            }
                        }
                        Button(
                            onClick = appState::refreshOperations,
                            enabled = !uiState.ops.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "刷新代理与日志")
                        }
                        uiState.ops.errorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                        Text(text = "最近日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (uiState.ops.logs.isEmpty()) {
                            Text(text = "暂无日志。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            uiState.ops.logs.takeLast(20).forEach { line ->
                                Text(text = line, style = MaterialTheme.typography.bodySmall)
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
                        Text(text = "当前下载任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (uiState.download.isStarting) {
                            CircularProgressIndicator()
                            Text(text = "正在创建下载任务…")
                        }
                        uiState.download.currentTask?.let { task ->
                            Text(text = uiState.download.selectedTitle ?: task.filename ?: task.musicId)
                            Text(text = "状态：${formatTaskStatus(task.status)} / ${formatTaskStage(task.stage)}")
                            LinearProgressIndicator(
                                progress = { task.progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(text = "进度：${task.progress.coerceIn(0, 100)}%")
                            formatTransferProgress(task)?.let {
                                Text(text = it, style = MaterialTheme.typography.bodySmall)
                            }
                            task.speedBps?.let {
                                Text(text = "速度：${formatSpeed(it)}")
                            }
                            task.etaSec?.let {
                                Text(text = "剩余时间：${formatEta(it)}")
                            }
                            task.strategy?.let {
                                Text(text = "策略：$it", style = MaterialTheme.typography.bodySmall)
                            }
                            task.filename?.let {
                                Text(text = "文件：$it", style = MaterialTheme.typography.bodySmall)
                            }
                            task.filePath?.let {
                                Text(text = "服务端路径：$it", style = MaterialTheme.typography.bodySmall)
                            }
                            if (task.status == "finished") {
                                Text(
                                    text = "文件已在服务端生成。桌面端当前不再负责导出到本机目录。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (!uiState.download.isStarting && uiState.download.currentTask == null && uiState.download.errorMessage == null) {
                            Text(text = "还没有下载任务。先从下方搜索结果里发起下载。")
                        }
                        uiState.download.errorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = appState::refreshLatestTask, modifier = Modifier.fillMaxWidth()) {
                            Text(text = "刷新任务状态")
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
                        Text(text = "本地 API 配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = uiState.serverConfig.host,
                            onValueChange = appState::updateHost,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Host") },
                        )
                        OutlinedTextField(
                            value = uiState.serverConfig.port.toString(),
                            onValueChange = appState::updatePort,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("端口") },
                        )
                        Text(text = "当前地址：${uiState.serverConfig.baseUrl}")
                        Button(onClick = appState::saveConfig, modifier = Modifier.fillMaxWidth()) {
                            Text(text = "保存并刷新健康状态")
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
                        Text(text = "健康检查", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (uiState.health.isLoading) {
                            CircularProgressIndicator()
                        }
                        uiState.health.health?.let { health ->
                            Text(text = "服务：${health.service.name}")
                            Text(text = "监听：${health.service.host}:${health.service.port}")
                            Text(text = "当前节点：${health.proxy.name.orEmpty()}")
                            Text(text = "Cookie：${if (health.runtime.cookies?.enabled == true) "已启用" else "未启用"}")
                            Text(text = "FFmpeg：${health.runtime.ffmpeg.orEmpty()}")
                        }
                        uiState.health.errorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = appState::refreshHealth, modifier = Modifier.fillMaxWidth()) {
                            Text(text = "重新检查")
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
                        Text(text = "搜索", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = uiState.search.input,
                            onValueChange = appState::updateSearchInput,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 3,
                            maxLines = 6,
                            label = { Text("关键词") },
                            placeholder = { Text("例如：King Gnu 飞行艇") },
                        )
                        Button(onClick = appState::search, modifier = Modifier.fillMaxWidth()) {
                            Text(text = if (uiState.search.isSearching) "搜索中…" else "搜索")
                        }
                        if (uiState.search.activeKeyword.isNotBlank()) {
                            Text(text = "当前关键词：${uiState.search.activeKeyword}")
                        }
                        uiState.search.errorMessage?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            items(uiState.search.results, key = { it.id }) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        item.channel?.let { Text(text = it) }
                        item.duration?.let { Text(text = "时长：${formatDuration(it)}") }
                        Button(
                            onClick = { appState.startDownload(item) },
                            enabled = !hasActiveDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = downloadButtonText(item = item, currentTask = uiState.download.currentTask, isStarting = uiState.download.isStarting))
                        }
                        Text(text = "当前阶段已接通下载任务创建与轮询，桌面端当前不负责把文件再拷贝到本机目录。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
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

private fun canDownloadOrOpenUpdate(updateState: DesktopUpdateUiState): Boolean {
    return !updateState.isChecking &&
        !updateState.isDownloading &&
        (updateState.downloadedInstallerPath != null || updateState.availableUpdate != null)
}

private fun formatTaskStatus(status: String): String {
    return when (status) {
        "queued" -> "排队中"
        "running" -> "下载中"
        "finished" -> "已完成"
        "failed" -> "失败"
        else -> status.ifBlank { "未知" }
    }
}

private fun formatTaskStage(stage: String): String {
    return when (stage) {
        "queued" -> "等待执行"
        "starting" -> "初始化"
        "attempt_start" -> "开始尝试"
        "downloading" -> "下载音频"
        "download_finished" -> "下载完成"
        "finished" -> "处理完成"
        "attempt_failed" -> "尝试失败"
        "failed" -> "任务失败"
        else -> stage.ifBlank { "未知阶段" }
    }
}

private fun formatTransferProgress(task: DownloadTask): String? {
    val downloaded = task.downloadedBytes.takeIf { it > 0L } ?: return null
    val total = task.totalBytes
    return if (total != null && total > 0L) {
        "已下载：${formatBytes(downloaded)} / ${formatBytes(total)}"
    } else {
        "已下载：${formatBytes(downloaded)}"
    }
}

private fun formatSpeed(speedBps: Double): String {
    if (speedBps <= 0.0) {
        return "0 B/s"
    }

    return "${formatBytes(speedBps.toLong())}/s"
}

private fun formatEta(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainSeconds = safeSeconds % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分 ${remainSeconds} 秒"
        minutes > 0 -> "${minutes} 分 ${remainSeconds} 秒"
        else -> "${remainSeconds} 秒"
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
        String.format("%.1f %s", value, units[index])
    }
}
