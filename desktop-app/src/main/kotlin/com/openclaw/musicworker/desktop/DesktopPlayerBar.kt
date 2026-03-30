package com.openclaw.musicworker.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private val PlaybackCoverCache = ConcurrentHashMap<String, ImageBitmap>()
private val PlaybackCoverFailedUrls = ConcurrentHashMap.newKeySet<String>()

@Composable
internal fun DesktopPlayerBar(
    playbackState: DesktopPlaybackUiState,
    onTogglePlayback: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!shouldShowPlayer(playbackState)) {
        return
    }

    var expanded by remember(playbackState.currentMusicId, playbackState.playRequestToken) { mutableStateOf(false) }
    val totalDurationMs = resolvePlaybackDurationMs(playbackState)
    var sliderValue by remember(playbackState.playRequestToken) { mutableFloatStateOf(0f) }
    var isSeeking by remember(playbackState.playRequestToken) { mutableStateOf(false) }

    LaunchedEffect(
        playbackState.playRequestToken,
        playbackState.currentPositionMs,
        playbackState.playbackDurationMs,
        playbackState.currentDurationSec,
        isSeeking,
    ) {
        if (!isSeeking) {
            sliderValue = if (totalDurationMs > 0L) {
                (playbackState.currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (expanded) {
            DesktopPlayerDetailPanel(
                playbackState = playbackState,
                totalDurationMs = totalDurationMs,
                sliderValue = sliderValue,
                onSliderValueChange = {
                    isSeeking = true
                    sliderValue = it
                },
                onSliderValueChangeFinished = {
                    val targetPositionMs = (totalDurationMs * sliderValue).roundToLong()
                    onSeekTo(targetPositionMs)
                    isSeeking = false
                },
                onTogglePlayback = onTogglePlayback,
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DesktopPlaybackCover(
                        coverUrl = playbackState.currentCoverUrl,
                        title = playbackState.currentTitle ?: "当前播放",
                        modifier = Modifier.clickable { expanded = !expanded },
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = playbackState.currentTitle ?: "正在准备播放",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = playbackState.currentChannel
                                ?: playbackState.message
                                ?: playbackState.errorMessage
                                ?: "服务端串流播放器",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (playbackState.errorMessage.isNullOrBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerMetaChip(label = buildPlayerStatusText(playbackState))
                            playbackState.currentTask?.let { task ->
                                PlayerMetaChip(label = "${task.status} · ${task.stage}")
                            }
                            if (totalDurationMs > 0L) {
                                PlayerMetaChip(
                                    label = "${formatPlaybackClock(playbackState.currentPositionMs)} / ${formatPlaybackClock(totalDurationMs)}",
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { expanded = !expanded }) {
                            Text(text = if (expanded) "收起详情" else "展开详情")
                        }
                        FilledTonalButton(
                            onClick = onTogglePlayback,
                            enabled = playbackToggleEnabled(playbackState),
                        ) {
                            Text(text = playbackToggleText(playbackState))
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text(text = "关闭")
                        }
                    }
                }

                if (playbackState.isPreparing && playbackState.currentTask != null) {
                    LinearProgressIndicator(
                        progress = { playbackState.currentTask.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (totalDurationMs > 0L) {
                    LinearProgressIndicator(
                        progress = {
                            (playbackState.currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (playbackState.isPreparing || playbackState.isBuffering) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DesktopPlayerDetailPanel(
    playbackState: DesktopPlaybackUiState,
    totalDurationMs: Long,
    sliderValue: Float,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopPlaybackCover(
                coverUrl = playbackState.currentCoverUrl,
                title = playbackState.currentTitle ?: "当前播放",
                size = 112.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = playbackState.currentTitle ?: "正在准备播放",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                playbackState.currentChannel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Slider(
                    value = sliderValue,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = onSliderValueChangeFinished,
                    enabled = playbackState.supportsSeeking &&
                        totalDurationMs > 0L &&
                        !playbackState.playbackUrl.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatPlaybackClock(playbackState.currentPositionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (totalDurationMs > 0L) formatPlaybackClock(totalDurationMs) else "--:--",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onTogglePlayback,
                        enabled = playbackToggleEnabled(playbackState),
                    ) {
                        Text(text = playbackToggleText(playbackState))
                    }
                    PlayerMetaChip(label = buildPlayerStatusText(playbackState))
                    if (!playbackState.playbackUrl.isNullOrBlank()) {
                        PlayerMetaChip(label = "服务端串流")
                    }
                }

                if (!playbackState.playbackUrl.isNullOrBlank() && !playbackState.supportsSeeking) {
                    Text(
                        text = "当前桌面串流播放暂不支持拖动定位",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                playbackState.currentTask?.let { task ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerDetailMetric(label = "任务进度", value = "${task.progress.coerceIn(0, 100)}%")
                        task.speedBps?.let {
                            PlayerDetailMetric(label = "准备速度", value = formatPlaybackSpeed(it))
                        }
                        task.filename?.takeIf { it.isNotBlank() }?.let {
                            PlayerDetailMetric(label = "输出文件", value = it)
                        }
                    }
                }

                playbackState.message?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                playbackState.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopPlaybackCover(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val image = rememberPlaybackCoverImage(coverUrl)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Box(
            modifier = Modifier.size(size),
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = buildPlaybackFallbackText(title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerMetaChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerDetailMetric(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberPlaybackCoverImage(coverUrl: String?): ImageBitmap? {
    val normalizedUrl = remember(coverUrl) { coverUrl?.trim().orEmpty() }
    val cachedImage = if (normalizedUrl.isBlank()) null else PlaybackCoverCache[normalizedUrl]
    val image by produceState(
        initialValue = cachedImage,
        key1 = normalizedUrl,
    ) {
        if (normalizedUrl.isBlank()) {
            value = null
            return@produceState
        }
        if (value != null || PlaybackCoverCache.containsKey(normalizedUrl) || PlaybackCoverFailedUrls.contains(normalizedUrl)) {
            return@produceState
        }
        value = loadPlaybackCoverImage(normalizedUrl)
    }
    return image
}

private suspend fun loadPlaybackCoverImage(url: String): ImageBitmap? {
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
            DesktopFileLogger.warn("playback cover load failed url=$url err=${error.message.orEmpty()}")
        }.getOrNull()

        if (image != null) {
            PlaybackCoverFailedUrls.remove(url)
            PlaybackCoverCache[url] = image
        } else {
            PlaybackCoverFailedUrls.add(url)
        }
        image
    }
}

private fun shouldShowPlayer(playbackState: DesktopPlaybackUiState): Boolean {
    return playbackState.currentMusicId != null ||
        !playbackState.currentTitle.isNullOrBlank() ||
        playbackState.isPreparing ||
        playbackState.isBuffering ||
        playbackState.isPlaying ||
        !playbackState.playbackUrl.isNullOrBlank() ||
        !playbackState.errorMessage.isNullOrBlank()
}

private fun resolvePlaybackDurationMs(playbackState: DesktopPlaybackUiState): Long {
    return playbackState.playbackDurationMs
        ?: playbackState.currentDurationSec
            ?.takeIf { it > 0 }
            ?.times(1000.0)
            ?.roundToLong()
        ?: 0L
}

private fun playbackToggleEnabled(playbackState: DesktopPlaybackUiState): Boolean {
    return !playbackState.playbackUrl.isNullOrBlank() && !playbackState.isPreparing
}

private fun playbackToggleText(playbackState: DesktopPlaybackUiState): String {
    return when {
        playbackState.isPreparing -> "准备中…"
        playbackState.isBuffering -> "缓冲中…"
        playbackState.isPlaying -> "暂停"
        playbackState.errorMessage != null && !playbackState.playbackUrl.isNullOrBlank() -> "重试播放"
        !playbackState.playbackUrl.isNullOrBlank() -> "播放"
        else -> "等待音频"
    }
}

private fun buildPlayerStatusText(playbackState: DesktopPlaybackUiState): String {
    return when {
        !playbackState.errorMessage.isNullOrBlank() -> "播放失败"
        playbackState.isPreparing -> "准备播放"
        playbackState.isBuffering -> "缓冲中"
        playbackState.isPlaying -> "播放中"
        !playbackState.playbackUrl.isNullOrBlank() -> "待播放"
        else -> "空闲"
    }
}

private fun buildPlaybackFallbackText(title: String): String {
    val firstVisible = title.firstOrNull { !it.isWhitespace() } ?: return "♪"
    return firstVisible.uppercaseChar().toString()
}

private fun formatPlaybackClock(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatPlaybackSpeed(speedBps: Double): String {
    if (speedBps <= 0.0) {
        return "0 B/s"
    }
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = speedBps
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (value >= 100 || unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format("%.1f %s", value, units[unitIndex])
    }
}
