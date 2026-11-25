package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
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
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class FaceDetectionEngine(
    private val context: Context,
    private val metadataLoader: MediaMetadataLoader
) {

    /**
     * Runs high-accuracy on-device ML Kit Face Detection with mandatory facial landmark
     * verification (eyes, nose, and mouth) on a static image so non-face objects are rejected.
     */
    suspend fun detectFacesInImage(uri: Uri): List<FaceTrack> = withContext(Dispatchers.Default) {
        val bitmap = metadataLoader.decodeOrientedBitmap(uri, maxDimension = 1600)
            ?: return@withContext emptyList()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.07f)
            .build()
        val detector = FaceDetection.getClient(options)
        try {
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val verifiedFaces = faces
                .filter { isVerifiedHumanFace(it, w, h) }
                .sortedBy { it.boundingBox.left }

            verifiedFaces.mapIndexed { index, face ->
                val norm = faceToNormalizedRect(face, w, h, expandFactor = 1.06f)
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
     * Extracts video frames at [accuracy].fps and runs ML Kit Accurate Face Detection with
     * Eyes + Nose + Mouth anatomical landmark verification and temporal stability filtering.
     */
    suspend fun scanVideoFaces(
        uri: Uri,
        durationMs: Long,
        accuracy: ScanAccuracy,
        onPartialTracks: (List<FaceTrack>) -> Unit = {},
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

        // Always use ACCURATE mode + LANDMARK_MODE_ALL so every face is verified by eyes, nose & mouth
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.075f)
            .enableTracking()
            .build()

        val detector = FaceDetection.getClient(options)
        val retriever = MediaMetadataRetriever()

        val trackMap = linkedMapOf<Int, MutableList<FaceKeyframe>>()
        var nextSyntheticId = 1000
        var lastPublishedTrackCount = 0

        try {
            retriever.setDataSource(context, uri)
            val total = timestamps.size

            for ((index, tsMs) in timestamps.withIndex()) {
                ensureActive()

                val frame = extractScaledFrame(retriever, tsMs * 1000L, maxDimension = 960)
                if (frame != null) {
                    try {
                        val faces = detector.process(InputImage.fromBitmap(frame, 0)).awaitResult()
                        val w = frame.width.toFloat().coerceAtLeast(1f)
                        val h = frame.height.toFloat().coerceAtLeast(1f)

                        for (face in faces) {
                            if (!isVerifiedHumanFace(face, w, h)) continue

                            val norm = faceToNormalizedRect(face, w, h, expandFactor = 1.08f)
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

                // Publish progressive tracks cleanly once confirmed
                val shouldPublish = trackMap.isNotEmpty() && (
                    index == 0 ||
                        trackMap.size != lastPublishedTrackCount ||
                        (index + 1) % 4 == 0
                    )
                if (shouldPublish) {
                    val snapshot = buildTracksSnapshot(
                        trackMap = trackMap,
                        minKeyframes = if (index >= 3) 2 else 1,
                        intervalMs = intervalMs
                    )
                    if (snapshot.isNotEmpty() || lastPublishedTrackCount > 0) {
                        lastPublishedTrackCount = trackMap.size
                        onPartialTracks(snapshot)
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

        // For multi-frame videos, require >= 2 detections per track to eliminate isolated 1-frame glitches
        val minFramesRequired = if (timestamps.size >= 4) 2 else 1
        buildTracksSnapshot(trackMap, minKeyframes = minFramesRequired, intervalMs = intervalMs)
    }

    /**
     * Strict anatomical validator that confirms a candidate ML Kit [Face] actually contains
     * human facial organs (Eyes, Nose, Mouth) in valid spatial proportion and vertical order.
     * Eliminates false positives on backgrounds, textures, hands, clothing, and pareidolia.
     */
    private fun isVerifiedHumanFace(face: Face, frameW: Float, frameH: Float): Boolean {
        val box = face.boundingBox
        val boxW = box.width().toFloat()
        val boxH = box.height().toFloat()
        val minFrameDim = min(frameW, frameH)

        // 1. Minimum size and reasonable human-face aspect ratio
        if (boxW < 24f || boxH < 24f) return false
        if (min(boxW, boxH) < minFrameDim * 0.035f) return false
        val aspect = boxW / boxH.coerceAtLeast(1f)
        if (aspect < 0.48f || aspect > 1.85f) return false

        // 2. Ensure at least 65% of the bounding box lies inside the visible frame
        val clipped = Rect(
            box.left.coerceIn(0, frameW.toInt()),
            box.top.coerceIn(0, frameH.toInt()),
            box.right.coerceIn(0, frameW.toInt()),
            box.bottom.coerceIn(0, frameH.toInt())
        )
        val boxArea = (boxW * boxH).coerceAtLeast(1f)
        val clippedArea = (clipped.width().coerceAtLeast(0) * clipped.height().coerceAtLeast(0)).toFloat()
        if (clippedArea / boxArea < 0.65f) return false

        // 3. Extract core facial organ landmarks (Eyes, Nose, Mouth) inside padded face bounds
        val padX = boxW * 0.16f
        val padY = boxH * 0.16f
        fun verifiedPoint(type: Int): PointF? {
            val pt = face.getLandmark(type)?.position ?: return null
            val inBounds = pt.x >= box.left - padX && pt.x <= box.right + padX &&
                pt.y >= box.top - padY && pt.y <= box.bottom + padY
            return if (inBounds) pt else null
        }

        val leftEye = verifiedPoint(FaceLandmark.LEFT_EYE)
        val rightEye = verifiedPoint(FaceLandmark.RIGHT_EYE)
        val nose = verifiedPoint(FaceLandmark.NOSE_BASE)
        val mouthPts = listOfNotNull(
            verifiedPoint(FaceLandmark.MOUTH_BOTTOM),
            verifiedPoint(FaceLandmark.MOUTH_LEFT),
            verifiedPoint(FaceLandmark.MOUTH_RIGHT)
        )
        val mouthCenter = if (mouthPts.isNotEmpty()) {
            PointF(
                mouthPts.sumOf { it.x.toDouble() }.toFloat() / mouthPts.size,
                mouthPts.sumOf { it.y.toDouble() }.toFloat() / mouthPts.size
            )
        } else {
            null
        }

        val eyeCount = (if (leftEye != null) 1 else 0) + (if (rightEye != null) 1 else 0)
        val hasNose = nose != null
        val hasMouth = mouthCenter != null

        // Mandatory facial organs rule:
        // - Frontal / 3/4 face: Both eyes + (Nose or Mouth)
        // - Side profile face: 1 Eye + BOTH Nose and Mouth
        // - Anything without eyes or without nose/mouth is rejected as a false positive.
        val organsVerified = (eyeCount == 2 && (hasNose || hasMouth)) ||
            (eyeCount == 1 && hasNose && hasMouth)
        if (!organsVerified) return false

        // 4. Tilt-invariant anatomical geometry check (using head roll angle Z)
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val rollRad = (-face.headEulerAngleZ * PI / 180.0)
        val cosR = cos(rollRad).toFloat()
        val sinR = sin(rollRad).toFloat()

        fun toUpright(pt: PointF): PointF {
            val dx = pt.x - cx
            val dy = pt.y - cy
            return PointF(
                dx * cosR - dy * sinR,
                dx * sinR + dy * cosR
            )
        }

        val uprightLeftEye = leftEye?.let(::toUpright)
        val uprightRightEye = rightEye?.let(::toUpright)
        val uprightNose = nose?.let(::toUpright)
        val uprightMouth = mouthCenter?.let(::toUpright)

        // Validate inter-ocular spacing and eye level alignment when both eyes are visible
        if (leftEye != null && rightEye != null && uprightLeftEye != null && uprightRightEye != null) {
            val eyeDist = hypot((rightEye.x - leftEye.x).toDouble(), (rightEye.y - leftEye.y).toDouble()).toFloat()
            if (eyeDist < boxW * 0.16f || eyeDist > boxW * 0.80f) return false
            if (abs(uprightLeftEye.y - uprightRightEye.y) > boxH * 0.28f) return false
        }

        val eyeCenterY = when {
            uprightLeftEye != null && uprightRightEye != null -> (uprightLeftEye.y + uprightRightEye.y) * 0.5f
            uprightLeftEye != null -> uprightLeftEye.y
            uprightRightEye != null -> uprightRightEye.y
            else -> return false
        }

        // Eyes must sit above the nose
        if (uprightNose != null) {
            val eyeToNose = uprightNose.y - eyeCenterY
            if (eyeToNose < boxH * 0.02f || eyeToNose > boxH * 0.62f) return false
        }

        // Eyes must sit above the mouth
        if (uprightMouth != null) {
            val eyeToMouth = uprightMouth.y - eyeCenterY
            if (eyeToMouth < boxH * 0.10f || eyeToMouth > boxH * 0.85f) return false
        }

        // Nose must sit above (or level with) the mouth
        if (uprightNose != null && uprightMouth != null) {
            if (uprightMouth.y < uprightNose.y - boxH * 0.04f) return false
        }

        return true
    }

    private fun faceToNormalizedRect(
        face: Face,
        frameW: Float,
        frameH: Float,
        expandFactor: Float
    ): NormalizedRect {
        val box = face.boundingBox
        val base = NormalizedRect(
            left = (box.left / frameW).coerceIn(0f, 1f),
            top = (box.top / frameH).coerceIn(0f, 1f),
            right = (box.right / frameW).coerceIn(0f, 1f),
            bottom = (box.bottom / frameH).coerceIn(0f, 1f)
        )
        return base.expanded(expandFactor).clamped()
    }

    private fun buildTracksSnapshot(
        trackMap: Map<Int, MutableList<FaceKeyframe>>,
        minKeyframes: Int,
        intervalMs: Long
    ): List<FaceTrack> {
        val eligible = trackMap.entries
            .filter { it.value.size >= minKeyframes }
            .sortedByDescending { it.value.size }

        return eligible.mapIndexed { idx, (_, rawFrames) ->
            val sorted = rawFrames.sortedBy { it.timestampMs }
            val smoothed = smoothKeyframes(sorted, maxNeighborGapMs = (intervalMs * 2L).coerceAtLeast(450L))
            FaceTrack(
                id = idx + 1,
                label = "Face ${idx + 1}",
                keyframes = smoothed,
                isSelected = true,
                isStaticImage = false
            )
        }
    }

    private fun smoothKeyframes(
        frames: List<FaceKeyframe>,
        maxNeighborGapMs: Long
    ): List<FaceKeyframe> {
        if (frames.size < 3) return frames
        return frames.mapIndexed { i, cur ->
            if (i == 0 || i == frames.lastIndex) {
                cur
            } else {
                val prev = frames[i - 1]
                val next = frames[i + 1]
                val closePrev = (cur.timestampMs - prev.timestampMs) <= maxNeighborGapMs
                val closeNext = (next.timestampMs - cur.timestampMs) <= maxNeighborGapMs
                if (closePrev && closeNext) {
                    val r = NormalizedRect(
                        left = prev.rect.left * 0.2f + cur.rect.left * 0.6f + next.rect.left * 0.2f,
                        top = prev.rect.top * 0.2f + cur.rect.top * 0.6f + next.rect.top * 0.2f,
                        right = prev.rect.right * 0.2f + cur.rect.right * 0.6f + next.rect.right * 0.2f,
                        bottom = prev.rect.bottom * 0.2f + cur.rect.bottom * 0.6f + next.rect.bottom * 0.2f
                    ).clamped()
                    cur.copy(rect = r)
                } else {
                    cur
                }
            }
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

        // Nearest-centroid + size-similarity association for continuity when ML Kit resets a tracking ID
        var bestId: Int? = null
        var bestDistance = 0.18f
        val maxWindowMs = (intervalMs * 3L).coerceAtLeast(600L)

        for ((id, frames) in existingTracks) {
            val last = frames.lastOrNull() ?: continue
            if (tsMs - last.timestampMs in 1..maxWindowMs) {
                val dist = hypot(
                    (norm.centerX - last.rect.centerX).toDouble(),
                    (norm.centerY - last.rect.centerY).toDouble()
                ).toFloat()
                val sizeRatio = max(norm.width, 0.01f) / max(last.rect.width, 0.01f)
                if (dist < bestDistance && sizeRatio in 0.55f..1.85f) {
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
