package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ink.ViewportTransform
import com.example.model.RulerState
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun InteractiveRulerView(
    rulerState: RulerState,
    cameraState: State<ViewportTransform>,
    onMove: (Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!rulerState.isVisible) return

    val widthDp = rulerState.length.coerceAtLeast(1f).dp
    val heightDp = rulerState.width.coerceAtLeast(1f).dp

    Box(
        modifier = modifier
            // Camera is read in the layout phase, not during parent composition.
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
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, _, rotation ->
                    if (rotation != 0f) onRotate(rotation)
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

            // Ruler body background (frosted acrylic glass)
            drawRect(color = Color(0xECFFFFFF))
            drawRect(color = Color(0xFF0078D4), style = Stroke(width = 3f))

            // Tick geometry stays in ruler-local coordinates; the graphics layer scales it.
            val numTicks = (w / 10f).toInt()
            for (i in 0..numTicks) {
                val xPos = i * 10f
                val isMajor = i % 10 == 0
                val isMedium = i % 5 == 0 && !isMajor

                val tickH = when {
                    isMajor -> 20f
                    isMedium -> 14f
                    else -> 8f
                }

                drawLine(color = Color(0xFF1E1E1E), start = Offset(xPos, 0f), end = Offset(xPos, tickH), strokeWidth = if (isMajor) 2.5f else 1f)
                drawLine(color = Color(0xFF1E1E1E), start = Offset(xPos, h), end = Offset(xPos, h - tickH), strokeWidth = if (isMajor) 2.5f else 1f)
            }
        }
    }
}
