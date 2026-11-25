package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.EditorUiState
import com.example.model.StudioPalettes
import com.example.model.WatermarkPositionPreset
import com.example.model.WatermarkType
import kotlin.math.roundToInt

@Composable
fun WatermarkPanel(
    uiState: EditorUiState,
    onSelectType: (WatermarkType) -> Unit,
    onTextChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onTextColorChange: (Int) -> Unit,
    onPickLogo: () -> Unit,
    onLogoScaleChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onSelectPreset: (WatermarkPositionPreset) -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = uiState.watermarkConfig
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WatermarkType.entries.forEach { type ->
                val selected = wm.type == type
                val icon = when (type) {
                    WatermarkType.NONE -> Icons.Filled.VisibilityOff
                    WatermarkType.TEXT -> Icons.Filled.TextFields
                    WatermarkType.IMAGE -> Icons.Filled.Image
                }
                FilterChip(
                    selected = selected,
                    onClick = { onSelectType(type) },
                    leadingIcon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(type.label) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("watermark_type_${type.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = primary
                    )
                )
            }
        }

        if (wm.type == WatermarkType.NONE) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Choose Text or Logo above, then tap an anchor dot or drag directly on the preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
            return
        }

        when (wm.type) {
            WatermarkType.TEXT -> {
                OutlinedTextField(
                    value = wm.text,
                    onValueChange = onTextChange,
                    label = { Text("Watermark Text") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("watermark_text_input")
                )

                StudioSliderRow(
                    label = "Size",
                    valueText = "${wm.fontSizeSp.roundToInt()} sp",
                    value = wm.fontSizeSp,
                    valueRange = 12f..64f,
                    onValueChange = onFontSizeChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "watermark_font_size_slider"
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(StudioPalettes.watermarkTextColors) { color ->
                            val argb = color.toArgb()
                            ColorSwatchDot(
                                color = color,
                                selected = wm.textColorArgb == argb,
                                onClick = { onTextColorChange(argb) }
                            )
                        }
                    }
                }
            }

            WatermarkType.IMAGE -> {
                OutlinedButton(
                    onClick = onPickLogo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pick_watermark_logo_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, primary.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (wm.logoUri != null) "Change PNG Logo" else "Choose Transparent PNG",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                StudioSliderRow(
                    label = "Logo Scale",
                    valueText = "${(wm.logoScale * 100).roundToInt()}%",
                    value = wm.logoScale,
                    valueRange = 0.08f..0.48f,
                    onValueChange = onLogoScaleChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "watermark_logo_scale_slider"
                )
            }

            WatermarkType.NONE -> Unit
        }

        StudioSliderRow(
            label = "Opacity",
            valueText = "${(wm.opacity * 100).roundToInt()}%",
            value = wm.opacity,
            valueRange = 0.1f..1.0f,
            onValueChange = onOpacityChange,
            onValueChangeFinished = onSliderFinished,
            testTag = "watermark_opacity_slider"
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(104.dp)
                        .height(66.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(6.dp)
                ) {
                    AnchorDot(
                        selected = wm.preset == WatermarkPositionPreset.TOP_LEFT,
                        onClick = { onSelectPreset(WatermarkPositionPreset.TOP_LEFT) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .testTag("watermark_preset_top_left")
                    )
                    AnchorDot(
                        selected = wm.preset == WatermarkPositionPreset.TOP_RIGHT,
                        onClick = { onSelectPreset(WatermarkPositionPreset.TOP_RIGHT) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .testTag("watermark_preset_top_right")
                    )
                    AnchorDot(
                        selected = wm.preset == WatermarkPositionPreset.CENTER,
                        onClick = { onSelectPreset(WatermarkPositionPreset.CENTER) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("watermark_preset_center")
                    )
                    AnchorDot(
                        selected = wm.preset == WatermarkPositionPreset.BOTTOM_LEFT,
                        onClick = { onSelectPreset(WatermarkPositionPreset.BOTTOM_LEFT) },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .testTag("watermark_preset_bottom_left")
                    )
                    AnchorDot(
                        selected = wm.preset == WatermarkPositionPreset.BOTTOM_RIGHT,
                        onClick = { onSelectPreset(WatermarkPositionPreset.BOTTOM_RIGHT) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .testTag("watermark_preset_bottom_right")
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Position: ${wm.preset.label}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenWith,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Tap an anchor or drag freely on preview.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchorDot(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (selected) primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 1.dp,
                color = if (selected) primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
