package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Square
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.EditorUiState
import com.example.model.FaceScanState
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.ScanAccuracy
import com.example.model.StudioPalettes
import com.example.ui.theme.TimecodeTextStyle
import kotlin.math.roundToInt

@Composable
fun FaceMaskPanel(
    uiState: EditorUiState,
    onSelectAccuracy: (ScanAccuracy) -> Unit,
    onScanFaces: () -> Unit,
    onCancelScan: () -> Unit,
    onAddManualBox: () -> Unit,
    onSetApplyToAll: (Boolean) -> Unit,
    onToggleFaceTrack: (Int) -> Unit,
    onSelectMaskMode: (MaskMode) -> Unit,
    onBlurIntensityChange: (Float) -> Unit,
    onPixelSizeChange: (Float) -> Unit,
    onSolidColorChange: (Int) -> Unit,
    onEmojiSelected: (String) -> Unit,
    onPickSticker: () -> Unit,
    onStickerScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = uiState.mediaAsset?.type == MediaType.VIDEO
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Face Detection Bar (On-Demand Scan with Accuracy Presets for Video, Instant/Rescan for Photo)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isScanning = uiState.faceScanState is FaceScanState.Scanning
                    Button(
                        onClick = if (isScanning) onCancelScan else onScanFaces,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("scan_faces_button"),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScanning) MaterialTheme.colorScheme.errorContainer else primary,
                            contentColor = if (isScanning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = if (isScanning) Icons.Filled.Close else Icons.Filled.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isScanning -> "Stop Scan"
                                uiState.faceTracks.isNotEmpty() -> stringResource(R.string.action_rescan_faces)
                                else -> stringResource(R.string.action_scan_faces)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onAddManualBox,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("add_manual_box_button"),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddBox,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_add_manual_box),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Accuracy Preset Selector (Video mode)
                if (isVideo) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ScanAccuracy.entries.forEach { preset ->
                            val selected = uiState.scanAccuracy == preset
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectAccuracy(preset) },
                                label = {
                                    Text(
                                        text = "${preset.label} (${preset.fps}fps)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scan_accuracy_${preset.name.lowercase()}"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                // Live Scanning Progress Bar
                when (val scan = uiState.faceScanState) {
                    is FaceScanState.Scanning -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Analyzing frames with ML Kit...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = primary
                                )
                                Text(
                                    text = "${scan.framesScanned}/${scan.totalFrames}",
                                    style = TimecodeTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LinearProgressIndicator(
                                progress = { scan.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape),
                                color = primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerLowest
                            )
                        }
                    }

                    is FaceScanState.Completed -> {
                        val manualCount = uiState.manualBoxes.size
                        Text(
                            text = "${scan.faceCount} ${if (scan.faceCount == 1) "face" else "faces"} tracked" +
                                if (manualCount > 0) " • $manualCount manual box${if (manualCount > 1) "es" else ""}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        if (isVideo) {
                            Text(
                                text = "Tap Scan Faces to detect & track faces, or add a manual privacy box.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 2. Scope Toggle: All Faces vs Selected Faces
        if (uiState.faceTracks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Apply To",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = uiState.maskConfig.applyToAllFaces,
                        onClick = { onSetApplyToAll(true) },
                        label = { Text(stringResource(R.string.scope_all_faces)) },
                        modifier = Modifier.testTag("scope_all_faces_chip"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = !uiState.maskConfig.applyToAllFaces,
                        onClick = { onSetApplyToAll(false) },
                        label = { Text(stringResource(R.string.scope_selected_faces)) },
                        modifier = Modifier.testTag("scope_selected_faces_chip"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }

                if (!uiState.maskConfig.applyToAllFaces) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.faceTracks, key = { it.id }) { track ->
                            FilterChip(
                                selected = track.isSelected,
                                onClick = { onToggleFaceTrack(track.id) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (track.isSelected) Icons.Filled.Check else Icons.Filled.Face,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text(track.label) },
                                modifier = Modifier.testTag("face_chip_${track.id}")
                            )
                        }
                    }
                }
            }
        }

        // 3. Masking Mode Selector (Blur, Mosaic, Solid, Emoji, Sticker)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Mask Style",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MaskMode.entries) { mode ->
                    val selected = uiState.maskConfig.mode == mode
                    MaskModePill(
                        label = mode.label,
                        icon = maskModeIcon(mode),
                        selected = selected,
                        onClick = { onSelectMaskMode(mode) },
                        modifier = Modifier.testTag("mask_mode_${mode.name.lowercase()}")
                    )
                }
            }
        }

        // 4. Active Mask Mode Parameters
        when (uiState.maskConfig.mode) {
            MaskMode.GAUSSIAN_BLUR -> {
                StudioSliderRow(
                    label = "Blur Intensity",
                    valueText = "${(uiState.maskConfig.blurIntensity * 100).roundToInt()}%",
                    value = uiState.maskConfig.blurIntensity,
                    valueRange = 0.15f..1.0f,
                    onValueChange = onBlurIntensityChange,
                    testTag = "blur_intensity_slider"
                )
            }

            MaskMode.PIXELATE -> {
                StudioSliderRow(
                    label = "Mosaic Block Size",
                    valueText = "${(uiState.maskConfig.pixelBlockSize * 100).roundToInt()}%",
                    value = uiState.maskConfig.pixelBlockSize,
                    valueRange = 0.10f..1.0f,
                    onValueChange = onPixelSizeChange,
                    testTag = "pixel_size_slider"
                )
            }

            MaskMode.SOLID_COLOR -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Box Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(StudioPalettes.maskSolidColors) { color ->
                            val argb = color.toArgb()
                            val selected = uiState.maskConfig.solidColorArgb == argb
                            ColorSwatchDot(
                                color = color,
                                selected = selected,
                                onClick = { onSolidColorChange(argb) }
                            )
                        }
                    }
                }
            }

            MaskMode.EMOJI -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Choose Privacy Emoji",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StudioPalettes.privacyEmojis) { emoji ->
                            val selected = uiState.maskConfig.selectedEmoji == emoji
                            Surface(
                                onClick = { onEmojiSelected(emoji) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("emoji_option_$emoji")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = emoji, fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
            }

            MaskMode.STICKER -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.maskConfig.stickerUri != null) "Custom Sticker Loaded" else "No sticker selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = onPickSticker,
                            modifier = Modifier.testTag("pick_sticker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (uiState.maskConfig.stickerUri != null) "Change PNG/JPG" else "Pick Sticker")
                        }
                    }
                    StudioSliderRow(
                        label = "Sticker Scale",
                        valueText = "${(uiState.maskConfig.stickerScale * 100).roundToInt()}%",
                        value = uiState.maskConfig.stickerScale,
                        valueRange = 0.7f..2.0f,
                        onValueChange = onStickerScaleChange,
                        testTag = "sticker_scale_slider"
                    )
                }
            }
        }
    }
}

private fun maskModeIcon(mode: MaskMode): ImageVector = when (mode) {
    MaskMode.GAUSSIAN_BLUR -> Icons.Filled.BlurOn
    MaskMode.PIXELATE -> Icons.Filled.GridOn
    MaskMode.SOLID_COLOR -> Icons.Filled.Square
    MaskMode.EMOJI -> Icons.Filled.EmojiEmotions
    MaskMode.STICKER -> Icons.Filled.Image
}

@Composable
private fun MaskModePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun ColorSwatchDot(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
    )
}

@Composable
internal fun StudioSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = valueText,
                style = TimecodeTextStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
