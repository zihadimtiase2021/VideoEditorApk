package com.example.ui.components

import android.graphics.Bitmap
import android.view.LayoutInflater
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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.engine.MaskRenderUtils
import com.example.engine.MediaMetadataLoader
import com.example.model.EditorTab
import com.example.model.EditorUiState
import com.example.model.FaceScanState
import com.example.model.ManualMaskBox
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.ResolvedMaskInstance
import com.example.model.TextOverlayItem
import com.example.model.TextOverlayStyle
import com.example.model.WatermarkType
import com.example.ui.theme.TimecodeTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * True WYSIWYG Preview Monitor matching the exact Export output:
 * - Photos render live pixels through [MaskRenderUtils.renderImagePreviewBase] (identical to JPG/PNG export).
 * - Videos run live GPU [PrivacyMaskGlEffect] + [ColorGradeGlEffect] on a TextureView-backed [PlayerView]
 *   inside an exact cover-crop [TransformConfig] viewport (identical to Media3 Transformer MP4 export).
 */
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    var isDraggingOverlay by remember { mutableStateOf(false) }

    val showEdited = !uiState.isComparingOriginal
    val transform = uiState.transformConfig
    val colorGrade = uiState.colorGradeConfig
    val isVideo = asset.type == MediaType.VIDEO

    val displayAspect = if (showEdited) {
        transform.effectiveAspectRatio(asset.width, asset.height)
    } else {
        asset.aspectRatio
    }.coerceIn(0.42f, 2.45f)

    // -------------------------------------------------------------------------
    // 100% Pixel-Identical Photo Preview Pipeline (exact MaskRenderUtils output)
    // -------------------------------------------------------------------------
    val metadataLoader = remember(context) { MediaMetadataLoader(context.applicationContext) }
    var imageSourceBitmap by remember(asset.uri) { mutableStateOf<Bitmap?>(null) }
    var imageRenderedBitmap by remember(asset.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(asset.uri, isVideo) {
        if (isVideo) {
            imageSourceBitmap = null
            imageRenderedBitmap = null
        } else {
            val decoded = metadataLoader.decodeOrientedBitmap(asset.uri, maxDimension = 1280)
            imageSourceBitmap = decoded
        }
    }

    val imageResolvedMasks = remember(
        isVideo,
        uiState.faceTracks,
        uiState.manualBoxes,
        uiState.maskConfig
    ) {
        if (isVideo) emptyList() else uiState.resolvedMasksAt(0L)
    }

    LaunchedEffect(
        isVideo,
        imageSourceBitmap,
        showEdited,
        imageResolvedMasks,
        uiState.stickerBitmap,
        colorGrade,
        transform
    ) {
        if (isVideo) return@LaunchedEffect
        val src = imageSourceBitmap ?: return@LaunchedEffect
        if (src.isRecycled) return@LaunchedEffect
        if (!showEdited) {
            imageRenderedBitmap = null
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.Default) {
            MaskRenderUtils.renderImagePreviewBase(
                sourceBitmap = src,
                resolvedMasks = imageResolvedMasks,
                stickerBitmap = uiState.stickerBitmap,
                colorGradeConfig = colorGrade,
                transformConfig = transform
            )
        }
        val previous = imageRenderedBitmap
        imageRenderedBitmap = rendered
        if (previous != null && previous !== rendered && !previous.isRecycled) {
            previous.recycle()
        }
    }

    DisposableEffect(asset.uri) {
        onDispose {
            imageRenderedBitmap?.let { if (!it.isRecycled) it.recycle() }
            imageSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

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
        // Exact export-aspect-ratio footage monitor
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
            val timestampMs = if (isVideo) uiState.currentPlaybackMs else 0L

            // Exact source-to-viewport transform math matching MaskRenderUtils.applyTransform
            // and MediaExportEngine.addTransformEffects (Flip -> Rotate -> Aspect Cover Crop -> Zoom).
            val rotDeg = if (showEdited) ((transform.rotationDegrees % 360) + 360) % 360 else 0
            val isRotated90 = (rotDeg % 180) != 0
            val srcRatio = asset.aspectRatio.coerceIn(0.3f, 3.0f)
            val rotRatio = if (isRotated90) 1f / srcRatio else srcRatio
            val outRatio = stageWidthPx / stageHeightPx

            val rotBoxW: Float
            val rotBoxH: Float
            if (rotRatio > outRatio) {
                rotBoxH = stageHeightPx
                rotBoxW = stageHeightPx * rotRatio
            } else {
                rotBoxW = stageWidthPx
                rotBoxH = stageWidthPx / rotRatio
            }
            val srcBoxW = if (isRotated90) rotBoxH else rotBoxW
            val srcBoxH = if (isRotated90) rotBoxW else rotBoxH
            val srcBoxWDp = with(density) { srcBoxW.toDp() }
            val srcBoxHDp = with(density) { srcBoxH.toDp() }
            val zoomScale = if (showEdited) transform.zoomScale.coerceIn(1.0f, 2.0f) else 1.0f
            val flipX = if (showEdited && transform.flipHorizontal) -1f else 1f
            val flipY = if (showEdited && transform.flipVertical) -1f else 1f

            // 1. Rendered Media Layer (WYSIWYG Photo Bitmap OR Live GPU-Shaded Video TextureView)
            if (isVideo && exoPlayer != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(srcBoxWDp, srcBoxHDp)
                        .graphicsLayer {
                            scaleX = zoomScale * flipX
                            scaleY = zoomScale * flipY
                            rotationZ = rotDeg.toFloat()
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.view_texture_player, null) as PlayerView).apply {
                                player = exoPlayer
                            }
                        },
                        update = { view ->
                            if (view.player !== exoPlayer) {
                                view.player = exoPlayer
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // For Video: all 7 shader modes (Blur, Mosaic, Frosted, Glitch, Invert, Spotlight, Solid)
                    // are rendered directly by GPU PrivacyMaskGlEffect on ExoPlayer!
                    // Here we only render non-shader Emoji/Sticker overlays + interactive selection chrome/handles.
                    if (showEdited) {
                        SourceSpaceOverlaysLayer(
                            uiState = uiState,
                            timestampMs = timestampMs,
                            renderNonShaderMasks = true,
                            srcWidthPx = srcBoxW,
                            srcHeightPx = srcBoxH,
                            onSelectFaceForEditing = onSelectFaceForEditing,
                            onSelectManualBox = onSelectManualBox,
                            onDragStart = { isDraggingOverlay = true },
                            onDragEnd = {
                                isDraggingOverlay = false
                                onEndContinuousEdit()
                            },
                            onMoveManualBox = onMoveManualBox,
                            onResizeManualBox = onResizeManualBox,
                            onDeleteManualBox = onDeleteManualBox
                        )
                    }
                }
            } else {
                // Photo mode: display the EXACT rendered Bitmap from MaskRenderUtils (or original bitmap on Before)
                val displayBitmap = if (showEdited) (imageRenderedBitmap ?: imageSourceBitmap) else imageSourceBitmap
                val composeBitmap = remember(displayBitmap) {
                    displayBitmap?.takeUnless { it.isRecycled }?.asImageBitmap()
                }
                if (composeBitmap != null) {
                    Image(
                        bitmap = composeBitmap,
                        contentDescription = asset.displayName,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = asset.uri,
                        contentDescription = asset.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Interactive face selection chrome & manual box drag/resize handles aligned in source space
                // (visual pixels are already rendered identically inside displayBitmap!)
                if (showEdited && uiState.activeTab == EditorTab.FACE_MASK) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredSize(srcBoxWDp, srcBoxHDp)
                            .graphicsLayer {
                                scaleX = zoomScale * flipX
                                scaleY = zoomScale * flipY
                                rotationZ = rotDeg.toFloat()
                            }
                    ) {
                        SourceSpaceOverlaysLayer(
                            uiState = uiState,
                            timestampMs = 0L,
                            renderNonShaderMasks = false,
                            srcWidthPx = srcBoxW,
                            srcHeightPx = srcBoxH,
                            onSelectFaceForEditing = onSelectFaceForEditing,
                            onSelectManualBox = onSelectManualBox,
                            onDragStart = { isDraggingOverlay = true },
                            onDragEnd = {
                                isDraggingOverlay = false
                                onEndContinuousEdit()
                            },
                            onMoveManualBox = onMoveManualBox,
                            onResizeManualBox = onResizeManualBox,
                            onDeleteManualBox = onDeleteManualBox
                        )
                    }
                }
            }

            // 2. Post-Transform Overlays: Styled Text Captions & Watermark (exact MaskRenderUtils parity)
            if (showEdited) {
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

            // 3. Instant ORIGINAL Indicator Badge when viewing Before state
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

            // 4. Live Auto-Detect & Blur floating pill during ML Kit scan
            val scanState = uiState.faceScanState
            if (scanState is FaceScanState.Scanning) {
                val percent = (scanState.progress * 100f).roundToInt().coerceIn(0, 100)
                val liveFaces = uiState.faceTracks.size
                val statusLabel = buildString {
                    append(if (scanState.isAutoImportScan) "Auto-Detect & Blur" else "Scanning Faces")
                    if (isVideo) {
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
private fun SourceSpaceOverlaysLayer(
    uiState: EditorUiState,
    timestampMs: Long,
    renderNonShaderMasks: Boolean,
    srcWidthPx: Float,
    srcHeightPx: Float,
    onSelectFaceForEditing: (Int) -> Unit,
    onSelectManualBox: (String?) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onMoveManualBox: (String, Float, Float) -> Unit,
    onResizeManualBox: (String, Float, Float) -> Unit,
    onDeleteManualBox: (String) -> Unit
) {
    val showSelectionChrome = uiState.activeTab == EditorTab.FACE_MASK

    for (track in uiState.faceTracks) {
        val baseRect = track.rectAt(timestampMs) ?: continue
        val isMasked = uiState.maskConfig.applyToAllFaces || track.isSelected
        val resolved = uiState.resolveTrackMask(track, baseRect)
        val isFocused = uiState.selectedFaceTrackId == track.id
        FaceOverlayChromeAndSticker(
            resolved = resolved,
            label = track.label,
            isMasked = isMasked,
            isFocused = isFocused,
            hasCustomStyle = track.styleOverride?.hasAnyOverride() == true,
            showSelectionChrome = showSelectionChrome,
            renderNonShaderOverlay = renderNonShaderMasks && isMasked && !resolved.mode.isShaderEffect,
            stickerBitmap = uiState.stickerBitmap,
            stageWidthPx = srcWidthPx,
            stageHeightPx = srcHeightPx,
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
            isSelected = uiState.selectedManualBoxId == box.id && showSelectionChrome,
            showHandles = showSelectionChrome,
            renderNonShaderOverlay = renderNonShaderMasks && !resolved.mode.isShaderEffect,
            stickerBitmap = uiState.stickerBitmap,
            stageWidthPx = srcWidthPx,
            stageHeightPx = srcHeightPx,
            onSelect = { onSelectManualBox(box.id) },
            onDragStart = onDragStart,
            onDragEnd = onDragEnd,
            onMove = { dx, dy -> onMoveManualBox(box.id, dx, dy) },
            onResize = { dw, dh -> onResizeManualBox(box.id, dw, dh) },
            onDelete = { onDeleteManualBox(box.id) }
        )
    }
}

@Composable
private fun FaceOverlayChromeAndSticker(
    resolved: ResolvedMaskInstance,
    label: String,
    isMasked: Boolean,
    isFocused: Boolean,
    hasCustomStyle: Boolean,
    showSelectionChrome: Boolean,
    renderNonShaderOverlay: Boolean,
    stickerBitmap: Bitmap?,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onClick: () -> Unit
) {
    if (!showSelectionChrome && !renderNonShaderOverlay) return
    val density = LocalDensity.current
    val rect = resolved.rect
    val boxW = (rect.width * stageWidthPx).coerceAtLeast(24f)
    val boxH = (rect.height * stageHeightPx).coerceAtLeast(24f)
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
            .then(if (showSelectionChrome) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("face_overlay_${label.lowercase().replace(' ', '_')}")
    ) {
        if (renderNonShaderOverlay) {
            EmojiOrStickerVisual(
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
                        width = if (isFocused) 2.dp else 1.dp,
                        color = when {
                            isFocused -> primaryColor
                            isMasked -> primaryColor.copy(alpha = 0.52f)
                            else -> Color.White.copy(alpha = 0.42f)
                        },
                        shape = clipShape
                    )
            )
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = if (isFocused) primaryColor else Color(0xAA14171D),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
            ) {
                Text(
                    text = buildString {
                        append(label)
                        if (!isMasked) append(" Off")
                        else if (hasCustomStyle) append(" ★")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) MaterialTheme.colorScheme.onPrimary else Color.White,
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
    renderNonShaderOverlay: Boolean,
    stickerBitmap: Bitmap?,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onDelete: () -> Unit
) {
    if (!showHandles && !renderNonShaderOverlay) return
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
        if (renderNonShaderOverlay) {
            EmojiOrStickerVisual(
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
                        color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.6f),
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
private fun EmojiOrStickerVisual(
    resolved: ResolvedMaskInstance,
    stickerBitmap: Bitmap?,
    boxWidthPx: Float,
    boxHeightPx: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val alphaScale = resolved.opacity.coerceIn(0.25f, 1f)

    when (resolved.mode) {
        MaskMode.EMOJI -> {
            // Exact same 0.84f sizing formula as MaskRenderUtils.drawOverlayMask
            val emojiSp = with(density) { (min(boxWidthPx, boxHeightPx) * 0.84f).toSp() }
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
                    // Exact same aspect-preserving sizing formula as MaskRenderUtils.drawOverlayMask
                    val scale = resolved.stickerScale.coerceIn(0.6f, 2.2f)
                    val baseSize = max(boxWidthPx, boxHeightPx) * scale
                    val aspect = imageBitmap.width.toFloat() / imageBitmap.height.coerceAtLeast(1)
                    val drawW = if (aspect >= 1f) baseSize else baseSize * aspect
                    val drawH = if (aspect >= 1f) baseSize / aspect else baseSize
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.cd_sticker_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.requiredSize(
                            width = with(density) { drawW.toDp() },
                            height = with(density) { drawH.toDp() }
                        )
                    )
                } else {
                    val shape = remember(resolved.shape) { maskComposeShape(resolved.shape) }
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

        else -> Unit
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

    // Exact same font-size math as MaskRenderUtils.drawTextOverlays
    val minDim = min(stageWidthPx, stageHeightPx)
    val textPx = ((item.fontSizeSp / 360f) * minDim * 1.05f).coerceAtLeast(16f)
    val scaledSp = with(density) { textPx.toSp() }
    val padHDp = with(density) { (textPx * 0.55f).toDp() }
    val padVDp = with(density) { (textPx * 0.28f).toDp() }
    val estWidthPx = (item.text.length.coerceAtLeast(4) * textPx * 0.68f + textPx * 1.6f)
        .coerceIn(104f, stageWidthPx * 0.94f)
    val estHeightPx = (textPx * 1.95f + 16f).coerceAtLeast(42f)
    val offsetX = (item.normalizedX.coerceIn(0.06f, 0.94f) * stageWidthPx - estWidthPx * 0.5f).roundToInt()
    val offsetY = (item.normalizedY.coerceIn(0.06f, 0.94f) * stageHeightPx - estHeightPx * 0.5f).roundToInt()

    val bgColor = when (item.style) {
        TextOverlayStyle.AMBER_PILL -> Color(0xFFF5A623).copy(alpha = item.opacity)
        TextOverlayStyle.SUBTITLE -> Color(0xCC0B0D12).copy(alpha = 0.85f * item.opacity)
        TextOverlayStyle.GLASS -> Color(0x66191D25).copy(alpha = 0.65f * item.opacity)
        TextOverlayStyle.MINIMAL, TextOverlayStyle.OUTLINE -> Color.Transparent
    }
    val textColor = when (item.style) {
        TextOverlayStyle.AMBER_PILL -> Color(0xFF1C1202).copy(alpha = item.opacity)
        else -> Color(item.textColorArgb).copy(alpha = item.opacity)
    }
    val pillShape = when (item.style) {
        TextOverlayStyle.AMBER_PILL -> CircleShape
        TextOverlayStyle.SUBTITLE -> RoundedCornerShape(with(density) { (textPx * 0.22f).toDp() })
        TextOverlayStyle.GLASS -> RoundedCornerShape(with(density) { (textPx * 0.35f).toDp() })
        else -> RoundedCornerShape(8.dp)
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
                        Modifier.border(
                            width = with(density) { max(2f, textPx * 0.06f).toDp() },
                            color = Color(0x99FFFFFF).copy(alpha = 0.60f * item.opacity),
                            shape = pillShape
                        )
                    } else if (isSelected) {
                        Modifier.border(1.5.dp, primaryColor, pillShape)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = padHDp, vertical = padVDp),
            contentAlignment = Alignment.Center
        ) {
            if (item.style == TextOverlayStyle.OUTLINE) {
                // Crisp black stroke behind text matching MaskRenderUtils.drawTextOverlays OUTLINE
                Text(
                    text = item.text,
                    color = Color.Black.copy(alpha = item.opacity),
                    fontSize = scaledSp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(
                        drawStyle = Stroke(
                            width = max(3f, textPx * 0.14f),
                            join = StrokeJoin.Round
                        )
                    )
                )
            }
            Text(
                text = item.text,
                color = textColor,
                fontSize = scaledSp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = if (item.style == TextOverlayStyle.MINIMAL) {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.78f * item.opacity),
                            offset = Offset(0f, textPx * 0.06f),
                            blurRadius = textPx * 0.18f
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
    val centerPxX = wm.normalizedX.coerceIn(0.04f, 0.96f) * stageWidthPx
    val centerPxY = wm.normalizedY.coerceIn(0.04f, 0.96f) * stageHeightPx

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
            // Exact same font-size math as MaskRenderUtils.drawWatermark
            val textPx = ((wm.fontSizeSp / 360f) * minDim * 1.05f).coerceAtLeast(16f)
            val scaledSp = with(density) { textPx.toSp() }
            val estWidthPx = (wm.text.length.coerceAtLeast(4) * textPx * 0.70f + 24f)
                .coerceIn(90f, stageWidthPx * 0.94f)
            val estHeightPx = (textPx * 1.65f).coerceAtLeast(36f)
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
                            offset = Offset(0f, textPx * 0.06f),
                            blurRadius = textPx * 0.14f
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
                val targetW = stageWidthPx * wm.logoScale.coerceIn(0.06f, 0.55f)
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
