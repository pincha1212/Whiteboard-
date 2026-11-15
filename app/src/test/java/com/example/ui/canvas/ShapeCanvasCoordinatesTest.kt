package com.example.ui.canvas

import com.example.model.ShapeItem
import com.example.model.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeCanvasCoordinatesTest {
    @Test
    fun inverseRotationReturnsPointerToOriginalLocalCoordinate() {
        val shape = ShapeItem(
            shapeType = ShapeType.RECTANGLE,
            x = 100f,
            y = 80f,
            width = 240f,
            height = 120f,
            rotation = 37f
        )
        val localX = 315f
        val localY = 125f
        val centerX = shape.x + shape.width / 2f
        val centerY = shape.y + shape.height / 2f
        val radians = shape.rotation * (PI.toFloat() / 180f)
        val dx = localX - centerX
        val dy = localY - centerY
        val renderedX = centerX + dx * cos(radians) - dy * sin(radians)
        val renderedY = centerY + dx * sin(radians) + dy * cos(radians)

        val restored = ShapeCanvasCoordinates.toLocalUnrotated(shape, renderedX, renderedY)

        assertEquals(localX, restored.first, 0.001f)
        assertEquals(localY, restored.second, 0.001f)
    }

    @Test
    fun lineRotationHandleOffsetRemainsThirtyTwoScreenPixelsAtDifferentZoomLevels() {
        assertEquals(64f, ShapeCanvasCoordinates.lineRotationHandleOffsetWorld(0.5f), 0.001f)
        assertEquals(32f, ShapeCanvasCoordinates.lineRotationHandleOffsetWorld(1f), 0.001f)
        assertEquals(16f, ShapeCanvasCoordinates.lineRotationHandleOffsetWorld(2f), 0.001f)
    }
}
