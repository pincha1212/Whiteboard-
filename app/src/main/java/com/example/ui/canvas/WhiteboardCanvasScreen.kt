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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ink.IdeationTemplateFactory
import com.example.ink.StickyNoteGeometry
import com.example.ink.StrokeStabilizer
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StickyNote
import com.example.model.isLineLike
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.ui.toolbar.WhiteboardPenDock
import com.example.ui.toolbar.WhiteboardTopBar
import com.example.viewmodel.WhiteboardViewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class CanvasGestureMode {
    NONE,
    DRAW_STROKE,
    PLACE_SHAPE,
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
    var showExportModal by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editingLabelShape by remember { mutableStateOf<ShapeItem?>(null) }
    var editingLabelText by remember { mutableStateOf("") }
    var notePendingRemoval by remember { mutableStateOf<StickyNote?>(null) }
    var newTitleInput by remember { mutableStateOf(state.board.title) }
    var canvasSizePx by remember { mutableStateOf(IntSize(1080, 1920)) }
    val density = LocalDensity.current

    LaunchedEffect(state.shouldAutoOpenTemplatePicker) {
        if (state.shouldAutoOpenTemplatePicker) {
            showTemplatePicker = true
            viewModel.consumeAutoOpenTemplatePicker()
        }
    }

    var pendingDragStart by remember { mutableStateOf<StrokePoint?>(null) }
    var pendingDragEnd by remember { mutableStateOf<StrokePoint?>(null) }
    var pixelEraserCursorWorld by remember { mutableStateOf<StrokePoint?>(null) }
    var activeRotationHud by remember { mutableStateOf<Pair<Offset, String>?>(null) }

    val localActivePoints = remember { mutableStateListOf<StrokePoint>() }
    val localLassoPoints = remember { mutableStateListOf<StrokePoint>() }

    var isDrawingGestureActive by remember { mutableStateOf(false) }
    var lastDrawMoveMs by remember { mutableLongStateOf(0L) }
    var snappedDuringHold by remember { mutableStateOf(false) }

    val hasAnimatedContent = state.penStyle == StrokeStyle.ANIMATED_PULSE ||
        state.pendingStrokeStyle == StrokeStyle.ANIMATED_PULSE ||
        state.board.strokes.any { it.strokeStyle == StrokeStyle.ANIMATED_PULSE } ||
        state.board.shapes.any { it.strokeStyle == StrokeStyle.ANIMATED_PULSE }
    var animPhase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(hasAnimatedContent) {
        if (hasAnimatedContent) {
            var startMs = -1L
            while (true) {
                withFrameMillis { frameMs ->
                    if (startMs < 0L) startMs = frameMs
                    val elapsed = (frameMs - startMs) % 1200L
                    animPhase = elapsed / 1200f
                }
            }
        } else {
            animPhase = 0f
        }
    }

    // Hold-still Snap-to-shape (~480ms at stroke end converts crooked circles/lines into clean geometry)
    LaunchedEffect(isDrawingGestureActive, lastDrawMoveMs) {
        if (isDrawingGestureActive && !snappedDuringHold) {
            delay(480L)
            val tool = viewModel.uiState.value.currentTool
            val isSnapEligible = tool == ToolType.PEN ||
                tool == ToolType.FINE_MARKER ||
                tool == ToolType.BRUSH ||
                tool == ToolType.HIGHLIGHTER ||
                tool == ToolType.CALLIGRAPHY ||
                tool == ToolType.AIRBRUSH
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

    val isPanOnlyMode = state.currentTool == ToolType.HAND &&
        state.pendingStickyNoteColor == null &&
        state.pendingShapeType == null

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

        // Global Camera / Viewport Transform Layer (handles 2-finger pinch-to-zoom & pan over both canvas and sticky notes)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput("global_viewport_multitouch") {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val activePointers = event.changes.filter { it.pressed }
                            if (activePointers.size >= 2) {
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
                        val handleTolerance = with(density) { 24.dp.toPx() } / curState.scale
                        val rotHitTolerance = with(density) { 28.dp.toPx() } / curState.scale
                        val hitTolerance = with(density) { 18.dp.toPx() } / curState.scale

                        val isDrawingTool = curState.currentTool == ToolType.PEN ||
                            curState.currentTool == ToolType.FINE_MARKER ||
                            curState.currentTool == ToolType.HIGHLIGHTER ||
                            curState.currentTool == ToolType.BRUSH ||
                            curState.currentTool == ToolType.CALLIGRAPHY ||
                            curState.currentTool == ToolType.AIRBRUSH

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

                        val hitSelectedShape = curState.board.shapes.any { s ->
                            !s.isLocked && !s.isHiddenByCollapse &&
                            (s.id == curState.selectedShapeId || curState.selectedShapeIds.contains(s.id)) &&
                            isPointHittingShape(s, wX, wY, hitTolerance)
                        }
                        val hitSelectedStroke = curState.board.strokes.any { s ->
                            (s.id == curState.selectedStrokeId || curState.selectedStrokeIds.contains(s.id)) &&
                            isPointHittingStroke(s, wX, wY, hitTolerance)
                        }
                        val hitSelectedNote = curState.board.stickyNotes.any { n ->
                            if (n.isLocked || n.isHiddenByCollapse || !(n.id == curState.selectedNoteId || curState.selectedNoteIds.contains(n.id))) {
                                false
                            } else {
                                val (lx, ly) = StickyNoteGeometry.toLocalUnrotated(n.x, n.y, n.width, n.height, n.rotation, wX, wY)
                                lx >= n.x && lx <= (n.x + n.width) && ly >= n.y && ly <= (n.y + n.height)
                            }
                        }

                        if (curState.pendingStickyNoteColor != null) {
                            viewModel.placePendingAt(wX, wY)
                        } else if (curState.pendingShapeType != null) {
                            val startPt = StrokePoint(wX, wY)
                            pendingDragStart = startPt
                            pendingDragEnd = startPt
                            gestureMode = CanvasGestureMode.PLACE_SHAPE
                        } else if (curState.currentTool == ToolType.HAND) {
                            // Standalone Hand button: strictly pan-only, disable any accidental object selection/drag
                            gestureMode = CanvasGestureMode.PAN
                        } else if (activeShape != null && getShapeHandleHitIndex(activeShape, wX, wY, handleTolerance, rotHitTolerance, curState.scale, density) != -1) {
                            activeHandleIndex = getShapeHandleHitIndex(activeShape, wX, wY, handleTolerance, rotHitTolerance, curState.scale, density)
                            gestureMode = if (activeHandleIndex == 4) CanvasGestureMode.ROTATE_SHAPE else CanvasGestureMode.RESIZE_SHAPE
                        } else if (activeStroke != null && getStrokeHandleHitIndex(activeStroke, wX, wY, handleTolerance, rotHitTolerance, curState.scale, density) != -1) {
                            activeHandleIndex = getStrokeHandleHitIndex(activeStroke, wX, wY, handleTolerance, rotHitTolerance, curState.scale, density)
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
                            (hitSelectedShape || hitSelectedStroke || hitSelectedNote)
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
                            // Hit test topmost unlocked & visible shapes, notes, strokes
                            val hitShape = curState.board.shapes.lastOrNull { s ->
                                !s.isLocked && !s.isHiddenByCollapse &&
                                isPointHittingShape(s, wX, wY, hitTolerance)
                            }
                            val hitNote = curState.board.stickyNotes.lastOrNull { n ->
                                if (n.isLocked || n.isHiddenByCollapse) {
                                    false
                                } else {
                                    val (lx, ly) = StickyNoteGeometry.toLocalUnrotated(n.x, n.y, n.width, n.height, n.rotation, wX, wY)
                                    lx >= n.x && lx <= (n.x + n.width) && ly >= n.y && ly <= (n.y + n.height)
                                }
                            }
                            val hitStroke = curState.board.strokes.lastOrNull { s ->
                                isPointHittingStroke(s, wX, wY, (24f / curState.scale))
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

                            if (activePointers.size >= 2 || event.changes.any { it.isConsumed }) {
                                isMultiTouch = true
                                isDrawingGestureActive = false
                                localActivePoints.clear()
                                localLassoPoints.clear()
                                pixelEraserCursorWorld = null
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
                                        if (activeHandleIndex == 2 || activeHandleIndex == 3) {
                                            val updatedShape = viewModel.uiState.value.board.shapes.find { it.id == liveState.selectedShapeId }
                                            if (updatedShape != null && updatedShape.vertices.size >= 2) {
                                                val p0 = updatedShape.vertices.first()
                                                val p1 = updatedShape.vertices.last()
                                                val deg = ((kotlin.math.atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble()) * 180.0 / PI).toFloat() % 360f + 360f) % 360f
                                                val midX = ((p0.x + p1.x) * 0.5f) * liveState.scale + liveState.panX
                                                val midY = ((p0.y + p1.y) * 0.5f) * liveState.scale + liveState.panY - with(density) { 48.dp.toPx() }
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
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        viewModel.rotateSelectedShapeToPoint(nwX, nwY)
                                        val updatedShape = viewModel.uiState.value.board.shapes.find { it.id == liveState.selectedShapeId }
                                        if (updatedShape != null) {
                                            val screenX = (updatedShape.x + updatedShape.width / 2f) * liveState.scale + liveState.panX
                                            val screenY = updatedShape.y * liveState.scale + liveState.panY - with(density) { 64.dp.toPx() }
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
                                    CanvasGestureMode.ROTATE_STROKE -> {
                                        if (!hasTransformed) {
                                            viewModel.beginTransformSnapshot()
                                            hasTransformed = true
                                        }
                                        val nwX = (change.position.x - liveState.panX) / liveState.scale
                                        val nwY = (change.position.y - liveState.panY) / liveState.scale
                                        viewModel.rotateSelectedStrokeToPoint(nwX, nwY)
                                        strokeRotCenterWorld?.let { c ->
                                            val curDeg = (kotlin.math.atan2((nwY - c.y).toDouble(), (nwX - c.x).toDouble()) * 180.0 / PI).toFloat()
                                            var delta = (curDeg - strokeRotStartDeg) % 360f
                                            if (delta > 180f) delta -= 360f
                                            if (delta < -180f) delta += 360f
                                            val hudX = c.x * liveState.scale + liveState.panX
                                            val hudY = (activeStroke?.points?.minOfOrNull { it.y } ?: c.y) * liveState.scale + liveState.panY - with(density) { 64.dp.toPx() }
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

                        isDrawingGestureActive = false
                        pixelEraserCursorWorld = null
                        activeRotationHud = null

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
                        snappedDuringHold = false
                        viewModel.resetStrokeDragAngle()
                        pendingDragStart = null
                        pendingDragEnd = null
                        localActivePoints.clear()
                        localLassoPoints.clear()
                    }
                }
        ) {
            val rotStemPx = ShapeCanvasCoordinates.rotationStemScreenPx(density)
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
                    animPhase = animPhase,
                    isSelected = isSelected,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = state.handDrawnFactor
                )
            }

            if (localActivePoints.size >= 2) {
                val activeBrush = viewModel.brushTypeForTool(state.currentTool)
                val previewPoints = StrokeStabilizer.stabilizePoints(localActivePoints.toList(), activeBrush.stabilization)
                val tempStroke = WhiteboardStroke(
                    id = "live_active_stroke",
                    points = previewPoints,
                    colorHex = viewModel.effectiveColorForBrush(activeBrush, state.penColorHex),
                    width = viewModel.effectiveStrokeWidthForBrush(activeBrush, state.penWidth),
                    isHighlighter = activeBrush == StrokeBrushType.HIGHLIGHTER,
                    isRainbow = state.isRainbowPen && activeBrush != StrokeBrushType.HIGHLIGHTER && activeBrush != StrokeBrushType.SOFT_BRUSH,
                    strokeStyle = state.penStyle,
                    capStyle = state.penCapStyle,
                    brushType = activeBrush,
                    opacity = activeBrush.defaultOpacity
                )
                drawStrokeVector(
                    stroke = tempStroke,
                    panX = state.panX,
                    panY = state.panY,
                    scale = state.scale,
                    animPhase = animPhase,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = state.handDrawnFactor
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
                    animPhase = animPhase,
                    isSelected = isSelected,
                    isDarkBoard = isDarkBoard,
                    rotStemPx = rotStemPx,
                    handDrawnFactor = state.handDrawnFactor
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
                    isFilled = state.pendingIsFilled && !state.pendingShapeType!!.isLineLike,
                    strokeStyle = state.pendingStrokeStyle,
                    capStyle = when (state.pendingShapeType!!) {
                        ShapeType.ARROW -> LineCapStyle.END_ARROW
                        ShapeType.DOUBLE_ARROW -> LineCapStyle.BOTH_ARROWS
                        else -> state.pendingCapStyle
                    },
                    vertices = if (state.pendingShapeType!!.isLineLike) listOf(pStart, pEnd) else emptyList(),
                    brushType = state.pendingBrushType
                )
                drawShapeVector(
                    shape = previewShape,
                    panX = state.panX,
                    panY = state.panY,
                    scale = state.scale,
                    animPhase = animPhase,
                    isSelected = true,
                    isDarkBoard = isDarkBoard,
                    rotStemPx = rotStemPx,
                    handDrawnFactor = state.handDrawnFactor
                )
            }

            if (state.selectedStrokeId != null && state.selectedStrokeIds.size <= 1) {
                val selStroke = state.board.strokes.find { it.id == state.selectedStrokeId }
                if (selStroke != null) {
                    drawStrokeSelectionHandles(
                        stroke = selStroke,
                        panX = state.panX,
                        panY = state.panY,
                        scale = state.scale,
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

        // Render Text Items (Locked axis/column labels + Editable Ishikawa cause boxes)
        state.board.textItems.forEach { tItem ->
            if (tItem.isHiddenByCollapse) return@forEach
            val sx = (tItem.x * state.scale + state.panX).roundToInt()
            val sy = (tItem.y * state.scale + state.panY).roundToInt()
            val tColor = parseColor(tItem.colorHex, Color(0xFF0F172A))
            val widthDp = with(density) { (tItem.width * state.scale).coerceAtLeast(80f).toDp() }
            val fontSizeSp = (tItem.fontSize * state.scale).coerceIn(10f, 28f).sp

            if (tItem.isLocked) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(sx, sy) }
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
                val cardBg = tItem.backgroundColorHex?.let { parseColor(it, Color.White) } ?: Color.White
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cardBg,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .offset { IntOffset(sx, sy) }
                        .width(widthDp)
                        .border(1.5.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
                        .testTag("editable_text_item_${tItem.id}")
                ) {
                    BasicTextField(
                        value = tItem.text,
                        onValueChange = { viewModel.updateTextItem(tItem.id, it) },
                        enabled = !isPanOnlyMode,
                        readOnly = isPanOnlyMode,
                        textStyle = TextStyle(
                            color = tColor,
                            fontSize = fontSizeSp,
                            fontWeight = if (tItem.isBold) FontWeight.Bold else FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(tColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("editable_text_input_${tItem.id}")
                    )
                }
            }
        }

        // Render Interactive Template Overlays (+ branch on cluster center, collapse chip on concept nodes, + note in columns, edit label)
        state.board.shapes.forEach { shape ->
            if (shape.isHiddenByCollapse) return@forEach
            val shapeScreenRight = ((shape.x + shape.width) * state.scale + state.panX).roundToInt()
            val shapeScreenCenterX = ((shape.x + shape.width / 2f) * state.scale + state.panX).roundToInt()
            val shapeScreenTop = (shape.y * state.scale + state.panY).roundToInt()
            val shapeScreenBottom = ((shape.y + shape.height) * state.scale + state.panY).roundToInt()

            if (shape.templateRole == "CLUSTER_CENTER") {
                val offsetPx = with(density) { 18.dp.roundToPx() }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .offset { IntOffset(shapeScreenRight - offsetPx, shapeScreenBottom - offsetPx * 2) }
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.addClusterBranch(shape.id) }
                        .testTag("cluster_add_branch_${shape.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Agregar rama al cluster",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (shape.templateRole == "CONCEPT_NODE") {
                val hasChildren = state.board.shapes.any { it.parentId == shape.id && it.templateRole != "CONCEPT_CONNECTOR" } ||
                    state.board.stickyNotes.any { it.parentId == shape.id }
                if (hasChildren) {
                    val hiddenCount = if (shape.isCollapsed) IdeationTemplateFactory.countHiddenDescendants(state.board, shape.id) else 0
                    val badgeHalfW = with(density) { 18.dp.roundToPx() }
                    val badgeHalfH = with(density) { 12.dp.roundToPx() }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (shape.isCollapsed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (shape.isCollapsed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .offset { IntOffset(shapeScreenCenterX - badgeHalfW, shapeScreenBottom - badgeHalfH) }
                            .heightIn(min = 24.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.toggleConceptBranchCollapse(shape.id) }
                            .testTag("concept_toggle_collapse_${shape.id}")
                    ) {
                        Text(
                            text = if (shape.isCollapsed) "+$hiddenCount" else "−",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (shape.templateRole == "COLUMN_CONTAINER") {
                val btnMargin = with(density) { 38.dp.roundToPx() }
                val topPad = with(density) { 8.dp.roundToPx() }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .offset { IntOffset(shapeScreenRight - btnMargin, shapeScreenTop + topPad) }
                        .size(28.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clip(CircleShape)
                        .clickable { viewModel.addNoteToColumn(shape.id) }
                        .testTag("column_add_note_${shape.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Agregar nota a columna",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (shape.id == state.selectedShapeId && shape.label.isNotBlank() && !shape.shapeType.isLineLike) {
                val editHalfW = with(density) { 18.dp.roundToPx() }
                val editTop = with(density) { 32.dp.roundToPx() }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .offset { IntOffset(shapeScreenCenterX - editHalfW, shapeScreenTop - editTop) }
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

        // Render Sticky Notes (unselected first, selected last so resize handles sit cleanly on top)
        val orderedNotes = state.board.stickyNotes
            .filter { !it.isHiddenByCollapse }
            .sortedBy { n ->
                if (n.id == state.selectedNoteId || state.selectedNoteIds.contains(n.id)) 1 else 0
            }

        orderedNotes.forEach { note ->
            val isSelected = note.id == state.selectedNoteId || state.selectedNoteIds.contains(note.id)
            StickyNoteView(
                note = note,
                scale = state.scale,
                panX = state.panX,
                panY = state.panY,
                isSelected = isSelected,
                isPanOnlyMode = isPanOnlyMode,
                onSelect = { viewModel.selectNote(note.id) },
                onBeginTransform = { viewModel.beginTransformSnapshot() },
                onEndTransform = { viewModel.commitWorkspaceSync() },
                onUpdateText = { viewModel.updateStickyNoteText(note.id, it) },
                onUpdateTitle = { viewModel.updateStickyNoteTitle(note.id, it) },
                onUpdateColor = { viewModel.updateStickyNoteColor(note.id, it) },
                onTogglePin = { viewModel.toggleStickyNotePin(note.id) },
                onDelete = {
                    if (note.text.isNotBlank() || !note.title.isNullOrBlank()) {
                        notePendingRemoval = note
                    } else {
                        viewModel.deleteStickyNote(note.id)
                    }
                },
                onDragDelta = { dx, dy ->
                    if (state.selectedNoteIds.contains(note.id) && (state.selectedNoteIds.size + state.selectedStrokeIds.size + state.selectedShapeIds.size) > 1) {
                        viewModel.moveSelectedObjects(dx / state.scale, dy / state.scale)
                    } else {
                        viewModel.moveStickyNote(note.id, dx, dy)
                    }
                },
                onResizeHandle = { handle, dxScreen, dyScreen ->
                    viewModel.resizeStickyNoteHandle(note.id, handle, dxScreen, dyScreen)
                },
                onRotate = { degrees ->
                    viewModel.rotateStickyNote(note.id, degrees)
                }
            )
        }

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
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.size >= 2 || event.changes.any { it.isConsumed }) {
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
                    onUpdateShapeCapStyle = { viewModel.updateSelectedShape(capStyle = it) },
                    onUpdateShapeBrushType = { viewModel.updateSelectedShapeBrushType(it) },
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
                    onUpdateStrokeCapStyle = { viewModel.updateSelectedStrokeCapStyle(it) },
                    onUpdateStrokeBrushType = { viewModel.updateSelectedStrokeBrushType(it) },
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
                    onToggleNoteAspectRatioLock = {
                        selectedNote?.let { viewModel.toggleStickyNoteAspectRatioLock(it.id) }
                    },
                    onDeleteNote = {
                        val target = selectedNote
                        if (target != null && (target.text.isNotBlank() || !target.title.isNullOrBlank())) {
                            notePendingRemoval = target
                        } else {
                            viewModel.deleteSelectedNote()
                        }
                    },
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
                    currentPenCapStyle = state.penCapStyle,
                    currentHandDrawnFactor = state.handDrawnFactor,
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
                    onUpdatePenCapStyle = { viewModel.setPenCapStyle(it) },
                    onUpdateHandDrawnFactor = { viewModel.setHandDrawnFactor(it) },
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
                initialStrokeStyle = state.penStyle,
                initialCapStyle = state.penCapStyle,
                initialBrushType = viewModel.brushTypeForTool(state.currentTool),
                onSelectBrushType = { viewModel.setPendingBrushType(it) },
                onSelectShapeType = { shapeType, isFilled, strokeColor, fillColor, style, capStyle ->
                    viewModel.requestAddCustomShape(
                        type = shapeType,
                        isFilled = isFilled,
                        strokeColor = strokeColor,
                        fillColor = fillColor,
                        style = style,
                        capStyle = capStyle,
                        brushType = viewModel.uiState.value.pendingBrushType
                    )
                },
                onDismiss = { showShapePicker = false }
            )
        }

        if (showTemplatePicker) {
            TemplatePickerModal(
                showOnNewBoard = state.showTemplatesOnNewBoard,
                onToggleShowOnNewBoard = { viewModel.toggleShowTemplatesOnNewBoard(it) },
                onStartBlankCanvas = {
                    val hasAny = state.board.strokes.isNotEmpty() ||
                        state.board.shapes.isNotEmpty() ||
                        state.board.stickyNotes.isNotEmpty() ||
                        state.board.textItems.isNotEmpty()
                    if (hasAny) {
                        viewModel.clearBoard()
                    }
                    showTemplatePicker = false
                },
                onSelectTemplate = { viewModel.applyTemplate(it, screenWidthPx, screenHeightPx) },
                onDismiss = { showTemplatePicker = false },
                customTemplates = state.customTemplates,
                currentBoardTitle = state.board.title,
                onSaveCurrentAsTemplate = { title, desc ->
                    val saved = viewModel.saveCurrentBoardAsTemplate(title, desc)
                    Toast.makeText(context, "Plantilla '${saved.title}' guardada", Toast.LENGTH_SHORT).show()
                },
                onSelectCustomTemplate = { tmpl ->
                    val ok = viewModel.applyCustomTemplate(tmpl)
                    if (!ok) {
                        Toast.makeText(context, "No se pudo aplicar esta plantilla", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteCustomTemplate = { templateId ->
                    viewModel.deleteCustomTemplate(templateId)
                }
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

