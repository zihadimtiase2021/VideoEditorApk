package com.example.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class MediaType {
    IMAGE,
    VIDEO
}

enum class EditorTab {
    FACE_MASK,
    WATERMARK,
    TRIM_CUT
}

enum class MaskMode(val label: String) {
    GAUSSIAN_BLUR("Blur"),
    PIXELATE("Mosaic"),
    SOLID_COLOR("Solid"),
    EMOJI("Emoji"),
    STICKER("Sticker")
}

enum class ScanAccuracy(val label: String, val fps: Int, val description: String) {
    FAST("Fast", 2, "2 fps • Quick scan"),
    BALANCED("Balanced", 5, "5 fps • Smooth tracking"),
    HIGH("High", 10, "10 fps • Fast motion")
}

enum class WatermarkType(val label: String) {
    NONE("Off"),
    TEXT("Text"),
    IMAGE("Logo")
}

enum class WatermarkPositionPreset(
    val label: String,
    val normalizedX: Float,
    val normalizedY: Float
) {
    TOP_LEFT("Top-Left", 0.16f, 0.10f),
    TOP_RIGHT("Top-Right", 0.84f, 0.10f),
    CENTER("Center", 0.50f, 0.50f),
    BOTTOM_LEFT("Bottom-Left", 0.16f, 0.90f),
    BOTTOM_RIGHT("Bottom-Right", 0.84f, 0.90f),
    CUSTOM("Custom", 0.50f, 0.50f)
}

enum class VideoExportQuality(val label: String, val subtitle: String, val targetHeight: Int?) {
    ORIGINAL("Original", "Preserve source resolution", null),
    HD_1080P("1080p HD", "Crisp full HD MP4", 1080),
    COMPACT_720P("720p Compact", "Faster export & smaller file", 720)
}

enum class ImageExportFormat(
    val label: String,
    val subtitle: String,
    val mimeType: String,
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
    val quality: Int
) {
    JPG_HIGH("JPG High", "Compact high-quality photo", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, 95),
    PNG_LOSSLESS("PNG Lossless", "Zero compression loss", "image/png", "png", Bitmap.CompressFormat.PNG, 100)
}

data class MediaAsset(
    val uri: Uri,
    val type: MediaType,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L,
    val displayName: String = "media"
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

/**
 * Resolution-independent normalized rectangle in [0f..1f] coordinates.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun clamped(): NormalizedRect {
        val l = left.coerceIn(0f, 0.96f)
        val t = top.coerceIn(0f, 0.96f)
        val r = right.coerceIn(l + 0.04f, 1f)
        val b = bottom.coerceIn(t + 0.04f, 1f)
        return NormalizedRect(l, t, r, b)
    }

    fun translated(dx: Float, dy: Float): NormalizedRect {
        val w = width.coerceAtMost(1f)
        val h = height.coerceAtMost(1f)
        val newLeft = (left + dx).coerceIn(0f, 1f - w)
        val newTop = (top + dy).coerceIn(0f, 1f - h)
        return NormalizedRect(newLeft, newTop, newLeft + w, newTop + h)
    }

    fun resized(dw: Float, dh: Float): NormalizedRect {
        val newRight = (right + dw).coerceIn(left + 0.06f, 1f)
        val newBottom = (bottom + dh).coerceIn(top + 0.06f, 1f)
        return NormalizedRect(left, top, newRight, newBottom)
    }

    fun expanded(factor: Float): NormalizedRect {
        val cx = centerX
        val cy = centerY
        val hw = (width * factor) * 0.5f
        val hh = (height * factor) * 0.5f
        return NormalizedRect(
            left = (cx - hw).coerceIn(0f, 1f),
            top = (cy - hh).coerceIn(0f, 1f),
            right = (cx + hw).coerceIn(0f, 1f),
            bottom = (cy + hh).coerceIn(0f, 1f)
        )
    }

    companion object {
        fun lerp(a: NormalizedRect, b: NormalizedRect, t: Float): NormalizedRect {
            val f = t.coerceIn(0f, 1f)
            return NormalizedRect(
                left = a.left + (b.left - a.left) * f,
                top = a.top + (b.top - a.top) * f,
                right = a.right + (b.right - a.right) * f,
                bottom = a.bottom + (b.bottom - a.bottom) * f
            )
        }
    }
}

data class FaceKeyframe(
    val timestampMs: Long,
    val rect: NormalizedRect
)

data class FaceTrack(
    val id: Int,
    val label: String,
    val keyframes: List<FaceKeyframe>,
    val isSelected: Boolean = true,
    val isStaticImage: Boolean = false
) {
    /**
     * Returns the interpolated normalized face rect at [timestampMs], or null if this face
     * is not present near [timestampMs] (beyond [maxGapMs] from nearest detected frame).
     */
    fun rectAt(timestampMs: Long, maxGapMs: Long = 850L): NormalizedRect? {
        if (keyframes.isEmpty()) return null
        if (isStaticImage || keyframes.size == 1) {
            val only = keyframes.first()
            return if (isStaticImage || kotlin.math.abs(timestampMs - only.timestampMs) <= maxGapMs) {
                only.rect
            } else {
                null
            }
        }

        val first = keyframes.first()
        if (timestampMs <= first.timestampMs) {
            return if (first.timestampMs - timestampMs <= maxGapMs) first.rect else null
        }
        val last = keyframes.last()
        if (timestampMs >= last.timestampMs) {
            return if (timestampMs - last.timestampMs <= maxGapMs) last.rect else null
        }

        // Binary search for surrounding keyframes
        var lo = 0
        var hi = keyframes.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midTs = keyframes[mid].timestampMs
            when {
                midTs == timestampMs -> return keyframes[mid].rect
                midTs < timestampMs -> lo = mid + 1
                else -> hi = mid - 1
            }
        }

        val prev = keyframes[hi.coerceIn(0, keyframes.lastIndex)]
        val next = keyframes[lo.coerceIn(0, keyframes.lastIndex)]
        val gap = next.timestampMs - prev.timestampMs
        if (gap > maxGapMs * 2) {
            return when {
                timestampMs - prev.timestampMs <= maxGapMs -> prev.rect
                next.timestampMs - timestampMs <= maxGapMs -> next.rect
                else -> null
            }
        }
        val t = if (gap > 0L) (timestampMs - prev.timestampMs).toFloat() / gap.toFloat() else 0f
        return NormalizedRect.lerp(prev.rect, next.rect, t)
    }
}

data class ManualMaskBox(
    val id: String = UUID.randomUUID().toString(),
    val rect: NormalizedRect = NormalizedRect(0.32f, 0.30f, 0.68f, 0.56f)
)

data class FaceMaskConfig(
    val mode: MaskMode = MaskMode.GAUSSIAN_BLUR,
    val blurIntensity: Float = 0.72f,       // 0.15f .. 1.0f
    val pixelBlockSize: Float = 0.55f,      // 0.1f .. 1.0f
    val solidColorArgb: Int = 0xFF090B10.toInt(),
    val selectedEmoji: String = "\uD83D\uDE0E", // 😎
    val stickerUri: Uri? = null,
    val stickerScale: Float = 1.2f,         // 0.7f .. 2.0f
    val applyToAllFaces: Boolean = true
)

data class WatermarkConfig(
    val type: WatermarkType = WatermarkType.NONE,
    val text: String = "MaskFrame",
    val fontSizeSp: Float = 26f,            // 12f .. 64f
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val logoUri: Uri? = null,
    val logoScale: Float = 0.22f,           // 0.08f .. 0.48f (fraction of media width)
    val opacity: Float = 0.85f,             // 0.1f .. 1.0f
    val preset: WatermarkPositionPreset = WatermarkPositionPreset.BOTTOM_RIGHT,
    val normalizedX: Float = WatermarkPositionPreset.BOTTOM_RIGHT.normalizedX,
    val normalizedY: Float = WatermarkPositionPreset.BOTTOM_RIGHT.normalizedY
)

data class CutSegment(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class TrimCutConfig(
    val totalDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val cutSegments: List<CutSegment> = emptyList()
) {
    /**
     * Returns sorted, non-overlapping kept timestamp intervals inside [trimStartMs, trimEndMs]
     * with all interior [cutSegments] excised.
     */
    fun keptRanges(): List<LongRange> {
        if (totalDurationMs <= 0L) return emptyList()
        val start = trimStartMs.coerceIn(0L, totalDurationMs)
        val end = trimEndMs.coerceIn(start, totalDurationMs)
        if (end <= start) return emptyList()

        val sortedCuts = cutSegments
            .mapNotNull { seg ->
                val s = max(seg.startMs, start)
                val e = min(seg.endMs, end)
                if (e - s >= 100L) s to e else null
            }
            .sortedBy { it.first }

        // Merge overlapping cuts
        val mergedCuts = mutableListOf<Pair<Long, Long>>()
        for (cut in sortedCuts) {
            if (mergedCuts.isEmpty()) {
                mergedCuts.add(cut)
            } else {
                val last = mergedCuts.last()
                if (cut.first <= last.second) {
                    mergedCuts[mergedCuts.lastIndex] = last.first to max(last.second, cut.second)
                } else {
                    mergedCuts.add(cut)
                }
            }
        }

        val kept = mutableListOf<LongRange>()
        var cursor = start
        for ((cutStart, cutEnd) in mergedCuts) {
            if (cutStart > cursor + 50L) {
                kept.add(cursor..cutStart)
            }
            cursor = max(cursor, cutEnd)
        }
        if (end > cursor + 50L) {
            kept.add(cursor..end)
        }
        return if (kept.isEmpty()) listOf(start..end) else kept
    }

    val outputDurationMs: Long
        get() = keptRanges().sumOf { (it.last - it.first).coerceAtLeast(0L) }

    /**
     * Maps an output timeline timestamp (in milliseconds, after trimming & cutting) back to the
     * original source video timestamp so face tracks stay locked to the right frames during export.
     */
    fun outputTimeMsToSourceTimeMs(outputMs: Long): Long {
        val ranges = keptRanges()
        if (ranges.isEmpty()) return outputMs
        var remaining = outputMs.coerceAtLeast(0L)
        for (range in ranges) {
            val len = (range.last - range.first).coerceAtLeast(0L)
            if (remaining <= len) {
                return range.first + remaining
            }
            remaining -= len
        }
        return ranges.last().last
    }
}

sealed interface FaceScanState {
    data object Idle : FaceScanState
    data class Scanning(val progress: Float, val framesScanned: Int, val totalFrames: Int) : FaceScanState
    data class Completed(val faceCount: Int) : FaceScanState
    data class Error(val message: String) : FaceScanState
}

sealed interface ExportState {
    data object Idle : ExportState
    data object Configuring : ExportState
    data class Processing(val progress: Float, val stage: String) : ExportState
    data class Success(
        val outputUri: Uri,
        val displayName: String,
        val mimeType: String,
        val folderPath: String
    ) : ExportState
    data class Error(val message: String) : ExportState
}

data class EditSnapshot(
    val faceTracks: List<FaceTrack>,
    val manualBoxes: List<ManualMaskBox>,
    val maskConfig: FaceMaskConfig,
    val watermarkConfig: WatermarkConfig,
    val trimCutConfig: TrimCutConfig
)

data class EditorUiState(
    val mediaAsset: MediaAsset? = null,
    val activeTab: EditorTab = EditorTab.FACE_MASK,
    val scanAccuracy: ScanAccuracy = ScanAccuracy.BALANCED,
    val faceScanState: FaceScanState = FaceScanState.Idle,
    val faceTracks: List<FaceTrack> = emptyList(),
    val manualBoxes: List<ManualMaskBox> = emptyList(),
    val selectedManualBoxId: String? = null,
    val maskConfig: FaceMaskConfig = FaceMaskConfig(),
    val stickerBitmap: Bitmap? = null,
    val watermarkConfig: WatermarkConfig = WatermarkConfig(),
    val watermarkLogoBitmap: Bitmap? = null,
    val trimCutConfig: TrimCutConfig = TrimCutConfig(),
    val filmstripThumbnails: List<Bitmap> = emptyList(),
    val isLoadingFilmstrip: Boolean = false,
    val currentPlaybackMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val isComparingOriginal: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val videoExportQuality: VideoExportQuality = VideoExportQuality.ORIGINAL,
    val imageExportFormat: ImageExportFormat = ImageExportFormat.JPG_HIGH,
    val exportState: ExportState = ExportState.Idle,
    val snackbarMessage: String? = null
) {
    /**
     * Active normalized rectangles that should be masked at [timestampMs] for rendering or preview.
     */
    fun activeMaskRectsAt(timestampMs: Long): List<NormalizedRect> {
        val rects = mutableListOf<NormalizedRect>()
        for (track in faceTracks) {
            if (maskConfig.applyToAllFaces || track.isSelected) {
                track.rectAt(timestampMs)?.let { rects.add(it) }
            }
        }
        for (manual in manualBoxes) {
            rects.add(manual.rect)
        }
        return rects
    }
}

object StudioPalettes {
    val maskSolidColors: List<Color> = listOf(
        Color(0xFF090B10),
        Color(0xFF1F2937),
        Color(0xFFFFFFFF),
        Color(0xFF00E5FF),
        Color(0xFFFF4D6D),
        Color(0xFFFFB74D),
        Color(0xFF22E58B)
    )

    val watermarkTextColors: List<Color> = listOf(
        Color(0xFFFFFFFF),
        Color(0xFF00E5FF),
        Color(0xFF090B10),
        Color(0xFFFFD166),
        Color(0xFFFF4D6D),
        Color(0xFF22E58B)
    )

    val privacyEmojis: List<String> = listOf(
        "\uD83D\uDE0E", // 😎
        "\uD83E\uDEE5", // 🫥
        "\uD83C\uDFAD", // 🎭
        "\uD83E\uDD16", // 🤖
        "\uD83D\uDC7D", // 👽
        "\uD83E\uDD8A", // 🦊
        "\uD83D\uDE36\u200D\uD83C\uDF2B\uFE0F", // 😶‍🌫️
        "\uD83D\uDD76\uFE0F", // 🕶️
        "\uD83D\uDD12", // 🔒
        "\u2B50",       // ⭐
        "\uD83D\uDC7B", // 👻
        "\uD83D\uDC31"  // 🐱
    )
}

fun formatTimecode(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (safeMs % 1000L) / 100L
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}
