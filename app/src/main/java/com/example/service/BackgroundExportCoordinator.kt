package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.model.BatchExportState
import com.example.model.BatchQueueItem
import com.example.model.ColorGradeConfig
import com.example.model.EditorUiState
import com.example.model.ExportState
import com.example.model.FaceMaskConfig
import com.example.model.ImageExportFormat
import com.example.model.ScanAccuracy
import com.example.model.TransformConfig
import com.example.model.VideoExportQuality
import com.example.model.WatermarkConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveBackgroundJob(
    val isBatch: Boolean,
    val progress: Float,
    val stage: String
)

sealed interface PendingExportJob {
    data class Single(val snapshot: EditorUiState) : PendingExportJob

    data class Batch(
        val items: List<BatchQueueItem>,
        val sharedMask: FaceMaskConfig,
        val sharedColorGrade: ColorGradeConfig,
        val sharedTransform: TransformConfig,
        val sharedWatermark: WatermarkConfig,
        val stickerBitmap: Bitmap?,
        val watermarkLogoBitmap: Bitmap?,
        val scanAccuracy: ScanAccuracy,
        val videoExportQuality: VideoExportQuality,
        val imageExportFormat: ImageExportFormat
    ) : PendingExportJob
}

/**
 * Process-wide coordinator bridging EditorViewModel / Compose UI with the lifecycle-independent
 * [MediaExportService] Foreground Service so renders continue seamlessly in the background.
 */
object BackgroundExportCoordinator {

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _batchExportState = MutableStateFlow<BatchExportState>(BatchExportState.Idle)
    val batchExportState: StateFlow<BatchExportState> = _batchExportState.asStateFlow()

    private val _activeJob = MutableStateFlow<ActiveBackgroundJob?>(null)
    val activeJob: StateFlow<ActiveBackgroundJob?> = _activeJob.asStateFlow()

    @Volatile
    private var pendingJob: PendingExportJob? = null

    fun consumePendingJob(): PendingExportJob? {
        val job = pendingJob
        pendingJob = null
        return job
    }

    fun setExportState(state: ExportState) {
        _exportState.value = state
        if (state !is ExportState.Processing) {
            if (_activeJob.value?.isBatch == false) {
                _activeJob.value = null
            }
        }
    }

    fun setBatchExportState(state: BatchExportState) {
        _batchExportState.value = state
        if (state !is BatchExportState.Processing) {
            if (_activeJob.value?.isBatch == true) {
                _activeJob.value = null
            }
        }
    }

    fun startSingleExport(context: Context, snapshot: EditorUiState) {
        pendingJob = PendingExportJob.Single(snapshot)
        _exportState.value = ExportState.Processing(
            progress = 0f,
            stage = "Starting background render...",
            runningInBackground = true
        )
        _activeJob.value = ActiveBackgroundJob(
            isBatch = false,
            progress = 0f,
            stage = "Starting background render..."
        )
        val intent = Intent(context, MediaExportService::class.java).apply {
            action = MediaExportService.ACTION_START_EXPORT
        }
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun startBatchExport(context: Context, job: PendingExportJob.Batch) {
        pendingJob = job
        _batchExportState.value = BatchExportState.Processing(
            items = job.items,
            currentIndex = 0,
            itemProgress = 0f,
            overallProgress = 0f,
            stage = "Starting background batch...",
            runningInBackground = true
        )
        _activeJob.value = ActiveBackgroundJob(
            isBatch = true,
            progress = 0f,
            stage = "Starting background batch..."
        )
        val intent = Intent(context, MediaExportService::class.java).apply {
            action = MediaExportService.ACTION_START_EXPORT
        }
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun updateSingleProgress(progress: Float, stage: String) {
        val p = progress.coerceIn(0f, 1f)
        _exportState.value = ExportState.Processing(progress = p, stage = stage, runningInBackground = true)
        _activeJob.value = ActiveBackgroundJob(isBatch = false, progress = p, stage = stage)
    }

    fun updateBatchProgress(
        items: List<BatchQueueItem>,
        currentIndex: Int,
        itemProgress: Float,
        overallProgress: Float,
        stage: String
    ) {
        val clampedOverall = overallProgress.coerceIn(0f, 1f)
        _batchExportState.value = BatchExportState.Processing(
            items = items,
            currentIndex = currentIndex,
            itemProgress = itemProgress.coerceIn(0f, 1f),
            overallProgress = clampedOverall,
            stage = stage,
            runningInBackground = true
        )
        _activeJob.value = ActiveBackgroundJob(isBatch = true, progress = clampedOverall, stage = stage)
    }

    fun completeSingle(result: ExportState.Success) {
        _exportState.value = result
        _activeJob.value = null
    }

    fun failSingle(message: String) {
        _exportState.value = ExportState.Error(message)
        _activeJob.value = null
    }

    fun completeBatch(items: List<BatchQueueItem>, savedUris: List<Uri>) {
        _batchExportState.value = BatchExportState.Completed(items = items, savedUris = savedUris)
        _activeJob.value = null
    }

    fun failBatch(message: String) {
        _batchExportState.value = BatchExportState.Error(message)
        _activeJob.value = null
    }

    fun cancelActive(context: Context) {
        pendingJob = null
        _activeJob.value = null
        if (_exportState.value is ExportState.Processing) {
            _exportState.value = ExportState.Idle
        }
        if (_batchExportState.value is BatchExportState.Processing) {
            _batchExportState.value = BatchExportState.Idle
        }
        val intent = Intent(context, MediaExportService::class.java).apply {
            action = MediaExportService.ACTION_CANCEL_EXPORT
        }
        runCatching { context.applicationContext.startService(intent) }
    }
}
