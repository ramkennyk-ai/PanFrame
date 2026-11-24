package com.panframe.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CinemaAmber = Color(0xFFFFB300)
val CinemaRed = Color(0xFFFF3B30)
val CinemaDark = Color(0xFF0A0A0C)
val CinemaSurface = Color(0xCC1C1C22)

private val DarkPalette = darkColorScheme(
    primary = CinemaAmber,
    secondary = Color(0xFF00E5FF),
    background = CinemaDark,
    surface = CinemaSurface,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun PanFrameTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkPalette,
        content = content
    )
}
