package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FaceRetouchingOff
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.engine.ColorGradeGlEffect
import com.example.engine.PrivacyMaskGlEffect
import com.example.model.AspectRatioPreset
import com.example.model.ColorFilterPreset
import com.example.model.ColorGradeConfig
import com.example.model.EditorTab
import com.example.model.EditorUiState
import com.example.model.MaskMode
import com.example.model.MaskShape
import com.example.model.MediaType
import com.example.model.ScanAccuracy
import com.example.model.TextOverlayStyle
import com.example.model.WatermarkPositionPreset
import com.example.model.WatermarkType
import com.example.model.formatTimecode
import com.example.ui.components.AdjustPanel
import com.example.ui.components.AudioPanel
import com.example.ui.components.FaceMaskPanel
import com.example.ui.components.PreviewStage
import com.example.ui.components.SpeedPanel
import com.example.ui.components.TextOverlayPanel
import com.example.ui.components.TransformPanel
import com.example.ui.components.TrimCutPanel
import com.example.ui.components.WatermarkPanel
import com.example.ui.theme.TimecodeTextStyle
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
@Composable
fun EditorWorkspaceScreen(
    uiState: EditorUiState,
    onCloseEditor: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetEdits: () -> Unit,
    onOpenExport: () -> Unit,
    onSelectTab: (EditorTab) -> Unit,
    onToggleComparingOriginal: () -> Unit,
    onToggleMute: () -> Unit,
    // 1. Face Mask studio
    onToggleAutoDetectOnImport: (Boolean) -> Unit,
    onSelectAccuracy: (ScanAccuracy) -> Unit,
    onAutoDetectAndBlur: () -> Unit,
    onCancelScan: () -> Unit,
    onAddManualBox: () -> Unit,
    onSetApplyToAll: (Boolean) -> Unit,
    onSelectFaceForEditing: (Int?) -> Unit,
    onResetFaceOverride: (Int) -> Unit,
    onToggleFaceTrack: (Int) -> Unit,
    onSelectMaskMode: (MaskMode) -> Unit,
    onSelectMaskShape: (MaskShape) -> Unit,
    onMaskSizeScaleChange: (Float) -> Unit,
    onMaskFeatherChange: (Float) -> Unit,
    onMaskOpacityChange: (Float) -> Unit,
    onBlurIntensityChange: (Float) -> Unit,
    onPixelSizeChange: (Float) -> Unit,
    onSolidColorChange: (Int) -> Unit,
    onEmojiSelected: (String) -> Unit,
    onPickSticker: () -> Unit,
    onStickerScaleChange: (Float) -> Unit,
    onSelectManualBox: (String?) -> Unit,
    onEndContinuousEdit: () -> Unit,
    onMoveManualBox: (String, Float, Float) -> Unit,
    onResizeManualBox: (String, Float, Float) -> Unit,
    onDeleteManualBox: (String) -> Unit,
    // 2. Color Adjust
    onSelectColorPreset: (ColorFilterPreset) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onWarmthChange: (Float) -> Unit,
    onVignetteChange: (Float) -> Unit,
    onResetColorGrade: () -> Unit,
    // 3. Transform
    onSelectAspectRatio: (AspectRatioPreset) -> Unit,
    onRotate90: () -> Unit,
    onToggleFlipH: () -> Unit,
    onToggleFlipV: () -> Unit,
    onZoomScaleChange: (Float) -> Unit,
    onResetTransform: () -> Unit,
    // 4. Speed
    onSelectSpeedPreset: (Float) -> Unit,
    onSpeedSliderChange: (Float) -> Unit,
    onTogglePreservePitch: (Boolean) -> Unit,
    // 5. Audio
    onOriginalVolumeChange: (Float) -> Unit,
    onPickMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onToggleLoopMusic: (Boolean) -> Unit,
    // 6. Text Overlays
    onAddTextOverlay: () -> Unit,
    onSelectTextOverlay: (String?) -> Unit,
    onDeleteTextOverlay: (String) -> Unit,
    onUpdateTextContent: (String, String) -> Unit,
    onUpdateTextStyle: (String, TextOverlayStyle) -> Unit,
    onUpdateTextFontSize: (String, Float) -> Unit,
    onUpdateTextColor: (String, Int) -> Unit,
    onUpdateTextOpacity: (String, Float) -> Unit,
    onUpdateTextTiming: (String, Long, Long) -> Unit,
    onMoveTextOverlay: (String, Float, Float) -> Unit,
    // 7. Watermark
    onSelectWatermarkType: (WatermarkType) -> Unit,
    onWatermarkTextChange: (String) -> Unit,
    onWatermarkFontSizeChange: (Float) -> Unit,
    onWatermarkTextColorChange: (Int) -> Unit,
    onPickWatermarkLogo: () -> Unit,
    onWatermarkLogoScaleChange: (Float) -> Unit,
    onWatermarkOpacityChange: (Float) -> Unit,
    onSelectWatermarkPreset: (WatermarkPositionPreset) -> Unit,
    onMoveWatermark: (Float, Float) -> Unit,
    // 8. Trim & Cut
    onUpdatePlaybackState: (Long, Boolean) -> Unit,
    onUpdateTrimRange: (Long, Long) -> Unit,
    onSetTrimInAtPlayhead: () -> Unit,
    onSetTrimOutAtPlayhead: () -> Unit,
    onResetTrimAndCuts: () -> Unit,
    onAddCutSegment: () -> Unit,
    onUpdateCutSegment: (String, Long, Long) -> Unit,
    onRemoveCutSegment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onCloseEditor)

    val context = LocalContext.current
    val asset = uiState.mediaAsset ?: return
    val isVideo = asset.type == MediaType.VIDEO

    // Thread-safe live state snapshot read directly by GPU OpenGL shaders on ExoPlayer's render thread
    val liveStateRef = remember { AtomicReference(uiState) }
    liveStateRef.set(uiState)

    val exoPlayer = remember(asset.uri, isVideo) {
        if (isVideo) {
            val liveMaskEffect = PrivacyMaskGlEffect(
                activeMasksProvider = { ptsUs ->
                    val state = liveStateRef.get()
                    if (state.isComparingOriginal) {
                        emptyList()
                    } else {
                        state.resolvedMasksAt(ptsUs / 1000L)
                    }
                }
            )
            val liveColorEffect = ColorGradeGlEffect(
                configProvider = {
                    val state = liveStateRef.get()
                    if (state.isComparingOriginal) ColorGradeConfig() else state.colorGradeConfig
                },
                alwaysActive = true
            )
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(asset.uri))
                setVideoEffects(listOf(liveMaskEffect, liveColorEffect))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
            }
        } else {
            null
        }
    }

    // Re-render current video frame through live GPU shaders immediately when editing while paused
    LaunchedEffect(
        exoPlayer,
        uiState.isComparingOriginal,
        uiState.faceTracks,
        uiState.manualBoxes,
        uiState.maskConfig,
        uiState.colorGradeConfig
    ) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
            delay(35L)
            if (!player.isPlaying) {
                player.seekTo(player.currentPosition)
            }
        }
    }

    val musicUri = uiState.audioConfig.musicUri
    val musicPlayer = remember(musicUri, isVideo) {
        if (isVideo && musicUri != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(musicUri))
                repeatMode = if (uiState.audioConfig.loopMusic) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                prepare()
            }
        } else {
            null
        }
    }

    // Live audio volume & speed sync
    LaunchedEffect(exoPlayer, uiState.audioConfig.effectiveOriginalVolume, uiState.isComparingOriginal) {
        exoPlayer?.volume = if (uiState.isComparingOriginal) 1f else uiState.audioConfig.effectiveOriginalVolume
    }

    LaunchedEffect(exoPlayer, uiState.speedConfig, uiState.isComparingOriginal) {
        val speed = if (uiState.isComparingOriginal) 1f else uiState.speedConfig.speedMultiplier.coerceIn(0.25f, 4.0f)
        val pitch = if (uiState.isComparingOriginal || uiState.speedConfig.preservePitch) 1f else speed
        exoPlayer?.playbackParameters = PlaybackParameters(speed, pitch)
    }

    LaunchedEffect(musicPlayer, uiState.audioConfig.musicVolume, uiState.audioConfig.loopMusic, uiState.isComparingOriginal) {
        musicPlayer?.let { mp ->
            mp.volume = if (uiState.isComparingOriginal) 0f else uiState.audioConfig.musicVolume.coerceIn(0f, 1f)
            mp.repeatMode = if (uiState.audioConfig.loopMusic) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    DisposableEffect(musicPlayer) {
        onDispose { musicPlayer?.release() }
    }

    val latestTrim by rememberUpdatedState(uiState.trimCutConfig)
    val latestPlaybackCallback by rememberUpdatedState(onUpdatePlaybackState)
    val latestMusicPlayer by rememberUpdatedState(musicPlayer)

    LaunchedEffect(exoPlayer) {
        if (exoPlayer == null) return@LaunchedEffect
        while (true) {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val playing = exoPlayer.isPlaying
            val trim = latestTrim

            if (playing && trim.totalDurationMs > 0L) {
                val activeCut = trim.cutSegments.firstOrNull { pos in it.startMs until it.endMs }
                when {
                    activeCut != null -> {
                        val jumpTo = activeCut.endMs.coerceAtMost(trim.trimEndMs)
                        exoPlayer.seekTo(jumpTo)
                    }
                    pos >= trim.trimEndMs && trim.trimEndMs > trim.trimStartMs -> {
                        exoPlayer.seekTo(trim.trimStartMs)
                        latestMusicPlayer?.seekTo(0L)
                    }
                    pos < trim.trimStartMs -> {
                        exoPlayer.seekTo(trim.trimStartMs)
                    }
                }
            }

            latestMusicPlayer?.let { mp ->
                if (playing && !mp.isPlaying) {
                    mp.play()
                } else if (!playing && mp.isPlaying) {
                    mp.pause()
                }
            }

            latestPlaybackCallback(exoPlayer.currentPosition.coerceAtLeast(0L), playing)
            delay(40L)
        }
    }

    val seekTo: (Long) -> Unit = { targetMs ->
        val clamped = targetMs.coerceIn(0L, asset.durationMs.coerceAtLeast(0L))
        exoPlayer?.seekTo(clamped)
        musicPlayer?.let { mp ->
            val musicDur = mp.duration.takeIf { it > 0L }
            val musicTarget = if (musicDur != null) clamped % musicDur else clamped
            mp.seekTo(musicTarget.coerceAtLeast(0L))
        }
        onUpdatePlaybackState(clamped, exoPlayer?.isPlaying == true)
    }

    val togglePlayPause: () -> Unit = {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                musicPlayer?.pause()
            } else {
                val trim = uiState.trimCutConfig
                if (player.currentPosition < trim.trimStartMs || player.currentPosition >= trim.trimEndMs) {
                    player.seekTo(trim.trimStartMs)
                    musicPlayer?.seekTo(0L)
                }
                player.play()
                musicPlayer?.play()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Minimalist Top Bar: Back | Undo / Redo | Before-After Toggle | Reset | Export
        MinimalEditorTopBar(
            isComparingOriginal = uiState.isComparingOriginal,
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            onBack = onCloseEditor,
            onUndo = onUndo,
            onRedo = onRedo,
            onToggleCompare = onToggleComparingOriginal,
            onResetEdits = onResetEdits,
            onExport = onOpenExport
        )

        // 2. Adaptive Workspace
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val isExpandedWide = maxWidth >= 720.dp

            val inspectorContent: @Composable (Modifier) -> Unit = { panelModifier ->
                AnimatedContent(
                    targetState = uiState.activeTab,
                    label = "inspector_tab_switch",
                    modifier = panelModifier
                ) { tab ->
                    when (tab) {
                        EditorTab.FACE_MASK -> FaceMaskPanel(
                            uiState = uiState,
                            onToggleAutoDetectOnImport = onToggleAutoDetectOnImport,
                            onSelectAccuracy = onSelectAccuracy,
                            onAutoDetectAndBlur = onAutoDetectAndBlur,
                            onCancelScan = onCancelScan,
                            onAddManualBox = onAddManualBox,
                            onSetApplyToAll = onSetApplyToAll,
                            onSelectFaceForEditing = onSelectFaceForEditing,
                            onResetFaceOverride = onResetFaceOverride,
                            onToggleFaceTrack = onToggleFaceTrack,
                            onSelectMaskMode = onSelectMaskMode,
                            onSelectMaskShape = onSelectMaskShape,
                            onMaskSizeScaleChange = onMaskSizeScaleChange,
                            onMaskFeatherChange = onMaskFeatherChange,
                            onMaskOpacityChange = onMaskOpacityChange,
                            onBlurIntensityChange = onBlurIntensityChange,
                            onPixelSizeChange = onPixelSizeChange,
                            onSolidColorChange = onSolidColorChange,
                            onEmojiSelected = onEmojiSelected,
                            onPickSticker = onPickSticker,
                            onStickerScaleChange = onStickerScaleChange,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.ADJUST -> AdjustPanel(
                            colorGrade = uiState.colorGradeConfig,
                            onSelectPreset = onSelectColorPreset,
                            onBrightnessChange = onBrightnessChange,
                            onContrastChange = onContrastChange,
                            onSaturationChange = onSaturationChange,
                            onWarmthChange = onWarmthChange,
                            onVignetteChange = onVignetteChange,
                            onReset = onResetColorGrade,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.TRANSFORM -> TransformPanel(
                            transform = uiState.transformConfig,
                            onSelectAspectRatio = onSelectAspectRatio,
                            onRotate90 = onRotate90,
                            onToggleFlipH = onToggleFlipH,
                            onToggleFlipV = onToggleFlipV,
                            onZoomChange = onZoomScaleChange,
                            onReset = onResetTransform,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.SPEED -> SpeedPanel(
                            uiState = uiState,
                            onSelectSpeedPreset = onSelectSpeedPreset,
                            onSpeedSliderChange = onSpeedSliderChange,
                            onTogglePreservePitch = onTogglePreservePitch,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.AUDIO -> AudioPanel(
                            uiState = uiState,
                            onOriginalVolumeChange = onOriginalVolumeChange,
                            onToggleMute = onToggleMute,
                            onPickMusic = onPickMusic,
                            onRemoveMusic = onRemoveMusic,
                            onMusicVolumeChange = onMusicVolumeChange,
                            onToggleLoopMusic = onToggleLoopMusic,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.TEXT -> TextOverlayPanel(
                            uiState = uiState,
                            onAddText = onAddTextOverlay,
                            onSelectText = { id -> onSelectTextOverlay(id) },
                            onDeleteText = onDeleteTextOverlay,
                            onUpdateContent = onUpdateTextContent,
                            onUpdateStyle = onUpdateTextStyle,
                            onUpdateFontSize = onUpdateTextFontSize,
                            onUpdateColor = onUpdateTextColor,
                            onUpdateOpacity = onUpdateTextOpacity,
                            onUpdateTiming = onUpdateTextTiming,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.WATERMARK -> WatermarkPanel(
                            uiState = uiState,
                            onSelectType = onSelectWatermarkType,
                            onTextChange = onWatermarkTextChange,
                            onFontSizeChange = onWatermarkFontSizeChange,
                            onTextColorChange = onWatermarkTextColorChange,
                            onPickLogo = onPickWatermarkLogo,
                            onLogoScaleChange = onWatermarkLogoScaleChange,
                            onOpacityChange = onWatermarkOpacityChange,
                            onSelectPreset = onSelectWatermarkPreset,
                            onSliderFinished = onEndContinuousEdit
                        )

                        EditorTab.TRIM_CUT -> TrimCutPanel(
                            uiState = uiState,
                            onUpdateTrimRange = onUpdateTrimRange,
                            onSetTrimInAtPlayhead = onSetTrimInAtPlayhead,
                            onSetTrimOutAtPlayhead = onSetTrimOutAtPlayhead,
                            onResetTrimAndCuts = onResetTrimAndCuts,
                            onAddCutSegment = onAddCutSegment,
                            onUpdateCutSegment = onUpdateCutSegment,
                            onRemoveCutSegment = onRemoveCutSegment,
                            onSliderFinished = onEndContinuousEdit,
                            onSeekTo = seekTo
                        )
                    }
                }
            }

            if (isExpandedWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                    ) {
                        PreviewStage(
                            uiState = uiState,
                            exoPlayer = exoPlayer,
                            onSelectFaceForEditing = { id -> onSelectFaceForEditing(id) },
                            onSelectManualBox = onSelectManualBox,
                            onSelectTextOverlay = onSelectTextOverlay,
                            onEndContinuousEdit = onEndContinuousEdit,
                            onMoveManualBox = onMoveManualBox,
                            onResizeManualBox = onResizeManualBox,
                            onDeleteManualBox = onDeleteManualBox,
                            onMoveTextOverlay = onMoveTextOverlay,
                            onDeleteTextOverlay = onDeleteTextOverlay,
                            onMoveWatermark = onMoveWatermark,
                            modifier = Modifier.weight(1f)
                        )
                        if (isVideo) {
                            MinimalVideoTransportDeck(
                                isPlaying = uiState.isPlaying,
                                isMuted = uiState.isMuted,
                                currentMs = uiState.currentPlaybackMs,
                                durationMs = asset.durationMs,
                                onTogglePlay = togglePlayPause,
                                onToggleMute = onToggleMute,
                                onStepBack = { seekTo(uiState.currentPlaybackMs - 1000L) },
                                onStepForward = { seekTo(uiState.currentPlaybackMs + 1000L) },
                                onSeek = seekTo
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(0.85f)
                            .fillMaxHeight()
                    ) {
                        inspectorContent(Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    PreviewStage(
                        uiState = uiState,
                        exoPlayer = exoPlayer,
                        onSelectFaceForEditing = { id -> onSelectFaceForEditing(id) },
                        onSelectManualBox = onSelectManualBox,
                        onSelectTextOverlay = onSelectTextOverlay,
                        onEndContinuousEdit = onEndContinuousEdit,
                        onMoveManualBox = onMoveManualBox,
                        onResizeManualBox = onResizeManualBox,
                        onDeleteManualBox = onDeleteManualBox,
                        onMoveTextOverlay = onMoveTextOverlay,
                        onDeleteTextOverlay = onDeleteTextOverlay,
                        onMoveWatermark = onMoveWatermark,
                        modifier = Modifier.weight(1f)
                    )

                    if (isVideo) {
                        MinimalVideoTransportDeck(
                            isPlaying = uiState.isPlaying,
                            isMuted = uiState.isMuted,
                            currentMs = uiState.currentPlaybackMs,
                            durationMs = asset.durationMs,
                            onTogglePlay = togglePlayPause,
                            onToggleMute = onToggleMute,
                            onStepBack = { seekTo(uiState.currentPlaybackMs - 1000L) },
                            onStepForward = { seekTo(uiState.currentPlaybackMs + 1000L) },
                            onSeek = seekTo
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(276.dp)
                    ) {
                        inspectorContent(Modifier.fillMaxSize())
                    }
                }
            }
        }

        // 3. Scrollable 8-Tool Pro Editor Dock (Edge-to-Edge safe)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ProEditorBottomDock(
            activeTab = uiState.activeTab,
            onSelectTab = onSelectTab
        )
    }
}

@Composable
private fun ProEditorBottomDock(
    activeTab: EditorTab,
    onSelectTab: (EditorTab) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(EditorTab.entries) { tab ->
                val selected = activeTab == tab
                val label = stringResource(tabLabelRes(tab))
                Surface(
                    onClick = { onSelectTab(tab) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = if (selected) BorderStroke(1.dp, primary.copy(alpha = 0.7f)) else null,
                    modifier = Modifier
                        .widthIn(min = 66.dp)
                        .height(54.dp)
                        .testTag("tab_${tab.name.lowercase()}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tabIcon(tab),
                            contentDescription = label,
                            tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun tabLabelRes(tab: EditorTab): Int = when (tab) {
    EditorTab.FACE_MASK -> R.string.tab_face_mask
    EditorTab.ADJUST -> R.string.tab_adjust
    EditorTab.TRANSFORM -> R.string.tab_transform
    EditorTab.SPEED -> R.string.tab_speed
    EditorTab.AUDIO -> R.string.tab_audio
    EditorTab.TEXT -> R.string.tab_text
    EditorTab.WATERMARK -> R.string.tab_watermark
    EditorTab.TRIM_CUT -> R.string.tab_trim_cut
}

private fun tabIcon(tab: EditorTab): ImageVector = when (tab) {
    EditorTab.FACE_MASK -> Icons.Filled.FaceRetouchingOff
    EditorTab.ADJUST -> Icons.Filled.Tune
    EditorTab.TRANSFORM -> Icons.Filled.Crop
    EditorTab.SPEED -> Icons.Filled.Speed
    EditorTab.AUDIO -> Icons.Filled.LibraryMusic
    EditorTab.TEXT -> Icons.Filled.TextFields
    EditorTab.WATERMARK -> Icons.Filled.BrandingWatermark
    EditorTab.TRIM_CUT -> Icons.Filled.ContentCut
}

@Composable
private fun MinimalEditorTopBar(
    isComparingOriginal: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleCompare: () -> Unit,
    onResetEdits: () -> Unit,
    onExport: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("editor_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("undo_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.cd_undo),
                    tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("redo_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.cd_redo),
                    tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Prominent Before / After Toggle Pill
            Surface(
                onClick = onToggleCompare,
                shape = CircleShape,
                color = if (isComparingOriginal) primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    if (isComparingOriginal) primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("compare_original_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (isComparingOriginal) Icons.Filled.Visibility else Icons.Filled.Compare,
                        contentDescription = stringResource(R.string.cd_compare_original),
                        tint = if (isComparingOriginal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (isComparingOriginal) stringResource(R.string.action_before) else stringResource(R.string.action_after),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isComparingOriginal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            IconButton(
                onClick = onResetEdits,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("reset_edits_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = stringResource(R.string.action_reset_edits),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            Button(
                onClick = onExport,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("export_toolbar_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.action_export),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MinimalVideoTransportDeck(
    isPlaying: Boolean,
    isMuted: Boolean,
    currentMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimecode(currentMs),
                        style = TimecodeTextStyle.copy(color = primary, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = " / ${formatTimecode(durationMs)}",
                        style = TimecodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onStepBack,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("step_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay5,
                            contentDescription = stringResource(R.string.cd_step_backward),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Surface(
                        onClick = onTogglePlay,
                        shape = CircleShape,
                        color = primary,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("play_pause_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onStepForward,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("step_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forward5,
                            contentDescription = stringResource(R.string.cd_step_forward),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("toggle_mute_button")
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.cd_toggle_mute),
                        tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Slider(
                value = (currentMs.toFloat() / safeDuration).coerceIn(0f, 1f),
                onValueChange = { frac -> onSeek((frac * safeDuration).roundToLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .testTag("video_scrubber_slider")
            )
        }
    }
}
