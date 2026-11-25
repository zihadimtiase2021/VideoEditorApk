package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MaskFrameDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = CyanInk,
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = SlateSteel,
    onSecondary = OnSlateSteel,
    secondaryContainer = SlateContainer,
    onSecondaryContainer = OnSlateContainer,
    tertiary = SignalCrimson,
    onTertiary = OnSignalCrimson,
    tertiaryContainer = CrimsonContainer,
    onTertiaryContainer = OnCrimsonContainer,
    background = ObsidianCanvas,
    onBackground = StudioTextPrimary,
    surface = ObsidianSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianContainerLow,
    surfaceContainer = ObsidianContainer,
    surfaceContainerHigh = ObsidianContainerHigh,
    surfaceContainerHighest = ObsidianContainerHighest,
    outline = StudioOutline,
    outlineVariant = StudioOutlineVariant,
    error = SignalCrimson,
    onError = OnSignalCrimson,
    errorContainer = CrimsonContainer,
    onErrorContainer = OnCrimsonContainer
)

private val MaskFrameShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MaskFrameTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MaskFrameDarkColorScheme,
        typography = MaskFrameTypography,
        shapes = MaskFrameShapes,
        content = content
    )
}
