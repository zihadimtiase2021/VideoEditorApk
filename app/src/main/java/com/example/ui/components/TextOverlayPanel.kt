package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EditorUiState
import com.example.model.MediaType
import com.example.model.StudioPalettes
import com.example.model.TextOverlayStyle
import com.example.model.formatTimecode
import com.example.ui.theme.TimecodeTextStyle
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun TextOverlayPanel(
    uiState: EditorUiState,
    onAddText: () -> Unit,
    onSelectText: (String) -> Unit,
    onDeleteText: (String) -> Unit,
    onUpdateContent: (String, String) -> Unit,
    onUpdateStyle: (String, TextOverlayStyle) -> Unit,
    onUpdateFontSize: (String, Float) -> Unit,
    onUpdateColor: (String, Int) -> Unit,
    onUpdateOpacity: (String, Float) -> Unit,
    onUpdateTiming: (String, Long, Long) -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val isVideo = uiState.mediaAsset?.type == MediaType.VIDEO
    val durationMs = uiState.mediaAsset?.durationMs?.coerceAtLeast(1000L) ?: 1000L
    val selectedItem = uiState.textOverlays.firstOrNull { it.id == uiState.selectedTextOverlayId }
        ?: uiState.textOverlays.lastOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Add Caption button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.TextFields,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Captions & Titles (${uiState.textOverlays.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Button(
                onClick = onAddText,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .height(40.dp)
                    .testTag("add_text_overlay_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_add_text), style = MaterialTheme.typography.labelLarge)
            }
        }

        if (uiState.textOverlays.isEmpty() || selectedItem == null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "Add draggable captions or styled title badges. Drag directly on the preview to position them anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
            return
        }

        // Layer selector strip
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.textOverlays, key = { it.id }) { item ->
                val isSelected = item.id == selectedItem.id
                Surface(
                    onClick = { onSelectText(item.id) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = item.text.ifBlank { "Empty" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = { onDeleteText(item.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cd_delete_text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // Caption Text Input
        OutlinedTextField(
            value = selectedItem.text,
            onValueChange = { onUpdateContent(selectedItem.id, it) },
            label = { Text("Caption Text") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("text_overlay_input")
        )

        // 5 Style Presets
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TextOverlayStyle.entries) { style ->
                FilterChip(
                    selected = selectedItem.style == style,
                    onClick = { onUpdateStyle(selectedItem.id, style) },
                    label = { Text(style.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("text_style_${style.name.lowercase()}")
                )
            }
        }

        // Color Swatches
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(StudioPalettes.watermarkTextColors) { color ->
                val argb = color.toArgb()
                ColorSwatchDot(
                    color = color,
                    selected = selectedItem.textColorArgb == argb,
                    onClick = { onUpdateColor(selectedItem.id, argb) }
                )
            }
        }

        // Size, Opacity, and Video Timing Range
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StudioSliderRow(
                    label = "Font Size",
                    valueText = "${selectedItem.fontSizeSp.roundToInt()} pt",
                    value = selectedItem.fontSizeSp,
                    valueRange = 14f..56f,
                    onValueChange = { onUpdateFontSize(selectedItem.id, it) },
                    onValueChangeFinished = onSliderFinished,
                    testTag = "text_font_size_slider"
                )

                StudioSliderRow(
                    label = "Opacity",
                    valueText = "${(selectedItem.opacity * 100f).roundToInt()}%",
                    value = selectedItem.opacity,
                    valueRange = 0.2f..1.0f,
                    onValueChange = { onUpdateOpacity(selectedItem.id, it) },
                    onValueChangeFinished = onSliderFinished,
                    testTag = "text_opacity_slider"
                )

                if (isVideo) {
                    val safeEnd = selectedItem.endMs.coerceAtMost(durationMs)
                    val safeStart = selectedItem.startMs.coerceIn(0L, safeEnd)
                    val startFrac = (safeStart.toFloat() / durationMs).coerceIn(0f, 1f)
                    val endFrac = (safeEnd.toFloat() / durationMs).coerceIn(startFrac, 1f)

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Visible Window",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${formatTimecode(safeStart)} – ${formatTimecode(safeEnd)}",
                                style = TimecodeTextStyle,
                                color = primary
                            )
                        }
                        RangeSlider(
                            value = startFrac..endFrac,
                            onValueChange = { range ->
                                onUpdateTiming(
                                    selectedItem.id,
                                    (range.start * durationMs).roundToLong(),
                                    (range.endInclusive * durationMs).roundToLong()
                                )
                            },
                            onValueChangeFinished = onSliderFinished,
                            colors = SliderDefaults.colors(
                                thumbColor = primary,
                                activeTrackColor = primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                            modifier = Modifier.testTag("text_timing_range_slider")
                        )
                    }
                }
            }
        }
    }
}
