package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.model.RulerState
import kotlin.math.roundToInt

@Composable
fun InteractiveRulerView(
    rulerState: RulerState,
    scale: Float,
    panX: Float,
    panY: Float,
    onMove: (Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!rulerState.isVisible) return

    val screenX = (rulerState.centerX * scale + panX).roundToInt()
    val screenY = (rulerState.centerY * scale + panY).roundToInt()
    val widthDp = (rulerState.length * scale).coerceAtLeast(200f).dp
    val heightDp = (rulerState.width * scale).coerceAtLeast(60f).dp

    Box(
        modifier = modifier
            .offset { IntOffset(screenX - (widthDp.toPx() / 2f).toInt(), screenY - (heightDp.toPx() / 2f).toInt()) }
            .size(widthDp, heightDp)
            .rotate(rulerState.angleDegrees)
            .shadow(12.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, _, rotation ->
                    if (rotation != 0f) onRotate(rotation)
                    if (pan != Offset.Zero) onMove(pan.x / scale, pan.y / scale)
                }
            }
            .testTag("interactive_ruler")
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // Ruler body background (frosted acrylic glass)
            drawRect(color = Color(0xECFFFFFF))
            drawRect(color = Color(0xFF0078D4), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

            // Millimeter ticks along top and bottom edges
            val numTicks = (w / (10f * scale)).toInt()
            for (i in 0..numTicks) {
                val xPos = i * 10f * scale
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
