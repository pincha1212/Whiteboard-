package com.example.ui.canvas

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ink.StrokeStabilizer
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.ui.toolbar.WhiteboardPenDock
import com.example.ui.toolbar.WhiteboardTopBar
import com.example.viewmodel.WhiteboardViewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private enum class CanvasGestureMode {
    NONE,
    DRAW_STROKE,
    PLACE_SHAPE,
    PAN,
    MOVE_SHAPE,
    RESIZE_SHAPE,
    MOVE_STROKE,
    RESIZE_STROKE,
    LASSO_SELECT,
    ERASE_OBJECT,
    ERASE_PIXEL,
    LASER
}

@Composable
fun WhiteboardCanvasScreen(
    viewModel: WhiteboardViewModel,
    onSwitchToNotes: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showShapePicker by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var showBackgroundSettings by remember { mutableStateOf(false) }
    var showBoardsManager by remember { mutableStateOf(false) }
    var showSnapshotsModal by remember { mutableStateOf(false) }
    var showExportModal by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newTitleInput by remember { mutableStateOf(state.board.title) }
    var canvasSizePx by remember { mutableStateOf(IntSize(1080, 1920)) }

    var pendingDragStart by remember { mutableStateOf<StrokePoint?>(null) }
    var pendingDragEnd by remember { mutableStateOf<StrokePoint?>(null) }
    var pixelEraserCursorWorld by remember { mutableStateOf<StrokePoint?>(null) }

    val localActivePoints = remember { mutableStateListOf<StrokePoint>() }
    val localLassoPoints = remember { mutableStateListOf<StrokePoint>() }

    var isDrawingGestureActive by remember { mutableStateOf(false) }
    var lastDrawMoveMs by remember { mutableLongStateOf(0L) }
    var snappedDuringHold by remember { mutableStateOf(false) }

    // Hold-still Snap-to-shape (~480ms at stroke end converts crooked circles/lines into clean geometry)
    LaunchedEffect(isDrawingGestureActive, lastDrawMoveMs) {
        if (isDrawingGestureActive && !snappedDuringHold) {
            delay(480L)
            val tool = viewModel.uiState.value.currentTool
            val isSnapEligible = tool == ToolType.PEN || tool == ToolType.PENCIL || tool == ToolType.FINE_MARKER
            if (isDrawingGestureActive && isSnapEligible && localActivePoints.size >= 6) {
                val snapped = viewModel.commitSnapToShapeFromPoints(localActivePoints.toList())
                if (snapped) {
                    snappedDuringHold = true
                    localActivePoints.clear()
                }
            }
        }
    }

    val canvasBg = try {
        Color(android.graphics.Color.parseColor(state.board.colorTheme.hex))
    } catch (_: Exception) {
        Color.White
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBg)
            .onSizeChanged { canvasSizePx = it }
            .testTag("whiteboard_screen")
    ) {
        val screenWidthPx = canvasSizePx.width.toFloat().coerceAtLeast(1f)
        val screenHeightPx = canvasSizePx.height.toFloat().coerceAtLeast(1f)

        // Main Drawing Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("infinite_canvas")
                .pointerInput(state.currentTool) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var isMultiTouch = false
                        var gestureMode = CanvasGestureMode.NONE
                        var activeHandleIndex = -1
                        var hasTransformed = false
                        snappedDuringHold = false
                        localLassoPoints.clear()
                        pixelEraserCursorWorld = null

                        val curState = viewModel.uiState.value
                        val wX = (down.position.x - curState.panX) / curState.scale
                        val wY = (down.position.y - curState.panY) / curState.scale
                        val handleTolerance = 32f / curState.scale
                        val hitTolerance = 22f / curState.scale

                        val isDrawingTool = curState.currentTool == ToolType.PEN ||
                            curState.currentTool == ToolType.PENCIL ||
                            curState.currentTool == ToolType.FINE_MARKER ||
                            curState.currentTool == ToolType.HIGHLIGHTER ||
                            curState.currentTool == ToolType.BRUSH

                        val activeShape = curState.board.shapes.find { it.id == curState.selectedShapeId }
                        val activeStroke = curState.board.strokes.find { it.id == curState.selectedStrokeId }

                        val hitSelectedShape = curState.board.shapes.any { s ->
                            (s.id == curState.selectedShapeId || curState.selectedShapeIds.contains(s.id)) &&
                            isPointHittingShape(s, wX, wY, hitTolerance)
                        }
                        val hitSelectedStroke = curState.board.strokes.any { s ->
                            (s.id == curState.selectedStrokeId || curState.selectedStrokeIds.contains(s.id)) &&
                            s.points.any { p -> hypot(p.x - wX, p.y - wY) < hitTolerance }
                        }
                        val hitSelectedNote = curState.board.stickyNotes.any { n ->
                            (n.id == curState.selectedNoteId || curState.selectedNoteIds.contains(n.id)) &&
                            wX >= n.x && wX <= (n.x + n.width) && wY >= n.y && wY <= (n.y + n.height)
                        }

                        if (curState.pendingStickyNoteColor != null) {
                            viewModel.placePendingAt(wX, wY)
                        } else if (curState.pendingShapeType != null) {
                            val startPt = StrokePoint(wX, wY)
                            pendingDragStart = startPt
                            pendingDragEnd = startPt
                            gestureMode = CanvasGestureMode.PLACE_SHAPE
                        } else if (activeShape != null && getShapeHandleHitIndex(activeShape, wX, wY, handleTolerance) != -1) {
                            activeHandleIndex = getShapeHandleHitIndex(activeShape, wX, wY, handleTolerance)
                            gestureMode = CanvasGestureMode.RESIZE_SHAPE
                        } else if (activeStroke != null && getStrokeHandleHitIndex(activeStroke, wX, wY, handleTolerance) != -1) {
                            activeHandleIndex = getStrokeHandleHitIndex(activeStroke, wX, wY, handleTolerance)
                            gestureMode = CanvasGestureMode.RESIZE_STROKE
                        } else if ((curState.currentTool == ToolType.SELECT_LASSO || curState.currentTool == ToolType.HAND) &&
                            (hitSelectedShape || hitSelectedStroke || hitSelectedNote)
                        ) {
                            gestureMode = CanvasGestureMode.MOVE_SHAPE
                        } else if (activeShape != null &&
                            (curState.currentTool == ToolType.SELECT_LASSO || curState.currentTool == ToolType.HAND) &&
                            isPointHittingShape(activeShape, wX, wY, hitTolerance)
                        ) {
                            gestureMode = CanvasGestureMode.MOVE_SHAPE
                        } else if (activeStroke != null &&
                            (curState.currentTool == ToolType.SELECT_LASSO || curState.currentTool == ToolType.HAND) &&
                            activeStroke.points.any { p -> hypot(p.x - wX, p.y - wY) < hitTolerance }
                        ) {
                            gestureMode = CanvasGestureMode.MOVE_STROKE
                        } else if (isDrawingTool) {
                            localActivePoints.clear()
                            val startPt = if (curState.isRulerActive) {
                                StrokeStabilizer.projectPointToRulerIfNear(StrokePoint(wX, wY), curState.rulerState)
                            } else {
                                StrokePoint(wX, wY)
                            }
                            localActivePoints.add(startPt)
                            isDrawingGestureActive = true
                            lastDrawMoveMs = System.currentTimeMillis()
                            gestureMode = CanvasGestureMode.DRAW_STROKE
                        } else if (curState.currentTool == ToolType.LASER_POINTER) {
                            viewModel.addLaserPoint(down.position.x, down.position.y)
                            gestureMode = CanvasGestureMode.LASER
                        } else if (curState.currentTool == ToolType.ERASER) {
                            val eraseTol = 24f / curState.scale
                            viewModel.eraseObjectAt(wX, wY, eraseTol, saveSnapshot = true)
                            gestureMode = CanvasGestureMode.ERASE_OBJECT
                        } else if (curState.currentTool == ToolType.PIXEL_ERASER) {
                            val eraseRadius = (curState.penWidth * 2.2f).coerceIn(14f, 36f) / curState.scale
                            pixelEraserCursorWorld = StrokePoint(wX, wY)
                            if (viewModel.erasePixelAt(wX, wY, eraseRadius, saveSnapshot = true)) {
                                hasTransformed = true
                            }
                            gestureMode = CanvasGestureMode.ERASE_PIXEL
                        } else {
                            // Hit test topmost shapes, notes, strokes
                            val hitShape = curState.board.shapes.lastOrNull { s ->
                                isPointHittingShape(s, wX, wY, hitTolerance)
                            }
                            val hitNote = curState.board.stickyNotes.lastOrNull { n ->
                                wX >= n.x && wX <= (n.x + n.width) && wY >= n.y && wY <= (n.y + n.height)
                            }
                            val hitStroke = curState.board.strokes.lastOrNull { s ->
                                s.points.any { p -> hypot(p.x - wX, p.y - wY) < (24f / curState.scale) }
                            }

                            if (hitShape != null) {
                                viewModel.selectShape(hitShape.id)
                                gestureMode = CanvasGestureMode.MOVE_SHAPE
                            } else if (hitNote != null) {
                                viewModel.selectNote(hitNote.id)
                            } else if (hitStroke != null) {
                                viewModel.selectStroke(hitStroke.id)
                                gestureMode = CanvasGestureMode.MOVE_STROKE
                            } else if (curState.currentTool == ToolType.SELECT_LASSO) {
                                localLassoPoints.add(StrokePoint(wX, wY))
                                gestureMode = CanvasGestureMode.LASSO_SELECT
                            } else if (curState.currentTool == ToolType.HAND) {
                                viewModel.clearSelection()
                                gestureMode = CanvasGestureMode.PAN
                            } else {
                                viewModel.clearSelection()
                            }
                        }

                        do {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }

                            if (activePointers.size >= 2) {
                                isMultiTouch = true
                                isDrawingGestureActive = false
                                localActivePoints.clear()
                                localLassoPoints.clear()
                                pixelEraserCursorWorld = null

                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                viewModel.onPanZoom(pan.x, pan.y, zoom)

                                event.changes.forEach { it.consume() }
                            } else if (activePointers.size == 1 && !isMultiTouch) {
                                val change = activePointers.first()
                                val liveState = viewModel.uiState.value

                                when (gestureMode) {
                                    CanvasGestureMode.PLACE_SHAPE -> {
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        pendingDragEnd = StrokePoint(nwX, nwY)
                                        change.consume()
                                    }
                                    CanvasGestureMode.RESIZE_SHAPE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        viewModel.resizeSelectedShapeHandle(activeHandleIndex, nwX, nwY)
                                        change.consume()
                                    }
                                    CanvasGestureMode.MOVE_SHAPE -> {
                                        val pan = event.calculatePan()
                                        if (pan.x != 0f || pan.y != 0f) {
                                            if (!hasTransformed) {
                                                viewModel.beginTransformSnapshot()
                                                hasTransformed = true
                                            }
                                            viewModel.moveSelectedShape(pan.x / liveState.scale, pan.y / liveState.scale)
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.RESIZE_STROKE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        viewModel.resizeSelectedStrokeBounds(activeHandleIndex, nwX, nwY)
                                        change.consume()
                                    }
                                    CanvasGestureMode.MOVE_STROKE -> {
                                        val pan = event.calculatePan()
                                        if (pan.x != 0f || pan.y != 0f) {
                                            if (!hasTransformed) {
                                                viewModel.beginTransformSnapshot()
                                                hasTransformed = true
                                            }
                                            viewModel.moveSelectedStroke(pan.x / liveState.scale, pan.y / liveState.scale)
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.LASSO_SELECT -> {
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        val last = localLassoPoints.lastOrNull()
                                        if (last == null || hypot(nwX - last.x, nwY - last.y) >= 4f / liveState.scale) {
                                            localLassoPoints.add(StrokePoint(nwX, nwY))
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.PAN -> {
                                        val pan = event.calculatePan()
                                        viewModel.onPanZoom(pan.x, pan.y, 1.0f)
                                        change.consume()
                                    }
                                    CanvasGestureMode.DRAW_STROKE -> {
                                        if (!snappedDuringHold) {
                                            val nwX = (change.position.x - liveState.panX) / liveState.scale
                                            val nwY = (change.position.y - liveState.panY) / liveState.scale
                                            val rawPt = StrokePoint(nwX, nwY)
                                            val pt = if (liveState.isRulerActive) {
                                                StrokeStabilizer.projectPointToRulerIfNear(rawPt, liveState.rulerState)
                                            } else {
                                                rawPt
                                            }
                                            val prevPt = localActivePoints.lastOrNull()
                                            if (prevPt == null || hypot(pt.x - prevPt.x, pt.y - prevPt.y) >= 2.2f / liveState.scale) {
                                                localActivePoints.add(pt)
                                                lastDrawMoveMs = System.currentTimeMillis()
                                            }
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.LASER -> {
                                        viewModel.addLaserPoint(change.position.x, change.position.y)
                                        change.consume()
                                    }
                                    CanvasGestureMode.ERASE_OBJECT -> {
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        val eraseTolerance = 24f / liveState.scale
                                        viewModel.eraseObjectAt(nwX, nwY, eraseTolerance, saveSnapshot = true)
                                        change.consume()
                                    }
                                    CanvasGestureMode.ERASE_PIXEL -> {
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        val eraseRadius = (liveState.penWidth * 2.2f).coerceIn(14f, 36f) / liveState.scale
                                        pixelEraserCursorWorld = StrokePoint(nwX, nwY)
                                        if (viewModel.erasePixelAt(nwX, nwY, eraseRadius, saveSnapshot = !hasTransformed)) {
                                            hasTransformed = true
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.NONE -> {}
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        isDrawingGestureActive = false
                        pixelEraserCursorWorld = null

                        if (gestureMode == CanvasGestureMode.PLACE_SHAPE && !isMultiTouch) {
                            val s = pendingDragStart
                            val e = pendingDragEnd
                            if (s != null && e != null) {
                                viewModel.placePendingShapeInBounds(s, e)
                            }
                            pendingDragStart = null
                            pendingDragEnd = null
                        } else if (gestureMode == CanvasGestureMode.LASSO_SELECT && !isMultiTouch) {
                            if (localLassoPoints.size >= 5) {
                                viewModel.selectByLasso(localLassoPoints.toList())
                            } else {
                                viewModel.clearSelection()
                            }
                        } else if (gestureMode == CanvasGestureMode.DRAW_STROKE && !snappedDuringHold && localActivePoints.size >= 2 && !isMultiTouch) {
                            val pointsCopy = localActivePoints.toList()
                            viewModel.commitCompletedStroke(pointsCopy, state.currentTool == ToolType.HIGHLIGHTER)
                        }
                        snappedDuringHold = false
                        pendingDragStart = null
                        pendingDragEnd = null
                        localActivePoints.clear()
                        localLassoPoints.clear()
                    }
                }
        ) {
            drawCanvasBackgroundAndGrid(
                gridType = state.board.gridType,
                colorTheme = state.board.colorTheme,
                panX = state.panX,
                panY = state.panY,
                scale = state.scale,
                screenW = size.width,
                screenH = size.height
            )

            val isDarkBoard = state.board.colorTheme.isDark
            state.board.strokes.forEach { stroke ->
                val isSelected = stroke.id == state.selectedStrokeId || state.selectedStrokeIds.contains(stroke.id)
                drawStrokeVector(
                    stroke = stroke,
                    panX = state.panX,
                    panY = state.panY,
                    scale = state.scale,
                    isSelected = isSelected,
                    isDarkBoard = isDarkBoard
                )
            }

            if (localActivePoints.size >= 2) {
                val activeBrush = viewModel.brushTypeForTool(state.currentTool)
                val previewPoints = StrokeStabilizer.stabilizePoints(localActivePoints.toList(), activeBrush.stabilization)
                val tempStroke = WhiteboardStroke(
                    points = previewPoints,
                    colorHex = viewModel.effectiveColorForBrush(activeBrush, state.penColorHex),
                    width = viewModel.effectiveStrokeWidthForBrush(activeBrush, state.penWidth),
                    isHighlighter = activeBrush == StrokeBrushType.HIGHLIGHTER,
                    isRainbow = state.isRainbowPen && activeBrush != StrokeBrushType.HIGHLIGHTER && activeBrush != StrokeBrushType.SOFT_BRUSH,
                    strokeStyle = state.penStyle,
                    brushType = activeBrush,
                    opacity = activeBrush.defaultOpacity
                )
                drawStrokeVector(
                    stroke = tempStroke,
                    panX = state.panX,
                    panY = state.panY,
                    scale = state.scale,
                    isDarkBoard = isDarkBoard
                )
            }

            if (localLassoPoints.size >= 2) {
                val lassoPath = Path().apply {
                    val p0 = localLassoPoints.first()
                    moveTo(p0.x * state.scale + state.panX, p0.y * state.scale + state.panY)
                    for (i in 1 until localLassoPoints.size) {
                        val pt = localLassoPoints[i]
                        lineTo(pt.x * state.scale + state.panX, pt.y * state.scale + state.panY)
                    }
                }
                drawPath(
                    path = lassoPath,
                    color = Color(0xFF2563EB),
                    style = Stroke(
                        width = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                )
            }

            pixelEraserCursorWorld?.let { cursor ->
                val screenRadius = (state.penWidth * 2.2f).coerceIn(14f, 36f)
                val center = Offset(cursor.x * state.scale + state.panX, cursor.y * state.scale + state.panY)
                drawCircle(
                    color = Color(0xFFEF4444).copy(alpha = 0.14f),
                    radius = screenRadius,
                    center = center
                )
                drawCircle(
                    color = Color(0xFFEF4444),
                    radius = screenRadius,
                    center = center,
                    style = Stroke(width = 2f)
                )
            }

            state.board.shapes.forEach { shape ->
                val isSelected = shape.id == state.selectedShapeId || state.selectedShapeIds.contains(shape.id)
                drawShapeVector(
                    shape = shape,
                    panX = state.panX,
                    panY = state.panY,
                    scale = state.scale,
                    isSelected = isSelected
                )
            }

            if (state.pendingShapeType != null && pendingDragStart != null && pendingDragEnd != null) {
                val pStart = pendingDragStart!!
                val pEnd = pendingDragEnd!!
                val minX = kotlin.math.min(pStart.x, pEnd.x)
                val minY = kotlin.math.min(pStart.y, pEnd.y)
                val previewShape = ShapeItem(
                    shapeType = state.pendingShapeType!!,
                    x = minX,
                    y = minY,
                    width = kotlin.math.abs(pEnd.x - pStart.x).coerceAtLeast(12f),
                    height = kotlin.math.abs(pEnd.y - pStart.y).coerceAtLeast(12f),
                    strokeColorHex = state.pendingStrokeColor,
                    fillColorHex = state.pendingFillColor,
                    strokeWidth = state.penWidth.coerceIn(2f, 16f),
                    isFilled = state.pendingIsFilled && state.pendingShapeType != ShapeType.LINE,
                    strokeStyle = state.pendingStrokeStyle,
                    vertices = if (state.pendingShapeType == ShapeType.LINE) listOf(pStart, pEnd) else emptyList()
                )
                drawShapeVector(
                    shape = previewShape,
                    panX = state.panX,
                    panY = state.panY,
                    scale = state.scale,
                    isSelected = true
                )
            }

            if (state.selectedStrokeId != null && state.selectedStrokeIds.size <= 1) {
                val selStroke = state.board.strokes.find { it.id == state.selectedStrokeId }
                if (selStroke != null) {
                    drawStrokeSelectionHandles(
                        stroke = selStroke,
                        panX = state.panX,
                        panY = state.panY,
                        scale = state.scale
                    )
                }
            }

            drawLaserTrail(state.laserTrail)
        }

        // Render Sticky Notes
        state.board.stickyNotes.forEach { note ->
            val isSelected = note.id == state.selectedNoteId || state.selectedNoteIds.contains(note.id)
            StickyNoteView(
                note = note,
                scale = state.scale,
                panX = state.panX,
                panY = state.panY,
                isSelected = isSelected,
                onUpdateText = { viewModel.updateStickyNoteText(note.id, it) },
                onUpdateColor = { viewModel.updateStickyNoteColor(note.id, it) },
                onTogglePin = { viewModel.toggleStickyNotePin(note.id) },
                onDelete = { viewModel.deleteStickyNote(note.id) },
                onDragDelta = { dx, dy ->
                    if (state.selectedNoteIds.contains(note.id) && (state.selectedNoteIds.size + state.selectedStrokeIds.size + state.selectedShapeIds.size) > 1) {
                        viewModel.moveSelectedObjects(dx, dy)
                    } else {
                        viewModel.moveStickyNote(note.id, dx, dy)
                    }
                }
            )
        }

        // Interactive Ruler below handles this cleanly; old antiquated selection banner removed in favor of bottom SelectionEditDock

        // Interactive Ruler
        InteractiveRulerView(
            rulerState = state.rulerState,
            scale = state.scale,
            panX = state.panX,
            panY = state.panY,
            onMove = { dx, dy -> viewModel.moveRuler(dx, dy) },
            onRotate = { da -> viewModel.rotateRuler(da) }
        )

        // Top Bar
        if (!state.isZenMode) {
            WhiteboardTopBar(
                boardTitle = state.board.title,
                scale = state.scale,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                isSnapToGrid = state.isSnapToGrid,
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onZoomIn = { viewModel.zoomIn() },
                onZoomOut = { viewModel.zoomOut() },
                onResetZoom = { viewModel.resetZoom() },
                onFitToContent = { viewModel.fitToContent(screenWidthPx, screenHeightPx) },
                onSetZoomPreset = { viewModel.setZoomPreset(it) },
                onToggleSnapToGrid = { viewModel.toggleSnapToGrid() },
                onEnterZenMode = { viewModel.toggleZenMode() },
                onOpenBoardsManager = { showBoardsManager = true },
                onOpenBackgroundSettings = { showBackgroundSettings = true },
                onOpenSnapshotModal = { showSnapshotsModal = true },
                onClearBoard = { viewModel.clearBoard() },
                onExportBoard = { showExportModal = true },
                onRenameBoard = {
                    newTitleInput = state.board.title
                    showRenameDialog = true
                },
                onBackToHome = { viewModel.closeBoardToHome() },
                onSwitchToNotes = onSwitchToNotes,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // Bottom Dock: Versatile Precision Editor when an item is selected, otherwise Pen Dock
        if (!state.isZenMode) {
            val totalSelectedCount = state.selectedStrokeIds.size + state.selectedShapeIds.size + state.selectedNoteIds.size
            val hasSelection = totalSelectedCount > 0 || state.selectedShapeId != null || state.selectedStrokeId != null || state.selectedNoteId != null
            val selectedShape = state.board.shapes.find { it.id == state.selectedShapeId }
            val selectedStroke = state.board.strokes.find { it.id == state.selectedStrokeId }
            val selectedNote = state.board.stickyNotes.find { it.id == state.selectedNoteId }

            if (hasSelection) {
                SelectionEditDock(
                    selectedShape = selectedShape,
                    selectedStroke = selectedStroke,
                    selectedNote = selectedNote,
                    totalSelectedCount = totalSelectedCount.coerceAtLeast(1),
                    onDeleteAll = { viewModel.deleteCurrentSelection() },
                    onDuplicateAll = {
                        viewModel.duplicateSelectedStroke()
                        viewModel.duplicateSelectedShape()
                    },
                    onScaleAll = { factor ->
                        viewModel.scaleSelectedStroke(factor)
                        viewModel.scaleSelectedShape(factor)
                    },
                    onRotateAll = { delta ->
                        viewModel.rotateSelectedStroke(delta)
                        viewModel.rotateSelectedShape(delta)
                    },
                    onUpdateAllColor = { col ->
                        viewModel.updateSelectedStrokeColor(col)
                        viewModel.updateSelectedShape(strokeColor = col)
                    },
                    onUpdateShapeColor = { viewModel.updateSelectedShape(strokeColor = it) },
                    onUpdateShapeFillColor = { viewModel.updateSelectedShape(fillColor = it, isFilled = true) },
                    onToggleShapeFilled = {
                        val cur = selectedShape?.isFilled ?: false
                        viewModel.updateSelectedShape(isFilled = !cur)
                    },
                    onUpdateShapeWidth = { viewModel.updateSelectedShape(width = it) },
                    onUpdateShapeStyle = { viewModel.updateSelectedShape(style = it) },
                    onScaleShape = { viewModel.scaleSelectedShape(it) },
                    onRotateShape = { viewModel.rotateSelectedShape(it) },
                    onResetShapeRotation = { viewModel.resetSelectedShapeRotation() },
                    onBringShapeToFront = { viewModel.bringSelectedShapeToFront() },
                    onSendShapeToBack = { viewModel.sendSelectedShapeToBack() },
                    onDuplicateShape = { viewModel.duplicateSelectedShape() },
                    onDeleteShape = { viewModel.deleteSelectedShape() },
                    onUpdateStrokeColor = { viewModel.updateSelectedStrokeColor(it) },
                    onUpdateStrokeWidth = { viewModel.updateSelectedStrokeWidth(it) },
                    onUpdateStrokeStyle = { viewModel.updateSelectedStrokeStyle(it) },
                    onScaleStroke = { viewModel.scaleSelectedStroke(it) },
                    onRotateStroke = { viewModel.rotateSelectedStroke(it) },
                    onConvertStrokeToShape = { viewModel.convertSelectedStrokeToShape() },
                    onDuplicateStroke = { viewModel.duplicateSelectedStroke() },
                    onDeleteStroke = { viewModel.deleteSelectedStroke() },
                    onUpdateNoteColor = { hex ->
                        selectedNote?.let { viewModel.updateStickyNoteColor(it.id, hex) }
                    },
                    onToggleNotePin = {
                        selectedNote?.let { viewModel.toggleStickyNotePin(it.id) }
                    },
                    onDeleteNote = { viewModel.deleteSelectedNote() },
                    onDone = { viewModel.clearSelection() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                )
            } else {
                WhiteboardPenDock(
                    currentTool = state.currentTool,
                    currentPenColor = state.penColorHex,
                    currentPenWidth = state.penWidth,
                    currentPenStyle = state.penStyle,
                    isRainbowPen = state.isRainbowPen,
                    autoShapeRecognition = state.autoShapeRecognition,
                    isRulerActive = state.isRulerActive,
                    pendingShapeType = state.pendingShapeType,
                    onSelectTool = {
                        viewModel.cancelPendingPlacement()
                        viewModel.setTool(it)
                    },
                    onUpdatePenColor = { col, isRainbow -> viewModel.setPenColor(col, isRainbow) },
                    onUpdatePenWidth = { viewModel.setPenWidth(it) },
                    onUpdatePenStyle = { viewModel.setPenStyle(it) },
                    onToggleAutoShape = { viewModel.toggleAutoShapeRecognition() },
                    onToggleRuler = { viewModel.toggleRuler() },
                    onOpenShapePicker = { showShapePicker = true },
                    onAddStickyNote = { viewModel.requestAddStickyNote("#FFF275") },
                    onAddText = { viewModel.requestAddStickyNote("#FFF275") },
                    onOpenTemplates = { showTemplatePicker = true },
                    onClearBoard = { viewModel.clearBoard() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                )
            }
        }

        // Pending Placement Banner
        if (state.pendingStickyNoteColor != null || state.pendingShapeType != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp)
                    .testTag("pending_placement_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val desc = when {
                        state.pendingStickyNoteColor != null -> "Toca en la pizarra para colocar la nota"
                        pendingDragStart != null && pendingDragEnd != null -> {
                            val w = kotlin.math.abs(pendingDragEnd!!.x - pendingDragStart!!.x).toInt()
                            val h = kotlin.math.abs(pendingDragEnd!!.y - pendingDragStart!!.y).toInt()
                            "${state.pendingShapeType!!.displayName}: ${w}×${h} px"
                        }
                        else -> "Arrastra en la pizarra para colocar y dimensionar (${state.pendingShapeType!!.displayName})"
                    }
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(onClick = { viewModel.cancelPendingPlacement() }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancelar",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Zen Mode Exit Pill
        if (state.isZenMode) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { viewModel.toggleZenMode() }
                    .testTag("zen_mode_exit_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Salir de Enfoque",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Salir de Enfoque",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Modals
        if (showBoardsManager) {
            BoardsManagerModal(
                boards = state.allBoards,
                currentBoardId = state.board.id,
                onSelectBoard = { viewModel.switchBoard(it) },
                onCreateBoard = { viewModel.createNewBoard(it) },
                onDuplicateBoard = { viewModel.duplicateCurrentBoard(it) },
                onDeleteBoard = { viewModel.deleteBoard(it) },
                onDismiss = { showBoardsManager = false }
            )
        }

        if (showShapePicker) {
            ShapePickerBottomSheet(
                initialColorHex = state.penColorHex,
                onSelectShapeType = { shapeType, isFilled, strokeColor, fillColor, style ->
                    viewModel.requestAddCustomShape(shapeType, isFilled, strokeColor, fillColor, style)
                },
                onDismiss = { showShapePicker = false }
            )
        }

        if (showTemplatePicker) {
            TemplatePickerModal(
                onSelectTemplate = { viewModel.applyTemplate(it, screenWidthPx, screenHeightPx) },
                onDismiss = { showTemplatePicker = false }
            )
        }

        if (showBackgroundSettings) {
            BackgroundSettingsModal(
                currentGrid = state.board.gridType,
                currentColorTheme = state.board.colorTheme,
                onSelectGrid = { viewModel.setGridType(it) },
                onSelectColorTheme = { viewModel.setColorTheme(it) },
                onDismiss = { showBackgroundSettings = false }
            )
        }

        if (showSnapshotsModal) {
            SnapshotsModal(
                board = state.board,
                onCreateSnapshot = { version, desc ->
                    viewModel.createSnapshot(version, desc)
                    Toast.makeText(context, "Snapshot '$version' guardado", Toast.LENGTH_SHORT).show()
                },
                onRestoreSnapshot = { snap ->
                    val ok = viewModel.restoreSnapshot(snap)
                    if (ok) {
                        Toast.makeText(context, "Snapshot '${snap.versionName}' restaurado", Toast.LENGTH_SHORT).show()
                        showSnapshotsModal = false
                    } else {
                        Toast.makeText(context, "No se pudo restaurar este snapshot", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteSnapshot = { viewModel.deleteSnapshot(it) },
                onDismiss = { showSnapshotsModal = false }
            )
        }

        if (showExportModal) {
            ExportBoardModal(
                board = state.board,
                onDismiss = { showExportModal = false }
            )
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Renombrar Pizarra") },
                text = {
                    OutlinedTextField(
                        value = newTitleInput,
                        onValueChange = { newTitleInput = it },
                        label = { Text("Título de la pizarra") },
                        modifier = Modifier.fillMaxWidth().testTag("input_rename_board")
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTitleInput.isNotBlank()) {
                                viewModel.renameBoard(newTitleInput.trim())
                                showRenameDialog = false
                            }
                        }
                    ) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showRenameDialog = false },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

private fun toLocalUnrotated(shape: ShapeItem, wX: Float, wY: Float): Pair<Float, Float> {
    if (shape.rotation == 0f || shape.shapeType == ShapeType.LINE) return wX to wY
    val cx = shape.x + shape.width / 2f
    val cy = shape.y + shape.height / 2f
    val rad = -shape.rotation * (PI.toFloat() / 180f)
    val dx = wX - cx
    val dy = wY - cy
    val rx = cx + dx * cos(rad) - dy * sin(rad)
    val ry = cy + dx * sin(rad) + dy * cos(rad)
    return rx to ry
}

private fun getShapeHandleHitIndex(
    shape: ShapeItem,
    wX: Float,
    wY: Float,
    tolerance: Float
): Int {
    if (shape.shapeType == ShapeType.LINE && shape.vertices.size >= 2) {
        if (hypot(wX - shape.vertices[0].x, wY - shape.vertices[0].y) <= tolerance) return 0
        if (hypot(wX - shape.vertices[1].x, wY - shape.vertices[1].y) <= tolerance) return 1
        return -1
    }
    val (lx, ly) = toLocalUnrotated(shape, wX, wY)
    val corners = listOf(
        shape.x to shape.y,
        (shape.x + shape.width) to shape.y,
        shape.x to (shape.y + shape.height),
        (shape.x + shape.width) to (shape.y + shape.height)
    )
    corners.forEachIndexed { idx, (cx, cy) ->
        if (hypot(lx - cx, ly - cy) <= tolerance) return idx
    }
    return -1
}

private fun getStrokeHandleHitIndex(
    stroke: WhiteboardStroke,
    wX: Float,
    wY: Float,
    tolerance: Float
): Int {
    if (stroke.points.isEmpty()) return -1
    val minX = stroke.points.minOf { it.x }
    val maxX = stroke.points.maxOf { it.x }
    val minY = stroke.points.minOf { it.y }
    val maxY = stroke.points.maxOf { it.y }
    val corners = listOf(
        minX to minY,
        maxX to minY,
        minX to maxY,
        maxX to maxY
    )
    corners.forEachIndexed { idx, (cx, cy) ->
        if (hypot(wX - cx, wY - cy) <= tolerance) return idx
    }
    return -1
}

private fun isPointHittingShape(
    shape: ShapeItem,
    wX: Float,
    wY: Float,
    tolerance: Float
): Boolean {
    if (shape.shapeType == ShapeType.LINE && shape.vertices.size >= 2) {
        return distanceToSegment(wX, wY, shape.vertices[0], shape.vertices[1]) <= tolerance
    }
    val (lx, ly) = toLocalUnrotated(shape, wX, wY)
    if (shape.vertices.size >= 3) {
        for (i in shape.vertices.indices) {
            val a = shape.vertices[i]
            val b = shape.vertices[(i + 1) % shape.vertices.size]
            if (distanceToSegment(lx, ly, a, b) <= tolerance) return true
        }
    }
    return lx >= (shape.x - tolerance) &&
        lx <= (shape.x + shape.width + tolerance) &&
        ly >= (shape.y - tolerance) &&
        ly <= (shape.y + shape.height + tolerance)
}

private fun distanceToSegment(px: Float, py: Float, a: StrokePoint, b: StrokePoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq < 0.0001f) return hypot(px - a.x, py - a.y)
    val t = (((px - a.x) * dx + (py - a.y) * dy) / lenSq).coerceIn(0f, 1f)
    val projX = a.x + t * dx
    val projY = a.y + t * dy
    return hypot(px - projX, py - projY)
}

