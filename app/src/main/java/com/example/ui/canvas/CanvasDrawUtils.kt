package com.example.ui.canvas

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LaserPoint
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import com.example.model.isLineLike
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil

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
            val points = buildGridPointPositions(startX, startY, step, screenW, screenH)
            drawGridPointsBatched(points, gridColor, (1.5f * scale).coerceIn(1f, 3f))
        }
        CanvasGridType.GRID, CanvasGridType.LINES -> {
            val lines = buildGridLineSegments(
                startX = startX,
                startY = startY,
                step = step,
                screenW = screenW,
                screenH = screenH,
                includeVertical = gridType == CanvasGridType.GRID,
                includeHorizontal = true
            )
            drawGridLinesBatched(lines, gridColor)
        }
        CanvasGridType.DIAMONDS -> {
            val points = buildGridPointPositions(startX, startY, step, screenW, screenH)
            drawGridPointsBatched(points, gridColor.copy(alpha = 0.06f), step * 0.4f)
        }
        else -> {}
    }
}

/** Returns the number of regular grid coordinates in the half-open interval [start, end). */
private fun gridAxisCount(start: Float, end: Float, step: Float): Int {
    if (!start.isFinite() || !end.isFinite() || !step.isFinite() || step <= 0f || end <= start) return 0
    return ceil((end - start) / step).toInt().coerceAtLeast(0)
}

/** Builds the same x-major/y-minor point order used by the original grid loops. */
internal fun buildGridPointPositions(
    startX: Float,
    startY: Float,
    step: Float,
    screenW: Float,
    screenH: Float
): FloatArray {
    val columns = gridAxisCount(startX, screenW, step)
    val rows = gridAxisCount(startY, screenH, step)
    if (columns == 0 || rows == 0) return FloatArray(0)

    val points = FloatArray(columns * rows * 2)
    var offset = 0
    for (column in 0 until columns) {
        val x = startX + column * step
        for (row in 0 until rows) {
            points[offset++] = x
            points[offset++] = startY + row * step
        }
    }
    return points
}

/** Builds vertical segments first and horizontal segments second, matching the original grid order. */
internal fun buildGridLineSegments(
    startX: Float,
    startY: Float,
    step: Float,
    screenW: Float,
    screenH: Float,
    includeVertical: Boolean,
    includeHorizontal: Boolean
): FloatArray {
    val verticalCount = if (includeVertical) gridAxisCount(startX, screenW, step) else 0
    val horizontalCount = if (includeHorizontal) gridAxisCount(startY, screenH, step) else 0
    if (verticalCount == 0 && horizontalCount == 0) return FloatArray(0)

    val segments = FloatArray((verticalCount + horizontalCount) * 4)
    var offset = 0
    for (index in 0 until verticalCount) {
        val x = startX + index * step
        segments[offset++] = x
        segments[offset++] = 0f
        segments[offset++] = x
        segments[offset++] = screenH
    }
    for (index in 0 until horizontalCount) {
        val y = startY + index * step
        segments[offset++] = 0f
        segments[offset++] = y
        segments[offset++] = screenW
        segments[offset++] = y
    }
    return segments
}

private fun DrawScope.drawGridLinesBatched(segments: FloatArray, color: Color) {
    if (segments.isEmpty()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        strokeWidth = 1f
        strokeCap = Paint.Cap.BUTT
        style = Paint.Style.STROKE
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawLines(segments, paint)
    }
}

private fun DrawScope.drawGridPointsBatched(points: FloatArray, color: Color, radius: Float) {
    if (points.isEmpty() || radius <= 0f) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        strokeWidth = radius * 2f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawPoints(points, paint)
    }
}

fun DrawScope.drawStrokeVector(
    stroke: WhiteboardStroke,
    panX: Float,
    panY: Float,
    scale: Float,
    animPhase: Float = 0f,
    isSelected: Boolean = false,
    isDarkBoard: Boolean = false,
    isOpaqueCanvasBackground: Boolean = true,
    handDrawnFactor: Float = com.example.ink.engine.UnifiedBrushPipeline.DEFAULT_HAND_DRAWN_FACTOR,
    viewportW: Float = size.width,
    viewportH: Float = size.height
) {
    AdvancedLineEffects.run {
        drawAdvancedStroke(
            stroke = stroke,
            panX = panX,
            panY = panY,
            scale = scale,
            animPhase = animPhase,
            isSelected = isSelected,
            isDarkBoard = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            handDrawnFactor = handDrawnFactor,
            viewportW = viewportW,
            viewportH = viewportH
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
    isDarkBoard: Boolean = false,
    rotStemPx: Float = 96f,
    isOpaqueCanvasBackground: Boolean = true,
    handDrawnFactor: Float = com.example.ink.engine.UnifiedBrushPipeline.DEFAULT_HAND_DRAWN_FACTOR,
    viewportW: Float = size.width,
    viewportH: Float = size.height
) {
    if (shape.isHiddenByCollapse) return
    val x = shape.x * scale + panX
    val y = shape.y * scale + panY
    val w = shape.width * scale
    val h = shape.height * scale

    drawIntoCanvas { composeCanvas ->
        com.example.ink.engine.UnifiedBrushPipeline.renderShape(
            canvas = composeCanvas.nativeCanvas,
            shape = shape,
            panX = panX,
            panY = panY,
            scale = scale,
            animPhase = animPhase,
            isDarkBoard = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            handDrawnFactor = handDrawnFactor,
            drawLabel = true,
            viewportW = viewportW,
            viewportH = viewportH
        )
    }

    if (isSelected) {
        val effectiveRotation = if (shape.shapeType == ShapeType.LINE || (shape.shapeType.isLineLike && shape.vertices.size >= 2)) 0f else shape.rotation
        rotate(degrees = effectiveRotation, pivot = Offset(x + w / 2f, y + h / 2f)) {
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
                val topCenter = Offset(x + w / 2f, y - 6f)
                val rotHandlePos = Offset(x + w / 2f, y - 6f - rotStemPx)
                drawLine(
                    color = Color(0xFF2563EB),
                    start = topCenter,
                    end = rotHandlePos,
                    strokeWidth = 3f
                )
                drawCircle(color = Color(0xFF2563EB), radius = 4.5f, center = topCenter)
                drawRotationHandleCircle(rotHandlePos)

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
    val radius = 21f
    drawCircle(color = Color(0xFF2563EB).copy(alpha = 0.16f), radius = radius + 6f, center = center)
    drawCircle(color = Color.White, radius = radius, center = center)
    drawCircle(color = Color(0xFF2563EB), radius = radius, center = center, style = Stroke(width = 3.5f))

    // Crisp circular rotation arrow inside the handle
    val arcR = radius * 0.48f
    drawArc(
        color = Color(0xFF2563EB),
        startAngle = -55f,
        sweepAngle = 250f,
        useCenter = false,
        topLeft = Offset(center.x - arcR, center.y - arcR),
        size = Size(arcR * 2f, arcR * 2f),
        style = Stroke(width = 2.8f, cap = StrokeCap.Round)
    )
    // Arrowhead at end of arc (~195 deg, pointing up-left-ish clockwise)
    val tipRad = (-55f) * (PI.toFloat() / 180f)
    val tip = Offset(
        center.x + arcR * cos(tipRad),
        center.y + arcR * sin(tipRad)
    )
    val arrowPath = Path().apply {
        moveTo(tip.x + 2f, tip.y + 1f)
        lineTo(tip.x - 6f, tip.y - 3f)
        lineTo(tip.x - 1f, tip.y - 8f)
        close()
    }
    drawPath(path = arrowPath, color = Color(0xFF2563EB))
}

fun DrawScope.drawStrokeSelectionHandles(
    stroke: WhiteboardStroke,
    panX: Float,
    panY: Float,
    scale: Float,
    rotStemPx: Float = 96f
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
    val topCenter = Offset(minX + w / 2f, minY - 6f)
    val rotHandlePos = Offset(minX + w / 2f, minY - 6f - rotStemPx)
    drawLine(
        color = Color(0xFF2563EB),
        start = topCenter,
        end = rotHandlePos,
        strokeWidth = 3f
    )
    drawCircle(color = Color(0xFF2563EB), radius = 4.5f, center = topCenter)
    drawRotationHandleCircle(rotHandlePos)

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
