package com.example.ui.canvas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasGridGeometryTest {

    @Test
    fun dotGridPositionsPreserveOriginalSpacingAndOrder() {
        val positions = buildGridPointPositions(
            startX = 5f,
            startY = 7f,
            step = 40f,
            screenW = 80f,
            screenH = 80f
        )

        assertArrayEquals(
            floatArrayOf(
                5f, 7f,
                5f, 47f,
                45f, 7f,
                45f, 47f
            ),
            positions,
            0.001f
        )
    }

    @Test
    fun gridLinesKeepVerticalThenHorizontalOrderAndViewportEndpoints() {
        val segments = buildGridLineSegments(
            startX = 5f,
            startY = 7f,
            step = 40f,
            screenW = 80f,
            screenH = 80f,
            includeVertical = true,
            includeHorizontal = true
        )

        assertArrayEquals(
            floatArrayOf(
                5f, 0f, 5f, 80f,
                45f, 0f, 45f, 80f,
                0f, 7f, 80f, 7f,
                0f, 47f, 80f, 47f
            ),
            segments,
            0.001f
        )
    }

    @Test
    fun linesModeEmitsOnlyHorizontalSegments() {
        val segments = buildGridLineSegments(
            startX = 5f,
            startY = 7f,
            step = 40f,
            screenW = 80f,
            screenH = 80f,
            includeVertical = false,
            includeHorizontal = true
        )

        assertArrayEquals(
            floatArrayOf(
                0f, 7f, 80f, 7f,
                0f, 47f, 80f, 47f
            ),
            segments,
            0.001f
        )
    }

    @Test
    fun gridGeometryExcludesRightAndBottomEdgesAndHandlesEmptyViewports() {
        val points = buildGridPointPositions(
            startX = 0f,
            startY = 0f,
            step = 40f,
            screenW = 40f,
            screenH = 40f
        )
        val noLines = buildGridLineSegments(
            startX = 0f,
            startY = 0f,
            step = 0f,
            screenW = 100f,
            screenH = 100f,
            includeVertical = true,
            includeHorizontal = true
        )

        assertEquals(2, points.size)
        assertArrayEquals(floatArrayOf(0f, 0f), points, 0.001f)
        assertTrue(noLines.isEmpty())
    }
}
