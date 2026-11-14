package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.BoardSnapshotSerializer
import com.example.ink.ShapeRecognizer
import com.example.ink.StrokeStabilizer
import com.example.model.BoardVersionSnapshot
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LaserPoint
import com.example.model.RulerState
import com.example.model.ShapeItem
import com.example.model.ShapeRecognitionProposal
import com.example.model.ShapeType
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class WhiteboardUiState(
    val board: WhiteboardBoard,
    val allBoards: List<WhiteboardBoard> = emptyList(),
    val isViewingBoardsHome: Boolean = true,
    val currentTool: ToolType = ToolType.PEN,
    val penColorHex: String = "#1E1E1E",
    val penWidth: Float = 6f,
    val penStyle: StrokeStyle = StrokeStyle.SOLID,
    val isRainbowPen: Boolean = false,
    val autoShapeRecognition: Boolean = false,
    val isRulerActive: Boolean = false,
    val rulerState: RulerState = RulerState(),
    val isSnapToGrid: Boolean = false,
    val isZenMode: Boolean = false,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val scale: Float = 1.0f,
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
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
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
        _uiState.update { it.copy(currentTool = tool) }
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

    fun onPanZoom(panX: Float, panY: Float, zoomChange: Float) {
        _uiState.update {
            val nextScale = (it.scale * zoomChange).coerceIn(0.20f, 5.0f)
            it.copy(
                scale = nextScale,
                panX = it.panX + panX,
                panY = it.panY + panY
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
            brushType = brushType,
            opacity = brushType.defaultOpacity
        )

        val recognized = if (state.autoShapeRecognition && !isTranslucentEmphasis) {
            ShapeRecognizer.recognizeStroke(newStroke)
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
        val tempStroke = WhiteboardStroke(
            points = points,
            colorHex = effectiveColorForBrush(brushType, state.penColorHex),
            width = effectiveStrokeWidthForBrush(brushType, state.penWidth),
            strokeStyle = state.penStyle,
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
                selectedStrokeId = null,
                selectedNoteId = null
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
            if (s.shapeType == ShapeType.LINE && s.vertices.size >= 2) {
                pointToSegmentDistance(worldX, worldY, s.vertices[0], s.vertices[1]) <= tolerance
            } else {
                worldX >= s.x - tolerance &&
                    worldX <= s.x + s.width + tolerance &&
                    worldY >= s.y - tolerance &&
                    worldY <= s.y + s.height + tolerance
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
     * Pixel Eraser: splits and trims only the touched portion of vector strokes cleanly.
     */
    fun erasePixelAt(
        worldX: Float,
        worldY: Float,
        radius: Float,
        saveSnapshot: Boolean = false
    ): Boolean {
        val board = _uiState.value.board
        if (board.strokes.isEmpty()) return false

        var anyErased = false
        val nextStrokes = mutableListOf<WhiteboardStroke>()

        for (stroke in board.strokes) {
            val effectiveRadius = radius + (stroke.width * 0.35f)
            val fragments = splitStrokeOutsideCircle(stroke.points, worldX, worldY, effectiveRadius)
            val unchanged = fragments.size == 1 && fragments.first().size == stroke.points.size
            if (unchanged) {
                nextStrokes.add(stroke)
            } else {
                anyErased = true
                fragments.forEachIndexed { idx, fragPts ->
                    if (fragPts.size >= 2) {
                        nextStrokes.add(
                            stroke.copy(
                                id = if (idx == 0) stroke.id else UUID.randomUUID().toString(),
                                points = fragPts
                            )
                        )
                    }
                }
            }
        }

        if (!anyErased) return false
        if (saveSnapshot) saveUndoSnapshot()
        _uiState.update {
            val stillHasSelected = nextStrokes.any { s -> s.id == it.selectedStrokeId }
            it.withUpdatedBoard { b -> b.copy(strokes = nextStrokes) }.copy(
                selectedStrokeId = if (stillHasSelected) it.selectedStrokeId else null
            )
        }
        return true
    }

    private fun splitStrokeOutsideCircle(
        points: List<StrokePoint>,
        cx: Float,
        cy: Float,
        radius: Float
    ): List<List<StrokePoint>> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<MutableList<StrokePoint>>()
        var current = mutableListOf<StrokePoint>()

        fun isInside(p: StrokePoint): Boolean = hypot(p.x - cx, p.y - cy) <= radius

        var prev = points.first()
        var prevIn = isInside(prev)
        if (!prevIn) {
            current.add(prev)
        }

        for (i in 1 until points.size) {
            val cur = points[i]
            val curIn = isInside(cur)
            val segHitsCircle = !prevIn && !curIn && pointToSegmentDistance(cx, cy, prev, cur) <= radius

            if (curIn || segHitsCircle) {
                if (current.size >= 2) {
                    result.add(current)
                }
                current = mutableListOf()
                if (!curIn) {
                    current.add(cur)
                }
            } else {
                current.add(cur)
            }
            prev = cur
            prevIn = curIn
        }

        if (current.size >= 2) {
            result.add(current)
        }
        return result
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
            val cx = s.x + s.width / 2f
            val cy = s.y + s.height / 2f
            isPointInPolygon(cx, cy, lassoPolygon) || (
                isPointInPolygon(s.x, s.y, lassoPolygon) &&
                isPointInPolygon(s.x + s.width, s.y, lassoPolygon) &&
                isPointInPolygon(s.x + s.width, s.y + s.height, lassoPolygon) &&
                isPointInPolygon(s.x, s.y + s.height, lassoPolygon)
            ) || (s.vertices.isNotEmpty() && s.vertices.any { isPointInPolygon(it.x, it.y, lassoPolygon) })
        }.map { it.id }.toSet()

        val enclosedStrokeIds = board.strokes.filter { s ->
            if (s.points.isEmpty()) false
            else {
                val insideCount = s.points.count { p -> isPointInPolygon(p.x, p.y, lassoPolygon) }
                insideCount > 0 && (insideCount * 2 >= s.points.size || insideCount >= 2)
            }
        }.map { it.id }.toSet()

        val enclosedNoteIds = board.stickyNotes.filter { n ->
            val cx = n.x + n.width / 2f
            val cy = n.y + n.height / 2f
            isPointInPolygon(cx, cy, lassoPolygon) || (
                isPointInPolygon(n.x, n.y, lassoPolygon) &&
                isPointInPolygon(n.x + n.width, n.y, lassoPolygon)
            )
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
                b.copy(stickyNotes = b.stickyNotes.map { n -> if (n.id == id) n.copy(text = newText) else n })
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

    fun moveStickyNote(id: String, deltaX: Float, deltaY: Float) {
        val state = _uiState.value
        val updated = state.board.stickyNotes.map { note ->
            if (note.id == id) {
                var nextX = note.x + (deltaX / state.scale)
                var nextY = note.y + (deltaY / state.scale)
                if (state.isSnapToGrid) {
                    nextX = kotlin.math.round(nextX / 40f) * 40f
                    nextY = kotlin.math.round(nextY / 40f) * 40f
                }
                note.copy(x = nextX, y = nextY)
            } else note
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(stickyNotes = updated) } }
    }

    fun deleteStickyNote(id: String) {
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(stickyNotes = b.stickyNotes.filter { n -> n.id != id })
            }.copy(selectedNoteId = null)
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
                b.copy(
                    strokes = b.strokes.filter { s -> !strokeIds.contains(s.id) },
                    shapes = b.shapes.filter { sh -> !shapeIds.contains(sh.id) },
                    stickyNotes = b.stickyNotes.filter { n -> !noteIds.contains(n.id) }
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
        style: StrokeStyle
    ) {
        _uiState.update {
            it.copy(
                pendingShapeType = type,
                pendingStickyNoteColor = null,
                pendingIsFilled = isFilled,
                pendingStrokeColor = strokeColor,
                pendingFillColor = fillColor,
                pendingStrokeStyle = style,
                selectedShapeId = null,
                selectedStrokeId = null,
                selectedNoteId = null
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
                    b.copy(stickyNotes = b.stickyNotes + newNote)
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
        val newShape: ShapeItem = if (shapeType == ShapeType.LINE) {
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
                shapeType = ShapeType.LINE,
                x = minX,
                y = minY,
                width = (maxX - minX).coerceAtLeast(1f),
                height = (maxY - minY).coerceAtLeast(1f),
                strokeColorHex = state.pendingStrokeColor,
                fillColorHex = state.pendingFillColor,
                strokeWidth = state.penWidth.coerceIn(2f, 16f),
                isFilled = false,
                strokeStyle = state.pendingStrokeStyle,
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
                selectedStrokeId = null,
                selectedNoteId = null,
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
        screenW: Float = 1080f,
        screenH: Float = 1920f
    ) {
        requestAddCustomShape(type, isFilled, strokeColor, fillColor, style)
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
                    selectedShapeId = recognized.shapeItem.id
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
        style: StrokeStyle? = null
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
                    strokeStyle = style ?: it.strokeStyle
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
            if (shapeIds.contains(sh.id)) {
                sh.copy(
                    x = sh.x + dxWorld,
                    y = sh.y + dyWorld,
                    vertices = sh.vertices.map { StrokePoint(it.x + dxWorld, it.y + dyWorld) }
                )
            } else sh
        }
        val updatedNotes = if (noteIds.isEmpty()) state.board.stickyNotes else state.board.stickyNotes.map { n ->
            if (noteIds.contains(n.id)) {
                n.copy(x = n.x + dxWorld, y = n.y + dyWorld)
            } else n
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = updatedStrokes, shapes = updatedShapes, stickyNotes = updatedNotes)
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
            } else if (s.shapeType == ShapeType.LINE && s.vertices.size >= 2) {
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
            } else if (s.shapeType == ShapeType.LINE && s.vertices.size >= 2) {
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
        val updated = state.board.shapes.map { s ->
            if (shapeIds.contains(s.id)) {
                val next = ((s.rotation + deltaDegrees) % 360f + 360f) % 360f
                s.copy(rotation = next)
            } else s
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

    fun applyTemplate(type: TemplateType, screenW: Float = 1080f, screenH: Float = 1920f) {
        saveUndoSnapshot()
        val state = _uiState.value
        val worldCenterX = (screenW / 2f - state.panX) / state.scale
        val worldCenterY = (screenH / 2f - state.panY) / state.scale
        val worldX = worldCenterX - 400f
        val worldY = worldCenterY - 220f

        when (type) {
            TemplateType.KANBAN -> {
                val colWidth = 260f
                val colHeight = 440f
                val cols = listOf("Por Hacer", "En Curso", "Terminado")
                val colors = listOf("#FFF275", "#86E3CE", "#A1F0A6")

                val newShapes = cols.mapIndexed { idx, _ ->
                    ShapeItem(
                        shapeType = ShapeType.ROUNDED_RECT,
                        x = worldX + (idx * (colWidth + 20f)),
                        y = worldY,
                        width = colWidth,
                        height = colHeight,
                        strokeColorHex = "#0078D4",
                        fillColorHex = "#F3F7FC",
                        isFilled = true,
                        strokeWidth = 2.5f
                    )
                }

                val newNotes = cols.mapIndexed { idx, title ->
                    StickyNote(
                        text = "📋 $title:\n• Tarea 1\n• Tarea 2",
                        x = worldX + (idx * (colWidth + 20f)) + 20f,
                        y = worldY + 30f,
                        width = 220f,
                        height = 180f,
                        colorHex = colors[idx]
                    )
                }

                _uiState.update {
                    it.withUpdatedBoard { b ->
                        b.copy(
                            shapes = b.shapes + newShapes,
                            stickyNotes = b.stickyNotes + newNotes
                        )
                    }
                }
            }
            else -> {}
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
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = null,
                pendingShapeType = null,
                pendingStickyNoteColor = null,
                canUndo = if (openImmediately) false else it.canUndo,
                canRedo = if (openImmediately) false else it.canRedo,
                panX = if (openImmediately) 0f else it.panX,
                panY = if (openImmediately) 0f else it.panY,
                scale = if (openImmediately) 1.0f else it.scale
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
                selectedNoteId = null
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
                selectedNoteId = null
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
                selectedShapeId = null
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
                canRedo = redoStack.isNotEmpty()
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

    fun toggleAutoShapeRecognition() {
        _uiState.update { it.copy(autoShapeRecognition = !it.autoShapeRecognition) }
    }
}
