package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ColorFilterPreset
import com.example.model.ColorGradeConfig
import kotlin.math.roundToInt

@Composable
fun AdjustPanel(
    colorGrade: ColorGradeConfig,
    onSelectPreset: (ColorFilterPreset) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onWarmthChange: (Float) -> Unit,
    onVignetteChange: (Float) -> Unit,
    onReset: () -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Color Filters",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!colorGrade.isDefault()) {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.testTag("adjust_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(" Reset", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorFilterPreset.entries) { preset ->
                val selected = colorGrade.preset == preset
                Surface(
                    onClick = { onSelectPreset(preset) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("filter_preset_${preset.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(presetSwatchBrush(preset))
                        )
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StudioSliderRow(
                    label = "Brightness",
                    valueText = signedPercent(colorGrade.brightness / 0.4f),
                    value = colorGrade.brightness,
                    valueRange = -0.4f..0.4f,
                    onValueChange = onBrightnessChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "adjust_brightness_slider"
                )
                StudioSliderRow(
                    label = "Contrast",
                    valueText = "${(colorGrade.contrast * 100f).roundToInt()}%",
                    value = colorGrade.contrast,
                    valueRange = 0.5f..1.6f,
                    onValueChange = onContrastChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "adjust_contrast_slider"
                )
                StudioSliderRow(
                    label = "Saturation",
                    valueText = "${(colorGrade.saturation * 100f).roundToInt()}%",
                    value = colorGrade.saturation,
                    valueRange = 0.0f..2.0f,
                    onValueChange = onSaturationChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "adjust_saturation_slider"
                )
                StudioSliderRow(
                    label = "Warmth",
                    valueText = signedPercent(colorGrade.warmth / 0.5f),
                    value = colorGrade.warmth,
                    valueRange = -0.5f..0.5f,
                    onValueChange = onWarmthChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "adjust_warmth_slider"
                )
                StudioSliderRow(
                    label = "Vignette",
                    valueText = "${((colorGrade.vignette / 0.85f) * 100f).roundToInt()}%",
                    value = colorGrade.vignette,
                    valueRange = 0.0f..0.85f,
                    onValueChange = onVignetteChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "adjust_vignette_slider"
                )
            }
        }
    }
}

private fun signedPercent(norm: Float): String {
    val pct = (norm * 100f).roundToInt()
    return if (pct > 0) "+$pct%" else "$pct%"
}

private fun presetSwatchBrush(preset: ColorFilterPreset): Brush = when (preset) {
    ColorFilterPreset.ORIGINAL -> Brush.linearGradient(listOf(Color(0xFF9198A6), Color(0xFFF4F5F7)))
    ColorFilterPreset.NOIR -> Brush.linearGradient(listOf(Color(0xFF101216), Color(0xFFD0D4DC)))
    ColorFilterPreset.WARM_CINEMA -> Brush.linearGradient(listOf(Color(0xFFF5A623), Color(0xFF3E2B14)))
    ColorFilterPreset.COOL_SLATE -> Brush.linearGradient(listOf(Color(0xFF4F8FF7), Color(0xFF1D2C42)))
    ColorFilterPreset.VIVID -> Brush.linearGradient(listOf(Color(0xFFE55353), Color(0xFF2CC984)))
    ColorFilterPreset.MATTE -> Brush.linearGradient(listOf(Color(0xFF5A616E), Color(0xFFB4B9C2)))
    ColorFilterPreset.VINTAGE -> Brush.linearGradient(listOf(Color(0xFFC68B59), Color(0xFF4F3B2B)))
}
