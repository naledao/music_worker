package com.openclaw.musicworker.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DesktopColorScheme = lightColorScheme(
    primary = Color(0xFFB85B31),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF4DCCF),
    onPrimaryContainer = Color(0xFF3A190B),
    secondary = Color(0xFF5C655E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EBE4),
    onSecondaryContainer = Color(0xFF182019),
    tertiary = Color(0xFF2C7D6D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4EEE7),
    onTertiaryContainer = Color(0xFF0D2E28),
    error = Color(0xFFBC4545),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6D9D8),
    onErrorContainer = Color(0xFF410C0C),
    background = Color(0xFFF2EEE8),
    onBackground = Color(0xFF1D1B18),
    surface = Color(0xFFFCFBF8),
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFE8E2DA),
    onSurfaceVariant = Color(0xFF4E473F),
    outline = Color(0xFFB7AEA5),
)

private val DesktopTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 27.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DesktopColorScheme,
        typography = DesktopTypography,
        shapes = DesktopShapes,
        content = content,
    )
}
