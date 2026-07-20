package com.openclaw.musicworker

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.Locale

class AndroidAudioPlayerModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "AndroidAudioPlayer"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var fallbackDurationMs: Int = 0
    private var prepared = false
    private var buffering = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            emitStatus()
            if (mediaPlayer != null) {
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    @ReactMethod
    fun play(url: String, title: String?, durationSec: Double, promise: Promise) {
        mainHandler.post {
            try {
                val normalizedUrl = url.trim()
                if (normalizedUrl.isBlank()) {
                    throw IllegalArgumentException("播放地址为空")
                }

                releasePlayer()
                currentUrl = normalizedUrl
                currentTitle = title?.trim()?.takeUnless { it.isBlank() }
                fallbackDurationMs = if (durationSec > 0) {
                    (durationSec * 1000.0).toInt()
                } else {
                    0
                }
                prepared = false
                buffering = true

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setOnPreparedListener {
                        prepared = true
                        buffering = false
                        start()
                        emitStatus(message = "正在播放")
                    }
                    setOnBufferingUpdateListener { _, _ ->
                        if (prepared) {
                            emitStatus()
                        }
                    }
                    setOnCompletionListener {
                        buffering = false
                        emitStatus(ended = true, message = "播放完成")
                    }
                    setOnErrorListener { _, what, extra ->
                        buffering = false
                        prepared = false
                        emitStatus(errorMessage = "播放失败: ${formatMediaError(what, extra)}")
                        true
                    }
                    setDataSource(normalizedUrl)
                    prepareAsync()
                }
                mediaPlayer = player
                startProgressLoop()
                emitStatus(message = "正在缓冲音频")
                promise.resolve(statusMap(message = "正在缓冲音频"))
            } catch (error: Throwable) {
                releasePlayer()
                promise.reject("AUDIO_PLAY_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun pause(promise: Promise) {
        mainHandler.post {
            runCatching {
                mediaPlayer?.takeIf { prepared && it.isPlaying }?.pause()
                emitStatus(message = "已暂停")
                promise.resolve(statusMap(message = "已暂停"))
            }.onFailure { error ->
                promise.reject("AUDIO_PAUSE_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun resume(promise: Promise) {
        mainHandler.post {
            runCatching {
                val player = mediaPlayer ?: throw IllegalStateException("没有可恢复的播放")
                if (prepared) {
                    player.start()
                    emitStatus(message = "正在播放")
                    promise.resolve(statusMap(message = "正在播放"))
                } else {
                    throw IllegalStateException("音频仍在准备中")
                }
            }.onFailure { error ->
                promise.reject("AUDIO_RESUME_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun seekBy(deltaSec: Double, promise: Promise) {
        mainHandler.post {
            runCatching {
                val player = mediaPlayer ?: throw IllegalStateException("没有可跳转的播放")
                if (!prepared) {
                    throw IllegalStateException("音频仍在准备中")
                }
                val duration = resolveDurationMs()
                val nextPosition = (player.currentPosition + (deltaSec * 1000.0).toInt()).coerceAtLeast(0)
                player.seekTo(if (duration > 0) nextPosition.coerceAtMost(duration) else nextPosition)
                emitStatus()
                promise.resolve(statusMap())
            }.onFailure { error ->
                promise.reject("AUDIO_SEEK_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        mainHandler.post {
            runCatching {
                releasePlayer()
                emitStatus(message = "已停止")
                promise.resolve(statusMap(message = "已停止"))
            }.onFailure { error ->
                promise.reject("AUDIO_STOP_FAILED", error.message, error)
            }
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        mainHandler.post {
            promise.resolve(statusMap())
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required by NativeEventEmitter.
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required by NativeEventEmitter.
    }

    override fun invalidate() {
        super.invalidate()
        mainHandler.post { releasePlayer() }
    }

    private fun startProgressLoop() {
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.post(progressRunnable)
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(progressRunnable)
        mediaPlayer?.runCatching {
            reset()
            release()
        }
        mediaPlayer = null
        prepared = false
        buffering = false
        currentUrl = null
        currentTitle = null
        fallbackDurationMs = 0
    }

    private fun emitStatus(
        message: String? = null,
        errorMessage: String? = null,
        ended: Boolean = false,
    ) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("AndroidAudioPlayerStatus", statusMap(message, errorMessage, ended))
    }

    private fun statusMap(
        message: String? = null,
        errorMessage: String? = null,
        ended: Boolean = false,
    ) = Arguments.createMap().apply {
        val player = mediaPlayer
        val positionMs = runCatching { if (player != null && prepared) player.currentPosition else 0 }.getOrDefault(0)
        val durationMs = resolveDurationMs()
        putString("url", currentUrl)
        putString("title", currentTitle)
        putBoolean("isPlaying", runCatching { player?.isPlaying == true }.getOrDefault(false))
        putBoolean("isBuffering", buffering)
        putBoolean("isPrepared", prepared)
        putBoolean("ended", ended)
        putDouble("positionSec", positionMs.toDouble() / 1000.0)
        putDouble("durationSec", durationMs.toDouble() / 1000.0)
        if (message != null) {
            putString("message", message)
        }
        if (errorMessage != null) {
            putString("errorMessage", errorMessage)
        }
    }

    private fun resolveDurationMs(): Int {
        val playerDuration = runCatching {
            if (mediaPlayer != null && prepared) mediaPlayer?.duration ?: 0 else 0
        }.getOrDefault(0)
        return if (playerDuration > 0) playerDuration else fallbackDurationMs
    }

    private fun formatMediaError(what: Int, extra: Int): String {
        return String.format(Locale.US, "what=%d extra=%d", what, extra)
    }
}
