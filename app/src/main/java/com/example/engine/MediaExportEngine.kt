package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.model.EditorUiState
import com.example.model.ExportState
import com.example.model.MaskMode
import com.example.model.MediaType
import com.example.model.WatermarkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class MediaExportEngine(
    private val context: Context,
    private val metadataLoader: MediaMetadataLoader
) {

    @OptIn(UnstableApi::class)
    suspend fun export(
        state: EditorUiState,
        onProgress: (progress: Float, stage: String) -> Unit
    ): ExportState.Success {
        val asset = state.mediaAsset ?: error("No media loaded")
        return when (asset.type) {
            MediaType.IMAGE -> exportImage(state, onProgress)
            MediaType.VIDEO -> exportVideo(state, onProgress)
        }
    }

    private suspend fun exportImage(
        state: EditorUiState,
        onProgress: (progress: Float, stage: String) -> Unit
    ): ExportState.Success = withContext(Dispatchers.IO) {
        val asset = state.mediaAsset ?: error("No image loaded")
        val format = state.imageExportFormat
        onProgress(0.12f, "Decoding full-resolution image...")

        val sourceBitmap = metadataLoader.decodeOrientedBitmap(asset.uri, maxDimension = 3840)
            ?: error("Could not decode selected image")

        val rendered = try {
            onProgress(0.45f, "Applying privacy masks & watermark...")
            MaskRenderUtils.renderImageEdits(
                sourceBitmap = sourceBitmap,
                activeRects = state.activeMaskRectsAt(0L),
                maskConfig = state.maskConfig,
                stickerBitmap = state.stickerBitmap,
                watermarkConfig = state.watermarkConfig,
                watermarkLogoBitmap = state.watermarkLogoBitmap
            )
        } finally {
            sourceBitmap.recycle()
        }

        try {
            onProgress(0.78f, "Saving to Pictures/MaskFrame...")
            val fileName = "MaskFrame_${timestampTag()}.${format.extension}"
            val uri = saveImageToMediaStore(rendered, fileName, format.mimeType, format.compressFormat, format.quality)
            onProgress(1f, "Export complete")
            ExportState.Success(
                outputUri = uri,
                displayName = fileName,
                mimeType = format.mimeType,
                folderPath = "Pictures/MaskFrame"
            )
        } finally {
            rendered.recycle()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun exportVideo(
        state: EditorUiState,
        onProgress: (progress: Float, stage: String) -> Unit
    ): ExportState.Success {
        val asset = state.mediaAsset ?: error("No video loaded")
        val trimConfig = state.trimCutConfig
        val keptRanges = trimConfig.keptRanges().ifEmpty {
            listOf(0L..asset.durationMs.coerceAtLeast(100L))
        }

        val targetHeight = state.videoExportQuality.targetHeight
        val (outWidth, outHeight) = computeOutputDimensions(asset.width, asset.height, targetHeight)

        val videoEffects = mutableListOf<Effect>()
        if (targetHeight != null && asset.height > targetHeight) {
            videoEffects.add(Presentation.createForHeight(targetHeight))
        }

        val hasAnyMasks = state.faceTracks.any { state.maskConfig.applyToAllFaces || it.isSelected } ||
            state.manualBoxes.isNotEmpty()
        val needsShaderMask = hasAnyMasks && (
            state.maskConfig.mode == MaskMode.GAUSSIAN_BLUR ||
                state.maskConfig.mode == MaskMode.PIXELATE
            )
        val needsCanvasMask = hasAnyMasks && (
            state.maskConfig.mode == MaskMode.SOLID_COLOR ||
                state.maskConfig.mode == MaskMode.EMOJI ||
                state.maskConfig.mode == MaskMode.STICKER
            )
        val needsWatermark = state.watermarkConfig.type != WatermarkType.NONE

        if (needsShaderMask) {
            videoEffects.add(
                PrivacyMaskGlEffect(
                    mode = state.maskConfig.mode,
                    blurIntensity = state.maskConfig.blurIntensity,
                    pixelBlockSize = state.maskConfig.pixelBlockSize,
                    activeRectsProvider = { outputTimeUs ->
                        val sourceMs = trimConfig.outputTimeMsToSourceTimeMs(outputTimeUs / 1000L)
                        state.activeMaskRectsAt(sourceMs)
                    }
                )
            )
        }

        var dynamicOverlay: DynamicMaskBitmapOverlay? = null
        if (needsCanvasMask || needsWatermark) {
            val overlay = DynamicMaskBitmapOverlay(
                width = outWidth,
                height = outHeight,
                state = state,
                drawCanvasMasks = needsCanvasMask
            )
            dynamicOverlay = overlay
            videoEffects.add(OverlayEffect(listOf(overlay)))
        }

        val editedItems = keptRanges.map { range ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(range.first)
                .setEndPositionMs(range.last)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(asset.uri)
                .setClippingConfiguration(clipping)
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
        }

        val composition = Composition.Builder(EditedMediaItemSequence(editedItems)).build()
        val tempFile = File(context.cacheDir, "maskframe_export_${System.currentTimeMillis()}.mp4")
        if (tempFile.exists()) tempFile.delete()

        try {
            onProgress(0.05f, "Preparing video pipeline...")
            runMedia3Transformer(composition, tempFile.absolutePath) { pct ->
                onProgress(0.05f + pct * 0.85f, "Rendering video (${(pct * 100).roundToInt()}%)...")
            }

            onProgress(0.92f, "Saving to Movies/MaskFrame...")
            val fileName = "MaskFrame_${timestampTag()}.mp4"
            val savedUri = withContext(Dispatchers.IO) {
                saveVideoFileToMediaStore(tempFile, fileName)
            }
            onProgress(1f, "Export complete")

            return ExportState.Success(
                outputUri = savedUri,
                displayName = fileName,
                mimeType = "video/mp4",
                folderPath = "Movies/MaskFrame"
            )
        } finally {
            dynamicOverlay?.releaseOverlay()
            if (tempFile.exists()) {
                runCatching { tempFile.delete() }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun runMedia3Transformer(
        composition: Composition,
        outputPath: String,
        onTransformProgress: (Float) -> Unit
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val progressHolder = ProgressHolder()
            var progressJob: kotlinx.coroutines.Job? = null

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressJob?.cancel()
                    onTransformProgress(1f)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    progressJob?.cancel()
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            }

            val transformer = Transformer.Builder(context)
                .addListener(listener)
                .build()

            transformer.start(composition, outputPath)

            progressJob = launch {
                while (isActive && cont.isActive) {
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onTransformProgress((progressHolder.progress / 100f).coerceIn(0f, 1f))
                    }
                    delay(120L)
                }
            }

            cont.invokeOnCancellation {
                progressJob?.cancel()
                runCatching { transformer.cancel() }
            }
        }
    }

    private fun saveImageToMediaStore(
        bitmap: Bitmap,
        displayName: String,
        mimeType: String,
        compressFormat: Bitmap.CompressFormat,
        quality: Int
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MaskFrame")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create gallery entry for image")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(compressFormat, quality, out)) {
                    error("Failed to compress output image")
                }
            } ?: error("Unable to open gallery output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun saveVideoFileToMediaStore(videoFile: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MaskFrame")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create gallery entry for video")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(videoFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            } ?: error("Unable to write video to gallery")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun computeOutputDimensions(srcW: Int, srcH: Int, targetHeight: Int?): Pair<Int, Int> {
        val safeW = srcW.coerceAtLeast(2)
        val safeH = srcH.coerceAtLeast(2)
        val (w, h) = if (targetHeight != null && safeH > targetHeight) {
            val scale = targetHeight.toFloat() / safeH.toFloat()
            (safeW * scale).roundToInt() to targetHeight
        } else {
            safeW to safeH
        }
        // Keep overlay bitmap reasonable in memory (max 1080 on shorter side) and even dimensions
        val maxOverlayDim = 1280
        val largest = maxOf(w, h)
        val capScale = if (largest > maxOverlayDim) maxOverlayDim.toFloat() / largest else 1f
        val finalW = ((w * capScale).roundToInt() and -2).coerceAtLeast(64)
        val finalH = ((h * capScale).roundToInt() and -2).coerceAtLeast(64)
        return finalW to finalH
    }

    private fun timestampTag(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

@OptIn(UnstableApi::class)
private class DynamicMaskBitmapOverlay(
    private val width: Int,
    private val height: Int,
    private val state: EditorUiState,
    private val drawCanvasMasks: Boolean
) : BitmapOverlay() {

    private var frameBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var frameCanvas: Canvas = Canvas(frameBitmap)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (frameBitmap.isRecycled) {
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frameCanvas = Canvas(frameBitmap)
        }
        frameCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (drawCanvasMasks) {
            val sourceMs = state.trimCutConfig.outputTimeMsToSourceTimeMs(presentationTimeUs / 1000L)
            val rects = state.activeMaskRectsAt(sourceMs)
            for (norm in rects) {
                val pixelRect = MaskRenderUtils.toPixelRect(norm, width, height)
                MaskRenderUtils.drawOverlayMask(
                    canvas = frameCanvas,
                    pixelRect = pixelRect,
                    maskConfig = state.maskConfig,
                    stickerBitmap = state.stickerBitmap
                )
            }
        }

        MaskRenderUtils.drawWatermark(
            canvas = frameCanvas,
            canvasWidth = width,
            canvasHeight = height,
            watermarkConfig = state.watermarkConfig,
            logoBitmap = state.watermarkLogoBitmap
        )

        return frameBitmap
    }

    fun releaseOverlay() {
        if (!frameBitmap.isRecycled) {
            frameBitmap.recycle()
        }
    }
}
