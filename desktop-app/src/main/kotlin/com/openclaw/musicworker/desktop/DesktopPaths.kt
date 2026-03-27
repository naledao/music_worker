package com.openclaw.musicworker.desktop

import java.nio.file.Path

object DesktopPaths {
    fun configDir(): Path {
        val appData = (System.getenv("APPDATA") ?: "").trim()
        return if (appData.isNotEmpty()) {
            Path.of(appData, "YinZhao")
        } else {
            val home = System.getProperty("user.home")
            Path.of(home, ".yinzhao-desktop")
        }
    }

    fun updatesDir(): Path {
        val localAppData = (System.getenv("LOCALAPPDATA") ?: "").trim()
        return if (localAppData.isNotEmpty()) {
            Path.of(localAppData, "YinZhao", "app-updates")
        } else {
            configDir().resolve("app-updates")
        }
    }
}
