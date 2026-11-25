package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.FaceDetectionEngine
import com.example.engine.MediaExportEngine
import com.example.engine.MediaMetadataLoader
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val metadataLoader = MediaMetadataLoader(application)
    private val faceDetectionEngine = FaceDetectionEngine(application, metadataLoader)
    private val exportEngine = MediaExportEngine(application, metadataLoader)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private val maxHistorySize = 30

    private var faceScanJob: Job? = null
    private var filmstripJob: Job? = null
    private var exportJob: Job? = null

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
        undoStack.addLast(captureSnapshot(current))
        while (undoStack.size > maxHistorySize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun updateWithHistory(
        recordUndo: Boolean = true,
        transform: (EditorUiState) -> EditorUiState
    ) {
        if (recordUndo) {
            pushUndoSnapshot()
        }
        _uiState.update { state ->
            transform(state).copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
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
                selectedManualBoxId = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
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
                selectedManualBoxId = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
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
                _uiState.update { current ->
                    current.filmstripThumbnails.forEach { if (!it.isRecycled) it.recycle() }
                    EditorUiState(
                        mediaAsset = asset,
                        activeTab = EditorTab.FACE_MASK,
                        scanAccuracy = current.scanAccuracy,
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

                if (asset.type == MediaType.IMAGE) {
                    scanFaces()
                } else {
                    loadVideoFilmstrip(uri, asset.durationMs)
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
                scanAccuracy = state.scanAccuracy,
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
                snackbarMessage = "All edits reset"
            )
        }
    }

    fun selectTab(tab: EditorTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setComparingOriginal(comparing: Boolean) {
        _uiState.update { it.copy(isComparingOriginal = comparing) }
    }

    fun toggleMute() {
        _uiState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun setScanAccuracy(accuracy: ScanAccuracy) {
        _uiState.update { it.copy(scanAccuracy = accuracy) }
    }

    fun scanFaces() {
        val asset = _uiState.value.mediaAsset ?: return
        faceScanJob?.cancel()
        faceScanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(faceScanState = FaceScanState.Scanning(progress = 0f, framesScanned = 0, totalFrames = 1))
            }
            try {
                val tracks = if (asset.type == MediaType.IMAGE) {
                    faceDetectionEngine.detectFacesInImage(asset.uri)
                } else {
                    faceDetectionEngine.scanVideoFaces(
                        uri = asset.uri,
                        durationMs = asset.durationMs,
                        accuracy = _uiState.value.scanAccuracy
                    ) { progress, scanned, total ->
                        _uiState.update {
                            it.copy(
                                faceScanState = FaceScanState.Scanning(
                                    progress = progress,
                                    framesScanned = scanned,
                                    totalFrames = total
                                )
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        faceTracks = tracks,
                        faceScanState = FaceScanState.Completed(tracks.size),
                        snackbarMessage = if (tracks.isEmpty()) {
                            "No faces detected. Tap Add Box to mask manually."
                        } else {
                            "Locked onto ${tracks.size} ${if (tracks.size == 1) "face" else "faces"}"
                        }
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
        _uiState.update {
            it.copy(maskConfig = it.maskConfig.copy(blurIntensity = intensity.coerceIn(0.15f, 1.0f)))
        }
    }

    fun setPixelBlockSize(size: Float) {
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

    fun beginGestureEdit() {
        pushUndoSnapshot()
        _uiState.update {
            it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
    }

    fun moveManualBox(boxId: String, dxNorm: Float, dyNorm: Float) {
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
        _uiState.update { it.copy(watermarkConfig = it.watermarkConfig.copy(text = text.take(48))) }
    }

    fun setWatermarkFontSize(sizeSp: Float) {
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
        _uiState.update {
            it.copy(watermarkConfig = it.watermarkConfig.copy(logoScale = scale.coerceIn(0.08f, 0.48f)))
        }
    }

    fun setWatermarkOpacity(opacity: Float) {
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

    // Export Sheet & Processing
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
                it.copy(exportState = ExportState.Processing(0f, "Starting export..."))
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

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.filmstripThumbnails.forEach { if (!it.isRecycled) it.recycle() }
        _uiState.value.stickerBitmap?.let { if (!it.isRecycled) it.recycle() }
        _uiState.value.watermarkLogoBitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}
