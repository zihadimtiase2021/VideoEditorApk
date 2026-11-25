package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.example.model.MediaAsset
import com.example.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class MediaMetadataLoader(private val context: Context) {

    suspend fun loadAsset(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val displayName = resolveDisplayName(uri)
        val isVideo = mimeType.startsWith("video/") ||
            displayName.endsWith(".mp4", ignoreCase = true) ||
            displayName.endsWith(".mov", ignoreCase = true) ||
            displayName.endsWith(".mkv", ignoreCase = true) ||
            displayName.endsWith(".webm", ignoreCase = true)

        if (isVideo) {
            loadVideoAsset(uri, displayName)
        } else {
            loadImageAsset(uri, displayName)
        }
    }

    private fun loadVideoAsset(uri: Uri, displayName: String): MediaAsset {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val (w, h) = if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight
            MediaAsset(
                uri = uri,
                type = MediaType.VIDEO,
                width = w.coerceAtLeast(1),
                height = h.coerceAtLeast(1),
                durationMs = durationMs.coerceAtLeast(0L),
                displayName = displayName
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadImageAsset(uri: Uri, displayName: String): MediaAsset {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val orientation = readExifOrientation(uri)
        val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

        val rawW = bounds.outWidth.coerceAtLeast(1)
        val rawH = bounds.outHeight.coerceAtLeast(1)
        val (w, h) = if (rotated) rawH to rawW else rawW to rawH

        return MediaAsset(
            uri = uri,
            type = MediaType.IMAGE,
            width = w,
            height = h,
            durationMs = 0L,
            displayName = displayName
        )
    }

    suspend fun decodeOrientedBitmap(uri: Uri, maxDimension: Int = 2560): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sampleSize = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return@withContext null

        val orientation = readExifOrientation(uri)
        applyExifOrientation(rawBitmap, orientation)
    }

    suspend fun extractFilmstrip(uri: Uri, durationMs: Long, count: Int = 10): List<Bitmap> =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0L || count <= 0) return@withContext emptyList()
            val retriever = MediaMetadataRetriever()
            val frames = mutableListOf<Bitmap>()
            try {
                retriever.setDataSource(context, uri)
                val stepUs = (durationMs * 1000L) / count
                for (i in 0 until count) {
                    val timeUs = (i * stepUs + stepUs / 2L).coerceIn(0L, durationMs * 1000L)
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val targetHeight = 96
                        val scale = targetHeight.toFloat() / frame.height.coerceAtLeast(1)
                        val targetWidth = (frame.width * scale).roundToInt().coerceAtLeast(48)
                        val scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
                        if (scaled !== frame) frame.recycle()
                        frames.add(scaled)
                    }
                }
            } catch (_: Exception) {
                // Best-effort thumbnails; timeline still works cleanly without them.
            } finally {
                runCatching { retriever.release() }
            }
            frames
        }

    private fun readExifOrientation(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) {
            bitmap.recycle()
        }
        return transformed
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                } ?: "media"
        } catch (_: Exception) {
            "media"
        }
    }
}
