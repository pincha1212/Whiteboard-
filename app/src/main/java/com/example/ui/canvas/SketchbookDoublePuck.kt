package com.example.ui.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sketchbook-signature Double Puck controller:
 * - Horizontal drag adjusts live brush strokeWidth (`1f..160f`) and shows a world-scaled preview ring.
 * - Vertical drag adjusts live opacity (`5%..100%`).
 * - Long-drag or quick tap toggles expanded/collapsed state.
 */
@Composable
fun SketchbookDoublePuck(
    strokeWidth: Float,
    opacity: Float,
    colorHex: String,
    scale: Float,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onUpdateSizeAndOpacity: (newWidth: Float, newOpacity: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isAdjusting by remember { mutableStateOf(false) }
    var offsetXPx by remember { mutableStateOf(0f) }
    var offsetYPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    val parsedColor = remember(colorHex) {
        try {
            Color(android.graphics.Color.parseColor(colorHex))
        } catch (_: Exception) {
            Color(0xFFE4E4E7)
        }
    }

    Box(modifier = modifier) {
        // World-scaled live brush size preview ring in center while dragging the Double Puck
        AnimatedVisibility(
            visible = isAdjusting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radiusPx = ((strokeWidth * scale.coerceAtLeast(0.1f)) * 0.5f)
                    .coerceIn(3f, size.minDimension * 0.42f)
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = parsedColor.copy(alpha = (opacity * 0.22f).coerceIn(0.06f, 0.45f)),
                    radius = radiusPx,
                    center = center
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = radiusPx,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.45f),
                    radius = radiusPx + 1.5f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        // Translucent Double Puck widget
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 104.dp)
                .offset { IntOffset(offsetXPx.roundToInt(), offsetYPx.roundToInt()) }
        ) {
            if (isAdjusting || isExpanded) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${strokeWidth.roundToInt()}px",
                            style = StudioMonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${(opacity * 100f).roundToInt()}%",
                            style = StudioMonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val puckSizeDp = if (isExpanded) 58.dp else 48.dp
            val maxMovePx = with(density) { 120.dp.toPx() }
            var currentW by remember(strokeWidth) { mutableStateOf(strokeWidth) }
            var currentOp by remember(opacity) { mutableStateOf(opacity) }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .size(puckSizeDp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .border(
                        width = if (isAdjusting) 2.dp else 1.2.dp,
                        color = if (isAdjusting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onToggleExpanded() })
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isAdjusting = true
                                currentW = strokeWidth
                                currentOp = opacity
                            },
                            onDragEnd = {
                                isAdjusting = false
                                offsetXPx = 0f
                                offsetYPx = 0f
                            },
                            onDragCancel = {
                                isAdjusting = false
                                offsetXPx = 0f
                                offsetYPx = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            if (abs(dragAmount.x) >= abs(dragAmount.y)) {
                                // Horizontal gesture tunes strokeWidth cleanly 1f..160f
                                currentW = (currentW + dragAmount.x * 0.42f).coerceIn(1f, 160f)
                            } else {
                                // Vertical up increases opacity, vertical down decreases opacity
                                currentOp = (currentOp - dragAmount.y * 0.0045f).coerceIn(0.05f, 1.0f)
                            }
                            offsetXPx = (offsetXPx + dragAmount.x * 0.12f).coerceIn(-maxMovePx, maxMovePx)
                            offsetYPx = (offsetYPx + dragAmount.y * 0.12f).coerceIn(-maxMovePx, maxMovePx)
                            onUpdateSizeAndOpacity(currentW, currentOp)
                        }
                    }
                    .semantics {
                        contentDescription = "Double Puck: desliza horizontal para tamaño (${strokeWidth.roundToInt()} px) y vertical para opacidad (${(opacity * 100f).roundToInt()}%)"
                    }
                    .testTag("sketchbook_double_puck")
            ) {
                // Inner pigment orb showing current color, opacity, and normalized size
                val orbFraction = ((currentW.coerceIn(1f, 160f) / 160f) * 0.56f + 0.22f).coerceIn(0.22f, 0.78f)
                Box(
                    modifier = Modifier
                        .size(puckSizeDp * orbFraction)
                        .clip(CircleShape)
                        .background(parsedColor.copy(alpha = currentOp.coerceIn(0.15f, 1f)))
                        .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                )
            }
        }
    }
}
