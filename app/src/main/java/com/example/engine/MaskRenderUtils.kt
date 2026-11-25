package com.example.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.model.ColorGradeConfig
import com.example.model.MaskMode
import com.example.model.MaskShape
import com.example.model.NormalizedRect
import com.example.model.ResolvedMaskInstance
import com.example.model.TextOverlayItem
import com.example.model.TextOverlayStyle
import com.example.model.TransformConfig
import com.example.model.WatermarkConfig
import com.example.model.WatermarkType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared CPU/Canvas rendering primitives used for high-resolution image export
 * and Media3 BitmapOverlay compositing (shaped/feathered masks, color grading,
 * crop/transform, timed styled text overlays, and watermarks).
 */
object MaskRenderUtils {

    /**
     * Shared base pipeline (Steps 1-3) used by BOTH live WYSIWYG Photo Preview and full-res Export:
     * 1. Apply privacy masks (per-face shaped & feathered across all 9 effects)
     * 2. Apply color grading & vignette
     * 3. Apply crop / aspect ratio / rotate / flip / zoom transform
     */
    fun renderImagePreviewBase(
        sourceBitmap: Bitmap,
        resolvedMasks: List<ResolvedMaskInstance>,
        stickerBitmap: Bitmap?,
        colorGradeConfig: ColorGradeConfig,
        transformConfig: TransformConfig
    ): Bitmap {
        var working = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)

        // 1. Apply Privacy Masks in source coordinates (where ML Kit detected faces)
        applyResolvedMasksToBitmap(working, resolvedMasks, stickerBitmap)

        // 2. Apply Color Grading & Vignette
        if (!colorGradeConfig.isDefault()) {
            val graded = applyColorGrade(working, colorGradeConfig)
            working.recycle()
            working = graded
        }

        // 3. Apply Rotate / Flip / Zoom / Social Aspect Crop
        if (!transformConfig.isDefault()) {
            val transformed = applyTransform(working, transformConfig)
            working.recycle()
            working = transformed
        }

        return working
    }

    /**
     * Full photo export pipeline:
     * Runs [renderImagePreviewBase] and draws styled text captions & watermark on the final canvas.
     */
    fun renderImageEdits(
        sourceBitmap: Bitmap,
        resolvedMasks: List<ResolvedMaskInstance>,
        stickerBitmap: Bitmap?,
        colorGradeConfig: ColorGradeConfig,
        transformConfig: TransformConfig,
        textOverlays: List<TextOverlayItem>,
        watermarkConfig: WatermarkConfig,
        watermarkLogoBitmap: Bitmap?
    ): Bitmap {
        val working = renderImagePreviewBase(
            sourceBitmap = sourceBitmap,
            resolvedMasks = resolvedMasks,
            stickerBitmap = stickerBitmap,
            colorGradeConfig = colorGradeConfig,
            transformConfig = transformConfig
        )

        // 4. Draw Text Captions & Watermark on final framed canvas
        val canvas = Canvas(working)
        drawTextOverlays(
            canvas = canvas,
            canvasWidth = working.width,
            canvasHeight = working.height,
            overlays = textOverlays,
            timestampMs = 0L,
            isVideo = false
        )
        drawWatermark(
            canvas = canvas,
            canvasWidth = working.width,
            canvasHeight = working.height,
            watermarkConfig = watermarkConfig,
            logoBitmap = watermarkLogoBitmap
        )

        return working
    }

    private fun applyResolvedMasksToBitmap(
        target: Bitmap,
        masks: List<ResolvedMaskInstance>,
        stickerBitmap: Bitmap?
    ) {
        if (masks.isEmpty()) return
        val canvas = Canvas(target)
        val width = target.width
        val height = target.height

        // Handle Spotlight mode first (blurs & dims everything OUTSIDE spotlight shapes)
        val spotlights = masks.filter { it.mode == MaskMode.SPOTLIGHT }
        if (spotlights.isNotEmpty()) {
            val ref = spotlights.first()
            val blurredFull = applyGaussianLikeBlur(target, ref.blurIntensity)
            val dimmedCanvas = Canvas(blurredFull)
            dimmedCanvas.drawColor(Color.argb((85 * ref.opacity).roundToInt().coerceIn(0, 140), 0, 0, 0))

            // Punch clear holes where the spotlight face shapes sit
            val punchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                if (ref.feather > 0.04f) {
                    maskFilter = BlurMaskFilter(
                        (min(width, height) * 0.03f * ref.feather).coerceAtLeast(2f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }
            }
            val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layer)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (ref.opacity * 255f).roundToInt().coerceIn(40, 255)
            }
            layerCanvas.drawBitmap(blurredFull, 0f, 0f, bgPaint)
            for (spot in spotlights) {
                val rectF = RectF(toPixelRect(spot.rect, width, height))
                layerCanvas.drawPath(buildShapePath(rectF, spot.shape), punchPaint)
            }
            canvas.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()
            if (blurredFull !== target) blurredFull.recycle()
        }

        // Apply individual masks inside their respective shapes
        for (mask in masks) {
            if (mask.mode == MaskMode.SPOTLIGHT) continue
            val pixelRect = toPixelRect(mask.rect, width, height)
            if (pixelRect.width() < 2 || pixelRect.height() < 2) continue

            when (mask.mode) {
                MaskMode.GAUSSIAN_BLUR -> {
                    val crop = Bitmap.createBitmap(target, pixelRect.left, pixelRect.top, pixelRect.width(), pixelRect.height())
                    val fx = applyGaussianLikeBlur(crop, mask.blurIntensity)
                    drawShapedFeatheredBitmap(canvas, fx, pixelRect, mask.shape, mask.feather, mask.opacity)
                    crop.recycle()
                    if (fx !== crop) fx.recycle()
                }

                MaskMode.PIXELATE -> {
                    val crop = Bitmap.createBitmap(target, pixelRect.left, pixelRect.top, pixelRect.width(), pixelRect.height())
                    val pix = applyPixelateMosaic(crop, mask.pixelBlockSize)
                    val nearestScaled = Bitmap.createScaledBitmap(pix, pixelRect.width(), pixelRect.height(), false)
                    drawShapedFeatheredBitmap(canvas, nearestScaled, pixelRect, mask.shape, mask.feather, mask.opacity)
                    crop.recycle()
                    if (pix !== crop) pix.recycle()
                    if (nearestScaled !== pix) nearestScaled.recycle()
                }

                MaskMode.FROSTED_GLASS -> {
                    val crop = Bitmap.createBitmap(target, pixelRect.left, pixelRect.top, pixelRect.width(), pixelRect.height())
                    val fx = applyFrostedGlass(crop, mask.blurIntensity)
                    drawShapedFeatheredBitmap(canvas, fx, pixelRect, mask.shape, mask.feather, mask.opacity)
                    crop.recycle()
                    if (fx !== crop) fx.recycle()
                }

                MaskMode.GLITCH -> {
                    val crop = Bitmap.createBitmap(target, pixelRect.left, pixelRect.top, pixelRect.width(), pixelRect.height())
                    val fx = applyGlitchEffect(crop, mask.blurIntensity)
                    drawShapedFeatheredBitmap(canvas, fx, pixelRect, mask.shape, mask.feather, mask.opacity)
                    crop.recycle()
                    if (fx !== crop) fx.recycle()
                }

                MaskMode.INVERT -> {
                    val crop = Bitmap.createBitmap(target, pixelRect.left, pixelRect.top, pixelRect.width(), pixelRect.height())
                    val fx = applyInvertEffect(crop)
                    drawShapedFeatheredBitmap(canvas, fx, pixelRect, mask.shape, mask.feather, mask.opacity)
                    crop.recycle()
                    if (fx !== crop) fx.recycle()
                }

                MaskMode.SOLID_COLOR, MaskMode.EMOJI, MaskMode.STICKER -> {
                    drawOverlayMask(canvas, pixelRect, mask, stickerBitmap)
                }

                MaskMode.SPOTLIGHT -> Unit
            }
        }
    }

    private fun drawShapedFeatheredBitmap(
        targetCanvas: Canvas,
        effectBitmap: Bitmap,
        destRect: Rect,
        shape: MaskShape,
        feather: Float,
        opacity: Float
    ) {
        val w = destRect.width().coerceAtLeast(2)
        val h = destRect.height().coerceAtLeast(2)
        val masked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mc = Canvas(masked)

        val localRect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val featherRadius = (min(w, h) * 0.14f * feather.coerceIn(0f, 1f))
        val insetRect = RectF(localRect).apply {
            if (featherRadius > 1f) {
                val inset = (featherRadius * 0.65f).coerceAtMost(min(w, h) * 0.25f)
                inset(inset, inset)
            }
        }

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            if (featherRadius >= 1.5f) {
                maskFilter = BlurMaskFilter(featherRadius, BlurMaskFilter.Blur.NORMAL)
            }
        }
        mc.drawPath(buildShapePath(insetRect, shape), maskPaint)

        val srcInPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        mc.drawBitmap(effectBitmap, null, localRect, srcInPaint)

        val compositePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (opacity.coerceIn(0.2f, 1f) * 255f).roundToInt()
        }
        targetCanvas.drawBitmap(masked, destRect.left.toFloat(), destRect.top.toFloat(), compositePaint)
        masked.recycle()
    }

    /**
     * Builds an Android Canvas [Path] for any of the 5 supported [MaskShape] styles.
     */
    fun buildShapePath(rectF: RectF, shape: MaskShape): Path {
        val path = Path()
        val w = rectF.width().coerceAtLeast(1f)
        val h = rectF.height().coerceAtLeast(1f)
        val cx = rectF.centerX()
        val cy = rectF.centerY()

        when (shape) {
            MaskShape.ROUNDED -> {
                val r = min(w, h) * 0.18f
                path.addRoundRect(rectF, r, r, Path.Direction.CW)
            }

            MaskShape.OVAL -> {
                path.addOval(rectF, Path.Direction.CW)
            }

            MaskShape.CIRCLE -> {
                val radius = min(w, h) * 0.5f
                path.addCircle(cx, cy, radius, Path.Direction.CW)
            }

            MaskShape.HEART -> {
                val topCurve = rectF.top + h * 0.30f
                path.moveTo(cx, rectF.bottom)
                path.cubicTo(
                    rectF.left - w * 0.08f, rectF.top + h * 0.62f,
                    rectF.left, rectF.top + h * 0.04f,
                    cx, topCurve
                )
                path.cubicTo(
                    rectF.right, rectF.top + h * 0.04f,
                    rectF.right + w * 0.08f, rectF.top + h * 0.62f,
                    cx, rectF.bottom
                )
                path.close()
            }

            MaskShape.STAR -> {
                val outerRx = w * 0.50f
                val outerRy = h * 0.50f
                val innerRx = w * 0.22f
                val innerRy = h * 0.22f
                val points = 5
                for (i in 0 until points * 2) {
                    val angle = -PI / 2.0 + i * PI / points
                    val rx = if (i % 2 == 0) outerRx else innerRx
                    val ry = if (i % 2 == 0) outerRy else innerRy
                    val x = cx + (cos(angle) * rx).toFloat()
                    val y = cy + (sin(angle) * ry).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
        return path
    }

    /**
     * Draws non-shader overlays (Solid Color, Emoji, Sticker) onto [canvas].
     * Used by both still-image export and Media3 BitmapOverlay for video export.
     */
    fun drawOverlayMask(
        canvas: Canvas,
        pixelRect: Rect,
        mask: ResolvedMaskInstance,
        stickerBitmap: Bitmap?
    ) {
        val rectF = RectF(pixelRect)
        val alphaInt = (mask.opacity.coerceIn(0.25f, 1f) * 255f).roundToInt()

        when (mask.mode) {
            MaskMode.SOLID_COLOR -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mask.solidColorArgb
                    alpha = alphaInt
                    style = Paint.Style.FILL
                    if (mask.feather > 0.06f) {
                        val radius = (min(rectF.width(), rectF.height()) * 0.10f * mask.feather).coerceAtLeast(2f)
                        maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                    }
                }
                canvas.drawPath(buildShapePath(rectF, mask.shape), paint)
            }

            MaskMode.EMOJI -> {
                val textSize = min(rectF.width(), rectF.height()) * 0.84f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.textSize = textSize
                    this.alpha = alphaInt
                    textAlign = Paint.Align.CENTER
                }
                val metrics = paint.fontMetrics
                val baseline = rectF.centerY() - (metrics.ascent + metrics.descent) * 0.5f
                canvas.drawText(mask.selectedEmoji, rectF.centerX(), baseline, paint)
            }

            MaskMode.STICKER -> {
                if (stickerBitmap != null && !stickerBitmap.isRecycled) {
                    val scale = mask.stickerScale.coerceIn(0.6f, 2.2f)
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
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = alphaInt
                    }
                    canvas.drawBitmap(stickerBitmap, null, dst, paint)
                } else {
                    val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xCC090B10.toInt()
                        alpha = alphaInt
                    }
                    canvas.drawPath(buildShapePath(rectF, mask.shape), fallbackPaint)
                }
            }

            else -> Unit
        }
    }

    /**
     * Draws styled text captions and title overlays onto [canvas].
     */
    fun drawTextOverlays(
        canvas: Canvas,
        canvasWidth: Int,
        canvasHeight: Int,
        overlays: List<TextOverlayItem>,
        timestampMs: Long,
        isVideo: Boolean
    ) {
        if (overlays.isEmpty()) return
        val minDim = min(canvasWidth, canvasHeight).toFloat()

        for (item in overlays) {
            if (!item.isVisibleAt(timestampMs, isVideo)) continue
            val text = item.text.trim()
            if (text.isEmpty()) continue

            val alphaInt = (item.opacity.coerceIn(0.1f, 1f) * 255f).roundToInt()
            val anchorX = item.normalizedX.coerceIn(0.06f, 0.94f) * canvasWidth
            val anchorY = item.normalizedY.coerceIn(0.06f, 0.94f) * canvasHeight
            val textPx = ((item.fontSizeSp / 360f) * minDim * 1.05f).coerceAtLeast(16f)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = textPx
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                color = if (item.style == TextOverlayStyle.AMBER_PILL) 0xFF1C1202.toInt() else item.textColorArgb
                alpha = alphaInt
            }

            val textWidth = textPaint.measureText(text)
            val metrics = textPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val padH = textPx * 0.55f
            val padV = textPx * 0.28f
            val bgRect = RectF(
                anchorX - textWidth * 0.5f - padH,
                anchorY - textHeight * 0.5f - padV,
                anchorX + textWidth * 0.5f + padH,
                anchorY + textHeight * 0.5f + padV
            )
            val baseline = anchorY - (metrics.ascent + metrics.descent) * 0.5f

            when (item.style) {
                TextOverlayStyle.AMBER_PILL -> {
                    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFFF5A623.toInt()
                        alpha = alphaInt
                    }
                    canvas.drawRoundRect(bgRect, bgRect.height() * 0.5f, bgRect.height() * 0.5f, pillPaint)
                    canvas.drawText(text, anchorX, baseline, textPaint)
                }

                TextOverlayStyle.SUBTITLE -> {
                    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xCC0B0D12.toInt()
                        alpha = (alphaInt * 0.85f).roundToInt()
                    }
                    canvas.drawRoundRect(bgRect, textPx * 0.22f, textPx * 0.22f, subPaint)
                    canvas.drawText(text, anchorX, baseline, textPaint)
                }

                TextOverlayStyle.GLASS -> {
                    val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x66191D25.toInt()
                        alpha = (alphaInt * 0.65f).roundToInt()
                    }
                    val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = max(2f, textPx * 0.06f)
                        color = 0x99FFFFFF.toInt()
                        alpha = (alphaInt * 0.6f).roundToInt()
                    }
                    val corner = textPx * 0.35f
                    canvas.drawRoundRect(bgRect, corner, corner, glassFill)
                    canvas.drawRoundRect(bgRect, corner, corner, glassStroke)
                    canvas.drawText(text, anchorX, baseline, textPaint)
                }

                TextOverlayStyle.OUTLINE -> {
                    val strokePaint = Paint(textPaint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = max(3f, textPx * 0.14f)
                        strokeJoin = Paint.Join.ROUND
                        color = Color.BLACK
                        alpha = alphaInt
                    }
                    canvas.drawText(text, anchorX, baseline, strokePaint)
                    canvas.drawText(text, anchorX, baseline, textPaint)
                }

                TextOverlayStyle.MINIMAL -> {
                    textPaint.setShadowLayer(
                        textPx * 0.18f,
                        0f,
                        textPx * 0.06f,
                        Color.argb((alphaInt * 0.78f).roundToInt(), 0, 0, 0)
                    )
                    canvas.drawText(text, anchorX, baseline, textPaint)
                }
            }
        }
    }

    /**
     * Draws text or logo watermark onto [canvas] using normalized coordinates.
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
                val scaledTextPx = ((watermarkConfig.fontSizeSp / 360f) * minDim * 1.05f).coerceAtLeast(16f)
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

    fun applyColorGrade(source: Bitmap, config: ColorGradeConfig): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(config.toColorMatrix20()))
        }
        canvas.drawBitmap(source, 0f, 0f, paint)

        if (config.vignette > 0.005f) {
            val radius = max(source.width, source.height) * 0.72f
            val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    source.width * 0.5f,
                    source.height * 0.5f,
                    radius.coerceAtLeast(1f),
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb((config.vignette.coerceIn(0f, 0.85f) * 215f).roundToInt(), 0, 0, 0)
                    ),
                    floatArrayOf(0.45f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), vignettePaint)
        }
        return out
    }

    fun applyTransform(source: Bitmap, config: TransformConfig): Bitmap {
        // 1. Rotate & Flip
        val matrix = Matrix().apply {
            val sx = if (config.flipHorizontal) -1f else 1f
            val sy = if (config.flipVertical) -1f else 1f
            postScale(sx, sy)
            val rot = ((config.rotationDegrees % 360) + 360) % 360
            if (rot != 0) {
                postRotate(rot.toFloat())
            }
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        // 2. Aspect Ratio Center Crop + Zoom
        val targetRatio = config.aspectRatio.ratio ?: (rotated.width.toFloat() / rotated.height.coerceAtLeast(1))
        val curRatio = rotated.width.toFloat() / rotated.height.coerceAtLeast(1)

        var cropW = rotated.width.toFloat()
        var cropH = rotated.height.toFloat()
        if (curRatio > targetRatio) {
            cropW = cropH * targetRatio
        } else {
            cropH = cropW / targetRatio
        }

        val zoom = config.zoomScale.coerceIn(1.0f, 2.0f)
        cropW /= zoom
        cropH /= zoom

        val finalW = cropW.roundToInt().coerceIn(2, rotated.width)
        val finalH = cropH.roundToInt().coerceIn(2, rotated.height)
        val left = ((rotated.width - finalW) / 2).coerceIn(0, rotated.width - finalW)
        val top = ((rotated.height - finalH) / 2).coerceIn(0, rotated.height - finalH)

        val cropped = Bitmap.createBitmap(rotated, left, top, finalW, finalH)
        if (cropped !== rotated) rotated.recycle()
        return cropped
    }

    fun toPixelRect(norm: NormalizedRect, width: Int, height: Int): Rect {
        val left = (norm.left * width).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = (norm.top * height).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val right = (norm.right * width).roundToInt().coerceIn(left + 1, width.coerceAtLeast(1))
        val bottom = (norm.bottom * height).roundToInt().coerceIn(top + 1, height.coerceAtLeast(1))
        return Rect(left, top, right, bottom)
    }

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

    private fun applyFrostedGlass(source: Bitmap, intensity: Float): Bitmap {
        val blurred = applyGaussianLikeBlur(source, max(0.55f, intensity))
        val frosted = blurred.copy(Bitmap.Config.ARGB_8888, true)
        if (blurred !== source) blurred.recycle()
        val c = Canvas(frosted)
        c.drawColor(Color.argb(62, 232, 240, 250))
        return frosted
    }

    private fun applyGlitchEffect(source: Bitmap, intensity: Float): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val shift = ((w * 0.04f) * (0.5f + intensity)).roundToInt().coerceAtLeast(3)
        val bandH = (h / 18).coerceAtLeast(4)

        for (y in 0 until h) {
            val row = y * w
            val band = y / bandH
            val jitter = if (band % 3 == 0) shift else if (band % 3 == 1) -shift / 2 else shift / 3
            val scanFactor = if (y % 4 == 0) 0.86f else 1.0f
            for (x in 0 until w) {
                val xr = (x + jitter + shift).coerceIn(0, w - 1)
                val xg = (x + jitter / 2).coerceIn(0, w - 1)
                val xb = (x + jitter - shift).coerceIn(0, w - 1)
                val r = (((src[row + xr] shr 16) and 0xFF) * scanFactor).roundToInt()
                val g = (((src[row + xg] shr 8) and 0xFF) * scanFactor).roundToInt()
                val b = ((src[row + xb] and 0xFF) * scanFactor).roundToInt()
                dst[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyInvertEffect(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val invertMatrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(invertMatrix) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
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
