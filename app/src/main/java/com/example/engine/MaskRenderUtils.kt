package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.example.model.FaceMaskConfig
import com.example.model.MaskMode
import com.example.model.NormalizedRect
import com.example.model.WatermarkConfig
import com.example.model.WatermarkType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared CPU/Canvas rendering primitives used for high-resolution image export
 * and Media3 BitmapOverlay compositing.
 */
object MaskRenderUtils {

    /**
     * Renders all active masks (including pixel-manipulating blur & mosaic) and watermark
     * directly onto a mutable copy of [sourceBitmap].
     */
    fun renderImageEdits(
        sourceBitmap: Bitmap,
        activeRects: List<NormalizedRect>,
        maskConfig: FaceMaskConfig,
        stickerBitmap: Bitmap?,
        watermarkConfig: WatermarkConfig,
        watermarkLogoBitmap: Bitmap?
    ): Bitmap {
        val output = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val width = output.width
        val height = output.height

        for (norm in activeRects) {
            val rect = toPixelRect(norm, width, height)
            if (rect.width() < 2 || rect.height() < 2) continue

            when (maskConfig.mode) {
                MaskMode.GAUSSIAN_BLUR -> {
                    val crop = Bitmap.createBitmap(output, rect.left, rect.top, rect.width(), rect.height())
                    val blurred = applyGaussianLikeBlur(crop, maskConfig.blurIntensity)
                    canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), null)
                    crop.recycle()
                    if (blurred !== crop) blurred.recycle()
                }

                MaskMode.PIXELATE -> {
                    val crop = Bitmap.createBitmap(output, rect.left, rect.top, rect.width(), rect.height())
                    val pixelated = applyPixelateMosaic(crop, maskConfig.pixelBlockSize)
                    val nearestPaint = Paint().apply { isFilterBitmap = false }
                    canvas.drawBitmap(pixelated, null, rect, nearestPaint)
                    crop.recycle()
                    if (pixelated !== crop) pixelated.recycle()
                }

                MaskMode.SOLID_COLOR, MaskMode.EMOJI, MaskMode.STICKER -> {
                    drawOverlayMask(canvas, rect, maskConfig, stickerBitmap)
                }
            }
        }

        drawWatermark(
            canvas = canvas,
            canvasWidth = width,
            canvasHeight = height,
            watermarkConfig = watermarkConfig,
            logoBitmap = watermarkLogoBitmap
        )

        return output
    }

    /**
     * Draws non-shader overlays (Solid Color, Emoji, Sticker) onto [canvas].
     * Used by both still-image export and Media3 BitmapOverlay for video export.
     */
    fun drawOverlayMask(
        canvas: Canvas,
        pixelRect: Rect,
        maskConfig: FaceMaskConfig,
        stickerBitmap: Bitmap?
    ) {
        val rectF = RectF(pixelRect)
        when (maskConfig.mode) {
            MaskMode.SOLID_COLOR -> {
                val corner = min(rectF.width(), rectF.height()) * 0.14f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = maskConfig.solidColorArgb
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(rectF, corner, corner, paint)
            }

            MaskMode.EMOJI -> {
                val textSize = min(rectF.width(), rectF.height()) * 0.92f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.textSize = textSize
                    textAlign = Paint.Align.CENTER
                }
                val metrics = paint.fontMetrics
                val baseline = rectF.centerY() - (metrics.ascent + metrics.descent) * 0.5f
                canvas.drawText(maskConfig.selectedEmoji, rectF.centerX(), baseline, paint)
            }

            MaskMode.STICKER -> {
                if (stickerBitmap != null && !stickerBitmap.isRecycled) {
                    val scale = maskConfig.stickerScale.coerceIn(0.6f, 2.2f)
                    val baseSize = max(rectF.width(), rectF.height()) * scale
                    val aspect = stickerBitmap.width.toFloat() / stickerBitmap.height.coerceAtLeast(1)
                    val drawW = if (aspect >= 1f) baseSize else baseSize * aspect
                    val drawH = if (aspect >= 1f) baseSize / aspect else baseSize
                    val dst = RectF(
                        rectF.centerX() - drawW * 0.5f,
                        rectF.centerY() - drawH * 0.5f,
                        rectF.centerX() + drawW * 0.5f,
                        rectF.centerY() + drawH * 0.5f
                    )
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(stickerBitmap, null, dst, paint)
                } else {
                    // Graceful fallback if no sticker image chosen yet
                    val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xCC090B10.toInt()
                    }
                    canvas.drawRoundRect(rectF, 16f, 16f, fallbackPaint)
                }
            }

            MaskMode.GAUSSIAN_BLUR, MaskMode.PIXELATE -> Unit
        }
    }

    /**
     * Draws text or logo watermark onto [canvas] using normalized coordinates so placement
     * matches the live preview canvas across any output resolution.
     */
    fun drawWatermark(
        canvas: Canvas,
        canvasWidth: Int,
        canvasHeight: Int,
        watermarkConfig: WatermarkConfig,
        logoBitmap: Bitmap?
    ) {
        if (watermarkConfig.type == WatermarkType.NONE) return
        val alphaInt = (watermarkConfig.opacity.coerceIn(0.05f, 1f) * 255f).roundToInt()
        val anchorX = watermarkConfig.normalizedX.coerceIn(0.04f, 0.96f) * canvasWidth
        val anchorY = watermarkConfig.normalizedY.coerceIn(0.04f, 0.96f) * canvasHeight

        when (watermarkConfig.type) {
            WatermarkType.TEXT -> {
                val text = watermarkConfig.text.trim()
                if (text.isEmpty()) return
                val minDim = min(canvasWidth, canvasHeight).toFloat()
                // Scale sp relative to 360dp reference preview width so text looks identical in export
                val scaledTextPx = (watermarkConfig.fontSizeSp / 360f) * minDim * 1.15f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = watermarkConfig.textColorArgb
                    alpha = alphaInt
                    textSize = scaledTextPx.coerceAtLeast(16f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(
                        scaledTextPx * 0.14f,
                        0f,
                        scaledTextPx * 0.06f,
                        Color.argb((alphaInt * 0.72f).roundToInt(), 0, 0, 0)
                    )
                }
                val metrics = paint.fontMetrics
                val baseline = anchorY - (metrics.ascent + metrics.descent) * 0.5f
                canvas.drawText(text, anchorX, baseline, paint)
            }

            WatermarkType.IMAGE -> {
                val logo = logoBitmap ?: return
                if (logo.isRecycled) return
                val targetWidth = (canvasWidth * watermarkConfig.logoScale.coerceIn(0.06f, 0.55f))
                val aspect = logo.width.toFloat() / logo.height.coerceAtLeast(1)
                val targetHeight = targetWidth / aspect
                val dst = RectF(
                    anchorX - targetWidth * 0.5f,
                    anchorY - targetHeight * 0.5f,
                    anchorX + targetWidth * 0.5f,
                    anchorY + targetHeight * 0.5f
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = alphaInt
                }
                canvas.drawBitmap(logo, null, dst, paint)
            }

            WatermarkType.NONE -> Unit
        }
    }

    fun toPixelRect(norm: NormalizedRect, width: Int, height: Int): Rect {
        val left = (norm.left * width).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = (norm.top * height).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val right = (norm.right * width).roundToInt().coerceIn(left + 1, width.coerceAtLeast(1))
        val bottom = (norm.bottom * height).roundToInt().coerceIn(top + 1, height.coerceAtLeast(1))
        return Rect(left, top, right, bottom)
    }

    /**
     * Multi-pass box blur + smooth downscale approximation that yields silky Gaussian blur
     * across API 24-36 with zero deprecated RenderScript dependencies.
     */
    private fun applyGaussianLikeBlur(source: Bitmap, intensity: Float): Bitmap {
        val clamped = intensity.coerceIn(0.15f, 1.0f)
        val downFactor = (4f + clamped * 14f)
        val smallW = (source.width / downFactor).roundToInt().coerceIn(6, source.width)
        val smallH = (source.height / downFactor).roundToInt().coerceIn(6, source.height)
        val downscaled = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val blurredSmall = fastBoxBlur(downscaled, radius = (2 + (clamped * 4f).roundToInt()).coerceIn(2, 6))
        if (blurredSmall !== downscaled) downscaled.recycle()
        val result = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
        if (result !== blurredSmall) blurredSmall.recycle()
        return result
    }

    private fun applyPixelateMosaic(source: Bitmap, blockScale: Float): Bitmap {
        val clamped = blockScale.coerceIn(0.1f, 1.0f)
        val minSide = min(source.width, source.height)
        val blockPx = (minSide * (0.04f + clamped * 0.16f)).roundToInt().coerceAtLeast(4)
        val gridW = (source.width / blockPx).coerceIn(3, source.width)
        val gridH = (source.height / blockPx).coerceIn(3, source.height)
        return Bitmap.createScaledBitmap(source, gridW, gridH, false)
    }

    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)

        repeat(2) {
            // Horizontal pass
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    val x0 = max(0, x - radius)
                    val x1 = min(w - 1, x + radius)
                    for (kx in x0..x1) {
                        val c = pix[row + kx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                    tmp[row + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                }
            }
            // Vertical pass
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    val y0 = max(0, y - radius)
                    val y1 = min(h - 1, y + radius)
                    for (ky in y0..y1) {
                        val c = tmp[ky * w + x]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                    pix[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                }
            }
        }

        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }
}
