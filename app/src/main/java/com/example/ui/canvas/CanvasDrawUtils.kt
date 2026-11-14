package com.example.ui.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LaserPoint
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.isLineLike
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
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
        StrokeStyle.ANIMATED_PULSE -> PathEffect.dashPathEffect(floatArrayOf(width * 3.5f, width * 2f), -(animPhase * width * 5.5f))
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
    isSelected: Boolean = false,
    isDarkBoard: Boolean = false
) {
    if (shape.isHiddenByCollapse) return
    val x = shape.x * scale + panX
    val y = shape.y * scale + panY
    val w = shape.width * scale
    val h = shape.height * scale

    val strokeColor = parseColor(shape.strokeColorHex).let {
        it.copy(alpha = (it.alpha * shape.opacity.coerceIn(0.15f, 1.0f)))
    }
    val fillColor = parseColor(shape.fillColorHex)
    val strokeW = (shape.strokeWidth * scale).coerceAtLeast(1.5f)
    val pathEffect = getPathEffect(shape.strokeStyle, strokeW, animPhase)

    val effectiveRotation = if (shape.shapeType == ShapeType.LINE) 0f else shape.rotation
    rotate(degrees = effectiveRotation, pivot = Offset(x + w / 2f, y + h / 2f)) {
        when (shape.shapeType) {
            ShapeType.RECTANGLE -> {
                val path = Path().apply {
                    addRect(Rect(x, y, x + w, y + h))
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
            ShapeType.ROUNDED_RECT -> {
                val cr = CornerRadius(16f * scale, 16f * scale)
                val path = Path().apply {
                    addRoundRect(RoundRect(Rect(x, y, x + w, y + h), cr))
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
            ShapeType.CIRCLE -> {
                val path = Path().apply {
                    addOval(Rect(x, y, x + w, y + h))
                }
                if (shape.isFilled) drawPath(path = path, color = fillColor)
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
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
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
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
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
            ShapeType.LINE -> {
                AdvancedLineEffects.run {
                    drawAdvancedLineShape(
                        shape = shape,
                        panX = panX,
                        panY = panY,
                        scale = scale,
                        animPhase = animPhase,
                        isSelected = false,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
            ShapeType.ARROW -> {
                val lineShape = if (shape.vertices.size >= 2) shape else shape.copy(
                    vertices = listOf(StrokePoint(x, y + h / 2f), StrokePoint(x + w, y + h / 2f))
                )
                AdvancedLineEffects.run {
                    drawAdvancedLineShape(
                        shape = lineShape.copy(capStyle = LineCapStyle.END_ARROW),
                        panX = panX,
                        panY = panY,
                        scale = scale,
                        animPhase = animPhase,
                        isSelected = false,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
            ShapeType.DOUBLE_ARROW -> {
                val lineShape = if (shape.vertices.size >= 2) shape else shape.copy(
                    vertices = listOf(StrokePoint(x, y + h / 2f), StrokePoint(x + w, y + h / 2f))
                )
                AdvancedLineEffects.run {
                    drawAdvancedLineShape(
                        shape = lineShape.copy(capStyle = LineCapStyle.BOTH_ARROWS),
                        panX = panX,
                        panY = panY,
                        scale = scale,
                        animPhase = animPhase,
                        isSelected = false,
                        isDarkBoard = isDarkBoard
                    )
                }
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
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
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
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = path,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
            ShapeType.CLOUD_CALLOUT -> {
                val bubbleH = h * 0.78f
                val cr = CornerRadius(bubbleH * 0.35f, bubbleH * 0.35f)
                val bubblePath = Path().apply {
                    addRoundRect(RoundRect(Rect(x, y, x + w, y + bubbleH), cr))
                }
                val tailPath = Path().apply {
                    moveTo(x + w * 0.26f, y + bubbleH - 1f)
                    lineTo(x + w * 0.16f, y + h)
                    lineTo(x + w * 0.42f, y + bubbleH - 1f)
                    close()
                }
                if (shape.isFilled) {
                    drawPath(path = bubblePath, color = fillColor)
                    drawPath(path = tailPath, color = fillColor)
                }
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = bubblePath,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                    drawStyledOutlinePath(
                        path = tailPath,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
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
                val topOvalPath = Path().apply {
                    addOval(Rect(x, y, x + w, y + capH))
                }
                if (shape.isFilled) {
                    drawPath(path = bodyPath, color = fillColor)
                    drawPath(path = topOvalPath, color = fillColor)
                }
                AdvancedLineEffects.run {
                    drawStyledOutlinePath(
                        path = bodyPath,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                    drawStyledOutlinePath(
                        path = topOvalPath,
                        style = shape.strokeStyle,
                        color = strokeColor,
                        strokeWidth = strokeW,
                        animPhase = animPhase,
                        isDarkBoard = isDarkBoard
                    )
                }
            }
        }

        if (shape.label.isNotBlank() && !shape.shapeType.isLineLike) {
            drawIntoCanvas { canvas ->
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (shape.isFilled) {
                        Color(0xFF0F172A).toArgb()
                    } else if (isDarkBoard) {
                        Color.White.toArgb()
                    } else {
                        strokeColor.toArgb()
                    }
                    textSize = (15.5f * scale).coerceIn(10f, 34f)
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val fm = textPaint.fontMetrics
                val textY = y + h / 2f - (fm.ascent + fm.descent) / 2f
                canvas.nativeCanvas.drawText(shape.label, x + w / 2f, textY, textPaint)
            }
        }

        if (isSelected) {
            if (shape.shapeType.isLineLike && shape.vertices.size >= 2) {
                val p0 = Offset(shape.vertices.first().x * scale + panX, shape.vertices.first().y * scale + panY)
                val p1 = Offset(shape.vertices.last().x * scale + panX, shape.vertices.last().y * scale + panY)

                val rot0 = p0 + Offset(0f, -32f)
                val rot1 = p1 + Offset(0f, -32f)

                drawLine(color = Color(0xFF2563EB), start = p0, end = rot0, strokeWidth = 2f)
                drawLine(color = Color(0xFF2563EB), start = p1, end = rot1, strokeWidth = 2f)

                drawHandleCircle(p0)
                drawRotationHandleCircle(rot0)

                drawHandleCircle(p1)
                drawRotationHandleCircle(rot1)
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

private fun DrawScope.drawRotationHandleCircle(center: Offset) {
    drawCircle(color = Color.White, radius = 10f, center = center)
    drawCircle(color = Color(0xFF2563EB), radius = 10f, center = center, style = Stroke(width = 3f))
    drawCircle(color = Color(0xFF2563EB), radius = 3.5f, center = center)
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
