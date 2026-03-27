package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.musicworker.data.settings.ApiServerConfig
import com.openclaw.musicworker.ui.DownloadUiState
import com.openclaw.musicworker.ui.HomeUiState

@Composable
fun HomeScreen(
    state: HomeUiState,
    downloadState: DownloadUiState,
    serverConfig: ApiServerConfig,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "本地 API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = serverConfig.baseUrl, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onRefresh) {
                    Text(text = "刷新状态")
                }
            }
        }

        when {
            state.isLoading -> {
                CircularProgressIndicator()
            }

            state.errorMessage != null -> {
                StatusCard(title = "健康检查失败", body = state.errorMessage)
            }

            state.health != null -> {
                val health = state.health
                StatusCard(
                    title = "服务在线",
                    body = "服务名：${health.service.name}\n" +
                        "监听：${health.service.host}:${health.service.port}\n" +
                        "当前节点：${health.proxy.name.orEmpty()}\n" +
                        "Cookie：${if (health.runtime.cookies?.enabled == true) "已启用" else "未启用"}\n" +
                        "FFmpeg：${health.runtime.ffmpeg.orEmpty()}",
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(text = "任务统计", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "总数 ${health.tasks.total}  排队 ${health.tasks.queued}  运行 ${health.tasks.running}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "完成 ${health.tasks.finished}  失败 ${health.tasks.failed}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "最近下载", style = MaterialTheme.typography.titleMedium)
                if (downloadState.currentTask == null) {
                    Text(text = "还没有发起新的下载任务。")
                } else {
                    Text(text = downloadState.selectedTitle ?: downloadState.currentTask.musicId)
                    Text(text = "状态：${downloadState.currentTask.status} / ${downloadState.currentTask.stage}")
                    Text(text = "进度：${downloadState.currentTask.progress}%")
                    downloadState.currentTask.filename?.let { Text(text = "文件：$it") }
                    downloadState.currentTask.errorMessage?.let { Text(text = "错误：$it") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
