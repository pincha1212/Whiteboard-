package com.example.ui.canvas

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
