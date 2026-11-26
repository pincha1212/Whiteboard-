package com.example.viewmodel

import android.content.ComponentCallbacks2
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.example.data.LocalFirstWorkspaceStore
import com.example.data.WorkspaceDbState
import com.example.ink.BoxResizeHandle
import com.example.ink.BoxTransformGeometry
import com.example.ink.PixelEraserGeometry
import com.example.ink.ShapeRecognizer
import com.example.ink.StrokeStabilizer
import com.example.ink.ViewportTransform
import com.example.ink.WorldBoxBounds
import com.example.ink.engine.FloodFillResult
import com.example.ink.engine.MemoryBudgetProfile
import com.example.ink.engine.MutableRasterTileStore
import com.example.ink.engine.PaintTileRasterCache
import com.example.ink.engine.TileRasterUndoPatch
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.model.BrushSlot
import com.example.model.BrushStudioSettings
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.DrawingLayer
import com.example.model.EllipseGuideState
import com.example.model.FrenchCurveGuideState
import com.example.model.GuideType
import com.example.model.GuidesOverlayState
import com.example.model.LayerBlendMode
import com.example.model.LineCapStyle
import com.example.model.PerspectiveGuideState
import com.example.model.PerspectiveMode
import com.example.model.RulerState
import com.example.model.ShapeItem
import com.example.model.ShapeRecognitionProposal
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.SymmetryMode
import com.example.model.TextItem
import com.example.model.ToolType
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import com.example.model.isLineLike
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class WhiteboardUiState(
    val board: WhiteboardBoard,
    val allBoards: List<WhiteboardBoard> = emptyList(),
    val isViewingBoardsHome: Boolean = true,
    val currentTool: ToolType = ToolType.PENCIL,
    val penColorHex: String = "#1E1E1E",
    val previousPenColorHex: String = "#1E1E1E",
    val penWidth: Float = 4f,
    val penOpacity: Float = StrokeBrushType.PENCIL.defaultOpacity,
    val penStyle: StrokeStyle = StrokeStyle.SOLID,
    val penCapStyle: LineCapStyle = LineCapStyle.NONE,
    val isRainbowPen: Boolean = false,
    val autoShapeRecognition: Boolean = false,
    val isRulerActive: Boolean = false,
    val rulerState: RulerState = RulerState(),
    val isSnapToGrid: Boolean = false,
    val isZenMode: Boolean = false,
    val symmetryMode: SymmetryMode = SymmetryMode.NONE,
    val symmetryCenterX: Float = 540f,
    val symmetryCenterY: Float = 960f,
    val isLayersPanelOpen: Boolean = false,
    val isColorStudioOpen: Boolean = false,
    val isBrushPopoverOpen: Boolean = false,
    val activeStroke: List<StrokePoint>? = null,
    val selectedStrokeId: String? = null,
    val selectedShapeId: String? = null,
    val selectedTextId: String? = null,
    val selectedStrokeIds: Set<String> = emptySet(),
    val selectedShapeIds: Set<String> = emptySet(),
    val selectedTextIds: Set<String> = emptySet(),
    val shapeRecognitionProposal: ShapeRecognitionProposal? = null,
    val handDrawnFactor: Float = 0.24f,
    val isPaintPixelMode: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val activeBrushSlotIndex: Int = 0,
    val brushStudioSlotIndex: Int? = null,
    val guidesState: GuidesOverlayState = GuidesOverlayState(),
    val predictionLevel: Int = 2,
    val floodFillTolerance: Int = 36
)

class WhiteboardViewModel(
    private val store: LocalFirstWorkspaceStore = LocalFirstWorkspaceStore()
) : ViewModel() {

    val memoryBudget: MemoryBudgetProfile
        get() = store.memoryBudget

    val mutableRasterTileStore: MutableRasterTileStore
        get() = store.rasterTileStore

    /**
     * Single shared world-space tile cache used by both UI rendering and raster tools
     * (Smudge & Flood Fill), eliminating duplicate tile bitmap memory.
     */
    val sharedPaintTileCache = PaintTileRasterCache(
        mutableRasterStore = store.rasterTileStore,
        budget = store.memoryBudget
    )

    private val studioEngine = StudioEngineController(
        rasterStore = store.rasterTileStore,
        paintTileCache = sharedPaintTileCache,
        memoryBudget = store.memoryBudget
    )
    private val historyStore = CanvasHistoryStore(store.memoryBudget)

    private var lastNonEraserTool: ToolType = ToolType.PENCIL
    private var pendingRasterUndoSnapshotPushed = false
    private var pendingRasterPatch: TileRasterUndoPatch? = null
    private var isCommittingToStore = false

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<WhiteboardUiState> = _uiState.asStateFlow()

    val brushSlots: SnapshotStateList<BrushSlot> = mutableStateListOf(
        BrushSlot(
            id = "dock_slot_0",
            brushType = StrokeBrushType.PENCIL,
            colorHex = "#1E1E1E",
            strokeWidth = 4f,
            opacity = StrokeBrushType.PENCIL.defaultOpacity
        ),
        BrushSlot(
            id = "dock_slot_1",
            brushType = StrokeBrushType.FOUNTAIN_PEN,
            colorHex = "#2563EB",
            strokeWidth = 6f,
            opacity = StrokeBrushType.FOUNTAIN_PEN.defaultOpacity
        ),
        BrushSlot(
            id = "dock_slot_2",
            brushType = StrokeBrushType.PAINT_BRUSH,
            colorHex = "#DC2626",
            strokeWidth = 14f,
            opacity = StrokeBrushType.PAINT_BRUSH.defaultOpacity
        )
    )

    // Camera updates stay isolated from content state so pan/zoom never invalidates toolbars or panels.
    private val _cameraState = MutableStateFlow(ViewportTransform())
    val cameraState: StateFlow<ViewportTransform> = _cameraState.asStateFlow()

    private val storeListener: (WorkspaceDbState) -> Unit = { db ->
        if (!isCommittingToStore && db.boards.isNotEmpty()) {
            syncWorkspaceBoards(db.boards)
        }
    }

    init {
        UnifiedBrushPipeline.configureMemoryBudget(store.memoryBudget)
        val currentBoards = store.currentState.boards
        if (currentBoards.isEmpty()) {
            store.ensureBoardRegistered(_uiState.value.board)
        } else {
            _uiState.update { state ->
                val cur = currentBoards.find { it.id == state.board.id } ?: currentBoards.first()
                state.copy(board = cur, allBoards = currentBoards)
            }
        }
        store.addListener(storeListener)
    }

    override fun onCleared() {
        store.flushToDisk()
        store.removeListener(storeListener)
        sharedPaintTileCache.clearAndReleasePool()
        super.onCleared()
    }

    @Suppress("DEPRECATION")
    fun handleMemoryTrim(level: Int) {
        val severe = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val moderate = severe ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (!moderate) return

        studioEngine.onTrimMemory(severe = severe)
        UnifiedBrushPipeline.trimMemory(severe = severe)
        historyStore.trimEmergency(if (severe) 0.25f else 0.5f)
        store.flushAndTrimInactiveBoards(_uiState.value.board.id)
        _uiState.update {
            it.copy(canUndo = historyStore.canUndo, canRedo = historyStore.canRedo)
        }
    }

    private fun commitBoardMutationToStore(
        board: WhiteboardBoard,
        persist: Boolean = true
    ) {
        isCommittingToStore = true
        try {
            val db = store.syncBoardFromWhiteboard(
                board = board,
                persist = persist
            )
            applyCanonicalBoardsFromDb(db.boards)
        } finally {
            isCommittingToStore = false
        }
    }

    private fun applyCanonicalBoardsFromDb(boards: List<WhiteboardBoard>) {
        if (boards.isEmpty()) return
        _uiState.update { state ->
            val currentBoard = boards.find { it.id == state.board.id }
                ?: if (state.isViewingBoardsHome) boards.first() else state.board
            if (state.allBoards == boards && state.board == currentBoard) {
                state
            } else {
                state.copy(board = currentBoard, allBoards = boards)
            }
        }
    }

    fun commitWorkspaceSync() {
        if (store.currentState.boards.isNotEmpty()) {
            store.flushToDisk()
        }
    }

    private fun createInitialState(): WhiteboardUiState {
        val blankBoardId = UUID.randomUUID().toString()
        val blankBoard = WhiteboardBoard(
            id = blankBoardId,
            title = "Pizarra Principal"
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

    private fun ToolType.isBrushSlotTool(): Boolean = when (this) {
        ToolType.PENCIL,
        ToolType.PEN,
        ToolType.TECHNICAL_INK,
        ToolType.FINE_MARKER,
        ToolType.CHISEL_MARKER,
        ToolType.PAINT_BRUSH,
        ToolType.WATERCOLOR,
        ToolType.AIRBRUSH,
        ToolType.HIGHLIGHTER -> true
        else -> false
    }

    fun setTool(tool: ToolType) {
        if (tool.isBrushSlotTool() || tool == ToolType.SMUDGE || tool == ToolType.FLOOD_FILL) {
            lastNonEraserTool = tool
        }
        _uiState.update { state ->
            val clearedPopover = state.copy(isBrushPopoverOpen = false)
            if (tool == ToolType.HAND) {
                clearedPopover.copy(
                    currentTool = tool,
                    selectedStrokeId = null,
                    selectedShapeId = null,
                    selectedTextId = null,
                    selectedStrokeIds = emptySet(),
                    selectedShapeIds = emptySet(),
                    selectedTextIds = emptySet()
                )
            } else if (tool.isBrushSlotTool()) {
                val activeSlot = brushSlots.getOrNull(clearedPopover.activeBrushSlotIndex)
                if (activeSlot != null && activeSlot.toolType == tool) {
                    clearedPopover.copy(
                        currentTool = activeSlot.toolType,
                        penColorHex = activeSlot.colorHex,
                        penWidth = activeSlot.strokeWidth,
                        penOpacity = activeSlot.opacity,
                        isRainbowPen = activeSlot.isRainbow
                    )
                } else {
                    val matchingIndex = brushSlots.indexOfFirst { it.toolType == tool }
                    if (matchingIndex >= 0) {
                        val resolvedSlot = brushSlots[matchingIndex]
                        clearedPopover.copy(
                            activeBrushSlotIndex = matchingIndex,
                            currentTool = resolvedSlot.toolType,
                            penColorHex = resolvedSlot.colorHex,
                            penWidth = resolvedSlot.strokeWidth,
                            penOpacity = resolvedSlot.opacity,
                            isRainbowPen = resolvedSlot.isRainbow
                        )
                    } else {
                        clearedPopover.copy(
                            currentTool = tool,
                            penOpacity = brushTypeForTool(tool).defaultOpacity
                        )
                    }
                }
            } else {
                clearedPopover.copy(currentTool = tool)
            }
        }
    }

    fun selectBrushSlot(slotIndex: Int) {
        if (slotIndex !in brushSlots.indices) return
        val slot = brushSlots[slotIndex]
        lastNonEraserTool = slot.toolType
        _uiState.update { state ->
            val wasAlreadyActiveBrush = state.activeBrushSlotIndex == slotIndex &&
                state.currentTool.isBrushSlotTool()
            state.copy(
                activeBrushSlotIndex = slotIndex,
                currentTool = slot.toolType,
                penColorHex = slot.colorHex,
                penWidth = slot.strokeWidth,
                penOpacity = slot.opacity,
                isRainbowPen = slot.isRainbow,
                isBrushPopoverOpen = if (wasAlreadyActiveBrush) !state.isBrushPopoverOpen else false
            )
        }
    }

    fun dismissBrushPopover() {
        _uiState.update { if (it.isBrushPopoverOpen) it.copy(isBrushPopoverOpen = false) else it }
    }

    fun updateBrushSlot(
        slotIndex: Int,
        brushType: StrokeBrushType? = null,
        colorHex: String? = null,
        strokeWidth: Float? = null,
        isRainbow: Boolean? = null,
        opacity: Float? = null
    ) {
        if (slotIndex !in brushSlots.indices) return
        val current = brushSlots[slotIndex]
        val resolvedBrush = brushType ?: current.brushType
        val brushChanged = brushType != null && brushType != current.brushType
        val resolvedOpacity = opacity?.coerceIn(0.05f, 1.0f)
            ?: if (brushChanged) resolvedBrush.defaultOpacity else current.opacity
        val resolvedSettings = if (brushChanged) {
            BrushStudioSettings.defaultFor(resolvedBrush)
        } else {
            current.studioSettings
        }
        val updated = current.copy(
            brushType = resolvedBrush,
            colorHex = colorHex ?: current.colorHex,
            strokeWidth = strokeWidth?.coerceIn(1f, 160f) ?: current.strokeWidth,
            opacity = resolvedOpacity,
            isRainbow = isRainbow ?: current.isRainbow,
            studioSettings = resolvedSettings
        )
        brushSlots[slotIndex] = updated
        if (slotIndex == _uiState.value.activeBrushSlotIndex) {
            _uiState.update { state ->
                val nextPrevColor = if (colorHex != null && !colorHex.equals(state.penColorHex, ignoreCase = true)) {
                    state.penColorHex
                } else {
                    state.previousPenColorHex
                }
                state.copy(
                    currentTool = if (state.currentTool.isBrushSlotTool()) updated.toolType else state.currentTool,
                    penColorHex = updated.colorHex,
                    previousPenColorHex = nextPrevColor,
                    penWidth = updated.strokeWidth,
                    penOpacity = updated.opacity,
                    isRainbowPen = updated.isRainbow
                )
            }
        }
    }

    fun openBrushStudio(slotIndex: Int) {
        if (slotIndex !in brushSlots.indices) return
        _uiState.update { it.copy(brushStudioSlotIndex = slotIndex, isBrushPopoverOpen = false) }
    }

    fun dismissBrushStudio() {
        _uiState.update { it.copy(brushStudioSlotIndex = null) }
    }

    fun updateBrushSlotStudioSettings(slotIndex: Int, settings: BrushStudioSettings) {
        if (slotIndex !in brushSlots.indices) return
        brushSlots[slotIndex] = brushSlots[slotIndex].copy(studioSettings = settings)
    }

    fun resetBrushSlotStudioSettings(slotIndex: Int) {
        if (slotIndex !in brushSlots.indices) return
        val current = brushSlots[slotIndex]
        brushSlots[slotIndex] = current.copy(
            studioSettings = BrushStudioSettings.defaultFor(current.brushType),
            opacity = current.brushType.defaultOpacity
        )
        if (slotIndex == _uiState.value.activeBrushSlotIndex) {
            _uiState.update { it.copy(penOpacity = current.brushType.defaultOpacity) }
        }
    }

    fun updateActiveBrushQuickSettings(newWidth: Float, newOpacity: Float) {
        val clampedWidth = newWidth.coerceIn(1f, 160f)
        val clampedOpacity = newOpacity.coerceIn(0.05f, 1.0f)
        val activeIdx = _uiState.value.activeBrushSlotIndex
        if (activeIdx in brushSlots.indices) {
            brushSlots[activeIdx] = brushSlots[activeIdx].copy(
                strokeWidth = clampedWidth,
                opacity = clampedOpacity
            )
        }
        _uiState.update {
            it.copy(penWidth = clampedWidth, penOpacity = clampedOpacity)
        }
    }

    fun quickToggleBrushAndEraser() {
        val curTool = _uiState.value.currentTool
        val isEraser = curTool == ToolType.PIXEL_ERASER ||
            curTool == ToolType.SOFT_ERASER ||
            curTool == ToolType.ERASER
        if (isEraser) {
            setTool(lastNonEraserTool)
        } else {
            setTool(ToolType.SOFT_ERASER)
        }
    }

    fun setPenOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.05f, 1.0f)
        val activeIdx = _uiState.value.activeBrushSlotIndex
        if (activeIdx in brushSlots.indices) {
            brushSlots[activeIdx] = brushSlots[activeIdx].copy(opacity = clamped)
        }
        _uiState.update { it.copy(penOpacity = clamped) }
    }

    fun toggleLayersPanel() {
        _uiState.update {
            it.copy(
                isLayersPanelOpen = !it.isLayersPanelOpen,
                isColorStudioOpen = false,
                isBrushPopoverOpen = false
            )
        }
    }

    fun openColorStudio() {
        _uiState.update {
            it.copy(
                isColorStudioOpen = true,
                isLayersPanelOpen = false,
                isBrushPopoverOpen = false
            )
        }
    }

    fun closeColorStudio() {
        _uiState.update { it.copy(isColorStudioOpen = false) }
    }

    fun setSymmetryMode(mode: SymmetryMode) {
        val cam = _cameraState.value
        val worldCenterX = cam.screenToWorldX(cam.viewportWidthPx * 0.5f)
        val worldCenterY = cam.screenToWorldY(cam.viewportHeightPx * 0.5f)
        _uiState.update { state ->
            if (state.symmetryMode == SymmetryMode.NONE && mode != SymmetryMode.NONE) {
                state.copy(
                    symmetryMode = mode,
                    symmetryCenterX = worldCenterX,
                    symmetryCenterY = worldCenterY
                )
            } else {
                state.copy(symmetryMode = mode)
            }
        }
    }

    fun cycleSymmetryMode() {
        val allModes = SymmetryMode.entries
        val cur = _uiState.value.symmetryMode
        val next = allModes[(allModes.indexOf(cur) + 1) % allModes.size]
        setSymmetryMode(next)
    }

    fun moveSymmetryCenter(deltaWorldX: Float, deltaWorldY: Float) {
        _uiState.update {
            it.copy(
                symmetryCenterX = it.symmetryCenterX + deltaWorldX,
                symmetryCenterY = it.symmetryCenterY + deltaWorldY
            )
        }
    }

    fun mirrorPointsForSymmetry(
        points: List<StrokePoint>,
        mode: SymmetryMode,
        cx: Float,
        cy: Float
    ): List<List<StrokePoint>> {
        if (points.isEmpty() || mode == SymmetryMode.NONE) return emptyList()
        return when (mode) {
            SymmetryMode.NONE -> emptyList()
            SymmetryMode.VERTICAL -> listOf(
                points.map { p -> StrokePoint(2f * cx - p.x, p.y) }
            )
            SymmetryMode.HORIZONTAL -> listOf(
                points.map { p -> StrokePoint(p.x, 2f * cy - p.y) }
            )
            SymmetryMode.QUAD -> listOf(
                points.map { p -> StrokePoint(2f * cx - p.x, p.y) },
                points.map { p -> StrokePoint(p.x, 2f * cy - p.y) },
                points.map { p -> StrokePoint(2f * cx - p.x, 2f * cy - p.y) }
            )
        }
    }

    private fun ensureBoardLayers(board: WhiteboardBoard): List<DrawingLayer> {
        return board.layers.ifEmpty { listOf(DrawingLayer()) }
    }

    fun activeDrawingLayer(board: WhiteboardBoard = _uiState.value.board): DrawingLayer {
        val layers = ensureBoardLayers(board)
        return layers.find { it.id == board.activeLayerId } ?: layers.last()
    }

    fun canDrawOnActiveLayer(): Boolean {
        val layer = activeDrawingLayer()
        return layer.isVisible && !layer.isLocked
    }

    private fun isLayerEditable(board: WhiteboardBoard, layerId: String): Boolean {
        val layers = ensureBoardLayers(board)
        val layer = layers.find { it.id == layerId } ?: layers.first()
        return layer.isVisible && !layer.isLocked
    }

    fun selectLayer(layerId: String) {
        _uiState.update { state ->
            val layers = ensureBoardLayers(state.board)
            if (layers.none { it.id == layerId }) return@update state
            state.withUpdatedBoard { b ->
                b.copy(layers = layers, activeLayerId = layerId)
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun addLayer() {
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                val nextIndex = cur.size + 1
                val newLayer = DrawingLayer(
                    id = "layer_${UUID.randomUUID().toString().take(8)}",
                    name = "Capa $nextIndex",
                    isVisible = true,
                    opacity = 1.0f,
                    isLocked = false
                )
                b.copy(layers = cur + newLayer, activeLayerId = newLayer.id)
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun toggleLayerVisibility(layerId: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                b.copy(
                    layers = cur.map { l -> if (l.id == layerId) l.copy(isVisible = !l.isVisible) else l }
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun toggleLayerLock(layerId: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                b.copy(
                    layers = cur.map { l -> if (l.id == layerId) l.copy(isLocked = !l.isLocked) else l }
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        val clamped = opacity.coerceIn(0f, 1f)
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                b.copy(
                    layers = cur.map { l -> if (l.id == layerId) l.copy(opacity = clamped) else l }
                )
            }
        }
    }

    fun moveLayerUp(layerId: String) {
        val cur = ensureBoardLayers(_uiState.value.board)
        val idx = cur.indexOfFirst { it.id == layerId }
        if (idx < 0 || idx >= cur.lastIndex) return
        saveUndoSnapshot()
        val reordered = cur.toMutableList().apply {
            val item = removeAt(idx)
            add(idx + 1, item)
        }
        _uiState.update { state ->
            state.withUpdatedBoard { b -> b.copy(layers = reordered) }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun moveLayerDown(layerId: String) {
        val cur = ensureBoardLayers(_uiState.value.board)
        val idx = cur.indexOfFirst { it.id == layerId }
        if (idx <= 0) return
        saveUndoSnapshot()
        val reordered = cur.toMutableList().apply {
            val item = removeAt(idx)
            add(idx - 1, item)
        }
        _uiState.update { state ->
            state.withUpdatedBoard { b -> b.copy(layers = reordered) }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun changeLayerBlendMode(layerId: String, mode: LayerBlendMode) {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                b.copy(layers = cur.map { l -> if (l.id == layerId) l.copy(blendMode = mode) else l })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun toggleLayerAlphaLock(layerId: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                b.copy(layers = cur.map { l -> if (l.id == layerId) l.copy(isAlphaLocked = !l.isAlphaLocked) else l })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun toggleLayerReference(layerId: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                val target = cur.find { it.id == layerId } ?: return@withUpdatedBoard b
                val nextRef = !target.isReference
                b.copy(
                    layers = cur.map { l ->
                        when {
                            l.id == layerId -> l.copy(isReference = nextRef)
                            nextRef -> l.copy(isReference = false)
                            else -> l
                        }
                    }
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun duplicateLayer(layerId: String) {
        val board = _uiState.value.board
        val cur = ensureBoardLayers(board)
        val sourceIdx = cur.indexOfFirst { it.id == layerId }
        if (sourceIdx < 0) return
        val source = cur[sourceIdx]
        val newLayerId = "layer_${UUID.randomUUID().toString().take(8)}"
        mutableRasterTileStore.duplicateLayerTiles(board.id, layerId, newLayerId)
        val dupPatch = studioEngine.createClearLayerPatch(board.id, newLayerId)?.let {
            TileRasterUndoPatch(
                boardId = board.id,
                layerId = newLayerId,
                beforeTiles = it.beforeTiles.keys.associateWith { null },
                afterTiles = it.beforeTiles
            )
        }
        saveUndoSnapshot(listOfNotNull(dupPatch))
        val duplicatedLayer = source.copy(
            id = newLayerId,
            name = "${source.name} copia",
            isLocked = false,
            isReference = false,
            rasterRevision = mutableRasterTileStore.getLayerRevision(board.id, newLayerId)
        )
        val newStrokes = board.strokes.filter { it.layerId == layerId }.map {
            it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId)
        }
        val newShapes = board.shapes.filter { it.layerId == layerId }.map {
            it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId)
        }
        val updatedLayers = cur.toMutableList().apply { add(sourceIdx + 1, duplicatedLayer) }
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(
                    layers = updatedLayers,
                    activeLayerId = newLayerId,
                    strokes = b.strokes + newStrokes,
                    shapes = b.shapes + newShapes
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun mergeLayerDown(layerId: String) {
        val board = _uiState.value.board
        val cur = ensureBoardLayers(board)
        val idx = cur.indexOfFirst { it.id == layerId }
        if (idx <= 0) return
        val topLayer = cur[idx]
        val targetLayer = cur[idx - 1]

        val topClearPatch = studioEngine.createClearLayerPatch(board.id, layerId)
        val bottomBefore = mutableRasterTileStore.snapshotLayerTileBytes(board.id, targetLayer.id)

        mutableRasterTileStore.mergeLayerTilesDown(
            boardId = board.id,
            topLayerId = topLayer.id,
            topOpacity = topLayer.opacity,
            topBlendMode = topLayer.blendMode,
            bottomLayerId = targetLayer.id
        )
        val bottomAfter = mutableRasterTileStore.snapshotLayerTileBytes(board.id, targetLayer.id)
        val bottomPatch = if (bottomBefore != bottomAfter) {
            val allCoords = bottomBefore.keys + bottomAfter.keys
            TileRasterUndoPatch(
                boardId = board.id,
                layerId = targetLayer.id,
                beforeTiles = allCoords.associateWith { bottomBefore[it] },
                afterTiles = allCoords.associateWith { bottomAfter[it] }
            )
        } else null

        saveUndoSnapshot(listOfNotNull(topClearPatch, bottomPatch))
        val newTargetRev = mutableRasterTileStore.getLayerRevision(board.id, targetLayer.id)
        val nextLayers = cur
            .filterNot { it.id == layerId }
            .map { if (it.id == targetLayer.id) it.copy(rasterRevision = newTargetRev) else it }
        val nextStrokes = board.strokes.map { if (it.layerId == layerId) it.copy(layerId = targetLayer.id) else it }
        val nextShapes = board.shapes.map { if (it.layerId == layerId) it.copy(layerId = targetLayer.id) else it }
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(
                    layers = nextLayers,
                    activeLayerId = targetLayer.id,
                    strokes = nextStrokes,
                    shapes = nextShapes
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun deleteLayer(layerId: String) {
        val board = _uiState.value.board
        val cur = ensureBoardLayers(board)
        if (cur.size <= 1) return
        val idx = cur.indexOfFirst { it.id == layerId }
        if (idx < 0) return
        val clearPatch = studioEngine.createClearLayerPatch(board.id, layerId)
        saveUndoSnapshot(listOfNotNull(clearPatch))
        mutableRasterTileStore.removeLayer(board.id, layerId)
        val remaining = cur.filterNot { it.id == layerId }
        val nextActiveId = if (board.activeLayerId == layerId) {
            remaining.getOrNull((idx - 1).coerceAtLeast(0))?.id ?: remaining.first().id
        } else {
            board.activeLayerId
        }
        val remainingStrokes = board.strokes.filterNot { it.layerId == layerId }
        val remainingShapes = board.shapes.filterNot { it.layerId == layerId }
        val survivingStrokeIds = remainingStrokes.map { it.id }.toSet()
        val survivingShapeIds = remainingShapes.map { it.id }.toSet()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(
                    layers = remaining,
                    activeLayerId = nextActiveId,
                    strokes = remainingStrokes,
                    shapes = remainingShapes
                )
            }.copy(
                selectedStrokeId = state.selectedStrokeId?.takeIf { it in survivingStrokeIds },
                selectedStrokeIds = state.selectedStrokeIds.filter { it in survivingStrokeIds }.toSet(),
                selectedShapeId = state.selectedShapeId?.takeIf { it in survivingShapeIds },
                selectedShapeIds = state.selectedShapeIds.filter { it in survivingShapeIds }.toSet()
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun sampleCanvasColorAt(worldX: Float, worldY: Float, fallbackHex: String? = null): String {
        val board = _uiState.value.board
        val layers = ensureBoardLayers(board)
        val layerOrder = layers.mapIndexed { i, l -> l.id to i }.toMap()
        val visibleIds = layers.filter { it.isVisible }.map { it.id }.toSet()

        val topStroke = board.strokes
            .asReversed()
            .sortedByDescending { layerOrder[it.layerId] ?: 0 }
            .firstOrNull { stroke ->
                visibleIds.contains(stroke.layerId) &&
                    !stroke.isRainbow &&
                    stroke.geometryMetrics.intersectsCircle(worldX, worldY, stroke.width * 0.7f + 16f) &&
                    isPointNearStroke(stroke.points, worldX, worldY, stroke.width * 0.7f + 16f)
            }
        if (topStroke != null) return topStroke.colorHex

        val topShape = board.shapes
            .asReversed()
            .sortedByDescending { layerOrder[it.layerId] ?: 0 }
            .firstOrNull { s ->
                !s.isHiddenByCollapse &&
                    visibleIds.contains(s.layerId) &&
                    worldX in (s.x - 16f)..(s.x + s.width + 16f) &&
                    worldY in (s.y - 16f)..(s.y + s.height + 16f)
            }
        if (topShape != null) {
            return if (topShape.isFilled) topShape.fillColorHex else topShape.strokeColorHex
        }

        return fallbackHex ?: board.colorTheme.hex
    }

    fun applyEyedropperColor(colorHex: String, returnToBrush: Boolean = true) {
        setPenColor(colorHex, isRainbow = false)
        if (returnToBrush) {
            val slot = brushSlots.getOrNull(_uiState.value.activeBrushSlotIndex)
            if (slot != null) {
                setTool(slot.toolType)
            }
        }
    }

    fun toggleZenMode() {
        _uiState.update { it.copy(isZenMode = !it.isZenMode, isBrushPopoverOpen = false) }
    }

    fun toggleSnapToGrid() {
        _uiState.update { it.copy(isSnapToGrid = !it.isSnapToGrid) }
    }

    fun toggleRuler() {
        _uiState.update {
            val next = !it.isRulerActive
            it.copy(
                isRulerActive = next,
                rulerState = it.rulerState.copy(isVisible = next),
                guidesState = it.guidesState.copy(
                    activeGuide = if (next) GuideType.RULER else if (it.guidesState.activeGuide == GuideType.RULER) GuideType.NONE else it.guidesState.activeGuide
                )
            )
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
            val snapped = StrokeStabilizer.snapRulerAngleToDetents(
                rawAngleDeg = r.angleDegrees + deltaAngle,
                snapEnabled = r.snapAnglesEnabled,
                isLocked = r.isAngleLocked,
                currentAngleDeg = r.angleDegrees
            )
            it.copy(rulerState = r.copy(angleDegrees = snapped))
        }
    }

    fun toggleRulerAngleLock() {
        _uiState.update {
            val r = it.rulerState
            it.copy(rulerState = r.copy(isAngleLocked = !r.isAngleLocked))
        }
    }

    fun selectGuide(guideType: GuideType) {
        val cam = _cameraState.value
        val worldCenterX = cam.screenToWorldX(cam.viewportWidthPx * 0.5f)
        val worldCenterY = cam.screenToWorldY(cam.viewportHeightPx * 0.5f)
        _uiState.update { state ->
            val currentGuides = state.guidesState
            val resolvedType = if (guideType != GuideType.NONE && currentGuides.activeGuide == guideType) {
                GuideType.NONE
            } else {
                guideType
            }
            val showRuler = resolvedType == GuideType.RULER
            val showEllipse = resolvedType == GuideType.ELLIPSE
            val showFrenchCurve = resolvedType == GuideType.FRENCH_CURVE
            val nextPersp = if (resolvedType == GuideType.PERSPECTIVE) {
                if (currentGuides.perspective.mode == PerspectiveMode.OFF) {
                    currentGuides.perspective.copy(mode = PerspectiveMode.TWO_POINT)
                } else {
                    currentGuides.perspective
                }
            } else {
                currentGuides.perspective.copy(mode = PerspectiveMode.OFF)
            }
            state.copy(
                isRulerActive = showRuler,
                rulerState = state.rulerState.copy(
                    isVisible = showRuler,
                    centerX = if (showRuler && !state.rulerState.isVisible) worldCenterX else state.rulerState.centerX,
                    centerY = if (showRuler && !state.rulerState.isVisible) worldCenterY else state.rulerState.centerY
                ),
                guidesState = currentGuides.copy(
                    activeGuide = resolvedType,
                    ellipse = currentGuides.ellipse.copy(
                        isVisible = showEllipse,
                        centerX = if (showEllipse && !currentGuides.ellipse.isVisible) worldCenterX else currentGuides.ellipse.centerX,
                        centerY = if (showEllipse && !currentGuides.ellipse.isVisible) worldCenterY else currentGuides.ellipse.centerY
                    ),
                    frenchCurve = currentGuides.frenchCurve.copy(
                        isVisible = showFrenchCurve,
                        centerX = if (showFrenchCurve && !currentGuides.frenchCurve.isVisible) worldCenterX else currentGuides.frenchCurve.centerX,
                        centerY = if (showFrenchCurve && !currentGuides.frenchCurve.isVisible) worldCenterY else currentGuides.frenchCurve.centerY
                    ),
                    perspective = nextPersp
                )
            )
        }
    }

    fun updateEllipseGuide(guide: EllipseGuideState) {
        _uiState.update { state ->
            state.copy(
                isRulerActive = false,
                rulerState = state.rulerState.copy(isVisible = false),
                guidesState = state.guidesState.copy(
                    activeGuide = if (guide.isVisible) GuideType.ELLIPSE else GuideType.NONE,
                    ellipse = guide
                )
            )
        }
    }

    fun updateFrenchCurveGuide(guide: FrenchCurveGuideState) {
        _uiState.update { state ->
            state.copy(
                isRulerActive = false,
                rulerState = state.rulerState.copy(isVisible = false),
                guidesState = state.guidesState.copy(
                    activeGuide = if (guide.isVisible) GuideType.FRENCH_CURVE else GuideType.NONE,
                    frenchCurve = guide
                )
            )
        }
    }

    fun selectPerspectiveMode(mode: PerspectiveMode) {
        val cam = _cameraState.value
        val worldCenterX = cam.screenToWorldX(cam.viewportWidthPx * 0.5f)
        val worldCenterY = cam.screenToWorldY(cam.viewportHeightPx * 0.5f)
        _uiState.update { state ->
            val prevPersp = state.guidesState.perspective
            val wasOff = prevPersp.mode == PerspectiveMode.OFF
            val updatedPersp = prevPersp.copy(
                mode = mode,
                horizonY = if (wasOff) worldCenterY - 40f else prevPersp.horizonY,
                vp1 = if (wasOff) prevPersp.vp1.copy(x = worldCenterX - 420f, y = worldCenterY - 40f) else prevPersp.vp1,
                vp2 = if (wasOff) prevPersp.vp2.copy(x = worldCenterX + 420f, y = worldCenterY - 40f) else prevPersp.vp2,
                vp3 = if (wasOff) prevPersp.vp3.copy(x = worldCenterX, y = worldCenterY + 620f) else prevPersp.vp3
            )
            state.copy(
                isRulerActive = false,
                rulerState = state.rulerState.copy(isVisible = false),
                guidesState = state.guidesState.copy(
                    activeGuide = if (mode != PerspectiveMode.OFF) GuideType.PERSPECTIVE else GuideType.NONE,
                    ellipse = state.guidesState.ellipse.copy(isVisible = false),
                    frenchCurve = state.guidesState.frenchCurve.copy(isVisible = false),
                    perspective = updatedPersp
                )
            )
        }
    }

    fun updatePerspectiveGuide(guide: PerspectiveGuideState) {
        _uiState.update { state ->
            state.copy(
                guidesState = state.guidesState.copy(
                    activeGuide = if (guide.mode != PerspectiveMode.OFF) GuideType.PERSPECTIVE else GuideType.NONE,
                    perspective = guide
                )
            )
        }
    }

    fun setPredictionLevel(level: Int) {
        _uiState.update { it.copy(predictionLevel = level.coerceIn(0, 5)) }
    }

    fun setFloodFillTolerance(tolerance: Int) {
        _uiState.update { it.copy(floodFillTolerance = tolerance.coerceIn(8, 96)) }
    }

    fun beginRasterToolGesture() {
        pendingRasterUndoSnapshotPushed = false
        pendingRasterPatch = null
    }

    fun applySmudgeSegment(
        points: List<StrokePoint>,
        brushRadiusWorld: Float,
        strength: Float = 0.72f
    ): Boolean {
        val state = _uiState.value
        val activeLayer = activeDrawingLayer(state.board)
        if (!activeLayer.isVisible || activeLayer.isLocked || points.size < 2) return false
        if (!pendingRasterUndoSnapshotPushed) {
            saveUndoSnapshot()
            pendingRasterUndoSnapshotPushed = true
        }
        val patch = studioEngine.executeSmudgeStroke(
            board = state.board,
            layer = activeLayer,
            points = points,
            brushRadiusWorld = brushRadiusWorld,
            strength = strength,
            handDrawnFactor = state.handDrawnFactor
        ) ?: return false

        pendingRasterPatch = StudioEngineController.mergeUndoPatches(pendingRasterPatch, patch)
        refreshLayerRasterRevision(activeLayer.id)
        return true
    }

    fun applySoftEraserSegment(
        from: StrokePoint,
        to: StrokePoint,
        radiusWorld: Float
    ): Boolean {
        val state = _uiState.value
        val activeLayer = activeDrawingLayer(state.board)
        if (!activeLayer.isVisible || activeLayer.isLocked) return false
        if (!pendingRasterUndoSnapshotPushed) {
            saveUndoSnapshot()
            pendingRasterUndoSnapshotPushed = true
        }
        val rasterPatch = studioEngine.executeSoftEraserSegment(
            board = state.board,
            layer = activeLayer,
            points = listOf(from, to),
            eraserRadiusWorld = radiusWorld,
            strength = 0.78f
        )
        if (rasterPatch != null) {
            pendingRasterPatch = StudioEngineController.mergeUndoPatches(pendingRasterPatch, rasterPatch)
            refreshLayerRasterRevision(activeLayer.id)
        }
        val erasedVectors = erasePixelSegment(from, to, radiusWorld, saveSnapshot = false)
        return rasterPatch != null || erasedVectors
    }

    fun commitRasterToolGesture() {
        val patch = pendingRasterPatch
        if (pendingRasterUndoSnapshotPushed && patch != null) {
            historyStore.appendRasterPatchToTop(patch)
        }
        val hadChanges = pendingRasterUndoSnapshotPushed
        pendingRasterUndoSnapshotPushed = false
        pendingRasterPatch = null
        if (hadChanges) {
            commitBoardMutationToStore(_uiState.value.board, persist = true)
        }
    }

    fun applyFloodFillTap(worldX: Float, worldY: Float): FloodFillResult {
        val state = _uiState.value
        val activeLayer = activeDrawingLayer(state.board)
        if (!activeLayer.isVisible || activeLayer.isLocked) {
            return FloodFillResult(null, 0, didHitRegionEdge = false)
        }
        val result = studioEngine.executeFloodFill(
            board = state.board,
            targetLayer = activeLayer,
            seedWorldX = worldX,
            seedWorldY = worldY,
            fillColorHex = state.penColorHex,
            fillOpacity = state.penOpacity,
            tolerance = state.floodFillTolerance,
            handDrawnFactor = state.handDrawnFactor
        )
        val patch = result.undoPatch ?: return result
        saveUndoSnapshot(listOf(patch))
        refreshLayerRasterRevision(activeLayer.id)
        commitBoardMutationToStore(_uiState.value.board, persist = true)
        return result
    }

    private fun refreshLayerRasterRevision(layerId: String) {
        _uiState.update { state ->
            val newRev = mutableRasterTileStore.getLayerRevision(state.board.id, layerId)
            state.withUpdatedBoard { b ->
                val cur = ensureBoardLayers(b)
                b.copy(
                    layers = cur.map { l -> if (l.id == layerId) l.copy(rasterRevision = newRev) else l }
                )
            }
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

        board.textItems.forEach { t ->
            hasElements = true
            val h = if (t.height > 0f) t.height else t.fontSize * 2f
            if (t.x < minX) minX = t.x
            if (t.x + t.width > maxX) maxX = t.x + t.width
            if (t.y < minY) minY = t.y
            if (t.y + h > maxY) maxY = t.y + h
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

    fun setHandDrawnFactor(factor: Float) {
        _uiState.update { it.copy(handDrawnFactor = factor.coerceIn(0f, 1f)) }
    }

    fun brushTypeForTool(tool: ToolType): StrokeBrushType {
        return when (tool) {
            ToolType.PENCIL -> StrokeBrushType.PENCIL
            ToolType.PEN -> StrokeBrushType.FOUNTAIN_PEN
            ToolType.TECHNICAL_INK -> StrokeBrushType.TECHNICAL_INK
            ToolType.FINE_MARKER -> StrokeBrushType.FINE_MARKER
            ToolType.CHISEL_MARKER -> StrokeBrushType.CHISEL_MARKER
            ToolType.PAINT_BRUSH -> StrokeBrushType.PAINT_BRUSH
            ToolType.WATERCOLOR -> StrokeBrushType.WATERCOLOR
            ToolType.AIRBRUSH -> StrokeBrushType.AIRBRUSH
            ToolType.HIGHLIGHTER -> StrokeBrushType.HIGHLIGHTER
            else -> StrokeBrushType.FOUNTAIN_PEN
        }
    }

    fun effectiveStrokeWidthForBrush(brushType: StrokeBrushType, basePenWidth: Float): Float {
        return when (brushType) {
            StrokeBrushType.PENCIL -> (basePenWidth * 0.72f).coerceIn(1f, 160f)
            StrokeBrushType.FOUNTAIN_PEN -> basePenWidth.coerceIn(1f, 160f)
            StrokeBrushType.TECHNICAL_INK -> basePenWidth.coerceIn(1f, 64f)
            StrokeBrushType.FINE_MARKER -> (basePenWidth * 0.80f).coerceIn(1.5f, 160f)
            StrokeBrushType.CHISEL_MARKER -> (basePenWidth * 1.15f).coerceIn(2f, 160f)
            StrokeBrushType.PAINT_BRUSH -> (basePenWidth * 1.45f).coerceIn(3f, 160f)
            StrokeBrushType.WATERCOLOR -> (basePenWidth * 1.65f).coerceIn(4f, 160f)
            StrokeBrushType.AIRBRUSH -> (basePenWidth * 2.10f).coerceIn(6f, 160f)
            StrokeBrushType.HIGHLIGHTER -> (basePenWidth * 2.20f).coerceIn(8f, 160f)
        }
    }

    fun effectiveColorForBrush(brushType: StrokeBrushType, penColorHex: String): String = penColorHex

    fun commitCompletedStroke(
        points: List<StrokePoint>,
        isHighlighter: Boolean,
        skipPrediction: Boolean = false
    ) {
        if (points.size < 2) return
        val state = _uiState.value
        val activeLayer = activeDrawingLayer(state.board)
        if (!activeLayer.isVisible || activeLayer.isLocked) {
            _uiState.update { it.copy(activeStroke = null) }
            return
        }
        saveUndoSnapshot()
        val hasGuideConstraint = skipPrediction ||
            state.isRulerActive ||
            state.guidesState.ellipse.isVisible ||
            state.guidesState.frenchCurve.isVisible
        val processedPoints = if (!hasGuideConstraint && state.predictionLevel > 0) {
            StrokeStabilizer.applyPredictiveStroke(points, state.predictionLevel)
        } else {
            points
        }

        val brushType = if (isHighlighter) StrokeBrushType.HIGHLIGHTER else brushTypeForTool(state.currentTool)
        val effectiveWidth = effectiveStrokeWidthForBrush(brushType, state.penWidth)
        val effectiveColor = effectiveColorForBrush(brushType, state.penColorHex)
        val isTranslucentEmphasis = brushType == StrokeBrushType.HIGHLIGHTER
        val effectiveOpacity = state.penOpacity.coerceIn(0.05f, 1.0f)
        val activeSlot = brushSlots.getOrNull(state.activeBrushSlotIndex)
        val studioSettings = if (activeSlot != null && activeSlot.brushType == brushType) {
            activeSlot.studioSettings
        } else {
            BrushStudioSettings.defaultFor(brushType)
        }

        val newStroke = WhiteboardStroke(
            points = processedPoints,
            colorHex = effectiveColor,
            width = effectiveWidth,
            isHighlighter = brushType == StrokeBrushType.HIGHLIGHTER,
            isRainbow = state.isRainbowPen && !isTranslucentEmphasis,
            strokeStyle = StrokeStyle.SOLID,
            capStyle = LineCapStyle.NONE,
            brushType = brushType,
            opacity = effectiveOpacity,
            layerId = activeLayer.id,
            studioSettings = studioSettings
        )

        val recognized = if (state.autoShapeRecognition && state.symmetryMode == SymmetryMode.NONE) {
            ShapeRecognizer.recognizeStroke(newStroke.copy(points = processedPoints))
        } else {
            null
        }

        if (recognized != null && recognized.confidence >= 0.80f && recognized.shapeType != ShapeType.LINE) {
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(shapes = b.shapes + recognized.shapeItem.copy(layerId = activeLayer.id))
                }.copy(
                    activeStroke = null,
                    shapeRecognitionProposal = null
                )
            }
        } else {
            val mirroredStrokes = mirrorPointsForSymmetry(
                points = processedPoints,
                mode = state.symmetryMode,
                cx = state.symmetryCenterX,
                cy = state.symmetryCenterY
            ).map { mirroredPts ->
                newStroke.copy(
                    id = UUID.randomUUID().toString(),
                    points = mirroredPts
                )
            }
            val addedStrokes = listOf(newStroke) + mirroredStrokes
            _uiState.update {
                it.withUpdatedBoard { b ->
                    b.copy(strokes = b.strokes + addedStrokes)
                }.copy(
                    activeStroke = null,
                    shapeRecognitionProposal = null
                )
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
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
            if (!isLayerEditable(board, stroke.layerId)) {
                false
            } else {
                val hitRadius = tolerance + stroke.width * 0.4f
                stroke.geometryMetrics.intersectsCircle(worldX, worldY, hitRadius) &&
                    isPointNearStroke(stroke.points, worldX, worldY, hitRadius)
            }
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
            if (s.isLocked || s.isHiddenByCollapse || !isLayerEditable(board, s.layerId)) {
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
                val (lx, ly) = BoxTransformGeometry.toLocalUnrotated(
                    x = s.x,
                    y = s.y,
                    width = s.width,
                    height = s.height,
                    rotationDegrees = s.rotation,
                    worldX = worldX,
                    worldY = worldY
                )
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
            if (!isLayerEditable(board, stroke.layerId) ||
                !stroke.geometryMetrics.intersectsCircle(worldX, worldY, effectiveRadius)
            ) {
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
            if (shape.isLocked || shape.isHiddenByCollapse || !isLayerEditable(board, shape.layerId)) {
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
            if (s.isLocked || s.isHiddenByCollapse || !isLayerEditable(board, s.layerId)) {
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
            if (s.points.isEmpty() || !isLayerEditable(board, s.layerId)) false
            else {
                val insideCount = s.points.count { p -> isPointInPolygon(p.x, p.y, lassoPolygon) }
                insideCount > 0 && (insideCount * 2 >= s.points.size || insideCount >= 2)
            }
        }.map { it.id }.toSet()

        val enclosedTextIds = board.textItems.filter { item ->
            if (item.isLocked || item.isHiddenByCollapse) false else {
                val effectiveH = if (item.height > 0f) item.height else item.fontSize * 2f
                val centerX = item.x + item.width / 2f
                val centerY = item.y + effectiveH / 2f
                isPointInPolygon(centerX, centerY, lassoPolygon) || (
                    isPointInPolygon(item.x, item.y, lassoPolygon) &&
                    isPointInPolygon(item.x + item.width, item.y + effectiveH, lassoPolygon)
                )
            }
        }.map { it.id }.toSet()

        if (enclosedShapeIds.isNotEmpty() || enclosedStrokeIds.isNotEmpty() || enclosedTextIds.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    selectedShapeIds = enclosedShapeIds,
                    selectedStrokeIds = enclosedStrokeIds,
                    selectedTextIds = enclosedTextIds,
                    selectedTextId = enclosedTextIds.firstOrNull(),
                    selectedShapeId = enclosedShapeIds.firstOrNull(),
                    selectedStrokeId = enclosedStrokeIds.firstOrNull(),
                    isBrushPopoverOpen = false
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

    fun deleteCurrentSelection() {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val textIds = if (state.selectedTextIds.isNotEmpty()) state.selectedTextIds else state.selectedTextId?.let { setOf(it) } ?: emptySet()

        if (strokeIds.isEmpty() && shapeIds.isEmpty() && textIds.isEmpty()) return
        saveUndoSnapshot()

        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = b.strokes.filterNot { s -> strokeIds.contains(s.id) },
                    shapes = b.shapes.filterNot { sh -> !sh.isLocked && shapeIds.contains(sh.id) },
                    textItems = b.textItems.filterNot { item -> !item.isLocked && textIds.contains(item.id) }
                )
            }.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet()
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun selectStroke(id: String?) {
        _uiState.update {
            it.copy(
                selectedStrokeId = id,
                selectedStrokeIds = if (id != null) setOf(id) else emptySet(),
                selectedShapeId = null,
                selectedShapeIds = emptySet(),
                selectedTextId = null,
                selectedTextIds = emptySet(),
                isBrushPopoverOpen = false
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
                selectedTextId = null,
                selectedTextIds = emptySet(),
                isBrushPopoverOpen = false
            )
        }
    }

    fun selectTextItem(id: String?) {
        _uiState.update {
            it.copy(
                selectedTextId = id,
                selectedTextIds = if (id != null) setOf(id) else emptySet(),
                selectedStrokeId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeId = null,
                selectedShapeIds = emptySet(),
                isBrushPopoverOpen = false
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet()
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
        commitBoardMutationToStore(_uiState.value.board, persist = true)
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
        commitBoardMutationToStore(_uiState.value.board, persist = true)
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
                    selectedTextIds = emptySet()
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

    fun beginCurrentSelectionTransformSnapshot() {
        beginTransformSnapshot()
    }

    fun commitCurrentSelectionTransform() {
        persistCurrentBoard()
    }

    fun updateTextItem(id: String, newText: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id) item.copy(text = newText) else item
                })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun beginTextItemTransformSnapshot() {
        saveUndoSnapshot()
    }

    fun moveTextItem(id: String, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id && !item.isLocked) {
                        item.copy(x = item.x + dx, y = item.y + dy)
                    } else {
                        item
                    }
                })
            }
        }
    }

    fun resizeTextItemWidth(id: String, newWidth: Float) {
        val clamped = newWidth.coerceIn(96f, 1400f)
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id && !item.isLocked) {
                        item.copy(width = clamped)
                    } else {
                        item
                    }
                })
            }
        }
    }

    fun resizeTextItemHandle(
        id: String,
        handle: BoxResizeHandle,
        deltaScreenX: Float,
        deltaScreenY: Float,
        fallbackHeightWorld: Float = 68f
    ) {
        val camera = _cameraState.value
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id && !item.isLocked) {
                        val effectiveH = max(item.height, fallbackHeightWorld).coerceAtLeast(52f)
                        val next = BoxTransformGeometry.resizeByCornerHandle(
                            bounds = WorldBoxBounds(item.x, item.y, item.width, effectiveH),
                            handle = handle,
                            deltaScreenX = deltaScreenX,
                            deltaScreenY = deltaScreenY,
                            scale = camera.scale,
                            rotationDegrees = item.rotation,
                            minWidthWorld = 96f,
                            minHeightWorld = 52f
                        )
                        item.copy(
                            x = next.x,
                            y = next.y,
                            width = next.width,
                            height = next.height
                        )
                    } else {
                        item
                    }
                })
            }
        }
    }

    fun rotateTextItem(id: String, rotationDegrees: Float) {
        val cleanAngle = ((rotationDegrees % 360f) + 360f) % 360f
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id && !item.isLocked) item.copy(rotation = cleanAngle) else item
                })
            }
        }
    }

    fun commitTextItemTransform() {
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun updateTextItemStyle(
        id: String,
        colorHex: String? = null,
        backgroundColorHex: String? = null,
        clearBackground: Boolean = false,
        isBold: Boolean? = null,
        fontSize: Float? = null,
        isEditableCard: Boolean? = null
    ) {
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(textItems = board.textItems.map { item ->
                    if (item.id == id) {
                        item.copy(
                            colorHex = colorHex ?: item.colorHex,
                            backgroundColorHex = when {
                                clearBackground -> "TRANSPARENT"
                                backgroundColorHex != null -> backgroundColorHex
                                else -> item.backgroundColorHex
                            },
                            isBold = isBold ?: item.isBold,
                            fontSize = fontSize?.coerceIn(18f, 72f) ?: item.fontSize,
                            isEditableCard = isEditableCard ?: item.isEditableCard
                        )
                    } else {
                        item
                    }
                })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun updateShapeLabel(id: String, newLabel: String) {
        _uiState.update { state ->
            state.withUpdatedBoard { board ->
                board.copy(shapes = board.shapes.map { shape ->
                    if (shape.id == id) shape.copy(label = newLabel) else shape
                })
            }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
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
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun moveSelectedObjects(dxWorld: Float, dyWorld: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val textIds = if (state.selectedTextIds.isNotEmpty()) state.selectedTextIds else state.selectedTextId?.let { setOf(it) } ?: emptySet()

        if (strokeIds.isEmpty() && shapeIds.isEmpty() && textIds.isEmpty()) return

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
        val updatedTexts = if (textIds.isEmpty()) state.board.textItems else state.board.textItems.map { item ->
            if (textIds.contains(item.id) && !item.isLocked) item.copy(x = item.x + dxWorld, y = item.y + dyWorld) else item
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(strokes = updatedStrokes, shapes = updatedShapes, textItems = updatedTexts)
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
                    val halfLen = hypot(p1.x - p0.x, p1.y - p0.y) / 2f
                    val angle = atan2(newWorldY - cy, newWorldX - cx)
                    val nv0: StrokePoint
                    val nv1: StrokePoint
                    if (handleIndex == 2) {
                        nv0 = StrokePoint(cx + halfLen * cos(angle), cy + halfLen * sin(angle))
                        nv1 = StrokePoint(cx - halfLen * cos(angle), cy - halfLen * sin(angle))
                    } else {
                        nv1 = StrokePoint(cx + halfLen * cos(angle), cy + halfLen * sin(angle))
                        nv0 = StrokePoint(cx - halfLen * cos(angle), cy - halfLen * sin(angle))
                    }
                    val minX = min(nv0.x, nv1.x)
                    val maxX = max(nv0.x, nv1.x)
                    val minY = min(nv0.y, nv1.y)
                    val maxY = max(nv0.y, nv1.y)
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
                    val minX = min(v0.x, v1.x)
                    val maxX = max(v0.x, v1.x)
                    val minY = min(v0.y, v1.y)
                    val maxY = max(v0.y, v1.y)
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
                    0, 2 -> min(newWorldX, anchorX - minSize)
                    else -> max(newWorldX, anchorX + minSize)
                }
                val clampedY = when (handleIndex) {
                    0, 1 -> min(newWorldY, anchorY - minSize)
                    else -> max(newWorldY, anchorY + minSize)
                }

                val newX = min(anchorX, clampedX)
                val newY = min(anchorY, clampedY)
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
                val len = hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                val clampedFactor = if (len * factor < 20f) 20f / len else if (len * factor > 3000f) 3000f / len else factor
                val nv0 = StrokePoint(mx + (p0.x - mx) * clampedFactor, my + (p0.y - my) * clampedFactor)
                val nv1 = StrokePoint(mx + (p1.x - mx) * clampedFactor, my + (p1.y - my) * clampedFactor)
                s.copy(
                    x = min(nv0.x, nv1.x),
                    y = min(nv0.y, nv1.y),
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
                    x = min(nv0.x, nv1.x),
                    y = min(nv0.y, nv1.y),
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
        commitBoardMutationToStore(_uiState.value.board, persist = true)
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
        commitBoardMutationToStore(_uiState.value.board, persist = true)
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
            val halfLen = hypot(p1.x - p0.x, p1.y - p0.y) / 2f
            val rawDeg = Math.toDegrees(atan2((worldY - cy).toDouble(), (worldX - cx).toDouble())).toFloat()
            val snappedDeg = BoxTransformGeometry.snapAngle(rawDeg, 3.5f)
            val angle = Math.toRadians(snappedDeg.toDouble()).toFloat()
            val nv0 = StrokePoint(cx - halfLen * cos(angle), cy - halfLen * sin(angle))
            val nv1 = StrokePoint(cx + halfLen * cos(angle), cy + halfLen * sin(angle))
            val minX = min(nv0.x, nv1.x)
            val maxX = max(nv0.x, nv1.x)
            val minY = min(nv0.y, nv1.y)
            val maxY = max(nv0.y, nv1.y)
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
            val deg = BoxTransformGeometry.calculateRotationDegrees(
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
            strokeRotationStartAngleDeg = (Math.toDegrees(atan2((worldY - cy).toDouble(), (worldX - cx).toDouble())) + 90.0).toFloat()
            return 0f
        }

        val (cx, cy) = strokeRotationPivot!!
        val startAngle = strokeRotationStartAngleDeg ?: 0f
        val curAngle = (Math.toDegrees(atan2((worldY - cy).toDouble(), (worldX - cx).toDouble())) + 90.0).toFloat()
        val rawDelta = ((curAngle - startAngle) % 360f + 360f) % 360f
        val snappedDelta = BoxTransformGeometry.snapAngle(rawDelta, 3.5f)
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
                val span = hypot(maxX - minX, maxY - minY).coerceAtLeast(1f)
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
                    0, 2 -> min(newWorldX, anchorX - minSize)
                    else -> max(newWorldX, anchorX + minSize)
                }
                val clampedY = when (handleIndex) {
                    0, 1 -> min(newWorldY, anchorY - minSize)
                    else -> max(newWorldY, anchorY + minSize)
                }

                val newX = min(anchorX, clampedX)
                val newY = min(anchorY, clampedY)
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
        val textIds = if (state.selectedTextIds.isNotEmpty()) state.selectedTextIds else state.selectedTextId?.let { setOf(it) } ?: emptySet()
        if (strokeIds.isEmpty() && shapeIds.isEmpty() && textIds.isEmpty()) return
        saveUndoSnapshot()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = if (strokeIds.isEmpty()) b.strokes else b.strokes.map { s ->
                        if (strokeIds.contains(s.id)) s.copy(colorHex = colorHex) else s
                    },
                    shapes = if (shapeIds.isEmpty()) b.shapes else b.shapes.map { sh ->
                        if (shapeIds.contains(sh.id)) sh.copy(strokeColorHex = colorHex) else sh
                    },
                    textItems = if (textIds.isEmpty()) b.textItems else b.textItems.map { item ->
                        if (textIds.contains(item.id)) item.copy(colorHex = colorHex) else item
                    }
                )
            }
        }
    }

    fun scaleCurrentSelection(factor: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val textIds = if (state.selectedTextIds.isNotEmpty()) state.selectedTextIds else state.selectedTextId?.let { setOf(it) } ?: emptySet()
        if (strokeIds.isEmpty() && shapeIds.isEmpty() && textIds.isEmpty()) return
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
                val span = hypot(maxX - minX, maxY - minY).coerceAtLeast(1f)
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
                val len = hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                val clampedFactor = if (len * factor < 20f) 20f / len else if (len * factor > 3000f) 3000f / len else factor
                val nv0 = StrokePoint(mx + (p0.x - mx) * clampedFactor, my + (p0.y - my) * clampedFactor)
                val nv1 = StrokePoint(mx + (p1.x - mx) * clampedFactor, my + (p1.y - my) * clampedFactor)
                s.copy(
                    x = min(nv0.x, nv1.x),
                    y = min(nv0.y, nv1.y),
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
        val updatedTexts = if (textIds.isEmpty()) state.board.textItems else state.board.textItems.map { item ->
            if (textIds.contains(item.id) && !item.isLocked) item.copy(
                width = (item.width * factor).coerceIn(96f, 1400f),
                height = if (item.height > 0f) (item.height * factor).coerceIn(52f, 1600f) else 0f,
                fontSize = (item.fontSize * factor).coerceIn(16f, 96f)
            ) else item
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updatedStrokes, shapes = updatedShapes, textItems = updatedTexts) } }
    }

    fun rotateCurrentSelection(deltaDegrees: Float) {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val textIds = if (state.selectedTextIds.isNotEmpty()) state.selectedTextIds else state.selectedTextId?.let { setOf(it) } ?: emptySet()
        if (strokeIds.isEmpty() && shapeIds.isEmpty() && textIds.isEmpty()) return
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
                    x = min(nv0.x, nv1.x),
                    y = min(nv0.y, nv1.y),
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
        val updatedTexts = if (textIds.isEmpty()) state.board.textItems else state.board.textItems.map { item ->
            if (textIds.contains(item.id) && !item.isLocked) item.copy(rotation = ((item.rotation + deltaDegrees) % 360f + 360f) % 360f) else item
        }
        _uiState.update { it.withUpdatedBoard { b -> b.copy(strokes = updatedStrokes, shapes = updatedShapes, textItems = updatedTexts) } }
    }

    fun duplicateCurrentSelection() {
        val state = _uiState.value
        val strokeIds = if (state.selectedStrokeIds.isNotEmpty()) state.selectedStrokeIds else state.selectedStrokeId?.let { setOf(it) } ?: emptySet()
        val shapeIds = if (state.selectedShapeIds.isNotEmpty()) state.selectedShapeIds else state.selectedShapeId?.let { setOf(it) } ?: emptySet()
        val textIds = if (state.selectedTextIds.isNotEmpty()) state.selectedTextIds else state.selectedTextId?.let { setOf(it) } ?: emptySet()
        val strokesToDup = state.board.strokes.filter { strokeIds.contains(it.id) }
        val shapesToDup = state.board.shapes.filter { shapeIds.contains(it.id) }
        val textsToDup = state.board.textItems.filter { textIds.contains(it.id) }
        if (strokesToDup.isEmpty() && shapesToDup.isEmpty() && textsToDup.isEmpty()) return
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
        val newTexts = textsToDup.map { item -> item.copy(id = UUID.randomUUID().toString(), x = item.x + 30f, y = item.y + 30f, groupId = null, parentId = null) }
        val newTextIds = newTexts.map { it.id }.toSet()
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = b.strokes + newStrokes,
                    shapes = b.shapes + newShapes,
                    textItems = b.textItems + newTexts
                )
            }.copy(
                selectedStrokeId = newStrokeIds.firstOrNull(),
                selectedStrokeIds = newStrokeIds,
                selectedShapeId = newShapeIds.firstOrNull(),
                selectedShapeIds = newShapeIds,
                selectedTextId = newTextIds.firstOrNull(),
                selectedTextIds = newTextIds
            )
        }
        if (newTexts.isNotEmpty()) {
            commitBoardMutationToStore(_uiState.value.board, persist = true)
        }
    }

    fun openBoard(boardId: String) {
        val target = _uiState.value.allBoards.find { it.id == boardId }
            ?: store.currentState.boards.find { it.id == boardId }
            ?: return
        historyStore.clear()
        store.activateBoardRasterTiles(boardId)
        _uiState.update {
            it.copy(
                board = target,
                isViewingBoardsHome = false,
                isBrushPopoverOpen = false,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet(),
                canUndo = false,
                canRedo = false
            )
        }
        resetZoom()
    }

    fun closeBoardToHome() {
        commitWorkspaceSync()
        _uiState.update {
            it.withUpdatedBoard { b -> b }.copy(
                isViewingBoardsHome = true,
                isZenMode = false,
                isBrushPopoverOpen = false,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet()
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
        val newBoard = WhiteboardBoard(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Nueva Pizarra" },
            gridType = gridType,
            colorTheme = colorTheme
        )
        if (openImmediately) {
            historyStore.clear()
            store.activateBoardRasterTiles(newBoard.id)
        }
        isCommittingToStore = true
        val db = try {
            store.createBoard(newBoard, prepend = true)
        } finally {
            isCommittingToStore = false
        }
        val canonicalBoard = db.boards.find { it.id == newBoard.id } ?: newBoard
        _uiState.update {
            it.copy(
                board = if (openImmediately) canonicalBoard else it.board,
                allBoards = db.boards,
                isViewingBoardsHome = !openImmediately,
                isBrushPopoverOpen = false,
                selectedStrokeId = if (openImmediately) null else it.selectedStrokeId,
                selectedShapeId = if (openImmediately) null else it.selectedShapeId,
                selectedTextId = if (openImmediately) null else it.selectedTextId,
                selectedStrokeIds = if (openImmediately) emptySet() else it.selectedStrokeIds,
                selectedShapeIds = if (openImmediately) emptySet() else it.selectedShapeIds,
                selectedTextIds = if (openImmediately) emptySet() else it.selectedTextIds,
                canUndo = if (openImmediately) false else it.canUndo,
                canRedo = if (openImmediately) false else it.canRedo
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
        val remappedTexts = original.textItems.map { t ->
            t.copy(
                id = remap(t.id)!!,
                groupId = remap(t.groupId),
                parentId = remap(t.parentId)
            )
        }
        val remappedStrokes = original.strokes.map { it.copy(id = UUID.randomUUID().toString()) }

        original.layers.forEach { layer ->
            val tiles = mutableRasterTileStore.getTilesForLayer(original.id, layer.id)
            for ((coord, bmp) in tiles) {
                val copyBmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: continue
                mutableRasterTileStore.putDecodedTile(dupId, layer.id, coord.tx, coord.ty, copyBmp)
            }
        }
        val dup = original.copy(
            id = dupId,
            title = "${original.title} (Copia)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            strokes = remappedStrokes,
            shapes = remappedShapes,
            textItems = remappedTexts
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
        _uiState.update { state ->
            state.withUpdatedBoard { b -> b.copy(gridType = grid) }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun setColorTheme(theme: CanvasColorTheme) {
        _uiState.update { state ->
            state.withUpdatedBoard { b -> b.copy(colorTheme = theme) }
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun renameBoard(newTitle: String) {
        renameBoardById(_uiState.value.board.id, newTitle)
    }

    fun clearBoard() {
        val board = _uiState.value.board
        val clearPatches = ensureBoardLayers(board).mapNotNull { layer ->
            studioEngine.createClearLayerPatch(board.id, layer.id)
        }
        saveUndoSnapshot(clearPatches)
        mutableRasterTileStore.removeBoard(board.id)
        _uiState.update {
            it.withUpdatedBoard { b ->
                b.copy(
                    strokes = emptyList(),
                    shapes = emptyList(),
                    textItems = emptyList(),
                    layers = ensureBoardLayers(b).map { l -> l.copy(rasterRevision = 0L) }
                )
            }.copy(
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet()
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun insertTextAtCenter(
        initialText: String = "Texto",
        colorHex: String? = null,
        fontSize: Float = 34f
    ) {
        val camera = _cameraState.value
        val worldCenterX = camera.screenToWorldX(camera.viewportWidthPx * 0.5f)
        val worldCenterY = camera.screenToWorldY(camera.viewportHeightPx * 0.5f)
        val board = _uiState.value.board
        val isDark = board.colorTheme.isDark
        val defaultWidth = 280f
        val defaultHeight = 84f
        val baseX = worldCenterX - defaultWidth * 0.5f
        val baseY = worldCenterY - defaultHeight * 0.5f
        val cascadeCount = board.textItems.count { existing ->
            kotlin.math.abs(existing.x - baseX) < 120f && kotlin.math.abs(existing.y - baseY) < 120f
        } % 5
        val cascadeOffset = cascadeCount * 28f

        val resolvedInk = colorHex ?: if (isDark) "#F1F5F9" else "#0F172A"
        val resolvedSurface = if (isDark) "#1E293B" else "#FFFFFF"

        val item = TextItem(
            text = initialText,
            x = baseX + cascadeOffset,
            y = baseY + cascadeOffset,
            fontSize = fontSize,
            colorHex = resolvedInk,
            isEditableCard = true,
            backgroundColorHex = resolvedSurface,
            width = defaultWidth,
            height = defaultHeight
        )
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(textItems = b.textItems + item)
            }.copy(
                selectedTextId = item.id,
                selectedTextIds = setOf(item.id),
                selectedStrokeId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeId = null,
                selectedShapeIds = emptySet(),
                isBrushPopoverOpen = false
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun deleteTextItem(id: String) {
        saveUndoSnapshot()
        _uiState.update { state ->
            state.withUpdatedBoard { b ->
                b.copy(textItems = b.textItems.filter { it.id != id })
            }.copy(
                selectedTextId = if (state.selectedTextId == id) null else state.selectedTextId,
                selectedTextIds = state.selectedTextIds - id
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    private fun saveUndoSnapshot(rasterPatches: List<TileRasterUndoPatch> = emptyList()) {
        historyStore.pushSnapshot(_uiState.value.board, rasterPatches)
        _uiState.update { it.copy(canUndo = historyStore.canUndo, canRedo = historyStore.canRedo) }
    }

    fun undo() {
        val currentBoard = _uiState.value.board
        val previous = historyStore.popUndo(currentBoard) ?: return
        previous.rasterPatches.asReversed().forEach { patch ->
            mutableRasterTileStore.applyPatch(patch, applyAfter = false)
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                val updatedLayers = previous.layers.map { l ->
                    l.copy(rasterRevision = mutableRasterTileStore.getLayerRevision(b.id, l.id))
                }
                b.copy(
                    strokes = previous.strokes,
                    shapes = previous.shapes,
                    textItems = previous.texts,
                    layers = updatedLayers,
                    activeLayerId = previous.activeLayerId
                )
            }.copy(
                canUndo = historyStore.canUndo,
                canRedo = historyStore.canRedo,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet()
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun redo() {
        val currentBoard = _uiState.value.board
        val next = historyStore.popRedo(currentBoard) ?: return
        next.rasterPatches.forEach { patch ->
            mutableRasterTileStore.applyPatch(patch, applyAfter = true)
        }
        _uiState.update {
            it.withUpdatedBoard { b ->
                val updatedLayers = next.layers.map { l ->
                    l.copy(rasterRevision = mutableRasterTileStore.getLayerRevision(b.id, l.id))
                }
                b.copy(
                    strokes = next.strokes,
                    shapes = next.shapes,
                    textItems = next.texts,
                    layers = updatedLayers,
                    activeLayerId = next.activeLayerId
                )
            }.copy(
                canUndo = historyStore.canUndo,
                canRedo = historyStore.canRedo,
                selectedStrokeId = null,
                selectedShapeId = null,
                selectedTextId = null,
                selectedStrokeIds = emptySet(),
                selectedShapeIds = emptySet(),
                selectedTextIds = emptySet()
            )
        }
        commitBoardMutationToStore(_uiState.value.board, persist = true)
    }

    fun setPenColor(hex: String, isRainbow: Boolean = false) {
        val activeIdx = _uiState.value.activeBrushSlotIndex
        if (activeIdx in brushSlots.indices) {
            brushSlots[activeIdx] = brushSlots[activeIdx].copy(colorHex = hex, isRainbow = isRainbow)
        }
        _uiState.update { state ->
            val prev = if (!hex.equals(state.penColorHex, ignoreCase = true)) {
                state.penColorHex
            } else {
                state.previousPenColorHex
            }
            state.copy(
                penColorHex = hex,
                previousPenColorHex = prev,
                isRainbowPen = isRainbow
            )
        }
    }

    fun setPenWidth(width: Float) {
        val clamped = width.coerceIn(1f, 160f)
        val activeIdx = _uiState.value.activeBrushSlotIndex
        if (activeIdx in brushSlots.indices) {
            brushSlots[activeIdx] = brushSlots[activeIdx].copy(strokeWidth = clamped)
        }
        _uiState.update { it.copy(penWidth = clamped) }
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

    fun togglePaintPixelMode() {
        _uiState.update { it.copy(isPaintPixelMode = !it.isPaintPixelMode) }
    }

    fun syncWorkspaceBoards(boards: List<WhiteboardBoard>) {
        applyCanonicalBoardsFromDb(boards)
    }
}
