package com.example.ui.canvas

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.isLineLike
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coordinate conversions shared by shape rendering and pointer hit testing.
 * World coordinates are stored unrotated; the canvas rotates the rendered
 * shape around the center of its bounds.
 */
internal object ShapeCanvasCoordinates {
    fun toLocalUnrotated(shape: ShapeItem, worldX: Float, worldY: Float): Pair<Float, Float> {
        if (shape.rotation == 0f || shape.shapeType == ShapeType.LINE || (shape.shapeType.isLineLike && shape.vertices.size >= 2)) {
            return worldX to worldY
        }

        val centerX = shape.x + shape.width / 2f
        val centerY = shape.y + shape.height / 2f
        val radians = -shape.rotation * (PI.toFloat() / 180f)
        val dx = worldX - centerX
        val dy = worldY - centerY
        return (centerX + dx * cos(radians) - dy * sin(radians)) to
            (centerY + dx * sin(radians) + dy * cos(radians))
    }

    /** Keeps the line's rotation affordance 32 screen pixels above its endpoint at every zoom. */
    fun lineRotationHandleOffsetWorld(scale: Float): Float =
        32f / scale.coerceAtLeast(0.01f)

    /** Screen-pixel stem length (36.dp) for the protruding rotation handle of shapes and strokes. */
    fun rotationStemScreenPx(density: Density): Float =
        with(density) { 36.dp.toPx() }

    /** World-unit vertical offset from top edge of unrotated bounds to center of protruding rotation handle. */
    fun rotationHandleCenterOffsetWorld(density: Density, scale: Float): Float =
        (6f + rotationStemScreenPx(density)) / scale.coerceAtLeast(0.01f)
}

