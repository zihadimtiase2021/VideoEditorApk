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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FaceRetouchingOff
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.BrandingWatermark
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.FaceRetouchingOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.model.EditorTab
import com.example.model.EditorUiState
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.ScanAccuracy
import com.example.model.WatermarkPositionPreset
import com.example.model.WatermarkType
import com.example.model.formatTimecode
import com.example.ui.components.FaceMaskPanel
import com.example.ui.components.PreviewStage
import com.example.ui.components.TrimCutPanel
import com.example.ui.components.WatermarkPanel
import com.example.ui.theme.TimecodeTextStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

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
    onToggleAutoDetectOnImport: (Boolean) -> Unit,
    onSelectAccuracy: (ScanAccuracy) -> Unit,
    onAutoDetectAndBlur: () -> Unit,
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
    onSelectManualBox: (String?) -> Unit,
    onEndContinuousEdit: () -> Unit,
    onMoveManualBox: (String, Float, Float) -> Unit,
    onResizeManualBox: (String, Float, Float) -> Unit,
    onDeleteManualBox: (String) -> Unit,
    onSelectWatermarkType: (WatermarkType) -> Unit,
    onWatermarkTextChange: (String) -> Unit,
    onWatermarkFontSizeChange: (Float) -> Unit,
    onWatermarkTextColorChange: (Int) -> Unit,
    onPickWatermarkLogo: () -> Unit,
    onWatermarkLogoScaleChange: (Float) -> Unit,
    onWatermarkOpacityChange: (Float) -> Unit,
    onSelectWatermarkPreset: (WatermarkPositionPreset) -> Unit,
    onMoveWatermark: (Float, Float) -> Unit,
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

    val exoPlayer = remember(asset.uri, isVideo) {
        if (isVideo) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(asset.uri))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
            }
        } else {
            null
        }
    }

    LaunchedEffect(exoPlayer, uiState.isMuted) {
        exoPlayer?.volume = if (uiState.isMuted) 0f else 1f
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    val latestTrim by rememberUpdatedState(uiState.trimCutConfig)
    val latestPlaybackCallback by rememberUpdatedState(onUpdatePlaybackState)

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
                    }
                    pos < trim.trimStartMs -> {
                        exoPlayer.seekTo(trim.trimStartMs)
                    }
                }
            }

            latestPlaybackCallback(exoPlayer.currentPosition.coerceAtLeast(0L), playing)
            delay(40L)
        }
    }

    val seekTo: (Long) -> Unit = { targetMs ->
        val clamped = targetMs.coerceIn(0L, asset.durationMs.coerceAtLeast(0L))
        exoPlayer?.seekTo(clamped)
        onUpdatePlaybackState(clamped, exoPlayer?.isPlaying == true)
    }

    val togglePlayPause: () -> Unit = {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                val trim = uiState.trimCutConfig
                if (player.currentPosition < trim.trimStartMs || player.currentPosition >= trim.trimEndMs) {
                    player.seekTo(trim.trimStartMs)
                }
                player.play()
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
                            onToggleFaceTrack = onToggleFaceTrack,
                            onSelectMaskMode = onSelectMaskMode,
                            onBlurIntensityChange = onBlurIntensityChange,
                            onPixelSizeChange = onPixelSizeChange,
                            onSolidColorChange = onSolidColorChange,
                            onEmojiSelected = onEmojiSelected,
                            onPickSticker = onPickSticker,
                            onStickerScaleChange = onStickerScaleChange,
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
                            onToggleFaceSelection = onToggleFaceTrack,
                            onSelectManualBox = onSelectManualBox,
                            onEndContinuousEdit = onEndContinuousEdit,
                            onMoveManualBox = onMoveManualBox,
                            onResizeManualBox = onResizeManualBox,
                            onDeleteManualBox = onDeleteManualBox,
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
                        onToggleFaceSelection = onToggleFaceTrack,
                        onSelectManualBox = onSelectManualBox,
                        onEndContinuousEdit = onEndContinuousEdit,
                        onMoveManualBox = onMoveManualBox,
                        onResizeManualBox = onResizeManualBox,
                        onDeleteManualBox = onDeleteManualBox,
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
                            .height(272.dp)
                    ) {
                        inspectorContent(Modifier.fillMaxSize())
                    }
                }
            }
        }

        // 3. Minimal Bottom Navigation Bar (Edge-to-edge safe)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            windowInsets = WindowInsets.navigationBars
        ) {
            NavigationBarItem(
                selected = uiState.activeTab == EditorTab.FACE_MASK,
                onClick = { onSelectTab(EditorTab.FACE_MASK) },
                icon = {
                    Icon(
                        imageVector = if (uiState.activeTab == EditorTab.FACE_MASK) Icons.Filled.FaceRetouchingOff else Icons.Outlined.FaceRetouchingOff,
                        contentDescription = stringResource(R.string.tab_face_mask)
                    )
                },
                label = { Text(stringResource(R.string.tab_face_mask)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("tab_face_mask")
            )

            NavigationBarItem(
                selected = uiState.activeTab == EditorTab.WATERMARK,
                onClick = { onSelectTab(EditorTab.WATERMARK) },
                icon = {
                    Icon(
                        imageVector = if (uiState.activeTab == EditorTab.WATERMARK) Icons.Filled.BrandingWatermark else Icons.Outlined.BrandingWatermark,
                        contentDescription = stringResource(R.string.tab_watermark)
                    )
                },
                label = { Text(stringResource(R.string.tab_watermark)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("tab_watermark")
            )

            NavigationBarItem(
                selected = uiState.activeTab == EditorTab.TRIM_CUT,
                onClick = { onSelectTab(EditorTab.TRIM_CUT) },
                icon = {
                    Icon(
                        imageVector = if (uiState.activeTab == EditorTab.TRIM_CUT) Icons.Filled.ContentCut else Icons.Outlined.ContentCut,
                        contentDescription = stringResource(R.string.tab_trim_cut)
                    )
                },
                label = { Text(stringResource(R.string.tab_trim_cut)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("tab_trim_cut")
            )
        }
    }
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
