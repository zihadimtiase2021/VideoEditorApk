package com.example.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.model.EditorTab
import com.example.model.EditorUiState
import com.example.model.FaceMaskConfig
import com.example.model.FaceScanState
import com.example.model.ManualMaskBox
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.NormalizedRect
import com.example.model.WatermarkType
import com.example.ui.theme.TimecodeTextStyle
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun PreviewStage(
    uiState: EditorUiState,
    exoPlayer: ExoPlayer?,
    onToggleFaceSelection: (Int) -> Unit,
    onSelectManualBox: (String?) -> Unit,
    onEndContinuousEdit: () -> Unit,
    onMoveManualBox: (String, Float, Float) -> Unit,
    onResizeManualBox: (String, Float, Float) -> Unit,
    onDeleteManualBox: (String) -> Unit,
    onMoveWatermark: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val asset = uiState.mediaAsset ?: return
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    var isDraggingOverlay by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(Unit) {
                detectTapGestures { onSelectManualBox(null) }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Distraction-free, aspect-ratio-locked footage monitor
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(asset.aspectRatio.coerceIn(0.45f, 2.4f), matchHeightConstraintsFirst = false)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .testTag("preview_stage_canvas")
        ) {
            val stageWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val stageHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

            // 1. Underlying Footage (ExoPlayer or Coil)
            if (asset.type == MediaType.VIDEO && exoPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view ->
                        if (view.player !== exoPlayer) {
                            view.player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = asset.uri,
                    contentDescription = asset.displayName,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }

            val timestampMs = if (asset.type == MediaType.VIDEO) uiState.currentPlaybackMs else 0L

            // Render masks & watermark only in edited ("After") view
            if (!uiState.isComparingOriginal) {
                for (track in uiState.faceTracks) {
                    val rect = track.rectAt(timestampMs) ?: continue
                    val isMasked = uiState.maskConfig.applyToAllFaces || track.isSelected
                    FaceOverlayItem(
                        rect = rect,
                        label = track.label,
                        isMasked = isMasked,
                        showSelectionChrome = uiState.activeTab == EditorTab.FACE_MASK,
                        maskConfig = uiState.maskConfig,
                        stickerBitmap = uiState.stickerBitmap,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        onClick = { onToggleFaceSelection(track.id) }
                    )
                }

                for (box in uiState.manualBoxes) {
                    ManualBoxOverlayItem(
                        box = box,
                        isSelected = uiState.selectedManualBoxId == box.id && uiState.activeTab == EditorTab.FACE_MASK,
                        showHandles = uiState.activeTab == EditorTab.FACE_MASK,
                        maskConfig = uiState.maskConfig,
                        stickerBitmap = uiState.stickerBitmap,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        onSelect = { onSelectManualBox(box.id) },
                        onDragStart = { isDraggingOverlay = true },
                        onDragEnd = {
                            isDraggingOverlay = false
                            onEndContinuousEdit()
                        },
                        onMove = { dx, dy -> onMoveManualBox(box.id, dx, dy) },
                        onResize = { dw, dh -> onResizeManualBox(box.id, dw, dh) },
                        onDelete = { onDeleteManualBox(box.id) }
                    )
                }

                if (uiState.watermarkConfig.type != WatermarkType.NONE) {
                    WatermarkOverlayItem(
                        uiState = uiState,
                        isEditable = uiState.activeTab == EditorTab.WATERMARK,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        onDragStart = { isDraggingOverlay = true },
                        onDragEnd = {
                            isDraggingOverlay = false
                            onEndContinuousEdit()
                        },
                        onDrag = onMoveWatermark
                    )
                }

                // Subtle center alignment hairlines while dragging
                if (isDraggingOverlay) {
                    val wm = uiState.watermarkConfig
                    val selectedBox = uiState.manualBoxes.firstOrNull { it.id == uiState.selectedManualBoxId }
                    val snapX = when {
                        uiState.activeTab == EditorTab.WATERMARK -> abs(wm.normalizedX - 0.5f) < 0.03f
                        selectedBox != null -> abs(selectedBox.rect.centerX - 0.5f) < 0.03f
                        else -> false
                    }
                    val snapY = when {
                        uiState.activeTab == EditorTab.WATERMARK -> abs(wm.normalizedY - 0.5f) < 0.03f
                        selectedBox != null -> abs(selectedBox.rect.centerY - 0.5f) < 0.03f
                        else -> false
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (snapX) {
                            drawLine(
                                color = primaryColor.copy(alpha = 0.8f),
                                start = Offset(size.width * 0.5f, 0f),
                                end = Offset(size.width * 0.5f, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        if (snapY) {
                            drawLine(
                                color = primaryColor.copy(alpha = 0.8f),
                                start = Offset(0f, size.height * 0.5f),
                                end = Offset(size.width, size.height * 0.5f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
                }
            }

            // Instant ORIGINAL Indicator Badge when viewing Before state
            if (uiState.isComparingOriginal) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryColor,
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .testTag("original_preview_badge")
                ) {
                    Text(
                        text = "ORIGINAL",
                        style = TimecodeTextStyle.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            // Live Auto-Detect & Blur floating pill on the footage monitor during ML Kit scan
            val scanState = uiState.faceScanState
            if (scanState is FaceScanState.Scanning) {
                val percent = (scanState.progress * 100f).roundToInt().coerceIn(0, 100)
                val liveFaces = uiState.faceTracks.size
                val statusLabel = buildString {
                    append(if (scanState.isAutoImportScan) "Auto-Detect & Blur" else "Scanning Faces")
                    if (asset.type == MediaType.VIDEO) {
                        append(" $percent%")
                    }
                    if (liveFaces > 0) {
                        append(" • $liveFaces ${if (liveFaces == 1) "face" else "faces"}")
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = Color(0xDD14171D),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .testTag("preview_scanning_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = primaryColor,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceOverlayItem(
    rect: NormalizedRect,
    label: String,
    isMasked: Boolean,
    showSelectionChrome: Boolean,
    maskConfig: FaceMaskConfig,
    stickerBitmap: android.graphics.Bitmap?,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val boxW = (rect.width * stageWidthPx).coerceAtLeast(28f)
    val boxH = (rect.height * stageHeightPx).coerceAtLeast(28f)
    val leftPx = (rect.left * stageWidthPx).roundToInt()
    val topPx = (rect.top * stageHeightPx).roundToInt()
    val widthDp = with(density) { boxW.toDp() }
    val heightDp = with(density) { boxH.toDp() }
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .offset { IntOffset(leftPx, topPx) }
            .size(widthDp, heightDp)
            .clickable(onClick = onClick)
            .testTag("face_overlay_${label.lowercase().replace(' ', '_')}")
    ) {
        if (isMasked) {
            MaskVisualFill(
                maskConfig = maskConfig,
                stickerBitmap = stickerBitmap,
                boxWidthPx = boxW,
                boxHeightPx = boxH,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showSelectionChrome) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = if (isMasked) 1.25.dp else 1.dp,
                        color = if (isMasked) primaryColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp)
                    )
            )
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = if (isMasked) primaryColor else Color(0xCC14171D),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = if (isMasked) label else "$label Off",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMasked) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun ManualBoxOverlayItem(
    box: ManualMaskBox,
    isSelected: Boolean,
    showHandles: Boolean,
    maskConfig: FaceMaskConfig,
    stickerBitmap: android.graphics.Bitmap?,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val currentMove by rememberUpdatedState(onMove)
    val currentResize by rememberUpdatedState(onResize)
    val boxW = (box.rect.width * stageWidthPx).coerceAtLeast(36f)
    val boxH = (box.rect.height * stageHeightPx).coerceAtLeast(36f)
    val leftPx = (box.rect.left * stageWidthPx).roundToInt()
    val topPx = (box.rect.top * stageHeightPx).roundToInt()
    val widthDp = with(density) { boxW.toDp() }
    val heightDp = with(density) { boxH.toDp() }
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .offset { IntOffset(leftPx, topPx) }
            .size(widthDp, heightDp)
            .pointerInput(box.id, stageWidthPx, stageHeightPx, showHandles) {
                if (!showHandles) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        onSelect()
                        onDragStart()
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentMove(dragAmount.x / stageWidthPx, dragAmount.y / stageHeightPx)
                    }
                )
            }
            .testTag("manual_mask_box")
    ) {
        MaskVisualFill(
            maskConfig = maskConfig,
            stickerBitmap = stickerBitmap,
            boxWidthPx = boxW,
            boxHeightPx = boxH,
            modifier = Modifier.fillMaxSize()
        )

        if (showHandles) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(10.dp)
                    )
            )

            Surface(
                onClick = onDelete,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(24.dp)
                    .testTag("delete_manual_box_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_delete_box),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = primaryColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(26.dp)
                    .pointerInput(box.id, stageWidthPx, stageHeightPx) {
                        detectDragGestures(
                            onDragStart = {
                                onSelect()
                                onDragStart()
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentResize(dragAmount.x / stageWidthPx, dragAmount.y / stageHeightPx)
                            }
                        )
                    }
                    .testTag("resize_manual_box_handle")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.OpenInFull,
                        contentDescription = stringResource(R.string.cd_resize_box),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MaskVisualFill(
    maskConfig: FaceMaskConfig,
    stickerBitmap: android.graphics.Bitmap?,
    boxWidthPx: Float,
    boxHeightPx: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(10.dp)

    when (maskConfig.mode) {
        MaskMode.GAUSSIAN_BLUR -> {
            val alpha = 0.56f + (maskConfig.blurIntensity * 0.36f)
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFD4CFC7).copy(alpha = alpha),
                                Color(0xFF333740).copy(alpha = alpha * 0.92f)
                            )
                        )
                    )
            )
        }

        MaskMode.PIXELATE -> {
            val cols = (12 - (maskConfig.pixelBlockSize * 8f).roundToInt()).coerceIn(4, 12)
            Canvas(modifier = modifier.clip(shape)) {
                val cellW = size.width / cols
                val rows = (size.height / cellW).roundToInt().coerceAtLeast(3)
                val cellH = size.height / rows
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val shade = ((r * 31 + c * 17) % 5)
                        val tileColor = when (shade) {
                            0 -> Color(0xE01F232B)
                            1 -> Color(0xE0323844)
                            2 -> Color(0xE0474E5E)
                            3 -> Color(0xE0292E38)
                            else -> Color(0xE05B6273)
                        }
                        drawRect(
                            color = tileColor,
                            topLeft = Offset(c * cellW, r * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f)
                        )
                    }
                }
            }
        }

        MaskMode.SOLID_COLOR -> {
            Canvas(modifier = modifier) {
                val radius = min(size.width, size.height) * 0.14f
                drawRoundRect(
                    color = Color(maskConfig.solidColorArgb),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }

        MaskMode.EMOJI -> {
            val emojiSp = with(density) { (min(boxWidthPx, boxHeightPx) * 0.72f).toSp() }
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = maskConfig.selectedEmoji,
                    fontSize = emojiSp,
                    textAlign = TextAlign.Center
                )
            }
        }

        MaskMode.STICKER -> {
            val imageBitmap = remember(stickerBitmap) { stickerBitmap?.asImageBitmap() }
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                if (imageBitmap != null) {
                    val scale = maskConfig.stickerScale.coerceIn(0.7f, 2.0f)
                    val targetW = with(density) { (boxWidthPx * scale).toDp() }
                    val targetH = with(density) { (boxHeightPx * scale).toDp() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.cd_sticker_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(targetW, targetH)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .background(Color(0xCC14171D)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Pick\nSticker",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatermarkOverlayItem(
    uiState: EditorUiState,
    isEditable: Boolean,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val wm = uiState.watermarkConfig
    val density = LocalDensity.current
    val currentDrag by rememberUpdatedState(onDrag)
    val primaryColor = MaterialTheme.colorScheme.primary

    val minDim = min(stageWidthPx, stageHeightPx)
    val centerPxX = wm.normalizedX * stageWidthPx
    val centerPxY = wm.normalizedY * stageHeightPx

    val dragModifier = Modifier.pointerInput(isEditable, stageWidthPx, stageHeightPx) {
        if (!isEditable) return@pointerInput
        detectDragGestures(
            onDragStart = { onDragStart() },
            onDragEnd = { onDragEnd() },
            onDragCancel = { onDragEnd() },
            onDrag = { change, dragAmount ->
                change.consume()
                currentDrag(dragAmount.x / stageWidthPx, dragAmount.y / stageHeightPx)
            }
        )
    }

    when (wm.type) {
        WatermarkType.TEXT -> {
            val scaledSp = with(density) { ((wm.fontSizeSp / 360f) * minDim * 0.92f).coerceAtLeast(12f).toSp() }
            val estWidthPx = (wm.text.length.coerceAtLeast(4) * scaledSp.value * 0.72f * density.density)
                .coerceIn(90f, stageWidthPx * 0.9f)
            val estHeightPx = (scaledSp.value * 1.6f * density.density).coerceAtLeast(36f)
            val offsetX = (centerPxX - estWidthPx * 0.5f).roundToInt()
            val offsetY = (centerPxY - estHeightPx * 0.5f).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX, offsetY) }
                    .size(
                        width = with(density) { estWidthPx.toDp() },
                        height = with(density) { estHeightPx.toDp() }
                    )
                    .then(dragModifier)
                    .then(
                        if (isEditable) {
                            Modifier.border(1.dp, primaryColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        }
                    )
                    .testTag("watermark_overlay_item"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = wm.text,
                    color = Color(wm.textColorArgb).copy(alpha = wm.opacity),
                    fontSize = scaledSp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.72f * wm.opacity),
                            offset = Offset(0f, 3f),
                            blurRadius = 6f
                        )
                    )
                )
            }
        }

        WatermarkType.IMAGE -> {
            val logoBitmap = remember(uiState.watermarkLogoBitmap) {
                uiState.watermarkLogoBitmap?.asImageBitmap()
            }
            if (logoBitmap != null) {
                val targetW = stageWidthPx * wm.logoScale.coerceIn(0.08f, 0.48f)
                val aspect = logoBitmap.width.toFloat() / logoBitmap.height.coerceAtLeast(1)
                val targetH = targetW / aspect
                val offsetX = (centerPxX - targetW * 0.5f).roundToInt()
                val offsetY = (centerPxY - targetH * 0.5f).roundToInt()

                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX, offsetY) }
                        .size(
                            width = with(density) { targetW.toDp() },
                            height = with(density) { targetH.toDp() }
                        )
                        .then(dragModifier)
                        .then(
                            if (isEditable) {
                                Modifier.border(1.dp, primaryColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
                        .testTag("watermark_overlay_item"),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = logoBitmap,
                        contentDescription = stringResource(R.string.cd_watermark_logo),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(wm.opacity)
                    )
                }
            }
        }

        WatermarkType.NONE -> Unit
    }
}
