package com.clipbau.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFF7F7F5)
private val Accent = Color(0xFF0F766E)
private val AccentLight = Color(0xFF5EEAD4)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Accent,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = Color(0xFF4A4A46),
    outline = Color(0xFFB9B9B4),
)

private val DarkColors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = AccentLight,
    onSecondary = Ink,
    background = Color(0xFF0C0C0C),
    onBackground = Paper,
    surface = Color(0xFF161616),
    onSurface = Paper,
    surfaceVariant = Color(0xFF262624),
    onSurfaceVariant = Color(0xFFC8C8C2),
    outline = Color(0xFF5A5A55),
)

@Composable
fun ClipBauTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
