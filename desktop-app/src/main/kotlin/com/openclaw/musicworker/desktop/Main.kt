package com.openclaw.musicworker.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "音爪 Windows",
    ) {
        DesktopApp()
    }
}
