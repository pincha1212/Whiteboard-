package com.example.ui.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Upright physical desk-tool illustrations for the compact two-tier Whiteboard Tray.
 * Tools stand vertically inside the upper inking tray with their bases anchored
 * at the bottom edge and only their functional tips/barrels peeking up cleanly.
 */

@Composable
fun PhysicalFountainPenIcon(
    inkColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Fine ink tip point
        val nibTip = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(cx + w * 0.12f, h * 0.34f)
            lineTo(cx - w * 0.12f, h * 0.34f)
            close()
        }
        drawPath(nibTip, color = inkColor)

        // Tapered silver/metallic cone collar
        val cone = Path().apply {
            moveTo(cx - w * 0.12f, h * 0.34f)
            lineTo(cx + w * 0.12f, h * 0.34f)
            lineTo(cx + w * 0.25f, h * 0.56f)
            lineTo(cx - w * 0.25f, h * 0.56f)
            close()
        }
        drawPath(cone, color = Color(0xFFF1F5F9))
        drawPath(cone, color = Color(0xFF475569), style = Stroke(width = w * 0.045f, join = StrokeJoin.Round))

        // Ink ring band
        drawRect(
            color = inkColor,
            topLeft = Offset(cx - w * 0.26f, h * 0.55f),
            size = Size(w * 0.52f, h * 0.08f)
        )

        // Colored upright barrel anchored cleanly to the bottom edge
        drawRoundRect(
            color = inkColor,
            topLeft = Offset(cx - w * 0.26f, h * 0.62f),
            size = Size(w * 0.52f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.08f)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.34f),
            start = Offset(cx - w * 0.12f, h * 0.66f),
            end = Offset(cx - w * 0.12f, h * 0.98f),
            strokeWidth = w * 0.055f,
            cap = StrokeCap.Round
        )
        drawRoundRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.26f, h * 0.62f),
            size = Size(w * 0.52f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(width = w * 0.042f)
        )
    }
}

@Composable
fun PhysicalPencilIcon(
    graphiteColor: Color = Color(0xFF1E293B),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Wood cone
        val cone = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(cx + w * 0.23f, h * 0.46f)
            lineTo(cx - w * 0.23f, h * 0.46f)
            close()
        }
        drawPath(cone, color = Color(0xFFFDE68A))

        // Colored graphite/lead tip
        val tip = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(cx + w * 0.095f, h * 0.26f)
            lineTo(cx - w * 0.095f, h * 0.26f)
            close()
        }
        drawPath(tip, color = graphiteColor)

        // Color collar band near top of wooden barrel
        drawRect(
            color = graphiteColor,
            topLeft = Offset(cx - w * 0.23f, h * 0.46f),
            size = Size(w * 0.46f, h * 0.10f)
        )

        // Hexagonal yellow wooden body anchored to bottom
        drawRect(
            color = Color(0xFFF59E0B),
            topLeft = Offset(cx - w * 0.23f, h * 0.56f),
            size = Size(w * 0.46f, h * 0.50f)
        )
        drawRect(
            color = Color(0xFFFDE047),
            topLeft = Offset(cx - w * 0.09f, h * 0.56f),
            size = Size(w * 0.18f, h * 0.50f)
        )

        val outline = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(cx + w * 0.23f, h * 0.46f)
            lineTo(cx + w * 0.23f, h * 1.04f)
            lineTo(cx - w * 0.23f, h * 1.04f)
            lineTo(cx - w * 0.23f, h * 0.46f)
            close()
        }
        drawPath(outline, color = Color(0xFF334155), style = Stroke(width = w * 0.045f, join = StrokeJoin.Round))
    }
}

@Composable
fun PhysicalMarkerIcon(
    markerColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Fine fiber tip
        val tip = Path().apply {
            moveTo(cx - w * 0.085f, h * 0.34f)
            lineTo(cx, h * 0.12f)
            lineTo(cx + w * 0.085f, h * 0.34f)
            close()
        }
        drawPath(tip, color = markerColor)

        // Colored cone shoulder
        val shoulder = Path().apply {
            moveTo(cx - w * 0.12f, h * 0.34f)
            lineTo(cx + w * 0.12f, h * 0.34f)
            lineTo(cx + w * 0.24f, h * 0.56f)
            lineTo(cx - w * 0.24f, h * 0.56f)
            close()
        }
        drawPath(shoulder, color = markerColor)
        drawPath(shoulder, color = Color(0xFF334155), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))

        // White fiber marker barrel anchored at bottom with colored band
        drawRoundRect(
            color = Color(0xFFF8FAFC),
            topLeft = Offset(cx - w * 0.26f, h * 0.56f),
            size = Size(w * 0.52f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRect(
            color = markerColor,
            topLeft = Offset(cx - w * 0.26f, h * 0.65f),
            size = Size(w * 0.52f, h * 0.11f)
        )
        drawRoundRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.26f, h * 0.56f),
            size = Size(w * 0.52f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = w * 0.045f)
        )
    }
}

@Composable
fun PhysicalHighlighterIcon(
    highlighterColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Wide slanted chisel felt tip
        val chiselTip = Path().apply {
            moveTo(cx - w * 0.16f, h * 0.36f)
            lineTo(cx - w * 0.12f, h * 0.14f)
            lineTo(cx + w * 0.16f, h * 0.23f)
            lineTo(cx + w * 0.16f, h * 0.36f)
            close()
        }
        drawPath(chiselTip, color = highlighterColor)
        drawPath(chiselTip, color = Color(0xFF334155), style = Stroke(width = w * 0.042f, join = StrokeJoin.Round))

        // Light tapered collar
        val collar = Path().apply {
            moveTo(cx - w * 0.22f, h * 0.36f)
            lineTo(cx + w * 0.22f, h * 0.36f)
            lineTo(cx + w * 0.30f, h * 0.54f)
            lineTo(cx - w * 0.30f, h * 0.54f)
            close()
        }
        drawPath(collar, color = Color(0xFFE2E8F0))
        drawPath(collar, color = Color(0xFF334155), style = Stroke(width = w * 0.042f, join = StrokeJoin.Round))

        // Chunky upright highlighter body
        drawRoundRect(
            color = highlighterColor,
            topLeft = Offset(cx - w * 0.32f, h * 0.54f),
            size = Size(w * 0.64f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.07f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.45f),
            topLeft = Offset(cx - w * 0.18f, h * 0.64f),
            size = Size(w * 0.14f, h * 0.32f),
            cornerRadius = CornerRadius(w * 0.04f)
        )
        drawRoundRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.32f, h * 0.54f),
            size = Size(w * 0.64f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.07f),
            style = Stroke(width = w * 0.045f)
        )
    }
}

@Composable
fun PhysicalBrushIcon(
    paintColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Sculpted brush bristle head with visible bristle tip lines
        val bristles = Path().apply {
            moveTo(cx, h * 0.11f)
            quadraticTo(cx + w * 0.28f, h * 0.30f, cx + w * 0.20f, h * 0.48f)
            lineTo(cx - w * 0.20f, h * 0.48f)
            quadraticTo(cx - w * 0.28f, h * 0.30f, cx, h * 0.11f)
            close()
        }
        drawPath(bristles, color = paintColor)
        drawPath(bristles, color = Color(0xFF334155), style = Stroke(width = w * 0.042f, join = StrokeJoin.Round))

        // Individual bristle hair marks inside head
        drawLine(
            color = Color.White.copy(alpha = 0.45f),
            start = Offset(cx - w * 0.06f, h * 0.24f),
            end = Offset(cx - w * 0.09f, h * 0.43f),
            strokeWidth = w * 0.035f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF0F172A).copy(alpha = 0.25f),
            start = Offset(cx + w * 0.06f, h * 0.24f),
            end = Offset(cx + w * 0.09f, h * 0.43f),
            strokeWidth = w * 0.035f,
            cap = StrokeCap.Round
        )

        // Silver ferrule
        drawRect(
            color = Color(0xFFCBD5E1),
            topLeft = Offset(cx - w * 0.21f, h * 0.48f),
            size = Size(w * 0.42f, h * 0.15f)
        )
        drawRect(
            color = Color(0xFF475569),
            topLeft = Offset(cx - w * 0.21f, h * 0.48f),
            size = Size(w * 0.42f, h * 0.15f),
            style = Stroke(width = w * 0.038f)
        )

        // Wooden handle anchored to bottom edge
        drawRect(
            color = Color(0xFFB45309),
            topLeft = Offset(cx - w * 0.18f, h * 0.63f),
            size = Size(w * 0.36f, h * 0.42f)
        )
        drawRect(
            color = Color(0xFF451A03),
            topLeft = Offset(cx - w * 0.18f, h * 0.63f),
            size = Size(w * 0.36f, h * 0.42f),
            style = Stroke(width = w * 0.04f)
        )
    }
}

@Composable
fun PhysicalLaserIcon(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Glowing red laser dot above tip
        drawCircle(
            color = Color(0xFFEF4444).copy(alpha = 0.28f),
            radius = w * 0.15f,
            center = Offset(cx, h * 0.14f)
        )
        drawCircle(
            color = Color(0xFFEF4444),
            radius = w * 0.075f,
            center = Offset(cx, h * 0.14f)
        )

        // Silver pointer cap & body anchored to bottom
        val nose = Path().apply {
            moveTo(cx - w * 0.10f, h * 0.26f)
            lineTo(cx + w * 0.10f, h * 0.26f)
            lineTo(cx + w * 0.22f, h * 0.46f)
            lineTo(cx - w * 0.22f, h * 0.46f)
            close()
        }
        drawPath(nose, color = Color(0xFFCBD5E1))
        drawPath(nose, color = Color(0xFF475569), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))

        drawRoundRect(
            color = Color(0xFFF1F5F9),
            topLeft = Offset(cx - w * 0.22f, h * 0.46f),
            size = Size(w * 0.44f, h * 0.58f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            color = Color(0xFF475569),
            topLeft = Offset(cx - w * 0.22f, h * 0.46f),
            size = Size(w * 0.44f, h * 0.58f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = w * 0.04f)
        )
        // Red button dot on barrel
        drawCircle(
            color = Color(0xFFDC2626),
            radius = w * 0.07f,
            center = Offset(cx, h * 0.64f)
        )
    }
}

@Composable
fun PhysicalEraserIcon(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Upright coral-pink rubber top
        drawRoundRect(
            color = Color(0xFFFDA4AF),
            topLeft = Offset(cx - w * 0.28f, h * 0.18f),
            size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.10f)
        )
        drawRoundRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.28f, h * 0.18f),
            size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.10f),
            style = Stroke(width = w * 0.042f)
        )

        // Metallic/gray sleeve holder anchored to bottom
        drawRect(
            color = Color(0xFFE2E8F0),
            topLeft = Offset(cx - w * 0.30f, h * 0.52f),
            size = Size(w * 0.60f, h * 0.54f)
        )
        drawRect(
            color = Color(0xFF94A3B8),
            topLeft = Offset(cx - w * 0.30f, h * 0.52f),
            size = Size(w * 0.60f, h * 0.10f)
        )
        drawRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.30f, h * 0.52f),
            size = Size(w * 0.60f, h * 0.54f),
            style = Stroke(width = w * 0.042f)
        )
    }
}

@Composable
fun PhysicalPixelEraserIcon(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Precision beveled tip
        val rubberTip = Path().apply {
            moveTo(cx - w * 0.14f, h * 0.42f)
            lineTo(cx - w * 0.10f, h * 0.14f)
            lineTo(cx + w * 0.14f, h * 0.22f)
            lineTo(cx + w * 0.14f, h * 0.42f)
            close()
        }
        drawPath(rubberTip, color = Color(0xFFFB7185))
        drawPath(rubberTip, color = Color(0xFF334155), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))

        // Precision holder barrel
        drawRect(
            color = Color(0xFF64748B),
            topLeft = Offset(cx - w * 0.22f, h * 0.42f),
            size = Size(w * 0.44f, h * 0.64f)
        )
        drawRect(
            color = Color(0xFF93C5FD),
            topLeft = Offset(cx - w * 0.22f, h * 0.50f),
            size = Size(w * 0.44f, h * 0.14f)
        )
        drawRect(
            color = Color(0xFF1E293B),
            topLeft = Offset(cx - w * 0.22f, h * 0.42f),
            size = Size(w * 0.44f, h * 0.64f),
            style = Stroke(width = w * 0.042f)
        )
    }
}

@Composable
fun PhysicalLassoIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val u = size.minDimension
        val loopStroke = Stroke(
            width = u * 0.085f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 0.12f, u * 0.09f), 0f)
        )
        drawCircle(
            color = tint,
            radius = u * 0.30f,
            center = Offset(size.width * 0.52f, size.height * 0.46f),
            style = loopStroke
        )
        drawCircle(
            color = tint,
            radius = u * 0.07f,
            center = Offset(size.width * 0.34f, size.height * 0.68f)
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.34f, size.height * 0.68f),
            end = Offset(size.width * 0.24f, size.height * 0.84f),
            strokeWidth = u * 0.08f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun PhysicalRulerIcon(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        val rw = w * 0.62f
        val left = cx - rw / 2f
        val top = h * 0.14f
        val bodyBrush = Brush.verticalGradient(
            if (isActive) listOf(Color(0xFFFDE047), Color(0xFFFACC15))
            else listOf(Color(0xFFF1F5F9), Color(0xFFCBD5E1))
        )
        val borderColor = if (isActive) Color(0xFF854D0E) else Color(0xFF475569)

        drawRoundRect(
            brush = bodyBrush,
            topLeft = Offset(left, top),
            size = Size(rw, h * 0.92f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(rw, h * 0.92f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = w * 0.042f)
        )

        // Millimeter horizontal ticks along left edge of vertical ruler
        val tickCount = 6
        val usableH = h * 0.72f
        val step = usableH / tickCount
        for (i in 1..tickCount) {
            val ty = top + step * i
            val tickLen = if (i % 2 == 1) rw * 0.44f else rw * 0.26f
            drawLine(
                color = borderColor,
                start = Offset(left, ty),
                end = Offset(left + tickLen, ty),
                strokeWidth = w * 0.045f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun PhysicalShapesIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val u = size.minDimension
        val stroke = Stroke(width = u * 0.075f, join = StrokeJoin.Round)
        // Triangle + Circle + Square (compact geometric badge)
        drawCircle(
            color = Color(0xFF3B82F6).copy(alpha = 0.20f),
            radius = u * 0.22f,
            center = Offset(u * 0.66f, u * 0.36f)
        )
        drawCircle(
            color = tint,
            radius = u * 0.20f,
            center = Offset(u * 0.66f, u * 0.36f),
            style = stroke
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(u * 0.16f, u * 0.42f),
            size = Size(u * 0.40f, u * 0.40f),
            cornerRadius = CornerRadius(u * 0.06f),
            style = stroke
        )
    }
}

@Composable
fun PhysicalStickyNoteIcon(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val u = size.minDimension
        val left = u * 0.16f
        val top = u * 0.16f
        val s = u * 0.68f
        val fold = u * 0.18f

        val body = Path().apply {
            moveTo(left, top)
            lineTo(left + s, top)
            lineTo(left + s, top + s - fold)
            lineTo(left + s - fold, top + s)
            lineTo(left, top + s)
            close()
        }
        drawPath(body, color = Color(0xFFFDE047))
        drawPath(body, color = Color(0xFFCA8A04), style = Stroke(width = u * 0.045f, join = StrokeJoin.Round))

        val corner = Path().apply {
            moveTo(left + s - fold, top + s - fold)
            lineTo(left + s, top + s - fold)
            lineTo(left + s - fold, top + s)
            close()
        }
        drawPath(corner, color = Color(0xFFFACC15))
    }
}

@Composable
fun PhysicalPenSquiggleIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val u = size.minDimension
        // Sleek tilted nib
        val nib = Path().apply {
            moveTo(u * 0.72f, u * 0.16f)
            lineTo(u * 0.84f, u * 0.28f)
            lineTo(u * 0.44f, u * 0.68f)
            lineTo(u * 0.28f, u * 0.72f)
            lineTo(u * 0.32f, u * 0.56f)
            close()
        }
        drawPath(nib, color = tint, style = Stroke(width = u * 0.07f, join = StrokeJoin.Round))

        // Flowing ink squiggle from pen tip
        val wave = Path().apply {
            moveTo(u * 0.20f, u * 0.78f)
            quadraticTo(u * 0.42f, u * 0.88f, u * 0.56f, u * 0.76f)
            quadraticTo(u * 0.68f, u * 0.66f, u * 0.82f, u * 0.78f)
        }
        drawPath(wave, color = tint, style = Stroke(width = u * 0.075f, cap = StrokeCap.Round))
    }
}

@Composable
fun PhysicalCalligraphyPenIcon(
    inkColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // 45-degree chiseled broad calligraphy nib
        val nib = Path().apply {
            moveTo(cx - w * 0.14f, h * 0.19f)
            lineTo(cx + w * 0.14f, h * 0.11f)
            lineTo(cx + w * 0.20f, h * 0.42f)
            lineTo(cx - w * 0.20f, h * 0.42f)
            close()
        }
        drawPath(nib, color = Color(0xFFFDE68A))
        drawPath(nib, color = Color(0xFF92400E), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))

        // Ink breather hole + slit
        drawCircle(
            color = inkColor,
            radius = w * 0.055f,
            center = Offset(cx, h * 0.30f)
        )
        drawLine(
            color = Color(0xFF92400E),
            start = Offset(cx, h * 0.15f),
            end = Offset(cx, h * 0.28f),
            strokeWidth = w * 0.032f
        )

        // Gold ring collar
        drawRect(
            color = inkColor,
            topLeft = Offset(cx - w * 0.24f, h * 0.42f),
            size = Size(w * 0.48f, h * 0.11f)
        )

        // Sleek tapered dark barrel anchored at bottom
        drawRoundRect(
            color = Color(0xFF312E81),
            topLeft = Offset(cx - w * 0.24f, h * 0.53f),
            size = Size(w * 0.48f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.07f)
        )
        drawRoundRect(
            color = Color(0xFF1E1B4B),
            topLeft = Offset(cx - w * 0.24f, h * 0.53f),
            size = Size(w * 0.48f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.07f),
            style = Stroke(width = w * 0.04f)
        )
    }
}

@Composable
fun PhysicalAirbrushIcon(
    sprayColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Fine mist spray particles above nozzle
        val mistDots = listOf(
            Offset(cx, h * 0.08f) to w * 0.045f,
            Offset(cx - w * 0.14f, h * 0.13f) to w * 0.038f,
            Offset(cx + w * 0.14f, h * 0.12f) to w * 0.038f,
            Offset(cx - w * 0.07f, h * 0.19f) to w * 0.035f,
            Offset(cx + w * 0.08f, h * 0.19f) to w * 0.035f
        )
        mistDots.forEach { (pt, r) ->
            drawCircle(color = sprayColor.copy(alpha = 0.72f), radius = r, center = pt)
        }

        // Nozzle cone
        val nozzle = Path().apply {
            moveTo(cx - w * 0.08f, h * 0.26f)
            lineTo(cx + w * 0.08f, h * 0.26f)
            lineTo(cx + w * 0.22f, h * 0.48f)
            lineTo(cx - w * 0.22f, h * 0.48f)
            close()
        }
        drawPath(nozzle, color = Color(0xFFE2E8F0))
        drawPath(nozzle, color = Color(0xFF475569), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))

        // Metallic body with color indicator cup
        drawRoundRect(
            color = Color(0xFF94A3B8),
            topLeft = Offset(cx - w * 0.24f, h * 0.48f),
            size = Size(w * 0.48f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.07f)
        )
        drawCircle(
            color = sprayColor,
            radius = w * 0.13f,
            center = Offset(cx, h * 0.72f)
        )
        drawCircle(
            color = Color(0xFF334155),
            radius = w * 0.13f,
            center = Offset(cx, h * 0.72f),
            style = Stroke(width = w * 0.035f)
        )
        drawRoundRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.24f, h * 0.48f),
            size = Size(w * 0.48f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.07f),
            style = Stroke(width = w * 0.042f)
        )
    }
}

@Composable
fun PhysicalEyedropperIcon(
    sampledColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // Glass pipette tip pointing down-to-up inverted upright tool
        val tip = Path().apply {
            moveTo(cx, h * 0.10f)
            lineTo(cx + w * 0.11f, h * 0.30f)
            lineTo(cx - w * 0.11f, h * 0.30f)
            close()
        }
        drawPath(tip, color = sampledColor)
        drawPath(tip, color = Color(0xFF334155), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))

        // Clear glass tube with sampled liquid inside
        drawRoundRect(
            color = Color(0xFFF1F5F9),
            topLeft = Offset(cx - w * 0.16f, h * 0.30f),
            size = Size(w * 0.32f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.05f)
        )
        drawRect(
            color = sampledColor.copy(alpha = 0.88f),
            topLeft = Offset(cx - w * 0.14f, h * 0.32f),
            size = Size(w * 0.28f, h * 0.22f)
        )
        drawRoundRect(
            color = Color(0xFF475569),
            topLeft = Offset(cx - w * 0.16f, h * 0.30f),
            size = Size(w * 0.32f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.05f),
            style = Stroke(width = w * 0.04f)
        )

        // Rubber squeeze bulb collar & body anchored at bottom
        drawRoundRect(
            color = Color(0xFF334155),
            topLeft = Offset(cx - w * 0.26f, h * 0.68f),
            size = Size(w * 0.52f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.12f)
        )
        drawRect(
            color = Color(0xFFCBD5E1),
            topLeft = Offset(cx - w * 0.27f, h * 0.65f),
            size = Size(w * 0.54f, h * 0.07f)
        )
    }
}


