package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = StudioRecordRed,
    onPrimary = Color.White,
    primaryContainer = StudioRecordRedContainer,
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = StudioEmerald,
    onSecondary = Color(0xFF04261B),
    tertiary = StudioAmber,
    background = StudioDarkBackground,
    onBackground = StudioDarkOnSurface,
    surface = StudioDarkSurface,
    onSurface = StudioDarkOnSurface,
    surfaceVariant = StudioDarkSurfaceVariant,
    onSurfaceVariant = StudioDarkOnSurfaceVariant,
    outline = StudioDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = StudioRecordRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E6),
    onPrimaryContainer = Color(0xFF7F1D1D),
    secondary = StudioEmerald,
    onSecondary = Color.White,
    tertiary = StudioAmber,
    background = StudioLightBackground,
    onBackground = StudioLightOnSurface,
    surface = StudioLightSurface,
    onSurface = StudioLightOnSurface,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightOnSurfaceVariant,
    outline = StudioLightOutline
)

@Composable
fun FrameCutTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = FrameCutTypography,
        content = content
    )
}
