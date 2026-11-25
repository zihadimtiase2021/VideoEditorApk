package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EditorUiState
import com.example.model.ExportState
import com.example.model.ImageExportFormat
import com.example.model.MediaType
import com.example.model.VideoExportQuality
import com.example.ui.theme.StudioSuccess
import com.example.ui.theme.TimecodeTextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    uiState: EditorUiState,
    onSelectVideoQuality: (VideoExportQuality) -> Unit,
    onSelectImageFormat: (ImageExportFormat) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onDismiss: () -> Unit,
    onShareExport: (Uri, String) -> Unit,
    onOpenExport: (Uri, String) -> Unit
) {
    if (uiState.exportState is ExportState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isVideo = uiState.mediaAsset?.type == MediaType.VIDEO
    val primary = MaterialTheme.colorScheme.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val export = uiState.exportState) {
                is ExportState.Configuring -> {
                    Text(
                        text = stringResource(R.string.export_sheet_title),
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Text(
                        text = stringResource(R.string.export_quality_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isVideo) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoExportQuality.entries.forEach { quality ->
                                ExportOptionRow(
                                    title = quality.label,
                                    subtitle = quality.subtitle,
                                    selected = uiState.videoExportQuality == quality,
                                    onClick = { onSelectVideoQuality(quality) },
                                    testTag = "export_quality_${quality.name.lowercase()}"
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImageExportFormat.entries.forEach { format ->
                                ExportOptionRow(
                                    title = format.label,
                                    subtitle = format.subtitle,
                                    selected = uiState.imageExportFormat == format,
                                    onClick = { onSelectImageFormat(format) },
                                    testTag = "export_format_${format.name.lowercase()}"
                                )
                            }
                        }
                    }

                    // Destination Card
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = primary
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.export_destination_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isVideo) "Movies/MaskFrame (MP4)" else "Pictures/MaskFrame (${uiState.imageExportFormat.extension.uppercase()})",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onStartExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_export_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.export_start_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is ExportState.Processing -> {
                    Text(
                        text = if (isVideo) "Rendering Video" else "Rendering Image",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = export.stage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(export.progress * 100).roundToInt()}%",
                                style = TimecodeTextStyle.copy(color = primary)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { export.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }

                    OutlinedButton(
                        onClick = onCancelExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("cancel_export_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                is ExportState.Success -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StudioSuccess,
                            modifier = Modifier.size(34.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.export_complete_title),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "${export.folderPath}/${export.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onShareExport(export.outputUri, export.mimeType) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("share_export_button"),
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_share))
                        }

                        OutlinedButton(
                            onClick = { onOpenExport(export.outputUri, export.mimeType) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("open_export_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.OpenInNew,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_open_gallery))
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("export_done_button")
                    ) {
                        Text(stringResource(R.string.action_done))
                    }
                }

                is ExportState.Error -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = "Export Failed",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = export.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Close")
                        }
                        Button(
                            onClick = onStartExport,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("retry_export_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Retry")
                        }
                    }
                }

                ExportState.Idle -> Unit
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ExportOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = primary)
            )
            Box(modifier = Modifier.weight(1f)) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
