package com.example.ink.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.LruCache
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import com.example.model.isLineLike
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Unified 4-Stage Pipeline Coordinator connecting:
 * 1. [ShapeGenerator] -> pure [Path]
 * 2. [PathHumanizer] -> uniform 2px [BrushNode] list with [handDrawnFactor]
 * 3. [BrushEngine] -> procedural organic brush rendering (no flat direct drawPath strokes)
 *
 * Used by [UnifiedDrawingView], Compose whiteboard rendering (`CanvasDrawUtils` / `AdvancedLineEffects`),
 * and [com.example.data.BoardExportService] so screen, live view, previews, and exported bitmaps match identically.
 */
object UnifiedBrushPipeline {

    const val DEFAULT_HAND_DRAWN_FACTOR = 0.24f
    private val nodeCache = LruCache<String, List<BrushNode>>(256)

    fun clearNodeCache() {
        nodeCache.evictAll()
    }

    fun renderCommand(
        canvas: Canvas,
        command: ShapeCommand,
        brushEngine: BrushEngine,
        config: BrushConfig,
        timestampsMs: List<Long>? = null
    ) {
        val rawPath = ShapeGenerator.generatePath(command)
        renderPathWithPipeline(
            canvas = canvas,
            rawPath = rawPath,
            brushEngine = brushEngine,
            config = config,
            capStyle = if (command is ShapeCommand.PresetShape) command.capStyle else LineCapStyle.NONE,
            timestampsMs = timestampsMs
        )
    }

    fun renderPathWithPipeline(
        canvas: Canvas,
        rawPath: Path,
        brushEngine: BrushEngine,
        config: BrushConfig,
        capStyle: LineCapStyle = LineCapStyle.NONE,
        timestampsMs: List<Long>? = null
    ) {
        if (rawPath.isEmpty) return

        val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(
            sourcePath = rawPath,
            style = config.strokeStyle,
            strokeWidth = config.strokeWidth,
            capStyle = capStyle,
            animPhase = config.animPhase
        )
        if (styledPath.isEmpty) return

        val nodes = PathHumanizer.humanizePath(
            path = styledPath,
            handDrawnFactor = config.handDrawnFactor,
            stepPx = PathHumanizer.DEFAULT_STEP_PX,
            seed = config.seed,
            timestampsMs = timestampsMs
        )
        if (nodes.isEmpty()) return

        renderNodesWithDecorators(canvas, nodes, brushEngine, config)
    }

    fun renderStroke(
        canvas: Canvas,
        stroke: WhiteboardStroke,
        panX: Float = 0f,
        panY: Float = 0f,
        scale: Float = 1f,
        animPhase: Float = 0f,
        isSelected: Boolean = false,
        isDarkBoard: Boolean = false,
        isOpaqueCanvasBackground: Boolean = true,
        handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
        viewportW: Float = 0f,
        viewportH: Float = 0f
    ) {
        if (stroke.points.size < 2) return
        if (viewportW > 0f && viewportH > 0f) {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (pt in stroke.points) {
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.y > maxY) maxY = pt.y
            }
            val sMinX = minX * scale + panX
            val sMaxX = maxX * scale + panX
            val sMinY = minY * scale + panY
            val sMaxY = maxY * scale + panY
            val margin = stroke.width * scale * 2f + 40f
            if (sMaxX < -margin || sMinX > viewportW + margin || sMaxY < -margin || sMinY > viewportH + margin) {
                return
            }
        }
        val scaledWidth = (stroke.width * scale).coerceAtLeast(1.5f)
        val effectiveBrushType = if (stroke.isHighlighter) StrokeBrushType.HIGHLIGHTER else stroke.brushType
        val engine = BrushEngineRegistry.forBrushType(effectiveBrushType, stroke.strokeStyle)
        val colorInt = safeParseColor(stroke.colorHex, Color.BLACK)

        val effectiveHandDrawn = when {
            stroke.strokeStyle == StrokeStyle.SKETCH -> max(handDrawnFactor, 0.48f)
            effectiveBrushType == StrokeBrushType.FINE_MARKER -> min(handDrawnFactor, 0.16f)
            else -> handDrawnFactor
        }

        val config = BrushConfig(
            color = colorInt,
            strokeWidth = scaledWidth,
            alpha = stroke.opacity.coerceIn(0.15f, 1.0f),
            handDrawnFactor = effectiveHandDrawn,
            isDarkBackground = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            isRainbow = stroke.isRainbow,
            strokeStyle = stroke.strokeStyle,
            animPhase = animPhase,
            seed = stroke.id.hashCode()
        )

        val nodes: List<BrushNode> = if (stroke.strokeStyle == StrokeStyle.ANIMATED_PULSE) {
            val worldScaledPts = stroke.points.map { StrokePoint(it.x * scale, it.y * scale) }
            val rawPath = ShapeGenerator.fromStrokePoints(worldScaledPts)
            val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(rawPath, config.strokeStyle, config.strokeWidth, stroke.capStyle, animPhase)
            PathHumanizer.humanizePath(styledPath, effectiveHandDrawn, PathHumanizer.DEFAULT_STEP_PX, config.seed)
        } else {
            val cacheKey = "S:${stroke.id}:${stroke.points.hashCode()}:${(scale * 1000).roundToInt()}:${stroke.width}:${stroke.strokeStyle.name}:${stroke.capStyle.name}:${effectiveBrushType.name}:${(effectiveHandDrawn * 100).roundToInt()}"
            nodeCache.get(cacheKey) ?: run {
                val worldScaledPts = stroke.points.map { StrokePoint(it.x * scale, it.y * scale) }
                val rawPath = ShapeGenerator.fromStrokePoints(worldScaledPts)
                val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(rawPath, config.strokeStyle, config.strokeWidth, stroke.capStyle, 0f)
                val built = PathHumanizer.humanizePath(styledPath, effectiveHandDrawn, PathHumanizer.DEFAULT_STEP_PX, config.seed)
                if (built.isNotEmpty()) nodeCache.put(cacheKey, built)
                built
            }
        }
        if (nodes.isEmpty()) return

        canvas.save()
        canvas.translate(panX, panY)
        try {
            if (isSelected) {
                val haloConfig = BrushConfig(
                    color = Color.parseColor("#2563EB"),
                    strokeWidth = scaledWidth + 6f,
                    alpha = 0.32f,
                    handDrawnFactor = 0f,
                    isDarkBackground = isDarkBoard,
                    isOpaqueCanvasBackground = isOpaqueCanvasBackground,
                    strokeStyle = StrokeStyle.SOLID,
                    seed = stroke.id.hashCode()
                )
                FineMarkerBrush().render(canvas, nodes, haloConfig)
            }
            renderNodesWithDecorators(canvas, nodes, engine, config)
        } finally {
            canvas.restore()
        }
    }

    private val cachedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    fun renderShape(
        canvas: Canvas,
        shape: ShapeItem,
        panX: Float = 0f,
        panY: Float = 0f,
        scale: Float = 1f,
        animPhase: Float = 0f,
        isDarkBoard: Boolean = false,
        isOpaqueCanvasBackground: Boolean = true,
        handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
        drawLabel: Boolean = true,
        viewportW: Float = 0f,
        viewportH: Float = 0f
    ) {
        if (shape.isHiddenByCollapse) return

        val safeScale = scale.coerceAtLeast(0.05f)
        val x = shape.x * scale + panX
        val y = shape.y * scale + panY
        val w = shape.width * scale
        val h = shape.height * scale
        if (viewportW > 0f && viewportH > 0f) {
            val margin = shape.strokeWidth * scale * 2f + 40f
            if (x + w < -margin || x > viewportW + margin || y + h < -margin || y > viewportH + margin) {
                return
            }
        }
        val cx = x + w / 2f
        val cy = y + h / 2f
        val effectiveRotation = if (shape.shapeType == ShapeType.LINE || (shape.shapeType.isLineLike && shape.vertices.size >= 2)) {
            0f
        } else {
            shape.rotation
        }

        canvas.save()
        if (effectiveRotation != 0f) {
            canvas.rotate(effectiveRotation, cx, cy)
        }

        val strokeColorInt = safeParseColor(shape.strokeColorHex, Color.parseColor("#2563EB"))
        val fillColorInt = safeParseColor(shape.fillColorHex, Color.parseColor("#DBEAFE"))
        val localStrokeWidth = max(shape.strokeWidth, 1.5f / safeScale)
        val effectiveCap = when (shape.shapeType) {
            ShapeType.ARROW -> LineCapStyle.END_ARROW
            ShapeType.DOUBLE_ARROW -> LineCapStyle.BOTH_ARROWS
            else -> shape.capStyle
        }

        // Fill region underneath if enabled on closed shapes
        ShapeGenerator.generateFillPath(shape, panX, panY, scale)?.let { fillPath ->
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = fillColorInt
            }
            canvas.drawPath(fillPath, fillPaint)
        }

        // Route shape outline through unified Stage 1 -> Stage 2 -> Stage 3 pipeline
        // Cached in shape-local world coordinates (0f, 0f at scale = 1f) so pan, zoom, and move keep 100% cache hits
        val engine = BrushEngineRegistry.forBrushType(shape.brushType, shape.strokeStyle)
        val effectiveHandDrawn = when {
            shape.strokeStyle == StrokeStyle.SKETCH -> max(handDrawnFactor, 0.48f)
            shape.brushType == StrokeBrushType.FINE_MARKER -> min(handDrawnFactor, 0.16f)
            else -> handDrawnFactor
        }

        val localConfig = BrushConfig(
            color = strokeColorInt,
            strokeWidth = localStrokeWidth,
            alpha = shape.opacity.coerceIn(0.15f, 1.0f),
            handDrawnFactor = effectiveHandDrawn,
            isDarkBackground = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            isRainbow = shape.isRainbow,
            strokeStyle = shape.strokeStyle,
            animPhase = animPhase,
            seed = shape.id.hashCode()
        )

        val worldNodes: List<BrushNode> = if (shape.strokeStyle == StrokeStyle.ANIMATED_PULSE) {
            val outlinePath = ShapeGenerator.fromShapeItem(shape, -shape.x, -shape.y, 1f)
            val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(outlinePath, shape.strokeStyle, shape.strokeWidth.coerceAtLeast(1.5f), effectiveCap, animPhase)
            PathHumanizer.humanizePath(styledPath, effectiveHandDrawn, PathHumanizer.DEFAULT_STEP_PX, localConfig.seed)
        } else {
            var relVertHash = 1
            for (v in shape.vertices) {
                relVertHash = 31 * relVertHash + (v.x - shape.x).toBits()
                relVertHash = 31 * relVertHash + (v.y - shape.y).toBits()
            }
            val cacheKey = "SHW:${shape.id}:${shape.shapeType.name}:${shape.width.toBits()}:${shape.height.toBits()}:$relVertHash:${shape.strokeWidth.toBits()}:${shape.strokeStyle.name}:${effectiveCap.name}:${shape.brushType.name}:${(effectiveHandDrawn * 100).roundToInt()}"
            nodeCache.get(cacheKey) ?: run {
                val outlinePath = ShapeGenerator.fromShapeItem(shape, -shape.x, -shape.y, 1f)
                val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(outlinePath, shape.strokeStyle, shape.strokeWidth.coerceAtLeast(1.5f), effectiveCap, 0f)
                val built = PathHumanizer.humanizePath(styledPath, effectiveHandDrawn, PathHumanizer.DEFAULT_STEP_PX, localConfig.seed)
                if (built.isNotEmpty()) nodeCache.put(cacheKey, built)
                built
            }
        }
        if (worldNodes.isNotEmpty()) {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            try {
                renderNodesWithDecorators(canvas, worldNodes, engine, localConfig)
            } finally {
                canvas.restore()
            }
        }

        if (drawLabel && shape.label.isNotBlank() && !shape.shapeType.isLineLike) {
            cachedLabelPaint.color = when {
                shape.isFilled -> Color.parseColor("#0F172A")
                isDarkBoard -> Color.WHITE
                else -> strokeColorInt
            }
            var targetSize = (15.5f * scale).coerceIn(9f, 34f)
            cachedLabelPaint.textSize = targetSize
            val maxLabelWidth = (w * 0.88f).coerceAtLeast(24f)
            val measuredW = cachedLabelPaint.measureText(shape.label)
            if (measuredW > maxLabelWidth && measuredW > 1f) {
                targetSize = (targetSize * (maxLabelWidth / measuredW)).coerceAtLeast(7f)
                cachedLabelPaint.textSize = targetSize
            }
            val fm = cachedLabelPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(shape.label, cx, textY, cachedLabelPaint)
        }

        canvas.restore()
    }

    /**
     * Composes atmospheric & specialty [StrokeStyle] passes (Neon Glow, Animated Pulse, Chalk dabs)
     * seamlessly around the polymorphic [BrushEngine.render] execution.
     */
    fun renderNodesWithDecorators(
        canvas: Canvas,
        nodes: List<BrushNode>,
        brushEngine: BrushEngine,
        config: BrushConfig
    ) {
        when (config.strokeStyle) {
            StrokeStyle.NEON_GLOW -> {
                val outerHalo = config.copy(
                    strokeWidth = config.strokeWidth * 3.1f,
                    alpha = if (config.isDarkBackground) 0.22f else 0.15f,
                    strokeStyle = StrokeStyle.SOLID
                )
                val midHalo = config.copy(
                    strokeWidth = config.strokeWidth * 1.85f,
                    alpha = if (config.isDarkBackground) 0.44f else 0.30f,
                    strokeStyle = StrokeStyle.SOLID
                )
                brushEngine.render(canvas, nodes, outerHalo)
                brushEngine.render(canvas, nodes, midHalo)
                brushEngine.render(canvas, nodes, config.copy(alpha = (config.alpha * 0.92f).coerceIn(0.4f, 1f)))

                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = Color.WHITE
                    alpha = 240
                    strokeWidth = (config.strokeWidth * 0.30f).coerceAtLeast(1.2f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                drawNodeSpine(canvas, nodes, corePaint)
            }
            StrokeStyle.ANIMATED_PULSE -> {
                val pulseAlpha = 0.20f + 0.14f * sin(config.animPhase * 2f * PI.toFloat())
                brushEngine.render(
                    canvas,
                    nodes,
                    config.copy(
                        strokeWidth = config.strokeWidth * 1.72f,
                        alpha = (config.alpha * pulseAlpha).coerceIn(0.08f, 0.45f)
                    )
                )
                brushEngine.render(canvas, nodes, config)
            }
            StrokeStyle.CHALK_TEXTURE -> {
                brushEngine.render(
                    canvas,
                    nodes,
                    config.copy(
                        strokeWidth = config.strokeWidth * 1.55f,
                        alpha = (config.alpha * 0.25f).coerceIn(0.10f, 0.40f)
                    )
                )
                brushEngine.render(canvas, nodes, config.copy(alpha = (config.alpha * 0.72f).coerceIn(0.20f, 0.85f)))
                renderChalkSpeckles(canvas, nodes, config)
            }
            else -> {
                brushEngine.render(canvas, nodes, config)
            }
        }
    }

    private fun drawNodeSpine(canvas: Canvas, nodes: List<BrushNode>, paint: Paint) {
        val contours = splitNodeContours(nodes)
        val path = Path()
        for (c in contours) {
            if (c.size < 2) continue
            path.reset()
            path.moveTo(c[0].x, c[0].y)
            for (i in 1 until c.size) {
                path.lineTo(c[i].x, c[i].y)
            }
            if (c[0].isClosed) path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun renderChalkSpeckles(canvas: Canvas, nodes: List<BrushNode>, config: BrushConfig) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.color
        }
        val spread = (config.strokeWidth * 0.48f).coerceAtLeast(2f)
        val dotR = (config.strokeWidth * 0.20f).coerceIn(0.9f, 2.8f)
        for (i in nodes.indices step 3) {
            val n = nodes[i]
            val nx = -sin(n.angle)
            val ny = cos(n.angle)
            val shift = when ((i / 3) % 3) {
                0 -> spread
                1 -> -spread * 0.9f
                else -> spread * 0.35f
            }
            dotPaint.alpha = (config.alpha * (if ((i / 3) % 2 == 0) 0.44f else 0.26f) * 255f)
                .roundToInt()
                .coerceIn(18, 160)
            canvas.drawCircle(n.x + nx * shift, n.y + ny * shift, dotR, dotPaint)
        }
    }

    fun safeParseColor(hex: String, default: Int = Color.BLACK): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            default
        }
    }
}
