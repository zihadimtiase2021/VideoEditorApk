package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.EditorWorkspaceScreen
import com.example.ui.LaunchStudioScreen
import com.example.ui.components.BackgroundExportBanner
import com.example.ui.components.BatchExportSheet
import com.example.ui.components.ExportBottomSheet
import com.example.ui.theme.MaskFrameTheme
import com.example.viewmodel.EditorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            MaskFrameTheme {
                MaskFrameApp()
            }
        }
    }
}

@Composable
fun MaskFrameApp(
    viewModel: EditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeBackgroundJob by viewModel.activeBackgroundJob.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingExportAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Notification runtime permission launcher (Android 13+) for background progress bar
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Launch export whether granted or declined so rendering is never blocked
        val action = pendingExportAction
        pendingExportAction = null
        action?.invoke()
    }

    val runWithNotificationPermission: (() -> Unit) -> Unit = { exportAction ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                exportAction()
            } else {
                pendingExportAction = exportAction
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            exportAction()
        }
    }

    // Zero-permission Android Photo Picker & SAF audio launchers
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.loadMedia(uri)
        }
    }

    val batchPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.openBatchSelection(uris)
        }
    }

    val stickerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.setStickerUri(uri)
        }
    }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.setWatermarkLogoUri(uri)
        }
    }

    val musicPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setBackgroundMusicUri(uri)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSnackbar()
    }

    val shareSingleMedia: (Uri, String) -> Unit = { uri, mimeType ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Share Exported Media"))
        }
    }

    val shareMultipleMedia: (List<Uri>) -> Unit = { uris ->
        if (uris.size == 1) {
            shareSingleMedia(uris.first(), "*/*")
        } else if (uris.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Share Batch Exports"))
            }
        }
    }

    val openMedia: (Uri, String) -> Unit = { uri, mimeType ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(intent)
        }
    }

    val launchBatchPicker: () -> Unit = {
        batchPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.mediaAsset == null) {
            LaunchStudioScreen(
                autoDetectAndBlurEnabled = uiState.autoDetectAndBlurOnImport,
                onToggleAutoDetectAndBlur = viewModel::setAutoDetectAndBlurOnImport,
                onPickAllMedia = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onPickVideoOnly = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onPickImageOnly = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onStartBatchPick = launchBatchPicker
            )
        } else {
            EditorWorkspaceScreen(
                uiState = uiState,
                onCloseEditor = viewModel::closeEditor,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onResetEdits = viewModel::resetEdits,
                onOpenExport = viewModel::openExportSheet,
                onSelectTab = viewModel::selectTab,
                onToggleComparingOriginal = viewModel::toggleComparingOriginal,
                onToggleMute = viewModel::toggleMute,
                // 1. Face Mask studio
                onToggleAutoDetectOnImport = viewModel::setAutoDetectAndBlurOnImport,
                onSelectAccuracy = viewModel::setScanAccuracy,
                onAutoDetectAndBlur = viewModel::autoDetectAndBlur,
                onCancelScan = viewModel::cancelFaceScan,
                onAddManualBox = viewModel::addManualBox,
                onSetApplyToAll = viewModel::setApplyToAllFaces,
                onSelectFaceForEditing = viewModel::selectFaceTrackForEditing,
                onResetFaceOverride = viewModel::resetFaceStyleOverride,
                onToggleFaceTrack = viewModel::toggleFaceSelection,
                onSelectMaskMode = viewModel::setMaskMode,
                onSelectMaskShape = viewModel::setMaskShape,
                onMaskSizeScaleChange = viewModel::setMaskSizeScale,
                onMaskFeatherChange = viewModel::setMaskFeather,
                onMaskOpacityChange = viewModel::setMaskOpacity,
                onBlurIntensityChange = viewModel::setBlurIntensity,
                onPixelSizeChange = viewModel::setPixelBlockSize,
                onSolidColorChange = viewModel::setSolidMaskColor,
                onEmojiSelected = viewModel::setSelectedEmoji,
                onPickSticker = {
                    stickerPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onStickerScaleChange = viewModel::setStickerScale,
                onSelectManualBox = viewModel::selectManualBox,
                onEndContinuousEdit = viewModel::endContinuousEdit,
                onMoveManualBox = viewModel::moveManualBox,
                onResizeManualBox = viewModel::resizeManualBox,
                onDeleteManualBox = viewModel::deleteManualBox,
                // 2. Color Adjust
                onSelectColorPreset = viewModel::selectColorPreset,
                onBrightnessChange = viewModel::setBrightness,
                onContrastChange = viewModel::setContrast,
                onSaturationChange = viewModel::setSaturation,
                onWarmthChange = viewModel::setWarmth,
                onVignetteChange = viewModel::setVignette,
                onResetColorGrade = viewModel::resetColorGrade,
                // 3. Transform
                onSelectAspectRatio = viewModel::setAspectRatioPreset,
                onRotate90 = viewModel::rotateClockwise90,
                onToggleFlipH = viewModel::toggleFlipHorizontal,
                onToggleFlipV = viewModel::toggleFlipVertical,
                onZoomScaleChange = viewModel::setZoomScale,
                onResetTransform = viewModel::resetTransform,
                // 4. Speed
                onSelectSpeedPreset = { preset -> viewModel.setSpeedMultiplier(preset, recordUndo = true) },
                onSpeedSliderChange = { speed -> viewModel.setSpeedMultiplier(speed, recordUndo = false) },
                onTogglePreservePitch = viewModel::setPreservePitch,
                // 5. Audio
                onOriginalVolumeChange = viewModel::setOriginalVolume,
                onPickMusic = { musicPickerLauncher.launch(arrayOf("audio/*")) },
                onRemoveMusic = viewModel::removeBackgroundMusic,
                onMusicVolumeChange = viewModel::setMusicVolume,
                onToggleLoopMusic = viewModel::setLoopMusic,
                // 6. Text Overlays
                onAddTextOverlay = viewModel::addTextOverlay,
                onSelectTextOverlay = viewModel::selectTextOverlay,
                onDeleteTextOverlay = viewModel::deleteTextOverlay,
                onUpdateTextContent = viewModel::updateTextOverlayContent,
                onUpdateTextStyle = viewModel::updateTextOverlayStyle,
                onUpdateTextFontSize = viewModel::updateTextOverlayFontSize,
                onUpdateTextColor = viewModel::updateTextOverlayColor,
                onUpdateTextOpacity = viewModel::updateTextOverlayOpacity,
                onUpdateTextTiming = viewModel::updateTextOverlayTiming,
                onMoveTextOverlay = viewModel::moveTextOverlay,
                // 7. Watermark
                onSelectWatermarkType = viewModel::setWatermarkType,
                onWatermarkTextChange = viewModel::setWatermarkText,
                onWatermarkFontSizeChange = viewModel::setWatermarkFontSize,
                onWatermarkTextColorChange = viewModel::setWatermarkTextColor,
                onPickWatermarkLogo = {
                    logoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onWatermarkLogoScaleChange = viewModel::setWatermarkLogoScale,
                onWatermarkOpacityChange = viewModel::setWatermarkOpacity,
                onSelectWatermarkPreset = viewModel::setWatermarkPreset,
                onMoveWatermark = viewModel::moveWatermark,
                // 8. Trim & Cut
                onUpdatePlaybackState = viewModel::updatePlaybackState,
                onUpdateTrimRange = viewModel::updateTrimRange,
                onSetTrimInAtPlayhead = viewModel::setTrimInAtPlayhead,
                onSetTrimOutAtPlayhead = viewModel::setTrimOutAtPlayhead,
                onResetTrimAndCuts = viewModel::resetTrimAndCuts,
                onAddCutSegment = viewModel::addCutAroundPlayhead,
                onUpdateCutSegment = viewModel::updateCutSegment,
                onRemoveCutSegment = viewModel::removeCutSegment
            )
        }

        // Floating Background Export Banner when render continues minimized
        val bgJob = activeBackgroundJob
        val showBgBanner = bgJob != null && (
            (bgJob.isBatch && uiState.isBatchSheetMinimized) ||
                (!bgJob.isBatch && uiState.isExportSheetMinimized)
            )
        if (showBgBanner && bgJob != null) {
            BackgroundExportBanner(
                job = bgJob,
                onOpenSheet = viewModel::reopenBackgroundExportSheet,
                onCancel = {
                    if (bgJob.isBatch) viewModel.cancelBatchExport() else viewModel.cancelExport()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 56.dp)
            )
        }

        ExportBottomSheet(
            uiState = uiState,
            onSelectVideoQuality = viewModel::setVideoExportQuality,
            onSelectImageFormat = viewModel::setImageExportFormat,
            onStartExport = { runWithNotificationPermission { viewModel.startExport() } },
            onApplyToBatch = launchBatchPicker,
            onMinimizeToBackground = viewModel::minimizeExportToBackground,
            onCancelExport = viewModel::cancelExport,
            onDismiss = viewModel::dismissExportSheet,
            onShareExport = shareSingleMedia,
            onOpenExport = openMedia
        )

        BatchExportSheet(
            uiState = uiState,
            onSelectMaskMode = viewModel::setMaskMode,
            onBlurIntensityChange = viewModel::setBlurIntensity,
            onPixelSizeChange = viewModel::setPixelBlockSize,
            onSliderFinished = viewModel::endContinuousEdit,
            onSelectVideoQuality = viewModel::setVideoExportQuality,
            onSelectImageFormat = viewModel::setImageExportFormat,
            onSetIncludeWatermark = viewModel::setBatchIncludeWatermark,
            onRemoveItem = viewModel::removeBatchItem,
            onPickDifferentBatch = launchBatchPicker,
            onStartBatch = { runWithNotificationPermission { viewModel.startBatchExport() } },
            onMinimizeToBackground = viewModel::minimizeBatchToBackground,
            onCancelBatch = viewModel::cancelBatchExport,
            onDismiss = viewModel::dismissBatchSheet,
            onShareAll = shareMultipleMedia
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary
            )
        }
    }
}
