package com.openclaw.musicworker.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "音爪",
        icon = painterResource("desktop-icon.png"),
    ) {
        DesktopApp()
    }
}
