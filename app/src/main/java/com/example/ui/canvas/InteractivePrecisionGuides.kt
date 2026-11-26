package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ink.GuidesGeometry
import com.example.ink.ViewportTransform
import com.example.model.EllipseGuideState
import com.example.model.FrenchCurveGuideState
import com.example.model.PerspectiveGuideState
import com.example.model.PerspectiveMode
import com.example.ui.theme.GuideAmber
import com.example.ui.theme.GuideCyan
import com.example.ui.theme.StudioIcons
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun InteractiveEllipseGuideView(
    guide: EllipseGuideState,
    cameraState: State<ViewportTransform>,
    onUpdate: (EllipseGuideState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!guide.isVisible) return

    val camera = cameraState.value
    val rxPx = guide.radiusX * camera.scale
    val ryPx = (if (guide.isCircleLocked) guide.radiusX else guide.radiusY) * camera.scale
    val cxPx = guide.centerX * camera.scale + camera.panX
    val cyPx = guide.centerY * camera.scale + camera.panY

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(guide) {
                    detectTransformGestures { centroid, pan, zoom, rotation ->
                        val distToCenter = hypot(centroid.x - cxPx, centroid.y - cyPx)
                        if (distToCenter <= maxOf(rxPx, ryPx) + 56f) {
                            val newCx = guide.centerX + pan.x / cameraState.value.scale
                            val newCy = guide.centerY + pan.y / cameraState.value.scale
                            val newRx = (guide.radiusX * zoom).coerceIn(28f, 950f)
                            val newRy = if (guide.isCircleLocked) newRx else (guide.radiusY * zoom).coerceIn(28f, 950f)
                            onUpdate(
                                guide.copy(
                                    centerX = newCx,
                                    centerY = newCy,
                                    radiusX = newRx,
                                    radiusY = newRy,
                                    rotationDegrees = (guide.rotationDegrees + rotation) % 360f
                                )
                            )
                        }
                    }
                }
        ) {
            rotate(degrees = guide.rotationDegrees, pivot = Offset(cxPx, cyPx)) {
                drawOval(
                    color = GuideCyan,
                    topLeft = Offset(cxPx - rxPx, cyPx - ryPx),
                    size = Size(rxPx * 2f, ryPx * 2f),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                // Crosshairs
                drawLine(
                    color = GuideCyan.copy(alpha = 0.45f),
                    start = Offset(cxPx - rxPx, cyPx),
                    end = Offset(cxPx + rxPx, cyPx),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
                drawLine(
                    color = GuideCyan.copy(alpha = 0.45f),
                    start = Offset(cxPx, cyPx - ryPx),
                    end = Offset(cxPx, cyPx + ryPx),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
            }
            drawCircle(color = GuideCyan, radius = 6.dp.toPx(), center = Offset(cxPx, cyPx))
        }

        // Major & Minor axis draggable handles
        val rad = guide.rotationDegrees * (PI.toFloat() / 180f)
        val majorHandleX = cxPx + rxPx * cos(rad)
        val majorHandleY = cyPx + rxPx * sin(rad)
        val minorHandleX = cxPx - ryPx * sin(rad)
        val minorHandleY = cyPx + ryPx * cos(rad)

        GuideHandleDot(
            xPx = majorHandleX,
            yPx = majorHandleY,
            color = GuideCyan,
            testTag = "ellipse_major_handle",
            onDrag = { dx, dy ->
                val wx = (majorHandleX + dx - cameraState.value.panX) / cameraState.value.scale - guide.centerX
                val wy = (majorHandleY + dy - cameraState.value.panY) / cameraState.value.scale - guide.centerY
                val newRx = hypot(wx, wy).coerceIn(28f, 950f)
                val newRot = atan2(wy, wx) * (180f / PI.toFloat())
                onUpdate(
                    guide.copy(
                        radiusX = newRx,
                        radiusY = if (guide.isCircleLocked) newRx else guide.radiusY,
                        rotationDegrees = newRot
                    )
                )
            }
        )

        GuideHandleDot(
            xPx = minorHandleX,
            yPx = minorHandleY,
            color = GuideAmber,
            testTag = "ellipse_minor_handle",
            onDrag = { dx, dy ->
                val wx = (minorHandleX + dx - cameraState.value.panX) / cameraState.value.scale - guide.centerX
                val wy = (minorHandleY + dy - cameraState.value.panY) / cameraState.value.scale - guide.centerY
                val newRy = hypot(wx, wy).coerceIn(24f, 950f)
                onUpdate(
                    guide.copy(
                        radiusY = newRy,
                        radiusX = if (guide.isCircleLocked) newRy else guide.radiusX
                    )
                )
            }
        )

        // Floating HUD control pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .offset { IntOffset((cxPx - 96f).roundToInt(), (cyPx - ryPx - 52f).roundToInt()) }
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE61E1E21))
                .border(1.dp, GuideCyan.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("ellipse_guide_hud")
        ) {
            Text(
                text = "${guide.radiusX.roundToInt()}×${(if (guide.isCircleLocked) guide.radiusX else guide.radiusY).roundToInt()} · ${guide.rotationDegrees.roundToInt()}°",
                style = StudioMonoTextStyle,
                color = Color(0xFFF2F2F5)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable {
                        onUpdate(
                            guide.copy(
                                isCircleLocked = !guide.isCircleLocked,
                                radiusY = if (!guide.isCircleLocked) guide.radiusX else guide.radiusY
                            )
                        )
                    }
                    .padding(6.dp)
                    .testTag("ellipse_circle_lock_btn")
            ) {
                Icon(
                    imageVector = if (guide.isCircleLocked) StudioIcons.Lock else StudioIcons.LockOpen,
                    contentDescription = "Bloquear círculo 1:1",
                    tint = if (guide.isCircleLocked) GuideAmber else Color(0xFF9A9AA3),
                    modifier = Modifier.size(16.dp)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(6.dp)
                    .testTag("ellipse_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar guía de elipse",
                    tint = Color(0xFFF2F2F5),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun InteractiveFrenchCurveView(
    guide: FrenchCurveGuideState,
    cameraState: State<ViewportTransform>,
    onUpdate: (FrenchCurveGuideState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!guide.isVisible) return

    val camera = cameraState.value
    val cxPx = guide.centerX * camera.scale + camera.panX
    val cyPx = guide.centerY * camera.scale + camera.panY
    val worldPts = GuidesGeometry.sampleFrenchCurvePoints(guide)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(guide) {
                    detectTransformGestures { centroid, pan, zoom, rotation ->
                        if (hypot(centroid.x - cxPx, centroid.y - cyPx) < 260f * guide.scale * cameraState.value.scale) {
                            onUpdate(
                                guide.copy(
                                    centerX = guide.centerX + pan.x / cameraState.value.scale,
                                    centerY = guide.centerY + pan.y / cameraState.value.scale,
                                    scale = (guide.scale * zoom).coerceIn(0.45f, 3.0f),
                                    rotationDegrees = (guide.rotationDegrees + rotation) % 360f
                                )
                            )
                        }
                    }
                }
        ) {
            if (worldPts.size >= 2) {
                val path = Path().apply {
                    moveTo(worldPts[0].x * camera.scale + camera.panX, worldPts[0].y * camera.scale + camera.panY)
                    for (i in 1 until worldPts.size) {
                        lineTo(worldPts[i].x * camera.scale + camera.panX, worldPts[i].y * camera.scale + camera.panY)
                    }
                }
                drawPath(path, color = GuideCyan.copy(alpha = 0.25f), style = Stroke(width = 8.dp.toPx()))
                drawPath(path, color = GuideCyan, style = Stroke(width = 2.5.dp.toPx()))
            }
            drawCircle(color = GuideCyan, radius = 5.dp.toPx(), center = Offset(cxPx, cyPx))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .offset { IntOffset((cxPx - 108f).roundToInt(), (cyPx + 140f * guide.scale * camera.scale).roundToInt()) }
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE61E1E21))
                .border(1.dp, GuideCyan.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("french_curve_hud")
        ) {
            Text(
                text = guide.profile.displayName,
                style = StudioMonoTextStyle,
                color = Color(0xFFF2F2F5)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable {
                        val entries = com.example.model.FrenchCurveProfile.entries
                        val next = entries[(guide.profile.ordinal + 1) % entries.size]
                        onUpdate(guide.copy(profile = next))
                    }
                    .padding(6.dp)
                    .testTag("french_curve_cycle_btn")
            ) {
                Icon(
                    imageVector = StudioIcons.Loop,
                    contentDescription = "Cambiar plantilla",
                    tint = GuideCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable { onUpdate(guide.copy(isFlippedX = !guide.isFlippedX)) }
                    .padding(6.dp)
                    .testTag("french_curve_flip_btn")
            ) {
                Icon(
                    imageVector = StudioIcons.Flip,
                    contentDescription = "Voltear horizontalmente",
                    tint = if (guide.isFlippedX) GuideAmber else Color(0xFF9A9AA3),
                    modifier = Modifier.size(16.dp)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(6.dp)
                    .testTag("french_curve_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar curva francesa",
                    tint = Color(0xFFF2F2F5),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun InteractivePerspectiveGridView(
    guide: PerspectiveGuideState,
    cameraState: State<ViewportTransform>,
    onUpdate: (PerspectiveGuideState) -> Unit,
    modifier: Modifier = Modifier
) {
    if (guide.mode == PerspectiveMode.OFF || !guide.showGridOverlay) return
    val camera = cameraState.value

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizonScreenY = guide.horizonY * camera.scale + camera.panY
            if (guide.mode != PerspectiveMode.ISOMETRIC) {
                drawLine(
                    color = GuideCyan.copy(alpha = 0.55f),
                    start = Offset(0f, horizonScreenY),
                    end = Offset(size.width, horizonScreenY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            }

            fun drawRaysFromVp(vpWorldX: Float, vpWorldY: Float, color: Color) {
                val vx = vpWorldX * camera.scale + camera.panX
                val vy = vpWorldY * camera.scale + camera.panY
                val rayCount = guide.gridDensity.coerceIn(6, 24)
                val rayLen = maxOf(size.width, size.height) * 2.2f
                for (i in 0 until rayCount) {
                    val angle = (2.0 * PI * i) / rayCount
                    val ex = vx + rayLen * cos(angle).toFloat()
                    val ey = vy + rayLen * sin(angle).toFloat()
                    drawLine(
                        color = color.copy(alpha = 0.20f),
                        start = Offset(vx, vy),
                        end = Offset(ex, ey),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            when (guide.mode) {
                PerspectiveMode.ONE_POINT -> {
                    drawRaysFromVp(guide.vp1.x, guide.vp1.y, GuideCyan)
                }
                PerspectiveMode.TWO_POINT -> {
                    drawRaysFromVp(guide.vp1.x, guide.vp1.y, GuideCyan)
                    drawRaysFromVp(guide.vp2.x, guide.vp2.y, GuideAmber)
                }
                PerspectiveMode.THREE_POINT -> {
                    drawRaysFromVp(guide.vp1.x, guide.vp1.y, GuideCyan)
                    drawRaysFromVp(guide.vp2.x, guide.vp2.y, GuideAmber)
                    drawRaysFromVp(guide.vp3.x, guide.vp3.y, Color(0xFFA78BFA))
                }
                PerspectiveMode.ISOMETRIC -> {
                    val rad = guide.isometricAngleDeg * (PI.toFloat() / 180f)
                    val step = (90f * camera.scale).coerceIn(36f, 220f)
                    val tanA = sin(rad) / cos(rad).coerceAtLeast(0.1f)
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(
                            color = GuideCyan.copy(alpha = 0.16f),
                            start = Offset(x, 0f),
                            end = Offset(x + size.height / tanA, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = GuideAmber.copy(alpha = 0.16f),
                            start = Offset(x, size.height),
                            end = Offset(x + size.height / tanA, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                        x += step
                    }
                }
                PerspectiveMode.OFF -> Unit
            }
        }

        if (!guide.isLocked && guide.mode != PerspectiveMode.ISOMETRIC) {
            val vp1x = guide.vp1.x * camera.scale + camera.panX
            val vp1y = guide.vp1.y * camera.scale + camera.panY
            GuideHandleDot(
                xPx = vp1x,
                yPx = vp1y,
                color = GuideCyan,
                label = "PF1",
                testTag = "perspective_vp1_handle",
                onDrag = { dx, dy ->
                    val newVp1 = guide.vp1.copy(
                        x = guide.vp1.x + dx / cameraState.value.scale,
                        y = guide.vp1.y + dy / cameraState.value.scale
                    )
                    onUpdate(guide.copy(vp1 = newVp1, horizonY = newVp1.y))
                }
            )
            if (guide.mode == PerspectiveMode.TWO_POINT || guide.mode == PerspectiveMode.THREE_POINT) {
                val vp2x = guide.vp2.x * camera.scale + camera.panX
                val vp2y = guide.vp2.y * camera.scale + camera.panY
                GuideHandleDot(
                    xPx = vp2x,
                    yPx = vp2y,
                    color = GuideAmber,
                    label = "PF2",
                    testTag = "perspective_vp2_handle",
                    onDrag = { dx, dy ->
                        val newVp2 = guide.vp2.copy(
                            x = guide.vp2.x + dx / cameraState.value.scale,
                            y = guide.vp2.y + dy / cameraState.value.scale
                        )
                        onUpdate(guide.copy(vp2 = newVp2, horizonY = newVp2.y))
                    }
                )
            }
            if (guide.mode == PerspectiveMode.THREE_POINT) {
                val vp3x = guide.vp3.x * camera.scale + camera.panX
                val vp3y = guide.vp3.y * camera.scale + camera.panY
                GuideHandleDot(
                    xPx = vp3x,
                    yPx = vp3y,
                    color = Color(0xFFA78BFA),
                    label = "PF3",
                    testTag = "perspective_vp3_handle",
                    onDrag = { dx, dy ->
                        onUpdate(
                            guide.copy(
                                vp3 = guide.vp3.copy(
                                    x = guide.vp3.x + dx / cameraState.value.scale,
                                    y = guide.vp3.y + dy / cameraState.value.scale
                                )
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun GuideHandleDot(
    xPx: Float,
    yPx: Float,
    color: Color,
    label: String? = null,
    testTag: String,
    onDrag: (Float, Float) -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset((xPx - 20f).roundToInt(), (yPx - 20f).roundToInt()) }
            .size(40.dp)
            .pointerInput(testTag) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (label != null) 28.dp else 18.dp)
                .clip(CircleShape)
                .background(Color(0xE0141416))
                .border(2.dp, color, CircleShape)
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = StudioMonoTextStyle,
                    color = color
                )
            }
        }
    }
}
