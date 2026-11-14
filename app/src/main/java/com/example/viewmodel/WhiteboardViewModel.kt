package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.BoardSnapshotSerializer
import com.example.ink.IdeationTemplateFactory
import com.example.ink.PixelEraserGeometry
import com.example.ink.ShapeRecognizer
import com.example.ink.StickyNoteBounds
import com.example.ink.StickyNoteGeometry
import com.example.ink.StickyNoteResizeHandle
import com.example.ink.StrokeStabilizer
import com.example.ink.ViewportTransform
import com.example.model.BoardVersionSnapshot
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.CustomBoardTemplate
import com.example.model.LaserPoint
import com.example.model.LineCapStyle
import com.example.model.RulerState
import com.example.model.ShapeItem
import com.example.model.ShapeRecognitionProposal
import com.example.model.ShapeType
import com.example.model.isLineLike
import com.example.model.StickyNote
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.TemplateType
import com.example.model.TextItem
import com.example.model.ToolType
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

data class WhiteboardUiState(
    val board: WhiteboardBoard,
    val allBoards: List<WhiteboardBoard> = emptyList(),
    val isViewingBoardsHome: Boolean = true,
    val currentTool: ToolType = ToolType.PEN,
    val penColorHex: String = "#1E1E1E",
    val penWidth: Float = 6f,
    val penStyle: StrokeStyle = StrokeStyle.SOLID,
    val penCapStyle: LineCapStyle = LineCapStyle.NONE,
    val isRainbowPen: Boolean = false,
    val autoShapeRecognition: Boolean = false,
    val isRulerActive: Boolean = false,
    val rulerState: RulerState = RulerState(),
    val isSnapToGrid: Boolean = false,
    val isZenMode: Boolean = false,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val scale: Float = 1.0f,
    val viewportWidthPx: Float = 1080f,
    val viewportHeightPx: Float = 1920f,
    val activeStroke: List<StrokePoint>? = null,
    val laserTrail: List<LaserPoint> = emptyList(),
    val selectedStrokeId: String? = null,
    val selectedShapeId: String? = null,
    val selectedNoteId: String? = null,
    val selectedStrokeIds: Set<String> = emptySet(),
    val selectedShapeIds: Set<String> = emptySet(),
    val selectedNoteIds: Set<String> = emptySet(),
    val shapeRecognitionProposal: ShapeRecognitionProposal? = null,
    val pendingStickyNoteColor: String? = null,
    val pendingShapeType: ShapeType? = null,
    val pendingIsFilled: Boolean = false,
    val pendingStrokeColor: String = "#2563EB",
    val pendingFillColor: String = "#DBEAFE",
    val pendingStrokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val pendingCapStyle: LineCapStyle = LineCapStyle.NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showTemplatesOnNewBoard: Boolean = false,
    val shouldAutoOpenTemplatePicker: Boolean = false,
    val customTemplates: List<CustomBoardTemplate> = emptyList()
)

private data class BoardSnapshot(
    val strokes: List<WhiteboardStroke>,
    val notes: List<StickyNote>,
    val shapes: List<ShapeItem>,
    val texts: List<TextItem>
)

class WhiteboardViewModel : ViewModel() {

    private val undoStack = mutableListOf<BoardSnapshot>()
    private val redoStack = mutableListOf<BoardSnapshot>()

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<WhiteboardUiState> = _uiState.asStateFlow()

    private fun createInitialState(): WhiteboardUiState {
        val blankBoardId = UUID.randomUUID().toString()
        val blankBoard = WhiteboardBoard(
            id = blankBoardId,
            title = "Pizarra Principal",
            strokes = emptyList(),
            stickyNotes = emptyList(),
            shapes = emptyList(),
            imageCards = emptyList(),
            textItems = emptyList(),
            stickers = emptyList()
        )

        return WhiteboardUiState(
            board = blankBoard,
            allBoards = listOf(blankBoard),
            isViewingBoardsHome = true
        )
    }

    private inline fun WhiteboardUiState.withUpdatedBoard(
        crossinline transform: (WhiteboardBoard) -> WhiteboardBoard
    ): WhiteboardUiState {
        val updated = transform(board).copy(updatedAt = System.currentTimeMillis())
        val exists = allBoards.any { it.id == updated.id }
        val updatedAll = if (exists) {
            allBoards.map { if (it.id == updated.id) updated else it }
        } else {
            allBoards + updated
        }
        return copy(board = updated, allBoards = updatedAll)
    }

    fun setTool(tool: ToolType) {
        _uiState.update { state ->
            if (tool == ToolType.HAND) {
                state.copy(
                    currentTool = tool,
                    selectedStrokeId = null,
                    selectedShapeId = null,
                    selectedNoteId = null,
                    selectedStrokeIds = emptySet(),
                    selectedShapeIds = emptySet(),
                    selectedNoteIds = emptySet(),
                    pendingStickyNoteColor = null,
                    pendingShapeType = null
                )
            } else {
                state.copy(currentTool = tool)
            }
        }
    }

    fun toggleZenMode() {
        _uiState.update { it.copy(isZenMode = !it.isZenMode) }
    }

    fun toggleSnapToGrid() {
        _uiState.update { it.copy(isSnapToGrid = !it.isSnapToGrid) }
    }

    fun toggleRuler() {
        _uiState.update {
            val next = !it.isRulerActive
            it.copy(isRulerActive = next, rulerState = it.rulerState.copy(isVisible = next))
        }
    }

    fun moveRuler(deltaX: Float, deltaY: Float) {
        _uiState.update {
            val r = it.rulerState
            it.copy(rulerState = r.copy(centerX = r.centerX + deltaX, centerY = r.centerY + deltaY))
        }
    }

    fun rotateRuler(deltaAngle: Float) {
        _uiState.update {
            val r = it.rulerState
            it.copy(rulerState = r.copy(angleDegrees = (r.angleDegrees + deltaAngle) % 360f))
        }
    }

    fun addLaserPoint(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val newPoint = LaserPoint(x, y, now)
        _uiState.update {
            val cleaned = it.laserTrail.filter { p -> now - p.timestamp < 1500 }
            it.copy(laserTrail = cleaned + newPoint)
        }
    }

    fun onViewportSizeChanged(newWidthPx: Float, newHeightPx: Float) {
        if (newWidthPx <= 0f || newHeightPx <= 0f) return
        _uiState.update { state ->
            val currentCam = ViewportTransform(
                scale = state.scale,
                panX = state.panX,
                panY = state.panY,
                viewportWidthPx = state.viewportWidthPx,
                viewportHeightPx = state.viewportHeightPx
            )
            val adapted = currentCam.adaptToViewportResize(newWidthPx, newHeightPx)
            state.copy(
                panX = adapted.panX,
                panY = adapted.panY,
                viewportWidthPx = adapted.viewportWidthPx,
                viewportHeightPx = adapted.viewportHeightPx
            )
        }
    }

    fun onPanZoom(
        panX: Float,
        panY: Float,
        zoomChange: Float,
        focalX: Float? = null,
        focalY: Float? = null
    ) {
        _uiState.update { state ->
            val cam = ViewportTransform(
                scale = state.scale,
                panX = state.panX,
                panY = state.panY,
                viewportWidthPx = state.viewportWidthPx,
                viewportHeightPx = state.viewportHeightPx
            ).panAndZoom(
                deltaPanX = panX,
                deltaPanY = panY,
                zoomChange = zoomChange,
                focalX = focalX,
                focalY = focalY
            )
            state.copy(
                scale = cam.scale,
                panX = cam.panX,
                panY = cam.panY
            )
        }
    }

    fun zoomIn() {
        _uiState.update { it.copy(scale = (it.scale * 1.2f).coerceAtMost(5.0f)) }
    }

    fun zoomOut() {
        _uiState.update { it.copy(scale = (it.scale / 1.2f).coerceAtLeast(0.20f)) }
    }

    fun setZoomPreset(targetScale: Float) {
        _uiState.update { it.copy(scale = targetScale.coerceIn(0.20f, 5.0f)) }
    }

    fun fitToContent(screenWidth: Float, screenHeight: Float) {
        val board = _uiState.value.board
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasElements = false

        board.strokes.forEach { s ->
            s.points.forEach { p ->
                hasElements = true
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }

        board.shapes.forEach { s ->
            hasElements = true
            if (s.x < minX) minX = s.x
            if (s.x + s.width > maxX) maxX = s.x + s.width
            if (s.y < minY) minY = s.y
            if (s.y + s.height > maxY) maxY = s.y + s.height
        }

        board.stickyNotes.forEach { n ->
            hasElements = true
            if (n.x < minX) minX = n.x
            if (n.x + n.width > maxX) maxX = n.x + n.width
            if (n.y < minY) minY = n.y
            if (n.y + n.height > maxY) maxY = n.y + n.height
        }

        if (!hasElements || minX == Float.MAX_VALUE) {
            resetZoom()
            return
        }

        val contentW = (maxX - minX).coerceAtLeast(200f)
        val contentH = (maxY - minY).coerceAtLeast(200f)
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        val scaleX = (screenWidth * 0.75f) / contentW
        val scaleY = (screenHeight * 0.75f) / contentH
        val optimalScale = minOf(scaleX, scaleY).coerceIn(0.25f, 2.5f)

        _uiState.update {
            val newPanX = (screenWidth / 2f) - (centerX * optimalScale)
            val newPanY = (screenHeight / 2f) - (centerY * optimalScale)
            it.copy(scale = optimalScale, panX = newPanX, panY = newPanY)
        }
    }

    fun navigateToWorld(worldX: Float, worldY: Float, screenW: Float = 1080f, screenH: Float = 1920f) {
        _uiState.update {
            val newPanX = (screenW / 2f) - (worldX * it.scale)
            val newPanY = (screenH / 2f) - (worldY * it.scale)
            it.copy(panX = newPanX, panY = newPanY)
        }
    }

    fun brushTypeForTool(tool: ToolType): StrokeBrushType {
        return when (tool) {
            ToolType.PEN -> StrokeBrushType.FOUNTAIN_PEN
            ToolType.PENCIL -> StrokeBrushType.GRAPHITE_PENCIL
            ToolType.FINE_MARKER -> StrokeBrushType.FINE_MARKER
            ToolType.HIGHLIGHTER -> StrokeBrushType.HIGHLIGHTER
            ToolType.BRUSH -> StrokeBrushType.SOFT_BRUSH
            else -> StrokeBrushType.FOUNTAIN_PEN
        }
    }

    fun effectiveStrokeWidthForBrush(brushType: StrokeBrushType, basePenWidth: Float): Float {
        return when (brushType) {
            StrokeBrushType.HIGHLIGHTER -> (basePenWidth * 2.2f).coerceAtLeast(16f)
            StrokeBrushType.SOFT_BRUSH -> (basePenWidth * 1.9f).coerceAtLeast(12f)
            StrokeBrushType.FINE_MARKER -> (basePenWidth * 0.65f).coerceIn(2f, 8f)
            StrokeBrushType.GRAPHITE_PENCIL -> (basePenWidth * 0.80f).coerceIn(2f, 10f)
            StrokeBrushType.FOUNTAIN_PEN -> basePenWidth
        }
    }

    fun effectiveColorForBrush(brushType: StrokeBrushType, penColorHex: String): String {
        if (brushType == StrokeBrushType.HIGHLIGHTER &&
            (penColorHex.equals("#0F172A", true) || penColorHex.equals("#1E1E1E", true))
        ) {
            return "#FFF500"
        }
        return penColorHex
    }

    fun commitCompletedStroke(points: List<StrokePoint>, isHighlighter: Boolean) {
        if (points.size < 2) return
        saveUndoSnapshot()
        val state = _uiState.value
        val brushType = if (isHighlighter) StrokeBrushType.HIGHLIGHTER else brushTypeForTool(state.currentTool)
        val stabilizedPoints = StrokeStabilizer.stabilizePoints(points, brushType.stabilization)
        val effectiveWidth = effectiveStrokeWidthForBrush(brushType, state.penWidth)
        val effectiveColor = effectiveColorForBrush(brushType, state.penColorHex)
        val isTranslucentEmphasis = brushType == StrokeBrushType.HIGHLIGHTER || brushType == StrokeBrushType.SOFT_BRUSH

        val newStroke = WhiteboardStroke(
            points = stabilizedPoints,
            colorHex = effectiveColor,
            width = effectiveWidth,
            isHighlighter = brushType == StrokeBrushType.HIGHLIGHTER,
            isRainbow = state.isRainbowPen && !isTranslucentEmphasis,
            strokeStyle = state.penStyle,
            capStyle = state.penCapStyle,
            brushType = brushType,
            opacity = brushType.defaultOpacity
        )

        val recognized = if (state.autoShapeRecognition) {
            ShapeRecognizer.recognizeStroke(newStroke.copy(points = points))
        } else {
            null
        }

        if (recognized != null && recognized.confidence >= 0.80f) {
            _uiState.update {
                it.withUpdatedBoard { b -> b.copy(shapes = b.shapes + recognized.shapeItem) }.copy(
                    activeStroke = null,
                    shapeRecognitionProposal = null
                )
            }
        } else {
            _uiState.update {
                it.withUpdatedBoard { b -> b.copy(strokes = b.strokes + newStroke) }.copy(
                    activeStroke = null,
                    shapeRecognitionProposal = null
                )
            }
        }
    }

    /**
     * Converts a freehand stroke directly into clean geometry when the user holds still
     * at the end of a stroke (Snap-to-shape on long hold).
     */
    fun commitSnapToShapeFromPoints(points: List<StrokePoint>): Boolean {
        if (points.size < 4) return false
        val state = _uiState.value
        val brushType = brushTypeForTool(state.currentTool)
        val isTranslucentEmphasis = brushType == StrokeBrushType.HIGHLIGHTER || brushType == StrokeBrushType.SOFT_BRUSH
        val tempStroke = WhiteboardStroke(
            points = points,
            colorHex = effectiveColorForBrush(brushType, state.penColorHex),
            width = effectiveStrokeWidthForBrush(brushType, state.penWidth),
            isHighlighter = brushType == StrokeBrushType.HIGHLIGHTER,
            isRainbow = state.isRainbowPen && !isTranslucentEmphasis,
            strokeStyle = state.penStyle,
            capStyle = state.penCapStyle,
            brushType = brushType,
            opacity = brushType.defaultOpacity
        )
        val recognized = ShapeRecognizer.recognizeStroke(tempStroke) ?: return false
        if (recognized.confidence < 0.72f) return false

        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(shapes = b.shapes + recognized.shapeItem)
            }.copy(
                activeStroke = null,
                shapeRecognitionProposal = null,
                selectedShapeId = recognized.shapeItem.id,
                selectedShapeIds = setOf(recognized.shapeItem.id),
                selectedStrokeId = null,
                selectedStrokeIds = emptySet(),
                selectedNoteId = null,
                selectedNoteIds = emptySet()
            )
        }
        return true
    }

    /**
     * Object Eraser: deletes an entire vector stroke or shape on a single touch or drag.
     */
    fun eraseObjectAt(
        worldX: Float,
        worldY: Float,
        tolerance: Float,
        saveSnapshot: Boolean = true
    ): Boolean {
        val board = _uiState.value.board
        val hitStroke = board.strokes.lastOrNull { s ->
            isPointNearStroke(s.points, worldX, worldY, tolerance + s.width * 0.4f)
        }
        if (hitStroke != null) {
            if (saveSnapshot) saveUndoSnapshot()
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(strokes = b.strokes.filter { s -> s.id != hitStroke.id })
                }.copy(
                    selectedStrokeId = if (it.selectedStrokeId == hitStroke.id) null else it.selectedStrokeId
                )
            }
            return true
        }

        val hitShape = board.shapes.lastOrNull { s ->
            if (s.isLocked || s.isHiddenByCollapse) {
                false
            } else if (s.shapeType.isLineLike && s.vertices.size >= 2) {
                var hit = false
                for (i in 0 until s.vertices.lastIndex) {
                    if (pointToSegmentDistance(worldX, worldY, s.vertices[i], s.vertices[i + 1]) <= tolerance) {
                        hit = true
                        break
                    }
                }
                hit
            } else {
                val (lx, ly) = if (s.rotation != 0f) {
                    val cx = s.x + s.width / 2f
                    val cy = s.y + s.height / 2f
                    val rad = -s.rotation * (PI.toFloat() / 180f)
                    val dx = worldX - cx
                    val dy = worldY - cy
                    (cx + dx * cos(rad) - dy * sin(rad)) to (cy + dx * sin(rad) + dy * cos(rad))
                } else {
                    worldX to worldY
                }
                lx >= s.x - tolerance &&
                    lx <= s.x + s.width + tolerance &&
                    ly >= s.y - tolerance &&
                    ly <= s.y + s.height + tolerance
            }
        }
        if (hitShape != null) {
            if (saveSnapshot) saveUndoSnapshot()
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(shapes = b.shapes.filter { s -> s.id != hitShape.id })
                }.copy(
                    selectedShapeId = if (it.selectedShapeId == hitShape.id) null else it.selectedShapeId
                )
            }
            return true
        }
        return false
    }

    /**
     * Pixel Eraser: sweeps along a pointer segment without leaving gaps during fast drags.
     */
    fun erasePixelSegment(
        from: StrokePoint,
        to: StrokePoint,
        radius: Float,
        saveSnapshot: Boolean = false
    ): Boolean {
        val dist = hypot(to.x - from.x, to.y - from.y)
        val stepLen = max(2f, radius * 0.45f)
        val steps = ceil(dist / stepLen).toInt().coerceIn(1, 32)
        var anyErased = false
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val px = from.x + (to.x - from.x) * t
            val py = from.y + (to.y - from.y) * t
            if (erasePixelAt(px, py, radius, saveSnapshot = saveSnapshot && !anyErased)) {
                anyErased = true
            }
        }
        return anyErased
    }

    /**
     * Pixel Eraser: splits and trims touched portions of both vector strokes and auto-created/preset shapes.
     */
    fun erasePixelAt(
        worldX: Float,
        worldY: Float,
        radius: Float,
        saveSnapshot: Boolean = false
    ): Boolean {
        val board = _uiState.value.board
        if (board.strokes.isEmpty() && board.shapes.isEmpty()) return false

        var anyErased = false
        val nextStrokes = mutableListOf<WhiteboardStroke>()
        val nextShapes = mutableListOf<ShapeItem>()

        for (stroke in board.strokes) {
            val effectiveRadius = radius + (stroke.width * 0.35f)
            val clipped = PixelEraserGeometry.clipContourOutsideCircle(
                points = stroke.points,
                isClosed = false,
                cx = worldX,
                cy = worldY,
                radius = effectiveRadius
            )
            if (!clipped.wasModified) {
                nextStrokes.add(stroke)
            } else {
                anyErased = true
                clipped.fragments.forEachIndexed { idx, frag ->
                    if (frag.points.size >= 2) {
                        nextStrokes.add(
                            stroke.copy(
                                id = if (idx == 0) stroke.id else UUID.randomUUID().toString(),
                                points = frag.points,
                                capStyle = PixelEraserGeometry.resolveCapStyleForFragment(
                                    stroke.capStyle,
                                    frag.keptOrigStart,
                                    frag.keptOrigEnd
                                )
                            )
                        )
                    }
                }
            }
        }

        for (shape in board.shapes) {
            if (shape.isLocked || shape.isHiddenByCollapse) {
                nextShapes.add(shape)
                continue
            }
            val decomposed = PixelEraserGeometry.eraseShapeAtCircle(
                shape = shape,
                cx = worldX,
                cy = worldY,
                radius = radius
            )
            if (decomposed == null) {
                nextShapes.add(shape)
            } else {
                anyErased = true
                nextStrokes.addAll(decomposed)
            }
        }

        if (!anyErased) return false
        if (saveSnapshot) saveUndoSnapshot()
        _uiState.update { cur ->
            val survivingStrokeIds = nextStrokes.map { it.id }.toSet()
            val survivingShapeIds = nextShapes.map { it.id }.toSet()
            cur.withUpdatedBoard { b ->
                b.copy(strokes = nextStrokes, shapes = nextShapes)
            }.copy(
                selectedStrokeId = cur.selectedStrokeId?.takeIf { it in survivingStrokeIds },
                selectedStrokeIds = cur.selectedStrokeIds.filter { it in survivingStrokeIds }.toSet(),
                selectedShapeId = cur.selectedShapeId?.takeIf { it in survivingShapeIds },
                selectedShapeIds = cur.selectedShapeIds.filter { it in survivingShapeIds }.toSet()
            )
        }
        return true
    }

    private fun isPointNearStroke(
        points: List<StrokePoint>,
        wx: Float,
        wy: Float,
        tolerance: Float
    ): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) return hypot(points[0].x - wx, points[0].y - wy) <= tolerance
        for (i in 0 until points.lastIndex) {
            if (pointToSegmentDistance(wx, wy, points[i], points[i + 1]) <= tolerance) {
                return true
            }
        }
        return false
    }

    private fun pointToSegmentDistance(px: Float, py: Float, a: StrokePoint, b: StrokePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.0001f) return hypot(px - a.x, py - a.y)
        val t = (((px - a.x) * dx + (py - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }

    fun selectByLasso(lassoPolygon: List<StrokePoint>): Boolean {
        if (lassoPolygon.size < 3) return false
        val board = _uiState.value.board

        val enclosedShapeIds = board.shapes.filter { s ->
            if (s.isLocked || s.isHiddenByCollapse) {
                false
            } else {
                val cx = s.x + s.width / 2f
                val cy = s.y + s.height / 2f
                isPointInPolygon(cx, cy, lassoPolygon) || (
                    isPointInPolygon(s.x, s.y, lassoPolygon) &&
                    isPointInPolygon(s.x + s.width, s.y, lassoPolygon) &&
                    isPointInPolygon(s.x + s.width, s.y + s.height, lassoPolygon) &&
                    isPointInPolygon(s.x, s.y + s.height, lassoPolygon)
                ) || (s.vertices.isNotEmpty() && s.vertices.any { isPointInPolygon(it.x, it.y, lassoPolygon) })
            }
        }.map { it.id }.toSet()

        val enclosedStrokeIds = board.strokes.filter { s ->
            if (s.points.isEmpty()) false
            else {
                val insideCount = s.points.count { p -> isPointInPolygon(p.x, p.y, lassoPolygon) }
                insideCount > 0 && (insideCount * 2 >= s.points.size || insideCount >= 2)
            }
        }.map { it.id }.toSet()

        val enclosedNoteIds = board.stickyNotes.filter { n ->
            if (n.isLocked || n.isHiddenByCollapse) {
                false
            } else {
                val cx = n.x + n.width / 2f
                val cy = n.y + n.height / 2f
                isPointInPolygon(cx, cy, lassoPolygon) || (
                    isPointInPolygon(n.x, n.y, lassoPolygon) &&
                    isPointInPolygon(n.x + n.width, n.y, lassoPolygon)
                )
            }
        }.map { it.id }.toSet()

        if (enclosedShapeIds.isNotEmpty() || enclosedStrokeIds.isNotEmpty() || enclosedNoteIds.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    selectedShapeIds = enclosedShapeIds,
                    selectedStrokeIds = enclosedStrokeIds,
                    selectedNoteIds = enclosedNoteIds,
                    selectedShapeId = enclosedShapeIds.firstOrNull(),
                    selectedStrokeId = enclosedStrokeIds.firstOrNull(),
                    selectedNoteId = enclosedNoteIds.firstOrNull()
                )
            }
            return true
        }

        clearSelection()
        return false
    }

    private fun isPointInPolygon(x: Float, y: Float, polygon: List<StrokePoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y
            val intersect = ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / ((yj - yi).let { if (kotlin.math.abs(it) < 0.0001f) 0.0001f else it }) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun acceptShapeProposal() {
        val proposal = _uiState.value.shapeRecognitionProposal ?: return
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = b.strokes.filter { s -> s.id != proposal.originalStrokeId },
                    shapes = b.shapes + proposal.suggestedShape
                )
            }.copy(shapeRecognitionProposal = null)
        }
    }

    fun dismissShapeProposal() {
        _uiState.update { it.copy(shapeRecognitionProposal = null) }
    }

    fun addStickyNote(screenW: Float = 1080f, screenH: Float = 1920f, colorHex: String = "#FFF275") {
        saveUndoSnapshot()
        val state = _uiState.value
        val worldCenterX = (screenW / 2f - state.panX) / state.scale
        val worldCenterY = (screenH / 2f - state.panY) / state.scale
        val worldX = worldCenterX - 110f
        val worldY = worldCenterY - 100f

        val newNote = StickyNote(
            text = "",
            x = worldX,
            y = worldY,
            colorHex = colorHex
        )

        _uiState.update {
            it.withUpdatedBoard { b -> b.copy(stickyNotes = b.stickyNotes + newNote) }
        }
    }

    fun updateStickyNoteText(id: String, newText: String) {
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(stickyNotes = b.stickyNotes.map { n ->
                    if (n.id == id) {
                        val adaptedH = StickyNoteGeometry.calculateAdaptiveHeightWorld(
                            text = newText,
                            noteWidthWorld = n.width,
                            currentHeightWorld = n.height,
                            isAspectRatioLocked = n.isAspectRatioLocked
                        )
                        if (n.isAspectRatioLocked && adaptedH > n.height) {
                            n.copy(text = newText, width = adaptedH, height = adaptedH)
                        } else if (adaptedH > n.height) {
                            n.copy(text = newText, height = adaptedH)
                        } else {
                            n.copy(text = newText)
                        }
                    } else n
                })
            }
        }
    }

    fun updateStickyNoteColor(id: String, colorHex: String) {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(stickyNotes = b.stickyNotes.map { n -> if (n.id == id) n.copy(colorHex = colorHex) else n })
            }
        }
    }

    fun toggleStickyNotePin(id: String) {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(stickyNotes = b.stickyNotes.map { n -> if (n.id == id) n.copy(isPinned = !n.isPinned) else n })
            }
        }
    }

    /** Avoid scanning every note against every column when the board has no auto-expanding columns. */
    private fun recalculateColumnsIfPresent(board: WhiteboardBoard): WhiteboardBoard {
        if (board.shapes.none { it.templateRole == "COLUMN_CONTAINER" }) return board
        return recalculateColumnsIfPresent(board)
    }

    fun moveStickyNote(id: String, deltaX: Float, deltaY: Float) {
        val state = _uiState.value
        val updated = state.board.stickyNotes.map { note ->
            if (note.id == id && !note.isLocked) {
                var nextX = note.x + (deltaX / state.scale)
                var nextY = note.y + (deltaY / state.scale)
                if (state.isSnapToGrid) {
                    nextX = kotlin.math.round(nextX / 40f) * 40f
                    nextY = kotlin.math.round(nextY / 40f) * 40f
                }
                note.copy(x = nextX, y = nextY)
            } else note
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(b.copy(stickyNotes = updated))
            }
        }
    }

    fun resizeStickyNoteHandle(
        id: String,
        handle: StickyNoteResizeHandle,
        deltaScreenX: Float,
        deltaScreenY: Float
    ) {
        val state = _uiState.value
        var modified = false
        val updated = state.board.stickyNotes.map { note ->
            if (note.id == id && !note.isLocked) {
                modified = true
                val next = StickyNoteGeometry.resizeByHandle(
                    bounds = StickyNoteBounds(note.x, note.y, note.width, note.height),
                    handle = handle,
                    deltaScreenX = deltaScreenX,
                    deltaScreenY = deltaScreenY,
                    scale = state.scale,
                    lockAspectRatio = note.isAspectRatioLocked,
                    targetAspectRatio = 1.0f
                )
                note.copy(
                    x = next.x,
                    y = next.y,
                    width = next.width,
                    height = next.height
                )
            } else note
        }
        if (!modified) return
        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(b.copy(stickyNotes = updated))
            }
        }
    }

    fun resizeStickyNoteBounds(
        id: String,
        newX: Float,
        newY: Float,
        newWidth: Float,
        newHeight: Float,
        saveSnapshot: Boolean = false
    ) {
        val state = _uiState.value
        val cleanW = newWidth.coerceIn(StickyNoteGeometry.MIN_SIZE_WORLD, StickyNoteGeometry.MAX_SIZE_WORLD)
        val cleanH = newHeight.coerceIn(StickyNoteGeometry.MIN_SIZE_WORLD, StickyNoteGeometry.MAX_SIZE_WORLD)
        if (saveSnapshot) saveUndoSnapshot()
        val updated = state.board.stickyNotes.map { note ->
            if (note.id == id && !note.isLocked) {
                note.copy(x = newX, y = newY, width = cleanW, height = cleanH)
            } else note
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(b.copy(stickyNotes = updated))
            }
        }
    }

    fun toggleStickyNoteAspectRatioLock(id: String) {
        val target = _uiState.value.board.stickyNotes.find { it.id == id } ?: return
        saveUndoSnapshot()
        val nextLocked = !target.isAspectRatioLocked
        val updated = _uiState.value.board.stickyNotes.map { note ->
            if (note.id != id) {
                note
            } else if (nextLocked) {
                val side = ((note.width + note.height) * 0.5f)
                    .coerceIn(StickyNoteGeometry.MIN_SIZE_WORLD, StickyNoteGeometry.MAX_SIZE_WORLD)
                note.copy(
                    width = side,
                    height = side,
                    isAspectRatioLocked = true
                )
            } else {
                note.copy(isAspectRatioLocked = false)
            }
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(b.copy(stickyNotes = updated))
            }
        }
    }

    fun rotateStickyNote(
        id: String,
        rotationDegrees: Float,
        saveSnapshot: Boolean = false
    ) {
        if (saveSnapshot) saveUndoSnapshot()
        val cleanAngle = ((rotationDegrees % 360f) + 360f) % 360f
        _uiState.update { state ->
            val updated = state.board.stickyNotes.map { note ->
                if (note.id == id && !note.isLocked) {
                    note.copy(rotation = cleanAngle)
                } else note
            }
            state.withUpdatedBoard { b -> b.copy(stickyNotes = updated) }
        }
    }

    fun deleteStickyNote(id: String) {
        saveUndoSnapshot()
        _uiState.update { state ->
            val remainingNoteIds = state.selectedNoteIds - id
            state.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(
                    b.copy(stickyNotes = b.stickyNotes.filter { n -> n.id != id })
                )
            }.copy(
                selectedNoteId = if (state.selectedNoteId == id) remainingNoteIds.firstOrNull() else state.selectedNoteId,
                selectedNoteIds = remainingNoteIds
            )
        }
    }

    fun deleteSelectedNote() {
        val noteId = _uiState.value.selectedNoteId ?: return
        deleteStickyNote(noteId)
    }

    fun deleteCurrentSelection() {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val noteIds = if (state.selectedNoteIds.isNotEmpty()) state.selectedNoteIds else state.selectedNoteId?.let { setOf(it) } ?: emptySet()

        if (strokeIds.isEmpty() && shapeIds.isEmpty() && noteIds.isEmpty()) return
        saveUndoSnapshot()

        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(
                    b.copy(
                        strokes = b.strokes.filter { s -> !strokeIds.contains(s.id) },
                        shapes = b.shapes.filter { sh -> sh.isLocked || !shapeIds.contains(sh.id) },
                        stickyNotes = b.stickyNotes.filter { n -> !noteIds.contains(n.id) }
                    )
                )
            }.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun requestAddStickyNote(colorHex: String = "#FFF275") {
        _uiState.update { it.copy(pendingStickyNoteColor = colorHex, pendingShapeType = null) }
    }

    fun requestAddCustomShape(
        type: ShapeType,
        isFilled: Boolean,
        strokeColor: String,
        fillColor: String,
        style: StrokeStyle,
        capStyle: LineCapStyle = LineCapStyle.NONE
    ) {
        _uiState.update {
            it.copy(
                pendingShapeType = type,
                pendingStickyNoteColor = null,
                pendingIsFilled = isFilled,
                pendingStrokeColor = strokeColor,
                pendingFillColor = fillColor,
                pendingStrokeStyle = style,
                pendingCapStyle = capStyle,
                selectedShapeId = null,
                selectedStrokeId = null,
                selectedNoteId = null,
                selectedShapeIds = emptySet(),
                selectedStrokeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun cancelPendingPlacement() {
        _uiState.update { it.copy(pendingStickyNoteColor = null, pendingShapeType = null) }
    }

    fun placePendingAt(worldX: Float, worldY: Float) {
        val state = _uiState.value
        if (state.pendingStickyNoteColor != null) {
            saveUndoSnapshot()
            val newNote = StickyNote(
                text = "",
                x = worldX - 110f,
                y = worldY - 100f,
                colorHex = state.pendingStickyNoteColor
            )
            _uiState.update {
                it.withUpdatedBoard { b ->
                    recalculateColumnsIfPresent(
                        b.copy(stickyNotes = b.stickyNotes + newNote)
                    )
                }.copy(
                    pendingStickyNoteColor = null,
                    selectedNoteId = newNote.id
                )
            }
        } else if (state.pendingShapeType != null) {
            placePendingShapeInBounds(StrokePoint(worldX, worldY), StrokePoint(worldX, worldY))
        }
    }

    fun placePendingShapeInBounds(start: StrokePoint, end: StrokePoint) {
        val state = _uiState.value
        val shapeType = state.pendingShapeType ?: return
        saveUndoSnapshot()

        val dragDist = kotlin.math.hypot(end.x - start.x, end.y - start.y)
        val newShape: ShapeItem = if (shapeType.isLineLike) {
            val v0: StrokePoint
            val v1: StrokePoint
            if (dragDist < 14f) {
                v0 = StrokePoint(start.x - 90f, start.y)
                v1 = StrokePoint(start.x + 90f, start.y)
            } else {
                v0 = start
                v1 = end
            }
            val minX = kotlin.math.min(v0.x, v1.x)
            val maxX = kotlin.math.max(v0.x, v1.x)
            val minY = kotlin.math.min(v0.y, v1.y)
            val maxY = kotlin.math.max(v0.y, v1.y)
            ShapeItem(
                shapeType = shapeType,
                x = minX,
                y = minY,
                width = (maxX - minX).coerceAtLeast(1f),
                height = (maxY - minY).coerceAtLeast(1f),
                strokeColorHex = state.pendingStrokeColor,
                fillColorHex = state.pendingFillColor,
                strokeWidth = state.penWidth.coerceIn(2f, 16f),
                isFilled = false,
                strokeStyle = state.pendingStrokeStyle,
                capStyle = when (shapeType) {
                    ShapeType.ARROW -> LineCapStyle.END_ARROW
                    ShapeType.DOUBLE_ARROW -> LineCapStyle.BOTH_ARROWS
                    else -> state.pendingCapStyle
                },
                vertices = listOf(v0, v1)
            )
        } else if (dragDist < 14f) {
            val isSquareLike = shapeType == ShapeType.CIRCLE ||
                shapeType == ShapeType.STAR ||
                shapeType == ShapeType.HEXAGON ||
                shapeType == ShapeType.DIAMOND ||
                shapeType == ShapeType.TRIANGLE
            val defW = if (isSquareLike) 160f else 190f
            val defH = if (isSquareLike) 160f else 130f
            ShapeItem(
                shapeType = shapeType,
                x = start.x - defW / 2f,
                y = start.y - defH / 2f,
                width = defW,
                height = defH,
                strokeColorHex = state.pendingStrokeColor,
                fillColorHex = state.pendingFillColor,
                strokeWidth = state.penWidth.coerceIn(2f, 16f),
                isFilled = state.pendingIsFilled,
                strokeStyle = state.pendingStrokeStyle
            )
        } else {
            val minX = kotlin.math.min(start.x, end.x)
            val minY = kotlin.math.min(start.y, end.y)
            val w = kotlin.math.abs(end.x - start.x).coerceAtLeast(28f)
            val h = kotlin.math.abs(end.y - start.y).coerceAtLeast(28f)
            ShapeItem(
                shapeType = shapeType,
                x = minX,
                y = minY,
                width = w,
                height = h,
                strokeColorHex = state.pendingStrokeColor,
                fillColorHex = state.pendingFillColor,
                strokeWidth = state.penWidth.coerceIn(2f, 16f),
                isFilled = state.pendingIsFilled,
                strokeStyle = state.pendingStrokeStyle
            )
        }

        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(shapes = b.shapes + newShape)
            }.copy(
                pendingShapeType = null,
                selectedShapeId = newShape.id,
                selectedShapeIds = setOf(newShape.id),
                selectedStrokeId = null,
                selectedStrokeIds = emptySet(),
                selectedNoteId = null,
                selectedNoteIds = emptySet(),
                currentTool = ToolType.SELECT_LASSO
            )
        }
    }

    fun addCustomShape(
        type: ShapeType,
        isFilled: Boolean,
        strokeColor: String,
        fillColor: String,
        style: StrokeStyle,
        capStyle: LineCapStyle = LineCapStyle.NONE,
        screenW: Float = 1080f,
        screenH: Float = 1920f
    ) {
        requestAddCustomShape(type, isFilled, strokeColor, fillColor, style, capStyle)
    }

    fun selectStroke(id: String?) {
        _uiState.update {
            it.copy(
                selectedStrokeId = id,
                selectedStrokeIds = if (id != null) setOf(id) else emptySet(),
                selectedShapeId = null,
                selectedShapeIds = emptySet(),
                selectedNoteId = null,
                selectedNoteIds = emptySet()
            )
        }
    }

    fun selectShape(id: String?) {
        _uiState.update {
            it.copy(
                selectedShapeId = id,
                selectedShapeIds = if (id != null) setOf(id) else emptySet(),
                selectedStrokeId = null,
                selectedStrokeIds = emptySet(),
                selectedNoteId = null,
                selectedNoteIds = emptySet()
            )
        }
    }

    fun selectNote(id: String?) {
        _uiState.update {
            it.copy(
                selectedNoteId = id,
                selectedNoteIds = if (id != null) setOf(id) else emptySet(),
                selectedStrokeId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeId = null,
                selectedShapeIds = emptySet()
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun updateSelectedStrokeColor(colorHex: String) {
        val strokeIds = if (_uiState.value.selectedStrokeIds.isNotEmpty()) {
            _uiState.value.selectedStrokeIds
        } else {
            _uiState.value.selectedStrokeId?.let { setOf(it) } ?: return
        }
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = b.strokes.map { s -> if (strokeIds.contains(s.id)) s.copy(colorHex = colorHex) else s })
            }
        }
    }

    fun updateSelectedStrokeWidth(width: Float) {
        val strokeIds = if (_uiState.value.selectedStrokeIds.isNotEmpty()) {
            _uiState.value.selectedStrokeIds
        } else {
            _uiState.value.selectedStrokeId?.let { setOf(it) } ?: return
        }
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = b.strokes.map { s -> if (strokeIds.contains(s.id)) s.copy(width = width) else s })
            }
        }
    }

    fun updateSelectedStrokeStyle(style: StrokeStyle) {
        val strokeIds = if (_uiState.value.selectedStrokeIds.isNotEmpty()) {
            _uiState.value.selectedStrokeIds
        } else {
            _uiState.value.selectedStrokeId?.let { setOf(it) } ?: return
        }
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = b.strokes.map { s -> if (strokeIds.contains(s.id)) s.copy(strokeStyle = style) else s })
            }
        }
    }

    fun updateSelectedStrokeCapStyle(capStyle: LineCapStyle) {
        val strokeIds = if (_uiState.value.selectedStrokeIds.isNotEmpty()) {
            _uiState.value.selectedStrokeIds
        } else {
            _uiState.value.selectedStrokeId?.let { setOf(it) } ?: return
        }
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = b.strokes.map { s -> if (strokeIds.contains(s.id)) s.copy(capStyle = capStyle) else s })
            }
        }
    }

    fun toggleSelectedStrokeHighlighter() {
        val strokeIds = if (_uiState.value.selectedStrokeIds.isNotEmpty()) {
            _uiState.value.selectedStrokeIds
        } else {
            _uiState.value.selectedStrokeId?.let { setOf(it) } ?: return
        }
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = b.strokes.map { s -> if (strokeIds.contains(s.id)) s.copy(isHighlighter = !s.isHighlighter) else s })
            }
        }
    }

    fun convertSelectedStrokeToShape() {
        val strokeId = _uiState.value.selectedStrokeId ?: return
        val targetStroke = _uiState.value.board.strokes.find { it.id == strokeId } ?: return
        val recognized = ShapeRecognizer.recognizeStroke(targetStroke)
        if (recognized != null) {
            saveUndoSnapshot()
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(
                        strokes = b.strokes.filter { s -> s.id != strokeId },
                        shapes = b.shapes + recognized.shapeItem
                    )
                }.copy(
                    selectedStrokeId = null,
                    selectedStrokeIds = emptySet(),
                    selectedShapeId = recognized.shapeItem.id,
                    selectedShapeIds = setOf(recognized.shapeItem.id),
                    selectedNoteId = null,
                    selectedNoteIds = emptySet()
                )
            }
        }
    }

    fun duplicateSelectedStroke() {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: return
        val strokesToDup = state.board.strokes.filter { strokeIds.contains(it.id) }
        if (strokesToDup.isEmpty()) return
        saveUndoSnapshot()
        val newStrokes = strokesToDup.map { s ->
            val duplicatedPoints = s.points.map { StrokePoint(it.x + 30f, it.y + 30f) }
            s.copy(id = UUID.randomUUID().toString(), points = duplicatedPoints)
        }
        val newIds = newStrokes.map { it.id }.toSet()
        _uiState.update {
            it.withUpdatedBoard { b -> b.copy(strokes = b.strokes + newStrokes) }.copy(
                selectedStrokeId = newIds.firstOrNull(),
                selectedStrokeIds = newIds
            )
        }
    }

    fun deleteSelectedStroke() {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: return
        if (strokeIds.isEmpty()) return
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b -> b.copy(strokes = b.strokes.filter { s -> !strokeIds.contains(s.id) }) }.copy(
                selectedStrokeId = null,
                selectedStrokeIds = emptySet()
            )
        }
    }

    fun beginTransformSnapshot() {
        saveUndoSnapshot()
    }

    fun updateSelectedShape(
        strokeColor: String? = null,
        fillColor: String? = null,
        isFilled: Boolean? = null,
        width: Float? = null,
        style: StrokeStyle? = null,
        capStyle: LineCapStyle? = null
    ) {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val updated = state.board.shapes.map {
            if (shapeIds.contains(it.id)) {
                it.copy(
                    strokeColorHex = strokeColor ?: it.strokeColorHex,
                    fillColorHex = fillColor ?: it.fillColorHex,
                    isFilled = isFilled ?: it.isFilled,
                    strokeWidth = width ?: it.strokeWidth,
                    strokeStyle = style ?: it.strokeStyle,
                    capStyle = capStyle ?: it.capStyle
                )
            } else it
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
    }

    fun moveSelectedObjects(dxWorld: Float, dyWorld: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val noteIds = if (state.selectedNoteIds.isNotEmpty()) state.selectedNoteIds else state.selectedNoteId?.let { setOf(it) } ?: emptySet()

        if (strokeIds.isEmpty() && shapeIds.isEmpty() && noteIds.isEmpty()) return

        val updatedStrokes = if (strokeIds.isEmpty()) state.board.strokes else state.board.strokes.map { s ->
            if (strokeIds.contains(s.id)) {
                s.copy(points = s.points.map { StrokePoint(it.x + dxWorld, it.y + dyWorld) })
            } else s
        }
        val updatedShapes = if (shapeIds.isEmpty()) state.board.shapes else state.board.shapes.map { sh ->
            if (shapeIds.contains(sh.id) && !sh.isLocked) {
                sh.copy(
                    x = sh.x + dxWorld,
                    y = sh.y + dyWorld,
                    vertices = sh.vertices.map { StrokePoint(it.x + dxWorld, it.y + dyWorld) }
                )
            } else sh
        }
        val updatedNotes = if (noteIds.isEmpty()) state.board.stickyNotes else state.board.stickyNotes.map { n ->
            if (noteIds.contains(n.id) && !n.isLocked) {
                n.copy(x = n.x + dxWorld, y = n.y + dyWorld)
            } else n
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(
                    b.copy(strokes = updatedStrokes, shapes = updatedShapes, stickyNotes = updatedNotes)
                )
            }
        }
    }

    fun moveSelectedShape(dxWorld: Float, dyWorld: Float) {
        moveSelectedObjects(dxWorld, dyWorld)
    }

    fun resizeSelectedShapeHandle(handleIndex: Int, newWorldX: Float, newWorldY: Float) {
        val shapeId = _uiState.value.selectedShapeId ?: return
        val updated = _uiState.value.board.shapes.map { s ->
            if (s.id != shapeId) {
                s
            } else if (s.shapeType.isLineLike && s.vertices.size >= 2) {
                if (handleIndex == 2 || handleIndex == 3) {
                    val p0 = s.vertices[0]
                    val p1 = s.vertices[1]
                    val cx = (p0.x + p1.x) / 2f
                    val cy = (p0.y + p1.y) / 2f
                    val halfLen = kotlin.math.hypot(p1.x - p0.x, p1.y - p0.y) / 2f
                    val angle = kotlin.math.atan2(newWorldY - cy, newWorldX - cx)
                    val nv0: StrokePoint
                    val nv1: StrokePoint
                    if (handleIndex == 2) {
                        nv0 = StrokePoint(cx + halfLen * cos(angle), cy + halfLen * sin(angle))
                        nv1 = StrokePoint(cx - halfLen * cos(angle), cy - halfLen * sin(angle))
                    } else {
                        nv1 = StrokePoint(cx + halfLen * cos(angle), cy + halfLen * sin(angle))
                        nv0 = StrokePoint(cx - halfLen * cos(angle), cy - halfLen * sin(angle))
                    }
                    val minX = kotlin.math.min(nv0.x, nv1.x)
                    val maxX = kotlin.math.max(nv0.x, nv1.x)
                    val minY = kotlin.math.min(nv0.y, nv1.y)
                    val maxY = kotlin.math.max(nv0.y, nv1.y)
                    s.copy(
                        x = minX,
                        y = minY,
                        width = (maxX - minX).coerceAtLeast(1f),
                        height = (maxY - minY).coerceAtLeast(1f),
                        vertices = listOf(nv0, nv1)
                    )
                } else {
                    val v0 = if (handleIndex == 0) StrokePoint(newWorldX, newWorldY) else s.vertices[0]
                    val v1 = if (handleIndex == 1) StrokePoint(newWorldX, newWorldY) else s.vertices[1]
                    val minX = kotlin.math.min(v0.x, v1.x)
                    val maxX = kotlin.math.max(v0.x, v1.x)
                    val minY = kotlin.math.min(v0.y, v1.y)
                    val maxY = kotlin.math.max(v0.y, v1.y)
                    s.copy(
                        x = minX,
                        y = minY,
                        width = (maxX - minX).coerceAtLeast(1f),
                        height = (maxY - minY).coerceAtLeast(1f),
                        vertices = listOf(v0, v1)
                    )
                }
            } else {
                // Corner handles: 0=TL, 1=TR, 2=BL, 3=BR
                val anchorX = if (handleIndex == 0 || handleIndex == 2) s.x + s.width else s.x
                val anchorY = if (handleIndex == 0 || handleIndex == 1) s.y + s.height else s.y
                val minSize = 24f

                val clampedX = when (handleIndex) {
                    0, 2 -> kotlin.math.min(newWorldX, anchorX - minSize)
                    else -> kotlin.math.max(newWorldX, anchorX + minSize)
                }
                val clampedY = when (handleIndex) {
                    0, 1 -> kotlin.math.min(newWorldY, anchorY - minSize)
                    else -> kotlin.math.max(newWorldY, anchorY + minSize)
                }

                val newX = kotlin.math.min(anchorX, clampedX)
                val newY = kotlin.math.min(anchorY, clampedY)
                val newW = kotlin.math.abs(clampedX - anchorX).coerceAtLeast(minSize)
                val newH = kotlin.math.abs(clampedY - anchorY).coerceAtLeast(minSize)

                val oldW = s.width.coerceAtLeast(1f)
                val oldH = s.height.coerceAtLeast(1f)
                val remappedVerts = s.vertices.map { v ->
                    val rx = (v.x - s.x) / oldW
                    val ry = (v.y - s.y) / oldH
                    StrokePoint(newX + rx * newW, newY + ry * newH)
                }

                s.copy(
                    x = newX,
                    y = newY,
                    width = newW,
                    height = newH,
                    vertices = remappedVerts
                )
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
    }

    fun scaleSelectedShape(factor: Float) {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val updated = state.board.shapes.map { s ->
            if (!shapeIds.contains(s.id)) {
                s
            } else if (s.shapeType.isLineLike && s.vertices.size >= 2) {
                val p0 = s.vertices[0]
                val p1 = s.vertices[1]
                val mx = (p0.x + p1.x) / 2f
                val my = (p0.y + p1.y) / 2f
                val len = kotlin.math.hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                val clampedFactor = if (len * factor < 20f) 20f / len else if (len * factor > 3000f) 3000f / len else factor
                val nv0 = StrokePoint(mx + (p0.x - mx) * clampedFactor, my + (p0.y - my) * clampedFactor)
                val nv1 = StrokePoint(mx + (p1.x - mx) * clampedFactor, my + (p1.y - my) * clampedFactor)
                s.copy(
                    x = kotlin.math.min(nv0.x, nv1.x),
                    y = kotlin.math.min(nv0.y, nv1.y),
                    width = kotlin.math.abs(nv1.x - nv0.x).coerceAtLeast(1f),
                    height = kotlin.math.abs(nv1.y - nv0.y).coerceAtLeast(1f),
                    vertices = listOf(nv0, nv1)
                )
            } else {
                val cx = s.x + s.width / 2f
                val cy = s.y + s.height / 2f
                val newW = (s.width * factor).coerceIn(24f, 3000f)
                val newH = (s.height * factor).coerceIn(24f, 3000f)
                val newX = cx - newW / 2f
                val newY = cy - newH / 2f
                val oldW = s.width.coerceAtLeast(1f)
                val oldH = s.height.coerceAtLeast(1f)
                val remappedVerts = s.vertices.map { v ->
                    val rx = (v.x - s.x) / oldW
                    val ry = (v.y - s.y) / oldH
                    StrokePoint(newX + rx * newW, newY + ry * newH)
                }
                s.copy(
                    x = newX,
                    y = newY,
                    width = newW,
                    height = newH,
                    vertices = remappedVerts
                )
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
    }

    fun rotateSelectedShape(deltaDegrees: Float) {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val rad = deltaDegrees * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val updated = state.board.shapes.map { s ->
            if (!shapeIds.contains(s.id)) {
                s
            } else if (s.shapeType.isLineLike && s.vertices.size >= 2) {
                val p0 = s.vertices[0]
                val p1 = s.vertices[1]
                val mx = (p0.x + p1.x) / 2f
                val my = (p0.y + p1.y) / 2f
                val nv0 = StrokePoint(
                    mx + (p0.x - mx) * cosA - (p0.y - my) * sinA,
                    my + (p0.x - mx) * sinA + (p0.y - my) * cosA
                )
                val nv1 = StrokePoint(
                    mx + (p1.x - mx) * cosA - (p1.y - my) * sinA,
                    my + (p1.x - mx) * sinA + (p1.y - my) * cosA
                )
                s.copy(
                    x = kotlin.math.min(nv0.x, nv1.x),
                    y = kotlin.math.min(nv0.y, nv1.y),
                    width = kotlin.math.abs(nv1.x - nv0.x).coerceAtLeast(1f),
                    height = kotlin.math.abs(nv1.y - nv0.y).coerceAtLeast(1f),
                    rotation = 0f,
                    vertices = listOf(nv0, nv1)
                )
            } else {
                val next = ((s.rotation + deltaDegrees) % 360f + 360f) % 360f
                s.copy(rotation = next)
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
    }

    fun resetSelectedShapeRotation() {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val updated = state.board.shapes.map { s ->
            if (shapeIds.contains(s.id)) s.copy(rotation = 0f) else s
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
    }

    fun bringSelectedShapeToFront() {
        val shapeId = _uiState.value.selectedShapeId ?: return
        val target = _uiState.value.board.shapes.find { it.id == shapeId } ?: return
        saveUndoSnapshot()
        val reordered = _uiState.value.board.shapes.filter { it.id != shapeId } + target
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = reordered) } }
    }

    fun sendSelectedShapeToBack() {
        val shapeId = _uiState.value.selectedShapeId ?: return
        val target = _uiState.value.board.shapes.find { it.id == shapeId } ?: return
        saveUndoSnapshot()
        val reordered = listOf(target) + _uiState.value.board.shapes.filter { it.id != shapeId }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = reordered) } }
    }

    fun moveSelectedStroke(dxWorld: Float, dyWorld: Float) {
        moveSelectedObjects(dxWorld, dyWorld)
    }

    fun scaleSelectedStroke(factor: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: return
        if (strokeIds.isEmpty()) return
        saveUndoSnapshot()
        val updated = state.board.strokes.map { s ->
            if (!strokeIds.contains(s.id) || s.points.isEmpty()) {
                s
            } else {
                val minX = s.points.minOf { it.x }
                val maxX = s.points.maxOf { it.x }
                val minY = s.points.minOf { it.y }
                val maxY = s.points.maxOf { it.y }
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val span = kotlin.math.hypot(maxX - minX, maxY - minY).coerceAtLeast(1f)
                val safeFactor = if (span * factor < 20f) 20f / span else if (span * factor > 3000f) 3000f / span else factor
                s.copy(points = s.points.map { p ->
                    StrokePoint(cx + (p.x - cx) * safeFactor, cy + (p.y - cy) * safeFactor)
                })
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updated) } }
    }

    fun rotateSelectedStroke(deltaDegrees: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: return
        if (strokeIds.isEmpty()) return
        saveUndoSnapshot()
        val rad = deltaDegrees * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val updated = state.board.strokes.map { s ->
            if (!strokeIds.contains(s.id) || s.points.isEmpty()) {
                s
            } else {
                val cx = (s.points.minOf { it.x } + s.points.maxOf { it.x }) / 2f
                val cy = (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f
                s.copy(points = s.points.map { p ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    StrokePoint(
                        x = cx + dx * cosA - dy * sinA,
                        y = cy + dx * sinA + dy * cosA
                    )
                })
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updated) } }
    }

    fun resizeSelectedStrokeBounds(handleIndex: Int, newWorldX: Float, newWorldY: Float) {
        val strokeId = _uiState.value.selectedStrokeId ?: return
        val updated = _uiState.value.board.strokes.map { s ->
            if (s.id != strokeId || s.points.isEmpty()) {
                s
            } else {
                val minX = s.points.minOf { it.x }
                val maxX = s.points.maxOf { it.x }
                val minY = s.points.minOf { it.y }
                val maxY = s.points.maxOf { it.y }
                val oldW = (maxX - minX).coerceAtLeast(1f)
                val oldH = (maxY - minY).coerceAtLeast(1f)

                val anchorX = if (handleIndex == 0 || handleIndex == 2) maxX else minX
                val anchorY = if (handleIndex == 0 || handleIndex == 1) maxY else minY
                val minSize = 20f

                val clampedX = when (handleIndex) {
                    0, 2 -> kotlin.math.min(newWorldX, anchorX - minSize)
                    else -> kotlin.math.max(newWorldX, anchorX + minSize)
                }
                val clampedY = when (handleIndex) {
                    0, 1 -> kotlin.math.min(newWorldY, anchorY - minSize)
                    else -> kotlin.math.max(newWorldY, anchorY + minSize)
                }

                val newX = kotlin.math.min(anchorX, clampedX)
                val newY = kotlin.math.min(anchorY, clampedY)
                val newW = kotlin.math.abs(clampedX - anchorX).coerceAtLeast(minSize)
                val newH = kotlin.math.abs(clampedY - anchorY).coerceAtLeast(minSize)

                s.copy(points = s.points.map { p ->
                    val rx = (p.x - minX) / oldW
                    val ry = (p.y - minY) / oldH
                    StrokePoint(newX + rx * newW, newY + ry * newH)
                })
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updated) } }
    }

    fun duplicateSelectedShape() {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        val shapesToDup = state.board.shapes.filter { shapeIds.contains(it.id) }
        if (shapesToDup.isEmpty()) return
        saveUndoSnapshot()
        val newShapes = shapesToDup.map { shape ->
            shape.copy(
                id = UUID.randomUUID().toString(),
                x = shape.x + 30f,
                y = shape.y + 30f,
                vertices = shape.vertices.map { StrokePoint(it.x + 30f, it.y + 30f) }
            )
        }
        val newIds = newShapes.map { it.id }.toSet()
        _uiState.update {
            it.withUpdatedBoard { b -> b.copy(shapes = b.shapes + newShapes) }.copy(
                selectedShapeId = newIds.firstOrNull(),
                selectedShapeIds = newIds
            )
        }
    }

    fun deleteSelectedShape() {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b -> b.copy(shapes = b.shapes.filter { s -> !shapeIds.contains(s.id) }) }.copy(
                selectedShapeId = null,
                selectedShapeIds = emptySet()
            )
        }
    }

    fun updateCurrentSelectionColor(colorHex: String) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        if (strokeIds.isEmpty() && shapeIds.isEmpty()) return
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = if (strokeIds.isEmpty()) b.strokes else b.strokes.map { s ->
                        if (strokeIds.contains(s.id)) s.copy(colorHex = colorHex) else s
                    },
                    shapes = if (shapeIds.isEmpty()) b.shapes else b.shapes.map { sh ->
                        if (shapeIds.contains(sh.id)) sh.copy(strokeColorHex = colorHex) else sh
                    }
                )
            }
        }
    }

    fun scaleCurrentSelection(factor: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        if (strokeIds.isEmpty() && shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val updatedStrokes = if (strokeIds.isEmpty()) state.board.strokes else state.board.strokes.map { s ->
            if (!strokeIds.contains(s.id) || s.points.isEmpty()) {
                s
            } else {
                val minX = s.points.minOf { it.x }
                val maxX = s.points.maxOf { it.x }
                val minY = s.points.minOf { it.y }
                val maxY = s.points.maxOf { it.y }
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val span = kotlin.math.hypot(maxX - minX, maxY - minY).coerceAtLeast(1f)
                val safeFactor = if (span * factor < 20f) 20f / span else if (span * factor > 3000f) 3000f / span else factor
                s.copy(points = s.points.map { p ->
                    StrokePoint(cx + (p.x - cx) * safeFactor, cy + (p.y - cy) * safeFactor)
                })
            }
        }
        val updatedShapes = if (shapeIds.isEmpty()) state.board.shapes else state.board.shapes.map { s ->
            if (!shapeIds.contains(s.id)) {
                s
            } else if (s.shapeType.isLineLike && s.vertices.size >= 2) {
                val p0 = s.vertices[0]
                val p1 = s.vertices[1]
                val mx = (p0.x + p1.x) / 2f
                val my = (p0.y + p1.y) / 2f
                val len = kotlin.math.hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                val clampedFactor = if (len * factor < 20f) 20f / len else if (len * factor > 3000f) 3000f / len else factor
                val nv0 = StrokePoint(mx + (p0.x - mx) * clampedFactor, my + (p0.y - my) * clampedFactor)
                val nv1 = StrokePoint(mx + (p1.x - mx) * clampedFactor, my + (p1.y - my) * clampedFactor)
                s.copy(
                    x = kotlin.math.min(nv0.x, nv1.x),
                    y = kotlin.math.min(nv0.y, nv1.y),
                    width = kotlin.math.abs(nv1.x - nv0.x).coerceAtLeast(1f),
                    height = kotlin.math.abs(nv1.y - nv0.y).coerceAtLeast(1f),
                    vertices = listOf(nv0, nv1)
                )
            } else {
                val cx = s.x + s.width / 2f
                val cy = s.y + s.height / 2f
                val newW = (s.width * factor).coerceIn(24f, 3000f)
                val newH = (s.height * factor).coerceIn(24f, 3000f)
                val newX = cx - newW / 2f
                val newY = cy - newH / 2f
                val oldW = s.width.coerceAtLeast(1f)
                val oldH = s.height.coerceAtLeast(1f)
                val remappedVerts = s.vertices.map { v ->
                    val rx = (v.x - s.x) / oldW
                    val ry = (v.y - s.y) / oldH
                    StrokePoint(newX + rx * newW, newY + ry * newH)
                }
                s.copy(x = newX, y = newY, width = newW, height = newH, vertices = remappedVerts)
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updatedStrokes, shapes = updatedShapes) } }
    }

    fun rotateCurrentSelection(deltaDegrees: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        if (strokeIds.isEmpty() && shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val rad = deltaDegrees * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val updatedStrokes = if (strokeIds.isEmpty()) state.board.strokes else state.board.strokes.map { s ->
            if (!strokeIds.contains(s.id) || s.points.isEmpty()) {
                s
            } else {
                val cx = (s.points.minOf { it.x } + s.points.maxOf { it.x }) / 2f
                val cy = (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f
                s.copy(points = s.points.map { p ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    StrokePoint(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
                })
            }
        }
        val updatedShapes = if (shapeIds.isEmpty()) state.board.shapes else state.board.shapes.map { s ->
            if (!shapeIds.contains(s.id)) {
                s
            } else if (s.shapeType.isLineLike && s.vertices.size >= 2) {
                val p0 = s.vertices[0]
                val p1 = s.vertices[1]
                val mx = (p0.x + p1.x) / 2f
                val my = (p0.y + p1.y) / 2f
                val nv0 = StrokePoint(
                    mx + (p0.x - mx) * cosA - (p0.y - my) * sinA,
                    my + (p0.x - mx) * sinA + (p0.y - my) * cosA
                )
                val nv1 = StrokePoint(
                    mx + (p1.x - mx) * cosA - (p1.y - my) * sinA,
                    my + (p1.x - mx) * sinA + (p1.y - my) * cosA
                )
                s.copy(
                    x = kotlin.math.min(nv0.x, nv1.x),
                    y = kotlin.math.min(nv0.y, nv1.y),
                    width = kotlin.math.abs(nv1.x - nv0.x).coerceAtLeast(1f),
                    height = kotlin.math.abs(nv1.y - nv0.y).coerceAtLeast(1f),
                    rotation = 0f,
                    vertices = listOf(nv0, nv1)
                )
            } else {
                val next = ((s.rotation + deltaDegrees) % 360f + 360f) % 360f
                s.copy(rotation = next)
            }
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updatedStrokes, shapes = updatedShapes) } }
    }

    fun duplicateCurrentSelection() {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val strokesToDup = state.board.strokes.filter { strokeIds.contains(it.id) }
        val shapesToDup = state.board.shapes.filter { shapeIds.contains(it.id) }
        if (strokesToDup.isEmpty() && shapesToDup.isEmpty()) return
        saveUndoSnapshot()
        val newStrokes = strokesToDup.map { s ->
            s.copy(id = UUID.randomUUID().toString(), points = s.points.map { StrokePoint(it.x + 30f, it.y + 30f) })
        }
        val newShapes = shapesToDup.map { shape ->
            shape.copy(
                id = UUID.randomUUID().toString(),
                x = shape.x + 30f,
                y = shape.y + 30f,
                vertices = shape.vertices.map { StrokePoint(it.x + 30f, it.y + 30f) }
            )
        }
        val newStrokeIds = newStrokes.map { it.id }.toSet()
        val newShapeIds = newShapes.map { it.id }.toSet()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = b.strokes + newStrokes, shapes = b.shapes + newShapes)
            }.copy(
                selectedStrokeId = newStrokeIds.firstOrNull(),
                selectedStrokeIds = newStrokeIds,
                selectedShapeId = newShapeIds.firstOrNull(),
                selectedShapeIds = newShapeIds
            )
        }
    }

    fun applyTemplate(type: TemplateType, screenW: Float = 1080f, screenH: Float = 1920f) {
        saveUndoSnapshot()
        val state = _uiState.value
        val worldCenterX = (screenW / 2f - state.panX) / state.scale
        val worldCenterY = (screenH / 2f - state.panY) / state.scale
        val generated = IdeationTemplateFactory.createTemplate(type, worldCenterX, worldCenterY)

        val wasEmpty = state.board.strokes.isEmpty() &&
            state.board.shapes.isEmpty() &&
            state.board.stickyNotes.isEmpty() &&
            state.board.textItems.isEmpty()

        val fitScale = if (wasEmpty && screenW < 980f) {
            (screenW * 0.90f / 980f).coerceIn(0.45f, 1.0f)
        } else {
            state.scale
        }
        val fitPanX = if (wasEmpty && fitScale != state.scale) {
            screenW / 2f - worldCenterX * fitScale
        } else {
            state.panX
        }
        val fitPanY = if (wasEmpty && fitScale != state.scale) {
            screenH / 2f - worldCenterY * fitScale
        } else {
            state.panY
        }

        _uiState.update {
            it.withUpdatedBoard { b ->
                recalculateColumnsIfPresent(
                    b.copy(
                        shapes = b.shapes + generated.shapes,
                        stickyNotes = b.stickyNotes + generated.stickyNotes,
                        textItems = b.textItems + generated.textItems
                    )
                )
            }.copy(
                scale = fitScale,
                panX = fitPanX,
                panY = fitPanY,
                pendingStickyNoteColor = null,
                pendingShapeType = null,
                shouldAutoOpenTemplatePicker = false
            )
        }
    }

    fun addClusterBranch(clusterShapeId: String) {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b -> IdeationTemplateFactory.addClusterBranch(b, clusterShapeId) }
        }
    }

    fun toggleConceptBranchCollapse(nodeId: String) {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b -> IdeationTemplateFactory.toggleConceptBranchCollapse(b, nodeId) }
        }
    }

    fun addNoteToColumn(columnShapeId: String) {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b -> IdeationTemplateFactory.addNoteToColumn(b, columnShapeId) }
        }
    }

    fun updateTextItem(id: String, newText: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(textItems = b.textItems.map { t -> if (t.id == id && !t.isLocked) t.copy(text = newText) else t })
            }
        }
    }

    fun updateShapeLabel(id: String, newLabel: String) {
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(shapes = b.shapes.map { s -> if (s.id == id) s.copy(label = newLabel) else s })
            }
        }
    }

    fun toggleShowTemplatesOnNewBoard(enabled: Boolean? = null) {
        _uiState.update {
            it.copy(showTemplatesOnNewBoard = enabled ?: !it.showTemplatesOnNewBoard)
        }
    }

    fun consumeAutoOpenTemplatePicker() {
        _uiState.update { it.copy(shouldAutoOpenTemplatePicker = false) }
    }

    fun saveCurrentBoardAsTemplate(
        title: String,
        description: String = ""
    ): CustomBoardTemplate {
        val state = _uiState.value
        val board = state.board
        val serialized = BoardSnapshotSerializer.serializeBoard(board)
        val cleanTitle = title.trim().ifBlank {
            board.title.ifBlank { "Mi plantilla ${state.customTemplates.size + 1}" }
        }
        val template = CustomBoardTemplate(
            title = cleanTitle,
            description = description.trim(),
            strokeCount = board.strokes.size,
            shapeCount = board.shapes.size,
            noteCount = board.stickyNotes.size,
            textCount = board.textItems.size,
            serializedData = serialized
        )
        _uiState.update {
            it.copy(customTemplates = listOf(template) + it.customTemplates)
        }
        return template
    }

    fun applyCustomTemplate(template: CustomBoardTemplate): Boolean {
        val current = _uiState.value.board
        val restored = BoardSnapshotSerializer.deserializeIntoBoard(template.serializedData, current)
            ?: return false
        saveUndoSnapshot()
        // Generate fresh IDs so elements from applied custom templates remain independent across boards
        val idMap = mutableMapOf<String, String>()
        fun remap(oldId: String?): String? {
            if (oldId == null) return null
            return idMap.getOrPut(oldId) { UUID.randomUUID().toString() }
        }
        val freshShapes = restored.shapes.map { sh ->
            sh.copy(
                id = remap(sh.id)!!,
                groupId = remap(sh.groupId),
                parentId = remap(sh.parentId)
            )
        }
        val freshNotes = restored.stickyNotes.map { n ->
            n.copy(
                id = remap(n.id)!!,
                groupId = remap(n.groupId),
                parentId = remap(n.parentId)
            )
        }
        val freshTexts = restored.textItems.map { t ->
            t.copy(
                id = remap(t.id)!!,
                groupId = remap(t.groupId),
                parentId = remap(t.parentId)
            )
        }
        val freshStrokes = restored.strokes.map { s ->
            s.copy(id = UUID.randomUUID().toString())
        }
        val freshStickers = restored.stickers.map { st ->
            st.copy(id = UUID.randomUUID().toString())
        }

        val appliedBoard = restored.copy(
            strokes = freshStrokes,
            shapes = freshShapes,
            stickyNotes = freshNotes,
            textItems = freshTexts,
            stickers = freshStickers
        )

        _uiState.update {
            it.withUpdatedBoard { appliedBoard }.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet(),
                pendingStickyNoteColor = null,
                pendingShapeType = null,
                shouldAutoOpenTemplatePicker = false
            )
        }
        return true
    }

    fun deleteCustomTemplate(templateId: String) {
        _uiState.update { state ->
            state.copy(customTemplates = state.customTemplates.filter { it.id != templateId })
        }
    }

    fun openBoard(boardId: String) {
        val target = _uiState.value.allBoards.find { it.id == boardId } ?: return
        undoStack.clear()
        redoStack.clear()
        _uiState.update {
            it.copy(
                board = target,
                isViewingBoardsHome = false,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet(),
                pendingShapeType = null,
                pendingStickyNoteColor = null,
                canUndo = false,
                canRedo = false,
                panX = 0f,
                panY = 0f,
                scale = 1.0f
            )
        }
    }

    fun closeBoardToHome() {
        _uiState.update {
            it.withUpdatedBoard { b -> b }.copy(
                isViewingBoardsHome = true,
                isZenMode = false,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet(),
                pendingShapeType = null,
                pendingStickyNoteColor = null
            )
        }
    }

    fun createNewBoard(
        title: String,
        gridType: CanvasGridType = CanvasGridType.GRID,
        colorTheme: CanvasColorTheme = CanvasColorTheme.WHITE,
        openImmediately: Boolean = true
    ) {
        val newBoard = WhiteboardBoard(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Nueva Pizarra" },
            gridType = gridType,
            colorTheme = colorTheme
        )
        if (openImmediately) {
            undoStack.clear()
            redoStack.clear()
        }
        _uiState.update {
            it.copy(
                board = if (openImmediately) newBoard else it.board,
                allBoards = listOf(newBoard) + it.allBoards,
                isViewingBoardsHome = !openImmediately,
                selectedStrokeId = if (openImmediately) null else it.selectedStrokeId,
                selectedShapeId = if (openImmediately) null else it.selectedShapeId,
                selectedNoteId = if (openImmediately) null else it.selectedNoteId,
                selectedStrokeIds = if (openImmediately) emptySet() else it.selectedStrokeIds,
                selectedShapeIds = if (openImmediately) emptySet() else it.selectedShapeIds,
                selectedNoteIds = if (openImmediately) emptySet() else it.selectedNoteIds,
                pendingShapeType = if (openImmediately) null else it.pendingShapeType,
                pendingStickyNoteColor = if (openImmediately) null else it.pendingStickyNoteColor,
                canUndo = if (openImmediately) false else it.canUndo,
                canRedo = if (openImmediately) false else it.canRedo,
                panX = if (openImmediately) 0f else it.panX,
                panY = if (openImmediately) 0f else it.panY,
                scale = if (openImmediately) 1.0f else it.scale,
                shouldAutoOpenTemplatePicker = if (openImmediately) it.showTemplatesOnNewBoard else it.shouldAutoOpenTemplatePicker
            )
        }
    }

    fun switchBoard(boardId: String) {
        openBoard(boardId)
    }

    fun duplicateCurrentBoard(boardId: String) {
        val original = _uiState.value.allBoards.find { it.id == boardId } ?: return
        val dupId = UUID.randomUUID().toString()
        val dup = original.copy(
            id = dupId,
            title = "${original.title} (Copia)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            snapshots = original.snapshots.map { it.copy(boardId = dupId) }
        )
        _uiState.update { it.copy(allBoards = listOf(dup) + it.allBoards) }
    }

    fun renameBoardById(boardId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        _uiState.update { state ->
            val updatedAll = state.allBoards.map { b ->
                if (b.id == boardId) b.copy(title = clean, updatedAt = System.currentTimeMillis()) else b
            }
            val updatedCurrent = if (state.board.id == boardId) {
                state.board.copy(title = clean, updatedAt = System.currentTimeMillis())
            } else {
                state.board
            }
            state.copy(board = updatedCurrent, allBoards = updatedAll)
        }
    }

    fun deleteBoard(boardId: String) {
        val state = _uiState.value
        val remaining = state.allBoards.filter { it.id != boardId }
        val nextCurrent = when {
            remaining.isEmpty() -> WhiteboardBoard(title = "Nueva Pizarra")
            state.board.id == boardId -> remaining.first()
            else -> state.board
        }
        _uiState.update {
            it.copy(
                board = nextCurrent,
                allBoards = remaining,
                isViewingBoardsHome = if (remaining.isEmpty()) true else it.isViewingBoardsHome
            )
        }
    }

    fun resetZoom() {
        _uiState.update { it.copy(scale = 1.0f, panX = 0f, panY = 0f) }
    }

    fun setGridType(grid: CanvasGridType) {
        _uiState.update { it.withUpdatedBoard { b -> b.copy(gridType = grid) } }
    }

    fun setColorTheme(theme: CanvasColorTheme) {
        _uiState.update { it.withUpdatedBoard { b -> b.copy(colorTheme = theme) } }
    }

    fun renameBoard(newTitle: String) {
        renameBoardById(_uiState.value.board.id, newTitle)
    }

    fun clearBoard() {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = emptyList(),
                    stickyNotes = emptyList(),
                    shapes = emptyList(),
                    textItems = emptyList()
                )
            }.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun createSnapshot(versionName: String, description: String) {
        val board = _uiState.value.board
        val serialized = BoardSnapshotSerializer.serializeBoard(board)
        val cleanName = versionName.trim().ifBlank { "v${board.snapshots.size + 1}.0" }
        val snapshot = BoardVersionSnapshot(
            boardId = board.id,
            versionName = cleanName,
            description = description.trim(),
            strokeCount = board.strokes.size,
            noteCount = board.stickyNotes.size,
            shapeCount = board.shapes.size,
            serializedData = serialized
        )
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(snapshots = b.snapshots + snapshot)
            }
        }
    }

    fun restoreSnapshot(snapshot: BoardVersionSnapshot): Boolean {
        val current = _uiState.value.board
        val restored = BoardSnapshotSerializer.deserializeIntoBoard(snapshot.serializedData, current)
            ?: return false
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { restored }.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
        return true
    }

    fun deleteSnapshot(versionId: String) {
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(snapshots = b.snapshots.filter { s -> s.versionId != versionId })
            }
        }
    }

    private fun saveUndoSnapshot() {
        val b = _uiState.value.board
        undoStack.add(BoardSnapshot(b.strokes, b.stickyNotes, b.shapes, b.textItems))
        redoStack.clear()
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val currentBoard = _uiState.value.board
        redoStack.add(BoardSnapshot(currentBoard.strokes, currentBoard.stickyNotes, currentBoard.shapes, currentBoard.textItems))
        val previous = undoStack.removeAt(undoStack.lastIndex)

        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = previous.strokes,
                    stickyNotes = previous.notes,
                    shapes = previous.shapes,
                    textItems = previous.texts
                )
            }.copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = true,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val currentBoard = _uiState.value.board
        undoStack.add(BoardSnapshot(currentBoard.strokes, currentBoard.stickyNotes, currentBoard.shapes, currentBoard.textItems))
        val next = redoStack.removeAt(redoStack.lastIndex)

        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = next.strokes,
                    stickyNotes = next.notes,
                    shapes = next.shapes,
                    textItems = next.texts
                )
            }.copy(
                canUndo = true,
                canRedo = redoStack.isNotEmpty(),
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun setPenColor(hex: String, isRainbow: Boolean = false) {
        _uiState.update { it.copy(penColorHex = hex, isRainbowPen = isRainbow) }
    }

    fun setPenWidth(width: Float) {
        _uiState.update { it.copy(penWidth = width) }
    }

    fun setPenStyle(style: StrokeStyle) {
        _uiState.update { it.copy(penStyle = style) }
    }

    fun setPenCapStyle(capStyle: LineCapStyle) {
        _uiState.update { it.copy(penCapStyle = capStyle) }
    }

    fun toggleAutoShapeRecognition() {
        _uiState.update { it.copy(autoShapeRecognition = !it.autoShapeRecognition) }
    }

    /**
     * Applies boards received from the shared workspace store without resetting
     * transient canvas state (tool, camera, selection, undo/redo).
     */
    fun syncWorkspaceBoards(boards: List<WhiteboardBoard>) {
        if (boards.isEmpty()) return
        _uiState.update { state ->
            val currentBoard = boards.find { it.id == state.board.id }
                ?: if (state.isViewingBoardsHome) boards.first() else state.board
            if (state.allBoards == boards && state.board == currentBoard) {
                state
            } else {
                state.copy(
                    board = currentBoard,
                    allBoards = boards
                )
            }
        }
    }

}
