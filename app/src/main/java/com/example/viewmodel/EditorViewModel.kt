package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.FaceDetectionEngine
import com.example.engine.MediaExportEngine
import com.example.engine.MediaMetadataLoader
import com.example.model.BatchExportState
import com.example.model.BatchItemStatus
import com.example.model.BatchQueueItem
import com.example.model.CutSegment
import com.example.model.EditSnapshot
import com.example.model.EditorTab
import com.example.model.EditorUiState
import com.example.model.ExportState
import com.example.model.FaceMaskConfig
import com.example.model.FaceScanState
import com.example.model.ImageExportFormat
import com.example.model.ManualMaskBox
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.NormalizedRect
import com.example.model.ScanAccuracy
import com.example.model.TrimCutConfig
import com.example.model.VideoExportQuality
import com.example.model.WatermarkConfig
import com.example.model.WatermarkPositionPreset
import com.example.model.WatermarkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val metadataLoader = MediaMetadataLoader(application)
    private val faceDetectionEngine = FaceDetectionEngine(application, metadataLoader)
    private val exportEngine = MediaExportEngine(application, metadataLoader)

    private val _uiState = MutableStateFlow(
        EditorUiState(
            autoDetectAndBlurOnImport = prefs.getBoolean(KEY_AUTO_DETECT_BLUR_ON_IMPORT, true)
        )
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private val maxHistorySize = 40
    private var continuousEditActive = false

    private var faceScanJob: Job? = null
    private var filmstripJob: Job? = null
    private var exportJob: Job? = null
    private var batchExportJob: Job? = null

    private fun captureSnapshot(state: EditorUiState): EditSnapshot = EditSnapshot(
        faceTracks = state.faceTracks,
        manualBoxes = state.manualBoxes,
        maskConfig = state.maskConfig,
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

    /**
     * Begins a coalesced continuous gesture/slider edit so dragging a slider or overlay
     * records exactly ONE clean Undo step rather than flooding history.
     */
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
        val previous = undoStack.removeLast()
        _uiState.update { state ->
            state.copy(
                faceTracks = previous.faceTracks,
                manualBoxes = previous.manualBoxes,
                maskConfig = previous.maskConfig,
                watermarkConfig = previous.watermarkConfig,
                trimCutConfig = previous.trimCutConfig,
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
                watermarkConfig = next.watermarkConfig,
                trimCutConfig = next.trimCutConfig,
                selectedManualBoxId = null
            ).withHistoryFlags()
        }
    }

    fun loadMedia(uri: Uri) {
        faceScanJob?.cancel()
        filmstripJob?.cancel()
        exportJob?.cancel()
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
                        imageExportFormat = current.imageExportFormat
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
        exportJob?.cancel()
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
                imageExportFormat = state.imageExportFormat
            )
        }
    }

    fun resetEdits() {
        val asset = _uiState.value.mediaAsset ?: return
        updateWithHistory { state ->
            state.copy(
                manualBoxes = emptyList(),
                selectedManualBoxId = null,
                maskConfig = FaceMaskConfig(),
                watermarkConfig = WatermarkConfig(),
                trimCutConfig = TrimCutConfig(
                    totalDurationMs = asset.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = asset.durationMs
                ),
                faceTracks = state.faceTracks.map { it.copy(isSelected = true) },
                isComparingOriginal = false,
                snackbarMessage = "Edits reset"
            )
        }
    }

    fun selectTab(tab: EditorTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setComparingOriginal(comparing: Boolean) {
        _uiState.update { it.copy(isComparingOriginal = comparing) }
    }

    fun toggleComparingOriginal() {
        _uiState.update { it.copy(isComparingOriginal = !it.isComparingOriginal) }
    }

    fun toggleMute() {
        _uiState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun setAutoDetectAndBlurOnImport(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DETECT_BLUR_ON_IMPORT, enabled).apply()
        _uiState.update { it.copy(autoDetectAndBlurOnImport = enabled) }
    }

    fun setScanAccuracy(accuracy: ScanAccuracy) {
        _uiState.update { it.copy(scanAccuracy = accuracy) }
    }

    /**
     * One-tap Auto-Detect & Blur: records an Undo snapshot, selects Gaussian Blur across all faces,
     * and runs ML Kit face detection on the active image or video.
     */
    fun autoDetectAndBlur() {
        if (_uiState.value.mediaAsset == null) return
        updateWithHistory { state ->
            state.copy(
                isComparingOriginal = false,
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
        updateWithHistory { it.copy(maskConfig = it.maskConfig.copy(applyToAllFaces = applyToAll)) }
    }

    fun toggleFaceSelection(trackId: Int) {
        updateWithHistory { state ->
            state.copy(
                maskConfig = state.maskConfig.copy(applyToAllFaces = false),
                faceTracks = state.faceTracks.map { track ->
                    if (track.id == trackId) track.copy(isSelected = !track.isSelected) else track
                }
            )
        }
    }

    fun setMaskMode(mode: MaskMode) {
        updateWithHistory { it.copy(maskConfig = it.maskConfig.copy(mode = mode)) }
    }

    fun setBlurIntensity(intensity: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(maskConfig = it.maskConfig.copy(blurIntensity = intensity.coerceIn(0.15f, 1.0f)))
        }
    }

    fun setPixelBlockSize(size: Float) {
        beginContinuousEdit()
        _uiState.update {
            it.copy(maskConfig = it.maskConfig.copy(pixelBlockSize = size.coerceIn(0.1f, 1.0f)))
        }
    }

    fun setSolidMaskColor(argb: Int) {
        updateWithHistory { it.copy(maskConfig = it.maskConfig.copy(solidColorArgb = argb)) }
    }

    fun setSelectedEmoji(emoji: String) {
        updateWithHistory {
            it.copy(maskConfig = it.maskConfig.copy(selectedEmoji = emoji, mode = MaskMode.EMOJI))
        }
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

    // Watermark controls
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

    // Video Trim & Cut controls
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

    // Single-File Export Sheet & Processing
    fun openExportSheet() {
        _uiState.update { it.copy(exportState = ExportState.Configuring) }
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
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(exportState = ExportState.Processing(0f, "Preparing export..."))
            }
            try {
                val result = exportEngine.export(snapshot) { progress, stage ->
                    _uiState.update {
                        it.copy(exportState = ExportState.Processing(progress, stage))
                    }
                }
                _uiState.update { it.copy(exportState = result) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(exportState = ExportState.Idle) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        exportState = ExportState.Error(
                            e.localizedMessage ?: "Export failed. Please try another format or clip."
                        )
                    )
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _uiState.update { it.copy(exportState = ExportState.Idle) }
    }

    fun dismissExportSheet() {
        if (_uiState.value.exportState is ExportState.Processing) {
            cancelExport()
        } else {
            _uiState.update { it.copy(exportState = ExportState.Idle) }
        }
    }

    // Batch Gallery Processing Pipeline
    fun openBatchSelection(uris: List<Uri>) {
        if (uris.isEmpty()) return
        batchExportJob?.cancel()
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
            _uiState.update { state ->
                val hasActiveWatermark = state.watermarkConfig.type != WatermarkType.NONE
                state.copy(
                    exportState = ExportState.Idle,
                    batchExportState = BatchExportState.Configuring(
                        items = resolvedItems,
                        includeWatermark = hasActiveWatermark
                    )
                )
            }
        }
    }

    fun removeBatchItem(itemId: String) {
        _uiState.update { state ->
            val current = state.batchExportState as? BatchExportState.Configuring ?: return@update state
            val updated = current.items.filterNot { it.id == itemId }
            if (updated.isEmpty()) {
                state.copy(batchExportState = BatchExportState.Idle)
            } else {
                state.copy(batchExportState = current.copy(items = updated))
            }
        }
    }

    fun setBatchIncludeWatermark(include: Boolean) {
        _uiState.update { state ->
            val current = state.batchExportState as? BatchExportState.Configuring ?: return@update state
            state.copy(batchExportState = current.copy(includeWatermark = include))
        }
    }

    fun startBatchExport() {
        val currentState = _uiState.value
        val configState = currentState.batchExportState as? BatchExportState.Configuring ?: return
        if (configState.items.isEmpty()) return

        batchExportJob?.cancel()
        batchExportJob = viewModelScope.launch {
            val items = configState.items.toMutableList()
            val totalCount = items.size
            val savedUris = mutableListOf<Uri>()
            val sharedMask = currentState.maskConfig.copy(applyToAllFaces = true)
            val sharedWatermark = if (configState.includeWatermark) {
                currentState.watermarkConfig
            } else {
                WatermarkConfig(type = WatermarkType.NONE)
            }

            try {
                for (index in items.indices) {
                    ensureActive()
                    val queueItem = items[index]

                    // 1. Detect Faces with ML Kit
                    items[index] = queueItem.copy(status = BatchItemStatus.DETECTING_FACES)
                    emitBatchProcessing(items, index, 0.05f, totalCount, "Scanning faces (${index + 1}/$totalCount)…")

                    try {
                        val asset = metadataLoader.loadAsset(queueItem.uri)
                        val tracks = if (asset.type == MediaType.IMAGE) {
                            faceDetectionEngine.detectFacesInImage(asset.uri)
                        } else {
                            faceDetectionEngine.scanVideoFaces(
                                uri = asset.uri,
                                durationMs = asset.durationMs,
                                accuracy = currentState.scanAccuracy
                            ) { scanProgress, _, _ ->
                                emitBatchProcessing(
                                    items = items,
                                    currentIndex = index,
                                    itemProgress = scanProgress * 0.45f,
                                    totalCount = totalCount,
                                    stage = "Scanning faces (${index + 1}/$totalCount)…"
                                )
                            }
                        }

                        // 2. Render & Save via MediaExportEngine
                        items[index] = items[index].copy(
                            status = BatchItemStatus.RENDERING,
                            detectedFaces = tracks.size
                        )
                        val itemEditorState = EditorUiState(
                            mediaAsset = asset,
                            faceTracks = tracks,
                            maskConfig = sharedMask,
                            stickerBitmap = currentState.stickerBitmap,
                            watermarkConfig = sharedWatermark,
                            watermarkLogoBitmap = currentState.watermarkLogoBitmap,
                            trimCutConfig = TrimCutConfig(
                                totalDurationMs = asset.durationMs,
                                trimStartMs = 0L,
                                trimEndMs = asset.durationMs
                            ),
                            videoExportQuality = currentState.videoExportQuality,
                            imageExportFormat = currentState.imageExportFormat
                        )

                        val result = exportEngine.export(itemEditorState) { renderProgress, _ ->
                            emitBatchProcessing(
                                items = items,
                                currentIndex = index,
                                itemProgress = 0.45f + renderProgress * 0.55f,
                                totalCount = totalCount,
                                stage = "Rendering ${asset.displayName} (${index + 1}/$totalCount)…"
                            )
                        }

                        items[index] = items[index].copy(
                            status = BatchItemStatus.SAVED,
                            outputUri = result.outputUri,
                            outputMimeType = result.mimeType
                        )
                        savedUris.add(result.outputUri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        items[index] = items[index].copy(
                            status = BatchItemStatus.FAILED,
                            errorMessage = e.localizedMessage ?: "Export error"
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        batchExportState = BatchExportState.Completed(
                            items = items.toList(),
                            savedUris = savedUris.toList()
                        ),
                        snackbarMessage = "Batch complete: ${savedUris.size} of $totalCount saved to gallery"
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(batchExportState = BatchExportState.Idle) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        batchExportState = BatchExportState.Error(
                            e.localizedMessage ?: "Batch export interrupted"
                        )
                    )
                }
            }
        }
    }

    private fun emitBatchProcessing(
        items: List<BatchQueueItem>,
        currentIndex: Int,
        itemProgress: Float,
        totalCount: Int,
        stage: String
    ) {
        val clampedItem = itemProgress.coerceIn(0f, 1f)
        val overall = ((currentIndex + clampedItem) / totalCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        _uiState.update {
            it.copy(
                batchExportState = BatchExportState.Processing(
                    items = items.toList(),
                    currentIndex = currentIndex,
                    itemProgress = clampedItem,
                    overallProgress = overall,
                    stage = stage
                )
            )
        }
    }

    fun cancelBatchExport() {
        batchExportJob?.cancel()
        _uiState.update { it.copy(batchExportState = BatchExportState.Idle) }
    }

    fun dismissBatchSheet() {
        if (_uiState.value.batchExportState is BatchExportState.Processing) {
            cancelBatchExport()
        } else {
            _uiState.update { it.copy(batchExportState = BatchExportState.Idle) }
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
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
