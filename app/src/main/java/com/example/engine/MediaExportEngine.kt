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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
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
import com.example.model.MediaType
import com.example.model.TransformConfig
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
import kotlin.math.abs
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
            onProgress(0.45f, "Applying masks, color grade & overlays...")
            MaskRenderUtils.renderImageEdits(
                sourceBitmap = sourceBitmap,
                resolvedMasks = state.resolvedMasksAt(0L),
                stickerBitmap = state.stickerBitmap,
                colorGradeConfig = state.colorGradeConfig,
                transformConfig = state.transformConfig,
                textOverlays = state.textOverlays,
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
        val speed = state.speedConfig.speedMultiplier.coerceIn(0.25f, 4.0f)
        val keptRanges = trimConfig.keptRanges().ifEmpty {
            listOf(0L..asset.durationMs.coerceAtLeast(100L))
        }

        val targetHeight = state.videoExportQuality.targetHeight
        val effectiveRatio = state.transformConfig.effectiveAspectRatio(asset.width, asset.height)
        val (outWidth, outHeight) = computeOutputDimensions(asset.width, asset.height, effectiveRatio, targetHeight)

        val videoEffects = mutableListOf<Effect>()

        // 1. Speed Control (video frame timestamps)
        if (!state.speedConfig.isDefault()) {
            videoEffects.add(SpeedChangeEffect(speed))
        }

        // 2. Privacy Masks in source coordinates BEFORE crop/rotate so ML Kit boxes lock accurately!
        val hasAnyMasks = state.faceTracks.any { state.maskConfig.applyToAllFaces || it.isSelected } ||
            state.manualBoxes.isNotEmpty()
        val hasShaderMasks = hasAnyMasks && (
            state.maskConfig.mode.isShaderEffect ||
                state.faceTracks.any { it.styleOverride?.mode?.isShaderEffect == true }
            )
        val hasCanvasMasks = hasAnyMasks && (
            !state.maskConfig.mode.isShaderEffect ||
                state.faceTracks.any { it.styleOverride?.mode?.isShaderEffect == false }
            )

        if (hasShaderMasks) {
            videoEffects.add(
                PrivacyMaskGlEffect(
                    activeMasksProvider = { outputTimeUs ->
                        val sourceMs = trimConfig.outputTimeMsToSourceTimeMs(outputTimeUs / 1000L, speed)
                        state.resolvedMasksAt(sourceMs)
                    }
                )
            )
        }

        var maskOverlay: DynamicCanvasOverlay? = null
        if (hasCanvasMasks) {
            val (srcOverlayW, srcOverlayH) = computeOutputDimensions(asset.width, asset.height, asset.aspectRatio, targetHeight)
            val overlay = DynamicCanvasOverlay(
                width = srcOverlayW,
                height = srcOverlayH,
                state = state,
                speedMultiplier = speed,
                drawCanvasMasks = true,
                drawPostTransformOverlays = false
            )
            maskOverlay = overlay
            videoEffects.add(OverlayEffect(listOf(overlay)))
        }

        // 3. Color Grading & Vignette GPU Shader
        if (!state.colorGradeConfig.isDefault()) {
            videoEffects.add(ColorGradeGlEffect(state.colorGradeConfig))
        }

        // 4. Transform: Rotate, Flip, Zoom & Social Aspect Ratio Crop
        addTransformEffects(videoEffects, asset.width, asset.height, state.transformConfig)

        // 5. Output Resolution Scaling
        if (targetHeight != null && asset.height > targetHeight) {
            videoEffects.add(Presentation.createForHeight(targetHeight))
        }

        // 6. Post-Transform Overlays: Styled Text Captions & Watermark
        val needsText = state.textOverlays.any { it.text.isNotBlank() }
        val needsWatermark = state.watermarkConfig.type != WatermarkType.NONE
        var foregroundOverlay: DynamicCanvasOverlay? = null
        if (needsText || needsWatermark) {
            val overlay = DynamicCanvasOverlay(
                width = outWidth,
                height = outHeight,
                state = state,
                speedMultiplier = speed,
                drawCanvasMasks = false,
                drawPostTransformOverlays = true
            )
            foregroundOverlay = overlay
            videoEffects.add(OverlayEffect(listOf(overlay)))
        }

        // 7. Original Clip Audio Processors (Speed + Volume Gain)
        val audioProcessors = mutableListOf<AudioProcessor>()
        val removeOriginalAudio = state.audioConfig.effectiveOriginalVolume <= 0.01f
        if (!removeOriginalAudio) {
            if (!state.speedConfig.isDefault()) {
                val sonic = SonicAudioProcessor().apply {
                    setSpeed(speed)
                    setPitch(if (state.speedConfig.preservePitch) 1.0f else speed)
                }
                audioProcessors.add(sonic)
            }
            if (state.audioConfig.effectiveOriginalVolume < 0.99f) {
                audioProcessors.add(GainAudioProcessor(state.audioConfig.effectiveOriginalVolume))
            }
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
                .setRemoveAudio(removeOriginalAudio)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence.Builder(editedItems).build())

        // 8. Optional Background Music Track (mixed into Media3 Composition)
        val musicUri = state.audioConfig.musicUri
        val musicVol = state.audioConfig.musicVolume.coerceIn(0f, 1f)
        if (musicUri != null && musicVol > 0.01f) {
            val targetOutputMs = state.effectiveVideoOutputDurationMs().coerceAtLeast(500L)
            val musicItem = MediaItem.Builder()
                .setUri(musicUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(targetOutputMs)
                        .build()
                )
                .build()
            val musicProcessors = if (musicVol < 0.99f) {
                listOf<AudioProcessor>(GainAudioProcessor(musicVol))
            } else {
                emptyList()
            }
            val editedMusic = EditedMediaItem.Builder(musicItem)
                .setRemoveVideo(true)
                .setEffects(Effects(musicProcessors, emptyList()))
                .build()
            val musicSeq = EditedMediaItemSequence.Builder(listOf(editedMusic))
                .setIsLooping(state.audioConfig.loopMusic)
                .build()
            sequences.add(musicSeq)
        }

        val composition = Composition.Builder(sequences).build()
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
            maskOverlay?.releaseOverlay()
            foregroundOverlay?.releaseOverlay()
            if (tempFile.exists()) {
                runCatching { tempFile.delete() }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun addTransformEffects(
        videoEffects: MutableList<Effect>,
        srcWidth: Int,
        srcHeight: Int,
        transform: TransformConfig
    ) {
        if (transform.isDefault()) return

        // Rotate & Flip (Media3 rotates counter-clockwise, so negate clockwise UI degrees)
        val rot = ((transform.rotationDegrees % 360) + 360) % 360
        val scaleX = (if (transform.flipHorizontal) -1f else 1f) * transform.zoomScale.coerceIn(1f, 2f)
        val scaleY = (if (transform.flipVertical) -1f else 1f) * transform.zoomScale.coerceIn(1f, 2f)

        if (rot != 0 || transform.flipHorizontal || transform.flipVertical || abs(transform.zoomScale - 1f) > 0.01f) {
            val sr = ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(if (rot == 0) 0f else (360 - rot).toFloat())
                .setScale(scaleX, scaleY)
                .build()
            videoEffects.add(sr)
        }

        // Social Aspect Ratio Center Crop in NDC [-1..1]
        val desiredRatio = transform.aspectRatio.ratio ?: return
        val rotated = (rot % 180) != 0
        val curW = (if (rotated) srcHeight else srcWidth).toFloat().coerceAtLeast(1f)
        val curH = (if (rotated) srcWidth else srcHeight).toFloat().coerceAtLeast(1f)
        val curRatio = curW / curH

        if (abs(curRatio - desiredRatio) > 0.02f) {
            if (curRatio > desiredRatio) {
                // Crop horizontally
                val halfW = (desiredRatio / curRatio).coerceIn(0.15f, 1.0f)
                videoEffects.add(Crop(-halfW, halfW, -1f, 1f))
            } else {
                // Crop vertically
                val halfH = (curRatio / desiredRatio).coerceIn(0.15f, 1.0f)
                videoEffects.add(Crop(-1f, 1f, -halfH, halfH))
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

    private fun computeOutputDimensions(
        srcW: Int,
        srcH: Int,
        aspectRatio: Float,
        targetHeight: Int?
    ): Pair<Int, Int> {
        val safeH = (targetHeight?.let { minOf(srcH, it) } ?: srcH).coerceAtLeast(64)
        val safeW = (safeH * aspectRatio.coerceIn(0.35f, 2.8f)).roundToInt().coerceAtLeast(64)
        val maxOverlayDim = 1280
        val largest = maxOf(safeW, safeH)
        val capScale = if (largest > maxOverlayDim) maxOverlayDim.toFloat() / largest else 1f
        val finalW = ((safeW * capScale).roundToInt() and -2).coerceAtLeast(64)
        val finalH = ((safeH * capScale).roundToInt() and -2).coerceAtLeast(64)
        return finalW to finalH
    }

    private fun timestampTag(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

@OptIn(UnstableApi::class)
private class DynamicCanvasOverlay(
    private val width: Int,
    private val height: Int,
    private val state: EditorUiState,
    private val speedMultiplier: Float,
    private val drawCanvasMasks: Boolean,
    private val drawPostTransformOverlays: Boolean
) : BitmapOverlay() {

    private var frameBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var frameCanvas: Canvas = Canvas(frameBitmap)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (frameBitmap.isRecycled) {
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frameCanvas = Canvas(frameBitmap)
        }
        frameCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val sourceMs = state.trimCutConfig.outputTimeMsToSourceTimeMs(
            outputMs = presentationTimeUs / 1000L,
            speedMultiplier = speedMultiplier
        )

        if (drawCanvasMasks) {
            val masks = state.resolvedMasksAt(sourceMs).filter { !it.mode.isShaderEffect }
            for (mask in masks) {
                val pixelRect = MaskRenderUtils.toPixelRect(mask.rect, width, height)
                MaskRenderUtils.drawOverlayMask(
                    canvas = frameCanvas,
                    pixelRect = pixelRect,
                    mask = mask,
                    stickerBitmap = state.stickerBitmap
                )
            }
        }

        if (drawPostTransformOverlays) {
            MaskRenderUtils.drawTextOverlays(
                canvas = frameCanvas,
                canvasWidth = width,
                canvasHeight = height,
                overlays = state.textOverlays,
                timestampMs = sourceMs,
                isVideo = true
            )
            MaskRenderUtils.drawWatermark(
                canvas = frameCanvas,
                canvasWidth = width,
                canvasHeight = height,
                watermarkConfig = state.watermarkConfig,
                logoBitmap = state.watermarkLogoBitmap
            )
        }

        return frameBitmap
    }

    fun releaseOverlay() {
        if (!frameBitmap.isRecycled) {
            frameBitmap.recycle()
        }
    }
}
