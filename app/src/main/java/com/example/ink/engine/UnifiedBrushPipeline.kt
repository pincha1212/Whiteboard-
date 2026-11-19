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
    // Bound retained node data by an estimated byte budget instead of an entry count.
    // A few very long strokes can otherwise consume much more memory than hundreds of small ones.
    private val nodeCache = object : LruCache<String, List<BrushNode>>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<BrushNode>): Int {
            return (value.size.toLong() * 48L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1)
        }
    }

    private data class CachedShapeFillPath(
        val path: Path,
        val estimatedBytes: Int
    )

    private val fillPathCache = object : LruCache<String, CachedShapeFillPath>(1024 * 1024) {
        override fun sizeOf(key: String, value: CachedShapeFillPath): Int = value.estimatedBytes
    }
    private val fillPaintCache = LruCache<Int, Paint>(32)

    // Reuse short-lived effect paints per rendering thread. The pipeline is also
    // used by export paths, so a process-wide mutable Paint would be unsafe.
    private val neonCorePaint = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            alpha = 240
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
    private val chalkSpecklePaint = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    }

    fun clearNodeCache() {
        nodeCache.evictAll()
        fillPathCache.evictAll()
        fillPaintCache.evictAll()
    }

    private fun cachedFillPath(shape: ShapeItem, geometryKey: String): Path {
        val key = "FILL:$geometryKey"
        fillPathCache.get(key)?.let { return it.path }

        // Store shape-local geometry so camera pan and zoom never invalidate the fill Path.
        val path = ShapeGenerator.fromShapeItem(shape, -shape.x, -shape.y, 1f)
        val estimatedBytes = (256L + shape.vertices.size.toLong() * 48L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(256)
        fillPathCache.put(key, CachedShapeFillPath(path, estimatedBytes))
        return path
    }

    private fun cachedFillPaint(color: Int): Paint {
        fillPaintCache.get(color)?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        fillPaintCache.put(color, paint)
        return paint
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
            capStyle = capStyle
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
        viewportH: Float = 0f,
        preserveInputPoints: Boolean = false
    ) {
        if (stroke.points.size < 2) return
        val safeScale = scale.coerceAtLeast(0.05f)
        val worldWidth = stroke.width.coerceAtLeast(1.5f)
        // A live preview wraps a mutable point list. Its bounds would require an O(n)
        // rescan on every pointer update, while the preview is already known to be active.
        val isLivePreview = stroke.id == "live_active_stroke"
        if (!isLivePreview && viewportW > 0f && viewportH > 0f) {
            val bounds = stroke.geometryMetrics
            if (bounds.hasPoints) {
                val sMinX = bounds.minX * safeScale + panX
                val sMaxX = bounds.maxX * safeScale + panX
                val sMinY = bounds.minY * safeScale + panY
                val sMaxY = bounds.maxY * safeScale + panY
                val margin = worldWidth * safeScale * 3.5f + 64f
                if (sMaxX < -margin || sMinX > viewportW + margin || sMaxY < -margin || sMinY > viewportH + margin) {
                    return
                }
            }
        }
        val effectiveBrushType = if (stroke.isHighlighter) StrokeBrushType.HIGHLIGHTER else stroke.brushType
        val engine = BrushEngineRegistry.forBrushType(effectiveBrushType, stroke.strokeStyle)
        val colorInt = safeParseColor(stroke.colorHex, Color.BLACK)

        // Freehand strokes must stay on the captured pointer path; no organic offset or lag.
        val effectiveHandDrawn = 0f

        val config = BrushConfig(
            color = colorInt,
            strokeWidth = worldWidth,
            alpha = stroke.opacity.coerceIn(0.15f, 1.0f),
            handDrawnFactor = effectiveHandDrawn,
            isDarkBackground = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            isRainbow = stroke.isRainbow,
            strokeStyle = stroke.strokeStyle,
            animPhase = animPhase,
            seed = stroke.id.hashCode()
        )

        val buildNodes = {
            val rawPath = ShapeGenerator.fromStrokePoints(stroke.points, simplify = false)
            val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(rawPath, config.strokeStyle, config.strokeWidth, stroke.capStyle)
            PathHumanizer.humanizePath(
                styledPath,
                effectiveHandDrawn,
                PathHumanizer.DEFAULT_STEP_PX,
                config.seed,
                stableOpenContours = preserveInputPoints
            )
        }
        // Only the mutable live preview bypasses the cache. Committed strokes can
        // preserve their captured input path while reusing the generated brush nodes.
        val nodes: List<BrushNode> = if (isLivePreview) {
            buildNodes()
        } else {
            val cacheKey = "S:${stroke.id}:${stroke.geometryMetrics.pointsHash}:${stroke.width}:${stroke.strokeStyle.name}:${stroke.capStyle.name}:${effectiveBrushType.name}:${preserveInputPoints}:${(effectiveHandDrawn * 100).roundToInt()}"
            nodeCache.get(cacheKey) ?: buildNodes().also { built ->
                if (built.isNotEmpty()) nodeCache.put(cacheKey, built)
            }
        }
        if (nodes.isEmpty()) return

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(safeScale, safeScale)
        try {
            if (isSelected) {
                val haloConfig = BrushConfig(
                    color = Color.parseColor("#2563EB"),
                    strokeWidth = worldWidth + 6f,
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
        val geometryKey = "${shape.id}:${shape.shapeType.name}:${shape.width.toBits()}:${shape.height.toBits()}:${shape.relativeVerticesHash}"

        // Reuse the same closed Path across frames; only the camera matrix changes while panning/zooming.
        if (shape.isFilled && !shape.shapeType.isLineLike) {
            val fillPath = cachedFillPath(shape, geometryKey)
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            try {
                canvas.drawPath(fillPath, cachedFillPaint(fillColorInt))
            } finally {
                canvas.restore()
            }
        }

        // Route shape outline through unified Stage 1 -> Stage 2 -> Stage 3 pipeline
        // Cached in shape-local world coordinates (0f, 0f at scale = 1f) so pan, zoom, and move keep 100% cache hits
        val engine = BrushEngineRegistry.forBrushType(shape.brushType, shape.strokeStyle)
        val effectiveHandDrawn = when {
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

        val cacheKey = "SHW:$geometryKey:${shape.strokeWidth.toBits()}:${shape.strokeStyle.name}:${effectiveCap.name}:${shape.brushType.name}:${(effectiveHandDrawn * 100).roundToInt()}"
        val worldNodes: List<BrushNode> = nodeCache.get(cacheKey) ?: run {
            val outlinePath = ShapeGenerator.fromShapeItem(shape, -shape.x, -shape.y, 1f)
            val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(outlinePath, shape.strokeStyle, shape.strokeWidth.coerceAtLeast(1.5f), effectiveCap)
            val built = PathHumanizer.humanizePath(styledPath, effectiveHandDrawn, PathHumanizer.DEFAULT_STEP_PX, localConfig.seed)
            if (built.isNotEmpty()) nodeCache.put(cacheKey, built)
            built
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

    /** Render every remaining stroke style as a plain solid pass. */
    fun renderNodesWithDecorators(
        canvas: Canvas,
        nodes: List<BrushNode>,
        brushEngine: BrushEngine,
        config: BrushConfig
    ) {
        brushEngine.render(canvas, nodes, config)
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
        val dotPaint = chalkSpecklePaint.get()!!
        dotPaint.color = config.color
        dotPaint.alpha = 255
        dotPaint.shader = null
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
