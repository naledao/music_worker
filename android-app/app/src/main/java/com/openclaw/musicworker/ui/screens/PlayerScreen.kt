package com.openclaw.musicworker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.openclaw.musicworker.ui.PlaybackUiState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class PlayerSurfaceMode {
    COVER,
    LYRICS,
}

private data class TimedLyricLine(
    val timeMs: Long,
    val text: String,
)

private val lrcTimestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

@Composable
fun PlayerScreen(
    state: PlaybackUiState,
    player: Player,
    onDownloadSong: () -> Unit,
    onBack: () -> Unit,
) {
    if (
        state.currentTitle == null &&
        state.currentTask == null &&
        state.playbackUri.isNullOrBlank() &&
        !state.isPreparing &&
        state.errorMessage == null
    ) {
        EmptyPlayerScreen(onBack = onBack)
        return
    }

    var surfaceMode by remember(state.playRequestToken) { mutableStateOf(PlayerSurfaceMode.COVER) }
    var sliderValue by remember(state.playRequestToken) { mutableFloatStateOf(0f) }
    var isSeeking by remember(state.playRequestToken) { mutableStateOf(false) }
    var positionMs by remember(state.playRequestToken) { mutableLongStateOf(0L) }
    var durationMs by remember(state.playRequestToken) {
        mutableLongStateOf((state.currentDurationSec?.times(1000.0))?.roundToLong() ?: 0L)
    }

    LaunchedEffect(player, state.playRequestToken, state.currentDurationSec, isSeeking, state.isPlayerPlaying) {
        while (true) {
            if (!isSeeking) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = resolveDurationMs(player = player, fallbackSec = state.currentDurationSec)
                sliderValue = positionMs.coerceAtMost(durationMs).toFloat()
            }
            delay(if (state.isPlayerPlaying) 320 else 700)
        }
    }

    val effectiveDuration = durationMs.coerceAtLeast(0L)
    val canControl = !state.playbackUri.isNullOrBlank()
    val mainButtonEnabled = canControl && !state.isPreparing
    val shownPosition = if (isSeeking) sliderValue.roundToLong() else positionMs
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush),
    ) {
        DecorativeGlow(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 8.dp),
            size = 220.dp,
            brush = Brush.radialGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                ),
            ),
        )
        DecorativeGlow(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 24.dp),
            size = 180.dp,
            brush = Brush.radialGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0f),
                ),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlayerSurfaceCard(
                state = state,
                surfaceMode = surfaceMode,
                positionMs = shownPosition,
                onToggleSurface = {
                    surfaceMode = when (surfaceMode) {
                        PlayerSurfaceMode.COVER -> PlayerSurfaceMode.LYRICS
                        PlayerSurfaceMode.LYRICS -> PlayerSurfaceMode.COVER
                    }
                },
            )

            PlaybackInfoBlock(state = state)
            TransportCard(
                state = state,
                player = player,
                shownPositionMs = shownPosition,
                durationMs = effectiveDuration,
                sliderValue = sliderValue,
                canControl = canControl,
                mainButtonEnabled = mainButtonEnabled,
                onSliderValueChange = { value ->
                    if (!canControl || effectiveDuration <= 0L) {
                        return@TransportCard
                    }
                    isSeeking = true
                    sliderValue = value.coerceIn(0f, effectiveDuration.toFloat())
                },
                onSliderValueFinished = {
                    if (!canControl || effectiveDuration <= 0L) {
                        isSeeking = false
                        return@TransportCard
                    }
                    player.seekTo(sliderValue.roundToLong().coerceIn(0L, effectiveDuration))
                    positionMs = sliderValue.roundToLong().coerceIn(0L, effectiveDuration)
                    isSeeking = false
                },
                onDownloadSong = onDownloadSong,
            )
            PlaybackStatusCard(state = state)
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(text = "返回上一页")
            }
        }
    }
}

@Composable
private fun PlayerSurfaceCard(
    state: PlaybackUiState,
    surfaceMode: PlayerSurfaceMode,
    positionMs: Long,
    onToggleSurface: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSurface),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(text = if (state.playbackUri.isNullOrBlank()) "准备在线播放" else "正在播放")
                MetaPill(
                    text = when (surfaceMode) {
                        PlayerSurfaceMode.COVER -> "点封面看歌词"
                        PlayerSurfaceMode.LYRICS -> "点歌词看封面"
                    },
                )
            }

            when (surfaceMode) {
                PlayerSurfaceMode.COVER -> PlayerCover(
                    coverUrl = state.currentCoverUrl,
                    title = state.currentTitle ?: "未命名歌曲",
                )

                PlayerSurfaceMode.LYRICS -> PlayerLyricsPanel(
                    state = state,
                    positionMs = positionMs,
                )
            }
        }
    }
}

@Composable
private fun PlaybackInfoBlock(state: PlaybackUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.currentTitle ?: "正在准备播放",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        state.currentChannel?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.currentDurationSec?.let {
                MetaPill(text = formatDuration(it))
            }
            MetaPill(text = if (state.source == "downloaded") "已下载串流" else "服务端串流")
            if (!state.lyricsContent.isNullOrBlank()) {
                MetaPill(text = "同步歌词")
            }
        }
    }
}

@Composable
private fun PlayerLyricsPanel(
    state: PlaybackUiState,
    positionMs: Long,
) {
    val lyricsLines = remember(state.lyricsContent) {
        parseTimedLyrics(state.lyricsContent)
    }
    val listState = rememberLazyListState()
    val activeIndex = remember(lyricsLines, positionMs) {
        findActiveLyricIndex(lyricsLines, positionMs)
    }

    LaunchedEffect(activeIndex, state.playRequestToken) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(26.dp),
            ),
    ) {
        when {
            state.isLyricsLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在加载歌词...",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            lyricsLines.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp,
                        vertical = 28.dp,
                    ),
                ) {
                    itemsIndexed(lyricsLines) { index, line ->
                        val distance = kotlin.math.abs(index - activeIndex)
                        val isActive = index == activeIndex
                        val textColor = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            distance <= 1 -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val style = when {
                            isActive -> MaterialTheme.typography.headlineSmall
                            distance <= 1 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.bodyLarge
                        }

                        Text(
                            text = line.text,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = style,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            color = textColor.copy(alpha = if (isActive) 1f else if (distance <= 1) 0.92f else 0.72f),
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Subtitles,
                        contentDescription = "暂无歌词",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "当前没有可滚动的同步歌词",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val secondaryText = when {
                        !state.lyricsErrorMessage.isNullOrBlank() -> state.lyricsErrorMessage
                        else -> "先在已下载列表里生成 .lrc，播放页就能跟随时间轴实时滚动。"
                    }
                    Text(
                        text = secondaryText ?: "",
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackStatusCard(state: PlaybackUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            state.currentTask?.let { task ->
                StatusRow(label = "服务端任务", value = "${task.status} / ${task.stage}")
                if (state.isPreparing) {
                    LinearProgressBlock(
                        progress = task.progress.coerceIn(0, 100) / 100f,
                        label = "音频准备中 ${task.progress}%",
                    )
                }
                task.speedBps?.let {
                    StatusRow(label = "当前速度", value = formatSpeed(it))
                }
                task.filename?.takeIf { it.isNotBlank() }?.let {
                    StatusRow(label = "输出文件", value = it, maxLines = 2)
                }
            }

            if (state.isPreparing && state.currentTask == null) {
                LinearProgressBlock(progress = null, label = "正在连接服务端并准备音频")
            }

            if (!state.lyricsUpdatedAt.isNullOrBlank()) {
                StatusRow(label = "歌词更新时间", value = state.lyricsUpdatedAt ?: "")
            }
            state.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TransportCard(
    state: PlaybackUiState,
    player: Player,
    shownPositionMs: Long,
    durationMs: Long,
    sliderValue: Float,
    canControl: Boolean,
    mainButtonEnabled: Boolean,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueFinished: () -> Unit,
    onDownloadSong: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "播放控制",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Slider(
                value = if (durationMs > 0L) sliderValue.coerceIn(0f, durationMs.toFloat()) else 0f,
                onValueChange = onSliderValueChange,
                onValueChangeFinished = onSliderValueFinished,
                enabled = canControl && durationMs > 0L,
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPosition(shownPositionMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatPosition(durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val nextPosition = (player.currentPosition - 10_000L).coerceAtLeast(0L)
                        player.seekTo(nextPosition)
                    },
                    enabled = canControl,
                    modifier = Modifier.size(58.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = "后退 10 秒",
                    )
                }

                FilledIconButton(
                    onClick = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (player.playbackState == Player.STATE_ENDED) {
                                player.seekTo(0L)
                            }
                            player.play()
                        }
                    },
                    enabled = mainButtonEnabled,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .size(86.dp),
                ) {
                    if (state.isPreparing && !canControl) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlayerPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlayerPlaying) "暂停" else "播放",
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = {
                        val nextPosition = (player.currentPosition + 10_000L).coerceAtMost(durationMs)
                        player.seekTo(nextPosition)
                    },
                    enabled = canControl,
                    modifier = Modifier.size(58.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = "前进 10 秒",
                    )
                }
            }

            OutlinedButton(
                onClick = onTogglePlayPauseLabel(player, state.isPlayerPlaying),
                enabled = mainButtonEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        state.isPreparing && !canControl -> "正在等待音频就绪"
                        state.isPlayerBuffering -> "正在缓冲"
                        state.isPlayerPlaying -> "暂停播放"
                        else -> "开始播放"
                    },
                )
            }

            Button(
                onClick = onDownloadSong,
                enabled = !state.currentMusicId.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = playbackDownloadActionLabel(state))
            }
        }
    }
}

private fun onTogglePlayPauseLabel(player: Player, isPlaying: Boolean): () -> Unit = {
    if (isPlaying) {
        player.pause()
    } else {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        }
        player.play()
    }
}

@Composable
private fun EmptyPlayerScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = "暂无播放内容",
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "还没有正在播放的歌曲",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "先去结果页或已下载页选择一首歌，这里会显示封面、歌词和完整控制界面。",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onBack) {
                    Text(text = "返回上一页")
                }
            }
        }
    }
}

@Composable
private fun PlayerCover(
    coverUrl: String?,
    title: String,
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .shadow(26.dp, RoundedCornerShape(34.dp), clip = false)
            .clip(RoundedCornerShape(34.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(34.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = "暂无封面",
                    modifier = Modifier.size(70.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                return@Box
            }

            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "$title 封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 2.5.dp,
                    )
                },
                error = {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = "封面加载失败",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                },
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetaPill(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = CircleShape,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    maxLines: Int = 1,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LinearProgressBlock(
    progress: Float?,
    label: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DecorativeGlow(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp,
    brush: Brush,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(brush),
    )
}

private fun parseTimedLyrics(rawLrc: String?): List<TimedLyricLine> {
    if (rawLrc.isNullOrBlank()) {
        return emptyList()
    }

    val entries = mutableListOf<TimedLyricLine>()
    rawLrc.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) {
            return@forEach
        }
        val matches = lrcTimestampRegex.findAll(line).toList()
        if (matches.isEmpty()) {
            return@forEach
        }
        val text = lrcTimestampRegex.replace(line, "").trim()
        if (text.isBlank()) {
            return@forEach
        }
        matches.forEach { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
            val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
            val fractionRaw = match.groupValues[3]
            val fractionMs = when (fractionRaw.length) {
                0 -> 0L
                1 -> fractionRaw.toLongOrNull()?.times(100L) ?: 0L
                2 -> fractionRaw.toLongOrNull()?.times(10L) ?: 0L
                else -> fractionRaw.take(3).toLongOrNull() ?: 0L
            }
            val timeMs = minutes * 60_000L + seconds * 1_000L + fractionMs
            entries += TimedLyricLine(
                timeMs = timeMs,
                text = text,
            )
        }
    }
    return entries.sortedBy { it.timeMs }
}

private fun findActiveLyricIndex(lines: List<TimedLyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) {
        return -1
    }
    return lines.indexOfLast { line -> line.timeMs <= positionMs }
}

private fun resolveDurationMs(player: Player, fallbackSec: Double?): Long {
    val playerDuration = player.duration
    if (playerDuration > 0L) {
        return playerDuration
    }
    return ((fallbackSec ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L)
}

private fun formatDuration(durationSec: Double): String {
    val totalSeconds = durationSec.roundToInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes} 分 ${seconds} 秒"
    } else {
        "${seconds} 秒"
    }
}

private fun formatSpeed(speedBps: Double): String {
    val kbps = speedBps / 1024.0
    val mbps = kbps / 1024.0
    return if (mbps >= 1) {
        String.format("%.2f MB/s", mbps)
    } else {
        String.format("%.0f KB/s", kbps)
    }
}

private fun formatPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

private fun playbackDownloadActionLabel(state: PlaybackUiState): String {
    return when {
        state.currentDownloadedSong != null -> "保存到下载目录"
        state.currentTask?.status == "finished" -> "保存当前歌曲到下载目录"
        else -> "下载当前歌曲"
    }
}
