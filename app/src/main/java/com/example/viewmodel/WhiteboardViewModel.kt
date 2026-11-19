package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.BoardSnapshotSerializer
import com.example.data.LocalFirstWorkspaceStore
import com.example.data.WorkspaceDbState
import com.example.ink.PixelEraserGeometry
import com.example.ink.ShapeRecognizer
import com.example.ink.StickyNoteBounds
import com.example.ink.StickyNoteGeometry
import com.example.ink.StickyNoteResizeHandle
import com.example.ink.ViewportTransform
import com.example.model.AuditLogEntry
import com.example.model.BoardVersionSnapshot
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LaserPoint
import com.example.model.PenToolSlot
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
import com.example.model.TextItem
import com.example.model.ToolType
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val penOpacity: Float = 1f,
    val penToolSlots: List<PenToolSlot> = PenToolSlot.EMPTY_SLOTS,
    val activePenSlotIndex: Int = 0,
    val penStyle: StrokeStyle = StrokeStyle.SOLID,
    val penCapStyle: LineCapStyle = LineCapStyle.NONE,
    val isRainbowPen: Boolean = false,
    val autoShapeRecognition: Boolean = false,
    val isRulerActive: Boolean = false,
    val rulerState: RulerState = RulerState(),
    val isSnapToGrid: Boolean = false,
    val isZenMode: Boolean = false,
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
    val pendingBrushType: StrokeBrushType = StrokeBrushType.FOUNTAIN_PEN,
    val handDrawnFactor: Float = 0.24f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

private data class BoardSnapshot(
    val strokes: List<WhiteboardStroke>,
    val notes: List<StickyNote>,
    val shapes: List<ShapeItem>,
    val texts: List<TextItem>
)

class WhiteboardViewModel(
    private val store: LocalFirstWorkspaceStore = LocalFirstWorkspaceStore()
) : ViewModel() {

    private val undoStack = mutableListOf<BoardSnapshot>()
    private val redoStack = mutableListOf<BoardSnapshot>()
    private var isCommittingToStore = false
    // Sticky-note transforms update only the target note's StateFlow on every pointer event
    // and consolidate into WhiteboardUiState + the shared store once when the gesture ends.
    private var isStickyNoteTransformInProgress = false
    private val noteSyncLock = Any()
    private val backgroundSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingNoteTextSyncJob: Job? = null
    @Volatile
    private var hasPendingNoteTextSync = false

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<WhiteboardUiState> = _uiState.asStateFlow()

    val stickyNotesStore = StickyNoteStateStore(_uiState.value.board.stickyNotes)

    // Camera updates are deliberately isolated from content state so pan/zoom never
    // publishes a new WhiteboardUiState or invalidates toolbars and editor panels.
    private val _cameraState = MutableStateFlow(ViewportTransform())
    val cameraState: StateFlow<ViewportTransform> = _cameraState.asStateFlow()

    private val storeListener: (WorkspaceDbState) -> Unit = { db ->
        if (!isCommittingToStore && db.boards.isNotEmpty()) {
            syncWorkspaceBoards(db.boards)
        }
    }

    init {
        val currentBoards = store.currentState.boards
        if (currentBoards.isEmpty()) {
            store.ensureBoardRegistered(_uiState.value.board)
        } else {
            _uiState.update { state ->
                val cur = currentBoards.find { it.id == state.board.id } ?: currentBoards.first()
                state.copy(board = cur, allBoards = currentBoards)
            }
            stickyNotesStore.replaceAll(_uiState.value.board.stickyNotes)
        }
        store.addListener(storeListener)
    }

    override fun onCleared() {
        flushPendingStickyNoteSync(persist = true)
        backgroundSyncScope.cancel()
        store.removeListener(storeListener)
        super.onCleared()
    }

    private fun scheduleNoteTextSync() {
        synchronized(noteSyncLock) {
            hasPendingNoteTextSync = true
            pendingNoteTextSyncJob?.cancel()
            pendingNoteTextSyncJob = backgroundSyncScope.launch {
                delay(300L)
                flushPendingStickyNoteSync(persist = true)
            }
        }
    }

    private fun syncStickyNotesIntoBoardState() {
        val latestNotes = stickyNotesStore.currentNotes()
        _uiState.update { state ->
            if (state.board.stickyNotes == latestNotes) {
                state
            } else {
                state.withUpdatedBoard { b -> b.copy(stickyNotes = latestNotes) }
            }
        }
    }

    private fun flushPendingStickyNoteSync(persist: Boolean) {
        synchronized(noteSyncLock) {
            if (!hasPendingNoteTextSync) return
            hasPendingNoteTextSync = false
            pendingNoteTextSyncJob?.cancel()
            pendingNoteTextSyncJob = null
            syncStickyNotesIntoBoardState()
            commitBoardMutationToStore(
                _uiState.value.board,
                softDeleteMissingNotes = false,
                persist = persist
            )
        }
    }

    private fun commitBoardMutationToStore(
        board: WhiteboardBoard,
        softDeleteMissingNotes: Boolean = false,
        persist: Boolean = true
    ) {
        isCommittingToStore = true
        try {
            val db = store.syncBoardFromWhiteboard(
                board = board,
                softDeleteMissingNotes = softDeleteMissingNotes,
                persist = persist
            )
            applyCanonicalBoardsFromDb(db.boards)
        } finally {
            isCommittingToStore = false
        }
    }

    private fun applyCanonicalBoardsFromDb(boards: List<WhiteboardBoard>) {
        if (boards.isEmpty()) return
        var activeBoardChangedOrUpdated: WhiteboardBoard? = null
        _uiState.update { state ->
            val currentBoard = boards.find { it.id == state.board.id }
                ?: if (state.isViewingBoardsHome) boards.first() else state.board
            if (state.allBoards == boards && state.board == currentBoard) {
                state
            } else {
                activeBoardChangedOrUpdated = currentBoard
                state.copy(board = currentBoard, allBoards = boards)
            }
        }
        activeBoardChangedOrUpdated?.let { updatedBoard ->
            if (!isStickyNoteTransformInProgress && !hasPendingNoteTextSync) {
                stickyNotesStore.replaceAll(updatedBoard.stickyNotes)
            }
        }
    }

    fun commitWorkspaceSync() {
        var didConsolidate = false
        if (isStickyNoteTransformInProgress) {
            isStickyNoteTransformInProgress = false
            stickyNotesStore.resetDragAccumulator()
            syncStickyNotesIntoBoardState()
            commitBoardMutationToStore(
                _uiState.value.board,
                softDeleteMissingNotes = false,
                persist = false
            )
            didConsolidate = true
        }
        if (hasPendingNoteTextSync) {
            flushPendingStickyNoteSync(persist = false)
            didConsolidate = true
        }
        if (didConsolidate || store.currentState.boards.isNotEmpty()) {
            store.flushToDisk()
        }
    }

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
            isViewingBoardsHome = true,
            penToolSlots = store.currentState.penToolSlots
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
        if (tool == ToolType.HAND) {
            flushPendingStickyNoteSync(persist = true)
        }
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
                state.copy(
                    currentTool = tool,
                    pendingBrushType = brushTypeForTool(tool)
                )
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
        _cameraState.update { it.adaptToViewportResize(newWidthPx, newHeightPx) }
    }

    fun onPanZoom(
        panX: Float,
        panY: Float,
        zoomChange: Float,
        focalX: Float? = null,
        focalY: Float? = null
    ) {
        _cameraState.update {
            it.panAndZoom(
                deltaPanX = panX,
                deltaPanY = panY,
                zoomChange = zoomChange,
                focalX = focalX,
                focalY = focalY
            )
        }
    }

    fun zoomIn() {
        _cameraState.update { it.copy(scale = (it.scale * 1.2f).coerceAtMost(ViewportTransform.MAX_SCALE)) }
    }

    fun zoomOut() {
        _cameraState.update { it.copy(scale = (it.scale / 1.2f).coerceAtLeast(ViewportTransform.MIN_SCALE)) }
    }

    fun setZoomPreset(targetScale: Float) {
        _cameraState.update { it.copy(scale = targetScale.coerceIn(ViewportTransform.MIN_SCALE, ViewportTransform.MAX_SCALE)) }
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

        stickyNotesStore.currentNotes().forEach { n ->
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

        _cameraState.update {
            val newPanX = (screenWidth / 2f) - (centerX * optimalScale)
            val newPanY = (screenHeight / 2f) - (centerY * optimalScale)
            it.copy(scale = optimalScale, panX = newPanX, panY = newPanY)
        }
    }

    fun navigateToWorld(worldX: Float, worldY: Float, screenW: Float = 1080f, screenH: Float = 1920f) {
        _cameraState.update {
            it.copy(
                panX = (screenW / 2f) - (worldX * it.scale),
                panY = (screenH / 2f) - (worldY * it.scale)
            )
        }
    }

    fun setHandDrawnFactor(factor: Float) {
        _uiState.update { it.copy(handDrawnFactor = factor.coerceIn(0f, 1f)) }
    }

    fun brushTypeForTool(tool: ToolType): StrokeBrushType {
        return when (tool) {
            ToolType.PEN -> StrokeBrushType.FOUNTAIN_PEN
            ToolType.FINE_MARKER -> StrokeBrushType.FINE_MARKER
            ToolType.HIGHLIGHTER -> StrokeBrushType.HIGHLIGHTER
            else -> StrokeBrushType.FOUNTAIN_PEN
        }
    }

    fun effectiveStrokeWidthForBrush(brushType: StrokeBrushType, basePenWidth: Float): Float {
        return when (brushType) {
            StrokeBrushType.HIGHLIGHTER -> (basePenWidth * 2.2f).coerceAtLeast(16f)
            StrokeBrushType.FINE_MARKER -> (basePenWidth * 0.65f).coerceIn(2f, 8f)
            StrokeBrushType.FOUNTAIN_PEN -> basePenWidth
        }
    }

    fun effectiveColorForBrush(brushType: StrokeBrushType, penColorHex: String): String {
        return penColorHex
    }

    fun commitCompletedStroke(points: List<StrokePoint>, isHighlighter: Boolean) {
        if (points.size < 2) return
        saveUndoSnapshot()
        val state = _uiState.value
        val brushType = if (isHighlighter) StrokeBrushType.HIGHLIGHTER else brushTypeForTool(state.currentTool)
        val effectiveWidth = effectiveStrokeWidthForBrush(brushType, state.penWidth)
        val effectiveColor = effectiveColorForBrush(brushType, state.penColorHex)
        val isTranslucentEmphasis = brushType == StrokeBrushType.HIGHLIGHTER

        val newStroke = WhiteboardStroke(
            points = points,
            colorHex = effectiveColor,
            width = effectiveWidth,
            isHighlighter = brushType == StrokeBrushType.HIGHLIGHTER,
            isRainbow = state.isRainbowPen && !isTranslucentEmphasis,
            strokeStyle = StrokeStyle.SOLID,
            capStyle = LineCapStyle.NONE,
            brushType = brushType,
            opacity = state.penOpacity.coerceIn(0.05f, 1f)
        )

        val recognized = if (state.autoShapeRecognition) {
            ShapeRecognizer.recognizeStroke(newStroke.copy(points = points))
        } else {
            null
        }

        if (recognized != null && recognized.confidence >= 0.80f && recognized.shapeType != ShapeType.LINE) {
            val log = AuditLogEntry(
                actionName = "AUTO_SHAPE_RECOGNIZED",
                details = "Forma ${recognized.shapeType.displayName} reconocida (${(recognized.confidence * 100).toInt()}%)",
                category = "SHAPES"
            )
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(
                        shapes = b.shapes + recognized.shapeItem,
                        auditLogs = (b.auditLogs + log).takeLast(100)
                    )
                }.copy(
                    activeStroke = null,
                    shapeRecognitionProposal = null
                )
            }
        } else {
            val log = AuditLogEntry(
                actionName = "STROKE_DRAWN",
                details = "Trazo ${newStroke.brushType.displayName} (${newStroke.points.size} pts)",
                category = "INK"
            )
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(
                        strokes = b.strokes + newStroke,
                        auditLogs = (b.auditLogs + log).takeLast(100)
                    )
                }.copy(
                    activeStroke = null,
                    shapeRecognitionProposal = null
                )
            }
        }
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
        val hitStroke = board.strokes.lastOrNull { stroke ->
            val hitRadius = tolerance + stroke.width * 0.4f
            stroke.geometryMetrics.intersectsCircle(worldX, worldY, hitRadius) &&
                isPointNearStroke(stroke.points, worldX, worldY, hitRadius)
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
            if (!stroke.geometryMetrics.intersectsCircle(worldX, worldY, effectiveRadius)) {
                nextStrokes.add(stroke)
                continue
            }
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

        val enclosedNoteIds = stickyNotesStore.currentNotes().filter { n ->
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
        val camera = _cameraState.value
        val worldCenterX = (screenW / 2f - camera.panX) / camera.scale
        val worldCenterY = (screenH / 2f - camera.panY) / camera.scale
        val worldX = worldCenterX - 110f
        val worldY = worldCenterY - 100f

        val newNote = StickyNote(
            title = "",
            text = "",
            x = worldX,
            y = worldY,
            colorHex = colorHex
        )

        stickyNotesStore.addNote(newNote)
        _uiState.update {
            it.withUpdatedBoard { b -> b.copy(stickyNotes = stickyNotesStore.currentNotes()) }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun updateStickyNoteTitle(id: String, newTitle: String) {
        val updated = stickyNotesStore.updateSingle(id) { n ->
            val adaptedH = StickyNoteGeometry.calculateAdaptiveHeightWorld(
                text = n.text,
                noteWidthWorld = n.width,
                currentHeightWorld = n.height,
                isAspectRatioLocked = n.isAspectRatioLocked,
                title = newTitle
            )
            if (n.isAspectRatioLocked && adaptedH > n.height) {
                n.copy(title = newTitle, width = adaptedH, height = adaptedH)
            } else if (adaptedH > n.height) {
                n.copy(title = newTitle, height = adaptedH)
            } else {
                n.copy(title = newTitle)
            }
        }
        if (updated != null) {
            scheduleNoteTextSync()
        }
    }

    fun updateStickyNoteText(id: String, newText: String) {
        val updated = stickyNotesStore.updateSingle(id) { n ->
            val adaptedH = StickyNoteGeometry.calculateAdaptiveHeightWorld(
                text = newText,
                noteWidthWorld = n.width,
                currentHeightWorld = n.height,
                isAspectRatioLocked = n.isAspectRatioLocked,
                title = n.title
            )
            if (n.isAspectRatioLocked && adaptedH > n.height) {
                n.copy(text = newText, width = adaptedH, height = adaptedH)
            } else if (adaptedH > n.height) {
                n.copy(text = newText, height = adaptedH)
            } else {
                n.copy(text = newText)
            }
        }
        if (updated != null) {
            scheduleNoteTextSync()
        }
    }

    fun updateStickyNoteColor(id: String, colorHex: String) {
        saveUndoSnapshot()
        stickyNotesStore.updateSingle(id) { n -> n.copy(colorHex = colorHex) }
        syncStickyNotesIntoBoardState()
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun toggleStickyNotePin(id: String) {
        saveUndoSnapshot()
        stickyNotesStore.updateSingle(id) { n -> n.copy(isPinned = !n.isPinned) }
        syncStickyNotesIntoBoardState()
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    /**
     * Moves a sticky note by a pure World-Space delta (deltaWorldX, deltaWorldY).
     * While [isStickyNoteTransformInProgress] is true, only that note's isolated StateFlow
     * updates (0 WhiteboardUiState emissions, 0 sibling recompositions).
     */
    fun moveStickyNote(id: String, deltaWorldX: Float, deltaWorldY: Float) {
        val before = stickyNotesStore.getOrNull(id) ?: return
        val updated = stickyNotesStore.moveSingleWorld(
            id = id,
            worldDx = deltaWorldX,
            worldDy = deltaWorldY,
            snapToGrid = _uiState.value.isSnapToGrid
        ) ?: return

        if (isStickyNoteTransformInProgress) return

        if (updated != before) {
            syncStickyNotesIntoBoardState()
            commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = false)
        }
    }

    fun resizeStickyNoteHandle(
        id: String,
        handle: StickyNoteResizeHandle,
        deltaScreenX: Float,
        deltaScreenY: Float
    ) {
        val camera = _cameraState.value
        val before = stickyNotesStore.getOrNull(id) ?: return
        if (before.isLocked) return

        val updated = stickyNotesStore.updateSingle(id) { note ->
            val next = StickyNoteGeometry.resizeByHandle(
                bounds = StickyNoteBounds(note.x, note.y, note.width, note.height),
                handle = handle,
                deltaScreenX = deltaScreenX,
                deltaScreenY = deltaScreenY,
                scale = camera.scale,
                lockAspectRatio = note.isAspectRatioLocked,
                targetAspectRatio = 1.0f,
                rotationDegrees = note.rotation
            )
            note.copy(
                x = next.x,
                y = next.y,
                width = next.width,
                height = next.height
            )
        } ?: return

        if (isStickyNoteTransformInProgress) return

        if (updated != before) {
            syncStickyNotesIntoBoardState()
            commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = false)
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
        val cleanW = newWidth.coerceIn(StickyNoteGeometry.MIN_SIZE_WORLD, StickyNoteGeometry.MAX_SIZE_WORLD)
        val cleanH = newHeight.coerceIn(StickyNoteGeometry.MIN_SIZE_WORLD, StickyNoteGeometry.MAX_SIZE_WORLD)
        if (saveSnapshot) saveUndoSnapshot()
        stickyNotesStore.updateSingle(id) { note ->
            if (!note.isLocked) note.copy(x = newX, y = newY, width = cleanW, height = cleanH) else note
        }
        syncStickyNotesIntoBoardState()
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = saveSnapshot)
    }

    fun toggleStickyNoteAspectRatioLock(id: String) {
        val target = stickyNotesStore.getOrNull(id) ?: return
        saveUndoSnapshot()
        val nextLocked = !target.isAspectRatioLocked
        stickyNotesStore.updateSingle(id) { note ->
            if (nextLocked) {
                val side = ((note.width + note.height) * 0.5f)
                    .coerceIn(StickyNoteGeometry.MIN_SIZE_WORLD, StickyNoteGeometry.MAX_SIZE_WORLD)
                note.copy(width = side, height = side, isAspectRatioLocked = true)
            } else {
                note.copy(isAspectRatioLocked = false)
            }
        }
        syncStickyNotesIntoBoardState()
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun rotateStickyNote(
        id: String,
        rotationDegrees: Float,
        saveSnapshot: Boolean = false
    ) {
        if (saveSnapshot) saveUndoSnapshot()
        val cleanAngle = ((rotationDegrees % 360f) + 360f) % 360f
        val before = stickyNotesStore.getOrNull(id) ?: return
        if (before.isLocked) return

        val updated = stickyNotesStore.updateSingle(id) { note ->
            if (!note.isLocked) note.copy(rotation = cleanAngle) else note
        } ?: return

        if (isStickyNoteTransformInProgress) return

        if (updated != before) {
            syncStickyNotesIntoBoardState()
            commitBoardMutationToStore(
                _uiState.value.board,
                softDeleteMissingNotes = false,
                persist = saveSnapshot
            )
        }
    }

    fun deleteStickyNote(id: String) {
        saveUndoSnapshot()
        stickyNotesStore.removeNotes(setOf(id))
        _uiState.update { state ->
            val remainingNoteIds = state.selectedNoteIds - id
            state.withUpdatedBoard { b ->
                b.copy(stickyNotes = stickyNotesStore.currentNotes())
            }.copy(
                selectedNoteId = if (state.selectedNoteId == id) remainingNoteIds.firstOrNull() else state.selectedNoteId,
                selectedNoteIds = remainingNoteIds
            )
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = true, persist = true)
    }

    fun detachStickyNoteFromBoard(id: String) {
        saveUndoSnapshot()
        stickyNotesStore.removeNotes(setOf(id))
        _uiState.update { state ->
            val remainingNoteIds = state.selectedNoteIds - id
            state.withUpdatedBoard { b ->
                b.copy(stickyNotes = stickyNotesStore.currentNotes())
            }.copy(
                selectedNoteId = if (state.selectedNoteId == id) remainingNoteIds.firstOrNull() else state.selectedNoteId,
                selectedNoteIds = remainingNoteIds
            )
        }
        isCommittingToStore = true
        try {
            val db = store.detachNoteFromCanvas(id)
            applyCanonicalBoardsFromDb(db.boards)
        } finally {
            isCommittingToStore = false
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
        stickyNotesStore.removeNotes(noteIds)

        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = b.strokes.filter { s -> !strokeIds.contains(s.id) },
                    shapes = b.shapes.filter { sh -> sh.isLocked || !shapeIds.contains(sh.id) },
                    stickyNotes = stickyNotesStore.currentNotes()
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
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = true, persist = true)
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
        capStyle: LineCapStyle = LineCapStyle.NONE,
        brushType: StrokeBrushType = brushTypeForTool(_uiState.value.currentTool)
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
                pendingBrushType = brushType,
                selectedShapeId = null,
                selectedStrokeId = null,
                selectedNoteId = null,
                selectedShapeIds = emptySet(),
                selectedStrokeIds = emptySet(),
                selectedNoteIds = emptySet()
            )
        }
    }

    fun setPendingBrushType(brushType: StrokeBrushType) {
        _uiState.update { it.copy(pendingBrushType = brushType) }
    }

    fun cancelPendingPlacement() {
        _uiState.update { it.copy(pendingStickyNoteColor = null, pendingShapeType = null) }
    }

    fun placePendingAt(worldX: Float, worldY: Float) {
        val state = _uiState.value
        if (state.pendingStickyNoteColor != null) {
            saveUndoSnapshot()
            val newNote = StickyNote(
                title = "",
                text = "",
                x = worldX - 110f,
                y = worldY - 100f,
                colorHex = state.pendingStickyNoteColor
            )
            stickyNotesStore.addNote(newNote)
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(stickyNotes = stickyNotesStore.currentNotes())
                }.copy(
                    pendingStickyNoteColor = null,
                    selectedNoteId = newNote.id,
                    selectedNoteIds = setOf(newNote.id)
                )
            }
            commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
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
            val activeBrush = state.pendingBrushType
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
                vertices = listOf(v0, v1),
                brushType = activeBrush
            )
        } else if (dragDist < 14f) {
            val isSquareLike = shapeType == ShapeType.CIRCLE ||
                shapeType == ShapeType.STAR ||
                shapeType == ShapeType.HEXAGON ||
                shapeType == ShapeType.DIAMOND ||
                shapeType == ShapeType.TRIANGLE
            val defW = if (isSquareLike) 160f else 190f
            val defH = if (isSquareLike) 160f else 130f
            val activeBrush = state.pendingBrushType
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
                strokeStyle = state.pendingStrokeStyle,
                brushType = activeBrush
            )
        } else {
            val minX = kotlin.math.min(start.x, end.x)
            val minY = kotlin.math.min(start.y, end.y)
            val w = kotlin.math.abs(end.x - start.x).coerceAtLeast(28f)
            val h = kotlin.math.abs(end.y - start.y).coerceAtLeast(28f)
            val activeBrush = state.pendingBrushType
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
                strokeStyle = state.pendingStrokeStyle,
                brushType = activeBrush
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
        flushPendingStickyNoteSync(persist = true)
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
        flushPendingStickyNoteSync(persist = true)
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
        if (_uiState.value.selectedNoteId != id) {
            flushPendingStickyNoteSync(persist = true)
        }
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
        flushPendingStickyNoteSync(persist = true)
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

    fun updateSelectedShapeBrushType(brushType: StrokeBrushType) {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        saveUndoSnapshot()
        val updated = state.board.shapes.map {
            if (shapeIds.contains(it.id)) it.copy(brushType = brushType) else it
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun updateSelectedStrokeBrushType(brushType: StrokeBrushType) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: return
        if (strokeIds.isEmpty()) return
        saveUndoSnapshot()
        val updated = state.board.strokes.map { s ->
            if (strokeIds.contains(s.id)) {
                s.copy(
                    brushType = brushType,
                    isHighlighter = brushType == StrokeBrushType.HIGHLIGHTER,
                    opacity = brushType.defaultOpacity
                )
            } else s
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updated) } }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
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

    fun beginStickyNoteTransformSnapshot() {
        // Recover a cancelled previous gesture before starting a new undo transaction.
        if (isStickyNoteTransformInProgress) commitWorkspaceSync()
        saveUndoSnapshot()
        isStickyNoteTransformInProgress = true
    }

    fun updateTextItem(id: String, newText: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id) item.copy(text = newText) else item
                })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun updateShapeLabel(id: String, newLabel: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(shapes = board.shapes.map { shape ->
                    if (shape.id == id) shape.copy(label = newLabel) else shape
                })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun updateSelectedShape(
        strokeColor: String? = null,
        fillColor: String? = null,
        isFilled: Boolean? = null,
        width: Float? = null,
        style: StrokeStyle? = null,
        capStyle: LineCapStyle? = null,
        brushType: StrokeBrushType? = null
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
                    capStyle = capStyle ?: it.capStyle,
                    brushType = brushType ?: it.brushType
                )
            } else it
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun moveSelectedObjects(dxWorld: Float, dyWorld: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val noteIds = if (state.selectedNoteIds.isNotEmpty()) state.selectedNoteIds else state.selectedNoteId?.let { setOf(it) } ?: emptySet()

        if (strokeIds.isEmpty() && shapeIds.isEmpty() && noteIds.isEmpty()) return

        if (noteIds.isNotEmpty()) {
            stickyNotesStore.moveMultipleWorld(noteIds, dxWorld, dyWorld)
        }
        if (strokeIds.isEmpty() && shapeIds.isEmpty() && isStickyNoteTransformInProgress) {
            return
        }

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
        val updatedNotes = if (noteIds.isEmpty()) state.board.stickyNotes else stickyNotesStore.currentNotes()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = updatedStrokes, shapes = updatedShapes, stickyNotes = updatedNotes)
            }
        }
        if (noteIds.isNotEmpty() && !isStickyNoteTransformInProgress) {
            commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = false)
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
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun setSelectedShapeRotation(degrees: Float) {
        val state = _uiState.value
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: return
        if (shapeIds.isEmpty()) return
        val normalized = ((degrees % 360f) + 360f) % 360f
        val updated = state.board.shapes.map { s ->
            if (shapeIds.contains(s.id)) s.copy(rotation = normalized) else s
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
    }

    fun persistCurrentBoard() {
        flushPendingStickyNoteSync(persist = false)
        syncStickyNotesIntoBoardState()
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun rotateSelectedShapeToPoint(worldX: Float, worldY: Float) {
        val state = _uiState.value
        val shapeId = state.selectedShapeId ?: state.selectedShapeIds.firstOrNull() ?: return
        val target = state.board.shapes.find { it.id == shapeId } ?: return
        if (target.shapeType.isLineLike && target.vertices.size >= 2) {
            val p0 = target.vertices[0]
            val p1 = target.vertices[1]
            val cx = (p0.x + p1.x) / 2f
            val cy = (p0.y + p1.y) / 2f
            val halfLen = kotlin.math.hypot(p1.x - p0.x, p1.y - p0.y) / 2f
            val rawDeg = Math.toDegrees(kotlin.math.atan2((worldY - cy).toDouble(), (worldX - cx).toDouble())).toFloat()
            val snappedDeg = com.example.ink.StickyNoteGeometry.snapAngle(rawDeg, 3.5f)
            val angle = Math.toRadians(snappedDeg.toDouble()).toFloat()
            val nv0 = StrokePoint(cx - halfLen * cos(angle), cy - halfLen * sin(angle))
            val nv1 = StrokePoint(cx + halfLen * cos(angle), cy + halfLen * sin(angle))
            val minX = kotlin.math.min(nv0.x, nv1.x)
            val maxX = kotlin.math.max(nv0.x, nv1.x)
            val minY = kotlin.math.min(nv0.y, nv1.y)
            val maxY = kotlin.math.max(nv0.y, nv1.y)
            val updated = state.board.shapes.map { s ->
                if (s.id == shapeId) {
                    s.copy(
                        x = minX,
                        y = minY,
                        width = (maxX - minX).coerceAtLeast(1f),
                        height = (maxY - minY).coerceAtLeast(1f),
                        vertices = listOf(nv0, nv1)
                    )
                } else s
            }
            _uiState.update { it.withUpdatedBoard { b -> b.copy(shapes = updated) } }
        } else {
            val cx = target.x + target.width / 2f
            val cy = target.y + target.height / 2f
            val deg = com.example.ink.StickyNoteGeometry.calculateRotationDegrees(
                centerX = cx,
                centerY = cy,
                pointerX = worldX,
                pointerY = worldY,
                snapThresholdDegrees = 3.5f
            )
            setSelectedShapeRotation(deg)
        }
    }

    private var strokeRotationSnapshots: Map<String, List<StrokePoint>> = emptyMap()
    private var strokeRotationPivot: Pair<Float, Float>? = null
    private var strokeRotationStartAngleDeg: Float? = null

    fun resetStrokeDragAngle() {
        strokeRotationSnapshots = emptyMap()
        strokeRotationPivot = null
        strokeRotationStartAngleDeg = null
    }

    fun rotateSelectedStrokeToPoint(worldX: Float, worldY: Float): Float {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) {
            state.selectedStrokeIds
        } else {
            state.selectedStrokeId?.let { setOf(it) } ?: return 0f
        }
        val selectedStrokes = state.board.strokes.filter { strokeIds.contains(it.id) && it.points.isNotEmpty() }
        if (selectedStrokes.isEmpty()) return 0f

        if (strokeRotationSnapshots.isEmpty() || strokeRotationPivot == null) {
            strokeRotationSnapshots = selectedStrokes.associate { it.id to it.points }
            val allPts = selectedStrokes.flatMap { it.points }
            val cx = (allPts.minOf { it.x } + allPts.maxOf { it.x }) / 2f
            val cy = (allPts.minOf { it.y } + allPts.maxOf { it.y }) / 2f
            strokeRotationPivot = cx to cy
            strokeRotationStartAngleDeg = (Math.toDegrees(kotlin.math.atan2((worldY - cy).toDouble(), (worldX - cx).toDouble())) + 90.0).toFloat()
            return 0f
        }

        val (cx, cy) = strokeRotationPivot!!
        val startAngle = strokeRotationStartAngleDeg ?: 0f
        val curAngle = (Math.toDegrees(kotlin.math.atan2((worldY - cy).toDouble(), (worldX - cx).toDouble())) + 90.0).toFloat()
        val rawDelta = ((curAngle - startAngle) % 360f + 360f) % 360f
        val snappedDelta = com.example.ink.StickyNoteGeometry.snapAngle(rawDelta, 3.5f)
        val rad = snappedDelta * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)

        val updated = state.board.strokes.map { s ->
            val basePoints = strokeRotationSnapshots[s.id]
            if (basePoints == null) {
                s
            } else {
                s.copy(points = basePoints.map { p ->
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
        return snappedDelta
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
        flushPendingStickyNoteSync(persist = false)
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val noteIds = if (state.selectedNoteIds.isNotEmpty()) state.selectedNoteIds else state.selectedNoteId?.let { setOf(it) } ?: emptySet()
        val strokesToDup = state.board.strokes.filter { strokeIds.contains(it.id) }
        val shapesToDup = state.board.shapes.filter { shapeIds.contains(it.id) }
        val notesToDup = stickyNotesStore.currentNotes().filter { noteIds.contains(it.id) }
        if (strokesToDup.isEmpty() && shapesToDup.isEmpty() && notesToDup.isEmpty()) return
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
        val newNotes = notesToDup.map { note ->
            note.copy(
                id = UUID.randomUUID().toString(),
                x = note.x + 30f,
                y = note.y + 30f,
                groupId = null,
                parentId = null
            )
        }
        for (newNote in newNotes) {
            stickyNotesStore.addNote(newNote)
        }
        val newStrokeIds = newStrokes.map { it.id }.toSet()
        val newShapeIds = newShapes.map { it.id }.toSet()
        val newNoteIds = newNotes.map { it.id }.toSet()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = b.strokes + newStrokes,
                    shapes = b.shapes + newShapes,
                    stickyNotes = stickyNotesStore.currentNotes()
                )
            }.copy(
                selectedStrokeId = newStrokeIds.firstOrNull(),
                selectedStrokeIds = newStrokeIds,
                selectedShapeId = newShapeIds.firstOrNull(),
                selectedShapeIds = newShapeIds,
                selectedNoteId = newNoteIds.firstOrNull(),
                selectedNoteIds = newNoteIds
            )
        }
        if (newNotes.isNotEmpty()) {
            commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
        }
    }

    fun openBoard(boardId: String) {
        flushPendingStickyNoteSync(persist = true)
        val target = _uiState.value.allBoards.find { it.id == boardId }
            ?: store.currentState.boards.find { it.id == boardId }
            ?: return
        undoStack.clear()
        redoStack.clear()
        isStickyNoteTransformInProgress = false
        stickyNotesStore.replaceAll(target.stickyNotes)
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
                canRedo = false
            )
        }
        resetZoom()
    }

    fun openBoardAndFocusNote(boardId: String, noteId: String) {
        flushPendingStickyNoteSync(persist = true)
        val targetBoard = _uiState.value.allBoards.find { it.id == boardId }
            ?: store.currentState.boards.find { it.id == boardId }
            ?: return
        val targetNote = targetBoard.stickyNotes.find { it.id == noteId }
        undoStack.clear()
        redoStack.clear()
        isStickyNoteTransformInProgress = false
        stickyNotesStore.replaceAll(targetBoard.stickyNotes)
        val currentCamera = _cameraState.value
        val focusScale = 1.0f
        val nextPanX: Float
        val nextPanY: Float
        if (targetNote != null) {
            val centerX = targetNote.x + targetNote.width * 0.5f
            val centerY = targetNote.y + targetNote.height * 0.5f
            nextPanX = currentCamera.viewportWidthPx * 0.5f - centerX * focusScale
            nextPanY = currentCamera.viewportHeightPx * 0.5f - centerY * focusScale
        } else {
            nextPanX = 0f
            nextPanY = 0f
        }
        _uiState.update { state ->
            state.copy(
                board = targetBoard,
                isViewingBoardsHome = false,
                isZenMode = false,
                currentTool = ToolType.SELECT_LASSO,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedNoteId = targetNote?.id,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedNoteIds = if (targetNote != null) setOf(targetNote.id) else emptySet(),
                pendingShapeType = null,
                pendingStickyNoteColor = null,
                canUndo = false,
                canRedo = false
            )
        }
        _cameraState.update { it.copy(scale = focusScale, panX = nextPanX, panY = nextPanY) }
    }

    fun closeBoardToHome() {
        commitWorkspaceSync()
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
        store.flushToDisk()
    }

    fun createNewBoard(
        title: String,
        gridType: CanvasGridType = CanvasGridType.GRID,
        colorTheme: CanvasColorTheme = CanvasColorTheme.WHITE,
        openImmediately: Boolean = true
    ) {
        if (openImmediately) {
            flushPendingStickyNoteSync(persist = true)
        }
        val newBoard = WhiteboardBoard(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Nueva Pizarra" },
            gridType = gridType,
            colorTheme = colorTheme
        )
        if (openImmediately) {
            undoStack.clear()
            redoStack.clear()
            isStickyNoteTransformInProgress = false
            stickyNotesStore.replaceAll(newBoard.stickyNotes)
        }
        isCommittingToStore = true
        val db = try {
            store.createBoard(newBoard, prepend = true)
        } finally {
            isCommittingToStore = false
        }
        val canonicalBoard = db.boards.find { it.id == newBoard.id } ?: newBoard
        if (openImmediately) {
            stickyNotesStore.replaceAll(canonicalBoard.stickyNotes)
        }
        _uiState.update {
            it.copy(
                board = if (openImmediately) canonicalBoard else it.board,
                allBoards = db.boards,
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
            )
        }
        if (openImmediately) resetZoom()
    }

    fun switchBoard(boardId: String) {
        openBoard(boardId)
    }

    fun duplicateCurrentBoard(boardId: String) {
        val original = _uiState.value.allBoards.find { it.id == boardId } ?: return
        val dupId = UUID.randomUUID().toString()
        val idMap = mutableMapOf<String, String>()
        fun remap(oldId: String?): String? {
            if (oldId == null) return null
            return idMap.getOrPut(oldId) { UUID.randomUUID().toString() }
        }
        val remappedShapes = original.shapes.map { sh ->
            sh.copy(
                id = remap(sh.id)!!,
                groupId = remap(sh.groupId),
                parentId = remap(sh.parentId)
            )
        }
        val remappedNotes = original.stickyNotes.map { n ->
            n.copy(
                id = remap(n.id)!!,
                groupId = remap(n.groupId),
                parentId = remap(n.parentId)
            )
        }
        val remappedTexts = original.textItems.map { t ->
            t.copy(
                id = remap(t.id)!!,
                groupId = remap(t.groupId),
                parentId = remap(t.parentId)
            )
        }
        val remappedStrokes = original.strokes.map { it.copy(id = UUID.randomUUID().toString()) }
        val remappedImages = original.imageCards.map { it.copy(id = UUID.randomUUID().toString()) }
        val remappedStickers = original.stickers.map { it.copy(id = UUID.randomUUID().toString()) }

        val dup = original.copy(
            id = dupId,
            title = "${original.title} (Copia)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            strokes = remappedStrokes,
            shapes = remappedShapes,
            stickyNotes = remappedNotes,
            textItems = remappedTexts,
            imageCards = remappedImages,
            stickers = remappedStickers,
            snapshots = original.snapshots.map { it.copy(boardId = dupId) }
        )
        isCommittingToStore = true
        val db = try {
            store.createBoard(dup, prepend = true)
        } finally {
            isCommittingToStore = false
        }
        applyCanonicalBoardsFromDb(db.boards)
    }

    fun renameBoardById(boardId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        isCommittingToStore = true
        val db = try {
            store.renameBoard(boardId, clean)
        } finally {
            isCommittingToStore = false
        }
        applyCanonicalBoardsFromDb(db.boards)
    }

    fun deleteBoard(boardId: String) {
        val state = _uiState.value
        val remaining = state.allBoards.filter { it.id != boardId }
        val fallback = if (remaining.isEmpty()) WhiteboardBoard(title = "Nueva Pizarra") else null
        isCommittingToStore = true
        val db = try {
            store.deleteBoard(boardId, fallback)
        } finally {
            isCommittingToStore = false
        }
        val nextBoards = db.boards
        val nextCurrent = when {
            state.board.id == boardId -> nextBoards.first()
            else -> nextBoards.find { it.id == state.board.id } ?: nextBoards.first()
        }
        if (state.board.id == boardId) {
            stickyNotesStore.replaceAll(nextCurrent.stickyNotes)
        }
        _uiState.update {
            it.copy(
                board = nextCurrent,
                allBoards = nextBoards,
                isViewingBoardsHome = if (remaining.isEmpty()) true else it.isViewingBoardsHome
            )
        }
    }

    fun resetZoom() {
        _cameraState.value = _cameraState.value.copy(scale = 1.0f, panX = 0f, panY = 0f)
    }

    fun setGridType(grid: CanvasGridType) {
        val log = AuditLogEntry(
            actionName = "GRID_CHANGED",
            details = "Cuadrícula cambiada a ${grid.title}",
            category = "CANVAS"
        )
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(gridType = grid, auditLogs = (b.auditLogs + log).takeLast(100))
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun setColorTheme(theme: CanvasColorTheme) {
        val log = AuditLogEntry(
            actionName = "THEME_CHANGED",
            details = "Color de lienzo cambiado a ${theme.title}",
            category = "CANVAS"
        )
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(colorTheme = theme, auditLogs = (b.auditLogs + log).takeLast(100))
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun renameBoard(newTitle: String) {
        renameBoardById(_uiState.value.board.id, newTitle)
    }

    fun clearBoard() {
        saveUndoSnapshot()
        stickyNotesStore.replaceAll(emptyList())
        val log = AuditLogEntry(
            actionName = "BOARD_CLEARED",
            details = "Lienzo limpiado (elementos eliminados)",
            category = "BOARD"
        )
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = emptyList(),
                    stickyNotes = emptyList(),
                    shapes = emptyList(),
                    textItems = emptyList(),
                    auditLogs = (b.auditLogs + log).takeLast(100)
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
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = true, persist = true)
    }

    fun logAudit(actionName: String, details: String, category: String = "CANVAS") {
        val entry = AuditLogEntry(
            actionName = actionName,
            details = details,
            category = category
        )
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val updatedLogs = (b.auditLogs + entry).takeLast(100)
                b.copy(auditLogs = updatedLogs)
            }
        }
    }

    fun clearAuditLogs() {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(auditLogs = emptyList())
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun insertShapeAtCenter(
        shapeType: ShapeType,
        isFilled: Boolean = false,
        strokeColor: String = "#2563EB",
        fillColor: String = "#DBEAFE"
    ) {
        val camera = _cameraState.value
        val worldCenterX = camera.screenToWorldX(camera.viewportWidthPx * 0.5f)
        val worldCenterY = camera.screenToWorldY(camera.viewportHeightPx * 0.5f)

        val width = if (shapeType.isLineLike) 240f else 220f
        val height = if (shapeType == ShapeType.CIRCLE) 220f else if (shapeType.isLineLike) 80f else 150f

        val x = worldCenterX - width / 2f
        val y = worldCenterY - height / 2f

        val vertices = if (shapeType.isLineLike) {
            listOf(StrokePoint(x, y + height / 2f), StrokePoint(x + width, y + height / 2f))
        } else {
            emptyList()
        }

        val capStyle = when (shapeType) {
            ShapeType.ARROW -> LineCapStyle.END_ARROW
            ShapeType.DOUBLE_ARROW -> LineCapStyle.BOTH_ARROWS
            else -> LineCapStyle.NONE
        }

        saveUndoSnapshot()
        val shape = ShapeItem(
            shapeType = shapeType,
            x = x,
            y = y,
            width = width,
            height = height,
            strokeColorHex = strokeColor,
            fillColorHex = fillColor,
            strokeWidth = _uiState.value.penWidth.coerceIn(2f, 16f),
            isFilled = isFilled && !shapeType.isLineLike,
            capStyle = capStyle,
            vertices = vertices,
            brushType = _uiState.value.pendingBrushType
        )
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val log = AuditLogEntry(
                    actionName = "SHAPE_INSERTED",
                    details = "Forma ${shapeType.displayName} insertada en centro",
                    category = "SHAPES"
                )
                b.copy(shapes = b.shapes + shape, auditLogs = (b.auditLogs + log).takeLast(100))
            }.copy(
                selectedShapeId = shape.id,
                pendingShapeType = null
            )
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun insertTextAtCenter(
        initialText: String = "Texto",
        colorHex: String = "#0F172A",
        fontSize: Float = 22f
    ) {
        val camera = _cameraState.value
        val worldCenterX = camera.screenToWorldX(camera.viewportWidthPx * 0.5f)
        val worldCenterY = camera.screenToWorldY(camera.viewportHeightPx * 0.5f)

        val item = TextItem(
            text = initialText,
            x = worldCenterX - 95f,
            y = worldCenterY - 24f,
            fontSize = fontSize,
            colorHex = colorHex,
            width = 190f
        )
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val log = AuditLogEntry(
                    actionName = "TEXT_INSERTED",
                    details = "Elemento de texto flotante insertado",
                    category = "TEXT"
                )
                b.copy(textItems = b.textItems + item, auditLogs = (b.auditLogs + log).takeLast(100))
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun deleteTextItem(id: String) {
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val log = AuditLogEntry(
                    actionName = "TEXT_DELETED",
                    details = "Elemento de texto eliminado",
                    category = "TEXT"
                )
                b.copy(
                    textItems = b.textItems.filter { it.id != id },
                    auditLogs = (b.auditLogs + log).takeLast(100)
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun insertStickyNoteAtCenter(
        colorHex: String = "#FFF275"
    ) {
        val camera = _cameraState.value
        val worldCenterX = camera.screenToWorldX(camera.viewportWidthPx * 0.5f)
        val worldCenterY = camera.screenToWorldY(camera.viewportHeightPx * 0.5f)

        saveUndoSnapshot()
        val note = StickyNote(
            title = "",
            text = "",
            x = worldCenterX - 110f,
            y = worldCenterY - 100f,
            colorHex = colorHex
        )
        stickyNotesStore.addNote(note)
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val log = AuditLogEntry(
                    actionName = "NOTE_CREATED",
                    details = "Nota adhesiva creada en centro ($colorHex)",
                    category = "NOTES"
                )
                b.copy(stickyNotes = stickyNotesStore.currentNotes(), auditLogs = (b.auditLogs + log).takeLast(100))
            }.copy(
                selectedNoteId = note.id,
                selectedNoteIds = setOf(note.id),
                pendingStickyNoteColor = null
            )
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun createSnapshot(versionName: String, description: String) {
        flushPendingStickyNoteSync(persist = false)
        syncStickyNotesIntoBoardState()
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
        val log = AuditLogEntry(
            actionName = "SNAPSHOT_SAVED",
            details = "Snapshot $cleanName creado (${board.strokes.size} trazos, ${board.shapes.size} formas, ${board.stickyNotes.size} notas)",
            category = "VERSION"
        )
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    snapshots = b.snapshots + snapshot,
                    auditLogs = (b.auditLogs + log).takeLast(100)
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    fun restoreSnapshot(snapshot: BoardVersionSnapshot): Boolean {
        val current = _uiState.value.board
        val restored = BoardSnapshotSerializer.deserializeIntoBoard(snapshot.serializedData, current)
            ?: return false
        saveUndoSnapshot()
        stickyNotesStore.replaceAll(restored.stickyNotes)
        val log = AuditLogEntry(
            actionName = "SNAPSHOT_RESTORED",
            details = "Restaurada versión ${snapshot.versionName}",
            category = "VERSION"
        )
        _uiState.update {
            it.withUpdatedBoard { b ->
                val updatedLogs = (restored.auditLogs + log).takeLast(100)
                restored.copy(
                    snapshots = b.snapshots,
                    auditLogs = updatedLogs
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
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = true, persist = true)
        return true
    }

    fun deleteSnapshot(versionId: String) {
        val log = AuditLogEntry(
            actionName = "SNAPSHOT_DELETED",
            details = "Snapshot eliminado",
            category = "VERSION"
        )
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    snapshots = b.snapshots.filter { s -> s.versionId != versionId },
                    auditLogs = (b.auditLogs + log).takeLast(100)
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = false, persist = true)
    }

    private fun saveUndoSnapshot() {
        flushPendingStickyNoteSync(persist = false)
        syncStickyNotesIntoBoardState()
        val b = _uiState.value.board
        if (undoStack.size >= 30) {
            undoStack.removeAt(0)
        }
        undoStack.add(BoardSnapshot(b.strokes, b.stickyNotes, b.shapes, b.textItems))
        redoStack.clear()
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        flushPendingStickyNoteSync(persist = false)
        syncStickyNotesIntoBoardState()
        val currentBoard = _uiState.value.board
        redoStack.add(BoardSnapshot(currentBoard.strokes, currentBoard.stickyNotes, currentBoard.shapes, currentBoard.textItems))
        val previous = undoStack.removeAt(undoStack.lastIndex)
        stickyNotesStore.replaceAll(previous.notes)

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
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = true, persist = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        flushPendingStickyNoteSync(persist = false)
        syncStickyNotesIntoBoardState()
        val currentBoard = _uiState.value.board
        undoStack.add(BoardSnapshot(currentBoard.strokes, currentBoard.stickyNotes, currentBoard.shapes, currentBoard.textItems))
        val next = redoStack.removeAt(redoStack.lastIndex)
        stickyNotesStore.replaceAll(next.notes)

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
        commitBoardMutationToStore(_uiState.value.board, softDeleteMissingNotes = true, persist = true)
    }

    private fun persistActivePenSlotUpdate(
        transform: (PenToolSlot) -> PenToolSlot
    ) {
        val state = _uiState.value
        val index = state.activePenSlotIndex.coerceIn(0, 2)
        val current = state.penToolSlots.getOrNull(index) ?: PenToolSlot()
        if (!current.isConfigured) return
        val updated = state.penToolSlots.toMutableList().apply {
            while (size < 3) add(PenToolSlot())
            this[index] = transform(current)
        }.take(3)
        _uiState.update { it.copy(penToolSlots = updated) }
        store.updatePenToolSlots(updated)
    }

    fun configurePenToolSlot(index: Int, tool: ToolType) {
        if (index !in 0..2 || tool !in setOf(ToolType.PEN, ToolType.FINE_MARKER, ToolType.HIGHLIGHTER)) return
        val state = _uiState.value
        val previous = state.penToolSlots.getOrNull(index) ?: PenToolSlot()
        val slots = state.penToolSlots.toMutableList().apply {
            while (size < 3) add(PenToolSlot())
            this[index] = PenToolSlot(
                tool = tool,
                colorHex = if (previous.isConfigured) previous.colorHex else state.penColorHex,
                width = if (previous.isConfigured) previous.width else state.penWidth.coerceIn(1f, 48f),
                opacity = if (tool == ToolType.HIGHLIGHTER && !previous.isConfigured) 0.30f else {
                    if (previous.isConfigured) previous.opacity else state.penOpacity.coerceIn(0.05f, 1f)
                }
            )
        }.take(3)
        store.updatePenToolSlots(slots)
        _uiState.update {
            it.copy(
                penToolSlots = slots,
                activePenSlotIndex = index,
                currentTool = tool,
                penColorHex = slots[index].colorHex,
                penWidth = slots[index].width,
                penOpacity = slots[index].opacity,
                isRainbowPen = false,
                pendingBrushType = brushTypeForTool(tool)
            )
        }
    }

    fun clearPenToolSlot(index: Int) {
        if (index !in 0..2) return
        val state = _uiState.value
        val slots = state.penToolSlots.toMutableList().apply {
            while (size < 3) add(PenToolSlot())
            this[index] = PenToolSlot()
        }.take(3)
        store.updatePenToolSlots(slots)
        _uiState.update {
            if (it.activePenSlotIndex == index) {
                it.copy(
                    penToolSlots = slots,
                    currentTool = ToolType.HAND,
                    isRainbowPen = false
                )
            } else {
                it.copy(penToolSlots = slots)
            }
        }
    }

    fun selectPenToolSlot(index: Int) {
        if (index !in 0..2) return
        val slot = _uiState.value.penToolSlots.getOrNull(index) ?: return
        val tool = slot.tool ?: return
        _uiState.update {
            it.copy(
                activePenSlotIndex = index,
                currentTool = tool,
                penColorHex = slot.colorHex,
                penWidth = slot.width,
                penOpacity = slot.opacity,
                isRainbowPen = false,
                pendingBrushType = brushTypeForTool(tool)
            )
        }
    }

    fun setPenColor(hex: String, isRainbow: Boolean = false) {
        _uiState.update { it.copy(penColorHex = hex, isRainbowPen = isRainbow) }
        persistActivePenSlotUpdate { it.copy(colorHex = hex) }
    }

    fun setPenWidth(width: Float) {
        val safeWidth = width.coerceIn(1f, 48f)
        _uiState.update { it.copy(penWidth = safeWidth) }
        persistActivePenSlotUpdate { it.copy(width = safeWidth) }
    }

    fun setPenOpacity(opacity: Float) {
        val safeOpacity = opacity.coerceIn(0.05f, 1f)
        _uiState.update { it.copy(penOpacity = safeOpacity) }
        persistActivePenSlotUpdate { it.copy(opacity = safeOpacity) }
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
        applyCanonicalBoardsFromDb(boards)
    }

}
