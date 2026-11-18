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
 * 2. Pincel de Cerdas / Óleo ([BristleBrush]):
 * Computes the normal vector perpendicular to each [BrushNode.angle] and distributes a balanced fan
 * of [bristleCount] (default 20) parallel organic micro-strands inside an offscreen layer so
 * overlapping central bristles never create a dark central stripe.
 */
class BristleBrush(
    private val bristleCount: Int = DEFAULT_BRISTLE_COUNT
) : BrushEngine {
    override val brushId: String = "bristle"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val count = bristleCount.coerceAtLeast(4)
        val totalWidth = (config.strokeWidth * 1.24f).coerceAtLeast(3f)
        val layerAlpha = (config.alpha.coerceIn(0.18f, 0.96f) * 255f).roundToInt()
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        // Precompute balanced characteristics across all 20 bristles (uniform coverage, no dark core band)
        val strandOffsets = FloatArray(count)
        val strandWidths = FloatArray(count)
        val strandAlphas = IntArray(count)

        var rng = (config.seed * 747796405 + 2891336453L).toInt()
        for (b in 0 until count) {
            rng = nextHash(rng)
            val normIdx = (b / (count - 1).coerceAtLeast(1).toFloat()) - 0.5f // -0.5..0.5
            val microOffset = (((rng and 0xFF) / 255f) - 0.5f) * 0.04f
            strandOffsets[b] = normIdx + microOffset

            rng = nextHash(rng)
            val widthJitter = 0.72f + ((rng and 0xFF) / 255f) * 0.56f
            strandWidths[b] = ((totalWidth / count.toFloat()) * 1.18f * widthJitter).coerceAtLeast(0.75f)

            rng = nextHash(rng)
            val radialWeight = (1f - abs(normIdx) * 0.24f).coerceIn(0.76f, 1f)
            val alphaJitter = 0.72f + ((rng and 0xFF) / 255f) * 0.28f
            strandAlphas[b] = (242f * radialWeight * alphaJitter).roundToInt().coerceIn(130, 250)

        }

        val layerBounds = computeNodeBounds(nodes, totalWidth * 0.9f + 6f)
        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = layerAlpha
        }

        val bristlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = config.color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.shader = shader
        }

        val saveCount = canvas.saveLayer(layerBounds, layerPaint)
        try {
            val strandPath = Path()
            for (contour in contours) {
                if (contour.size < 2) continue

                for (b in 0 until count) {
                    strandPath.reset()
                    val offsetFrac = strandOffsets[b]
                    var hasMove = false

                    for (i in contour.indices) {
                        val n = contour[i]
                        val nx = -sin(n.angle)
                        val ny = cos(n.angle)
                        val widthMod = computeNodeWidthMultiplier(n, config.strokeStyle)
                        val endSplay = if (n.isClosed) {
                            1f
                        } else {
                            1f + 0.28f * (1f - sin(n.progress * PI.toFloat()).coerceIn(0f, 1f))
                        }
                        // Keep strand spacing spatially stable; no synthetic lateral wobble.
                        val latDist = totalWidth * offsetFrac * widthMod * endSplay
                        val px = n.x + nx * latDist
                        val py = n.y + ny * latDist
                        if (!hasMove) {
                            strandPath.moveTo(px, py)
                            hasMove = true
                        } else {
                            strandPath.lineTo(px, py)
                        }
                    }
                    if (contour[0].isClosed) strandPath.close()

                    bristlePaint.strokeWidth = strandWidths[b]
                    bristlePaint.alpha = strandAlphas[b]
                    canvas.drawPath(strandPath, bristlePaint)
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    companion object {
        const val DEFAULT_BRISTLE_COUNT = 10
    }
}


/**
 * 4. Pluma Caligráfica ([CalligraphyBrush]):
 * Fixed flat chisel nib rotated at 45° (configurable [nibAngleDegrees]) generating closed polygon quads
 * whose cross-sectional thickness varies dramatically with the angle between stroke motion and nib orientation.
 */
class CalligraphyBrush(
    private val nibAngleDegrees: Float = 45f,
    private val minThicknessRatio: Float = 0.16f,
    private val velocityModulation: Boolean = true
) : BrushEngine {
    override val brushId: String = "calligraphy"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val contours = splitNodeContours(nodes)
        if (contours.isEmpty()) return

        val baseHalfW = (config.strokeWidth * 0.72f).coerceAtLeast(1.8f)
        val nibRad = nibAngleDegrees * (PI.toFloat() / 180f)
        val nibCos = cos(nibRad)
        val nibSin = sin(nibRad)

        val shader = buildDynamicShaderIfNeeded(nodes, config)
        val alphaInt = (config.alpha.coerceIn(0.18f, 1.0f) * 255f).roundToInt()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            alpha = alphaInt
            this.shader = shader
        }

        val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = config.color
            alpha = alphaInt
            strokeWidth = (baseHalfW * minThicknessRatio * 1.4f).coerceAtLeast(1.0f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.shader = shader
        }

        val quadPath = Path()
        val hairlinePath = Path()

        for (contour in contours) {
            if (contour.isEmpty()) continue
            if (contour.size == 1) {
                val p = contour[0]
                val kx = nibCos * baseHalfW
                val ky = nibSin * baseHalfW
                val px = -nibSin * baseHalfW * minThicknessRatio
                val py = nibCos * baseHalfW * minThicknessRatio
                quadPath.reset()
                quadPath.moveTo(p.x - kx - px, p.y - ky - py)
                quadPath.lineTo(p.x + kx - px, p.y + ky - py)
                quadPath.lineTo(p.x + kx + px, p.y + ky + py)
                quadPath.lineTo(p.x - kx + px, p.y - ky + py)
                quadPath.close()
                canvas.drawPath(quadPath, fillPaint)
                continue
            }

            // Fine ink spine ensures clean joins even where direction aligns with 45° nib axis
            hairlinePath.reset()
            hairlinePath.moveTo(contour[0].x, contour[0].y)
            for (i in 1 until contour.size) {
                hairlinePath.lineTo(contour[i].x, contour[i].y)
            }
            if (contour[0].isClosed) hairlinePath.close()
            canvas.drawPath(hairlinePath, hairlinePaint)

            // Batch quad ribbons per contour for fast sharp calligraphy edges
            val segCount = if (contour[0].isClosed) contour.size else contour.lastIndex
            for (i in 0 until segCount) {
                val n0 = contour[i]
                val n1 = contour[(i + 1) % contour.size]

                val w0 = computeCalligraphyHalfNib(n0, baseHalfW, config.strokeStyle)
                val w1 = computeCalligraphyHalfNib(n1, baseHalfW, config.strokeStyle)

                val kx0 = nibCos * w0
                val ky0 = nibSin * w0
                val kx1 = nibCos * w1
                val ky1 = nibSin * w1

                quadPath.reset()
                quadPath.moveTo(n0.x - kx0, n0.y - ky0)
                quadPath.lineTo(n0.x + kx0, n0.y + ky0)
                quadPath.lineTo(n1.x + kx1, n1.y + ky1)
                quadPath.lineTo(n1.x - kx1, n1.y - ky1)
                quadPath.close()
                canvas.drawPath(quadPath, fillPaint)
            }
        }
    }

    private fun computeCalligraphyHalfNib(
        node: BrushNode,
        baseHalfW: Float,
        style: StrokeStyle
    ): Float {
        val styleMul = computeNodeWidthMultiplier(node, style)
        val velScale = if (velocityModulation) (1.06f - 0.24f * node.velocity).coerceIn(0.75f, 1.08f) else 1f
        return (baseHalfW * styleMul * velScale).coerceAtLeast(0.8f)
    }
}

/**
 * 5. Aerógrafo ([Airbrush]):
 * Sprays radial mist particles around each [BrushNode] with true Box-Muller Gaussian distribution,
 * velocity-modulated density, and deterministic seeded PRNG so redraws never shimmer randomly.
 */
class Airbrush(
    private val particlesPerNode: Int = DEFAULT_PARTICLES_PER_NODE
) : BrushEngine {
    override val brushId: String = "airbrush"

    override fun render(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        if (nodes.isEmpty()) return

        val baseRadius = (config.strokeWidth * 1.45f).coerceAtLeast(4f)
        val sigma = baseRadius * 0.48f
        val maxSprayRadius = baseRadius * 1.45f
        val baseAlpha = config.alpha.coerceIn(0.18f, 1.0f)
        val shader = buildDynamicShaderIfNeeded(nodes, config)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            this.shader = shader
        }

        // Core soft mist halo behind crisp Gaussian spray dots
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
            this.shader = shader
        }

        val dotRadiusBase = (config.strokeWidth * 0.14f).coerceIn(0.8f, 2.8f)

        for (nodeIdx in nodes.indices) {
            val n = nodes[nodeIdx]
            val styleMul = computeNodeWidthMultiplier(n, config.strokeStyle)
            val nodeSigma = sigma * styleMul
            val nodeMaxR = maxSprayRadius * styleMul

            // Slight translucent mist center every ~2 nodes
            if (nodeIdx % 2 == 0) {
                corePaint.alpha = (baseAlpha * (0.08f - 0.03f * n.velocity) * 255f).roundToInt().coerceIn(4, 34)
                canvas.drawCircle(n.x, n.y, nodeSigma * 0.9f, corePaint)
            }

            // Deterministic per-node PRNG seed for flicker-free Gaussian droplets
            var rng = nextHash(config.seed xor (nodeIdx * 1597334677) xor (n.contourIndex * 381201580))
            val speedFactor = (1.12f - 0.45f * n.velocity).coerceIn(0.45f, 1.15f)
            val count = (particlesPerNode * speedFactor).roundToInt().coerceIn(6, 36)

            for (p in 0 until count) {
                rng = nextHash(rng)
                val u1 = (((rng ushr 8) and 0xFFFFFF) / 16777216f).coerceIn(0.0001f, 0.9999f)
                rng = nextHash(rng)
                val u2 = ((rng ushr 8) and 0xFFFFFF) / 16777216f

                // Box-Muller Gaussian transform
                val mag = nodeSigma * sqrt(-2f * ln(u1))
                val theta = 2f * PI.toFloat() * u2
                val dx = (mag * cos(theta)).coerceIn(-nodeMaxR, nodeMaxR)
                val dy = (mag * sin(theta)).coerceIn(-nodeMaxR, nodeMaxR)

                val distSq = dx * dx + dy * dy
                val gaussianFalloff = exp(-distSq / (2f * nodeSigma * nodeSigma).coerceAtLeast(0.1f))

                rng = nextHash(rng)
                val sizeVar = 0.65f + ((rng and 0xFF) / 255f) * 0.95f
                val dropRadius = (dotRadiusBase * sizeVar).coerceIn(0.6f, 3.2f)
                val dropAlpha = (baseAlpha * 0.34f * gaussianFalloff * speedFactor * 255f)
                    .roundToInt()
                    .coerceIn(10, 145)

                dotPaint.alpha = dropAlpha
                canvas.drawCircle(n.x + dx, n.y + dy, dropRadius, dotPaint)
            }
        }
    }

    companion object {
        const val DEFAULT_PARTICLES_PER_NODE = 8
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
    private val bristle = BristleBrush()
    private val calligraphy = CalligraphyBrush(nibAngleDegrees = 45f)
    private val airbrush = Airbrush()
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
