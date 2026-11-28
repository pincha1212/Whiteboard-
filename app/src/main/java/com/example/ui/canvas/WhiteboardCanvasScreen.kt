package com.example.ui.canvas

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.example.ink.BoxResizeHandle
import com.example.ink.GuidesGeometry
import com.example.ink.PerspectiveAxisLock
import com.example.ink.StrokeStabilizer
import com.example.ink.ViewportTransform
import com.example.ink.voice.LiveSpeechDictationController
import com.example.model.BrushStudioSettings
import com.example.model.DrawingLayer
import com.example.model.GuideType
import com.example.model.LineCapStyle
import com.example.model.PerspectiveMode
import com.example.model.ShapeItem
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.SymmetryMode
import com.example.model.TextItem
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.model.isLineLike
import com.example.ui.theme.StudioIcons
import com.example.ui.toolbar.BrushStudioSheet
import com.example.ui.toolbar.DockLayoutMode
import com.example.ui.toolbar.WhiteboardPenDock
import com.example.ui.toolbar.WhiteboardTopBar
import com.example.viewmodel.WhiteboardUiState
import com.example.viewmodel.WhiteboardViewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class CanvasGestureMode {
    NONE,
    DRAW_STROKE,
    SMUDGE,
    PAN,
    MOVE_SHAPE,
    RESIZE_SHAPE,
    ROTATE_SHAPE,
    MOVE_STROKE,
    RESIZE_STROKE,
    ROTATE_STROKE,
    MOVE_SYMMETRY_CENTER,
    EYEDROPPER,
    LASSO_SELECT,
    ERASE_OBJECT,
    ERASE_PIXEL
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WhiteboardCanvasScreen(
    viewModel: WhiteboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showBackgroundSettings by remember { mutableStateOf(false) }
    var showExportModal by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editingLabelShape by remember { mutableStateOf<ShapeItem?>(null) }
    var editingLabelText by remember { mutableStateOf("") }
    var newTitleInput by remember { mutableStateOf(state.board.title) }
    var canvasSizePx by remember { mutableStateOf(IntSize(1080, 1920)) }
    val density = LocalDensity.current

    var pixelEraserCursorWorld by remember { mutableStateOf<StrokePoint?>(null) }
    var smudgeCursorWorld by remember { mutableStateOf<StrokePoint?>(null) }
    var eyedropperPreviewState by remember { mutableStateOf<Pair<Offset, String>?>(null) }
    val activeRotationHudState = remember { mutableStateOf<Pair<Offset, String>?>(null) }
    val textMeasuredHeights = remember { mutableStateMapOf<String, Float>() }

    val localActivePoints = remember { mutableStateListOf<StrokePoint>() }
    val livePreviewFilter = remember { StrokeStabilizer.IncrementalLivePreviewFilter() }
    val localLassoPoints = remember { mutableStateListOf<StrokePoint>() }
    val lassoPath = remember { Path() }
    val coroutineScope = rememberCoroutineScope()

    val dictationController = remember(context, viewModel) {
        LiveSpeechDictationController(
            context = context,
            scope = coroutineScope,
            onTextStreamed = { targetId, streamedText ->
                viewModel.streamDictatedTextToItem(targetId, streamedText)
            },
            onPhraseCommitted = { targetId, committedText ->
                viewModel.commitDictationPhrase(targetId, committedText)
            },
            onRmsChanged = { rms ->
                viewModel.updateDictationAudioLevel(rms)
            },
            onDictationStopped = { errorMessage ->
                viewModel.stopVoiceDictationState(persist = true, statusMessage = errorMessage)
            }
        )
    }

    val beginDictationSession = remember(context, viewModel, dictationController) {
        {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                viewModel.showDictationStatusMessage("El dictado por voz no está disponible en este dispositivo.")
            } else {
                val (targetId, seed) = viewModel.prepareDictationTarget()
                viewModel.startVoiceDictationState(targetId)
                dictationController.start(targetId = targetId, initialSeed = seed)
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            beginDictationSession()
        } else {
            viewModel.showDictationStatusMessage("Permiso de micrófono necesario para dictar en vivo.")
        }
    }

    val onToggleVoiceDictation = remember(context, viewModel, dictationController, beginDictationSession, audioPermissionLauncher) {
        {
            if (viewModel.uiState.value.isVoiceDictating) {
                dictationController.stop()
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    beginDictationSession()
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    DisposableEffect(dictationController) {
        onDispose {
            dictationController.dispose()
        }
    }

    LaunchedEffect(state.isVoiceDictating) {
        if (!state.isVoiceDictating) {
            // Ensure recognizer stops cleanly if target item was deleted or board closed
            dictationController.dispose()
        }
    }

    LaunchedEffect(state.dictationStatusMessage, state.isVoiceDictating) {
        if (!state.isVoiceDictating && state.dictationStatusMessage != null) {
            delay(3600L)
            viewModel.clearDictationStatusMessage()
        }
    }

    val activePreviewBrush = viewModel.brushTypeForTool(state.currentTool)
    val activeSlotStudioSettings = viewModel.brushSlots.getOrNull(state.activeBrushSlotIndex)?.studioSettings
        ?: BrushStudioSettings.defaultFor(activePreviewBrush)
    val livePreviewStroke = remember(
        localActivePoints,
        state.currentTool,
        state.penColorHex,
        state.penWidth,
        state.penOpacity,
        state.isRainbowPen,
        activeSlotStudioSettings
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
            opacity = state.penOpacity.coerceIn(0.05f, 1.0f),
            studioSettings = activeSlotStudioSettings
        )
    }

    val canvasBg = try {
        Color(android.graphics.Color.parseColor(state.board.colorTheme.hex))
    } catch (_: Exception) {
        Color.White
    }

    val isPanOnlyMode = state.currentTool == ToolType.HAND

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

    val selectedShapeForLabel = remember(state.board.shapes, state.selectedShapeId) {
        state.board.shapes.firstOrNull {
            it.id == state.selectedShapeId && !it.isHiddenByCollapse && !it.shapeType.isLineLike
        }
    }
    val selectedStrokeForHandles = remember(state.board.strokes, state.selectedStrokeId) {
        state.board.strokes.firstOrNull { it.id == state.selectedStrokeId }
    }

    val selectedStrokeIdSet = remember(state.selectedStrokeIds) { state.selectedStrokeIds.toHashSet() }
    val selectedShapeIdSet = remember(state.selectedShapeIds) { state.selectedShapeIds.toHashSet() }

    val isDarkBoard = state.board.colorTheme.isDark
    val paintTileCache = viewModel.sharedPaintTileCache

    val visibleLayerIds = remember(state.board.layers) {
        val layers = state.board.layers.ifEmpty { listOf(DrawingLayer()) }
        layers.filter { it.isVisible }.map { it.id }.toHashSet()
    }
    val paintSpatialIndex = remember(
        state.board.id,
        state.board.layers,
        state.board.strokes,
        state.board.shapes,
        state.selectedStrokeId,
        selectedStrokeIdSet,
        state.selectedShapeId,
        selectedShapeIdSet,
        isDarkBoard,
        state.handDrawnFactor
    ) {
        paintTileCache.buildSpatialIndex(
            boardId = state.board.id,
            strokes = state.board.strokes,
            shapes = state.board.shapes,
            selectedStrokeId = state.selectedStrokeId,
            selectedStrokeIds = selectedStrokeIdSet,
            selectedShapeId = state.selectedShapeId,
            selectedShapeIds = selectedShapeIdSet,
            isDarkBoard = isDarkBoard,
            handDrawnFactor = state.handDrawnFactor,
            layers = state.board.layers
        )
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
            val visibleTextItems by remember(
                state.board.textItems,
                cameraState,
                screenWidthPx,
                screenHeightPx,
                state.selectedTextId,
                state.selectedTextIds
            ) {
                derivedStateOf {
                    val camera = cameraState.value
                    val safeScale = camera.scale.coerceAtLeast(0.1f)
                    val margin = 180f / safeScale
                    val visibleLeft = -camera.panX / safeScale - margin
                    val visibleTop = -camera.panY / safeScale - margin
                    val visibleRight = (screenWidthPx - camera.panX) / safeScale + margin
                    val visibleBottom = (screenHeightPx - camera.panY) / safeScale + margin
                    state.board.textItems.filter { item ->
                        if (item.isHiddenByCollapse) {
                            false
                        } else if (
                            item.id == state.selectedTextId ||
                            state.selectedTextIds.contains(item.id)
                        ) {
                            true
                        } else {
                            val fontSize = item.fontSize.coerceIn(14f, 80f)
                            val charsPerLine = (item.width.coerceAtLeast(60f) / (fontSize * 0.55f))
                                .toInt().coerceAtLeast(1)
                            val lineCount = ((item.text.length + charsPerLine - 1) / charsPerLine)
                                .coerceAtLeast(1)
                            val estimatedHeight = maxOf(item.height, fontSize * 2.5f, lineCount * fontSize * 1.5f)
                            val radius = hypot(item.width, estimatedHeight) * 0.5f
                            val centerX = item.x + item.width * 0.5f
                            val centerY = item.y + estimatedHeight * 0.5f
                            centerX + radius >= visibleLeft &&
                                centerX - radius <= visibleRight &&
                                centerY + radius >= visibleTop &&
                                centerY - radius <= visibleBottom
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput("global_viewport_multitouch") {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val gestureStartMs = down.uptimeMillis
                            var maxPointerCount = 1
                            var totalPanMovement = 0f
                            var didPinchZoom = false
                            var lastEventTimeMs = gestureStartMs

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                var activePointerCount = 0
                                for (change in event.changes) {
                                    if (change.pressed) {
                                        activePointerCount++
                                        if (change.uptimeMillis > lastEventTimeMs) {
                                            lastEventTimeMs = change.uptimeMillis
                                        }
                                    }
                                }
                                if (activePointerCount > maxPointerCount) {
                                    maxPointerCount = activePointerCount
                                }
                                if (activePointerCount >= 2) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    totalPanMovement += hypot(pan.x, pan.y)
                                    if (kotlin.math.abs(zoom - 1f) > 0.03f) {
                                        didPinchZoom = true
                                    }
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val fx = if (centroid != Offset.Unspecified) centroid.x else null
                                    val fy = if (centroid != Offset.Unspecified) centroid.y else null
                                    viewModel.onPanZoom(pan.x, pan.y, zoom, fx, fy)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })

                            val durationMs = lastEventTimeMs - gestureStartMs
                            if ((maxPointerCount == 2 || maxPointerCount == 3) &&
                                durationMs < 260L &&
                                totalPanMovement < 22f &&
                                !didPinchZoom
                            ) {
                                if (maxPointerCount == 2) {
                                    viewModel.undo()
                                } else {
                                    viewModel.redo()
                                }
                            }
                        }
                    }
            ) {
                // Layer 1: Background & Grid Canvas (invalidates only on camera pan/zoom or board grid/theme changes)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val camera = cameraState.value
                    drawCanvasBackgroundAndGrid(
                        gridType = state.board.gridType,
                        colorTheme = state.board.colorTheme,
                        panX = camera.panX,
                        panY = camera.panY,
                        scale = camera.scale,
                        screenW = size.width,
                        screenH = size.height
                    )
                }

                // Layer 2: Committed Board Tiles + Selected Vectors + Symmetry Guides (never invalidates during pointer MOVE!)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val camera = cameraState.value
                    val rotStemPx = ShapeCanvasCoordinates.rotationStemScreenPx(density)
                    val safeScale = if (camera.scale > 0.001f) camera.scale else 1.0f
                    val visibleWorldLeft = -camera.panX / safeScale
                    val visibleWorldTop = -camera.panY / safeScale
                    val visibleWorldRight = (size.width - camera.panX) / safeScale
                    val visibleWorldBottom = (size.height - camera.panY) / safeScale
                    val usePaintRaster = paintSpatialIndex != null

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
                                if (!visibleLayerIds.contains(stroke.layerId)) return@forEach
                                val isSelected = stroke.id == state.selectedStrokeId || selectedStrokeIdSet.contains(stroke.id)
                                if (!isSelected) return@forEach
                                val bounds = stroke.geometryMetrics
                                if (bounds.hasPoints) {
                                    val pad = (stroke.width * 3.5f + 64f) / safeScale
                                    if (bounds.maxX + pad < visibleWorldLeft || bounds.minX - pad > visibleWorldRight ||
                                        bounds.maxY + pad < visibleWorldTop || bounds.minY - pad > visibleWorldBottom
                                    ) {
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
                            if (!visibleLayerIds.contains(stroke.layerId)) return@forEach
                            val isSelected = stroke.id == state.selectedStrokeId || selectedStrokeIdSet.contains(stroke.id)
                            val bounds = stroke.geometryMetrics
                            if (bounds.hasPoints) {
                                val pad = (stroke.width * 3.5f + 64f) / safeScale
                                if (bounds.maxX + pad < visibleWorldLeft || bounds.minX - pad > visibleWorldRight ||
                                    bounds.maxY + pad < visibleWorldTop || bounds.minY - pad > visibleWorldBottom
                                ) {
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
                                if (shape.isHiddenByCollapse || !visibleLayerIds.contains(shape.layerId)) return@forEach
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
                            if (hypot(cached.centerX - viewportCenterX, cached.centerY - viewportCenterY) >
                                cached.radius + viewportRadius
                            ) {
                                return@forEach
                            }
                            val shape = cached.shape
                            if (shape.isHiddenByCollapse || !visibleLayerIds.contains(shape.layerId)) return@forEach
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
                            if (visibleLayerIds.contains(selStroke.layerId)) {
                                drawStrokeSelectionHandles(
                                    stroke = selStroke,
                                    panX = camera.panX,
                                    panY = camera.panY,
                                    scale = camera.scale,
                                    rotStemPx = rotStemPx
                                )
                            }
                        }
                    }

                    if (state.symmetryMode != SymmetryMode.NONE) {
                        val guideColor = Color(0xFF0EA5E9)
                        val symScreenX = state.symmetryCenterX * camera.scale + camera.panX
                        val symScreenY = state.symmetryCenterY * camera.scale + camera.panY
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                        if (state.symmetryMode == SymmetryMode.VERTICAL || state.symmetryMode == SymmetryMode.QUAD) {
                            drawLine(
                                color = guideColor.copy(alpha = 0.72f),
                                start = Offset(symScreenX, 0f),
                                end = Offset(symScreenX, size.height),
                                strokeWidth = 2f,
                                pathEffect = dashEffect
                            )
                        }
                        if (state.symmetryMode == SymmetryMode.HORIZONTAL || state.symmetryMode == SymmetryMode.QUAD) {
                            drawLine(
                                color = guideColor.copy(alpha = 0.72f),
                                start = Offset(0f, symScreenY),
                                end = Offset(size.width, symScreenY),
                                strokeWidth = 2f,
                                pathEffect = dashEffect
                            )
                        }
                        drawCircle(
                            color = Color.White.copy(alpha = 0.9f),
                            radius = 11f,
                            center = Offset(symScreenX, symScreenY)
                        )
                        drawCircle(
                            color = guideColor,
                            radius = 11f,
                            center = Offset(symScreenX, symScreenY),
                            style = Stroke(width = 3f)
                        )
                        drawCircle(
                            color = guideColor,
                            radius = 4f,
                            center = Offset(symScreenX, symScreenY)
                        )
                    }
                }

                // Layer 3: Lightweight Live Active Stroke + Lasso + Cursor Overlay + Touch Input
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
                                livePreviewFilter.reset()
                                pixelEraserCursorWorld = null

                                val curState = viewModel.uiState.value
                                val curCamera = viewModel.cameraState.value
                                val wX = (down.position.x - curCamera.panX) / curCamera.scale
                                val wY = (down.position.y - curCamera.panY) / curCamera.scale
                                val handleTolerance = with(density) { 24.dp.toPx() } / curCamera.scale
                                val rotHitTolerance = with(density) { 28.dp.toPx() } / curCamera.scale
                                val hitTolerance = with(density) { 18.dp.toPx() } / curCamera.scale

                                var strokeOriginWorld: StrokePoint? = null
                                var perspectiveAxisLock = PerspectiveAxisLock.NONE
                                var didUsePrecisionGuide = false

                                fun projectWithGuides(raw: StrokePoint, st: WhiteboardUiState): StrokePoint {
                                    if (st.isRulerActive) {
                                        val projected = StrokeStabilizer.projectPointToRulerIfNear(raw, st.rulerState)
                                        if (projected.x != raw.x || projected.y != raw.y) didUsePrecisionGuide = true
                                        return projected
                                    }
                                    if (st.guidesState.ellipse.isVisible) {
                                        val projected = GuidesGeometry.projectPointToEllipseGuide(raw, st.guidesState.ellipse)
                                        if (projected.x != raw.x || projected.y != raw.y) didUsePrecisionGuide = true
                                        return projected
                                    }
                                    if (st.guidesState.frenchCurve.isVisible) {
                                        val projected = GuidesGeometry.projectPointToFrenchCurveGuide(raw, st.guidesState.frenchCurve)
                                        if (projected.x != raw.x || projected.y != raw.y) didUsePrecisionGuide = true
                                        return projected
                                    }
                                    val persp = st.guidesState.perspective
                                    val origin = strokeOriginWorld
                                    if (persp.mode != PerspectiveMode.OFF && persp.isSnapEnabled && origin != null) {
                                        if (perspectiveAxisLock == PerspectiveAxisLock.NONE) {
                                            perspectiveAxisLock = GuidesGeometry.detectPerspectiveAxisLock(origin, raw, persp)
                                        }
                                        if (perspectiveAxisLock != PerspectiveAxisLock.NONE) {
                                            didUsePrecisionGuide = true
                                            return GuidesGeometry.projectPointOnPerspectiveLock(origin, raw, perspectiveAxisLock, persp)
                                        }
                                    }
                                    return raw
                                }

                                val isDrawingTool = curState.currentTool == ToolType.PENCIL ||
                                    curState.currentTool == ToolType.PEN ||
                                    curState.currentTool == ToolType.TECHNICAL_INK ||
                                    curState.currentTool == ToolType.FINE_MARKER ||
                                    curState.currentTool == ToolType.CHISEL_MARKER ||
                                    curState.currentTool == ToolType.PAINT_BRUSH ||
                                    curState.currentTool == ToolType.WATERCOLOR ||
                                    curState.currentTool == ToolType.AIRBRUSH ||
                                    curState.currentTool == ToolType.HIGHLIGHTER

                                val boardLayers = curState.board.layers.ifEmpty { listOf(DrawingLayer()) }
                                val editableLayerIds = boardLayers.filter { it.isVisible && !it.isLocked }.map { it.id }.toHashSet()

                                val activeShape = curState.board.shapes.find {
                                    it.id == curState.selectedShapeId && !it.isLocked && !it.isHiddenByCollapse && editableLayerIds.contains(it.layerId)
                                }
                                val activeStroke = curState.board.strokes.find {
                                    it.id == curState.selectedStrokeId && editableLayerIds.contains(it.layerId)
                                }
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
                                        editableLayerIds.contains(s.layerId) &&
                                        (s.id == curState.selectedShapeId || selectedShapeIds.contains(s.id)) &&
                                        isPointHittingShape(s, wX, wY, hitTolerance)
                                }
                                val hitSelectedStroke = curState.board.strokes.any { s ->
                                    editableLayerIds.contains(s.layerId) &&
                                        (s.id == curState.selectedStrokeId || selectedStrokeIds.contains(s.id)) &&
                                        isPointHittingStroke(s, wX, wY, hitTolerance)
                                }

                                val hitSymmetryCenter = curState.symmetryMode != SymmetryMode.NONE &&
                                    hypot(wX - curState.symmetryCenterX, wY - curState.symmetryCenterY) <= handleTolerance

                                if (curState.currentTool == ToolType.HAND) {
                                    gestureMode = CanvasGestureMode.PAN
                                } else if (curState.currentTool == ToolType.EYEDROPPER) {
                                    val sampledHex = viewModel.sampleCanvasColorAt(wX, wY, curState.board.colorTheme.hex)
                                    eyedropperPreviewState = down.position to sampledHex
                                    gestureMode = CanvasGestureMode.EYEDROPPER
                                } else if (curState.currentTool == ToolType.FLOOD_FILL) {
                                    if (!viewModel.canDrawOnActiveLayer()) {
                                        Toast.makeText(context, "La capa activa está oculta o bloqueada", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val res = viewModel.applyFloodFillTap(wX, wY)
                                        if (res.didHitRegionEdge) {
                                            Toast.makeText(context, "Área abierta: rellenado hasta el límite activo", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    gestureMode = CanvasGestureMode.NONE
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
                                } else if (hitSymmetryCenter) {
                                    gestureMode = CanvasGestureMode.MOVE_SYMMETRY_CENTER
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
                                } else if (curState.currentTool == ToolType.SMUDGE) {
                                    if (!viewModel.canDrawOnActiveLayer()) {
                                        Toast.makeText(context, "La capa activa está oculta o bloqueada", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.beginRasterToolGesture()
                                        val downPt = StrokePoint(wX, wY, down.uptimeMillis, down.pressure.coerceIn(0.2f, 1.5f))
                                        smudgeCursorWorld = downPt
                                        localActivePoints.clear()
                                        localActivePoints.add(downPt)
                                        gestureMode = CanvasGestureMode.SMUDGE
                                    }
                                } else if (isDrawingTool) {
                                    if (!viewModel.canDrawOnActiveLayer()) {
                                        Toast.makeText(context, "La capa activa está oculta o bloqueada", Toast.LENGTH_SHORT).show()
                                    } else {
                                        localActivePoints.clear()
                                        val rawStart = StrokePoint(wX, wY, down.uptimeMillis, down.pressure.coerceIn(0.2f, 1.5f))
                                        val projectedStart = projectWithGuides(rawStart, curState)
                                        strokeOriginWorld = projectedStart
                                        localActivePoints.add(projectedStart)
                                        gestureMode = CanvasGestureMode.DRAW_STROKE
                                    }
                                } else if (curState.currentTool == ToolType.ERASER) {
                                    val eraseTol = 24f / curCamera.scale
                                    viewModel.eraseObjectAt(wX, wY, eraseTol, saveSnapshot = true)
                                    gestureMode = CanvasGestureMode.ERASE_OBJECT
                                } else if (curState.currentTool == ToolType.PIXEL_ERASER || curState.currentTool == ToolType.SOFT_ERASER) {
                                    val isSoft = curState.currentTool == ToolType.SOFT_ERASER
                                    val eraseRadius = if (isSoft) {
                                        (curState.penWidth * 1.15f).coerceIn(10f, 90f)
                                    } else {
                                        (curState.penWidth * 2.2f).coerceIn(14f, 44f) / curCamera.scale
                                    }
                                    pixelEraserCursorWorld = StrokePoint(wX, wY)
                                    if (isSoft) {
                                        viewModel.beginRasterToolGesture()
                                        if (viewModel.applySoftEraserSegment(StrokePoint(wX, wY), StrokePoint(wX, wY), eraseRadius)) {
                                            hasTransformed = true
                                        }
                                    } else {
                                        if (viewModel.erasePixelAt(wX, wY, eraseRadius, saveSnapshot = true)) {
                                            hasTransformed = true
                                        }
                                    }
                                    gestureMode = CanvasGestureMode.ERASE_PIXEL
                                } else {
                                    val hitShape = curState.board.shapes.lastOrNull { s ->
                                        !s.isLocked && !s.isHiddenByCollapse &&
                                            editableLayerIds.contains(s.layerId) &&
                                            isPointHittingShape(s, wX, wY, hitTolerance)
                                    }
                                    val hitStroke = curState.board.strokes.lastOrNull { s ->
                                        editableLayerIds.contains(s.layerId) &&
                                            isPointHittingStroke(s, wX, wY, 24f / curCamera.scale)
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
                                    } else {
                                        viewModel.clearSelection()
                                    }
                                }

                                var lastDrawPointerPosition = down.position
                                var lastDrawTimestampMs = down.uptimeMillis
                                var lastDrawPressure = down.pressure.coerceIn(0.2f, 1.5f)
                                do {
                                    val event = awaitPointerEvent()
                                    val drawPointerChange = event.changes.firstOrNull { it.id == down.id }
                                    if (gestureMode == CanvasGestureMode.DRAW_STROKE && drawPointerChange != null) {
                                        lastDrawPointerPosition = drawPointerChange.position
                                        lastDrawTimestampMs = drawPointerChange.uptimeMillis
                                        lastDrawPressure = drawPointerChange.pressure.coerceIn(0.2f, 1.5f)
                                    }
                                    var activePointerCount = 0
                                    var activePointer: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                    for (change in event.changes) {
                                        if (change.pressed) {
                                            activePointerCount++
                                            if (activePointerCount == 1) activePointer = change
                                        }
                                    }

                                    if (activePointerCount >= 2) {
                                        isMultiTouch = true
                                        localActivePoints.clear()
                                        localLassoPoints.clear()
                                        pixelEraserCursorWorld = null
                                        smudgeCursorWorld = null
                                    } else if (activePointerCount == 1 && !isMultiTouch) {
                                        val change = activePointer!!
                                        val liveState = viewModel.uiState.value
                                        val liveCamera = viewModel.cameraState.value

                                        when (gestureMode) {
                                            CanvasGestureMode.RESIZE_SHAPE -> {
                                                if (!hasTransformed) {
                                                    viewModel.beginCurrentSelectionTransformSnapshot()
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
                                                        activeRotationHudState.value = Offset(midX, midY) to "${deg.roundToInt()}°"
                                                    }
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.ROTATE_SHAPE -> {
                                                if (!hasTransformed) {
                                                    viewModel.beginCurrentSelectionTransformSnapshot()
                                                    hasTransformed = true
                                                }
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                viewModel.rotateSelectedShapeToPoint(nwX, nwY)
                                                val updatedShape = viewModel.uiState.value.board.shapes.find { it.id == liveState.selectedShapeId }
                                                if (updatedShape != null) {
                                                    val screenX = (updatedShape.x + updatedShape.width / 2f) * liveCamera.scale + liveCamera.panX
                                                    val screenY = updatedShape.y * liveCamera.scale + liveCamera.panY - with(density) { 64.dp.toPx() }
                                                    activeRotationHudState.value = Offset(screenX, screenY) to "${updatedShape.rotation.roundToInt()}°"
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.MOVE_SHAPE -> {
                                                val pan = event.calculatePan()
                                                if (pan.x != 0f || pan.y != 0f) {
                                                    if (!hasTransformed) {
                                                        viewModel.beginCurrentSelectionTransformSnapshot()
                                                        hasTransformed = true
                                                    }
                                                    viewModel.moveSelectedShape(pan.x / liveCamera.scale, pan.y / liveCamera.scale)
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.RESIZE_STROKE -> {
                                                if (!hasTransformed) {
                                                    viewModel.beginCurrentSelectionTransformSnapshot()
                                                    hasTransformed = true
                                                }
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                viewModel.resizeSelectedStrokeBounds(activeHandleIndex, nwX, nwY)
                                                change.consume()
                                            }
                                            CanvasGestureMode.ROTATE_STROKE -> {
                                                if (!hasTransformed) {
                                                    viewModel.beginCurrentSelectionTransformSnapshot()
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
                                                    activeRotationHudState.value = Offset(hudX, hudY) to "${delta.roundToInt()}°"
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.MOVE_STROKE -> {
                                                val pan = event.calculatePan()
                                                if (pan.x != 0f || pan.y != 0f) {
                                                    if (!hasTransformed) {
                                                        viewModel.beginCurrentSelectionTransformSnapshot()
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
                                            CanvasGestureMode.SMUDGE -> {
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                val prevPt = smudgeCursorWorld ?: StrokePoint(nwX, nwY)
                                                val nextPt = StrokePoint(nwX, nwY, change.uptimeMillis, change.pressure.coerceIn(0.2f, 1.5f))
                                                smudgeCursorWorld = nextPt
                                                val smudgeRadius = (liveState.penWidth * 1.15f).coerceIn(10f, 84f)
                                                if (viewModel.applySmudgeSegment(listOf(prevPt, nextPt), smudgeRadius)) {
                                                    hasTransformed = true
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.DRAW_STROKE -> {
                                                for (historical in change.historical) {
                                                    val hx = (historical.position.x - liveCamera.panX) / liveCamera.scale
                                                    val hy = (historical.position.y - liveCamera.panY) / liveCamera.scale
                                                    val rawHistorical = StrokePoint(
                                                        x = hx,
                                                        y = hy,
                                                        timestampMs = historical.uptimeMillis,
                                                        pressure = change.pressure.coerceIn(0.2f, 1.5f)
                                                    )
                                                    val historicalPoint = projectWithGuides(rawHistorical, liveState)
                                                    val previous = localActivePoints.lastOrNull()
                                                    if (previous == null || previous.x != historicalPoint.x || previous.y != historicalPoint.y) {
                                                        localActivePoints.add(historicalPoint)
                                                    }
                                                }
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                val rawPoint = StrokePoint(
                                                    x = nwX,
                                                    y = nwY,
                                                    timestampMs = change.uptimeMillis,
                                                    pressure = change.pressure.coerceIn(0.2f, 1.5f)
                                                )
                                                val point = projectWithGuides(rawPoint, liveState)
                                                val previous = localActivePoints.lastOrNull()
                                                if (previous == null || previous.x != point.x || previous.y != point.y) {
                                                    localActivePoints.add(point)
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.ERASE_OBJECT -> {
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                val eraseTolerance = 24f / liveCamera.scale
                                                viewModel.eraseObjectAt(nwX, nwY, eraseTolerance, saveSnapshot = true)
                                                change.consume()
                                            }
                                            CanvasGestureMode.MOVE_SYMMETRY_CENTER -> {
                                                val pan = event.calculatePan()
                                                if (pan != Offset.Zero) {
                                                    viewModel.moveSymmetryCenter(pan.x / liveCamera.scale, pan.y / liveCamera.scale)
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.EYEDROPPER -> {
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                val sampledHex = viewModel.sampleCanvasColorAt(nwX, nwY, liveState.board.colorTheme.hex)
                                                eyedropperPreviewState = change.position to sampledHex
                                                change.consume()
                                            }
                                            CanvasGestureMode.ERASE_PIXEL -> {
                                                val nwX = (change.position.x - liveCamera.panX) / liveCamera.scale
                                                val nwY = (change.position.y - liveCamera.panY) / liveCamera.scale
                                                val isSoft = liveState.currentTool == ToolType.SOFT_ERASER
                                                val eraseRadius = if (isSoft) {
                                                    (liveState.penWidth * 1.15f).coerceIn(10f, 90f)
                                                } else {
                                                    (liveState.penWidth * 2.2f).coerceIn(14f, 44f) / liveCamera.scale
                                                }
                                                val prevCursor = pixelEraserCursorWorld ?: StrokePoint(nwX, nwY)
                                                val nextCursor = StrokePoint(nwX, nwY)
                                                pixelEraserCursorWorld = nextCursor
                                                val didErase = if (isSoft) {
                                                    viewModel.applySoftEraserSegment(prevCursor, nextCursor, eraseRadius)
                                                } else {
                                                    viewModel.erasePixelSegment(prevCursor, nextCursor, eraseRadius, saveSnapshot = !hasTransformed)
                                                }
                                                if (didErase) {
                                                    hasTransformed = true
                                                }
                                                change.consume()
                                            }
                                            CanvasGestureMode.NONE -> {}
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (gestureMode == CanvasGestureMode.DRAW_STROKE && !isMultiTouch) {
                                    val releaseCamera = viewModel.cameraState.value
                                    val releaseState = viewModel.uiState.value
                                    val rawFinal = StrokePoint(
                                        (lastDrawPointerPosition.x - releaseCamera.panX) / releaseCamera.scale,
                                        (lastDrawPointerPosition.y - releaseCamera.panY) / releaseCamera.scale,
                                        timestampMs = lastDrawTimestampMs,
                                        pressure = lastDrawPressure
                                    )
                                    val finalPoint = projectWithGuides(rawFinal, releaseState)
                                    val previousPoint = localActivePoints.lastOrNull()
                                    if (previousPoint == null ||
                                        hypot(finalPoint.x - previousPoint.x, finalPoint.y - previousPoint.y) >= 0.35f / releaseCamera.scale
                                    ) {
                                        localActivePoints.add(finalPoint)
                                    }
                                }

                                pixelEraserCursorWorld = null
                                smudgeCursorWorld = null
                                activeRotationHudState.value = null

                                if (gestureMode == CanvasGestureMode.SMUDGE ||
                                    (gestureMode == CanvasGestureMode.ERASE_PIXEL && state.currentTool == ToolType.SOFT_ERASER)
                                ) {
                                    viewModel.commitRasterToolGesture()
                                } else if (gestureMode == CanvasGestureMode.EYEDROPPER && !isMultiTouch) {
                                    val pickedHex = eyedropperPreviewState?.second
                                    eyedropperPreviewState = null
                                    if (pickedHex != null) {
                                        viewModel.applyEyedropperColor(pickedHex, returnToBrush = true)
                                    }
                                } else if (gestureMode == CanvasGestureMode.LASSO_SELECT && !isMultiTouch) {
                                    if (localLassoPoints.size >= 5) {
                                        viewModel.selectByLasso(localLassoPoints.toList())
                                    } else {
                                        viewModel.clearSelection()
                                    }
                                } else if (gestureMode == CanvasGestureMode.DRAW_STROKE && localActivePoints.size >= 2 && !isMultiTouch) {
                                    val pointsCopy = localActivePoints.toList()
                                    viewModel.commitCompletedStroke(
                                        points = pointsCopy,
                                        isHighlighter = state.currentTool == ToolType.HIGHLIGHTER,
                                        skipPrediction = didUsePrecisionGuide || perspectiveAxisLock != PerspectiveAxisLock.NONE
                                    )
                                } else if (hasTransformed && (
                                        gestureMode == CanvasGestureMode.ROTATE_SHAPE ||
                                            gestureMode == CanvasGestureMode.ROTATE_STROKE ||
                                            gestureMode == CanvasGestureMode.RESIZE_SHAPE ||
                                            gestureMode == CanvasGestureMode.RESIZE_STROKE ||
                                            gestureMode == CanvasGestureMode.MOVE_SHAPE ||
                                            gestureMode == CanvasGestureMode.MOVE_STROKE
                                        )
                                ) {
                                    viewModel.commitCurrentSelectionTransform()
                                }
                                eyedropperPreviewState = null
                                viewModel.resetStrokeDragAngle()
                                localActivePoints.clear()
                                livePreviewFilter.reset()
                                localLassoPoints.clear()
                            }
                        }
                ) {
                    val camera = cameraState.value

                    if (localActivePoints.size >= 2 && state.currentTool != ToolType.SMUDGE) {
                        val hasActiveShapeGuide = state.isRulerActive ||
                            state.guidesState.ellipse.isVisible ||
                            state.guidesState.frenchCurve.isVisible
                        val previewPoints = if (!hasActiveShapeGuide && state.predictionLevel > 0) {
                            livePreviewFilter.update(localActivePoints, state.predictionLevel)
                        } else {
                            localActivePoints
                        }
                        val previewStrokeToRender = if (previewPoints === localActivePoints) {
                            livePreviewStroke
                        } else {
                            livePreviewStroke.copy(points = previewPoints)
                        }
                        drawStrokeVector(
                            stroke = previewStrokeToRender,
                            panX = camera.panX,
                            panY = camera.panY,
                            scale = camera.scale,
                            animPhase = 0f,
                            preserveInputPoints = true,
                            isDarkBoard = isDarkBoard,
                            handDrawnFactor = state.handDrawnFactor
                        )
                        if (state.symmetryMode != SymmetryMode.NONE) {
                            val mirroredBranches = viewModel.mirrorPointsForSymmetry(
                                points = previewPoints,
                                mode = state.symmetryMode,
                                cx = state.symmetryCenterX,
                                cy = state.symmetryCenterY
                            )
                            mirroredBranches.forEach { mirroredPts ->
                                drawStrokeVector(
                                    stroke = livePreviewStroke.copy(points = mirroredPts),
                                    panX = camera.panX,
                                    panY = camera.panY,
                                    scale = camera.scale,
                                    animPhase = 0f,
                                    preserveInputPoints = true,
                                    isDarkBoard = isDarkBoard,
                                    handDrawnFactor = state.handDrawnFactor
                                )
                            }
                        }
                    }

                    if (localLassoPoints.size >= 2) {
                        lassoPath.reset()
                        val p0 = localLassoPoints.first()
                        lassoPath.moveTo(p0.x * camera.scale + camera.panX, p0.y * camera.scale + camera.panY)
                        for (i in 1 until localLassoPoints.size) {
                            val pt = localLassoPoints[i]
                            lassoPath.lineTo(pt.x * camera.scale + camera.panX, pt.y * camera.scale + camera.panY)
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
                        val screenRadius = if (state.currentTool == ToolType.SOFT_ERASER) {
                            (state.penWidth * 1.15f).coerceIn(10f, 90f) * camera.scale
                        } else {
                            (state.penWidth * 2.2f).coerceIn(14f, 44f)
                        }
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

                    smudgeCursorWorld?.let { cursor ->
                        val screenRadius = (state.penWidth * 1.15f).coerceIn(10f, 84f) * camera.scale
                        val center = Offset(cursor.x * camera.scale + camera.panX, cursor.y * camera.scale + camera.panY)
                        drawCircle(
                            color = com.example.ui.theme.GuideCyan.copy(alpha = 0.16f),
                            radius = screenRadius,
                            center = center
                        )
                        drawCircle(
                            color = com.example.ui.theme.GuideCyan,
                            radius = screenRadius,
                            center = center,
                            style = Stroke(width = 2f)
                        )
                    }
                }

                RotationHudOverlay(
                    hudState = activeRotationHudState,
                    density = density
                )

                // Basic floating TextItems (locked labels + editable text cards with 4-corner handles & rotation)
                visibleTextItems.forEach { tItem ->
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
                        val isActivelyDictatingHere = state.isVoiceDictating && state.dictatingTextItemId == tItem.id
                        val isTransparentBg = tItem.backgroundColorHex == "TRANSPARENT"
                        val cardBg = if (isTransparentBg) {
                            Color.Transparent
                        } else {
                            tItem.backgroundColorHex?.let { parseColor(it, Color.White) }
                                ?: if (isDarkBoard) Color(0xFF1E293B) else Color.White
                        }
                        val cardBorderColor = when {
                            isActivelyDictatingHere -> Color(0xFFDC2626)
                            isSelected -> Color(0xFF2563EB)
                            isTransparentBg -> Color.Transparent
                            isDarkBoard -> Color(0xFF334155)
                            else -> Color(0xFFCBD5E1)
                        }
                        val cardBorderWidth = when {
                            isActivelyDictatingHere || isSelected -> 2.dp
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
                                                        if (!viewModel.uiState.value.selectedTextIds.contains(tItem.id)) {
                                                            viewModel.selectTextItem(tItem.id)
                                                        }
                                                        viewModel.beginCurrentSelectionTransformSnapshot()
                                                    }
                                                    if (dragging) {
                                                        change.consume()
                                                        val cameraScale = cameraState.value.scale.coerceAtLeast(0.1f)
                                                        viewModel.moveSelectedObjects(delta.x / cameraScale, delta.y / cameraScale)
                                                    }
                                                }
                                            } while (event.changes.any { it.id == down.id && it.pressed })
                                            if (dragging) {
                                                viewModel.commitCurrentSelectionTransform()
                                            } else if (!viewModel.uiState.value.selectedTextIds.contains(tItem.id)) {
                                                viewModel.selectTextItem(tItem.id)
                                            }
                                        }
                                    }
                                }
                                .testTag("editable_text_item_${tItem.id}")
                        ) {
                            val minHeightDp = with(density) { tItem.height.coerceAtLeast(0f).toDp() }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = cardBg,
                                shadowElevation = if (isTransparentBg) 0.dp else if (isSelected || isActivelyDictatingHere) 4.dp else 1.5.dp,
                                modifier = Modifier
                                    .width(widthDp)
                                    .then(if (tItem.height > 0f) Modifier.heightIn(min = minHeightDp) else Modifier)
                                    .onSizeChanged { measured ->
                                        textMeasuredHeights[tItem.id] = measured.height.toFloat().coerceAtLeast(52f)
                                    }
                                    .border(cardBorderWidth, cardBorderColor, RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        if (isActivelyDictatingHere && tItem.text.isEmpty()) {
                                            Text(
                                                text = "Habla ahora…",
                                                color = tColor.copy(alpha = 0.45f),
                                                fontSize = fontSizeSp,
                                                lineHeight = (fontSizeSp.value * 1.24f).sp,
                                                fontWeight = FontWeight.Normal
                                            )
                                        }
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
                                                .fillMaxWidth()
                                                .testTag("editable_text_input_${tItem.id}")
                                        )
                                    }
                                    if (isActivelyDictatingHere) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Color(0xFFDC2626).copy(
                                                        alpha = 0.45f + state.dictationRmsLevel * 0.55f
                                                    )
                                                )
                                                .testTag("dictating_card_pulse_dot")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Edit label button for the currently selected non-line shape
                selectedShapeForLabel?.let { shape ->
                    if (!isPanOnlyMode) {
                        val editHalfW = with(density) { 24.dp.roundToPx() }
                        val editTop = with(density) { 40.dp.roundToPx() }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .offset {
                                    val camera = cameraState.value
                                    val shapeScreenCenterX = ((shape.x + shape.width / 2f) * camera.scale + camera.panX).roundToInt()
                                    val shapeScreenTop = (shape.y * camera.scale + camera.panY).roundToInt()
                                    IntOffset(shapeScreenCenterX - editHalfW, shapeScreenTop - editTop)
                                }
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    editingLabelShape = shape
                                    editingLabelText = shape.label
                                }
                                .testTag("edit_shape_label_${shape.id}")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shadowElevation = 2.dp,
                                modifier = Modifier.size(32.dp)
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
                }

                val isMultiSelectActive = (state.selectedStrokeIds.size + state.selectedShapeIds.size + state.selectedTextIds.size) > 1
                val activeSelectedText = state.selectedTextId?.let { selId ->
                    state.board.textItems.find { it.id == selId && !it.isLocked && !it.isHiddenByCollapse }
                }
                if (activeSelectedText != null && !isMultiSelectActive && !isPanOnlyMode) {
                    val effectiveTextHeight = maxOf(
                        activeSelectedText.height,
                        textMeasuredHeights[activeSelectedText.id] ?: (activeSelectedText.fontSize * 2.4f),
                        52f
                    )
                    key("text_selection_overlay_${activeSelectedText.id}") {
                        val onBeginTextTransform = remember(viewModel) { { viewModel.beginTextItemTransformSnapshot() } }
                        val onEndTextTransform = remember(viewModel) { { viewModel.commitTextItemTransform() } }
                        val onResizeTextHandle = remember(viewModel, activeSelectedText.id, effectiveTextHeight) {
                            { handle: BoxResizeHandle, dxScreen: Float, dyScreen: Float ->
                                viewModel.resizeTextItemHandle(
                                    id = activeSelectedText.id,
                                    handle = handle,
                                    deltaScreenX = dxScreen,
                                    deltaScreenY = dyScreen,
                                    fallbackHeightWorld = effectiveTextHeight
                                )
                            }
                        }
                        val onRotateText = remember(viewModel, activeSelectedText.id) {
                            { degrees: Float -> viewModel.rotateTextItem(activeSelectedText.id, degrees) }
                        }
                        TextItemSelectionOverlay(
                            textItem = activeSelectedText,
                            effectiveHeightWorld = effectiveTextHeight,
                            cameraState = cameraState,
                            onBeginTransform = onBeginTextTransform,
                            onEndTransform = onEndTextTransform,
                            onResizeHandle = onResizeTextHandle,
                            onRotate = onRotateText
                        )
                    }
                }

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
            }

            // Precision Guides & Interactive Ruler Overlays
            InteractivePerspectiveGridView(
                guide = state.guidesState.perspective,
                cameraState = cameraState,
                onUpdate = { viewModel.updatePerspectiveGuide(it) }
            )

            InteractiveEllipseGuideView(
                guide = state.guidesState.ellipse,
                cameraState = cameraState,
                onUpdate = { viewModel.updateEllipseGuide(it) },
                onClose = { viewModel.selectGuide(GuideType.NONE) }
            )

            InteractiveFrenchCurveView(
                guide = state.guidesState.frenchCurve,
                cameraState = cameraState,
                onUpdate = { viewModel.updateFrenchCurveGuide(it) },
                onClose = { viewModel.selectGuide(GuideType.NONE) }
            )

            InteractiveRulerView(
                rulerState = state.rulerState,
                cameraState = cameraState,
                onMove = { dx, dy -> viewModel.moveRuler(dx, dy) },
                onRotate = { da -> viewModel.rotateRuler(da) },
                onToggleAngleLock = { viewModel.toggleRulerAngleLock() }
            )
        }

        // Single Top Bar
        CameraAwareWhiteboardTopBar(
            viewModel = viewModel,
            state = state,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            onOpenBackgroundSettings = { showBackgroundSettings = true },
            onClearBoard = { viewModel.clearBoard() },
            onExportBoard = { showExportModal = true },
            onRenameBoard = {
                newTitleInput = state.board.title
                showRenameDialog = true
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
        )

        // Single Bottom Bar (or Selection Inspector when items are selected)
        if (!state.isZenMode) {
            val totalSelectedCount = state.selectedStrokeIds.size + state.selectedShapeIds.size + state.selectedTextIds.size
            val hasSelection = totalSelectedCount > 0 ||
                state.selectedShapeId != null ||
                state.selectedStrokeId != null ||
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
                selectedText = selectedText,
                totalSelectedCount = totalSelectedCount.coerceAtLeast(1),
                currentTool = state.currentTool,
                activeBrushSlotIndex = state.activeBrushSlotIndex,
                isBrushPopoverOpen = state.isBrushPopoverOpen,
                penColorHex = state.penColorHex,
                isRainbowPen = state.isRainbowPen,
                autoShapeRecognition = state.autoShapeRecognition,
                floodFillTolerance = state.floodFillTolerance,
                isVoiceDictating = state.isVoiceDictating,
                onToggleVoiceDictation = onToggleVoiceDictation
            )

            if (state.isVoiceDictating || state.dictationStatusMessage != null) {
                LiveDictationStatusPill(
                    isListening = state.isVoiceDictating,
                    rmsLevel = state.dictationRmsLevel,
                    statusMessage = state.dictationStatusMessage,
                    onStopOrDismiss = {
                        if (state.isVoiceDictating) {
                            dictationController.stop()
                        } else {
                            viewModel.clearDictationStatusMessage()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .displayCutoutPadding()
                        .padding(bottom = 86.dp, start = 16.dp, end = 16.dp)
                        .zIndex(28f)
                )
            }
        }

        // Eyedropper floating sample loupe
        eyedropperPreviewState?.let { (screenPos, sampledHex) ->
            val sampledColor = parseColor(sampledHex, Color.Black)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (screenPos.x - with(density) { 28.dp.toPx() }).roundToInt().coerceAtLeast(8),
                            (screenPos.y - with(density) { 88.dp.toPx() }).roundToInt().coerceAtLeast(8)
                        )
                    }
                    .size(56.dp)
                    .border(3.dp, sampledColor, CircleShape)
                    .testTag("eyedropper_loupe")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(sampledColor)
                ) {}
            }
        }

        // Sketchbook Floating Layers Drawer
        if (state.isLayersPanelOpen && !state.isZenMode) {
            SketchbookLayersPanel(
                layers = state.board.layers,
                activeLayerId = state.board.activeLayerId,
                strokes = state.board.strokes,
                shapes = state.board.shapes,
                onSelectLayer = { viewModel.selectLayer(it) },
                onAddLayer = { viewModel.addLayer() },
                onToggleVisibility = { viewModel.toggleLayerVisibility(it) },
                onToggleLock = { viewModel.toggleLayerLock(it) },
                onChangeOpacity = { id, opacity -> viewModel.setLayerOpacity(id, opacity) },
                onOpacityChangeFinished = { viewModel.commitWorkspaceSync() },
                onMoveUp = { viewModel.moveLayerUp(it) },
                onMoveDown = { viewModel.moveLayerDown(it) },
                onDuplicateLayer = { viewModel.duplicateLayer(it) },
                onMergeDown = { viewModel.mergeLayerDown(it) },
                onDeleteLayer = { viewModel.deleteLayer(it) },
                onDismiss = { viewModel.toggleLayersPanel() },
                onChangeBlendMode = { id, mode -> viewModel.changeLayerBlendMode(id, mode) },
                onToggleAlphaLock = { id -> viewModel.toggleLayerAlphaLock(id) },
                onToggleReference = { id -> viewModel.toggleLayerReference(id) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(end = 10.dp)
            )
        }

        // Sketchbook Floating Color Studio
        if (state.isColorStudioOpen && !state.isZenMode) {
            SketchbookColorStudio(
                currentColorHex = state.penColorHex,
                previousColorHex = state.previousPenColorHex,
                opacity = state.penOpacity,
                isRainbow = state.isRainbowPen,
                onSelectColor = { hex, rainbow -> viewModel.setPenColor(hex, isRainbow = rainbow) },
                onChangeOpacity = { opacity -> viewModel.setPenOpacity(opacity) },
                onActivateEyedropper = {
                    viewModel.closeColorStudio()
                    viewModel.setTool(ToolType.EYEDROPPER)
                },
                onDismiss = { viewModel.closeColorStudio() }
            )
        }

        // Brush Studio Parametric Sheet
        val studioSlotIdx = state.brushStudioSlotIndex
        if (studioSlotIdx != null && studioSlotIdx in viewModel.brushSlots.indices) {
            BrushStudioSheet(
                slot = viewModel.brushSlots[studioSlotIdx],
                onUpdateSettings = { settings -> viewModel.updateBrushSlotStudioSettings(studioSlotIdx, settings) },
                onResetSettings = { viewModel.resetBrushSlotStudioSettings(studioSlotIdx) },
                onDismiss = { viewModel.dismissBrushStudio() }
            )
        }

        // Sole Zen Mode Exit Pill shown when Zen Focus Mode is active
        if (state.isZenMode) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(12.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { viewModel.toggleZenMode() }
                    .zIndex(29f)
                    .testTag("zen_mode_exit_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = StudioIcons.FullscreenExit,
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
                mutableRasterTileStore = viewModel.mutableRasterTileStore,
                activeLayerId = state.board.activeLayerId,
                memoryBudget = viewModel.memoryBudget,
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
    }
}

@Composable
private fun RotationHudOverlay(
    hudState: State<Pair<Offset, String>?>,
    density: androidx.compose.ui.unit.Density
) {
    val hud by hudState
    val (hudPos, angleText) = hud ?: return

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
    onOpenBackgroundSettings: () -> Unit,
    onClearBoard: () -> Unit,
    onExportBoard: () -> Unit,
    onRenameBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val camera by viewModel.cameraState.collectAsState()
    if (!state.isZenMode) {
        WhiteboardTopBar(
            boardTitle = state.board.title,
            scale = camera.scale,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            isSnapToGrid = state.isSnapToGrid,
            symmetryMode = state.symmetryMode,
            layerCount = state.board.layers.size.coerceAtLeast(1),
            isLayersPanelOpen = state.isLayersPanelOpen,
            guidesState = state.guidesState,
            isRulerVisible = state.isRulerActive,
            predictionLevel = state.predictionLevel,
            onSelectGuide = { viewModel.selectGuide(it) },
            onSelectPerspectiveMode = { viewModel.selectPerspectiveMode(it) },
            onChangePredictionLevel = { viewModel.setPredictionLevel(it) },
            onCycleSymmetryMode = { viewModel.cycleSymmetryMode() },
            onToggleLayersPanel = { viewModel.toggleLayersPanel() },
            onUndo = { viewModel.undo() },
            onRedo = { viewModel.redo() },
            onZoomIn = { viewModel.zoomIn() },
            onZoomOut = { viewModel.zoomOut() },
            onResetZoom = { viewModel.resetZoom() },
            onFitToContent = { viewModel.fitToContent(screenWidthPx, screenHeightPx) },
            onSetZoomPreset = { viewModel.setZoomPreset(it) },
            onToggleSnapToGrid = { viewModel.toggleSnapToGrid() },
            onOpenBackgroundSettings = onOpenBackgroundSettings,
            onClearBoard = onClearBoard,
            onExportBoard = onExportBoard,
            onRenameBoard = onRenameBoard,
            onBackToHome = { viewModel.closeBoardToHome() },
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
    selectedText: TextItem?,
    totalSelectedCount: Int,
    currentTool: ToolType,
    activeBrushSlotIndex: Int,
    isBrushPopoverOpen: Boolean,
    penColorHex: String,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    floodFillTolerance: Int,
    isVoiceDictating: Boolean,
    onToggleVoiceDictation: () -> Unit
) {
    val onSelectTool = remember(viewModel) {
        { tool: ToolType -> viewModel.setTool(tool) }
    }
    val onSelectBrushSlot = remember(viewModel) {
        { index: Int -> viewModel.selectBrushSlot(index) }
    }
    val onDismissBrushPopover = remember(viewModel) {
        { viewModel.dismissBrushPopover() }
    }
    val onUpdateBrushSlot = remember(viewModel) {
        { index: Int, brushType: StrokeBrushType?, width: Float? ->
            viewModel.updateBrushSlot(
                slotIndex = index,
                brushType = brushType,
                strokeWidth = width
            )
        }
    }
    val onUpdateSlotOpacity = remember(viewModel) {
        { index: Int, opacity: Float ->
            viewModel.updateBrushSlot(slotIndex = index, opacity = opacity)
        }
    }
    val onOpenColorStudio = remember(viewModel) {
        { viewModel.openColorStudio() }
    }
    val onToggleAutoShape = remember(viewModel) {
        { viewModel.toggleAutoShapeRecognition() }
    }
    val onChangeFloodFillTolerance = remember(viewModel) {
        { tol: Int -> viewModel.setFloodFillTolerance(tol) }
    }
    val onAddText = remember(viewModel) {
        { viewModel.insertTextAtCenter() }
    }
    val onEnterZenMode = remember(viewModel) {
        { viewModel.toggleZenMode() }
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
                onScaleShape = { viewModel.scaleSelectedShape(it) },
                onRotateShape = { viewModel.rotateSelectedShape(it) },
                onResetShapeRotation = { viewModel.resetSelectedShapeRotation() },
                onBringShapeToFront = { viewModel.bringSelectedShapeToFront() },
                onSendShapeToBack = { viewModel.sendSelectedShapeToBack() },
                onDuplicateShape = { viewModel.duplicateSelectedShape() },
                onDeleteShape = { viewModel.deleteSelectedShape() },
                onUpdateStrokeColor = { viewModel.updateSelectedStrokeColor(it) },
                onUpdateStrokeWidth = { viewModel.updateSelectedStrokeWidth(it) },
                onScaleStroke = { viewModel.scaleSelectedStroke(it) },
                onRotateStroke = { viewModel.rotateSelectedStroke(it) },
                onConvertStrokeToShape = { viewModel.convertSelectedStrokeToShape() },
                onDuplicateStroke = { viewModel.duplicateSelectedStroke() },
                onDeleteStroke = { viewModel.deleteSelectedStroke() },
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
                isVoiceDictating = isVoiceDictating,
                onToggleVoiceDictation = onToggleVoiceDictation,
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
                isBrushPopoverOpen = isBrushPopoverOpen,
                currentPenColor = penColorHex,
                isRainbowPen = isRainbowPen,
                autoShapeRecognition = autoShapeRecognition,
                floodFillTolerance = floodFillTolerance,
                layoutMode = layoutMode,
                onSelectTool = onSelectTool,
                onSelectBrushSlot = onSelectBrushSlot,
                onDismissBrushPopover = onDismissBrushPopover,
                onUpdateBrushSlot = onUpdateBrushSlot,
                onUpdateSlotOpacity = onUpdateSlotOpacity,
                onOpenColorStudio = onOpenColorStudio,
                onOpenBrushStudio = { viewModel.openBrushStudio(it) },
                onToggleAutoShape = onToggleAutoShape,
                onChangeFloodFillTolerance = onChangeFloodFillTolerance,
                onAddText = onAddText,
                isVoiceDictating = isVoiceDictating,
                onToggleVoiceDictation = onToggleVoiceDictation,
                onEnterZenMode = onEnterZenMode,
                modifier = dockModifier
            )
        }
    }
}

@Composable
private fun LiveDictationStatusPill(
    isListening: Boolean,
    rmsLevel: Float,
    statusMessage: String?,
    onStopOrDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isListening) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val borderColor = if (isListening) {
        Color(0xFFDC2626).copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    }
    val contentColor = if (isListening) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.25.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onStopOrDismiss)
            .testTag("live_dictation_pill")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size((8f + rmsLevel * 6f).dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDC2626))
                )
            }
            Text(
                text = if (isListening) "Dictando en vivo… Toca para detener" else (statusMessage ?: ""),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isListening) {
                Icon(
                    imageVector = StudioIcons.Stop,
                    contentDescription = "Detener dictado",
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private data class CachedShapeBounds(
    val shape: ShapeItem,
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

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
