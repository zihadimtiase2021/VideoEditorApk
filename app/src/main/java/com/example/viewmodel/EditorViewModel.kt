package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.FaceDetectionEngine
import com.example.engine.MediaMetadataLoader
import com.example.model.AspectRatioPreset
import com.example.model.AudioConfig
import com.example.model.BatchExportState
import com.example.model.BatchQueueItem
import com.example.model.ColorFilterPreset
import com.example.model.ColorGradeConfig
import com.example.model.CutSegment
import com.example.model.EditSnapshot
import com.example.model.EditorTab
import com.example.model.EditorUiState
import com.example.model.ExportState
import com.example.model.FaceMaskConfig
import com.example.model.FaceScanState
import com.example.model.FaceStyleOverride
import com.example.model.ImageExportFormat
import com.example.model.ManualMaskBox
import com.example.model.MaskMode
import com.example.model.MaskShape
import com.example.model.MediaType
import com.example.model.NormalizedRect
import com.example.model.ScanAccuracy
import com.example.model.SpeedConfig
import com.example.model.TextOverlayItem
import com.example.model.TextOverlayStyle
import com.example.model.TransformConfig
import com.example.model.TrimCutConfig
import com.example.model.VideoExportQuality
import com.example.model.WatermarkConfig
import com.example.model.WatermarkPositionPreset
import com.example.model.WatermarkType
import com.example.service.ActiveBackgroundJob
import com.example.service.BackgroundExportCoordinator
import com.example.service.PendingExportJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val metadataLoader = MediaMetadataLoader(application)
    private val faceDetectionEngine = FaceDetectionEngine(application, metadataLoader)

    private val _uiState = MutableStateFlow(
        EditorUiState(
            autoDetectAndBlurOnImport = prefs.getBoolean(KEY_AUTO_DETECT_BLUR_ON_IMPORT, true),
            exportState = BackgroundExportCoordinator.exportState.value,
            batchExportState = BackgroundExportCoordinator.batchExportState.value
        )
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val activeBackgroundJob: StateFlow<ActiveBackgroundJob?> = BackgroundExportCoordinator.activeJob

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private val maxHistorySize = 40
    private var continuousEditActive = false

    private var faceScanJob: Job? = null
    private var filmstripJob: Job? = null

    init {
        viewModelScope.launch {
            BackgroundExportCoordinator.exportState.collect { exportState ->
                _uiState.update { state ->
                    val resetMinimized = exportState is ExportState.Idle || exportState is ExportState.Configuring
                    state.copy(
                        exportState = exportState,
                        isExportSheetMinimized = if (resetMinimized) false else state.isExportSheetMinimized,
                        snackbarMessage = if (exportState is ExportState.Success && state.isExportSheetMinimized) {
                            "Saved ${exportState.displayName} to gallery"
                        } else {
                            state.snackbarMessage
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            BackgroundExportCoordinator.batchExportState.collect { batchState ->
                _uiState.update { state ->
                    val resetMinimized = batchState is BatchExportState.Idle || batchState is BatchExportState.Configuring
                    state.copy(
                        batchExportState = batchState,
                        isBatchSheetMinimized = if (resetMinimized) false else state.isBatchSheetMinimized,
                        snackbarMessage = if (batchState is BatchExportState.Completed && state.isBatchSheetMinimized) {
                            "Batch complete: ${batchState.savedUris.size} of ${batchState.items.size} saved"
                        } else {
                            state.snackbarMessage
                        }
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Coalesced 40-Step Undo / Redo History
    // -------------------------------------------------------------------------

    private fun captureSnapshot(state: EditorUiState): EditSnapshot = EditSnapshot(
        faceTracks = state.faceTracks,
        manualBoxes = state.manualBoxes,
        maskConfig = state.maskConfig,
        colorGradeConfig = state.colorGradeConfig,
        transformConfig = state.transformConfig,
        speedConfig = state.speedConfig,
        audioConfig = state.audioConfig,
        textOverlays = state.textOverlays,
        watermarkConfig = state.watermarkConfig,
        trimCutConfig = state.trimCutConfig
    )

    private fun pushUndoSnapshot() {
        val current = _uiState.value
        if (current.mediaAsset == null) return
        val snapshot = captureSnapshot(current)
        if (undoStack.lastOrNull() == snapshot) return
        undoStack.addLast(snapshot)
        while (undoStack.size > maxHistorySize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        continuousEditActive = false
    }

    private fun EditorUiState.withHistoryFlags(): EditorUiState = copy(
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
        undoCount = undoStack.size,
        redoCount = redoStack.size
    )

    private fun updateWithHistory(
        recordUndo: Boolean = true,
        transform: (EditorUiState) -> EditorUiState
    ) {
        if (recordUndo) {
            continuousEditActive = false
            pushUndoSnapshot()
        }
        _uiState.update { state -> transform(state).withHistoryFlags() }
    }

    fun beginContinuousEdit() {
        if (!continuousEditActive && _uiState.value.mediaAsset != null) {
            continuousEditActive = true
            pushUndoSnapshot()
            _uiState.update { it.withHistoryFlags() }
        }
    }

    fun endContinuousEdit() {
        continuousEditActive = false
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        continuousEditActive = false
        val current = _uiState.value
        redoStack.addLast(captureSnapshot(current))
        val prev = undoStack.removeLast()
        _uiState.update { state ->
            state.copy(
                faceTracks = prev.faceTracks,
                manualBoxes = prev.manualBoxes,
                maskConfig = prev.maskConfig,
                colorGradeConfig = prev.colorGradeConfig,
                transformConfig = prev.transformConfig,
                speedConfig = prev.speedConfig,
                audioConfig = prev.audioConfig,
                textOverlays = prev.textOverlays,
                watermarkConfig = prev.watermarkConfig,
                trimCutConfig = prev.trimCutConfig,
                selectedManualBoxId = null
            ).withHistoryFlags()
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        continuousEditActive = false
        val current = _uiState.value
        undoStack.addLast(captureSnapshot(current))
        val next = redoStack.removeLast()
        _uiState.update { state ->
            state.copy(
                faceTracks = next.faceTracks,
                manualBoxes = next.manualBoxes,
                maskConfig = next.maskConfig,
                colorGradeConfig = next.colorGradeConfig,
                transformConfig = next.transformConfig,
                speedConfig = next.speedConfig,
                audioConfig = next.audioConfig,
                textOverlays = next.textOverlays,
                watermarkConfig = next.watermarkConfig,
                trimCutConfig = next.trimCutConfig,
                selectedManualBoxId = null
            ).withHistoryFlags()
        }
    }

    // -------------------------------------------------------------------------
    // Media Loading & Lifecycle
    // -------------------------------------------------------------------------

    fun loadMedia(uri: Uri) {
        faceScanJob?.cancel()
        filmstripJob?.cancel()
        clearHistory()

        viewModelScope.launch {
            try {
                val asset = metadataLoader.loadAsset(uri)
                val shouldAutoDetectAndBlur = _uiState.value.autoDetectAndBlurOnImport
                _uiState.update { current ->
                    current.filmstripThumbnails.forEach { if (!it.isRecycled) it.recycle() }
                    val initialMaskConfig = if (shouldAutoDetectAndBlur) {
                        current.maskConfig.copy(
                            mode = MaskMode.GAUSSIAN_BLUR,
                            applyToAllFaces = true
                        )
                    } else {
                        current.maskConfig
                    }
                    EditorUiState(
                        mediaAsset = asset,
                        activeTab = EditorTab.FACE_MASK,
                        autoDetectAndBlurOnImport = current.autoDetectAndBlurOnImport,
                        scanAccuracy = current.scanAccuracy,
                        maskConfig = initialMaskConfig,
                        stickerBitmap = current.stickerBitmap,
                        watermarkConfig = current.watermarkConfig,
                        watermarkLogoBitmap = current.watermarkLogoBitmap,
                        trimCutConfig = TrimCutConfig(
                            totalDurationMs = asset.durationMs,
                            trimStartMs = 0L,
                            trimEndMs = asset.durationMs
                        ),
                        isLoadingFilmstrip = asset.type == MediaType.VIDEO,
                        videoExportQuality = current.videoExportQuality,
                        imageExportFormat = current.imageExportFormat,
                        exportState = BackgroundExportCoordinator.exportState.value,
                        batchExportState = BackgroundExportCoordinator.batchExportState.value,
                        isExportSheetMinimized = current.isExportSheetMinimized,
                        isBatchSheetMinimized = current.isBatchSheetMinimized
                    )
                }

                if (asset.type == MediaType.VIDEO) {
                    loadVideoFilmstrip(uri, asset.durationMs)
                }

                if (shouldAutoDetectAndBlur) {
                    scanFaces(isAutoImport = true, forceBlurAll = true)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(snackbarMessage = "Could not load selected media: ${e.localizedMessage ?: "Unsupported file"}")
                }
            }
        }
    }

    private fun loadVideoFilmstrip(uri: Uri, durationMs: Long) {
        filmstripJob?.cancel()
        filmstripJob = viewModelScope.launch {
            val frames = metadataLoader.extractFilmstrip(uri, durationMs, count = 12)
            _uiState.update { state ->
                if (state.mediaAsset?.uri == uri) {
                    state.copy(filmstripThumbnails = frames, isLoadingFilmstrip = false)
                } else {
                    frames.forEach { if (!it.isRecycled) it.recycle() }
                    state
                }
            }
        }
    }

    fun closeEditor() {
        faceScanJob?.cancel()
        filmstripJob?.cancel()
        clearHistory()
        _uiState.update { state ->
            state.filmstripThumbnails.forEach { if (!it.isRecycled) it.recycle() }
            EditorUiState(
                autoDetectAndBlurOnImport = state.autoDetectAndBlurOnImport,
                scanAccuracy = state.scanAccuracy,
                maskConfig = state.maskConfig,
                stickerBitmap = state.stickerBitmap,
                watermarkConfig = state.watermarkConfig,
                watermarkLogoBitmap = state.watermarkLogoBitmap,
                videoExportQuality = state.videoExportQuality,
                imageExportFormat = state.imageExportFormat,
                exportState = BackgroundExportCoordinator.exportState.value,
                batchExportState = BackgroundExportCoordinator.batchExportState.value,
                isExportSheetMinimized = state.isExportSheetMinimized,
                isBatchSheetMinimized = state.isBatchSheetMinimized
            )
        }
    }

    fun resetEdits() {
        val asset = _uiState.value.mediaAsset ?: return
        updateWithHistory { state ->
            state.copy(
                manualBoxes = emptyList(),
                selectedManualBoxId = null,
                selectedFaceTrackId = null,
                selectedTextOverlayId = null,
                maskConfig = FaceMaskConfig(),
                colorGradeConfig = ColorGradeConfig(),
                transformConfig = TransformConfig(),
                speedConfig = SpeedConfig(),
                audioConfig = AudioConfig(),
                textOverlays = emptyList(),
                watermarkConfig = WatermarkConfig(),
                trimCutConfig = TrimCutConfig(
                    totalDurationMs = asset.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = asset.durationMs
                ),
                faceTracks = state.faceTracks.map { it.copy(isSelected = true, styleOverride = null) },
                isComparingOriginal = false,
                snackbarMessage = "All edits reset"
            )
        }
    }

    fun selectTab(tab: EditorTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun toggleComparingOriginal() {
        _uiState.update { it.copy(isComparingOriginal = !it.isComparingOriginal) }
    }

    fun setAutoDetectAndBlurOnImport(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DETECT_BLUR_ON_IMPORT, enabled).apply()
        _uiState.update { it.copy(autoDetectAndBlurOnImport = enabled) }
    }

    fun setScanAccuracy(accuracy: ScanAccuracy) {
        _uiState.update { it.copy(scanAccuracy = accuracy) }
    }

    // -------------------------------------------------------------------------
    // 1. Face Detection, Shapes, Extra Privacy Effects & Per-Face Overrides
    // -------------------------------------------------------------------------

    fun autoDetectAndBlur() {
        if (_uiState.value.mediaAsset == null) return
        updateWithHistory { state ->
            state.copy(
                isComparingOriginal = false,
                selectedFaceTrackId = null,
                maskConfig = state.maskConfig.copy(
                    mode = MaskMode.GAUSSIAN_BLUR,
                    applyToAllFaces = true
                )
            )
        }
        scanFaces(isAutoImport = false, forceBlurAll = true)
    }

    fun scanFaces(isAutoImport: Boolean = false, forceBlurAll: Boolean = false) {
        val asset = _uiState.value.mediaAsset ?: return
        faceScanJob?.cancel()
        faceScanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    faceScanState = FaceScanState.Scanning(
                        progress = 0f,
                        framesScanned = 0,
                        totalFrames = 1,
                        isAutoImportScan = isAutoImport
                    )
                )
            }
            try {
                val tracks = if (asset.type == MediaType.IMAGE) {
                    faceDetectionEngine.detectFacesInImage(asset.uri)
                } else {
                    faceDetectionEngine.scanVideoFaces(
                        uri = asset.uri,
                        durationMs = asset.durationMs,
                        accuracy = _uiState.value.scanAccuracy,
                        onPartialTracks = { partialTracks ->
                            _uiState.update { state ->
                                if (state.mediaAsset?.uri == asset.uri) {
                                    state.copy(faceTracks = partialTracks)
                                } else {
                                    state
                                }
                            }
                        }
                    ) { progress, scanned, total ->
                        _uiState.update {
                            it.copy(
                                faceScanState = FaceScanState.Scanning(
                                    progress = progress,
                                    framesScanned = scanned,
                                    totalFrames = total,
                                    isAutoImportScan = isAutoImport
                                )
                            )
                        }
                    }
                }
                _uiState.update { state ->
                    val updatedMaskConfig = if (forceBlurAll) {
                        state.maskConfig.copy(
                            mode = MaskMode.GAUSSIAN_BLUR,
                            applyToAllFaces = true
                        )
                    } else {
                        state.maskConfig
                    }
                    val faceNoun = if (tracks.size == 1) "face" else "faces"
                    val summaryMessage = when {
                        tracks.isEmpty() -> "No faces found. Tap Add Box to mask manually."
                        isAutoImport || forceBlurAll -> "Auto-blurred ${tracks.size} $faceNoun"
                        else -> "Detected ${tracks.size} $faceNoun"
                    }
                    state.copy(
                        faceTracks = tracks,
                        maskConfig = updatedMaskConfig,
                        faceScanState = FaceScanState.Completed(tracks.size),
                        snackbarMessage = summaryMessage
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(faceScanState = FaceScanState.Idle) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        faceScanState = FaceScanState.Error(e.localizedMessage ?: "Face scan failed"),
                        snackbarMessage = "Face scan failed: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun cancelFaceScan() {
        faceScanJob?.cancel()
        _uiState.update { it.copy(faceScanState = FaceScanState.Idle) }
    }

    fun setApplyToAllFaces(applyToAll: Boolean) {
        updateWithHistory {
            it.copy(
                maskConfig = it.maskConfig.copy(applyToAllFaces = applyToAll),
                selectedFaceTrackId = if (applyToAll) null else (it.selectedFaceTrackId ?: it.faceTracks.firstOrNull()?.id)
            )
        }
    }

    fun selectFaceTrackForEditing(trackId: Int?) {
        _uiState.update { state ->
            state.copy(
                selectedFaceTrackId = trackId,
                maskConfig = if (trackId != null) state.maskConfig else state.maskConfig
            )
        }
    }

    fun toggleFaceSelection(trackId: Int) {
        updateWithHistory { state ->
            state.copy(
                maskConfig = state.maskConfig.copy(applyToAllFaces = false),
                selectedFaceTrackId = trackId,
                faceTracks = state.faceTracks.map { track ->
                    if (track.id == trackId) track.copy(isSelected = !track.isSelected) else track
                }
            )
        }
    }

    private fun updateFocusedFaceOrGlobal(
        recordUndo: Boolean,
        globalTransform: (FaceMaskConfig) -> FaceMaskConfig,
        overrideTransform: (FaceStyleOverride) -> FaceStyleOverride
    ) {
        val focusedId = _uiState.value.selectedFaceTrackId
        if (focusedId == null) {
            if (recordUndo) {
                updateWithHistory { it.copy(maskConfig = globalTransform(it.maskConfig)) }
            } else {
                beginContinuousEdit()
                _uiState.update { it.copy(maskConfig = globalTransform(it.maskConfig)) }
            }
        } else {
            val updater: (EditorUiState) -> EditorUiState = { state ->
                state.copy(
                    faceTracks = state.faceTracks.map { track ->
                        if (track.id == focusedId) {
                            val currentOv = track.styleOverride ?: FaceStyleOverride()
                            val nextOv = overrideTransform(currentOv)
                            track.copy(
                                isSelected = true,
                                styleOverride = if (nextOv.hasAnyOverride()) nextOv else null
                            )
                        } else {
                            track
                        }
                    }
                )
            }
            if (recordUndo) {
                updateWithHistory(transform = updater)
            } else {
                beginContinuousEdit()
                _uiState.update(updater)
            }
        }
    }

    fun resetFaceStyleOverride(trackId: Int) {
        updateWithHistory { state ->
            state.copy(
                faceTracks = state.faceTracks.map { track ->
                    if (track.id == trackId) track.copy(styleOverride = null) else track
                }
            )
        }
    }

    fun setMaskMode(mode: MaskMode) {
        updateFocusedFaceOrGlobal(
            recordUndo = true,
            globalTransform = { it.copy(mode = mode) },
            overrideTransform = { it.copy(mode = mode) }
        )
    }

    fun setMaskShape(shape: MaskShape) {
        updateFocusedFaceOrGlobal(
            recordUndo = true,
            globalTransform = { it.copy(shape = shape) },
            overrideTransform = { it.copy(shape = shape) }
        )
    }

    fun setMaskSizeScale(scale: Float) {
        val clamped = scale.coerceIn(0.75f, 1.80f)
        updateFocusedFaceOrGlobal(
            recordUndo = false,
            globalTransform = { it.copy(sizeScale = clamped) },
            overrideTransform = { it.copy(sizeScale = clamped) }
        )
    }

    fun setMaskFeather(feather: Float) {
        val clamped = feather.coerceIn(0f, 1f)
        updateFocusedFaceOrGlobal(
            recordUndo = false,
            globalTransform = { it.copy(feather = clamped) },
            overrideTransform = { it.copy(feather = clamped) }
        )
    }

    fun setMaskOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.25f, 1f)
        updateFocusedFaceOrGlobal(
            recordUndo = false,
            globalTransform = { it.copy(opacity = clamped) },
            overrideTransform = { it.copy(opacity = clamped) }
        )
    }

    fun setBlurIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0.15f, 1.0f)
        updateFocusedFaceOrGlobal(
            recordUndo = false,
            globalTransform = { it.copy(blurIntensity = clamped) },
            overrideTransform = { it.copy(blurIntensity = clamped) }
        )
    }

    fun setPixelBlockSize(size: Float) {
        val clamped = size.coerceIn(0.1f, 1.0f)
        updateFocusedFaceOrGlobal(
            recordUndo = false,
            globalTransform = { it.copy(pixelBlockSize = clamped) },
            overrideTransform = { it.copy(pixelBlockSize = clamped) }
        )
    }

    fun setSolidMaskColor(argb: Int) {
        updateFocusedFaceOrGlobal(
            recordUndo = true,
            globalTransform = { it.copy(solidColorArgb = argb) },
            overrideTransform = { it.copy(solidColorArgb = argb, mode = MaskMode.SOLID_COLOR) }
        )
    }

    fun setSelectedEmoji(emoji: String) {
        updateFocusedFaceOrGlobal(
            recordUndo = true,
            globalTransform = { it.copy(selectedEmoji = emoji, mode = MaskMode.EMOJI) },
            overrideTransform = { it.copy(selectedEmoji = emoji, mode = MaskMode.EMOJI) }
        )
    }

    fun setStickerUri(uri: Uri) {
        viewModelScope.launch {
            val bitmap = metadataLoader.decodeOrientedBitmap(uri, maxDimension = 512)
            if (bitmap != null) {
                updateWithHistory { state ->
                    state.stickerBitmap?.let { if (!it.isRecycled) it.recycle() }
                    state.copy(
                        maskConfig = state.maskConfig.copy(
                            stickerUri = uri,
                            mode = MaskMode.STICKER
                        ),
                        stickerBitmap = bitmap
                    )
                }
            } else {
                _uiState.update { it.copy(snackbarMessage = "Could not load sticker image") }
            }
        }
    }

    fun setStickerScale(scale: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(maskConfig = it.maskConfig.copy(stickerScale = scale.coerceIn(0.7f, 2.0f)))
        }
    }

    fun addManualBox() {
        val count = _uiState.value.manualBoxes.size
        val offset = ((count % 4) * 0.05f)
        val box = ManualMaskBox(
            rect = NormalizedRect(
                left = (0.30f + offset).coerceAtMost(0.60f),
                top = (0.28f + offset).coerceAtMost(0.58f),
                right = (0.62f + offset).coerceAtMost(0.92f),
                bottom = (0.54f + offset).coerceAtMost(0.84f)
            )
        )
        updateWithHistory {
            it.copy(
                manualBoxes = it.manualBoxes + box,
                selectedManualBoxId = box.id
            )
        }
    }

    fun selectManualBox(boxId: String?) {
        _uiState.update { it.copy(selectedManualBoxId = boxId) }
    }

    fun moveManualBox(boxId: String, dxNorm: Float, dyNorm: Float) {
        beginContinuousEdit()
        _uiState.update { state ->
            state.copy(
                selectedManualBoxId = boxId,
                manualBoxes = state.manualBoxes.map { box ->
                    if (box.id == boxId) box.copy(rect = box.rect.translated(dxNorm, dyNorm)) else box
                }
            )
        }
    }

    fun resizeManualBox(boxId: String, dwNorm: Float, dhNorm: Float) {
        beginContinuousEdit()
        _uiState.update { state ->
            state.copy(
                selectedManualBoxId = boxId,
                manualBoxes = state.manualBoxes.map { box ->
                    if (box.id == boxId) box.copy(rect = box.rect.resized(dwNorm, dhNorm)) else box
                }
            )
        }
    }

    fun deleteManualBox(boxId: String) {
        updateWithHistory { state ->
            state.copy(
                manualBoxes = state.manualBoxes.filterNot { it.id == boxId },
                selectedManualBoxId = if (state.selectedManualBoxId == boxId) null else state.selectedManualBoxId
            )
        }
    }

    // -------------------------------------------------------------------------
    // 2. Color Grading & Filters
    // -------------------------------------------------------------------------

    fun selectColorPreset(preset: ColorFilterPreset) {
        updateWithHistory {
            it.copy(
                colorGradeConfig = ColorGradeConfig(
                    preset = preset,
                    brightness = preset.brightness,
                    contrast = preset.contrast,
                    saturation = preset.saturation,
                    warmth = preset.warmth,
                    vignette = preset.vignette
                )
            )
        }
    }

    fun setBrightness(value: Float) {
        beginContinuousEdit()
        _uiState.update { it.copy(colorGradeConfig = it.colorGradeConfig.copy(brightness = value.coerceIn(-0.4f, 0.4f))) }
    }

    fun setContrast(value: Float) {
        beginContinuousEdit()
        _uiState.update { it.copy(colorGradeConfig = it.colorGradeConfig.copy(contrast = value.coerceIn(0.5f, 1.6f))) }
    }

    fun setSaturation(value: Float) {
        beginContinuousEdit()
        _uiState.update { it.copy(colorGradeConfig = it.colorGradeConfig.copy(saturation = value.coerceIn(0f, 2.0f))) }
    }

    fun setWarmth(value: Float) {
        beginContinuousEdit()
        _uiState.update { it.copy(colorGradeConfig = it.colorGradeConfig.copy(warmth = value.coerceIn(-0.5f, 0.5f))) }
    }

    fun setVignette(value: Float) {
        beginContinuousEdit()
        _uiState.update { it.copy(colorGradeConfig = it.colorGradeConfig.copy(vignette = value.coerceIn(0f, 0.85f))) }
    }

    fun resetColorGrade() {
        updateWithHistory { it.copy(colorGradeConfig = ColorGradeConfig()) }
    }

    // -------------------------------------------------------------------------
    // 3. Transform (Aspect Ratio, Rotate, Flip, Zoom)
    // -------------------------------------------------------------------------

    fun setAspectRatioPreset(preset: AspectRatioPreset) {
        updateWithHistory { it.copy(transformConfig = it.transformConfig.copy(aspectRatio = preset)) }
    }

    fun rotateClockwise90() {
        updateWithHistory {
            val next = (it.transformConfig.rotationDegrees + 90) % 360
            it.copy(transformConfig = it.transformConfig.copy(rotationDegrees = next))
        }
    }

    fun toggleFlipHorizontal() {
        updateWithHistory {
            it.copy(transformConfig = it.transformConfig.copy(flipHorizontal = !it.transformConfig.flipHorizontal))
        }
    }

    fun toggleFlipVertical() {
        updateWithHistory {
            it.copy(transformConfig = it.transformConfig.copy(flipVertical = !it.transformConfig.flipVertical))
        }
    }

    fun setZoomScale(scale: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(transformConfig = it.transformConfig.copy(zoomScale = scale.coerceIn(1.0f, 2.0f)))
        }
    }

    fun resetTransform() {
        updateWithHistory { it.copy(transformConfig = TransformConfig()) }
    }

    // -------------------------------------------------------------------------
    // 4. Playback Speed (0.25x .. 4.0x)
    // -------------------------------------------------------------------------

    fun setSpeedMultiplier(speed: Float, recordUndo: Boolean = false) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        if (recordUndo) {
            updateWithHistory { it.copy(speedConfig = it.speedConfig.copy(speedMultiplier = clamped)) }
        } else {
            beginContinuousEdit()
            _uiState.update { it.copy(speedConfig = it.speedConfig.copy(speedMultiplier = clamped)) }
        }
    }

    fun setPreservePitch(preserve: Boolean) {
        updateWithHistory { it.copy(speedConfig = it.speedConfig.copy(preservePitch = preserve)) }
    }

    // -------------------------------------------------------------------------
    // 5. Audio Studio (Volume, Mute & Background Music Track)
    // -------------------------------------------------------------------------

    fun toggleMute() {
        updateWithHistory { it.copy(audioConfig = it.audioConfig.copy(isMuted = !it.audioConfig.isMuted)) }
    }

    fun setOriginalVolume(volume: Float) {
        beginContinuousEdit()
        val clamped = volume.coerceIn(0f, 1f)
        _uiState.update {
            it.copy(
                audioConfig = it.audioConfig.copy(
                    originalVolume = clamped,
                    isMuted = clamped <= 0.01f
                )
            )
        }
    }

    fun setBackgroundMusicUri(uri: Uri) {
        val displayName = resolveDisplayName(uri) ?: "Music Track"
        updateWithHistory {
            it.copy(
                audioConfig = it.audioConfig.copy(
                    musicUri = uri,
                    musicDisplayName = displayName
                ),
                snackbarMessage = "Added music: $displayName"
            )
        }
    }

    fun removeBackgroundMusic() {
        updateWithHistory {
            it.copy(
                audioConfig = it.audioConfig.copy(
                    musicUri = null,
                    musicDisplayName = null
                )
            )
        }
    }

    fun setMusicVolume(volume: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(audioConfig = it.audioConfig.copy(musicVolume = volume.coerceIn(0f, 1f)))
        }
    }

    fun setLoopMusic(loop: Boolean) {
        updateWithHistory { it.copy(audioConfig = it.audioConfig.copy(loopMusic = loop)) }
    }

    // -------------------------------------------------------------------------
    // 6. Styled Text Captions & Title Overlays
    // -------------------------------------------------------------------------

    fun addTextOverlay() {
        val state = _uiState.value
        val totalMs = state.mediaAsset?.durationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE
        val count = state.textOverlays.size
        val item = TextOverlayItem(
            text = if (count == 0) "MaskFrame" else "Caption ${count + 1}",
            normalizedX = 0.50f,
            normalizedY = (0.76f - (count % 3) * 0.14f).coerceIn(0.20f, 0.85f),
            startMs = 0L,
            endMs = totalMs
        )
        updateWithHistory {
            it.copy(
                textOverlays = it.textOverlays + item,
                selectedTextOverlayId = item.id
            )
        }
    }

    fun selectTextOverlay(id: String?) {
        _uiState.update { it.copy(selectedTextOverlayId = id) }
    }

    fun updateTextOverlayContent(id: String, text: String) {
        beginContinuousEdit()
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == id) it.copy(text = text.take(64)) else it
                }
            )
        }
    }

    fun updateTextOverlayStyle(id: String, style: TextOverlayStyle) {
        updateWithHistory { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == id) it.copy(style = style) else it
                }
            )
        }
    }

    fun updateTextOverlayFontSize(id: String, sizeSp: Float) {
        beginContinuousEdit()
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == id) it.copy(fontSizeSp = sizeSp.coerceIn(14f, 56f)) else it
                }
            )
        }
    }

    fun updateTextOverlayColor(id: String, argb: Int) {
        updateWithHistory { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == id) it.copy(textColorArgb = argb) else it
                }
            )
        }
    }

    fun updateTextOverlayOpacity(id: String, opacity: Float) {
        beginContinuousEdit()
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == id) it.copy(opacity = opacity.coerceIn(0.2f, 1.0f)) else it
                }
            )
        }
    }

    fun updateTextOverlayTiming(id: String, startMs: Long, endMs: Long) {
        beginContinuousEdit()
        val maxDur = _uiState.value.mediaAsset?.durationMs?.coerceAtLeast(500L) ?: Long.MAX_VALUE
        val safeStart = startMs.coerceIn(0L, (maxDur - 300L).coerceAtLeast(0L))
        val safeEnd = endMs.coerceIn((safeStart + 300L).coerceAtMost(maxDur), maxDur)
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == id) it.copy(startMs = safeStart, endMs = safeEnd) else it
                }
            )
        }
    }

    fun moveTextOverlay(id: String, dxNorm: Float, dyNorm: Float) {
        beginContinuousEdit()
        _uiState.update { state ->
            state.copy(
                selectedTextOverlayId = id,
                textOverlays = state.textOverlays.map { item ->
                    if (item.id == id) {
                        item.copy(
                            normalizedX = (item.normalizedX + dxNorm).coerceIn(0.08f, 0.92f),
                            normalizedY = (item.normalizedY + dyNorm).coerceIn(0.08f, 0.92f)
                        )
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun deleteTextOverlay(id: String) {
        updateWithHistory { state ->
            val remaining = state.textOverlays.filterNot { it.id == id }
            state.copy(
                textOverlays = remaining,
                selectedTextOverlayId = if (state.selectedTextOverlayId == id) remaining.lastOrNull()?.id else state.selectedTextOverlayId
            )
        }
    }

    // -------------------------------------------------------------------------
    // 7. Watermark Controls
    // -------------------------------------------------------------------------

    fun setWatermarkType(type: WatermarkType) {
        updateWithHistory { it.copy(watermarkConfig = it.watermarkConfig.copy(type = type)) }
    }

    fun setWatermarkText(text: String) {
        beginContinuousEdit()
        _uiState.update { it.copy(watermarkConfig = it.watermarkConfig.copy(text = text.take(48))) }
    }

    fun setWatermarkFontSize(sizeSp: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(watermarkConfig = it.watermarkConfig.copy(fontSizeSp = sizeSp.coerceIn(12f, 64f)))
        }
    }

    fun setWatermarkTextColor(argb: Int) {
        updateWithHistory { it.copy(watermarkConfig = it.watermarkConfig.copy(textColorArgb = argb)) }
    }

    fun setWatermarkLogoUri(uri: Uri) {
        viewModelScope.launch {
            val bitmap = metadataLoader.decodeOrientedBitmap(uri, maxDimension = 640)
            if (bitmap != null) {
                updateWithHistory { state ->
                    state.watermarkLogoBitmap?.let { if (!it.isRecycled) it.recycle() }
                    state.copy(
                        watermarkConfig = state.watermarkConfig.copy(
                            logoUri = uri,
                            type = WatermarkType.IMAGE
                        ),
                        watermarkLogoBitmap = bitmap
                    )
                }
            } else {
                _uiState.update { it.copy(snackbarMessage = "Could not load watermark logo") }
            }
        }
    }

    fun setWatermarkLogoScale(scale: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(watermarkConfig = it.watermarkConfig.copy(logoScale = scale.coerceIn(0.08f, 0.48f)))
        }
    }

    fun setWatermarkOpacity(opacity: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(watermarkConfig = it.watermarkConfig.copy(opacity = opacity.coerceIn(0.1f, 1.0f)))
        }
    }

    fun setWatermarkPreset(preset: WatermarkPositionPreset) {
        updateWithHistory {
            it.copy(
                watermarkConfig = it.watermarkConfig.copy(
                    preset = preset,
                    normalizedX = preset.normalizedX,
                    normalizedY = preset.normalizedY
                )
            )
        }
    }

    fun moveWatermark(dxNorm: Float, dyNorm: Float) {
        beginContinuousEdit()
        _uiState.update { state ->
            val wm = state.watermarkConfig
            state.copy(
                watermarkConfig = wm.copy(
                    preset = WatermarkPositionPreset.CUSTOM,
                    normalizedX = (wm.normalizedX + dxNorm).coerceIn(0.06f, 0.94f),
                    normalizedY = (wm.normalizedY + dyNorm).coerceIn(0.06f, 0.94f)
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // 8. Video Trim & Cut Controls
    // -------------------------------------------------------------------------

    fun updatePlaybackState(positionMs: Long, isPlaying: Boolean) {
        _uiState.update {
            it.copy(currentPlaybackMs = positionMs.coerceAtLeast(0L), isPlaying = isPlaying)
        }
    }

    fun updateTrimRange(startMs: Long, endMs: Long) {
        beginContinuousEdit()
        _uiState.update { state ->
            val total = state.trimCutConfig.totalDurationMs
            val minLen = minOf(500L, total)
            val safeStart = startMs.coerceIn(0L, (total - minLen).coerceAtLeast(0L))
            val safeEnd = endMs.coerceIn((safeStart + minLen).coerceAtMost(total), total)
            state.copy(
                trimCutConfig = state.trimCutConfig.copy(
                    trimStartMs = safeStart,
                    trimEndMs = safeEnd
                )
            )
        }
    }

    fun setTrimInAtPlayhead() {
        val state = _uiState.value
        val trim = state.trimCutConfig
        val pos = state.currentPlaybackMs
        if (pos >= trim.trimEndMs - 500L) {
            _uiState.update { it.copy(snackbarMessage = "Playhead must be before Trim Out") }
            return
        }
        updateWithHistory { s ->
            s.copy(trimCutConfig = s.trimCutConfig.copy(trimStartMs = pos.coerceAtLeast(0L)))
        }
    }

    fun setTrimOutAtPlayhead() {
        val state = _uiState.value
        val trim = state.trimCutConfig
        val pos = state.currentPlaybackMs
        if (pos <= trim.trimStartMs + 500L) {
            _uiState.update { it.copy(snackbarMessage = "Playhead must be after Trim In") }
            return
        }
        updateWithHistory { s ->
            s.copy(trimCutConfig = s.trimCutConfig.copy(trimEndMs = pos.coerceAtMost(trim.totalDurationMs)))
        }
    }

    fun resetTrimAndCuts() {
        val asset = _uiState.value.mediaAsset ?: return
        updateWithHistory { s ->
            s.copy(
                trimCutConfig = TrimCutConfig(
                    totalDurationMs = asset.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = asset.durationMs
                )
            )
        }
    }

    fun addCutAroundPlayhead() {
        val state = _uiState.value
        val trim = state.trimCutConfig
        val span = (trim.trimEndMs - trim.trimStartMs)
        if (span < 1200L) {
            _uiState.update { it.copy(snackbarMessage = "Clip too short to add an interior cut") }
            return
        }
        val cutWidth = (span * 0.16f).toLong().coerceIn(500L, 3500L)
        val center = state.currentPlaybackMs.coerceIn(
            trim.trimStartMs + cutWidth / 2 + 200L,
            (trim.trimEndMs - cutWidth / 2 - 200L).coerceAtLeast(trim.trimStartMs + 200L)
        )
        val start = (center - cutWidth / 2).coerceAtLeast(trim.trimStartMs + 200L)
        val end = (center + cutWidth / 2).coerceAtMost(trim.trimEndMs - 200L)
        if (end - start < 400L) {
            _uiState.update { it.copy(snackbarMessage = "Move playhead away from edges to cut") }
            return
        }
        val segment = CutSegment(startMs = start, endMs = end)
        updateWithHistory { s ->
            s.copy(trimCutConfig = s.trimCutConfig.copy(cutSegments = s.trimCutConfig.cutSegments + segment))
        }
    }

    fun updateCutSegment(id: String, startMs: Long, endMs: Long) {
        beginContinuousEdit()
        _uiState.update { state ->
            val trim = state.trimCutConfig
            val minStart = trim.trimStartMs + 100L
            val maxEnd = (trim.trimEndMs - 100L).coerceAtLeast(minStart + 200L)
            val safeStart = startMs.coerceIn(minStart, (maxEnd - 250L).coerceAtLeast(minStart))
            val safeEnd = endMs.coerceIn((safeStart + 250L).coerceAtMost(maxEnd), maxEnd)
            state.copy(
                trimCutConfig = trim.copy(
                    cutSegments = trim.cutSegments.map { seg ->
                        if (seg.id == id) seg.copy(startMs = safeStart, endMs = safeEnd) else seg
                    }
                )
            )
        }
    }

    fun removeCutSegment(id: String) {
        updateWithHistory { state ->
            state.copy(
                trimCutConfig = state.trimCutConfig.copy(
                    cutSegments = state.trimCutConfig.cutSegments.filterNot { it.id == id }
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Background Single & Batch Export Orchestration (Foreground Service)
    // -------------------------------------------------------------------------

    fun openExportSheet() {
        _uiState.update { it.copy(isExportSheetMinimized = false) }
        if (BackgroundExportCoordinator.exportState.value is ExportState.Idle) {
            BackgroundExportCoordinator.setExportState(ExportState.Configuring)
        }
    }

    fun setVideoExportQuality(quality: VideoExportQuality) {
        _uiState.update { it.copy(videoExportQuality = quality) }
    }

    fun setImageExportFormat(format: ImageExportFormat) {
        _uiState.update { it.copy(imageExportFormat = format) }
    }

    fun startExport() {
        val snapshot = _uiState.value
        if (snapshot.mediaAsset == null) return
        _uiState.update { it.copy(isExportSheetMinimized = false) }
        BackgroundExportCoordinator.startSingleExport(appContext, snapshot)
    }

    fun minimizeExportToBackground() {
        _uiState.update {
            it.copy(
                isExportSheetMinimized = true,
                snackbarMessage = "Rendering in background • Track progress in notification bar"
            )
        }
    }

    fun reopenBackgroundExportSheet() {
        val active = activeBackgroundJob.value
        if (active?.isBatch == true || _uiState.value.batchExportState !is BatchExportState.Idle) {
            _uiState.update { it.copy(isBatchSheetMinimized = false) }
        } else {
            _uiState.update { it.copy(isExportSheetMinimized = false) }
        }
    }

    fun cancelExport() {
        BackgroundExportCoordinator.cancelActive(appContext)
        _uiState.update { it.copy(isExportSheetMinimized = false) }
    }

    fun dismissExportSheet() {
        if (_uiState.value.exportState is ExportState.Processing) {
            minimizeExportToBackground()
        } else {
            _uiState.update { it.copy(isExportSheetMinimized = false) }
            BackgroundExportCoordinator.setExportState(ExportState.Idle)
        }
    }

    fun openBatchSelection(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolvedItems = uris.mapNotNull { uri ->
                runCatching {
                    val asset = metadataLoader.loadAsset(uri)
                    BatchQueueItem(
                        uri = uri,
                        displayName = asset.displayName,
                        mediaType = asset.type
                    )
                }.getOrNull()
            }
            if (resolvedItems.isEmpty()) {
                _uiState.update { it.copy(snackbarMessage = "Could not load selected gallery files") }
                return@launch
            }
            val hasActiveWatermark = _uiState.value.watermarkConfig.type != WatermarkType.NONE
            _uiState.update { it.copy(isBatchSheetMinimized = false) }
            BackgroundExportCoordinator.setExportState(ExportState.Idle)
            BackgroundExportCoordinator.setBatchExportState(
                BatchExportState.Configuring(
                    items = resolvedItems,
                    includeWatermark = hasActiveWatermark
                )
            )
        }
    }

    fun removeBatchItem(itemId: String) {
        val current = BackgroundExportCoordinator.batchExportState.value as? BatchExportState.Configuring ?: return
        val updated = current.items.filterNot { it.id == itemId }
        if (updated.isEmpty()) {
            BackgroundExportCoordinator.setBatchExportState(BatchExportState.Idle)
        } else {
            BackgroundExportCoordinator.setBatchExportState(current.copy(items = updated))
        }
    }

    fun setBatchIncludeWatermark(include: Boolean) {
        val current = BackgroundExportCoordinator.batchExportState.value as? BatchExportState.Configuring ?: return
        BackgroundExportCoordinator.setBatchExportState(current.copy(includeWatermark = include))
    }

    fun startBatchExport() {
        val currentState = _uiState.value
        val configState = currentState.batchExportState as? BatchExportState.Configuring ?: return
        if (configState.items.isEmpty()) return

        _uiState.update { it.copy(isBatchSheetMinimized = false) }
        val sharedWatermark = if (configState.includeWatermark) {
            currentState.watermarkConfig
        } else {
            WatermarkConfig(type = WatermarkType.NONE)
        }
        BackgroundExportCoordinator.startBatchExport(
            context = appContext,
            job = PendingExportJob.Batch(
                items = configState.items,
                sharedMask = currentState.maskConfig.copy(applyToAllFaces = true),
                sharedColorGrade = currentState.colorGradeConfig,
                sharedTransform = currentState.transformConfig,
                sharedWatermark = sharedWatermark,
                stickerBitmap = currentState.stickerBitmap,
                watermarkLogoBitmap = currentState.watermarkLogoBitmap,
                scanAccuracy = currentState.scanAccuracy,
                videoExportQuality = currentState.videoExportQuality,
                imageExportFormat = currentState.imageExportFormat
            )
        )
    }

    fun minimizeBatchToBackground() {
        _uiState.update {
            it.copy(
                isBatchSheetMinimized = true,
                snackbarMessage = "Batch running in background • Check notification bar"
            )
        }
    }

    fun cancelBatchExport() {
        BackgroundExportCoordinator.cancelActive(appContext)
        _uiState.update { it.copy(isBatchSheetMinimized = false) }
    }

    fun dismissBatchSheet() {
        if (_uiState.value.batchExportState is BatchExportState.Processing) {
            minimizeBatchToBackground()
        } else {
            _uiState.update { it.copy(isBatchSheetMinimized = false) }
            BackgroundExportCoordinator.setBatchExportState(BatchExportState.Idle)
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.filmstripThumbnails.forEach { if (!it.isRecycled) it.recycle() }
        _uiState.value.stickerBitmap?.let { if (!it.isRecycled) it.recycle() }
        _uiState.value.watermarkLogoBitmap?.let { if (!it.isRecycled) it.recycle() }
    }

    companion object {
        private const val PREFS_NAME = "maskframe_prefs"
        private const val KEY_AUTO_DETECT_BLUR_ON_IMPORT = "auto_detect_blur_on_import"
    }
}
