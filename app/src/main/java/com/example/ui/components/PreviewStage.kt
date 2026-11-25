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
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.graphics.StrokeCap
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
    onBeginGestureEdit: () -> Unit,
    onMoveManualBox: (String, Float, Float) -> Unit,
    onResizeManualBox: (String, Float, Float) -> Unit,
    onDeleteManualBox: (String) -> Unit,
    onMoveWatermark: (Float, Float) -> Unit,
    onSetComparingOriginal: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val asset = uiState.mediaAsset ?: return
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    var isDraggingOverlay by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05070B))
            .pointerInput(Unit) {
                detectTapGestures { onSelectManualBox(null) }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Subtle calibrated monitor dot-grid texture behind footage
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 22.dp.toPx()
            var x = spacing
            while (x < size.width) {
                var y = spacing
                while (y < size.height) {
                    drawCircle(
                        color = Color(0xFF1A2232),
                        radius = 1.dp.toPx(),
                        center = Offset(x, y)
                    )
                    y += spacing
                }
                x += spacing
            }
        }

        // Aspect-ratio-locked footage monitor
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(asset.aspectRatio.coerceIn(0.45f, 2.4f), matchHeightConstraintsFirst = false)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .testTag("preview_stage_canvas")
        ) {
            val stageWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val stageHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

            // 1. Underlying Footage (ExoPlayer for Video, Coil for Image)
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

            // Only render masks & watermark when NOT comparing original
            if (!uiState.isComparingOriginal) {
                // 2. Detected Face Overlays with Cinema Reticle Corners
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

                // 3. Manual Draggable & Resizable Mask Boxes
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
                        onDragStart = {
                            isDraggingOverlay = true
                            onBeginGestureEdit()
                        },
                        onDragEnd = { isDraggingOverlay = false },
                        onMove = { dx, dy -> onMoveManualBox(box.id, dx, dy) },
                        onResize = { dw, dh -> onResizeManualBox(box.id, dw, dh) },
                        onDelete = { onDeleteManualBox(box.id) }
                    )
                }

                // 4. Draggable Watermark Overlay
                if (uiState.watermarkConfig.type != WatermarkType.NONE) {
                    WatermarkOverlayItem(
                        uiState = uiState,
                        isEditable = uiState.activeTab == EditorTab.WATERMARK,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        onDragStart = {
                            isDraggingOverlay = true
                            onBeginGestureEdit()
                        },
                        onDragEnd = { isDraggingOverlay = false },
                        onDrag = onMoveWatermark
                    )
                }

                // 5. Center Alignment Snap Guides during Drag
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
                                color = primaryColor.copy(alpha = 0.85f),
                                start = Offset(size.width * 0.5f, 0f),
                                end = Offset(size.width * 0.5f, size.height),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                        if (snapY) {
                            drawLine(
                                color = primaryColor.copy(alpha = 0.85f),
                                start = Offset(0f, size.height * 0.5f),
                                end = Offset(size.width, size.height * 0.5f),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }
                }
            }

            // 6. Floating Monitor HUD — Top-Left Telemetry Badge
            val activeMasksCount = uiState.activeMaskRectsAt(timestampMs).size
            val showHud = uiState.isComparingOriginal || activeMasksCount > 0 || uiState.watermarkConfig.type != WatermarkType.NONE
            if (showHud) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (uiState.isComparingOriginal) Color(0xD9FF4D6D) else Color(0xB8090B10),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isComparingOriginal) Color.White.copy(alpha = 0.4f) else primaryColor.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (uiState.isComparingOriginal) {
                            "ORIGINAL"
                        } else {
                            buildString {
                                if (activeMasksCount > 0) {
                                    append("$activeMasksCount ${if (activeMasksCount == 1) "MASK" else "MASKS"} • ${uiState.maskConfig.mode.label.uppercase()}")
                                }
                                if (uiState.watermarkConfig.type != WatermarkType.NONE) {
                                    if (isNotEmpty()) append(" • ")
                                    append("WM")
                                }
                            }
                        },
                        style = TimecodeTextStyle,
                        color = if (uiState.isComparingOriginal) Color.White else primaryColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // 7. Floating Before/After Compare Button (Bottom-Right of Monitor)
            val hasAnyEdits = uiState.faceTracks.isNotEmpty() ||
                uiState.manualBoxes.isNotEmpty() ||
                uiState.watermarkConfig.type != WatermarkType.NONE
            if (hasAnyEdits) {
                Surface(
                    onClick = { onSetComparingOriginal(!uiState.isComparingOriginal) },
                    shape = CircleShape,
                    color = if (uiState.isComparingOriginal) primaryColor else Color(0xBF090B10),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isComparingOriginal) primaryColor else Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .testTag("compare_original_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isComparingOriginal) Icons.Filled.Visibility else Icons.Filled.Compare,
                            contentDescription = stringResource(R.string.cd_compare_original),
                            tint = if (uiState.isComparingOriginal) MaterialTheme.colorScheme.onPrimary else Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (uiState.isComparingOriginal) "Showing Raw" else "Compare",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.isComparingOriginal) MaterialTheme.colorScheme.onPrimary else Color.White
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
            // Pro cinema corner-bracket tracking reticle
            val reticleColor = if (isMasked) primaryColor else Color.White.copy(alpha = 0.5f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val len = (min(size.width, size.height) * 0.24f).coerceIn(8f, 22f)
                val sw = if (isMasked) 2.2.dp.toPx() else 1.4.dp.toPx()
                // Top-Left
                drawLine(reticleColor, Offset(0f, 0f), Offset(len, 0f), sw, StrokeCap.Round)
                drawLine(reticleColor, Offset(0f, 0f), Offset(0f, len), sw, StrokeCap.Round)
                // Top-Right
                drawLine(reticleColor, Offset(size.width, 0f), Offset(size.width - len, 0f), sw, StrokeCap.Round)
                drawLine(reticleColor, Offset(size.width, 0f), Offset(size.width, len), sw, StrokeCap.Round)
                // Bottom-Left
                drawLine(reticleColor, Offset(0f, size.height), Offset(len, size.height), sw, StrokeCap.Round)
                drawLine(reticleColor, Offset(0f, size.height), Offset(0f, size.height - len), sw, StrokeCap.Round)
                // Bottom-Right
                drawLine(reticleColor, Offset(size.width, size.height), Offset(size.width - len, size.height), sw, StrokeCap.Round)
                drawLine(reticleColor, Offset(size.width, size.height), Offset(size.width, size.height - len), sw, StrokeCap.Round)
            }

            Surface(
                shape = RoundedCornerShape(5.dp),
                color = if (isMasked) primaryColor else Color(0xCC090B10),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
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
                        width = if (isSelected) 2.dp else 1.2.dp,
                        color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(10.dp)
                    )
            )

            Surface(
                onClick = onDelete,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(26.dp)
                    .testTag("delete_manual_box_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_delete_box),
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = primaryColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(28.dp)
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
                        modifier = Modifier.size(14.dp)
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
    val shape = RoundedCornerShape(12.dp)

    when (maskConfig.mode) {
        MaskMode.GAUSSIAN_BLUR -> {
            val alpha = 0.56f + (maskConfig.blurIntensity * 0.36f)
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFC2DDE8).copy(alpha = alpha),
                                Color(0xFF283747).copy(alpha = alpha * 0.92f)
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
                            0 -> Color(0xE01B2432)
                            1 -> Color(0xE02D3C52)
                            2 -> Color(0xE0415670)
                            3 -> Color(0xE0253345)
                            else -> Color(0xE0556D87)
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
                            .background(Color(0xCC090B10)),
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
                            Modifier.border(
                                1.dp,
                                primaryColor.copy(alpha = 0.75f),
                                RoundedCornerShape(8.dp)
                            )
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
                            color = Color.Black.copy(alpha = 0.75f * wm.opacity),
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
                                Modifier.border(
                                    1.dp,
                                    primaryColor.copy(alpha = 0.75f),
                                    RoundedCornerShape(8.dp)
                                )
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
