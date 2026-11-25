package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.BatchExportState
import com.example.model.BatchItemStatus
import com.example.model.BatchQueueItem
import com.example.model.EditorUiState
import com.example.model.ImageExportFormat
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.VideoExportQuality
import com.example.model.WatermarkType
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.TimecodeTextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchExportSheet(
    uiState: EditorUiState,
    onSelectMaskMode: (MaskMode) -> Unit,
    onBlurIntensityChange: (Float) -> Unit,
    onPixelSizeChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onSelectVideoQuality: (VideoExportQuality) -> Unit,
    onSelectImageFormat: (ImageExportFormat) -> Unit,
    onSetIncludeWatermark: (Boolean) -> Unit,
    onRemoveItem: (String) -> Unit,
    onPickDifferentBatch: () -> Unit,
    onStartBatch: () -> Unit,
    onCancelBatch: () -> Unit,
    onDismiss: () -> Unit,
    onShareAll: (List<Uri>) -> Unit
) {
    if (uiState.batchExportState is BatchExportState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val primary = MaterialTheme.colorScheme.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (val batch = uiState.batchExportState) {
                is BatchExportState.Configuring -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.batch_sheet_title),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "${batch.items.size} ${if (batch.items.size == 1) "file" else "files"} queued • Auto ML Kit face detection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onPickDifferentBatch,
                            modifier = Modifier.testTag("batch_repick_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Repick")
                        }
                    }

                    // Selected Media Thumbnail Queue Strip
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        items(batch.items, key = { it.id }) { item ->
                            BatchThumbnailCard(
                                item = item,
                                onRemove = { onRemoveItem(item.id) }
                            )
                        }
                    }

                    // Shared Privacy Mask Mode Picker
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Shared Face Mask Style",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(MaskMode.entries) { mode ->
                                FilterChip(
                                    selected = uiState.maskConfig.mode == mode,
                                    onClick = { onSelectMaskMode(mode) },
                                    label = { Text(mode.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.testTag("batch_mask_${mode.name.lowercase()}")
                                )
                            }
                        }
                    }

                    if (uiState.maskConfig.mode == MaskMode.GAUSSIAN_BLUR) {
                        StudioSliderRow(
                            label = "Blur Strength",
                            valueText = "${(uiState.maskConfig.blurIntensity * 100).roundToInt()}%",
                            value = uiState.maskConfig.blurIntensity,
                            valueRange = 0.15f..1.0f,
                            onValueChange = onBlurIntensityChange,
                            onValueChangeFinished = onSliderFinished,
                            testTag = "batch_blur_slider"
                        )
                    } else if (uiState.maskConfig.mode == MaskMode.PIXELATE) {
                        StudioSliderRow(
                            label = "Mosaic Size",
                            valueText = "${(uiState.maskConfig.pixelBlockSize * 100).roundToInt()}%",
                            value = uiState.maskConfig.pixelBlockSize,
                            valueRange = 0.10f..1.0f,
                            onValueChange = onPixelSizeChange,
                            onValueChangeFinished = onSliderFinished,
                            testTag = "batch_pixel_slider"
                        )
                    }

                    // Optional Watermark Carry-Over Toggle
                    if (uiState.watermarkConfig.type != WatermarkType.NONE) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Include Active Watermark",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = if (uiState.watermarkConfig.type == WatermarkType.TEXT) {
                                            "\"${uiState.watermarkConfig.text}\" • ${uiState.watermarkConfig.preset.label}"
                                        } else {
                                            "Logo • ${uiState.watermarkConfig.preset.label}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = batch.includeWatermark,
                                    onCheckedChange = onSetIncludeWatermark,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = primary
                                    ),
                                    modifier = Modifier.testTag("batch_watermark_switch")
                                )
                            }
                        }
                    }

                    // Compact Format / Resolution Preset Chips
                    val hasImages = batch.items.any { it.mediaType == MediaType.IMAGE }
                    val hasVideos = batch.items.any { it.mediaType == MediaType.VIDEO }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasImages) {
                            ImageExportFormat.entries.forEach { fmt ->
                                FilterChip(
                                    selected = uiState.imageExportFormat == fmt,
                                    onClick = { onSelectImageFormat(fmt) },
                                    label = { Text(fmt.extension.uppercase()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                        if (hasVideos) {
                            VideoExportQuality.entries.forEach { qual ->
                                FilterChip(
                                    selected = uiState.videoExportQuality == qual,
                                    onClick = { onSelectVideoQuality(qual) },
                                    label = { Text(qual.label.substringBefore(' ')) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onStartBatch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_batch_export_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Process ${batch.items.size} ${if (batch.items.size == 1) "File" else "Files"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is BatchExportState.Processing -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Processing Batch",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "${(batch.overallProgress * 100).roundToInt()}%",
                            style = TimecodeTextStyle.copy(color = primary, fontWeight = FontWeight.Bold)
                        )
                    }

                    Text(
                        text = batch.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LinearProgressIndicator(
                        progress = { batch.overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(CircleShape),
                        color = primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(batch.items, key = { it.id }) { item ->
                            BatchItemStatusRow(item = item)
                        }
                    }

                    OutlinedButton(
                        onClick = onCancelBatch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("cancel_batch_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                is BatchExportState.Completed -> {
                    val savedCount = batch.savedUris.size
                    val totalCount = batch.items.size
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StudioSuccess,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = "Batch Complete",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "$savedCount of $totalCount saved to MaskFrame gallery folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(batch.items, key = { it.id }) { item ->
                            BatchItemStatusRow(item = item)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (batch.savedUris.isNotEmpty()) {
                            Button(
                                onClick = { onShareAll(batch.savedUris) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("batch_share_all_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_share_all))
                            }
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("batch_done_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }

                is BatchExportState.Error -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = batch.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }

                BatchExportState.Idle -> Unit
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun BatchThumbnailCard(
    item: BatchQueueItem,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        if (item.mediaType == MediaType.IMAGE) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // Media Type Pill
        Text(
            text = if (item.mediaType == MediaType.VIDEO) "VID" else "IMG",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color(0xAA0E1014), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )

        // Remove Button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .background(Color(0xCC0E1014), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_remove_batch_item),
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun BatchItemStatusRow(item: BatchQueueItem) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.mediaType == MediaType.VIDEO) Icons.Filled.Videocam else Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = when (item.status) {
                    BatchItemStatus.QUEUED -> "Queued"
                    BatchItemStatus.DETECTING_FACES -> "Detecting faces…"
                    BatchItemStatus.RENDERING -> "${item.detectedFaces} face${if (item.detectedFaces == 1) "" else "s"} • Rendering…"
                    BatchItemStatus.SAVED -> "${item.detectedFaces} face${if (item.detectedFaces == 1) "" else "s"} masked • Saved"
                    BatchItemStatus.FAILED -> item.errorMessage ?: "Failed"
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (item.status) {
                        BatchItemStatus.SAVED -> StudioSuccess
                        BatchItemStatus.FAILED -> MaterialTheme.colorScheme.error
                        BatchItemStatus.DETECTING_FACES, BatchItemStatus.RENDERING -> MaterialTheme.colorScheme.primary
                        BatchItemStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            when (item.status) {
                BatchItemStatus.QUEUED -> Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                BatchItemStatus.DETECTING_FACES, BatchItemStatus.RENDERING -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                BatchItemStatus.SAVED -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = StudioSuccess,
                    modifier = Modifier.size(18.dp)
                )
                BatchItemStatus.FAILED -> Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
