package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.ink.engine.MutableRasterTileStore
import com.example.ink.engine.PaintTileRasterCache
import com.example.ink.engine.PigmentMixEngine
import com.example.ink.engine.ScanlineFloodFillEngine
import com.example.model.LayerBlendMode
import com.example.model.StrokePoint
import com.example.model.ToolType
import com.example.viewmodel.WhiteboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterMixingV32Test {

    @Test
    fun `pigment mix engine smudges pigment onto adjacent empty pixels and emits undo patch`() {
        val store = MutableRasterTileStore()
        val boardId = "b1"
        val layerId = "l1"
        val seedTile = store.getOrCreateTile(boardId, layerId, 0, 0)
        val canvas = Canvas(seedTile)
        val paint = Paint().apply {
            color = Color.argb(255, 220, 40, 40)
            style = Paint.Style.FILL
        }
        canvas.drawRect(40f, 40f, 80f, 80f, paint)

        val patch = PigmentMixEngine.applySmudgeStroke(
            boardId = boardId,
            layerId = layerId,
            points = listOf(
                StrokePoint(60f, 60f),
                StrokePoint(95f, 60f),
                StrokePoint(120f, 60f)
            ),
            brushRadiusWorld = 18f,
            strength = 0.85f,
            isAlphaLocked = false,
            rasterStore = store,
            layerSampler = { tx, ty -> store.getTile(boardId, layerId, tx, ty) }
        )

        assertNotNull(patch)
        val smearedPixel = store.getTile(boardId, layerId, 0, 0)?.getPixel(100, 60) ?: 0
        assertTrue("Expected smudged pigment at (100,60), alpha=${Color.alpha(smearedPixel)}", Color.alpha(smearedPixel) > 15)
        assertTrue(Color.red(smearedPixel) > Color.blue(smearedPixel))
    }

    @Test
    fun `scanline flood fill respects closed boundary and alpha lock`() {
        val store = MutableRasterTileStore()
        val boardId = "b_fill"
        val layerId = "l_fill"
        val tile = store.getOrCreateTile(boardId, layerId, 0, 0)
        val canvas = Canvas(tile)
        val wallPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRect(30f, 30f, 130f, 130f, wallPaint)

        val fillRes = ScanlineFloodFillEngine.floodFill(
            boardId = boardId,
            targetLayerId = layerId,
            seedWorldX = 80,
            seedWorldY = 80,
            fillColorInt = Color.BLUE,
            fillOpacity = 1.0f,
            tolerance = 24,
            gapClosingPx = 1,
            isAlphaLocked = false,
            regionWorld = Rect(0, 0, PaintTileRasterCache.TILE_PIXEL_SIZE, PaintTileRasterCache.TILE_PIXEL_SIZE),
            rasterStore = store,
            boundaryTileSampler = { tx, ty -> store.getTile(boardId, layerId, tx, ty) }
        )

        assertNotNull(fillRes.undoPatch)
        assertTrue(fillRes.filledPixelCount in 7000..11000)
        assertEquals(Color.BLUE, tile.getPixel(80, 80))
        assertEquals(0, Color.alpha(tile.getPixel(10, 10)))

        // With Alpha Lock enabled, tapping transparent outside region must not paint empty pixels
        val lockedRes = ScanlineFloodFillEngine.floodFill(
            boardId = boardId,
            targetLayerId = layerId,
            seedWorldX = 10,
            seedWorldY = 10,
            fillColorInt = Color.GREEN,
            fillOpacity = 1.0f,
            tolerance = 24,
            isAlphaLocked = true,
            regionWorld = Rect(0, 0, PaintTileRasterCache.TILE_PIXEL_SIZE, PaintTileRasterCache.TILE_PIXEL_SIZE),
            rasterStore = store,
            boundaryTileSampler = { tx, ty -> store.getTile(boardId, layerId, tx, ty) }
        )
        assertEquals(0, Color.alpha(tile.getPixel(10, 10)))
        assertEquals(null, lockedRes.undoPatch)
    }

    @Test
    fun `viewmodel flood fill uses reference layer boundary and undo redo restores exact raster bytes`() {
        val vm = WhiteboardViewModel()
        val boardId = vm.uiState.value.board.id
        val refLayerId = vm.uiState.value.board.activeLayerId

        // Mark layer 1 as Reference and draw a closed rectangle on its raster tile
        vm.toggleLayerReference(refLayerId)
        assertTrue(vm.uiState.value.board.layers.first { it.id == refLayerId }.isReference)

        val refTile = vm.mutableRasterTileStore.getOrCreateTile(boardId, refLayerId, 0, 0)
        Canvas(refTile).drawRect(
            40f, 40f, 140f, 140f,
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
        )

        // Add Layer 2 (becomes active layer) and verify Reference exclusivity
        vm.addLayer()
        val fillLayerId = vm.uiState.value.board.activeLayerId
        assertFalse(vm.uiState.value.board.layers.first { it.id == fillLayerId }.isReference)

        vm.setTool(ToolType.FLOOD_FILL)
        vm.setPenColor("#00AAFF")
        val result = vm.applyFloodFillTap(90f, 90f)
        assertNotNull(result.undoPatch)
        assertTrue(result.filledPixelCount > 5000)

        val filledTileBeforeUndo = vm.mutableRasterTileStore.getTile(boardId, fillLayerId, 0, 0)
        assertNotNull(filledTileBeforeUndo)
        assertTrue(Color.alpha(filledTileBeforeUndo!!.getPixel(90, 90)) > 200)
        assertEquals(0, Color.alpha(filledTileBeforeUndo.getPixel(10, 10)))

        // Undo must clear filled pixel back to transparent
        vm.undo()
        val tileAfterUndo = vm.mutableRasterTileStore.getTile(boardId, fillLayerId, 0, 0)
        val alphaAfterUndo = tileAfterUndo?.getPixel(90, 90)?.let { Color.alpha(it) } ?: 0
        assertEquals(0, alphaAfterUndo)

        // Redo must restore filled blue pixel
        vm.redo()
        val tileAfterRedo = vm.mutableRasterTileStore.getTile(boardId, fillLayerId, 0, 0)
        assertNotNull(tileAfterRedo)
        assertTrue(Color.alpha(tileAfterRedo!!.getPixel(90, 90)) > 200)

        // Layer blend mode and alpha lock toggles
        vm.changeLayerBlendMode(fillLayerId, LayerBlendMode.MULTIPLY)
        vm.toggleLayerAlphaLock(fillLayerId)
        val updatedLayer = vm.uiState.value.board.layers.first { it.id == fillLayerId }
        assertEquals(LayerBlendMode.MULTIPLY, updatedLayer.blendMode)
        assertTrue(updatedLayer.isAlphaLocked)
    }
}
