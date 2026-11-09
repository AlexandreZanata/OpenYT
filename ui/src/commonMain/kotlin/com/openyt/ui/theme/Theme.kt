package com.openyt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF0B0D0F)
internal val Paper = Color(0xFFF7F8F7)
internal val TealDark = Color(0xFF0F8A6E)
internal val TealLight = Color(0xFF5EEAD4)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E8E5),
    onPrimaryContainer = Ink,
    secondary = TealDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF475569),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2E8F0),
    onTertiaryContainer = Color(0xFF1E293B),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDF1EF),
    onSurfaceVariant = Color(0xFF46524E),
    surfaceDim = Color(0xFFDCE3E0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F5F3),
    surfaceContainer = Color(0xFFECF0EE),
    surfaceContainerHigh = Color(0xFFE5EBE8),
    surfaceContainerHighest = Color(0xFFDEE5E2),
    surfaceTint = TealDark,
    inverseSurface = Color(0xFF1B1F1E),
    inverseOnSurface = Color(0xFFF1F5F3),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFB6C0BC),
    outlineVariant = Color(0xFFD7DEDB),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8F1EE),
    onPrimary = Ink,
    primaryContainer = Color(0xFF25302D),
    onPrimaryContainer = Color(0xFFE8F1EE),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF04251D),
    secondaryContainer = Color(0xFF103A31),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFAFC3D9),
    onTertiary = Color(0xFF0B1B2A),
    tertiaryContainer = Color(0xFF243447),
    onTertiaryContainer = Color(0xFFD8E6F5),
    background = Color(0xFF08090A),
    onBackground = Color(0xFFE6ECE9),
    surface = Color(0xFF101214),
    onSurface = Color(0xFFE6ECE9),
    surfaceVariant = Color(0xFF171B1E),
    onSurfaceVariant = Color(0xFFB6C2BE),
    surfaceDim = Color(0xFF08090A),
    surfaceBright = Color(0xFF242B2E),
    surfaceContainerLowest = Color(0xFF060708),
    surfaceContainerLow = Color(0xFF101214),
    surfaceContainer = Color(0xFF151A1D),
    surfaceContainerHigh = Color(0xFF1A2024),
    surfaceContainerHighest = Color(0xFF20272B),
    surfaceTint = TealLight,
    inverseSurface = Color(0xFFE6ECE9),
    inverseOnSurface = Color(0xFF1B1F1E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3C474B),
    outlineVariant = Color(0xFF283237),
    scrim = Color.Black,
)

@Composable
fun OpenYtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
