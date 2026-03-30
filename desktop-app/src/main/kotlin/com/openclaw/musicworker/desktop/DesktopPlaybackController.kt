package com.openclaw.musicworker.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.media.Media
import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlin.math.roundToLong

internal class DesktopPlaybackController(
    private val onPlayingChanged: (Long, Boolean) -> Unit,
    private val onBufferingChanged: (Long, Boolean) -> Unit,
    private val onProgressChanged: (Long, Long, Long?) -> Unit,
    private val onPlaybackCompleted: (Long) -> Unit,
    private val onPlaybackError: (Long, String) -> Unit,
) {
    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var currentRequestToken: Long = 0L

    @Volatile
    private var currentUrl: String? = null

    fun play(url: String, requestToken: Long) {
        if (url.isBlank() || requestToken <= 0L) {
            return
        }
        runOnFxThread {
            val existingPlayer = mediaPlayer
            if (existingPlayer != null && currentRequestToken == requestToken && currentUrl == url) {
                if (existingPlayer.status == MediaPlayer.Status.STOPPED) {
                    existingPlayer.seek(Duration.ZERO)
                }
                existingPlayer.play()
                return@runOnFxThread
            }

            releasePlayer()
            currentRequestToken = requestToken
            currentUrl = url

            val media = runCatching { Media(url) }
                .getOrElse { error ->
                    onPlaybackError(requestToken, error.message ?: "桌面播放器无法加载音频地址")
                    return@runOnFxThread
                }

            val player = MediaPlayer(media)
            bindPlayer(player, requestToken)
            mediaPlayer = player
            onBufferingChanged(requestToken, true)
            player.play()
        }
    }

    fun pause() {
        runOnFxThread {
            mediaPlayer?.pause()
        }
    }

    fun stop() {
        runOnFxThread {
            releasePlayer()
        }
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) {
            return
        }
        runOnFxThread {
            mediaPlayer?.seek(Duration.millis(positionMs.toDouble()))
        }
    }

    fun close() {
        stop()
    }

    private fun bindPlayer(
        player: MediaPlayer,
        requestToken: Long,
    ) {
        player.setOnReady {
            onBufferingChanged(requestToken, false)
            onProgressChanged(requestToken, 0L, resolveDurationMs(player))
        }
        player.setOnPlaying {
            onBufferingChanged(requestToken, false)
            onPlayingChanged(requestToken, true)
        }
        player.setOnPaused {
            onPlayingChanged(requestToken, false)
        }
        player.setOnStopped {
            onPlayingChanged(requestToken, false)
        }
        player.setOnStalled {
            onBufferingChanged(requestToken, true)
        }
        player.setOnEndOfMedia {
            onPlayingChanged(requestToken, false)
            onBufferingChanged(requestToken, false)
            onPlaybackCompleted(requestToken)
        }
        player.setOnError {
            onPlaybackError(requestToken, buildPlaybackErrorMessage(player.error))
        }
        player.currentTimeProperty().addListener { _, _, newValue ->
            onProgressChanged(requestToken, newValue.toMillis().safeRoundToLong(), resolveDurationMs(player))
        }
        player.totalDurationProperty().addListener { _, _, newValue ->
            val durationMs = newValue.toMillis().safeRoundToLong().takeIf { it > 0L }
            onProgressChanged(requestToken, player.currentTime.toMillis().safeRoundToLong(), durationMs)
        }
        player.errorProperty().addListener { _, _, error ->
            if (error != null) {
                onPlaybackError(requestToken, buildPlaybackErrorMessage(error))
            }
        }
    }

    private fun resolveDurationMs(player: MediaPlayer): Long? {
        val duration = player.totalDuration
        return duration?.toMillis()?.safeRoundToLong()?.takeIf { it > 0L }
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        currentRequestToken = 0L
        currentUrl = null
        runCatching { player.stop() }
        runCatching { player.dispose() }
    }

    private fun runOnFxThread(block: () -> Unit) {
        ensureJavaFxRuntime()
        if (Platform.isFxApplicationThread()) {
            block()
        } else {
            Platform.runLater(block)
        }
    }

    private fun ensureJavaFxRuntime() {
        if (javaFxStarted.get()) {
            return
        }
        synchronized(javaFxStarted) {
            if (javaFxStarted.get()) {
                return
            }
            val latch = CountDownLatch(1)
            SwingUtilities.invokeLater {
                runCatching { JFXPanel() }
                    .onFailure { error ->
                        DesktopFileLogger.error("initialize JavaFX runtime failed", error)
                    }
                latch.countDown()
            }
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("JavaFX runtime 初始化超时")
            }
            javaFxStarted.set(true)
        }
    }

    private fun buildPlaybackErrorMessage(error: MediaException?): String {
        return error?.message?.takeIf { it.isNotBlank() } ?: "桌面播放器发生错误"
    }

    private fun Double.safeRoundToLong(): Long {
        return if (isFinite() && this >= 0.0) {
            roundToLong()
        } else {
            0L
        }
    }

    private companion object {
        val javaFxStarted = AtomicBoolean(false)
    }
}
