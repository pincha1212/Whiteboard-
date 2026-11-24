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
import android.graphics.RadialGradient
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
        val effectiveAlpha = (config.alpha * config.flow).coerceIn(0.12f, 1.0f)
        val alphaInt = (effectiveAlpha * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = alphaInt
            this.shader = shader
        }

        for (contour in contours) {
            if (contour.size == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW * contour[0].pressure, paint)
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
                val paramMul = computeParametricWidthFactor(i, contour.size, n, config)
                val hw = (baseHalfW * styleMul * paramMul).coerceAtLeast(0.55f)
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
 * 2. Lápiz de Grafito Sketchbook ([PencilBrush]):
 * Textured mineral core with paper-tooth micro-grain speckles modulated by speed.
 */
class PencilBrush : BrushEngine {
    override val brushId: String = "pencil"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val baseHalfW = (config.strokeWidth * 0.44f).coerceAtLeast(0.65f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.08f, 1.0f)
        val grain = config.grainStrength.coerceIn(0f, 1f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = (alphaFrac * (0.62f + 0.28f * config.hardness) * 255f).roundToInt().coerceIn(14, 240)
            this.shader = shader
        }
        val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = (alphaFrac * (0.18f + 0.44f * grain) * 255f).roundToInt().coerceIn(8, 200)
            this.shader = shader
        }

        for (contour in contours) {
            if (contour.size == 1) {
                canvas.drawCircle(contour[0].x, contour[0].y, baseHalfW * 0.9f, corePaint)
                continue
            }
            val leftX = FloatArray(contour.size)
            val leftY = FloatArray(contour.size)
            val rightX = FloatArray(contour.size)
            val rightY = FloatArray(contour.size)

            var hashState = config.seed xor (contour.size * 7919)
            for (i in contour.indices) {
                val n = contour[i]
                hashState = nextHash(hashState xor (i * 2654435761L).toInt())
                val tooth = ((hashState and 0xFF) / 255f)
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val paramMul = computeParametricWidthFactor(i, contour.size, n, config)
                val toothJitter = (1f - grain * 0.28f) + grain * 0.34f * tooth
                val hw = (baseHalfW * paramMul * toothJitter).coerceAtLeast(0.45f)
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
            canvas.drawPath(ribbon, corePaint)

            if (grain > 0.08f) {
                val speckRadius = (baseHalfW * (0.16f + 0.14f * grain)).coerceIn(0.4f, 2.0f)
                val spread = baseHalfW * (0.75f + 0.55f * grain)
                val speckStep = (1 + ((config.spacing * 2f).roundToInt())).coerceIn(1, 3)
                for (i in contour.indices step speckStep) {
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
                        val gx = n.x + nx * offsetN + tx * offsetT
                        val gy = n.y + ny * offsetN + ty * offsetT
                        canvas.drawCircle(gx, gy, speckRadius, grainPaint)
                    }
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

        val baseHalfW = (config.strokeWidth * 0.62f).coerceAtLeast(1.4f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.10f, 1.0f)
        val hardness = config.hardness.coerceIn(0.1f, 1f)
        val grain = config.grainStrength.coerceIn(0f, 1f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = (alphaFrac * (0.48f - 0.24f * hardness) * 255f).roundToInt().coerceIn(8, 150)
            this.shader = shader
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = (alphaFrac * (0.58f + 0.30f * hardness) * 255f).roundToInt().coerceIn(18, 235)
            this.shader = shader
        }
        val bristlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (baseHalfW * (0.18f + 0.18f * grain)).coerceIn(0.8f, 5.5f)
            color = config.color
            alpha = (alphaFrac * (0.20f + 0.36f * grain) * 255f).roundToInt().coerceIn(10, 175)
            this.shader = shader
        }

        for (contour in contours) {
            if (contour.size == 1) {
                val p = contour[0]
                canvas.drawCircle(p.x, p.y, baseHalfW * 1.15f, haloPaint)
                canvas.drawCircle(p.x, p.y, baseHalfW * 0.88f, bodyPaint)
                continue
            }

            val haloLX = FloatArray(contour.size)
            val haloLY = FloatArray(contour.size)
            val haloRX = FloatArray(contour.size)
            val haloRY = FloatArray(contour.size)
            val bodyLX = FloatArray(contour.size)
            val bodyLY = FloatArray(contour.size)
            val bodyRX = FloatArray(contour.size)
            val bodyRY = FloatArray(contour.size)

            val streakLeft = Path()
            val streakRight = Path()

            for (i in contour.indices) {
                val n = contour[i]
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                val paramMul = computeParametricWidthFactor(i, contour.size, n, config)
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

            canvas.drawPath(buildRoundedRibbonPath(contour, haloLX, haloLY, haloRX, haloRY), haloPaint)
            canvas.drawPath(buildRoundedRibbonPath(contour, bodyLX, bodyLY, bodyRX, bodyRY), bodyPaint)
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

        val sprayRadius = (config.strokeWidth * 1.15f).coerceAtLeast(5f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.06f, 1.0f)
        val perStampAlpha = (alphaFrac * (0.16f + 0.18f * config.hardness) * 255f).roundToInt().coerceIn(6, 110)

        val rgb = config.color and 0x00FFFFFF
        val centerColor = (perStampAlpha shl 24) or rgb
        val midStop = (0.30f + 0.35f * config.hardness).coerceIn(0.25f, 0.75f)
        val midColor = ((perStampAlpha * 0.42f).roundToInt().coerceAtLeast(2) shl 24) or rgb
        val edgeColor = rgb

        val unitRadial = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(centerColor, midColor, edgeColor),
            floatArrayOf(0f, midStop, 1f),
            Shader.TileMode.CLAMP
        )

        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = unitRadial
        }

        val stepDist = max(2.5f, sprayRadius * config.spacing.coerceIn(0.10f, 0.75f))
        val rainbowPalette = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA
        )

        for (contour in contours) {
            if (contour.isEmpty()) continue
            var lastStampX = contour[0].x
            var lastStampY = contour[0].y

            fun drawSprayAt(x: Float, y: Float, radius: Float, progress: Float) {
                if (config.isRainbow) {
                    val idx = ((progress * (rainbowPalette.size - 1)).toInt()).coerceIn(0, rainbowPalette.size - 1)
                    val cRgb = rainbowPalette[idx] and 0x00FFFFFF
                    stampPaint.shader = RadialGradient(
                        0f, 0f, 1f,
                        intArrayOf((perStampAlpha shl 24) or cRgb, ((perStampAlpha / 2) shl 24) or cRgb, cRgb),
                        floatArrayOf(0f, midStop, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.save()
                canvas.translate(x, y)
                canvas.scale(radius, radius)
                canvas.drawCircle(0f, 0f, 1f, stampPaint)
                canvas.restore()
            }

            drawSprayAt(lastStampX, lastStampY, sprayRadius * contour[0].pressure, contour[0].progress)
            for (i in 1 until contour.size) {
                val n = contour[i]
                val dist = hypot(n.x - lastStampX, n.y - lastStampY)
                if (dist >= stepDist || i == contour.lastIndex) {
                    val subSteps = (dist / stepDist).toInt().coerceIn(1, 8)
                    val r = sprayRadius * (0.5f + 0.5f * n.pressure)
                    for (s in 1..subSteps) {
                        val t = s / subSteps.toFloat()
                        val sx = lastStampX + (n.x - lastStampX) * t
                        val sy = lastStampY + (n.y - lastStampY) * t
                        drawSprayAt(sx, sy, r, n.progress)
                    }
                    lastStampX = n.x
                    lastStampY = n.y
                }
            }
        }
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

        val baseHalfW = (config.strokeWidth * 0.40f).coerceAtLeast(0.6f)
        val alphaInt = ((config.alpha * config.flow).coerceIn(0.20f, 1.0f) * 255f).roundToInt()
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
                val hw = (baseHalfW * (0.94f + 0.06f * n.pressure)).coerceAtLeast(0.55f)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }
            canvas.drawPath(buildRoundedRibbonPath(contour, leftX, leftY, rightX, rightY), paint)
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

        val halfNib = (config.strokeWidth * 0.68f).coerceAtLeast(1.5f)
        val chiselRad = config.chiselAngleDeg * (PI.toFloat() / 180f)
        val nibCos = cos(chiselRad)
        val nibSin = sin(chiselRad)
        val effectiveAlpha = (config.alpha * config.flow).coerceIn(0.15f, 1.0f)
        val alphaInt = (effectiveAlpha * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = alphaInt
            this.shader = shader
        }

        for (contour in contours) {
            if (contour.size == 1) {
                val p = contour[0]
                val dx = nibCos * halfNib
                val dy = nibSin * halfNib
                val perpX = -nibSin * halfNib * 0.30f
                val perpY = nibCos * halfNib * 0.30f
                val tip = Path().apply {
                    moveTo(p.x - dx - perpX, p.y - dy - perpY)
                    lineTo(p.x + dx - perpX, p.y + dy - perpY)
                    lineTo(p.x + dx + perpX, p.y + dy + perpY)
                    lineTo(p.x - dx + perpX, p.y - dy + perpY)
                    close()
                }
                canvas.drawPath(tip, paint)
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
                val align = abs(nx * nibCos + ny * nibSin)
                val paramMul = computeParametricWidthFactor(i, contour.size, n, config)
                val hw = (halfNib * (0.34f + 0.66f * align) * paramMul).coerceAtLeast(0.8f)
                val bevelShift = if (!n.isClosed && (i == 0 || i == contour.lastIndex)) hw * 0.32f else 0f
                leftX[i] = n.x + nx * hw + tx * bevelShift
                leftY[i] = n.y + ny * hw + ty * bevelShift
                rightX[i] = n.x - nx * hw - tx * bevelShift
                rightY[i] = n.y - ny * hw - ty * bevelShift
            }

            val path = Path().apply {
                moveTo(leftX[0], leftY[0])
                for (i in 1 until contour.size) lineTo(leftX[i], leftY[i])
                for (i in contour.lastIndex downTo 0) lineTo(rightX[i], rightY[i])
                close()
            }
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

        val baseHalfW = (config.strokeWidth * 0.72f).coerceAtLeast(2.0f)
        val alphaFrac = (config.alpha * config.flow).coerceIn(0.08f, 0.92f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = (alphaFrac * 0.42f * 255f).roundToInt().coerceIn(8, 130)
            this.shader = shader
        }
        val wetEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = (baseHalfW * 0.22f).coerceIn(1.0f, 4.5f)
            color = config.color
            alpha = (alphaFrac * 0.52f * 255f).roundToInt().coerceIn(10, 150)
            this.shader = shader
        }

        val rgb = config.color and 0x00FFFFFF
        val bloomAlpha = (alphaFrac * 0.16f * 255f).roundToInt().coerceIn(4, 58)
        val bloomGrad = RadialGradient(
            0f, 0f, 1f,
            intArrayOf((bloomAlpha shl 24) or rgb, ((bloomAlpha / 2) shl 24) or rgb, rgb),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.shader = bloomGrad
        }

        for (contour in contours) {
            if (contour.size == 1) {
                val p = contour[0]
                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.scale(baseHalfW * 1.4f, baseHalfW * 1.4f)
                canvas.drawCircle(0f, 0f, 1f, bloomPaint)
                canvas.restore()
                canvas.drawCircle(p.x, p.y, baseHalfW * 0.85f, washPaint)
                continue
            }

            val leftX = FloatArray(contour.size)
            val leftY = FloatArray(contour.size)
            val rightX = FloatArray(contour.size)
            val rightY = FloatArray(contour.size)
            var hashState = config.seed xor contour.size

            for (i in contour.indices) {
                val n = contour[i]
                hashState = nextHash(hashState xor i)
                val organicBleed = 0.88f + 0.24f * ((hashState and 0xFF) / 255f) * config.grainStrength
                val paramMul = computeParametricWidthFactor(i, contour.size, n, config)
                val hw = (baseHalfW * paramMul * organicBleed).coerceAtLeast(1.2f)
                val nx = -sin(n.angle)
                val ny = cos(n.angle)
                leftX[i] = n.x + nx * hw
                leftY[i] = n.y + ny * hw
                rightX[i] = n.x - nx * hw
                rightY[i] = n.y - ny * hw
            }

            val washRibbon = buildRoundedRibbonPath(contour, leftX, leftY, rightX, rightY)
            canvas.drawPath(washRibbon, washPaint)
            canvas.drawPath(washRibbon, wetEdgePaint)

            val step = max(3, (baseHalfW * 0.45f).roundToInt())
            for (i in contour.indices step step) {
                val n = contour[i]
                val r = baseHalfW * 1.25f * computeParametricWidthFactor(i, contour.size, n, config)
                canvas.save()
                canvas.translate(n.x, n.y)
                canvas.scale(r, r)
                canvas.drawCircle(0f, 0f, 1f, bloomPaint)
                canvas.restore()
            }
        }
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
        val radius = (config.strokeWidth * 0.85f).coerceAtLeast(4f)
        val maxAlpha = (config.alpha * config.flow * 255f).roundToInt().coerceIn(16, 255)
        val unitGrad = RadialGradient(
            0f, 0f, 1f,
            intArrayOf((maxAlpha shl 24), ((maxAlpha * 0.45f).roundToInt() shl 24), 0),
            floatArrayOf(0f, (0.35f + 0.45f * config.hardness).coerceIn(0.2f, 0.9f), 1f),
            Shader.TileMode.CLAMP
        )
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = unitGrad
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        val step = max(2f, radius * 0.25f)
        var lastX = nodes[0].x
        var lastY = nodes[0].y
        for (i in nodes.indices) {
            val n = nodes[i]
            val dist = hypot(n.x - lastX, n.y - lastY)
            if (i == 0 || dist >= step || i == nodes.lastIndex) {
                canvas.save()
                canvas.translate(n.x, n.y)
                canvas.scale(radius * n.pressure, radius * n.pressure)
                canvas.drawCircle(0f, 0f, 1f, clearPaint)
                canvas.restore()
                lastX = n.x
                lastY = n.y
            }
        }
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
