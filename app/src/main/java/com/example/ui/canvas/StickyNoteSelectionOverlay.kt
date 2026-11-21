package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ink.StickyNoteGeometry
import com.example.ink.StickyNoteResizeHandle
import com.example.ink.ViewportTransform
import com.example.model.StickyNote
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val CornerResizeHandles = listOf(
    StickyNoteResizeHandle.TOP_LEFT,
    StickyNoteResizeHandle.TOP_RIGHT,
    StickyNoteResizeHandle.BOTTOM_RIGHT,
    StickyNoteResizeHandle.BOTTOM_LEFT
)

/**
 * Screen-space selection frame, 4 basic corner resize handles, and 1 rotation handle for the
 * currently selected sticky note.
 *
 * - Constant 48.dp touch targets at any zoom level.
 * - Reads note geometry and camera exclusively in draw / placement phases so drag, resize,
 *   rotate, pan, and zoom produce zero recompositions.
 */
@Composable
fun StickyNoteSelectionOverlay(
    noteFlow: StateFlow<StickyNote>,
    cameraState: State<ViewportTransform>,
    onBeginTransform: () -> Unit,
    onEndTransform: () -> Unit,
    onResizeHandle: (StickyNoteResizeHandle, Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val noteState = noteFlow.collectAsState()
    val overlayMeta by remember(noteState) {
        derivedStateOf {
            val n = noteState.value
            Triple(n.id, n.isLocked, n.isHiddenByCollapse)
        }
    }
    val (noteId, isLocked, isHiddenByCollapse) = overlayMeta
    if (isLocked || isHiddenByCollapse) return

    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val stemScreenPx = with(density) { 40.dp.toPx() }
    val halfHandlePx = with(density) { 24.dp.toPx() }
    val cornerRadiusWorld = 10f
    val strokeWidthPx = with(density) { 1.75.dp.toPx() }

    val currentOnBeginTransform by rememberUpdatedState(onBeginTransform)
    val currentOnEndTransform by rememberUpdatedState(onEndTransform)
    val currentOnResizeHandle by rememberUpdatedState(onResizeHandle)
    val currentOnRotate by rememberUpdatedState(onRotate)

    var isRotating by remember(noteId) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(20f)
    ) {
        // Non-intercepting vector overlay for crisp rotated selection border & stem at any zoom
        Canvas(modifier = Modifier.fillMaxSize()) {
            val note = noteState.value
            val cam = cameraState.value
            val safeScale = cam.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)

            val screenLeft = note.x * safeScale + cam.panX
            val screenTop = note.y * safeScale + cam.panY
            val screenW = note.width * safeScale
            val screenH = note.height * safeScale
            val center = Offset(screenLeft + screenW * 0.5f, screenTop + screenH * 0.5f)
            val cornerRadiusPx = cornerRadiusWorld * safeScale

            withTransform({
                rotate(degrees = note.rotation, pivot = center)
            }) {
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.85f),
                    topLeft = Offset(screenLeft, screenTop),
                    size = Size(screenW, screenH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = strokeWidthPx)
                )
                // Top-edge stem connecting note box to rotation handle
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x, screenTop),
                    end = Offset(center.x, screenTop - stemScreenPx),
                    strokeWidth = strokeWidthPx
                )
            }
        }

        // 4 screen-space corner resize handles (basic 4-vertex layout)
        CornerResizeHandles.forEach { handle ->
            val placedTopLeft = remember(noteId, handle) { floatArrayOf(0f, 0f) }
            val visualW: Dp = 13.dp
            val visualH: Dp = 13.dp

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset {
                        val note = noteState.value
                        val cam = cameraState.value
                        val safeScale = cam.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
                        val relX = when (handle) {
                            StickyNoteResizeHandle.TOP_LEFT,
                            StickyNoteResizeHandle.LEFT,
                            StickyNoteResizeHandle.BOTTOM_LEFT -> -note.width * 0.5f
                            StickyNoteResizeHandle.TOP_RIGHT,
                            StickyNoteResizeHandle.RIGHT,
                            StickyNoteResizeHandle.BOTTOM_RIGHT -> note.width * 0.5f
                            StickyNoteResizeHandle.TOP,
                            StickyNoteResizeHandle.BOTTOM -> 0f
                        }
                        val relY = when (handle) {
                            StickyNoteResizeHandle.TOP_LEFT,
                            StickyNoteResizeHandle.TOP,
                            StickyNoteResizeHandle.TOP_RIGHT -> -note.height * 0.5f
                            StickyNoteResizeHandle.BOTTOM_LEFT,
                            StickyNoteResizeHandle.BOTTOM,
                            StickyNoteResizeHandle.BOTTOM_RIGHT -> note.height * 0.5f
                            StickyNoteResizeHandle.LEFT,
                            StickyNoteResizeHandle.RIGHT -> 0f
                        }
                        val cxWorld = note.x + note.width * 0.5f
                        val cyWorld = note.y + note.height * 0.5f
                        val handleWorldX: Float
                        val handleWorldY: Float
                        if (note.rotation != 0f) {
                            val rad = note.rotation * (PI.toFloat() / 180f)
                            val cosA = cos(rad)
                            val sinA = sin(rad)
                            handleWorldX = cxWorld + relX * cosA - relY * sinA
                            handleWorldY = cyWorld + relX * sinA + relY * cosA
                        } else {
                            handleWorldX = cxWorld + relX
                            handleWorldY = cyWorld + relY
                        }
                        val screenX = handleWorldX * safeScale + cam.panX
                        val screenY = handleWorldY * safeScale + cam.panY
                        val leftPx = (screenX - halfHandlePx).roundToInt()
                        val topPx = (screenY - halfHandlePx).roundToInt()
                        placedTopLeft[0] = leftPx.toFloat()
                        placedTopLeft[1] = topPx.toFloat()
                        IntOffset(leftPx, topPx)
                    }
                    .size(48.dp)
                    .pointerInput(noteId, handle) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var began = false
                            var lastScreenX = placedTopLeft[0] + down.position.x
                            var lastScreenY = placedTopLeft[1] + down.position.y

                            do {
                                val event = awaitPointerEvent()
                                var activeCount = 0
                                var activePointer: PointerInputChange? = null
                                for (c in event.changes) {
                                    if (c.pressed) {
                                        activeCount++
                                        if (activeCount == 1) activePointer = c
                                    }
                                }
                                if (activeCount >= 2 || event.changes.any { it.isConsumed }) {
                                    break
                                }
                                val change = activePointer ?: break
                                val curScreenX = placedTopLeft[0] + change.position.x
                                val curScreenY = placedTopLeft[1] + change.position.y
                                val dxScreen = curScreenX - lastScreenX
                                val dyScreen = curScreenY - lastScreenY
                                lastScreenX = curScreenX
                                lastScreenY = curScreenY

                                if (!began && hypot(dxScreen, dyScreen) > 0.5f) {
                                    began = true
                                    currentOnBeginTransform()
                                }
                                if (began && (dxScreen != 0f || dyScreen != 0f)) {
                                    currentOnResizeHandle(handle, dxScreen, dyScreen)
                                }
                                change.consume()
                            } while (event.changes.any { it.pressed })

                            if (began) {
                                currentOnEndTransform()
                            }
                        }
                    }
                    .testTag("sticky_note_handle_${handle.name}_$noteId")
            ) {
                Box(
                    modifier = Modifier
                        .size(visualW, visualH)
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .border(1.75.dp, primaryColor, CircleShape)
                )
            }
        }

        // Rotation handle centered 40.dp above the note's rotated top edge
        val rotPlacedTopLeft = remember(noteId) { floatArrayOf(0f, 0f) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset {
                    val note = noteState.value
                    val cam = cameraState.value
                    val safeScale = cam.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
                    val cxWorld = note.x + note.width * 0.5f
                    val cyWorld = note.y + note.height * 0.5f
                    val stemWorld = stemScreenPx / safeScale
                    val relY = -note.height * 0.5f - stemWorld
                    val handleWorldX: Float
                    val handleWorldY: Float
                    if (note.rotation != 0f) {
                        val rad = note.rotation * (PI.toFloat() / 180f)
                        handleWorldX = cxWorld - relY * sin(rad)
                        handleWorldY = cyWorld + relY * cos(rad)
                    } else {
                        handleWorldX = cxWorld
                        handleWorldY = cyWorld + relY
                    }
                    val screenX = handleWorldX * safeScale + cam.panX
                    val screenY = handleWorldY * safeScale + cam.panY
                    val leftPx = (screenX - halfHandlePx).roundToInt()
                    val topPx = (screenY - halfHandlePx).roundToInt()
                    rotPlacedTopLeft[0] = leftPx.toFloat()
                    rotPlacedTopLeft[1] = topPx.toFloat()
                    IntOffset(leftPx, topPx)
                }
                .size(48.dp)
                .pointerInput(noteId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var began = false

                        do {
                            val event = awaitPointerEvent()
                            var activeCount = 0
                            var activePointer: PointerInputChange? = null
                            for (c in event.changes) {
                                if (c.pressed) {
                                    activeCount++
                                    if (activeCount == 1) activePointer = c
                                }
                            }
                            if (activeCount >= 2 || event.changes.any { it.isConsumed }) {
                                break
                            }
                            val change = activePointer ?: break
                            val curScreenX = rotPlacedTopLeft[0] + change.position.x
                            val curScreenY = rotPlacedTopLeft[1] + change.position.y

                            if (!began) {
                                began = true
                                isRotating = true
                                currentOnBeginTransform()
                            }

                            val note = noteState.value
                            val cam = cameraState.value
                            val safeScale = cam.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
                            val centerScreenX = (note.x + note.width * 0.5f) * safeScale + cam.panX
                            val centerScreenY = (note.y + note.height * 0.5f) * safeScale + cam.panY
                            val degrees = StickyNoteGeometry.calculateRotationDegrees(
                                centerX = centerScreenX,
                                centerY = centerScreenY,
                                pointerX = curScreenX,
                                pointerY = curScreenY
                            )
                            currentOnRotate(degrees)
                            change.consume()
                        } while (event.changes.any { it.pressed })

                        isRotating = false
                        if (began) {
                            currentOnEndTransform()
                        }
                    }
                }
                .testTag("sticky_note_rotation_handle_$noteId")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(surfaceColor)
                    .border(1.75.dp, primaryColor, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Rotar nota",
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Live rotation angle HUD pill displayed only while rotating
        if (isRotating) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .offset {
                        val note = noteState.value
                        val cam = cameraState.value
                        val safeScale = cam.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
                        val cxWorld = note.x + note.width * 0.5f
                        val cyWorld = note.y + note.height * 0.5f
                        val hudOffsetWorld = (stemScreenPx + with(density) { 42.dp.toPx() }) / safeScale
                        val relY = -note.height * 0.5f - hudOffsetWorld
                        val rad = note.rotation * (PI.toFloat() / 180f)
                        val worldX = cxWorld - relY * sin(rad)
                        val worldY = cyWorld + relY * cos(rad)
                        val screenX = (worldX * safeScale + cam.panX - with(density) { 24.dp.toPx() }).roundToInt()
                        val screenY = (worldY * safeScale + cam.panY).roundToInt().coerceAtLeast(0)
                        IntOffset(screenX, screenY)
                    }
                    .testTag("sticky_note_rotation_hud_$noteId")
            ) {
                Text(
                    text = "${noteState.value.rotation.roundToInt()}°",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}
