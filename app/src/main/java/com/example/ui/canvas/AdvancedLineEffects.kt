package com.example.ui.canvas

import android.graphics.PathMeasure
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Advanced Line & Stroke Rendering Engine.
 * Implements rich line patterns (Dot-dash, Double, Zigzag, Wavy, Square wave, Railway, Continuous arrows),
 * organic pressure dynamics (Tapered, Calligraphic, Sketch),
 * lighting & textures (Neon glow, Dynamic gradient, Chalk/Watercolor, Animated pulse),
 * and arrow endpoints (End arrow, Start arrow, Both arrows).
 */
object AdvancedLineEffects {

    fun DrawScope.drawAdvancedStroke(
        stroke: WhiteboardStroke,
        panX: Float,
        panY: Float,
        scale: Float,
        animPhase: Float = 0f,
        isSelected: Boolean = false,
        isDarkBoard: Boolean = false
    ) {
        if (stroke.points.size < 2) return

        val rawScreenPoints = stroke.points.map { Offset(it.x * scale + panX, it.y * scale + panY) }
        val screenPoints = deduplicateOffsets(rawScreenPoints, minStep = 0.75f)
        if (screenPoints.size < 2) return
        val scaledWidth = (stroke.width * scale).coerceAtLeast(1.5f)
        val baseColor = parseColor(stroke.colorHex)
        val alpha = stroke.opacity.coerceIn(0.15f, 1.0f)
        val effectiveColor = baseColor.copy(alpha = alpha)

        val arrowSize = (scaledWidth * 3.0f + 10f).coerceIn(12f, 36f)
        val bodyPoints = if (stroke.capStyle != LineCapStyle.NONE) {
            trimPointsForArrowCaps(screenPoints, stroke.capStyle, arrowSize * 0.62f)
        } else {
            screenPoints
        }

        when (stroke.strokeStyle) {
            StrokeStyle.DOUBLE -> {
                drawDoubleStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.ZIGZAG -> {
                drawZigzagStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.WAVY -> {
                drawWavyStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.SQUARE_WAVE -> {
                drawSquareWaveStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.RAILWAY -> {
                drawRailwayStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.ARROW_PATTERN -> {
                drawArrowPatternStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.TAPERED -> {
                drawTaperedStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.CALLIGRAPHIC -> {
                drawCalligraphicStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.SKETCH -> {
                drawSketchStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.NEON_GLOW -> {
                drawNeonStroke(bodyPoints, scaledWidth, baseColor, isDarkBoard)
            }
            StrokeStyle.DYNAMIC_GRADIENT -> {
                drawDynamicGradientStroke(bodyPoints, scaledWidth, baseColor, stroke.isRainbow)
            }
            StrokeStyle.CHALK_TEXTURE -> {
                drawChalkStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.ANIMATED_PULSE -> {
                drawAnimatedPulseStroke(bodyPoints, scaledWidth, effectiveColor, animPhase)
            }
            StrokeStyle.DASH_DOT -> {
                drawDashDotStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.DASHED -> {
                drawDashedStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.DOTTED -> {
                drawDottedStroke(bodyPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.SOLID -> {
                drawStandardSmoothStroke(bodyPoints, scaledWidth, effectiveColor, stroke.brushType, stroke.isHighlighter, stroke.isRainbow, isDarkBoard)
            }
        }

        // Draw arrowheads at endpoints if requested
        if (stroke.strokeStyle == StrokeStyle.NEON_GLOW && stroke.capStyle != LineCapStyle.NONE) {
            val haloAlpha = if (isDarkBoard) 0.42f else 0.28f
            drawStrokeArrowCaps(screenPoints, stroke.capStyle, scaledWidth, arrowSize * 1.32f, baseColor.copy(alpha = haloAlpha))
            drawStrokeArrowCaps(screenPoints, stroke.capStyle, scaledWidth, arrowSize, baseColor.copy(alpha = 0.94f))
            drawStrokeArrowCaps(screenPoints, stroke.capStyle, scaledWidth, arrowSize * 0.52f, Color.White.copy(alpha = 0.88f))
        } else {
            drawStrokeArrowCaps(screenPoints, stroke.capStyle, scaledWidth, arrowSize, effectiveColor)
        }

        if (isSelected) {
            val selPath = buildSmoothPath(screenPoints)
            drawPath(
                path = selPath,
                color = Color(0xFF2563EB).copy(alpha = 0.35f),
                style = Stroke(width = scaledWidth + 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }

    fun DrawScope.drawAdvancedLineShape(
        shape: ShapeItem,
        panX: Float,
        panY: Float,
        scale: Float,
        animPhase: Float = 0f,
        isSelected: Boolean = false,
        isDarkBoard: Boolean = false
    ) {
        val worldPoints = if (shape.vertices.size >= 2) {
            shape.vertices
        } else {
            listOf(
                StrokePoint(shape.x, shape.y),
                StrokePoint(shape.x + shape.width, shape.y + shape.height)
            )
        }

        val strokeTemp = WhiteboardStroke(
            points = worldPoints,
            colorHex = shape.strokeColorHex,
            width = shape.strokeWidth,
            strokeStyle = shape.strokeStyle,
            capStyle = shape.capStyle,
            isRainbow = shape.isRainbow,
            opacity = shape.opacity
        )

        drawAdvancedStroke(
            stroke = strokeTemp,
            panX = panX,
            panY = panY,
            scale = scale,
            animPhase = animPhase,
            isSelected = isSelected,
            isDarkBoard = isDarkBoard
        )
    }

    fun DrawScope.drawStylePreview(
        style: StrokeStyle,
        capStyle: LineCapStyle,
        colorHex: String,
        animPhase: Float = 0f,
        isDarkBoard: Boolean = false
    ) {
        val w = size.width
        val h = size.height
        val padX = (w * 0.14f).coerceAtLeast(8f)
        val midY = h * 0.5f
        val amp = (h * 0.18f).coerceIn(2f, 6f)
        val previewPoints = listOf(
            StrokePoint(padX, midY + amp * 0.35f),
            StrokePoint(w * 0.36f, midY - amp * 0.55f),
            StrokePoint(w * 0.66f, midY + amp * 0.55f),
            StrokePoint(w - padX, midY - amp * 0.25f)
        )
        val previewStroke = WhiteboardStroke(
            points = previewPoints,
            colorHex = colorHex,
            width = 3.6f,
            strokeStyle = style,
            capStyle = capStyle
        )
        drawAdvancedStroke(
            stroke = previewStroke,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            animPhase = animPhase,
            isSelected = false,
            isDarkBoard = isDarkBoard
        )
    }

    /**
     * Renders any Compose Path outline (closed shapes, multi-contour geometry, or open paths)
     * with the full set of 17 StrokeStyles so preset shapes match freehand/line styles consistently.
     */
    fun DrawScope.drawStyledOutlinePath(
        path: Path,
        style: StrokeStyle,
        color: Color,
        strokeWidth: Float,
        animPhase: Float = 0f,
        isDarkBoard: Boolean = false
    ) {
        val w = strokeWidth.coerceAtLeast(1.5f)
        when (style) {
            StrokeStyle.SOLID -> {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            StrokeStyle.DASHED -> {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = w,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3f, w * 2f), 0f)
                    )
                )
            }
            StrokeStyle.DOTTED -> {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = w,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(w, w * 1.5f), 0f)
                    )
                )
            }
            StrokeStyle.DASH_DOT -> {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = w,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 4f, w * 1.6f, w * 1.2f, w * 1.6f), 0f)
                    )
                )
            }
            StrokeStyle.ANIMATED_PULSE -> {
                val dashLen = w * 3.5f
                val gapLen = w * 2.0f
                val phaseOffset = -(animPhase * (dashLen + gapLen))
                val pulseAlpha = 0.20f + 0.14f * sin(animPhase * 2f * PI.toFloat())
                drawPath(
                    path = path,
                    color = color.copy(alpha = (color.alpha * pulseAlpha).coerceIn(0.08f, 0.45f)),
                    style = Stroke(width = w * 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = w,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), phaseOffset)
                    )
                )
            }
            StrokeStyle.NEON_GLOW -> {
                val glowColor = color.copy(alpha = 1.0f)
                drawPath(
                    path = path,
                    color = glowColor.copy(alpha = if (isDarkBoard) 0.22f else 0.16f),
                    style = Stroke(width = w * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = glowColor.copy(alpha = if (isDarkBoard) 0.45f else 0.32f),
                    style = Stroke(width = w * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = glowColor.copy(alpha = 0.88f),
                    style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.95f),
                    style = Stroke(width = (w * 0.32f).coerceAtLeast(1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            StrokeStyle.DYNAMIC_GRADIENT -> {
                val bounds = path.getBounds()
                val startOffset = Offset(bounds.left, bounds.top)
                val endOffset = if (hypot(bounds.width, bounds.height) < 2f) {
                    Offset(bounds.left + 4f, bounds.top + 4f)
                } else {
                    Offset(bounds.right, bounds.bottom)
                }
                val brush = Brush.linearGradient(
                    colors = listOf(
                        color,
                        Color(0xFF38BDF8),
                        Color(0xFFA855F7),
                        Color(0xFFFB7185),
                        Color(0xFFFACC15)
                    ),
                    start = startOffset,
                    end = endOffset
                )
                drawPath(
                    path = path,
                    brush = brush,
                    style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            StrokeStyle.SKETCH -> {
                drawPath(
                    path = path,
                    color = color.copy(alpha = color.alpha * 0.75f),
                    style = Stroke(width = (w * 0.45f).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                val contours = extractPathContours(path, stepHint = (w * 0.8f).coerceIn(3f, 8f))
                contours.forEach { contour ->
                    val j1Pts = contour.points.mapIndexed { idx, p ->
                        val jitter = if (idx % 2 == 0) 1.5f else -1.2f
                        Offset(p.x + jitter, p.y - jitter * 0.8f)
                    }
                    val j2Pts = contour.points.mapIndexed { idx, p ->
                        val jitter = if (idx % 2 == 1) 1.3f else -1.5f
                        Offset(p.x - jitter * 0.8f, p.y + jitter)
                    }
                    val p1 = if (contour.isClosed) buildClosedSmoothPath(j1Pts) else buildSmoothPath(j1Pts)
                    val p2 = if (contour.isClosed) buildClosedSmoothPath(j2Pts) else buildSmoothPath(j2Pts)
                    drawPath(
                        path = p1,
                        color = color.copy(alpha = color.alpha * 0.50f),
                        style = Stroke(width = (w * 0.35f).coerceAtLeast(0.9f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = p2,
                        color = color.copy(alpha = color.alpha * 0.45f),
                        style = Stroke(width = (w * 0.30f).coerceAtLeast(0.8f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            StrokeStyle.CHALK_TEXTURE -> {
                drawPath(
                    path = path,
                    color = color.copy(alpha = color.alpha * 0.24f),
                    style = Stroke(width = w * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = color.copy(alpha = color.alpha * 0.45f),
                    style = Stroke(width = w * 0.88f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = color.copy(alpha = color.alpha * 0.55f),
                    style = Stroke(
                        width = w * 0.65f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(max(2.5f, w * 0.55f), max(1.8f, w * 0.35f)), 0f)
                    )
                )
                val contours = extractPathContours(path, stepHint = (w * 0.9f).coerceIn(4f, 12f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val samples = resamplePolyline(loopPts, (w * 0.9f).coerceIn(4f, 12f))
                    samples.forEachIndexed { idx, s ->
                        val shift = when (idx % 3) {
                            0 -> w * 0.36f
                            1 -> -w * 0.34f
                            else -> w * 0.14f
                        }
                        drawCircle(
                            color = color.copy(alpha = color.alpha * if (idx % 2 == 0) 0.44f else 0.28f),
                            radius = (w * 0.24f).coerceAtLeast(1f),
                            center = Offset(s.pos.x + s.normal.x * shift, s.pos.y + s.normal.y * shift)
                        )
                    }
                }
            }
            StrokeStyle.ARROW_PATTERN -> {
                drawPath(
                    path = path,
                    color = color.copy(alpha = (color.alpha * 0.65f).coerceIn(0.15f, 1f)),
                    style = Stroke(width = (w * 0.45f).coerceAtLeast(1.3f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                val contours = extractPathContours(path, stepHint = (w * 0.65f).coerceIn(2.5f, 7f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val contourLen = polylineLength(loopPts)
                    if (contourLen < 4f) return@forEach
                    val spacing = min((w * 2.6f).coerceIn(14f, 44f), (contourLen / 6f).coerceAtLeast(6f))
                    val samples = resamplePolyline(loopPts, spacing)
                    val wingLen = min((w * 0.95f).coerceIn(6f, 26f), (contourLen / 16f).coerceAtLeast(3.2f))
                    val wingWidth = (w * 0.45f).coerceAtLeast(1.3f)
                    for (i in 1 until samples.size) {
                        if (i == samples.lastIndex) {
                            val distPrev = hypot(samples[i].pos.x - samples[i - 1].pos.x, samples[i].pos.y - samples[i - 1].pos.y)
                            if (distPrev < spacing * 0.45f) continue
                        }
                        val s = samples[i]
                        val wing1 = Offset(
                            s.pos.x - s.tangent.x * wingLen + s.normal.x * (wingLen * 0.7f),
                            s.pos.y - s.tangent.y * wingLen + s.normal.y * (wingLen * 0.7f)
                        )
                        val wing2 = Offset(
                            s.pos.x - s.tangent.x * wingLen - s.normal.x * (wingLen * 0.7f),
                            s.pos.y - s.tangent.y * wingLen - s.normal.y * (wingLen * 0.7f)
                        )
                        val chevronPath = Path().apply {
                            moveTo(wing1.x, wing1.y)
                            lineTo(s.pos.x, s.pos.y)
                            lineTo(wing2.x, wing2.y)
                        }
                        drawPath(
                            path = chevronPath,
                            color = color,
                            style = Stroke(width = wingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }
            StrokeStyle.CALLIGRAPHIC -> {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = (w * 0.22f).coerceAtLeast(1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                val contours = extractPathContours(path, stepHint = (w * 0.4f).coerceIn(2.5f, 7f))
                val angleRad = 45f * (PI.toFloat() / 180f)
                val halfW = (w * 0.62f).coerceAtLeast(1.6f)
                val k = Offset(cos(angleRad) * halfW, sin(angleRad) * halfW)
                contours.forEach { contour ->
                    val pts = contour.points
                    if (pts.size < 2) return@forEach
                    val count = if (contour.isClosed) pts.size else pts.lastIndex
                    for (i in 0 until count) {
                        val p0 = pts[i]
                        val p1 = pts[(i + 1) % pts.size]
                        val quad = Path().apply {
                            moveTo(p0.x - k.x, p0.y - k.y)
                            lineTo(p0.x + k.x, p0.y + k.y)
                            lineTo(p1.x + k.x, p1.y + k.y)
                            lineTo(p1.x - k.x, p1.y - k.y)
                            close()
                        }
                        drawPath(path = quad, color = color)
                    }
                }
            }
            StrokeStyle.DOUBLE -> {
                val contours = extractPathContours(path, stepHint = (w * 0.65f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    drawPath(path = path, color = color, style = Stroke(width = w))
                    return
                }
                val offsetDist = (w * 0.65f).coerceAtLeast(if (w < 3f) 1.8f else 2.5f)
                val subWidth = (w * 0.42f).coerceAtLeast(1f)
                val subStyle = Stroke(width = subWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                contours.forEach { contour ->
                    val (pathA, pathB) = if (contour.isClosed) {
                        buildClosedParallelPaths(contour.points, offsetDist)
                    } else {
                        buildParallelPaths(contour.points, offsetDist)
                    }
                    drawPath(path = pathA, color = color, style = subStyle)
                    drawPath(path = pathB, color = color, style = subStyle)
                }
            }
            StrokeStyle.RAILWAY -> {
                val contours = extractPathContours(path, stepHint = (w * 0.65f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    drawPath(path = path, color = color, style = Stroke(width = w))
                    return
                }
                val railOffset = (w * 0.6f).coerceAtLeast(if (w < 3f) 2.2f else 4f)
                val railWidth = (w * 0.32f).coerceAtLeast(1.2f)
                val railStyle = Stroke(width = railWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val tieLen = railOffset * 1.55f
                val tieWidth = (w * 0.38f).coerceAtLeast(1.2f)
                contours.forEach { contour ->
                    val (railA, railB) = if (contour.isClosed) {
                        buildClosedParallelPaths(contour.points, railOffset)
                    } else {
                        buildParallelPaths(contour.points, railOffset)
                    }
                    drawPath(path = railA, color = color, style = railStyle)
                    drawPath(path = railB, color = color, style = railStyle)

                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val contourLen = polylineLength(loopPts)
                    if (contourLen < 4f) return@forEach
                    val tieSpacing = min((w * 2.2f).coerceIn(10f, 36f), (contourLen / 7f).coerceAtLeast(5f))
                    val samples = resamplePolyline(loopPts, tieSpacing)
                    for (i in samples.indices) {
                        if (i == samples.lastIndex && i > 0) {
                            val distPrev = hypot(samples[i].pos.x - samples[i - 1].pos.x, samples[i].pos.y - samples[i - 1].pos.y)
                            val distStart = if (contour.isClosed) hypot(samples[i].pos.x - samples[0].pos.x, samples[i].pos.y - samples[0].pos.y) else Float.MAX_VALUE
                            if (distPrev < tieSpacing * 0.45f || distStart < tieSpacing * 0.55f) continue
                        }
                        val s = samples[i]
                        drawLine(
                            color = color,
                            start = Offset(s.pos.x - s.normal.x * tieLen, s.pos.y - s.normal.y * tieLen),
                            end = Offset(s.pos.x + s.normal.x * tieLen, s.pos.y + s.normal.y * tieLen),
                            strokeWidth = tieWidth,
                            cap = StrokeCap.Square
                        )
                    }
                }
            }
            StrokeStyle.ZIGZAG -> {
                val contours = extractPathContours(path, stepHint = (w * 0.6f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    drawPath(path = path, color = color, style = Stroke(width = w))
                    return
                }
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val contourLen = polylineLength(loopPts)
                    if (contourLen < 4f) return@forEach
                    val stepLen = min((w * 2.2f).coerceIn(10f, 40f), (contourLen / 8f).coerceAtLeast(4f))
                    val amplitude = min((w * 0.9f).coerceIn(4f, 28f), (contourLen / 20f).coerceAtLeast(2.2f))
                    val samples = resamplePolyline(loopPts, stepLen * 0.5f)
                    if (samples.size < 2) return@forEach
                    val zigPath = Path().apply {
                        moveTo(samples[0].pos.x, samples[0].pos.y)
                        for (i in 1 until samples.lastIndex) {
                            val s = samples[i]
                            val sign = if (i % 2 == 1) 1f else -1f
                            lineTo(s.pos.x + s.normal.x * (amplitude * sign), s.pos.y + s.normal.y * (amplitude * sign))
                        }
                        if (contour.isClosed) close() else lineTo(samples.last().pos.x, samples.last().pos.y)
                    }
                    drawPath(
                        path = zigPath,
                        color = color,
                        style = Stroke(width = (w * 0.65f).coerceAtLeast(1.3f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            StrokeStyle.WAVY -> {
                val contours = extractPathContours(path, stepHint = (w * 0.5f).coerceIn(2.5f, 6f))
                if (contours.isEmpty()) {
                    drawPath(path = path, color = color, style = Stroke(width = w))
                    return
                }
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val contourLen = polylineLength(loopPts)
                    if (contourLen < 4f) return@forEach
                    val rawWavelength = min((w * 4.2f).coerceIn(18f, 70f), (contourLen / 5f).coerceAtLeast(8f))
                    val stepLen = (rawWavelength / 5.2f).coerceIn(3f, 16f)
                    val amplitude = min((w * 0.85f).coerceIn(4f, 26f), (contourLen / 20f).coerceAtLeast(2.2f))
                    val samples = resamplePolyline(loopPts, stepLen)
                    if (samples.size < 2) return@forEach
                    val totalSampleLen = polylineLength(samples.map { it.pos }).coerceAtLeast(1f)
                    val closedCycles = max(2, (totalSampleLen / rawWavelength).roundToInt())
                    val wavyPath = Path().apply {
                        moveTo(samples[0].pos.x, samples[0].pos.y)
                        var accumulatedDist = 0f
                        val endIdx = if (contour.isClosed) samples.lastIndex else samples.size
                        for (i in 1 until endIdx) {
                            accumulatedDist += hypot(samples[i].pos.x - samples[i - 1].pos.x, samples[i].pos.y - samples[i - 1].pos.y)
                            val phase = if (contour.isClosed) {
                                (accumulatedDist / totalSampleLen) * (closedCycles * 2f * PI.toFloat())
                            } else {
                                (accumulatedDist / rawWavelength) * (2f * PI.toFloat())
                            }
                            val waveOffset = sin(phase) * amplitude
                            val s = samples[i]
                            lineTo(s.pos.x + s.normal.x * waveOffset, s.pos.y + s.normal.y * waveOffset)
                        }
                        if (contour.isClosed) close()
                    }
                    drawPath(
                        path = wavyPath,
                        color = color,
                        style = Stroke(width = (w * 0.72f).coerceAtLeast(1.3f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            StrokeStyle.SQUARE_WAVE -> {
                val contours = extractPathContours(path, stepHint = (w * 0.6f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    drawPath(path = path, color = color, style = Stroke(width = w))
                    return
                }
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val contourLen = polylineLength(loopPts)
                    if (contourLen < 4f) return@forEach
                    val blockLen = min((w * 2.5f).coerceIn(12f, 44f), (contourLen / 6f).coerceAtLeast(5f))
                    val amplitude = min((w * 0.85f).coerceIn(4f, 26f), (contourLen / 20f).coerceAtLeast(2.2f))
                    val samples = resamplePolyline(loopPts, blockLen)
                    if (samples.size < 2) return@forEach
                    val sqPath = Path().apply {
                        moveTo(samples[0].pos.x, samples[0].pos.y)
                        for (i in 0 until samples.lastIndex) {
                            val cur = samples[i]
                            val next = samples[i + 1]
                            val sign = if (i % 2 == 0) 1f else -1f
                            val up1 = Offset(cur.pos.x + cur.normal.x * (amplitude * sign), cur.pos.y + cur.normal.y * (amplitude * sign))
                            val up2 = Offset(next.pos.x + next.normal.x * (amplitude * sign), next.pos.y + next.normal.y * (amplitude * sign))
                            lineTo(up1.x, up1.y)
                            lineTo(up2.x, up2.y)
                            lineTo(next.pos.x, next.pos.y)
                        }
                        if (contour.isClosed) close()
                    }
                    drawPath(
                        path = sqPath,
                        color = color,
                        style = Stroke(width = (w * 0.68f).coerceAtLeast(1.3f), cap = StrokeCap.Square, join = StrokeJoin.Miter)
                    )
                }
            }
            StrokeStyle.TAPERED -> {
                val contours = extractPathContours(path, stepHint = (w * 0.65f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    drawPath(path = path, color = color, style = Stroke(width = w))
                    return
                }
                contours.forEach { contour ->
                    if (!contour.isClosed) {
                        drawTaperedStroke(contour.points, w, color)
                    } else {
                        val pts = contour.points
                        val n = pts.size
                        if (n < 3) return@forEach
                        val loopLen = (polylineLength(pts) + hypot(pts.first().x - pts.last().x, pts.first().y - pts.last().y)).coerceAtLeast(1f)
                        var acc = 0f
                        val left = ArrayList<Offset>(n)
                        val right = ArrayList<Offset>(n)
                        for (i in 0 until n) {
                            if (i > 0) acc += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
                            val t = (acc / loopLen).coerceIn(0f, 1f)
                            val pressure = (0.65f + 0.45f * sin(t * 2f * PI.toFloat()) + 0.08f * sin(t * 6f * PI.toFloat())).coerceIn(0.20f, 1.15f)
                            val halfW = (w * pressure * 0.52f).coerceAtLeast(0.8f)
                            val prev = pts[(i - 1 + n) % n]
                            val next = pts[(i + 1) % n]
                            val dx = next.x - prev.x
                            val dy = next.y - prev.y
                            val len = hypot(dx, dy).coerceAtLeast(0.001f)
                            val normal = Offset(-dy / len, dx / len)
                            left.add(Offset(pts[i].x + normal.x * halfW, pts[i].y + normal.y * halfW))
                            right.add(Offset(pts[i].x - normal.x * halfW, pts[i].y - normal.y * halfW))
                        }
                        for (i in 0 until n) {
                            val nextIdx = (i + 1) % n
                            val quad = Path().apply {
                                moveTo(left[i].x, left[i].y)
                                lineTo(left[nextIdx].x, left[nextIdx].y)
                                lineTo(right[nextIdx].x, right[nextIdx].y)
                                lineTo(right[i].x, right[i].y)
                                close()
                            }
                            drawPath(path = quad, color = color)
                        }
                    }
                }
            }
        }
    }

    // --- Line Styles Renderers ---

    private fun DrawScope.drawStandardSmoothStroke(
        points: List<Offset>,
        width: Float,
        color: Color,
        brushType: StrokeBrushType,
        isHighlighter: Boolean,
        isRainbow: Boolean,
        isDarkBoard: Boolean
    ) {
        if (brushType == StrokeBrushType.SOFT_BRUSH && !isHighlighter && !isRainbow) {
            drawSoftBrushStroke(points, width, color)
            return
        }
        val path = buildSmoothPath(points)
        val cap = if (isHighlighter || brushType == StrokeBrushType.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round
        val style = Stroke(width = width, cap = cap, join = StrokeJoin.Round)

        if (isRainbow) {
            val rainbowBrush = Brush.linearGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Magenta))
            drawPath(path = path, brush = rainbowBrush, style = style)
        } else if (isHighlighter || brushType == StrokeBrushType.HIGHLIGHTER) {
            val blend = if (isDarkBoard) BlendMode.SrcOver else BlendMode.Multiply
            drawPath(path = path, color = color, style = style, blendMode = blend)
        } else {
            drawPath(path = path, color = color, style = style)
        }
    }

    private fun DrawScope.drawSoftBrushStroke(points: List<Offset>, width: Float, color: Color) {
        if (points.size < 2) return
        // 1. Soft translucent wash body
        val bodyPath = buildSmoothPath(points)
        drawPath(
            path = bodyPath,
            color = color.copy(alpha = color.alpha * 0.38f),
            style = Stroke(width = width * 1.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 2. Bristle strands (Cerdas del pincel) with fanned/frayed tips at start and end
        val n = points.size
        val centerPts = points
        val innerLeft = mutableListOf<Offset>()
        val innerRight = mutableListOf<Offset>()
        val outerLeft = mutableListOf<Offset>()
        val outerRight = mutableListOf<Offset>()

        for (i in 0 until n) {
            val t = i / (n - 1).coerceAtLeast(1).toFloat()
            // Fray/spread factor: wider and more separated at start and end (tapered bristle tips)
            val tipFactor = 1.0f + 0.55f * (1.0f - sin(t * PI.toFloat()).toFloat())
            val spread = 0.35f + 0.65f * tipFactor

            val tangent = if (i < n - 1) {
                val dx = points[i + 1].x - points[i].x
                val dy = points[i + 1].y - points[i].y
                val l = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / l, dy / l)
            } else {
                val dx = points[i].x - points[i - 1].x
                val dy = points[i].y - points[i - 1].y
                val l = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / l, dy / l)
            }
            val normal = Offset(-tangent.y, tangent.x)

            val innerDist = width * 0.30f * spread
            val outerDist = width * 0.65f * spread

            innerLeft.add(Offset(points[i].x + normal.x * innerDist, points[i].y + normal.y * innerDist))
            innerRight.add(Offset(points[i].x - normal.x * innerDist, points[i].y - normal.y * innerDist))
            outerLeft.add(Offset(points[i].x + normal.x * outerDist, points[i].y + normal.y * outerDist))
            outerRight.add(Offset(points[i].x - normal.x * outerDist, points[i].y - normal.y * outerDist))
        }

        val innerW = (width * 0.35f).coerceAtLeast(1.1f)
        val outerW = (width * 0.20f).coerceAtLeast(0.8f)
        val centerW = (width * 0.48f).coerceAtLeast(1.4f)

        // Draw core bristle
        drawPath(
            path = buildSmoothPath(centerPts),
            color = color.copy(alpha = color.alpha * 0.94f),
            style = Stroke(width = centerW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Draw inner bristle strands
        drawPath(
            path = buildSmoothPath(innerLeft),
            color = color.copy(alpha = color.alpha * 0.70f),
            style = Stroke(width = innerW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = buildSmoothPath(innerRight),
            color = color.copy(alpha = color.alpha * 0.70f),
            style = Stroke(width = innerW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Draw outer bristle hairs (feathery bristle ends)
        drawPath(
            path = buildSmoothPath(outerLeft),
            color = color.copy(alpha = color.alpha * 0.42f),
            style = Stroke(width = outerW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = buildSmoothPath(outerRight),
            color = color.copy(alpha = color.alpha * 0.42f),
            style = Stroke(width = outerW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    private fun DrawScope.drawDoubleStroke(points: List<Offset>, width: Float, color: Color) {
        val offsetDist = (width * 0.65f).coerceAtLeast(2.5f)
        val subWidth = (width * 0.42f).coerceAtLeast(1f)

        val (pathA, pathB) = buildParallelPaths(points, offsetDist)
        val style = Stroke(width = subWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = pathA, color = color, style = style)
        drawPath(path = pathB, color = color, style = style)
    }

    private fun DrawScope.drawZigzagStroke(points: List<Offset>, width: Float, color: Color) {
        val path = Path()
        val stepLen = (width * 2.2f).coerceIn(12f, 40f)
        val amplitude = (width * 0.9f).coerceIn(6f, 28f)

        val samples = resamplePolyline(points, stepLen * 0.5f)
        if (samples.size < 2) return

        path.moveTo(samples[0].pos.x, samples[0].pos.y)
        for (i in 1 until samples.lastIndex) {
            val s = samples[i]
            val sign = if (i % 2 == 1) 1f else -1f
            val zx = s.pos.x + s.normal.x * (amplitude * sign)
            val zy = s.pos.y + s.normal.y * (amplitude * sign)
            path.lineTo(zx, zy)
        }
        val last = samples.last().pos
        path.lineTo(last.x, last.y)

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = (width * 0.65f).coerceAtLeast(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    private fun DrawScope.drawWavyStroke(points: List<Offset>, width: Float, color: Color) {
        val path = Path()
        val stepLen = (width * 0.8f).coerceIn(6f, 18f)
        val wavelength = (width * 4.2f).coerceIn(24f, 70f)
        val amplitude = (width * 0.85f).coerceIn(5f, 26f)

        val samples = resamplePolyline(points, stepLen)
        if (samples.size < 2) return

        path.moveTo(samples[0].pos.x, samples[0].pos.y)
        var accumulatedDist = 0f
        for (i in 1 until samples.size) {
            val pPrev = samples[i - 1].pos
            val s = samples[i]
            accumulatedDist += hypot(s.pos.x - pPrev.x, s.pos.y - pPrev.y)
            val waveOffset = sin((accumulatedDist / wavelength) * (2f * PI.toFloat())) * amplitude
            val wx = s.pos.x + s.normal.x * waveOffset
            val wy = s.pos.y + s.normal.y * waveOffset
            path.lineTo(wx, wy)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = (width * 0.72f).coerceAtLeast(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    private fun DrawScope.drawSquareWaveStroke(points: List<Offset>, width: Float, color: Color) {
        val path = Path()
        val blockLen = (width * 2.5f).coerceIn(16f, 44f)
        val amplitude = (width * 0.85f).coerceIn(6f, 26f)

        val samples = resamplePolyline(points, blockLen)
        if (samples.size < 2) return

        path.moveTo(samples[0].pos.x, samples[0].pos.y)
        for (i in 0 until samples.lastIndex) {
            val cur = samples[i]
            val next = samples[i + 1]
            val sign = if (i % 2 == 0) 1f else -1f

            val up1 = Offset(cur.pos.x + cur.normal.x * (amplitude * sign), cur.pos.y + cur.normal.y * (amplitude * sign))
            val up2 = Offset(next.pos.x + next.normal.x * (amplitude * sign), next.pos.y + next.normal.y * (amplitude * sign))

            path.lineTo(up1.x, up1.y)
            path.lineTo(up2.x, up2.y)
            path.lineTo(next.pos.x, next.pos.y)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = (width * 0.68f).coerceAtLeast(1.5f), cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
    }

    private fun DrawScope.drawRailwayStroke(points: List<Offset>, width: Float, color: Color) {
        val railOffset = (width * 0.6f).coerceAtLeast(4f)
        val railWidth = (width * 0.32f).coerceAtLeast(1.5f)
        val densePts = resamplePolyline(points, 10f).map { it.pos }
        val railBase = if (densePts.size >= 2) densePts else points

        // Dual parallel rails
        val (railA, railB) = buildParallelPaths(railBase, railOffset)
        val railStyle = Stroke(width = railWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = railA, color = color, style = railStyle)
        drawPath(path = railB, color = color, style = railStyle)

        // Crossties (durmientes)
        val tieSpacing = (width * 2.2f).coerceIn(14f, 36f)
        val samples = resamplePolyline(railBase, tieSpacing)
        val tieLen = railOffset * 1.55f
        val tieWidth = (width * 0.38f).coerceAtLeast(1.5f)

        for (i in samples.indices) {
            if (i == samples.lastIndex && i > 0) {
                val distPrev = hypot(samples[i].pos.x - samples[i - 1].pos.x, samples[i].pos.y - samples[i - 1].pos.y)
                if (distPrev < tieSpacing * 0.45f) continue
            }
            val s = samples[i]
            val t0 = Offset(s.pos.x - s.normal.x * tieLen, s.pos.y - s.normal.y * tieLen)
            val t1 = Offset(s.pos.x + s.normal.x * tieLen, s.pos.y + s.normal.y * tieLen)
            drawLine(color = color, start = t0, end = t1, strokeWidth = tieWidth, cap = StrokeCap.Square)
        }
    }

    private fun DrawScope.drawArrowPatternStroke(points: List<Offset>, width: Float, color: Color) {
        // Base center line
        val centerPath = buildSmoothPath(points)
        val centerStyle = Stroke(width = (width * 0.45f).coerceAtLeast(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = centerPath, color = color.copy(alpha = (color.alpha * 0.65f).coerceIn(0.15f, 1f)), style = centerStyle)

        // Continuous chevrons along path
        val chevronSpacing = (width * 2.6f).coerceIn(18f, 44f)
        val samples = resamplePolyline(points, chevronSpacing)
        val wingLen = (width * 0.95f).coerceIn(8f, 26f)
        val wingWidth = (width * 0.45f).coerceAtLeast(1.5f)

        for (i in 1 until samples.size) {
            if (i == samples.lastIndex) {
                val distPrev = hypot(samples[i].pos.x - samples[i - 1].pos.x, samples[i].pos.y - samples[i - 1].pos.y)
                if (distPrev < chevronSpacing * 0.45f) continue
            }
            val s = samples[i]
            val wing1 = Offset(
                s.pos.x - s.tangent.x * wingLen + s.normal.x * (wingLen * 0.7f),
                s.pos.y - s.tangent.y * wingLen + s.normal.y * (wingLen * 0.7f)
            )
            val wing2 = Offset(
                s.pos.x - s.tangent.x * wingLen - s.normal.x * (wingLen * 0.7f),
                s.pos.y - s.tangent.y * wingLen - s.normal.y * (wingLen * 0.7f)
            )
            val chevronPath = Path().apply {
                moveTo(wing1.x, wing1.y)
                lineTo(s.pos.x, s.pos.y)
                lineTo(wing2.x, wing2.y)
            }
            drawPath(path = chevronPath, color = color, style = Stroke(width = wingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawTaperedStroke(points: List<Offset>, width: Float, color: Color) {
        if (points.size < 2) return
        val densePoints = resamplePolyline(points, 8f).map { it.pos }
        val workingPoints = if (densePoints.size >= 3) densePoints else points
        val totalLength = polylineLength(workingPoints)
        if (totalLength < 1f) return

        var accumulated = 0f
        val ribbonLeft = mutableListOf<Offset>()
        val ribbonRight = mutableListOf<Offset>()

        for (i in workingPoints.indices) {
            if (i > 0) {
                accumulated += hypot(workingPoints[i].x - workingPoints[i - 1].x, workingPoints[i].y - workingPoints[i - 1].y)
            }
            val t = (accumulated / totalLength).coerceIn(0f, 1f)
            // Organic tapered pressure curve: narrow tips swelling smoothly in the body
            val swell = sin(t * PI.toFloat())
            val organicRipple = 0.08f * sin(t * 3f * PI.toFloat())
            val pressure = (0.18f + 0.92f * swell + organicRipple).coerceIn(0.15f, 1.15f)
            val halfW = (width * pressure * 0.52f).coerceAtLeast(0.8f)

            val tangent = if (i < workingPoints.lastIndex) {
                val dx = workingPoints[i + 1].x - workingPoints[i].x
                val dy = workingPoints[i + 1].y - workingPoints[i].y
                val len = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / len, dy / len)
            } else {
                val dx = workingPoints[i].x - workingPoints[i - 1].x
                val dy = workingPoints[i].y - workingPoints[i - 1].y
                val len = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / len, dy / len)
            }
            val normal = Offset(-tangent.y, tangent.x)

            ribbonLeft.add(Offset(workingPoints[i].x + normal.x * halfW, workingPoints[i].y + normal.y * halfW))
            ribbonRight.add(Offset(workingPoints[i].x - normal.x * halfW, workingPoints[i].y - normal.y * halfW))
        }

        val ribbonPath = Path().apply {
            moveTo(ribbonLeft.first().x, ribbonLeft.first().y)
            for (p in ribbonLeft) lineTo(p.x, p.y)
            for (j in ribbonRight.indices.reversed()) lineTo(ribbonRight[j].x, ribbonRight[j].y)
            close()
        }
        drawPath(path = ribbonPath, color = color)
    }

    private fun DrawScope.drawCalligraphicStroke(points: List<Offset>, width: Float, color: Color) {
        if (points.size < 2) return
        // 45-degree fixed chisel nib swept along densely resampled path (prevents 45° dropout & self-intersection)
        val angleRad = 45f * (PI.toFloat() / 180f)
        val halfW = (width * 0.62f).coerceAtLeast(1.8f)
        val k = Offset(cos(angleRad) * halfW, sin(angleRad) * halfW)
        val dense = resamplePolyline(points, (width * 0.4f).coerceIn(3f, 8f)).map { it.pos }
        val working = if (dense.size >= 2) dense else points

        // Thin hairline chisel edge guarantees visibility even when moving parallel to the 45° nib angle
        val hairline = (width * 0.22f).coerceAtLeast(1.4f)
        drawPath(
            path = buildSmoothPath(working),
            color = color,
            style = Stroke(width = hairline, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        for (i in 0 until working.lastIndex) {
            val p0 = working[i]
            val p1 = working[i + 1]
            val quad = Path().apply {
                moveTo(p0.x - k.x, p0.y - k.y)
                lineTo(p0.x + k.x, p0.y + k.y)
                lineTo(p1.x + k.x, p1.y + k.y)
                lineTo(p1.x - k.x, p1.y - k.y)
                close()
            }
            drawPath(path = quad, color = color)
        }
    }

    private fun DrawScope.drawSketchStroke(points: List<Offset>, width: Float, color: Color) {
        val pathCenter = buildSmoothPath(points)
        val styleMain = Stroke(width = (width * 0.45f).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = pathCenter, color = color.copy(alpha = color.alpha * 0.75f), style = styleMain)

        // Pass 2: slightly offset +1.4px
        val pathJitter1 = buildSmoothPath(points.mapIndexed { idx, p ->
            val jitter = if (idx % 2 == 0) 1.5f else -1.2f
            Offset(p.x + jitter, p.y - jitter * 0.8f)
        })
        val styleJitter1 = Stroke(width = (width * 0.35f).coerceAtLeast(0.9f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = pathJitter1, color = color.copy(alpha = color.alpha * 0.50f), style = styleJitter1)

        // Pass 3: offset -1.4px
        val pathJitter2 = buildSmoothPath(points.mapIndexed { idx, p ->
            val jitter = if (idx % 2 == 1) 1.3f else -1.5f
            Offset(p.x - jitter * 0.8f, p.y + jitter)
        })
        val styleJitter2 = Stroke(width = (width * 0.30f).coerceAtLeast(0.8f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = pathJitter2, color = color.copy(alpha = color.alpha * 0.45f), style = styleJitter2)
    }

    private fun DrawScope.drawNeonStroke(points: List<Offset>, width: Float, color: Color, isDarkBoard: Boolean) {
        val path = buildSmoothPath(points)
        val glowColor = color.copy(alpha = 1.0f)

        // 1. Broad diffuse glow
        drawPath(
            path = path,
            color = glowColor.copy(alpha = if (isDarkBoard) 0.22f else 0.16f),
            style = Stroke(width = width * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 2. Mid intense halo
        drawPath(
            path = path,
            color = glowColor.copy(alpha = if (isDarkBoard) 0.45f else 0.32f),
            style = Stroke(width = width * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 3. Colored tube core
        drawPath(
            path = path,
            color = glowColor.copy(alpha = 0.88f),
            style = Stroke(width = width * 1.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 4. White-hot center filament
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.95f),
            style = Stroke(width = (width * 0.32f).coerceAtLeast(1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    private fun DrawScope.drawDynamicGradientStroke(points: List<Offset>, width: Float, baseColor: Color, isRainbow: Boolean) {
        val path = buildSmoothPath(points)
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val startOffset = Offset(minX, minY)
        val endOffset = if (hypot(maxX - minX, maxY - minY) < 2f) {
            Offset(minX + 4f, minY + 4f)
        } else {
            Offset(maxX, maxY)
        }

        val gradientColors = if (isRainbow) {
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Magenta)
        } else {
            listOf(
                baseColor,
                Color(0xFF38BDF8), // Electric cyan
                Color(0xFFA855F7), // Neon purple
                Color(0xFFFB7185), // Coral rose
                Color(0xFFFACC15)  // Bright gold
            )
        }

        val brush = Brush.linearGradient(
            colors = gradientColors,
            start = startOffset,
            end = endOffset
        )
        drawPath(
            path = path,
            brush = brush,
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    private fun DrawScope.drawChalkStroke(points: List<Offset>, width: Float, color: Color) {
        val path = buildSmoothPath(points)
        // Soft watercolor bleed wash
        drawPath(
            path = path,
            color = color.copy(alpha = color.alpha * 0.24f),
            style = Stroke(width = width * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Semi-translucent inner pigment spine
        drawPath(
            path = path,
            color = color.copy(alpha = color.alpha * 0.45f),
            style = Stroke(width = width * 0.88f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Porous chalk texture overlay
        drawPath(
            path = path,
            color = color.copy(alpha = color.alpha * 0.55f),
            style = Stroke(
                width = width * 0.65f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(max(2.5f, width * 0.55f), max(1.8f, width * 0.35f)), 0f)
            )
        )
        // Stippled chalk/pigment grain along edges
        val samples = resamplePolyline(points, (width * 0.9f).coerceIn(4f, 12f))
        samples.forEachIndexed { idx, s ->
            val shift = when (idx % 3) {
                0 -> width * 0.36f
                1 -> -width * 0.34f
                else -> width * 0.14f
            }
            val dotCenter = Offset(s.pos.x + s.normal.x * shift, s.pos.y + s.normal.y * shift)
            drawCircle(
                color = color.copy(alpha = color.alpha * if (idx % 2 == 0) 0.44f else 0.28f),
                radius = (width * 0.24f).coerceAtLeast(1f),
                center = dotCenter
            )
        }
    }

    private fun DrawScope.drawAnimatedPulseStroke(points: List<Offset>, width: Float, color: Color, animPhase: Float) {
        val path = buildSmoothPath(points)
        val dashLen = width * 3.5f
        val gapLen = width * 2.0f
        val phaseOffset = -(animPhase * (dashLen + gapLen))
        val pulseAlpha = 0.20f + 0.14f * sin(animPhase * 2f * PI.toFloat())

        // Pulsing backglow
        drawPath(
            path = path,
            color = color.copy(alpha = (color.alpha * pulseAlpha).coerceIn(0.08f, 0.45f)),
            style = Stroke(width = width * 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Marching energy dashes
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), phaseOffset)
            )
        )
    }

    private fun DrawScope.drawDashDotStroke(points: List<Offset>, width: Float, color: Color) {
        val path = buildSmoothPath(points)
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 1.6f, width * 1.2f, width * 1.6f), 0f)
            )
        )
    }

    private fun DrawScope.drawDashedStroke(points: List<Offset>, width: Float, color: Color) {
        val path = buildSmoothPath(points)
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
            )
        )
    }

    private fun DrawScope.drawDottedStroke(points: List<Offset>, width: Float, color: Color) {
        val path = buildSmoothPath(points)
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(width, width * 1.5f), 0f)
            )
        )
    }

    // --- Arrow Endpoints Helper ---

    private fun DrawScope.drawStrokeArrowCaps(
        points: List<Offset>,
        capStyle: LineCapStyle,
        width: Float,
        arrowSize: Float,
        color: Color
    ) {
        if (capStyle == LineCapStyle.NONE || points.size < 2) return
        val lookbackDist = max(8f, width * 1.6f)

        if (capStyle == LineCapStyle.END_ARROW || capStyle == LineCapStyle.BOTH_ARROWS) {
            val last = points.last()
            var prev = points[points.lastIndex - 1]
            for (i in points.lastIndex - 1 downTo 0) {
                val candidate = points[i]
                prev = candidate
                if (hypot(last.x - candidate.x, last.y - candidate.y) >= lookbackDist) break
            }
            val dir = Offset(last.x - prev.x, last.y - prev.y)
            drawArrowHead(tip = last, direction = dir, size = arrowSize, color = color)
        }

        if (capStyle == LineCapStyle.START_ARROW || capStyle == LineCapStyle.BOTH_ARROWS) {
            val first = points.first()
            var next = points[1]
            for (i in 1 until points.size) {
                val candidate = points[i]
                next = candidate
                if (hypot(first.x - candidate.x, first.y - candidate.y) >= lookbackDist) break
            }
            val dir = Offset(first.x - next.x, first.y - next.y)
            drawArrowHead(tip = first, direction = dir, size = arrowSize, color = color)
        }
    }

    fun DrawScope.drawArrowHead(
        tip: Offset,
        direction: Offset,
        size: Float,
        color: Color
    ) {
        val len = hypot(direction.x, direction.y)
        if (len < 0.001f) return
        val u = Offset(direction.x / len, direction.y / len)
        val n = Offset(-u.y, u.x)

        val baseCenter = Offset(tip.x - u.x * size, tip.y - u.y * size)
        val wing1 = Offset(baseCenter.x + n.x * (size * 0.55f), baseCenter.y + n.y * (size * 0.55f))
        val wing2 = Offset(baseCenter.x - n.x * (size * 0.55f), baseCenter.y - n.y * (size * 0.55f))

        val arrowPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(wing1.x, wing1.y)
            lineTo(baseCenter.x - u.x * (size * 0.18f), baseCenter.y - u.y * (size * 0.18f))
            lineTo(wing2.x, wing2.y)
            close()
        }
        drawPath(path = arrowPath, color = color)
    }

    // --- Geometry & Polyline Utils ---

    private fun buildSmoothPath(points: List<Offset>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
            return path
        }
        for (i in 1 until points.lastIndex) {
            val cur = points[i]
            val next = points[i + 1]
            val mx = (cur.x + next.x) * 0.5f
            val my = (cur.y + next.y) * 0.5f
            path.quadraticTo(cur.x, cur.y, mx, my)
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    private data class PathContour(val points: List<Offset>, val isClosed: Boolean)

    private fun extractPathContours(path: Path, stepHint: Float): List<PathContour> {
        val contours = mutableListOf<PathContour>()
        val androidPath = path.asAndroidPath()
        val pm = PathMeasure(androidPath, false)
        val pos = FloatArray(2)
        val safeStep = stepHint.coerceIn(2.5f, 8f)
        do {
            val len = pm.length
            if (len >= 1f) {
                val isClosed = pm.isClosed
                val targetSamples = ceil((len / safeStep).toDouble()).toInt().coerceIn(8, 360)
                val pts = ArrayList<Offset>(targetSamples + 1)
                val endIndex = if (isClosed) targetSamples - 1 else targetSamples
                for (i in 0..endIndex) {
                    val d = ((i.toFloat() / targetSamples.toFloat()) * len).coerceIn(0f, len)
                    if (pm.getPosTan(d, pos, null)) {
                        pts.add(Offset(pos[0], pos[1]))
                    }
                }
                if (pts.size >= 2) {
                    contours.add(PathContour(pts, isClosed))
                }
            }
        } while (pm.nextContour())
        return contours
    }

    private fun buildClosedSmoothPath(points: List<Offset>): Path {
        val path = Path()
        val n = points.size
        if (n < 3) return buildSmoothPath(points)
        val startMidX = (points[n - 1].x + points[0].x) * 0.5f
        val startMidY = (points[n - 1].y + points[0].y) * 0.5f
        path.moveTo(startMidX, startMidY)
        for (i in 0 until n) {
            val cur = points[i]
            val next = points[(i + 1) % n]
            val mx = (cur.x + next.x) * 0.5f
            val my = (cur.y + next.y) * 0.5f
            path.quadraticTo(cur.x, cur.y, mx, my)
        }
        path.close()
        return path
    }

    private fun buildClosedParallelPaths(points: List<Offset>, offsetDist: Float): Pair<Path, Path> {
        val n = points.size
        if (n < 3) return buildParallelPaths(points, offsetDist)
        val ptsA = ArrayList<Offset>(n)
        val ptsB = ArrayList<Offset>(n)
        for (i in 0 until n) {
            val prev = points[(i - 1 + n) % n]
            val next = points[(i + 1) % n]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val normal = Offset(-dy / l, dx / l)
            ptsA.add(Offset(points[i].x + normal.x * offsetDist, points[i].y + normal.y * offsetDist))
            ptsB.add(Offset(points[i].x - normal.x * offsetDist, points[i].y - normal.y * offsetDist))
        }
        return Pair(buildClosedSmoothPath(ptsA), buildClosedSmoothPath(ptsB))
    }

    private fun buildParallelPaths(points: List<Offset>, offsetDist: Float): Pair<Path, Path> {
        val pathA = Path()
        val pathB = Path()
        if (points.size < 2) return Pair(pathA, pathB)

        val ptsA = mutableListOf<Offset>()
        val ptsB = mutableListOf<Offset>()

        for (i in points.indices) {
            val tangent = if (i < points.lastIndex) {
                val dx = points[i + 1].x - points[i].x
                val dy = points[i + 1].y - points[i].y
                val l = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / l, dy / l)
            } else {
                val dx = points[i].x - points[i - 1].x
                val dy = points[i].y - points[i - 1].y
                val l = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / l, dy / l)
            }
            val normal = Offset(-tangent.y, tangent.x)
            ptsA.add(Offset(points[i].x + normal.x * offsetDist, points[i].y + normal.y * offsetDist))
            ptsB.add(Offset(points[i].x - normal.x * offsetDist, points[i].y - normal.y * offsetDist))
        }

        return Pair(buildSmoothPath(ptsA), buildSmoothPath(ptsB))
    }

    private data class PolylineSample(val pos: Offset, val tangent: Offset, val normal: Offset)

    private fun resamplePolyline(points: List<Offset>, stepDist: Float): List<PolylineSample> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<PolylineSample>()
        val safeStep = stepDist.coerceAtLeast(4f)

        var currentDist = 0f
        var nextTargetDist = 0f

        val initialTangent = Offset(
            (points[1].x - points[0].x) / hypot(points[1].x - points[0].x, points[1].y - points[0].y).coerceAtLeast(0.001f),
            (points[1].y - points[0].y) / hypot(points[1].x - points[0].x, points[1].y - points[0].y).coerceAtLeast(0.001f)
        )
        result.add(PolylineSample(points[0], initialTangent, Offset(-initialTangent.y, initialTangent.x)))
        nextTargetDist += safeStep

        for (i in 0 until points.lastIndex) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val segLen = hypot(p1.x - p0.x, p1.y - p0.y)
            if (segLen < 0.001f) continue

            val tangent = Offset((p1.x - p0.x) / segLen, (p1.y - p0.y) / segLen)
            val normal = Offset(-tangent.y, tangent.x)

            while (currentDist + segLen >= nextTargetDist) {
                val t = ((nextTargetDist - currentDist) / segLen).coerceIn(0f, 1f)
                val samplePos = Offset(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)
                result.add(PolylineSample(samplePos, tangent, normal))
                nextTargetDist += safeStep
            }
            currentDist += segLen
        }

        val last = points.last()
        val lastSamplePos = result.lastOrNull()?.pos
        if (lastSamplePos == null || hypot(last.x - lastSamplePos.x, last.y - lastSamplePos.y) > 1.5f) {
            val prev = points[points.lastIndex - 1]
            val dx = last.x - prev.x
            val dy = last.y - prev.y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val t = if (l > 0.01f) Offset(dx / l, dy / l) else initialTangent
            result.add(PolylineSample(last, t, Offset(-t.y, t.x)))
        }
        return result
    }

    private fun deduplicateOffsets(points: List<Offset>, minStep: Float): List<Offset> {
        if (points.size <= 2) return points
        val out = mutableListOf(points.first())
        for (i in 1 until points.lastIndex) {
            val cur = points[i]
            val last = out.last()
            if (hypot(cur.x - last.x, cur.y - last.y) >= minStep) {
                out.add(cur)
            }
        }
        val endPt = points.last()
        if (hypot(endPt.x - out.last().x, endPt.y - out.last().y) > 0.1f || out.size == 1) {
            out.add(endPt)
        }
        return out
    }

    private fun trimPointsForArrowCaps(
        points: List<Offset>,
        capStyle: LineCapStyle,
        inset: Float
    ): List<Offset> {
        if (points.size < 2 || capStyle == LineCapStyle.NONE) return points
        val total = polylineLength(points)
        val trimStart = (capStyle == LineCapStyle.START_ARROW || capStyle == LineCapStyle.BOTH_ARROWS)
        val trimEnd = (capStyle == LineCapStyle.END_ARROW || capStyle == LineCapStyle.BOTH_ARROWS)
        val required = (if (trimStart) inset else 0f) + (if (trimEnd) inset else 0f)
        if (total <= required + 8f) return points

        val startDist = if (trimStart) inset else 0f
        val endDist = if (trimEnd) total - inset else total

        val out = mutableListOf<Offset>()
        var acc = 0f
        var started = !trimStart
        if (started) out.add(points.first())

        for (i in 0 until points.lastIndex) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val seg = hypot(p1.x - p0.x, p1.y - p0.y)
            if (seg < 0.001f) continue

            if (!started && acc + seg >= startDist) {
                val t = ((startDist - acc) / seg).coerceIn(0f, 1f)
                out.add(Offset(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t))
                started = true
            }
            if (started) {
                if (acc + seg >= endDist) {
                    val t = ((endDist - acc) / seg).coerceIn(0f, 1f)
                    val endPt = Offset(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)
                    if (out.isEmpty() || hypot(endPt.x - out.last().x, endPt.y - out.last().y) > 0.5f) {
                        out.add(endPt)
                    }
                    break
                } else {
                    if (out.isEmpty() || hypot(p1.x - out.last().x, p1.y - out.last().y) > 0.5f) {
                        out.add(p1)
                    }
                }
            }
            acc += seg
        }
        return if (out.size >= 2) out else points
    }

    private fun polylineLength(points: List<Offset>): Float {
        var len = 0f
        for (i in 0 until points.lastIndex) {
            len += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        return len
    }
}
