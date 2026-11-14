package com.example.ui.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LaserPoint
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun parseColor(hex: String, default: Color = Color.Black): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        default
    }
}

fun getPathEffect(style: StrokeStyle, width: Float, animPhase: Float = 0f): PathEffect? {
    return when (style) {
        StrokeStyle.SOLID -> null
        StrokeStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
        StrokeStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(width, width * 1.5f), 0f)
        StrokeStyle.DASH_DOT -> PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 1.6f, width * 1.2f, width * 1.6f), 0f)
        StrokeStyle.ANIMATED_PULSE -> PathEffect.dashPathEffect(floatArrayOf(width * 3.5f, width * 2f), animPhase * width * 5.5f)
        else -> null
    }
}

fun DrawScope.drawCanvasBackgroundAndGrid(
    gridType: CanvasGridType,
    colorTheme: CanvasColorTheme,
    panX: Float,
    panY: Float,
    scale: Float,
    screenW: Float,
    screenH: Float
) {
    val bgHex = colorTheme.hex
    val bgColor = parseColor(bgHex, Color.White)
    drawRect(color = bgColor)

    if (gridType == CanvasGridType.BLANK) return

    val gridColor = if (colorTheme.isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val baseStep = 40f
    val step = baseStep * scale

    if (step < 8f) return

    val startX = (panX % step + step) % step
    val startY = (panY % step + step) % step

    when (gridType) {
        CanvasGridType.DOTS -> {
            var x = startX
            while (x < screenW) {
                var y = startY
                while (y < screenH) {
                    drawCircle(color = gridColor, radius = (1.5f * scale).coerceIn(1f, 3f), center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }
        CanvasGridType.GRID -> {
            var x = startX
            while (x < screenW) {
                drawLine(color = gridColor, start = Offset(x, 0f), end = Offset(x, screenH), strokeWidth = 1f)
                x += step
            }
            var y = startY
            while (y < screenH) {
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(screenW, y), strokeWidth = 1f)
                y += step
            }
        }
        CanvasGridType.LINES -> {
            var y = startY
            while (y < screenH) {
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(screenW, y), strokeWidth = 1f)
                y += step
            }
        }
        CanvasGridType.DIAMONDS -> {
            var x = startX
            while (x < screenW) {
                var y = startY
                while (y < screenH) {
                    drawCircle(color = gridColor.copy(alpha = 0.06f), radius = step * 0.4f, center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }
        else -> {}
    }
}

fun DrawScope.drawStrokeVector(
    stroke: WhiteboardStroke,
    panX: Float,
    panY: Float,
    scale: Float,
    animPhase: Float = 0f,
    isSelected: Boolean = false,
    isDarkBoard: Boolean = false
) {
    AdvancedLineEffects.run {
        drawAdvancedStroke(
            stroke = stroke,
            panX = panX,
            panY = panY,
            scale = scale,
            animPhase = animPhase,
            isSelected = isSelected,
            isDarkBoard = isDarkBoard
        )
    }
}

fun DrawScope.drawShapeVector(
    shape: ShapeItem,
    panX: Float,
    panY: Float,
    scale: Float,
    animPhase: Float = 0f,
    isSelected: Boolean = false
) {
    val x = shape.x * scale + panX
    val y = shape.y * scale + panY
    val w = shape.width * scale
    val h = shape.height * scale

    val strokeColor = parseColor(shape.strokeColorHex)
    val fillColor = parseColor(shape.fillColorHex)
    val strokeW = (shape.strokeWidth * scale).coerceAtLeast(1.5f)
    val pathEffect = getPathEffect(shape.strokeStyle, strokeW, animPhase)

    rotate(degrees = shape.rotation, pivot = Offset(x + w / 2f, y + h / 2f)) {
        when (shape.shapeType) {
            ShapeType.RECTANGLE -> {
                if (shape.isFilled) drawRect(color = fillColor, topLeft = Offset(x, y), size = Size(w, h))
                drawRect(
                    color = strokeColor,
                    topLeft = Offset(x, y),
                    size = Size(w, h),
                    style = Stroke(width = strokeW, pathEffect = pathEffect)
                )
            }
            ShapeType.ROUNDED_RECT -> {
                val cr = CornerRadius(16f * scale, 16f * scale)
                if (shape.isFilled) drawRoundRect(color = fillColor, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = cr)
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(x, y),
                    size = Size(w, h),
                    cornerRadius = cr,
                    style = Stroke(width = strokeW, pathEffect = pathEffect)
                )
            }
            ShapeType.CIRCLE -> {
                if (shape.isFilled) {
                    drawOval(color = fillColor, topLeft = Offset(x, y), size = Size(w, h))
                }
                drawOval(
                    color = strokeColor,
                    topLeft = Offset(x, y),
                    size = Size(w, h),
                    style = Stroke(width = strokeW, pathEffect = pathEffect)
                )
            }
            ShapeType.TRIANGLE -> {
                val path = Path().apply {
                    if (shape.vertices.size == 3) {
                        val v0 = shape.vertices[0]
                        val v1 = shape.vertices[1]
                        val v2 = shape.vertices[2]
                        moveTo(v0.x * scale + panX, v0.y * scale + panY)
                        lineTo(v1.x * scale + panX, v1.y * scale + panY)
                        lineTo(v2.x * scale + panX, v2.y * scale + panY)
                    } else {
                        moveTo(x + w / 2f, y)
                        lineTo(x + w, y + h)
                        lineTo(x, y + h)
                    }
                    close()
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = strokeW, join = StrokeJoin.Round, pathEffect = pathEffect)
                )
            }
            ShapeType.DIAMOND -> {
                val path = Path().apply {
                    if (shape.vertices.size >= 4) {
                        val v0 = shape.vertices[0]
                        moveTo(v0.x * scale + panX, v0.y * scale + panY)
                        for (i in 1 until shape.vertices.size) {
                            val vi = shape.vertices[i]
                            lineTo(vi.x * scale + panX, vi.y * scale + panY)
                        }
                    } else {
                        moveTo(x + w / 2f, y)
                        lineTo(x + w, y + h / 2f)
                        lineTo(x + w / 2f, y + h)
                        lineTo(x, y + h / 2f)
                    }
                    close()
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = strokeW, join = StrokeJoin.Round, pathEffect = pathEffect)
                )
            }
            ShapeType.LINE -> {
                AdvancedLineEffects.run {
                    drawAdvancedLineShape(
                        shape = shape,
                        panX = panX,
                        panY = panY,
                        scale = scale,
                        animPhase = animPhase,
                        isSelected = false
                    )
                }
            }
            ShapeType.ARROW -> {
                val head = kotlin.math.min(22f * scale, kotlin.math.min(w * 0.35f, h * 0.45f))
                drawLine(
                    color = strokeColor,
                    start = Offset(x, y + h / 2f),
                    end = Offset(x + w - head * 0.4f, y + h / 2f),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                    pathEffect = pathEffect
                )
                val path = Path().apply {
                    moveTo(x + w, y + h / 2f)
                    lineTo(x + w - head, y + h / 2f - head * 0.65f)
                    lineTo(x + w - head, y + h / 2f + head * 0.65f)
                    close()
                }
                drawPath(path = path, color = strokeColor)
            }
            ShapeType.DOUBLE_ARROW -> {
                val head = kotlin.math.min(20f * scale, kotlin.math.min(w * 0.25f, h * 0.45f))
                drawLine(
                    color = strokeColor,
                    start = Offset(x + head * 0.4f, y + h / 2f),
                    end = Offset(x + w - head * 0.4f, y + h / 2f),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                    pathEffect = pathEffect
                )
                val rightHead = Path().apply {
                    moveTo(x + w, y + h / 2f)
                    lineTo(x + w - head, y + h / 2f - head * 0.65f)
                    lineTo(x + w - head, y + h / 2f + head * 0.65f)
                    close()
                }
                val leftHead = Path().apply {
                    moveTo(x, y + h / 2f)
                    lineTo(x + head, y + h / 2f - head * 0.65f)
                    lineTo(x + head, y + h / 2f + head * 0.65f)
                    close()
                }
                drawPath(path = rightHead, color = strokeColor)
                drawPath(path = leftHead, color = strokeColor)
            }
            ShapeType.HEXAGON -> {
                val path = Path().apply {
                    moveTo(x + w * 0.25f, y)
                    lineTo(x + w * 0.75f, y)
                    lineTo(x + w, y + h * 0.5f)
                    lineTo(x + w * 0.75f, y + h)
                    lineTo(x + w * 0.25f, y + h)
                    lineTo(x, y + h * 0.5f)
                    close()
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = strokeW, join = StrokeJoin.Round, pathEffect = pathEffect)
                )
            }
            ShapeType.STAR -> {
                val cx = x + w / 2f
                val cy = y + h / 2f
                val outerRx = w / 2f
                val outerRy = h / 2f
                val innerRx = outerRx * 0.42f
                val innerRy = outerRy * 0.42f
                val path = Path().apply {
                    for (i in 0 until 10) {
                        val angle = -PI / 2.0 + i * (PI / 5.0)
                        val rx = if (i % 2 == 0) outerRx else innerRx
                        val ry = if (i % 2 == 0) outerRy else innerRy
                        val px = cx + (rx * cos(angle)).toFloat()
                        val py = cy + (ry * sin(angle)).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = strokeW, join = StrokeJoin.Round, pathEffect = pathEffect)
                )
            }
            ShapeType.CLOUD_CALLOUT -> {
                val bubbleH = h * 0.78f
                val cr = CornerRadius(bubbleH * 0.35f, bubbleH * 0.35f)
                val tailPath = Path().apply {
                    moveTo(x + w * 0.26f, y + bubbleH - 1f)
                    lineTo(x + w * 0.16f, y + h)
                    lineTo(x + w * 0.42f, y + bubbleH - 1f)
                    close()
                }
                if (shape.isFilled) {
                    drawRoundRect(color = fillColor, topLeft = Offset(x, y), size = Size(w, bubbleH), cornerRadius = cr)
                    drawPath(path = tailPath, color = fillColor)
                }
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(x, y),
                    size = Size(w, bubbleH),
                    cornerRadius = cr,
                    style = Stroke(width = strokeW, pathEffect = pathEffect)
                )
                drawPath(
                    path = tailPath,
                    color = strokeColor,
                    style = Stroke(width = strokeW, join = StrokeJoin.Round, pathEffect = pathEffect)
                )
            }
            ShapeType.CYLINDER -> {
                val capH = (h * 0.22f).coerceAtLeast(10f)
                val bodyPath = Path().apply {
                    moveTo(x, y + capH / 2f)
                    lineTo(x, y + h - capH / 2f)
                    quadraticTo(x + w / 2f, y + h + capH * 0.45f, x + w, y + h - capH / 2f)
                    lineTo(x + w, y + capH / 2f)
                    close()
                }
                if (shape.isFilled) {
                    drawPath(path = bodyPath, color = fillColor)
                    drawOval(color = fillColor, topLeft = Offset(x, y), size = Size(w, capH))
                }
                drawPath(
                    path = bodyPath,
                    color = strokeColor,
                    style = Stroke(width = strokeW, join = StrokeJoin.Round, pathEffect = pathEffect)
                )
                drawOval(
                    color = strokeColor,
                    topLeft = Offset(x, y),
                    size = Size(w, capH),
                    style = Stroke(width = strokeW, pathEffect = pathEffect)
                )
            }
        }

        if (isSelected) {
            if (shape.shapeType == ShapeType.LINE && shape.vertices.size >= 2) {
                val p0 = Offset(shape.vertices[0].x * scale + panX, shape.vertices[0].y * scale + panY)
                val p1 = Offset(shape.vertices[1].x * scale + panX, shape.vertices[1].y * scale + panY)
                drawHandleCircle(p0)
                drawHandleCircle(p1)
            } else {
                drawRect(
                    color = Color(0xFF2563EB),
                    topLeft = Offset(x - 6f, y - 6f),
                    size = Size(w + 12f, h + 12f),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                )
                drawHandleCircle(Offset(x, y))
                drawHandleCircle(Offset(x + w, y))
                drawHandleCircle(Offset(x, y + h))
                drawHandleCircle(Offset(x + w, y + h))
            }
        }
    }
}

private fun DrawScope.drawHandleCircle(center: Offset) {
    drawCircle(color = Color.White, radius = 11f, center = center)
    drawCircle(color = Color(0xFF2563EB), radius = 11f, center = center, style = Stroke(width = 3.5f))
    drawCircle(color = Color(0xFF2563EB), radius = 4f, center = center)
}

fun DrawScope.drawStrokeSelectionHandles(
    stroke: WhiteboardStroke,
    panX: Float,
    panY: Float,
    scale: Float
) {
    if (stroke.points.isEmpty()) return
    val minX = stroke.points.minOf { it.x } * scale + panX
    val maxX = stroke.points.maxOf { it.x } * scale + panX
    val minY = stroke.points.minOf { it.y } * scale + panY
    val maxY = stroke.points.maxOf { it.y } * scale + panY
    val w = (maxX - minX).coerceAtLeast(12f)
    val h = (maxY - minY).coerceAtLeast(12f)

    drawRect(
        color = Color(0xFF2563EB),
        topLeft = Offset(minX - 6f, minY - 6f),
        size = Size(w + 12f, h + 12f),
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
    )
    drawHandleCircle(Offset(minX, minY))
    drawHandleCircle(Offset(minX + w, minY))
    drawHandleCircle(Offset(minX, minY + h))
    drawHandleCircle(Offset(minX + w, minY + h))
}

fun DrawScope.drawLaserTrail(trail: List<LaserPoint>) {
    if (trail.isEmpty()) return
    val now = System.currentTimeMillis()
    for (p in trail) {
        val age = now - p.timestamp
        if (age < 1500) {
            val alpha = (1.0f - (age / 1500f)).coerceIn(0f, 1f)
            drawCircle(color = Color(0xFFFF1744).copy(alpha = alpha), radius = 10f * alpha, center = Offset(p.x, p.y))
        }
    }
}
