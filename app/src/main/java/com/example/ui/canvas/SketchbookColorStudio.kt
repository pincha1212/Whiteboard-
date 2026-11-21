package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.toolbar.DockRainbowColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val CopicQuickPalette = listOf(
    "#0F172A", "#475569", "#FFFFFF", "#DC2626", "#EA580C",
    "#FACC15", "#16A34A", "#0891B2", "#2563EB", "#7C3AED"
)

/**
 * Sketchbook-inspired floating Color Studio featuring an interactive HSB Hue Wheel +
 * Saturation/Value square, split Current/Previous color puck, opacity slider, Copic quick swatches,
 * Rainbow toggle, and canvas Eyedropper launcher.
 */
@Composable
fun SketchbookColorStudio(
    currentColorHex: String,
    previousColorHex: String,
    opacity: Float,
    isRainbow: Boolean,
    onSelectColor: (hex: String, isRainbow: Boolean) -> Unit,
    onChangeOpacity: (Float) -> Unit,
    onActivateEyedropper: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initialHsv = remember(currentColorHex) {
        val hsv = FloatArray(3)
        val parsed = try {
            android.graphics.Color.parseColor(currentColorHex)
        } catch (_: Exception) {
            android.graphics.Color.BLACK
        }
        android.graphics.Color.colorToHSV(parsed, hsv)
        hsv
    }

    var hue by remember(currentColorHex) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(currentColorHex) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(currentColorHex) { mutableFloatStateOf(initialHsv[2]) }

    val activeColor = remember(hue, sat, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    }
    val prevColor = remember(previousColorHex) { parseColor(previousColorHex, Color.Black) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(42f)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .width(304.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .clickable(enabled = false, onClick = {})
                .testTag("sketchbook_color_studio")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                // Header: Split Current / Previous puck + Eyedropper + Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Split color puck (New | Previous)
                        Row(
                            modifier = Modifier
                                .size(width = 48.dp, height = 28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(activeColor)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(prevColor)
                                    .clickable {
                                        onSelectColor(previousColorHex, false)
                                    }
                            )
                        }
                        Text(
                            text = if (isRainbow) "Arcoíris" else hsvToHex(hue, sat, value),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    onDismiss()
                                    onActivateEyedropper()
                                }
                                .testTag("color_studio_eyedropper_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Colorize,
                                contentDescription = "Cuentagotas",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClick = onDismiss)
                                .testTag("color_studio_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar selector de color",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Interactive Sketchbook Hue Wheel + Inner Saturation/Value Square
                HsbWheelAndSquare(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onHueChanged = { newHue ->
                        hue = newHue
                        onSelectColor(hsvToHex(newHue, sat, value), false)
                    },
                    onSatValChanged = { newSat, newVal ->
                        sat = newSat
                        value = newVal
                        onSelectColor(hsvToHex(hue, newSat, newVal), false)
                    },
                    modifier = Modifier.size(216.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Opacity bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Opacidad",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${(opacity * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = opacity.coerceIn(0.05f, 1f),
                    onValueChange = onChangeOpacity,
                    valueRange = 0.05f..1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .testTag("color_studio_opacity_slider")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Copic quick palette (2 rows of 5 + Rainbow chip)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CopicQuickPalette.take(5).forEach { hex ->
                            ColorQuickDot(
                                hex = hex,
                                isSelected = !isRainbow && currentColorHex.equals(hex, ignoreCase = true),
                                onClick = { onSelectColor(hex, false) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CopicQuickPalette.drop(5).take(4).forEach { hex ->
                            ColorQuickDot(
                                hex = hex,
                                isSelected = !isRainbow && currentColorHex.equals(hex, ignoreCase = true),
                                onClick = { onSelectColor(hex, false) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Rainbow swatch
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .testTag("color_studio_rainbow_chip")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Brush.sweepGradient(DockRainbowColors))
                                    .border(
                                        width = if (isRainbow) 2.5.dp else 1.dp,
                                        color = if (isRainbow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable { onSelectColor(hsvToHex(hue, sat, value), true) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorQuickDot(
    hex: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val swatchColor = remember(hex) { parseColor(hex, Color.Black) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(30.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(swatchColor)
                .border(
                    width = if (isSelected) 2.5.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun HsbWheelAndSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onHueChanged: (Float) -> Unit,
    onSatValChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueSweepColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outerR = minOf(cx, cy)
                    val ringThickness = outerR * 0.22f
                    val innerR = outerR - ringThickness
                    val squareHalf = innerR * 0.64f

                    val dx0 = down.position.x - cx
                    val dy0 = down.position.y - cy
                    val dist0 = hypot(dx0, dy0)
                    val draggingHueRing = dist0 >= innerR * 0.92f

                    fun updateAt(pos: Offset) {
                        if (draggingHueRing) {
                            val angleRad = atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())
                            var deg = Math.toDegrees(angleRad).toFloat()
                            if (deg < 0f) deg += 360f
                            onHueChanged(deg % 360f)
                        } else {
                            val left = cx - squareHalf
                            val top = cy - squareHalf
                            val side = squareHalf * 2f
                            val nextS = ((pos.x - left) / side).coerceIn(0f, 1f)
                            val nextV = 1f - ((pos.y - top) / side).coerceIn(0f, 1f)
                            onSatValChanged(nextS, nextV)
                        }
                    }

                    updateAt(down.position)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        updateAt(change.position)
                        change.consume()
                    } while (event.changes.any { it.pressed })
                }
            }
            .testTag("hsb_color_wheel")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = size.minDimension / 2f
            val ringThickness = outerR * 0.22f
            val ringRadius = outerR - ringThickness * 0.5f
            val innerR = outerR - ringThickness
            val squareHalf = innerR * 0.64f
            val sqLeft = cx - squareHalf
            val sqTop = cy - squareHalf
            val sqSize = squareHalf * 2f

            // 1. Hue Ring
            drawCircle(
                brush = Brush.sweepGradient(hueSweepColors, center = Offset(cx, cy)),
                radius = ringRadius,
                center = Offset(cx, cy),
                style = Stroke(width = ringThickness)
            )

            // Hue Thumb
            val hueRad = hue * (PI.toFloat() / 180f)
            val hueThumbX = cx + ringRadius * cos(hueRad)
            val hueThumbY = cy + ringRadius * sin(hueRad)
            drawCircle(
                color = Color.White,
                radius = ringThickness * 0.42f,
                center = Offset(hueThumbX, hueThumbY),
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF0F172A),
                radius = ringThickness * 0.42f + 1.5.dp.toPx(),
                center = Offset(hueThumbX, hueThumbY),
                style = Stroke(width = 1.dp.toPx())
            )

            // 2. Inner Saturation/Value Square
            val pureHueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, pureHueColor),
                    startX = sqLeft,
                    endX = sqLeft + sqSize
                ),
                topLeft = Offset(sqLeft, sqTop),
                size = androidx.compose.ui.geometry.Size(sqSize, sqSize)
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = sqTop,
                    endY = sqTop + sqSize
                ),
                topLeft = Offset(sqLeft, sqTop),
                size = androidx.compose.ui.geometry.Size(sqSize, sqSize)
            )
            drawRect(
                color = Color.Gray.copy(alpha = 0.35f),
                topLeft = Offset(sqLeft, sqTop),
                size = androidx.compose.ui.geometry.Size(sqSize, sqSize),
                style = Stroke(width = 1.dp.toPx())
            )

            // SV Thumb
            val svThumbX = sqLeft + sat * sqSize
            val svThumbY = sqTop + (1f - value) * sqSize
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = Offset(svThumbX, svThumbY),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF0F172A),
                radius = 8.5.dp.toPx(),
                center = Offset(svThumbX, svThumbY),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

private fun hsvToHex(hue: Float, sat: Float, value: Float): String {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
    return String.format("#%06X", 0xFFFFFF and argb)
}
