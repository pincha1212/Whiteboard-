package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import com.example.model.StrokeBrushType
import com.example.model.StrokeStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
    val seed: Int = 1337
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
 * Splits a flat [List<BrushNode>] returned by [PathHumanizer] back into contiguous contours
 * by [BrushNode.contourIndex] so disconnected dashes/shapes never bridge unintentionally.
 */
internal fun splitNodeContours(nodes: List<BrushNode>): List<List<BrushNode>> {
    if (nodes.isEmpty()) return emptyList()
    val contours = mutableListOf<List<BrushNode>>()
    var current = mutableListOf<BrushNode>()
    var activeContour = nodes[0].contourIndex
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

internal fun computeNodeBounds(nodes: List<BrushNode>, padding: Float): RectF {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (n in nodes) {
        if (n.x < minX) minX = n.x
        if (n.x > maxX) maxX = n.x
        if (n.y < minY) minY = n.y
        if (n.y > maxY) maxY = n.y
    }
    if (minX == Float.MAX_VALUE) return RectF(0f, 0f, 1f, 1f)
    val safePad = padding.coerceAtLeast(4f)
    return RectF(minX - safePad, minY - safePad, maxX + safePad, maxY + safePad)
}

internal fun computeNodeWidthMultiplier(node: BrushNode, style: StrokeStyle): Float {
    return 1f
}

internal fun buildDynamicShaderIfNeeded(nodes: List<BrushNode>, config: BrushConfig): Shader? {
    if (!config.isRainbow) return null
    if (nodes.isEmpty()) return null
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (n in nodes) {
        if (n.x < minX) minX = n.x
        if (n.x > maxX) maxX = n.x
        if (n.y < minY) minY = n.y
        if (n.y > maxY) maxY = n.y
    }
    val endX = if (hypot(maxX - minX, maxY - minY) < 4f) minX + 16f else maxX
    val endY = if (hypot(maxX - minX, maxY - minY) < 4f) minY + 16f else maxY
    val colors = if (config.isRainbow) {
        intArrayOf(
            Color.RED,
            Color.YELLOW,
            Color.GREEN,
            Color.CYAN,
            Color.MAGENTA
        )
    } else {
        intArrayOf(
            config.color,
            Color.parseColor("#38BDF8"),
            Color.parseColor("#A855F7"),
            Color.parseColor("#FB7185"),
            Color.parseColor("#FACC15")
        )
    }
    return LinearGradient(minX, minY, endX, endY, colors, null, Shader.TileMode.CLAMP)
}

/**
 * 1. Resaltador ([HighlighterBrush]):
 * Translucent broad chiseled-tip highlighter rendered into an isolated offscreen layer
 * at 100% internal fill opacity (with ZERO center-axis stroke pass) and composited once
 * at `0.30f` alpha using true MULTIPLY blending ([BlendMode.MULTIPLY] on API 29+, or
 * white-backed offscreen compositing with [PorterDuff.Mode.MULTIPLY] on API 24-28).
 */
class HighlighterBrush(
    private val chiselAngleDegrees: Float = 32f
) : BrushEngine {
    override val brushId: String = "highlighter"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

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
        val layerBounds = computeNodeBounds(nodes, halfNib * 1.8f + 6f)

        // Single pure FILL paint at 100% opacity inside the offscreen layer; never render both fill + stroke.
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = 255
            this.shader = shader
        }

        val ribbonPaths = buildList {
            for (contour in contours) {
                if (contour.size == 1) {
                    val p = contour[0]
                    val dx = nibCos * halfNib
                    val dy = nibSin * halfNib
                    val perpX = -nibSin * halfNib * 0.35f
                    val perpY = nibCos * halfNib * 0.35f
                    add(
                        Path().apply {
                            moveTo(p.x - dx - perpX, p.y - dy - perpY)
                            lineTo(p.x + dx - perpX, p.y + dy - perpY)
                            lineTo(p.x + dx + perpX, p.y + dy + perpY)
                            lineTo(p.x - dx + perpX, p.y - dy + perpY)
                            close()
                        }
                    )
                    continue
                }

                val leftX = FloatArray(contour.size)
                val leftY = FloatArray(contour.size)
                val rightX = FloatArray(contour.size)
                val rightY = FloatArray(contour.size)

                for (i in contour.indices) {
                    val n = contour[i]
                    val nx = -sin(n.angle)
                    val ny = cos(n.angle)
                    val tx = cos(n.angle)
                    val ty = sin(n.angle)
                    val widthMod = computeNodeWidthMultiplier(n, config.strokeStyle)
                    val align = abs(nx * nibCos + ny * nibSin)
                    val hw = (halfNib * (0.64f + 0.36f * align) * widthMod).coerceAtLeast(1.2f)

                    // Bevel open endpoints slightly along tangent for authentic slanted marker tips
                    val bevelShift = if (!n.isClosed && (i == 0 || i == contour.lastIndex)) {
                        hw * 0.38f
                    } else {
                        0f
                    }
                    leftX[i] = n.x + nx * hw + tx * bevelShift
                    leftY[i] = n.y + ny * hw + ty * bevelShift
                    rightX[i] = n.x - nx * hw - tx * bevelShift
                    rightY[i] = n.y - ny * hw - ty * bevelShift
                }

                add(
                    Path().apply {
                        moveTo(leftX[0], leftY[0])
                        for (i in 1 until contour.size) {
                            lineTo(leftX[i], leftY[i])
                        }
                        for (i in contour.lastIndex downTo 0) {
                            lineTo(rightX[i], rightY[i])
                        }
                        close()
                    }
                )
            }
        }

        if (useMultiply && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 24-28 fallback: PorterDuff.Mode.MULTIPLY is kModulate (Sc * Dc) without (1 - Sa),
            // so composite 100%-opaque stroke onto a white-backed layer at targetAlpha and multiply once.
            val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            val saveCount = canvas.saveLayer(layerBounds, layerPaint)
            try {
                val whiteBg = Paint().apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
                canvas.drawRect(layerBounds, whiteBg)
                val innerSave = canvas.saveLayer(
                    layerBounds,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = targetAlpha }
                )
                try {
                    for (path in ribbonPaths) {
                        canvas.drawPath(path, fillPaint)
                    }
                } finally {
                    canvas.restoreToCount(innerSave)
                }
            } finally {
                canvas.restoreToCount(saveCount)
            }
            return
        }

        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = targetAlpha
            if (useMultiply && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // BlendMode.MULTIPLY (W3C multiply) preserves background outside the stroke (Sa=0)
                // and blends translucent colors faithfully (e.g. black at 0.30f -> neutral 70% luminance).
                blendMode = BlendMode.MULTIPLY
            }
        }

        val saveCount = canvas.saveLayer(layerBounds, layerPaint)
        try {
            for (path in ribbonPaths) {
                canvas.drawPath(path, fillPaint)
            }
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

        val baseHalfW = (config.strokeWidth * 0.5f).coerceAtLeast(0.85f)
        val alphaInt = (config.alpha.coerceIn(0.18f, 1.0f) * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = alphaInt
            this.shader = shader
        }

        for (contour in contours) {
            if (contour.size == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW, paint)
                continue
            }

            val leftX = FloatArray(contour.size)
            val leftY = FloatArray(contour.size)
            val rightX = FloatArray(contour.size)
            val rightY = FloatArray(contour.size)

            for (i in contour.indices) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val styleMul = computeNodeWidthMultiplier(n, config.strokeStyle)
                val taperEnd = if (n.isClosed) {
                    1f
                } else {
                    (0.76f + 0.24f * sin(n.progress * PI.toFloat()).coerceIn(0f, 1f))
                }
                val velScale = (1.08f - 0.24f * n.velocity).coerceIn(0.76f, 1.10f)
                val hw = (baseHalfW * styleMul * taperEnd * velScale).coerceAtLeast(0.65f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            val ribbon = buildRoundedRibbonPath(
                contour = contour,
                leftX = leftX,
                leftY = leftY,
                rightX = rightX,
                rightY = rightY
            )
            canvas.drawPath(ribbon, paint)
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

        val baseHalfW = (config.strokeWidth * 0.38f).coerceAtLeast(0.75f)
        val alphaInt = (config.alpha.coerceIn(0.20f, 1.0f) * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = alphaInt
            this.shader = shader
        }

        for (contour in contours) {
            if (contour.size == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW, paint)
                continue
            }
            val leftX = FloatArray(contour.size)
            val leftY = FloatArray(contour.size)
            val rightX = FloatArray(contour.size)
            val rightY = FloatArray(contour.size)

            for (i in contour.indices) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val hw = (baseHalfW * computeNodeWidthMultiplier(n, config.strokeStyle)).coerceAtLeast(0.65f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            val ribbon = buildRoundedRibbonPath(
                contour = contour,
                leftX = leftX,
                leftY = leftY,
                rightX = rightX,
                rightY = rightY
            )
            canvas.drawPath(ribbon, paint)
        }
    }
}

private fun buildRoundedRibbonPath(
    contour: List<BrushNode>,
    leftX: FloatArray,
    leftY: FloatArray,
    rightX: FloatArray,
    rightY: FloatArray
): Path {
    val ribbon = Path()
    ribbon.moveTo(leftX[0], leftY[0])
    for (i in 1 until contour.size) {
        ribbon.lineTo(leftX[i], leftY[i])
    }
    if (!contour[0].isClosed) {
        val last = contour.last()
        val endR = hypot(leftX.last() - rightX.last(), leftY.last() - rightY.last()) * 0.5f
        val endBaseAngle = last.angle + (PI.toFloat() * 0.5f)
        for (step in 1..3) {
            val a = endBaseAngle - (step / 4f) * PI.toFloat()
            ribbon.lineTo(last.x + cos(a) * endR, last.y + sin(a) * endR)
        }
    }
    for (i in contour.lastIndex downTo 0) {
        ribbon.lineTo(rightX[i], rightY[i])
    }
    if (!contour[0].isClosed) {
        val first = contour.first()
        val startR = hypot(leftX[0] - rightX[0], leftY[0] - rightY[0]) * 0.5f
        val startBaseAngle = first.angle - (PI.toFloat() * 0.5f)
        for (step in 1..3) {
            val a = startBaseAngle - (step / 4f) * PI.toFloat()
            ribbon.lineTo(first.x + cos(a) * startR, first.y + sin(a) * startR)
        }
    }
    ribbon.close()
    return ribbon
}

private fun nextHash(state: Int): Int {
    var x = state
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

/**
 * Central registry mapping app [StrokeBrushType] values to their Stage 3 [BrushEngine] implementations.
 */
object BrushEngineRegistry {
    private val highlighter = HighlighterBrush()
    private val fountainPen = FountainPenBrush()
    private val fineMarker = FineMarkerBrush()

    fun forBrushType(brushType: StrokeBrushType, strokeStyle: StrokeStyle = StrokeStyle.SOLID): BrushEngine {
        return when (brushType) {
            StrokeBrushType.FOUNTAIN_PEN -> fountainPen
            StrokeBrushType.FINE_MARKER -> fineMarker
            StrokeBrushType.HIGHLIGHTER -> highlighter
        }
    }

    fun allCoreBrushes(): List<BrushEngine> = listOf(
        highlighter
    )
}
