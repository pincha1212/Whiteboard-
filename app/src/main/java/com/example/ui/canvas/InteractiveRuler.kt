package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ink.StrokeStabilizer
import com.example.ink.ViewportTransform
import com.example.model.RulerState
import com.example.ui.theme.GuideAmber
import com.example.ui.theme.GuideCyan
import com.example.ui.theme.StudioIcons
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun InteractiveRulerView(
    rulerState: RulerState,
    cameraState: State<ViewportTransform>,
    onMove: (Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    onToggleAngleLock: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!rulerState.isVisible) return

    val widthDp = rulerState.length.coerceAtLeast(1f).dp
    val heightDp = rulerState.width.coerceAtLeast(1f).dp
    val isDetentSnapped = StrokeStabilizer.isNearRulerDetent(rulerState.angleDegrees)

    Box(
        modifier = modifier
            .offset {
                val camera = cameraState.value
                val baseWidthPx = widthDp.toPx()
                val baseHeightPx = heightDp.toPx()
                val scaleX = max(camera.scale, 200f / baseWidthPx)
                val scaleY = max(camera.scale, 60f / baseHeightPx)
                IntOffset(
                    (rulerState.centerX * camera.scale + camera.panX - baseWidthPx * scaleX / 2f).roundToInt(),
                    (rulerState.centerY * camera.scale + camera.panY - baseHeightPx * scaleY / 2f).roundToInt()
                )
            }
            .size(widthDp, heightDp)
            .graphicsLayer {
                val camera = cameraState.value
                scaleX = max(camera.scale, 200f / size.width.coerceAtLeast(1f))
                scaleY = max(camera.scale, 60f / size.height.coerceAtLeast(1f))
            }
            .rotate(rulerState.angleDegrees)
            .shadow(12.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(rulerState.isAngleLocked) {
                detectTransformGestures { _, pan, _, rotation ->
                    if (!rulerState.isAngleLocked && rotation != 0f) {
                        onRotate(rotation)
                    }
                    if (pan != Offset.Zero) {
                        onMove(
                            pan.x / cameraState.value.scale,
                            pan.y / cameraState.value.scale
                        )
                    }
                }
            }
            .testTag("interactive_ruler")
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // Neutral frosted dark studio acrylic
            drawRect(color = Color(0xE01E1E21))
            drawRect(
                color = if (isDetentSnapped) GuideAmber else GuideCyan.copy(alpha = 0.78f),
                style = Stroke(width = 3f)
            )

            val numTicks = (w / 10f).toInt()
            for (i in 0..numTicks) {
                val xPos = i * 10f
                val isMajor = i % 10 == 0
                val isMedium = i % 5 == 0 && !isMajor

                val tickH = when {
                    isMajor -> 20f
                    isMedium -> 13f
                    else -> 7f
                }

                val tickColor = if (isMajor) Color(0xFFF2F2F5) else Color(0xFF9A9AA3)
                drawLine(
                    color = tickColor,
                    start = Offset(xPos, 0f),
                    end = Offset(xPos, tickH),
                    strokeWidth = if (isMajor) 2.2f else 1f
                )
                drawLine(
                    color = tickColor,
                    start = Offset(xPos, h),
                    end = Offset(xPos, h - tickH),
                    strokeWidth = if (isMajor) 2.2f else 1f
                )
            }
        }

        // Center angle readout & lock chip
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xCC141416))
                .border(
                    1.dp,
                    if (isDetentSnapped) GuideAmber else Color(0xFF3A3A40),
                    RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = "${rulerState.angleDegrees.roundToInt()}°",
                style = StudioMonoTextStyle,
                color = if (isDetentSnapped) GuideAmber else Color(0xFFF2F2F5)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onToggleAngleLock)
                    .padding(4.dp)
                    .testTag("ruler_lock_angle_btn")
            ) {
                Icon(
                    imageVector = if (rulerState.isAngleLocked) StudioIcons.Lock else StudioIcons.LockOpen,
                    contentDescription = if (rulerState.isAngleLocked) "Desbloquear ángulo" else "Bloquear ángulo",
                    tint = if (rulerState.isAngleLocked) GuideAmber else Color(0xFF9A9AA3),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
