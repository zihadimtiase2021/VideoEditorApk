package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.engine.FaceDetectionEngine
import com.example.engine.MediaExportEngine
import com.example.engine.MediaMetadataLoader
import com.example.model.BatchItemStatus
import com.example.model.EditorUiState
import com.example.model.MediaType
import com.example.model.TrimCutConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Play-compliant Foreground Service (mediaProcessing on Android 15+, dataSync on Android 14)
 * that executes single and batch media exports reliably in the background with a live
 * progress notification bar and inline Cancel action.
 */
class MediaExportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeRenderJob: Job? = null
    private var lastNotifyTimeMs: Long = 0L

    private lateinit var notificationManager: NotificationManager
    private lateinit var metadataLoader: MediaMetadataLoader
    private lateinit var faceDetectionEngine: FaceDetectionEngine
    private lateinit var exportEngine: MediaExportEngine

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        metadataLoader = MediaMetadataLoader(applicationContext)
        faceDetectionEngine = FaceDetectionEngine(applicationContext, metadataLoader)
        exportEngine = MediaExportEngine(applicationContext, metadataLoader)
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_EXPORT -> {
                activeRenderJob?.cancel()
                activeRenderJob = null
                BackgroundExportCoordinator.setExportState(com.example.model.ExportState.Idle)
                BackgroundExportCoordinator.setBatchExportState(com.example.model.BatchExportState.Idle)
                finishService()
                return START_NOT_STICKY
            }

            ACTION_START_EXPORT -> {
                val job = BackgroundExportCoordinator.consumePendingJob()
                if (job == null) {
                    finishService()
                    return START_NOT_STICKY
                }
                promoteToForeground(
                    title = if (job is PendingExportJob.Batch) "Batch Exporting Media" else "Exporting Media",
                    stage = "Preparing hardware render...",
                    progressPercent = 0
                )
                startRenderJob(job)
            }

            else -> {
                finishService()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeRenderJob?.cancel()
        activeRenderJob = null
        BackgroundExportCoordinator.failSingle("Background export timed out by system. Please retry.")
        finishService()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeRenderJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRenderJob(job: PendingExportJob) {
        activeRenderJob?.cancel()
        activeRenderJob = serviceScope.launch {
            try {
                when (job) {
                    is PendingExportJob.Single -> runSingleExport(job)
                    is PendingExportJob.Batch -> runBatchExport(job)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                finishService()
            }
        }
    }

    private suspend fun runSingleExport(job: PendingExportJob.Single) {
        try {
            val result = exportEngine.export(job.snapshot) { progress, stage ->
                BackgroundExportCoordinator.updateSingleProgress(progress, stage)
                notifyProgressThrottled(
                    title = "Exporting ${job.snapshot.mediaAsset?.displayName ?: "Media"}",
                    stage = stage,
                    progressPercent = (progress * 100f).roundToInt().coerceIn(0, 100)
                )
            }
            BackgroundExportCoordinator.completeSingle(result)
            postCompletionNotification(
                title = "Export Saved to Gallery",
                body = "${result.folderPath}/${result.displayName}",
                openUri = result.outputUri,
                mimeType = result.mimeType
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Export failed. Please try another format or clip."
            BackgroundExportCoordinator.failSingle(msg)
            postCompletionNotification(
                title = "Export Failed",
                body = msg,
                openUri = null,
                mimeType = null
            )
        }
    }

    private suspend fun runBatchExport(job: PendingExportJob.Batch) {
        val items = job.items.toMutableList()
        val totalCount = items.size.coerceAtLeast(1)
        val savedUris = mutableListOf<Uri>()

        try {
            for (index in items.indices) {
                coroutineContext.ensureActive()
                val queueItem = items[index]
                items[index] = queueItem.copy(status = BatchItemStatus.DETECTING_FACES)

                val scanStage = "Scanning faces (${index + 1}/$totalCount)…"
                emitBatchUpdate(items, index, 0.05f, totalCount, scanStage)

                try {
                    val asset = metadataLoader.loadAsset(queueItem.uri)
                    val tracks = if (asset.type == MediaType.IMAGE) {
                        faceDetectionEngine.detectFacesInImage(asset.uri)
                    } else {
                        faceDetectionEngine.scanVideoFaces(
                            uri = asset.uri,
                            durationMs = asset.durationMs,
                            accuracy = job.scanAccuracy
                        ) { scanProgress, _, _ ->
                            emitBatchUpdate(
                                items = items,
                                currentIndex = index,
                                itemProgress = scanProgress * 0.45f,
                                totalCount = totalCount,
                                stage = scanStage
                            )
                        }
                    }

                    items[index] = items[index].copy(
                        status = BatchItemStatus.RENDERING,
                        detectedFaces = tracks.size
                    )

                    val itemState = EditorUiState(
                        mediaAsset = asset,
                        faceTracks = tracks,
                        maskConfig = job.sharedMask,
                        stickerBitmap = job.stickerBitmap,
                        colorGradeConfig = job.sharedColorGrade,
                        transformConfig = job.sharedTransform,
                        watermarkConfig = job.sharedWatermark,
                        watermarkLogoBitmap = job.watermarkLogoBitmap,
                        trimCutConfig = TrimCutConfig(
                            totalDurationMs = asset.durationMs,
                            trimStartMs = 0L,
                            trimEndMs = asset.durationMs
                        ),
                        videoExportQuality = job.videoExportQuality,
                        imageExportFormat = job.imageExportFormat
                    )

                    val result = exportEngine.export(itemState) { renderProgress, _ ->
                        emitBatchUpdate(
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

            BackgroundExportCoordinator.completeBatch(items.toList(), savedUris.toList())
            postCompletionNotification(
                title = "Batch Export Complete",
                body = "Saved ${savedUris.size} of $totalCount files to MaskFrame gallery",
                openUri = savedUris.firstOrNull(),
                mimeType = items.firstOrNull { it.outputUri != null }?.outputMimeType
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Batch export interrupted"
            BackgroundExportCoordinator.failBatch(msg)
            postCompletionNotification(
                title = "Batch Export Interrupted",
                body = msg,
                openUri = null,
                mimeType = null
            )
        }
    }

    private fun emitBatchUpdate(
        items: List<com.example.model.BatchQueueItem>,
        currentIndex: Int,
        itemProgress: Float,
        totalCount: Int,
        stage: String
    ) {
        val clampedItem = itemProgress.coerceIn(0f, 1f)
        val overall = ((currentIndex + clampedItem) / totalCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        BackgroundExportCoordinator.updateBatchProgress(
            items = items.toList(),
            currentIndex = currentIndex,
            itemProgress = clampedItem,
            overallProgress = overall,
            stage = stage
        )
        notifyProgressThrottled(
            title = "Batch Processing (${currentIndex + 1}/$totalCount)",
            stage = stage,
            progressPercent = (overall * 100f).roundToInt().coerceIn(0, 100)
        )
    }

    private fun promoteToForeground(title: String, stage: String, progressPercent: Int) {
        val notification = buildProgressNotification(title, stage, progressPercent)
        val fgsType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID_PROGRESS, notification, fgsType)
    }

    private fun notifyProgressThrottled(title: String, stage: String, progressPercent: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTimeMs < 240L && progressPercent < 100) return
        lastNotifyTimeMs = now
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(title, stage, progressPercent)
        )
    }

    private fun buildProgressNotification(
        title: String,
        stage: String,
        progressPercent: Int
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            10,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, MediaExportService::class.java).apply {
            action = ACTION_CANCEL_EXPORT
        }
        val cancelPending = PendingIntent.getService(
            this,
            11,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$stage ($progressPercent%)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progressPercent, progressPercent == 0)
            .setContentIntent(contentPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPending
            )
            .build()
    }

    private fun postCompletionNotification(
        title: String,
        body: String,
        openUri: Uri?,
        mimeType: String?
    ) {
        val tapIntent = if (openUri != null && mimeType != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(openUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val pending = PendingIntent.getActivity(
            this,
            12,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID_PROGRESS,
                getString(R.string.notification_channel_progress_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_progress_desc)
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                getString(R.string.notification_channel_done_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_done_desc)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completeChannel)
        }
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START_EXPORT = "com.example.action.START_BACKGROUND_EXPORT"
        const val ACTION_CANCEL_EXPORT = "com.example.action.CANCEL_BACKGROUND_EXPORT"

        private const val CHANNEL_ID_PROGRESS = "maskframe_export_progress"
        private const val CHANNEL_ID_COMPLETE = "maskframe_export_complete"
        private const val NOTIFICATION_ID_PROGRESS = 4101
        private const val NOTIFICATION_ID_COMPLETE = 4102
    }
}
