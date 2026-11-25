package com.example.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class MediaType {
    IMAGE,
    VIDEO
}

enum class EditorTab {
    FACE_MASK,
    ADJUST,
    TRANSFORM,
    SPEED,
    AUDIO,
    TEXT,
    WATERMARK,
    TRIM_CUT
}

enum class MaskMode(val label: String, val shaderId: Int) {
    GAUSSIAN_BLUR("Blur", 1),
    PIXELATE("Mosaic", 2),
    FROSTED_GLASS("Frosted", 3),
    GLITCH("Glitch", 4),
    INVERT("Invert", 5),
    SPOTLIGHT("Spotlight", 6),
    SOLID_COLOR("Solid", 7),
    EMOJI("Emoji", 0),
    STICKER("Sticker", 0);

    val isShaderEffect: Boolean
        get() = shaderId > 0
}

enum class MaskShape(val label: String, val shaderId: Int) {
    ROUNDED("Rounded", 0),
    OVAL("Oval", 1),
    CIRCLE("Circle", 2),
    HEART("Heart", 3),
    STAR("Star", 4)
}

enum class ScanAccuracy(val label: String, val fps: Int, val description: String) {
    FAST("Fast", 2, "2 fps • Quick"),
    BALANCED("Balanced", 5, "5 fps • Smooth"),
    HIGH("High", 10, "10 fps • Precise")
}

enum class ColorFilterPreset(
    val label: String,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val vignette: Float = 0f
) {
    ORIGINAL("Original", 0f, 1.0f, 1.0f, 0f, 0f),
    NOIR("Noir", -0.02f, 1.24f, 0.0f, 0f, 0.25f),
    WARM_CINEMA("Cinema", -0.01f, 1.12f, 1.08f, 0.22f, 0.28f),
    COOL_SLATE("Slate", 0.0f, 1.08f, 0.88f, -0.22f, 0.14f),
    VIVID("Vivid", 0.03f, 1.15f, 1.38f, 0.06f, 0.08f),
    MATTE("Matte", 0.05f, 0.88f, 0.86f, 0.08f, 0.18f),
    VINTAGE("Vintage", 0.02f, 0.96f, 0.72f, 0.30f, 0.36f)
}

data class ColorGradeConfig(
    val preset: ColorFilterPreset = ColorFilterPreset.ORIGINAL,
    val brightness: Float = 0f,      // -0.4f..0.4f
    val contrast: Float = 1.0f,      // 0.5f..1.6f
    val saturation: Float = 1.0f,    // 0.0f..2.0f
    val warmth: Float = 0f,          // -0.5f..0.5f
    val vignette: Float = 0f         // 0.0f..0.85f
) {
    fun isDefault(): Boolean {
        return abs(brightness) < 0.005f &&
            abs(contrast - 1f) < 0.005f &&
            abs(saturation - 1f) < 0.005f &&
            abs(warmth) < 0.005f &&
            vignette < 0.005f
    }

    /**
     * Builds a standard 4x5 Android/Compose ColorMatrix float array (20 floats) matching
     * both Compose preview and GPU/CPU exports.
     */
    fun toColorMatrix20(): FloatArray {
        val c = contrast.coerceIn(0.5f, 1.6f)
        val s = saturation.coerceIn(0f, 2.0f)
        val b = brightness.coerceIn(-0.4f, 0.4f)
        val w = warmth.coerceIn(-0.5f, 0.5f)

        val lumR = 0.2126f
        val lumG = 0.7152f
        val lumB = 0.0722f
        val sr = (1f - s) * lumR
        val sg = (1f - s) * lumG
        val sb = (1f - s) * lumB

        val rGain = (1f + w * 0.22f).coerceIn(0.7f, 1.3f)
        val gGain = (1f + w * 0.04f).coerceIn(0.85f, 1.15f)
        val bGain = (1f - w * 0.24f).coerceIn(0.7f, 1.3f)

        val offset = (0.5f * (1f - c) + b) * 255f

        return floatArrayOf(
            (sr + s) * c * rGain, sg * c * rGain, sb * c * rGain, 0f, offset,
            sr * c * gGain, (sg + s) * c * gGain, sb * c * gGain, 0f, offset,
            sr * c * bGain, sg * c * bGain, (sb + s) * c * bGain, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }
}

enum class AspectRatioPreset(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_1_1("1:1", 1f),
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_4_3("4:3", 4f / 3f)
}

data class TransformConfig(
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.ORIGINAL,
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val zoomScale: Float = 1.0f   // 1.0f..2.0f
) {
    fun isDefault(): Boolean {
        return aspectRatio == AspectRatioPreset.ORIGINAL &&
            (rotationDegrees % 360) == 0 &&
            !flipHorizontal &&
            !flipVertical &&
            abs(zoomScale - 1.0f) < 0.005f
    }

    fun effectiveAspectRatio(sourceWidth: Int, sourceHeight: Int): Float {
        aspectRatio.ratio?.let { return it }
        val rotated = (rotationDegrees % 180) != 0
        val w = if (rotated) sourceHeight else sourceWidth
        val h = if (rotated) sourceWidth else sourceHeight
        return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 16f / 9f
    }
}

data class SpeedConfig(
    val speedMultiplier: Float = 1.0f, // 0.25f..4.0f
    val preservePitch: Boolean = true
) {
    fun isDefault(): Boolean = abs(speedMultiplier - 1.0f) < 0.01f

    fun outputDurationMs(sourceDurationMs: Long): Long {
        if (sourceDurationMs <= 0L) return 0L
        return (sourceDurationMs / speedMultiplier.coerceIn(0.25f, 4.0f)).roundToLong().coerceAtLeast(100L)
    }
}

data class AudioConfig(
    val originalVolume: Float = 1.0f, // 0f..1f
    val isMuted: Boolean = false,
    val musicUri: Uri? = null,
    val musicDisplayName: String? = null,
    val musicVolume: Float = 0.65f,   // 0f..1f
    val loopMusic: Boolean = true
) {
    val effectiveOriginalVolume: Float
        get() = if (isMuted) 0f else originalVolume.coerceIn(0f, 1f)
}

enum class TextOverlayStyle(val label: String) {
    MINIMAL("Minimal"),
    AMBER_PILL("Amber Pill"),
    SUBTITLE("Subtitle"),
    OUTLINE("Outline"),
    GLASS("Glass")
}

data class TextOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "MaskFrame Studio",
    val style: TextOverlayStyle = TextOverlayStyle.AMBER_PILL,
    val fontSizeSp: Float = 26f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val opacity: Float = 1.0f,
    val normalizedX: Float = 0.50f,
    val normalizedY: Float = 0.78f,
    val startMs: Long = 0L,
    val endMs: Long = Long.MAX_VALUE
) {
    fun isVisibleAt(timestampMs: Long, isVideo: Boolean): Boolean {
        if (text.isBlank()) return false
        if (!isVideo) return true
        return timestampMs in startMs..endMs
    }
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
    ORIGINAL("Original", "Source resolution", null),
    HD_1080P("1080p HD", "Crisp full HD MP4", 1080),
    COMPACT_720P("720p Compact", "Faster & smaller file", 720)
}

enum class ImageExportFormat(
    val label: String,
    val subtitle: String,
    val mimeType: String,
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
    val quality: Int
) {
    JPG_HIGH("JPG High", "Compact high quality", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, 95),
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

/**
 * Optional per-face customization that overrides the global [FaceMaskConfig] for a single person.
 */
data class FaceStyleOverride(
    val mode: MaskMode? = null,
    val shape: MaskShape? = null,
    val sizeScale: Float? = null,
    val blurIntensity: Float? = null,
    val pixelBlockSize: Float? = null,
    val feather: Float? = null,
    val opacity: Float? = null,
    val solidColorArgb: Int? = null,
    val selectedEmoji: String? = null
) {
    fun hasAnyOverride(): Boolean =
        mode != null || shape != null || sizeScale != null || blurIntensity != null ||
            pixelBlockSize != null || feather != null || opacity != null ||
            solidColorArgb != null || selectedEmoji != null
}

data class FaceTrack(
    val id: Int,
    val label: String,
    val keyframes: List<FaceKeyframe>,
    val isSelected: Boolean = true,
    val isStaticImage: Boolean = false,
    val styleOverride: FaceStyleOverride? = null
) {
    fun rectAt(timestampMs: Long, maxGapMs: Long = 520L): NormalizedRect? {
        if (keyframes.isEmpty()) return null
        if (isStaticImage || keyframes.size == 1) {
            val only = keyframes.first()
            return if (isStaticImage || abs(timestampMs - only.timestampMs) <= maxGapMs) {
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
    val shape: MaskShape = MaskShape.OVAL,
    val sizeScale: Float = 1.0f,       // 0.75f..1.80f
    val feather: Float = 0.28f,        // 0.0f..1.0f
    val opacity: Float = 1.0f,         // 0.25f..1.0f
    val blurIntensity: Float = 0.72f,
    val pixelBlockSize: Float = 0.55f,
    val solidColorArgb: Int = 0xFF0E1014.toInt(),
    val selectedEmoji: String = "\uD83D\uDE0E", // 😎
    val stickerUri: Uri? = null,
    val stickerScale: Float = 1.2f,
    val applyToAllFaces: Boolean = true
)

/**
 * Fully resolved mask instance merging global [FaceMaskConfig] with an optional [FaceStyleOverride].
 */
data class ResolvedMaskInstance(
    val rect: NormalizedRect,
    val mode: MaskMode,
    val shape: MaskShape,
    val blurIntensity: Float,
    val pixelBlockSize: Float,
    val feather: Float,
    val opacity: Float,
    val solidColorArgb: Int,
    val selectedEmoji: String,
    val stickerScale: Float
)

data class WatermarkConfig(
    val type: WatermarkType = WatermarkType.NONE,
    val text: String = "MaskFrame",
    val fontSizeSp: Float = 26f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val logoUri: Uri? = null,
    val logoScale: Float = 0.22f,
    val opacity: Float = 0.85f,
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

    fun outputTimeMsToSourceTimeMs(outputMs: Long, speedMultiplier: Float = 1.0f): Long {
        val scaledOutputMs = (outputMs.coerceAtLeast(0L) * speedMultiplier.coerceIn(0.25f, 4.0f)).roundToLong()
        val ranges = keptRanges()
        if (ranges.isEmpty()) return scaledOutputMs
        var remaining = scaledOutputMs
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
    data class Scanning(
        val progress: Float,
        val framesScanned: Int,
        val totalFrames: Int,
        val isAutoImportScan: Boolean = false
    ) : FaceScanState
    data class Completed(val faceCount: Int) : FaceScanState
    data class Error(val message: String) : FaceScanState
}

sealed interface ExportState {
    data object Idle : ExportState
    data object Configuring : ExportState
    data class Processing(val progress: Float, val stage: String, val runningInBackground: Boolean = false) : ExportState
    data class Success(
        val outputUri: Uri,
        val displayName: String,
        val mimeType: String,
        val folderPath: String
    ) : ExportState
    data class Error(val message: String) : ExportState
}

enum class BatchItemStatus {
    QUEUED,
    DETECTING_FACES,
    RENDERING,
    SAVED,
    FAILED
}

data class BatchQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val mediaType: MediaType,
    val status: BatchItemStatus = BatchItemStatus.QUEUED,
    val detectedFaces: Int = 0,
    val outputUri: Uri? = null,
    val outputMimeType: String? = null,
    val errorMessage: String? = null
)

sealed interface BatchExportState {
    data object Idle : BatchExportState
    data class Configuring(
        val items: List<BatchQueueItem>,
        val includeWatermark: Boolean
    ) : BatchExportState
    data class Processing(
        val items: List<BatchQueueItem>,
        val currentIndex: Int,
        val itemProgress: Float,
        val overallProgress: Float,
        val stage: String,
        val runningInBackground: Boolean = false
    ) : BatchExportState
    data class Completed(
        val items: List<BatchQueueItem>,
        val savedUris: List<Uri>
    ) : BatchExportState
    data class Error(val message: String) : BatchExportState
}

data class EditSnapshot(
    val faceTracks: List<FaceTrack>,
    val manualBoxes: List<ManualMaskBox>,
    val maskConfig: FaceMaskConfig,
    val colorGradeConfig: ColorGradeConfig,
    val transformConfig: TransformConfig,
    val speedConfig: SpeedConfig,
    val audioConfig: AudioConfig,
    val textOverlays: List<TextOverlayItem>,
    val watermarkConfig: WatermarkConfig,
    val trimCutConfig: TrimCutConfig
)

data class EditorUiState(
    val mediaAsset: MediaAsset? = null,
    val activeTab: EditorTab = EditorTab.FACE_MASK,
    val autoDetectAndBlurOnImport: Boolean = true,
    val scanAccuracy: ScanAccuracy = ScanAccuracy.BALANCED,
    val faceScanState: FaceScanState = FaceScanState.Idle,
    val faceTracks: List<FaceTrack> = emptyList(),
    val selectedFaceTrackId: Int? = null,
    val manualBoxes: List<ManualMaskBox> = emptyList(),
    val selectedManualBoxId: String? = null,
    val maskConfig: FaceMaskConfig = FaceMaskConfig(),
    val stickerBitmap: Bitmap? = null,
    val colorGradeConfig: ColorGradeConfig = ColorGradeConfig(),
    val transformConfig: TransformConfig = TransformConfig(),
    val speedConfig: SpeedConfig = SpeedConfig(),
    val audioConfig: AudioConfig = AudioConfig(),
    val textOverlays: List<TextOverlayItem> = emptyList(),
    val selectedTextOverlayId: String? = null,
    val watermarkConfig: WatermarkConfig = WatermarkConfig(),
    val watermarkLogoBitmap: Bitmap? = null,
    val trimCutConfig: TrimCutConfig = TrimCutConfig(),
    val filmstripThumbnails: List<Bitmap> = emptyList(),
    val isLoadingFilmstrip: Boolean = false,
    val currentPlaybackMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isComparingOriginal: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val videoExportQuality: VideoExportQuality = VideoExportQuality.ORIGINAL,
    val imageExportFormat: ImageExportFormat = ImageExportFormat.JPG_HIGH,
    val exportState: ExportState = ExportState.Idle,
    val batchExportState: BatchExportState = BatchExportState.Idle,
    val isExportSheetMinimized: Boolean = false,
    val isBatchSheetMinimized: Boolean = false,
    val snackbarMessage: String? = null
) {
    val isMuted: Boolean get() = audioConfig.isMuted

    fun resolveTrackMask(track: FaceTrack, baseRect: NormalizedRect): ResolvedMaskInstance {
        val ov = track.styleOverride
        val scale = (ov?.sizeScale ?: maskConfig.sizeScale).coerceIn(0.75f, 1.80f)
        val scaledRect = if (abs(scale - 1.0f) > 0.01f) baseRect.expanded(scale).clamped() else baseRect
        return ResolvedMaskInstance(
            rect = scaledRect,
            mode = ov?.mode ?: maskConfig.mode,
            shape = ov?.shape ?: maskConfig.shape,
            blurIntensity = ov?.blurIntensity ?: maskConfig.blurIntensity,
            pixelBlockSize = ov?.pixelBlockSize ?: maskConfig.pixelBlockSize,
            feather = ov?.feather ?: maskConfig.feather,
            opacity = ov?.opacity ?: maskConfig.opacity,
            solidColorArgb = ov?.solidColorArgb ?: maskConfig.solidColorArgb,
            selectedEmoji = ov?.selectedEmoji ?: maskConfig.selectedEmoji,
            stickerScale = maskConfig.stickerScale
        )
    }

    fun resolvedMasksAt(timestampMs: Long): List<ResolvedMaskInstance> {
        val result = mutableListOf<ResolvedMaskInstance>()
        for (track in faceTracks) {
            if (maskConfig.applyToAllFaces || track.isSelected) {
                track.rectAt(timestampMs)?.let { baseRect ->
                    result.add(resolveTrackMask(track, baseRect))
                }
            }
        }
        for (manual in manualBoxes) {
            result.add(
                ResolvedMaskInstance(
                    rect = manual.rect,
                    mode = maskConfig.mode,
                    shape = maskConfig.shape,
                    blurIntensity = maskConfig.blurIntensity,
                    pixelBlockSize = maskConfig.pixelBlockSize,
                    feather = maskConfig.feather,
                    opacity = maskConfig.opacity,
                    solidColorArgb = maskConfig.solidColorArgb,
                    selectedEmoji = maskConfig.selectedEmoji,
                    stickerScale = maskConfig.stickerScale
                )
            )
        }
        return result
    }

    fun activeMaskRectsAt(timestampMs: Long): List<NormalizedRect> =
        resolvedMasksAt(timestampMs).map { it.rect }

    fun effectiveVideoOutputDurationMs(): Long {
        val baseTrimmed = trimCutConfig.outputDurationMs
        return speedConfig.outputDurationMs(baseTrimmed)
    }
}

object StudioPalettes {
    val maskSolidColors: List<Color> = listOf(
        Color(0xFF0E1014),
        Color(0xFF232833),
        Color(0xFFF4F5F7),
        Color(0xFFF5A623),
        Color(0xFFE55353),
        Color(0xFF2CC984),
        Color(0xFF4F8FF7)
    )

    val watermarkTextColors: List<Color> = listOf(
        Color(0xFFFFFFFF),
        Color(0xFFF5A623),
        Color(0xFF0E1014),
        Color(0xFFE55353),
        Color(0xFF2CC984),
        Color(0xFFDCE1EA)
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

    val speedPresets: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)
}

fun formatTimecode(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (safeMs % 1000L) / 100L
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}
