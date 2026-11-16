package com.example.ink.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.model.LineCapStyle
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.isLineLike
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 4 of the Unified 2D Rendering Pipeline: Central Custom Canvas View.
 *
 * Manages:
 * - The offscreen confirmed cache [Bitmap] + backing [Canvas].
 * - Real-time organic stroke rendering while dragging (both freehand gestures via [MotionEvent]
 *   and live stretching of preset [ShapeType] shapes).
 * - Final bake onto the cache [Bitmap] on [MotionEvent.ACTION_UP] using the unified
 *   [ShapeGenerator] -> [PathHumanizer] -> [BrushEngine] pipeline.
 */
class UnifiedDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class CommittedLayerEntry(
        val nodes: List<BrushNode>,
        val brushEngine: BrushEngine,
        val config: BrushConfig,
        val fillPath: Path? = null,
        val fillColor: Int = Color.TRANSPARENT
    )

    private var confirmedBitmap: Bitmap? = null
    private var confirmedCanvas: Canvas? = null

    private var boardBackgroundColor: Int = Color.WHITE
    private var activeBrushEngine: BrushEngine = CalligraphyBrush()
    private var activeBrushConfig: BrushConfig = BrushConfig(
        color = Color.parseColor("#0F172A"),
        strokeWidth = 10f,
        alpha = 1f,
        handDrawnFactor = 0.25f,
        isDarkBackground = false,
        isOpaqueCanvasBackground = true,
        strokeStyle = StrokeStyle.SOLID
    )

    private var activeShapeType: ShapeType? = null
    private var isShapeFilled: Boolean = false
    private var shapeFillColor: Int = Color.parseColor("#DBEAFE")
    private var activeCapStyle: LineCapStyle = LineCapStyle.NONE

    private val touchSession = ShapeGenerator.TouchStrokeSession()
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragEndX = 0f
    private var dragEndY = 0f
    private var strokeCounter = 1

    private val committedEntries = mutableListOf<CommittedLayerEntry>()
    private val bitmapPaint = Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)

    var onStrokeCommittedListener: ((List<TouchSample>, List<BrushNode>, BrushConfig) -> Unit)? = null
    var onShapeCommittedListener: ((ShapeType, RectF, List<BrushNode>, BrushConfig) -> Unit)? = null

    val committedCount: Int
        get() = committedEntries.size

    fun setBoardBackgroundColor(color: Int) {
        boardBackgroundColor = color
        val lum = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
        activeBrushConfig = activeBrushConfig.copy(isDarkBackground = lum < 0.45f)
        rebakeConfirmedCache()
        invalidate()
    }

    fun setBrushEngine(engine: BrushEngine) {
        activeBrushEngine = engine
    }

    fun setBrushType(brushType: StrokeBrushType) {
        activeBrushEngine = BrushEngineRegistry.forBrushType(brushType, activeBrushConfig.strokeStyle)
        activeBrushConfig = activeBrushConfig.copy(alpha = brushType.defaultOpacity)
    }

    fun setStrokeColor(color: Int) {
        activeBrushConfig = activeBrushConfig.copy(color = color)
    }

    fun setStrokeWidth(widthPx: Float) {
        activeBrushConfig = activeBrushConfig.copy(strokeWidth = widthPx.coerceAtLeast(1.5f))
    }

    fun setHandDrawnFactor(factor: Float) {
        activeBrushConfig = activeBrushConfig.copy(handDrawnFactor = factor.coerceIn(0f, 1f))
    }

    fun setStrokeStyle(style: StrokeStyle) {
        activeBrushConfig = activeBrushConfig.copy(strokeStyle = style)
    }

    fun setRainbowEnabled(enabled: Boolean) {
        activeBrushConfig = activeBrushConfig.copy(isRainbow = enabled)
    }

    fun setBrushConfig(config: BrushConfig) {
        activeBrushConfig = config
    }

    /**
     * Switches between Freehand mode (`null`) and Preset Shape drag-stretch mode (e.g. [ShapeType.CIRCLE]).
     */
    fun setPresetShapeMode(
        shapeType: ShapeType?,
        filled: Boolean = false,
        fillColor: Int = shapeFillColor,
        capStyle: LineCapStyle = LineCapStyle.NONE
    ) {
        activeShapeType = shapeType
        isShapeFilled = filled
        shapeFillColor = fillColor
        activeCapStyle = capStyle
    }

    /**
     * Imports any external [android.graphics.Path] directly through Stage 2 + Stage 3
     * and bakes it onto the confirmed cache [Bitmap].
     */
    fun drawImportedPath(
        importedPath: Path,
        brushEngine: BrushEngine = activeBrushEngine,
        config: BrushConfig = activeBrushConfig
    ) {
        ensureCacheBitmap()
        val rawPath = ShapeGenerator.generatePath(ShapeCommand.ImportedPath(importedPath))
        val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(rawPath, config.strokeStyle, config.strokeWidth)
        val nodes = PathHumanizer.humanizePath(
            path = styledPath,
            handDrawnFactor = config.handDrawnFactor,
            stepPx = PathHumanizer.DEFAULT_STEP_PX,
            seed = config.seed
        )
        if (nodes.isEmpty()) return
        commitEntryToCache(
            CommittedLayerEntry(
                nodes = nodes,
                brushEngine = brushEngine,
                config = config
            )
        )
        invalidate()
    }

    fun undoLast(): Boolean {
        if (committedEntries.isEmpty()) return false
        committedEntries.removeAt(committedEntries.lastIndex)
        rebakeConfirmedCache()
        invalidate()
        return true
    }

    fun clearCanvas() {
        committedEntries.clear()
        touchSession.reset()
        isDragging = false
        confirmedCanvas?.drawColor(boardBackgroundColor)
        invalidate()
    }

    fun getConfirmedBitmapSnapshot(): Bitmap? {
        val bmp = confirmedBitmap ?: return null
        return bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        allocateCacheBitmap(w, h)
        rebakeConfirmedCache()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        ensureCacheBitmap()
        val presetShape = activeShapeType

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                strokeCounter++
                if (presetShape == null) {
                    touchSession.onTouchEvent(event)
                } else {
                    dragStartX = event.x
                    dragStartY = event.y
                    dragEndX = event.x
                    dragEndY = event.y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                if (presetShape == null) {
                    touchSession.onTouchEvent(event)
                } else {
                    dragEndX = event.x
                    dragEndY = event.y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                if (presetShape == null) {
                    touchSession.onTouchEvent(event)
                    commitFreehandGesture()
                } else {
                    dragEndX = event.x
                    dragEndY = event.y
                    commitPresetShapeGesture(presetShape)
                }
                isDragging = false
                touchSession.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cache = confirmedBitmap
        if (cache != null && !cache.isRecycled) {
            canvas.drawBitmap(cache, 0f, 0f, bitmapPaint)
        } else {
            canvas.drawColor(boardBackgroundColor)
        }

        if (!isDragging) return

        val liveSeed = activeBrushConfig.seed + strokeCounter * 7919
        val liveConfig = activeBrushConfig.copy(seed = liveSeed)
        val preset = activeShapeType

        if (preset == null) {
            val samples = touchSession.currentSamples
            if (samples.isEmpty()) return
            val rawPath = ShapeGenerator.fromTouchSamples(samples)
            val timestamps = samples.map { it.timestampMs }
            UnifiedBrushPipeline.renderPathWithPipeline(
                canvas = canvas,
                rawPath = rawPath,
                brushEngine = activeBrushEngine,
                config = liveConfig,
                capStyle = activeCapStyle,
                timestampsMs = timestamps
            )
        } else {
            val (bounds, vertices, cap) = resolvePresetGeometry(preset)
            val fillPath = if (isShapeFilled && !preset.isLineLike) {
                ShapeGenerator.fromPresetShape(preset, bounds.left, bounds.top, bounds.width(), bounds.height(), vertices, cap)
            } else {
                null
            }
            fillPath?.let { fp ->
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = shapeFillColor
                }
                canvas.drawPath(fp, fillPaint)
            }
            val cmd = ShapeCommand.PresetShape(preset, bounds, vertices, cap)
            UnifiedBrushPipeline.renderCommand(
                canvas = canvas,
                command = cmd,
                brushEngine = activeBrushEngine,
                config = liveConfig
            )
        }
    }

    private fun commitFreehandGesture() {
        val samples = touchSession.currentSamples.toList()
        if (samples.isEmpty()) return

        val rawPath = ShapeGenerator.fromTouchSamples(samples)
        val commitConfig = activeBrushConfig.copy(seed = activeBrushConfig.seed + strokeCounter * 7919)
        val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(
            sourcePath = rawPath,
            style = commitConfig.strokeStyle,
            strokeWidth = commitConfig.strokeWidth,
            capStyle = activeCapStyle
        )
        val nodes = PathHumanizer.humanizePath(
            path = styledPath,
            handDrawnFactor = commitConfig.handDrawnFactor,
            stepPx = PathHumanizer.DEFAULT_STEP_PX,
            seed = commitConfig.seed,
            timestampsMs = samples.map { it.timestampMs }
        )
        if (nodes.isEmpty()) return

        val entry = CommittedLayerEntry(
            nodes = nodes,
            brushEngine = activeBrushEngine,
            config = commitConfig
        )
        commitEntryToCache(entry)
        onStrokeCommittedListener?.invoke(samples, nodes, commitConfig)
    }

    private fun commitPresetShapeGesture(preset: ShapeType) {
        val (bounds, vertices, cap) = resolvePresetGeometry(preset)
        val commitConfig = activeBrushConfig.copy(seed = activeBrushConfig.seed + strokeCounter * 7919)
        val rawPath = ShapeGenerator.fromPresetShape(
            shapeType = preset,
            x = bounds.left,
            y = bounds.top,
            width = bounds.width(),
            height = bounds.height(),
            vertices = vertices,
            capStyle = cap
        )
        val fillPath = if (isShapeFilled && !preset.isLineLike) Path(rawPath) else null
        val styledPath = ShapeGenerator.applyStrokeStyleAndCaps(
            sourcePath = rawPath,
            style = commitConfig.strokeStyle,
            strokeWidth = commitConfig.strokeWidth,
            capStyle = cap
        )
        val nodes = PathHumanizer.humanizePath(
            path = styledPath,
            handDrawnFactor = commitConfig.handDrawnFactor,
            stepPx = PathHumanizer.DEFAULT_STEP_PX,
            seed = commitConfig.seed
        )
        if (nodes.isEmpty()) return

        val entry = CommittedLayerEntry(
            nodes = nodes,
            brushEngine = activeBrushEngine,
            config = commitConfig,
            fillPath = fillPath,
            fillColor = shapeFillColor
        )
        commitEntryToCache(entry)
        onShapeCommittedListener?.invoke(preset, bounds, nodes, commitConfig)
    }

    private fun resolvePresetGeometry(preset: ShapeType): Triple<RectF, List<StrokePoint>, LineCapStyle> {
        val dist = hypot(dragEndX - dragStartX, dragEndY - dragStartY)
        val effectiveCap = when (preset) {
            ShapeType.ARROW -> LineCapStyle.END_ARROW
            ShapeType.DOUBLE_ARROW -> LineCapStyle.BOTH_ARROWS
            else -> activeCapStyle
        }

        if (preset.isLineLike) {
            val p0: StrokePoint
            val p1: StrokePoint
            if (dist < 10f) {
                p0 = StrokePoint(dragStartX - 60f, dragStartY)
                p1 = StrokePoint(dragStartX + 60f, dragStartY)
            } else {
                p0 = StrokePoint(dragStartX, dragStartY)
                p1 = StrokePoint(dragEndX, dragEndY)
            }
            val bounds = RectF(
                min(p0.x, p1.x),
                min(p0.y, p1.y),
                max(p0.x, p1.x).coerceAtLeast(min(p0.x, p1.x) + 1f),
                max(p0.y, p1.y).coerceAtLeast(min(p0.y, p1.y) + 1f)
            )
            return Triple(bounds, listOf(p0, p1), effectiveCap)
        }

        val bounds = if (dist < 10f) {
            RectF(dragStartX - 60f, dragStartY - 60f, dragStartX + 60f, dragStartY + 60f)
        } else {
            val left = min(dragStartX, dragEndX)
            val top = min(dragStartY, dragEndY)
            val w = abs(dragEndX - dragStartX).coerceAtLeast(16f)
            val h = abs(dragEndY - dragStartY).coerceAtLeast(16f)
            RectF(left, top, left + w, top + h)
        }
        return Triple(bounds, emptyList(), effectiveCap)
    }

    private fun ensureCacheBitmap() {
        val targetW = width.coerceAtLeast(1)
        val targetH = height.coerceAtLeast(1)
        val cur = confirmedBitmap
        if (cur == null || cur.isRecycled || cur.width != targetW || cur.height != targetH) {
            if (targetW > 0 && targetH > 0) {
                allocateCacheBitmap(targetW, targetH)
                rebakeConfirmedCache()
            }
        }
    }

    private fun allocateCacheBitmap(w: Int, h: Int) {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        if (confirmedBitmap != null && confirmedBitmap?.width == safeW && confirmedBitmap?.height == safeH && confirmedBitmap?.isRecycled == false) {
            return
        }
        confirmedBitmap?.recycle()
        val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        confirmedBitmap = bmp
        confirmedCanvas = Canvas(bmp).apply {
            drawColor(boardBackgroundColor)
        }
    }

    private fun rebakeConfirmedCache() {
        val cacheCanvas = confirmedCanvas ?: return
        cacheCanvas.drawColor(boardBackgroundColor)
        for (entry in committedEntries) {
            renderEntryOntoCanvas(cacheCanvas, entry)
        }
    }

    private fun commitEntryToCache(entry: CommittedLayerEntry) {
        committedEntries.add(entry)
        val cacheCanvas = confirmedCanvas ?: return
        renderEntryOntoCanvas(cacheCanvas, entry)
    }

    private fun renderEntryOntoCanvas(canvas: Canvas, entry: CommittedLayerEntry) {
        entry.fillPath?.let { fp ->
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = entry.fillColor
            }
            canvas.drawPath(fp, fillPaint)
        }
        UnifiedBrushPipeline.renderNodesWithDecorators(
            canvas = canvas,
            nodes = entry.nodes,
            brushEngine = entry.brushEngine,
            config = entry.config
        )
    }
}
