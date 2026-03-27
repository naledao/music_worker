package com.openclaw.musicworker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = ClayOrange,
    onPrimary = Cream,
    secondary = Moss,
    onSecondary = Cream,
    background = Sand,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    error = BurntOrange,
)

private val DarkColors = darkColorScheme(
    primary = ColorTokens.DarkPrimary,
    onPrimary = Cream,
    secondary = Moss,
    onSecondary = Cream,
    background = Night,
    onBackground = Cream,
    surface = Ink,
    onSurface = Cream,
    error = BurntOrange,
)

@Composable
fun MusicWorkerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

private object ColorTokens {
    val DarkPrimary = ClayOrange
}
