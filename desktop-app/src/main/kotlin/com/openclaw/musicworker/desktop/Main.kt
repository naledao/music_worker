package com.openclaw.musicworker.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    val windowState = rememberWindowState(
        width = 1400.dp,
        height = 920.dp,
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "音爪",
        icon = painterResource("desktop-icon.png"),
        state = windowState,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1280, 820)
        }
        DesktopApp()
    }
}
