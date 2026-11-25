package com.example

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.EditorWorkspaceScreen
import com.example.ui.LaunchStudioScreen
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Zero-permission Android Photo Picker launchers (Single, Batch Multi-Select, Sticker, Logo)
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
                onToggleAutoDetectOnImport = viewModel::setAutoDetectAndBlurOnImport,
                onSelectAccuracy = viewModel::setScanAccuracy,
                onAutoDetectAndBlur = viewModel::autoDetectAndBlur,
                onCancelScan = viewModel::cancelFaceScan,
                onAddManualBox = viewModel::addManualBox,
                onSetApplyToAll = viewModel::setApplyToAllFaces,
                onToggleFaceTrack = viewModel::toggleFaceSelection,
                onSelectMaskMode = viewModel::setMaskMode,
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

        ExportBottomSheet(
            uiState = uiState,
            onSelectVideoQuality = viewModel::setVideoExportQuality,
            onSelectImageFormat = viewModel::setImageExportFormat,
            onStartExport = viewModel::startExport,
            onApplyToBatch = launchBatchPicker,
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
            onStartBatch = viewModel::startBatchExport,
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
