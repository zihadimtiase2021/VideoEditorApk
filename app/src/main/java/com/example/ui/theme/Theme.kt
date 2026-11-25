package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MaskFrameMinimalDarkScheme = darkColorScheme(
    primary = WarmAmber,
    onPrimary = AmberInk,
    primaryContainer = AmberContainer,
    onPrimaryContainer = OnAmberContainer,
    secondary = GraphiteMist,
    onSecondary = OnGraphiteMist,
    secondaryContainer = MistContainer,
    onSecondaryContainer = OnMistContainer,
    tertiary = StudioRed,
    onTertiary = OnStudioRed,
    tertiaryContainer = StudioRedContainer,
    onTertiaryContainer = OnStudioRedContainer,
    background = GraphiteCanvas,
    onBackground = StudioTextPrimary,
    surface = GraphiteSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = GraphiteSurfaceVariant,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLowest = GraphiteMonitor,
    surfaceContainerLow = GraphiteContainerLow,
    surfaceContainer = GraphiteContainer,
    surfaceContainerHigh = GraphiteContainerHigh,
    surfaceContainerHighest = GraphiteContainerHighest,
    outline = StudioOutline,
    outlineVariant = StudioOutlineVariant,
    error = StudioRed,
    onError = OnStudioRed,
    errorContainer = StudioRedContainer,
    onErrorContainer = OnStudioRedContainer
)

private val MaskFrameShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MaskFrameTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MaskFrameMinimalDarkScheme,
        typography = MaskFrameTypography,
        shapes = MaskFrameShapes,
        content = content
    )
}
