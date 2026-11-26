package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.model.BrushStudioSettings
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.viewmodel.WhiteboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioPipelineV31Test {

    @Test
    fun `all 9 brushes define distinct default studio settings`() {
        val allTypes = StrokeBrushType.entries
        assertEquals(9, allTypes.size)

        val tech = BrushStudioSettings.defaultFor(StrokeBrushType.TECHNICAL_INK)
        val chisel = BrushStudioSettings.defaultFor(StrokeBrushType.CHISEL_MARKER)
        val water = BrushStudioSettings.defaultFor(StrokeBrushType.WATERCOLOR)
        val airbrush = BrushStudioSettings.defaultFor(StrokeBrushType.AIRBRUSH)

        assertEquals(1.0f, tech.hardness, 0.001f)
        assertEquals(0.0f, tech.taperStart, 0.001f)
        assertEquals(38f, chisel.chiselAngleDeg, 0.001f)
        assertTrue(water.grainStrength > chisel.grainStrength)
        assertTrue(airbrush.hardness < tech.hardness)
    }

    @Test
    fun `viewmodel updates brush slot studio settings, clamps double puck, and resets cleanly`() {
        val vm = WhiteboardViewModel()

        vm.updateBrushSlot(
            slotIndex = 0,
            brushType = StrokeBrushType.CHISEL_MARKER,
            colorHex = "#E53935",
            strokeWidth = 24f
        )
        assertEquals(StrokeBrushType.CHISEL_MARKER, vm.brushSlots[0].brushType)
        assertEquals(ToolType.CHISEL_MARKER, vm.uiState.value.currentTool)

        val custom = BrushStudioSettings(
            flow = 0.42f,
            spacing = 0.33f,
            hardness = 0.55f,
            grainStrength = 0.80f,
            taperStart = 0.40f,
            taperEnd = 0.60f,
            velocitySensitivity = 0.75f,
            chiselAngleDeg = 52f
        )
        vm.openBrushStudio(0)
        assertEquals(0, vm.uiState.value.brushStudioSlotIndex)
        vm.updateBrushSlotStudioSettings(0, custom)
        assertEquals(custom, vm.brushSlots[0].studioSettings)

        vm.updateActiveBrushQuickSettings(newWidth = 220f, newOpacity = 0.01f)
        assertEquals(160f, vm.uiState.value.penWidth, 0.001f)
        assertEquals(0.05f, vm.uiState.value.penOpacity, 0.001f)
        assertEquals(160f, vm.brushSlots[0].strokeWidth, 0.001f)

        vm.resetBrushSlotStudioSettings(0)
        assertEquals(BrushStudioSettings.defaultFor(StrokeBrushType.CHISEL_MARKER), vm.brushSlots[0].studioSettings)
        assertEquals(StrokeBrushType.CHISEL_MARKER.defaultOpacity, vm.uiState.value.penOpacity, 0.001f)
        vm.dismissBrushStudio()
        assertEquals(null, vm.uiState.value.brushStudioSlotIndex)
    }

    @Test
    fun `unified brush pipeline renders visible pixels for technical ink, chisel marker, and watercolor`() {
        val points = listOf(
            StrokePoint(20f, 60f, timestampMs = 100L, pressure = 0.6f),
            StrokePoint(90f, 50f, timestampMs = 135L, pressure = 0.9f),
            StrokePoint(170f, 80f, timestampMs = 180L, pressure = 0.7f)
        )

        listOf(
            StrokeBrushType.TECHNICAL_INK,
            StrokeBrushType.CHISEL_MARKER,
            StrokeBrushType.WATERCOLOR
        ).forEach { brush ->
            val bitmap = Bitmap.createBitmap(220, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val stroke = WhiteboardStroke(
                id = "test_${brush.name}",
                points = points,
                colorHex = "#1E88E5",
                width = 14f,
                brushType = brush,
                opacity = brush.defaultOpacity,
                studioSettings = BrushStudioSettings.defaultFor(brush)
            )
            UnifiedBrushPipeline.renderStroke(
                canvas = canvas,
                stroke = stroke,
                isDarkBoard = false,
                isOpaqueCanvasBackground = false
            )

            var opaqueOrTintedPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 12) {
                        opaqueOrTintedPixels++
                    }
                }
            }
            assertTrue("Expected non-empty render for $brush, got $opaqueOrTintedPixels px", opaqueOrTintedPixels > 40)
            bitmap.recycle()
        }
    }

    @Test
    fun `quick toggle switches between brush and soft eraser`() {
        val vm = WhiteboardViewModel()
        vm.setTool(ToolType.WATERCOLOR)
        vm.quickToggleBrushAndEraser()
        assertEquals(ToolType.SOFT_ERASER, vm.uiState.value.currentTool)
        vm.quickToggleBrushAndEraser()
        assertEquals(ToolType.WATERCOLOR, vm.uiState.value.currentTool)
        assertNotEquals(ToolType.SOFT_ERASER, vm.uiState.value.currentTool)
    }
}
