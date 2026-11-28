package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import com.example.model.StrokeBrushType
import com.example.model.StrokeStyle
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Configuration passed into Stage 3 polymorphic [BrushEngine.render] calls.
 */
data class BrushConfig(
    val color: Int,
    val strokeWidth: Float = 8f,
    val alpha: Float = 1.0f,
    val handDrawnFactor: Float = 0.25f,
    val isDarkBackground: Boolean = false,
    val isOpaqueCanvasBackground: Boolean = true,
    val isRainbow: Boolean = false,
    val strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val animPhase: Float = 0f,
    val seed: Int = 1337,
    val flow: Float = 1.0f,
    val spacing: Float = 0.25f,
    val hardness: Float = 0.85f,
    val grainStrength: Float = 0.35f,
    val taperStart: Float = 0.20f,
    val taperEnd: Float = 0.35f,
    val velocitySensitivity: Float = 0.50f,
    val chiselAngleDeg: Float = 38f
)

/**
 * Stage 3 of the Unified 2D Rendering Pipeline: Polymorphic Brush Engine.
 * Every brush in the application implements [render], consuming uniform [BrushNode] lists
 * and rendering procedurally without external raster assets.
 */
interface BrushEngine {
    val brushId: String
    fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig)
}

/**
 * Per-thread scratch buffers and reusable Paint/Path/Shader caches so rendering strokes
 * causes near-zero GC churn and zero per-frame native Paint/Path/RadialGradient allocations.
 */
private class BrushThreadScratch {
    val paint1 = Paint(Paint.ANTI_ALIAS_FLAG)
    val paint2 = Paint(Paint.ANTI_ALIAS_FLAG)
    val paint3 = Paint(Paint.ANTI_ALIAS_FLAG)
    val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val innerLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val whiteBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    val dstOutXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    val multiplyXfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

    val path1 = Path()
    val path2 = Path()
    val path3 = Path()
    val rectF = RectF()
    val matrix = Matrix()

    var leftX = FloatArray(256)
    var leftY = FloatArray(256)
    var rightX = FloatArray(256)
    var rightY = FloatArray(256)
    var haloLX = FloatArray(256)
    var haloLY = FloatArray(256)
    var haloRX = FloatArray(256)
    var haloRY = FloatArray(256)
    var pointsBuf = FloatArray(512)

    val radialCache = LruCache<Long, RadialGradient>(24)

    fun ensureRibbonCapacity(size: Int) {
        if (leftX.size < size) {
            val newCap = max(size, leftX.size * 2)
            leftX = FloatArray(newCap)
            leftY = FloatArray(newCap)
            rightX = FloatArray(newCap)
            rightY = FloatArray(newCap)
        }
    }

    fun ensureHaloCapacity(size: Int) {
        if (haloLX.size < size) {
            val newCap = max(size, haloLX.size * 2)
            haloLX = FloatArray(newCap)
            haloLY = FloatArray(newCap)
            haloRX = FloatArray(newCap)
            haloRY = FloatArray(newCap)
        }
    }

    fun ensurePointsCapacity(floatCount: Int) {
        if (pointsBuf.size < floatCount) {
            pointsBuf = FloatArray(max(floatCount, pointsBuf.size * 2))
        }
    }

    fun getOrCreateRadial(
        key: Long,
        c0: Int,
        c1: Int,
        c2: Int,
        midStop: Float
    ): RadialGradient {
        radialCache.get(key)?.let { return it }
        val grad = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, midStop, 1f),
            Shader.TileMode.CLAMP
        )
        radialCache.put(key, grad)
        return grad
    }

    fun resetFillPaint(p: Paint, color: Int, alpha: Int, shader: Shader?) {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = alpha
        p.shader = shader
    }

    fun resetStrokePaint(p: Paint, color: Int, alpha: Int, strokeWidth: Float, shader: Shader?) {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = strokeWidth
        p.color = color
        p.alpha = alpha
        p.shader = shader
    }
}

private val threadScratch = ThreadLocal.withInitial { BrushThreadScratch() }

/**
 * Splits a flat [List<BrushNode>] returned by [PathHumanizer] back into contiguous contours
 * by [BrushNode.contourIndex] so disconnected dashes/shapes never bridge unintentionally.
 */
internal fun splitNodeContours(nodes: List<BrushNode>): List<List<BrushNode>> {
    if (nodes.isEmpty()) return emptyList()
    val firstContour = nodes[0].contourIndex
    var isSingleContour = true
    for (i in 1 until nodes.size) {
        if (nodes[i].contourIndex != firstContour) {
            isSingleContour = false
            break
        }
    }
    if (isSingleContour) {
        return Collections.singletonList(nodes)
    }
    val contours = mutableListOf<List<BrushNode>>()
    var current = mutableListOf<BrushNode>()
    var activeContour = firstContour
    for (node in nodes) {
        if (node.contourIndex != activeContour) {
            if (current.isNotEmpty()) contours.add(current)
            current = mutableListOf()
            activeContour = node.contourIndex
        }
        current.add(node)
    }
    if (current.isNotEmpty()) contours.add(current)
    return contours
}

internal fun computeNodeBoundsInto(nodes: List<BrushNode>, padding: Float, out: RectF): RectF {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in nodes.indices) {
        val n = nodes[i]
        if (n.x < minX) minX = n.x
        if (n.x > maxX) maxX = n.x
        if (n.y < minY) minY = n.y
        if (n.y > maxY) maxY = n.y
    }
    if (minX == Float.MAX_VALUE) {
        out.set(0f, 0f, 1f, 1f)
    } else {
        val safePad = padding.coerceAtLeast(4f)
        out.set(minX - safePad, minY - safePad, maxX + safePad, maxY + safePad)
    }
    return out
}

internal fun computeNodeWidthMultiplier(node: BrushNode, style: StrokeStyle): Float = 1f

internal fun computeParametricWidthFactor(
    index: Int,
    contourSize: Int,
    node: BrushNode,
    config: BrushConfig
): Float {
    val pressureMul = (0.45f + 0.55f * node.pressure.coerceIn(0.15f, 1.25f))
    val velDelta = (node.velocity - 0.35f) * config.velocitySensitivity.coerceIn(0f, 1f)
    val velocityMul = (1.0f - 0.38f * velDelta).coerceIn(0.60f, 1.24f)
    val taperMul = if (node.isClosed || contourSize < 6) {
        1.0f
    } else {
        val posNorm = index.toFloat() / (contourSize - 1).coerceAtLeast(1)
        val startSpan = (0.04f + 0.16f * config.taperStart).coerceIn(0.02f, 0.22f)
        val endSpan = (0.04f + 0.18f * config.taperEnd).coerceIn(0.02f, 0.24f)
        val sTaper = if (config.taperStart > 0.01f && posNorm < startSpan) {
            val u = (posNorm / startSpan).coerceIn(0f, 1f)
            (1f - config.taperStart * 0.72f) + (config.taperStart * 0.72f) * u
        } else 1f
        val eTaper = if (config.taperEnd > 0.01f && posNorm > 1f - endSpan) {
            val u = ((1f - posNorm) / endSpan).coerceIn(0f, 1f)
            (1f - config.taperEnd * 0.76f) + (config.taperEnd * 0.76f) * u
        } else 1f
        min(sTaper, eTaper)
    }
    return (pressureMul * velocityMul * taperMul).coerceIn(0.25f, 1.35f)
}

private val rainbowColors = intArrayOf(
    Color.RED,
    Color.YELLOW,
    Color.GREEN,
    Color.CYAN,
    Color.MAGENTA
)

internal fun buildDynamicShaderIfNeeded(nodes: List<BrushNode>, config: BrushConfig): Shader? {
    if (!config.isRainbow || nodes.isEmpty()) return null
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in nodes.indices) {
        val n = nodes[i]
        if (n.x < minX) minX = n.x
        if (n.x > maxX) maxX = n.x
        if (n.y < minY) minY = n.y
        if (n.y > maxY) maxY = n.y
    }
    val span = hypot(maxX - minX, maxY - minY)
    val endX = if (span < 4f) minX + 16f else maxX
    val endY = if (span < 4f) minY + 16f else maxY
    return LinearGradient(minX, minY, endX, endY, rainbowColors, null, Shader.TileMode.CLAMP)
}

/**
 * 1. Resaltador ([HighlighterBrush]):
 * Translucent broad chiseled-tip highlighter rendered into an isolated offscreen layer
 * at 100% internal fill opacity (with ZERO center-axis stroke pass) and composited once
 * at `0.30f` alpha using true MULTIPLY blending.
 */
class HighlighterBrush(
    private val chiselAngleDegrees: Float = 32f
) : BrushEngine {
    override val brushId: String = "highlighter"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseWidth = (config.strokeWidth * 1.55f).coerceAtLeast(4f)
        val halfNib = baseWidth * 0.50f
        val chiselRad = chiselAngleDegrees * (PI.toFloat() / 180f)
        val nibCos = cos(chiselRad)
        val nibSin = sin(chiselRad)

        val targetAlpha = (config.alpha.coerceIn(0.08f, DEFAULT_HIGHLIGHTER_ALPHA) * 255f)
            .roundToInt()
            .coerceIn(20, 77)
        val shader = buildDynamicShaderIfNeeded(nodes, config)
        val useMultiply = !config.isDarkBackground && config.isOpaqueCanvasBackground
        val layerBounds = computeNodeBoundsInto(nodes, halfNib * 1.8f + 6f, s.rectF)

        val fillPaint = s.paint1
        s.resetFillPaint(fillPaint, config.color, 255, shader)

        val pathOut = s.path1
        pathOut.rewind()
        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            if (contour.size == 1) {
                val p = contour[0]
                val dx = nibCos * halfNib
                val dy = nibSin * halfNib
                val perpX = -nibSin * halfNib * 0.35f
                val perpY = nibCos * halfNib * 0.35f
                pathOut.moveTo(p.x - dx - perpX, p.y - dy - perpY)
                pathOut.lineTo(p.x + dx - perpX, p.y + dy - perpY)
                pathOut.lineTo(p.x + dx + perpX, p.y + dy + perpY)
                pathOut.lineTo(p.x - dx + perpX, p.y - dy + perpY)
                pathOut.close()
                continue
            }

            val cSize = contour.size
            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY

            for (i in 0 until cSize) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val tx = cos(n.angle)
                val ty = sin(n.angle)
                val align = abs(nx * nibCos + ny * nibSin)
                val hw = (halfNib * (0.64f + 0.36f * align)).coerceAtLeast(1.2f)
                val bevelShift = if (!n.isClosed && (i == 0 || i == cSize - 1)) hw * 0.38f else 0f
                leftX[i] = n.x + nx * hw + tx * bevelShift
                leftY[i] = n.y + ny * hw + ty * bevelShift
                rightX[i] = n.x - nx * hw - tx * bevelShift
                rightY[i] = n.y - ny * hw - ty * bevelShift
            }

            pathOut.moveTo(leftX[0], leftY[0])
            for (i in 1 until cSize) {
                pathOut.lineTo(leftX[i], leftY[i])
            }
            for (i in cSize - 1 downTo 0) {
                pathOut.lineTo(rightX[i], rightY[i])
            }
            pathOut.close()
        }

        if (useMultiply && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val layerPaint = s.layerPaint
            layerPaint.reset()
            layerPaint.isAntiAlias = true
            layerPaint.xfermode = s.multiplyXfermode
            val saveCount = canvas.saveLayer(layerBounds, layerPaint)
            try {
                canvas.drawRect(layerBounds, s.whiteBgPaint)
                val innerPaint = s.innerLayerPaint
                innerPaint.reset()
                innerPaint.isAntiAlias = true
                innerPaint.alpha = targetAlpha
                val innerSave = canvas.saveLayer(layerBounds, innerPaint)
                try {
                    canvas.drawPath(pathOut, fillPaint)
                } finally {
                    canvas.restoreToCount(innerSave)
                }
            } finally {
                canvas.restoreToCount(saveCount)
            }
            return
        }

        val layerPaint = s.layerPaint
        layerPaint.reset()
        layerPaint.isAntiAlias = true
        layerPaint.alpha = targetAlpha
        if (useMultiply && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            layerPaint.blendMode = BlendMode.MULTIPLY
        }

        val saveCount = canvas.saveLayer(layerBounds, layerPaint)
        try {
            canvas.drawPath(pathOut, fillPaint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    companion object {
        const val DEFAULT_HIGHLIGHTER_ALPHA = 0.30f
    }
}

/**
 * 6. Pluma Estilográfica Orgánica ([FountainPenBrush]):
 * Polygon contour extrusion modulated by velocity & progress so fountain pen strokes
 * and shapes render organically through the exact same [BrushNode] pipeline.
 */
class FountainPenBrush : BrushEngine {
    override val brushId: String = "fountain_pen"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseHalfW = (config.strokeWidth * 0.5f).coerceAtLeast(0.85f)
        val effectiveAlpha = (config.alpha * config.flow).coerceIn(0.12f, 1.0f)
        val alphaInt = (effectiveAlpha * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = s.paint1
        s.resetFillPaint(paint, config.color, alphaInt, shader)

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW * contour[0].pressure, paint)
                continue
            }

            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY

            for (i in 0 until cSize) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val paramMul = computeParametricWidthFactor(i, cSize, n, config)
                val hw = (baseHalfW * paramMul).coerceAtLeast(0.55f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            fillRoundedRibbonPath(s.path1, contour, leftX, leftY, rightX, rightY)
            canvas.drawPath(s.path1, paint)
        }
    }
}

/**
 * 7. Marcador Fino Técnico ([FineMarkerBrush]):
 * Uniform, crisp architectural nib extruded along [BrushNode] normals with clean corners.
 */
class FineMarkerBrush : BrushEngine {
    override val brushId: String = "fine_marker"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseHalfW = (config.strokeWidth * 0.38f).coerceAtLeast(0.75f)
        val alphaInt = (config.alpha.coerceIn(0.20f, 1.0f) * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = s.paint1
        s.resetFillPaint(paint, config.color, alphaInt, shader)

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW, paint)
                continue
            }
            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY

            for (i in 0 until cSize) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val hw = baseHalfW.coerceAtLeast(0.65f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            fillRoundedRibbonPath(s.path1, contour, leftX, leftY, rightX, rightY)
            canvas.drawPath(s.path1, paint)
        }
    }
}

private fun fillRoundedRibbonPath(
    ribbon: Path,
    contour: List<BrushNode>,
    leftX: FloatArray,
    leftY: FloatArray,
    rightX: FloatArray,
    rightY: FloatArray
) {
    val cSize = contour.size
    ribbon.rewind()
    ribbon.moveTo(leftX[0], leftY[0])
    for (i in 1 until cSize) {
        ribbon.lineTo(leftX[i], leftY[i])
    }
    if (!contour[0].isClosed) {
        val last = contour[cSize - 1]
        val endR = hypot(leftX[cSize - 1] - rightX[cSize - 1], leftY[cSize - 1] - rightY[cSize - 1]) * 0.5f
        val endBaseAngle = last.angle + (PI.toFloat() * 0.5f)
        for (step in 1..3) {
            val a = endBaseAngle - (step / 4f) * PI.toFloat()
            ribbon.lineTo(last.x + cos(a) * endR, last.y + sin(a) * endR)
        }
    }
    for (i in cSize - 1 downTo 0) {
        ribbon.lineTo(rightX[i], rightY[i])
    }
    if (!contour[0].isClosed) {
        val first = contour[0]
        val startR = hypot(leftX[0] - rightX[0], leftY[0] - rightY[0]) * 0.5f
        val startBaseAngle = first.angle - (PI.toFloat() * 0.5f)
        for (step in 1..3) {
            val a = startBaseAngle - (step / 4f) * PI.toFloat()
            ribbon.lineTo(first.x + cos(a) * startR, first.y + sin(a) * startR)
        }
    }
    ribbon.close()
}

private fun nextHash(state: Int): Int {
    var x = state
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

/**
 * 2. Lápiz de Grafito Sketchbook ([PencilBrush]):
 * Textured mineral core with paper-tooth micro-grain speckles batched via single drawPoints call.
 */
class PencilBrush : BrushEngine {
    override val brushId: String = "pencil"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseHalfW = (config.strokeWidth * 0.44f).coerceAtLeast(0.65f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.08f, 1.0f)
        val grain = config.grainStrength.coerceIn(0f, 1f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val corePaint = s.paint1
        s.resetFillPaint(
            corePaint,
            config.color,
            (alphaFrac * (0.62f + 0.28f * config.hardness) * 255f).roundToInt().coerceIn(14, 240),
            shader
        )

        val grainPaint = s.paint2
        val speckRadius = (baseHalfW * (0.16f + 0.14f * grain)).coerceIn(0.4f, 2.0f)
        s.resetStrokePaint(
            grainPaint,
            config.color,
            (alphaFrac * (0.18f + 0.44f * grain) * 255f).roundToInt().coerceIn(8, 200),
            speckRadius * 2f,
            shader
        )

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW * 0.9f, corePaint)
                continue
            }
            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY

            var hashState = config.seed xor (cSize * 7919)
            for (i in 0 until cSize) {
                val n = contour[i]
                hashState = nextHash(hashState xor (i * 2654435761L).toInt())
                val tooth = ((hashState and 0xFF) / 255f)
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val paramMul = computeParametricWidthFactor(i, cSize, n, config)
                val toothJitter = (1f - grain * 0.28f) + grain * 0.34f * tooth
                val hw = (baseHalfW * paramMul * toothJitter).coerceAtLeast(0.45f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            fillRoundedRibbonPath(s.path1, contour, leftX, leftY, rightX, rightY)
            canvas.drawPath(s.path1, corePaint)

            if (grain > 0.08f) {
                val spread = baseHalfW * (0.75f + 0.55f * grain)
                val speckStep = (1 + ((config.spacing * 2f).roundToInt())).coerceIn(1, 3)
                val maxSpecks = ((cSize + speckStep - 1) / speckStep) * 2
                s.ensurePointsCapacity(maxSpecks * 2)
                val pts = s.pointsBuf
                var pOut = 0
                var i = 0
                while (i < cSize) {
                    val n = contour[i]
                    val nx = -sin(n.angle)
                    val ny = cos(n.angle)
                    val tx = cos(n.angle)
                    val ty = sin(n.angle)
                    for (g in 0 until 2) {
                        hashState = nextHash(hashState)
                        val offsetN = (((hashState and 0xFF) / 127.5f) - 1f) * spread
                        hashState = nextHash(hashState)
                        val offsetT = (((hashState and 0xFF) / 127.5f) - 1f) * (baseHalfW * 0.5f)
                        pts[pOut++] = n.x + nx * offsetN + tx * offsetT
                        pts[pOut++] = n.y + ny * offsetN + ty * offsetT
                    }
                    i += speckStep
                }
                if (pOut > 0) {
                    canvas.drawPoints(pts, 0, pOut, grainPaint)
                }
            }
        }
    }
}

/**
 * 3. Pincel Artístico Sketchbook ([PaintBrush]):
 * Layered pigment halo + directional bristle streaks for expressive painting.
 */
class PaintBrush : BrushEngine {
    override val brushId: String = "paint_brush"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseHalfW = (config.strokeWidth * 0.62f).coerceAtLeast(1.4f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.10f, 1.0f)
        val hardness = config.hardness.coerceIn(0.1f, 1f)
        val grain = config.grainStrength.coerceIn(0f, 1f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val haloPaint = s.paint1
        s.resetFillPaint(
            haloPaint,
            config.color,
            (alphaFrac * (0.48f - 0.24f * hardness) * 255f).roundToInt().coerceIn(8, 150),
            shader
        )
        val bodyPaint = s.paint2
        s.resetFillPaint(
            bodyPaint,
            config.color,
            (alphaFrac * (0.58f + 0.30f * hardness) * 255f).roundToInt().coerceIn(18, 235),
            shader
        )
        val bristlePaint = s.paint3
        s.resetStrokePaint(
            bristlePaint,
            config.color,
            (alphaFrac * (0.20f + 0.36f * grain) * 255f).roundToInt().coerceIn(10, 175),
            (baseHalfW * (0.18f + 0.18f * grain)).coerceIn(0.8f, 5.5f),
            shader
        )

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                val p = contour[0]
                canvas.drawCircle(p.x, p.y, baseHalfW * 1.15f, haloPaint)
                canvas.drawCircle(p.x, p.y, baseHalfW * 0.88f, bodyPaint)
                continue
            }

            s.ensureRibbonCapacity(cSize)
            s.ensureHaloCapacity(cSize)
            val haloLX = s.haloLX
            val haloLY = s.haloLY
            val haloRX = s.haloRX
            val haloRY = s.haloRY
            val bodyLX = s.leftX
            val bodyLY = s.leftY
            val bodyRX = s.rightX
            val bodyRY = s.rightY

            val streakLeft = s.path2
            val streakRight = s.path3
            streakLeft.rewind()
            streakRight.rewind()

            for (i in 0 until cSize) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val paramMul = computeParametricWidthFactor(i, cSize, n, config)
                val hwBody = (baseHalfW * 0.88f * paramMul).coerceAtLeast(0.75f)
                val hwHalo = (baseHalfW * (1.28f - 0.24f * hardness) * paramMul).coerceAtLeast(1.0f)

                haloLX[i] = n.x + nx * hwHalo
                haloLY[i] = n.y + ny * hwHalo
                haloRX[i] = n.x - nx * hwHalo
                haloRY[i] = n.y - ny * hwHalo

                bodyLX[i] = n.x + nx * hwBody
                bodyLY[i] = n.y + ny * hwBody
                bodyRX[i] = n.x - nx * hwBody
                bodyRY[i] = n.y - ny * hwBody

                val sOff = hwBody * 0.48f
                if (i == 0) {
                    streakLeft.moveTo(n.x + nx * sOff, n.y + ny * sOff)
                    streakRight.moveTo(n.x - nx * sOff, n.y - ny * sOff)
                } else {
                    streakLeft.lineTo(n.x + nx * sOff, n.y + ny * sOff)
                    streakRight.lineTo(n.x - nx * sOff, n.y - ny * sOff)
                }
            }

            fillRoundedRibbonPath(s.path1, contour, haloLX, haloLY, haloRX, haloRY)
            canvas.drawPath(s.path1, haloPaint)
            fillRoundedRibbonPath(s.path1, contour, bodyLX, bodyLY, bodyRX, bodyRY)
            canvas.drawPath(s.path1, bodyPaint)
            if (grain > 0.08f) {
                canvas.drawPath(streakLeft, bristlePaint)
                canvas.drawPath(streakRight, bristlePaint)
            }
        }
    }
}

/**
 * 4. Aerógrafo Sketchbook ([Airbrush]):
 * Soft feathered radial spray stamps spaced evenly along each contour.
 */
class Airbrush : BrushEngine {
    override val brushId: String = "airbrush"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val sprayRadius = (config.strokeWidth * 1.15f).coerceAtLeast(5f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.06f, 1.0f)
        val perStampAlpha = (alphaFrac * (0.16f + 0.18f * config.hardness) * 255f).roundToInt().coerceIn(6, 110)

        val rgb = config.color and 0x00FFFFFF
        val midStop = (0.30f + 0.35f * config.hardness).coerceIn(0.25f, 0.75f)
        val midStopQuant = (midStop * 100f).roundToInt()

        val unitGrad = if (!config.isRainbow) {
            val key = (rgb.toLong() shl 20) or (perStampAlpha.toLong() shl 8) or midStopQuant.toLong()
            val centerColor = (perStampAlpha shl 24) or rgb
            val midColor = ((perStampAlpha * 0.42f).roundToInt().coerceAtLeast(2) shl 24) or rgb
            s.getOrCreateRadial(key, centerColor, midColor, rgb, midStop)
        } else {
            null
        }

        val stampPaint = s.paint1
        stampPaint.reset()
        stampPaint.isAntiAlias = true
        stampPaint.style = Paint.Style.FILL

        val stepDist = max(3.0f, sprayRadius * config.spacing.coerceIn(0.14f, 0.75f))
        val mat = s.matrix

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            if (contour.isEmpty()) continue
            var lastStampX = contour[0].x
            var lastStampY = contour[0].y

            fun drawSprayAt(x: Float, y: Float, radius: Float, progress: Float) {
                val activeShader = if (config.isRainbow) {
                    val idx = ((progress * (rainbowColors.size - 1)).toInt()).coerceIn(0, rainbowColors.size - 1)
                    val cRgb = rainbowColors[idx] and 0x00FFFFFF
                    val rKey = (cRgb.toLong() shl 20) or (perStampAlpha.toLong() shl 8) or midStopQuant.toLong() or (1L shl 50)
                    s.getOrCreateRadial(
                        rKey,
                        (perStampAlpha shl 24) or cRgb,
                        ((perStampAlpha / 2) shl 24) or cRgb,
                        cRgb,
                        midStop
                    )
                } else {
                    unitGrad!!
                }
                mat.setScale(radius, radius)
                mat.postTranslate(x, y)
                activeShader.setLocalMatrix(mat)
                stampPaint.shader = activeShader
                canvas.drawCircle(x, y, radius, stampPaint)
            }

            drawSprayAt(lastStampX, lastStampY, sprayRadius * contour[0].pressure, contour[0].progress)
            for (i in 1 until contour.size) {
                val n = contour[i]
                val dist = hypot(n.x - lastStampX, n.y - lastStampY)
                if (dist >= stepDist || i == contour.lastIndex) {
                    val subSteps = (dist / stepDist).toInt().coerceIn(1, 6)
                    val r = sprayRadius * (0.5f + 0.5f * n.pressure)
                    for (st in 1..subSteps) {
                        val t = st / subSteps.toFloat()
                        val sx = lastStampX + (n.x - lastStampX) * t
                        val sy = lastStampY + (n.y - lastStampY) * t
                        drawSprayAt(sx, sy, r, n.progress)
                    }
                    lastStampX = n.x
                    lastStampY = n.y
                }
            }
        }
        stampPaint.shader = null
    }
}

/**
 * 5. Estilógrafo Técnico ([TechnicalInkBrush]):
 * Constant-caliber precision drafting ink pen with minimal velocity drop and sharp joins.
 */
class TechnicalInkBrush : BrushEngine {
    override val brushId: String = "technical_ink"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseHalfW = (config.strokeWidth * 0.40f).coerceAtLeast(0.6f)
        val alphaInt = ((config.alpha * config.flow).coerceIn(0.20f, 1.0f) * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = s.paint1
        s.resetFillPaint(paint, config.color, alphaInt, shader)

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW, paint)
                continue
            }
            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY

            for (i in 0 until cSize) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val hw = (baseHalfW * (0.94f + 0.06f * n.pressure)).coerceAtLeast(0.55f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }
            fillRoundedRibbonPath(s.path1, contour, leftX, leftY, rightX, rightY)
            canvas.drawPath(s.path1, paint)
        }
    }
}

/**
 * 6. Rotulador Biselado ([ChiselMarkerBrush]):
 * Calibrated flat chisel felt tip whose width varies cleanly by stroke tangent vs chiselAngleDeg.
 */
class ChiselMarkerBrush : BrushEngine {
    override val brushId: String = "chisel_marker"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val halfNib = (config.strokeWidth * 0.68f).coerceAtLeast(1.5f)
        val chiselRad = config.chiselAngleDeg * (PI.toFloat() / 180f)
        val nibCos = cos(chiselRad)
        val nibSin = sin(chiselRad)
        val effectiveAlpha = (config.alpha * config.flow).coerceIn(0.15f, 1.0f)
        val alphaInt = (effectiveAlpha * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = s.paint1
        s.resetFillPaint(paint, config.color, alphaInt, shader)
        val path = s.path1

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                val p = contour[0]
                val dx = nibCos * halfNib
                val dy = nibSin * halfNib
                val perpX = -nibSin * halfNib * 0.30f
                val perpY = nibCos * halfNib * 0.30f
                path.rewind()
                path.moveTo(p.x - dx - perpX, p.y - dy - perpY)
                path.lineTo(p.x + dx - perpX, p.y + dy - perpY)
                path.lineTo(p.x + dx + perpX, p.y + dy + perpY)
                path.lineTo(p.x - dx + perpX, p.y - dy + perpY)
                path.close()
                canvas.drawPath(path, paint)
                continue
            }

            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY

            for (i in 0 until cSize) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val tx = cos(n.angle)
                val ty = sin(n.angle)
                val align = abs(nx * nibCos + ny * nibSin)
                val paramMul = computeParametricWidthFactor(i, cSize, n, config)
                val hw = (halfNib * (0.34f + 0.66f * align) * paramMul).coerceAtLeast(0.8f)
                val bevelShift = if (!n.isClosed && (i == 0 || i == cSize - 1)) hw * 0.32f else 0f
                leftX[i] = n.x + nx * hw + tx * bevelShift
                leftY[i] = n.y + ny * hw + ty * bevelShift
                rightX[i] = n.x - nx * hw - tx * bevelShift
                rightY[i] = n.y - ny * hw - ty * bevelShift
            }

            path.rewind()
            path.moveTo(leftX[0], leftY[0])
            for (i in 1 until cSize) path.lineTo(leftX[i], leftY[i])
            for (i in cSize - 1 downTo 0) path.lineTo(rightX[i], rightY[i])
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}

/**
 * 7. Acuarela Húmeda ([WatercolorBrush]):
 * Multi-pass translucent pigment wash with soft radial blooms and wet-edge pigment pooling.
 */
class WatercolorBrush : BrushEngine {
    override val brushId: String = "watercolor"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val s = threadScratch.get()!!
        val baseHalfW = (config.strokeWidth * 0.72f).coerceAtLeast(2.0f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.08f, 0.92f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val washPaint = s.paint1
        s.resetFillPaint(
            washPaint,
            config.color,
            (alphaFrac * 0.42f * 255f).roundToInt().coerceIn(8, 130),
            shader
        )
        val wetEdgePaint = s.paint2
        s.resetStrokePaint(
            wetEdgePaint,
            config.color,
            (alphaFrac * 0.52f * 255f).roundToInt().coerceIn(10, 150),
            (baseHalfW * 0.22f).coerceIn(1.0f, 4.5f),
            shader
        )

        val rgb = config.color and 0x00FFFFFF
        val bloomAlpha = (alphaFrac * 0.16f * 255f).roundToInt().coerceIn(4, 58)
        val bloomKey = (rgb.toLong() shl 20) or (bloomAlpha.toLong() shl 8) or 55L or (2L shl 50)
        val bloomGrad = s.getOrCreateRadial(
            bloomKey,
            (bloomAlpha shl 24) or rgb,
            ((bloomAlpha / 2) shl 24) or rgb,
            rgb,
            0.55f
        )
        val bloomPaint = s.paint3
        bloomPaint.reset()
        bloomPaint.isAntiAlias = true
        bloomPaint.style = Paint.Style.FILL
        bloomPaint.shader = bloomGrad
        val mat = s.matrix

        for (cIdx in contours.indices) {
            val contour = contours[cIdx]
            val cSize = contour.size
            if (cSize == 1) {
                val p = contour[0]
                val br = baseHalfW * 1.4f
                mat.setScale(br, br)
                mat.postTranslate(p.x, p.y)
                bloomGrad.setLocalMatrix(mat)
                canvas.drawCircle(p.x, p.y, br, bloomPaint)
                canvas.drawCircle(p.x, p.y, baseHalfW * 0.85f, washPaint)
                continue
            }

            s.ensureRibbonCapacity(cSize)
            val leftX = s.leftX
            val leftY = s.leftY
            val rightX = s.rightX
            val rightY = s.rightY
            var hashState = config.seed xor cSize

            for (i in 0 until cSize) {
                val n = contour[i]
                hashState = nextHash(hashState xor i)
                val organicBleed = 0.88f + 0.24f * ((hashState and 0xFF) / 255f) * config.grainStrength
                val paramMul = computeParametricWidthFactor(i, cSize, n, config)
                val hw = (baseHalfW * paramMul * organicBleed).coerceAtLeast(1.2f)
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            fillRoundedRibbonPath(s.path1, contour, leftX, leftY, rightX, rightY)
            canvas.drawPath(s.path1, washPaint)
            canvas.drawPath(s.path1, wetEdgePaint)

            val step = max(3, (baseHalfW * 0.45f).roundToInt())
            var i = 0
            while (i < cSize) {
                val n = contour[i]
                val r = baseHalfW * 1.25f * computeParametricWidthFactor(i, cSize, n, config)
                mat.setScale(r, r)
                mat.postTranslate(n.x, n.y)
                bloomGrad.setLocalMatrix(mat)
                canvas.drawCircle(n.x, n.y, r, bloomPaint)
                i += step
            }
        }
        bloomPaint.shader = null
    }
}

/**
 * 8. Borrador Suave ([SoftEraserBrush]):
 * Feathered CLEAR radial brush for smooth alpha attenuation on layer bitmaps.
 */
class SoftEraserBrush : BrushEngine {
    override val brushId: String = "soft_eraser"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        if (nodes.isEmpty()) return
        val s = threadScratch.get()!!
        val radius = (config.strokeWidth * 0.85f).coerceAtLeast(4f)
        val maxAlpha = (config.alpha * config.flow * 255f).roundToInt().coerceIn(16, 255)
        val midStop = (0.35f + 0.45f * config.hardness).coerceIn(0.2f, 0.9f)
        val midQuant = (midStop * 100f).roundToInt()
        val key = (maxAlpha.toLong() shl 8) or midQuant.toLong() or (3L shl 50)
        val unitGrad = s.getOrCreateRadial(
            key,
            maxAlpha shl 24,
            (maxAlpha * 0.45f).roundToInt() shl 24,
            0,
            midStop
        )
        val clearPaint = s.paint1
        clearPaint.reset()
        clearPaint.isAntiAlias = true
        clearPaint.style = Paint.Style.FILL
        clearPaint.shader = unitGrad
        clearPaint.xfermode = s.dstOutXfermode

        val mat = s.matrix
        val step = max(2f, radius * 0.25f)
        var lastX = nodes[0].x
        var lastY = nodes[0].y
        for (i in nodes.indices) {
            val n = nodes[i]
            val dist = hypot(n.x - lastX, n.y - lastY)
            if (i == 0 || dist >= step || i == nodes.lastIndex) {
                val r = radius * n.pressure
                mat.setScale(r, r)
                mat.postTranslate(n.x, n.y)
                unitGrad.setLocalMatrix(mat)
                canvas.drawCircle(n.x, n.y, r, clearPaint)
                lastX = n.x
                lastY = n.y
            }
        }
        clearPaint.shader = null
        clearPaint.xfermode = null
    }
}

/**
 * Central registry mapping app [StrokeBrushType] values to their Stage 3 [BrushEngine] implementations.
 */
object BrushEngineRegistry {
    private val pencil = PencilBrush()
    private val highlighter = HighlighterBrush()
    private val fountainPen = FountainPenBrush()
    private val technicalInk = TechnicalInkBrush()
    private val fineMarker = FineMarkerBrush()
    private val chiselMarker = ChiselMarkerBrush()
    private val paintBrush = PaintBrush()
    private val watercolor = WatercolorBrush()
    private val airbrush = Airbrush()
    val softEraser = SoftEraserBrush()

    fun forBrushType(brushType: StrokeBrushType, strokeStyle: StrokeStyle = StrokeStyle.SOLID): BrushEngine {
        return when (brushType) {
            StrokeBrushType.PENCIL -> pencil
            StrokeBrushType.FOUNTAIN_PEN -> fountainPen
            StrokeBrushType.TECHNICAL_INK -> technicalInk
            StrokeBrushType.FINE_MARKER -> fineMarker
            StrokeBrushType.CHISEL_MARKER -> chiselMarker
            StrokeBrushType.PAINT_BRUSH -> paintBrush
            StrokeBrushType.WATERCOLOR -> watercolor
            StrokeBrushType.AIRBRUSH -> airbrush
            StrokeBrushType.HIGHLIGHTER -> highlighter
        }
    }

    fun allCoreBrushes(): List<BrushEngine> = listOf(
        pencil,
        fountainPen,
        technicalInk,
        fineMarker,
        chiselMarker,
        paintBrush,
        watercolor,
        airbrush,
        highlighter
    )
}
