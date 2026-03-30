package com.openclaw.musicworker.desktop

import java.nio.file.Path

object DesktopPaths {
    private fun userHomeDir(): Path {
        val home = (System.getProperty("user.home") ?: "").trim()
        return if (home.isNotEmpty()) {
            Path.of(home)
        } else {
            Path.of(".").toAbsolutePath().normalize()
        }
    }

    fun configDir(): Path {
        val appData = (System.getenv("APPDATA") ?: "").trim()
        return if (appData.isNotEmpty()) {
            Path.of(appData, "YinZhao")
        } else {
            userHomeDir().resolve(".yinzhao-desktop")
        }
    }

    fun defaultDownloadsDir(): Path = userHomeDir().resolve("Downloads")

    fun updatesDir(): Path {
        val localAppData = (System.getenv("LOCALAPPDATA") ?: "").trim()
        return if (localAppData.isNotEmpty()) {
            Path.of(localAppData, "YinZhao", "app-updates")
        } else {
            configDir().resolve("app-updates")
        }
    }

    fun playbackCacheDir(): Path {
        val localAppData = (System.getenv("LOCALAPPDATA") ?: "").trim()
        return if (localAppData.isNotEmpty()) {
            Path.of(localAppData, "YinZhao", "playback-cache")
        } else {
            configDir().resolve("playback-cache")
        }
    }

    fun logsDir(): Path = configDir().resolve("logs")

    fun desktopLogFile(): Path = logsDir().resolve("desktop.log")
}
