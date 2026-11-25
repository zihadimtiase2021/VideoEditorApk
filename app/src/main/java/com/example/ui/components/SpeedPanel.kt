package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.EditorUiState
import com.example.model.MediaType
import com.example.model.StudioPalettes
import com.example.model.formatTimecode
import com.example.ui.theme.TimecodeTextStyle
import java.util.Locale
import kotlin.math.abs

@Composable
fun SpeedPanel(
    uiState: EditorUiState,
    onSelectSpeedPreset: (Float) -> Unit,
    onSpeedSliderChange: (Float) -> Unit,
    onTogglePreservePitch: (Boolean) -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = uiState.mediaAsset?.type == MediaType.VIDEO
    val speed = uiState.speedConfig
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isVideo) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Playback speed controls (0.25x to 4x) apply to videos. Open a video clip to adjust tempo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        // Recalculated output duration badge
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(19.dp)
                    )
                    Column {
                        Text(
                            text = "Playback Speed",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Output: ${formatTimecode(uiState.effectiveVideoOutputDurationMs())}",
                            style = TimecodeTextStyle,
                            color = primary
                        )
                    }
                }
                Text(
                    text = String.format(Locale.US, "%.2fx", speed.speedMultiplier),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = primary
                )
            }
        }

        // Preset speed chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StudioPalettes.speedPresets) { preset ->
                val selected = abs(speed.speedMultiplier - preset) < 0.03f
                val label = if (preset == preset.toInt().toFloat()) {
                    "${preset.toInt()}x"
                } else {
                    "${preset}x"
                }
                FilterChip(
                    selected = selected,
                    onClick = { onSelectSpeedPreset(preset) },
                    label = {
                        Text(
                            text = label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("speed_preset_$label")
                )
            }
        }

        // Precision speed slider + Keep Pitch
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
                    label = "Fine Speed",
                    valueText = String.format(Locale.US, "%.2fx", speed.speedMultiplier),
                    value = speed.speedMultiplier,
                    valueRange = 0.25f..4.0f,
                    onValueChange = onSpeedSliderChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "speed_fine_slider"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTogglePreservePitch(!speed.preservePitch) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Keep Voice Pitch",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Prevents chipmunk or deep distortion when exported",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = speed.preservePitch,
                        onCheckedChange = onTogglePreservePitch,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = primary
                        ),
                        modifier = Modifier.testTag("speed_preserve_pitch_switch")
                    )
                }
            }
        }
    }
}
