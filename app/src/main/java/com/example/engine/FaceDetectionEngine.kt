package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.example.model.FaceKeyframe
import com.example.model.FaceTrack
import com.example.model.NormalizedRect
import com.example.model.ScanAccuracy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot

class FaceDetectionEngine(
    private val context: Context,
    private val metadataLoader: MediaMetadataLoader
) {

    /**
     * Runs on-device ML Kit Face Detection on a static image and returns normalized FaceTracks.
     */
    suspend fun detectFacesInImage(uri: Uri): List<FaceTrack> = withContext(Dispatchers.Default) {
        val bitmap = metadataLoader.decodeOrientedBitmap(uri, maxDimension = 1600)
            ?: return@withContext emptyList()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build()
        val detector = FaceDetection.getClient(options)
        try {
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            faces.sortedBy { it.boundingBox.left }.mapIndexed { index, face ->
                val box = face.boundingBox
                val norm = NormalizedRect(
                    left = (box.left / w).coerceIn(0f, 1f),
                    top = (box.top / h).coerceIn(0f, 1f),
                    right = (box.right / w).coerceIn(0f, 1f),
                    bottom = (box.bottom / h).coerceIn(0f, 1f)
                ).expanded(1.12f).clamped()
                FaceTrack(
                    id = index + 1,
                    label = "Face ${index + 1}",
                    keyframes = listOf(FaceKeyframe(timestampMs = 0L, rect = norm)),
                    isSelected = true,
                    isStaticImage = true
                )
            }
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }

    /**
     * Extracts video frames at [accuracy].fps and runs ML Kit Face Detection with tracking enabled.
     * Emits real-time progress via [onProgress].
     */
    suspend fun scanVideoFaces(
        uri: Uri,
        durationMs: Long,
        accuracy: ScanAccuracy,
        onProgress: (progress: Float, scanned: Int, total: Int) -> Unit
    ): List<FaceTrack> = withContext(Dispatchers.Default) {
        if (durationMs <= 0L) return@withContext emptyList()

        val intervalMs = (1000L / accuracy.fps.coerceAtLeast(1)).coerceAtLeast(80L)
        val timestamps = mutableListOf<Long>()
        var t = 0L
        while (t <= durationMs) {
            timestamps.add(t)
            t += intervalMs
        }
        if (timestamps.lastOrNull() != durationMs) {
            timestamps.add(durationMs)
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(
                if (accuracy == ScanAccuracy.HIGH) {
                    FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                } else {
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                }
            )
            .setMinFaceSize(0.06f)
            .enableTracking()
            .build()

        val detector = FaceDetection.getClient(options)
        val retriever = MediaMetadataRetriever()

        // Track ID -> mutable keyframes list
        val trackMap = linkedMapOf<Int, MutableList<FaceKeyframe>>()
        var nextSyntheticId = 1000

        try {
            retriever.setDataSource(context, uri)
            val total = timestamps.size

            for ((index, tsMs) in timestamps.withIndex()) {
                ensureActive()

                val frame = extractScaledFrame(retriever, tsMs * 1000L, maxDimension = 720)
                if (frame != null) {
                    try {
                        val faces = detector.process(InputImage.fromBitmap(frame, 0)).awaitResult()
                        val w = frame.width.toFloat().coerceAtLeast(1f)
                        val h = frame.height.toFloat().coerceAtLeast(1f)

                        for (face in faces) {
                            val box = face.boundingBox
                            val norm = NormalizedRect(
                                left = (box.left / w).coerceIn(0f, 1f),
                                top = (box.top / h).coerceIn(0f, 1f),
                                right = (box.right / w).coerceIn(0f, 1f),
                                bottom = (box.bottom / h).coerceIn(0f, 1f)
                            ).expanded(1.16f).clamped()

                            val trackId = resolveTrackId(
                                face = face,
                                norm = norm,
                                tsMs = tsMs,
                                intervalMs = intervalMs,
                                existingTracks = trackMap,
                                nextIdProvider = { nextSyntheticId++ }
                            )
                            trackMap.getOrPut(trackId) { mutableListOf() }
                                .add(FaceKeyframe(timestampMs = tsMs, rect = norm))
                        }
                    } finally {
                        frame.recycle()
                    }
                }

                onProgress(
                    (index + 1).toFloat() / total.toFloat(),
                    index + 1,
                    total
                )
            }
        } finally {
            detector.close()
            runCatching { retriever.release() }
        }

        // Convert to clean sequential Face 1, Face 2... sorted by appearance frequency
        trackMap.entries
            .sortedByDescending { it.value.size }
            .mapIndexed { idx, (_, frames) ->
                FaceTrack(
                    id = idx + 1,
                    label = "Face ${idx + 1}",
                    keyframes = frames.sortedBy { it.timestampMs },
                    isSelected = true,
                    isStaticImage = false
                )
            }
    }

    private fun resolveTrackId(
        face: Face,
        norm: NormalizedRect,
        tsMs: Long,
        intervalMs: Long,
        existingTracks: Map<Int, List<FaceKeyframe>>,
        nextIdProvider: () -> Int
    ): Int {
        val mlKitId = face.trackingId
        if (mlKitId != null && existingTracks.containsKey(mlKitId)) {
            return mlKitId
        }

        // Fallback nearest-centroid association for continuity when ML Kit resets a tracking ID
        var bestId: Int? = null
        var bestDistance = 0.20f
        val maxWindowMs = (intervalMs * 3L).coerceAtLeast(600L)

        for ((id, frames) in existingTracks) {
            val last = frames.lastOrNull() ?: continue
            if (tsMs - last.timestampMs in 1..maxWindowMs) {
                val dist = hypot(
                    (norm.centerX - last.rect.centerX).toDouble(),
                    (norm.centerY - last.rect.centerY).toDouble()
                ).toFloat()
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestId = id
                }
            }
        }

        return bestId ?: mlKitId ?: nextIdProvider()
    }

    private fun extractScaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        maxDimension: Int
    ): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    maxDimension,
                    maxDimension
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        } catch (_: Exception) {
            runCatching {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}
