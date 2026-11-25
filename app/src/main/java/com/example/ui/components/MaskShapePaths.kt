package com.example.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.model.MaskShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Returns a Compose [Shape] matching the GPU shader and CPU Canvas geometry for each [MaskShape].
 */
fun maskComposeShape(shape: MaskShape): Shape = when (shape) {
    MaskShape.ROUNDED -> RoundedCornerShape(16)
    MaskShape.OVAL -> GenericShape { size, _ ->
        addOval(Rect(0f, 0f, size.width, size.height))
    }
    MaskShape.CIRCLE -> GenericShape { size, _ ->
        val d = min(size.width, size.height)
        val left = (size.width - d) * 0.5f
        val top = (size.height - d) * 0.5f
        addOval(Rect(left, top, left + d, top + d))
    }
    MaskShape.HEART -> GenericShape { size, _ ->
        addHeartToPath(this, size)
    }
    MaskShape.STAR -> GenericShape { size, _ ->
        addStarToPath(this, size)
    }
}

fun buildMaskComposePath(shape: MaskShape, size: Size): Path {
    val path = Path()
    when (shape) {
        MaskShape.ROUNDED -> {
            val r = min(size.width, size.height) * 0.16f
            path.addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = r,
                    radiusY = r
                )
            )
        }
        MaskShape.OVAL -> {
            path.addOval(Rect(0f, 0f, size.width, size.height))
        }
        MaskShape.CIRCLE -> {
            val d = min(size.width, size.height)
            val left = (size.width - d) * 0.5f
            val top = (size.height - d) * 0.5f
            path.addOval(Rect(left, top, left + d, top + d))
        }
        MaskShape.HEART -> addHeartToPath(path, size)
        MaskShape.STAR -> addStarToPath(path, size)
    }
    return path
}

private fun addHeartToPath(path: Path, size: Size) {
    val w = size.width
    val h = size.height
    path.moveTo(w * 0.5f, h * 0.90f)
    path.cubicTo(w * 0.08f, h * 0.62f, 0f, h * 0.28f, w * 0.25f, h * 0.14f)
    path.cubicTo(w * 0.40f, h * 0.05f, w * 0.50f, h * 0.22f, w * 0.50f, h * 0.30f)
    path.cubicTo(w * 0.50f, h * 0.22f, w * 0.60f, h * 0.05f, w * 0.75f, h * 0.14f)
    path.cubicTo(w, h * 0.28f, w * 0.92f, h * 0.62f, w * 0.50f, h * 0.90f)
    path.close()
}

private fun addStarToPath(path: Path, size: Size) {
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val outerRx = size.width * 0.49f
    val outerRy = size.height * 0.49f
    val innerRx = outerRx * 0.43f
    val innerRy = outerRy * 0.43f
    for (i in 0 until 10) {
        val angle = -PI / 2.0 + i * (PI / 5.0)
        val rx = if (i % 2 == 0) outerRx else innerRx
        val ry = if (i % 2 == 0) outerRy else innerRy
        val px = cx + (cos(angle) * rx).toFloat()
        val py = cy + (sin(angle) * ry).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}
