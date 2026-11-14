package com.example.ui.canvas

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
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

        val screenPoints = stroke.points.map { Offset(it.x * scale + panX, it.y * scale + panY) }
        val scaledWidth = (stroke.width * scale).coerceAtLeast(1.5f)
        val baseColor = parseColor(stroke.colorHex)
        val alpha = stroke.opacity.coerceIn(0.15f, 1.0f)
        val effectiveColor = baseColor.copy(alpha = alpha)

        when (stroke.strokeStyle) {
            StrokeStyle.DOUBLE -> {
                drawDoubleStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.ZIGZAG -> {
                drawZigzagStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.WAVY -> {
                drawWavyStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.SQUARE_WAVE -> {
                drawSquareWaveStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.RAILWAY -> {
                drawRailwayStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.ARROW_PATTERN -> {
                drawArrowPatternStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.TAPERED -> {
                drawTaperedStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.CALLIGRAPHIC -> {
                drawCalligraphicStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.SKETCH -> {
                drawSketchStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.NEON_GLOW -> {
                drawNeonStroke(screenPoints, scaledWidth, baseColor, isDarkBoard)
            }
            StrokeStyle.DYNAMIC_GRADIENT -> {
                drawDynamicGradientStroke(screenPoints, scaledWidth, baseColor, stroke.isRainbow)
            }
            StrokeStyle.CHALK_TEXTURE -> {
                drawChalkStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.ANIMATED_PULSE -> {
                drawAnimatedPulseStroke(screenPoints, scaledWidth, effectiveColor, animPhase)
            }
            StrokeStyle.DASH_DOT -> {
                drawDashDotStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.DASHED -> {
                drawDashedStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.DOTTED -> {
                drawDottedStroke(screenPoints, scaledWidth, effectiveColor)
            }
            StrokeStyle.SOLID -> {
                drawStandardSmoothStroke(screenPoints, scaledWidth, effectiveColor, stroke.brushType, stroke.isHighlighter, stroke.isRainbow, isDarkBoard)
            }
        }

        // Draw arrowheads at endpoints if requested
        drawStrokeArrowCaps(screenPoints, stroke.capStyle, scaledWidth, effectiveColor)

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
        isSelected: Boolean = false
    ) {
        val p0 = if (shape.vertices.size >= 2) {
            Offset(shape.vertices[0].x * scale + panX, shape.vertices[0].y * scale + panY)
        } else {
            Offset(shape.x * scale + panX, shape.y * scale + panY)
        }
        val p1 = if (shape.vertices.size >= 2) {
            Offset(shape.vertices[1].x * scale + panX, shape.vertices[1].y * scale + panY)
        } else {
            Offset((shape.x + shape.width) * scale + panX, (shape.y + shape.height) * scale + panY)
        }

        val strokeWidth = (shape.strokeWidth * scale).coerceAtLeast(1.5f)
        val strokeColor = parseColor(shape.strokeColorHex)

        val strokeTemp = WhiteboardStroke(
            points = listOf(StrokePoint((p0.x - panX) / scale, (p0.y - panY) / scale), StrokePoint((p1.x - panX) / scale, (p1.y - panY) / scale)),
            colorHex = shape.strokeColorHex,
            width = shape.strokeWidth,
            strokeStyle = shape.strokeStyle,
            capStyle = shape.capStyle
        )

        drawAdvancedStroke(
            stroke = strokeTemp,
            panX = panX,
            panY = panY,
            scale = scale,
            animPhase = animPhase,
            isSelected = isSelected
        )
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
        for (i in 1 until samples.size) {
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

        // Dual parallel rails
        val (railA, railB) = buildParallelPaths(points, railOffset)
        val railStyle = Stroke(width = railWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = railA, color = color, style = railStyle)
        drawPath(path = railB, color = color, style = railStyle)

        // Crossties (durmientes)
        val tieSpacing = (width * 2.2f).coerceIn(14f, 36f)
        val samples = resamplePolyline(points, tieSpacing)
        val tieLen = railOffset * 1.55f
        val tieWidth = (width * 0.38f).coerceAtLeast(1.5f)

        for (s in samples) {
            val t0 = Offset(s.pos.x - s.normal.x * tieLen, s.pos.y - s.normal.y * tieLen)
            val t1 = Offset(s.pos.x + s.normal.x * tieLen, s.pos.y + s.normal.y * tieLen)
            drawLine(color = color, start = t0, end = t1, strokeWidth = tieWidth, cap = StrokeCap.Square)
        }
    }

    private fun DrawScope.drawArrowPatternStroke(points: List<Offset>, width: Float, color: Color) {
        // Base center line
        val centerPath = buildSmoothPath(points)
        val centerStyle = Stroke(width = (width * 0.45f).coerceAtLeast(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = centerPath, color = color.copy(alpha = 0.65f), style = centerStyle)

        // Continuous chevrons along path
        val chevronSpacing = (width * 2.6f).coerceIn(18f, 44f)
        val samples = resamplePolyline(points, chevronSpacing)
        val wingLen = (width * 0.95f).coerceIn(8f, 26f)
        val wingWidth = (width * 0.45f).coerceAtLeast(1.5f)

        for (s in samples) {
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
        val totalLength = polylineLength(points)
        if (totalLength < 1f) return

        var accumulated = 0f
        val ribbonLeft = mutableListOf<Offset>()
        val ribbonRight = mutableListOf<Offset>()

        for (i in points.indices) {
            if (i > 0) {
                accumulated += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            }
            val t = (accumulated / totalLength).coerceIn(0f, 1f)
            // Sine swell pressure profile: starts at 20%, peaks at 100%, finishes at 20%
            val pressure = 0.20f + 0.80f * sin(t * PI.toFloat())
            val halfW = (width * pressure * 0.5f).coerceAtLeast(0.8f)

            val tangent = if (i < points.lastIndex) {
                val dx = points[i + 1].x - points[i].x
                val dy = points[i + 1].y - points[i].y
                val len = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / len, dy / len)
            } else {
                val dx = points[i].x - points[i - 1].x
                val dy = points[i].y - points[i - 1].y
                val len = hypot(dx, dy).coerceAtLeast(0.001f)
                Offset(dx / len, dy / len)
            }
            val normal = Offset(-tangent.y, tangent.x)

            ribbonLeft.add(Offset(points[i].x + normal.x * halfW, points[i].y + normal.y * halfW))
            ribbonRight.add(Offset(points[i].x - normal.x * halfW, points[i].y - normal.y * halfW))
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
        // 45-degree fixed chisel vector
        val angleRad = 45f * (PI.toFloat() / 180f)
        val halfW = (width * 0.6f).coerceAtLeast(1.5f)
        val k = Offset(cos(angleRad) * halfW, sin(angleRad) * halfW)

        val topPoints = points.map { Offset(it.x + k.x, it.y + k.y) }
        val bottomPoints = points.map { Offset(it.x - k.x, it.y - k.y) }

        val path = Path().apply {
            moveTo(topPoints.first().x, topPoints.first().y)
            for (p in topPoints) lineTo(p.x, p.y)
            for (j in bottomPoints.indices.reversed()) lineTo(bottomPoints[j].x, bottomPoints[j].y)
            close()
        }
        drawPath(path = path, color = color)
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
            start = Offset(minX, minY),
            end = Offset(maxX, maxY)
        )
        drawPath(
            path = path,
            brush = brush,
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    private fun DrawScope.drawChalkStroke(points: List<Offset>, width: Float, color: Color) {
        val path = buildSmoothPath(points)
        // Porous dashed chalk texture
        drawPath(
            path = path,
            color = color.copy(alpha = color.alpha * 0.72f),
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 1.2f, width * 0.8f), 0f)
            )
        )
        // Secondary stippled edge
        drawPath(
            path = path,
            color = color.copy(alpha = color.alpha * 0.38f),
            style = Stroke(
                width = width * 1.35f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 0.4f, width * 1.5f), width * 0.6f)
            )
        )
    }

    private fun DrawScope.drawAnimatedPulseStroke(points: List<Offset>, width: Float, color: Color, animPhase: Float) {
        val path = buildSmoothPath(points)
        val dashLen = width * 3.5f
        val gapLen = width * 2.0f
        val phaseOffset = (animPhase * (dashLen + gapLen))

        // Faint backglow
        drawPath(
            path = path,
            color = color.copy(alpha = color.alpha * 0.22f),
            style = Stroke(width = width * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Marching dashes
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
        color: Color
    ) {
        if (capStyle == LineCapStyle.NONE || points.size < 2) return

        val arrowSize = (width * 3.0f + 10f).coerceIn(12f, 36f)

        if (capStyle == LineCapStyle.END_ARROW || capStyle == LineCapStyle.BOTH_ARROWS) {
            val last = points.last()
            val prev = points[points.lastIndex - 1]
            val dir = Offset(last.x - prev.x, last.y - prev.y)
            drawArrowHead(tip = last, direction = dir, size = arrowSize, color = color)
        }

        if (capStyle == LineCapStyle.START_ARROW || capStyle == LineCapStyle.BOTH_ARROWS) {
            val first = points.first()
            val next = points[1]
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

        if (result.size < 2 && points.isNotEmpty()) {
            val last = points.last()
            result.add(PolylineSample(last, initialTangent, Offset(-initialTangent.y, initialTangent.x)))
        }
        return result
    }

    private fun polylineLength(points: List<Offset>): Float {
        var len = 0f
        for (i in 0 until points.lastIndex) {
            len += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        return len
    }
}
