package com.localcam.stream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LocalCamColorScheme = darkColorScheme(
    primary = Mint,
    secondary = Ember,
    background = InkBlue,
    surface = SlateCard,
    surfaceVariant = Color(0xFF172033),
    onPrimary = InkBlue,
    onSecondary = InkBlue,
    onBackground = Mist,
    onSurface = Mist,
    onSurfaceVariant = Color(0xFFB8C2D9)
)

@Composable
fun LocalCamStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LocalCamColorScheme,
        typography = LocalCamTypography,
        content = content
    )
}
