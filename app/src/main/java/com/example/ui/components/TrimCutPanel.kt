package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.CutSegment
import com.example.model.EditorUiState
import com.example.model.MediaType
import com.example.model.WatermarkType
import com.example.model.formatTimecode
import com.example.ui.theme.StudioWarning
import com.example.ui.theme.TimecodeTextStyle
import kotlin.math.roundToLong

@Composable
fun TrimCutPanel(
    uiState: EditorUiState,
    onUpdateTrimRange: (Long, Long) -> Unit,
    onSetTrimInAtPlayhead: () -> Unit,
    onSetTrimOutAtPlayhead: () -> Unit,
    onResetTrimAndCuts: () -> Unit,
    onAddCutSegment: () -> Unit,
    onUpdateCutSegment: (String, Long, Long) -> Unit,
    onRemoveCutSegment: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val asset = uiState.mediaAsset
    if (asset == null || asset.type != MediaType.VIDEO) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Multi-track Trim & Cut timeline is active for video projects. Use Face Mask or Watermark for still photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    val trim = uiState.trimCutConfig
    val totalMs = trim.totalDurationMs.coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary
    val crimson = MaterialTheme.colorScheme.error
    val currentSeek by rememberUpdatedState(onSeekTo)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. NLE Action Bar (Set In, Set Out, Cut, Reset) + Output Duration Metric
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NleTimelineActionChip(
                label = "In",
                icon = Icons.Filled.FirstPage,
                tint = primary,
                onClick = onSetTrimInAtPlayhead,
                modifier = Modifier
                    .weight(1f)
                    .testTag("set_trim_in_button")
            )
            NleTimelineActionChip(
                label = "Out",
                icon = Icons.Filled.LastPage,
                tint = primary,
                onClick = onSetTrimOutAtPlayhead,
                modifier = Modifier
                    .weight(1f)
                    .testTag("set_trim_out_button")
            )
            NleTimelineActionChip(
                label = "Cut",
                icon = Icons.Filled.ContentCut,
                tint = crimson,
                onClick = onAddCutSegment,
                modifier = Modifier
                    .weight(1f)
                    .testTag("add_cut_segment_button")
            )
            IconButton(
                onClick = onResetTrimAndCuts,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("reset_trim_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = "Reset trim and cuts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 2. Pro Multi-Track Visual Timeline (Ruler + V1 Video + M1 Mask + W1 Watermark + Continuous Playhead)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0A0D13),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(totalMs) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            currentSeek((frac * totalMs).roundToLong())
                        }
                    }
                    .pointerInput(totalMs) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            currentSeek((frac * totalMs).roundToLong())
                        }
                    }
                    .testTag("video_filmstrip_timeline")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Timecode Ruler
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .background(Color(0xFF101520))
                            .padding(horizontal = 6.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val majorCount = 5
                            val minorPerMajor = 4
                            val totalTicks = majorCount * minorPerMajor
                            for (i in 0..totalTicks) {
                                val x = (i.toFloat() / totalTicks) * size.width
                                val isMajor = i % minorPerMajor == 0
                                val tickH = if (isMajor) 9.dp.toPx() else 4.dp.toPx()
                                drawLine(
                                    color = if (isMajor) Color(0xFF6B7F9E) else Color(0xFF2B364A),
                                    start = Offset(x, size.height),
                                    end = Offset(x, size.height - tickH),
                                    strokeWidth = if (isMajor) 1.5f else 1f
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTimecode(0L),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "OUT ${formatTimecode(trim.outputDurationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = primary
                            )
                            Text(
                                text = formatTimecode(totalMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Track V1 — Video Filmstrip Track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(Color(0xFF090B10))
                    ) {
                        if (uiState.filmstripThumbnails.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                uiState.filmstripThumbnails.forEach { bmp ->
                                    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                                    Image(
                                        bitmap = imageBitmap,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }

                        // Trim window, cut regions & grip brackets
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val startFrac = (trim.trimStartMs.toFloat() / totalMs).coerceIn(0f, 1f)
                            val endFrac = (trim.trimEndMs.toFloat() / totalMs).coerceIn(startFrac, 1f)
                            val leftX = startFrac * size.width
                            val rightX = endFrac * size.width

                            if (leftX > 0f) {
                                drawRect(Color(0xCC07090E), Offset.Zero, Size(leftX, size.height))
                            }
                            if (rightX < size.width) {
                                drawRect(Color(0xCC07090E), Offset(rightX, 0f), Size(size.width - rightX, size.height))
                            }

                            trim.cutSegments.forEach { seg ->
                                val cs = (seg.startMs.toFloat() / totalMs).coerceIn(0f, 1f) * size.width
                                val ce = (seg.endMs.toFloat() / totalMs).coerceIn(0f, 1f) * size.width
                                if (ce > cs) {
                                    drawRect(crimson.copy(alpha = 0.55f), Offset(cs, 0f), Size(ce - cs, size.height))
                                    var hx = cs
                                    while (hx < ce) {
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.35f),
                                            start = Offset(hx, size.height),
                                            end = Offset((hx + 10f).coerceAtMost(ce), 0f),
                                            strokeWidth = 1.5f
                                        )
                                        hx += 12f
                                    }
                                }
                            }

                            // Cyan bracket frame
                            drawLine(primary, Offset(leftX, 1.5f), Offset(rightX, 1.5f), 3.5f)
                            drawLine(primary, Offset(leftX, size.height - 1.5f), Offset(rightX, size.height - 1.5f), 3.5f)
                            drawRoundRect(
                                color = primary,
                                topLeft = Offset((leftX - 4f).coerceAtLeast(0f), 0f),
                                size = Size(8f, size.height),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                            drawRoundRect(
                                color = primary,
                                topLeft = Offset((rightX - 4f).coerceAtMost(size.width - 8f), 0f),
                                size = Size(8f, size.height),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }

                        // Track badge V1
                        Text(
                            text = "V1",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .background(Color(0x99090B10), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    // Track M1 — Face & Manual Privacy Mask Span Track
                    val hasMasks = uiState.faceTracks.isNotEmpty() || uiState.manualBoxes.isNotEmpty()
                    if (hasMasks) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(Color(0xFF0E131D))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val padY = 3.dp.toPx()
                                val barH = size.height - padY * 2
                                if (uiState.manualBoxes.isNotEmpty()) {
                                    drawRoundRect(
                                        color = primary.copy(alpha = 0.45f),
                                        topLeft = Offset(0f, padY),
                                        size = Size(size.width, barH),
                                        cornerRadius = CornerRadius(4f, 4f)
                                    )
                                } else {
                                    uiState.faceTracks.forEach { track ->
                                        if (uiState.maskConfig.applyToAllFaces || track.isSelected) {
                                            val firstTs = track.keyframes.firstOrNull()?.timestampMs ?: return@forEach
                                            val lastTs = track.keyframes.lastOrNull()?.timestampMs ?: return@forEach
                                            val sx = (firstTs.toFloat() / totalMs).coerceIn(0f, 1f) * size.width
                                            val ex = (lastTs.toFloat() / totalMs).coerceIn(0f, 1f) * size.width
                                            drawRoundRect(
                                                color = primary.copy(alpha = 0.65f),
                                                topLeft = Offset(sx, padY),
                                                size = Size((ex - sx).coerceAtLeast(8f), barH),
                                                cornerRadius = CornerRadius(4f, 4f)
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "M1 • ${uiState.maskConfig.mode.label.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(horizontal = 6.dp)
                            )
                        }
                    }

                    // Track W1 — Watermark Track
                    if (uiState.watermarkConfig.type != WatermarkType.NONE) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(Color(0xFF101622))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val padY = 3.dp.toPx()
                                drawRoundRect(
                                    color = StudioWarning.copy(alpha = 0.42f),
                                    topLeft = Offset(0f, padY),
                                    size = Size(size.width, size.height - padY * 2),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                            }
                            Text(
                                text = "W1 • ${uiState.watermarkConfig.type.label.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(horizontal = 6.dp)
                            )
                        }
                    }
                }

                // Continuous Vertical Playhead Needle with Inverted Triangle Cap across ALL tracks
                Canvas(modifier = Modifier.matchParentSize()) {
                    val playFrac = (uiState.currentPlaybackMs.toFloat() / totalMs).coerceIn(0f, 1f)
                    val playX = playFrac * size.width
                    val capW = 6.dp.toPx()
                    val capH = 7.dp.toPx()
                    val triangle = Path().apply {
                        moveTo(playX - capW, 0f)
                        lineTo(playX + capW, 0f)
                        lineTo(playX, capH)
                        close()
                    }
                    drawPath(triangle, color = Color.White)
                    drawLine(
                        color = Color.White,
                        start = Offset(playX, capH),
                        end = Offset(playX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }

        // 3. Precision Dual-Handle Trim Range Slider
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "IN ${formatTimecode(trim.trimStartMs)}",
                    style = TimecodeTextStyle,
                    color = primary
                )
                Text(
                    text = "OUT ${formatTimecode(trim.trimEndMs)}",
                    style = TimecodeTextStyle,
                    color = primary
                )
            }

            val startNorm = (trim.trimStartMs.toFloat() / totalMs).coerceIn(0f, 1f)
            val endNorm = (trim.trimEndMs.toFloat() / totalMs).coerceIn(startNorm, 1f)
            RangeSlider(
                value = startNorm..endNorm,
                onValueChange = { range ->
                    onUpdateTrimRange(
                        (range.start * totalMs).roundToLong(),
                        (range.endInclusive * totalMs).roundToLong()
                    )
                },
                onValueChangeFinished = {
                    onSeekTo(trim.trimStartMs)
                },
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier.testTag("trim_range_slider")
            )
        }

        // 4. Cut Segments Inspector Cards
        if (trim.cutSegments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "EXCISED SEGMENTS (${trim.cutSegments.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = crimson
                )
                trim.cutSegments.forEachIndexed { index, segment ->
                    CutSegmentCard(
                        index = index + 1,
                        segment = segment,
                        totalMs = totalMs,
                        crimson = crimson,
                        onUpdate = { s, e -> onUpdateCutSegment(segment.id, s, e) },
                        onDelete = { onRemoveCutSegment(segment.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NleTimelineActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = modifier.height(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun CutSegmentCard(
    index: Int,
    segment: CutSegment,
    totalMs: Long,
    crimson: Color,
    onUpdate: (Long, Long) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, crimson.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CUT #$index • ${formatTimecode(segment.startMs)} → ${formatTimecode(segment.endMs)}",
                    style = TimecodeTextStyle,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("delete_cut_segment_$index")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Remove cut segment",
                        tint = crimson,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val sNorm = (segment.startMs.toFloat() / totalMs).coerceIn(0f, 1f)
            val eNorm = (segment.endMs.toFloat() / totalMs).coerceIn(sNorm, 1f)
            RangeSlider(
                value = sNorm..eNorm,
                onValueChange = { range ->
                    onUpdate(
                        (range.start * totalMs).roundToLong(),
                        (range.endInclusive * totalMs).roundToLong()
                    )
                },
                colors = SliderDefaults.colors(
                    thumbColor = crimson,
                    activeTrackColor = crimson,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                modifier = Modifier.testTag("cut_segment_slider_$index")
            )
        }
    }
}
