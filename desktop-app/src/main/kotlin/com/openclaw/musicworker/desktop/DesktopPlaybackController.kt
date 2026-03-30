package com.openclaw.musicworker.desktop

import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javazoom.jlgui.basicplayer.BasicController
import javazoom.jlgui.basicplayer.BasicPlayer
import javazoom.jlgui.basicplayer.BasicPlayerEvent
import javazoom.jlgui.basicplayer.BasicPlayerListener

internal class DesktopPlaybackController(
    private val onPlayingChanged: (Long, Boolean) -> Unit,
    private val onBufferingChanged: (Long, Boolean) -> Unit,
    private val onProgressChanged: (Long, Long, Long?) -> Unit,
    private val onPlaybackCompleted: (Long) -> Unit,
    private val onPlaybackError: (Long, String) -> Unit,
) {
    private val stateLock = Any()
    private val sessionSequence = AtomicLong(0L)
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "desktop-playback-control").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var player: BasicPlayer? = null

    @Volatile
    private var currentRequestToken: Long = 0L

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var currentSessionId: Long = 0L

    @Volatile
    private var currentDurationMs: Long? = null

    fun play(url: String, requestToken: Long) {
        if (url.isBlank() || requestToken <= 0L || controlExecutor.isShutdown) {
            return
        }

        submitControl {
            when (val action = preparePlayAction(url, requestToken)) {
                null -> Unit
                is PlaybackAction.Resume -> resumeExistingPlayer(action)
                is PlaybackAction.Start -> startNewPlayer(action)
            }
        }
    }

    fun pause() {
        submitControl {
            val target = synchronized(stateLock) {
                val existingPlayer = player ?: return@submitControl
                ControlTarget(
                    player = existingPlayer,
                    requestToken = currentRequestToken,
                    sessionId = currentSessionId,
                )
            }

            runCatching { target.player.pause() }
                .onFailure { error ->
                    DesktopFileLogger.error("desktop playback pause failed", error)
                    if (isActiveSession(target.sessionId, target.requestToken)) {
                        onPlaybackError(target.requestToken, buildPlaybackErrorMessage(error))
                    }
                }
        }
    }

    fun stop() {
        submitControl {
            val existingPlayer = synchronized(stateLock) { detachCurrentPlayerLocked(resetIdentity = true) }
            releasePlayer(existingPlayer)
        }
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) {
            return
        }

        DesktopFileLogger.info("desktop playback seek ignored because streaming controller does not support seek")
    }

    fun close() {
        if (controlExecutor.isShutdown) {
            return
        }

        runCatching {
            controlExecutor.submit<Unit> {
                val existingPlayer = synchronized(stateLock) { detachCurrentPlayerLocked(resetIdentity = true) }
                releasePlayer(existingPlayer)
            }.get(5, TimeUnit.SECONDS)
        }.onFailure { error ->
            DesktopFileLogger.warn("desktop playback close cleanup failed err=${error.message.orEmpty()}")
        }

        controlExecutor.shutdownNow()
    }

    private fun preparePlayAction(url: String, requestToken: Long): PlaybackAction? {
        synchronized(stateLock) {
            val existingPlayer = player
            if (existingPlayer != null && currentRequestToken == requestToken && currentUrl == url) {
                return when (existingPlayer.status) {
                    BasicPlayer.PLAYING -> null
                    BasicPlayer.PAUSED -> PlaybackAction.Resume(
                        player = existingPlayer,
                        requestToken = requestToken,
                        sessionId = currentSessionId,
                    )
                    else -> buildStartActionLocked(url, requestToken, existingPlayer)
                }
            }

            return buildStartActionLocked(url, requestToken, existingPlayer)
        }
    }

    private fun buildStartActionLocked(
        url: String,
        requestToken: Long,
        previousPlayer: BasicPlayer?,
    ): PlaybackAction.Start {
        player = null
        currentDurationMs = null
        currentRequestToken = requestToken
        currentUrl = url
        val sessionId = sessionSequence.incrementAndGet()
        currentSessionId = sessionId

        val newPlayer = BasicPlayer().apply {
            addBasicPlayerListener(StreamingPlayerListener(sessionId, requestToken))
        }
        player = newPlayer

        return PlaybackAction.Start(
            previousPlayer = previousPlayer,
            player = newPlayer,
            requestToken = requestToken,
            sessionId = sessionId,
            url = url,
        )
    }

    private fun startNewPlayer(action: PlaybackAction.Start) {
        releasePlayer(action.previousPlayer)
        onBufferingChanged(action.requestToken, true)

        runCatching {
            action.player.open(URL(action.url))
            action.player.play()
        }.onFailure { error ->
            DesktopFileLogger.error("desktop playback start failed url=${action.url}", error)
            synchronized(stateLock) {
                if (player === action.player && currentSessionId == action.sessionId) {
                    detachCurrentPlayerLocked(resetIdentity = true)
                }
            }
            releasePlayer(action.player)
            onBufferingChanged(action.requestToken, false)
            onPlayingChanged(action.requestToken, false)
            onPlaybackError(action.requestToken, buildPlaybackErrorMessage(error))
        }
    }

    private fun resumeExistingPlayer(action: PlaybackAction.Resume) {
        runCatching { action.player.resume() }
            .onFailure { error ->
                DesktopFileLogger.error("desktop playback resume failed", error)
                if (isActiveSession(action.sessionId, action.requestToken)) {
                    onPlaybackError(action.requestToken, buildPlaybackErrorMessage(error))
                }
            }
    }

    private fun detachCurrentPlayerLocked(resetIdentity: Boolean): BasicPlayer? {
        val existingPlayer = player
        player = null
        currentDurationMs = null
        if (resetIdentity) {
            currentRequestToken = 0L
            currentUrl = null
            currentSessionId = 0L
        }
        return existingPlayer
    }

    private fun releasePlayer(player: BasicPlayer?) {
        if (player == null) {
            return
        }

        runCatching { player.stop() }
            .onFailure { error ->
                DesktopFileLogger.warn("desktop playback stop failed err=${error.message.orEmpty()}")
            }
    }

    private fun rememberDuration(sessionId: Long, durationMs: Long?) {
        if (durationMs == null || durationMs <= 0L) {
            return
        }

        synchronized(stateLock) {
            if (currentSessionId == sessionId) {
                currentDurationMs = durationMs
            }
        }
    }

    private fun resolveDuration(sessionId: Long, properties: Map<*, *>?): Long? {
        val propertyDuration = extractDurationMs(properties)
        rememberDuration(sessionId, propertyDuration)
        return propertyDuration ?: synchronized(stateLock) {
            if (currentSessionId == sessionId) currentDurationMs else null
        }
    }

    private fun isActiveSession(sessionId: Long, requestToken: Long): Boolean {
        synchronized(stateLock) {
            return currentSessionId == sessionId && currentRequestToken == requestToken && player != null
        }
    }

    private fun extractDurationMs(properties: Map<*, *>?): Long? {
        if (properties.isNullOrEmpty()) {
            return null
        }

        val microsecondKeys = listOf(
            "duration",
            "audio.length.microseconds",
            "mp3.length.microseconds",
        )
        microsecondKeys.forEach { key ->
            parseLong(properties[key])?.takeIf { it > 0L }?.let { return it / 1000L }
        }

        val millisecondKeys = listOf(
            "duration.ms",
            "audio.length.milliseconds",
            "mp3.length.milliseconds",
        )
        millisecondKeys.forEach { key ->
            parseLong(properties[key])?.takeIf { it > 0L }?.let { return it }
        }

        return null
    }

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            null -> null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> value.toString().trim().toLongOrNull()
        }
    }

    private fun buildPlaybackErrorMessage(error: Throwable): String {
        val rootCause = generateSequence(error) { it.cause }.lastOrNull() ?: error
        val message = rootCause.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error.message?.trim().takeIf { !it.isNullOrEmpty() }

        return when {
            message.isNullOrBlank() -> "桌面播放器发生错误"
            message.contains("Unable to retrieve", ignoreCase = true) -> "桌面播放器无法连接音频流"
            message.contains("Connection refused", ignoreCase = true) -> "桌面播放器无法连接本地音频服务"
            message.contains("mark/reset not supported", ignoreCase = true) -> "桌面播放器无法读取当前音频流"
            else -> message
        }
    }

    private fun submitControl(action: () -> Unit) {
        if (controlExecutor.isShutdown) {
            return
        }

        controlExecutor.execute {
            runCatching(action).onFailure { error ->
                DesktopFileLogger.error("desktop playback control failed", error)
            }
        }
    }

    private sealed interface PlaybackAction {
        data class Start(
            val previousPlayer: BasicPlayer?,
            val player: BasicPlayer,
            val requestToken: Long,
            val sessionId: Long,
            val url: String,
        ) : PlaybackAction

        data class Resume(
            val player: BasicPlayer,
            val requestToken: Long,
            val sessionId: Long,
        ) : PlaybackAction
    }

    private data class ControlTarget(
        val player: BasicPlayer,
        val requestToken: Long,
        val sessionId: Long,
    )

    private inner class StreamingPlayerListener(
        private val sessionId: Long,
        private val requestToken: Long,
    ) : BasicPlayerListener {
        private val completed = AtomicBoolean(false)

        override fun opened(stream: Any?, properties: MutableMap<Any?, Any?>?) {
            if (!isActiveSession(sessionId, requestToken)) {
                return
            }

            val durationMs = resolveDuration(sessionId, properties)
            onBufferingChanged(requestToken, false)
            onProgressChanged(requestToken, 0L, durationMs)
        }

        override fun progress(
            bytesread: Int,
            microseconds: Long,
            pcmdata: ByteArray?,
            properties: MutableMap<Any?, Any?>?,
        ) {
            if (!isActiveSession(sessionId, requestToken)) {
                return
            }

            val durationMs = resolveDuration(sessionId, properties)
            val positionMs = (microseconds / 1000L).coerceAtLeast(0L)
            onProgressChanged(requestToken, positionMs, durationMs)
        }

        override fun stateUpdated(event: BasicPlayerEvent?) {
            if (!isActiveSession(sessionId, requestToken) || event == null) {
                return
            }

            when (event.code) {
                BasicPlayerEvent.OPENING -> onBufferingChanged(requestToken, true)
                BasicPlayerEvent.PLAYING, BasicPlayerEvent.RESUMED -> {
                    onBufferingChanged(requestToken, false)
                    onPlayingChanged(requestToken, true)
                }
                BasicPlayerEvent.PAUSED -> onPlayingChanged(requestToken, false)
                BasicPlayerEvent.EOM -> {
                    if (completed.compareAndSet(false, true)) {
                        onBufferingChanged(requestToken, false)
                        onPlayingChanged(requestToken, false)
                        onPlaybackCompleted(requestToken)
                    }
                }
                BasicPlayerEvent.STOPPED -> {
                    if (!completed.get()) {
                        onBufferingChanged(requestToken, false)
                        onPlayingChanged(requestToken, false)
                    }
                }
                BasicPlayerEvent.SEEKING -> onBufferingChanged(requestToken, true)
                BasicPlayerEvent.SEEKED -> onBufferingChanged(requestToken, false)
            }
        }

        override fun setController(controller: BasicController?) = Unit
    }
}
