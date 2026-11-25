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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.model.FaceScanState
import com.example.model.ManualMaskBox
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.NormalizedRect
import com.example.model.ResolvedMaskInstance
import com.example.model.TextOverlayItem
import com.example.model.TextOverlayStyle
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
    onSelectFaceForEditing: (Int) -> Unit,
    onSelectManualBox: (String?) -> Unit,
    onSelectTextOverlay: (String?) -> Unit,
    onEndContinuousEdit: () -> Unit,
    onMoveManualBox: (String, Float, Float) -> Unit,
    onResizeManualBox: (String, Float, Float) -> Unit,
    onDeleteManualBox: (String) -> Unit,
    onMoveTextOverlay: (String, Float, Float) -> Unit,
    onDeleteTextOverlay: (String) -> Unit,
    onMoveWatermark: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val asset = uiState.mediaAsset ?: return
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    var isDraggingOverlay by remember { mutableStateOf(false) }

    val showEdited = !uiState.isComparingOriginal
    val transform = uiState.transformConfig
    val colorGrade = uiState.colorGradeConfig
    val displayAspect = if (showEdited) {
        transform.effectiveAspectRatio(asset.width, asset.height)
    } else {
        asset.aspectRatio
    }.coerceIn(0.45f, 2.4f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(Unit) {
                detectTapGestures {
                    onSelectManualBox(null)
                    onSelectTextOverlay(null)
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-ratio-locked footage monitor
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .testTag("preview_stage_canvas")
        ) {
            val stageWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val stageHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val timestampMs = if (asset.type == MediaType.VIDEO) uiState.currentPlaybackMs else 0L

            // Pre-transform footage + privacy masks layer (inherits Rotate, Flip, Zoom)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (showEdited) {
                            val zoom = transform.zoomScale.coerceIn(1f, 2f)
                            scaleX = zoom * (if (transform.flipHorizontal) -1f else 1f)
                            scaleY = zoom * (if (transform.flipVertical) -1f else 1f)
                            rotationZ = transform.rotationDegrees.toFloat()
                        }
                    }
            ) {
                // 1. Underlying Footage (ExoPlayer or Coil)
                if (asset.type == MediaType.VIDEO && exoPlayer != null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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
                    val imgColorFilter = remember(showEdited, colorGrade) {
                        if (showEdited && !colorGrade.isDefault()) {
                            ColorFilter.colorMatrix(ColorMatrix(colorGrade.toColorMatrix20()))
                        } else {
                            null
                        }
                    }
                    AsyncImage(
                        model = asset.uri,
                        contentDescription = asset.displayName,
                        contentScale = ContentScale.Crop,
                        colorFilter = imgColorFilter,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 2. Live Color Grade Overlay (for Video preview tint/brightness/desat + Vignette for both)
                if (showEdited && !colorGrade.isDefault()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (asset.type == MediaType.VIDEO) {
                            if (colorGrade.brightness > 0.01f) {
                                drawRect(Color.White.copy(alpha = (colorGrade.brightness * 0.55f).coerceIn(0f, 0.35f)))
                            } else if (colorGrade.brightness < -0.01f) {
                                drawRect(Color.Black.copy(alpha = (-colorGrade.brightness * 0.7f).coerceIn(0f, 0.45f)))
                            }
                            if (colorGrade.warmth > 0.02f) {
                                drawRect(Color(0xFFFFA73B).copy(alpha = (colorGrade.warmth * 0.28f).coerceIn(0f, 0.22f)))
                            } else if (colorGrade.warmth < -0.02f) {
                                drawRect(Color(0xFF4F8FF7).copy(alpha = (-colorGrade.warmth * 0.28f).coerceIn(0f, 0.22f)))
                            }
                            if (colorGrade.saturation < 0.25f) {
                                // Subtle monochrome cinema wash for Noir preview on video
                                drawRect(Color(0xFF14171D).copy(alpha = ((0.25f - colorGrade.saturation) * 0.65f).coerceIn(0f, 0.22f)))
                            }
                        }
                        if (colorGrade.vignette > 0.01f) {
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Transparent,
                                        Color.Black.copy(alpha = (colorGrade.vignette * 0.92f).coerceIn(0f, 0.85f))
                                    ),
                                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                                    radius = maxOf(size.width, size.height) * 0.74f
                                )
                            )
                        }
                    }
                }

                // 3. Privacy Masks (Per-Face & Manual)
                if (showEdited) {
                    val activeResolved = uiState.resolvedMasksAt(timestampMs)
                    val spotlightMasks = activeResolved.filter { it.mode == MaskMode.SPOTLIGHT }

                    // Spotlight inverted mask layer: dims/frosted everything OUTSIDE spotlight faces
                    if (spotlightMasks.isNotEmpty()) {
                        val spotStrength = spotlightMasks.maxOf { it.blurIntensity }
                        val spotOpacity = spotlightMasks.maxOf { it.opacity }
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        ) {
                            drawRect(
                                color = Color(0xFF0B0D12).copy(
                                    alpha = ((0.48f + spotStrength * 0.42f) * spotOpacity).coerceIn(0.2f, 0.92f)
                                )
                            )
                            for (spot in spotlightMasks) {
                                val r = spot.rect
                                val w = (r.width * size.width).coerceAtLeast(12f)
                                val h = (r.height * size.height).coerceAtLeast(12f)
                                val path = buildMaskComposePath(spot.shape, Size(w, h))
                                translate(left = r.left * size.width, top = r.top * size.height) {
                                    drawPath(path = path, color = Color.Transparent, blendMode = BlendMode.Clear)
                                }
                            }
                        }
                    }

                    for (track in uiState.faceTracks) {
                        val baseRect = track.rectAt(timestampMs) ?: continue
                        val isMasked = uiState.maskConfig.applyToAllFaces || track.isSelected
                        val resolved = uiState.resolveTrackMask(track, baseRect)
                        val isFocused = uiState.selectedFaceTrackId == track.id
                        FaceOverlayItem(
                            resolved = resolved,
                            label = track.label,
                            isMasked = isMasked,
                            isFocused = isFocused,
                            hasCustomStyle = track.styleOverride?.hasAnyOverride() == true,
                            showSelectionChrome = uiState.activeTab == EditorTab.FACE_MASK,
                            stickerBitmap = uiState.stickerBitmap,
                            stageWidthPx = stageWidthPx,
                            stageHeightPx = stageHeightPx,
                            onClick = { onSelectFaceForEditing(track.id) }
                        )
                    }

                    for (box in uiState.manualBoxes) {
                        val resolved = ResolvedMaskInstance(
                            rect = box.rect,
                            mode = uiState.maskConfig.mode,
                            shape = uiState.maskConfig.shape,
                            blurIntensity = uiState.maskConfig.blurIntensity,
                            pixelBlockSize = uiState.maskConfig.pixelBlockSize,
                            feather = uiState.maskConfig.feather,
                            opacity = uiState.maskConfig.opacity,
                            solidColorArgb = uiState.maskConfig.solidColorArgb,
                            selectedEmoji = uiState.maskConfig.selectedEmoji,
                            stickerScale = uiState.maskConfig.stickerScale
                        )
                        ManualBoxOverlayItem(
                            box = box,
                            resolved = resolved,
                            isSelected = uiState.selectedManualBoxId == box.id && uiState.activeTab == EditorTab.FACE_MASK,
                            showHandles = uiState.activeTab == EditorTab.FACE_MASK,
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
                }
            }

            // 4. Post-Transform Overlays: Styled Text Captions & Watermark
            if (showEdited) {
                val isVideo = asset.type == MediaType.VIDEO
                for (textItem in uiState.textOverlays) {
                    if (!textItem.isVisibleAt(timestampMs, isVideo)) continue
                    TextCaptionOverlayView(
                        item = textItem,
                        isEditable = uiState.activeTab == EditorTab.TEXT,
                        isSelected = uiState.selectedTextOverlayId == textItem.id && uiState.activeTab == EditorTab.TEXT,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        onSelect = { onSelectTextOverlay(textItem.id) },
                        onDragStart = { isDraggingOverlay = true },
                        onDragEnd = {
                            isDraggingOverlay = false
                            onEndContinuousEdit()
                        },
                        onDrag = { dx, dy -> onMoveTextOverlay(textItem.id, dx, dy) },
                        onDelete = { onDeleteTextOverlay(textItem.id) }
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

                // Subtle center alignment hairlines while dragging overlays
                if (isDraggingOverlay) {
                    val wm = uiState.watermarkConfig
                    val selectedBox = uiState.manualBoxes.firstOrNull { it.id == uiState.selectedManualBoxId }
                    val selectedText = uiState.textOverlays.firstOrNull { it.id == uiState.selectedTextOverlayId }
                    val snapX = when {
                        uiState.activeTab == EditorTab.WATERMARK -> abs(wm.normalizedX - 0.5f) < 0.03f
                        uiState.activeTab == EditorTab.TEXT && selectedText != null -> abs(selectedText.normalizedX - 0.5f) < 0.03f
                        selectedBox != null -> abs(selectedBox.rect.centerX - 0.5f) < 0.03f
                        else -> false
                    }
                    val snapY = when {
                        uiState.activeTab == EditorTab.WATERMARK -> abs(wm.normalizedY - 0.5f) < 0.03f
                        uiState.activeTab == EditorTab.TEXT && selectedText != null -> abs(selectedText.normalizedY - 0.5f) < 0.03f
                        selectedBox != null -> abs(selectedBox.rect.centerY - 0.5f) < 0.03f
                        else -> false
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (snapX) {
                            drawLine(
                                color = primaryColor.copy(alpha = 0.85f),
                                start = Offset(size.width * 0.5f, 0f),
                                end = Offset(size.width * 0.5f, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        if (snapY) {
                            drawLine(
                                color = primaryColor.copy(alpha = 0.85f),
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
    resolved: ResolvedMaskInstance,
    label: String,
    isMasked: Boolean,
    isFocused: Boolean,
    hasCustomStyle: Boolean,
    showSelectionChrome: Boolean,
    stickerBitmap: android.graphics.Bitmap?,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val rect = resolved.rect
    val boxW = (rect.width * stageWidthPx).coerceAtLeast(28f)
    val boxH = (rect.height * stageHeightPx).coerceAtLeast(28f)
    val leftPx = (rect.left * stageWidthPx).roundToInt()
    val topPx = (rect.top * stageHeightPx).roundToInt()
    val widthDp = with(density) { boxW.toDp() }
    val heightDp = with(density) { boxH.toDp() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val clipShape = remember(resolved.shape) { maskComposeShape(resolved.shape) }

    Box(
        modifier = Modifier
            .offset { IntOffset(leftPx, topPx) }
            .size(widthDp, heightDp)
            .clickable(onClick = onClick)
            .testTag("face_overlay_${label.lowercase().replace(' ', '_')}")
    ) {
        if (isMasked && resolved.mode != MaskMode.SPOTLIGHT) {
            MaskVisualFill(
                resolved = resolved,
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
                        width = if (isFocused) 2.dp else if (isMasked) 1.25.dp else 1.dp,
                        color = when {
                            isFocused -> primaryColor
                            isMasked -> primaryColor.copy(alpha = 0.72f)
                            else -> Color.White.copy(alpha = 0.45f)
                        },
                        shape = clipShape
                    )
            )
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = if (isFocused || isMasked) primaryColor else Color(0xCC14171D),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = buildString {
                        append(label)
                        if (!isMasked) append(" Off")
                        else if (hasCustomStyle) append(" ★")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused || isMasked) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun ManualBoxOverlayItem(
    box: ManualMaskBox,
    resolved: ResolvedMaskInstance,
    isSelected: Boolean,
    showHandles: Boolean,
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
    val clipShape = remember(resolved.shape) { maskComposeShape(resolved.shape) }

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
        if (resolved.mode != MaskMode.SPOTLIGHT) {
            MaskVisualFill(
                resolved = resolved,
                stickerBitmap = stickerBitmap,
                boxWidthPx = boxW,
                boxHeightPx = boxH,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showHandles) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.65f),
                        shape = clipShape
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
    resolved: ResolvedMaskInstance,
    stickerBitmap: android.graphics.Bitmap?,
    boxWidthPx: Float,
    boxHeightPx: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val shape = remember(resolved.shape) { maskComposeShape(resolved.shape) }
    val alphaScale = resolved.opacity.coerceIn(0.25f, 1f)
    val edgeAlpha = (1f - resolved.feather * 0.68f).coerceIn(0.25f, 1f)

    when (resolved.mode) {
        MaskMode.GAUSSIAN_BLUR -> {
            val baseAlpha = (0.58f + resolved.blurIntensity * 0.36f) * alphaScale
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFD4CFC7).copy(alpha = baseAlpha),
                                Color(0xFF333740).copy(alpha = baseAlpha * edgeAlpha)
                            )
                        )
                    )
            )
        }

        MaskMode.FROSTED_GLASS -> {
            val baseAlpha = (0.60f + resolved.blurIntensity * 0.34f) * alphaScale
            Canvas(modifier = modifier.clip(shape)) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFEAF2FA).copy(alpha = baseAlpha * 0.92f),
                            Color(0xFFB5C4D4).copy(alpha = baseAlpha * edgeAlpha)
                        )
                    )
                )
                val step = (size.minDimension / 12f).coerceAtLeast(5f)
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = 0f
                    var col = 0
                    while (x < size.width) {
                        if ((row * 7 + col * 13) % 3 == 0) {
                            drawRect(
                                color = Color.White.copy(alpha = 0.14f * alphaScale),
                                topLeft = Offset(x, y),
                                size = Size(step * 0.7f, step * 0.7f)
                            )
                        }
                        x += step
                        col++
                    }
                    y += step
                    row++
                }
            }
        }

        MaskMode.GLITCH -> {
            Canvas(modifier = modifier.clip(shape).alpha(alphaScale)) {
                val slices = (6 + (resolved.blurIntensity * 10f).roundToInt()).coerceIn(6, 16)
                val sliceH = size.height / slices
                for (i in 0 until slices) {
                    val shift = if (i % 2 == 0) 6f else -6f
                    val stripeColor = when (i % 4) {
                        0 -> Color(0xDD1A1F2B)
                        1 -> Color(0xDDF5A623)
                        2 -> Color(0xDD27D7E8)
                        else -> Color(0xDDE55378)
                    }
                    drawRect(
                        color = stripeColor,
                        topLeft = Offset(shift, i * sliceH),
                        size = Size(size.width, sliceH + 0.6f)
                    )
                }
            }
        }

        MaskMode.INVERT -> {
            Canvas(modifier = modifier.clip(shape).alpha(alphaScale)) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xCC21E6C1),
                            Color(0xCC7B5CFF),
                            Color(0xCCFF5E94)
                        )
                    )
                )
                drawRect(
                    color = Color.White.copy(alpha = 0.25f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        MaskMode.PIXELATE -> {
            val cols = (12 - (resolved.pixelBlockSize * 8f).roundToInt()).coerceIn(4, 12)
            Canvas(modifier = modifier.clip(shape).alpha(alphaScale)) {
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
            val fill = Color(resolved.solidColorArgb)
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                fill.copy(alpha = alphaScale),
                                fill.copy(alpha = alphaScale * edgeAlpha)
                            )
                        )
                    )
            )
        }

        MaskMode.EMOJI -> {
            val emojiSp = with(density) { (min(boxWidthPx, boxHeightPx) * 0.72f).toSp() }
            Box(
                modifier = modifier.alpha(alphaScale),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = resolved.selectedEmoji,
                    fontSize = emojiSp,
                    textAlign = TextAlign.Center
                )
            }
        }

        MaskMode.STICKER -> {
            val imageBitmap = remember(stickerBitmap) { stickerBitmap?.asImageBitmap() }
            Box(
                modifier = modifier.alpha(alphaScale),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    val scale = resolved.stickerScale.coerceIn(0.7f, 2.0f)
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

        MaskMode.SPOTLIGHT -> Unit
    }
}

@Composable
private fun TextCaptionOverlayView(
    item: TextOverlayItem,
    isEditable: Boolean,
    isSelected: Boolean,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val currentDrag by rememberUpdatedState(onDrag)
    val primaryColor = MaterialTheme.colorScheme.primary

    val minDim = min(stageWidthPx, stageHeightPx)
    val scaledSp = with(density) { ((item.fontSizeSp / 360f) * minDim * 0.92f).coerceAtLeast(13f).toSp() }
    val estWidthPx = (item.text.length.coerceAtLeast(4) * scaledSp.value * 0.72f * density.density + 48f)
        .coerceIn(104f, stageWidthPx * 0.92f)
    val estHeightPx = (scaledSp.value * 1.85f * density.density + 16f).coerceAtLeast(42f)
    val offsetX = (item.normalizedX * stageWidthPx - estWidthPx * 0.5f).roundToInt()
    val offsetY = (item.normalizedY * stageHeightPx - estHeightPx * 0.5f).roundToInt()

    val bgColor = when (item.style) {
        TextOverlayStyle.AMBER_PILL -> primaryColor.copy(alpha = 0.94f * item.opacity)
        TextOverlayStyle.SUBTITLE -> Color(0xC80C0E13).copy(alpha = 0.85f * item.opacity)
        TextOverlayStyle.GLASS -> Color(0x66FFFFFF).copy(alpha = 0.34f * item.opacity)
        TextOverlayStyle.MINIMAL, TextOverlayStyle.OUTLINE -> Color.Transparent
    }
    val textColor = when (item.style) {
        TextOverlayStyle.AMBER_PILL -> Color(0xFF1C1202).copy(alpha = item.opacity)
        else -> Color(item.textColorArgb).copy(alpha = item.opacity)
    }
    val pillShape = when (item.style) {
        TextOverlayStyle.AMBER_PILL -> CircleShape
        else -> RoundedCornerShape(10.dp)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(
                width = with(density) { estWidthPx.toDp() },
                height = with(density) { estHeightPx.toDp() }
            )
            .pointerInput(item.id, isEditable, stageWidthPx, stageHeightPx) {
                if (!isEditable) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        onSelect()
                        onDragStart()
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentDrag(dragAmount.x / stageWidthPx, dragAmount.y / stageHeightPx)
                    }
                )
            }
            .clickable(enabled = isEditable) { onSelect() }
            .testTag("text_overlay_item_${item.id}"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(pillShape)
                .background(bgColor)
                .then(
                    if (item.style == TextOverlayStyle.GLASS) {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.45f * item.opacity), pillShape)
                    } else if (isSelected) {
                        Modifier.border(1.5.dp, primaryColor, pillShape)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.text,
                color = textColor,
                fontSize = scaledSp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = if (item.style == TextOverlayStyle.OUTLINE || item.style == TextOverlayStyle.MINIMAL) {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.85f * item.opacity),
                            offset = Offset(0f, 3f),
                            blurRadius = if (item.style == TextOverlayStyle.OUTLINE) 4f else 8f
                        )
                    } else {
                        null
                    }
                )
            )
        }

        if (isSelected) {
            Surface(
                onClick = onDelete,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_delete_text),
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
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
