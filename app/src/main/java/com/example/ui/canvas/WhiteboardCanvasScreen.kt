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
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ink.StickyNoteResizeHandle
import com.example.ink.StrokeStabilizer
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.StickyNote
import com.example.model.isLineLike
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.TextItem
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.ui.toolbar.DockLayoutMode
import com.example.ui.toolbar.WhiteboardPenDock
import com.example.ui.toolbar.WhiteboardTopBar
import com.example.viewmodel.WhiteboardViewModel
import com.example.viewmodel.WhiteboardUiState
import com.example.ink.ViewportTransform
import com.example.ink.engine.PaintTileRasterCache
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class CanvasGestureMode {
    NONE,
    DRAW_STROKE,
    PAN,
    MOVE_SHAPE,
    RESIZE_SHAPE,
    ROTATE_SHAPE,
    MOVE_STROKE,
    RESIZE_STROKE,
    ROTATE_STROKE,
    LASSO_SELECT,
    ERASE_OBJECT,
    ERASE_PIXEL,
    LASER
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WhiteboardCanvasScreen(
    viewModel: WhiteboardViewModel,
    onSwitchToNotes: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showBackgroundSettings by remember { mutableStateOf(false) }
    var showBoardsManager by remember { mutableStateOf(false) }
    var showExportModal by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSnapshotsModal by remember { mutableStateOf(false) }
    var editingLabelShape by remember { mutableStateOf<ShapeItem?>(null) }
    var editingLabelText by remember { mutableStateOf("") }
    var notePendingRemoval by remember { mutableStateOf<StickyNote?>(null) }
    var newTitleInput by remember { mutableStateOf(state.board.title) }
    var canvasSizePx by remember { mutableStateOf(IntSize(1080, 1920)) }
    val density = LocalDensity.current


    var pixelEraserCursorWorld by remember { mutableStateOf<StrokePoint?>(null) }
    var activeRotationHud by remember { mutableStateOf<Pair<Offset, String>?>(null) }

    val localActivePoints = remember { mutableStateListOf<StrokePoint>() }
    val localLassoPoints = remember { mutableStateListOf<StrokePoint>() }

    // Keep one preview stroke wrapper while its backing point list changes in place.
    // The live renderer reads the current points directly and does not cache its geometry.
    val activePreviewBrush = viewModel.brushTypeForTool(state.currentTool)
    val livePreviewStroke = remember(
        localActivePoints,
        state.currentTool,
        state.penColorHex,
        state.penWidth,
        state.isRainbowPen,
    ) {
        WhiteboardStroke(
            id = "live_active_stroke",
            points = localActivePoints,
            colorHex = viewModel.effectiveColorForBrush(activePreviewBrush, state.penColorHex),
            width = viewModel.effectiveStrokeWidthForBrush(activePreviewBrush, state.penWidth),
            isHighlighter = activePreviewBrush == StrokeBrushType.HIGHLIGHTER,
            isRainbow = state.isRainbowPen && activePreviewBrush != StrokeBrushType.HIGHLIGHTER,
            strokeStyle = StrokeStyle.SOLID,
            capStyle = LineCapStyle.NONE,
            brushType = activePreviewBrush,
            opacity = activePreviewBrush.defaultOpacity
        )
    }

    var isDrawingGestureActive by remember { mutableStateOf(false) }

    val canvasBg = try {
        Color(android.graphics.Color.parseColor(state.board.colorTheme.hex))
    } catch (_: Exception) {
        Color.White
    }

    val isPanOnlyMode = state.currentTool == ToolType.HAND &&
        state.pendingStickyNoteColor == null

    // Cache conservative world-space bounds by immutable shape-list identity.
    // Camera movement can then cull off-screen shapes without rescanning vertices.
    val cachedShapeBounds = remember(state.board.shapes) {
        state.board.shapes.map { shape ->
            val centerX = shape.x + shape.width / 2f
            val centerY = shape.y + shape.height / 2f
            val rectRadius = hypot(shape.width, shape.height) / 2f
            val vertexRadius = shape.vertices.maxOfOrNull { vertex ->
                hypot(vertex.x - centerX, vertex.y - centerY)
            } ?: 0f
            CachedShapeBounds(
                shape = shape,
                centerX = centerX,
                centerY = centerY,
                radius = maxOf(rectRadius, vertexRadius) + shape.strokeWidth * 2f
            )
        }
    }

    // Resolve selected objects only when content or selection changes, not on every camera frame.
    val selectedShapeForLabel = remember(state.board.shapes, state.selectedShapeId) {
        state.board.shapes.firstOrNull {
            it.id == state.selectedShapeId && !it.isHiddenByCollapse && !it.shapeType.isLineLike
        }
    }
    val selectedStrokeForHandles = remember(state.board.strokes, state.selectedStrokeId) {
        state.board.strokes.firstOrNull { it.id == state.selectedStrokeId }
    }

    // Selection membership is queried for every visible object on each canvas frame.
    // Keep hash sets keyed to selection-list identity to avoid repeated linear scans.
    val selectedStrokeIdSet = remember(state.selectedStrokeIds) { state.selectedStrokeIds.toHashSet() }
    val selectedShapeIdSet = remember(state.selectedShapeIds) { state.selectedShapeIds.toHashSet() }

    val isDarkBoard = state.board.colorTheme.isDark
    val paintTileCache = remember { PaintTileRasterCache() }
    DisposableEffect(Unit) {
        onDispose { paintTileCache.clear() }
    }
    val paintSpatialIndex = remember(
        state.isPaintPixelMode,
        state.board.id,
        state.board.strokes,
        state.board.shapes,
        state.selectedStrokeId,
        selectedStrokeIdSet,
        state.selectedShapeId,
        selectedShapeIdSet,
        isDarkBoard,
        state.handDrawnFactor
    ) {
        if (state.isPaintPixelMode) {
            paintTileCache.buildSpatialIndex(
                boardId = state.board.id,
                strokes = state.board.strokes,
                shapes = state.board.shapes,
                selectedStrokeId = state.selectedStrokeId,
                selectedStrokeIds = selectedStrokeIdSet,
                selectedShapeId = state.selectedShapeId,
                selectedShapeIds = selectedShapeIdSet,
                isDarkBoard = isDarkBoard,
                handDrawnFactor = state.handDrawnFactor
            )
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBg)
            .onSizeChanged { newSize ->
                canvasSizePx = newSize
                viewModel.onViewportSizeChanged(newSize.width.toFloat(), newSize.height.toFloat())
            }
            .testTag("whiteboard_screen")
    ) {
        val screenWidthPx = canvasSizePx.width.toFloat().coerceAtLeast(1f)
        val screenHeightPx = canvasSizePx.height.toFloat().coerceAtLeast(1f)

        CameraStateHost(viewModel) { cameraState ->
        // Global Camera / Viewport Transform Layer (handles 2-finger pinch-to-zoom & pan over both canvas and sticky notes)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput("global_viewport_multitouch") {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // Count pressed pointers without allocating a filtered list on every event.
                            var activePointerCount = 0
                            for (change in event.changes) {
                                if (change.pressed) activePointerCount++
                            }
                            if (activePointerCount >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val fx = if (centroid != Offset.Unspecified) centroid.x else null
                                val fy = if (centroid != Offset.Unspecified) centroid.y else null
                                viewModel.onPanZoom(pan.x, pan.y, zoom, fx, fy)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            // Main Drawing Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("infinite_canvas")
                    .pointerInput(state.currentTool) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                        var isMultiTouch = false
                        var gestureMode = CanvasGestureMode.NONE
                        var activeHandleIndex = -1
                        var hasTransformed = false
                        localLassoPoints.clear()
                        pixelEraserCursorWorld = null

                        val curState = viewModel.uiState.value
                        val curCamera = viewModel.cameraState.value
                        val wX = (down.position.x - curCamera.panX) / curCamera.scale
                        val wY = (down.position.y - curCamera.panY) / curCamera.scale
                        val handleTolerance = with(density) { 24.dp.toPx() } / curCamera.scale
                        val rotHitTolerance = with(density) { 28.dp.toPx() } / curCamera.scale
                        val hitTolerance = with(density) { 18.dp.toPx() } / curCamera.scale

                        val isDrawingTool = curState.currentTool == ToolType.PEN ||
                            curState.currentTool == ToolType.FINE_MARKER ||
                            curState.currentTool == ToolType.HIGHLIGHTER

                        val activeShape = curState.board.shapes.find { it.id == curState.selectedShapeId && !it.isLocked && !it.isHiddenByCollapse }
                        val activeStroke = curState.board.strokes.find { it.id == curState.selectedStrokeId }
                        var strokeRotStartDeg = 0f
                        var strokeRotCenterWorld: Offset? = null
                        if (activeStroke != null && activeStroke.points.isNotEmpty()) {
                            val minX = activeStroke.points.minOf { it.x }
                            val maxX = activeStroke.points.maxOf { it.x }
                            val minY = activeStroke.points.minOf { it.y }
                            val maxY = activeStroke.points.maxOf { it.y }
                            strokeRotCenterWorld = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
                        }

                        val selectedShapeIds = curState.selectedShapeIds.toHashSet()
                        val selectedStrokeIds = curState.selectedStrokeIds.toHashSet()
                        val hitSelectedShape = curState.board.shapes.any { s ->
                            !s.isLocked && !s.isHiddenByCollapse &&
                            (s.id == curState.selectedShapeId || selectedShapeIds.contains(s.id)) &&
                            isPointHittingShape(s, wX, wY, hitTolerance)
                        }
                        val hitSelectedStroke = curState.board.strokes.any { s ->
                            (s.id == curState.selectedStrokeId || selectedStrokeIds.contains(s.id)) &&
                            isPointHittingStroke(s, wX, wY, hitTolerance)
                        }

                        if (curState.pendingStickyNoteColor != null) {
                            viewModel.placePendingAt(wX, wY)
                        } else if (curState.currentTool == ToolType.HAND) {
                            // Standalone Hand button: strictly pan-only, disable any accidental object selection/drag
                            gestureMode = CanvasGestureMode.PAN
                        } else if (activeShape != null && getShapeHandleHitIndex(activeShape, wX, wY, handleTolerance, rotHitTolerance, curCamera.scale, density).also { activeHandleIndex = it } != -1) {
                            gestureMode = if (activeHandleIndex == 4) CanvasGestureMode.ROTATE_SHAPE else CanvasGestureMode.RESIZE_SHAPE
                        } else if (activeStroke != null && getStrokeHandleHitIndex(activeStroke, wX, wY, handleTolerance, rotHitTolerance, curCamera.scale, density).also { activeHandleIndex = it } != -1) {
                            gestureMode = if (activeHandleIndex == 4) {
                                viewModel.resetStrokeDragAngle()
                                strokeRotCenterWorld?.let { c ->
                                    strokeRotStartDeg = (kotlin.math.atan2((wY - c.y).toDouble(), (wX - c.x).toDouble()) * 180.0 / PI).toFloat()
                                }
                                CanvasGestureMode.ROTATE_STROKE
                            } else {
                                CanvasGestureMode.RESIZE_STROKE
                            }
                        } else if (curState.currentTool == ToolType.SELECT_LASSO &&
                            (hitSelectedShape || hitSelectedStroke)
                        ) {
                            gestureMode = CanvasGestureMode.MOVE_SHAPE
                        } else if (activeShape != null &&
                            curState.currentTool == ToolType.SELECT_LASSO &&
                            isPointHittingShape(activeShape, wX, wY, hitTolerance)
                        ) {
                            gestureMode = CanvasGestureMode.MOVE_SHAPE
                        } else if (activeStroke != null &&
                            curState.currentTool == ToolType.SELECT_LASSO &&
                            isPointHittingStrokeOrBounds(activeStroke, wX, wY, hitTolerance)
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
                            gestureMode = CanvasGestureMode.DRAW_STROKE
                        } else if (curState.currentTool == ToolType.LASER_POINTER) {
                            viewModel.addLaserPoint(down.position.x, down.position.y)
                            gestureMode = CanvasGestureMode.LASER
                        } else if (curState.currentTool == ToolType.ERASER) {
                            val eraseTol = 24f / curCamera.scale
                            viewModel.eraseObjectAt(wX, wY, eraseTol, saveSnapshot = true)
                            gestureMode = CanvasGestureMode.ERASE_OBJECT
                        } else if (curState.currentTool == ToolType.PIXEL_ERASER) {
                            val eraseRadius = (curState.penWidth * 2.2f).coerceIn(14f, 36f) / curCamera.scale
                            pixelEraserCursorWorld = StrokePoint(wX, wY)
                            if (viewModel.erasePixelAt(wX, wY, eraseRadius, saveSnapshot = true)) {
                                hasTransformed = true
                            }
                            gestureMode = CanvasGestureMode.ERASE_PIXEL
                        } else {
                            // Hit test topmost unlocked & visible shapes and strokes
                            val hitShape = curState.board.shapes.lastOrNull { s ->
                                !s.isLocked && !s.isHiddenByCollapse &&
                                isPointHittingShape(s, wX, wY, hitTolerance)
                            }
                            val hitStroke = curState.board.strokes.lastOrNull { s ->
                                isPointHittingStroke(s, wX, wY, (24f / curCamera.scale))
                            }

                            if (hitShape != null) {
                                viewModel.selectShape(hitShape.id)
                                gestureMode = CanvasGestureMode.MOVE_SHAPE
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

                        var lastDrawPointerPosition = down.position
                        do {
                            val event = awaitPointerEvent()
                            val drawPointerChange = event.changes.firstOrNull { it.id == down.id }
                            if (gestureMode == CanvasGestureMode.DRAW_STROKE && drawPointerChange != null) {
                                lastDrawPointerPosition = drawPointerChange.position
                            }
                            // Avoid allocating a new List<PointerInputChange> for every move event.
                            var activePointerCount = 0
                            var activePointer = null as androidx.compose.ui.input.pointer.PointerInputChange?
                            for (change in event.changes) {
                                if (change.pressed) {
                                    activePointerCount++
                                    if (activePointerCount == 1) activePointer = change
                                }
                            }

                            if (activePointerCount >= 2) {
                                isMultiTouch = true
                                isDrawingGestureActive = false
                                localActivePoints.clear()
                                localLassoPoints.clear()
                                pixelEraserCursorWorld = null
                            } else if (activePointerCount == 1 && !isMultiTouch) {
                                val change = activePointer!!
                                val liveState = viewModel.uiState.value
                                val liveCamera = viewModel.cameraState.value

                                when (gestureMode) {
                                    CanvasGestureMode.RESIZE_SHAPE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        viewModel.resizeSelectedShapeHandle(activeHandleIndex, nwX, nwY)
                                        if (activeHandleIndex == 2 || activeHandleIndex == 3) {
                                            val updatedShape = viewModel.uiState.value.board.shapes.find { it.id == liveState.selectedShapeId }
                                            if (updatedShape != null && updatedShape.vertices.size >= 2) {
                                                val p0 = updatedShape.vertices.first()
                                                val p1 = updatedShape.vertices.last()
                                                val deg = ((kotlin.math.atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble()) * 180.0 / PI).toFloat() % 360f + 360f) % 360f
                                                val midX = ((p0.x + p1.x) * 0.5f) * liveCamera.scale + liveCamera.panX
                                                val midY = ((p0.y + p1.y) * 0.5f) * liveCamera.scale + liveCamera.panY - with(density) { 48.dp.toPx() }
                                                activeRotationHud = Offset(midX, midY) to "${deg.roundToInt()}°"
                                            }
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.ROTATE_SHAPE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        viewModel.rotateSelectedShapeToPoint(nwX, nwY)
                                        val updatedShape = viewModel.uiState.value.board.shapes.find { it.id == liveState.selectedShapeId }
                                        if (updatedShape != null) {
                                            val screenX = (updatedShape.x + updatedShape.width / 2f) * liveCamera.scale + liveCamera.panX
                                            val screenY = updatedShape.y * liveCamera.scale + liveCamera.panY - with(density) { 64.dp.toPx() }
                                            activeRotationHud = Offset(screenX, screenY) to "${updatedShape.rotation.roundToInt()}°"
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.MOVE_SHAPE -> {
                                        val pan = event.calculatePan()
                                        if (pan.x != 0f || pan.y != 0f) {
                                            if (!hasTransformed) {
                                                viewModel.beginTransformSnapshot()
                                                hasTransformed = true
                                            }
                                            viewModel.moveSelectedShape(pan.x / liveCamera.scale, pan.y / liveCamera.scale)
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.RESIZE_STROKE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        viewModel.resizeSelectedStrokeBounds(activeHandleIndex, nwX, nwY)
                                        change.consume()
                                    }
                                    CanvasGestureMode.ROTATE_STROKE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        viewModel.rotateSelectedStrokeToPoint(nwX, nwY)
                                        strokeRotCenterWorld?.let { c ->
                                            val curDeg = (kotlin.math.atan2((nwY - c.y).toDouble(), (nwX - c.x).toDouble()) * 180.0 / PI).toFloat()
                                            var delta = (curDeg - strokeRotStartDeg) % 360f
                                            if (delta > 180f) delta -= 360f
                                            if (delta < -180f) delta += 360f
                                            val hudX = c.x * liveCamera.scale + liveCamera.panX
                                            val hudY = (activeStroke?.points?.minOfOrNull { it.y } ?: c.y) * liveCamera.scale + liveCamera.panY - with(density) { 64.dp.toPx() }
                                            activeRotationHud = Offset(hudX, hudY) to "${delta.roundToInt()}°"
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.MOVE_STROKE -> {
                                        val pan = event.calculatePan()
                                        if (pan.x != 0f || pan.y != 0f) {
                                            if (!hasTransformed) {
                                                viewModel.beginTransformSnapshot()
                                                hasTransformed = true
                                            }
                                            viewModel.moveSelectedStroke(pan.x / liveCamera.scale, pan.y / liveCamera.scale)
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.LASSO_SELECT -> {
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        val last = localLassoPoints.lastOrNull()
                                        if (last == null || hypot(nwX - last.x, nwY - last.y) >= 4f / liveCamera.scale) {
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
                                        // Replay historical stylus samples that Android batches into a pointer event.
                                        for (historical in change.historical) {
                                            val hx = (historical.position.x - liveCamera.panX) / liveCamera.scale
                                            val hy = (historical.position.y - liveCamera.panY) / liveCamera.scale
                                            val rawHistorical = StrokePoint(hx, hy)
                                            val historicalPoint = if (liveState.isRulerActive) StrokeStabilizer.projectPointToRulerIfNear(rawHistorical, liveState.rulerState) else rawHistorical
                                            val previous = localActivePoints.lastOrNull()
                                            if (previous == null || previous.x != historicalPoint.x || previous.y != historicalPoint.y) localActivePoints.add(historicalPoint)
                                        }
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        val rawPoint = StrokePoint(nwX, nwY)
                                        val point = if (liveState.isRulerActive) StrokeStabilizer.projectPointToRulerIfNear(rawPoint, liveState.rulerState) else rawPoint
                                        val previous = localActivePoints.lastOrNull()
                                        if (previous == null || previous.x != point.x || previous.y != point.y) localActivePoints.add(point)
                                        change.consume()
                                    }
                                    CanvasGestureMode.LASER -> {
                                        viewModel.addLaserPoint(change.position.x, change.position.y)
                                        change.consume()
                                    }
                                    CanvasGestureMode.ERASE_OBJECT -> {
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        val eraseTolerance = 24f / liveCamera.scale
                                        viewModel.eraseObjectAt(nwX, nwY, eraseTolerance, saveSnapshot = true)
                                        change.consume()
                                    }
                                    CanvasGestureMode.ERASE_PIXEL -> {
                                        val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                        val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                        val eraseRadius = (liveState.penWidth * 2.2f).coerceIn(14f, 36f) / liveCamera.scale
                                        val prevCursor = pixelEraserCursorWorld ?: StrokePoint(nwX, nwY)
                                        val nextCursor = StrokePoint(nwX, nwY)
                                        pixelEraserCursorWorld = nextCursor
                                        if (viewModel.erasePixelSegment(prevCursor, nextCursor, eraseRadius, saveSnapshot = !hasTransformed)) {
                                            hasTransformed = true
                                        }
                                        change.consume()
                                    }
                                    CanvasGestureMode.NONE -> {}
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (gestureMode == CanvasGestureMode.DRAW_STROKE && !isMultiTouch) {
                            // Include the actual pointer-up coordinate; the final event can arrive
                            // with pressed=false and therefore is not handled by the active-pointer branch.
                            val releaseCamera = viewModel.cameraState.value
                            val finalPoint = StrokePoint(
                                (lastDrawPointerPosition.x - releaseCamera.panX) / releaseCamera.scale,
                                (lastDrawPointerPosition.y - releaseCamera.panY) / releaseCamera.scale
                            )
                            val previousPoint = localActivePoints.lastOrNull()
                            if (previousPoint == null ||
                                hypot(finalPoint.x - previousPoint.x, finalPoint.y - previousPoint.y) >= 0.35f / releaseCamera.scale
                            ) {
                                localActivePoints.add(finalPoint)
                            }
                        }

                        isDrawingGestureActive = false
                        pixelEraserCursorWorld = null
                        activeRotationHud = null

                        if (gestureMode == CanvasGestureMode.LASSO_SELECT && !isMultiTouch) {
                            if (localLassoPoints.size >= 5) {
                                viewModel.selectByLasso(localLassoPoints.toList())
                            } else {
                                viewModel.clearSelection()
                            }
                        } else if (gestureMode == CanvasGestureMode.DRAW_STROKE && localActivePoints.size >= 2 && !isMultiTouch) {
                            val pointsCopy = localActivePoints.toList()
                            // A freehand gesture must always be committed as the same stroke.
                            // Shape conversion is reserved for automatic shape recognition when enabled.
                            viewModel.commitCompletedStroke(pointsCopy, state.currentTool == ToolType.HIGHLIGHTER)
                        } else if (hasTransformed && (
                                gestureMode == CanvasGestureMode.ROTATE_SHAPE ||
                                    gestureMode == CanvasGestureMode.ROTATE_STROKE ||
                                    gestureMode == CanvasGestureMode.RESIZE_SHAPE ||
                                    gestureMode == CanvasGestureMode.RESIZE_STROKE ||
                                    gestureMode == CanvasGestureMode.MOVE_SHAPE ||
                                    gestureMode == CanvasGestureMode.MOVE_STROKE
                                )
                        ) {
                            viewModel.persistCurrentBoard()
                        }
                        viewModel.resetStrokeDragAngle()
                        localActivePoints.clear()
                        localLassoPoints.clear()
                    }
                }
        ) {
            // Camera state is read in the draw phase: pan/zoom invalidates drawing, not composition.
            val camera = cameraState.value
            val rotStemPx = ShapeCanvasCoordinates.rotationStemScreenPx(density)
            drawCanvasBackgroundAndGrid(
                gridType = state.board.gridType,
                colorTheme = state.board.colorTheme,
                panX = camera.panX,
                panY = camera.panY,
                scale = camera.scale,
                screenW = size.width,
                screenH = size.height
            )

            val safeScale = if (camera.scale > 0.001f) camera.scale else 1.0f
            val visibleWorldLeft = -camera.panX / safeScale
            val visibleWorldTop = -camera.panY / safeScale
            val visibleWorldRight = (size.width - camera.panX) / safeScale
            val visibleWorldBottom = (size.height - camera.panY) / safeScale
            val usePaintRaster = state.isPaintPixelMode && paintSpatialIndex != null

            if (usePaintRaster) {
                drawIntoCanvas { composeCanvas ->
                    paintTileCache.renderVisibleTiles(
                        canvas = composeCanvas.nativeCanvas,
                        spatialIndex = paintSpatialIndex!!,
                        panX = camera.panX,
                        panY = camera.panY,
                        scale = camera.scale,
                        screenW = size.width,
                        screenH = size.height,
                        isDarkBoard = isDarkBoard,
                        handDrawnFactor = state.handDrawnFactor
                    )
                }
                if (state.selectedStrokeId != null || selectedStrokeIdSet.isNotEmpty()) {
                    state.board.strokes.forEach { stroke ->
                        val isSelected = stroke.id == state.selectedStrokeId || selectedStrokeIdSet.contains(stroke.id)
                        if (!isSelected) return@forEach
                        val bounds = stroke.geometryMetrics
                        if (bounds.hasPoints) {
                            val pad = (stroke.width * 3.5f + 64f) / safeScale
                            if (bounds.maxX + pad < visibleWorldLeft || bounds.minX - pad > visibleWorldRight ||
                                bounds.maxY + pad < visibleWorldTop || bounds.minY - pad > visibleWorldBottom) {
                                return@forEach
                            }
                        }
                        drawStrokeVector(
                            stroke = stroke,
                            panX = camera.panX,
                            panY = camera.panY,
                            scale = camera.scale,
                            isSelected = true,
                            isDarkBoard = isDarkBoard,
                            handDrawnFactor = state.handDrawnFactor
                        )
                    }
                }
            } else {
                state.board.strokes.forEach { stroke ->
                    val isSelected = stroke.id == state.selectedStrokeId || selectedStrokeIdSet.contains(stroke.id)
                    val bounds = stroke.geometryMetrics
                    if (bounds.hasPoints) {
                        // Include humanization and the widest procedural brush footprint.
                        // Convert the screen-space safety margin back into board coordinates.
                        val pad = (stroke.width * 3.5f + 64f) / safeScale
                        if (bounds.maxX + pad < visibleWorldLeft || bounds.minX - pad > visibleWorldRight ||
                            bounds.maxY + pad < visibleWorldTop || bounds.minY - pad > visibleWorldBottom) {
                            return@forEach
                        }
                    }
                    drawStrokeVector(
                        stroke = stroke,
                        panX = camera.panX,
                        panY = camera.panY,
                        scale = camera.scale,
                        isSelected = isSelected,
                        isDarkBoard = isDarkBoard,
                        handDrawnFactor = state.handDrawnFactor
                    )
                }
            }

            if (localActivePoints.size >= 2) {
                // Show the captured pointer path directly. Stabilization is applied once on release,
                // so the live tip never lags behind the user's actual movement.
                drawStrokeVector(
                    stroke = livePreviewStroke,
                    panX = camera.panX,
                    panY = camera.panY,
                    scale = camera.scale,
                    // Keep the in-progress preview spatially stable while the pointer is held.
                    // Animated stroke effects begin only after the stroke is committed.
                    animPhase = 0f,
                    preserveInputPoints = true,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = state.handDrawnFactor
                )
            }

            if (localLassoPoints.size >= 2) {
                val lassoPath = Path().apply {
                    val p0 = localLassoPoints.first()
                    moveTo(p0.x * camera.scale + camera.panX, p0.y * camera.scale + camera.panY)
                    for (i in 1 until localLassoPoints.size) {
                        val pt = localLassoPoints[i]
                        lineTo(pt.x * camera.scale + camera.panX, pt.y * camera.scale + camera.panY)
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
                val center = Offset(cursor.x * camera.scale + camera.panX, cursor.y * camera.scale + camera.panY)
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

            val viewportCenterX = (visibleWorldLeft + visibleWorldRight) / 2f
            val viewportCenterY = (visibleWorldTop + visibleWorldBottom) / 2f
            val viewportRadius = hypot(
                (visibleWorldRight - visibleWorldLeft) / 2f,
                (visibleWorldBottom - visibleWorldTop) / 2f
            ) + 80f / safeScale

            if (usePaintRaster) {
                if (state.selectedShapeId != null || selectedShapeIdSet.isNotEmpty()) {
                    cachedShapeBounds.forEach { cached ->
                        val shape = cached.shape
                        if (shape.isHiddenByCollapse) return@forEach
                        val isSelected = shape.id == state.selectedShapeId || selectedShapeIdSet.contains(shape.id)
                        if (!isSelected) return@forEach
                        if (hypot(cached.centerX - viewportCenterX, cached.centerY - viewportCenterY) >
                            cached.radius + viewportRadius
                        ) {
                            return@forEach
                        }
                        drawShapeVector(
                            shape = shape,
                            panX = camera.panX,
                            panY = camera.panY,
                            scale = camera.scale,
                            isSelected = true,
                            isDarkBoard = isDarkBoard,
                            rotStemPx = rotStemPx,
                            handDrawnFactor = state.handDrawnFactor
                        )
                    }
                }
            } else {
                cachedShapeBounds.forEach { cached ->
                    // The enclosing circle remains valid for rotated shapes and custom vertices.
                    if (hypot(cached.centerX - viewportCenterX, cached.centerY - viewportCenterY) >
                        cached.radius + viewportRadius
                    ) {
                        return@forEach
                    }
                    val shape = cached.shape
                    if (shape.isHiddenByCollapse) return@forEach
                    val isSelected = shape.id == state.selectedShapeId || selectedShapeIdSet.contains(shape.id)
                    drawShapeVector(
                        shape = shape,
                        panX = camera.panX,
                        panY = camera.panY,
                        scale = camera.scale,
                        isSelected = isSelected,
                        isDarkBoard = isDarkBoard,
                        rotStemPx = rotStemPx,
                        handDrawnFactor = state.handDrawnFactor
                    )
                }
            }

            if (state.selectedStrokeId != null && state.selectedStrokeIds.size <= 1) {
                selectedStrokeForHandles?.let { selStroke ->
                    drawStrokeSelectionHandles(
                        stroke = selStroke,
                        panX = camera.panX,
                        panY = camera.panY,
                        scale = camera.scale,
                        rotStemPx = rotStemPx
                    )
                }
            }

            drawLaserTrail(state.laserTrail)
        }

        // Live rotation angle HUD pill on canvas while dragging any shape/line/stroke rotation handle
        activeRotationHud?.let { (hudPos, angleText) ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (hudPos.x - with(density) { 24.dp.toPx() }).roundToInt(),
                            hudPos.y.roundToInt().coerceAtLeast(0)
                        )
                    }
                    .testTag("canvas_rotation_hud")
            ) {
                Text(
                    text = angleText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }

        // Render Text Items (Locked axis/column labels + Modern floating editable text cards)
        state.board.textItems.forEach { tItem ->
            if (tItem.isHiddenByCollapse) return@forEach
            val tColor = parseColor(tItem.colorHex, Color(0xFF0F172A))
            val widthDp = with(density) { tItem.width.coerceAtLeast(120f).toDp() }
            val fontSizeSp = with(density) { tItem.fontSize.coerceIn(14f, 80f).toSp() }
            val cameraLayer = Modifier
                .offset {
                    val camera = cameraState.value
                    IntOffset(
                        (tItem.x * camera.scale + camera.panX).roundToInt(),
                        (tItem.y * camera.scale + camera.panY).roundToInt()
                    )
                }
                .graphicsLayer {
                    val camera = cameraState.value
                    scaleX = camera.scale
                    scaleY = camera.scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }

            if (tItem.isLocked) {
                Box(
                    modifier = cameraLayer
                        .widthIn(min = widthDp, max = widthDp)
                        .testTag("locked_text_item_${tItem.id}")
                ) {
                    Text(
                        text = tItem.text,
                        color = tColor,
                        fontSize = fontSizeSp,
                        fontWeight = if (tItem.isBold) FontWeight.Bold else FontWeight.Medium,
                        lineHeight = (fontSizeSp.value * 1.25f).sp
                    )
                }
            } else {
                val isSelected = state.selectedTextId == tItem.id || state.selectedTextIds.contains(tItem.id)
                val isTransparentBg = tItem.backgroundColorHex == "TRANSPARENT"
                val cardBg = if (isTransparentBg) {
                    Color.Transparent
                } else {
                    tItem.backgroundColorHex?.let { parseColor(it, Color.White) }
                        ?: if (isDarkBoard) Color(0xFF1E293B) else Color.White
                }
                val cardBorderColor = when {
                    isSelected -> Color(0xFF2563EB)
                    isTransparentBg -> Color.Transparent
                    isDarkBoard -> Color(0xFF334155)
                    else -> Color(0xFFCBD5E1)
                }
                val cardBorderWidth = when {
                    isSelected -> 2.dp
                    isTransparentBg -> 0.dp
                    else -> 1.dp
                }

                Box(
                    modifier = cameraLayer
                        .graphicsLayer {
                            rotationZ = tItem.rotation
                            transformOrigin = TransformOrigin.Center
                        }
                        .pointerInput(tItem.id, isPanOnlyMode) {
                            if (!isPanOnlyMode) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial
                                    )
                                    var previous = down.position
                                    var dragging = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.pressed) {
                                            val delta = change.position - previous
                                            previous = change.position
                                            if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                dragging = true
                                                if (!viewModel.uiState.value.selectedTextIds.contains(tItem.id)) viewModel.selectTextItem(tItem.id)
                                                viewModel.beginTransformSnapshot()
                                            }
                                            if (dragging) {
                                                change.consume()
                                                val cameraScale = cameraState.value.scale.coerceAtLeast(0.1f)
                                                viewModel.moveSelectedObjects(delta.x / cameraScale, delta.y / cameraScale)
                                            }
                                        }
                                    } while (event.changes.any { it.id == down.id && it.pressed })
                                    if (dragging) {
                                        viewModel.persistCurrentBoard()
                                    } else if (!viewModel.uiState.value.selectedTextIds.contains(tItem.id)) {
                                        viewModel.selectTextItem(tItem.id)
                                    }
                                }
                            }
                        }
                        .testTag("editable_text_item_${tItem.id}")
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cardBg,
                        shadowElevation = if (isTransparentBg) 0.dp else if (isSelected) 4.dp else 1.5.dp,
                        modifier = Modifier
                            .width(widthDp)
                            .border(cardBorderWidth, cardBorderColor, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            BasicTextField(
                                value = tItem.text,
                                onValueChange = {
                                    if (!isSelected) viewModel.selectTextItem(tItem.id)
                                    viewModel.updateTextItem(tItem.id, it)
                                },
                                enabled = !isPanOnlyMode,
                                readOnly = isPanOnlyMode,
                                textStyle = TextStyle(
                                    color = tColor,
                                    fontSize = fontSizeSp,
                                    lineHeight = (fontSizeSp.value * 1.24f).sp,
                                    fontWeight = if (tItem.isBold) FontWeight.Bold else FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(tColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTag("editable_text_input_${tItem.id}")
                            )


                        }
                    }

                    if (isSelected && !isPanOnlyMode) {
                        var currentDragWidth by remember(tItem.id, tItem.width) { mutableStateOf(tItem.width) }
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 7.dp)
                                .size(width = 12.dp, height = 26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2563EB))
                                .border(1.5.dp, Color.White, RoundedCornerShape(6.dp))
                                .pointerInput(tItem.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            currentDragWidth = tItem.width
                                            viewModel.beginTextItemTransformSnapshot()
                                        },
                                        onDragEnd = { viewModel.commitTextItemTransform() },
                                        onDragCancel = { viewModel.commitTextItemTransform() },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val s = cameraState.value.scale.coerceAtLeast(0.1f)
                                            currentDragWidth = (currentDragWidth + dragAmount.x / s).coerceIn(140f, 760f)
                                            viewModel.resizeTextItemWidth(tItem.id, currentDragWidth)
                                        }
                                    )
                                }
                                .testTag("resize_text_handle_${tItem.id}")
                        )
                    }
                }
            }
        }

        // Edit label button for the currently selected non-line shape (single global affordance)
        selectedShapeForLabel?.let { shape ->
            if (!isPanOnlyMode) {
                val editHalfW = with(density) { 18.dp.roundToPx() }
                val editTop = with(density) { 32.dp.roundToPx() }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .offset {
                        val camera = cameraState.value
                        val shapeScreenCenterX = ((shape.x + shape.width / 2f) * camera.scale + camera.panX).roundToInt()
                        val shapeScreenTop = (shape.y * camera.scale + camera.panY).roundToInt()
                        IntOffset(shapeScreenCenterX - editHalfW, shapeScreenTop - editTop)
                    }
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            editingLabelShape = shape
                            editingLabelText = shape.label
                        }
                        .testTag("edit_shape_label_${shape.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editar etiqueta de forma",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Render Sticky Notes (isolated per-note StateFlow cards + constant-size selection overlay)
        val hasViewport = canvasSizePx.width > 0 && canvasSizePx.height > 0
        val isPlacementPending = state.pendingStickyNoteColor != null
        val isMultiSelectActive = (state.selectedNoteIds.size + state.selectedStrokeIds.size + state.selectedShapeIds.size + state.selectedTextIds.size) > 1

        StickyNoteLayer(
            viewModel = viewModel,
            cameraState = cameraState,
            selectedNoteId = state.selectedNoteId,
            selectedNoteIds = state.selectedNoteIds,
            isMultiSelectActive = isMultiSelectActive,
            isPanOnlyMode = isPanOnlyMode,
            isPlacementPending = isPlacementPending,
            hasViewport = hasViewport,
            viewportWidthPx = screenWidthPx,
            viewportHeightPx = screenHeightPx,
            onRequestDeleteNote = { target ->
                notePendingRemoval = target
            }
        )

        // Strict pan-only touch shield when standalone Hand tool is active: prevents any accidental object selection/editing
        if (isPanOnlyMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput("pan_only_shield") {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                var activePointerCount = 0
                                for (change in event.changes) {
                                    if (change.pressed) activePointerCount++
                                }
                                if (activePointerCount >= 2 || event.changes.any { it.isConsumed }) {
                                    break
                                }
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    viewModel.onPanZoom(pan.x, pan.y, 1.0f)
                                }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .testTag("pan_only_overlay")
            )
        }
        } // End Global Viewport Box

        // Interactive Ruler
        InteractiveRulerView(
            rulerState = state.rulerState,
            cameraState = cameraState,
            onMove = { dx, dy -> viewModel.moveRuler(dx, dy) },
            onRotate = { da -> viewModel.rotateRuler(da) }
        )

        } // End camera-only composition

        CameraAwareWhiteboardTopBar(
            viewModel = viewModel,
            state = state,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            onOpenBoardsManager = { showBoardsManager = true },
            onOpenBackgroundSettings = { showBackgroundSettings = true },
            onClearBoard = { viewModel.clearBoard() },
            onExportBoard = { showExportModal = true },
            onRenameBoard = {
                newTitleInput = state.board.title
                showRenameDialog = true
            },
            onOpenSnapshotsAndAudit = { showSnapshotsModal = true },
            onSwitchToNotes = onSwitchToNotes,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Adaptive Dock Overlay: Selection Inspector when selected, otherwise Adaptive WhiteboardPenDock
        if (!state.isZenMode) {
            val totalSelectedCount = state.selectedStrokeIds.size + state.selectedShapeIds.size + state.selectedNoteIds.size + state.selectedTextIds.size
            val hasSelection = totalSelectedCount > 0 ||
                state.selectedShapeId != null ||
                state.selectedStrokeId != null ||
                state.selectedNoteId != null ||
                state.selectedTextId != null
            val selectedShape = remember(state.board.shapes, state.selectedShapeId) {
                state.selectedShapeId?.let { id -> state.board.shapes.find { it.id == id } }
            }
            val selectedStroke = remember(state.board.strokes, state.selectedStrokeId) {
                state.selectedStrokeId?.let { id -> state.board.strokes.find { it.id == id } }
            }
            val selectedText = remember(state.board.textItems, state.selectedTextId) {
                state.selectedTextId?.let { id -> state.board.textItems.find { it.id == id } }
            }

            CanvasDockOverlay(
                viewModel = viewModel,
                hasSelection = hasSelection,
                selectedShape = selectedShape,
                selectedStroke = selectedStroke,
                selectedNoteId = state.selectedNoteId,
                selectedText = selectedText,
                totalSelectedCount = totalSelectedCount.coerceAtLeast(1),
                currentTool = state.currentTool,
                activeBrushSlotIndex = state.activeBrushSlotIndex,
                penColorHex = state.penColorHex,
                isRainbowPen = state.isRainbowPen,
                autoShapeRecognition = state.autoShapeRecognition,
                isRulerActive = state.isRulerActive,
                pendingStickyNoteColor = state.pendingStickyNoteColor,
                onRequestDeleteNote = { target ->
                    if (target.text.isNotBlank() || !target.title.isNullOrBlank()) {
                        notePendingRemoval = target
                    } else {
                        viewModel.deleteSelectedNote()
                    }
                }
            )
        }

        // Pending Placement Banner
        if (state.pendingStickyNoteColor != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .testTag("pending_placement_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Toca en la pizarra para colocar la nota",
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


        if (showBackgroundSettings) {
            BackgroundSettingsModal(
                currentGrid = state.board.gridType,
                currentColorTheme = state.board.colorTheme,
                isPaintPixelMode = state.isPaintPixelMode,
                onSelectGrid = { viewModel.setGridType(it) },
                onSelectColorTheme = { viewModel.setColorTheme(it) },
                onTogglePaintPixelMode = { viewModel.togglePaintPixelMode() },
                onDismiss = { showBackgroundSettings = false }
            )
        }

        if (showExportModal) {
            ExportBoardModal(
                board = state.board,
                onDismiss = { showExportModal = false }
            )
        }

        if (showSnapshotsModal) {
            SnapshotsModal(
                board = state.board,
                onCreateSnapshot = { versionName, description ->
                    viewModel.createSnapshot(versionName, description)
                },
                onRestoreSnapshot = { snapshot ->
                    val ok = viewModel.restoreSnapshot(snapshot)
                    if (!ok) {
                        Toast.makeText(context, "No se pudo restaurar el snapshot", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteSnapshot = { versionId ->
                    viewModel.deleteSnapshot(versionId)
                },
                onClearAuditLogs = {
                    viewModel.clearAuditLogs()
                },
                onDismiss = { showSnapshotsModal = false }
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
        if (editingLabelShape != null) {
            AlertDialog(
                onDismissRequest = { editingLabelShape = null },
                title = { Text("Editar texto del nodo") },
                text = {
                    OutlinedTextField(
                        value = editingLabelText,
                        onValueChange = { editingLabelText = it },
                        label = { Text("Etiqueta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_shape_label")
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            editingLabelShape?.let { viewModel.updateShapeLabel(it.id, editingLabelText.trim()) }
                            editingLabelShape = null
                        }
                    ) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { editingLabelShape = null },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("Cancelar")
                    }
                }
            )
        }
        if (notePendingRemoval != null) {
            val targetNote = notePendingRemoval!!
            AlertDialog(
                onDismissRequest = { notePendingRemoval = null },
                title = { Text("Quitar nota de la pizarra") },
                text = {
                    Text(
                        "¿Deseas mover esta nota a la papelera de Notas o quitarla solo de la pizarra conservándola en Notas?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteStickyNote(targetNote.id)
                            notePendingRemoval = null
                        }
                    ) {
                        Text("Mover a papelera")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                viewModel.detachStickyNoteFromBoard(targetNote.id)
                                notePendingRemoval = null
                            }
                        ) {
                            Text("Solo quitar de pizarra")
                        }
                        TextButton(onClick = { notePendingRemoval = null }) {
                            Text("Cancelar")
                        }
                    }
                }
            )
        }
    }
}


@Composable
private fun CameraStateHost(
    viewModel: WhiteboardViewModel,
    content: @Composable (State<ViewportTransform>) -> Unit
) {
    val cameraState = viewModel.cameraState.collectAsState()
    content(cameraState)
}

@Composable
private fun CameraAwareWhiteboardTopBar(
    viewModel: WhiteboardViewModel,
    state: WhiteboardUiState,
    screenWidthPx: Float,
    screenHeightPx: Float,
    onOpenBoardsManager: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    onClearBoard: () -> Unit,
    onExportBoard: () -> Unit,
    onRenameBoard: () -> Unit,
    onOpenSnapshotsAndAudit: () -> Unit,
    onSwitchToNotes: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    // The zoom read is isolated to this small component; the screen and tool docks
    // do not subscribe to camera changes.
    val camera by viewModel.cameraState.collectAsState()
    if (!state.isZenMode) {
        WhiteboardTopBar(
            boardTitle = state.board.title,
            scale = camera.scale,
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
            onOpenBoardsManager = onOpenBoardsManager,
            onOpenBackgroundSettings = onOpenBackgroundSettings,
            onClearBoard = onClearBoard,
            onExportBoard = onExportBoard,
            onRenameBoard = onRenameBoard,
            onOpenSnapshotsAndAudit = onOpenSnapshotsAndAudit,
            onBackToHome = { viewModel.closeBoardToHome() },
            onSwitchToNotes = onSwitchToNotes,
            modifier = modifier
        )
    }
}

@Composable
private fun CanvasDockOverlay(
    viewModel: WhiteboardViewModel,
    hasSelection: Boolean,
    selectedShape: ShapeItem?,
    selectedStroke: WhiteboardStroke?,
    selectedNoteId: String?,
    selectedText: TextItem?,
    totalSelectedCount: Int,
    currentTool: ToolType,
    activeBrushSlotIndex: Int,
    penColorHex: String,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingStickyNoteColor: String?,
    onRequestDeleteNote: (StickyNote) -> Unit
) {
    val selectedNote = if (selectedNoteId != null) {
        val noteFlow = remember(viewModel, selectedNoteId) {
            viewModel.stickyNotesStore.flowFor(selectedNoteId)
        }
        val initialNote = remember(viewModel, selectedNoteId) {
            viewModel.stickyNotesStore.getOrNull(selectedNoteId)
        }
        val dockNoteFlow = remember(noteFlow) {
            noteFlow?.distinctUntilChanged { old, new ->
                old.id == new.id &&
                    old.colorHex == new.colorHex &&
                    old.isPinned == new.isPinned &&
                    old.isAspectRatioLocked == new.isAspectRatioLocked
            }
        }
        dockNoteFlow?.collectAsState(initial = initialNote)?.value
    } else {
        null
    }

    val onSelectTool = remember(viewModel) {
        { tool: ToolType ->
            viewModel.cancelPendingPlacement()
            viewModel.setTool(tool)
        }
    }
    val onSelectBrushSlot = remember(viewModel) {
        { index: Int -> viewModel.selectBrushSlot(index) }
    }
    val onUpdateBrushSlot = remember(viewModel) {
        { index: Int, brushType: StrokeBrushType?, hex: String?, width: Float?, isRainbow: Boolean? ->
            viewModel.updateBrushSlot(
                slotIndex = index,
                brushType = brushType,
                colorHex = hex,
                strokeWidth = width,
                isRainbow = isRainbow
            )
        }
    }
    val onToggleAutoShape = remember(viewModel) {
        { viewModel.toggleAutoShapeRecognition() }
    }
    val onToggleRuler = remember(viewModel) {
        { viewModel.toggleRuler() }
    }
    val onAddStickyNote = remember(viewModel) {
        { color: String -> viewModel.insertStickyNoteAtCenter(color) }
    }
    val onRequestPlaceStickyNote = remember(viewModel) {
        { color: String -> viewModel.requestAddStickyNote(color) }
    }
    val onAddText = remember(viewModel) {
        { viewModel.insertTextAtCenter() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutMode = when {
            maxHeight < 500.dp -> DockLayoutMode.COMPACT_LANDSCAPE_RAIL
            maxWidth >= 740.dp -> DockLayoutMode.EXPANDED_TABLET
            else -> DockLayoutMode.COMPACT_PORTRAIT
        }

        if (hasSelection) {
            SelectionEditDock(
                selectedShape = selectedShape,
                selectedStroke = selectedStroke,
                selectedNote = selectedNote,
                selectedText = selectedText,
                totalSelectedCount = totalSelectedCount,
                onDeleteAll = { viewModel.deleteCurrentSelection() },
                onDuplicateAll = { viewModel.duplicateCurrentSelection() },
                onScaleAll = { factor -> viewModel.scaleCurrentSelection(factor) },
                onRotateAll = { delta -> viewModel.rotateCurrentSelection(delta) },
                onUpdateAllColor = { col -> viewModel.updateCurrentSelectionColor(col) },
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
                    selectedNoteId?.let { viewModel.updateStickyNoteColor(it, hex) }
                },
                onToggleNotePin = {
                    selectedNoteId?.let { viewModel.toggleStickyNotePin(it) }
                },
                onToggleNoteAspectRatioLock = {
                    selectedNoteId?.let { viewModel.toggleStickyNoteAspectRatioLock(it) }
                },
                onDeleteNote = {
                    val target = selectedNoteId?.let { viewModel.stickyNotesStore.getOrNull(it) }
                    if (target != null) {
                        onRequestDeleteNote(target)
                    } else {
                        viewModel.deleteSelectedNote()
                    }
                },
                onUpdateTextColor = { hex ->
                    selectedText?.let { viewModel.updateTextItemStyle(it.id, colorHex = hex) }
                },
                onToggleTextBackground = {
                    selectedText?.let { t ->
                        val isTransparent = t.backgroundColorHex == "TRANSPARENT"
                        viewModel.updateTextItemStyle(
                            id = t.id,
                            backgroundColorHex = if (isTransparent) "#FFFFFF" else null,
                            clearBackground = !isTransparent
                        )
                    }
                },
                onToggleTextBold = {
                    selectedText?.let { t ->
                        viewModel.updateTextItemStyle(t.id, isBold = !t.isBold)
                    }
                },
                onUpdateTextFontSize = { size ->
                    selectedText?.let { t ->
                        viewModel.updateTextItemStyle(t.id, fontSize = size)
                    }
                },
                onDeleteText = {
                    selectedText?.let { viewModel.deleteTextItem(it.id) }
                },
                onDone = { viewModel.clearSelection() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
            )
        } else {
            val dockModifier = if (layoutMode == DockLayoutMode.COMPACT_LANDSCAPE_RAIL) {
                Modifier
                    .align(Alignment.CenterStart)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .displayCutoutPadding()
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
            }

            WhiteboardPenDock(
                currentTool = currentTool,
                brushSlots = viewModel.brushSlots,
                activeBrushSlotIndex = activeBrushSlotIndex,
                currentPenColor = penColorHex,
                isRainbowPen = isRainbowPen,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingStickyNoteColor = pendingStickyNoteColor,
                layoutMode = layoutMode,
                onSelectTool = onSelectTool,
                onSelectBrushSlot = onSelectBrushSlot,
                onUpdateBrushSlot = onUpdateBrushSlot,
                onToggleAutoShape = onToggleAutoShape,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                modifier = dockModifier
            )
        }
    }
}

private data class CachedShapeBounds(
    val shape: ShapeItem,
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

@Composable
private fun StickyNoteLayer(
    viewModel: WhiteboardViewModel,
    cameraState: State<ViewportTransform>,
    selectedNoteId: String?,
    selectedNoteIds: Set<String>,
    isMultiSelectActive: Boolean,
    isPanOnlyMode: Boolean,
    isPlacementPending: Boolean,
    hasViewport: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onRequestDeleteNote: (StickyNote) -> Unit
) {
    // Subscribes only to note ID list membership (add/remove/board switch).
    // Individual note drags, resizes, rotations, and typing emit 0 changes here.
    val noteIds by viewModel.stickyNotesStore.noteIds.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    val onBeginTransform = viewModel::beginStickyNoteTransformSnapshot
    val onEndTransform = viewModel::commitWorkspaceSync

    noteIds.forEach { noteId ->
        val noteFlow = viewModel.stickyNotesStore.flowFor(noteId) ?: return@forEach
        val isSelected = noteId == selectedNoteId || selectedNoteIds.contains(noteId)

        key(noteId) {
            val onSelect = remember(viewModel, noteId) { { viewModel.selectNote(noteId) } }
            val onUpdateText = remember(viewModel, noteId) {
                { text: String -> viewModel.updateStickyNoteText(noteId, text) }
            }
            val onUpdateTitle = remember(viewModel, noteId) {
                { title: String -> viewModel.updateStickyNoteTitle(noteId, title) }
            }
            val onUpdateColor = remember(viewModel, noteId) {
                { hex: String -> viewModel.updateStickyNoteColor(noteId, hex) }
            }
            val onTogglePin = remember(viewModel, noteId) {
                { viewModel.toggleStickyNotePin(noteId) }
            }
            val onDelete = remember(viewModel, noteId, onRequestDeleteNote) {
                {
                    val currentNote = viewModel.stickyNotesStore.getOrNull(noteId)
                    if (currentNote != null && (currentNote.text.isNotBlank() || !currentNote.title.isNullOrBlank())) {
                        onRequestDeleteNote(currentNote)
                    } else {
                        viewModel.deleteStickyNote(noteId)
                    }
                }
            }
            val onDragDelta = remember(viewModel, noteId) {
                { worldDx: Float, worldDy: Float ->
                    val cur = viewModel.uiState.value
                    val totalSel = cur.selectedNoteIds.size + cur.selectedStrokeIds.size + cur.selectedShapeIds.size
                    if (totalSel > 1 && cur.selectedNoteIds.contains(noteId)) {
                        viewModel.moveSelectedObjects(worldDx, worldDy)
                    } else {
                        viewModel.moveStickyNote(noteId, worldDx, worldDy)
                    }
                }
            }

            StickyNoteCard(
                noteFlow = noteFlow,
                cameraState = cameraState,
                textMeasurer = textMeasurer,
                isSelected = isSelected,
                isPanOnlyMode = isPanOnlyMode,
                isPlacementPending = isPlacementPending,
                hasViewport = hasViewport,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                onSelect = onSelect,
                onBeginTransform = onBeginTransform,
                onEndTransform = onEndTransform,
                onUpdateText = onUpdateText,
                onUpdateTitle = onUpdateTitle,
                onUpdateColor = onUpdateColor,
                onTogglePin = onTogglePin,
                onDelete = onDelete,
                onDragDelta = onDragDelta
            )
        }
    }

    if (selectedNoteId != null && !isMultiSelectActive && !isPanOnlyMode && !isPlacementPending) {
        val selectedFlow = viewModel.stickyNotesStore.flowFor(selectedNoteId)
        if (selectedFlow != null) {
            key("selection_overlay_$selectedNoteId") {
                val onResizeHandle = remember(viewModel, selectedNoteId) {
                    { handle: StickyNoteResizeHandle, dxScreen: Float, dyScreen: Float ->
                        viewModel.resizeStickyNoteHandle(selectedNoteId, handle, dxScreen, dyScreen)
                    }
                }
                val onRotate = remember(viewModel, selectedNoteId) {
                    { degrees: Float -> viewModel.rotateStickyNote(selectedNoteId, degrees) }
                }
                StickyNoteSelectionOverlay(
                    noteFlow = selectedFlow,
                    cameraState = cameraState,
                    onBeginTransform = onBeginTransform,
                    onEndTransform = onEndTransform,
                    onResizeHandle = onResizeHandle,
                    onRotate = onRotate
                )
            }
        }
    }
}

private fun getShapeHandleHitIndex(
    shape: ShapeItem,
    wX: Float,
    wY: Float,
    tolerance: Float,
    rotHitTolerance: Float,
    scale: Float = 1f,
    density: androidx.compose.ui.unit.Density
): Int {
    if (shape.shapeType.isLineLike && shape.vertices.size >= 2) {
        val p0 = shape.vertices[0]
        val p1 = shape.vertices[1]
        val rotOffset = ShapeCanvasCoordinates.lineRotationHandleOffsetWorld(scale)
        if (hypot(wX - p0.x, wY - (p0.y - rotOffset)) <= rotHitTolerance) return 2
        if (hypot(wX - p1.x, wY - (p1.y - rotOffset)) <= rotHitTolerance) return 3
        if (hypot(wX - p0.x, wY - p0.y) <= tolerance) return 0
        if (hypot(wX - p1.x, wY - p1.y) <= tolerance) return 1
        return -1
    }
    val (lx, ly) = ShapeCanvasCoordinates.toLocalUnrotated(shape, wX, wY)
    // Check rotation handle (index 4) protruding above top center
    val rotCenterX = shape.x + shape.width / 2f
    val rotCenterY = shape.y - ShapeCanvasCoordinates.rotationHandleCenterOffsetWorld(density, scale)
    if (hypot(lx - rotCenterX, ly - rotCenterY) <= rotHitTolerance) {
        return 4
    }

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
    tolerance: Float,
    rotHitTolerance: Float,
    scale: Float = 1f,
    density: androidx.compose.ui.unit.Density
): Int {
    if (stroke.points.isEmpty()) return -1
    val minX = stroke.points.minOf { it.x }
    val maxX = stroke.points.maxOf { it.x }
    val minY = stroke.points.minOf { it.y }
    val maxY = stroke.points.maxOf { it.y }

    // Check rotation handle (index 4) protruding above top center
    val rotCenterX = (minX + maxX) / 2f
    val rotCenterY = minY - ShapeCanvasCoordinates.rotationHandleCenterOffsetWorld(density, scale)
    if (hypot(wX - rotCenterX, wY - rotCenterY) <= rotHitTolerance) {
        return 4
    }

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

private fun isPointHittingStrokeOrBounds(
    stroke: WhiteboardStroke,
    wX: Float,
    wY: Float,
    tolerance: Float
): Boolean {
    if (isPointHittingStroke(stroke, wX, wY, tolerance)) return true
    if (stroke.points.isEmpty()) return false
    val minX = stroke.points.minOf { it.x } - tolerance
    val maxX = stroke.points.maxOf { it.x } + tolerance
    val minY = stroke.points.minOf { it.y } - tolerance
    val maxY = stroke.points.maxOf { it.y } + tolerance
    return wX in minX..maxX && wY in minY..maxY
}

private fun isPointHittingShape(
    shape: ShapeItem,
    wX: Float,
    wY: Float,
    tolerance: Float
): Boolean {
    if (shape.shapeType.isLineLike && shape.vertices.size >= 2) {
        for (i in 0 until shape.vertices.lastIndex) {
            if (distanceToSegment(wX, wY, shape.vertices[i], shape.vertices[i + 1]) <= tolerance) {
                return true
            }
        }
        return false
    }
    val (lx, ly) = ShapeCanvasCoordinates.toLocalUnrotated(shape, wX, wY)
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

private fun isPointHittingStroke(
    stroke: WhiteboardStroke,
    wX: Float,
    wY: Float,
    tolerance: Float
): Boolean {
    val pts = stroke.points
    if (pts.isEmpty()) return false
    val effTol = tolerance + stroke.width * 0.4f
    if (pts.size == 1) return hypot(pts[0].x - wX, pts[0].y - wY) <= effTol
    for (i in 0 until pts.lastIndex) {
        if (distanceToSegment(wX, wY, pts[i], pts[i + 1]) <= effTol) return true
    }
    return false
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

